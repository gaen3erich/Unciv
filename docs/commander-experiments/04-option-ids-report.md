# Tactical arena: `default+UnitTactics+LlmCommander` vs `default+UnitTactics`

- Positions: 5 from 3 games (Tune)
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**too few positions for a verdict (need at least 10)**

| | value |
|---|---|
| Mean effect, production per position | +230.5 [+137.5, +412.5] |
| Mean effect, share of the production in the battle | +34.5% [+19.3%, +83.3%] |
| Positions better / worse / equal | 4 / 1 / 0 |
| Controls that replayed identically | 3 of 5 |
| LLM commander calls / battles lost to model errors | 185 / 2 |

Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.
