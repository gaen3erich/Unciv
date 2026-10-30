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
        is LlmLegalAction.RunUnitAutomation -> "let the built-in automation move all units that got no order yet"
        is LlmLegalAction.AttackCity -> "send the army to attack ${action.targetCityName} at (${action.location.x},${action.location.y})"
        is LlmLegalAction.DefendCity -> "send the army to defend ${action.cityName}"
        is LlmLegalAction.DeclareWar -> "declare war on ${action.targetCivName}"
        is LlmLegalAction.OfferPeace -> "offer peace to ${action.targetCivName} (they answer on their turn)"
        is LlmLegalAction.FoundCity -> "move ${action.unitName} #${action.unitId} to (${action.location.x},${action.location.y}) and found a city there"
        is LlmLegalAction.EndTurn -> "finish this turn"
    }

    companion object {
        private const val systemPrompt =
            "You play one civilization in Unciv as a strategic agent. " +
                "You receive the current game observation as JSON. " +
                "You MUST respond with a single pick_actions tool call. " +
                "Pick zero or more legal actions by exact id; they are applied in the order given. " +
                "Units are commanded in groups, not one by one, and each unit follows only the first order " +
                "that covers it this turn: found_city moves one settler, attack_city and defend_city move " +
                "the whole remaining army, run_units hands every remaining unit to the built-in automation. " +
                "Units that get no order stay idle, so include run_units every turn before end_turn. " +
                "Choose end_turn to advance the game."

        val toolSchema: String = """
            {"name":"pick_actions","description":"Apply zero or more legal actions to the current game state, in order.","input_schema":{"type":"object","properties":{"actions":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string","description":"Exact id of a legal action"},"kind":{"type":"string"}},"required":["id"]}},"rationale":{"type":"string"}},"required":["actions"]}}
        """.trimIndent()
    }
}
