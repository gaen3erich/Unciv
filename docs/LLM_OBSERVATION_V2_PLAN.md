# Observation v2 — Economy Plan

This plan extends `LlmObservation` from `llm-obs/v1` to `llm-obs/v2` by adding an economy block to the civ-level snapshot. v2 is **additive** with respect to v1: every field present in a v1 observation is still present in a v2 observation, in the same place, with the same semantics.

The goal: an LLM (or a heuristic policy) reading a v2 observation should be able to answer "can I afford to rush-buy this?", "am I about to enter unhappiness?", "do I have iron for swordsmen?" without consulting anything outside the observation.

## 1. Что добавляем

Top-level new fields on `LlmObservation`:

- `economy: LlmEconomy` — civ-wide stockpiles and per-turn deltas.
- `resources: LlmResources` — current strategic and luxury resource counts.

That's it. No new fields on `LlmCityObservation` in this stage (per-city economy is part of v3 map/cities, not v2).

Pre-existing top-level fields `sciencePerTurn` and `productionPerTurn`-in-city are **kept as deprecated duplicates** for v2 (per the v1 plan's "additive v2" decision). Their values inside `economy.sciencePerTurn` and city's `productionPerTurn` field must match. A future breaking bump (`llm-obs/v3` if we decide to clean them up at that boundary, or a separate cleanup version) removes the top-level `sciencePerTurn`. Not now.

## 2. Предлагаемые файлы/классы

Core (new):

```text
core/src/com/unciv/logic/llm/LlmEconomy.kt
core/src/com/unciv/logic/llm/LlmResources.kt
```

Изменения в существующих файлах:

```text
core/src/com/unciv/logic/llm/LlmObservationVersion.kt     (CURRENT → "llm-obs/v2")
core/src/com/unciv/logic/llm/LlmObservation.kt            (добавить economy, resources)
core/src/com/unciv/logic/llm/LlmActionCollector.kt        (наполнение economy/resources)
core/src/com/unciv/logic/llm/io/LlmJsonCodec.kt           (сериализация новых полей)
```

Tests:

```text
tests/src/com/unciv/logic/llm/observation/ObservationV2EconomyTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV2ResourcesTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV2JsonShapeTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV2AdditiveTests.kt
```

## 3. Структуры данных

```kotlin
data class LlmEconomy(
    // Stockpiles (current totals, not per-turn deltas)
    val gold: Int,
    val storedCulture: Int,
    val storedFaith: Int,
    val happiness: Int,           // CURRENT happiness level, NOT delta — see notes

    // Per-turn deltas (what the player will gain or lose next turn)
    val goldPerTurn: Int,
    val sciencePerTurn: Int,
    val culturePerTurn: Int,
    val faithPerTurn: Int,
    val happinessPerTurn: Int     // Delta — see notes
)
```

```kotlin
data class LlmResources(
    // Strategic resources Unciv reports for the civ, e.g. Iron, Horses, Oil.
    // Includes only resources the civ currently has access to (count > 0 OR known type).
    // Sorted by resource name, ascending.
    val strategic: List<LlmResourceEntry>,

    // Luxury resources: relevant for happiness/trading. Same shape as strategic.
    val luxury: List<LlmResourceEntry>
)

data class LlmResourceEntry(
    val name: String,
    val amount: Int
)
```

Why two lists, not one map:

- Lists preserve order for deterministic JSON. Maps in Unciv come back as `HashMap` and would need explicit sorting anyway.
- Strategic vs Luxury have different policy implications. Keeping them separate at the data-class level forces collectors to classify, which is the right place to do it.
- A single `Map<String, Int>` collapses to one JSON object but loses the type partition, and we'd have to look up each resource's type at read time. That's a worse contract for downstream consumers.

What we deliberately **do not** add in v2:

- Bonus resources (Wheat, Cattle, etc.). They are per-tile yield boosts, not stockpiles. Belong to v3 (map/cities).
- Resource trade offers in flight. Belong to v4 (diplomacy).
- Happiness breakdown (which buildings give how much). The single `happiness` + `happinessPerTurn` number is the v2 contract; the breakdown is a nice-to-have for later.
- Treasury projections ("how many turns until I can afford X"). Belong to a derived/computed layer, not raw observation.
- Score, era, government — these are not "economy", they are civ-state. Separate later stage if needed.

## 4. Как наполнять (методы Unciv)

### Stockpiles

| Field             | Source                                            | Notes |
| ----------------- | ------------------------------------------------- | ----- |
| `gold`            | `civ.gold`                                        | Already `Int`. |
| `storedCulture`   | `civ.policies.storedCulture`                      | `Int`. Used to determine when the next policy can be unlocked. |
| `storedFaith`     | `civ.religionManager.storedFaith`                 | `Int`. Used for pantheons/great prophets. |
| `happiness`       | `civ.getHappiness()`                              | Returns current happiness level (sum of breakdown components). NOT the per-turn delta. |

Important pitfall: `civ.stats.happiness` (a field on `Stats`) is also an `Int`-like value, and `civ.getHappiness()` is defined as `= stats.happiness`. So they appear identical at the Civilization level — but **the `happiness` field inside `civ.stats.statsForNextTurn` is a per-turn delta**, because `statsForNextTurn` is a *next-turn* `Stats` object built fresh, where the `.happiness` slot holds the delta, not the level. This naming collision is the source of bugs. The test plan in section 6 pins this down.

### Per-turn deltas

All from `civ.stats.statsForNextTurn` (a `Stats` instance):

| Field              | Source                                          | Conversion |
| ------------------ | ----------------------------------------------- | ---------- |
| `goldPerTurn`      | `civ.stats.statsForNextTurn.gold`               | `Float` → `roundToInt()` |
| `sciencePerTurn`   | `civ.stats.statsForNextTurn.science`            | `Float` → `roundToInt()` |
| `culturePerTurn`   | `civ.stats.statsForNextTurn.culture`            | `Float` → `roundToInt()` |
| `faithPerTurn`     | `civ.stats.statsForNextTurn.faith`              | `Float` → `roundToInt()` |
| `happinessPerTurn` | `civ.stats.statsForNextTurn.happiness`          | `Float` → `roundToInt()`. This is the value in the next-turn `Stats` object, not the current happiness level. |

We round to `Int` for the same reason as v1's `sciencePerTurn`: stable JSON output, no float-formatting variance across platforms, no false diffs in trace comparisons. If a downstream consumer needs sub-unit precision (unlikely for these stats), v3+ can introduce parallel `*Float` fields.

### Strategic / luxury resources

Source: `civ.getCivResourcesByName(): HashMap<String, Int>` returns all resources the civ has, by name, with positive **or zero or negative** amounts (depleted resources can be 0 or negative when over-used).

Classification: cross-reference each entry against `gameInfo.ruleset.tileResources[name].resourceType`:

- `ResourceType.Strategic` → `strategic` list.
- `ResourceType.Luxury` → `luxury` list.
- `ResourceType.Bonus` → skip (not in v2).

Resources missing from the ruleset (unlikely, but defensively): skip and log nothing. Do not throw. Trace shape must remain valid even on weird modded states.

Inclusion rule for v2: include the entry if `amount != 0`. Rationale — a `0` entry is noise; a negative entry (over-used Strategic) is genuinely informative and must be kept.

Sort by `name` ascending in each list. Determinism is part of the contract — see section 5.

## 5. Гарантии детерминизма

Same standard as v1. Two observations built from the same `GameInfo` must produce byte-identical JSON.

- `economy` — all fields are scalars, no order issue.
- `resources.strategic` and `resources.luxury` — sorted by `name` ascending.
- `LlmResourceEntry` — fields in fixed declaration order in the codec.

Negative test: shuffle `civ.getCivResourcesByName()` insertion order (test fixture) → observation JSON must be unchanged.

## 6. Тесты

`ObservationV2EconomyTests`:

- On a fixture civ with `gold=42`, assert `economy.gold == 42` and the JSON contains `"gold": 42` inside the economy block.
- `happiness` field reflects `civ.getHappiness()`, **not** `civ.stats.statsForNextTurn.happiness`. Test fixture sets these to different values to make the distinction observable. This is the bug-trap; if the field assignments are ever swapped, this fails loudly.
- `happinessPerTurn` reflects `civ.stats.statsForNextTurn.happiness`. Same fixture, opposite direction.
- `goldPerTurn`, `sciencePerTurn`, `culturePerTurn`, `faithPerTurn` all reflect their respective `statsForNextTurn` slots, rounded.
- `storedCulture` and `storedFaith` reflect their managers, not anything in `statsForNextTurn`.

`ObservationV2ResourcesTests`:

- Civ with Iron=3 and Wine=2 (Strategic + Luxury) and Wheat=5 (Bonus): observation has Iron in `strategic`, Wine in `luxury`, Wheat appears nowhere.
- Civ with depleted Iron (`amount = -1`): entry IS included with `amount = -1`. Inclusion rule is `!= 0`, not `> 0`.
- Civ with `amount = 0` for Coal: entry is skipped.
- Resources are sorted by name; shuffling source map does not change observation JSON.
- Resource missing from `ruleset.tileResources` does not throw; it is silently dropped.

`ObservationV2JsonShapeTests`:

- Encoded observation has top-level `schemaVersion == "llm-obs/v2"`.
- Encoded observation has both `economy` (object) and `resources` (object with `strategic` and `luxury` arrays).
- `economy` object has exactly these keys: `gold`, `storedCulture`, `storedFaith`, `happiness`, `goldPerTurn`, `sciencePerTurn`, `culturePerTurn`, `faithPerTurn`, `happinessPerTurn` — closed set, asserted by name comparison.
- Each `LlmResourceEntry` JSON object has exactly `{name, amount}`.

`ObservationV2AdditiveTests` (the v1→v2 contract test):

- Build a fixture, collect observation, encode to JSON.
- Confirm: every top-level field listed in `LLM_OBSERVATION_V1_PLAN.md` section 4 is still present with the same name and same type.
- Confirm: `sciencePerTurn` at top level equals `economy.sciencePerTurn` (duplicate-value invariant).
- Confirm: every `LlmCityObservation` still has its full v1 field set.

JSONL header tests (in the existing trace-writer test file):

- After this stage, header carries `observationSchema: "llm-obs/v2"`.
- Because `LlmJsonCodec` is still encode-only, do not add a committed v1 golden trace fixture yet. Instead, `ObservationV2AdditiveTests` verifies that the current encoded v2 observation still contains every v1 field with the same names and JSON shapes. Golden trace fixtures can wait until we add a decoder/replay path.

Test command:

```bash
./gradlew tests:test
```

## 7. Что НЕ трогать в v2

- Save format. No new fields on `GameInfo`/`Civilization`/`City`. v2 only reads existing managers.
- `LlmGateway`, `LlmActionApplier`, `LlmPolicy`. v2 changes the observation only; nothing in the decision/apply loop depends on the new fields.
- v1 field names/positions in JSON. v2 is additive — any rename of an existing v1 field IS a breaking change and must wait.
- Per-city economy. Tempting to add `gold`/`culture` per city → it's v3 city-level work. v2 is civ-aggregate only.
- Bonus resources. Defer to v3 with tile/map data, where their per-tile context makes them meaningful.
- Computed fields. No "turns until next policy", no "treasury runway", no "happiness will go negative in N turns". Observation is raw; computation belongs in policy/prompt layer.
- Trace file format (`schema: "llm-trace/v1"`). Unchanged.
- Decoder in `LlmJsonCodec`. Still encode-only.

## 8. Совместимость с v1-decision: что важно для existing-кода

- `LlmObservation` field additions are non-breaking for Kotlin if added with defaults? They are top-level, so all existing test constructors must pass them. Two options:
  1. Give `economy` and `resources` default values (`LlmEconomy.zero()`, empty lists). Minimal test churn, but hides "I forgot to fill it in" bugs in production paths.
  2. No defaults; explicitly fill them everywhere. More churn, but no silent zeros in production traces.
- Recommendation: **no defaults**. Production path always goes through `LlmActionCollector.buildObservation`, which fills them. Test fixtures explicitly construct minimal `LlmEconomy(0, 0, 0, 0, 0, 0, 0, 0, 0)` — annoying but obvious, and prevents an `LlmEconomy.zero()` from quietly leaking into a real trace because something forgot to populate it.

## 9. Definition of done

- `./gradlew tests:test` зелёный.
- JSONL header writes `observationSchema: "llm-obs/v2"`.
- Каждое observation в JSONL содержит `schemaVersion: "llm-obs/v2"`, `economy` object с 9 полями, `resources` object с `strategic` + `luxury` arrays.
- На фикстуре с разными `civ.getHappiness()` и `civ.stats.statsForNextTurn.happiness` оба значения попадают в правильные поля наблюдения (это самый ценный тест — он защищает от naming collision на уровне Stats).
- `sciencePerTurn` at top level == `economy.sciencePerTurn`. v1 поля не удалены.
- v1-fixture trace всё ещё читается без потерь полей.

## 10. Implementation order

1. `LlmEconomy.kt`, `LlmResources.kt` — pure data classes. Не требуют контекста, чистая компиляция.
2. Поля `economy` и `resources` в `LlmObservation` без default-значений → существующие тесты падают компиляцией. Это намеренно: ловим все места, где конструируется `LlmObservation` в тестах, и приводим их в порядок.
3. Наполнение в `LlmActionCollector.buildObservation(...)`. Один новый приватный helper `collectEconomy(civ)` и `collectResources(civ)`.
4. Bump `LlmObservationVersion.CURRENT` на `"llm-obs/v2"`. Это коммит-разделитель — после него все новые трейсы помечаются v2.
5. `LlmJsonCodec` — добавить кодирование `LlmEconomy`, `LlmResources`, `LlmResourceEntry`. Сохранить порядок полей.
6. Тесты: `ObservationV2EconomyTests`, `ObservationV2ResourcesTests`, `ObservationV2JsonShapeTests`, `ObservationV2AdditiveTests`.
7. Ручной smoke: прогнать CLI на тестовом save, открыть JSONL, глазами проверить header (`v2`), `economy` block, `resources` block.
