# Tactical arena: `default+UnitTactics+LlmCommander` vs `default+UnitTactics`

- Positions: 20 from 12 games (All)
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**candidate is better than the baseline**

| | value |
|---|---|
| Mean effect, production per position | +179.4 [+82.6, +284.2] |
| Mean effect, share of the production in the battle | +19.4% [+8.8%, +32.0%] |
| Positions better / worse / equal | 14 / 5 / 1 |
| Controls that replayed identically | 16 of 20 |
| LLM commander calls / battles lost to model errors | 552 / 0 |

Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.
