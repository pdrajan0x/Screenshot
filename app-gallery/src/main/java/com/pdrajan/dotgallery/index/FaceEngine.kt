package com.pdrajan.dotgallery.index

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.pdrajan.dot.engine.FaceAlign
import com.pdrajan.dot.engine.Pt
import com.pdrajan.dot.engine.VectorMath
import com.pdrajan.dotgallery.data.FaceResult
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * People: ML Kit finds faces + landmarks, each face is aligned to the ArcFace template and
 * embedded with MobileFaceNet (ONNX Runtime). Faces become [FaceResult]s for clustering.
 */
class FaceEngine(private val context: Context) : Closeable {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.06f)
            .build(),
    )
    private val env = OrtEnvironment.getEnvironment().apply { runCatching { setTelemetry(false) } }
    private val config = JSONObject(context.assets.open("faces/faces_config.json").bufferedReader().use { it.readText() })
    private val inputName = config.optString("input_name", "input.1")
    private val size = config.optInt("size", 112)
    private val mean = config.optDouble("mean", 127.5).toFloat()
    private val std = config.optDouble("std", 127.5).toFloat()
    private var sessionRef: OrtSession? = null

    @Synchronized
    private fun session(): OrtSession = sessionRef ?: run {
        val buffer = context.assets.openFd("faces/face_embedder.onnx").use { fd ->
            FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length) }
        }
        env.createSession(buffer, OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }).also { sessionRef = it }
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun analyze(bitmap: Bitmap, mediaId: Long, thumbDir: File): List<FaceResult> {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
            .filter { usable(it, bitmap) }
            .sortedByDescending { it.boundingBox.width() }
            .take(MAX_FACES)
        if (faces.isEmpty()) return emptyList()

        val plane = size * size
        val input = FloatArray(faces.size * 3 * plane)
        val aligned = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(plane)
        faces.forEachIndexed { i, face ->
            align(bitmap, face, aligned)
            aligned.getPixels(pixels, 0, size, 0, 0, size, size)
            val off = i * 3 * plane
            for (p in 0 until plane) {
                val c = pixels[p]
                input[off + p] = (((c shr 16) and 0xff) - mean) / std
                input[off + plane + p] = (((c shr 8) and 0xff) - mean) / std
                input[off + 2 * plane + p] = ((c and 0xff) - mean) / std
            }
        }
        aligned.recycle()

        val embeddings: Array<FloatArray> = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(faces.size.toLong(), 3, size.toLong(), size.toLong())).use { t ->
            session().run(mapOf(inputName to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                out[0].value as Array<FloatArray>
            }
        }

        thumbDir.mkdirs()
        return faces.mapIndexed { i, face ->
            val box = face.boundingBox
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            FaceResult(
                left = (box.left / w).coerceIn(0f, 1f),
                top = (box.top / h).coerceIn(0f, 1f),
                right = (box.right / w).coerceIn(0f, 1f),
                bottom = (box.bottom / h).coerceIn(0f, 1f),
                quality = quality(face, bitmap),
                embedding = VectorMath.l2Normalize(embeddings[i]),
                thumbPath = saveThumb(bitmap, box, File(thumbDir, "$mediaId-$i.jpg")),
            )
        }
    }

    private fun usable(face: Face, bitmap: Bitmap): Boolean {
        val box = face.boundingBox
        if (min(box.width(), box.height()) < 36) return false
        if (abs(face.headEulerAngleY) > 45 || abs(face.headEulerAngleZ) > 40) return false
        return LANDMARKS.all { face.getLandmark(it) != null } && box.width() < bitmap.width * 1.2f
    }

    private fun quality(face: Face, bitmap: Bitmap): Float {
        val sizeScore = min(1f, face.boundingBox.width() / (0.25f * min(bitmap.width, bitmap.height)))
        val pose = (1 - abs(face.headEulerAngleY) / 90f) * (1 - abs(face.headEulerAngleZ) / 90f)
        return sizeScore * pose
    }

    private fun align(src: Bitmap, face: Face, target: Bitmap) {
        fun p(type: Int) = face.getLandmark(type)!!.position.let { Pt(it.x, it.y) }
        // Order eyes and mouth corners by x so it doesn't matter which side ML Kit calls "left".
        val eyes = listOf(p(FaceLandmark.LEFT_EYE), p(FaceLandmark.RIGHT_EYE)).sortedBy { it.x }
        val mouth = listOf(p(FaceLandmark.MOUTH_LEFT), p(FaceLandmark.MOUTH_RIGHT)).sortedBy { it.x }
        val t = FaceAlign.estimate(listOf(eyes[0], eyes[1], p(FaceLandmark.NOSE_BASE), mouth[0], mouth[1]))
        val m = Matrix().apply { setValues(t.toMatrixValues()) }
        val canvas = Canvas(target)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(src, m, paint)
    }

    private fun saveThumb(src: Bitmap, box: Rect, file: File): String? = runCatching {
        val side = (max(box.width(), box.height()) * 1.5f).toInt()
        val cx = box.centerX()
        val cy = box.centerY()
        val left = (cx - side / 2).coerceIn(0, max(0, src.width - 1))
        val top = (cy - side / 2).coerceIn(0, max(0, src.height - 1))
        val w = min(side, src.width - left)
        val h = min(side, src.height - top)
        val crop = Bitmap.createBitmap(src, left, top, w, h)
        val thumb = Bitmap.createScaledBitmap(crop, THUMB, THUMB * h / max(1, w), true)
        FileOutputStream(file).use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (crop !== src) crop.recycle()
        thumb.recycle()
        file.absolutePath
    }.getOrNull()

    @Synchronized
    override fun close() {
        detector.close()
        sessionRef?.close()
        sessionRef = null
    }

    companion object {
        private const val MAX_FACES = 12
        private const val THUMB = 160
        private val LANDMARKS = listOf(
            FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.NOSE_BASE, FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT,
        )

        fun isAvailable(context: Context): Boolean =
            runCatching { context.assets.openFd("faces/face_embedder.onnx").close(); true }.getOrDefault(false)
    }
}
