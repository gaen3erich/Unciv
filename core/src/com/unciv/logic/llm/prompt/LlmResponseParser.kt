package com.unciv.logic.llm.prompt

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.SerializationException
import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.client.LlmResponse

class LlmResponseParser {
    fun parse(response: LlmResponse, observation: LlmObservation): ParseResult {
        response.toolCallJson?.let { toolJson ->
            parseCandidate(toolJson, observation, ParsePath.TOOL_CALL)?.let { return it }
        }

        // Reasoning models (DeepSeek-R1 and the like) may put <think>...</think> before the answer
        val text = response.rawText.replace(THINK_BLOCK, "").substringAfter("</think>")
        for (block in fencedJsonBlocks(text)) {
            parseCandidate(block, observation, ParsePath.FENCED_JSON)?.let { return it }
        }

        balancedTopLevelObject(text)?.let { objectJson ->
            parseCandidate(objectJson, observation, ParsePath.BALANCED_JSON)?.let { return it }
        }

        return ParseResult.Failure("No valid pick_actions JSON found", response.rawText, ParsePath.FAILED)
    }

    private fun parseCandidate(jsonText: String, observation: LlmObservation, path: ParsePath): ParseResult? {
        return try {
            val json = JsonReader().parse(jsonText)
            val actionsNode = json.get("actions") ?: return null
            val aliases = LlmActionAliases.of(observation)
            val actions = actionsNode.map { node ->
                val id = node.getString("id")
                aliases.resolve(id) ?: return ParseResult.Failure("Action id is not legal in current observation: $id", jsonText, path)
            }
            val notes = json.getString("notes", null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(LlmPromptBuilder.NOTES_MAX_CHARS)
            ParseResult.Success(actions, json.getString("rationale", null), path, notes)
        } catch (_: SerializationException) {
            null
        } catch (ex: Exception) {
            ParseResult.Failure("Invalid pick_actions JSON: ${ex.message}", jsonText, path)
        }
    }

    private fun fencedJsonBlocks(text: String): Sequence<String> = sequence {
        var start = text.indexOf("```")
        while (start >= 0) {
            val contentStart = text.indexOf('\n', start + 3).takeIf { it >= 0 } ?: break
            val end = text.indexOf("```", contentStart + 1).takeIf { it >= 0 } ?: break
            val header = text.substring(start + 3, contentStart).trim()
            if (header.isEmpty() || header.equals("json", ignoreCase = true))
                yield(text.substring(contentStart + 1, end).trim())
            start = text.indexOf("```", end + 3)
        }
    }

    private fun balancedTopLevelObject(text: String): String? {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    if (depth > 0) depth--
                    if (depth == 0 && start >= 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private companion object {
        val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    }

    enum class ParsePath {
        TOOL_CALL,
        FENCED_JSON,
        BALANCED_JSON,
        FAILED
    }

    sealed interface ParseResult {
        val parsePath: ParsePath

        data class Success(
            val actions: List<LlmLegalAction>,
            val rationale: String?,
            override val parsePath: ParsePath,
            val notes: String? = null
        ) : ParseResult

        data class Failure(
            val reason: String,
            val rawResponse: String,
            override val parsePath: ParsePath
        ) : ParseResult
    }
}
