package com.unciv.logic.automation.civilization.strategy

import com.unciv.logic.automation.AiFeature
import com.unciv.logic.automation.unit.CivilianUnitAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.Victory
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.stats.Stat
import com.unciv.ui.screens.victoryscreen.RankingType
import yairm210.purity.annotations.Readonly
import org.jetbrains.annotations.VisibleForTesting
import kotlin.math.pow

/**
 * Keeps a civ's [StrategicPlan] up to date.
 *
 * Two levels of attention instead of a fixed heartbeat:
 * - **Every turn, a cheap check.** [SituationSnapshot] is a handful of counters (enemies, cities, sieges, era...).
 *   If it differs from last turn in a way that matters, a detailed review runs right away.
 * - **Every few turns, a detailed review.** It re-scores the victory goal against the rankings, compares armies,
 *   looks for free land and picks the phase. It runs every [MILITARY_REVIEW_INTERVAL] turns in war-related phases
 *   and every [PEACE_REVIEW_INTERVAL] turns otherwise, or earlier when the cheap check fires.
 *
 * To stop the plan from flip-flopping, the goal only changes when another one is clearly better and has held
 * for [MIN_GOAL_TURNS], and peaceful phase changes wait [MIN_PHASE_TURNS]. Urgent events (war declared on us,
 * a city lost, a siege) skip the wait.
 *
 * Everything here is deterministic: no settings, no unseeded randomness.
 */
object StrategicPlanner {
    const val PEACE_REVIEW_INTERVAL = 10
    const val MILITARY_REVIEW_INTERVAL = 5
    const val MIN_PHASE_TURNS = 5
    const val MIN_GOAL_TURNS = 20
    /** How long we wait for a self-initiated war preparation to turn into a war */
    const val MAX_OWN_WAR_PREPARATION_TURNS = 30
    /** After giving up a self-initiated war preparation, don't start another one for this long */
    const val OWN_WAR_PREPARATION_COOLDOWN = 30

    @Readonly
    fun isEnabled(civ: Civilization) = civ.isMajorCiv() && civ.aiProfile.has(AiFeature.StrategicPlan)

    /** The plan of [civ], or null when the feature is off or the plan was not made yet */
    @Readonly
    fun activePlan(civ: Civilization): StrategicPlan? {
        if (!isEnabled(civ)) return null
        val plan = civ.aiMemory.strategicPlan
        return if (plan.isActive()) plan else null
    }

    /** Multipliers for the existing AI functions; [StrategicWeights.NEUTRAL] when there is no plan */
    @Readonly
    fun weights(civ: Civilization): StrategicWeights = activePlan(civ)?.weights() ?: StrategicWeights.NEUTRAL

    /** Added to [com.unciv.logic.automation.civilization.MotivationToAttackAutomation.hasAtLeastMotivationToAttack] */
    @Readonly
    fun attackMotivationModifier(civ: Civilization, target: Civilization): Float {
        val plan = activePlan(civ) ?: return 0f
        val weights = plan.weights()
        return weights.warMotivation + if (target.civID == plan.warTarget) weights.targetMotivation else 0f
    }

    /**
     * How much the plan wants [tech], judged by what it unlocks: military units when arming, settlers when
     * expanding, wonders and buildings by the stats the plan favours. 1 when there is no plan.
     */
    fun techMultiplier(civ: Civilization, tech: Technology): Float {
        val weights = weights(civ)
        if (weights == StrategicWeights.NEUTRAL) return 1f
        val ruleset = civ.gameInfo.ruleset
        var multiplier = 1f
        for (unit in ruleset.units.values) {
            if (tech.name !in unit.requiredTechs()) continue
            if (unit.isMilitary) multiplier += (weights.military - 1f) * 0.5f
            if (unit.isCityFounder()) multiplier += (weights.expansion - 1f) * 0.5f
        }
        for (building in ruleset.buildings.values) {
            if (tech.name !in building.requiredTechs()) continue
            if (building.isAnyWonder()) multiplier += (weights.wonders - 1f) * 0.3f
            if (building.cityStrength > 0 || building.cityHealth > 0) multiplier += (weights.defense - 1f) * 0.3f
            for (stat in Stat.entries)
                if (building[stat] > 0f) multiplier += (weights.forStat(stat) - 1f) * 0.3f
        }
        return multiplier.coerceIn(0.25f, 4f)
    }

    /** Called at the start of every AI turn of a major civ */
    fun update(civ: Civilization) {
        if (!isEnabled(civ)) return
        val plan = civ.aiMemory.strategicPlan
        val turn = civ.gameInfo.turns
        val now = takeSnapshot(civ)
        val trigger = findTrigger(plan, now, turn)
        plan.snapshot = now
        if (trigger != null) review(civ, plan, trigger)
        civ.experimentCounters.countPhaseTurn(plan.phase)
    }

    class Trigger(val reason: String, val urgent: Boolean)

    // region Cheap per-turn check

    @VisibleForTesting
    fun takeSnapshot(civ: Civilization): SituationSnapshot {
        val snapshot = SituationSnapshot()
        for (diplomacy in civ.diplomacy.values) {
            val other = diplomacy.otherCiv
            if (other.isDefeated()) continue
            if (other.isMajorCiv() && civ.isAtWarWith(other)) snapshot.majorEnemies.add(other.civID)
            if (diplomacy.hasFlag(DiplomacyFlags.WaryOf) && diplomacy.getFlag(DiplomacyFlags.WaryOf) < 0)
                snapshot.preparingWarOn.add(other.civID)
        }
        snapshot.cities = civ.cities.size
        snapshot.citiesUnderSiege = civ.cities.count { it.health < it.getMaxHealth() * 3 / 4 }
        snapshot.era = civ.getEra().name
        snapshot.unhappy = civ.getHappiness() < 0
        return snapshot
    }

    @VisibleForTesting
    fun findTrigger(plan: StrategicPlan, now: SituationSnapshot, turn: Int): Trigger? {
        if (!plan.isActive()) return Trigger("first review", true)
        val before = plan.snapshot
        val newEnemies = now.majorEnemies - before.majorEnemies
        return when {
            newEnemies.isNotEmpty() -> Trigger("war with ${newEnemies.sorted().joinToString()}", true)
            now.cities < before.cities -> Trigger("lost a city", true)
            now.citiesUnderSiege > 0 && before.citiesUnderSiege == 0 -> Trigger("city under siege", true)
            now.majorEnemies.size < before.majorEnemies.size -> Trigger("peace", true)
            now.preparingWarOn != before.preparingWarOn -> Trigger("war preparation changed", false)
            now.cities > before.cities -> Trigger("new city", false)
            now.era != before.era -> Trigger("new era ${now.era}", false)
            now.unhappy != before.unhappy -> Trigger(if (now.unhappy) "unhappy" else "happy again", false)
            turn >= plan.nextReviewTurn -> Trigger("scheduled", false)
            else -> null
        }
    }

    // endregion

    // region Detailed review

    @VisibleForTesting
    fun review(civ: Civilization, plan: StrategicPlan, trigger: Trigger) {
        val turn = civ.gameInfo.turns
        val firstReview = !plan.isActive()

        val goal = chooseGoal(civ, plan, turn)
        if (goal != null && goal.name != plan.goal) {
            if (!firstReview) plan.addHistory("T$turn goal ${plan.goal.ifEmpty { "-" }} -> ${goal.name}")
            plan.goal = goal.name
            plan.goalKind = VictoryKind.of(goal)
            plan.goalSinceTurn = turn
        }

        updateExpansion(civ, plan)
        val (phase, target) = choosePhase(civ, plan, turn)
        val peacefulChange = phase in PEACEFUL_PHASES && plan.phase in PEACEFUL_PHASES
        val mayChange = firstReview || trigger.urgent || !peacefulChange || turn - plan.phaseSinceTurn >= MIN_PHASE_TURNS
        if (phase != plan.phase && mayChange) {
            if (plan.phase == StrategicPhase.WarPreparation && phase != StrategicPhase.War && plan.snapshot.preparingWarOn.isEmpty())
                plan.noOwnWarPlansUntilTurn = turn + OWN_WAR_PREPARATION_COOLDOWN
            if (!firstReview) plan.addHistory("T$turn ${plan.phase} -> $phase (${trigger.reason})")
            plan.phase = phase
            plan.phaseSinceTurn = turn
        }
        plan.warTarget = if (plan.phase.isMilitary()) target ?: plan.warTarget else ""

        plan.lastReviewTurn = turn
        plan.lastReviewReason = trigger.reason
        plan.nextReviewTurn = turn + if (plan.phase.isMilitary()) MILITARY_REVIEW_INTERVAL else PEACE_REVIEW_INTERVAL
        civ.experimentCounters.strategicReviews++
        if (trigger.urgent || trigger.reason != "scheduled") civ.experimentCounters.triggeredStrategicReviews++
    }

    private val PEACEFUL_PHASES = setOf(StrategicPhase.Expansion, StrategicPhase.Development, StrategicPhase.WarPreparation)

    /** Victory types we can actually aim for in this game */
    @VisibleForTesting
    fun candidateVictories(civ: Civilization): List<Victory> {
        val ruleset = civ.gameInfo.ruleset
        return civ.gameInfo.gameParameters.victoryTypes.mapNotNull { ruleset.victories[it] }
            .filter { VictoryKind.of(it) != VictoryKind.Other }
    }

    @VisibleForTesting
    fun chooseGoal(civ: Civilization, plan: StrategicPlan, turn: Int): Victory? {
        val victories = candidateVictories(civ)
        if (victories.isEmpty()) return null
        val scores = victories.associateWith { scoreGoal(civ, it) }
        val best = scores.maxBy { it.value }
        val current = victories.firstOrNull { it.name == plan.goal } ?: return best.key
        if (best.key == current) return current
        // Sticky: only switch to a clearly better goal, and not too often
        if (turn - plan.goalSinceTurn < MIN_GOAL_TURNS) return current
        return if (best.value > scores[current]!! * 1.25f) best.key else current
    }

    /** How promising [victory] looks for [civ]: personality preference times relative standing, plus progress */
    @VisibleForTesting
    fun scoreGoal(civ: Civilization, victory: Victory): Float {
        val preferred = listOf(civ.nation.preferredVictoryType, civ.getPersonality().preferredVictoryType)
        var score = if (victory.name in preferred) 2f else 1f

        val rivals = civ.getKnownCivs().filter { it.isMajorCiv() && it.isAlive() }.toList() + civ
        @Readonly fun standing(stat: (Civilization) -> Float): Float {
            val average = rivals.map(stat).average().toFloat()
            if (average <= 0f) return 1f
            return (stat(civ) / average).coerceIn(0.5f, 2f)
        }

        val standing = when (VictoryKind.of(victory)) {
            VictoryKind.Science -> standing { it.getStatForRanking(RankingType.Technologies).toFloat() }
            VictoryKind.Culture -> standing { it.policies.adoptedPolicies.size.toFloat() }
            VictoryKind.Military -> if (rivals.size == 1) 1f else standing { it.getStatForRanking(RankingType.Force).toFloat() }
            VictoryKind.Diplomatic -> standing { other ->
                1f + other.getKnownCivs().count { it.isCityState && it.allyCiv == other }
            }
            VictoryKind.Religion -> if (civ.religionManager.religion != null) 1.2f else 0.5f
            VictoryKind.Other -> 1f
        }
        score *= standing.pow(1.5f)
        score += 0.5f * civ.victoryManager.amountMilestonesCompleted(victory)
        return score
    }

    @VisibleForTesting
    fun choosePhase(civ: Civilization, plan: StrategicPlan, turn: Int): Pair<StrategicPhase, String?> {
        val snapshot = plan.snapshot
        val majorEnemies = civ.getCivsAtWarWith().filter { it.isMajorCiv() }

        if (majorEnemies.isNotEmpty() || snapshot.citiesUnderSiege > 0) {
            val ourForce = civ.getStatForRanking(RankingType.Force).toFloat()
            val enemyForce = civ.threatManager.getCombinedForceOfWarringCivs().toFloat().coerceAtLeast(1f)
            val ratio = ourForce / enemyForce
            // Hysteresis: once defending, we want a clearly better army before going back on the offensive
            val losingThreshold = if (plan.phase == StrategicPhase.Defense) 1f else 0.8f
            val losing = ratio < losingThreshold || (snapshot.citiesUnderSiege > 0 && ratio < 1.2f)
            val mainEnemy = majorEnemies.maxByOrNull { it.getStatForRanking(RankingType.Force) }
            if (mainEnemy == null) {
                // Siege by barbarians or a city-state: defend, but this is no reason for a war phase
                if (losing) return StrategicPhase.Defense to null
            } else return (if (losing) StrategicPhase.Defense else StrategicPhase.War) to mainEnemy.civID
        }

        // The existing war logic decided to prepare an attack: back it with production
        val preparing = snapshot.preparingWarOn.sorted().firstOrNull()
        if (preparing != null) return StrategicPhase.WarPreparation to preparing

        if (plan.goalKind == VictoryKind.Military && turn >= plan.noOwnWarPlansUntilTurn) {
            val stillPreparing = plan.phase == StrategicPhase.WarPreparation
            val tooLong = stillPreparing && turn - plan.phaseSinceTurn > MAX_OWN_WAR_PREPARATION_TURNS
            if (!tooLong && !wantsToExpand(civ)) {
                val target = findOwnWarTarget(civ, if (stillPreparing) plan.warTarget else null)
                if (target != null) return StrategicPhase.WarPreparation to target.civID
            }
        }

        if (wantsToExpand(civ)) return StrategicPhase.Expansion to null
        return StrategicPhase.Development to null
    }

    /** A neighbour clearly weaker than us; [current] is kept while it still qualifies */
    private fun findOwnWarTarget(civ: Civilization, current: String?): Civilization? {
        val ourForce = civ.getStatForRanking(RankingType.Force)
        val candidates = civ.threatManager.getNeighboringCivilizations()
            .filter { it.isMajorCiv() && it.isAlive() && it.cities.isNotEmpty() && !civ.isAtWarWith(it) }
            .filter { civ.getDiplomacyManager(it)?.canDeclareWar() == true }
            .filter { it.getStatForRanking(RankingType.Force) < ourForce * 0.5f }
        return candidates.firstOrNull { it.civID == current }
            ?: candidates.minByOrNull { it.getStatForRanking(RankingType.Force) }
    }

    /** The last detailed review's answer to "new city or develop the old ones?" */
    @VisibleForTesting
    fun wantsToExpand(civ: Civilization): Boolean = civ.aiMemory.strategicPlan.expansionWanted

    /** Re-evaluates expansion: whether the next settler pays off better than buildings, and where it should go */
    @VisibleForTesting
    fun updateExpansion(civ: Civilization, plan: StrategicPlan) {
        if (civ.cities.isEmpty() || CivilianUnitAutomation.isLateGame(civ)) {
            plan.expansionWanted = civ.cities.isEmpty()
            plan.settleTarget = null
            plan.settlerBuilder = ""
            plan.expansionNote = if (civ.cities.isEmpty()) "no cities" else "late game"
            return
        }
        val decision = ExpansionEvaluator.evaluate(civ)
        plan.expansionWanted = decision.expand
        plan.settleTarget = if (decision.expand) decision.target?.position else null
        plan.settlerBuilder = if (decision.expand) decision.builder?.id ?: "" else ""
        plan.expansionNote = decision.toString()
    }

    // endregion
}
