package com.unciv.logic.llm

data class LlmKnownCiv(
    val civName: String,
    val isMajorCiv: Boolean,
    val isCityState: Boolean,
    val isDefeated: Boolean,
    val diplomaticStatus: String,
    val relationshipLevel: String,
    val opinionScore: Int?,
    val hasOpenBorders: Boolean,
    val theirsHaveOpenBorders: Boolean,
    val hasDeclarationOfFriendship: Boolean,
    val hasDefensivePact: Boolean,
    val hasResearchAgreement: Boolean,
    val cityStateInfluence: Int?,
    val cityStateAlly: Boolean?
)
