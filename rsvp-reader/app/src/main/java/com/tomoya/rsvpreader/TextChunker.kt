package com.tomoya.rsvpreader

import android.icu.text.BreakIterator
import java.util.Locale

object TextChunker {
    private val hardPunctuation = setOf('。', '！', '？', '!', '?')
    private val softPunctuation = setOf('、', '，', ',', '；', ';', '：', ':')

    fun chunk(raw: String): List<String> {
        val text = raw
            .replace(Regex("[\\t\\r\\n ]+"), " ")
            .trim()
        if (text.isBlank()) return emptyList()

        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()

        fun flush() {
            val value = buffer.toString().trim()
            if (value.isNotEmpty()) chunks += value
            buffer.clear()
        }

        for (token in tokens) {
            if (token.isBlank()) continue

            if (isPunctuationOnly(token)) {
                if (buffer.isNotEmpty()) {
                    buffer.append(token)
                } else if (chunks.isNotEmpty()) {
                    chunks[chunks.lastIndex] = chunks.last() + token
                }

                if (token.any { it in hardPunctuation }) {
                    flush()
                } else if (token.any { it in softPunctuation } && buffer.length >= MIN_CHUNK) {
                    flush()
                }
                continue
            }

            if (buffer.isNotEmpty() && buffer.length >= TARGET_CHUNK && buffer.length + token.length > MAX_CHUNK) {
                flush()
            }

            // Never cut a token in the middle. A long proper noun/URL is kept intact.
            buffer.append(token)

            if (buffer.length >= MAX_CHUNK) {
                flush()
            }
        }

        flush()
        return mergeTinyChunks(chunks)
    }

    private fun tokenize(text: String): List<String> {
        val iterator = BreakIterator.getWordInstance(Locale.JAPANESE)
        iterator.setText(text)

        val result = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()

        while (end != BreakIterator.DONE) {
            val piece = text.substring(start, end)
            if (piece.isNotBlank()) result += piece.trim()
            start = end
            end = iterator.next()
        }
        return result
    }

    private fun isPunctuationOnly(value: String): Boolean =
        value.all { ch ->
            !ch.isLetterOrDigit() &&
                ch !in '\u3040'..'\u30ff' &&
                ch !in '\u3400'..'\u9fff'
        }

    private fun mergeTinyChunks(input: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (part in input) {
            if (
                part.length <= 2 &&
                result.isNotEmpty() &&
                result.last().length + part.length <= MAX_CHUNK
            ) {
                result[result.lastIndex] = result.last() + part
            } else {
                result += part
            }
        }
        return result
    }

    private const val MIN_CHUNK = 4
    private const val TARGET_CHUNK = 7
    private const val MAX_CHUNK = 12
}
