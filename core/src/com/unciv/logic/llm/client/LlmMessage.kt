package com.unciv.logic.llm.client

data class LlmMessage(
    val role: Role,
    val content: String,
    val cacheable: Boolean = false
) {
    enum class Role {
        USER,
        ASSISTANT
    }
}
