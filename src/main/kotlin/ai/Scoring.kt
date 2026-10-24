package main.bot.ai

import de.fhkiel.ki.cathedral.game.Color
import de.fhkiel.ki.cathedral.game.Game

class Multiplier {
    companion object {
        const val TERRITORY = 1.0f
        const val BUILDING = 1.0f
        const val SIDE_BUILDING = 1.0f

        const val DELTA = 1.0f
        const val teritory = 1.0f
        const val BUILDING_CONTROL = 1.0f
        const val SIDE_BUILDING_CONTROL = 1.0f
    }
}

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

            val connectivityAndEnclosure = connectedEnclosureScore(game, player)
            val twoStoneEnclosure = twoStoneEnclosureScore(game, player)
            val strategicEnclosure = strategicEnclosureScore(game, player)
            val cornerExpansion = cornerExpansionScore(game, player)

            // TODO ("change to more fitting values")
            return (remainingDelta * 7.5f) +
                (territoryControl * 2f) +
                (buildingControl * 1.5f) +
                sideBuildingControl * 1.5f +
                connectivityAndEnclosure * 1.5f +
                twoStoneEnclosure * 3f +
                strategicEnclosure * 1.0f +
                cornerExpansion * 2f
        }

        fun calcMovePriority(before: Game, after: Game, player: Color): Float {
            val beforeOwned = countOwnedFields(before, player)
            val afterOwned = countOwnedFields(after, player)
            val territoryGain = afterOwned - beforeOwned

            val chainBonus = connectionToExistingClusterBonus(before, after, player)
            return calcScorePrimitive(after, player) + territoryGain * 12f + chainBonus
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
                0 -> 3.5f
                1 -> 2.5f
                2 -> 1f
                else -> 0f
            } // TODO ("change (4, 2.5, 1)")

            val earlyGameFactor = (1.5f - placedBuildingCount / 30f).coerceIn(0.5f, 1.5f)
            return edgeWeight * earlyGameFactor
        }

        private fun countOwnedFields(game: Game, player: Color): Int {
            val owned = ownedColor(player)
            return game.board.getField().sumOf { row ->
                row.count { field -> field == owned }
            }
        }

        private fun cornerExpansionScore(game: Game, player: Color): Float {
            val board = game.board.getField()
            if (board.isEmpty() || board[0].isEmpty()) {
                return 0f
            }

            val height = board.size
            val width = board[0].size
            var totalScore = 0f

            fun isOwn(x: Int, y: Int): Boolean {
                return board[y][x] == player || board[y][x] == ownedColor(player)
            }

            fun evaluateEdgePath(
                cornerX: Int,
                cornerY: Int,
                edgeDx: Int,
                edgeDy: Int,
                inwardDx: Int,
                inwardDy: Int
            ): Float {
                var pathScore = 0f
                var blocked = false

                for (step in 1..3) {
                    val x = cornerX + edgeDx * step
                    val y = cornerY + edgeDy * step
                    if (x !in 0 until width || y !in 0 until height) {
                        blocked = true
                        break
                    }

                    val cell = board[y][x]
                    when {
                        cell == Color.None -> pathScore += step * 4f
                        cell == player || cell == ownedColor(player) -> pathScore += step * 6f
                        else -> {
                            blocked = true
                            break
                        }
                    }

                    val inwardX = x + inwardDx
                    val inwardY = y + inwardDy
                    if (inwardX in 0 until width && inwardY in 0 until height) {
                        when (board[inwardY][inwardX]) {
                            Color.None -> pathScore += 2f
                            player, ownedColor(player) -> pathScore += 3f
                            else -> Unit
                        }
                    }
                }

                return if (blocked) pathScore * 0.35f else pathScore + 6f
            }

            val corners = listOf(
                0 to 0,
                (width - 1) to 0,
                0 to (height - 1),
                (width - 1) to (height - 1)
            )

            for ((cornerX, cornerY) in corners) {
                val nearbyOwn = (0 until height).flatMap { y ->
                    (0 until width).map { x -> x to y }
                }.count { (x, y) ->
                    val distance = kotlin.math.abs(x - cornerX) + kotlin.math.abs(y - cornerY)
                    distance <= 4 && isOwn(x, y)
                }
                if (nearbyOwn == 0) {
                    continue
                }

                val horizontal = evaluateEdgePath(
                    cornerX = cornerX,
                    cornerY = cornerY,
                    edgeDx = if (cornerX == 0) 1 else -1,
                    edgeDy = 0,
                    inwardDx = 0,
                    inwardDy = if (cornerY == 0) 1 else -1
                )
                val vertical = evaluateEdgePath(
                    cornerX = cornerX,
                    cornerY = cornerY,
                    edgeDx = 0,
                    edgeDy = if (cornerY == 0) 1 else -1,
                    inwardDx = if (cornerX == 0) 1 else -1,
                    inwardDy = 0
                )

                val bestDirection = maxOf(horizontal, vertical)
                val secondDirection = minOf(horizontal, vertical)
                val anchorBonus = nearbyOwn.coerceAtMost(3) * 3f

                totalScore += bestDirection + secondDirection * 0.35f + anchorBonus

                if (bestDirection > 20f && secondDirection > 10f) {
                    // Corner can realistically be enclosed by multiple stones.
                    totalScore += 15f
                }
            }

            return totalScore
        }

        private fun connectionToExistingClusterBonus(before: Game, after: Game, player: Color): Float {
            val move = after.turns.last().action ?: return 0f
            val beforeBoard = before.board.getField()
            val width = beforeBoard[0].size
            val height = beforeBoard.size
            val ownTerritory = ownedColor(player)
            val directions = arrayOf(
                intArrayOf(1, 0),
                intArrayOf(-1, 0),
                intArrayOf(0, 1),
                intArrayOf(0, -1)
            )

            var touchingEdges = 0
            for (part in move.form()) {
                val x = move.x() + part.x()
                val y = move.y() + part.y()
                for (direction in directions) {
                    val nx = x + direction[0]
                    val ny = y + direction[1]
                    if (nx !in 0 until width || ny !in 0 until height) {
                        continue
                    }
                    if (beforeBoard[ny][nx] == player || beforeBoard[ny][nx] == ownTerritory) {
                        touchingEdges++
                    }
                }
            }

            if (touchingEdges > 0) {
                return touchingEdges * 5f
            }

            // Avoid isolated second move when we already have stones on board.
            val ownCellsBefore = beforeBoard.sumOf { row ->
                row.count { it == player || it == ownTerritory }
            }
            return if (ownCellsBefore > 0) -18f else 0f
        }

        private fun connectedEnclosureScore(game: Game, player: Color): Float {
            val board = game.board.getField()
            val height = board.size
            if (height == 0) {
                return 0f
            }

            val width = board[0].size
            val ownBuilding = player
            val ownTerritory = ownedColor(player)
            val opponent = if (player == Color.Black) Color.White else Color.Black
            val opponentTerritory = ownedColor(opponent)
            var connectedScore = 0f

            fun isOwnCell(x: Int, y: Int): Boolean {
                return board[y][x] == ownBuilding || board[y][x] == ownTerritory
            }

            fun isOpponentCell(x: Int, y: Int): Boolean {
                return board[y][x] == opponent || board[y][x] == opponentTerritory
            }

            // Reward orthogonally connected own building/territory cells.
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (!isOwnCell(x, y)) {
                        continue
                    }

                    if (x + 1 < width && isOwnCell(x + 1, y)) {
                        connectedScore += 1.5f
                    }
                    if (y + 1 < height && isOwnCell(x, y + 1)) {
                        connectedScore += 1.5f
                    }
                }
            }

            // Reward closed empty regions whose boundary is mostly ours.
            val visited = Array(height) { BooleanArray(width) }
            val directions = arrayOf(
                intArrayOf(1, 0),
                intArrayOf(-1, 0),
                intArrayOf(0, 1),
                intArrayOf(0, -1)
            )

            for (startY in 0 until height) {
                for (startX in 0 until width) {
                    if (visited[startY][startX] || board[startY][startX] != Color.None) {
                        continue
                    }

                    val region = ArrayDeque<Pair<Int, Int>>()
                    region.addLast(startX to startY)
                    visited[startY][startX] = true
                    var regionSize = 0
                    var touchesBoardEdge = false
                    var ownBoundary = 0
                    var opponentBoundary = 0

                    while (region.isNotEmpty()) {
                        val (x, y) = region.removeFirst()
                        regionSize++
                        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                            touchesBoardEdge = true
                        }

                        for (direction in directions) {
                            val nextX = x + direction[0]
                            val nextY = y + direction[1]
                            if (nextX !in 0 until width || nextY !in 0 until height) {
                                continue
                            }

                            when {
                                board[nextY][nextX] == Color.None &&
                                    !visited[nextY][nextX] -> {
                                    visited[nextY][nextX] = true
                                    region.addLast(nextX to nextY)
                                }
                                isOwnCell(nextX, nextY) -> ownBoundary++
                                isOpponentCell(nextX, nextY) -> opponentBoundary++
                            }
                        }
                    }

                    if (!touchesBoardEdge && ownBoundary > opponentBoundary) {
                        connectedScore += regionSize * 2f
                        connectedScore += (ownBoundary - opponentBoundary) * 0.75f
                    }
                }
            }

            return connectedScore
        }

        private fun twoStoneEnclosureScore(game: Game, player: Color): Float {
            val board = game.board.getField()
            if (board.isEmpty() || board[0].isEmpty()) {
                return 0f
            }

            val height = board.size
            val width = board[0].size
            val ownBuildingColor = player
            val ownTerritory = ownedColor(player)
            val visited = Array(height) { BooleanArray(width) }
            val directions = arrayOf(
                intArrayOf(1, 0),
                intArrayOf(-1, 0),
                intArrayOf(0, 1),
                intArrayOf(0, -1)
            )
            var score = 0f

            for (startY in 0 until height) {
                for (startX in 0 until width) {
                    if (visited[startY][startX] || board[startY][startX] != ownTerritory) {
                        continue
                    }

                    val region = ArrayDeque<Pair<Int, Int>>()
                    val regionCells = HashSet<Pair<Int, Int>>()
                    region.addLast(startX to startY)
                    visited[startY][startX] = true
                    var touchesEdge = false

                    while (region.isNotEmpty()) {
                        val (x, y) = region.removeFirst()
                        regionCells.add(x to y)
                        touchesEdge = touchesEdge ||
                            x == 0 || y == 0 || x == width - 1 || y == height - 1

                        for (direction in directions) {
                            val nextX = x + direction[0]
                            val nextY = y + direction[1]
                            if (nextX !in 0 until width || nextY !in 0 until height) {
                                continue
                            }

                            when {
                                board[nextY][nextX] == ownTerritory &&
                                    !visited[nextY][nextX] -> {
                                    visited[nextY][nextX] = true
                                    region.addLast(nextX to nextY)
                                }
                            }
                        }
                    }

                    if (touchesEdge) {
                        continue
                    }

                    val borderingBuildings = game.board.placedBuildings
                        .withIndex()
                        .filter { (_, placement) ->
                            placement.building().getColor() == ownBuildingColor &&
                                placement.form().any { part ->
                                    val x = placement.x() + part.x()
                                    val y = placement.y() + part.y()
                                    directions.any { direction ->
                                        (x + direction[0] to y + direction[1]) in regionCells
                                    }
                                }
                        }
                        .map { it.index }
                        .toSet()

                    if (borderingBuildings.size >= 2) {
                        val extraStoneCount = borderingBuildings.size - 1
                        score += regionCells.size * (4f + extraStoneCount)
                    }
                }
            }

            return score
        }

        /**
         * Rewards small empty pockets that are fully enclosed by this player and/or the
         * board edge. This is intentionally conservative: only tactical pockets that are
         * about to become captured territory score, not large open areas.
         */
        private fun strategicEnclosureScore(game: Game, player: Color): Float {
            val board = game.board.getField()
            if (board.isEmpty() || board[0].isEmpty()) {
                return 0f
            }

            val height = board.size
            val width = board[0].size
            val opponent = if (player == Color.Black) Color.White else Color.Black
            val ownTerritory = ownedColor(player)
            val opponentTerritory = ownedColor(opponent)
            val visited = Array(height) { BooleanArray(width) }
            val directions = arrayOf(
                intArrayOf(1, 0),
                intArrayOf(-1, 0),
                intArrayOf(0, 1),
                intArrayOf(0, -1)
            )

            var totalScore = 0f

            for (startY in 0 until height) {
                for (startX in 0 until width) {
                    if (visited[startY][startX] || board[startY][startX] != Color.None) {
                        continue
                    }

                    val region = ArrayDeque<Pair<Int, Int>>()
                    val ownBoundary = HashSet<Pair<Int, Int>>()
                    val opponentBoundary = HashSet<Pair<Int, Int>>()
                    var regionSize = 0
                    var edgeContacts = 0

                    region.addLast(startX to startY)
                    visited[startY][startX] = true

                    while (region.isNotEmpty()) {
                        val (x, y) = region.removeFirst()
                        regionSize++

                        for (direction in directions) {
                            val nextX = x + direction[0]
                            val nextY = y + direction[1]

                            if (nextX !in 0 until width || nextY !in 0 until height) {
                                edgeContacts++
                                continue
                            }

                            val cell = board[nextY][nextX]
                            when {
                                cell == Color.None && !visited[nextY][nextX] -> {
                                    visited[nextY][nextX] = true
                                    region.addLast(nextX to nextY)
                                }
                                cell == player || cell == ownTerritory -> {
                                    ownBoundary.add(nextX to nextY)
                                }
                                cell == opponent || cell == opponentTerritory -> {
                                    opponentBoundary.add(nextX to nextY)
                                }
                            }
                        }
                    }

                    // Only small, fully bounded pockets are tactical enclosures.
                    if (regionSize > 8 || opponentBoundary.isNotEmpty()) {
                        continue
                    }

                    val own = ownBoundary.size
                    if (own < 2) {
                        continue
                    }

                    val boundarySize = own + edgeContacts
                    if (boundarySize == 0) {
                        continue
                    }

                    val ownShare = (own + edgeContacts * 0.75f) / boundarySize
                    if (ownShare < 0.8f) {
                        continue
                    }

                    totalScore += regionSize * 2f + 4f
                }
            }

            return totalScore.coerceAtMost(30f)
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