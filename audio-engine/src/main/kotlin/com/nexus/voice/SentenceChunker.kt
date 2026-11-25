package com.nexus.voice

/** Groups streamed model text into speakable sentences (English and Arabic punctuation). */
class SentenceChunker(private val maxChars: Int = 240, private val minChars: Int = 12) {
    private val buffer = StringBuilder()
    private val enders = setOf('.', '!', '?', '؟', '…', '۔', '\n')
    private val softBreaks = setOf(',', '،', ';', '؛', ':', ' ')

    fun push(delta: String): List<String> {
        buffer.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val cut = nextCut() ?: break
            val piece = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (piece.isNotEmpty()) out += piece
        }
        return out
    }

    fun flush(): String? = buffer.toString().trim().also { buffer.clear() }.ifEmpty { null }

    private fun nextCut(): Int? {
        for (i in buffer.indices) {
            if (i >= maxChars) break
            // Requires following whitespace, so "3.14" is not split.
            if (buffer[i] in enders && i + 1 >= minChars &&
                (buffer[i] == '\n' || (i + 1 < buffer.length && buffer[i + 1].isWhitespace()))
            ) return i + 1
        }
        if (buffer.length <= maxChars) return null
        for (i in maxChars - 1 downTo minChars) if (buffer[i] in softBreaks) return i + 1
        return if (buffer[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
    }
}
