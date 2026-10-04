package com.pdrajan.dot.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.pdrajan.dot.engine.ClipTokenizer
import com.pdrajan.dot.engine.CropPlanner
import com.pdrajan.dot.engine.PixelPreprocess
import org.json.JSONObject
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

data class ClipConfig(
    val imageSize: Int,
    val mean: FloatArray,
    val std: FloatArray,
    val embedDim: Int,
    val contextLength: Int,
    val logitScale: Float,
    val modelName: String,
    /** ONNX Runtime graph optimisation level per encoder, as verified exact by the export script. */
    val imageOptLevel: String = "all",
    val textOptLevel: String = "all",
) {
    companion object {
        fun parse(json: String): ClipConfig {
            val o = JSONObject(json)
            fun floats(key: String) = o.getJSONArray(key).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            return ClipConfig(
                imageSize = o.getInt("image_size"),
                mean = floats("mean"),
                std = floats("std"),
                embedDim = o.optInt("embed_dim", 512),
                contextLength = o.optInt("context_length", 77),
                logitScale = o.optDouble("logit_scale", 100.0).toFloat(),
                modelName = o.optString("model", "clip"),
                imageOptLevel = o.optJSONObject("ort_optimization")?.optString("image", "all") ?: "all",
                textOptLevel = o.optJSONObject("ort_optimization")?.optString("text", "all") ?: "all",
            )
        }
    }
}

/**
 * MobileCLIP2-S0 on ONNX Runtime. Sessions load lazily; the image encoder is all that indexing
 * needs, the text encoder only loads for searches. Models are memory-mapped straight from the
 * (uncompressed) APK assets, so nothing is copied to storage.
 */
class ClipModel private constructor(
    private val assets: AssetManager,
    val config: ClipConfig,
    private val threads: Int,
) : Closeable {

    private val env = OrtEnvironment.getEnvironment().apply { runCatching { setTelemetry(false) } }
    private var imageSession: OrtSession? = null
    private var textSession: OrtSession? = null
    val tokenizer: ClipTokenizer by lazy {
        // The packager un-gzips "x.txt.gz" assets into "x.txt"; accept either.
        val vocab = runCatching { assets.open("clip/bpe_simple_vocab_16e6.txt") }.getOrElse { assets.open("clip/bpe_simple_vocab_16e6.txt.gz") }
        vocab.use { ClipTokenizer(it, config.contextLength) }
    }
    private fun options(level: String) = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(
            when (level) {
                "none" -> OrtSession.SessionOptions.OptLevel.NO_OPT
                "basic" -> OrtSession.SessionOptions.OptLevel.BASIC_OPT
                "extended" -> OrtSession.SessionOptions.OptLevel.EXTENDED_OPT
                else -> OrtSession.SessionOptions.OptLevel.ALL_OPT
            },
        )
        // Don't busy-wait between ops: noticeably kinder to the battery, negligible latency cost.
        addConfigEntry("session.intra_op.allow_spinning", "0")
    }

    private fun mapAsset(name: String): ByteBuffer {
        assets.openFd(name).use { fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
            }
        }
    }

    @Synchronized
    private fun image(): OrtSession = imageSession ?: env.createSession(mapAsset("clip/image_encoder.onnx"), options(config.imageOptLevel)).also { imageSession = it }

    @Synchronized
    private fun text(): OrtSession = textSession ?: env.createSession(mapAsset("clip/text_encoder.onnx"), options(config.textOptLevel)).also { textSession = it }

    /** One L2-normalised embedding per square crop (1 for photos, up to 3 for tall screenshots). */
    fun embedImage(bitmap: Bitmap, maxCrops: Int = 3): List<FloatArray> {
        val size = config.imageSize
        val crops = CropPlanner.squareCrops(bitmap.width, bitmap.height, maxCrops)
        val plane = 3 * size * size
        val input = FloatArray(crops.size * plane)
        val pixels = IntArray(size * size)
        val target = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            crops.forEachIndexed { i, crop ->
                drawCrop(bitmap, Rect(crop.left, crop.top, crop.left + crop.size, crop.top + crop.size), target)
                target.getPixels(pixels, 0, size, 0, 0, size, size)
                PixelPreprocess.argbToChw(pixels, size, config.mean, config.std, input, i * plane)
            }
        } finally {
            target.recycle()
        }
        val shape = longArrayOf(crops.size.toLong(), 3, size.toLong(), size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            image().run(mapOf("pixel_values" to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                return (out[0].value as Array<FloatArray>).toList()
            }
        }
    }

    fun embedTexts(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val ctx = config.contextLength
        val ids = LongArray(texts.size * ctx)
        texts.forEachIndexed { i, t -> tokenizer.tokenize(t).copyInto(ids, i * ctx) }
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(texts.size.toLong(), ctx.toLong())).use { tensor ->
            text().run(mapOf("input_ids" to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                return (out[0].value as Array<FloatArray>).toList()
            }
        }
    }

    /** Frees the text encoder (~65 MB) when search hasn't been used for a while. */
    @Synchronized
    fun releaseText() {
        textSession?.close()
        textSession = null
    }

    @Synchronized
    fun releaseImage() {
        imageSession?.close()
        imageSession = null
    }

    @Synchronized
    override fun close() {
        releaseText()
        releaseImage()
    }

    companion object {
        fun load(context: Context): ClipModel {
            val assets = context.assets
            val config = ClipConfig.parse(assets.open("clip/clip_config.json").bufferedReader().use { it.readText() })
            val cores = Runtime.getRuntime().availableProcessors()
            // Two threads: a photo still takes a fraction of a second, and the rest of the phone stays smooth.
            return ClipModel(assets, config, threads = max(1, min(2, cores / 2)))
        }

        fun isAvailable(context: Context): Boolean =
            runCatching { context.assets.openFd("clip/image_encoder.onnx").close(); true }.getOrDefault(false)

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        /**
         * Scales [src] region into [target]. Halves repeatedly first so a 1080px crop → 256px
         * isn't aliased (torchvision's resize antialiases; plain bilinear wouldn't).
         */
        private fun drawCrop(source: Bitmap, region: Rect, target: Bitmap) {
            var src = source
            var r = Rect(region)
            val owned = ArrayList<Bitmap>()
            while (r.width() / 2 >= target.width * 2) {
                val half = Bitmap.createBitmap(r.width() / 2, r.height() / 2, Bitmap.Config.ARGB_8888)
                Canvas(half).drawBitmap(src, r, Rect(0, 0, half.width, half.height), paint)
                owned += half
                src = half
                r = Rect(0, 0, half.width, half.height)
            }
            Canvas(target).drawBitmap(src, r, Rect(0, 0, target.width, target.height), paint)
            owned.forEach { it.recycle() }
        }
    }
}
