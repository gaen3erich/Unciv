# Tactical arena: `default+UnitTactics+LlmCommander` vs `default+UnitTactics`

- Positions: 96 from 17 games (Tune)
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**no detectable difference**

| | value |
|---|---|
| Mean effect, production per position | -65.2 [-174.6, +40.5] |
| Mean effect, share of the production in the battle | -1.1% [-4.2%, +2.1%] |
| Positions better / worse / equal | 44 / 51 / 1 |
| Controls that replayed identically | 68 of 96 |
| LLM commander calls / battles lost to model errors | 2278 / 0 |

Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.
