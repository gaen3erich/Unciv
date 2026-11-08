# LLM Observation Sources

This file maps `LlmObservation` fields to the Unciv APIs that populate them.
Use it before changing observation code, tests, or JSONL schema.

## 1. Versioning

| Observation field | Source | Notes |
|---|---|---|
| `schemaVersion` | `LlmObservationVersion.CURRENT` | Current observation contract, e.g. `llm-obs/v1` or `llm-obs/v2`. |
| JSONL header `observationSchema` | `LlmObservationVersion.CURRENT` via `LlmTraceHeader` | Independent from trace container schema `llm-trace/v1`. |

## 2. Game And Civ Identity

| Observation field | Source | Notes |
|---|---|---|
| `gameId` | `gameInfo.gameId` | Save/game identifier. |
| `turn` | `gameInfo.turns` | Current game turn counter. |
| `currentPlayerCivId` | `civ.civID` | Target civ ID for this observation. Runner currently uses `gameInfo.getCurrentPlayerCivilization()`. |
| `currentPlayerCivName` | `civ.civName` | Human-readable civ name. |
| `isOnlineMultiplayer` | `gameInfo.gameParameters.isOnlineMultiplayer` | Gateway rejects by default unless explicitly allowed. |

## 3. Research

| Observation field | Source | Notes |
|---|---|---|
| `currentResearch` | `civ.tech.currentTechnologyName()` | `null` means no active research selected. |
| `researchedTechs` | `gameInfo.ruleset.technologies.keys.filter { civ.tech.isResearched(it) }.sorted()` | Must stay sorted for deterministic JSON. |
| `sciencePerTurn` | `civ.stats.statsForNextTurn.science.roundToInt()` | Early economy duplicate. In v2 this must equal `economy.sciencePerTurn`. |

## 4. Cities

| Observation field | Source | Notes |
|---|---|---|
| `cities` | `civ.cities.sortedBy { cityIdFor(civ, it) }` | Sort by stable `cityId`, not display name. |
| `cities[].cityId` | `city.id` | Primary stable ID. |
| `cities[].cityId` fallback | `"${civ.civName}/${city.location.x},${city.location.y}"` | Use only if `city.id == City.NO_ID`. `city.location` is `HexCoord`. |
| `cities[].name` | `city.name` | Display name; can change without changing `cityId`. |
| `cities[].population` | `city.population.population` | Current population. |
| `cities[].currentConstruction` | `city.cityConstructions.currentConstructionName().ifEmpty { null }` | `null` means empty/none. |
| `cities[].productionPerTurn` | `city.cityStats.currentCityStats.production.roundToInt()` | Early economy/city duplicate. Kept for v1/v2 additive compatibility. |
| `cities[].queue` | `city.cityConstructions.constructionQueue.toList()` | Snapshot copy, not mutable queue reference. |

## 5. Legal Actions

| Observation field | Source | Notes |
|---|---|---|
| `legalActions` research | `gameInfo.ruleset.technologies.values.sortedBy { it.name }` filtered with `civ.tech.canBeResearched(name)` | Collector must check only known ruleset techs to avoid `TechManager.canBeResearched` unknown-tech throw. |
| `legalActions` construction | Buildings + units filtered with `city.cityConstructions.canAddToQueue(construction)` | Sort constructions by `construction.name`. |
| `legalActions` end turn | Always `LlmLegalAction.EndTurn()` when not online-multiplayer-blocked | End turn is explicit, never implicit. |
| `legalActionsSummary.technologies` | Count of `ChooseTechnology` actions | Must match full `legalActions`. |
| `legalActionsSummary.constructionsByCity` | Count of `ChooseCityConstruction` actions grouped by `cityId` | Keep deterministic order by `cityId`. |
| `legalActionsSummary.endTurn` | Whether any `EndTurn` action exists | Must match full `legalActions`. |

## 6. Economy V2

Stockpiles are current totals. Per-turn values are deltas from
`civ.stats.statsForNextTurn`.

| Observation field | Source | Type | Notes |
|---|---|---:|---|
| `economy.gold` | `civ.gold` | `Int` | Treasury stockpile. |
| `economy.storedCulture` | `civ.policies.storedCulture` | `Int` | Culture stockpile for policies. |
| `economy.storedFaith` | `civ.religionManager.storedFaith` | `Int` | Faith stockpile. |
| `economy.happiness` | `civ.getHappiness()` | `Int` | Current happiness level. Not a per-turn delta. |
| `economy.goldPerTurn` | `civ.stats.statsForNextTurn.gold.roundToInt()` | `Int` | Next-turn gold delta. |
| `economy.sciencePerTurn` | `civ.stats.statsForNextTurn.science.roundToInt()` | `Int` | Must equal top-level `sciencePerTurn` while duplicate exists. |
| `economy.culturePerTurn` | `civ.stats.statsForNextTurn.culture.roundToInt()` | `Int` | Next-turn culture delta. |
| `economy.faithPerTurn` | `civ.stats.statsForNextTurn.faith.roundToInt()` | `Int` | Next-turn faith delta. |
| `economy.happinessPerTurn` | `civ.stats.statsForNextTurn.happiness.roundToInt()` | `Int` | Value from next-turn `Stats`; not current happiness level. |

### Happiness Pitfall

Do not confuse these:

```kotlin
civ.getHappiness()                    // current happiness level, Int
civ.stats.statsForNextTurn.happiness  // next-turn Stats value, Float
```

Both use the word "happiness", but they are different values and must be
tested with a fixture where they differ.

## 7. Resources V2

| Observation field | Source | Notes |
|---|---|---|
| source map | `civ.getCivResourcesByName()` | Returns all ruleset tile resources initialized to `0`, then current supply/stockpiles. |
| classification | `gameInfo.ruleset.tileResources[name]?.resourceType` | Skip missing ruleset entries defensively. |
| `resources.strategic` | entries with `ResourceType.Strategic` and `amount != 0` | Include negative amounts; they indicate over-use/depletion. |
| `resources.luxury` | entries with `ResourceType.Luxury` and `amount != 0` | Sorted by resource name. |
| skipped in v2 | `ResourceType.Bonus` | Bonus resources belong with tile/city context in v3. |

Resource lists must be sorted by `name` ascending for byte-stable JSON.

## 8. Out Of Scope For V2

- Bonus resources.
- Per-city economy.
- Happiness breakdown.
- Diplomacy/known civs/wars/trades.
- Visible units, threats, tile yields, workable tiles.
- Derived projections such as turns until next policy or treasury runway.
