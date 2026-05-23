package com.unciv.logic.llm

sealed interface LlmLegalAction {
    val id: String

    data class ChooseTechnology(
        override val id: String,
        val techName: String,
        val path: List<String> = listOf(techName)
    ) : LlmLegalAction

    data class ChooseCityConstruction(
        override val id: String,
        val cityId: String,
        val cityName: String,
        val constructionName: String,
        val addToTop: Boolean = false
    ) : LlmLegalAction

    data class EndTurn(
        override val id: String = "end_turn"
    ) : LlmLegalAction
}
