package com.pdrajan.dotgallery.index

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.pdrajan.dot.engine.ImageQuality
import com.pdrajan.dot.engine.PhotoTagger
import com.pdrajan.dot.engine.PhotoTags
import com.pdrajan.dot.ml.BitmapLoader
import com.pdrajan.dot.ml.ClipModel
import com.pdrajan.dot.ml.TextReader
import com.pdrajan.dotgallery.data.GalleryAnalysis
import com.pdrajan.dotgallery.data.GalleryRepository
import java.io.File

/**
 * One photo or video → CLIP embeddings, "Things" tags, perceptual hash, blur score, faces, and
 * (only for text-heavy photos, to save battery) OCR.
 */
class GalleryAnalyzer(
    private val context: Context,
    private val clip: ClipModel,
    private val tagger: PhotoTagger,
    private val faces: FaceEngine?,
    private val reader: TextReader?,
) {
    private val thumbDir = File(context.filesDir, "faces")

    fun analyze(item: GalleryRepository.Pending): GalleryAnalysis {
        val bitmap = if (item.isVideo) videoFrame(item.uri) else BitmapLoader.load(context.contentResolver, item.uri, maxWidth = 1600, maxPixels = 4_000_000)
        try {
            val crops = clip.embedImage(bitmap, maxCrops = 2)
            val tags = tagger.tags(crops)
            val isScreenshot = item.path?.contains("Screenshot", ignoreCase = true) == true
            val wantsText = !item.isVideo && reader != null && (isScreenshot || tags.any { it in PhotoTags.TEXT_HEAVY })
            return GalleryAnalysis(
                cropEmbeddings = crops,
                tags = if (isScreenshot && "screenshot" !in tags) tags + "screenshot" else tags,
                ocrText = if (wantsText) runCatching { reader!!.read(bitmap) }.getOrDefault("") else "",
                dHash = dHash(bitmap),
                sharpness = if (item.isVideo) null else sharpness(bitmap),
                faces = if (!item.isVideo && faces != null) runCatching { faces.analyze(bitmap, item.id, thumbDir) }.getOrDefault(emptyList()) else emptyList(),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun videoFrame(uri: Uri): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1024, 1024)
            } else {
                retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: error("no frame")
            return if (frame.config == Bitmap.Config.ARGB_8888) frame else frame.copy(Bitmap.Config.ARGB_8888, false).also { frame.recycle() }
        } finally {
            retriever.release()
        }
    }

    private fun dHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val px = IntArray(72)
        small.getPixels(px, 0, 9, 0, 0, 9, 8)
        if (small !== bitmap) small.recycle()
        return ImageQuality.dHash(IntArray(72) { ImageQuality.luma(px[it]) })
    }

    private fun sharpness(bitmap: Bitmap): Double {
        val w = 256
        val h = (bitmap.height * w / bitmap.width.toFloat()).toInt().coerceIn(16, 1024)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        return ImageQuality.sharpness(IntArray(px.size) { ImageQuality.luma(px[it]) }, w, h)
    }
}
