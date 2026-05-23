package com.unciv.logic.llm.prompt

import com.unciv.logic.llm.LlmLegalAction
import com.unciv.logic.llm.LlmObservation
import com.unciv.logic.llm.client.LlmMessage
import com.unciv.logic.llm.client.LlmRequest
import com.unciv.logic.llm.client.LlmRequestKind
import com.unciv.logic.llm.io.LlmJsonCodec

class LlmPromptBuilder(
    private val maxTokens: Int = 4096,
    private val temperature: Double = 0.0,
    private val codec: LlmJsonCodec = LlmJsonCodec()
) {
    fun build(observation: LlmObservation, civName: String = observation.currentPlayerCivName): LlmRequest {
        return LlmRequest(
            kind = LlmRequestKind.DECISION,
            systemPrompt = systemPrompt,
            messages = listOf(LlmMessage(LlmMessage.Role.USER, buildUserPrompt(observation, civName))),
            toolSchema = toolSchema,
            maxTokens = maxTokens,
            temperature = temperature
        )
    }

    private fun buildUserPrompt(observation: LlmObservation, civName: String): String = buildString {
        appendLine("You are playing $civName. Current turn: ${observation.turn}.")
        appendLine()
        appendLine("Brief state:")
        appendLine("- Research: ${observation.currentResearch ?: "none"} (${observation.sciencePerTurn}/turn)")
        appendLine("- Gold: ${observation.economy.gold} (${signed(observation.economy.goldPerTurn)}/turn)")
        appendLine("- Happiness: ${observation.economy.happiness} (${signed(observation.economy.happinessPerTurn)}/turn)")
        appendLine("- Cities: ${observation.cities.size}")
        appendLine("- Wars: ${observation.diplomacy.threats.warCount}")
        appendLine()
        appendLine("Full observation:")
        appendLine("```json")
        appendLine(codec.encodeObservation(observation))
        appendLine("```")
        appendLine()
        appendLine("Legal actions (pick from these; the id field must match exactly):")
        for (action in observation.legalActions) {
            appendLine("- ${action.id} - ${describe(action)}")
        }
        appendLine()
        appendLine("Call pick_actions with the actions you want to apply this turn.")
    }

    private fun signed(value: Int) = if (value >= 0) "+$value" else value.toString()

    private fun describe(action: LlmLegalAction): String = when (action) {
        is LlmLegalAction.ChooseTechnology -> "research ${action.techName}"
        is LlmLegalAction.ChooseCityConstruction -> "build ${action.constructionName} in ${action.cityName}"
        is LlmLegalAction.EndTurn -> "finish this turn"
    }

    companion object {
        private const val systemPrompt =
            "You play one civilization in Unciv as a strategic agent. " +
                "You receive the current game observation as JSON. " +
                "You MUST respond with a single pick_actions tool call. " +
                "Pick zero or more legal actions by exact id. Choose end_turn to advance the game."

        val toolSchema: String = """
            {"name":"pick_actions","description":"Apply zero or more legal actions to the current game state.","input_schema":{"type":"object","properties":{"actions":{"type":"array","items":{"oneOf":[{"type":"object","properties":{"kind":{"const":"ChooseTechnology"},"id":{"type":"string"},"techName":{"type":"string"},"path":{"type":"array","items":{"type":"string"}}},"required":["kind","id","techName"]},{"type":"object","properties":{"kind":{"const":"ChooseCityConstruction"},"id":{"type":"string"},"cityId":{"type":"string"},"cityName":{"type":"string"},"constructionName":{"type":"string"},"addToTop":{"type":"boolean"}},"required":["kind","id","cityId","cityName","constructionName"]},{"type":"object","properties":{"kind":{"const":"EndTurn"},"id":{"const":"end_turn"}},"required":["kind","id"]}]}},"rationale":{"type":"string"}},"required":["actions"]}}
        """.trimIndent()
    }
}
