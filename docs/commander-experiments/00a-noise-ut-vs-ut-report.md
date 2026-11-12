# Tactical arena: `default+UnitTactics` vs `default+UnitTactics`

- Positions: 96 from 17 games (Tune)
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**no detectable difference**

| | value |
|---|---|
| Mean effect, production per position | +4.8 [-26.1, +37.2] |
| Mean effect, share of the production in the battle | +0.5% [-0.7%, +2.0%] |
| Positions better / worse / equal | 11 / 8 / 77 |
| Controls that replayed identically | 75 of 96 |

Some controls did not replay identically, so part of the effect may be noise of the playouts themselves.

Candidate and baseline have the same features: the effect only shows how reproducible the arena is.
