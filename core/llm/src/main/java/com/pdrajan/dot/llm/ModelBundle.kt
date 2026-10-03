package com.pdrajan.dot.llm

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File

/**
 * A model made of several files downloaded together (a vision model: language model + projector),
 * shown as one download with one progress bar. The files live in Download/AI Models.
 */
class ModelBundle(context: Context, val label: String, specs: List<ModelSpec>) {

    val parts: List<ModelDownloader> = specs.map { ModelDownloader(context, it) }

    val sizeBytes: Long = specs.sumOf { it.sizeBytes }

    val state: Flow<ModelDownloader.State> = combine(parts.map { it.state }) { combined(it.toList()) }

    fun currentState(): ModelDownloader.State = combined(parts.map { it.state.value })

    fun isReady(): Boolean = parts.all { it.isReady() }

    /** The downloaded files in [parts] order, or null until all are there. */
    fun files(): List<File>? = parts.map { it.file() ?: return null }

    /** Downloads the missing files one after the other; stops at the first that fails. */
    suspend fun download() {
        for (part in parts) {
            part.download()
            if (!part.isReady()) return
        }
    }

    fun refresh() = parts.forEach { it.refresh() }

    /** The other Dot app already downloaded it; this app just needs "All files access" to use that copy. */
    fun othersCopyLocked(): Boolean = parts.any { it.othersCopyLocked() }

    /** Bytes already on the phone: finished files plus interrupted downloads that will resume. */
    fun downloadedBytes(): Long = parts.sumOf { if (it.isReady()) it.spec.sizeBytes else it.partialBytes() }

    /** Where the files are, for people ("Download/AI Models"), or null until downloaded. */
    fun location(): String? = parts.firstNotNullOfOrNull { it.describeLocation() }

    private fun combined(states: List<ModelDownloader.State>): ModelDownloader.State {
        if (states.all { it == ModelDownloader.State.Ready }) return ModelDownloader.State.Ready
        val done = states.indices.sumOf { i ->
            when (val s = states[i]) {
                ModelDownloader.State.Ready -> parts[i].spec.sizeBytes
                is ModelDownloader.State.Downloading -> s.bytes
                is ModelDownloader.State.Failed -> s.bytes
                else -> parts[i].partialBytes()
            }
        }
        states.firstOrNull { it is ModelDownloader.State.Downloading }?.let { return ModelDownloader.State.Downloading(done, sizeBytes) }
        if (states.any { it == ModelDownloader.State.Verifying }) return ModelDownloader.State.Verifying
        states.firstOrNull { it is ModelDownloader.State.Failed }?.let { return ModelDownloader.State.Failed((it as ModelDownloader.State.Failed).message, done) }
        return ModelDownloader.State.Missing
    }
}
