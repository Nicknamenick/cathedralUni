package main.bot.ai

import de.fhkiel.ki.cathedral.ai.Agent
import de.fhkiel.ki.cathedral.game.Game
import de.fhkiel.ki.cathedral.game.Placement
import java.util.Optional

import main.bot.ai.Utils.Companion.logger

class RandomAgent : Agent {
    override fun name(): String {
        return "randomAgent"
    }

    override fun calculateTurn(
        game: Game,
        timeForTurn: Int,
        timeBonus: Int
    ): Optional<Placement> {
        val validMoves = mutableListOf<Placement>()
        Utils.getValidMoves(game, validMoves)

        if (validMoves.isNotEmpty()) {
            return Optional.of(validMoves.random())
        }
        logger.warn("No valid moves available")
        return Optional.empty<Placement>()
    }
}