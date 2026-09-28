package com.tomoya.rsvpreader

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer

object TextChunker {

    private data class Morph(
        val surface: String,
        val pos1: String,
        val pos2: String
    )

    private val tokenizer: Tokenizer by lazy {
        Tokenizer()
    }

    fun normalizeSource(raw: String): String {
        val lineRepaired =
            repairLayoutLineBreaks(raw)

        val collapsed = lineRepaired
            .replace(
                Regex("[\\t 　]+"),
                " "
            )
            .trim()

        if (collapsed.isBlank()) {
            return ""
        }

        val out = StringBuilder()

        for (i in collapsed.indices) {
            val ch = collapsed[i]

            if (ch != ' ') {
                out.append(ch)
                continue
            }

            val previous =
                out.lastOrNull()

            val next =
                collapsed.getOrNull(
                    i + 1
                )

            if (
                previous == null ||
                next == null
            ) {
                continue
            }

            if (
                shouldDropLayoutSpace(
                    previous,
                    next
                )
            ) {
                continue
            }

            if (
                out.lastOrNull() != ' '
            ) {
                out.append(' ')
            }
        }

        return out
            .toString()
            .trim()
    }

    fun stitchFragments(
        parts: List<String>
    ): String {
        val out =
            StringBuilder()

        for (part in parts) {
            val value =
                normalizeSource(part)

            if (value.isBlank()) {
                continue
            }

            if (out.isEmpty()) {
                out.append(value)
                continue
            }

            val previous =
                out.last()

            val next =
                value.first()

            if (
                !shouldDropLayoutSpace(
                    previous,
                    next
                ) &&
                needsLatinSpace(
                    previous,
                    next
                )
            ) {
                out.append(' ')
            }

            out.append(value)
        }

        return normalizeSource(
            out.toString()
        )
    }

    /**
     * Creates bunsetsu-like RSVP units:
     *
     *   私 / は / 新しい / 本 / を / 読ん / で / いる
     *
     * becomes roughly:
     *
     *   私は / 新しい / 本を / 読んでいる
     *
     * No unit is split by raw character count.
     */
    fun chunk(
        raw: String
    ): List<String> {
        val text =
            normalizeSource(raw)

        if (text.isBlank()) {
            return emptyList()
        }

        val tokens =
            runCatching {
                tokenizer
                    .tokenize(text)
                    .map {
                        Morph(
                            surface =
                                it.surface,
                            pos1 =
                                it.partOfSpeechLevel1,
                            pos2 =
                                it.partOfSpeechLevel2
                        )
                    }
            }.getOrElse {
                return fallbackByPunctuation(
                    text
                )
            }

        if (tokens.isEmpty()) {
            return emptyList()
        }

        val units =
            mutableListOf<String>()

        val current =
            mutableListOf<Morph>()

        val prefix =
            StringBuilder()

        fun currentText(): String =
            buildString {
                for (morph in current) {
                    append(
                        morph.surface
                    )
                }
            }

        fun flush() {
            val value =
                currentText()
                    .trim()

            if (value.isNotEmpty()) {
                units += value
            }

            current.clear()
        }

        fun start(
            morph: Morph
        ) {
            if (prefix.isNotEmpty()) {
                current += Morph(
                    surface =
                        prefix.toString(),
                    pos1 = "記号",
                    pos2 = "括弧開"
                )
                prefix.clear()
            }

            current += morph
        }

        for (morph in tokens) {
            val surface =
                morph.surface

            if (surface.isBlank()) {
                continue
            }

            if (
                morph.pos1 == "記号"
            ) {
                val opening =
                    surface.all {
                        isOpeningPunctuation(
                            it
                        )
                    }

                if (opening) {
                    if (
                        current.isNotEmpty()
                    ) {
                        flush()
                    }

                    prefix.append(
                        surface
                    )

                    continue
                }

                if (
                    current.isNotEmpty()
                ) {
                    current += morph
                } else if (
                    units.isNotEmpty()
                ) {
                    units[
                        units.lastIndex
                    ] =
                        units.last() +
                            surface
                } else {
                    prefix.append(
                        surface
                    )
                }

                if (
                    surface.any {
                        isSentenceEnding(
                            it
                        )
                    }
                ) {
                    flush()
                }

                continue
            }

            if (
                morph.pos1 ==
                    "接頭詞"
            ) {
                if (
                    current.isNotEmpty()
                ) {
                    flush()
                }

                prefix.append(
                    surface
                )

                continue
            }

            if (
                isFunctionWord(
                    morph
                ) ||
                isDependentWord(
                    morph
                )
            ) {
                if (
                    current.isNotEmpty()
                ) {
                    current += morph
                } else if (
                    units.isNotEmpty()
                ) {
                    units[
                        units.lastIndex
                    ] =
                        units.last() +
                            surface
                } else {
                    start(morph)
                }

                continue
            }

            if (
                current.isEmpty()
            ) {
                start(morph)
                continue
            }

            val currentHead =
                current.firstOrNull {
                    !isFunctionWord(it) &&
                        !isDependentWord(it) &&
                        it.pos1 != "記号"
                }

            val candidate =
                currentText() +
                    surface

            val canMerge =
                canMergeContentWords(
                    currentHead =
                        currentHead,
                    next =
                        morph,
                    combined =
                        candidate
                )

            if (canMerge) {
                current += morph
            } else {
                flush()
                start(morph)
            }
        }

        if (
            prefix.isNotEmpty()
        ) {
            if (
                current.isNotEmpty()
            ) {
                current += Morph(
                    surface =
                        prefix.toString(),
                    pos1 = "記号",
                    pos2 = "括弧開"
                )
            } else if (
                units.isNotEmpty()
            ) {
                units[
                    units.lastIndex
                ] =
                    units.last() +
                        prefix.toString()
            } else {
                current += Morph(
                    surface =
                        prefix.toString(),
                    pos1 = "記号",
                    pos2 = "括弧開"
                )
            }
        }

        flush()

        return rebalanceTinyUnits(
            units
        )
    }

    private fun canMergeContentWords(
        currentHead: Morph?,
        next: Morph,
        combined: String
    ): Boolean {
        if (currentHead == null) {
            return false
        }

        val length =
            visibleCharCount(
                combined
            )

        if (
            length >
                MAX_PHRASE_CHARS
        ) {
            return false
        }

        val compoundNoun =
            currentHead.pos1 ==
                "名詞" &&
                next.pos1 ==
                    "名詞"

        val prenominal =
            currentHead.pos1 ==
                "連体詞" &&
                next.pos1 ==
                    "名詞"

        val shortModifier =
            currentHead.pos1 ==
                "形容詞" &&
                next.pos1 ==
                    "名詞" &&
                length <=
                    SHORT_MODIFIER_MAX_CHARS

        return compoundNoun ||
            prenominal ||
            shortModifier
    }

    private fun isFunctionWord(
        morph: Morph
    ): Boolean =
        morph.pos1 == "助詞" ||
            morph.pos1 == "助動詞" ||
            morph.pos1 == "フィラー"

    private fun isDependentWord(
        morph: Morph
    ): Boolean =
        morph.pos2 == "非自立" ||
            morph.pos2 == "接尾" ||
            morph.pos1 == "接尾詞"

    private fun rebalanceTinyUnits(
        raw: List<String>
    ): List<String> {
        if (raw.size < 2) {
            return raw
        }

        val result =
            mutableListOf<String>()

        var i = 0

        while (i < raw.size) {
            val current =
                raw[i]

            val currentLength =
                visibleCharCount(
                    current
                )

            if (
                currentLength == 1 &&
                i + 1 < raw.size
            ) {
                val next =
                    raw[i + 1]

                val combined =
                    current + next

                if (
                    visibleCharCount(
                        combined
                    ) <=
                        TINY_MERGE_MAX_CHARS &&
                    !endsSentence(
                        current
                    )
                ) {
                    result += combined
                    i += 2
                    continue
                }
            }

            result += current
            i++
        }

        return result
    }

    fun visibleCharCount(
        value: String
    ): Int =
        value.count {
            ch ->
            ch.isLetterOrDigit() ||
                isJapanese(ch)
        }.coerceAtLeast(1)

    fun sentencePauseMs(
        value: String
    ): Long =
        when {
            value.endsWith('。') ||
                value.endsWith('！') ||
                value.endsWith('？') ||
                value.endsWith('!') ||
                value.endsWith('?') ->
                190L

            value.endsWith('、') ||
                value.endsWith(',') ->
                85L

            value.endsWith('；') ||
                value.endsWith(';') ||
                value.endsWith('：') ||
                value.endsWith(':') ->
                55L

            else -> 0L
        }

    private fun fallbackByPunctuation(
        text: String
    ): List<String> {
        val result =
            mutableListOf<String>()

        val current =
            StringBuilder()

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
                val value =
                    current
                        .toString()
                        .trim()

                if (
                    value.isNotEmpty()
                ) {
                    result += value
                }

                current.clear()
            }
        }

        val tail =
            current
                .toString()
                .trim()

        if (tail.isNotEmpty()) {
            result += tail
        }

        return result
    }

    private fun endsSentence(
        value: String
    ): Boolean =
        value.lastOrNull()
            ?.let {
                isSentenceEnding(it)
            }
            ?: false

    private fun isSentenceEnding(
        ch: Char
    ): Boolean =
        ch == '。' ||
            ch == '！' ||
            ch == '？' ||
            ch == '!' ||
            ch == '?'

    private fun repairLayoutLineBreaks(
        raw: String
    ): String {
        if (
            !raw.contains('\n') &&
            !raw.contains('\r')
        ) {
            return raw
        }

        val normalized =
            raw.replace("\r\n", "\n")
                .replace('\r', '\n')

        val out = StringBuilder()
        var index = 0

        while (index < normalized.length) {
            val ch = normalized[index]

            if (ch != '\n') {
                out.append(ch)
                index++
                continue
            }

            var nextIndex = index
            while (
                nextIndex < normalized.length &&
                normalized[nextIndex] == '\n'
            ) {
                nextIndex++
            }

            val left =
                out.lastOrNull {
                    !it.isWhitespace()
                }

            val right =
                normalized
                    .drop(nextIndex)
                    .firstOrNull {
                        !it.isWhitespace()
                    }

            if (
                left != null &&
                right != null &&
                isLatinLike(left) &&
                isLatinLike(right)
            ) {
                if (
                    out.lastOrNull() != ' '
                ) {
                    out.append(' ')
                }
            }
            // Japanese line wrapping is visual layout only.
            // Do not emit a separator.

            index = nextIndex
        }

        return out.toString()
    }

    private fun isLatinLike(
        ch: Char
    ): Boolean =
        ch.isLetterOrDigit() &&
            !isJapanese(ch)

    private fun shouldDropLayoutSpace(
        left: Char,
        right: Char
    ): Boolean {
        if (
            isJapanese(left) ||
            isJapanese(right)
        ) {
            return true
        }

        if (
            isJapanese(left) &&
            isClosingPunctuation(
                right
            )
        ) {
            return true
        }

        if (
            isOpeningPunctuation(
                left
            ) &&
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

    private fun isJapanese(
        ch: Char
    ): Boolean =
        ch in '\u3040'..'\u30ff' ||
            ch in '\u3400'..'\u9fff' ||
            ch in '\uf900'..'\ufaff'

    private fun isOpeningPunctuation(
        ch: Char
    ): Boolean =
        ch in setOf(
            '「',
            '『',
            '（',
            '(',
            '【',
            '〈',
            '《',
            '［',
            '['
        )

    private fun isClosingPunctuation(
        ch: Char
    ): Boolean =
        ch in setOf(
            '。',
            '、',
            '！',
            '？',
            '」',
            '』',
            '）',
            ')',
            '】',
            '〉',
            '》',
            '］',
            ']',
            ',',
            '.',
            '!',
            '?',
            ':',
            ';'
        )

    private const val MAX_PHRASE_CHARS =
        7

    private const val SHORT_MODIFIER_MAX_CHARS =
        6

    private const val TINY_MERGE_MAX_CHARS =
        5
}
