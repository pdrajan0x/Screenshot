package com.pdrajan.dot.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

object VectorMath {
    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum).toFloat()
        if (norm == 0f) return v
        return FloatArray(v.size) { v[it] / norm }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun mean(vectors: List<FloatArray>): FloatArray {
        val out = FloatArray(vectors.first().size)
        for (v in vectors) for (i in out.indices) out[i] += v[i]
        return l2Normalize(out)
    }
}

/**
 * Symmetric int8 quantisation of an embedding: `v ≈ bytes * scale`.
 * 512 floats (2 KB) become 512 bytes + 4, with negligible effect on cosine ranking.
 */
class QuantizedVector(val bytes: ByteArray, val scale: Float) {

    fun dot(query: FloatArray): Float {
        var s = 0f
        for (i in bytes.indices) s += query[i] * bytes[i]
        return s * scale
    }

    fun toFloats(): FloatArray = FloatArray(bytes.size) { bytes[it] * scale }

    fun toBlob(): ByteArray =
        ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).putFloat(scale).put(bytes).array()

    companion object {
        fun of(v: FloatArray): QuantizedVector {
            var maxAbs = 0f
            for (x in v) maxAbs = maxOf(maxAbs, abs(x))
            val scale = if (maxAbs == 0f) 1f else maxAbs / 127f
            return QuantizedVector(ByteArray(v.size) { (v[it] / scale).let { q -> Math.round(q).coerceIn(-127, 127).toByte() } }, scale)
        }

        fun fromBlob(blob: ByteArray): QuantizedVector {
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val scale = buf.float
            val bytes = ByteArray(blob.size - 4)
            buf.get(bytes)
            return QuantizedVector(bytes, scale)
        }
    }
}

data class VectorHit(val id: Long, val score: Float)

/**
 * In-memory brute-force index. Each item holds one vector per image crop; an item's score is
 * the best crop's cosine similarity. 10k screenshots × 3 crops scan in a few tens of ms.
 */
class VectorIndex {
    private val items = ConcurrentHashMap<Long, Array<QuantizedVector>>()

    val size: Int get() = items.size

    fun put(id: Long, crops: List<QuantizedVector>) {
        if (crops.isEmpty()) items.remove(id) else items[id] = crops.toTypedArray()
    }

    fun remove(id: Long) {
        items.remove(id)
    }

    fun clear() = items.clear()

    fun contains(id: Long) = items.containsKey(id)

    fun vectorsOf(id: Long): List<FloatArray>? = items[id]?.map { it.toFloats() }

    fun search(query: FloatArray, topK: Int, filter: ((Long) -> Boolean)? = null): List<VectorHit> {
        val heap = PriorityQueue<VectorHit>(topK + 1, compareBy { it.score })
        for ((id, crops) in items) {
            if (filter != null && !filter(id)) continue
            var best = Float.NEGATIVE_INFINITY
            for (c in crops) {
                val s = c.dot(query)
                if (s > best) best = s
            }
            if (heap.size < topK) {
                heap.add(VectorHit(id, best))
            } else if (best > heap.peek().score) {
                heap.poll()
                heap.add(VectorHit(id, best))
            }
        }
        return heap.sortedByDescending { it.score }
    }

    /** Items that look like [id], using the mean of its crops as the query. */
    fun similarTo(id: Long, topK: Int): List<VectorHit> {
        val source = vectorsOf(id) ?: return emptyList()
        val query = VectorMath.mean(source)
        return search(query, topK + 1) { it != id }.take(topK)
    }
}
