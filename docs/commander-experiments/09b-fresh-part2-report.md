# Tactical arena: `default+UnitTactics+LlmCommander` vs `default+UnitTactics`

- Positions: 6 from 3 games (All), 2 left out because the model failed in them. STOPPED EARLY: the model stopped answering, the remaining positions were not played
- Horizon: 15 turns; a city is worth 300 production
- Effect of a position: candidate's score minus the baseline's score on the same side of it, averaged over both sides
- Intervals: bootstrap over games, 95%

## Result

**too few positions for a verdict (need at least 10)**

| | value |
|---|---|
| Mean effect, production per position | +80.8 [-88.8, +187.5] |
| Mean effect, share of the production in the battle | +5.4% [+1.2%, +9.0%] |
| Positions better / worse / equal | 4 / 2 / 0 |
| Controls that replayed identically | 6 of 6 |
| LLM commander calls / battles lost to model errors | 158 / 0 |
