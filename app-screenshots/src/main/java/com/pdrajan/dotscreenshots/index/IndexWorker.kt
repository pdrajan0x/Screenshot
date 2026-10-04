package com.pdrajan.dotscreenshots.index

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dotscreenshots.DotScreenshotsApp
import java.time.Duration

/**
 * Background indexing. Two flavours:
 *  - "new": wakes when MediaStore images change (no polling), handles the latest screenshots.
 *  - "backlog": older screenshots, following Settings → Processing.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as DotScreenshotsApp).container
        val mode = inputData.getString(KEY_MODE) ?: MODE_NEW
        DotLog.i("worker: $mode run started")
        val deadline = System.currentTimeMillis() + 8 * 60_000L

        runCatching { container.engine.sync() }

        // Settings → Processing: background work at all, and on battery only above the chosen level.
        // Screenshots are read a few at a time, newest first.
        val power = container.power
        val blocker = power.blocker(foreground = false)
        val stopped = { isStopped || power.blocker(foreground = false) != null }
        var progressed = 0
        if (blocker == null) {
            while (!stopped() && System.currentTimeMillis() < deadline) {
                val n = container.processStep(userAsked = false, foreground = false, deadline = deadline, isStopped = stopped)
                if (n == 0) break
                progressed += n
            }
        }

        val counts = container.repo.counts()
        val toRead = counts.pending + counts.updating
        val scheduler = container.scheduler
        if (mode == MODE_NEW) scheduler.watchForNewScreenshots(afterCurrent = true)
        // Only chain another backlog run when this one got somewhere; a run that did nothing
        // (a persistent error) waits for the next app open or screenshot instead of looping.
        if ((progressed > 0 || mode == MODE_NEW) && toRead > 0 && container.settings.processing.value.background) {
            scheduler.scheduleBacklog(afterCurrent = mode == MODE_BACKLOG)
        }
        DotLog.i(
            "worker: $mode run finished · $progressed items moved forward" +
                (blocker?.let { " (paused: $it)" } ?: "") + " · $toRead to read",
        )
        return Result.success()
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_NEW = "new"
        const val MODE_BACKLOG = "backlog"
    }
}

class IndexScheduler(private val context: Context, private val chargingOnly: () -> Boolean) {

    private val workManager get() = WorkManager.getInstance(context)

    /** One-shot job that fires on the next MediaStore image change. Re-armed after each run. */
    fun watchForNewScreenshots(afterCurrent: Boolean = false) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(3))
            .setTriggerContentMaxDelay(Duration.ofSeconds(30))
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(IndexWorker.KEY_MODE to IndexWorker.MODE_NEW))
            .build()
        workManager.enqueueUniqueWork(
            WORK_WATCH,
            if (afterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Older screenshots; waits for the charger unless processing on battery is allowed. */
    fun scheduleBacklog(afterCurrent: Boolean = false, requireCharging: Boolean = chargingOnly()) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(requireCharging)
            .build()
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(IndexWorker.KEY_MODE to IndexWorker.MODE_BACKLOG))
            .build()
        workManager.enqueueUniqueWork(
            WORK_BACKLOG,
            if (afterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Settings changed (e.g. the charging rule): replace the queued backlog job. */
    fun rescheduleBacklog() {
        workManager.cancelUniqueWork(WORK_BACKLOG)
        scheduleBacklog()
    }

    fun cancelBacklog() {
        workManager.cancelUniqueWork(WORK_BACKLOG)
    }

    private companion object {
        const val WORK_WATCH = "watch-new-screenshots"
        const val WORK_BACKLOG = "index-backlog"
    }
}
