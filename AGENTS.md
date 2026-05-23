# AGENTS.md - LLM Gateway for Unciv

This file is the entry point for any agent or human working on the
**LLM Gateway MVP** inside the Unciv codebase. It describes what we're
building, how we work, and where to find detailed guides.

If you read only one file before starting work, read this one. Then follow
the links below into the specific guide you need.

---

## 1. What this project is

We're adding a small **in-process gateway** that lets us experiment with
LLM-like decision policies inside Unciv without touching UI, networking,
or real model providers.

The gateway:

- Takes a `GameInfo` and selects a target `Civilization`.
- Builds a small, plain-data `LlmObservation` of that civ's situation.
- Asks a pluggable `LlmPolicy` (currently dummy: `RandomPolicy`,
  `ScriptedPolicy`) for a list of actions.
- Validates the actions against the current game state.
- Applies the legal ones through existing Unciv managers.
- Returns an `LlmDecisionResult` describing what happened.

The gateway should live in `core/src/com/unciv/logic/llm/`. The MVP
intentionally does not include any real LLM clients (OpenAI, Ollama, etc.):
only the infrastructure that would eventually plug into one.

For the full plan and rationale, see `docs/LLM_GATEWAY_MVP_PLAN.md`.
For the codebase map of relevant Unciv internals, see
`docs/LLM_GATEWAY_CODEMAP.md`.

---

## 2. How we work in this codebase

This section sets the values and defaults. Specific implementation details
live in the linked guides.

**We prefer reversible, well-scoped changes.** The gateway should be
something you can delete without leaving traces in the rest of Unciv.
That shapes every decision: live in our own package, call existing APIs,
never mutate save format, never add fields to core game classes.

**We trust existing managers.** Unciv has spent years getting
`TechManager`, `CityConstructions`, and `TurnManager` right. The gateway's
job is to call them correctly, not to reimplement what they already do.
When you find yourself reaching into a private field or rebuilding queue
logic, stop and look for an existing manager method first.

**We keep the core gateway deterministic and synchronous.** Tests are the
primary user of this code right now. That means: no background threads in
the core, no hidden auto-end-turn, no implicit fallback to full AI unless
the policy asked for it. Determinism is a feature.

**We treat UI and networking as someone else's problem.** The gateway is
headless-core code. UI adapters (CLI, debug menus) can come later, in
`desktop/`. If you find yourself importing `WorldScreen`, `GUI`, `Gdx`,
or anything in `com.unciv.ui.*`, stop: that import does not belong in
`com.unciv.logic.llm`.

**Multiplayer is out of scope for MVP.** Online multiplayer changes
current-player semantics, requires server uploads, and has staleness
concerns we have not designed for yet. The gateway rejects
`gameInfo.gameParameters.isOnlineMultiplayer` by default; tests that need
to bypass this should set an explicit flag.

**We write tests as the primary deliverable.** Every behavior the gateway
supports should ship with a test that would fail without it. See
`docs/testing-guide.md` for how we write them.

**We protect legacy behavior with characterization tests.** Unciv code we
call into is mature. When we depend on existing manager behavior, capture
that behavior before leaning on it. See `docs/characterization-guide.md`.

---

## 3. Project layout

The gateway and its supporting docs should live in a few specific places:

```text
core/src/com/unciv/logic/llm/
    LlmGateway.kt            <- public facade
    LlmPolicy.kt             <- policy interface
    RandomPolicy.kt          <- dummy policy: random legal actions
    ScriptedPolicy.kt        <- dummy policy: deterministic, test-driven
    LlmObservation.kt        <- plain-data snapshot of game state
    LlmLegalAction.kt        <- closed set of actions we can apply
    LlmActionCollector.kt    <- builds legal actions from GameInfo
    LlmActionApplier.kt      <- applies one legal action via managers
    LlmDecisionResult.kt     <- what the gateway did and why

tests/src/com/unciv/logic/llm/
    LlmGatewayResearchTests.kt
    LlmGatewayConstructionTests.kt
    LlmGatewayEndTurnTests.kt
    RandomPolicySmokeTests.kt

docs/
    LLM_GATEWAY_MVP_PLAN.md  <- full MVP plan and scope
    LLM_GATEWAY_CODEMAP.md   <- map of Unciv internals we touch
    LLM_OBSERVATION_SOURCES.md <- source-of-truth mapping from observation fields to Unciv APIs
    testing-guide.md         <- how we write regular gateway tests
    characterization-guide.md <- how we capture existing Unciv behavior
    characterization-index.md <- current characterization coverage map
    known-behaviors.md       <- captured SMELL log and known quirks
```

Additional testing or characterization docs can be added later when they
exist. Do not reference non-existent docs as required reading.

---

## 4. Where to dig deeper

Pick the doc that matches what you're about to do:

| If you're about to... | Read this first |
|---|---|
| Add or change gateway code | This file + `docs/LLM_GATEWAY_MVP_PLAN.md` |
| Understand which Unciv methods to call | `docs/LLM_GATEWAY_CODEMAP.md` |
| Change observation fields or JSON shape | `docs/LLM_OBSERVATION_SOURCES.md` |
| Write a new test for gateway behavior | `docs/testing-guide.md` |
| Capture existing Unciv behavior before relying on it | `docs/characterization-guide.md` |
| See existing characterization coverage | `docs/characterization-index.md` |
| Investigate a `// SMELL:` marker | `docs/known-behaviors.md` |
| Find a UI/CLI entry point for experiments | `docs/LLM_GATEWAY_CODEMAP.md` section 8 |
| Check MVP boundaries | This file section 7 + `docs/LLM_GATEWAY_MVP_PLAN.md` section 5 |

---

## 5. Key Unciv touchpoints (quick reference)

For details, see `docs/LLM_GATEWAY_CODEMAP.md`. This is the at-a-glance
cheat sheet.

**Current civ (headless context):**

```kotlin
val civ = gameInfo.getCurrentPlayerCivilization()
// or, when explicit:
val civ = gameInfo.getCivilization(civId)
```

**Research - collect legal techs:**

```kotlin
civ.tech.canBeResearched(techName)
civ.tech.isResearched(techName)
civ.tech.getRequiredTechsToDestination(technology)
civ.tech.currentTechnologyName()
```

**Research - apply choice:**

```kotlin
civ.tech.techsToResearch = ArrayList(pathOrSingleTechNames)
civ.tech.updateResearchProgress()
```

Use `civ.tech.getFreeTechnology(techName)` only when `civ.tech.freeTechs > 0`.

**City construction - collect/apply:**

```kotlin
city.cityConstructions.canAddToQueue(construction)
city.cityConstructions.addToQueue(constructionName)
city.reassignPopulation()
```

Use existing queue APIs rather than mutating `constructionQueue` directly.
The queue methods carry validation and `currentConstructionIsUserSet`
bookkeeping that we want.

**End turn (headless):**

```kotlin
gameInfo.nextTurn(progressBar = null)
```

`WorldScreen.nextTurn()` is the UI wrapper and is not for gateway use.

**Fallback automation (only when a policy explicitly asks for it):**

```kotlin
TurnManager(civ).automateTurn()
// or narrower:
NextTurnAutomation.automateCities(civ)
```

**Serialization (tests/CLI):**

```kotlin
UncivFiles.gameInfoToString(gameInfo)
UncivFiles.gameInfoFromString(data)
// Always call setTransients() after custom deserialization:
gameInfo.setTransients()
```

---

## 6. Build and test commands

From repository root:

```bash
./gradlew tests:test          # run the full test suite
./gradlew classes             # quick compile check
./gradlew check               # tests + static checks
./gradlew desktop:run         # run the game, only when verifying UI did not break
```

The gateway has no dependency on `desktop:run`. If you're working on
gateway-only code, `./gradlew tests:test` is the loop you want.

---

## 7. Scope boundaries

These are out of scope for the MVP. If a task seems to require them, that
task probably belongs to a later phase: flag it and ask before expanding
scope.

- **UI:** no changes to `WorldScreen`, `NextTurnButton`, `NextTurnAction`,
  `TechPickerScreen`, `CityScreen`, `CityConstructionsTable`, or anything
  else under `com.unciv.ui.*`.
- **Real model providers:** no OpenAI, Ollama, HTTP clients, prompts, API
  keys, streaming, or provider config. The `LlmPolicy` interface exists
  so these can be added later without rewriting the gateway.
- **Save format:** no new serialized fields on `GameInfo`, `Civilization`,
  `City`, `CityConstructions`, or `TechManager`. No changes to
  `CompatibilityVersion` or `BackwardCompatibility.kt`.
- **Online multiplayer:** rejected by default. No uploads/downloads, no
  inspection of `settings.multiplayer`, no force-resign or spectator
  paths.
- **Autonomy:** no implicit end-of-turn after an action, no implicit
  fallback to full AI. Both are explicit policy choices.

---

## 8. When something doesn't fit

Surface, don't paper over. Three escape valves:

1. **The task seems to need expanded scope** (UI change, save-format
   tweak, new manager method): flag it as out of MVP, propose a tightly
   scoped alternative, and ask before proceeding.
2. **An existing manager doesn't expose what you need:** first check if
   you're looking in the right manager. If genuinely missing, prefer a
   small additive manager API over duplicating logic in the gateway.
3. **A characterization test fails on existing Unciv behavior you didn't
   touch:** pause and inspect the failure before changing mature manager
   behavior. Follow `docs/characterization-guide.md` before acting.
