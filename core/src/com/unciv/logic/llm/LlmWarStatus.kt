package com.unciv.logic.llm

data class LlmWarStatus(
    val enemyCivName: String,
    val turnsSinceWarDeclaration: Int?,
    val warDeclaredByUs: Boolean,
    val knownEnemyAlliesAtWar: List<String>,
    val canMakePeace: Boolean
)
