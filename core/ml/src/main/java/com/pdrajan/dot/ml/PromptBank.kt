package com.pdrajan.dot.ml

import android.content.Context
import com.pdrajan.dot.engine.AppLookClassifier
import com.pdrajan.dot.engine.AppRecognizer
import com.pdrajan.dot.engine.Categories
import com.pdrajan.dot.engine.CategoryClassifier
import com.pdrajan.dot.engine.PhotoTagger
import com.pdrajan.dot.engine.PhotoTags
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Text embeddings for fixed prompts (screenshot categories, photo tags), computed once with the
 * text encoder and cached on disk so indexing never has to load the text model again.
 */
object PromptBank {

    /** Screenshot categories (Dot Screenshots). */
    fun classifier(context: Context, clip: ClipModel): CategoryClassifier {
        val prompts: List<String> = Categories.ALL.flatMap { it.prompts } + Categories.BACKGROUND_PROMPTS
        val vectors = embed(context, clip, "categories", prompts)
        var i = 0
        val byCategory = LinkedHashMap<String, List<FloatArray>>()
        for (c in Categories.ALL) byCategory[c.id] = c.prompts.map { vectors[i++] }
        val background = Categories.BACKGROUND_PROMPTS.map { vectors[i++] }
        return CategoryClassifier(byCategory, background, clip.config.logitScale)
    }

    /** Photo "Things" tags (Dot Gallery). */
    fun photoTagger(context: Context, clip: ClipModel): PhotoTagger {
        val prompts = PhotoTags.ALL.flatMap { it.prompts } + PhotoTags.BACKGROUND
        val vectors = embed(context, clip, "phototags", prompts)
        var i = 0
        val byTag = LinkedHashMap<String, List<FloatArray>>()
        for (t in PhotoTags.ALL) byTag[t.id] = t.prompts.map { vectors[i++] }
        val background = PhotoTags.BACKGROUND.map { vectors[i++] }
        return PhotoTagger(byTag, background, clip.config.logitScale)
    }

    /** "Which app does this look like" (Dot Screenshots), for screenshots without usage history. */
    fun appLook(context: Context, clip: ClipModel): AppLookClassifier {
        val prompts = AppRecognizer.VISUAL.values.flatten()
        val vectors = embed(context, clip, "applook", prompts)
        var i = 0
        val byApp = LinkedHashMap<String, List<FloatArray>>()
        for ((app, list) in AppRecognizer.VISUAL) byApp[app] = list.map { vectors[i++] }
        return AppLookClassifier(byApp, clip.config.logitScale)
    }

    fun embed(context: Context, clip: ClipModel, name: String, prompts: List<String>): List<FloatArray> {
        val key = (clip.config.modelName + "\n" + prompts.joinToString("\n")).hashCode().toUInt().toString(16)
        val dir = File(context.noBackupFilesDir, "clip")
        val file = File(dir, "$name-$key.bin")
        read(file, prompts.size, clip.config.embedDim)?.let { return it }
        val computed = prompts.chunked(8).flatMap { clip.embedTexts(it) }
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith("$name-") }?.forEach { it.delete() }
        write(file, computed)
        return computed
    }

    private fun read(file: File, count: Int, dim: Int): List<FloatArray>? = runCatching {
        if (!file.exists()) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != count || input.readInt() != dim) return null
            List(count) { FloatArray(dim) { input.readFloat() } }
        }
    }.getOrNull()

    private fun write(file: File, vectors: List<FloatArray>) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(vectors.size)
            out.writeInt(vectors.first().size)
            vectors.forEach { v -> v.forEach { out.writeFloat(it) } }
        }
    }
}
