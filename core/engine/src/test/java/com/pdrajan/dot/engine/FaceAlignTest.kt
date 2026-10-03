package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FaceAlignTest {

    @Test
    fun recoversAKnownSimilarity() {
        val theta = 0.3
        val s = 2.5f
        val truth = Similarity((s * cos(theta)).toFloat(), (s * sin(theta)).toFloat(), 40f, -12f)
        val src = FaceAlign.ARCFACE_112
        val dst = src.map { truth.apply(it) }
        val est = FaceAlign.estimate(src, dst)
        assertEquals(truth.a, est.a, 1e-4f)
        assertEquals(truth.b, est.b, 1e-4f)
        assertEquals(truth.tx, est.tx, 1e-2f)
        assertEquals(truth.ty, est.ty, 1e-2f)
        assertEquals(2.5f, FaceAlign.scale(est), 1e-4f)
    }

    @Test
    fun mapsDetectedLandmarksOntoTemplate() {
        // A face twice the template size, shifted: alignment should land on the template.
        val detected = FaceAlign.ARCFACE_112.map { Pt(it.x * 2 + 100, it.y * 2 + 50) }
        val t = FaceAlign.estimate(detected)
        detected.zip(FaceAlign.ARCFACE_112).forEach { (d, target) ->
            val p = t.apply(d)
            assertEquals(target.x, p.x, 1e-3f)
            assertEquals(target.y, p.y, 1e-3f)
        }
    }

    @Test
    fun clustererGroupsSimilarFaces() {
        val clusterer = FaceClusterer(threshold = 0.6f)
        val a = VectorMath.l2Normalize(floatArrayOf(1f, 0.1f, 0f))
        val aPrime = VectorMath.l2Normalize(floatArrayOf(0.95f, 0.15f, 0.05f))
        val b = VectorMath.l2Normalize(floatArrayOf(0f, 0.2f, 1f))
        val people = mutableListOf(FaceClusterer.Person(1, a, 1))
        val match = clusterer.assign(aPrime, people)
        assertNotNull(match)
        clusterer.absorb(match!!, aPrime)
        assertEquals(2, match.count)
        assertNull(clusterer.assign(b, people))
    }
}
