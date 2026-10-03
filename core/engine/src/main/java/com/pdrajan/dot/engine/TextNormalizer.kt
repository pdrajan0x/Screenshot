package com.pdrajan.dot.engine

import java.text.Normalizer
import java.util.Locale

/**
 * Port of OpenCLIP's `_clean_lower`: `whitespace_clean(basic_clean(text)).lower()`.
 *
 * `basic_clean` runs ftfy, which has no Kotlin equivalent; [fixText] covers the ftfy fixes
 * that change real-world search queries and OCR text (quotes, ligatures, full-width forms,
 * line breaks, control characters, NFC). Mojibake repair is not ported.
 */
object TextNormalizer {

    fun cleanLower(text: String): String =
        whitespaceClean(basicClean(text)).lowercase(Locale.ROOT)

    fun basicClean(text: String): String =
        HtmlEntities.unescape(HtmlEntities.unescape(fixText(text))).trim(::isPyWhitespace)

    /** Python's `" ".join(text.split())`. */
    fun whitespaceClean(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (c in text) {
            if (isPyWhitespace(c)) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(c)
            }
        }
        return out.toString()
    }

    /** Characters Python's `str.split()` treats as whitespace. */
    fun isPyWhitespace(c: Char): Boolean =
        c.isWhitespace() || c == '\u0085' || c in '\u001c'..'\u001f'

    fun fixText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' -> {
                    sb.append('\n')
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                c == ' ' || c == ' ' || c == '\u0085' -> sb.append('\n')
                isRemovedControl(c) -> Unit
                c == 'ʼ' || c in '‘'..'‛' -> sb.append('\'')
                c in '“'..'‟' -> sb.append('"')
                c in '！'..'～' -> sb.append((c.code - 0xfee0).toChar())
                c == '　' -> sb.append(' ')
                else -> {
                    val lig = LIGATURES[c]
                    if (lig != null) sb.append(lig) else sb.append(c)
                }
            }
            i++
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }

    private fun isRemovedControl(c: Char): Boolean =
        c in '\u0000'..'\u0008' || c == '\u000b' || c in '\u000e'..'\u001f' ||
            c == '\u007f' || c == '﻿' || c in '￹'..'￼'

    private val LIGATURES = mapOf(
        'ﬀ' to "ff", 'ﬁ' to "fi", 'ﬂ' to "fl", 'ﬃ' to "ffi", 'ﬄ' to "ffl",
        'ﬅ' to "ſt", 'ﬆ' to "st", 'Ĳ' to "IJ", 'ĳ' to "ij", 'ŉ' to "ʼn",
        'Ǳ' to "DZ", 'ǲ' to "Dz", 'ǳ' to "dz", 'Ǆ' to "DŽ", 'ǅ' to "Dž", 'ǆ' to "dž",
        'Ǉ' to "LJ", 'ǈ' to "Lj", 'ǉ' to "lj", 'Ǌ' to "NJ", 'ǋ' to "Nj", 'ǌ' to "nj",
    )
}

/** Minimal `html.unescape`: numeric references plus the named entities seen in practice. */
object HtmlEntities {
    private val ENTITY = Regex("&(#[0-9]{1,7};|#[xX][0-9a-fA-F]{1,6};|[a-zA-Z][a-zA-Z0-9]{1,31};)")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
        "rdquo" to "”", "bull" to "•", "middot" to "·", "deg" to "°", "times" to "×",
        "divide" to "÷", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "laquo" to "«", "raquo" to "»", "sect" to "§", "para" to "¶", "plusmn" to "±",
    )

    fun unescape(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { m ->
            val body = m.value.substring(1, m.value.length - 1)
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    codePointString(body.substring(2).toIntOrNull(16)) ?: m.value
                body.startsWith("#") -> codePointString(body.substring(1).toIntOrNull()) ?: m.value
                else -> NAMED[body] ?: m.value
            }
        }
    }

    private fun codePointString(cp: Int?): String? {
        if (cp == null) return null
        if (cp == 0 || cp in 0xd800..0xdfff || cp > 0x10ffff) return "�"
        return String(Character.toChars(cp))
    }
}
