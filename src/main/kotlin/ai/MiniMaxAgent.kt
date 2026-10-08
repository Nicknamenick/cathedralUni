package main.bot.ai

import de.fhkiel.ki.cathedral.ai.Agent
import de.fhkiel.ki.cathedral.game.Color
import de.fhkiel.ki.cathedral.game.Game
import de.fhkiel.ki.cathedral.game.Placement
import java.util.Optional

import main.bot.ai.Scoring.Companion.calcMovePriority
import main.bot.ai.Scoring.Companion.calcScorePrimitive
import main.bot.ai.Utils.Companion.getValidMoves
import main.bot.ai.Utils.Companion.logger

class MiniMaxAgent : Agent {
    private val maxDepth = 3
    private val maxMovesPerBranch = 40
    private val maxRootMoves = 80

    override fun name(): String {
        return "MiniMaxAgent"
    }

    override fun calculateTurn(
        game: Game?,
        timeForTurn: Int,
        timeBonus: Int
    ): Optional<Placement> {
        val rootGame = game?.copy() ?: throw IllegalArgumentException("Game cannot be null")
        val availableMoves = mutableListOf<Placement>()
        getValidMoves(rootGame, availableMoves)

        if (availableMoves.isEmpty()) {
            logger.warn("No valid moves available")
            return Optional.empty()
        }

        val rootPlayer = rootGame.currentPlayer
        val prunedMoves = availableMoves
            .map { move ->
                val nextGame = rootGame.copy()
                if (nextGame.takeTurn(move)) {
                    move to calcMovePriority(rootGame, nextGame, rootPlayer)
                } else {
                    null
                }
            }
            .filterNotNull()
            .sortedByDescending { it.second }
            .take(maxRootMoves)
            .map { it.first }

        var bestMove = prunedMoves.first()
        var bestScore = Float.NEGATIVE_INFINITY

        for (move in prunedMoves) {
            val nextGame = rootGame.copy()
            if (!nextGame.takeTurn(move)) {
                continue
            }

            val score = minimax(
                game = nextGame,
                depth = maxDepth - 1,
                rootPlayer = rootPlayer,
                alpha = Float.NEGATIVE_INFINITY,
                beta = Float.POSITIVE_INFINITY
            )
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
        }

        logger.debug(
            "selecting best move: ${bestMove.building().name} at position: ${bestMove.position()} with score=$bestScore"
        )
        return Optional.of(bestMove)
    }

    private fun minimax(
        game: Game,
        depth: Int,
        rootPlayer: Color,
        alpha: Float,
        beta: Float
    ): Float {
        if (depth <= 0 || game.isFinished()) {
            return calcScorePrimitive(game, rootPlayer)
        }

        val legalMoves = mutableListOf<Placement>()
        getValidMoves(game, legalMoves)

        if (legalMoves.isEmpty()) {
            val passedGame = game.copy()
            passedGame.passTurn()
            return minimax(passedGame, depth - 1, rootPlayer, alpha, beta)
        }

        val maximizing = game.currentPlayer == rootPlayer
        val candidateMoves = legalMoves
            .map { move ->
                val nextGame = game.copy()
                nextGame.takeTurn(move)
                move to calcMovePriority(game, nextGame, rootPlayer)
            }
            .let { moves ->
                if (maximizing) {
                    moves.sortedByDescending { it.second }
                } else {
                    moves.sortedBy { it.second }
                }
            }
            .take(maxMovesPerBranch)
            .map { it.first }

        if (maximizing) {
            var bestScore = Float.NEGATIVE_INFINITY
            var currentAlpha = alpha
            for (move in candidateMoves) {
                val nextGame = game.copy()
                if (!nextGame.takeTurn(move)) {
                    continue
                }
                bestScore = maxOf(
                    bestScore,
                    minimax(nextGame, depth - 1, rootPlayer, currentAlpha, beta)
                )
                currentAlpha = maxOf(currentAlpha, bestScore)
                if (currentAlpha >= beta) {
                    break
                }
            }
            return bestScore
        }

        var worstScore = Float.POSITIVE_INFINITY
        var currentBeta = beta
        for (move in candidateMoves) {
            val nextGame = game.copy()
            if (!nextGame.takeTurn(move)) {
                continue
            }
            worstScore = minOf(
                worstScore,
                minimax(nextGame, depth - 1, rootPlayer, alpha, currentBeta)
            )
            currentBeta = minOf(currentBeta, worstScore)
            if (alpha >= currentBeta) {
                break
            }
        }
        return worstScore
    }
}