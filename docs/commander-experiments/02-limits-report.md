# Tactical arena: `default+UnitTactics+LlmCommander` vs `default+UnitTactics`

- Positions: 5 from 3 games (Tune)
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**too few positions for a verdict (need at least 10)**

| | value |
|---|---|
| Mean effect, production per position | +134.0 [+77.5, +161.3] |
| Mean effect, share of the production in the battle | +17.7% [+14.7%, +21.7%] |
| Positions better / worse / equal | 4 / 0 / 1 |
| Controls that replayed identically | 3 of 5 |
| LLM commander calls / battles lost to model errors | 190 / 0 |

Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.
