# LLM Gateway MVP Plan

This plan describes a minimal in-process gateway for experimenting with LLM-like decision policies inside Unciv without network calls, UI changes, or real OpenAI/Ollama integration.

## 1. Предлагаемые файлы/классы

Recommended package:

```text
core/src/com/unciv/logic/llm/
```

Suggested files:

```text
core/src/com/unciv/logic/llm/LlmGateway.kt
core/src/com/unciv/logic/llm/LlmPolicy.kt
core/src/com/unciv/logic/llm/RandomPolicy.kt
core/src/com/unciv/logic/llm/ScriptedPolicy.kt
core/src/com/unciv/logic/llm/LlmObservation.kt
core/src/com/unciv/logic/llm/LlmLegalAction.kt
core/src/com/unciv/logic/llm/LlmActionApplier.kt
core/src/com/unciv/logic/llm/LlmActionCollector.kt
core/src/com/unciv/logic/llm/LlmDecisionResult.kt
```

Class responsibilities:

- `LlmGateway`
  - Public facade.
  - Takes a `GameInfo`, selects the target `Civilization`, builds an observation, asks a policy for actions, validates/applies actions.
  - Should not know about UI, networking, prompts, or model providers.

- `LlmPolicy`
  - Small interface for dummy policies.
  - Example shape:

```kotlin
interface LlmPolicy {
    fun chooseActions(observation: LlmObservation): List<LlmLegalAction>
}
```

- `RandomPolicy`
  - Selects randomly from legal tech/construction/end-turn actions.
  - Useful for smoke tests and property-style tests.

- `ScriptedPolicy`
  - Deterministic policy driven by a list of desired actions.
  - Useful for unit tests: "choose Pottery", "build Monument in capital", "end turn".

- `LlmObservation`
  - Snapshot of only the data needed for the MVP.
  - Should be plain Kotlin data classes with strings/ints/lists, not direct mutable references to `GameInfo`.

- `LlmLegalAction`
  - Closed set of actions the gateway is allowed to apply.
  - MVP actions:
    - choose research technology;
    - choose city construction;
    - end turn.

- `LlmActionCollector`
  - Builds legal actions from `GameInfo`/`Civilization`.
  - Uses existing Unciv predicates such as `TechManager.canBeResearched(...)` and `CityConstructions.canAddToQueue(...)`.

- `LlmActionApplier`
  - Applies one legal action by calling existing managers.
  - Should never mutate raw queues directly unless there is no existing manager method.

- `LlmDecisionResult`
  - Records selected actions, applied actions, rejected actions, and a short reason/error.
  - Useful for tests and future logs.

Optional later, not MVP:

```text
core/src/com/unciv/logic/llm/LlmJsonCodec.kt
core/src/com/unciv/logic/llm/LlmPromptBuilder.kt
desktop/src/com/unciv/app/desktop/LlmGatewayCli.kt
```

Do not add provider classes such as `OpenAiPolicy` or `OllamaPolicy` yet.

## 2. Методы, которые нужно вызывать в существующем коде Unciv

Access current game/civilization:

- `GameInfo.getCurrentPlayerCivilization()`
  - Primary default target for local single-player tests.
- `GameInfo.getCivilization(civID)`
  - Use only when the caller explicitly targets a civ.
- Avoid `GUI.getViewingPlayer()`, `GUI.getSelectedPlayer()`, and `WorldScreen.viewingCiv` in the first implementation.

Collect legal research actions:

- `civ.tech.canBeResearched(techName)`
  - Filter candidate technologies.
- `civ.tech.isResearched(techName)`
  - Exclude already researched non-repeatable techs.
- `civ.tech.getRequiredTechsToDestination(technology)`
  - Optional: produce a path if the policy chooses a destination tech.
- `civ.tech.currentTechnologyName()`
  - Include current research in observation.
- `civ.gameInfo.ruleset.technologies`
  - Source of available tech definitions.

Apply research action:

- Prefer:

```kotlin
civ.tech.techsToResearch = ArrayList(pathOrSingleTechNames)
civ.tech.updateResearchProgress()
```

- For free techs:

```kotlin
civ.tech.getFreeTechnology(techName)
```

Only allow `getFreeTechnology(...)` when `civ.tech.freeTechs > 0` and the tech is legal.

Collect legal city construction actions:

- Iterate `civ.cities`.
- For each city, use:
  - `city.cityConstructions.getBuildableBuildings()` if accessible from the gateway package only after visibility is checked. It is currently `internal`, so a gateway in `com.unciv.logic.llm` may not be able to call it if module/package visibility blocks it.
  - `city.cityConstructions.getConstructableUnits()` if accessible.
  - Fallback: iterate `city.getRuleset().buildings.values` and `city.getRuleset().units.values`, then filter with `construction.isBuildable(city.cityConstructions)`.
  - Include `PerpetualConstruction` values only if they are buildable and useful for MVP.
- Use `city.cityConstructions.canAddToQueue(construction)` before exposing an action.
- Include current construction via `city.cityConstructions.currentConstructionName()`.

Apply city construction action:

- Preferred:

```kotlin
city.cityConstructions.addToQueue(constructionName)
city.reassignPopulation()
```

- If the MVP needs "replace current construction" semantics, use a separate explicit action and be conservative:
  - remove current queue entries through existing queue APIs;
  - then `addToQueue(...)`;
  - avoid raw `constructionQueue.clear()` in the first version.

End turn:

- Headless/core path:

```kotlin
gameInfo.nextTurn(progressBar = null)
```

- If the gateway first applies decisions and then ends the turn, apply research/construction actions before calling `nextTurn(null)`.
- Do not call `WorldScreen.nextTurn()` in the MVP.

Fallback automation:

- For optional dummy behavior:

```kotlin
TurnManager(civ).automateTurn()
```

- For narrower fallback:

```kotlin
NextTurnAutomation.automateCities(civ)
```

Use fallback automation only as a policy choice or test helper, not as hidden behavior inside the action applier.

Serialization for tests/CLI later:

- `UncivFiles.gameInfoFromString(...)`
- `UncivFiles.gameInfoToString(...)`
- Always ensure `GameInfo.setTransients()` after custom deserialization paths.

## 3. Структуры данных для Observation и LegalAction

Keep MVP data small and deterministic. The goal is not to expose the whole save file.

Suggested observation model:

```kotlin
data class LlmObservation(
    val gameId: String,
    val turn: Int,
    val currentPlayerCivId: String,
    val currentPlayerCivName: String,
    val isOnlineMultiplayer: Boolean,
    val currentResearch: String?,
    val sciencePerTurn: Int,
    val cities: List<LlmCityObservation>,
    val researchedTechs: List<String>,
    val legalActions: List<LlmLegalAction>
)

data class LlmCityObservation(
    val cityId: String,
    val name: String,
    val population: Int,
    val currentConstruction: String?,
    val productionPerTurn: Int,
    val queue: List<String>
)
```

`cityId` can initially be the city name if there is no stable public id suitable for this MVP. If duplicate city names are possible in the target scenarios, use a derived id such as `"$civId/${city.id or location}"` based on available city fields.

Suggested legal action model:

```kotlin
sealed interface LlmLegalAction {
    val id: String

    data class ChooseTechnology(
        override val id: String,
        val techName: String,
        val path: List<String> = listOf(techName)
    ) : LlmLegalAction

    data class ChooseCityConstruction(
        override val id: String,
        val cityId: String,
        val cityName: String,
        val constructionName: String,
        val addToTop: Boolean = false
    ) : LlmLegalAction

    data class EndTurn(
        override val id: String = "end_turn"
    ) : LlmLegalAction
}
```

Suggested result model:

```kotlin
data class LlmDecisionResult(
    val observationTurn: Int,
    val selectedActions: List<LlmLegalAction>,
    val appliedActions: List<LlmLegalAction>,
    val rejectedActions: List<LlmRejectedAction>
)

data class LlmRejectedAction(
    val action: LlmLegalAction,
    val reason: String
)
```

Validation rules:

- Only apply actions that are present in `observation.legalActions`, or revalidate against the current `GameInfo` immediately before applying.
- End turn should be explicit. Do not end the turn automatically after choosing tech/construction unless the caller/policy selected `EndTurn`.
- Apply at most one research action per gateway step.
- Apply at most one construction action per city per gateway step.
- Reject online multiplayer in MVP unless explicitly enabled by test setup.

## 4. Как тестировать это без настоящей LLM

Unit tests with `ScriptedPolicy`:

- Create a small test game using existing test helpers or `GameStarter.startNewGame(...)`.
- Build an observation for the current civ.
- Script a known technology action.
- Run gateway step.
- Assert:
  - `civ.tech.techsToResearch.firstOrNull() == expectedTech`;
  - action appears in `appliedActions`;
  - no turn advanced unless `EndTurn` was scripted.

City construction tests:

- Use a game with at least one city.
- Pick a legal construction from `observation.legalActions`.
- Script `ChooseCityConstruction`.
- Assert:
  - city queue contains the construction;
  - `currentConstructionIsUserSet == true`;
  - invalid construction is rejected and does not mutate queue.

End-turn tests:

- Script `EndTurn`.
- Record `gameInfo.currentPlayer`, `gameInfo.turns`, and current civ.
- Run gateway step.
- Assert that `GameInfo.nextTurn(null)` was invoked indirectly:
  - `currentPlayer` changes or `turns` advances depending on player order;
  - transients remain valid;
  - no UI classes are required.

Random policy smoke tests:

- Run `RandomPolicy(seed = fixed)` over a tiny game for several gateway steps.
- Assert no exceptions.
- Assert every applied action was legal at the time of application.
- Keep the number of steps low at first because `nextTurn` can trigger broad AI automation.

Serialization round-trip tests:

- Serialize with `UncivFiles.gameInfoToString(gameInfo)`.
- Deserialize with `UncivFiles.gameInfoFromString(data)`.
- Run one scripted gateway step.
- This catches missing transient assumptions early.

Negative tests:

- Unknown tech is rejected.
- Already researched tech is rejected unless continually researchable.
- Construction not buildable in the selected city is rejected.
- Construction for missing city is rejected.
- Online multiplayer game is rejected by default.
- Policy returns duplicate/conflicting actions; gateway applies only allowed subset or rejects duplicates deterministically.

Test location:

```text
tests/src/com/unciv/logic/llm/
```

Suggested test files:

```text
tests/src/com/unciv/logic/llm/LlmGatewayResearchTests.kt
tests/src/com/unciv/logic/llm/LlmGatewayConstructionTests.kt
tests/src/com/unciv/logic/llm/LlmGatewayEndTurnTests.kt
tests/src/com/unciv/logic/llm/RandomPolicySmokeTests.kt
```

Test command:

```bash
./gradlew tests:test
```

## 5. Что не трогать

Do not touch UI:

- No changes to `WorldScreen`, `NextTurnButton`, `NextTurnAction`, `TechPickerScreen`, `CityScreen`, or `CityConstructionsTable` for the MVP.
- No new buttons, popups, debug menu entries, or settings screens.

Do not add networking or model providers:

- No OpenAI/Ollama clients.
- No HTTP calls.
- No API keys, local model paths, streaming, prompt templates, or provider configuration.

Do not alter save format:

- Do not add serialized fields to `GameInfo`, `Civilization`, `City`, `CityConstructions`, or `TechManager`.
- Do not change `CompatibilityVersion`.
- Do not modify `BackwardCompatibility.kt`.

Do not bypass existing game rules:

- Do not write directly to `constructionQueue` except through existing queue methods.
- Do not write directly to `techsResearched` for normal research choice.
- Do not call `addTechnology(...)` for ordinary research selection.
- Do not skip `canBeResearched(...)`, `canAddToQueue(...)`, or construction buildability checks.

Do not couple gateway to desktop UI:

- Avoid `GUI`, `WorldScreen`, `BaseScreen`, `Gdx.input`, `ImageGetter` UI paths, and GL-thread assumptions.
- A future CLI adapter may live in `desktop`, but the MVP decision/apply core should live in `core/src/com/unciv/logic/llm`.

Do not support multiplayer yet:

- Reject `gameInfo.gameParameters.isOnlineMultiplayer` by default.
- Do not upload/download games.
- Do not inspect or mutate `settings.multiplayer`.
- Do not implement force-resign, skip-player, or spectator behavior.

Do not make the gateway autonomous by default:

- No hidden automatic end-turn after a decision.
- No hidden fallback to full AI unless the selected policy/action explicitly requests it.
- No background threads in the core gateway; keep it synchronous and deterministic for tests.
