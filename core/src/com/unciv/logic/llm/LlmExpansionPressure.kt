package com.unciv.logic.llm

data class LlmExpansionPressure(
    val cultureStored: Int,
    val turnsToNextTile: Int?,
    val ownedTiles: Int,
    val foreignBorderTiles: Int,
    val nearestForeignCity: LlmNearbyCity?
)

data class LlmNearbyCity(
    val civName: String,
    val cityName: String,
    val distance: Int
)
