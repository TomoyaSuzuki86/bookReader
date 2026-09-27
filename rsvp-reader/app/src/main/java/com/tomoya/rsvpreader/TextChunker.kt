package com.tomoya.rsvpreader

import android.icu.text.BreakIterator
import java.util.Locale

object TextChunker {
    private val hardPunctuation = setOf('。', '！', '？', '!', '?')
    private val softPunctuation = setOf('、', '，', ',', '；', ';', '：', ':')

    fun normalizeSource(raw: String): String {
        val collapsed = raw
            .replace(Regex("[\\t\\r\\n ]+"), " ")
            .trim()
        if (collapsed.isBlank()) return ""

        val out = StringBuilder()
        for (i in collapsed.indices) {
            val ch = collapsed[i]
            if (ch != ' ') {
                out.append(ch)
                continue
            }

            val previous = out.lastOrNull()
            val next = collapsed.getOrNull(i + 1)
            if (previous == null || next == null) continue

            // Kindle/OCR often exposes a visual line break as whitespace.
            // For Japanese prose that whitespace is layout, not a word boundary.
            if (shouldDropLayoutSpace(previous, next)) continue
            if (out.lastOrNull() != ' ') out.append(' ')
        }
        return out.toString().trim()
    }

    fun stitchFragments(parts: List<String>): String {
        val out = StringBuilder()
        for (part in parts) {
            val value = normalizeSource(part)
            if (value.isBlank()) continue
            if (out.isEmpty()) {
                out.append(value)
                continue
            }

            val previous = out.last()
            val next = value.first()
            if (!shouldDropLayoutSpace(previous, next) && needsLatinSpace(previous, next)) {
                out.append(' ')
            }
            out.append(value)
        }
        return normalizeSource(out.toString())
    }

    fun chunk(raw: String): List<String> {
        val text = normalizeSource(raw)
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

            if (
                buffer.isNotEmpty() &&
                buffer.length >= TARGET_CHUNK &&
                buffer.length + token.length > MAX_CHUNK
            ) {
                flush()
            }

            // BreakIterator decides word boundaries. Never cut a token by character count.
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

    private fun shouldDropLayoutSpace(left: Char, right: Char): Boolean {
        if (isJapanese(left) && isJapanese(right)) return true
        if (isJapanese(left) && isClosingPunctuation(right)) return true
        if (isOpeningPunctuation(left) && isJapanese(right)) return true
        return false
    }

    private fun needsLatinSpace(left: Char, right: Char): Boolean =
        (left.isLetterOrDigit() && right.isLetterOrDigit()) &&
            !isJapanese(left) &&
            !isJapanese(right)

    private fun isJapanese(ch: Char): Boolean =
        ch in '\u3040'..'\u30ff' ||
            ch in '\u3400'..'\u9fff' ||
            ch in '\uf900'..'\ufaff'

    private fun isOpeningPunctuation(ch: Char): Boolean =
        ch in setOf('「', '『', '（', '(', '【', '〈', '《', '［', '[')

    private fun isClosingPunctuation(ch: Char): Boolean =
        ch in setOf('。', '、', '！', '？', '」', '』', '）', ')', '】', '〉', '》', '］', ']', ',', '.', '!', '?', ':', ';')

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
