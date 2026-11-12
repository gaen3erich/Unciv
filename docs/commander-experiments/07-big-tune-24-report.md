# Tactical arena: `default+UnitTactics+LlmCommander` vs `default+UnitTactics`

- Positions: 24 from 14 games (Tune)
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**candidate is better than the baseline**

| | value |
|---|---|
| Mean effect, production per position | +177.8 [+51.1, +295.3] |
| Mean effect, share of the production in the battle | +13.8% [+4.5%, +24.1%] |
| Positions better / worse / equal | 18 / 6 / 0 |
| Controls that replayed identically | 21 of 24 |
| LLM commander calls / battles lost to model errors | 598 / 0 |

Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.
