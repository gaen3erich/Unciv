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

    /** Hands every unit not yet ordered this step to the built-in unit automation. */
    data class RunUnitAutomation(
        override val id: String = RUN_UNITS_ID
    ) : LlmLegalAction

    /** Military land units not yet ordered this step advance on and attack this enemy city. */
    data class AttackCity(
        override val id: String,
        val targetCivId: String,
        val targetCityId: String,
        val targetCityName: String,
        val location: LlmHexCoord
    ) : LlmLegalAction

    /** Military land units not yet ordered this step move to and hold this own city. */
    data class DefendCity(
        override val id: String,
        val cityId: String,
        val cityName: String
    ) : LlmLegalAction

    data class DeclareWar(
        override val id: String,
        val targetCivId: String,
        val targetCivName: String
    ) : LlmLegalAction

    /** Sends a peace treaty proposal; the other civ answers it on its own turn. */
    data class OfferPeace(
        override val id: String,
        val targetCivId: String,
        val targetCivName: String
    ) : LlmLegalAction

    /** Moves the settler towards one of the top-ranked city sites and founds the city once there. */
    data class FoundCity(
        override val id: String,
        val unitId: Int,
        val unitName: String,
        val location: LlmHexCoord
    ) : LlmLegalAction

    data class EndTurn(
        override val id: String = "end_turn"
    ) : LlmLegalAction

    companion object {
        const val RUN_UNITS_ID = "run_units"
    }
}
