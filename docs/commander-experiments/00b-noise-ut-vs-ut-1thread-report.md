# Tactical arena: `default+UnitTactics` vs `default+UnitTactics`

- Positions: 96 from 17 games (Tune)
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**no detectable difference**

| | value |
|---|---|
| Mean effect, production per position | -2.3 [-37.4, +27.1] |
| Mean effect, share of the production in the battle | +0.1% [-0.7%, +0.9%] |
| Positions better / worse / equal | 13 / 14 / 69 |
| Controls that replayed identically | 70 of 96 |

Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.

Candidate and baseline have the same features: the effect only shows how reproducible the arena is.
