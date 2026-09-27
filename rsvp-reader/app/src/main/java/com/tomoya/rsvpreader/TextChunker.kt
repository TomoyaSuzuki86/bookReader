package com.tomoya.rsvpreader

import android.icu.text.BreakIterator
import java.util.Locale

object TextChunker {

    private val particles = setOf(
        "は", "が", "を", "に", "へ", "で", "と", "も", "の",
        "や", "か", "ね", "よ", "ぞ", "さ", "な", "から", "まで",
        "より", "だけ", "ほど", "しか", "でも", "など", "って"
    )

    private val auxiliaries = setOf(
        "です", "ます", "でした", "ません", "ない", "たい",
        "た", "て", "だ", "いる", "ある", "なる"
    )

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

            if (shouldDropLayoutSpace(previous, next)) {
                continue
            }

            if (out.lastOrNull() != ' ') {
                out.append(' ')
            }
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

            if (
                !shouldDropLayoutSpace(previous, next) &&
                needsLatinSpace(previous, next)
            ) {
                out.append(' ')
            }

            out.append(value)
        }

        return normalizeSource(out.toString())
    }

    /**
     * RSVP display units.
     *
     * The base unit is one ICU word. Japanese particles and short auxiliaries
     * are attached to the previous lexical word so they are not flashed alone.
     * Punctuation is also attached to the preceding unit.
     */
    fun chunk(raw: String): List<String> {
        val text = normalizeSource(raw)
        if (text.isBlank()) return emptyList()

        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()

        val units = mutableListOf<String>()
        val prefix = StringBuilder()

        for (token in tokens) {
            val value = token.trim()
            if (value.isBlank()) continue

            if (isPunctuationOnly(value)) {
                if (isOpeningPunctuationOnly(value)) {
                    prefix.append(value)
                } else if (units.isNotEmpty()) {
                    units[units.lastIndex] =
                        units.last() + value
                } else {
                    prefix.append(value)
                }
                continue
            }

            val word = prefix.toString() + value
            prefix.clear()

            val attachToPrevious =
                units.isNotEmpty() &&
                (value in particles || value in auxiliaries) &&
                visibleCharCount(units.last() + word) <= MAX_ATTACHED_CHARS

            if (attachToPrevious) {
                units[units.lastIndex] =
                    units.last() + word
            } else {
                units += word
            }
        }

        if (prefix.isNotEmpty()) {
            if (units.isNotEmpty()) {
                units[units.lastIndex] =
                    units.last() + prefix.toString()
            } else {
                units += prefix.toString()
            }
        }

        return units.filter { visibleCharCount(it) > 0 }
    }

    fun visibleCharCount(value: String): Int =
        value.count { ch ->
            ch.isLetterOrDigit() || isJapanese(ch)
        }.coerceAtLeast(1)

    fun sentencePauseMs(value: String): Long =
        when {
            value.endsWith('。') ||
                value.endsWith('！') ||
                value.endsWith('？') ||
                value.endsWith('!') ||
                value.endsWith('?') -> 170L

            value.endsWith('、') ||
                value.endsWith(',') -> 75L

            value.endsWith('；') ||
                value.endsWith(';') ||
                value.endsWith('：') ||
                value.endsWith(':') -> 50L

            else -> 0L
        }

    private fun tokenize(text: String): List<String> {
        val iterator =
            BreakIterator.getWordInstance(Locale.JAPANESE)

        iterator.setText(text)

        val result = mutableListOf<String>()

        var start = iterator.first()
        var end = iterator.next()

        while (end != BreakIterator.DONE) {
            val piece = text.substring(start, end)

            if (piece.isNotBlank()) {
                result += piece
            }

            start = end
            end = iterator.next()
        }

        return result
    }

    private fun shouldDropLayoutSpace(
        left: Char,
        right: Char
    ): Boolean {
        if (isJapanese(left) && isJapanese(right)) {
            return true
        }

        if (
            isJapanese(left) &&
            isClosingPunctuation(right)
        ) {
            return true
        }

        if (
            isOpeningPunctuation(left) &&
            isJapanese(right)
        ) {
            return true
        }

        return false
    }

    private fun needsLatinSpace(
        left: Char,
        right: Char
    ): Boolean =
        left.isLetterOrDigit() &&
            right.isLetterOrDigit() &&
            !isJapanese(left) &&
            !isJapanese(right)

    private fun isJapanese(ch: Char): Boolean =
        ch in '\u3040'..'\u30ff' ||
            ch in '\u3400'..'\u9fff' ||
            ch in '\uf900'..'\ufaff'

    private fun isOpeningPunctuation(ch: Char): Boolean =
        ch in setOf(
            '「', '『', '（', '(',
            '【', '〈', '《', '［', '['
        )

    private fun isOpeningPunctuationOnly(
        value: String
    ): Boolean =
        value.all { isOpeningPunctuation(it) }

    private fun isClosingPunctuation(ch: Char): Boolean =
        ch in setOf(
            '。', '、', '！', '？',
            '」', '』', '）', ')',
            '】', '〉', '》', '］', ']',
            ',', '.', '!', '?', ':', ';'
        )

    private fun isPunctuationOnly(
        value: String
    ): Boolean =
        value.all { ch ->
            !ch.isLetterOrDigit() &&
                !isJapanese(ch)
        }

    private const val MAX_ATTACHED_CHARS = 8
}
