package com.unciv.logic.llm

data class LlmWorkableTilesSummary(
    val totalTiles: Int,
    val workedTiles: Int,
    val totalYields: LlmTileYields,
    val resourceCounts: Map<String, Int>,
    val improvementCounts: Map<String, Int>
)

data class LlmTileYields(
    val food: Int,
    val production: Int,
    val gold: Int,
    val science: Int,
    val culture: Int,
    val faith: Int,
    val happiness: Int
)
