package com.unciv.logic.llm

data class LlmCityHealth(
    val current: Int,
    val max: Int,
    val isUnderAttack: Boolean,
    val isCritical: Boolean
)
