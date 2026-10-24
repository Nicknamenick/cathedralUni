package main.bot.ai

import de.fhkiel.ki.cathedral.ai.Agent
import de.fhkiel.ki.cathedral.game.Board
import de.fhkiel.ki.cathedral.game.Building
import de.fhkiel.ki.cathedral.game.Color
import de.fhkiel.ki.cathedral.game.Game
import de.fhkiel.ki.cathedral.game.Placement
import java.util.Optional

import main.bot.ai.Scoring.Companion.calcScorePrimitive
import main.bot.ai.Utils.Companion.logger

/**
 * Time-limited alpha-beta minimax agent.
 *
 * The agent always produces a legal fallback move before searching. Iterative deepening
 * then improves that move only while time remains, so it cannot lose on the clock.
 */
class MiniMaxAgent : Agent {
    private val maxDepth = 8
    private val maxRootMoves = 100
    private val maxMovesPerBranch = 28
    private val maxTranspositionEntries = 200_000

    private enum class TTFlag { EXACT, LOWER, UPPER }

    private data class TTEntry(
        val depth: Int,
        val score: Float,
        val flag: TTFlag,
        val bestMove: Placement?
    )

    private data class RootResult(
        val bestMove: Placement,
        val bestScore: Float,
        val evaluatedAny: Boolean,
        val completed: Boolean
    )

    private val transpositionTable = HashMap<Long, TTEntry>(1 shl 16)
    private val directions = arrayOf(
        intArrayOf(1, 0),
        intArrayOf(-1, 0),
        intArrayOf(0, 1),
        intArrayOf(0, -1)
    )

    @Volatile
    private var stopped = false

    private var deadlineNanos = Long.MAX_VALUE
    private var aborted = false
    private var nodes = 0L

    override fun name(): String {
        return "MiniMaxAgent"
    }

    override fun stop() {
        stopped = true
    }

    override fun calculateTurn(
        game: Game?,
        timeForTurn: Int,
        timeBonus: Int
    ): Optional<Placement> {
        val startedAt = System.nanoTime()
        stopped = false
        aborted = false
        nodes = 0
        transpositionTable.clear()

        val totalBudgetMs = (timeForTurn.coerceAtLeast(0).toLong() +
            timeBonus.coerceAtLeast(0).toLong()) * 1_000L
        val safetyMs = minOf(2_000L, maxOf(100L, totalBudgetMs / 5))
        val searchBudgetMs = (totalBudgetMs - safetyMs).coerceAtLeast(50L)
        deadlineNanos = startedAt + searchBudgetMs * 1_000_000L

        val rootGame = game ?: throw IllegalArgumentException("Game cannot be null")
        val rootBoard = rootGame.board.copy()
        val rootPlayer = rootGame.currentPlayer

        // The cathedral is a neutral opening. Return quickly instead of searching a
        // position where the evaluation is constant for Blue.
        if (rootPlayer == Color.Blue) {
            val cathedralMoves = generateValidMoves(rootBoard, rootPlayer)
            if (cathedralMoves.isEmpty()) {
                return Optional.empty()
            }
            val orderedCathedralMoves = orderMoves(rootBoard, rootPlayer, cathedralMoves, null)
            return Optional.of(orderedCathedralMoves.first())
        }

        val availableMoves = generateValidMoves(rootBoard, rootPlayer)
        if (availableMoves.isEmpty()) {
            logger.warn("No valid moves available")
            return Optional.empty()
        }

        // Guaranteed legal fallback, chosen before any iterative-deepening work.
        var bestMove = orderMoves(rootBoard, rootPlayer, availableMoves, null).first()
        if (aborted || timeUp()) {
            aborted = false
            logger.warn("Move generation exhausted the budget; using fallback move")
            return Optional.of(bestMove)
        }

        var bestScore = Float.NEGATIVE_INFINITY
        var reachedDepth = 0
        var rootMoves = orderMoves(
            rootBoard,
            rootPlayer,
            availableMoves,
            bestMove
        ).take(maxRootMoves)

        for (depth in 1..maxDepth) {
            if (timeUp()) {
                break
            }

            // Search the previous iteration's best move first.
            rootMoves = listOf(bestMove) + rootMoves.filter { it != bestMove }
            val result = searchRoot(rootBoard, rootPlayer, rootMoves, depth)

            if (result.evaluatedAny) {
                bestMove = result.bestMove
                bestScore = result.bestScore
                reachedDepth = depth
            }

            if (!result.completed) {
                break
            }
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        logger.debug(
            "selecting best move: ${bestMove.building().name} at position: ${bestMove.position()} " +
                "score=$bestScore depth=$reachedDepth nodes=$nodes time=${elapsedMs}ms"
        )
        return Optional.of(bestMove)
    }

    private fun searchRoot(
        board: Board,
        player: Color,
        moves: List<Placement>,
        depth: Int
    ): RootResult {
        var bestMove = moves.first()
        var bestScore = Float.NEGATIVE_INFINITY
        var evaluatedAny = false
        var alpha = Float.NEGATIVE_INFINITY

        for (move in moves) {
            if (timeUp()) {
                aborted = true
                break
            }

            val child = board.copy()
            if (!child.placeBuilding(move, false)) {
                continue
            }

            val score = minimax(
                board = child,
                player = nextPlayer(player),
                depth = depth - 1,
                rootPlayer = player,
                alpha = alpha,
                beta = Float.POSITIVE_INFINITY
            )

            if (aborted) {
                break
            }

            evaluatedAny = true
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
            if (bestScore > alpha) {
                alpha = bestScore
            }
        }

        return RootResult(
            bestMove = bestMove,
            bestScore = bestScore,
            evaluatedAny = evaluatedAny,
            completed = !aborted
        )
    }

    private fun minimax(
        board: Board,
        player: Color,
        depth: Int,
        rootPlayer: Color,
        alpha: Float,
        beta: Float
    ): Float {
        nodes++
        if (stopped || ((nodes and 0x3FFL) == 0L && timeUp())) {
            aborted = true
            return 0f
        }

        if (depth <= 0) {
            return evaluate(board, rootPlayer)
        }

        val key = transpositionKey(board, player, rootPlayer)
        val entry = transpositionTable[key]
        var currentAlpha = alpha
        var currentBeta = beta
        var ttMove: Placement? = null

        if (entry != null) {
            ttMove = entry.bestMove
            if (entry.depth >= depth) {
                when (entry.flag) {
                    TTFlag.EXACT -> return entry.score
                    TTFlag.LOWER -> if (entry.score > currentAlpha) currentAlpha = entry.score
                    TTFlag.UPPER -> if (entry.score < currentBeta) currentBeta = entry.score
                }
                if (currentAlpha >= currentBeta) {
                    return entry.score
                }
            }
        }

        val legalMoves = generateValidMoves(board, player)
        if (aborted) {
            return 0f
        }

        if (legalMoves.isEmpty()) {
            return minimax(
                board = board,
                player = nextPlayer(player),
                depth = depth - 1,
                rootPlayer = rootPlayer,
                alpha = currentAlpha,
                beta = currentBeta
            )
        }

        val orderedMoves = orderMoves(board, player, legalMoves, ttMove)
        val moveLimit = minOf(orderedMoves.size, maxMovesPerBranch)
        val maximizing = player == rootPlayer
        var bestScore = if (maximizing) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
        var bestMove: Placement? = null

        for (index in 0 until moveLimit) {
            if (timeUp()) {
                aborted = true
                break
            }

            val move = orderedMoves[index]
            val child = board.copy()
            if (!child.placeBuilding(move, false)) {
                continue
            }

            val score = minimax(
                board = child,
                player = nextPlayer(player),
                depth = depth - 1,
                rootPlayer = rootPlayer,
                alpha = currentAlpha,
                beta = currentBeta
            )

            if (aborted) {
                return 0f
            }

            if (maximizing) {
                if (score > bestScore) {
                    bestScore = score
                    bestMove = move
                }
                if (bestScore > currentAlpha) {
                    currentAlpha = bestScore
                }
            } else {
                if (score < bestScore) {
                    bestScore = score
                    bestMove = move
                }
                if (bestScore < currentBeta) {
                    currentBeta = bestScore
                }
            }

            if (currentAlpha >= currentBeta) {
                break
            }
        }

        if (bestMove == null) {
            return evaluate(board, rootPlayer)
        }

        val flag = when {
            bestScore <= alpha -> TTFlag.UPPER
            bestScore >= beta -> TTFlag.LOWER
            else -> TTFlag.EXACT
        }

        if (transpositionTable.size < maxTranspositionEntries) {
            transpositionTable[key] = TTEntry(depth, bestScore, flag, bestMove)
        }

        return bestScore
    }

    /**
     * Fast legal-move generation. This mirrors Board.placeBuilding's placement check
     * without copying the whole board for every candidate placement.
     */
    private fun generateValidMoves(board: Board, player: Color): MutableList<Placement> {
        val moves = ArrayList<Placement>(128)
        val field = board.getField()
        val ownTerritory = when (player) {
            Color.Black -> Color.Black_Owned
            Color.White -> Color.White_Owned
            else -> Color.None
        }

        for (building in board.getPlacableBuildings(player)) {
            if (timeUp()) {
                aborted = true
                break
            }

            var checked = 0
            for (placement in building.getAllPossiblePlacements()) {
                if ((++checked and 0xFF) == 0 && timeUp()) {
                    aborted = true
                    break
                }
                if (isLegal(field, placement, ownTerritory)) {
                    moves.add(placement)
                }
            }

            if (aborted) {
                break
            }
        }

        return moves
    }

    private fun isLegal(
        field: Array<Array<Color>>,
        placement: Placement,
        ownTerritory: Color
    ): Boolean {
        for (part in placement.form()) {
            val x = placement.x() + part.x()
            val y = placement.y() + part.y()
            if (x !in 0..9 || y !in 0..9) {
                return false
            }

            val cell = field[y][x]
            if (cell != Color.None && cell != ownTerritory) {
                return false
            }
        }
        return true
    }

    /**
     * Cheap move ordering. It deliberately avoids full board evaluation for every
     * candidate; leaf evaluation is handled by the scoring function.
     */
    private fun orderMoves(
        board: Board,
        player: Color,
        moves: List<Placement>,
        ttMove: Placement?
    ): List<Placement> {
        val field = board.getField()
        val ownTerritory = when (player) {
            Color.Black -> Color.Black_Owned
            Color.White -> Color.White_Owned
            else -> Color.None
        }

        return moves
            .map { move ->
                val orderingScore = if (move == ttMove) {
                    Float.MAX_VALUE
                } else {
                    moveOrderScore(field, move, player, ownTerritory)
                }
                move to orderingScore
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun moveOrderScore(
        field: Array<Array<Color>>,
        move: Placement,
        player: Color,
        ownTerritory: Color
    ): Float {
        var score = move.building().score() * 10f

        for (part in move.form()) {
            val x = move.x() + part.x()
            val y = move.y() + part.y()

            for (direction in directions) {
                val nextX = x + direction[0]
                val nextY = y + direction[1]
                if (nextX !in 0..9 || nextY !in 0..9) {
                    continue
                }

                when (field[nextY][nextX]) {
                    player, ownTerritory -> score += 4f
                    Color.Black, Color.White, Color.Black_Owned, Color.White_Owned -> score -= 1f
                    else -> Unit
                }
            }

            val edgeDistance = minOf(x, y, 9 - x, 9 - y)
            score += (4 - edgeDistance) * 0.25f
        }

        return score
    }

    private fun evaluate(board: Board, player: Color): Float {
        return calcScorePrimitive(Game(board), player)
    }

    private fun nextPlayer(player: Color): Color {
        return when (player) {
            Color.Blue -> Color.Black
            Color.Black -> Color.White
            Color.White -> Color.Black
            else -> Color.None
        }
    }

    /**
     * Order-independent board key for the transposition table. Placement order is
     * irrelevant, so only the field, free building counts and the multiset of placed
     * buildings are included.
     */
    private fun transpositionKey(board: Board, player: Color, rootPlayer: Color): Long {
        var hash = 1125899906842597L
        val field = board.getField()

        for (y in field.indices) {
            for (x in field[y].indices) {
                hash = (hash xor field[y][x].id.toLong()) * 1099511628211L
            }
        }

        for (building in Building.values()) {
            hash = (hash xor (building.id.toLong() shl 8)) * 1099511628211L
            hash = (hash xor board.getNumberOfFreeBuildings(building).toLong()) * 1099511628211L
        }

        var placedHash = 0L
        for (placement in board.placedBuildings) {
            placedHash += placement.hashCode().toLong()
        }

        hash = hash xor placedHash
        hash = hash xor (player.id.toLong() shl 32)
        return hash xor (rootPlayer.id.toLong() shl 48)
    }

    private fun timeUp(): Boolean {
        return stopped || System.nanoTime() >= deadlineNanos
    }
}
