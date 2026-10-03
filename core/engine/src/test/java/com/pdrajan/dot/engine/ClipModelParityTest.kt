package com.pdrajan.dot.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * End-to-end check of the exported model against PyTorch reference outputs, using the same
 * Kotlin tokenizer and pixel preprocessing the app uses. Runs in CI after the model export;
 * skipped locally when model-out/ is absent.
 */
class ClipModelParityTest {

    private val modelOut = File(System.getProperty("clip.modelOut"))
    private val fixtures = File(modelOut, "fixtures/clip_fixtures.json")

    private fun session(env: OrtEnvironment, name: String): OrtSession =
        env.createSession(File(modelOut, "assets/clip/$name").absolutePath, OrtSession.SessionOptions())

    private fun cosine(a: FloatArray, b: FloatArray) =
        VectorMath.dot(VectorMath.l2Normalize(a), VectorMath.l2Normalize(b))

    @Test
    fun textEncoderMatchesReference() {
        assumeTrue("model not exported", fixtures.exists())
        val root = JSONObject(fixtures.readText())
        val tokenizer = File(System.getProperty("clip.vocab")).inputStream().use { ClipTokenizer(it) }
        val env = OrtEnvironment.getEnvironment()
        session(env, "text_encoder.onnx").use { s ->
            val texts = root.getJSONArray("texts")
            for (i in 0 until texts.length()) {
                val t = texts.getJSONObject(i)
                val ids = tokenizer.tokenize(t.getString("text"))
                val expectedIds = t.getJSONArray("ids").let { a -> LongArray(a.length()) { a.getLong(it) } }
                assertEquals(expectedIds.toList(), ids.toList())

                OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
                    s.run(mapOf("input_ids" to input)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val emb = (out[0].value as Array<FloatArray>)[0]
                        val ref = t.getJSONArray("embedding").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                        val cos = cosine(emb, ref)
                        assertTrue("\"${t.getString("text")}\" cos=$cos", cos > 0.9999f)
                    }
                }
            }
        }
    }

    @Test
    fun imageEncoderMatchesReference() {
        assumeTrue("model not exported", fixtures.exists())
        val root = JSONObject(fixtures.readText())
        val size = root.getInt("image_size")
        val pixels = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val r = x * 255 / (size - 1)
            val g = y * 255 / (size - 1)
            val b = ((x / 16 + y / 16) % 2) * 200 + 27
            (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        val chw = FloatArray(3 * size * size)
        PixelPreprocess.argbToChw(pixels, size, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), chw)

        val env = OrtEnvironment.getEnvironment()
        session(env, "image_encoder.onnx").use { s ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
                s.run(mapOf("pixel_values" to input)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val emb = (out[0].value as Array<FloatArray>)[0]
                    val ref = root.getJSONArray("image_embedding").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                    val cos = cosine(emb, ref)
                    assertTrue("image cos=$cos", cos > 0.9999f)
                }
            }
        }
    }
}
