package com.tomoya.rsvpreader

object TextChunker {
    private val hardBreaks = setOf('。', '！', '？', '!', '?')
    private val softBreaks = setOf('、', '，', ',', '；', ';', '：', ':')
    private val preferredBreaks = setOf('は', 'が', 'を', 'に', 'へ', 'で', 'と', 'も', 'の', 'ね', 'よ')

    fun chunk(raw: String): List<String> {
        val text = raw
            .replace(Regex("[\\t\\r\\n ]+"), "")
            .trim()
        if (text.isBlank()) return emptyList()

        val output = mutableListOf<String>()
        val buffer = StringBuilder()

        fun flush() {
            val s = buffer.toString().trim()
            if (s.isNotEmpty()) output += s
            buffer.clear()
        }

        for (ch in text) {
            buffer.append(ch)

            if (ch in hardBreaks) {
                flush()
                continue
            }
            if (ch in softBreaks && buffer.length >= 4) {
                flush()
                continue
            }

            if (buffer.length >= 7 && ch in preferredBreaks) {
                flush()
                continue
            }

            if (buffer.length >= 11) {
                flush()
            }
        }
        flush()

        val merged = mutableListOf<String>()
        output.forEach { part ->
            if (part.length <= 2 && merged.isNotEmpty() && merged.last().length + part.length <= 11) {
                merged[merged.lastIndex] = merged.last() + part
            } else {
                merged += part
            }
        }
        return merged
    }
}
