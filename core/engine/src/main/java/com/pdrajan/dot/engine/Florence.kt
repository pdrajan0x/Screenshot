package com.pdrajan.dot.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** assets/florence/florence_config.json and tokens.json, written by tools/model/fetch_florence.py. */
class FlorenceConfig(
    val imageSize: Int,
    val mean: FloatArray,
    val std: FloatArray,
    val decoderStart: Int,
    val eos: Int,
    val special: Set<Int>,
    val maxTokens: Int,
    /** Token ids of each task's prompt ("caption", "detailed", "objects"). */
    val prompts: Map<String, LongArray>,
    /** Byte-level BPE string of each token id. */
    val tokens: List<String>,
) {
    companion object {
        fun parse(configJson: String, tokensJson: String): FlorenceConfig {
            val c = JSONObject(configJson)
            fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
            val p = c.getJSONObject("prompts")
            val t = JSONArray(tokensJson)
            return FlorenceConfig(
                imageSize = c.getInt("image_size"),
                mean = floats(c.getJSONArray("mean")),
                std = floats(c.getJSONArray("std")),
                decoderStart = c.getInt("decoder_start"),
                eos = c.getInt("eos"),
                special = c.getJSONArray("special").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() },
                maxTokens = c.optInt("max_tokens", 120),
                prompts = p.keys().asSequence().associateWith { k -> p.getJSONArray(k).let { a -> LongArray(a.length()) { a.getLong(it) } } },
                tokens = List(t.length()) { t.getString(it) },
            )
        }
    }

    /** Text of [ids]; special tokens (<s>, </s>…) dropped, location tokens kept. */
    fun decode(ids: List<Int>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id in special) continue
            for (ch in tokens.getOrElse(id) { "" }) {
                val b: Int? = BYTE_OF[ch]
                if (b != null) bytes.write(b) else ch.toString().toByteArray().let { bytes.write(it, 0, it.size) }
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}

/** GPT-2's byte-to-character table, reversed: each character in a token stands for one byte. */
private val BYTE_OF: Map<Char, Int> = run {
    val bs = ((33..126) + (161..172) + (174..255)).toMutableList()
    val cs = bs.toMutableList()
    var n = 0
    for (b in 0..255) if (b !in bs) {
        bs += b
        cs += 256 + n++
    }
    bs.indices.associate { cs[it].toChar() to bs[it] }
}

/**
 * Florence-2 on ONNX Runtime: the picture once ([encodeImage]), then a few short answers about it
 * ([generate]: caption, detailed caption, objects), each by greedy decoding that never repeats a
 * 3-token sequence, exactly like tools/model/fetch_florence.py does in Python.
 */
class FlorenceModel(
    private val env: OrtEnvironment,
    private val vision: OrtSession,
    private val embed: OrtSession,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    val config: FlorenceConfig,
) : Closeable {

    /** The picture as the model sees it: [tokens] x [dim] features. */
    class ImageFeatures(val values: FloatArray, val tokens: Int, val dim: Int)

    private val pastInputs: Map<String, LongArray> = decoder.inputInfo.filterKeys { it.startsWith("past_key_values") }.mapValues { (_, info) ->
        val shape = (info.info as TensorInfo).shape
        longArrayOf(1, shape[1].takeIf { it > 0 } ?: 12, 0, shape[3].takeIf { it > 0 } ?: 64)
    }

    /** [pixels]: 3 x size x size, normalised (see [config]). */
    fun encodeImage(pixels: FloatArray): ImageFeatures {
        val size = config.imageSize.toLong()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, size, size)).use { input ->
            vision.run(mapOf("pixel_values" to input)).use { out ->
                val t = out[0] as OnnxTensor
                val shape = t.info.shape
                return ImageFeatures(t.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }, shape[1].toInt(), shape[2].toInt())
            }
        }
    }

    private fun embedTokens(ids: LongArray): FloatArray =
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
            embed.run(mapOf("input_ids" to input)).use { out ->
                (out[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
            }
        }

    /** Token ids answering [task] ("caption", "detailed", "objects") about [image]. */
    fun generate(image: ImageFeatures, task: String): List<Int> {
        val prompt = config.prompts[task] ?: error("unknown task $task")
        val dim = image.dim
        val text = embedTokens(prompt)
        val length = image.tokens + prompt.size
        val inputs = FloatArray(length * dim).also {
            image.values.copyInto(it)
            text.copyInto(it, image.values.size)
        }
        val maskValues = LongArray(length) { 1 }
        val closeables = ArrayList<AutoCloseable>()
        var previous: OrtSession.Result? = null
        try {
            val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(maskValues), longArrayOf(1, length.toLong())).also { closeables += it }
            val hidden: OnnxTensor
            OnnxTensor.createTensor(env, FloatBuffer.wrap(inputs), longArrayOf(1, length.toLong(), dim.toLong())).use { emb ->
                val result = encoder.run(mapOf("inputs_embeds" to emb, "attention_mask" to mask)).also { closeables += it }
                hidden = result[0] as OnnxTensor
            }
            val past = HashMap<String, OnnxTensor>()
            for ((name, shape) in pastInputs) {
                past[name] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), shape).also { closeables += it }
            }
            val out = ArrayList<Int>()
            var token = config.decoderStart
            for (step in 0 until config.maxTokens) {
                val stepInputs = HashMap<String, OnnxTensor>(past)
                val tokenEmbed = OnnxTensor.createTensor(env, FloatBuffer.wrap(embedTokens(longArrayOf(token.toLong()))), longArrayOf(1, 1, dim.toLong()))
                val useCache = OnnxTensor.createTensor(env, booleanArrayOf(step > 0))
                stepInputs["inputs_embeds"] = tokenEmbed
                stepInputs["encoder_hidden_states"] = hidden
                stepInputs["encoder_attention_mask"] = mask
                stepInputs["use_cache_branch"] = useCache
                val result = try {
                    decoder.run(stepInputs.filterKeys { it in decoder.inputNames })
                } finally {
                    tokenEmbed.close()
                    useCache.close()
                }
                val logitsTensor = result.get("logits").get() as OnnxTensor
                val logits = logitsTensor.floatBuffer.let { b ->
                    val vocab = logitsTensor.info.shape.last().toInt()
                    FloatArray(vocab).also { b.position(b.limit() - vocab); b.get(it) }
                }
                token = nextToken(logits, out)
                for (name in pastInputs.keys) {
                    val present = result.get(name.replace("past_key_values", "present"))
                    // The encoder's keys/values come from the first step only.
                    if (present.isPresent && (step == 0 || ".decoder." in name)) past[name] = present.get() as OnnxTensor
                }
                // Step 0 holds the encoder keys/values for the whole answer; later steps only replace the decoder's.
                if (step == 0) closeables += result else previous?.close()
                previous = if (step == 0) null else result
                if (token == config.eos) break
                out += token
            }
            return out
        } finally {
            runCatching { previous?.close() }
            closeables.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun nextToken(logits: FloatArray, out: List<Int>): Int {
        val seq = listOf(config.decoderStart) + out
        for (i in 0 until seq.size - 2) {
            if (seq[i] == seq[seq.size - 2] && seq[i + 1] == seq[seq.size - 1]) logits[seq[i + 2]] = -1e9f
        }
        var best = 0
        for (i in logits.indices) if (logits[i] > logits[best]) best = i
        return best
    }

    override fun close() {
        listOf(vision, embed, encoder, decoder).forEach { runCatching { it.close() } }
    }
}

/** What Florence says about a screenshot, after checking it against the screen's own text. */
data class ImageDescription(val text: String, val objects: List<String>)

/**
 * Turns Florence's raw answers into what the app shows. On pictures (it found real objects) the
 * detailed caption is accurate; on text-only screens it tends to invent what the text says, so
 * there only sentences quoting words really on the screen are kept, else the short caption.
 */
object FlorenceText {

    private val LOCATION = Regex("(?:<loc_\\d+>)+")
    private val QUOTED = Regex("[\"“”]([^\"“”]{2,})[\"“”]")
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** Labels that say nothing about a screenshot (every one is a phone screen). */
    private val GENERIC = setOf("mobile phone", "telephone", "screenshot", "text", "human face", "computer monitor", "display device")

    /** Object labels from an "objects" answer ("footwear<loc_1>…cricket ball<loc_…>"), first seen first. */
    fun objects(raw: String): List<String> =
        raw.split(LOCATION).map { it.trim(' ', '.', ',').lowercase() }.filter { it.isNotEmpty() }.distinct()

    fun describe(caption: String?, detailed: String?, objectsRaw: String?, screenText: String): ImageDescription {
        val objects = objects(objectsRaw.orEmpty()).filter { it !in GENERIC }
        val sentences = detailed.orEmpty().trim().split(SENTENCE_END).filter { it.isNotBlank() }
        val text = if (objects.isNotEmpty()) {
            // A picture: keep the detailed caption, minus any sentence quoting words not on the screen.
            sentences.filter { s -> QUOTED.findAll(s).all { onScreen(it.groupValues[1], screenText) } }.joinToString(" ")
        } else {
            sentences.filter { s -> QUOTED.findAll(s).toList().let { q -> q.isNotEmpty() && q.all { onScreen(it.groupValues[1], screenText) } } }
                .joinToString(" ")
        }.ifBlank { caption.orEmpty().trim() }
        return ImageDescription(sentenceCase(text), objects)
    }

    /** Whether most words of a quote appear on the screen (OCR spells some words differently). */
    fun onScreen(quote: String, screenText: String): Boolean {
        val words = WORD.findAll(quote.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return false
        val screen = WORD.findAll(screenText.lowercase()).map { it.value }.toSet()
        return words.count { it in screen } >= (words.size * 0.7).toInt().coerceAtLeast(1)
    }

    private fun sentenceCase(s: String) = s.trim().replaceFirstChar { it.uppercaseChar() }
}
