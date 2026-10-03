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

/** Same scheme as Dot Screenshots: new media on change, the backlog while charging. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as GalleryApp).container
        val mode = inputData.getString(KEY_MODE) ?: MODE_NEW
        DotLog.i("worker: $mode run started")
        val deadline = System.currentTimeMillis() + 8 * 60_000L
        runCatching { c.engine.sync() }
        // Photo descriptions never run in the background on battery: they wait for the charger
        // (or for the app to be opened), and unplugging stops them mid-batch.
        val power = c.power
        val describeStopped = { isStopped || !power.isCharging }
        var indexed = 0
        var described = 0
        if (mode == MODE_NEW) {
            indexed = c.engine.process(limit = 40, deadline = deadline) { isStopped }
            if (power.isCharging) {
                described = c.describer.process(
                    limit = 6, since = System.currentTimeMillis() - 2 * 24 * 60 * 60_000L, deadline = deadline, isStopped = describeStopped,
                )
            }
        } else {
            while (!isStopped && System.currentTimeMillis() < deadline) {
                val n = c.engine.process(limit = 50, deadline = deadline) { isStopped }
                if (n == 0) break
                indexed += n
            }
            while (!describeStopped() && System.currentTimeMillis() < deadline) {
                val n = c.describer.process(limit = 10, deadline = deadline, isStopped = describeStopped)
                if (n == 0) break
                described += n
            }
        }
        if (mode == MODE_NEW) c.scheduler.watchForNewMedia(afterCurrent = true)
        // Only chain another backlog run when this one got somewhere; a run that did nothing
        // (a persistent error) waits for the next app open or new photo instead of looping.
        val toRead = c.repo.counts().pending
        val toDescribe = if (c.describer.available) c.repo.captionCounts().second else 0
        val progressed = indexed + described > 0 || mode == MODE_NEW
        if (progressed && toRead > 0) {
            c.scheduler.scheduleBacklog(afterCurrent = mode == MODE_BACKLOG)
        } else if (progressed && toDescribe > 0) {
            c.scheduler.scheduleBacklog(afterCurrent = mode == MODE_BACKLOG, requireCharging = true)
        }
        DotLog.i(
            "worker: $mode run finished · $indexed analysed, $described described" +
                (if (power.isCharging) "" else " (descriptions wait for the charger)") + " · $toRead to analyse, $toDescribe to describe",
        )
        return Result.success()
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_NEW = "new"
        const val MODE_BACKLOG = "backlog"
    }
}

class IndexScheduler(private val context: Context, private val backlogWhileCharging: () -> Boolean) {
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

    /**
     * Older media. Analysing follows the "only while charging" setting; when only descriptions are
     * left ([requireCharging]), the job always waits for the charger.
     */
    fun scheduleBacklog(afterCurrent: Boolean = false, requireCharging: Boolean = backlogWhileCharging()) {
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
}
