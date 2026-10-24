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
import main.bot.ai.MinimaxBotCopy
import main.bot.ai.Utils

import java.util.Locale
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random

fun main(args: Array<String>) {
    logger.info("starting Main")

    when (args.firstOrNull()?.lowercase()) {
        "eval" -> {
            val games = args.getOrNull(1)?.toIntOrNull() ?: 100
            require(games > 0) { "The number of evaluation games must be positive" }
            EvalGame(games).runEval()
        }

        "env" -> testEnv()
        null, "selfplay" -> testRandomAgent()
        else -> logger.error("Unknown mode '${args.first()}'. Use: selfplay, eval [games] or env")
    }
}

fun testRandomAgent() {
    val game = Game()
    val randomAgent = RandomAgent()
    val miniMaxAgent = MiniMaxAgent()
    val miniMaxAgentCompare = MinimaxBotCopy() // White (2. player after cathedral)

    val enableRandomness = false

    var t: Optional<Placement> = Optional.empty()

    // Black is MiniMaxAgent, White is MiniMaxAgentCompare
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

class EvalGame(
    private val gamesPlayed: Int = 100,
    private val parallelGames: Boolean = true
) {
    enum class Outcome { WIN, LOSS, DRAW }

    data class GameResult(
        val gameNumber: Int,
        val candidateColor: Color,
        val blackScore: Int,
        val whiteScore: Int,
        val candidateScore: Int,
        val baselineScore: Int,
        val durationMillis: Long,
        val maxCandidateMoveMillis: Long,
        val maxBaselineMoveMillis: Long,
        val candidateTimeouts: Int,
        val baselineTimeouts: Int
    ) {
        // Lower score is better because score() counts unplaced buildings.
        val outcome: Outcome
            get() = when {
                candidateScore < baselineScore -> Outcome.WIN
                candidateScore > baselineScore -> Outcome.LOSS
                else -> Outcome.DRAW
            }
    }

    private fun candidateColorFor(gameNumber: Int): Color {
        // Alternate colors so the cathedral opening does not favour one agent.
        return if (gameNumber % 2 == 0) Color.Black else Color.White
    }

    /**
     * The game engine caches board forms and possible placements lazily in shared enum
     * instances. Fill those caches once before starting parallel games.
     */
    private fun prewarmEngineCaches() {
        for (building in Building.values()) {
            building.getAllPossiblePlacements()
            for (direction in building.turnable.possibleDirections) {
                building.turn(direction)
                building.corners(direction)
                building.silhouette(direction)
            }
        }
    }

    private fun playGame(gameNumber: Int, candidateColor: Color): GameResult {
        val logger = Utils.Logger(
            "eval $gameNumber",
            "${Utils.LOG_FILE_PATH_EVAL}_$gameNumber.log",
            Utils.LOG_TO_FILE,
            "INFO"
        )
        val game = Game()
        val candidateAgent = MiniMaxAgent()
        val baselineAgent = MinimaxBotCopy()

        logger.info(
            "Starting game $gameNumber: modified ${candidateAgent.name()} plays $candidateColor, " +
                "baseline ${baselineAgent.name()} plays the other color"
        )

        val startedAt = System.nanoTime()
        val turnLimitMillis = 10_000L
        var turnsPlayed = 0
        var maxCandidateMoveMillis = 0L
        var maxBaselineMoveMillis = 0L
        var candidateTimeouts = 0
        var baselineTimeouts = 0

        while (!game.isFinished()) {
            val actingAgent = if (game.currentPlayer == candidateColor) candidateAgent else baselineAgent
            val moveStartedAt = System.nanoTime()
            val turn: Optional<Placement> = actingAgent.calculateTurn(game, 10, 0)
            val moveMillis = (System.nanoTime() - moveStartedAt) / 1_000_000

            if (actingAgent === candidateAgent) {
                maxCandidateMoveMillis = maxOf(maxCandidateMoveMillis, moveMillis)
                if (moveMillis > turnLimitMillis) {
                    candidateTimeouts++
                    logger.error(
                        "TIMEOUT: candidate took ${moveMillis}ms for ${game.currentPlayer} " +
                            "(limit ${turnLimitMillis}ms)"
                    )
                }
            } else {
                maxBaselineMoveMillis = maxOf(maxBaselineMoveMillis, moveMillis)
                if (moveMillis > turnLimitMillis) {
                    baselineTimeouts++
                }
            }

            if (!turn.isPresent) {
                logger.info("No valid moves for ${game.currentPlayer}; passing turn")
                game.passTurn()
                continue
            }

            val placement = turn.get()
            logger.info(
                "${actingAgent.name()} places ${placement.building().name} " +
                    "at position ${placement.position()} for ${game.currentPlayer}"
            )

            if (!game.takeTurn(placement)) {
                logger.error("Selected placement was rejected by the engine: $placement")
                game.passTurn()
            }
            turnsPlayed++
        }

        val durationMillis = (System.nanoTime() - startedAt) / 1_000_000
        val score = game.score()
        val blackScore = score[Color.Black] ?: 0
        val whiteScore = score[Color.White] ?: 0
        val candidateScore = if (candidateColor == Color.Black) blackScore else whiteScore
        val baselineScore = if (candidateColor == Color.Black) whiteScore else blackScore

        val result = GameResult(
            gameNumber = gameNumber,
            candidateColor = candidateColor,
            blackScore = blackScore,
            whiteScore = whiteScore,
            candidateScore = candidateScore,
            baselineScore = baselineScore,
            durationMillis = durationMillis,
            maxCandidateMoveMillis = maxCandidateMoveMillis,
            maxBaselineMoveMillis = maxBaselineMoveMillis,
            candidateTimeouts = candidateTimeouts,
            baselineTimeouts = baselineTimeouts
        )

        logger.info(
            "Game $gameNumber finished after $turnsPlayed turns in ${durationMillis}ms: " +
                "Black=$blackScore, White=$whiteScore; " +
                "modified ${candidateAgent.name()} as $candidateColor -> ${result.outcome}; " +
                "maxCandidateMove=${maxCandidateMoveMillis}ms, candidateTimeouts=$candidateTimeouts"
        )
        return result
    }

    fun eval(): List<GameResult> {
        val startedAt = System.nanoTime()
        prewarmEngineCaches()

        val results = if (!parallelGames || gamesPlayed <= 1) {
            (1..gamesPlayed).map { gameNumber ->
                playGame(gameNumber, candidateColorFor(gameNumber))
            }
        } else {
            val threadCount = minOf(gamesPlayed, Runtime.getRuntime().availableProcessors())
            val executor = Executors.newFixedThreadPool(threadCount)
            try {
                val futures = (1..gamesPlayed).map { gameNumber ->
                    executor.submit(Callable {
                        playGame(gameNumber, candidateColorFor(gameNumber))
                    })
                }
                futures.map { it.get() }
            } finally {
                executor.shutdown()
            }
        }

        val totalSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        Utils.logger.info(
            "Completed $gamesPlayed evaluation games in " +
                String.format(Locale.ROOT, "%.1f", totalSeconds) + "s"
        )
        return results.sortedBy { it.gameNumber }
    }

    fun runEval() {
        val results = eval()

        Utils.logger.info("Result of Eval (modified MiniMaxAgent = candidate):")
        for (result in results) {
            Utils.logger.info(
                "game ${result.gameNumber}: modified agent as ${result.candidateColor} -> ${result.outcome} " +
                    "(Black=${result.blackScore}, White=${result.whiteScore}, " +
                    "candidate=${result.candidateScore}, baseline=${result.baselineScore}, " +
                    "time=${result.durationMillis}ms, " +
                    "maxCandidateMove=${result.maxCandidateMoveMillis}ms, " +
                    "candidateTimeouts=${result.candidateTimeouts})"
            )
        }

        val wins = results.count { it.outcome == Outcome.WIN }
        val losses = results.count { it.outcome == Outcome.LOSS }
        val draws = results.count { it.outcome == Outcome.DRAW }
        val averageCandidateScore = results.map { it.candidateScore }.average()
        val averageBaselineScore = results.map { it.baselineScore }.average()
        val winRate = if (results.isEmpty()) 0.0 else wins * 100.0 / results.size
        val maxCandidateMoveMillis = results.maxOfOrNull { it.maxCandidateMoveMillis } ?: 0L
        val maxBaselineMoveMillis = results.maxOfOrNull { it.maxBaselineMoveMillis } ?: 0L
        val candidateTimeouts = results.sumOf { it.candidateTimeouts }
        val baselineTimeouts = results.sumOf { it.baselineTimeouts }

        Utils.logger.info(
            "Summary over ${results.size} games for modified MiniMaxAgent: " +
                "wins=$wins, losses=$losses, draws=$draws, " +
                "winRate=" + String.format(Locale.ROOT, "%.1f", winRate) + "%, " +
                "avgCandidateScore=" + String.format(Locale.ROOT, "%.2f", averageCandidateScore) + ", " +
                "avgBaselineScore=" + String.format(Locale.ROOT, "%.2f", averageBaselineScore) + ", " +
                "maxCandidateMove=${maxCandidateMoveMillis}ms, " +
                "maxBaselineMove=${maxBaselineMoveMillis}ms, " +
                "candidateTimeouts=$candidateTimeouts, baselineTimeouts=$baselineTimeouts"
        )
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