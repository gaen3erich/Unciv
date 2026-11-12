# Tactical arena: `default+UnitTactics+LlmCommander` vs `default+UnitTactics`

- Positions: 5 from 3 games (Tune)
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**too few positions for a verdict (need at least 10)**

| | value |
|---|---|
| Mean effect, production per position | +116.5 [-90.0, +270.0] |
| Mean effect, share of the production in the battle | +14.6% [-18.2%, +35.7%] |
| Positions better / worse / equal | 3 / 2 / 0 |
| Controls that replayed identically | 3 of 5 |
| LLM commander calls / battles lost to model errors | 155 / 0 |

Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.
