package com.pdrajan.dot.engine

object PixelPreprocess {
    /**
     * Writes ARGB pixels of a `size × size` image into [out] at [offset] as planar RGB floats:
     * `(channel / 255 - mean) / std` (Florence-2: ImageNet mean and std).
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
