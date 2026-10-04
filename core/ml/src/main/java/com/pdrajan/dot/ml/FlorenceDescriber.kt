package com.pdrajan.dot.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.pdrajan.dot.engine.FlorenceConfig
import com.pdrajan.dot.engine.FlorenceModel
import com.pdrajan.dot.engine.FlorenceText
import com.pdrajan.dot.engine.ImageDescription
import com.pdrajan.dot.engine.PixelPreprocess
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Describes a screenshot with Florence-2-base: what's in its pictures and the objects it sees,
 * checked against the screen's text ([FlorenceText]). The config ships in the APK
 * (assets/florence); the model files are downloaded after install into [dir].
 */
class FlorenceDescriber private constructor(
    private val dir: File,
    val config: FlorenceConfig,
    private val threads: Int,
) : Closeable {

    private val env = OrtEnvironment.getEnvironment()
    private var model: FlorenceModel? = null

    @Synchronized
    private fun model(): FlorenceModel = model ?: run {
        fun session(name: String) = env.createSession(
            File(dir, name).absolutePath,
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // Don't busy-wait between ops: kinder to the battery.
                addConfigEntry("session.intra_op.allow_spinning", "0")
            },
        )
        FlorenceModel(env, session("vision_encoder.onnx"), session("embed_tokens.onnx"), session("encoder_model.onnx"), session("decoder_model.onnx"), config)
    }.also { model = it }

    /**
     * Blocking; call from a background thread. [screenText] is what OCR read, to check quotes
     * against. Synchronized with [close], so the model is never released mid-answer.
     */
    @Synchronized
    fun describe(bitmap: Bitmap, screenText: String): ImageDescription {
        val m = model()
        val image = m.encodeImage(pixels(bitmap))
        val objects = config.decode(m.generate(image, "objects"))
        val detailed = config.decode(m.generate(image, "detailed"))
        val first = FlorenceText.describe(caption = null, detailed = detailed, objectsRaw = objects, screenText = screenText)
        if (first.text.isNotBlank()) return first
        // Nothing of the detailed caption held up: the short caption, which rarely invents anything.
        val caption = config.decode(m.generate(image, "caption"))
        return FlorenceText.describe(caption, detailed, objects, screenText)
    }

    /** The whole screenshot squeezed to size x size (as Florence's processor does), normalised CHW. */
    private fun pixels(bitmap: Bitmap): FloatArray {
        val size = config.imageSize
        val target = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            draw(bitmap, target)
            val argb = IntArray(size * size)
            target.getPixels(argb, 0, size, 0, 0, size, size)
            return FloatArray(3 * size * size).also { PixelPreprocess.argbToChw(argb, size, config.mean, config.std, it) }
        } finally {
            target.recycle()
        }
    }

    @Synchronized
    override fun close() {
        model?.close()
        model = null
    }

    companion object {
        /** The shipped config (prompts, tokens and the files to download). */
        fun config(context: Context, withTokens: Boolean = true): FlorenceConfig {
            fun text(name: String) = context.assets.open("florence/$name").bufferedReader().use { it.readText() }
            return FlorenceConfig.parse(text("florence_config.json"), if (withTokens) text("tokens.json") else "[]")
        }

        /** [dir]: where the downloaded, checked model files are. */
        fun load(context: Context, dir: File): FlorenceDescriber {
            val cores = Runtime.getRuntime().availableProcessors()
            return FlorenceDescriber(dir, config(context), threads = max(1, min(4, cores / 2)))
        }

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        /** Scales [source] into [target], halving each side first while it's over twice the target, so text doesn't alias. */
        private fun draw(source: Bitmap, target: Bitmap) {
            var src = source
            val owned = ArrayList<Bitmap>()
            while (src.width >= target.width * 2 || src.height >= target.height * 2) {
                val w = if (src.width >= target.width * 2) src.width / 2 else src.width
                val h = if (src.height >= target.height * 2) src.height / 2 else src.height
                val half = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(half).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), paint)
                owned += half
                src = half
            }
            Canvas(target).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, target.width, target.height), paint)
            owned.forEach { it.recycle() }
        }
    }
}
