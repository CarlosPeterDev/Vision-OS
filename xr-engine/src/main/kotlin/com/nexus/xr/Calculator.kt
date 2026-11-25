package com.nexus.xr

import kotlin.math.*

/** Recursive-descent evaluator for + - * / ^ % parentheses, constants and common functions. */
object Calculator {
    fun evaluate(expression: String): Double {
        val text = expression.replace('×', '*').replace('÷', '/').replace('−', '-').filterNot { it.isWhitespace() }
        require(text.isNotEmpty() && text.length <= 200) { "Invalid expression" }
        val parser = Parser(text)
        val value = parser.sum()
        require(parser.done && value.isFinite()) { "Invalid expression" }
        return value
    }

    private class Parser(private val s: String) {
        private var i = 0
        private var depth = 0
        val done get() = i == s.length
        private fun eat(c: Char) = if (i < s.length && s[i] == c) { i++; true } else false

        fun sum(): Double {
            require(++depth < 60) { "Expression is too deep" }
            var v = product()
            while (true) v = when { eat('+') -> v + product(); eat('-') -> v - product(); else -> break }
            depth--
            return v
        }

        private fun product(): Double {
            var v = signed()
            while (true) v = when { eat('*') -> v * signed(); eat('/') -> v / signed(); else -> break }
            return v
        }

        private fun signed(): Double {
            require(++depth < 60) { "Expression is too deep" }
            val v = when { eat('-') -> -signed(); eat('+') -> signed(); else -> power() }
            depth--
            return v
        }

        private fun power(): Double {
            var base = atom()
            while (eat('%')) base /= 100
            return if (eat('^')) base.pow(signed()) else base
        }

        private fun atom(): Double {
            if (eat('(')) return sum().also { require(eat(')')) { "Missing )" } }
            if (eat('π')) return PI
            val start = i
            if (i < s.length && s[i].isLetter()) {
                while (i < s.length && s[i] in 'a'..'z') i++
                val name = s.substring(start, i)
                if (name == "e") return E
                require(eat('(')) { "Unknown name" }
                val a = sum()
                require(eat(')')) { "Missing )" }
                return when (name) {
                    "sqrt" -> sqrt(a)
                    "sin" -> sin(Math.toRadians(a))
                    "cos" -> cos(Math.toRadians(a))
                    "tan" -> tan(Math.toRadians(a))
                    "ln" -> ln(a)
                    "log" -> log10(a)
                    else -> throw IllegalArgumentException("Unknown function")
                }
            }
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.')) i++
            return requireNotNull(s.substring(start, i).toDoubleOrNull()) { "Invalid number" }
        }
    }
}
