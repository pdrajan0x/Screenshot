package com.pdrajan.dot.ml

import android.content.Context
import com.pdrajan.dot.engine.Categories
import com.pdrajan.dot.engine.CategoryClassifier
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Text embeddings for the category prompts, computed once with the text encoder and cached on
 * disk so indexing never has to load the text model.
 */
object PromptBank {

    fun classifier(context: Context, clip: ClipModel): CategoryClassifier {
        val prompts: List<String> = Categories.ALL.flatMap { it.prompts } + Categories.BACKGROUND_PROMPTS
        val key = (clip.config.modelName + "\n" + prompts.joinToString("\n")).hashCode().toUInt().toString(16)
        val file = File(context.noBackupFilesDir, "clip/prompts-$key.bin")

        val vectors: List<FloatArray> = read(file, prompts.size, clip.config.embedDim) ?: run {
            val computed = prompts.chunked(8).flatMap { clip.embedTexts(it) }
            write(file, computed)
            computed
        }

        var i = 0
        val byCategory = LinkedHashMap<String, List<FloatArray>>()
        for (c in Categories.ALL) {
            byCategory[c.id] = c.prompts.map { vectors[i++] }
        }
        val background = Categories.BACKGROUND_PROMPTS.map { vectors[i++] }
        return CategoryClassifier(byCategory, background, clip.config.logitScale)
    }

    private fun read(file: File, count: Int, dim: Int): List<FloatArray>? = runCatching {
        if (!file.exists()) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != count || input.readInt() != dim) return null
            List(count) { FloatArray(dim) { input.readFloat() } }
        }
    }.getOrNull()

    private fun write(file: File, vectors: List<FloatArray>) {
        file.parentFile?.mkdirs()
        file.parentFile?.listFiles { f -> f.name.startsWith("prompts-") }?.forEach { it.delete() }
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(vectors.size)
            out.writeInt(vectors.first().size)
            vectors.forEach { v -> v.forEach { out.writeFloat(it) } }
        }
    }
}
