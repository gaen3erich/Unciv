package com.unciv.logic.automation.civilization.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.Victory
import com.unciv.models.stats.Stat
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly

/** What the civilization is mainly busy with right now. Chosen by [StrategicPlanner]. */
enum class StrategicPhase {
    /** Land is free and we have few cities: settlers and growth first */
    Expansion,
    /** No free land or enough cities: buildings, wonders, the victory goal */
    Development,
    /** We intend to attack [StrategicPlan.warTarget]: build up the army before declaring */
    WarPreparation,
    /** At war and holding our own */
    War,
    /** At war and losing, or our cities are under siege: defend and look for peace */
    Defense;

    /** Phases in which the situation changes fast, so the detailed review runs more often */
    @Pure fun isMilitary() = this == WarPreparation || this == War || this == Defense
}

/**
 * Rough kind of a victory type, read from its milestones so that it also works for mod victories.
 * The plan's weights depend on the kind, not on the victory's name.
 */
enum class VictoryKind {
    Science, Culture, Military, Diplomatic, Religion, Other;

    companion object {
        @Readonly
        fun of(victory: Victory): VictoryKind {
            val types = victory.milestoneObjects.map { it.type }
            return when {
                types.any { it == MilestoneType.CaptureAllCapitals || it == MilestoneType.DestroyAllPlayers } -> Military
                types.any { it == MilestoneType.AddedSSPartsInCapital } -> Science
                types.any { it == MilestoneType.CompletePolicyBranches } -> Culture
                types.any { it == MilestoneType.WinDiplomaticVote } -> Diplomatic
                types.any { it == MilestoneType.WorldReligion } -> Religion
                else -> Other
            }
        }
    }
}

/**
 * Multipliers that the plan hands to the existing AI functions.
 *
 * They do not replace any decision, they only tilt it: 1 (or 0 for the additive motivations) means
 * "no opinion". [NEUTRAL] is what every function gets when the plan is switched off.
 */
data class StrategicWeights(
    /** Military unit production */
    val military: Float = 1f,
    /** Defensive buildings (walls, castles) */
    val defense: Float = 1f,
    /** Settlers */
    val expansion: Float = 1f,
    val wonders: Float = 1f,
    val food: Float = 1f,
    val production: Float = 1f,
    val gold: Float = 1f,
    val science: Float = 1f,
    val culture: Float = 1f,
    val faith: Float = 1f,
    val happiness: Float = 1f,
    /** Added to the motivation to attack anyone; negative values favour peace */
    val warMotivation: Float = 0f,
    /** Added to the motivation to attack [StrategicPlan.warTarget] only */
    val targetMotivation: Float = 0f,
) {
    @Readonly
    fun forStat(stat: Stat): Float = when (stat) {
        Stat.Food -> food
        Stat.Production -> production
        Stat.Gold -> gold
        Stat.Science -> science
        Stat.Culture -> culture
        Stat.Faith -> faith
        Stat.Happiness -> happiness
    }

    companion object {
        val NEUTRAL = StrategicWeights()

        @Pure
        fun of(phase: StrategicPhase, goal: VictoryKind): StrategicWeights {
            val base = when (phase) {
                StrategicPhase.Expansion -> StrategicWeights(
                    military = 0.9f, expansion = 1.6f, wonders = 0.6f, food = 1.25f, production = 1.1f,
                    warMotivation = -5f)
                StrategicPhase.Development -> StrategicWeights(
                    military = 0.9f, expansion = 0.7f, wonders = 1.2f, production = 1.1f)
                StrategicPhase.WarPreparation -> StrategicWeights(
                    military = 1.8f, defense = 1.1f, expansion = 0.4f, wonders = 0.6f, production = 1.2f,
                    science = 0.9f, culture = 0.9f, targetMotivation = 4f)
                StrategicPhase.War -> StrategicWeights(
                    military = 1.2f, defense = 1.3f, expansion = 0.3f, wonders = 0.5f, production = 1.1f,
                    warMotivation = -5f, targetMotivation = 0f)
                StrategicPhase.Defense -> StrategicWeights(
                    military = 2.2f, defense = 2f, expansion = 0f, wonders = 0.3f, production = 1.2f,
                    science = 0.8f, culture = 0.8f, warMotivation = -15f)
            }
            return when (goal) {
                VictoryKind.Science -> base.copy(science = base.science * 1.3f)
                VictoryKind.Culture -> base.copy(culture = base.culture * 1.35f, wonders = base.wonders * 1.25f,
                    expansion = base.expansion * 0.85f)
                VictoryKind.Military -> base.copy(military = base.military * 1.1f, production = base.production * 1.1f,
                    warMotivation = base.warMotivation)
                VictoryKind.Diplomatic -> base.copy(gold = base.gold * 1.3f, warMotivation = base.warMotivation - 3f)
                VictoryKind.Religion -> base.copy(faith = base.faith * 1.4f)
                VictoryKind.Other -> base
            }
        }
    }
}

/**
 * The cheap per-turn picture of a civ's situation. The planner compares it with the previous one
 * to decide whether something happened that needs a detailed review before the scheduled one.
 */
class SituationSnapshot : IsPartOfGameInfoSerialization {
    /** civIDs of major civs we are at war with */
    var majorEnemies = HashSet<String>()
    /** civIDs we are secretly preparing to attack (see DeclareWarTargetAutomation.prepareWar) */
    var preparingWarOn = HashSet<String>()
    var cities = 0
    var citiesUnderSiege = 0
    var era = ""
    var unhappy = false

    fun clone(): SituationSnapshot {
        val toReturn = SituationSnapshot()
        toReturn.majorEnemies.addAll(majorEnemies)
        toReturn.preparingWarOn.addAll(preparingWarOn)
        toReturn.cities = cities
        toReturn.citiesUnderSiege = citiesUnderSiege
        toReturn.era = era
        toReturn.unhappy = unhappy
        return toReturn
    }
}

/**
 * A civilization's long-term intent: which victory it goes for ([goal]) and what it is busy with now ([phase]).
 *
 * Kept in [com.unciv.logic.automation.AiMemory] and saved with the game, so the plan survives a reload
 * and does not flip back and forth. Only [StrategicPlanner] changes it; everyone else reads [weights].
 */
class StrategicPlan : IsPartOfGameInfoSerialization {
    /** Name of the victory type we go for; empty until the first review */
    var goal = ""
    var goalKind = VictoryKind.Other
    var goalSinceTurn = 0

    var phase = StrategicPhase.Expansion
    var phaseSinceTurn = 0

    /** civID of the civ we prepare to attack or fight; empty when none */
    var warTarget = ""
    /** We gave up a war preparation of our own and don't start another one before this turn */
    var noOwnWarPlansUntilTurn = 0

    /** Whether the next settler is worth more than developing our cities, see [ExpansionEvaluator] */
    var expansionWanted = false
    /** Where the next city should be founded; settlers head here */
    var settleTarget: HexCoord? = null
    /** id of the city that should build the next settler */
    var settlerBuilder = ""
    /** Numbers behind the last expansion decision, for the dev console */
    var expansionNote = ""

    /** -1 means the plan was never reviewed, so it has no opinion yet */
    var lastReviewTurn = -1
    var nextReviewTurn = 0
    var lastReviewReason = ""

    var snapshot = SituationSnapshot()

    /** Last changes of goal or phase, newest last, for the dev console and debugging */
    var history = ArrayList<String>()

    @Readonly fun isActive() = lastReviewTurn >= 0

    @Readonly fun weights(): StrategicWeights =
        if (isActive()) StrategicWeights.of(phase, goalKind) else StrategicWeights.NEUTRAL

    fun addHistory(entry: String) {
        history.add(entry)
        while (history.size > MAX_HISTORY) history.removeAt(0)
    }

    fun clone(): StrategicPlan {
        val toReturn = StrategicPlan()
        toReturn.goal = goal
        toReturn.goalKind = goalKind
        toReturn.goalSinceTurn = goalSinceTurn
        toReturn.phase = phase
        toReturn.phaseSinceTurn = phaseSinceTurn
        toReturn.warTarget = warTarget
        toReturn.noOwnWarPlansUntilTurn = noOwnWarPlansUntilTurn
        toReturn.expansionWanted = expansionWanted
        toReturn.settleTarget = settleTarget
        toReturn.settlerBuilder = settlerBuilder
        toReturn.expansionNote = expansionNote
        toReturn.lastReviewTurn = lastReviewTurn
        toReturn.nextReviewTurn = nextReviewTurn
        toReturn.lastReviewReason = lastReviewReason
        toReturn.snapshot = snapshot.clone()
        toReturn.history.addAll(history)
        return toReturn
    }

    override fun toString() =
        if (!isActive()) "no plan yet"
        else "goal $goal ($goalKind) since turn $goalSinceTurn, phase $phase since turn $phaseSinceTurn" +
            (if (warTarget.isNotEmpty()) ", target $warTarget" else "") +
            ", expansion: $expansionNote" +
            ", last review turn $lastReviewTurn ($lastReviewReason), next by turn $nextReviewTurn"

    companion object {
        const val MAX_HISTORY = 12
    }
}
