package com.pdrajan.dot.engine

import kotlin.math.sqrt

/** A 2D point in image pixels. */
data class Pt(val x: Float, val y: Float)

/**
 * Similarity transform `dst = s·R·src + t`, stored as the 2×3 matrix
 * `[a -b tx; b a ty]` where `a = s·cosθ`, `b = s·sinθ`.
 */
data class Similarity(val a: Float, val b: Float, val tx: Float, val ty: Float) {
    fun apply(p: Pt) = Pt(a * p.x - b * p.y + tx, b * p.x + a * p.y + ty)

    /** Row-major 3×3 values (for android.graphics.Matrix.setValues). */
    fun toMatrixValues(): FloatArray = floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f)
}

object FaceAlign {
    /**
     * ArcFace's canonical 112×112 positions for: image-left eye, image-right eye, nose tip,
     * image-left mouth corner, image-right mouth corner.
     */
    val ARCFACE_112 = listOf(
        Pt(38.2946f, 51.6963f),
        Pt(73.5318f, 51.5014f),
        Pt(56.0252f, 71.7366f),
        Pt(41.5493f, 92.3655f),
        Pt(70.7299f, 92.2041f),
    )

    /**
     * Least-squares similarity transform mapping [src] onto [dst] (Umeyama 1991, 2D closed form).
     * Used to warp a detected face so its landmarks land on [ARCFACE_112].
     */
    fun estimate(src: List<Pt>, dst: List<Pt> = ARCFACE_112): Similarity {
        require(src.size == dst.size && src.size >= 2)
        val n = src.size
        val sx = src.sumOf { it.x.toDouble() } / n
        val sy = src.sumOf { it.y.toDouble() } / n
        val dx = dst.sumOf { it.x.toDouble() } / n
        val dy = dst.sumOf { it.y.toDouble() } / n
        var num1 = 0.0 // Σ (x'·u + y'·v)
        var num2 = 0.0 // Σ (x'·v − y'·u)
        var den = 0.0  // Σ (x'² + y'²)
        for (i in 0 until n) {
            val x = src[i].x - sx
            val y = src[i].y - sy
            val u = dst[i].x - dx
            val v = dst[i].y - dy
            num1 += x * u + y * v
            num2 += x * v - y * u
            den += x * x + y * y
        }
        if (den == 0.0) return Similarity(1f, 0f, (dx - sx).toFloat(), (dy - sy).toFloat())
        val a = num1 / den
        val b = num2 / den
        val tx = dx - (a * sx - b * sy)
        val ty = dy - (b * sx + a * sy)
        return Similarity(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat())
    }

    /** Scale factor of a transform; tiny faces (large upscaling) make poor embeddings. */
    fun scale(t: Similarity): Float = sqrt(t.a * t.a + t.b * t.b)
}

/**
 * Greedy online clustering of face embeddings into people. Each person keeps a normalised
 * running-mean centroid; a face joins the closest person above [threshold], otherwise it
 * starts a new one.
 */
class FaceClusterer(private val threshold: Float = 0.42f) {

    data class Person(val id: Long, var centroid: FloatArray, var count: Int)

    fun assign(embedding: FloatArray, people: List<Person>): Person? {
        var best: Person? = null
        var bestScore = threshold
        for (p in people) {
            val s = VectorMath.dot(embedding, p.centroid)
            if (s > bestScore) {
                bestScore = s
                best = p
            }
        }
        return best
    }

    fun absorb(person: Person, embedding: FloatArray) {
        val n = person.count
        val merged = FloatArray(embedding.size) { (person.centroid[it] * n + embedding[it]) / (n + 1) }
        person.centroid = VectorMath.l2Normalize(merged)
        person.count = n + 1
    }
}
