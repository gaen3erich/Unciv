package com.unciv.logic.automation.commander

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/** One order from the model. Units, targets and tiles are kept as the model wrote them and resolved at execution */
data class CommanderOrder(
    val unitId: Int,
    /** attack, move, move_toward, fortify, hold */
    val action: String,
    /** Enemy unit id, `city@x,y` (also `city:Name`), or `x,y` */
    val target: String? = null,
    /** `x,y` */
    val tile: String? = null,
    /** For attacks: the tile to attack from, `x,y`; for moves: what to do after arriving, e.g. `fortify` */
    val from: String? = null,
    val then: String? = null,
) {
    override fun toString(): String = buildString {
        append("#$unitId $action")
        if (target != null) append(" target=$target")
        if (tile != null) append(" tile=$tile")
        if (from != null) append(" from=$from")
        if (then != null) append(" then=$then")
    }
}

data class ProductionHint(val city: String, val prefer: List<String>)

class CommanderPlan(
    val intent: String,
    val orders: List<CommanderOrder>,
    val productionHints: List<ProductionHint>,
    /** Problems found while parsing; the plan is still usable when [orders] is not empty */
    val warnings: List<String>,
)

/**
 * Reads the model's answer. Tolerant on purpose: `<think>` blocks, Markdown fences and text around the JSON object are
 * ignored, ids may be numbers or strings like `"#17"` / `"u17"`, and one bad order does not spoil the others.
 */
object CommanderPlanParser {
    val ACTIONS = setOf("attack", "move", "move_toward", "fortify", "hold")

    fun extractJson(answer: String): String? {
        var text = answer.replace(Regex("(?s)<think>.*?</think>"), "")
        // An unterminated <think> means everything after it is thinking
        if (text.contains("<think>")) text = text.substringBefore("<think>")
        val fenced = Regex("(?s)```(?:json)?\\s*(\\{.*?})\\s*```").findAll(text).lastOrNull()
        if (fenced != null) return fenced.groupValues[1]
        // Last balanced top-level object in the text
        var depth = 0
        var start = -1
        var lastStart = -1
        var lastEnd = -1
        var inString = false
        var escaped = false
        for ((i, c) in text.withIndex()) {
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) { depth--; if (depth == 0) { lastStart = start; lastEnd = i } }
            }
        }
        return if (lastStart >= 0) text.substring(lastStart, lastEnd + 1) else null
    }

    fun parse(answer: String): CommanderPlan {
        val json = extractJson(answer) ?: return CommanderPlan("", emptyList(), emptyList(), listOf("no JSON object in the answer"))
        val root = try { JsonReader().parse(json) } catch (e: Exception) {
            return CommanderPlan("", emptyList(), emptyList(), listOf("invalid JSON: ${e.message?.take(200)}"))
        }
        val warnings = ArrayList<String>()
        val intent = root.getString("intent", "") ?: ""
        val orders = ArrayList<CommanderOrder>()
        val ordersJson = root.get("orders")
        if (ordersJson == null || !ordersJson.isArray) warnings += "no orders array"
        else for ((i, o) in ordersJson.withIndex()) {
            val unitId = idOf(o.get("unit"))
            val action = o.getString("action", "")?.trim()?.lowercase()?.replace(' ', '_') ?: ""
            if (unitId == null) { warnings += "order ${i + 1}: no unit id"; continue }
            if (action !in ACTIONS) { warnings += "order ${i + 1}: unknown action '$action'"; continue }
            orders += CommanderOrder(unitId, action,
                target = stringOf(o.get("target")), tile = stringOf(o.get("tile")),
                from = stringOf(o.get("from")), then = stringOf(o.get("then"))?.lowercase())
        }
        val hints = ArrayList<ProductionHint>()
        val hintJson = root.get("production_hint")
        val hintList = when {
            hintJson == null || hintJson.isNull -> emptyList()
            hintJson.isArray -> hintJson.toList()
            else -> listOf(hintJson)
        }
        for (h in hintList) {
            val city = stringOf(h.get("city"))?.removePrefix("city:") ?: continue // a name or city@x,y
            val prefer = h.get("prefer")?.let { p -> if (p.isArray) p.mapNotNull { it.asString() } else listOfNotNull(p.asString()) } ?: emptyList()
            if (prefer.isNotEmpty()) hints += ProductionHint(city, prefer)
        }
        return CommanderPlan(intent, orders, hints, warnings)
    }

    private fun stringOf(value: JsonValue?): String? = when {
        value == null || value.isNull -> null
        value.isArray && value.size == 2 -> "${value.getInt(0)},${value.getInt(1)}"
        else -> value.asString()?.trim()
    }

    private fun idOf(value: JsonValue?): Int? {
        if (value == null || value.isNull) return null
        if (value.isNumber) return value.asInt()
        return value.asString()?.filter { it.isDigit() }?.toIntOrNull()
    }
}
