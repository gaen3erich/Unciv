# Characterization Tests Guide - LLM Gateway

This guide is about a specific kind of test that protects us when we call
into mature Unciv code: **characterization tests**. They photograph what
existing managers (`TechManager`, `CityConstructions`, `TurnManager`, ...)
do today, so that if their behavior shifts under our feet, we hear about
it instead of silently inheriting the change.

For tests of our own gateway code, specifications of what it *should* do,
see `testing-guide.md`. The two are complementary but have very different
semantics. Mixing them up causes the wrong reaction to failures.

---

## 1. The mental model

A regular test says: **"this is what the code should do."** When it fails,
the code is wrong.

A characterization test says: **"this is what the code did when we
captured it."** When it fails, the behavior changed, which might be a bug
we just caught, a fix someone made upstream, or a deliberate update we now
need to acknowledge.

The right reaction to a red characterization test is **investigate first,
edit second**. Never the reverse.

Why does this matter for the gateway? We do not own `TechManager` or
`CityConstructions`. We call them. If we refactor or update Unciv and
their behavior shifts, our gateway's downstream effects shift too, and
our regular tests, which use the same managers, may not catch the change.
Characterization tests are the seatbelt for that scenario.

---

## 2. Where they live

```text
tests/src/com/unciv/logic/llm/characterization/
    _TEMPLATE.kt
    tech/
        TechManagerCharacterization.kt
        __snapshots__/
    construction/
        CityConstructionsCharacterization.kt
        __snapshots__/
    turn/
        GameInfoNextTurnCharacterization.kt
        __snapshots__/
```

The directory structure mirrors the **Unciv** class under test, not our
gateway code. That makes it obvious which areas are protected and which
are not. A glance at the directory is a coverage map.

When characterization coverage grows, keep the explicit coverage map in:

```text
docs/characterization-index.md
```

Known captured oddities should be logged in:

```text
docs/known-behaviors.md
```

If those files do not exist yet, create them with the first test that
needs them.

---

## 3. Reading a failure

When a characterization test goes red, work through it in this order:

**1. Read the test's docstring.** It records what behavior was captured,
when, and why. This is the most important context: it tells you what
"unchanged" was supposed to mean.

**2. Look for `// SMELL:` markers** in the test body. They flag captured
oddities. Cross-reference `docs/known-behaviors.md` when it exists.

**3. Check linked ADRs, tickets, or PRs.** Docstrings include references
when available; chase them. Sometimes the answer is "yes, we changed this
on purpose."

**4. Decide what kind of change you're looking at:**

- **Intentional update**: Unciv fixed a bug we captured, or we deliberately
  changed behavior in this PR. Update the expected value or snapshot.
  Explain the change in the commit message. If the change resolves a
  `SMELL`, remove the marker and update `known-behaviors.md`.
- **Unintended regression**: our change had a side effect we did not
  expect. Revert or rework the change. The test caught what it was
  supposed to catch.
- **Unclear**: leave the test red and ask. A failing characterization test
  is information, not a blocker to be silenced.

**5. Never "fix" by deleting or by editing the expected value to match
without understanding why.** That removes the seatbelt and ships the
change unprotected. If the seatbelt is loud and the change is right, the
update is one commit; if the seatbelt is loud and the change is wrong,
that one commit just saved you.

---

## 4. The template

Every characterization test starts from
`tests/src/com/unciv/logic/llm/characterization/_TEMPLATE.kt`:

```kotlin
/**
 * CHARACTERIZATION TEST
 *
 * Captures the current behavior of <UncivClass>.<method>(...)
 * so the gateway can rely on it.
 *
 * Captured on:  YYYY-MM-DD
 * Captured by:  <name or @handle>
 * Source:       <how inputs were chosen: production logs / synthetic /
 *               boundary exploration / etc.>
 * Status:       FAITHFUL | SMELL
 * Notes:        <if SMELL, one-line description and pointer to
 *               known-behaviors.md section>
 * Related:      <ADR, ticket, PR>
 */
class TemplateCharacterization : GdxTestRunner() {

    @Test
    fun `<short_sentence_describing_captured_behavior>`() {
        // ARRANGE: minimal fixture that reproduces the situation.
        val gameInfo = TestGameBuilder.smallGame()
        val civ = gameInfo.getCurrentPlayerCivilization()

        // ACT: call the Unciv code whose behavior we're photographing.
        val actual = civ.tech.canBeResearched("Pottery")

        // ASSERT: exact observed value. Not what you think it should be.
        //
        // If you're writing this for the first time:
        //   1. Put a deliberately wrong expected value.
        //   2. Run the test.
        //   3. Copy the actual value the test printed.
        //   4. Paste it as the expected value.
        //   5. Test goes green. You've now captured reality, not intent.
        assertEquals(true, actual)
    }
}
```

Use this template literally. Copy the docstring fields and fill them in.
The fields exist so a future reader can reconstruct why the test exists
without having to dig.

---

## 5. Writing one: the procedure

The procedure is mechanical on purpose. It stops us from accidentally
writing a regular test in characterization clothing.

**Step 1. Pick the smallest piece of Unciv behavior you're about to depend
on.** If the gateway is about to call `civ.tech.canBeResearched` on a tech
that is already researched, that is a single behavior worth capturing.

**Step 2. Build the minimum fixture.** Use `TestGameBuilder` from the
regular test infrastructure. The fixture should set up exactly the
situation that triggers the behavior, no more.

**Step 3. Run the Unciv code under test.** Do not write the final assertion
yet. Print the result, or use a deliberately wrong assertion that will fail
loudly with the actual value:

```kotlin
assertEquals("PLACEHOLDER - replace with observed value", actual.toString())
```

**Step 4. Copy the observed value into the assertion.** This is the
characterizing step. Your expected value is the value the code produced,
period. You are not allowed to improve it.

**Step 5. If something about the observed value smells off**, leave the
assertion as-is, add a `// SMELL:` comment with a short description, and
add an entry to `docs/known-behaviors.md`. Do not edit the code or the
assertion to make it "right." A captured smell is data; a silenced smell
is technical debt.

**Step 6. Update `docs/characterization-index.md`** with the new coverage
entry if that file exists, or create it when this is the first
characterization suite.

---

## 6. A worked example

We're about to write `LlmActionCollector` and we plan to call
`civ.tech.canBeResearched(techName)` to filter candidate techs. Before we
build on that, we capture what it does:

```kotlin
/**
 * CHARACTERIZATION TEST
 *
 * Captures TechManager.canBeResearched(...) behavior on:
 *   - a tech with all prerequisites met
 *   - a tech missing prerequisites
 *   - a tech already researched
 *   - a tech name that doesn't exist in the ruleset
 *
 * Captured on:  2026-05-22
 * Captured by:  nick
 * Source:       Boundary exploration: covers the cases the
 *               LlmActionCollector will route through.
 * Status:       FAITHFUL for cases 1-3, SMELL for case 4
 *               (silent false instead of exception; see known-behaviors.md
 *               #techmanager-unknown-tech-silent).
 * Related:      MVP plan section 2 "Collect legal research actions"
 */
class TechManagerCanBeResearchedCharacterization : GdxTestRunner() {

    @Test
    fun `canBeResearched is true for a tech with all prereqs met`() {
        val gameInfo = TestGameBuilder.smallGame()
        val civ = gameInfo.getCurrentPlayerCivilization()

        val actual = civ.tech.canBeResearched("Pottery")

        assertEquals(true, actual)
    }

    @Test
    fun `canBeResearched is false for a tech with missing prereqs`() {
        val gameInfo = TestGameBuilder.smallGame()
        val civ = gameInfo.getCurrentPlayerCivilization()

        // Philosophy normally requires multiple prerequisites unmet at start.
        val actual = civ.tech.canBeResearched("Philosophy")

        assertEquals(false, actual)
    }

    @Test
    fun `canBeResearched is false for a tech already researched`() {
        val gameInfo = TestGameBuilder.smallGame()
        val civ = gameInfo.getCurrentPlayerCivilization()
        civ.tech.addTechnology("Pottery", showNotification = false)

        val actual = civ.tech.canBeResearched("Pottery")

        assertEquals(false, actual)
    }

    @Test
    fun `canBeResearched returns false silently for unknown tech name`() {
        val gameInfo = TestGameBuilder.smallGame()
        val civ = gameInfo.getCurrentPlayerCivilization()

        val actual = civ.tech.canBeResearched("DefinitelyNotARealTech")

        // SMELL: returns false rather than throwing. Means the collector
        // cannot distinguish "this tech doesn't exist" from "this tech
        // exists but isn't researchable." See known-behaviors.md
        // #techmanager-unknown-tech-silent.
        assertEquals(false, actual)
    }
}
```

The fourth test captures a real smell: `false` for an unknown name is
ambiguous. We did not try to fix it; we wrote it down. Now
`LlmActionCollector` can be designed knowing this is what it gets, and the
smell is tracked for a future deliberate fix.

The assertions use the simplest comparison that works. We are not
verifying behavior is good, only that it is unchanged. Resist the urge to
assert more than the photograph contains.

---

## 7. Golden-master / snapshot tests

For Unciv behaviors that produce structured output, such as a city's queue
after a sequence of operations or a serialized `GameInfo` after a round
trip, comparing field-by-field can become brittle. Snapshot tests serialize
the output to a canonical string and compare:

```kotlin
@Test
fun `addToQueue then chooseNextConstruction produces this queue state`() {
    val gameInfo = TestGameBuilder.gameWithFoundedCity()
    val city = gameInfo.getCurrentPlayerCivilization().cities.first()

    city.cityConstructions.addToQueue("Monument")
    city.cityConstructions.addToQueue("Granary")

    val snapshot = SnapshotFormatter.cityConstructionsState(city.cityConstructions)
    assertMatchesSnapshot("queue_after_two_adds", snapshot)
}
```

Rules for snapshots in this project:

- **Use human-readable, diffable formats.** YAML or pretty-printed JSON
  with sorted keys. Never raw `toString()` of complex objects, never
  binary, never single-line JSON.
- **Keep snapshots next to the test**, in `__snapshots__/`. Co-location
  means behavior changes and snapshot changes show up in the same diff
  area.
- **Sort everything that has no inherent order.** Sets and hashmap keys
  must be stable.
- **Strip volatile fields** such as timestamps, game IDs, and generated
  UUIDs before snapshotting. The snapshot should be stable across runs.

When a snapshot test fails, the diff is the most useful artifact. Review
it like a code review.

---

## 8. SMELL protocol

A SMELL is a captured behavior that is preserved as-is but flagged as
suspicious.

**In the test:** add a `// SMELL:` comment near the assertion with a
one-line description and a pointer to `known-behaviors.md`. Do not change
the assertion or the code.

**In the docstring:** set `Status: SMELL` and add a `Notes:` line.

**In `docs/known-behaviors.md`:** add an entry under the relevant module
with:

- the behavior in one paragraph;
- why we're preserving it, usually because changing it now is risky or out
  of scope;
- when to revisit, such as a milestone, refactor, or specific signal;
- owner, meaning who decides when to revisit.

A SMELL is not a TODO. It is a deliberate "we know about this, here's the
plan, here's who owns it." Without the protocol, captured smells become
invisible debt.

---

## 9. What characterization tests are not for

They are not a substitute for regular tests. Common confusions:

**"I'm writing the gateway's `LlmActionApplier`, should this be a
characterization test?"** No. Gateway code is our code; tests for it are
specifications (`testing-guide.md`). Characterization tests are for the
Unciv code the applier calls into.

**"This test asserts the current behavior of the gateway."** Then it is a
regular test. The distinction is not "what kind of assertion"; it is
"whose code is being photographed." Code we do not control gets
characterization; code we do gets specification.

**"I want to lock in behavior so nobody changes it."** Characterization
tests are not a sign-off mechanism. If you want a behavior to be
guaranteed forever, write a regular test that says so, with the rationale
in its name. Characterization tests are explicitly defeasible: they are
allowed to change with deliberate updates.

**"I don't have time to write proper tests, characterization is faster."**
It is the wrong tool. Reach for the template here only when the thing being
tested is existing Unciv behavior we're newly depending on.

---

## 10. When to add one

The trigger is not "every time I write code." Add a characterization test
when:

- We're about to call a Unciv method or manager the gateway has not called
  before. Capture the behavior at the boundaries we rely on.
- We're refactoring gateway code in a way that exercises an existing
  manager differently than before.
- We notice an odd Unciv behavior during exploratory work and want to pin
  it before anything else relies on it.
- A regression report says "Unciv version X.Y changed how Z behaves";
  characterize the new behavior so we own the migration explicitly.

If none of these apply, the regular tests in `testing-guide.md` are
probably what you want.

---

## 11. Checklist before adding/updating

Before opening a PR that adds or modifies characterization tests:

- The docstring has all template fields filled in: date, source, status,
  related.
- Expected values are observed, not desired.
- Any `SMELL` has a corresponding entry in `docs/known-behaviors.md`.
- `docs/characterization-index.md` is updated for new coverage.
- If a snapshot changed, the diff is reviewed in the PR description with a
  one-line "intentional because ..." or "regression, reverting in this PR."
- The test is in `tests/src/com/unciv/logic/llm/characterization/`, not
  mixed with regular tests.
