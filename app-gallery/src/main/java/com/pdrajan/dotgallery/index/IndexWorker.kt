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
        var indexed = 0
        if (mode == MODE_NEW) {
            indexed = c.engine.process(limit = 40, deadline = deadline) { isStopped }
        } else {
            while (!isStopped && System.currentTimeMillis() < deadline) {
                val n = c.engine.process(limit = 50, deadline = deadline) { isStopped }
                if (n == 0) break
                indexed += n
            }
        }
        if (mode == MODE_NEW) c.scheduler.watchForNewMedia(afterCurrent = true)
        // Only chain another backlog run when this one got somewhere; a run that indexed nothing
        // (a persistent error) waits for the next app open or new photo instead of looping.
        val pending = c.repo.counts().pending
        if (pending > 0 && (indexed > 0 || mode == MODE_NEW)) c.scheduler.scheduleBacklog(afterCurrent = mode == MODE_BACKLOG)
        DotLog.i("worker: $mode run finished · $indexed indexed, $pending still pending")
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

    fun scheduleBacklog(afterCurrent: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(backlogWhileCharging())
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
