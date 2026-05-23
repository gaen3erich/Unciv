package com.unciv.logic.llm

data class LlmDiplomacy(
    val knownCivs: List<LlmKnownCiv>,
    val activeWars: List<LlmWarStatus>,
    val activeTrades: List<LlmTradeSummary>,
    val threats: LlmThreats,
    val relativeStanding: LlmRelativeStanding
)
