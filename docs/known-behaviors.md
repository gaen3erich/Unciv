# Known Behaviors

This file records captured behaviors that are relevant to the LLM Gateway
work. Entries marked as SMELL are intentionally preserved by
characterization tests until a deliberate change is designed.

## TechManager

### techmanager-unknown-tech-throws

`TechManager.canBeResearched("DefinitelyNotARealTech")` currently throws
`NullPointerException` because it indexes `ruleset.technologies[techName]!!`.

We preserve this behavior because the gateway should not change mature
Unciv manager semantics while adding a new adapter layer. Gateway collectors
must check `civ.gameInfo.ruleset.technologies` before calling
`canBeResearched(...)` when the tech name may come from a policy.

Revisit if Unciv introduces a public safe tech lookup or changes
`canBeResearched(...)` to return a typed failure. Owner: LLM Gateway MVP
maintainer.

## GameInfo / Turn Flow

### gameinfo-nextturn-music-controller

`GameInfo.nextTurn(progressBar = null)` is mostly core turn logic, but when
the resulting turn number is a multiple of 10 it calls
`UncivGame.Current.musicController.chooseTrack(...)`.

In the existing lightweight `TestGame` headless fixture, `musicController`
is not initialized, so calling `nextTurn(null)` through that branch throws
`UninitializedPropertyAccessException`.

We preserve this behavior because the MVP gateway should not move music or
UI-adjacent behavior out of `GameInfo.nextTurn`. Headless gateway tests and
adapters should either initialize enough of `UncivGame.Current` or use
fixtures that avoid turn multiples of 10 when the test is not about music
coupling.

Revisit if the gateway needs robust arbitrary-turn headless stepping or if
Unciv separates music side effects from core turn advancement. Owner: LLM
Gateway MVP maintainer.
