package com.unciv.logic.llm.client

interface LlmClient {
    val identifier: String
    fun send(request: LlmRequest): LlmResponse
}

open class LlmClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class LlmClientConfigurationException(message: String) : LlmClientException(message)
