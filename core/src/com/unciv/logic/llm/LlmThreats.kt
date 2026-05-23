package com.unciv.logic.llm

data class LlmThreats(
    val warCount: Int,
    val denunciationCount: Int,
    val recentDeclarationsAgainstUs: Int,
    val nearbyHostileMilitaryCount: Int,
    val cityStatesAtWarWithUs: Int
)
