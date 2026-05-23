package com.unciv.logic.llm

data class LlmEconomy(
    val gold: Int,
    val storedCulture: Int,
    val storedFaith: Int,
    val happiness: Int,
    val goldPerTurn: Int,
    val sciencePerTurn: Int,
    val culturePerTurn: Int,
    val faithPerTurn: Int,
    // From civ.stats.statsForNextTurn.happiness; not the current happiness level.
    val happinessPerTurn: Int
)
