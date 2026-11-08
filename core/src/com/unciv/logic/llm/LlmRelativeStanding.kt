package com.unciv.logic.llm

data class LlmRelativeStanding(
    val score: LlmRankingEntry,
    val population: LlmRankingEntry,
    val growth: LlmRankingEntry,
    val production: LlmRankingEntry,
    val gold: LlmRankingEntry,
    val territory: LlmRankingEntry,
    val force: LlmRankingEntry,
    val happiness: LlmRankingEntry,
    val technologies: LlmRankingEntry,
    val culture: LlmRankingEntry
)

data class LlmRankingEntry(
    val ourValue: Int,
    val rank: Int,
    val totalKnownMajors: Int,
    val leaderValue: Int
)
