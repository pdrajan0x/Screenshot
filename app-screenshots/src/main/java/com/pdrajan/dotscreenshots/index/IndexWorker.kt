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
import com.pdrajan.dotscreenshots.DotScreenshotsApp
import java.time.Duration

/**
 * Background indexing. Two flavours:
 *  - "new": wakes when MediaStore images change (no polling), handles the latest screenshots.
 *  - "backlog": older screenshots, by default only while charging.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as DotScreenshotsApp).container
        val mode = inputData.getString(KEY_MODE) ?: MODE_NEW
        val deadline = System.currentTimeMillis() + 8 * 60_000L

        runCatching { container.engine.sync() }

        if (mode == MODE_NEW) {
            container.engine.process(limit = 30, deadline = deadline) { isStopped }
        } else {
            while (!isStopped && System.currentTimeMillis() < deadline) {
                if (container.engine.process(limit = 50, deadline = deadline) { isStopped } == 0) break
            }
        }

        val pending = container.repo.counts().pending
        val scheduler = container.scheduler
        if (mode == MODE_NEW) scheduler.watchForNewScreenshots(afterCurrent = true)
        if (pending > 0) scheduler.scheduleBacklog(afterCurrent = mode == MODE_BACKLOG)
        return Result.success()
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_NEW = "new"
        const val MODE_BACKLOG = "backlog"
    }
}

class IndexScheduler(private val context: Context, private val backlogWhileCharging: () -> Boolean) {

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

    fun scheduleBacklog(afterCurrent: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(backlogWhileCharging())
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

    private companion object {
        const val WORK_WATCH = "watch-new-screenshots"
        const val WORK_BACKLOG = "index-backlog"
    }
}
