package com.nexus.xr

import kotlin.random.Random

/** Pure logic behind the small built-in apps. */
object Units {
    /** [factor] turns a value into the category's base unit. */
    class Unit(val symbol: String, val factor: Double)

    val categories: Map<String, List<Unit>> = linkedMapOf(
        "length" to listOf(Unit("m", 1.0), Unit("km", 1000.0), Unit("cm", 0.01), Unit("mm", 0.001), Unit("in", 0.0254), Unit("ft", 0.3048), Unit("mi", 1609.344)),
        "weight" to listOf(Unit("kg", 1.0), Unit("g", 0.001), Unit("t", 1000.0), Unit("lb", 0.45359237), Unit("oz", 0.028349523125)),
        "data" to listOf(Unit("MB", 1.0), Unit("KB", 1.0 / 1024), Unit("GB", 1024.0), Unit("TB", 1024.0 * 1024)),
        "speed" to listOf(Unit("km/h", 1.0), Unit("m/s", 3.6), Unit("mph", 1.609344), Unit("knot", 1.852)),
        "area" to listOf(Unit("m²", 1.0), Unit("km²", 1e6), Unit("feddan", 4200.833), Unit("ha", 10000.0), Unit("ft²", 0.09290304)),
        "temperature" to listOf(Unit("°C", 1.0), Unit("°F", 1.0), Unit("K", 1.0))
    )

    fun convert(value: Double, from: Unit, to: Unit): Double {
        if (from.symbol !in listOf("°C", "°F", "K")) return value * from.factor / to.factor
        val celsius = when (from.symbol) { "°F" -> (value - 32) * 5 / 9; "K" -> value - 273.15; else -> value }
        return when (to.symbol) { "°F" -> celsius * 9 / 5 + 32; "K" -> celsius + 273.15; else -> celsius }
    }
}

enum class Slide { LEFT, RIGHT, UP, DOWN }

class Game2048(private val random: Random = Random.Default) {
    val cells = IntArray(16)
    var score = 0
        private set

    init { reset() }

    fun reset() { cells.fill(0); score = 0; spawn(); spawn() }

    private fun spawn() {
        val free = cells.indices.filter { cells[it] == 0 }
        if (free.isNotEmpty()) cells[free.random(random)] = if (random.nextInt(10) == 0) 4 else 2
    }

    /** Indices of one row or column, starting at the side tiles move towards. */
    private fun line(slide: Slide, i: Int) = IntArray(4) { j ->
        when (slide) { Slide.LEFT -> i * 4 + j; Slide.RIGHT -> i * 4 + 3 - j; Slide.UP -> j * 4 + i; Slide.DOWN -> (3 - j) * 4 + i }
    }

    fun move(slide: Slide): Boolean {
        var changed = false
        for (i in 0..3) {
            val at = line(slide, i)
            val values = at.map { cells[it] }.filter { it != 0 }
            val merged = mutableListOf<Int>()
            var j = 0
            while (j < values.size) {
                if (j + 1 < values.size && values[j] == values[j + 1]) {
                    merged += values[j] * 2
                    score += values[j] * 2
                    j += 2
                } else merged += values[j++]
            }
            for (k in 0..3) {
                val next = merged.getOrElse(k) { 0 }
                if (cells[at[k]] != next) { cells[at[k]] = next; changed = true }
            }
        }
        if (changed) spawn()
        return changed
    }

    fun over() = cells.none { it == 0 } && (0..15).none {
        (it % 4 < 3 && cells[it] == cells[it + 1]) || (it < 12 && cells[it] == cells[it + 4])
    }

    /** Replaces the board, for tests. */
    fun load(values: IntArray) { values.copyInto(cells) }
}

object TicTacToe {
    private val lines = listOf(
        intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8), intArrayOf(0, 3, 6),
        intArrayOf(1, 4, 7), intArrayOf(2, 5, 8), intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
    )

    /** 'X' or 'O', or null while nobody has three in a row. Empty cells are spaces. */
    fun winner(board: CharArray): Char? =
        lines.firstOrNull { board[it[0]] != ' ' && board[it[0]] == board[it[1]] && board[it[1]] == board[it[2]] }?.let { board[it[0]] }

    /** A best move for [player], or -1 when the game is finished. */
    fun bestMove(board: CharArray, player: Char): Int {
        if (winner(board) != null) return -1
        var best = -1
        var bestScore = Int.MIN_VALUE
        for (i in board.indices) {
            if (board[i] != ' ') continue
            board[i] = player
            val score = -score(board, other(player))
            board[i] = ' '
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best
    }

    private fun other(player: Char) = if (player == 'X') 'O' else 'X'

    private fun score(board: CharArray, player: Char): Int {
        if (winner(board) != null) return -1 - board.count { it == ' ' }
        if (board.none { it == ' ' }) return 0
        var best = Int.MIN_VALUE
        for (i in board.indices) {
            if (board[i] != ' ') continue
            board[i] = player
            best = maxOf(best, -score(board, other(player)))
            board[i] = ' '
        }
        return best
    }
}

class Task(val text: String, val done: Boolean = false)

object TaskCodec {
    fun encode(tasks: List<Task>) = tasks.joinToString("\n") { (if (it.done) "x\t" else "o\t") + it.text.replace('\n', ' ').replace('\t', ' ') }
    fun decode(text: String) = text.lines().mapNotNull { line ->
        val parts = line.split('\t', limit = 2)
        if (parts.size == 2 && parts[1].isNotBlank()) Task(parts[1], parts[0] == "x") else null
    }
}
