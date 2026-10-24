# Important Changes

Changes here are outside the scoring system and were explicitly approved.

## Evaluation harness overhaul (approved 2026-10-23)

### What changed

- `EvalGame` now treats **`MiniMaxAgent` (modified `Scoring`) as the candidate**
  and **`MiniMaxAgentCopy` (unchanged `ScoringCopy`) as the baseline**.
- Evaluation games run in parallel on a fixed thread pool.
- Engine caches (`Building` forms, corners, silhouettes and all possible placements)
  are prewarmed before parallel games start. This avoids concurrent lazy writes into
  shared `EnumMap` caches inside the game library.
- Per-game evaluation logging uses `INFO` instead of `DEBUG`.
- Added a Gradle `run` task:
  `./gradlew run -PappArgs="eval 5"`.

### Why

- The previous summary called the unchanged copy the "candidate", which inverted the
  meaning of wins and losses when tuning `Scoring`.
- Each move currently takes tens of seconds because the agents ignore time limits and
  deep-copy the game tree. Running all games sequentially made 5-game comparisons
  impractical.
- Parallel execution is safe only after prewarming the library's shared lazy caches.

### Impact on game logic

None. These changes do not alter scoring weights, search depth, move ordering or
agent decision rules. They only change how evaluation games are scheduled, labelled
and logged.

## Approved scoring-algorithm change: strategic enclosure (2026-10-23)

### What changed

- Added `strategicEnclosureScore(game, player)` to `Scoring.kt`.
- It detects small empty pockets (up to 8 cells) that are fully bounded by the
  player's buildings/territory and/or the board edge.
- It requires no opponent boundary contact, at least two own boundary cells, and
  an own/edge boundary share of at least 0.8.
- A qualifying pocket scores `size * 2 + 4`; the total term is capped at 30.
- Added the term to the evaluation with weight `1.0`.
- Also tuned related weights:
  - `remainingDelta`: 3.5 -> 6.0
  - `buildingControl`: 3.0 -> 1.5
- `ScoringCopy.kt` was deliberately left unchanged as the baseline.

### Why

The original enclosure heuristics either ignored edge-assisted pockets or rewarded
large, partially open areas. Those broad rewards caused large regressions in
Black's play. The conservative pocket rule targets only tactical enclosures that
are about to become captured territory.

### Verified result

Each variant was evaluated over 5 games against the unchanged copy:

- Final variant: 5 wins, 0 losses.
- Modified agent scored 0 in every game.
- Baseline scored 8 when the modified agent was White, and 13 when Black.
- Final runtime: 217.2s for 5 parallel games.

## 10-second move limit and search optimization (approved 2026-10-23)

### What changed

- `MinimaxBotCopy` is the frozen previous-version comparison bot.
- `MiniMaxAgent` now:
  - uses `timeForTurn + timeBonus` with a 2-second safety margin,
  - always prepares a legal fallback move before searching,
  - uses iterative deepening and returns the best completed result,
  - searches on `Board` copies instead of full `Game` history copies,
  - generates legal moves directly from cached placements,
  - uses a transposition table with alpha-beta bounds and best-move reuse,
  - supports `stop()`.
- `EvalGame` measures and reports per-move timing and timeout counts.

### Why

The old bot ignored its time budget and could exceed 10 seconds, which would lose
the game automatically. The new design guarantees a legal move before the deadline
while using the remaining time to search deeper.

### Verification

- Smoke test: new bot maximum move time 8,505ms, 0 timeouts; old copy reached
  21,492ms and timed out twice.
- Full 5-game evaluation against the old bot:
  - 4 wins, 1 loss.
  - Maximum candidate move time: 8,007ms.
  - Candidate timeouts: 0.
  - Old bot maximum move time: 31,812ms; 13 timeouts.
- Additional optimization experiments:
  - Principal Variation Search: rejected (2W-3L).
  - `maxMovesPerBranch = 20`: rejected (3W-2L, mixed result).
- Final accepted version is the first optimized minimax. `MinimaxBotCopy` is an
  identical frozen snapshot apart from its class name.
- Full evaluation history is recorded in `changes.md` and `memory.md`.

## Approved depth/heuristic tuning (2026-10-23)

### Kept

- `remainingDelta` weight 6.0 -> 7.5.
- Same 2W-3L record against the frozen baseline, but head-to-head score margin
  improved from +0.80 to 0.00.
- `ScoringCopy` was frozen at these H1 values for the comparison bot.

### Discarded

- Maximum iterative depth 8 -> 16: identical result, no improvement.
- Branch limit 28 -> 20: 1W-4L, clearly worse.
- `strategicEnclosure` 1.0 -> 1.25: 2W-3L but worse score margin.
- `territoryControl` 2.0 -> 2.5: 1W-4L, clearly worse.

All discarded changes are recorded in `DISCARDED`. The final candidate had
0 timeouts and a maximum observed move time of 8,008ms.
