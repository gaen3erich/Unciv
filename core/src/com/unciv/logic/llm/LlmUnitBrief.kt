package com.unciv.logic.llm

data class LlmUnitBrief(
    val name: String,
    val healthPercent: Int,
    val isInjured: Boolean,
    val movementLeft: Int
)
