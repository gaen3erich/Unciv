# Tactical arena: `default+UnitTactics+LlmCommander` vs `default+UnitTactics`

- Positions: 12 from 6 games (Holdout)
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**no detectable difference**

| | value |
|---|---|
| Mean effect, production per position | +250.4 [-27.1, +452.7] |
| Mean effect, share of the production in the battle | +18.5% [+5.7%, +30.3%] |
| Positions better / worse / equal | 9 / 3 / 0 |
| Controls that replayed identically | 8 of 12 |
| LLM commander calls / battles lost to model errors | 326 / 0 |

Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.
