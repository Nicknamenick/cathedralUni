package main.bot.ai

import de.fhkiel.ki.cathedral.ai.Agent
import de.fhkiel.ki.cathedral.game.Color
import de.fhkiel.ki.cathedral.game.Game
import de.fhkiel.ki.cathedral.game.Placement
import java.util.Optional

import main.bot.ai.Scoring.Companion.calcScorePrimitive2
import main.bot.ai.Utils.Companion.getValidMoves
import main.bot.ai.Utils.Companion.logger

class MiniMaxAgentCopy : Agent {
    private val maxDepth = 3
    private val maxMovesPerBranch = 20

    override fun name(): String? {
        return "MiniMaxAgentCopy"
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
                nextGame.takeTurn(move, true)
                move to calcScorePrimitive2(nextGame, rootPlayer)
            }
            .sortedByDescending { it.second }
            .take(maxMovesPerBranch)
            .map { it.first }

        var bestMove = prunedMoves.first()
        var bestScore = Float.NEGATIVE_INFINITY

        for (move in prunedMoves) {
            val nextGame = rootGame.copy()
            if (!nextGame.takeTurn(move, true)) {
                continue
            }

            val score = minimax(nextGame, maxDepth - 1, rootPlayer)
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

    private fun minimax(game: Game, depth: Int, rootPlayer: Color): Float {
        if (depth <= 0 || game.isFinished()) {
            return calcScorePrimitive2(game, rootPlayer)
        }

        val legalMoves = mutableListOf<Placement>()
        getValidMoves(game, legalMoves)

        if (legalMoves.isEmpty()) {
            return calcScorePrimitive2(game, rootPlayer)
        }

        val candidateMoves = legalMoves
            .map { move ->
                val nextGame = game.copy()
                nextGame.takeTurn(move, true)
                move to calcScorePrimitive2(nextGame, rootPlayer)
            }
            .sortedByDescending { it.second }
            .take(maxMovesPerBranch)
            .map { it.first }

        if (game.currentPlayer == rootPlayer) {
            var bestScore = Float.NEGATIVE_INFINITY
            for (move in candidateMoves) {
                val nextGame = game.copy()
                if (!nextGame.takeTurn(move, true)) {
                    continue
                }
                bestScore = maxOf(bestScore, minimax(nextGame, depth - 1, rootPlayer))
            }
            return bestScore
        }

        var worstScore = Float.POSITIVE_INFINITY
        for (move in candidateMoves) {
            val nextGame = game.copy()
            if (!nextGame.takeTurn(move, true)) {
                continue
            }
            worstScore = minOf(worstScore, minimax(nextGame, depth - 1, rootPlayer))
        }
        return worstScore
    }
}