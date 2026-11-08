# LLM Gateway CLI + JSONL Logging Plan

This plan describes the second stage after the MVP: a headless CLI runner that loads a real Unciv save, drives the existing `LlmGateway` with a chosen dummy policy for N steps, and writes a JSONL trace of observations and decisions. No UI, no network, no real LLM, no save-format changes.

The goal of this stage is to give us "eyes": the ability to feed real games into the gateway, watch what observations and legal actions it produces, and collect a dataset for later stages (richer observation, heuristic policy, real LLM client).

## 1. Предлагаемые файлы/классы

Core (headless, reusable from tests):

```text
core/src/com/unciv/logic/llm/io/LlmJsonCodec.kt
core/src/com/unciv/logic/llm/io/LlmTraceWriter.kt
core/src/com/unciv/logic/llm/io/LlmTraceRecord.kt
core/src/com/unciv/logic/llm/runner/LlmGatewayRunner.kt
core/src/com/unciv/logic/llm/runner/LlmRunnerConfig.kt
core/src/com/unciv/logic/llm/runner/LlmRunnerSummary.kt
```

Desktop (process entry point + arg parsing only):

```text
desktop/src/com/unciv/app/desktop/LlmGatewayCli.kt
```

Tests:

```text
tests/src/com/unciv/logic/llm/io/LlmJsonCodecTests.kt
tests/src/com/unciv/logic/llm/io/LlmTraceWriterTests.kt
tests/src/com/unciv/logic/llm/runner/LlmGatewayRunnerTests.kt
```

Class responsibilities:

- `LlmJsonCodec`
  - **Encode-only in this stage.** `LlmObservation`, `LlmLegalAction`, `LlmRejectedAction`, `LlmDecisionResult` → JSON strings.
  - Hand-written serialization, not reflection — we want the JSONL schema to be stable independently of class renames.
  - Schema versioned: every encoded record carries `"schema": "llm-trace/v1"`.
  - Decode (parse JSON → in-memory objects) is **deferred** to whichever stage needs replay. Tests for the encoder parse lines with a standard JSON reader and assert on field values; they do not depend on a symmetric decoder.

- `LlmTraceRecord`
  - Plain data class describing one row in the JSONL file: step index, wall-clock timestamp, game turn, civ id, observation, selected/applied/rejected actions, optional `error` field.
  - Has no dependency on file I/O.

- `LlmTraceWriter`
  - Append-only writer wrapped around a `java.io.Writer` (so tests can pass a `StringWriter`).
  - Has an explicit `writeHeader(header: LlmTraceHeader)` call. Header writing is not implicit in `writeStep(...)`; callers must make the trace lifecycle obvious.
  - Writes one JSON object per line, flushes after each line.
  - Owns the schema version constant.
  - Never throws on `close()` if already closed; closes the underlying writer.

- `LlmRunnerConfig`
  - Data class: `saveFile: File`, `outputFile: File?`, `policy: PolicyKind`, `seed: Long?`, `steps: Int`, `allowOnlineMultiplayer: Boolean`, `failOnRejected: Boolean`.
  - `PolicyKind` is an enum: `RANDOM`, `END_TURN_ONLY` (always returns `EndTurn` — this changes the game, so it is **not** a no-op), and later additions.
  - No `targetCivName` in this stage — see "Out of scope" below.
  - No code, just data.

- `LlmRunnerSummary`
  - Data class returned from `LlmGatewayRunner.run(...)`:
    - `stepsRun: Int`, `appliedCount: Int`, `rejectedCount: Int`, `finalTurn: Int`, `outputPath: String?`, `stoppedReason: StoppedReason`.
  - `StoppedReason` enum: `COMPLETED`, `CIV_DEFEATED`, `DEGENERATE_POLICY`, `ERROR`.
  - The CLI prints its summary line directly from this object — it never re-parses the JSONL it just wrote.

- `LlmGatewayRunner`
  - Headless orchestrator. Takes a loaded `GameInfo`, a `LlmPolicy`, a `LlmTraceWriter`, and an `LlmRunnerConfig`.
  - Receives display metadata such as `uncivVersion` through config/header data; the core `io` layer does not depend on desktop `BuildConfig`.
  - Always targets `gameInfo.getCurrentPlayerCivilization()`. Single-civ semantics only.
  - Loop: for `i` in `0 until config.steps`, call `gateway.step(...)`, write a `LlmTraceRecord`, stop early if no actions were applied AND no `EndTurn` was selected for two consecutive steps.
  - Returns `LlmRunnerSummary`.
  - No printing, no file paths — pure logic.

- `LlmGatewayCli` (desktop module only)
  - Parses CLI args, loads ruleset (via `RulesetCache.loadRulesets`), loads save file (via `UncivFiles.gameInfoFromString`), constructs `LlmGatewayRunner`, writes JSONL to disk, prints a summary line to stdout, returns exit code.
  - Catches `IncompatibleGameInfoVersionException` and known errors with a clean error message + non-zero exit code.
  - The only file in this stage that touches `System.out`, `System.err`, `args`, or `File.writer()` directly.

What deliberately does NOT live here ("Out of scope"):

- **`--civ` flag / multi-civ control.** `LlmGateway.step(gameInfo, civ)` applies research/construction to the explicitly chosen civ, but `EndTurn` calls `gameInfo.nextTurn(null)` which advances **the current player**, not the chosen civ. Mixing those two within one step produces unclear semantics (apply tech to civ A, end-turn advances civ B; next step's observation is for civ A again while `currentPlayer` is now C). Picking the right semantics — single-civ controller vs. multi-civ controller vs. observer — is a separate design decision and belongs to a later stage. For this stage the runner is hard-wired to `getCurrentPlayerCivilization()`.
- **JSON decoder.** Logging only needs encode. Tests parse lines with a generic JSON reader (libgdx `JsonReader` or `org.json`) to assert field values, but `LlmJsonCodec` does not provide `decodeObservation(...)` / `decodeAction(...)` yet. Adding decode prematurely couples us to a stable observation hash for replay, which we'll design properly when replay is actually built.
- **`LlmPromptBuilder`, `LlmResponseParser`.** Belong to the "real LLM client" stage. Adding them now anchors the JSON schema on something we'll regret later.
- **Replay mode (`--replay-trace foo.jsonl`).** Tempting but needs the decoder + a stable observation hash. Defer.
- **Comparison/diff tools across runs.** Defer to a later stage once we have a heuristic baseline to compare against.

## 2. Методы, которые нужно вызывать в существующем коде Unciv

Loading a save headlessly:

- `UncivFiles.gameInfoFromString(saveFile.readText())`
  - The companion-object form is callable without an initialized libgdx `Gdx.app`. The existing tests in `tests/src/com/unciv/logic/llm/serialization/GatewayRoundTripTests.kt` already use this pattern.
  - Always confirm `gameInfo.setTransients()` was called — `gameInfoFromString` already does this, but a comment in `LlmGatewayCli` should make the assumption explicit.

- Ruleset must be loaded before deserialization, otherwise transients fail:

```kotlin
RulesetCache.loadRulesets(consoleMode = true, noMods = false)
```

  - `consoleMode = true` avoids `Gdx.files` and loads `jsons/` / `mods/` through plain file handles. The desktop CLI runs from the same `assetsDir` as the desktop launcher (`workingDir = assetsDir` in `desktop/build.gradle.kts`), so those paths are resolved from `android/assets`.

Save file path resolution (CLI only):

- Accept an absolute or relative path to a save file. Do not try to guess from `UncivFiles.getSaves()` — keep CLI independent of platform-specific save directories.
- If the path is a `.json.gz`/`.unciv` save written by Unciv, `gameInfoFromString` already auto-unzips via `Gzip.unzip` (see `UncivFiles.kt` line 408–413).

Driving the gateway:

- Reuse the existing `LlmGateway` exactly as it is. No changes to its signature.
- Construct policy:
  - `RandomPolicy(seed)` — already exists; expose the seed in `LlmRunnerConfig.seed`. If null, use `0L` so runs are reproducible by default.
  - `ScriptedPolicy(listOf(LlmLegalAction.EndTurn()))` for the "just press end turn N times" mode. Useful as a smoke-test config that drives the game forward without making creative choices.
- Construct gateway with `allowOnlineMultiplayer` propagated from config.

Selecting the target civ:

- Always `gameInfo.getCurrentPlayerCivilization()`. No CLI flag. Single-civ semantics — see "Out of scope".

Ending the run early (sets `LlmRunnerSummary.stoppedReason`):

- `DEGENERATE_POLICY` — `appliedActions.isEmpty() && selectedActions.isEmpty()` for two consecutive steps.
- `CIV_DEFEATED` — `civ.isDefeated()` after a step.
- `COMPLETED` — ran all `config.steps` without the above.
- `ERROR` — uncaught exception from the gateway; the runner re-throws after writing whatever it can to the trace.

What NOT to call:

- Do NOT call `WorldScreen.nextTurn()`, `GUI.*`, `ImageGetter.*`, anything from `com.unciv.ui.*`.
- Do NOT call `UncivGame.Current` — keep the runner independent of the singleton.
- Do NOT call `UncivFiles.saveGame(...)` from this stage. Trace writing is to our own JSONL file, not to Unciv's save directory.

## 3. Формат JSONL трассы

One JSON object per line. UTF-8. Newline-terminated. No trailing comma. No pretty-printing.

The first line is a header record. All subsequent lines are step records. This makes the file self-describing without needing a sidecar manifest.

Header record:

```json
{
  "schema": "llm-trace/v1",
  "type": "header",
  "createdAt": "2026-05-22T11:42:00Z",
  "uncivVersion": "<BuildConfig.appVersion>",
  "compatibilityVersion": "<CompatibilityVersion.CURRENT_COMPATIBILITY_VERSION>",
  "saveFile": "MySave.unciv",
  "policy": { "kind": "RANDOM", "seed": 0 },
  "allowOnlineMultiplayer": false
}
```

Step record:

```json
{
  "schema": "llm-trace/v1",
  "type": "step",
  "step": 0,
  "turn": 42,
  "civId": "Babylon",
  "observation": { /* LlmObservation as JSON */ },
  "selectedActions": [ /* LlmLegalAction[] */ ],
  "appliedActions": [ /* LlmLegalAction[] */ ],
  "rejectedActions": [
    { "action": { /* LlmLegalAction */ }, "reason": "Technology is not researchable: Bronze Working" }
  ]
}
```

`LlmLegalAction` encoding. The `id` field is exactly what `LlmActionCollector` already produces — the codec does not invent a parallel format:

```json
{ "kind": "ChooseTechnology", "id": "choose_technology:Pottery", "techName": "Pottery", "path": ["Pottery"] }
{ "kind": "ChooseCityConstruction", "id": "choose_city_construction:Babylon:Monument", "cityId": "Babylon", "cityName": "Babylon", "constructionName": "Monument", "addToTop": false }
{ "kind": "EndTurn", "id": "end_turn" }
```

`LlmObservation` encoding follows the existing data class field-by-field — no remapping, no renaming. `cities[].queue` is a JSON array of strings.

Notes on schema stability:

- The `schema` string is the contract. If anything in the encoding changes (field added, renamed, removed), bump to `llm-trace/v2`.
- A reader written to `v1` must be able to ignore unknown fields without crashing — encoder is allowed to add fields, never remove them, within a major schema version.

## 4. CLI surface

Gradle task (added in `desktop/build.gradle.kts`):

```kotlin
tasks.register<JavaExec>("llmGatewayCli") {
    dependsOn(tasks.getByName("classes"))
    mainClass.set("com.unciv.app.desktop.LlmGatewayCli")
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = assetsDir
    standardInput = System.`in`
    isIgnoreExitValue = false
}
```

Run examples:

```bash
./gradlew :desktop:llmGatewayCli --args="--save MySave.unciv --steps 5 --policy random --seed 0 --out trace.jsonl"
./gradlew :desktop:llmGatewayCli --args="--save MySave.unciv --steps 20 --policy end_turn_only --out endturn.jsonl"
```

Required arg: `--save <path>`.
Optional args, all with sensible defaults:

- `--steps <int>` (default: 1)
- `--policy <random|end_turn_only>` (default: `random`)
- `--seed <long>` (default: 0)
- `--out <path>` (default: `llm-trace.jsonl` in `assetsDir`)
- `--allow-multiplayer` (default: false)
- `--fail-on-rejected` (default: false; if set, non-zero exit code if any rejected action appears)

No `--civ` flag in this stage (see "Out of scope"). Runner always targets the current player.

Exit codes:

- `0` — clean completion (including "no steps because civ defeated").
- `1` — invalid arguments / unknown policy / save file not found / save incompatible.
- `2` — runtime error inside the gateway (uncaught exception). Stack trace goes to stderr, summary line still attempted.
- `3` — rejected action present and `--fail-on-rejected` was set.

Stdout (one final summary line, parseable):

```text
llm-cli: steps=5 applied=4 rejected=1 final_turn=43 wrote=trace.jsonl
```

Stderr is reserved for warnings/errors only.

## 5. Как тестировать

Unit tests for `LlmJsonCodec` (encode-only):

- For every `LlmLegalAction` subtype: build an instance, encode it, parse the resulting JSON with a generic reader, assert `kind`, `id`, and all payload fields. `id` values must match what `LlmActionCollector` produces (e.g. `choose_technology:Pottery`, not `tech:Pottery`).
- Encode `LlmObservation` with empty and non-empty `cities` and `researchedTechs`; parse and assert structure.
- Encode an `LlmDecisionResult` built from `LlmGatewayTestFixtures` and assert that the parsed JSON line-by-line matches the in-memory objects.
- Assert every encoded record contains `"schema": "llm-trace/v1"`.

Unit tests for `LlmTraceWriter`:

- Writes a header only when `writeHeader(...)` is called explicitly.
- Each `writeStep(...)` produces exactly one line ending in `\n`.
- Lines are valid JSON individually (parse each line independently).
- `close()` is idempotent.
- Use `StringWriter` — no real file I/O in unit tests.

Integration tests for `LlmGatewayRunner` (in `tests/`):

- Reuse `LlmGatewayTestFixtures` to build a small `GameInfo`.
- Run the runner with `ScriptedPolicy([EndTurn])` for 3 steps; assert:
  - trace has 1 header + 3 step records;
  - `turn` is non-decreasing across steps;
  - each step's `appliedActions` contains exactly the `EndTurn`;
  - returned `LlmRunnerSummary` matches: `stepsRun=3`, `appliedCount=3`, `rejectedCount=0`, `stoppedReason=COMPLETED`.
- Run with `RandomPolicy(seed=0)` for 5 steps; assert:
  - no exceptions;
  - every applied action is present in that step's `legalActions`;
  - trace file is parseable line-by-line.
- **Characterization test for `nextTurn` + `Gdx.app == null`.** Run `END_TURN_ONLY` policy for enough steps to cross a turn divisible by 10. Assert no exception. This locks in the current "headless `nextTurn` skips the `musicController` branch" behavior so that a future change to `GameInfo.nextTurn(...)` which drops its `Gdx.app != null` guard fails this test loudly rather than crashing the CLI in production.
- Degenerate-policy test: a policy that always returns `emptyList()` for two steps in a row → `stoppedReason == DEGENERATE_POLICY`, `stepsRun == 2`, no further steps written.

CLI-level tests (lighter — just argv parsing, no real gradle invocation):

- `LlmGatewayCli.parseArgs(...)` (internal helper) — table of invalid argument combinations → expected error messages.
- Avoid spawning a JVM; the round-trip from real save → JSONL is covered by the runner integration tests using `GatewayRoundTripTests`-style fixtures.

Test command:

```bash
./gradlew tests:test
```

## 6. Что не трогать

- Save format. No new fields on `GameInfo`, `Civilization`, `City`. No bump of `CompatibilityVersion`. The CLI is read-only with respect to saves: it loads, but never writes back. If a future stage wants "save after N steps", that's a separate decision.
- UI. No `WorldScreen`, `GUI`, `BaseScreen`, `ImageGetter`, `Gdx.app`. The runner must run in plain JVM. The CLI may construct headless libgdx only if absolutely required for ruleset loading (it isn't — `RulesetCache.loadRulesets(noMods = false)` is enough; see existing test fixtures).
- Existing `LlmGateway` / `LlmPolicy` / `LlmActionCollector` / `LlmActionApplier` signatures. No changes. If something feels missing on the gateway, it's a sign we should design that change explicitly in the next stage, not sneak it in here.
- Networking. No HTTP, no model providers, no replay-from-network. The output is a local file on the user's disk.
- Multiplayer. Honor the existing `allowOnlineMultiplayer = false` default. The CLI exposes the flag but does not turn it on by default.
- Concurrency. The runner is single-threaded and synchronous. No background threads, no coroutines. This keeps traces deterministic and tests fast.
- Logging frameworks. Use plain `println` to stderr (errors) and a single stdout summary line. Do not bring in slf4j/logback.

## 7. Definition of done

- `./gradlew tests:test` is green.
- `./gradlew :desktop:llmGatewayCli --args="--save <existingSave> --steps 3"` produces a JSONL file with a header line and three step lines, each parseable as JSON.
- A `--policy end_turn_only` run advances the game turn at least once.
- A `--save` pointing at a non-existent file exits with code 1 and a clean error on stderr.
- This document and the produced JSONL header agree on the schema string `"llm-trace/v1"`.
- Action `id` values in produced JSONL match those generated by `LlmActionCollector` exactly — no parallel ID format.
- The `nextTurn` + `Gdx.app == null` characterization test is present and passing.

## 8. Implementation order

1. `LlmTraceRecord`, `LlmJsonCodec` (encode-only), `LlmTraceWriter` + unit tests. Codec tests use `LlmGatewayTestFixtures` so we catch mismatches with real collector output before step 2.
2. `LlmRunnerConfig`, `PolicyKind`, `LlmRunnerSummary`, `StoppedReason`, `LlmGatewayRunner` + runner tests (including the `nextTurn` characterization test and the degenerate-policy test).
3. `desktop/src/com/unciv/app/desktop/LlmGatewayCli.kt` with `parseArgs` unit tests. No real JVM-spawning tests.
4. Gradle task `:desktop:llmGatewayCli` in `desktop/build.gradle.kts`.
5. Manual smoke run on a real save (or one produced by an existing test) — verify the JSONL header, three step lines, summary stdout, exit code 0.
