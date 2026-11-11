package com.unciv.logic.automation.commander

import com.badlogic.gdx.utils.JsonReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class CommanderMessage(val role: String, val content: String)

/** What the model said: [content] is the answer, [reasoning] the separate chain of thought some servers return */
class CommanderReply(val content: String, val reasoning: String = "", val elapsedMillis: Long = 0)

/** Anything that turns a conversation about one battle into a plan (see [CommanderPlanParser] for the format) */
interface CommanderModel {
    val name: String
    /** [digest] is the structured form of the battle in the last user message; real models ignore it */
    fun complete(messages: List<CommanderMessage>, digest: BattleDigest): CommanderReply
}

class CommanderModelException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Talks to any OpenAI-compatible `/chat/completions` endpoint: vLLM, llama.cpp server, Ollama, DeepSeek's API.
 * No tool calling is used, so reasoning models such as DeepSeek-R1 work too; `<think>` blocks in the answer are
 * stripped by the parser, and a separate `reasoning_content` field (vLLM `--reasoning-parser`) is kept for the logs.
 */
class OpenAiCompatibleCommanderModel(private val config: CommanderConfig) : CommanderModel {
    override val name: String get() = "${config.model}@${config.baseUrl}"

    override fun complete(messages: List<CommanderMessage>, digest: BattleDigest): CommanderReply {
        val body = buildString {
            append("{\"model\":").append(jsonString(config.model))
            append(",\"temperature\":").append(config.temperature)
            append(",\"max_tokens\":").append(config.maxTokens)
            append(",\"messages\":[")
            messages.forEachIndexed { i, m ->
                if (i > 0) append(',')
                append("{\"role\":").append(jsonString(m.role)).append(",\"content\":").append(jsonString(m.content)).append('}')
            }
            append("]}")
        }
        val url = URI(config.baseUrl.trimEnd('/') + "/chat/completions").toURL()
        val started = System.currentTimeMillis()
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = config.timeoutSeconds * 1000
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (config.apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw CommanderModelException("HTTP $code from $url: ${text.take(500)}")
            return parseResponse(text, System.currentTimeMillis() - started)
        } catch (e: CommanderModelException) {
            throw e
        } catch (e: Exception) {
            throw CommanderModelException("Request to $url failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun parseResponse(text: String, elapsedMillis: Long = 0): CommanderReply {
            val root = try { JsonReader().parse(text) } catch (e: Exception) {
                throw CommanderModelException("Response is not JSON: ${text.take(300)}", e)
            }
            val message = root.get("choices")?.get(0)?.get("message")
                ?: throw CommanderModelException("No choices[0].message in response: ${text.take(300)}")
            val content = message.getString("content", null) ?: ""
            val reasoning = message.getString("reasoning_content", null) ?: message.getString("reasoning", null) ?: ""
            return CommanderReply(content, reasoning, elapsedMillis)
        }
    }
}

fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}

/**
 * Stand-in for a real model, used when no endpoint is configured and in tests.
 *
 * Plays a simple greedy plan from the structured digest, in the same JSON format a model would answer with:
 * attacks that kill first, then the best trades, units in danger to the safest tile, the rest left to the regular AI.
 * It exists to exercise
 * the whole pipeline (prompt, parsing, simulation, execution, logs) without a model, not to play well.
 */
class StubCommanderModel : CommanderModel {
    override val name = "stub"

    override fun complete(messages: List<CommanderMessage>, digest: BattleDigest): CommanderReply {
        val orders = ArrayList<String>()
        val enemyHealth = HashMap<String, Int>()
        for (enemy in digest.enemies) enemyHealth[enemy.unit.id.toString()] = enemy.unit.health
        for (city in digest.engagement.enemyCities) enemyHealth[city.ref()] = city.health
        val usedTiles = HashSet<String>()
        // Ranged first so melee units can finish
        val ordered = digest.own.sortedBy { if (it.unit.baseUnit.isRanged()) 0 else 1 }
        for (u in ordered) {
            val attack = u.attacks
                .filter { !it.attackerDies && (enemyHealth[it.targetRef] ?: 0) > 0 }
                .filter { it.damageTaken * 2 < u.unit.health || it.damageDealt >= (enemyHealth[it.targetRef] ?: 0) }
                .maxByOrNull { (if (it.damageDealt >= (enemyHealth[it.targetRef] ?: 0)) 1000 else 0) + it.damageDealt - it.damageTaken }
            if (attack != null) {
                enemyHealth[attack.targetRef] = (enemyHealth[attack.targetRef] ?: 0) - attack.damageDealt
                orders += "{\"option\": \"${BattleDigestBuilder.attackId(u.unit, u.attacks.indexOf(attack))}\"}"
                continue
            }
            // Units not in danger are left to the regular AI, which knows how to advance and besiege
            if (u.threatHere * 2 < u.unit.health) continue
            val move = u.moves.filter { it.tile.ref() !in usedTiles && it.threat < u.unit.health }
                .maxWithOrNull(compareBy<MoveOption> { -it.threat }.thenBy { it.defence })
            if (move != null && move.kind != "stay") {
                usedTiles += move.tile.ref()
                orders += "{\"option\": \"${BattleDigestBuilder.moveId(u.unit, u.moves.indexOf(move))}\", \"then\": \"fortify\"}"
            }
        }
        val json = "{\n  \"intent\": \"stub: kill what we can, trade well, endangered units step back\",\n  \"orders\": [\n    " +
            orders.joinToString(",\n    ") + "\n  ]\n}"
        return CommanderReply(json)
    }
}
