# Observation v4 — Diplomacy Plan

This plan extends `LlmObservation` from `llm-obs/v3` to `llm-obs/v4` by adding a diplomacy block to the civ-level snapshot: who the observing civ knows, what their relationship is, which wars are active, what trades are running, what threats are perceived, and how the civ compares to others on a few standard ranking axes.

v4 is **additive** with respect to v3: every field present in a v3 observation is still present, in the same place, with the same semantics. Only top-level `LlmObservation` grows; `LlmCityObservation` stays at the v3 shape.

After v4, the observation is "feature-complete" relative to the original v1–v4 outline. The next stage (whenever we get there) should be the LLM client / prompt builder, not more observation widening.

## 1. Что добавляем

New top-level fields on `LlmObservation`:

- `diplomacy: LlmDiplomacy` — a single object containing per-other-civ entries, active wars, active trades, threat summary, and relative rankings.

That's it. Only one new top-level field, but it carries a lot. Putting everything under one `diplomacy` object (as opposed to four separate top-level fields) makes the v4 boundary visible at a glance in JSONL and keeps the schema diff small.

No new fields on `LlmCityObservation` in v4. Per-city diplomatic context (e.g., is this city near an enemy's army) was largely covered by v3's `visibleEnemies` and `expansionPressure`. If a need surfaces later, it's an additive sub-stage on top of v4.

## 2. Предлагаемые файлы/классы

Core (new):

```text
core/src/com/unciv/logic/llm/LlmDiplomacy.kt
core/src/com/unciv/logic/llm/LlmKnownCiv.kt
core/src/com/unciv/logic/llm/LlmWarStatus.kt
core/src/com/unciv/logic/llm/LlmTradeSummary.kt
core/src/com/unciv/logic/llm/LlmThreats.kt
core/src/com/unciv/logic/llm/LlmRelativeStanding.kt
```

Изменения в существующих файлах:

```text
core/src/com/unciv/logic/llm/LlmObservationVersion.kt   (CURRENT → "llm-obs/v4")
core/src/com/unciv/logic/llm/LlmObservation.kt          (добавить diplomacy)
core/src/com/unciv/logic/llm/LlmActionCollector.kt      (наполнение diplomacy)
core/src/com/unciv/logic/llm/io/LlmJsonCodec.kt         (сериализация новых типов)
```

Tests:

```text
tests/src/com/unciv/logic/llm/observation/ObservationV4KnownCivsTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV4WarsTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV4TradesTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV4ThreatsTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV4RelativeStandingTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV4VisibilityTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV4JsonShapeTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV4AdditiveTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV4SizeBudgetTests.kt
```

## 3. Структуры данных

```kotlin
data class LlmDiplomacy(
    val knownCivs: List<LlmKnownCiv>,        // sorted by civName ascending
    val activeWars: List<LlmWarStatus>,      // sorted by enemyCivName ascending
    val activeTrades: List<LlmTradeSummary>, // sorted by partnerCivName ascending
    val threats: LlmThreats,                 // aggregated
    val relativeStanding: LlmRelativeStanding
)
```

### Per-civ entry

```kotlin
data class LlmKnownCiv(
    val civName: String,
    val isMajorCiv: Boolean,
    val isCityState: Boolean,
    val isDefeated: Boolean,

    // Relationship
    val diplomaticStatus: String,            // "Peace" | "War" | "DefensivePact" | "Protector"
    val relationshipLevel: String,           // "Unforgivable" | "Enemy" | ... | "Ally"; absent for war/defensive-pact, see notes
    val opinionScore: Int?,                  // sum of diplomatic modifiers, roundToInt; null for city-states (see notes)

    // Agreements
    val hasOpenBorders: Boolean,             // they have OUR open borders (we let them in)
    val theirsHaveOpenBorders: Boolean,      // we have THEIR open borders (we can enter their territory)
    val hasDeclarationOfFriendship: Boolean,
    val hasDefensivePact: Boolean,
    val hasResearchAgreement: Boolean,

    // City-state-specific (null for major civs)
    val cityStateInfluence: Int?,            // floor of influence
    val cityStateAlly: Boolean?,             // true if we're the current ally
)
```

Notes:

- `diplomaticStatus` mirrors `DiplomaticStatus` enum: `Peace`, `War`, `DefensivePact`, `Protector`. We stringify with the enum name; no remapping. `Protector` only applies to city-state diplomacy entries.
- `relationshipLevel` mirrors `RelationshipLevel` enum: `Unforgivable`, `Enemy`, `Afraid`, `Competitor`, `Neutral`, `Favorable`, `Friend`, `Ally`. The `DiplomacyManager.relationshipLevel()` call already accounts for War / DefensivePact special-casing — we serialize whatever it returns. (`War` is not in the `RelationshipLevel` enum; it's only on the `DiplomaticStatus` axis. That's intentional in Unciv: a civ at war still has an underlying relationship level used after peace.)
- `opinionScore`: sum of `diplomaticModifiers.values`, floored. For city-states, modifiers are not how relationship works — they use `influence` instead. We emit `null` to flag the field is not meaningful there, rather than serializing `0` which would lie.
- `hasOpenBorders` vs `theirsHaveOpenBorders` is the easiest naming-collision trap in Unciv's diplomacy code (`DiplomacyManager.hasOpenBorders` is the flag for **the relationship from this side's perspective**, meaning whether `otherCiv` can enter `civInfo`). The test plan pins this down explicitly. From the observing civ's point of view, `hasOpenBorders` = "they can walk into our territory"; `theirsHaveOpenBorders` = "we can walk into their territory" (= `otherCivDiplomacy().hasOpenBorders`).

### Wars

```kotlin
data class LlmWarStatus(
    val enemyCivName: String,
    val turnsSinceWarDeclaration: Int?,      // null if unknown
    val warDeclaredByUs: Boolean,
    val knownEnemyAlliesAtWar: List<String>, // names of other civs we know are also at war with this enemy, sorted
    val canMakePeace: Boolean                // whether this civ may currently negotiate peace
)
```

Active wars are a subset of `knownCivs` where `diplomaticStatus == "War"`, but giving them their own list lets a policy iterate just wars without filtering, and lets us add war-specific fields without bloating `LlmKnownCiv`.

`turnsSinceWarDeclaration` source: if a peace-treaty flag countdown exists we can derive it; otherwise we leave `null`. v4 does not force this number to be computed if Unciv doesn't already expose it cheaply.

### Trades

```kotlin
data class LlmTradeSummary(
    val partnerCivName: String,
    val outgoing: List<LlmTradeOfferBrief>,  // what we give them
    val incoming: List<LlmTradeOfferBrief>,  // what they give us
    val turnsRemainingMin: Int?              // shortest remaining duration among ongoing offers; null if all immediate
)

data class LlmTradeOfferBrief(
    val type: String,                        // TradeOfferType enum name
    val name: String,                        // resource name, treaty name, civ id for introductions, city id for ceded city, etc.
    val amount: Int                          // 0 for one-shot non-numeric offers
)
```

Active trades = `civ.diplomacy[otherName].trades` (an `ArrayList<Trade>`). Each `Trade` has `ourOffers` and `theirOffers`, both `TradeOffersList`. We flatten across trades-with-the-same-partner so each `LlmTradeSummary` is one row per partner, not one row per trade. Same-partner-multiple-trades is unusual but possible (sequential agreements); concatenating into single outgoing/incoming lists keeps the v4 contract simple.

What we deliberately **do not** include in trades:

- Pending / proposed-but-not-accepted trades. Unciv does not store these in `diplomacy.trades` — they're transient UI state. Bringing them in would require new tracking. Defer.
- Trade history (past deals that ended). Not stored; skip.
- AI's evaluation score for offers. Not part of observation; that's policy/computation.

### Threats

```kotlin
data class LlmThreats(
    val warCount: Int,                       // civs we are currently at war with
    val denunciationCount: Int,              // known civ relationships with active Denunciation in either direction
    val recentDeclarationsAgainstUs: Int,    // reverse-side "DeclaredWar" flag while war is active
    val nearbyHostileMilitaryCount: Int,     // sum of v3's visibleEnemies.totalCount across all our cities
    val cityStatesAtWarWithUs: Int           // count of city-state knownCivs with status War
)
```

This is an **aggregated** view. Per-city visibility lives in v3. v4's role is to give a one-glance "how surrounded am I" summary at civ level. Every number here can be cross-checked against either `knownCivs[]` or per-city `visibleEnemies` — that cross-check is a test (see section 7).

### Relative standing

```kotlin
data class LlmRelativeStanding(
    val score: LlmRankingEntry,
    val population: LlmRankingEntry,
    val growth: LlmRankingEntry,
    val production: LlmRankingEntry,
    val gold: LlmRankingEntry,
    val territory: LlmRankingEntry,
    val force: LlmRankingEntry,
    val happiness: LlmRankingEntry,
    val technologies: LlmRankingEntry,
    val culture: LlmRankingEntry
)

data class LlmRankingEntry(
    val ourValue: Int,                       // civ.getStatForRanking(category)
    val rank: Int,                           // 1-based position among non-defeated major civs we know (incl. us)
    val totalKnownMajors: Int,               // denominator for rank: count of known + us
    val leaderValue: Int                     // best known value across same set (0 if no one observed)
)
```

Why ranking is here and not in v2 economy: v2 was about *our* numbers in isolation. v4's job is *comparative* context, which requires knowing other civs. Putting `RelativeStanding` here means it's also fog-of-war-gated (see section 4): we only rank against civs we've actually met.

What we deliberately **do not** include in standing:

- Score history / per-turn series. Unciv has `CivRankingHistory` but emitting a series per observation explodes JSON. A single snapshot per category is the v4 contract.
- Per-tech / per-policy diffs. Too noisy.

## 4. Fog-of-war rules for v4

Diplomacy data is **gated by what the observing civ knows**. The rules:

1. **Per-civ entries** (`knownCivs`): only civs in `civ.getKnownCivs()` AND known via a `DiplomacyManager` entry. The observing civ itself is excluded.
2. **Wars** (`activeWars`): only wars where the observing civ is a participant. We do NOT enumerate third-party wars (e.g. "civ B is at war with civ C") — those are leaks unless we explicitly know via a known modifier or a known introduction. **Out of scope for v4.**
3. **Trades** (`activeTrades`): only trades involving the observing civ. We don't see other civs' trade tables.
4. **Threats**:
    - `warCount` / `cityStatesAtWarWithUs` / `denunciationCount` — derivable from our own diplomacy state. Safe.
    - `nearbyHostileMilitaryCount` — already fog-of-war-gated by v3's `visibleEnemies`. We just sum.
5. **Relative standing**:
    - `ourValue` — always known (it's ours).
    - `leaderValue` and `rank` — computed over **{us} ∪ {known major civs that are not defeated}**. We do NOT include civs we have not met. We do NOT include city-states. We do NOT include defeated civs.
    - If we know nobody, `rank == 1`, `totalKnownMajors == 1`, `leaderValue == ourValue`.

Rule 2 is the easiest fog-of-war leak in v4. The test plan pins it: a war between two civs we both know about, but where we are not a participant, must NOT appear in `activeWars` or anywhere else in our observation.

## 5. Как наполнять (методы Unciv)

### Known civs

```kotlin
val knownCivs = civ.diplomacy.values
    .filter { !it.otherCiv.isDefeated() }
    .sortedBy { it.otherCiv.civName }
    .map { dm ->
        val other = dm.otherCiv
        LlmKnownCiv(
            civName = other.civName,
            isMajorCiv = other.isMajorCiv(),
            isCityState = other.isCityState,
            isDefeated = other.isDefeated(),
            diplomaticStatus = dm.diplomaticStatus.name,
            relationshipLevel = dm.relationshipLevel().name,
            opinionScore = if (other.isCityState) null
                           else dm.diplomaticModifiers.values.sum().roundToInt(),
            hasOpenBorders = dm.hasOpenBorders,
            theirsHaveOpenBorders = dm.otherCivDiplomacy().hasOpenBorders,
            hasDeclarationOfFriendship = dm.hasFlag(DiplomacyFlags.DeclarationOfFriendship),
            hasDefensivePact = dm.diplomaticStatus == DiplomaticStatus.DefensivePact,
            hasResearchAgreement = dm.hasFlag(DiplomacyFlags.ResearchAgreement),
            cityStateInfluence = if (other.isCityState) dm.getInfluence().toInt() else null,
            cityStateAlly = if (other.isCityState) /* see notes */ null else null
        )
    }
```

Open questions resolved inline:

- **Should defeated civs appear?** No for v4. Defeated majors and defeated city-states are filtered out to keep the list focused on currently actionable diplomacy. If we want a "graveyard" view later, add it as a separate v4.x field rather than mixing it into `knownCivs`.
- **`cityStateAlly` computation.** Requires walking all majors' diplomacy with this city-state and finding the highest-influence one. Non-trivial. v4 emits `null` initially; this is a v4.x follow-up. The field stays in the schema so we don't break additivity later when filling it in.

### Wars

```kotlin
val activeWars = civ.diplomacy.values
    .filter { it.diplomaticStatus == DiplomaticStatus.War && !it.otherCiv.isDefeated() }
    .sortedBy { it.otherCiv.civName }
    .map { dm ->
        LlmWarStatus(
            enemyCivName = dm.otherCiv.civName,
            turnsSinceWarDeclaration = null, // see notes
            warDeclaredByUs = dm.hasFlag(DiplomacyFlags.DeclaredWar),
            knownEnemyAlliesAtWar = emptyList(), // see notes
            canMakePeace = !dm.hasFlag(DiplomacyFlags.DeclinedPeace) // approximate, see notes
        )
    }
```

- `turnsSinceWarDeclaration`: Unciv stores `DeclaredWar` countdown but it ticks down, not up. Deriving "turns since" requires history we don't have cheaply. Leave `null` for v4; a follow-up can wire it.
- `knownEnemyAlliesAtWar`: a v4 nice-to-have. For each war, iterate the other civ's known wars from OUR knowledge. Requires careful fog-of-war: we know only what we've been told (via Introduction trades) or directly observed. Conservative v4 leaves it empty; v4.x can fill it.
- `canMakePeace`: deliberately approximate in v4. `!hasFlag(DeclinedPeace)` captures the main "minimum war duration / recently declined peace" blocker. A full "is an actually valid peace trade possible right now?" check pulls in broader trade validation and is out of scope.

### Trades

```kotlin
val activeTrades = civ.diplomacy.values
    .filter { it.trades.isNotEmpty() }
    .sortedBy { it.otherCiv.civName }
    .map { dm ->
        val partner = dm.otherCiv
        val allOur = dm.trades.flatMap { it.ourOffers }
        val allTheir = dm.trades.flatMap { it.theirOffers }
        LlmTradeSummary(
            partnerCivName = partner.civName,
            outgoing = allOur.map { it.toBrief() }.sortedWith(tradeOfferBriefComparator),
            incoming = allTheir.map { it.toBrief() }.sortedWith(tradeOfferBriefComparator),
            turnsRemainingMin = (allOur + allTheir)
                .mapNotNull { if (it.duration > 0) it.duration else null }
                .minOrNull()
        )
    }

val tradeOfferBriefComparator = compareBy<LlmTradeOfferBrief> { it.type }
    .thenBy { it.name }
    .thenBy { it.amount }
```

`TradeOffer.duration` is the existing field (counts down each turn). Immediate offers have `duration == -1`; expired/invalid ongoing offers should not appear in active trades. `turnsRemainingMin` therefore ignores all `duration <= 0` offers and returns `null` for immediate-only deals.

Offer lists are sorted deterministically after flattening, by `type`, `name`, then `amount`.

### Threats

```kotlin
val warCount = civ.diplomacy.values.count {
    it.diplomaticStatus == DiplomaticStatus.War && !it.otherCiv.isDefeated()
}
val denunciationCount = civ.diplomacy.values.count {
    it.hasFlag(DiplomacyFlags.Denunciation)
        || it.otherCivDiplomacy().hasFlag(DiplomacyFlags.Denunciation)
}
val recentDeclarationsAgainstUs = civ.diplomacy.values.count {
    it.otherCivDiplomacy().hasFlag(DiplomacyFlags.DeclaredWar)
        && it.diplomaticStatus == DiplomaticStatus.War
}
val nearbyHostileMilitaryCount = observation.cities
    .sumOf { it.visibleEnemies.totalCount }
val cityStatesAtWarWithUs = civ.diplomacy.values.count {
    it.otherCiv.isCityState && it.diplomaticStatus == DiplomaticStatus.War
}
```

Note: `nearbyHostileMilitaryCount` is computed from v3 data already populated in the same observation, so the collector should build the cities list before the diplomacy block.

`denunciationCount` counts known civ relationships involving us, not raw flag instances. If both sides have active `Denunciation` flags for the same partner, that partner contributes `1`, not `2`. The iteration is only over `civ.diplomacy.values`, so unknown civs cannot contribute.

`recentDeclarationsAgainstUs` intentionally checks the reverse diplomacy manager: from the observing civ's perspective, `dm.hasFlag(DeclaredWar)` means "we declared war on them", while `dm.otherCivDiplomacy().hasFlag(DeclaredWar)` means "they declared war on us".

### Relative standing

```kotlin
import com.unciv.ui.screens.victoryscreen.RankingType

val cohort = (civ.getKnownCivs().filter { it.isMajorCiv() && !it.isDefeated() }.toList() + civ)
fun rank(category: RankingType): LlmRankingEntry {
    val pairs = cohort.map { it to it.getStatForRanking(category) }
    val sorted = pairs.sortedByDescending { it.second }
    val ourPair = pairs.first { it.first == civ }
    return LlmRankingEntry(
        ourValue = ourPair.second,
        rank = sorted.indexOf(ourPair) + 1,
        totalKnownMajors = cohort.size,
        leaderValue = sorted.first().second
    )
}
```

`RankingType` is in `com.unciv.ui.screens.victoryscreen` but its `getImage: () -> Image?` parameter is a lazy lambda — class-load is safe in headless contexts (verified: `tests/src/com/unciv/logic/SerializationTests.kt` imports and uses it directly). No fallback or reflection needed.

v4 includes all 10 current `RankingType` axes: `Score`, `Population`, `Growth`, `Production`, `Gold`, `Territory`, `Force`, `Happiness`, `Technologies`, `Culture`. `growth` is `civ.stats.statsForNextTurn.food.roundToInt()` via `getStatForRanking(RankingType.Growth)`, and `happiness` is `civ.getHappiness()` via `getStatForRanking(RankingType.Happiness)`.

## 6. Гарантии детерминизма + budget

Determinism:

- All lists sorted as declared (civNames ascending, etc.).
- Maps not used at top level of v4 — easier to keep deterministic.

Size budget:

- A civ at peace with 4 known majors and 2 city-states: v4 block under 4 KB.
- A civ at war with 3 civs, 5 active trades, 6 known majors and 4 city-states: v4 block under 8 KB.
- `ObservationV4SizeBudgetTests` enforces both.

Combined with v3 budget (per-city < 2 KB, observation < 32 KB on mid-game), full v4 observation should stay under 40 KB on realistic mid-game saves.

## 7. Тесты

`ObservationV4KnownCivsTests`:

- Civ that knows two majors (A, B) and one city-state (CS) → `knownCivs.size == 3`, sorted by name, observing civ NOT in the list.
- Civ knows civ X with `diplomaticStatus == War` → entry has `diplomaticStatus == "War"`, `relationshipLevel == <whatever DiplomacyManager.relationshipLevel() returns>`.
- City-state entry has `opinionScore == null` and `cityStateInfluence != null`. Major civ entry is the opposite.
- `hasOpenBorders` vs `theirsHaveOpenBorders` — fixture with one-way open borders in each direction. Assert flags are NOT swapped. This is the bug-trap test.
- Civ that doesn't know X → X is absent. Civ never has itself in `knownCivs`.

`ObservationV4WarsTests`:

- Civ at peace → `activeWars` is empty.
- Civ at war with two civs → `activeWars.size == 2`, sorted by `enemyCivName`. Each entry's name is also in `knownCivs` with `diplomaticStatus == "War"`.
- `warDeclaredByUs` is true when we declared, false when they did.
- Defeated enemy civ is NOT in `activeWars` even if war flag still exists in `diplomacy`.

`ObservationV4TradesTests`:

- No trades → `activeTrades` empty.
- We sell Iron(3) for Gold(120) to civ B → entry for B with `outgoing == [{type:"Strategic_Resource", name:"Iron", amount:3}]`, `incoming == [{type:"Gold", name:"Gold", amount:120}]`.
- Multiple trades with same partner aggregate into one summary; offer lists are concatenated.
- Immediate-only deal → `turnsRemainingMin == null`. Mixed deal → `turnsRemainingMin` is the smallest positive duration.

`ObservationV4ThreatsTests`:

- All threat numbers cross-check against per-civ data on the same observation:
  - `warCount == activeWars.size`.
  - `cityStatesAtWarWithUs == knownCivs.count { it.isCityState && it.diplomaticStatus == "War" }`.
  - `nearbyHostileMilitaryCount == cities.sumOf { it.visibleEnemies.totalCount }`.
- `denunciationCount` counts a partner once even if both sides have an active `Denunciation` flag.
- `recentDeclarationsAgainstUs` is driven by the reverse diplomacy manager, not by wars we declared.
- Civ at peace, no nearby enemies, no denunciations → all zeros.

`ObservationV4RelativeStandingTests`:

- Civ knows two majors (A, B) and one city-state (CS), nobody defeated → `cohort` for ranking has 3 members: us, A, B. CS is excluded.
- `ourValue` for `gold` equals `civ.gold`.
- `ourValue` for `growth` equals `civ.stats.statsForNextTurn.food.roundToInt()`.
- `ourValue` for `happiness` equals `civ.getHappiness()`.
- `rank == 1` when our value is highest in cohort; `rank == cohort.size` when lowest.
- If we know nobody → `rank == 1`, `totalKnownMajors == 1`, `leaderValue == ourValue`. (Single-civ game / very early turn.)
- Defeated major is excluded from cohort even if still in `diplomacy`.

`ObservationV4VisibilityTests` (the fog-of-war guard, mirrors v3's enemy/resource visibility tests):

- Two civs A and B are at war with each other, observing civ C knows both but is not involved → C's `activeWars` is empty, no leak.
- C does not know civ D → D appears nowhere in C's observation: not in `knownCivs`, not in `activeWars`, not in `relativeStanding.cohort` math.
- Civ E joins via Introduction trade → next observation includes E in `knownCivs`.

`ObservationV4JsonShapeTests`:

- Top-level has `diplomacy` object.
- `diplomacy` object has exactly `{knownCivs, activeWars, activeTrades, threats, relativeStanding}` — closed set.
- `relativeStanding` has exactly 10 entries (one per current `RankingType`) — closed set.
- `schemaVersion == "llm-obs/v4"`.

`ObservationV4AdditiveTests`:

- Every v1/v2/v3 top-level field still present with same name and type.
- v3 invariants (city block field set, fog-of-war for `visibleEnemies`, resource-visibility rule) still hold on the same fixture.

`ObservationV4SizeBudgetTests`: see section 6.

Test command:

```bash
./gradlew tests:test
```

## 8. Что НЕ трогать в v4

- Save format. No new fields on `Civilization`, `DiplomacyManager`, `Trade`, `TradeOffer`. v4 only reads existing state.
- `LlmGateway`, `LlmActionApplier`, `LlmPolicy`. No new actions (declare war, sue for peace, propose trade) — those are a future MVP-of-action-space, not v4.
- Third-party wars / trades. Strictly leaked-info-free observation. Wars not involving us are out.
- Trade evaluation / AI score. Not part of observation.
- City-state ally computation (full walk of all majors' influence). v4 emits `null`; v4.x can fill in.
- `turnsSinceWarDeclaration`. v4 emits `null`. Wiring this needs either history tracking or a re-interpretation of the existing flag countdown, which is too much for v4.
- Historical / per-turn series for any field. Single-snapshot only.
- Trace file format (`schema: "llm-trace/v1"`). Unchanged.
- Decoder in `LlmJsonCodec`. Still encode-only.

## 9. Decisions (locked before implementation)

1. **One top-level `diplomacy` object, not five separate top-level fields.** Smaller schema diff, clearer v4 boundary, easier to add v5 elsewhere.
2. **`opinionScore == null` for city-states, not 0.** City-states use `influence`, not modifiers. `null` honestly signals "this axis doesn't apply".
3. **`relationshipLevel` is always emitted**, including for war. We pass through whatever `DiplomacyManager.relationshipLevel()` returns. Unciv keeps a meaningful underlying level even during war (used after peace), and consumers can correlate with `diplomaticStatus` separately.
4. **City-state `cityStateAlly` initially `null`.** Requires non-trivial walk of all majors. Keeping the field in schema preserves additivity for v4.x.
5. **`turnsSinceWarDeclaration` initially `null`.** Same reason — wiring is non-trivial, but field exists in schema for later.
6. **Third-party wars / trades excluded.** Fog-of-war first principle; revisit if a use case demands it.
7. **Defeated civs filtered out of `knownCivs` by default.** Keeps the list focused on actionable context. If we want a "graveyard" view, that's an additive option later.
8. **`RankingType` import is safe in headless contexts.** Verified by existing test usage; no fallback needed.
9. **`relativeStanding` cohort = `{us} ∪ {known majors, not defeated}`.** City-states excluded; defeated civs excluded; unknown civs excluded.
10. **All 10 current `RankingType` axes are included.** `Growth` and `Happiness` are comparative context, not top-level economy duplicates.
11. **`denunciationCount` counts relationships, not flags.** A partner with denunciations in both directions contributes `1`; unknown civs cannot contribute.
12. **`recentDeclarationsAgainstUs` uses reverse diplomacy.** The forward `DeclaredWar` flag means we declared; the reverse flag means they declared.
13. **`nearbyHostileMilitaryCount` derived from v3 per-city blocks.** The collector must build the cities list (v3 data) before the diplomacy block (v4 data). One-way dependency, document in code.

## 10. Definition of done

- `./gradlew tests:test` зелёный.
- JSONL header writes `observationSchema: "llm-obs/v4"`.
- Каждое observation в JSONL содержит `diplomacy` object with five sub-blocks.
- Fog-of-war leak tests passing: third-party wars do NOT appear; unknown civs do NOT appear in `knownCivs` or in ranking cohort.
- `hasOpenBorders` vs `theirsHaveOpenBorders` naming-collision test passing.
- All threat counters cross-check against per-civ / per-city data in the same observation.
- All v1+v2+v3 invariants still hold (regression).
- Mid-game observation stays under 40 KB; v4 block alone under 8 KB.

## 11. Implementation order

1. Pure data classes: `LlmRankingEntry`, `LlmKnownCiv`, `LlmWarStatus`, `LlmTradeOfferBrief`, `LlmTradeSummary`, `LlmThreats`, `LlmRelativeStanding`, then `LlmDiplomacy`.
2. Field `diplomacy` on `LlmObservation`, no default — existing tests fail compilation. Fix fixtures.
3. `LlmActionCollector` — private helpers in this order, each independently testable:
   `collectKnownCivs(civ)` → `collectActiveWars(civ)` → `collectActiveTrades(civ)` → `collectRelativeStanding(civ)` → `collectThreats(civ, cities)`.
4. Bump `LlmObservationVersion.CURRENT` to `"llm-obs/v4"`.
5. `LlmJsonCodec` — encoders for all new types. Preserve declared field order. Sort lists.
6. Tests in the order in section 7: `KnownCivsTests` first (smallest surface), `VisibilityTests` and `AdditiveTests` last (broadest invariants).
7. Manual smoke: run CLI on a real save with at least one ongoing war and one ongoing trade. Open JSONL, inspect the `diplomacy` block, confirm sorted lists and cross-checked counters.
