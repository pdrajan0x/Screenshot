package com.pdrajan.dotscreenshots.index

import android.content.Context
import android.content.Intent
import com.pdrajan.dot.engine.AppCandidate
import com.pdrajan.dot.engine.AppIdentifier
import com.pdrajan.dot.engine.AppPrompts
import com.pdrajan.dot.engine.AppRecognizer
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.ml.ForegroundAppResolver
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
 * Gives every screenshot a source app. Exact where Android still has the usage history (needs
 * Usage access); everywhere else the best guess from [AppIdentifier], which learns from the
 * screenshots whose app is certain and from the user's corrections.
 */
class AppIdentification(
    private val context: Context,
    private val repo: ShotsRepository,
    private val hub: ModelHub,
    val foreground: ForegroundAppResolver,
) {
    private val lock = Mutex()
    private val prefs = context.getSharedPreferences("app_identification", Context.MODE_PRIVATE)

    data class AppChoice(val label: String, val packageName: String?)

    /**
     * Matches from history, then guesses. Guesses are redone for the whole library when there is
     * much more to learn from (or [relearn]); otherwise only new screenshots are guessed.
     */
    suspend fun run(relearn: Boolean = false): Unit = lock.withLock {
        try {
            matchFromHistory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DotLog.w("apps: matching from usage history failed", e)
        }
        try {
            guess(relearn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DotLog.w("apps: guessing failed", e)
        }
        Unit
    }

    /**
     * Exact apps for every screenshot Android still has usage events for (usually a week or
     * more). Screenshots checked on an earlier run aren't checked again.
     */
    private suspend fun matchFromHistory(): Int {
        if (!foreground.hasAccess()) {
            // Look at everything again once access is (re)granted.
            prefs.edit().remove(KEY_HISTORY_CHECKED).apply()
            return 0
        }
        val now = System.currentTimeMillis()
        val checked = prefs.getLong(KEY_HISTORY_CHECKED, 0L)
        val since = maxOf(if (checked > 0) checked - DAY else 0L, now - MAX_HISTORY)
        val uncertain = repo.uncertainAppShots(since)
        prefs.edit().putLong(KEY_HISTORY_CHECKED, now).apply()
        if (uncertain.isEmpty()) return 0
        val from = uncertain.minOf { it.second } - 3 * 60 * 60_000L
        val timeline = withContext(Dispatchers.IO) { foreground.timeline(from, now) } ?: return 0
        if (timeline.isEmpty) return 0
        val found = uncertain.mapNotNull { (id, takenAt) -> timeline.appAt(takenAt)?.let { Triple(id, it.label, it.packageName) } }
        repo.setExactApps(found)
        if (found.isNotEmpty()) DotLog.i("apps: ${found.size} screenshots matched to their app from usage history")
        return found.size
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
        val usage = foreground.hasAccess()
        var afterId = Long.MIN_VALUE
        var guessed = 0
        var moved = 0
        while (true) {
            val batch = repo.guessTargets(all, afterId, BATCH)
            if (batch.isEmpty()) break
            val results = withContext(Dispatchers.Default) {
                batch.mapNotNull { t ->
                    identifier.identify(t.text, t.crops, t.takenAt, if (usage) foreground.usageMinutes(t.takenAt) else null)?.let { t.id to it }
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
        val launcher = foreground.launchers.firstOrNull()
        out += AppCandidate(ForegroundAppResolver.HOME_SCREEN, launcher, AppPrompts.forApp(ForegroundAppResolver.HOME_SCREEN, launcher, isLauncher = true))
        installed.forEach { out += AppCandidate(it.label, it.packageName, AppPrompts.forApp(it.label, it.packageName)) }
        val covered = installed.mapNotNull { AppRecognizer.canonical(it.label) }.toSet()
        (AppRecognizer.TEXT_APPS + AppRecognizer.VISUAL.keys.filterNot { it.startsWith("~") })
            .distinct()
            .filter { it !in covered }
            .forEach { out += AppCandidate(it, null, AppPrompts.forApp(it, null)) }
        return out
    }

    private fun installedApps(): List<AppChoice> = runCatching {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName && it !in foreground.launchers }
            .map { AppChoice(foreground.label(it), it) }
            .sortedBy { it.label.lowercase() }
    }.getOrElse {
        DotLog.w("apps: could not list installed apps", it)
        emptyList()
    }

    /** Apps to choose from when correcting a screenshot: the library's apps first, then installed ones. */
    suspend fun choices(): List<AppChoice> = withContext(Dispatchers.IO) {
        val installed = installedApps()
        val byLabel = installed.associateBy { it.label }
        val inLibrary = repo.appLabels().map { (label, _) -> byLabel[label] ?: AppChoice(label, null) }
        (inLibrary + AppChoice(ForegroundAppResolver.HOME_SCREEN, foreground.launchers.firstOrNull()) + installed)
            .distinctBy { it.label.lowercase() }
    }

    private companion object {
        const val BATCH = 300
        const val MAX_HISTORY = 60L * 24 * 60 * 60_000
        const val DAY = 24 * 60 * 60_000L
        const val KEY_HISTORY_CHECKED = "history_checked_at"
        const val KEY_KNOWN = "learned_from"
        const val KEY_CANDIDATES = "candidates"
    }
}
