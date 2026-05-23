# Observation v3 — Map / Cities Plan

This plan extends `LlmObservation` from `llm-obs/v2` to `llm-obs/v3` by adding spatial and tactical context to each city: where it sits on the map, who is defending it, what enemies the civ can see nearby, what the workable footprint looks like, and how much pressure neighbors put on its borders.

v3 is **additive** with respect to v2: every field present in a v2 observation is still present in a v3 observation, in the same place, with the same semantics. Only `LlmCityObservation` grows; top-level `LlmObservation` stays the same shape.

v3 is also the **largest** step in this series. The main design tension is "useful enough for the LLM to reason about combat and city placement" vs "not exploding trace size into megabytes". The plan resolves that tension explicitly with **summaries, not full tile dumps** (see section 4).

## 1. Что добавляем

New fields on `LlmCityObservation`:

- `location: LlmHexCoord` — stable map coordinates of the city center.
- `health: LlmCityHealth` — current and max HP plus a few derived flags (is-being-attacked, is-low-hp).
- `defenders: LlmCityDefenders` — what military unit currently garrisons the city, plus a small "supporting units nearby" summary.
- `visibleEnemies: LlmVisibleEnemies` — count and brief description of enemy units the civ can currently see within a fixed radius of this city.
- `workableTilesSummary: LlmWorkableTilesSummary` — aggregated counts and yields over `city.getWorkableTiles()`, not a per-tile dump.
- `expansionPressure: LlmExpansionPressure` — culture-driven border expansion progress AND a measure of foreign pressure on this city's borders.

No new top-level fields on `LlmObservation`. All v3 additions are city-scoped.

## 2. Предлагаемые файлы/классы

Core (new):

```text
core/src/com/unciv/logic/llm/LlmHexCoord.kt
core/src/com/unciv/logic/llm/LlmCityHealth.kt
core/src/com/unciv/logic/llm/LlmCityDefenders.kt
core/src/com/unciv/logic/llm/LlmVisibleEnemies.kt
core/src/com/unciv/logic/llm/LlmWorkableTilesSummary.kt
core/src/com/unciv/logic/llm/LlmExpansionPressure.kt
core/src/com/unciv/logic/llm/LlmUnitBrief.kt
```

Изменения в существующих файлах:

```text
core/src/com/unciv/logic/llm/LlmObservationVersion.kt   (CURRENT → "llm-obs/v3")
core/src/com/unciv/logic/llm/LlmObservation.kt          (LlmCityObservation: 6 новых полей)
core/src/com/unciv/logic/llm/LlmActionCollector.kt      (наполнение per-city блоков)
core/src/com/unciv/logic/llm/io/LlmJsonCodec.kt         (сериализация новых типов)
```

Tests:

```text
tests/src/com/unciv/logic/llm/observation/ObservationV3LocationTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV3DefendersTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV3VisibleEnemiesTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV3WorkableTilesTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV3ResourceVisibilityTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV3ExpansionTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV3JsonShapeTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV3AdditiveTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV3SizeBudgetTests.kt
```

## 3. Структуры данных

### Coordinates

```kotlin
data class LlmHexCoord(val x: Int, val y: Int)
```

Direct mirror of Unciv's `HexCoord(x: Int, y: Int)` from `core/src/com/unciv/logic/map/HexMath.kt`. Trivial. Kept as a separate type so the v3 contract does not leak Unciv's `HexCoord` into our public-ish JSON schema.

### City health

```kotlin
data class LlmCityHealth(
    val current: Int,
    val max: Int,
    val isUnderAttack: Boolean,   // true if current < max
    val isCritical: Boolean       // true if current <= max / 4
)
```

`isUnderAttack` and `isCritical` are derived flags. They are redundant with the raw values, but writing them out makes downstream policies (and humans skimming traces) much faster — and they cost almost nothing in JSON.

### Defenders

```kotlin
data class LlmCityDefenders(
    val garrison: LlmUnitBrief?,           // unit on the city tile, military slot
    val supportingUnitCount: Int,          // friendly military units within distance 2 of the city, excluding garrison
    val supportingUnitTypeBreakdown: Map<String, Int>  // e.g. {"Warrior": 1, "Archer": 1}
)

data class LlmUnitBrief(
    val name: String,                      // e.g. "Warrior"
    val healthPercent: Int,                // 0..100
    val isInjured: Boolean,                // healthPercent < 100
    val movementLeft: Int                  // floor of currentMovement
)
```

The "supporting units" count uses a fixed radius (2 hexes). Larger radii blow up cost on big maps and rarely change tactical interpretation.

### Visible enemies

```kotlin
data class LlmVisibleEnemies(
    val totalCount: Int,                   // total visible enemy military units within radius
    val closestDistance: Int?,             // hex distance to nearest visible enemy, or null
    val byCiv: List<LlmEnemyCivCount>,     // per-enemy-civ aggregation
    val brief: List<LlmUnitBrief>          // up to N closest units, full info
)

data class LlmEnemyCivCount(
    val civName: String,
    val count: Int
)
```

"Enemy" = civ currently at war with the observing civ AND known to the observing civ. "Visible" = `tile in civ.viewableTiles`. Radius defaults to 5 hexes from city center.

`brief` is capped at **5 units** by default (the closest ones). That's enough for an LLM to plan a defense without ballooning the trace. The full count is still in `totalCount`.

What we deliberately omit:

- Enemy civilian units (settlers, workers). Tactical interest is low for v3; bring in v4 if needed.
- Enemy units outside the observing civ's vision but inside fog (last-seen positions). Out of scope.

### Workable tiles summary

```kotlin
data class LlmWorkableTilesSummary(
    val totalTiles: Int,
    val workedTiles: Int,                  // tiles a citizen is currently working
    val totalYields: LlmTileYields,        // sum across all workable tiles, integerized
    val resourceCounts: Map<String, Int>,  // resource name -> tile count, sorted, includes Bonus
    val improvementCounts: Map<String, Int>// improvement name -> tile count, sorted
)

data class LlmTileYields(
    val food: Int,
    val production: Int,
    val gold: Int,
    val science: Int,
    val culture: Int,
    val faith: Int,
    val happiness: Int
)
```

**No per-tile detail in v3.** That's the size-budget decision. If a per-tile observation is needed later (v5? a separate `tile-detail` schema?), it will be its own structure. v3 buys policies enough to answer "this city is food-rich but production-poor" without listing 36 tiles.

Resource counts include Bonus resources here (food/production tile bonuses like Wheat or Stone), unlike v2's `resources` block which only lists Strategic + Luxury **stockpiles**. The two views are intentionally different: v2 = "what does the civ have", v3 = "what's on the ground around this city". A test pins down that distinction.

### Expansion pressure

```kotlin
data class LlmExpansionPressure(
    val cultureStored: Int,
    val turnsToNextTile: Int?,             // null if no expansion in progress or culturePerTurn <= 0
    val ownedTiles: Int,                   // tiles currently owned by this city
    val foreignBorderTiles: Int,           // workable-or-adjacent tiles owned by another civ
    val nearestForeignCity: LlmNearbyCity?
)

data class LlmNearbyCity(
    val civName: String,
    val cityName: String,
    val distance: Int                      // hex distance, center-to-center
)
```

Two notions of "expansion pressure" merged into one block:

1. **Outgoing pressure** — our own culture-driven expansion: `cultureStored`, `turnsToNextTile`, `ownedTiles`. Mirrors what the `CityStatsTable` UI shows for "turns to expansion".
2. **Incoming pressure** — neighbors pushing against this city: `foreignBorderTiles` (how many adjacent tiles belong to someone else) and `nearestForeignCity` (the closest non-friendly settled point).

This intentionally collapses what could be two separate blocks into one. v3 already adds five fields per city; six was the limit before the JSON gets noisy.

## 4. Размер JSON и почему v3 — это summaries

A realistic mid-game civ has 6–10 cities. If we wrote per-tile yields for every workable tile (~36 per city), that's ~250–360 tile records per civ per turn, each with ~8 fields. Multiply by ~5 stats × 200 turns and a single random-policy trace easily passes 50 MB. JSONL line parsers slow to a crawl past ~10 MB lines.

v3 chooses **aggregates over enumeration** as the default. The contract gives:

- counts (`totalTiles`, `workedTiles`, `foreignBorderTiles`, `supportingUnitCount`, `totalCount`),
- sums (`totalYields`),
- distributions (`resourceCounts`, `improvementCounts`, `supportingUnitTypeBreakdown`, `byCiv`),
- a bounded sample (`brief: List<LlmUnitBrief>`, capped at 5).

That keeps each city's v3 block under ~1 KB even on dense maps. `ObservationV3SizeBudgetTests` enforces this — see section 6.

If a later level (v5+) wants per-tile detail, it can introduce `workableTiles: List<LlmTileDetail>` as a sibling of `workableTilesSummary`, additively. The summary stays.

## 5. Как наполнять (методы Unciv)

### Location

| Field      | Source              | Notes |
| ---------- | ------------------- | ----- |
| `location` | `city.location`     | `HexCoord(x: Int, y: Int)` — copy fields directly. |

### Health

| Field        | Source                  | Notes |
| ------------ | ----------------------- | ----- |
| `current`    | `city.health`           | `Int`. Game initializes to 200. |
| `max`        | `city.getMaxHealth()`   | `200 + Σ building.cityHealth`. Declared `internal`, but `com.unciv.logic.llm` is in the same `core` Gradle module, so it can be called directly — no fallback recomputation, no reflection test. |
| `isUnderAttack` | `current < max`      | Derived. |
| `isCritical` | `current <= max / 4`   | Derived. |

### Defenders

| Field                          | Source |
| ------------------------------ | ------ |
| `garrison`                     | `city.getCenterTile().militaryUnit` → `LlmUnitBrief(...)`, or `null`. |
| `supportingUnitCount`          | `city.getCenterTile().getTilesInDistance(2).flatMap { it.getUnits() }.filter { it.civ == civ && it.isMilitary() && it != garrisonUnit }.count()`. |
| `supportingUnitTypeBreakdown`  | Same filter, grouped by `unit.baseUnit.name`. Sorted by key. |

`LlmUnitBrief` fields:

| Field           | Source                                                                                           |
| --------------- | ------------------------------------------------------------------------------------------------ |
| `name`          | `unit.baseUnit.name`. The display name of the unit type (e.g. "Warrior", "Composite Bowman").     |
| `healthPercent` | `unit.health` (already 0..100 in Unciv).                                                          |
| `isInjured`     | `unit.health < 100`.                                                                              |
| `movementLeft`  | `unit.currentMovement.toInt()` (floor).                                                           |

### Visible enemies

Use `MapUnit.civ.isAtWarWith(civ)` for the war-state check rather than walking `civ.diplomacy.values`. It's simpler, doesn't depend on the `Diplomacy` map structure, and correctly handles edge cases where war state exists without the usual make-meet path.

```kotlin
private const val VISIBLE_ENEMY_RADIUS = 5

val center = city.getCenterTile()
val tilesInRadius = center.getTilesInDistance(VISIBLE_ENEMY_RADIUS)
val visibleEnemyUnits = tilesInRadius
    .filter { it in civ.viewableTiles }
    .flatMap { it.getUnits() }
    .filter { it.civ.isAtWarWith(civ) && it.isMilitary() }
    .toList()
```

From `visibleEnemyUnits`:

| Field             | Source |
| ----------------- | ------ |
| `totalCount`      | `visibleEnemyUnits.size`. |
| `closestDistance` | `visibleEnemyUnits.minOfOrNull { it.getTile().aerialDistanceTo(center) }`. |
| `byCiv`           | `groupingBy { it.civ.civName }.eachCount()` → sorted list of `LlmEnemyCivCount`, by `civName` asc. |
| `brief`           | `visibleEnemyUnits.sortedBy { it.getTile().aerialDistanceTo(center) }.take(5).map { it.toBrief() }`. |

`VISIBLE_ENEMY_RADIUS` and the `brief` cap (5) live as named constants in the collector file so a future tuning change is one edit, not a grep.

### Workable tiles summary

```kotlin
val workable = city.getWorkableTiles().toList()
val worked = workable.filter { city.isWorked(it) }
val yieldsSum = workable.map { it.stats.getTileStats(city, civ) }
    .fold(Stats()) { acc, s -> acc.apply { add(s) } }
```

| Field                | Source |
| -------------------- | ------ |
| `totalTiles`         | `workable.size`. |
| `workedTiles`        | `worked.size`. |
| `totalYields.*`      | `tile.stats.getTileStats(city, civ)` summed across `workable`; `Float` → `roundToInt()` per stat. |
| `resourceCounts`     | See "Resource visibility" below. Sorted by key. |
| `improvementCounts`  | `workable.mapNotNull { it.improvement }.groupingBy { it }.eachCount()`. Sorted by key. Pillaged status is **ignored** in v3 — a pillaged Farm still counts as "Farm". A pillage breakdown can be added additively later. |

If `city.getWorkableTiles()` is empty (very edge case — newly founded city tile turning?): emit zeros, do not throw.

#### Resource visibility (fog-of-war rule for tile resources)

This mirrors the fog-of-war rule for `visibleEnemies`: a tile resource only appears in `resourceCounts` if the civ has **already revealed** it. Without this rule, an Iron deposit on a workable tile would leak into the observation before the civ has researched Iron Working, giving the policy strictly more knowledge than a human in the same seat.

```kotlin
val visibleResource = { tile: Tile ->
    val res = tile.tileResource ?: return@visibleResource null
    if (civ.canSeeResource(res)) tile.resource else null
}
val resourceCounts = workable
    .mapNotNull(visibleResource)
    .groupingBy { it }
    .eachCount()
    .toSortedMap()
```

- `tile.resource: String?` — the resource name on the tile, if any.
- `tile.tileResource: TileResource` — the resolved ruleset object, used for the visibility check.
- `civ.canSeeResource(tileResource)` — the existing predicate Unciv uses to decide whether to draw the resource icon on the map for this civ. Reusing it keeps the observation aligned with what the player would actually see.

A tile with an Iron resource the civ has not yet revealed:

- contributes its **yields** to `totalYields` (the food/production bonus from terrain is visible).
- does NOT add an entry to `resourceCounts`.
- does NOT add anything resource-specific to `improvementCounts`.

This is a deliberate asymmetry: the civ feels the terrain in worked yields, but doesn't know what's hidden under it. Same rule a human would play under.

### Expansion pressure

| Field                  | Source |
| ---------------------- | ------ |
| `cultureStored`        | `city.expansion.cultureStored`. |
| `turnsToNextTile`      | If `city.cityStats.currentCityStats.culture <= 0` → `null`. Else: `ceil((city.expansion.getCultureToNextTile() - city.expansion.cultureStored) / city.cityStats.currentCityStats.culture).toInt().coerceAtLeast(1)`. `getCultureToNextTile()` is public — no formula replication. |
| `ownedTiles`           | `city.getTiles().count()`. |
| `foreignBorderTiles`   | Tiles bordering this city's owned tiles, owned by a different civ. See computation below. |
| `nearestForeignCity`   | `gameInfo.getCities().filter { it.civ != civ }.minByOrNull { it.getCenterTile().aerialDistanceTo(city.getCenterTile()) }` → null-safe `LlmNearbyCity`. |

```kotlin
val foreignBorderTiles = city.getTiles()
    .flatMap { it.neighbors }
    .distinct()
    .count { it.getOwner() != null && it.getOwner() != civ }
```

Rationale: this is "border pressure", not "proximity". A foreign tile inside the work radius but two hexes away from any tile we actually own does not press on our border — it's just nearby. Counting tiles that touch our boundary directly matches the intuitive meaning of `foreignBorderTiles`.

`nearestForeignCity` includes **all** foreign cities: majors, city-states, allies, enemies. v3 answers the geometric question "how close are my neighbors", not "how friendly". Hostility lives in v4 as part of the diplomacy block.

## 6. Гарантии детерминизма + budget

Determinism:

- All `Map<String, Int>` fields are sorted by key, ascending.
- `byCiv: List<LlmEnemyCivCount>` sorted by `civName`, ascending.
- `brief: List<LlmUnitBrief>` sorted by hex distance to city center, ascending; ties broken by `unit.id` (or by `unit.civ.civName + name` if `id` is not present on `MapUnit`).
- Cities continue to be sorted as in v1 (by `cityId` ascending). No change.

Size budget tests:

`ObservationV3SizeBudgetTests`:

- On a mid-game fixture (8 cities, ~30 enemies visible across the map, ~30 workable tiles per city), the encoded JSON for one observation must be **under 32 KB**.
- On a small fixture (2 cities, no enemies, 10 workable tiles each) — must be **under 4 KB**.
- A single `LlmCityObservation` must serialize to less than 2 KB on the mid-game fixture.
- `brief` arrays never exceed 5 elements.

These limits exist so a 200-turn random-policy trace fits comfortably under 10 MB. If we later need more detail, we add a parallel block, not inflate the summary.

## 7. Тесты

`ObservationV3LocationTests`:

- City founded at `(3, 5)` → observation has `location == {x:3, y:5}`.
- Two observations from the same `GameInfo` produce identical `location` (sanity).

`ObservationV3DefendersTests`:

- City with no military unit on center tile → `defenders.garrison == null`.
- City with a Warrior at full health → `garrison.name == "Warrior"`, `healthPercent == 100`, `isInjured == false`.
- City with a Warrior at 30 HP → `healthPercent == 30`, `isInjured == true`.
- Two friendly Archers within distance 2 → `supportingUnitCount == 2`, breakdown `{"Archer": 2}`. Garrison not double-counted.
- A foreign friendly unit (e.g. via open borders) within distance 2 → NOT counted as supporting. Only `it.civ == civ`.

`ObservationV3VisibleEnemiesTests`:

- Civ at peace with everyone → `visibleEnemies.totalCount == 0`, `closestDistance == null`, `byCiv` empty, `brief` empty.
- Civ at war with Civ B; one B-Warrior on a visible tile 3 hexes from city → `totalCount == 1`, `closestDistance == 3`, `byCiv == [{civName:"B", count:1}]`, `brief.size == 1`.
- Same setup but the enemy tile is fogged (not in `civ.viewableTiles`) → `totalCount == 0`. This is the most important test: invisible enemies are NOT in the observation.
- 10 enemy units in radius → `totalCount == 10`, `brief.size == 5` (capped), `brief` sorted by ascending distance.
- Enemy civilian unit (worker) on a visible tile → NOT counted. Only military.
- Enemy unit known to civ but city has not met that civ at all — shouldn't happen if `viewableTiles` is honored; assert behavior matches "filter by viewableTiles" not by "known civs".

`ObservationV3WorkableTilesTests`:

- City with no improvements and no resources → `resourceCounts == {}`, `improvementCounts == {}`, `totalTiles > 0`.
- City with 2 Wheat tiles → `resourceCounts == {"Wheat": 2}` (Bonus included here, unlike v2's stockpile view; Wheat is visible by default).
- `workedTiles <= totalTiles` always.
- `totalYields.food` equals the integerized sum of food yields across all workable tiles, not just worked tiles. Same for production/gold.
- Same `GameInfo` collected twice produces identical map ordering (keys sorted).
- A pillaged Farm tile still contributes "Farm" to `improvementCounts`.

`ObservationV3ResourceVisibilityTests` (zero-leak guard — the mirror of the fog-of-war test for enemies):

- City with an Iron tile in its workable footprint AND civ has NOT researched the revealing tech → `resourceCounts` does NOT contain Iron. The Iron tile still contributes its terrain yields to `totalYields`.
- Same civ, same city, after the revealing tech is researched → `resourceCounts` now contains Iron with the correct count.
- City with a Wheat tile (Bonus, visible from turn 0) → Wheat appears in `resourceCounts` regardless of any tech state.
- An Iron tile worked by the civ before reveal still contributes yields but NOT a `resourceCounts` entry — visibility, not workedness, gates the entry.

`ObservationV3ExpansionTests`:

- Newly founded city with 0 stored culture, `culturePerTurn > 0` → `cultureStored == 0`, `turnsToNextTile == ceil(cultureToNextTile / culturePerTurn)`.
- City with `culturePerTurn == 0` → `turnsToNextTile == null`.
- City surrounded by neutral terrain → `foreignBorderTiles == 0`, `nearestForeignCity` may be null if no other cities exist.
- City with two foreign tiles inside `workRange + 1` → `foreignBorderTiles == 2`.

`ObservationV3JsonShapeTests`:

- Encoded observation top-level `schemaVersion == "llm-obs/v3"`.
- Each `LlmCityObservation` has exactly the v1 fields PLUS `location`, `health`, `defenders`, `visibleEnemies`, `workableTilesSummary`, `expansionPressure` — closed set, asserted by name comparison.
- `health` object has `{current, max, isUnderAttack, isCritical}`, all `Int`/`Bool`.
- `defenders.garrison` is either `null` or an `LlmUnitBrief` object.
- `visibleEnemies.brief.size <= 5`.

`ObservationV3AdditiveTests` (the v2→v3 contract test):

- Build a fixture, collect observation, encode to JSON.
- Confirm every top-level field listed in v1 and v2 plans is still present with the same names, types, and JSON shape.
- Confirm `economy.sciencePerTurn` and top-level `sciencePerTurn` are still present and still equal (the v2 invariant).
- Confirm every `LlmCityObservation` still has its full v1+v2 field set on top of the v3 additions.

`ObservationV3SizeBudgetTests`: see section 6.

Test command:

```bash
./gradlew tests:test
```

## 8. Что НЕ трогать в v3

- Save format. No new fields on `GameInfo`/`Civilization`/`City`/`Tile`/`MapUnit`. v3 only reads existing state.
- `LlmGateway`, `LlmActionApplier`, `LlmPolicy`. v3 changes the observation only. There are no new actions for moving units, attacking, or working tiles — those are not in the MVP action set and remain out of scope.
- Per-tile detail. The summary IS the v3 contract. A future per-tile schema is a separate decision.
- Fog-of-war reconstruction / last-seen positions. Only currently-visible enemies are reported.
- Enemy civilian units. Only military.
- Diplomatic context (relationship score, recent declarations, trade history). That is v4.
- Score, era, government, victory progress. Not "map/cities".
- Trace file format (`schema: "llm-trace/v1"`). Unchanged.
- Decoder in `LlmJsonCodec`. Still encode-only.

## 9. Decisions (locked before implementation)

These were open questions in the first draft of this plan. Each is now resolved; recording the decision and rationale here so the implementing agent does not have to re-litigate.

1. **Visible-enemy radius — 5 hexes.** Encoded as `private const val VISIBLE_ENEMY_RADIUS = 5` in the collector. Matches an existing automation heuristic ("edge city defender" logic looks at enemy cities within ≤5). Easy to retune later by editing one line.
2. **`brief` cap — 5 units.** Same rationale. `totalCount` carries the full mass; `brief` is a bounded sample. If 5 ever proves too few, raising the cap is an additive/contract-tuned change.
3. **Foreign border tiles — "tiles bordering `city.getTiles()`", not `workRange + 1`.** "Border pressure" was the intent; `workRange + 1` measured "proximity" instead. The new formula counts only tiles that actually touch the city's owned territory and are owned by someone else. See section 5 for the snippet.
4. **`nearestForeignCity` — includes everyone.** Majors, city-states, allies, enemies. v3 is geometric; hostility lives in v4.
5. **`LlmUnitBrief` strength — deferred.** Naive `baseUnit.strength` is incomplete; correct effective strength pulls in terrain/promotions/conditionals. Name + HP + movement + counts is enough for v3.
6. **`city.getMaxHealth()` — called directly.** It's `internal`, but `com.unciv.logic.llm` is in the same `core` Gradle module, so Kotlin visibility allows the call. No fallback recomputation, no reflection test.
7. **Tile-resource visibility — gated by `civ.canSeeResource(tile.tileResource)`.** Mirrors the fog-of-war rule for `visibleEnemies`. Unrevealed Iron/Oil/Coal does NOT appear in `resourceCounts`. Yields from the underlying terrain still flow into `totalYields`. See section 5 for snippet and section 7 for the test.
8. **Improvement pillage status — ignored in v3.** A pillaged Farm still counts as "Farm". A pillage breakdown can be added additively in a later level if it turns out to matter.
9. **War-state check — `unit.civ.isAtWarWith(civ)`, not walking `civ.diplomacy.values`.** Simpler and doesn't depend on diplomacy-map structure.

## 10. Definition of done

- `./gradlew tests:test` зелёный.
- JSONL header writes `observationSchema: "llm-obs/v3"`.
- Каждое `LlmCityObservation` в JSONL содержит все v1+v2 поля плюс 6 новых v3-блоков.
- Size budget test passing: encoded mid-game observation under 32 KB; per-city under 2 KB.
- Fog-of-war test passing: enemy unit on a fogged tile does NOT appear in `visibleEnemies`.
- Resource-visibility test passing: unrevealed Iron does NOT appear in `resourceCounts` (but its terrain yields still flow into `totalYields`).
- `city.getMaxHealth()` called directly (no reflection, no formula replication).
- Visible-enemy radius lives as a single named constant in the collector.
- Happiness/Stats naming-collision test from v2 still passes (regression guard).
- Every `Map<String, Int>` and `List` in v3 output is deterministically sorted (verified by repeat-encode test).

## 11. Implementation order

1. `LlmHexCoord`, `LlmUnitBrief` — smallest, no dependencies.
2. `LlmCityHealth`, `LlmCityDefenders`, `LlmVisibleEnemies`, `LlmWorkableTilesSummary`, `LlmExpansionPressure` — pure data classes.
3. Add the six new fields to `LlmCityObservation` without defaults. Existing tests will fail compilation; fix the fixtures.
4. `LlmActionCollector` — one private helper per block: `collectLocation`, `collectHealth`, `collectDefenders`, `collectVisibleEnemies`, `collectWorkableTilesSummary`, `collectExpansionPressure`. Each helper is independently testable.
5. Bump `LlmObservationVersion.CURRENT` to `"llm-obs/v3"`.
6. `LlmJsonCodec` — encoders for all new types. Preserve field order. Sort all maps.
7. Tests in the order they're listed in section 7: smallest first (`LocationTests`) so failures surface incrementally.
8. `ObservationV3SizeBudgetTests` last. If this fails, we have a design problem (not a coding bug), and we revisit which fields are summaries vs full lists.
9. Manual smoke: run CLI on a real save, open the JSONL, inspect one step. Confirm one city has visible enemies if at war and zero if at peace.
