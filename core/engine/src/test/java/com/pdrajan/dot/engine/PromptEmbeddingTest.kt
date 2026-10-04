package com.pdrajan.dot.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.LongBuffer

/**
 * What the app does on first run: tokenize every built-in prompt (screenshot categories, picture
 * keywords) and embed them with the text encoder in batches of 8. Skipped when the model is absent.
 */
class PromptEmbeddingTest {

    private val modelOut = File(System.getProperty("clip.modelOut"))
    private val textModel = File(modelOut, "assets/clip/text_encoder.onnx")

    @Test
    fun allPromptsTokenizeAndEmbedInBatches() {
        val tokenizer = File(System.getProperty("clip.vocab")).inputStream().use { ClipTokenizer(it) }
        val words = PictureWords.parse(File(System.getProperty("clip.pictureWords")).readText())
        val prompts = Categories.ALL.flatMap { it.prompts } + Categories.BACKGROUND_PROMPTS + words.prompts(forScreenshots = true)
        val ids = prompts.map { p -> tokenizer.tokenize(p).also { assertEquals(p, 77, it.size) } }

        assumeTrue("model not exported", textModel.exists())
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
        env.createSession(textModel.absolutePath, options).use { session ->
            var embedded = 0
            ids.chunked(8).forEach { chunk ->
                val flat = LongArray(chunk.size * 77)
                chunk.forEachIndexed { i, row -> row.copyInto(flat, i * 77) }
                OnnxTensor.createTensor(env, LongBuffer.wrap(flat), longArrayOf(chunk.size.toLong(), 77)).use { input ->
                    session.run(mapOf("input_ids" to input)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val vectors = out[0].value as Array<FloatArray>
                        assertEquals(chunk.size, vectors.size)
                        vectors.forEach { v -> assertTrue(kotlin.math.abs(VectorMath.dot(v, v) - 1f) < 1e-3f) }
                        embedded += vectors.size
                    }
                }
            }
            assertEquals(prompts.size, embedded)
        }
    }
}
