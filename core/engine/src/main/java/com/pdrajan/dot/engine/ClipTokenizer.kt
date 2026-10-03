package com.pdrajan.dot.engine

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * Byte-level BPE tokenizer matching OpenCLIP's `SimpleTokenizer` (the one MobileCLIP2 uses).
 *
 * @param vocabStream `bpe_simple_vocab_16e6.txt(.gz)` as shipped with OpenCLIP / OpenAI CLIP, gzipped or
 *   plain (the Android packager silently un-gzips `.gz` assets and drops the extension).
 */
class ClipTokenizer(vocabStream: InputStream, val contextLength: Int = 77) {

    private val byteEncoder: Array<String>
    private val encoder: HashMap<String, Int>
    private val bpeRanks: HashMap<String, Int>
    private val cache = object : LinkedHashMap<String, List<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 20_000
    }

    val sotTokenId: Int
    val eotTokenId: Int

    init {
        val (byByte, inOrder) = bytesToUnicode()
        byteEncoder = byByte
        val lines = maybeGunzip(vocabStream).bufferedReader(Charsets.UTF_8).use { it.readText() }.split('\n')
        val merges = lines.subList(1, 49152 - 256 - 2 + 1).map { line ->
            val parts = line.split(' ')
            parts[0] to parts[1]
        }
        val vocab = ArrayList<String>(49408)
        vocab.addAll(inOrder)
        inOrder.forEach { vocab.add("$it</w>") }
        merges.forEach { (a, b) -> vocab.add(a + b) }
        vocab.add(SOT)
        vocab.add(EOT)

        encoder = HashMap(vocab.size * 2)
        vocab.forEachIndexed { i, token -> encoder[token] = i }
        bpeRanks = HashMap(merges.size * 2)
        merges.forEachIndexed { i, (a, b) -> bpeRanks[pairKey(a, b)] = i }
        sotTokenId = encoder.getValue(SOT)
        eotTokenId = encoder.getValue(EOT)
    }

    /** Token ids without start/end markers. */
    fun encode(text: String): List<Int> {
        val cleaned = TextNormalizer.cleanLower(text)
        val out = ArrayList<Int>()
        val m = PATTERN.matcher(cleaned)
        while (m.find()) {
            val token = m.group()
            val mapped = if (token == SOT || token == EOT) {
                token
            } else {
                val sb = StringBuilder()
                for (b in token.toByteArray(Charsets.UTF_8)) sb.append(byteEncoder[b.toInt() and 0xff])
                sb.toString()
            }
            for (piece in bpe(mapped)) out.add(encoder.getValue(piece))
        }
        return out
    }

    /** `[SOT] + tokens + [EOT]`, truncated (keeping EOT last) and zero-padded to [contextLength]. */
    fun tokenize(text: String): LongArray {
        val tokens = ArrayList<Int>(contextLength)
        tokens.add(sotTokenId)
        tokens.addAll(encode(text))
        tokens.add(eotTokenId)
        val result = LongArray(contextLength)
        val n = minOf(tokens.size, contextLength)
        for (i in 0 until n) result[i] = tokens[i].toLong()
        if (tokens.size > contextLength) result[contextLength - 1] = eotTokenId.toLong()
        return result
    }

    private fun bpe(token: String): List<String> {
        if (token == SOT || token == EOT) return listOf(token)
        synchronized(cache) { cache[token]?.let { return it } }

        var word: List<String> = buildList {
            for (i in 0 until token.length - 1) add(token[i].toString())
            add(token.last() + "</w>")
        }
        if (word.size == 1) return word.also { put(token, it) }

        while (true) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val rank = bpeRanks[pairKey(word[i], word[i + 1])] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    best = word[i] to word[i + 1]
                }
            }
            val (first, second) = best ?: break
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                val j = indexOf(word, first, i)
                if (j < 0) {
                    merged.addAll(word.subList(i, word.size))
                    break
                }
                merged.addAll(word.subList(i, j))
                i = j
                if (i < word.size - 1 && word[i + 1] == second) {
                    merged.add(first + second)
                    i += 2
                } else {
                    merged.add(word[i])
                    i += 1
                }
            }
            word = merged
            if (word.size == 1) break
        }
        return word.also { put(token, it) }
    }

    private fun put(token: String, pieces: List<String>) {
        synchronized(cache) { cache[token] = pieces }
    }

    private fun indexOf(word: List<String>, symbol: String, from: Int): Int {
        for (k in from until word.size) if (word[k] == symbol) return k
        return -1
    }

    companion object {
        const val SOT = "<start_of_text>"
        const val EOT = "<end_of_text>"

        private fun maybeGunzip(input: InputStream): InputStream {
            val buffered = BufferedInputStream(input)
            buffered.mark(2)
            val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            return if (gzip) GZIPInputStream(buffered) else buffered
        }

        // OpenCLIP's pattern. `\s` is written as a plain space: input is already whitespace-cleaned,
        // and Android's regex engine does not support UNICODE_CHARACTER_CLASS.
        private val PATTERN: Pattern = Pattern.compile(
            "<start_of_text>|<end_of_text>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^ \\p{L}\\p{N}]+",
            Pattern.CASE_INSENSITIVE,
        )

        private fun pairKey(a: String, b: String) = "$a\u0000$b"

        /**
         * GPT-2/CLIP reversible byte → printable-character table.
         * Returns (table indexed by byte value, characters in Python's dict insertion order).
         * The vocabulary is built from the second list, so the order matters.
         */
        fun bytesToUnicode(): Pair<Array<String>, List<String>> {
            val bs = ArrayList<Int>()
            bs.addAll('!'.code..'~'.code)
            bs.addAll('¡'.code..'¬'.code)
            bs.addAll('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val byByte = Array(256) { "" }
            val inOrder = ArrayList<String>(256)
            for (i in bs.indices) {
                val s = cs[i].toChar().toString()
                byByte[bs[i]] = s
                inOrder.add(s)
            }
            return byByte to inOrder
        }
    }
}
