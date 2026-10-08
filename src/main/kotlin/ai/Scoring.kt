package main.bot.ai

import de.fhkiel.ki.cathedral.game.Color
import de.fhkiel.ki.cathedral.game.Game

class Scoring {
    companion object {
        fun calcScorePrimitive(game: Game, player: Color): Float {
            if (player == Color.Blue) {
                return 0f
            }

            val opponent = if (player == Color.Black) Color.White else Color.Black
            val remainingScore = game.score()

            val ownRemaining = (remainingScore[player] ?: 0).toFloat()
            val opponentRemaining = (remainingScore[opponent] ?: 0).toFloat()
            val remainingDelta = opponentRemaining - ownRemaining

            val board = game.board.getField()
            val ownColor = ownedColor(player)
            val opponentColor = ownedColor(opponent)
            var territoryControl = 0
            var buildingControl = 0
            var sideBuildingControl = 0f

            for (y in board.indices) {
                for (x in board[y].indices) {
                    val control = when (board[y][x]) {
                        ownColor -> 1
                        opponentColor -> -1
                        else -> 0
                    }

                    territoryControl += control
                }
            }

            for (placement in game.board.placedBuildings) {
                val placedBuildingControl = when (placement.building().getColor()) {
                    player -> 1
                    opponent -> -1
                    else -> 0
                }

                if (placedBuildingControl != 0) {
                    buildingControl += placedBuildingControl

                    for (position in placement.form()) {
                        val x = placement.x() + position.x()
                        val y = placement.y() + position.y()
                        sideBuildingControl += placedBuildingControl * sideWeight(x, y, game.board.placedBuildings.size)
                    }
                }
            }

            return (remainingDelta * 3.5f) +
                (territoryControl * 6f) +
                (buildingControl * 3f) +
                sideBuildingControl
        }

        fun calcMovePriority(before: Game, after: Game, player: Color): Float {
            val beforeOwned = countOwnedFields(before, player)
            val afterOwned = countOwnedFields(after, player)
            val territoryGain = afterOwned - beforeOwned

            return calcScorePrimitive(after, player) + territoryGain * 12f
        }

        private fun ownedColor(player: Color): Color {
            return if (player == Color.Black) Color.Black_Owned else Color.White_Owned
        }

        private fun sideWeight(x: Int, y: Int, placedBuildingCount: Int): Float {
            /**
                Used to calculate the weight of a building's position based on its distance to the edge
                Changes the bots behavior to prefer placing buildings closer to the edge of the board
                / makes it more aggressive in the early game and more defensive in the late game
            */

            if (x !in 0..9 || y !in 0..9) {
                return 0f
            }

            val distanceToSide = minOf(x, y, 9 - x, 9 - y)
            val edgeWeight = when (distanceToSide) {
                0 -> 2f
                1 -> 2.5f
                2 -> 3f
                else -> 0f
            } // TODO ("change (4, 2.5, 1)")

            val cornerBonus = if ((x == 0 || x == 9) && (y == 0 || y == 9)) 1.5f else 0f // TODO("change (9, 1.5)")
            val earlyGameFactor = (1.5f - placedBuildingCount / 30f).coerceIn(0.5f, 1.5f)
            return (edgeWeight + cornerBonus) * earlyGameFactor
        }

        private fun countOwnedFields(game: Game, player: Color): Int {
            val owned = ownedColor(player)
            return game.board.getField().sumOf { row ->
                row.count { field -> field == owned }
            }
        }

        fun calcScorePrimitive2(game: Game, player: Color): Float {
            if (player == Color.Blue) {
                return 0f
            }

            val opponent = if (player == Color.Black) Color.White else Color.Black
            val remainingScore = game.score()

            val ownRemaining = (remainingScore[player] ?: 0).toFloat()
            val opponentRemaining = (remainingScore[opponent] ?: 0).toFloat()
            val remainingDelta = opponentRemaining - ownRemaining

            val board = game.board.getField()
            var centerControl = 0

            return (remainingDelta * 20f) + (centerControl * 1.5f)
        }
    }
}