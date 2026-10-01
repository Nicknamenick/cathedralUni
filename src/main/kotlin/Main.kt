package main.bot

import de.fhkiel.ki.cathedral.ai.Agent
import de.fhkiel.ki.cathedral.game.Game
import de.fhkiel.ki.cathedral.game.Placement
import de.fhkiel.ki.cathedral.game.Building
import de.fhkiel.ki.cathedral.game.Direction
import de.fhkiel.ki.cathedral.game.Position

import main.bot.ai.RandomAgent
import main.bot.ai.Utils.Companion.logger

import java.util.Optional

fun main() {
    logger.info("starting Main")
    testRandomAgent()
}

fun testRandomAgent() {
    val game = Game()
    val agent = RandomAgent()

    while (!game.isFinished()) {
        val t: Optional<Placement> = agent.calculateTurn(game, 1, 0)
        if (!t.isPresent) {
            logger.warn("No valid moves available")
            return
        }
        val placement = t.get()
        logger.info("Taking a turn with placement: ${placement.building().name} at position: ${placement.position()}")

        game.takeTurn(placement)
        logger.debug("\n")
        logger.logGame(game)
        logger.debug("----------------------------------------------------")
    }
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