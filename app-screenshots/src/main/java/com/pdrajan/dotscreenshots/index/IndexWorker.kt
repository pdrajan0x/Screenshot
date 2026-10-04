package com.pdrajan.dotscreenshots.index

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dotscreenshots.DotScreenshotsApp
import com.pdrajan.dotscreenshots.R
import kotlinx.coroutines.CancellationException
import java.time.Duration

/**
 * Background indexing. Two flavours:
 *  - "new": wakes when MediaStore images change (no polling), handles the latest screenshots.
 *  - "backlog": older screenshots, following Settings → Processing.
 *
 * With a lot left, a run asks to become a foreground service (a quiet "Reading screenshots"
 * notification) so Android lets it finish instead of stopping it after 10 minutes. Android only
 * allows that while the app is open or exempt from battery optimisation; otherwise it carries on
 * as an ordinary job and the next run picks up where it stopped.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as DotScreenshotsApp).container
        val mode = inputData.getString(KEY_MODE) ?: MODE_NEW
        DotLog.i("worker: $mode run started")
        runCatching { container.engine.sync() }

        val before = container.repo.counts()
        val left = before.pending + if (container.hub.download.ready) before.updating else 0
        val foreground = left > FOREGROUND_ABOVE && goForeground(left)
        // An ordinary job is stopped by Android after 10 minutes; a foreground one may run for hours.
        val deadline = System.currentTimeMillis() + if (foreground) 60 * 60_000L else 8 * 60_000L

        // Settings → Processing: background work at all, and on battery only above the chosen level.
        // Screenshots are read a few at a time, newest first.
        val power = container.power
        val blocker = power.blocker(foreground = false)
        val stopped = { isStopped || power.blocker(foreground = false) != null }
        var progressed = 0
        if (blocker == null) {
            container.running {
                while (!stopped() && System.currentTimeMillis() < deadline) {
                    val n = container.processStep(userAsked = false, foreground = false, deadline = deadline, isStopped = stopped)
                    if (n == 0) break
                    progressed += n
                }
            }
        }

        val counts = container.repo.counts()
        val toRead = counts.pending + if (container.hub.download.ready) counts.updating else 0
        val scheduler = container.scheduler
        if (mode == MODE_NEW) scheduler.watchForNewScreenshots(afterCurrent = true)
        // Only chain another backlog run when this one got somewhere; a run that did nothing
        // (a persistent error) waits for the next app open or screenshot instead of looping.
        if ((progressed > 0 || mode == MODE_NEW) && toRead > 0 && container.settings.processing.value.background) {
            scheduler.scheduleBacklog(afterCurrent = mode == MODE_BACKLOG)
        }
        DotLog.i(
            "worker: $mode run finished · $progressed items moved forward" +
                (blocker?.let { " (paused: $it)" } ?: "") + " · $toRead to read" + if (foreground) " (foreground)" else "",
        )
        return Result.success()
    }

    /** Becomes a foreground service when Android allows it; false when it doesn't. */
    private suspend fun goForeground(left: Int): Boolean = try {
        setForeground(foregroundInfo(left))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DotLog.i("worker: running as an ordinary job (${e.javaClass.simpleName})")
        false
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    private fun foregroundInfo(left: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reading screenshots", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while screenshots are read in the background"
                setShowBadge(false)
            },
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reading screenshots")
            .setContentText(if (left > 0) "$left left" else null)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(NOTIFICATION_ID, notification, type)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_NEW = "new"
        const val MODE_BACKLOG = "backlog"
        private const val CHANNEL = "processing"
        private const val NOTIFICATION_ID = 7
        /** Fewer than this left: a short ordinary job is enough, no notification. */
        private const val FOREGROUND_ABOVE = 8
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

    /**
     * Older screenshots; waits for the charger unless processing on battery is allowed. A queued
     * job that hasn't started is replaced, so it never waits on rules that no longer apply.
     * Blocking (asks WorkManager what's queued); call off the main thread.
     */
    fun scheduleBacklog(afterCurrent: Boolean = false) {
        val running = runCatching {
            workManager.getWorkInfosForUniqueWork(WORK_BACKLOG).get().any { it.state == WorkInfo.State.RUNNING }
        }.getOrDefault(true)
        enqueueBacklog(
            when {
                afterCurrent -> ExistingWorkPolicy.APPEND_OR_REPLACE
                running -> ExistingWorkPolicy.KEEP
                else -> ExistingWorkPolicy.REPLACE
            },
        )
    }

    /** Settings changed (e.g. the charging rule): replace the queued backlog job. */
    fun rescheduleBacklog() = enqueueBacklog(ExistingWorkPolicy.REPLACE)

    private fun enqueueBacklog(policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(chargingOnly())
            .build()
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(IndexWorker.KEY_MODE to IndexWorker.MODE_BACKLOG))
            .build()
        workManager.enqueueUniqueWork(WORK_BACKLOG, policy, request)
    }

    fun cancelBacklog() {
        workManager.cancelUniqueWork(WORK_BACKLOG)
    }

    private companion object {
        const val WORK_WATCH = "watch-new-screenshots"
        const val WORK_BACKLOG = "index-backlog"
    }
}
