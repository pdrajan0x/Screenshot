package com.pdrajan.dot.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The app's Florence-2 pipeline (FlorenceModel) against tools/model/fetch_florence.py: same
 * picture in, same tokens out. Skipped when the model hasn't been fetched (model-out/).
 * Also checks the shipped config lists the files to download.
 */
class FlorenceParityTest {

    @Test
    fun shippedConfigListsTheModelFiles() {
        val config = FlorenceConfig.parse(File(assets, "florence_config.json").readText(), File(assets, "tokens.json").readText())
        assertEquals(listOf("vision_encoder.onnx", "embed_tokens.onnx", "encoder_model.onnx", "decoder_model.onnx"), config.files.map { it.name })
        assertTrue(config.files.all { it.url.startsWith("https://huggingface.co/") && it.sha256.length == 64 && it.size > 1_000_000 })
    }

    private val modelOut = File(System.getProperty("model.out"))
    private val folder = File(modelOut, "florence")
    private val assets = File(System.getProperty("florence.assets"))
    private val fixtures = File(modelOut, "fixtures/florence_fixtures.json")

    @Test
    fun generatesWhatTheReferenceGenerates() {
        assumeTrue("Florence not fetched", fixtures.exists())
        val config = FlorenceConfig.parse(File(assets, "florence_config.json").readText(), File(assets, "tokens.json").readText())
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        fun session(name: String) = env.createSession(File(folder, name).absolutePath, options)
        val bytes = File(modelOut, "fixtures/florence_pixels.bin").readBytes()
        val pixels = FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        FlorenceModel(env, session("vision_encoder.onnx"), session("embed_tokens.onnx"), session("encoder_model.onnx"), session("decoder_model.onnx"), config).use { model ->
            val image = model.encodeImage(pixels)
            val expected = JSONObject(fixtures.readText())
            for (task in listOf("caption", "detailed", "objects")) {
                val want = expected.getJSONObject(task)
                val ids = model.generate(image, task)
                val wantIds = want.getJSONArray("ids").let { a -> List(a.length()) { a.getInt(it) } }
                assertEquals("$task tokens", wantIds, ids)
                assertEquals("$task text", want.getString("text").replace("<s>", "").replace("</s>", ""), config.decode(ids))
            }
        }
    }
}

/** How raw Florence answers become a screenshot's description (answers from real screenshots). */
class FlorenceTextTest {

    @Test
    fun objectLabelsWithoutLocations() {
        assertEquals(listOf("cricket ball", "footwear"), FlorenceText.objects("cricket ball<loc_12><loc_40><loc_88><loc_91>footwear<loc_1><loc_2><loc_3><loc_4>"))
        assertEquals(emptyList<String>(), FlorenceText.objects(""))
    }

    @Test
    fun picturesKeepTheDetailedCaption() {
        val d = FlorenceText.describe(
            caption = "a woman sitting on top of a bed wearing a black top and jeans",
            detailed = "The image shows a woman sitting on a couch wearing a black top and blue jeans, with a pair of black boots. The background of the image is a wall, and there are several buttons and text visible.",
            objectsRaw = "footwear<loc_1><loc_2><loc_3><loc_4>human face<loc_5><loc_6><loc_7><loc_8>person<loc_9><loc_1><loc_2><loc_3>trousers<loc_4><loc_5><loc_6><loc_7>",
            screenText = "25 Save kpetruk_zdes",
        )
        assertTrue(d.text.startsWith("The image shows a woman sitting on a couch"))
        assertEquals(listOf("footwear", "person", "trousers"), d.objects)
    }

    @Test
    fun quotesNotOnTheScreenAreDropped() {
        val d = FlorenceText.describe(
            caption = "a screenshot of a cell phone with a message on the screen",
            detailed = "The image shows a screenshot of a cell phone with a message on the screen, displaying text and icons. The text reads \"How to get a VMR card from Netflix\" and there are several icons below it.",
            objectsRaw = "mobile phone<loc_1><loc_2><loc_3><loc_4>",
            screenText = "VM-HDFCBK Messages now UPI-Mandate Created for Rs.649.00 For NETFLIX From HDFC Bank Credit Card",
        )
        assertEquals("A screenshot of a cell phone with a message on the screen", d.text)
        assertTrue(d.objects.isEmpty())
    }

    @Test
    fun textScreensKeepSentencesQuotingTheScreen() {
        val d = FlorenceText.describe(
            caption = "a screenshot of a cell phone with a number on the screen",
            detailed = "The image shows a screenshot of a mobile phone with a message on the screen that reads \"RMA Tracker\" and a link to a link for a RMA tracker.",
            objectsRaw = "mobile phone<loc_1><loc_2><loc_3><loc_4>",
            screenText = "Kaizen Whatsapp us: RMA Tracker Enter RMA Number Submit",
        )
        assertTrue(d.text.contains("RMA Tracker"))
    }

    @Test
    fun textScreensWithoutQuotesUseTheCaption() {
        val d = FlorenceText.describe(
            caption = "a screenshot of a cell phone with a bunch of different things on it",
            detailed = "The image shows a screenshot of a cell phone displaying a list of the most popular music bands in the world.",
            objectsRaw = "mobile phone<loc_1><loc_2><loc_3><loc_4>",
            screenText = "r/TheMentalist 4x24 - The Crimson Hat Season 5",
        )
        assertEquals("A screenshot of a cell phone with a bunch of different things on it", d.text)
        assertFalse(d.text.contains("music"))
    }
}
