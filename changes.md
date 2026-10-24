# Changes

This file documents every code change and the reasoning behind it.

## 2026-10-23

### Evaluation harness (`Main.kt`)

- Added a mode-based `main(args)`:
  - `selfplay` (default): existing self-play loop
  - `eval [games]`: evaluation harness
  - `env`: existing environment test
- Implemented `EvalGame`:
  - `GameResult` stores per-game scores, colors and duration.
  - `playGame()` plays one complete game, handles passes and rejected moves.
  - `eval()` alternates the candidate agent between Black and White.
  - `runEval()` prints per-game results and aggregate win/loss/draw statistics.
- Winner rule: lower score wins because unplaced buildings count as points.
- Removed unused `jdk.jshell.execution.Util` import.

Reason: the previous `EvalGame` did not compile (`if (i % 2)` without branches) and
could not produce usable evaluation results.

### Approved evaluation-harness changes (`Main.kt`, `build.gradle.kts`)

- `MiniMaxAgent` (modified `Scoring`) is now reported as the candidate.
- `MiniMaxAgentCopy` (unchanged `ScoringCopy`) is the baseline.
- Added parallel game execution across CPU cores.
- Added cache prewarming before parallel games to make shared library caches safe.
- Eval per-game logging reduced from DEBUG to INFO.
- Added Gradle `run` task with `-PappArgs="..."`.

Reason: evaluation was sequential and extremely slow, and the old summary inverted
the meaning of wins/losses for scoring changes. These changes do not affect scoring
or agent decisions. See `important_changes.md`.

### Baseline evaluation result (2026-10-23)

- 5 games, 240s total, run in parallel.
- Every game ended `Black=3, White=15`; both agents were identical.
- Candidate (modified agent) played White in games 1/3/5: losses.
- Candidate played Black in games 2/4: wins.
- Summary: 2 wins, 3 losses, 0 draws; avg candidate 10.20, avg baseline 7.80.
- Conclusion: current outcome is determined by color, not by agent strength.
  Black is at 3, White at 15. Improved scoring must be measured by per-color score
  reductions as well as wins.

### Scoring tuning

#### Change 1: increase remaining-score weight

- File: `src/main/kotlin/ai/Scoring.kt`
- `remainingDelta` weight: `3.5f` -> `6.0f`

Reason: the final game result is the difference in unplaced building points.
`remainingDelta` is therefore the most direct objective term, while the current
weight may under-emphasize it relative to territory/building proxies.

Evaluation: 5 games, 247.9s.

- Candidate White (games 1/3/5): 7 vs baseline Black 9 -> wins.
- Candidate Black (games 2/4): 0 vs baseline White 13 -> wins.
- Summary: 5 wins, 0 losses, 0 draws; avg candidate 4.20, avg baseline 10.60.
- Decision: keep. This change improved both colors substantially.

#### Change 2: dedicated strategic-enclosure reward

- File: `src/main/kotlin/ai/Scoring.kt`
- Added `strategicEnclosureScore(game, player)`.
- Added weight `strategicEnclosure * 2.5f` to `calcScorePrimitive`.

What it rewards:
- Connected empty regions whose boundary is mostly the player's buildings/territory.
- Board edges count as partial walls, so edge-assisted enclosures are rewarded.
- Larger regions score more, capped at 15 cells to avoid runaway values.
- Extra bonus when there is no opponent boundary.
- Extra bonus for small (<= 6 cells), fully bounded pockets.
- Regions larger than 25 cells are ignored: open board areas are not enclosures.
- The final term is capped at 60 before applying the `1.5` weight.

Reason: the existing enclosure term only rewards fully closed empty regions and
ignores edge-assisted enclosures. This function explicitly scores strategic pockets
that are close to becoming captured territory.

Evaluation: 5 games, 253.5s.

- Candidate White (games 1/3/5): 4 vs baseline Black 11 -> wins.
- Candidate Black (games 2/4): 7 vs baseline White 4 -> losses.
- Summary: 3 wins, 2 losses, 0 draws; avg candidate 5.20, avg baseline 8.20.
- Decision: do not keep at weight 1.5. It improves White (7 -> 4) but causes a
  serious Black regression (0 -> 7). Keep the function, reduce the weight.

#### Change 3: reduce strategic-enclosure weight

- File: `src/main/kotlin/ai/Scoring.kt`
- `strategicEnclosure` weight: `1.5f` -> `0.75f`

Reason: the enclosure term is useful for White but dominates Black's direct scoring
at weight 1.5. A lower weight should preserve the White benefit without sacrificing
Black's direct building placements.

Evaluation: 5 games, 253.5s.

- Candidate White (games 1/3/5): 10 vs baseline Black 0 -> losses.
- Candidate Black (games 2/4): 4 vs baseline White 4 -> draws.
- Summary: 0 wins, 3 losses, 2 draws; avg candidate 7.60, avg baseline 1.60.
- Decision: reject. Weight 0.75 is worse than both 1.5 and no enclosure term.
  The broad boundary-share reward is too noisy and also affects move ordering.

#### Change 4: conservative strategic-enclosure function

- File: `src/main/kotlin/ai/Scoring.kt`
- Replaced broad boundary-share enclosure with small-pocket detection.
- Weight: `0.75f` -> `1.0f`.

New rules:
- Only empty regions of size <= 8 cells.
- No opponent boundary contact.
- At least 2 own boundary cells and ownShare >= 0.8.
- Reward `regionSize * 2 + 4`, capped at 30 before weighting.

Reason: the previous function rewarded large, partially bounded open areas. A
conservative rule should only reward tactical pockets that are about to become
captured territory, reducing disruption to direct building placement.

Evaluation: 5 games, 265.3s.

- Candidate White (games 1/3/5): 0 vs baseline Black 8 -> wins.
- Candidate Black (games 2/4): 0 vs baseline White 13 -> wins.
- Summary: 5 wins, 0 losses, 0 draws; avg candidate 0.00, avg baseline 10.00.
- Decision: keep. This is the best result so far and the first perfect candidate
  score in every game.
- Best-state backup: `/tmp/opencode/Scoring.best.kt`.

#### Change 5: reduce double-counted building control

- File: `src/main/kotlin/ai/Scoring.kt`
- `buildingControl` weight: `3.0f` -> `1.5f`

Reason: `remainingDelta` already reflects the value of placed buildings and captured
buildings. The raw count of placed buildings can overvalue many small pieces. Lowering
it should let the evaluation focus more on building value and territory.

Goal: preserve the candidate's perfect 0 score while increasing the baseline's score
(i.e., capturing more opponent points).

Evaluation: 5 games, 217.2s.

- Candidate White (games 1/3/5): 0 vs baseline Black 8 -> wins.
- Candidate Black (games 2/4): 0 vs baseline White 13 -> wins.
- Summary: 5 wins, 0 losses, 0 draws; avg candidate 0.00, avg baseline 10.00.
- Same final scores as Change 4, but about 18% faster (217.2s vs 265.3s).
- Decision: keep. It preserves the perfect result and reduces double counting.

### Evaluation summary

| Variant | Candidate White | Candidate Black | Record |
|---|---:|---:|---|
| Baseline (no changes) | 15 | 3 | 2W-3L |
| Change 1: remainingDelta 6.0 | 7 | 0 | 5W-0L |
| Change 2: enclosure 1.5 | 4 | 7 | 3W-2L |
| Change 3: enclosure 0.75 | 10 | 4 | 0W-3L-2D |
| Change 4: conservative enclosure 1.0 | 0 | 0 | 5W-0L |
| Change 5: buildingControl 1.5 | 0 | 0 | 5W-0L |

Final scoring weights:

- `remainingDelta`: 6.0
- `territoryControl`: 2.0
- `buildingControl`: 1.5
- `sideBuildingControl`: 1.5
- `connectivityAndEnclosure`: 1.5
- `twoStoneEnclosure`: 3.0
- `strategicEnclosure`: 1.0
- `cornerExpansion`: 2.0

`ScoringCopy` remains unchanged and is the fixed baseline opponent.

### Final decision

Keep Change 5. It combines the direct-objective improvement, the conservative
strategic-enclosure function, and the reduced building-count weight. It wins all
5 games with a perfect candidate score of 0, and is faster than Change 4.

## 2026-10-23: 10-second move limit and search optimization

### Frozen comparison bot

- Renamed the old compare class to `MinimaxBotCopy`.
- It is an exact snapshot of the previous minimax search and still uses the
  unchanged `ScoringCopy`.

Reason: every subsequent search change must be evaluated against the previous
version, so the comparison bot must be frozen before optimizing the main bot.

### `MiniMaxAgent` timer logic

- `calculateTurn` now derives its budget from `timeForTurn + timeBonus`.
- Safety margin: up to 2 seconds (20% of the budget).
- For a 10-second turn the internal deadline is 8 seconds.
- A legal fallback move is generated before the search starts.
- Iterative deepening only replaces the fallback with fully evaluated results.
- If the deadline is reached, the last completed depth (or the fallback) is returned.
- The agent always returns a legal move; it cannot lose on the clock.

### Search optimization

- Replaced full `Game.copy()` / `takeTurn` per node with `Board.copy()` and
  `Board.placeBuilding`.
- Added a custom fast legal-move generator that checks cached placements directly
  instead of copying the board for every candidate.
- Added transposition table with canonical board hash, exact/lower/upper bounds,
  and best-move reuse.
- Added iterative deepening with time-based cutoff.
- Added cheap move ordering using building value, own-cell adjacency, and edge
  proximity.
- Added `stop()` support via a volatile flag.
- The cathedral opening returns immediately because the evaluation is constant for
  Blue.

### Timer instrumentation in evaluation

- `EvalGame` now measures every move.
- Per-game and summary output include:
  - maximum candidate move time,
  - maximum baseline move time,
  - candidate timeouts,
  - baseline timeouts.
- Timeout threshold is 10,000ms.

Smoke test (1 game):
- Candidate max move: 8,505ms.
- Candidate timeouts: 0.
- Old baseline max move: 21,492ms; baseline timeouts: 2.
- Candidate won 4 vs 8.

Full 5-game evaluation (167.9s):
- Game 1: candidate White 4 vs baseline 8 -> win.
- Game 2: candidate Black 7 vs baseline 10 -> win.
- Game 3: candidate White 4 vs baseline 8 -> win.
- Game 4: candidate Black 7 vs baseline 10 -> win.
- Game 5: candidate White 9 vs baseline 8 -> loss.
- Summary: 4 wins, 1 loss, 0 draws.
- Candidate max move: 8,007ms; candidate timeouts: 0.
- Baseline max move: 31,812ms; baseline timeouts: 13.
- Average candidate score 6.20; average baseline 8.80.

Decision: accept. The new bot always returns within the 10-second limit. The one loss
was a close 9-vs-8 game. Freeze this version as the new `MinimaxBotCopy` before the
next search optimization.

### Change 6: Principal Variation Search (rejected)

- File: `src/main/kotlin/ai/MiniMaxAgent.kt`
- Added PVS null-window probes and re-search after the first move.
- Comparison: frozen pre-PVS optimized minimax, same scoring.
- Evaluation: 5 games, 141.2s.
- Result: 2 wins, 3 losses, 0 draws.
- Candidate max move 8,007ms; 0 timeouts.
- Average candidate 6.40; average baseline 5.60.
- Decision: reject and revert. PVS did not improve move quality in this game.
- Reverted to the frozen pre-PVS implementation.

### Change 7: tighter branch limit

- File: `src/main/kotlin/ai/MiniMaxAgent.kt`
- `maxMovesPerBranch`: `28` -> `20`.
- Rationale: searching fewer moves per node may allow deeper iterative-deepening
  iterations within the same 8-second budget.
- Comparison: frozen pre-PVS optimized minimax, same scoring.
- Evaluation: 5 games, 153.6s.
- Result: 3 wins, 2 losses, 0 draws.
- Candidate max move 8,006ms; 0 timeouts.
- Average candidate 7.40; average baseline 9.00.
- Decision: reject. The result is mixed rather than a clear improvement.
- Reverted `maxMovesPerBranch` to 28.

## 2026-10-23: deeper iterative search and heuristic tuning

### New baseline (current bot vs identical frozen copy)

- 5 games, 141.2s.
- 2 wins, 3 losses, 0 draws.
- Candidate White score 8 in games 1/3/5; candidate Black score 4 in games 2/4.
- Average candidate 6.40; baseline 5.60.
- Max candidate move 8007ms; 0 timeouts.
- Result is color-determined, as expected for identical agents.

### Change D1: raise maximum iterative depth

- `src/main/kotlin/ai/MiniMaxAgent.kt`
- `maxDepth`: `8` -> `16`.
- Branch limit stays 28; root move limit stays 100.

Reason: several moves complete depth 8 in only 2-3 seconds and then stop, leaving
most of the 8-second budget unused. A higher cap lets the search continue deeper
when time permits.

Evaluation: 5 games, 144.3s.

- 2 wins, 3 losses, 0 draws.
- Candidate max move 8,006ms; 0 timeouts.
- Average candidate 6.40; baseline 5.60.
- Result identical to baseline; no measurable improvement.
- Decision: discard and revert to `maxDepth = 8`.
- Recorded in `DISCARDED`.

### Change D2: retry tighter branch limit

- `maxMovesPerBranch`: 28 -> 20.
- Evaluation: 5 games, 148.2s.
- Result: 1 win, 4 losses, 0 draws.
- Candidate max move 8,008ms; 0 timeouts.
- Average candidate 11.80; baseline 4.00.
- Decision: discard and revert to 28. A prior run gave 3W-2L, confirming timing
  variance; this repeat result is clearly worse.
- Recorded in `DISCARDED`.

### Frozen scoring baseline for heuristic tuning

- Copied current tuned scoring into `ScoringCopy`.
- `MinimaxBotCopy` now imports `ScoringCopy`; `MiniMaxAgent` imports `Scoring`.
- Both search implementations are identical, so scoring is the only difference.
- Fresh 5-game scoring baseline: pending.
- First heuristic experiment H1: `remainingDelta` 6.0 -> 7.5.

### Heuristic tuning baseline

- Frozen `ScoringCopy` = current scoring.
- `MinimaxBotCopy` uses `ScoringCopy`; `MiniMaxAgent` uses `Scoring`.
- Fresh baseline: 2W-3L, candidate 6.40 vs baseline 5.60 (margin +0.80).

### H1: `remainingDelta` 6.0 -> 7.5 (kept)

- 5 games: 2W-3L.
- Candidate 6.00 vs baseline 6.00 (margin 0.00).
- Max candidate move 8007ms; 0 timeouts.
- Decision: keep. Same record, improved head-to-head margin by 0.8.
- Froze H1 into `ScoringCopy`.

### H2: `strategicEnclosure` 1.0 -> 1.25 (discarded)

- 5 games: 2W-3L.
- Candidate 6.60 vs baseline 5.40 (margin +1.20).
- Max candidate move 8008ms; 0 timeouts.
- Decision: discard and revert to 1.0. Recorded in `DISCARDED`.

### H3: `territoryControl` 2.0 -> 2.5 (discarded)

- 5 games: 1W-4L.
- Candidate 9.00 vs baseline 4.20.
- Max candidate move 8008ms; 0 timeouts.
- Decision: discard and revert to 2.0. Recorded in `DISCARDED`.

### Final accepted tuning

- `maxDepth`: 8 (discarded the 16 variant).
- `maxMovesPerBranch`: 28 (discarded the 20 variant).
- `remainingDelta`: 7.5
- `territoryControl`: 2.0
- `buildingControl`: 1.5
- `sideBuildingControl`: 1.5
- `connectivityAndEnclosure`: 1.5
- `twoStoneEnclosure`: 3.0
- `strategicEnclosure`: 1.0
- `cornerExpansion`: 2.0
- `ScoringCopy` is frozen at these H1 values for the comparison bot.
- All candidate runs had 0 timeouts and stayed under the 10-second limit.

### Final accepted state (2026-10-23)

- `MiniMaxAgent` is the first optimized version:
  - 10-second turn budget with a 2-second safety margin,
  - guaranteed legal fallback move,
  - iterative deepening,
  - Board-based search without full game-history copies,
  - fast cached legal-move generation,
  - transposition table,
  - cheap move ordering,
  - `stop()` support.
- `MinimaxBotCopy` is identical apart from its class name and uses the same scoring.
- Verified result against the old bot: 4 wins, 1 loss, 0 timeouts,
  maximum candidate move time 8,007ms.
- Rejected experiments: PVS (2W-3L) and `maxMovesPerBranch=20` (3W-2L).
- Final build: successful.
