# Characterization Index

This index lists the current characterization coverage for the LLM Gateway
work. These tests capture existing Unciv behavior that the gateway expects
to call through existing managers.

## TechManager

- `tests/src/com/unciv/logic/llm/characterization/tech/TechManagerCharacterization.kt`
  - `TechManager.canBeResearched(...)`
  - Captures researchable, prerequisite-blocked, already-researched, and unknown-tech behavior.
  - Known SMELL: `#techmanager-unknown-tech-throws`.

## CityConstructions

- `tests/src/com/unciv/logic/llm/characterization/construction/CityConstructionsCharacterization.kt`
  - `CityConstructions.addToQueue(...)`
  - `CityConstructions.canAddToQueue(...)`
  - `CityConstructions.removeFromQueue(...)`
  - Captures current-construction-as-queue-head and user-set bookkeeping.

## GameInfo / Turn Flow

- `tests/src/com/unciv/logic/llm/characterization/turn/GameInfoNextTurnCharacterization.kt`
  - `GameInfo.nextTurn(progressBar = null)`
  - Captures local two-human turn rotation and turn-number increment on wrap.
  - Known SMELL: `#gameinfo-nextturn-music-controller`.
