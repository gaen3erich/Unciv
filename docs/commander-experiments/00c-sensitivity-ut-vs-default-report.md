# Tactical arena: `default+UnitTactics` vs `default`

- Positions: 96 from 17 games (Tune)
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**candidate is better than the baseline**

| | value |
|---|---|
| Mean effect, production per position | +272.4 [+194.3, +349.6] |
| Mean effect, share of the production in the battle | +9.8% [+6.7%, +13.3%] |
| Positions better / worse / equal | 64 / 26 / 6 |
| Controls that replayed identically | 67 of 96 |

Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.
