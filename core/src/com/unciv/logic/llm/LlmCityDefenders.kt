package com.unciv.logic.llm

data class LlmCityDefenders(
    val garrison: LlmUnitBrief?,
    val supportingUnitCount: Int,
    val supportingUnitTypeBreakdown: Map<String, Int>
)
