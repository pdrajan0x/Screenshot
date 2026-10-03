package com.pdrajan.dotgallery.index

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
import com.pdrajan.dotgallery.GalleryApp
import java.time.Duration

/** Same scheme as Dot Screenshots: new media on change, then the rest, following Settings → Processing. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as GalleryApp).container
        val mode = inputData.getString(KEY_MODE) ?: MODE_NEW
        DotLog.i("worker: $mode run started")
        val deadline = System.currentTimeMillis() + 8 * 60_000L
        runCatching { c.engine.sync() }
        val power = c.power
        // Settings → Processing: background work at all, and on battery only above the chosen level.
        // Photos are finished a few at a time (analysis, then description and keywords), newest first.
        val blocker = power.blocker(foreground = false)
        val stopped = { isStopped || power.blocker(foreground = false) != null }
        var progressed = 0
        if (blocker == null) {
            while (!stopped() && System.currentTimeMillis() < deadline) {
                val n = c.processStep(userAsked = false, foreground = false, deadline = deadline, isStopped = stopped)
                if (n == 0) break
                progressed += n
            }
        }
        if (mode == MODE_NEW) c.scheduler.watchForNewMedia(afterCurrent = true)
        // Only chain another backlog run when this one got somewhere; a run that did nothing
        // waits for the next app open or new photo instead of looping.
        val toRead = c.repo.counts().pending
        val toDescribe = if (c.describer.available) c.repo.captionCounts().second else 0
        if ((progressed > 0 || mode == MODE_NEW) && (toRead > 0 || toDescribe > 0) && c.settings.processing.value.background) {
            c.scheduler.scheduleBacklog(afterCurrent = mode == MODE_BACKLOG)
        }
        DotLog.i(
            "worker: $mode run finished · $progressed items moved forward" +
                (blocker?.let { " (paused: $it)" } ?: "") + " · $toRead to analyse, $toDescribe to describe",
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
    private val wm get() = WorkManager.getInstance(context)

    fun watchForNewMedia(afterCurrent: Boolean = false) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
            .setTriggerContentMaxDelay(Duration.ofSeconds(60))
            .setRequiresBatteryNotLow(true)
            .build()
        wm.enqueueUniqueWork(
            "gallery-watch",
            if (afterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(IndexWorker.KEY_MODE to IndexWorker.MODE_NEW))
                .build(),
        )
    }

    /** Older media and AI descriptions; waits for the charger unless processing on battery is allowed. */
    fun scheduleBacklog(afterCurrent: Boolean = false, requireCharging: Boolean = chargingOnly()) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(requireCharging)
            .build()
        wm.enqueueUniqueWork(
            "gallery-backlog",
            if (afterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(IndexWorker.KEY_MODE to IndexWorker.MODE_BACKLOG))
                .build(),
        )
    }

    fun rescheduleBacklog() {
        wm.cancelUniqueWork("gallery-backlog")
        scheduleBacklog()
    }

    fun cancelBacklog() {
        wm.cancelUniqueWork("gallery-backlog")
    }
}
