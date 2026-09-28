package com.tomoya.rsvpreader

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer

object TextChunker {

    private val tokenizer: Tokenizer by lazy {
        Tokenizer()
    }

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
     * Japanese RSVP units are grammatical phrases, not arbitrary character
     * slices. Kuromoji supplies morphological boundaries and POS tags.
     *
     * Examples:
     *   私 / は / 本 / を / 読ん / で / いる
     * becomes:
     *   私は / 本を / 読んでいる
     */
    fun chunk(raw: String): List<String> {
        val text = normalizeSource(raw)
        if (text.isBlank()) return emptyList()

        val tokens = runCatching {
            tokenizer.tokenize(text)
        }.getOrElse {
            return fallbackByPunctuation(text)
        }

        if (tokens.isEmpty()) return emptyList()

        val units = mutableListOf<String>()
        val current = StringBuilder()
        val prefix = StringBuilder()
        var currentHeadPos = ""

        fun flush() {
            val value = current.toString().trim()
            if (value.isNotEmpty()) {
                units += value
            }
            current.clear()
            currentHeadPos = ""
        }

        fun startContent(token: Token) {
            if (prefix.isNotEmpty()) {
                current.append(prefix)
                prefix.clear()
            }
            current.append(token.surface)
            currentHeadPos = token.partOfSpeechLevel1
        }

        for (token in tokens) {
            val surface = token.surface
            if (surface.isBlank()) continue

            val pos1 = token.partOfSpeechLevel1
            val pos2 = token.partOfSpeechLevel2

            if (pos1 == "記号") {
                val opening = surface.all { isOpeningPunctuation(it) }

                if (opening) {
                    if (current.isNotEmpty()) {
                        flush()
                    }
                    prefix.append(surface)
                } else {
                    if (current.isNotEmpty()) {
                        current.append(surface)
                    } else if (units.isNotEmpty()) {
                        units[units.lastIndex] =
                            units.last() + surface
                    } else {
                        prefix.append(surface)
                    }

                    if (
                        surface.any {
                            it == '。' ||
                                it == '！' ||
                                it == '？' ||
                                it == '!' ||
                                it == '?'
                        }
                    ) {
                        flush()
                    }
                }
                continue
            }

            if (pos1 == "接頭詞") {
                if (current.isNotEmpty()) {
                    flush()
                }
                prefix.append(surface)
                continue
            }

            val functionWord =
                pos1 == "助詞" ||
                    pos1 == "助動詞" ||
                    pos1 == "フィラー"

            val dependentContent =
                pos2 == "非自立" ||
                    pos2 == "接尾" ||
                    pos1 == "接尾詞"

            if (functionWord || dependentContent) {
                if (current.isNotEmpty()) {
                    current.append(surface)
                } else if (units.isNotEmpty()) {
                    units[units.lastIndex] =
                        units.last() + surface
                } else {
                    current.append(surface)
                }
                continue
            }

            if (current.isEmpty()) {
                startContent(token)
                continue
            }

            val mergeCompoundNoun =
                currentHeadPos == "名詞" &&
                    pos1 == "名詞" &&
                    visibleCharCount(
                        current.toString() + surface
                    ) <= MAX_COMPOUND_CHARS

            if (mergeCompoundNoun) {
                current.append(surface)
            } else {
                flush()
                startContent(token)
            }
        }

        if (prefix.isNotEmpty()) {
            if (current.isNotEmpty()) {
                current.append(prefix)
            } else if (units.isNotEmpty()) {
                units[units.lastIndex] =
                    units.last() + prefix.toString()
            } else {
                current.append(prefix)
            }
        }

        flush()

        return units
            .map { it.trim() }
            .filter { visibleCharCount(it) > 0 }
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

    private fun fallbackByPunctuation(
        text: String
    ): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()

        for (ch in text) {
            current.append(ch)

            if (
                ch == '。' ||
                ch == '、' ||
                ch == '！' ||
                ch == '？' ||
                ch == '!' ||
                ch == '?'
            ) {
                val value = current.toString().trim()
                if (value.isNotEmpty()) {
                    result += value
                }
                current.clear()
            }
        }

        val tail = current.toString().trim()
        if (tail.isNotEmpty()) result += tail

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

    private fun isClosingPunctuation(ch: Char): Boolean =
        ch in setOf(
            '。', '、', '！', '？',
            '」', '』', '）', ')',
            '】', '〉', '》', '］', ']',
            ',', '.', '!', '?', ':', ';'
        )

    private const val MAX_COMPOUND_CHARS = 8
}
