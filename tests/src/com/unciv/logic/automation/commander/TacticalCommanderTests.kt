package com.unciv.logic.automation.commander

import com.sun.net.httpserver.HttpServer
import com.unciv.logic.automation.AiProfile
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

@RunWith(GdxTestRunner::class)
class TacticalCommanderTests {
    private lateinit var game: TestGame
    private lateinit var us: Civilization
    private lateinit var enemy: Civilization
    private lateinit var archer: MapUnit
    private lateinit var warrior: MapUnit
    private lateinit var enemyWarrior: MapUnit
    private lateinit var enemyWarrior2: MapUnit

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(8)
        us = game.addCiv()
        enemy = game.addCiv()
        us.diplomacyFunctions.makeCivilizationsMeet(enemy)
        us.getDiplomacyManager(enemy)!!.declareWar()
        game.addCity(us, game.getTile(-5, -5))
        game.addCity(enemy, game.getTile(6, 6))

        archer = game.addUnit("Archer", us, game.getTile(0, 0))
        warrior = game.addUnit("Warrior", us, game.getTile(1, 0))
        enemyWarrior = game.addUnit("Warrior", enemy, game.getTile(2, 1))
        enemyWarrior.health = 40
        enemyWarrior2 = game.addUnit("Warrior", enemy, game.getTile(3, 2))
        us.cache.updateViewableTiles()
        enemy.cache.updateViewableTiles()
        us.aiProfile = AiProfile.parse("default+UnitTactics+LlmCommander")
        TacticalCommander.config = CommanderConfig(logDir = "")
    }

    @After
    fun tearDown() {
        TacticalCommander.modelOverride = null
        TacticalCommander.config = CommanderConfig(logDir = "")
    }

    @Test
    fun detectorFindsTheBattleAndReinforcements() {
        val farSpearman = game.addUnit("Spearman", us, game.getTile(-4, -2))
        val veryFar = game.addUnit("Warrior", us, game.getTile(-8, -8))
        us.cache.updateViewableTiles()

        val battles = BattleDetector.detect(us)
        assertEquals(1, battles.size)
        val battle = battles.single()
        assertEquals(setOf(archer, warrior), battle.ownUnits.toSet())
        assertEquals(setOf(enemyWarrior, enemyWarrior2), battle.enemyUnits.toSet())
        assertTrue(farSpearman in battle.reinforcements)
        assertFalse(veryFar in battle.reinforcements)
        assertTrue(battle.isImportant(3))
        assertFalse(battle.isImportant(5))
    }

    @Test
    fun noBattleWithoutVisibleEnemies() {
        enemyWarrior.destroy()
        enemyWarrior2.destroy()
        us.cache.updateViewableTiles()
        assertTrue(BattleDetector.detect(us).none { it.enemyUnits.isNotEmpty() })
    }

    @Test
    fun digestListsComputedAttacksAndMoves() {
        val battle = BattleDetector.detect(us).single()
        val digest = BattleDigestBuilder.build(us, battle)
        val ownWarrior = digest.own.first { it.unit == warrior }
        val attack = ownWarrior.attacks.firstOrNull { it.targetRef == enemyWarrior.id.toString() }
        assertNotNull(attack)
        assertTrue(attack!!.damageDealt > 0)
        assertTrue(attack.damageTaken > 0)
        assertTrue(ownWarrior.moves.isNotEmpty())
        assertTrue(ownWarrior.moves.size <= BattleDigestBuilder.MAX_MOVES)

        val text = BattleDigestBuilder.render(digest)
        assertTrue(text, text.contains("#${warrior.id} Warrior [melee]"))
        assertTrue(text, text.contains("attack ${enemyWarrior.id} (Warrior)"))
        assertTrue(text, text.contains("### Enemy units"))
        val prompt = CommanderPrompt.userPrompt(digest, "hold the line", null)
        assertTrue(prompt.contains("Your intent for this battle last turn: hold the line"))
        assertTrue(prompt.contains("Can build now"))
        assertTrue(prompt, prompt.contains("city@-5,-5"))
    }

    @Test
    fun parserIsTolerant() {
        val answer = """
            <think>maybe attack {"unit": 1}</think>
            Here is the plan:
            ```json
            {"intent": "бьём раненого", "orders": [
              {"unit": "#17", "action": "attack", "target": 42},
              {"unit": 9, "action": "Move", "tile": [7, 12], "then": "Fortify"},
              {"unit": 5, "action": "dance"},
              {"action": "hold"}
            ], "production_hint": {"city": "city:Rome", "prefer": ["Archer"]}}
            ```
        """.trimIndent()
        val plan = CommanderPlanParser.parse(answer)
        assertEquals("бьём раненого", plan.intent)
        assertEquals(2, plan.orders.size)
        assertEquals(CommanderOrder(17, "attack", target = "42"), plan.orders[0])
        assertEquals(CommanderOrder(9, "move", tile = "7,12", then = "fortify"), plan.orders[1])
        assertEquals(2, plan.warnings.size)
        assertEquals(listOf(ProductionHint("Rome", listOf("Archer"))), plan.productionHints)

        assertTrue(CommanderPlanParser.parse("I refuse").orders.isEmpty())
        assertEquals(1, CommanderPlanParser.parse("""text {"orders":[{"unit":3,"action":"hold"}]} more""").orders.size)
    }

    @Test
    fun executorRunsValidOrdersAndRejectsBadOnes() {
        val battle = BattleDetector.detect(us).single()
        val allowed = battle.ownUnits.map { it.id }.toSet()
        val executor = OrderExecutor(us, allowed)
        val results = executor.execute(listOf(
            CommanderOrder(archer.id, "attack", target = enemyWarrior.id.toString()),
            CommanderOrder(warrior.id, "attack", target = enemyWarrior.id.toString()),
            CommanderOrder(enemyWarrior2.id, "hold"),
            CommanderOrder(archer.id, "move", tile = "99,99"),
        ))
        assertTrue(results[0].toString(), results[0].ok)
        assertTrue(results[1].toString(), results[1].ok)
        assertTrue(enemyWarrior.isDestroyed)
        assertFalse(results[2].ok) // not our unit
        assertFalse(results[3].ok) // no such tile
        assertEquals(setOf(archer.id, warrior.id), executor.handledUnitIds)
    }

    @Test
    fun commanderRunsTheWholeLoopWithTheStubModel() {
        val logDir = Files.createTempDirectory("commander").toFile()
        TacticalCommander.config = CommanderConfig(logDir = logDir.path)
        TacticalCommander.modelOverride = StubCommanderModel()

        val handled = TacticalCommander.commandBattles(us)
        assertTrue(handled.isNotEmpty())
        assertEquals(1, us.experimentCounters.commanderBattles)
        val log = logDir.listFiles()!!.single { it.name.endsWith(".md") }.readText()
        assertTrue(log, us.experimentCounters.commanderCalls >= 1)
        assertEquals(0, us.experimentCounters.commanderModelErrors)
        assertTrue(log, log.contains("## Execution"))
        assertTrue(log, log.contains("OK   #${archer.id} attack"))
        assertTrue(File(logDir, "system-prompt.txt").exists())
        logDir.deleteRecursively()
    }

    /** Answers with the given texts in turn, recording what it was asked */
    private class ScriptedModel(vararg val answers: String) : CommanderModel {
        override val name = "scripted"
        val asked = ArrayList<List<CommanderMessage>>()
        override fun complete(messages: List<CommanderMessage>, digest: BattleDigest): CommanderReply {
            asked += messages.toList()
            return CommanderReply(answers[(asked.size - 1).coerceAtMost(answers.size - 1)])
        }
    }

    @Test
    fun surpriseStopsExecutionAndTheModelReplans() {
        val logDir = Files.createTempDirectory("commander").toFile()
        TacticalCommander.config = CommanderConfig(logDir = logDir.path, replans = 2)
        // First plan: an impossible move (a surprise), then an attack that must not run before the re-plan
        val model = ScriptedModel(
            """{"intent": "a", "orders": [{"unit": ${warrior.id}, "action": "move", "tile": "99,99"},
                {"unit": ${archer.id}, "action": "attack", "target": ${enemyWarrior.id}}]}""",
            """{"intent": "b", "orders": [{"unit": ${archer.id}, "action": "attack", "target": ${enemyWarrior.id}},
                {"unit": ${warrior.id}, "action": "fortify"}]}""",
        )
        TacticalCommander.modelOverride = model

        val handled = TacticalCommander.commandBattles(us)
        assertEquals(2, model.asked.size)
        val replanPrompt = model.asked[1].last().content
        assertTrue(replanPrompt, replanPrompt.contains("Surprise: order failed"))
        assertTrue(replanPrompt, replanPrompt.contains("1 of your orders were not carried out"))
        assertEquals(setOf(archer.id, warrior.id), handled)
        assertTrue(archer.attacksThisTurn == 1)
        val log = logDir.listFiles()!!.single { it.name.endsWith(".md") }.readText()
        assertTrue(log, log.contains("## Replan after a surprise"))
        logDir.deleteRecursively()
    }

    private fun alwaysFailing() = """{"intent": "a", "orders": [{"unit": ${warrior.id}, "action": "move", "tile": "99,99"}]}"""

    @Test
    fun replanLimitHoldsWhenTheLastOrderKeepsFailing() {
        TacticalCommander.config = CommanderConfig(logDir = "", replans = 2)
        val model = ScriptedModel(alwaysFailing())
        TacticalCommander.modelOverride = model

        TacticalCommander.commandBattles(us)

        assertEquals(3, model.asked.size)
    }

    @Test
    fun replanRequestsDoNotGrow() {
        TacticalCommander.config = CommanderConfig(logDir = "", replans = 5)
        val model = ScriptedModel(alwaysFailing())
        TacticalCommander.modelOverride = model

        TacticalCommander.commandBattles(us)

        assertEquals(6, model.asked.size)
        for (request in model.asked) assertEquals(2, request.size)
        val sizes = model.asked.drop(1).map { request -> request.sumOf { it.content.length } }
        assertEquals("every re-plan is the same size: $sizes", 1, sizes.toSet().size)
    }

    @Test
    fun requestOverTheLimitIsNotSent() {
        TacticalCommander.config = CommanderConfig(logDir = "", maxRequestChars = 100)
        val model = ScriptedModel(alwaysFailing())
        TacticalCommander.modelOverride = model

        assertTrue(TacticalCommander.commandBattles(us).isEmpty())

        assertEquals(0, model.asked.size)
        assertEquals(1, us.experimentCounters.commanderModelErrors)
    }

    @Test
    fun secondOrderForTheSameTileGoesToAnotherTileOfTheMenu() {
        TacticalCommander.config = CommanderConfig(logDir = "", replans = 2)
        val free = archer.movement.getDistanceToTiles().keys.first { tile ->
            tile != archer.getTile() && tile != warrior.getTile() && tile.militaryUnit == null &&
                tile in warrior.movement.getDistanceToTiles().keys
        }
        val spot = "${free.position.x},${free.position.y}"
        val model = ScriptedModel(
            """{"intent": "a", "orders": [{"unit": ${archer.id}, "action": "move", "tile": "$spot"},
                {"unit": ${warrior.id}, "action": "move", "tile": "$spot"}]}""")
        TacticalCommander.modelOverride = model

        val handled = TacticalCommander.commandBattles(us)

        assertEquals(1, model.asked.size)
        assertEquals(setOf(archer.id, warrior.id), handled)
        assertEquals(free, archer.getTile())
        assertTrue(warrior.getTile() != free)
    }

    @Test
    fun attackTheMenuNeverOfferedIsNotASurprise() {
        TacticalCommander.config = CommanderConfig(logDir = "", replans = 2)
        val model = ScriptedModel(
            """{"intent": "a", "orders": [{"unit": ${warrior.id}, "action": "attack", "target": "city@6,6"},
                {"unit": ${archer.id}, "action": "fortify"}]}""")
        TacticalCommander.modelOverride = model

        val handled = TacticalCommander.commandBattles(us)

        assertEquals(1, model.asked.size)
        assertEquals(setOf(archer.id), handled)
    }

    private fun menuWith(attacks: List<AttackOption>): BattleDigest {
        val engagement = BattleDetector.detect(us).single()
        return BattleDigest(us, engagement, listOf(OwnUnitDigest(warrior, 0, attacks, emptyList())), emptyList())
    }

    @Test
    fun attackTheMenuOfferedThatIsNoLongerPossibleIsASurprise() {
        val offered = AttackOption("city@6,6", "City", game.getTile(5, 5), 10, 0, 100, 100, 0, true)
        val executor = OrderExecutor(us, setOf(warrior.id), menuWith(listOf(offered)))

        val result = executor.execute(listOf(CommanderOrder(warrior.id, "attack", target = "city@6,6"))).single()

        assertFalse(result.ok)
        assertNotNull(result.surprise)
    }

    @Test
    fun promptAsksForTheReasonOfAttacksAndTheParserAcceptsIt() {
        assertTrue(CommanderPrompt.systemPrompt("Russian").contains("\"why\""))

        val plan = CommanderPlanParser.parse("""{"intent": "a", "orders": [{"unit": 3, "action": "attack", "target": 7, "why": "добиваю"}]}""")

        assertEquals(1, plan.orders.size)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun menuSaysWhenAUnitHasNoAttack() {
        assertTrue(BattleDigestBuilder.render(menuWith(emptyList())).contains("no attack available"))
    }

    @Test
    fun onlyTheLatestProductionHintOfABattleIsApplied() {
        TacticalCommander.config = CommanderConfig(logDir = "", replans = 2)
        val city = us.cities.single()
        val hint = { unit: String -> """"production_hint": {"city": "city@${city.location.x},${city.location.y}", "prefer": ["$unit"]}""" }
        val model = ScriptedModel(
            """{"intent": "a", "orders": [{"unit": ${warrior.id}, "action": "move", "tile": "99,99"}], ${hint("Scout")}}""",
            """{"intent": "b", "orders": [{"unit": ${warrior.id}, "action": "fortify"}], ${hint("Warrior")}}""",
        )
        TacticalCommander.modelOverride = model

        TacticalCommander.commandBattles(us)

        assertEquals(2, model.asked.size)
        assertEquals(1, us.experimentCounters.commanderProductionHints)
        assertEquals("Warrior", city.cityConstructions.currentConstructionName())
    }

    @Test
    fun withoutReplansAllOrdersRun() {
        TacticalCommander.config = CommanderConfig(logDir = "", replans = 0)
        val model = ScriptedModel(
            """{"intent": "a", "orders": [{"unit": ${warrior.id}, "action": "move", "tile": "99,99"},
                {"unit": ${archer.id}, "action": "attack", "target": ${enemyWarrior.id}}]}""")
        TacticalCommander.modelOverride = model
        TacticalCommander.commandBattles(us)
        assertEquals(1, model.asked.size)
        assertEquals(1, archer.attacksThisTurn)
    }

    @Test
    fun brokenModelFallsBackToDefaultAi() {
        TacticalCommander.modelOverride = object : CommanderModel {
            override val name = "broken"
            override fun complete(messages: List<CommanderMessage>, digest: BattleDigest): CommanderReply =
                throw CommanderModelException("connection refused")
        }
        assertTrue(TacticalCommander.commandBattles(us).isEmpty())
        assertEquals(1, us.experimentCounters.commanderModelErrors)
        assertEquals(100, archer.health)
        assertEquals(archer.getMaxMovement().toFloat(), archer.currentMovement)
    }

    @Test
    fun openAiCompatibleClientTalksHttp() {
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requestBody = exchange.requestBody.bufferedReader().readText()
            val response = """{"choices":[{"message":{"role":"assistant","content":"{\"intent\":\"x\",\"orders\":[]}","reasoning_content":"thinking..."}}]}"""
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val config = CommanderConfig(baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "deepseek-test")
            val model = config.createModel()
            val digest = BattleDigestBuilder.build(us, BattleDetector.detect(us).single())
            val reply = model.complete(listOf(CommanderMessage("user", "line1\n\"quoted\"")), digest)
            assertEquals("{\"intent\":\"x\",\"orders\":[]}", reply.content)
            assertEquals("thinking...", reply.reasoning)
            assertTrue(requestBody, requestBody.contains("\"model\":\"deepseek-test\""))
            assertTrue(requestBody, requestBody.contains("line1\\n\\\"quoted\\\""))
        } finally {
            server.stop(0)
        }
    }
}
