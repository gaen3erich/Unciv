# Testing Guide - LLM Gateway

This guide covers **regular tests** for the gateway: unit tests, smoke
tests, and serialization round-trips. Tests that capture existing Unciv
behavior for safe reuse are characterization tests; see
`characterization-guide.md`.

The distinction matters. Regular tests express what the gateway **should**
do: they are a specification, written by us, for our own code. They fail
when the gateway is wrong. Characterization tests photograph what Unciv
**currently does**: they fail when behavior we depend on shifts, which may
or may not be a problem.

---

## 1. Where tests live

```text
tests/src/com/unciv/logic/llm/
    LlmGatewayResearchTests.kt
    LlmGatewayConstructionTests.kt
    LlmGatewayEndTurnTests.kt
    RandomPolicySmokeTests.kt
    LlmObservationTests.kt
    LlmActionApplierTests.kt
    serialization/
        GatewayRoundTripTests.kt
```

The package mirrors the production package (`com.unciv.logic.llm`). When
you add a new gateway file, add the corresponding test file at the same
relative path. Agents and humans both navigate by mirroring.

Characterization tests, if added, should live in a sibling directory and
get their own guide:

```text
tests/src/com/unciv/logic/llm/characterization/
```

---

## 2. The shape of a good test

Every test in this codebase tells a small story: **here's a setup, here's
the action, here's what we expect, here's why we care**. The why is
usually implicit in the test name, but a one-line comment helps when the
assertion is not obvious.

A complete example:

```kotlin
class LlmGatewayResearchTests : GdxTestRunner() {

    @Test
    fun `scripted ChooseTechnology sets techsToResearch and does not advance turn`() {
        // Setup: small game with a known starting civ.
        val gameInfo = TestGameBuilder.smallGame(humanCivCount = 1)
        val civ = gameInfo.getCurrentPlayerCivilization()
        val initialTurn = gameInfo.turns
        val initialPlayer = gameInfo.currentPlayer

        // A tech we know is researchable from the starting position.
        val targetTech = "Pottery"
        check(civ.tech.canBeResearched(targetTech)) {
            "Test fixture assumption broken: $targetTech not researchable from start"
        }

        val policy = ScriptedPolicy(
            actions = listOf(
                LlmLegalAction.ChooseTechnology(
                    id = "choose-pottery",
                    techName = targetTech
                )
            )
        )
        val gateway = LlmGateway(policy = policy)

        // Action.
        val result = gateway.step(gameInfo)

        // Expectation.
        assertEquals(targetTech, civ.tech.techsToResearch.firstOrNull())
        assertTrue(result.appliedActions.any { it is LlmLegalAction.ChooseTechnology })
        assertTrue(result.rejectedActions.isEmpty())

        // Why we care: choosing research must not silently advance the turn.
        assertEquals(initialTurn, gameInfo.turns)
        assertEquals(initialPlayer, gameInfo.currentPlayer)
    }
}
```

Notice the structure:

- **Three named regions**: Setup, Action, Expectation. Even without
  comments, the blank lines and grouping make it scannable.
- **Fixture assumptions are explicit.** The `check(...)` call says "this
  test assumes Pottery is researchable from start." If a ruleset change
  breaks that assumption, the test fails loudly with the reason instead
  of mysteriously asserting `null != "Pottery"`.
- **Multiple assertions, each meaningful.** We check the state the
  gateway changed and the state it should not have changed. The "didn't
  advance turn" assertion captures the "no hidden auto-end-turn" rule
  from `AGENTS.md`.
- **Backtick names.** Kotlin tests read better with sentence-style names.
  The name describes both what was done and what is expected.

---

## 3. Test fixtures: how to make small games

Unciv games are heavy by default: full ruleset, full map, multiple civs.
For unit tests, we want the smallest viable game.

**Use a builder, not raw `GameStarter` calls.** A test-local
`TestGameBuilder` keeps fixtures consistent and lets us tighten them in
one place:

```kotlin
object TestGameBuilder {

    /** Minimal single-civ game on a tiny map. Deterministic seed. */
    fun smallGame(humanCivCount: Int = 1, seed: Long = 1L): GameInfo {
        val setup = GameSetupInfo().apply {
            mapParameters.mapSize = MapSize(MapSize.Tiny)
            mapParameters.seed = seed
            gameParameters.players = buildPlayers(humanCivCount)
            // ... other minimal config
        }
        val game = GameStarter.startNewGame(setup)
        game.setTransients() // belt-and-braces; GameStarter does it, loaders may not
        return game
    }

    /** Game with at least one city, ready to test construction actions. */
    fun gameWithFoundedCity(): GameInfo { TODO() }
}
```

**Always seed RNG.** Map generation, starting positions, and barbarian
placement all use RNG. A fixed seed makes the test deterministic. When a
test fails, you want it to fail the same way on every machine.

**Bootstrap the global state once per test class.** Several Unciv
subsystems read `UncivGame.Current.settings` and the ruleset cache. Inherit
from `GdxTestRunner` in `tests/src/com/unciv/testing/`, which handles the
`HeadlessApplication` setup. Do not reinvent that setup.

**If a test needs a saved game**, prefer building one with
`TestGameBuilder` and serializing it inside the test, not committing a
binary save file. Save formats can drift; in-test construction stays
correct because it goes through production paths.

---

## 4. Test categories - what we cover and how

The gateway has a small surface, but each surface has several edges.

### 4.1 Happy-path action tests

For each `LlmLegalAction` variant, write one test that demonstrates the
end-to-end happy path: build observation, script the action, apply it, and
assert state changed correctly.

These live in:

- `LlmGatewayResearchTests.kt` - `ChooseTechnology` happy paths
- `LlmGatewayConstructionTests.kt` - `ChooseCityConstruction` happy paths
- `LlmGatewayEndTurnTests.kt` - `EndTurn` happy paths

### 4.2 Validation tests

The gateway's validation logic is high-leverage: it protects Unciv from a
misbehaving policy. Each rule from the MVP plan gets a test:

```kotlin
@Test
fun `unknown technology is rejected with a reason`() { /* ... */ }

@Test
fun `already-researched non-repeatable tech is rejected`() { /* ... */ }

@Test
fun `construction not buildable in selected city is rejected`() { /* ... */ }

@Test
fun `action targeting missing city is rejected`() { /* ... */ }

@Test
fun `online multiplayer game is rejected by default`() { /* ... */ }
```

Each of these asserts two things:

1. The action appears in `result.rejectedActions` with a non-empty reason.
2. The game state is unchanged compared to before the call.

The second assertion is the important one: rejection means no mutation.
Test it explicitly; do not infer it.

### 4.3 Smoke tests with `RandomPolicy`

These exist to catch crashes and invariant violations we did not think to
write specific tests for:

```kotlin
@Test
fun `random policy over 5 steps applies only legal actions`() {
    val gameInfo = TestGameBuilder.smallGame()
    val policy = RandomPolicy(seed = 42L)
    val gateway = LlmGateway(policy = policy)

    val allApplied = mutableListOf<LlmLegalAction>()
    repeat(5) {
        val result = gateway.step(gameInfo)
        // Every applied action was legal at apply-time.
        for (action in result.appliedActions) {
            assertTrue(
                "Applied action $action was not in legalActions at step $it",
                result.observationLegalActionIds.contains(action.id)
            )
        }
        allApplied += result.appliedActions
    }

    // Did something happen: guards against silent no-op regressions.
    assertTrue("RandomPolicy produced zero applied actions in 5 steps", allApplied.isNotEmpty())
}
```

Keep step counts low, usually 5 to 10 or less. `nextTurn(null)` triggers
full AI for other civs and can make tests slow.

### 4.4 Serialization round-trip tests

These catch missing transient initialization, a class of bug that is
silent until production:

```kotlin
@Test
fun `gateway works on a deserialized game`() {
    val original = TestGameBuilder.smallGame()
    val serialized = UncivFiles.gameInfoToString(original)
    val restored = UncivFiles.gameInfoFromString(serialized)
    // gameInfoFromString already calls setTransients(); this checks that
    // the gateway does not reach into anything it did not restore.

    val policy = ScriptedPolicy(actions = listOf(LlmLegalAction.EndTurn()))
    val result = LlmGateway(policy).step(restored)

    assertTrue(result.appliedActions.any { it is LlmLegalAction.EndTurn })
}
```

One round-trip test per major action variant is enough. We do not need to
re-test gateway logic here, only that deserialized games work.

---

## 5. Determinism is a feature

The core gateway is synchronous and deterministic by design. Tests should
preserve that.

**Always seed RNG.** Seed both `RandomPolicy` and map generation. A test
that passes 99 times out of 100 is a test that does not pass.

**Avoid wall-clock time.** If gateway code needs time, which it should not
for MVP, inject a `Clock` so tests can pin it.

**Avoid filesystem state.** Tests should not write to or read from
arbitrary disk paths. Serialization round-trip tests use in-memory strings,
not files.

**One assertion per concept, not one per test.** A test can have several
related assertions: state changed correctly, invariants held, and nothing
else mutated. That is one concept. Split only when failures would point to
different causes.

---

## 6. Testing policies specifically

`LlmPolicy` is the extension point. Its dummy implementations are tested
two ways.

**`ScriptedPolicy` tests** are mechanical: given an action list, verify the
policy returns exactly that list, in order, ignoring the observation.
Keep them short and exhaustive:

```kotlin
@Test
fun `ScriptedPolicy returns scripted actions in order`() {
    val a = LlmLegalAction.EndTurn(id = "et1")
    val b = LlmLegalAction.EndTurn(id = "et2")
    val policy = ScriptedPolicy(actions = listOf(a, b))
    val observation = anyObservation()

    val first = policy.chooseActions(observation)
    val second = policy.chooseActions(observation)

    assertEquals(listOf(a), first)   // one per call
    assertEquals(listOf(b), second)
}
```

Decide the consumption semantics (one per call or all at once) and lock
them in tests. Either is fine, but it must be consistent.

**`RandomPolicy` tests** check properties, not specific outputs:

```kotlin
@Test
fun `RandomPolicy only returns actions present in observation legalActions`() {
    val observation = observationWithLegalActions(
        LlmLegalAction.EndTurn(),
        LlmLegalAction.ChooseTechnology(id = "p", techName = "Pottery")
    )
    val policy = RandomPolicy(seed = 7L)

    repeat(20) {
        val choices = policy.chooseActions(observation)
        for (c in choices) {
            assertTrue(observation.legalActions.any { it.id == c.id })
        }
    }
}

@Test
fun `RandomPolicy with same seed is reproducible`() {
    val observation = observationWithLegalActions(/* ... */)
    val a = RandomPolicy(seed = 99L).chooseActions(observation)
    val b = RandomPolicy(seed = 99L).chooseActions(observation)
    assertEquals(a, b)
}
```

When a real LLM-backed policy eventually exists, it should follow the same
pattern: policies are tested against observations in isolation; the gateway
is tested against scripted policies. The test suite should never require a
live LLM.

---

## 7. Negative tests are first-class

Treat "this should be rejected" as the same kind of test as "this should
be applied." For every legal action variant, include at least one negative
test:

- Action references a thing that does not exist: unknown tech, missing city.
- Action would violate a game rule: research already-researched tech, build
  something not buildable.
- Action arrives in a state that forbids it: online multiplayer when not
  opted in.
- Action conflicts with another in the same batch: two research selections
  per gateway step, two constructions per city per step.

Negative tests catch the failures that hurt most in production: the policy
emits garbage and the gateway dutifully applies it.

---

## 8. Running tests

The whole suite:

```bash
./gradlew tests:test
```

A single class:

```bash
./gradlew tests:test --tests "com.unciv.logic.llm.LlmGatewayResearchTests"
```

A single test method:

```bash
./gradlew tests:test --tests "com.unciv.logic.llm.LlmGatewayResearchTests.scripted ChooseTechnology sets techsToResearch and does not advance turn"
```

Tests run on a headless LibGDX application via `GdxTestRunner`. If you see
initialization errors for fonts, skin, or asset loading, the test class is
probably missing `: GdxTestRunner()` inheritance or equivalent setup.

---

## 9. When a test feels hard to write

That is usually a design signal. Common patterns:

**"I need to mock six things to test one method."** The gateway is likely
doing too much. Split it: collector, policy, and applier should each be
independently testable. `LlmGateway` itself should orchestrate them and get
thin integration tests.

**"I can't reach the state I want to test."** The test fixture is wrong,
not the production code. Add a `TestGameBuilder` method that constructs
exactly the situation you need.

**"The test passes locally but fails in CI."** Almost always
non-determinism: unseeded RNG, ordering dependence on a `HashMap`, or
reliance on file paths. Hunt the source; do not add retries.

**"I want to assert something about logs/notifications."** That is a
signal Unciv code emits side effects we would rather have as return
values. For the gateway, prefer making information flow back through
`LlmDecisionResult` instead of relying on notification capture.

---

## 10. Checklist before opening a PR

- All new behavior has at least one test that would fail without it.
- New action variants have happy-path, validation, and round-trip tests.
- Tests use seeded RNG and `TestGameBuilder`, not ad-hoc setup.
- `./gradlew tests:test` passes locally.
- If a test exercises a previously untouched Unciv code path, consider
  whether a characterization test is also needed.
- Test names describe behavior, not implementation.
