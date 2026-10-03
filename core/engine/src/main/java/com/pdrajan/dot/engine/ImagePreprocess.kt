package com.pdrajan.dot.engine

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A square region of the source image, in source pixels. */
data class CropRect(val left: Int, val top: Int, val size: Int)

object CropPlanner {

    /**
     * CLIP sees a square. A tall screenshot (e.g. 1080×2400) centre-cropped to a square loses its
     * top and bottom, so long images are covered by up to [maxCrops] evenly spaced squares instead.
     * Near-square images (≤ 1.35:1) get a single centre crop, matching CLIP's own preprocessing.
     */
    fun squareCrops(width: Int, height: Int, maxCrops: Int = 3): List<CropRect> {
        require(width > 0 && height > 0)
        val side = min(width, height)
        val long = max(width, height)
        val ratio = long.toFloat() / side
        val n = if (ratio <= 1.35f) 1 else min(maxCrops, max(2, ceil(ratio - 0.2f).toInt()))
        val offsets = if (n == 1) {
            listOf((long - side) / 2)
        } else {
            val step = (long - side).toFloat() / (n - 1)
            List(n) { i -> (i * step).roundToInt() }
        }
        return offsets.map { off ->
            if (height >= width) CropRect(0, off, side) else CropRect(off, 0, side)
        }
    }
}

object PixelPreprocess {
    /**
     * Writes ARGB pixels of a `size × size` image into [out] at [offset] as planar RGB floats:
     * `(channel / 255 - mean) / std`. MobileCLIP uses mean 0 / std 1.
     */
    fun argbToChw(
        pixels: IntArray,
        size: Int,
        mean: FloatArray,
        std: FloatArray,
        out: FloatArray,
        offset: Int = 0,
    ) {
        val plane = size * size
        require(pixels.size >= plane && out.size >= offset + 3 * plane)
        val rScale = 1f / (255f * std[0]); val rShift = mean[0] / std[0]
        val gScale = 1f / (255f * std[1]); val gShift = mean[1] / std[1]
        val bScale = 1f / (255f * std[2]); val bShift = mean[2] / std[2]
        for (i in 0 until plane) {
            val p = pixels[i]
            out[offset + i] = ((p shr 16) and 0xff) * rScale - rShift
            out[offset + plane + i] = ((p shr 8) and 0xff) * gScale - gShift
            out[offset + 2 * plane + i] = (p and 0xff) * bScale - bShift
        }
    }
}
