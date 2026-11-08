package com.unciv.logic.automation.commander

import com.unciv.logic.automation.AiFeature
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unit.BaseUnit
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The LLM tactical commander, switched on by [AiFeature.LlmCommander].
 *
 * Once per turn, before the regular unit automation, it finds the battles the civ is in ([BattleDetector]), and for
 * each important one makes ONE model call with a menu of computed options ([BattleDigestBuilder]). The answer is an
 * ordered list of orders, executed one by one ([OrderExecutor]). When something goes differently from the menu's
 * promise (an order fails, a unit dies, a kill does not happen), execution stops and the model re-plans the units that
 * can still act, seeing only what a player would see. Units with an executed order are skipped by the regular
 * automation this turn; all others, and everything when the model fails, are left to UnitTactics / the default AI.
 *
 * The model's intent is remembered per battle and shown again next turn, so plans carry over.
 * Every call is logged to [CommanderConfig.logDir] for reading and replay.
 */
object TacticalCommander {
    @Volatile var config: CommanderConfig = CommanderConfig.fromEnvironment()
        set(value) { field = value; cachedModel = null }
    /** Tests and tools can plug in their own model; null means [CommanderConfig.createModel] */
    @Volatile var modelOverride: CommanderModel? = null
    @Volatile private var cachedModel: CommanderModel? = null

    private val calls = AtomicInteger()
    private val callSerial = AtomicInteger()

    private class BattleMemory(val unitIds: Set<Int>, val turn: Int, val intent: String, val outcome: String)
    private val memory = WeakHashMap<Civilization, MutableList<BattleMemory>>()

    fun model(): CommanderModel = modelOverride ?: cachedModel ?: config.createModel().also { cachedModel = it }

    fun resetCallCount() = calls.set(0)
    val callCount: Int get() = calls.get()

    fun isActive(civ: Civilization) = civ.aiProfile.has(AiFeature.LlmCommander)

    /** Commands every important battle of [civ] this turn. Returns ids of units that got orders and must not be moved again */
    fun commandBattles(civ: Civilization): Set<Int> {
        val cfg = config
        val handled = HashSet<Int>()
        val engagements = try { BattleDetector.detect(civ, cfg.reinforcementTurns) } catch (e: Exception) {
            log(cfg, "detector-error.txt", "${civ.civName} T${civ.gameInfo.turns}: ${e.stackTraceToString()}", append = true)
            return handled
        }
        for (engagement in engagements) {
            if (!engagement.isImportant(cfg.minUnits)) continue
            if (cfg.maxCalls > 0 && calls.get() >= cfg.maxCalls) break
            // An earlier battle this turn may have moved or killed some of these units
            if (engagement.ownUnits.all { it.isDestroyed || it.id in handled }) continue
            civ.experimentCounters.commanderBattles++
            handled += commandBattle(civ, engagement, cfg)
        }
        return handled
    }

    private fun commandBattle(civ: Civilization, engagement: Engagement, cfg: CommanderConfig): Set<Int> {
        val turn = civ.gameInfo.turns
        val serial = callSerial.incrementAndGet()
        val logName = "T%03d-%s-b%d-%04d.md".format(turn, civ.civName.replace(Regex("[^A-Za-z0-9]"), ""), engagement.index, serial)
        val logText = StringBuilder()
        val allowed = (engagement.ownUnits + engagement.reinforcements).map { it.id }.toSet()
        val previous = recall(civ, engagement)

        try {
            val digest = BattleDigestBuilder.build(civ, engagement)
            val system = CommanderPrompt.systemPrompt(cfg.intentLanguage)
            val user = CommanderPrompt.userPrompt(digest, previous?.intent, previous?.outcome)
            val messages = arrayListOf(CommanderMessage("system", system), CommanderMessage("user", user))
            logText.appendLine("# ${civ.civName}, turn $turn, battle ${engagement.index} (game ${civ.gameInfo.gameId.take(8)}, model ${model().name})")
            logText.appendLine().appendLine("## Prompt").appendLine().appendLine("```").append(user).appendLine("```")

            var reply = ask(civ, messages, digest, logText, "Answer")
            var plan = CommanderPlanParser.parse(reply.content)
            logPlan(logText, plan)

            // Execute; after a surprise (failed order, lost unit, a kill that did not happen) show the model the
            // real situation and let it re-plan the units that can still act. No peeking at outcomes in advance.
            val executor = OrderExecutor(civ, allowed)
            var replansLeft = cfg.replans
            logText.appendLine().appendLine("## Execution").appendLine().appendLine("```")
            while (true) {
                val before = executor.results.size
                val notRun = if (replansLeft > 0) executor.executeUntilSurprise(plan.orders) else { executor.execute(plan.orders); emptyList() }
                val newResults = executor.results.drop(before)
                newResults.forEach { logText.appendLine(it) }
                val surprise = newResults.lastOrNull()?.surprise ?: break
                val current = refresh(civ, engagement)
                if (current.ownUnits.none { it.hasMovement() }) {
                    if (notRun.isNotEmpty()) logText.appendLine("(no unit can act any more, ${notRun.size} orders dropped)")
                    break
                }
                replansLeft--
                logText.appendLine("```")
                val situation = BattleDigestBuilder.build(civ, current)
                val feedback = buildString {
                    appendLine("Execution so far:")
                    executor.results.forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
                    appendLine()
                    appendLine("Surprise: $surprise. Execution stopped here; ${notRun.size} of your orders were not carried out.")
                    appendLine("The situation now (units that already acted cannot attack or move any more):")
                    appendLine()
                    append(BattleDigestBuilder.render(situation))
                    appendLine("Give new orders (same JSON format) for the units that can still act. Executed orders are done, do not repeat them.")
                }
                logText.appendLine().appendLine("## Replan after a surprise").appendLine().appendLine("```").append(feedback).appendLine("```")
                messages += CommanderMessage("assistant", reply.content)
                messages += CommanderMessage("user", feedback)
                reply = ask(civ, messages, situation, logText, "Replanned answer")
                val replanned = CommanderPlanParser.parse(reply.content)
                logPlan(logText, replanned)
                if (replanned.intent.isNotBlank()) plan = CommanderPlan(replanned.intent, replanned.orders, plan.productionHints + replanned.productionHints, replanned.warnings)
                else plan = CommanderPlan(plan.intent, replanned.orders, plan.productionHints + replanned.productionHints, replanned.warnings)
                logText.appendLine().appendLine("## Execution, continued").appendLine().appendLine("```")
            }
            val results = executor.results
            val ok = results.count { it.ok }
            civ.experimentCounters.commanderOrdersOk += ok
            civ.experimentCounters.commanderOrdersFailed += results.size - ok
            val hints = applyProductionHints(civ, plan)
            hints.forEach { logText.appendLine(it) }
            logText.appendLine("```")
            val unmentioned = engagement.ownUnits.filter { it.id !in executor.handledUnitIds && !it.isDestroyed }
            if (unmentioned.isNotEmpty()) logText.appendLine().appendLine("Left to the default AI: " + unmentioned.joinToString { "#${it.id} ${it.name}" })

            remember(civ, engagement, plan.intent, "$ok of ${results.size} orders carried out; " +
                results.filter { it.order.action == "attack" && it.ok }.joinToString("; ") { it.message }.ifEmpty { "no attacks" })
            return executor.handledUnitIds
        } catch (e: Exception) {
            civ.experimentCounters.commanderModelErrors++
            logText.appendLine().appendLine("## Error, battle left to the default AI").appendLine().appendLine("```")
                .appendLine(e.stackTraceToString().take(3000)).appendLine("```")
            return emptySet()
        } finally {
            log(cfg, logName, logText.toString())
        }
    }

    /** The battle as it stands now: dead units dropped, everything else as when the battle was detected */
    private fun refresh(civ: Civilization, engagement: Engagement): Engagement {
        val visible = BattleDetector.visibleEnemyUnits(civ).toSet()
        return Engagement(engagement.index,
            engagement.ownUnits.filter { !it.isDestroyed && it.civ == civ },
            engagement.enemyUnits.filter { !it.isDestroyed && it in visible },
            engagement.ownCities.filter { it.civ == civ },
            engagement.enemyCities.filter { it.civ != civ && civ.isAtWarWith(it.civ) },
            engagement.reinforcements.filter { !it.isDestroyed && it.civ == civ })
    }

    private fun ask(civ: Civilization, messages: List<CommanderMessage>, digest: BattleDigest, logText: StringBuilder, title: String): CommanderReply {
        calls.incrementAndGet()
        civ.experimentCounters.commanderCalls++
        val reply = model().complete(messages, digest)
        logText.appendLine().appendLine("## $title (${reply.elapsedMillis / 1000}s)").appendLine()
        if (reply.reasoning.isNotBlank())
            logText.appendLine("<details><summary>reasoning</summary>").appendLine().appendLine(reply.reasoning).appendLine("</details>").appendLine()
        logText.appendLine("```").appendLine(reply.content).appendLine("```")
        return reply
    }

    private fun logPlan(logText: StringBuilder, plan: CommanderPlan) {
        if (plan.warnings.isNotEmpty()) logText.appendLine().appendLine("Parser warnings: " + plan.warnings.joinToString("; "))
    }

    /** Puts the first preferred unit the city can build at the top of its queue */
    private fun applyProductionHints(civ: Civilization, plan: CommanderPlan): List<String> {
        val messages = ArrayList<String>()
        for (hint in plan.productionHints) {
            val city = civ.cities.firstOrNull { it.ref().equals(hint.city, ignoreCase = true) || it.name.equals(hint.city, ignoreCase = true) }
            if (city == null) { messages += "production_hint: no own city '${hint.city}'"; continue }
            val constructions = city.cityConstructions
            val unit: BaseUnit? = hint.prefer.mapNotNull { civ.gameInfo.ruleset.units[it] }
                .firstOrNull { it.isBuildable(constructions) }
            if (unit == null) { messages += "production_hint: ${city.name} cannot build any of ${hint.prefer}"; continue }
            if (constructions.currentConstructionName() == unit.name) { messages += "production_hint: ${city.name} already builds ${unit.name}"; continue }
            constructions.addToQueue(unit, addToTop = true)
            civ.experimentCounters.commanderProductionHints++
            messages += "production_hint: ${city.name} now builds ${constructions.currentConstructionName()}"
        }
        return messages
    }

    private fun recall(civ: Civilization, engagement: Engagement): BattleMemory? = synchronized(memory) {
        val ids = engagement.unitIds
        memory[civ]?.filter { it.turn >= civ.gameInfo.turns - 2 }
            ?.maxByOrNull { (it.unitIds intersect ids).size }
            ?.takeIf { (it.unitIds intersect ids).isNotEmpty() }
    }

    private fun remember(civ: Civilization, engagement: Engagement, intent: String, outcome: String) = synchronized(memory) {
        val list = memory.getOrPut(civ) { ArrayList() }
        val ids = engagement.unitIds
        list.removeAll { it.turn < civ.gameInfo.turns - 2 || (it.unitIds intersect ids).isNotEmpty() }
        list += BattleMemory(ids, civ.gameInfo.turns, intent, outcome)
    }

    private fun log(cfg: CommanderConfig, name: String, text: String, append: Boolean = false) {
        val dir = cfg.logDirFile() ?: return
        try {
            dir.mkdirs()
            val file = File(dir, name)
            if (append) file.appendText(text + "\n") else file.writeText(text)
            if (!append && !File(dir, "system-prompt.txt").exists())
                File(dir, "system-prompt.txt").writeText(CommanderPrompt.systemPrompt(cfg.intentLanguage))
        } catch (_: Exception) { }
    }
}
