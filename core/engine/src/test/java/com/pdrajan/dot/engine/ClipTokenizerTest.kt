package com.pdrajan.dot.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ClipTokenizerTest {

    private val tokenizer by lazy {
        File(System.getProperty("clip.vocab")).inputStream().use { ClipTokenizer(it) }
    }

    @Test
    fun acceptsPlainVocabLikeTheApkShips() {
        val plain = java.util.zip.GZIPInputStream(File(System.getProperty("clip.vocab")).inputStream()).use { it.readBytes() }
        val fromPlain = ClipTokenizer(plain.inputStream())
        for (text in listOf("a photo of a dog", "UPI ₹499 paid", "मौसम")) {
            assertEquals(tokenizer.tokenize(text).toList(), fromPlain.tokenize(text).toList())
        }
    }

    @Test
    fun matchesOpenClipSimpleTokenizer() {
        val json = javaClass.getResource("/tokenizer_fixtures.json")!!.readText()
        val root = JSONObject(json)
        assertEquals(root.getInt("sot"), tokenizer.sotTokenId)
        assertEquals(root.getInt("eot"), tokenizer.eotTokenId)

        val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val text = case.getString("text")
            val expectedIds = case.getJSONArray("ids")
            val expected = LongArray(expectedIds.length()) { expectedIds.getLong(it) }
            val actual = tokenizer.tokenize(text)
            assertEquals("token ids for \"$text\"", expected.toList(), actual.toList())
        }
    }
}
