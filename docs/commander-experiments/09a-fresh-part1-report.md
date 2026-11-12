# Tactical arena: `default+UnitTactics+LlmCommander` vs `default+UnitTactics`

- Positions: 50 from 28 games (All)
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**no detectable difference**

| | value |
|---|---|
| Mean effect, production per position | +19.8 [-23.0, +74.5] |
| Mean effect, share of the production in the battle | +2.3% [-3.3%, +8.2%] |
| Positions better / worse / equal | 13 / 14 / 23 |
| Controls that replayed identically | 43 of 50 |
| LLM commander calls / battles lost to model errors | 1079 / 556 |

Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.
