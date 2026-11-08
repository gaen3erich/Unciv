# LLM Agent — Stage A: Stateless Single-Model MVP

Stage A is the **minimum viable LLM agent**. Goal: close the loop `observation → LLM → action → applier → next turn` end-to-end, on a real save, with traces. Stateless, no plan, no memory, no router, no caching layer of our own. Two providers supported from day one: Anthropic (baseline) and any OpenAI-compatible endpoint (so we can stress-test the prompt/parser against open-weight models hosted on OpenRouter / Together / Fireworks / Groq / local Ollama). Everything we learn from running Stage A informs Stage B (multi-tier with static lore, plans, working notes, prompt caching).

Stage B is **strictly gated by Stage A acceptance**. We do not start writing Stage B types or scaffolding until Stage A is running on real saves, traces have been reviewed, and the H1–H6 hypotheses in section 11 have data on them. The discipline matters: Stage B's value is in fixing the right thing, and "the right thing" is unknowable until we see Stage A behavior.

Non-goal of Stage A: be smart. We expect the agent to play badly. We want to know **what kind of bad** so Stage B fixes the right thing.

## 1. Архитектурная позиция

Three boundaries to fix upfront — getting them wrong now means rewriting more of Stage B than necessary.

**Boundary 1: `core` stays HTTP-free.** Today `core/build.gradle.kts` has zero external dependencies, and Android compiles from the same module. We must not introduce OkHttp, Ktor, or any HTTP library into `core`. The `LlmClient` interface lives in `core`; the Anthropic HTTP implementation lives in `desktop` (or a new `desktop`-only sub-module). The CLI wires them up.

**Boundary 2: Synchronous Policy API stays.** `LlmPolicy.chooseActions` is synchronous. The HTTP call to Claude is a blocking call inside the policy implementation. We do not introduce coroutines or callbacks into `LlmGateway`. This is the same constraint Stage B will keep — coroutine-thread-pool / parallel civs is a separate decision we defer.

**Boundary 3: Stage B extension points exist as types, not as code.** `LlmClient` interface has a single method that already accepts a list of messages with a "cacheable prefix" marker — so Stage B's prompt caching is a flag on the call, not a redesign of the type. Similarly, `LlmRequestKind` enum exists in Stage A with one value (`DECISION`); Stage B adds `PLAN_REFRESH`, `STATIC_LORE_DIGEST`, etc., without touching the interface.

**Boundary 4: Agent configuration is external, not in CLI flags.** All provider-specific knobs (which client, which model, base URL, temperature, max tokens, timeouts, retries, headers, fallback, trace verbosity) live in a JSON config file under `configs/llm-agent/`. CLI takes `--config <path>` and otherwise stays focused on the run itself (save, steps, output). This keeps Stage B additions (router maps, plan TTLs, refresh triggers) inside the same config file as nested objects — without ever growing the CLI surface. Reproducibility comes for free: the config path and its SHA256 land in the JSONL trace header, so any trace can be retraced to the exact settings that produced it.

## 2. Предлагаемые файлы/классы

Core (new, HTTP-free):

```text
core/src/com/unciv/logic/llm/client/LlmClient.kt
core/src/com/unciv/logic/llm/client/LlmMessage.kt
core/src/com/unciv/logic/llm/client/LlmRequest.kt
core/src/com/unciv/logic/llm/client/LlmResponse.kt
core/src/com/unciv/logic/llm/client/LlmRequestKind.kt
core/src/com/unciv/logic/llm/client/FakeLlmClient.kt
core/src/com/unciv/logic/llm/prompt/LlmPromptBuilder.kt
core/src/com/unciv/logic/llm/prompt/LlmResponseParser.kt
core/src/com/unciv/logic/llm/policy/RealLlmPolicy.kt
core/src/com/unciv/logic/llm/policy/LlmPolicyFallback.kt
core/src/com/unciv/logic/llm/config/LlmAgentConfig.kt
core/src/com/unciv/logic/llm/config/LlmAgentConfigLoader.kt
core/src/com/unciv/logic/llm/config/LlmAgentConfigFactory.kt
```

Configs (new, under version control):

```text
configs/llm-agent/anthropic-sonnet-baseline.json
configs/llm-agent/openrouter-llama-3.3-stress.json
configs/llm-agent/ollama-local.json
configs/llm-agent/fake-replay-smoke.json
```

Desktop (new, HTTP-allowed):

```text
desktop/src/com/unciv/app/desktop/llm/AnthropicLlmClient.kt
desktop/src/com/unciv/app/desktop/llm/AnthropicConfig.kt
desktop/src/com/unciv/app/desktop/llm/OpenAiCompatibleLlmClient.kt
desktop/src/com/unciv/app/desktop/llm/OpenAiCompatibleConfig.kt
```

`OpenAiCompatibleLlmClient` is a single implementation that talks to any OpenAI `/v1/chat/completions`-style endpoint. It covers OpenAI proper, OpenRouter, Together AI, Fireworks, Groq, and local Ollama (which exposes an OpenAI-compatible API). The provider differences are in URL + model name + auth header, not in protocol.

Изменения в существующих файлах:

```text
core/src/com/unciv/logic/llm/io/LlmTraceRecord.kt        (добавить llmCall: LlmCallTrace?)
core/src/com/unciv/logic/llm/io/LlmJsonCodec.kt           (сериализация llmCall)
core/src/com/unciv/logic/llm/runner/LlmRunnerConfig.kt   (добавить PolicyKind.LLM)
core/src/com/unciv/logic/llm/runner/LlmGatewayRunner.kt  (wiring под PolicyKind.LLM)
desktop/src/com/unciv/app/desktop/LlmGatewayCli.kt       (новые args: --client, --model, --api-key-env)
desktop/build.gradle.kts                                  (—; полагаемся на JDK java.net.http)
```

Tests:

```text
tests/src/com/unciv/logic/llm/client/FakeLlmClientTests.kt
tests/src/com/unciv/logic/llm/prompt/LlmPromptBuilderTests.kt
tests/src/com/unciv/logic/llm/prompt/LlmResponseParserTests.kt
tests/src/com/unciv/logic/llm/policy/RealLlmPolicyTests.kt
tests/src/com/unciv/logic/llm/policy/RealLlmPolicyFallbackTests.kt
tests/src/com/unciv/logic/llm/config/LlmAgentConfigLoaderTests.kt
tests/src/com/unciv/logic/llm/config/LlmAgentConfigFactoryTests.kt
```

No tests for `AnthropicLlmClient` in `tests/` — it would require network or a mock HTTP server. Manual smoke is the verification path; see section 9.

## 3. Class responsibilities

### `LlmClient` (core, interface)

```kotlin
interface LlmClient {
    fun send(request: LlmRequest): LlmResponse
    val identifier: String   // "anthropic:claude-sonnet-4-5", "fake:scripted", etc. — for trace metadata
}
```

Single synchronous method. No streaming. No coroutines. Errors thrown as typed exceptions (`LlmClientException` hierarchy in the same file). Implementations decide their own retry semantics; the interface does not mandate any.

### `LlmRequest` / `LlmResponse` / `LlmMessage` / `LlmRequestKind`

```kotlin
data class LlmRequest(
    val kind: LlmRequestKind,             // DECISION in Stage A; PLAN_REFRESH / etc. in Stage B
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    val toolSchema: String?,              // JSON schema for the expected tool call; null means free text
    val maxTokens: Int,
    val temperature: Double               // 0.0 in Stage A
)

data class LlmMessage(
    val role: Role,                       // USER | ASSISTANT
    val content: String,
    val cacheable: Boolean = false        // hint for Stage B prompt caching; ignored in Stage A
)

data class LlmResponse(
    val rawText: String,                  // full text content from the model
    val toolCallJson: String?,            // if tool_use was returned, the raw JSON of the input
    val stopReason: String,               // model-reported stop reason ("end_turn", "tool_use", ...)
    val usageInputTokens: Int,
    val usageOutputTokens: Int,
    val usageCacheReadTokens: Int = 0,    // populated only when provider supports it; Stage A always 0
    val latencyMs: Long
)

enum class LlmRequestKind { DECISION }    // Stage B will extend
```

`cacheable` and `usageCacheReadTokens` are placeholders that Stage A's Anthropic client populates trivially (always 0 / always false), but they exist in the type so Stage B doesn't need to break the contract.

### `FakeLlmClient` (core, test-only-style but lives in main for runner reuse)

Returns canned responses indexed by request signature or by call count. Two construction modes:

- `FakeLlmClient.byCallIndex(listOf(LlmResponse(...), ...))` — returns the Nth response on the Nth call. Throws if exhausted.
- `FakeLlmClient.fromTranscript(file)` — reads a previously recorded JSONL transcript of responses (one per line, in the same `LlmResponse` shape). For deterministic replay tests.

`identifier = "fake:byCallIndex"` or `"fake:transcript:<filename>"`.

### `LlmPromptBuilder` (core)

```kotlin
class LlmPromptBuilder {
    fun build(observation: LlmObservation, civName: String): LlmRequest
}
```

Stage A prompt is simple and uncached:

- `systemPrompt` (~1-2 KB): role definition, the rules of play, the output format requirement (tool_use).
- One `USER` message containing:
  - Brief turn summary (civName, turn, era if cheap to compute, current research, gold, happiness).
  - Full `observation` JSON dump.
  - `legalActions` list with each action's `id`, `kind`, and human-readable description.
- No assistant messages (stateless).
- `toolSchema`: JSON schema matching `LlmLegalAction` sealed hierarchy. The schema is **the single point of truth** about what action shapes are valid. Generated from the sealed class via hand-written builder, not reflection.

Token estimate: 30-50 KB of JSON for a mid-game observation + 10-30 KB for full `legalActions` list. At ~4 chars/token that's roughly 10-20K input tokens per turn. With Sonnet at current pricing this is ~$0.03-0.06 per turn. A 200-turn game ~$6-12. Acceptable for Stage A experimentation; the very thing Stage B prompt caching will fix.

### `LlmResponseParser` (core)

```kotlin
class LlmResponseParser {
    fun parse(response: LlmResponse, observation: LlmObservation): ParseResult

    sealed interface ParseResult {
        data class Success(val actions: List<LlmLegalAction>) : ParseResult
        data class Failure(val reason: String, val rawResponse: String) : ParseResult
    }
}
```

Parsing path, tried in order — first success wins:

1. If `response.toolCallJson != null` and parses as a valid `pick_actions` input — use it.
2. Else, scan `rawText` for a fenced JSON code block (between triple backticks). First fenced block that parses wins.
3. Else, scan `rawText` for the first **balanced top-level `{...}` object** that parses as JSON and looks like a `pick_actions` input (has an `actions` array). This is the "open-source models often return raw JSON without a fence or without proper tool_use" branch.
4. Else, return `Failure`.

Branch 3 is what makes Stage A usable against open-weight models. Anthropic almost always goes via branch 1. Llama / Qwen / DeepSeek through OpenRouter often go via branch 2 (if instructed to wrap in code fence) or branch 3 (if they decided not to). The parser does not need to know which provider it's talking to — it just tries paths in cost-ascending order.

Validation: every parsed action must match by `id` to an entry in `observation.legalActions`. Mismatched `id` → `Failure`. The parser does NOT re-derive legality from `GameInfo` — that's the gateway's job. Parser only checks shape and ID-presence.

What we do NOT do in Stage A:

- Retry with a "your previous response was malformed" feedback message. This is a Stage B technique once we know how often each branch fires on each provider. Stage A goes straight to fallback.
- Fuzzy match action IDs (e.g. "Pottery" vs "choose_technology:Pottery"). Mismatched ID is a hard failure; the prompt and schema must be tight enough that this rarely happens.

### `RealLlmPolicy` (core)

```kotlin
class RealLlmPolicy(
    private val client: LlmClient,
    private val promptBuilder: LlmPromptBuilder = LlmPromptBuilder(),
    private val parser: LlmResponseParser = LlmResponseParser(),
    private val fallback: LlmPolicyFallback = LlmPolicyFallback.EndTurnOnly
) : LlmPolicy {
    override fun chooseActions(observation: LlmObservation): List<LlmLegalAction> {
        val request = promptBuilder.build(observation, observation.currentPlayerCivName)
        val response = try {
            client.send(request)
        } catch (e: LlmClientException) {
            return fallback.onClientError(observation, e)
        }
        return when (val result = parser.parse(response, observation)) {
            is ParseResult.Success -> result.actions
            is ParseResult.Failure -> fallback.onParseFailure(observation, response, result.reason)
        }
    }

    // Optionally exposes the most recent LlmCallTrace for the runner to record
    fun lastCallTrace(): LlmCallTrace?
}
```

### `LlmPolicyFallback` (core)

```kotlin
sealed interface LlmPolicyFallback {
    fun onClientError(observation: LlmObservation, e: LlmClientException): List<LlmLegalAction>
    fun onParseFailure(observation: LlmObservation, response: LlmResponse, reason: String): List<LlmLegalAction>

    object EndTurnOnly : LlmPolicyFallback {
        override fun onClientError(observation, e) = listOf(LlmLegalAction.EndTurn())
        override fun onParseFailure(observation, response, reason) = listOf(LlmLegalAction.EndTurn())
    }

    object Empty : LlmPolicyFallback { ... returns emptyList(), letting gateway record nothing as applied ... }
}
```

Default is `EndTurnOnly`: if Claude is unreachable or returns garbage, we end the turn and move on. This makes a runaway CLI useful even with intermittent failures, and a degenerate-policy detector in the runner can still stop early if too many consecutive turns ended without other actions.

### `AnthropicLlmClient` (desktop)

JDK-only HTTP via `java.net.http.HttpClient` (Java 11+, our desktop module already targets Java 21 for `sourceCompatibility`). No external dependency added.

- Endpoint: `https://api.anthropic.com/v1/messages`.
- Headers: `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`.
- Body: `{model, system, messages, tools, tool_choice, max_tokens, temperature}`.
- `tool_choice` set to force the tool when `request.toolSchema != null`, encouraging a `tool_use` stop reason.

Configuration (`AnthropicConfig`): `apiKey: String`, `model: String` (default `"claude-sonnet-4-5"`), `baseUrl: String` (default the production URL, override for local mock servers in manual testing), `timeoutSeconds: Int` (default 60), `maxRetries: Int` (default 2 with exponential backoff on 429 / 5xx only).

Key from `--api-key-env ANTHROPIC_API_KEY` CLI arg → `System.getenv(...)`. Never logged. The Anthropic client identifier in traces is `"anthropic:${model}"` — no key, ever.

### `OpenAiCompatibleLlmClient` (desktop)

Same JDK-only HTTP path. Targets the OpenAI `/v1/chat/completions` protocol, which OpenAI, OpenRouter, Together AI, Fireworks, Groq, and local Ollama all expose.

- Endpoint: `{baseUrl}/chat/completions`. The full URL is configurable so any of the above hosts works.
- Headers: `Authorization: Bearer ${apiKey}`, `Content-Type: application/json`. Some providers (OpenRouter) also benefit from optional `HTTP-Referer` / `X-Title` for analytics — supported via `OpenAiCompatibleConfig.extraHeaders` but not required.
- Body: `{model, messages, tools, tool_choice, max_tokens, temperature}`. `messages` uses the OpenAI shape: `[{role: "system", content: ...}, {role: "user", content: ...}]`. `tools` uses OpenAI's function-tool shape.
- `tool_choice: {"type": "function", "function": {"name": "pick_actions"}}` to force the tool. Some open-weight models ignore this anyway — that's why the parser has branches 2 and 3.

Configuration (`OpenAiCompatibleConfig`): `baseUrl: String` (e.g. `"https://openrouter.ai/api/v1"`, `"https://api.together.xyz/v1"`, `"http://localhost:11434/v1"` for Ollama), `apiKey: String` (may be empty for Ollama local), `model: String` (e.g. `"meta-llama/llama-3.3-70b-instruct"` on OpenRouter), `timeoutSeconds: Int` (default 120 — open-weight inference can be slower), `maxRetries: Int` (default 2 on 429/5xx), `extraHeaders: Map<String, String>` (default empty).

Identifier in traces: `"openai-compat:${host}:${model}"` where `host` is the hostname extracted from `baseUrl`. Example: `"openai-compat:openrouter.ai:meta-llama/llama-3.3-70b-instruct"`. This lets us diff trace metadata across providers without writing custom serialization per provider.

Provider-specific differences we explicitly **do not** abstract over in Stage A:

- Token usage field naming. OpenAI returns `usage.prompt_tokens` / `usage.completion_tokens`. We map to `usageInputTokens` / `usageOutputTokens` and accept the rare provider that reports differently as "0 tokens" rather than fail. A Stage B follow-up can normalize properly.
- Streaming. Not used.
- Cached input tokens (OpenAI now reports `usage.prompt_tokens_details.cached_tokens` on cache hits). Map to `usageCacheReadTokens` if present, otherwise 0.

### `LlmCallTrace`

```kotlin
data class LlmCallTrace(
    val clientId: String,
    val requestKind: String,            // "DECISION" in Stage A
    val systemPromptHash: String,       // sha256 prefix for diffing across runs
    val userPromptHash: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val latencyMs: Long,
    val stopReason: String,
    val parsedSuccessfully: Boolean,
    val fallbackUsed: String?           // "client_error" | "parse_failure" | null
)
```

Persisted in the JSONL trace per step (new optional field `llmCall` on `LlmTraceRecord`). Hashes (not full prompts) by default — prompts are big and noisy in traces. A CLI flag `--include-prompts` enables full bodies for debugging.

## 4. Agent configuration file

All agent settings live in a JSON file under `configs/llm-agent/`. The CLI references it via `--config <path>`. The file is the single source of truth — there are no CLI overrides in Stage A (a future stage can add `--config-override key=value` if it proves needed; not now, to keep one execution path).

### Schema

```json
{
  "schema": "llm-agent-config/v1",
  "name": "human-readable identifier, no spaces (e.g. anthropic-sonnet-baseline)",
  "description": "optional free-text, one or two sentences",

  "client": {
    "kind": "anthropic | openai-compat | fake",
    "apiKeyEnv": "ANTHROPIC_API_KEY",
    "model": "claude-sonnet-4-5",
    "baseUrl": null,
    "timeoutSeconds": 60,
    "maxRetries": 2,
    "extraHeaders": {},
    "transcriptPath": null
  },

  "request": {
    "temperature": 0.0,
    "maxTokens": 4096
  },

  "policy": {
    "kind": "llm",
    "fallback": "end_turn_only | empty",
    "failOnLlmErrorAfterConsecutive": 5
  },

  "trace": {
    "includePrompts": false,
    "includeRationale": true
  }
}
```

Per-field rules:

- `schema` — must equal `"llm-agent-config/v1"` exactly. Mismatch → load fails with a clean error. v2 is reserved for Stage B additions.
- `name` — used in trace header and CLI summary. Forbidden characters: spaces and `/`. Required.
- `client.kind` — switches between Anthropic, OpenAI-compatible HTTP, and the in-memory fake. All other `client.*` fields are interpreted per kind:
  - `anthropic`: `apiKeyEnv` required, `model` required, `baseUrl` optional (defaults to production URL), `transcriptPath` ignored.
  - `openai-compat`: `apiKeyEnv` required, `model` required, `baseUrl` required, `extraHeaders` optional (e.g. OpenRouter analytics), `transcriptPath` ignored.
  - `fake`: `transcriptPath` required, all other fields ignored.
- `apiKeyEnv` is the NAME of the environment variable, not the key itself. The key is **never** in the config. The loader resolves the key in runtime via `System.getenv(name)`. If the env var is missing, load fails.
- `request.temperature` — default 0.0; floats up to 2.0 accepted but anything above 1.0 is uncommon for decision-making.
- `request.maxTokens` — caps the model's output. 4096 covers a generous rationale + tool call. Provider may cap lower; client honors provider's cap silently.
- `policy.fallback` — what to do when the LLM call fails or the response can't be parsed. `end_turn_only` returns `[EndTurn]`; `empty` returns `[]` (gateway applies nothing, runner's degenerate-policy detector may stop the run).
- `policy.failOnLlmErrorAfterConsecutive` — abort the run with exit code 4 if this many client errors fire back-to-back. Set to 0 to disable.
- `trace.includePrompts` — when true, the full prompt body lands in `LlmCallTrace`. Otherwise only SHA256 hashes. Default false to keep trace size sane.
- `trace.includeRationale` — when true, the `rationale` returned by the model is kept in `LlmCallTrace`. Default true; rationale is what makes traces human-readable.

### Example: Anthropic baseline

`configs/llm-agent/anthropic-sonnet-baseline.json`:

```json
{
  "schema": "llm-agent-config/v1",
  "name": "anthropic-sonnet-baseline",
  "description": "Default Stage A config. Claude Sonnet 4.5, deterministic temperature, EndTurn fallback.",
  "client": {
    "kind": "anthropic",
    "apiKeyEnv": "ANTHROPIC_API_KEY",
    "model": "claude-sonnet-4-5",
    "timeoutSeconds": 60,
    "maxRetries": 2
  },
  "request": { "temperature": 0.0, "maxTokens": 4096 },
  "policy": { "kind": "llm", "fallback": "end_turn_only", "failOnLlmErrorAfterConsecutive": 5 },
  "trace": { "includePrompts": false, "includeRationale": true }
}
```

### Example: OpenRouter open-weight

`configs/llm-agent/openrouter-llama-3.3-stress.json`:

```json
{
  "schema": "llm-agent-config/v1",
  "name": "openrouter-llama-3.3-stress",
  "description": "Stress-test the prompt and parser against Llama 3.3 70B via OpenRouter.",
  "client": {
    "kind": "openai-compat",
    "apiKeyEnv": "OPENROUTER_API_KEY",
    "model": "meta-llama/llama-3.3-70b-instruct",
    "baseUrl": "https://openrouter.ai/api/v1",
    "timeoutSeconds": 120,
    "maxRetries": 2,
    "extraHeaders": {
      "HTTP-Referer": "https://github.com/your/unciv-fork",
      "X-Title": "Unciv LLM Agent"
    }
  },
  "request": { "temperature": 0.0, "maxTokens": 4096 },
  "policy": { "kind": "llm", "fallback": "end_turn_only", "failOnLlmErrorAfterConsecutive": 5 },
  "trace": { "includePrompts": false, "includeRationale": true }
}
```

### Example: Local Ollama

`configs/llm-agent/ollama-local.json`:

```json
{
  "schema": "llm-agent-config/v1",
  "name": "ollama-local",
  "description": "Local Ollama on http://localhost:11434, OpenAI-compatible endpoint.",
  "client": {
    "kind": "openai-compat",
    "apiKeyEnv": "OLLAMA_API_KEY",
    "model": "llama3.3",
    "baseUrl": "http://localhost:11434/v1",
    "timeoutSeconds": 300,
    "maxRetries": 1
  },
  "request": { "temperature": 0.0, "maxTokens": 4096 },
  "policy": { "kind": "llm", "fallback": "end_turn_only", "failOnLlmErrorAfterConsecutive": 5 },
  "trace": { "includePrompts": false, "includeRationale": true }
}
```

`OLLAMA_API_KEY` is typically empty for local Ollama, but the loader still requires the env var to be set (even to an empty string). This forces an explicit "yes I know I'm not authenticating" rather than a silent skip.

### Example: Fake replay

`configs/llm-agent/fake-replay-smoke.json`:

```json
{
  "schema": "llm-agent-config/v1",
  "name": "fake-replay-smoke",
  "description": "Replay a recorded transcript through FakeLlmClient. No network. Used by CI and dev loop.",
  "client": {
    "kind": "fake",
    "transcriptPath": "tests/fixtures/llm-transcripts/sonnet-10-turns.jsonl"
  },
  "request": { "temperature": 0.0, "maxTokens": 4096 },
  "policy": { "kind": "llm", "fallback": "end_turn_only", "failOnLlmErrorAfterConsecutive": 5 },
  "trace": { "includePrompts": false, "includeRationale": true }
}
```

### Loader

`LlmAgentConfigLoader.load(path: Path): LlmAgentConfig`:

1. Read file as UTF-8 JSON.
2. Compute SHA256 of the raw bytes (used in trace header).
3. Parse via libgdx `json()` into a `LlmAgentConfig` data class.
4. Validate `schema` version matches.
5. Validate per-kind required fields.
6. Resolve `client.apiKeyEnv` via `System.getenv(name)`. If missing AND the client kind requires a key — load fails.
7. Returns `LlmAgentConfig` plus a `configSourceMetadata: ConfigSourceMetadata(path, sha256, name)` for trace-side recording.

### Factory

`LlmAgentConfigFactory.buildPolicy(config, runtime): LlmPolicy`:

- Constructs the `LlmClient` (Anthropic, OpenAI-compatible, or Fake) from `config.client`.
- Constructs the `LlmPromptBuilder` and `LlmResponseParser`.
- Constructs the `LlmPolicyFallback` per `config.policy.fallback`.
- Returns a fully wired `RealLlmPolicy`.

`runtime` is a small holder for things the config can't carry (HTTP-client factory, clock, system env reader) — useful for testing the factory without touching the network or real env.

### Trace header changes

`LlmTraceWriter` now includes an `agentConfig` block in the header line:

```json
{
  "schema": "llm-trace/v1",
  "type": "header",
  "agentConfig": {
    "path": "configs/llm-agent/anthropic-sonnet-baseline.json",
    "sha256": "9c1a4f3a...",
    "name": "anthropic-sonnet-baseline",
    "clientKind": "anthropic",
    "model": "claude-sonnet-4-5"
  },
  ...
}
```

API key and `apiKeyEnv` name are NOT written into the header. `apiKeyEnv` is in the config file; tracking the env-var name in the trace would couple the trace to a specific machine setup without adding diagnostic value.

## 5. Prompt shape (Stage A)

```
[system]
You play one civilization in Unciv as a strategic agent. You will receive
the current game observation as JSON. You MUST respond with a single
tool_use call to `pick_actions`. Do not output prose unless explicitly
asked. Pick zero or more legal actions. Choose `end_turn` to advance the
game when you don't want to make other changes this turn.

[user]
You are playing {civName}. Current turn: {turn}.

Brief state:
- Research: {currentResearch ?: "none"} ({sciencePerTurn}/turn)
- Gold: {economy.gold} ({economy.goldPerTurn:+}/turn)
- Happiness: {economy.happiness} ({economy.happinessPerTurn:+}/turn)
- Cities: {cities.size}
- Wars: {diplomacy.threats.warCount}

Full observation:
```json
{observation as JSON}
```

Legal actions (pick from these; the `id` field must match exactly):
- choose_technology:Pottery — research Pottery
- choose_city_construction:Babylon:Monument — build Monument in Babylon
- ... (one line per legal action)
- end_turn — finish this turn

Call `pick_actions` with the actions you want to apply this turn.
```

Tool schema (passed in `tools` field of Anthropic request):

```json
{
  "name": "pick_actions",
  "description": "Apply zero or more legal actions to the current game state.",
  "input_schema": {
    "type": "object",
    "properties": {
      "actions": {
        "type": "array",
        "items": {
          "oneOf": [
            {"type": "object", "properties": {"kind": {"const": "ChooseTechnology"}, "id": {"type": "string"}, "techName": {"type": "string"}, "path": {"type": "array", "items": {"type": "string"}}}, "required": ["kind", "id", "techName"]},
            {"type": "object", "properties": {"kind": {"const": "ChooseCityConstruction"}, "id": {"type": "string"}, "cityId": {"type": "string"}, "cityName": {"type": "string"}, "constructionName": {"type": "string"}, "addToTop": {"type": "boolean"}}, "required": ["kind", "id", "cityId", "cityName", "constructionName"]},
            {"type": "object", "properties": {"kind": {"const": "EndTurn"}, "id": {"const": "end_turn"}}, "required": ["kind", "id"]}
          ]
        }
      },
      "rationale": {"type": "string", "description": "5-6 sentences explaining: (1) what you think the current strategic situation is, (2) what your goal is for the next several turns, (3) why this particular set of actions advances that goal, (4) what you are NOT doing this turn and why it's lower priority, (5) any threats or opportunities you're tracking that didn't change your action this turn but might soon, (6) what you'll look for next turn to decide whether to continue or pivot. This is captured in the trace for human review and does not feed back into future turns in Stage A."}
    },
    "required": ["actions"]
  }
}
```

`rationale` is captured into the JSONL trace as `llmCall.rationale` (a Stage A diagnostic). It's never sent back to the model in subsequent turns (Stage A is stateless). The 5-6 sentence requirement is deliberate: shorter rationales tend to be post-hoc justifications ("I picked Pottery because it's important"), longer ones force the model to expose what it's actually tracking — strategic frame, current goal, what it explicitly de-prioritized, what to watch next turn. This is exactly the material Stage B's plan layer will be built from, so we want a clear sample of it in Stage A traces.

The output-token cost of a 5-6 sentence rationale is ~150-250 tokens. At Sonnet pricing that's ~$0.003 per turn, ~$0.60 per 200-turn game. Easily worth it for trace readability.

## 6. CLI extensions

New CLI args (additive over the existing CLI plan):

- `--policy llm` — selects `RealLlmPolicy`. Existing `random` and `end_turn_only` still work and ignore `--config`.
- `--config <path>` — required when `--policy llm`. Path to an agent config JSON file as specified in section 4.

That's it. The provider, model, base URL, key env var, temperature, max tokens, timeouts, retries, headers, fallback strategy, and trace verbosity all live in the config file. There are no per-flag overrides in Stage A — one execution path, one source of truth.

Examples:

```bash
# Anthropic Sonnet baseline
./gradlew :desktop:llmGatewayCli --args="--save MySave.unciv --steps 50 --policy llm --config configs/llm-agent/anthropic-sonnet-baseline.json --out trace-anthropic.jsonl"

# Open-weight via OpenRouter (Llama 3.3 70B)
./gradlew :desktop:llmGatewayCli --args="--save MySave.unciv --steps 50 --policy llm --config configs/llm-agent/openrouter-llama-3.3-stress.json --out trace-llama.jsonl"

# Local Ollama
./gradlew :desktop:llmGatewayCli --args="--save MySave.unciv --steps 5 --policy llm --config configs/llm-agent/ollama-local.json --out trace-local.jsonl"

# Fake replay (no network, used by dev loop and CI)
./gradlew :desktop:llmGatewayCli --args="--save MySave.unciv --steps 5 --policy llm --config configs/llm-agent/fake-replay-smoke.json --out trace-fake.jsonl"
```

Exit code behavior extended:
- `4` — `LlmClientException` repeated > N (default 5) consecutive steps. Run stopped to avoid burning budget on a broken connection. `--fail-on-llm-error` flag toggles this from "abort" (default) to "continue with fallback".

## 7. Что НЕ делаем в Stage A (заранее зафиксировано)

- **No streaming.** Single blocking `send()`. Streaming requires a different `LlmClient` shape and we don't need partial responses.
- **No prompt caching.** Anthropic supports `cache_control: ephemeral` markers but Stage A doesn't have a static prefix worth caching (full observation goes in each turn, observations differ each turn). Stage B introduces a static lore prefix that's the right target for caching.
- **No multi-model routing.** One `LlmClient` instance, one model, all turn types.
- **No plans, no memory, no working notes.** Each `chooseActions` call is independent.
- **No retry on parse failure.** If Claude returns malformed output, we fall back to `EndTurn`. We do NOT call Claude again with "your previous response was malformed". That's a Stage B technique once we know how often it actually happens.
- **No cost ceiling.** Stage A trusts the user-driven `--steps N` to bound spend. A `--max-cost-usd` flag is a Stage B nicety.
- **No parallel civs.** One civ, current player, same as the rest of the gateway today.
- **No save format changes.** No new `Civilization` fields, no new `GameInfo` fields. All Stage A state is process-local and trace-side.
- **No `core` HTTP dependency.** Repeating because this is the most likely violation to slip in.

## 8. Тесты

`FakeLlmClientTests`:
- `byCallIndex` returns the Nth response on the Nth call.
- Exhaustion throws a clear `IllegalStateException` rather than returning silent nulls.
- `fromTranscript` reads JSONL correctly and round-trips.

`LlmPromptBuilderTests`:
- Builds a request with `kind == DECISION`, `temperature == 0.0`, non-empty `systemPrompt`, exactly one user message.
- The user message contains the observation as a JSON code block.
- The user message lists every `legalActions` entry by `id`.
- `toolSchema` is non-null and is valid JSON.

`LlmResponseParserTests`:
- A `toolCallJson` with `{"actions":[{"kind":"EndTurn","id":"end_turn"}]}` and a valid `EndTurn` in `observation.legalActions` → `Success([EndTurn])`.
- A `toolCallJson` referencing an action ID that is NOT in `observation.legalActions` → `Failure`.
- No `toolCallJson` but `rawText` contains a fenced JSON block → parses from there.
- Pure garbage in `rawText` and null tool call → `Failure`.
- A well-formed `ChooseTechnology` with `techName == "Pottery"` and `id` matching → `Success`.
- Empty `actions` array → `Success(emptyList())`. The gateway will apply nothing; the runner's degenerate-policy detector handles this.

`RealLlmPolicyTests` (using `FakeLlmClient`):
- Happy path: scripted response of `[EndTurn]` → policy returns `[EndTurn]`.
- Client throws `LlmClientException` → fallback `EndTurnOnly` returns `[EndTurn]`.
- Client returns garbage → fallback returns `[EndTurn]`.
- Repeated calls with the same observation: each call sends a fresh request (no caching, no state reuse).

`RealLlmPolicyFallbackTests`:
- `EndTurnOnly.onClientError` and `onParseFailure` both return `[EndTurn]`.
- `Empty.onClientError` and `onParseFailure` return `[]`.

`LlmTraceRecord` updates:
- A test in the existing trace-writer test file verifies `llmCall` field is null when policy is `random`, populated when policy is `llm`.
- The trace header `agentConfig` block is populated when `--config` is provided, contains path / sha256 / name / clientKind / model, and contains NO API key or env var name.

`LlmAgentConfigLoaderTests`:
- Loads each of the four sample configs (`anthropic-sonnet-baseline`, `openrouter-llama-3.3-stress`, `ollama-local`, `fake-replay-smoke`) without error.
- Mismatched `schema` value → `LlmAgentConfigException` with a clear message.
- Missing required field per client kind → typed failure (e.g. `anthropic` without `model`).
- Forbidden chars in `name` → failure.
- Computed SHA256 matches an external `sha256sum` invocation for one fixture (proves the hash is over the raw file bytes, not over a re-serialized JSON).
- Missing env var when client requires a key → load fails clearly. A separate test sets the env via a test-only `EnvReader` and verifies the success path.

`LlmAgentConfigFactoryTests`:
- `anthropic` kind → factory produces a `RealLlmPolicy` wrapping an `AnthropicLlmClient` (verified by inspecting `identifier`). HTTP is mocked via a fake `HttpClientFactory` in the runtime holder; no real network.
- `openai-compat` kind → similar with `OpenAiCompatibleLlmClient`.
- `fake` kind → factory produces a policy with `FakeLlmClient` loaded from `transcriptPath`.
- Each fallback string (`end_turn_only`, `empty`) → corresponding sealed-interface case.

No integration tests against real Anthropic API in `tests:test`. Real API hits live only in manual smoke (see DoD).

## 9. Definition of done

- `./gradlew tests:test` зелёный.
- `./gradlew :desktop:llmGatewayCli --args="--save <save> --steps 5 --policy llm --config configs/llm-agent/fake-replay-smoke.json"` runs end-to-end without network, applies scripted actions, and writes JSONL with `llmCall` and `agentConfig` populated.
- `./gradlew :desktop:llmGatewayCli --args="--save <save> --steps 5 --policy llm --config configs/llm-agent/anthropic-sonnet-baseline.json"` runs end-to-end against the real Anthropic API, applies parsed actions, writes JSONL.
- `./gradlew :desktop:llmGatewayCli --args="--save <save> --steps 5 --policy llm --config configs/llm-agent/openrouter-llama-3.3-stress.json"` runs end-to-end against an open-weight model on OpenRouter, applies parsed actions, writes JSONL. This is the "second provider" gate — Stage A is not done until both providers complete a 5-step smoke.
- `agentConfig.sha256` in the trace header equals an external `sha256sum` of the config file used. (Proves traces are retraceable to exact settings.)
- API key is never present in the trace (header or step lines). Verified by a `grep` of the trace against the actual key value in the env, in the manual smoke checklist.
- A bad/missing API key → exit code 4 (or 1 if invalid arg), clean stderr message.
- Repeated parse failures over 5 consecutive steps → exit code 4 with reason.
- `core` module has zero new external dependencies. `desktop` adds none beyond JDK.
- `LlmCallTrace` records: input/output tokens, latency, stop reason, parsedSuccessfully, fallbackUsed, rationale (5-6 sentences from the model).
- A 10-turn manual smoke on a real save with `--client anthropic` produces a trace where: at least one turn picks a tech, at least one turn ends without other actions, and total cost is computable from token sums × known per-token price.
- A parallel 10-turn smoke with `--client openai-compat` on the same save documents which parser branch (1 / 2 / 3) fired per turn. Branches 2 and 3 should be measurably non-zero for the open-weight model — that's the whole point of having them.

## 10. Manual smoke procedure (after auto tests pass)

This is the real test of Stage A; auto tests verify wiring, smoke verifies behavior. Run **both passes** — Anthropic baseline, then open-weight stress.

### Pass 1 — Anthropic baseline

1. Set `ANTHROPIC_API_KEY` in env.
2. Load a small mid-game save (~turn 50, 2-3 cities, no active wars). If we don't have one, generate via existing test fixture and save with `UncivFiles.saveGame(...)` as a one-off helper.
3. Edit `configs/llm-agent/anthropic-sonnet-baseline.json` to set `trace.includePrompts: true` for this run (or create a sibling config `anthropic-sonnet-debug.json`). Run CLI for 10 steps with `--policy llm --config <path>`.
4. Open the JSONL trace. For each step:
   - `llmCall.fallbackUsed == null`.
   - `llmCall.outputTokens` is in the range ~300-1500 (rationale + tool call). If consistently > 2000, prompt is asking for too much; if consistently < 200, rationale isn't being filled out.
   - `llmCall.rationale` is populated and has roughly 5-6 sentences.
   - At least one applied action across the run is a tech or construction (not all `EndTurn`).
5. Compute total cost from token counts. Sanity: under $1 for a 10-step run.
6. Re-run with the same save (same model, temperature 0). Outputs likely still vary because the model is non-deterministic even at T=0. This is expected; Stage A does not require deterministic LLM behavior, only deterministic plumbing.

### Pass 2 — Open-weight stress test

The point: stress the **parser**, not the model. We expect a weaker model to play worse Civ, that's not what we're measuring. We're measuring whether our prompt+parser+fallback pipeline degrades gracefully.

1. Set `OPENROUTER_API_KEY` in env (or equivalent for chosen provider).
2. Same save as Pass 1.
3. Run CLI for 10 steps with `--policy llm --config configs/llm-agent/openrouter-llama-3.3-stress.json` (set `trace.includePrompts: true` in that file for this debug run).
4. For each step in the trace, identify which **parser branch** fired (this should be visible in `llmCall.parseSourceBranch` — add this field to `LlmCallTrace` as part of Stage A).
5. Record per-step:
   - Branch 1 fired (clean tool_use): expected most-but-not-all turns.
   - Branch 2 fired (fenced JSON block): expected occasionally.
   - Branch 3 fired (raw JSON in rawText): expected occasionally.
   - Fallback fired (parse failure): should be < 30% of turns. If higher, the prompt needs to be tightened before Stage B work begins.
6. Repeat with one more open-weight model by copying the OpenRouter config and changing only the `client.model` field (e.g. to `qwen/qwen-2.5-72b-instruct` or `deepseek/deepseek-chat-v3`). Goal: same parser, same prompt, same code — only config changes — handles both without modification.

If any pass fails or looks weird, that's the signal for what Stage B needs to fix first — but **do not** start writing Stage B until the passes are green and the parser branch distribution is recorded for use in H6 (see section 11).

## 11. Implementation order

1. **Pure data classes**, no logic: `LlmMessage`, `LlmRequest`, `LlmResponse`, `LlmRequestKind`, `LlmCallTrace` (including `parseSourceBranch` field). Compile-only step.
2. **`LlmClient` interface + `FakeLlmClient`**. Tests for `FakeLlmClient`. No HTTP yet.
3. **`LlmAgentConfig` data class** mirroring the schema in section 4. Plain data, no logic.
4. **`LlmAgentConfigLoader`** + tests. JSON parse via libgdx `json()`, SHA256, env-var resolution, schema-version check, per-kind validation. Plus the four sample configs under `configs/llm-agent/` (committed so tests can load them).
5. **`LlmAgentConfigFactory`** + tests. Builds a `RealLlmPolicy` from a config. Uses a `LlmRuntime` holder so tests can substitute a fake `HttpClientFactory` and a fake `EnvReader`.
6. **`LlmPromptBuilder`** + tests. Hand-written tool schema generator for `LlmLegalAction`. Rationale field constrained to 5-6 sentences in schema.
7. **`LlmResponseParser`** + tests. Cover all four parsing branches (tool_use, fenced block, raw JSON in text, total failure) plus ID mismatch failure.
8. **`LlmPolicyFallback` sealed interface + EndTurnOnly + Empty.** Trivial; tests trivial.
9. **`RealLlmPolicy`** wiring all of the above. Tests against `FakeLlmClient`. Each test asserts the correct `parseSourceBranch` value lands in the trace.
10. **`LlmTraceRecord` extension** (`llmCall: LlmCallTrace?`) + trace-header `agentConfig` block + JSON codec update + tests.
11. **`AnthropicLlmClient`** in `desktop` using `java.net.http.HttpClient`. No unit tests in `tests:test`; manual smoke only.
12. **`OpenAiCompatibleLlmClient`** in `desktop`. Same HTTP path, OpenAI chat-completions protocol. No unit tests in `tests:test`; manual smoke only. The two clients should share a small helper for `HttpClient` setup, timeouts, retry-on-429/5xx, JSON encoding/decoding — but each implements its own request body shape.
13. **CLI args** (`--policy llm`, `--config <path>`) + parse tests. CLI must reject `--policy llm` without `--config`. Provider-specific validation happens in the loader/factory; CLI just passes the path through.
14. **End-to-end CLI smoke with `fake-replay-smoke.json`** to verify wiring.
15. **End-to-end CLI smoke with `anthropic-sonnet-baseline.json`** on a real save. Pass 1 of manual smoke.
16. **End-to-end CLI smoke with `openrouter-llama-3.3-stress.json`** on the same save, against an open-weight model on OpenRouter. Pass 2 of manual smoke. **This is the Stage A acceptance moment** — both passes must complete successfully.
17. **Trace review.** Look at 10 steps from each smoke. Write down 3-5 observations per provider that will inform Stage B priorities (what's wasted in the prompt, what kind of mistakes each model makes, where structure helps and where it confuses, which parser branch fires how often per provider).
18. **Hypothesis update.** Update section 12 (H1-H6) with measured values where possible. This document becomes the input artifact for the Stage B plan.

## 12. Что мы ожидаем узнать (выходы Stage A, входы Stage B)

This section is not a deliverable; it's a hypothesis log. Confirming or refuting each shapes Stage B.

- **H1 — Token cost dominates.** Each turn ~$0.05 with no caching is sustainable for ~10-100-turn experiments but prohibitive for 10000-turn datasets. Stage B prompt caching expected to drop this 5-10× on the static prefix.
- **H2 — Sonnet is overkill for trivial turns.** Many turns are "research next, end turn" with no real decision. Routing trivial turns to Haiku in Stage B should be near-free quality-wise.
- **H3 — Stateless agent does not remember war aims.** We expect to see Claude declare goals one turn and forget them the next. This justifies Stage B's plan layer.
- **H4 — `legalActions` enumeration is the prompt's largest section.** If true, Stage B should consider summary-by-default + tool-use to fetch full lists. If false (it compresses well), keep enumerating.
- **H5 — Parse failure rate on Anthropic is < 5%.** Anthropic with strict tool_use should be near-100% structurally valid. If failure rate is higher, the tool schema needs work before Stage B.
- **H6 — Parse failure rate on open-weight models is < 30%, and branch-3 (raw JSON in rawText) is the dominant fallback.** We expect Llama / Qwen / DeepSeek to obey tool_use when given a strict tool_choice ~60-80% of the time, fall back to a fenced block ~10-20% of the time, and emit naked JSON or wrapped text the rest. If the rates are dramatically different (e.g. >50% complete failures), the prompt itself is the issue, not the parser — that's a Stage B priority.

Stage B's plan will be written **after** Stage A is running on both providers and these hypotheses have data on them. The strict gate exists because Stage B's architecture (static lore prefix, plan layer, multi-tier routing, prompt caching) is shaped by what specifically hurt in Stage A — and that's unknowable without running Stage A first.
