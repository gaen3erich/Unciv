package com.unciv.logic.llm

data class LlmResources(
    val strategic: List<LlmResourceEntry>,
    val luxury: List<LlmResourceEntry>
)

data class LlmResourceEntry(
    val name: String,
    val amount: Int
)
