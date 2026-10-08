package main.bot

import de.fhkiel.ki.cathedral.ai.Agent
import de.fhkiel.ki.cathedral.game.Game
import de.fhkiel.ki.cathedral.game.Placement
import de.fhkiel.ki.cathedral.game.Building
import de.fhkiel.ki.cathedral.game.Direction
import de.fhkiel.ki.cathedral.game.Position

import main.bot.ai.RandomAgent
import main.bot.ai.MiniMaxAgent
import main.bot.ai.Utils.Companion.logger
import de.fhkiel.ki.cathedral.game.Color
import main.bot.ai.MiniMaxAgentCopy

import java.util.Optional
import kotlin.random.Random

fun main() {
    logger.info("starting Main")
    testRandomAgent()
}

fun testRandomAgent() {
    val game = Game()
    val randomAgent = RandomAgent()
    val miniMaxAgent = MiniMaxAgent()
    val miniMaxAgentCompare = MiniMaxAgentCopy() // White (2. player after cathedral)

    val enableRandomness = false

    var t: Optional<Placement> = Optional.empty()

    while (!game.isFinished()) {
        if (game.currentPlayer == Color.White) {
            if (Random.nextInt(1, 5) == 3 && enableRandomness) {
                logger.info("Taking a turn with random agent")
                t = randomAgent.calculateTurn(game, 10, 0)
            } else {
                logger.info("Taking a turn with: ${miniMaxAgentCompare.name()}")
                t = miniMaxAgentCompare.calculateTurn(game, 10, 0)
            }
        } else {
            logger.info("Taking a turn with: ${miniMaxAgent.name()}")
            t = miniMaxAgent.calculateTurn(game, 10, 0)
        }
        if (!t.isPresent) {
            logger.info("No valid moves for ${game.currentPlayer}; passing turn")
            game.passTurn()
            logger.logGame(game)
            logger.debug("----------------------------------------------------")
            continue
        }
        val placement = t.get()
        logger.info("Taking a turn with placement: ${placement.building().name} at position: ${placement.position()}")

        if (!game.takeTurn(placement)) {
            logger.error("Selected placement was rejected by the engine: $placement")
            game.passTurn()
        }
        logger.debug("\n")
        logger.logGame(game)
        logger.debug("----------------------------------------------------")
    }

    logger.info("Game finished with score: ${game.score()}")
}

fun testEnv() {
    val game: Game = Game()

    class TestAgent : Agent {
        override fun name(): String {
            return "testAgent"
        }

        override fun calculateTurn(
            game: Game?,
            timeForTurn: Int,
            timeBonus: Int
        ): Optional<Placement> {
            return Optional.empty<Placement>()
        }
    }

    val agent = TestAgent()
    val t: Optional<Placement> = agent.calculateTurn(game, 1, 0)

    for (b in game.placableBuildings) {
        println("playable building: " + b.name)
    }
    println("t: $t")
    println("board: ${game.board}")

    val place: Placement = Placement(
        Position(2, 1),
        Direction._90,
        Building.Blue_Cathedral
    )

    println("place: $place")
    println("Taking a turn with player: ${game.currentPlayer}")
    game.takeTurn(place)
    println("Board after turn: ${game.board}")
    println("Current player after turn: ${game.currentPlayer}")
    println("---------------------------------------------------")
    for (b in game.placableBuildings) {
        println("playable building: " + b.name)
    }
/*    Utils.getValidMoves(game, mutableListOf()).forEach { placement: Placement ->
        println("Valid move: $placement")
    }*/

}