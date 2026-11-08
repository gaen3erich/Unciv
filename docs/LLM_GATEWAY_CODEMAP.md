# LLM Gateway Codemap

This document maps the smallest relevant parts of Unciv for a future minimal LLM gateway. It is an investigation note only; no code changes are implied.

## 1. Как создаётся и загружается новая игра

UI path:

- `core/src/com/unciv/ui/screens/mainmenuscreen/MainMenuScreen.kt`
  - "Start new game" opens `NewGameScreen`.
  - Quickstart also calls `GameStarter.startNewGame(...)` and then `game.loadGame(newGame)`.
- `core/src/com/unciv/ui/screens/newgamescreen/NewGameScreen.kt`
  - `startGameAvoidANRs()` validates options and multiplayer requirements off the GL thread.
  - `startGame()` disables the UI and starts background creation.
  - `startNewGame()` is the orchestration point:
    - For normal games it calls `GameStarter.startNewGame(gameSetupInfo)`.
    - For scenarios it loads a saved `GameInfo` via `game.files.loadGameFromFile(...)` and adjusts `playerType`.
    - For online multiplayer it uploads the created game.
    - It then calls `game.loadGame(newGame)` and `worldScreen.autoSave()`.
- `core/src/com/unciv/logic/GameStarter.kt`
  - `GameStarter.startNewGame(gameSetupInfo: GameSetupInfo): GameInfo` is the real factory.
  - It builds `GameInfo`, resolves a combined `Ruleset`, loads or generates `TileMap`, adds civilizations, calls `tileMap.setTransients(...)`, calls `gameInfo.setTransients()`, adds starting units/policies/techs/stats, clears starting locations, and stores `lastGameSetup`.
- `core/src/com/unciv/UncivGame.kt`
  - `loadGame(newGameInfo: GameInfo, autoPlay: AutoPlay = ..., callFromLoadScreen: Boolean = false): WorldScreen` is the central load-into-runtime path.
  - It sets `UncivGame.Current.gameInfo`, initializes resources for the ruleset/mods, disposes old screens, constructs `WorldScreen(newGameInfo, autoPlay, newGameInfo.getPlayerToViewAs(), ...)`, and sets it as the active screen.

Headless-ish existing path:

- `desktop/src/com/unciv/app/desktop/DesktopLauncher.kt`
  - `--creategame=...` creates `UncivGame(true)`, initializes minimal settings/rulesets, then calls `CreateGameFromSettings.startGame(...)` and exits.
- `desktop/src/com/unciv/app/desktop/CreateGameFromSettings.kt`
  - `GameConfig` contains `GameParameters`, `MapParameters`, and optional server password.
  - `CreateGameFromSettings.startGame(...)` loads JSON config, optionally configures multiplayer, calls `GameStarter.startNewGame(...)`, stores `UncivGame.Current.gameInfo`, optionally uploads multiplayer state, prints the game id, and returns it.

## 2. Где создаётся, хранится, сериализуется и продвигается по ходам GameInfo

Creation and storage:

- `core/src/com/unciv/logic/GameInfo.kt`
  - `class GameInfo` is the main save/runtime world object.
  - Serialized fields include `civilizations`, `barbarians`, `religions`, `difficulty`, `tileMap`, `gameParameters`, `turns`, `currentPlayer`, `gameId`, `checksum`, `victoryData`, and other save-state fields.
  - Transient runtime fields include `ruleset`, `speed`, `currentPlayerCiv`, `difficultyObject`, `combinedGlobalUniques`, and caches.
- `core/src/com/unciv/logic/GameStarter.kt`
  - Creates the object in `startNewGame(...)`.
- `core/src/com/unciv/UncivGame.kt`
  - Stores the active game in `var gameInfo: GameInfo?`.
- `core/src/com/unciv/ui/screens/worldscreen/WorldScreen.kt`
  - Stores the screen-local active state in constructor field `val gameInfo: GameInfo`.

Serialization and loading:

- `core/src/com/unciv/logic/files/UncivFiles.kt`
  - `saveGame(game: GameInfo, ...)` writes `gameInfoToString(game)`.
  - `loadGameFromFile(gameFile)` reads text and calls `gameInfoFromString(...)`.
  - `gameInfoToString(game, forceZip, updateChecksum)` sets current compatibility version, optionally updates checksum, serializes via `json().toJson(game)`, and optionally gzip-compresses.
  - `gameInfoFromString(gameData)` trims, optionally ungzips, deserializes `GameInfo`, checks compatibility, then calls `gameInfo.setTransients()`.
  - `Autosaves.requestAutoSave(gameInfo, nextTurn)` clones `GameInfo` before async saving to avoid concurrent mutation.
- `core/src/com/unciv/logic/GameInfo.kt`
  - `clone()` creates a deep-ish game-state clone used by autosave and by UI next-turn processing.
  - `calculateChecksum()` serializes the object with checksum blanked and hashes it.
  - `asPreview()` produces `GameInfoPreview`, not a full restorable world.

Transient restoration:

- `GameInfo.setTransients()` is mandatory after load and central after creation.
  - It resolves `ruleset`, `speed`, `difficultyObject`, `combinedGlobalUniques`.
  - It initializes tile/civ/city/unit transient links.
  - It initializes `currentPlayerCiv` from `currentPlayer` or first relevant human.
  - It runs backward-compatibility/migration work.

Turn advancement:

- `core/src/com/unciv/logic/GameInfo.kt`
  - `nextTurn(progressBar: NextTurnProgress? = null, shouldGainTime: Boolean = false)` is the game-rule turn orchestrator.
  - It ends the current human player's turn, rotates through civilizations, auto-processes AI/defeated/spectator players, and starts the next human turn.
  - It updates `turns`, `currentPlayer`, `currentPlayerCiv`, and `currentTurnStartTime`.
- `core/src/com/unciv/logic/civilization/managers/TurnManager.kt`
  - `startTurn(...)`, `endTurn(...)`, and `automateTurn()` handle per-civilization lifecycle.
- `core/src/com/unciv/ui/screens/worldscreen/WorldScreen.kt`
  - `nextTurn()` is the UI wrapper: clone current `GameInfo`, call `gameInfoClone.setTransients()`, run `gameInfoClone.nextTurn(...)` in a background job, upload multiplayer if needed, then reload the clone via `UncivGame.Current.loadGame(...)`.

## 3. Как получить доступ к текущему CivilizationInfo

The runtime class is named `Civilization`, not `CivilizationInfo`.

Core access:

- `core/src/com/unciv/logic/GameInfo.kt`
  - `currentPlayerCiv: Civilization` is a transient cached field.
  - `getCurrentPlayerCivilization()` returns `currentPlayerCiv`.
  - `getCivilization(civID)` and `getCivilizationOrNull(civID)` resolve from `civMap`.
  - `getPlayerToViewAs()` returns the current player in local games; in online multiplayer it chooses the civilization matching `settings.multiplayer.getUserId()` or creates/returns a spectator.
- `core/src/com/unciv/logic/civilization/Civilization.kt`
  - `isCurrentPlayer()` checks whether this civ is `gameInfo.currentPlayerCiv`.

UI access:

- `core/src/com/unciv/ui/screens/worldscreen/WorldScreen.kt`
  - `viewingCiv` is the civilization this screen is showing as.
  - `selectedCiv` can differ for spectator/replay/UI context.
  - `canChangeState` is `isPlayersTurn && !viewingCiv.isSpectator()`.
- `core/src/com/unciv/GUI.kt`
  - `GUI.getViewingPlayer()` returns `UncivGame.Current.worldScreen!!.viewingCiv`.
  - `GUI.getSelectedPlayer()` returns `UncivGame.Current.worldScreen!!.selectedCiv`.

For a minimal LLM gateway, prefer `gameInfo.getCurrentPlayerCivilization()` for turn decisions in headless/core contexts. Avoid `GUI` and `WorldScreen` unless the experiment is explicitly desktop/UI-only.

## 4. Как представлены и применяются выборы строительства в городе

State model:

- `core/src/com/unciv/logic/city/City.kt`
  - Each city owns a `CityConstructions`.
- `core/src/com/unciv/logic/city/CityConstructions.kt`
  - `constructionQueue: ArrayList<String>` is serialized and limited to 10 entries.
  - Current construction is not a separate field: `currentConstructionName()` returns `constructionQueue.first()` or `""`.
  - `setCurrentConstruction(value)` writes queue index `0`.
  - `currentConstructionIsUserSet` records whether the queue head was set by user/explicit choice.
  - `inProgressConstructions: HashMap<String, Int>` stores accumulated production by construction name.
  - `builtBuildings: HashSet<String>` stores completed buildings.

Choice application:

- `CityConstructions.addToQueue(construction, addToTop = false)`:
  - Checks `canAddToQueue(...)`.
  - Adds to the empty queue, head, tail, or before a trailing perpetual construction.
  - Sets `currentConstructionIsUserSet = true`.
- `CityConstructions.addToQueue(constructionName: String)` resolves the construction name then delegates.
- `CityConstructions.chooseNextConstruction()`:
  - Keeps non-perpetual current construction or user-set perpetual construction.
  - If auto selection is allowed, calls `ConstructionAutomation(this).chooseNextConstruction()`.
  - Handles auto-picking a tile for buildings with `UniqueType.CreatesOneImprovement`.
- `core/src/com/unciv/logic/automation/city/ConstructionAutomation.kt`
  - `chooseNextConstruction()` scores possible buildings/units/perpetual constructions and calls `cityConstructions.setCurrentConstruction(chosenConstruction.name)`.
- `CityConstructions.endTurn(cityStats)`:
  - Validates queue and in-progress work.
  - Calls `constructionBegun(...)` when work starts.
  - Adds production to current construction.
- `CityConstructions.constructIfEnough()`:
  - Completes current construction when enough production has accumulated.
  - Calls `completeConstruction(...)`, handles overflow, then validates queue.
- `CityConstructions.completeConstruction(construction)`:
  - For `Building`, calls `construction.construct(this)`.
  - For `BaseUnit`, calls `construction.construct(this, null)` and handles placement failure.
  - Removes finished item from queue and sends notifications.
- `CityConstructions.purchaseConstruction(...)`:
  - Used by automation and city UI.
  - Builds immediately, deducts stat cost, consumes stockpiled resources as needed, removes queue entry if relevant, and validates queue.

UI path:

- `core/src/com/unciv/ui/screens/cityscreen/CityConstructionsTable.kt`
  - `addConstructionToQueue(...)` is the UI commit path for selecting production.
  - It handles impossible choices, improvement-placement buildings, sound/tutorial updates, then calls `cityConstructions.addToQueue(construction.name)`.
- `core/src/com/unciv/ui/popups/CityScreenConstructionMenu.kt`
  - Context menu calls queue operations such as `addToQueue`, `moveEntryToTop`, `moveEntryToEnd`, and `removeAllByName`.
- `core/src/com/unciv/ui/screens/cityscreen/BuyButtonFactory.kt`
  - Calls `city.cityConstructions.purchaseConstruction(...)`.

For LLM output, the minimal action shape can be `cityId/name + constructionName + mode(addToQueue|setHead|purchase)`. The safest first implementation should use existing `canAddToQueue(...)` and `addToQueue(...)` rather than directly mutating `constructionQueue`.

## 5. Как представлены и применяются выборы исследования технологий

State model:

- `core/src/com/unciv/logic/civilization/managers/TechManager.kt`
  - Each `Civilization` has `var tech = TechManager()`.
  - `techsResearched: HashSet<String>` is serialized completed research.
  - `techsToResearch: ArrayList<String>` is serialized research queue.
  - Current research is not a separate field: `currentTechnologyName()` returns `techsToResearch[0]` or `null`.
  - `techsInProgress: HashMap<String, Int>` stores accumulated science by tech name.
  - `overflowScience`, `freeTechs`, `scienceOfLast8Turns`, and `scienceFromResearchAgreements` affect progression.

Choice application:

- `TechManager.currentTechnologyName()` and `currentTechnology()` read queue head.
- `TechManager.canBeResearched(techName)` checks prerequisites and availability.
- `TechManager.endTurn(scienceForNewTurn)` adds science to current research, including overflow and research-agreement science.
- `TechManager.addScience(scienceGet)` increments current tech progress and calls `addTechnology(...)` when cost is reached.
- `TechManager.addTechnology(techName, showNotification)`:
  - Adds to `techsResearched`.
  - Removes it from `techsToResearch` unless continually researchable.
  - Clears in-progress entry.
  - Updates transient tech objects/uniques/era/booleans.
  - Fires notifications, triggers uniques, updates civ resources and city population assignment.
- `TechManager.getFreeTechnology(techName)` consumes a free tech and calls `addTechnology(...)`.
- `TechManager.updateResearchProgress()` completes research if existing progress plus overflow is enough.
- `core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt`
  - `chooseTechToResearch(civInfo)` is private AI logic.
  - It fills `civInfo.tech.techsToResearch` when empty and handles free tech choices.

UI path:

- `core/src/com/unciv/ui/screens/pickerscreens/TechPickerScreen.kt`
  - `tempTechsToResearch` is an editable UI copy.
  - `selectTechnology(...)` updates only the temp queue.
  - `tryExit()` commits:
    - Free tech: `civTech.getFreeTechnology(selectedTech!!.name)`.
    - Normal research: `civTech.techsToResearch = tempTechsToResearch`.
    - Then `civTech.updateResearchProgress()` and closes the screen.
- `core/src/com/unciv/ui/screens/worldscreen/status/NextTurnAction.kt`
  - Opens `TechPickerScreen(worldScreen.viewingCiv, null)` when a tech choice is required.
- `core/src/com/unciv/ui/screens/worldscreen/TechPolicyDiplomacyButtons.kt`
  - Displays current research or "Pick a tech".

For LLM output, the minimal action shape can be `civId + techName/path`. The safest first implementation should set `civ.tech.techsToResearch` using existing path/prerequisite helpers, then call `updateResearchProgress()`.

## 6. Как работает завершение хода — "end turn"

UI flow:

- `core/src/com/unciv/ui/screens/worldscreen/status/NextTurnButton.kt`
  - Activation calls `nextTurnAction.action(worldScreen)`.
  - Multi-turn autoplay calls `TurnManager(worldScreen.viewingCiv).automateTurn()` then `worldScreen.nextTurn()`.
- `core/src/com/unciv/ui/screens/worldscreen/status/NextTurnAction.kt`
  - Chooses whether the button means "Next unit", "Pick tech", "Move automated units", "AutoPlay", or default end-turn behavior.
- `core/src/com/unciv/ui/screens/worldscreen/WorldScreen.kt`
  - `nextTurn()` disables UI, creates `NextTurnProgress`, clones `gameInfo`, calls `gameInfoClone.setTransients()`, runs `gameInfoClone.nextTurn(progressBar, true)` off the GL thread, uploads multiplayer state if relevant, then calls `startNewScreenJob(gameInfoClone, autoPlay)`.
  - `startNewScreenJob(...)` calls `UncivGame.Current.loadGame(...)` and triggers autosave depending on settings.

Core flow:

- `core/src/com/unciv/logic/GameInfo.kt`
  - `nextTurn(...)` starts with `currentPlayerCiv`.
  - If current player is human, calls `TurnManager(player).endTurn(...)` and advances index.
  - While the next player is simulation, AI, defeated hotseat player, online spectator, or defeated online player:
    - `TurnManager(player).startTurn(...)`
    - `TurnManager(player).automateTurn()`
    - victory/autoplay checks
    - `TurnManager(player).endTurn(...)`
    - advance to next player
  - Sets `currentPlayer`, `currentPlayerCiv`, `currentTurnStartTime`.
  - Calls `TurnManager(player).startTurn(...)` for the next human/current player.
  - Clears spectator popups and sends start-turn notifications.

Per-civ lifecycle:

- `core/src/com/unciv/logic/civilization/managers/TurnManager.kt`
  - `startTurn(...)` updates research progress, resources, city connections, stats, religion, unit start-turn state, automated units if configured, trade request validity, promotions, and victory state.
  - `endTurn(...)` handles city auto-bombard, turn-end triggers, notification logs, culture/policies, city-state quests, gold/upkeep, tech science, faith/religion, espionage, great people, each city `CityTurnManager(city).endTurn()`, temporary uniques, golden ages, unit end-turn, diplomacy next-turn, caches, and victory state.
  - `automateTurn()` calls `NextTurnAutomation.automateCivMoves(civInfo)` for non-defeated civs and updates barbarian encampments.

The core turn path can run with `progressBar = null`, which is important for headless experiments. The main caveat is that some code still reads `UncivGame.Current.settings` and occasionally `UncivGame.Current.worldScreen`.

## 7. Какие существующие точки входа AI/automation можно переиспользовать

Core automation:

- `core/src/com/unciv/logic/civilization/managers/TurnManager.kt`
  - `automateTurn()` is the compact "let AI play this civ" entry point.
- `core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt`
  - `automateCivMoves(civInfo, tradeAndChangeState = true)` is the top-level AI task list.
  - Public helpers include `automateCityBombardment(civInfo)`, `automateCities(civInfo)`, `automateGoldToSciencePercentage(civInfo)`, and `getUnitPriority(...)`.
  - Private but relevant logic includes `chooseTechToResearch(...)`, policy adoption, diplomacy, faith, city-state actions, and voting.
- `core/src/com/unciv/logic/automation/city/ConstructionAutomation.kt`
  - `ConstructionAutomation(cityConstructions).chooseNextConstruction()` is the city-production decision engine.
- `core/src/com/unciv/logic/automation/unit/UnitAutomation.kt`
  - Used by autoplay and AI for automated unit moves.
- `core/src/com/unciv/ui/screens/worldscreen/unit/AutoPlay.kt`
  - UI-oriented thread guard/state for autoplay. Useful to understand behavior, less ideal as gateway API because it depends on `WorldScreen`.

Desktop/headless-ish entry points:

- `desktop/src/com/unciv/app/desktop/CreateGameFromSettings.kt`
  - Best existing minimal gateway-like path for creating games from JSON without entering normal UI.
- `desktop/src/com/unciv/app/desktop/ConsoleLauncher.kt`
  - Runs deterministic-ish simulations using `GameStarter` and `Simulation`.
- `core/src/com/unciv/logic/simulation/Simulation.kt`
  - Repeated AI simulations over `GameInfo`.
- `tests/src/com/unciv/testing/GdxTestRunner.kt`
  - Shows how tests bootstrap a LibGDX `HeadlessApplication`.

Reusable decision surfaces for an LLM:

- Current game summary: `GameInfo`, `Civilization`, city/unit/tech managers.
- Apply city production: `CityConstructions.addToQueue(...)`, `setCurrentConstruction(...)` only if bypassing queue semantics is intentional.
- Apply research: assign `TechManager.techsToResearch`, then call `updateResearchProgress()`.
- End turn: `GameInfo.nextTurn(null)` in a headless/core path, or `WorldScreen.nextTurn()` in UI.
- Fall back to AI: `TurnManager(civ).automateTurn()` or narrower `NextTurnAutomation.automateCities(civ)`.

## 8. Самое маленькое место, куда можно добавить headless или desktop-only эксперимент с LLM

Recommended smallest experiment:

- Add a desktop-only CLI branch alongside `--creategame=` in `desktop/src/com/unciv/app/desktop/DesktopLauncher.kt`, implemented in a new helper near `desktop/src/com/unciv/app/desktop/CreateGameFromSettings.kt`.
- Reuse the same bootstrap pattern:
  - `val game = UncivGame(true)`
  - `UncivGame.Current = game`
  - `UncivGame.Current.settings = GameSettings()`
  - `RulesetCache.loadRulesets(consoleMode = true, ...)`
  - Load or create `GameInfo`.
  - Apply a tiny command surface to `GameInfo`.
  - Serialize result with `UncivFiles.gameInfoToString(...)` or save through `UncivFiles`.

Why this is smaller than touching UI:

- `CreateGameFromSettings.startGame(...)` already creates games without `WorldScreen`, skin, audio, `LoadingScreen`, or GL screen stack.
- `ConsoleLauncher` already proves `GameStarter`, `RulesetCache`, and simulations can run outside normal desktop UI.
- `UncivGame.loadGame(...)` should be avoided for the first gateway because it creates `WorldScreen` and touches GL/UI.

Minimal viable gateway shape:

1. Input: JSON containing either save path/string or `GameConfig`, current player/civ id, and requested LLM/automation action.
2. Bootstrap rulesets/settings in console mode.
3. Load/create `GameInfo`.
4. Call `gameInfo.setTransients()` after load if using custom parse path.
5. Read `val civ = gameInfo.getCurrentPlayerCivilization()` or explicit `gameInfo.getCivilization(civId)`.
6. Apply one action:
   - city production via `city.cityConstructions.addToQueue(name)`;
   - research via `civ.tech.techsToResearch = arrayListOf(...)` and `updateResearchProgress()`;
   - fallback automation via `TurnManager(civ).automateTurn()`;
   - end turn via `gameInfo.nextTurn(null)`.
7. Output serialized `GameInfo` or a compact decision/result JSON.

Avoid initially:

- Network calls to real LLM from inside core logic.
- Adding LLM calls inside `GameInfo.nextTurn(...)`.
- Mutating multiplayer games unless ownership/current-user checks are explicitly handled.
- Returning UI-only objects or relying on `GUI.getWorldScreen()`.

## 9. Какие команды нужны, чтобы собрать и запустить desktop-версию

From repository root:

```bash
./gradlew desktop:run
```

This runs `DesktopLauncher` with working directory `android/assets` as configured in `desktop/build.gradle.kts`.

Build desktop jar:

```bash
./gradlew desktop:dist
```

The jar task produces `desktop/build/libs/Unciv.jar` and bundles desktop runtime classpath plus assets.

Useful checks:

```bash
./gradlew classes
./gradlew tests:test
./gradlew check
```

Existing headless game creation path:

```bash
./gradlew desktop:run --args="--creategame=/absolute/path/to/game-config.json"
```

The `desktop:run` task uses `DesktopLauncher`, whose `--creategame=` branch exits after creating/uploading the game.

Simulation path:

- Use `desktop/src/com/unciv/app/desktop/ConsoleLauncher.kt`.
- Working directory should be `android/assets`.
- It is not currently wired as the default `desktop:run` main class.

## 10. Риски: глобальное состояние, связка с UI, сериализация/transients, multiplayer-предположения

Global state:

- `UncivGame.Current` is widely used from core and UI.
- `UncivGame.Current.settings` is read by turn logic, automation, saving, notifications, and city production.
- Other global/static surfaces include `Gdx`, `GUI`, `Display.platform`, `Fonts.fontImplementation`, `UncivFiles.saverLoader`, `ImageGetter.ruleset`, `RulesetCache`, `SkinCache`, `TileSetCache`, and `DebugUtils`.
- A headless gateway must initialize enough of this global state to satisfy core code, even if it avoids screens.

UI coupling:

- `UncivGame.loadGame(...)` creates `LoadingScreen` and `WorldScreen`, manages screen stack, and requires GL-context operations.
- `WorldScreen.nextTurn()` is a UI wrapper around core turn logic and should not be the first headless API.
- `GameInfo.nextTurn(...)` is mostly core, but still reads `UncivGame.Current.worldScreen` for autoplay/victory checks and music-related state.
- Some managers emit notifications/popups rather than returning structured results.

Serialization and transients:

- Classes implementing `IsPartOfGameInfoSerialization` are part of save compatibility.
- `@Transient` fields are essential runtime links/caches, not optional conveniences.
- After custom deserialization, always call `GameInfo.setTransients()`.
- Do not add nontrivial fields to serialized classes casually; changes may require compatibility version/migration work in `BackwardCompatibility.kt`.
- `List`/collection concrete types matter because LibGDX JSON deserialization has constraints documented in `GameInfo.kt`.
- `GameInfo.clone()` is used to avoid concurrent mutation during autosave and UI next-turn.

Multiplayer assumptions:

- `GameInfo.gameParameters.isOnlineMultiplayer` changes current-player semantics.
- `GameInfo.getPlayerToViewAs()` depends on `UncivGame.Current.settings.multiplayer.getUserId()`.
- `isUsersTurn()` and multiplayer upload/download paths assume a real player id and server state.
- `WorldScreen.nextTurn()` uploads new multiplayer state and may reject/handle stale server state.
- `CreateGameFromSettings` can configure multiplayer and upload, but that is a separate risk surface from local headless experimentation.
- A minimal gateway should start with local single-player saves/configs and explicitly reject online multiplayer until ownership, staleness, upload, and authentication semantics are designed.

Practical conclusion:

- The cleanest first LLM gateway is not a new core dependency on LLMs.
- It should be a desktop/headless adapter that reads a `GameInfo`, summarizes a bounded decision surface, applies one validated action through existing managers, and writes the resulting `GameInfo`.
- Core hooks to reuse first: `GameStarter.startNewGame`, `UncivFiles.gameInfoFromString/gameInfoToString`, `GameInfo.getCurrentPlayerCivilization`, `CityConstructions.addToQueue`, `TechManager.techsToResearch/updateResearchProgress`, `TurnManager.automateTurn`, and `GameInfo.nextTurn(null)`.
