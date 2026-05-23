package com.unciv.logic.llm

data class LlmVisibleEnemies(
    val totalCount: Int,
    val closestDistance: Int?,
    val byCiv: List<LlmEnemyCivCount>,
    val brief: List<LlmUnitBrief>
)

data class LlmEnemyCivCount(
    val civName: String,
    val count: Int
)
