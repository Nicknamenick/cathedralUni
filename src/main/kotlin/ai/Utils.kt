package main.bot.ai

import de.fhkiel.ki.cathedral.game.Placement
import de.fhkiel.ki.cathedral.game.Game
import de.fhkiel.ki.cathedral.game.Color

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant


class Utils {
    class Logger(
        private val name: String,
        private val logFile: String,
        private val logToFile: Boolean,
        private val logLevel: String
    ) {

        private val logLevels = mapOf(
            "DEBUG" to 1,
            "INFO" to 2,
            "WARN" to 3,
            "ERROR" to 4
        )
        private val currentLogLevel = logLevels[logLevel.uppercase()]
            ?: throw IllegalArgumentException("Unknown log level: $logLevel")
        private val filePath: Path? = if (logToFile) Path.of(logFile) else null
        private val lock = Any()

        init {
            if (logToFile) {
                require(logFile.isNotBlank()) { "logFile must not be blank when file logging is enabled" }
                filePath!!.parent?.let { Files.createDirectories(it) }
            }
        }

        fun debug(message: String) {
            write("DEBUG", message)
        }

        fun info(message: String) {
            write("INFO", message)
        }

        fun warn(message: String) {
            write("WARN", message)
        }

        fun error(message: String) {
            write("ERROR", message)
        }

        fun log(message: String) {
            info(message)
        }

        fun logGame(game: Game) {
            write("DEBUG", "Board state:\n${game.board}\n")
            write("DEBUG", "Current player: ${game.currentPlayer}\n")
            val lastAction = game.turns.last().action
            write("DEBUG", "Last move: ${lastAction?.position() ?: "pass"}\n")
            write("DEBUG", "score: White: ${game.score()[Color.White]}, Black: ${game.score()[Color.Black]}\n")
            write("DEBUG", "game finished: ${game.isFinished()}")
        }

        private fun write(level: String, message: String) {
            if (!ENABLE_LOGGER || logLevels.getValue(level) < currentLogLevel) {
                return
            }

            val line = "[$name][$level][${Instant.now()}] $message"
            synchronized(lock) {
                println(line)
                filePath?.let { path ->
                    Files.writeString(
                        path,
                        "$line${System.lineSeparator()}",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                    )
                }
            }
        }
    }

    companion object {
        const val BOARD_WIDTH: Int = 10
        const val BOARD_HEIGHT: Int = 10

        var ENABLE_LOGGER: Boolean = true

        fun getValidMoves(game: Game, validMoves: MutableList<Placement>) {
            for (building in game.placableBuildings) {
                validMoves += building.getPossiblePlacements(game.board)
            }
        }

        const val LOG_FILE_PATH: String = "logs/debug.log"
        const val LOG_FILE_PATH_EVAL: String = "logs/eval"
        const val LOG_LEVEL: String = "DEBUG"
        const val LOG_TO_FILE: Boolean = true

        val logger: Logger = Logger("main(db)", LOG_FILE_PATH, LOG_TO_FILE, LOG_LEVEL)
    }
}