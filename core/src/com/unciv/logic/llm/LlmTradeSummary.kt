package com.unciv.logic.llm

data class LlmTradeSummary(
    val partnerCivName: String,
    val outgoing: List<LlmTradeOfferBrief>,
    val incoming: List<LlmTradeOfferBrief>,
    val turnsRemainingMin: Int?
)

data class LlmTradeOfferBrief(
    val type: String,
    val name: String,
    val amount: Int
)
