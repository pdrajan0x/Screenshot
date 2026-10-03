package com.pdrajan.dotscreenshots.index

import android.content.Context
import com.pdrajan.dot.engine.AppCandidate
import com.pdrajan.dot.engine.AppIdentifier
import com.pdrajan.dot.engine.AppPrompts
import com.pdrajan.dot.engine.AppRecognizer
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.ml.InstalledApps
import com.pdrajan.dot.ml.PromptBank
import com.pdrajan.dotscreenshots.data.ShotsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gives every screenshot a source app: the phone maker's file name when it says, otherwise the best
 * guess from [AppIdentifier], which learns from the screenshots whose app is certain and from the
 * user's corrections. No special permission is needed.
 */
class AppIdentification(
    private val context: Context,
    private val repo: ShotsRepository,
    private val hub: ModelHub,
    private val installed: InstalledApps,
) {
    private val lock = Mutex()
    private val prefs = context.getSharedPreferences("app_identification", Context.MODE_PRIVATE)

    data class AppChoice(val label: String, val packageName: String?)

    /**
     * Guesses are redone for the whole library when there is much more to learn from (or
     * [relearn]); otherwise only new screenshots are guessed.
     */
    suspend fun run(relearn: Boolean = false) {
        lock.withLock {
            try {
                guess(relearn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DotLog.w("apps: guessing failed", e)
            }
        }
    }

    private suspend fun guess(relearn: Boolean): Int {
        val known = repo.knownAppShots()
        val candidates = candidates()
        val candidateKey = candidates.joinToString("|") { it.label }.hashCode()
        val learnedFrom = prefs.getInt(KEY_KNOWN, -1)
        val all = relearn || prefs.getInt(KEY_CANDIDATES, 0) != candidateKey ||
            known.size < learnedFrom || known.size >= learnedFrom + maxOf(5, learnedFrom / 10)
        if (!all && repo.guessTargets(all = false, afterId = Long.MIN_VALUE, limit = 1).isEmpty()) return 0
        val clip = hub.clip() ?: return 0

        val started = System.currentTimeMillis()
        val prompts = candidates.flatMap { it.prompts }.distinct()
        val vectors = withContext(Dispatchers.Default) { PromptBank.embedEach(context, clip, "apps", prompts) }
        clip.releaseText()
        val identifier = AppIdentifier(candidates, vectors, clip.config.logitScale, known)
        var afterId = Long.MIN_VALUE
        var guessed = 0
        var moved = 0
        while (true) {
            val batch = repo.guessTargets(all, afterId, BATCH)
            if (batch.isEmpty()) break
            val results = withContext(Dispatchers.Default) {
                batch.mapNotNull { t ->
                    identifier.identify(t.text, t.crops, t.takenAt)?.let { t.id to it }
                }
            }
            moved += repo.saveGuesses(results)
            guessed += results.size
            afterId = batch.last().id
            currentCoroutineContext().ensureActive()
        }
        prefs.edit().putInt(KEY_KNOWN, known.size).putInt(KEY_CANDIDATES, candidateKey).apply()
        DotLog.i(
            "apps: guessed $guessed screenshots (${if (all) "all" else "new only"}, $moved changed) from ${identifier.size} apps " +
                "and ${known.size} certain screenshots in ${System.currentTimeMillis() - started} ms",
        )
        return guessed
    }

    /** Installed apps, the home screen, and well-known apps that may have been installed before. */
    private fun candidates(): List<AppCandidate> {
        val installed = installedApps()
        val out = ArrayList<AppCandidate>()
        val launcher = this.installed.launchers.firstOrNull()
        out += AppCandidate(AppPrompts.HOME_SCREEN, launcher, AppPrompts.forApp(AppPrompts.HOME_SCREEN, launcher, isLauncher = true))
        installed.forEach { out += AppCandidate(it.label, it.packageName, AppPrompts.forApp(it.label, it.packageName)) }
        val covered = installed.mapNotNull { AppRecognizer.canonical(it.label) }.toSet()
        (AppRecognizer.TEXT_APPS + AppRecognizer.VISUAL.keys.filterNot { it.startsWith("~") })
            .distinct()
            .filter { it !in covered }
            .forEach { out += AppCandidate(it, null, AppPrompts.forApp(it, null)) }
        return out
    }

    private fun installedApps(): List<AppChoice> = runCatching {
        installed.launchable().map { AppChoice(it.label, it.packageName) }
    }.getOrElse {
        DotLog.w("apps: could not list installed apps", it)
        emptyList()
    }

    /** Apps to choose from when correcting a screenshot: the library's apps first, then installed ones. */
    suspend fun choices(): List<AppChoice> = withContext(Dispatchers.IO) {
        val installed = installedApps()
        val byLabel = installed.associateBy { it.label }
        val inLibrary = repo.appLabels().map { (label, _) -> byLabel[label] ?: AppChoice(label, null) }
        (inLibrary + AppChoice(AppPrompts.HOME_SCREEN, this.installed.launchers.firstOrNull()) + installed)
            .distinctBy { it.label.lowercase() }
    }

    private companion object {
        const val BATCH = 300
        const val KEY_KNOWN = "learned_from"
        const val KEY_CANDIDATES = "candidates"
    }
}
