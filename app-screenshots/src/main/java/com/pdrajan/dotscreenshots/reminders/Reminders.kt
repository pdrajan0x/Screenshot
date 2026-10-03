package com.pdrajan.dotscreenshots.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.pdrajan.dot.design.NothingRed
import com.pdrajan.dot.media.MediaPermissions
import com.pdrajan.dotscreenshots.DotScreenshotsApp
import com.pdrajan.dotscreenshots.MainActivity
import com.pdrajan.dotscreenshots.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toArgb

object ReminderScheduler {
    const val CHANNEL = "reminders"
    private const val EXTRA_REMINDER = "reminder_id"
    const val EXTRA_SHOT = "shot_id"

    fun createChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders you set on screenshots"
            },
        )
    }

    /** Inexact but Doze-friendly; fires within a few minutes of [at]. No exact-alarm permission needed. */
    fun schedule(context: Context, reminderId: Long, shotId: Long, at: Long) {
        val alarm = context.getSystemService<AlarmManager>() ?: return
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context, reminderId, shotId))
    }

    fun cancel(context: Context, reminderId: Long, shotId: Long) {
        context.getSystemService<AlarmManager>()?.cancel(pendingIntent(context, reminderId, shotId))
    }

    private fun pendingIntent(context: Context, reminderId: Long, shotId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_REMINDER, reminderId).putExtra(EXTRA_SHOT, shotId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun reminderIdOf(intent: Intent) = intent.getLongExtra(EXTRA_REMINDER, -1)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = ReminderScheduler.reminderIdOf(intent)
        val shotId = intent.getLongExtra(ReminderScheduler.EXTRA_SHOT, -1)
        if (reminderId < 0 || shotId < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as DotScreenshotsApp).container.repo
                repo.reminder(reminderId) ?: return@launch
                val (title, uri) = repo.reminderTitle(shotId)
                repo.markReminderDone(reminderId)
                if (!MediaPermissions.notificationsGranted(context)) return@launch

                val open = PendingIntent.getActivity(
                    context,
                    reminderId.toInt(),
                    Intent(context, MainActivity::class.java)
                        .putExtra(ReminderScheduler.EXTRA_SHOT, shotId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val picture: Bitmap? = uri?.let { u ->
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, u.toUri())) { d, info, _ ->
                                val scale = 720f / maxOf(info.size.width, 1)
                                if (scale < 1f) d.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                            }
                        } else {
                            null
                        }
                    }.getOrNull()
                }
                val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(NothingRed.toArgb())
                    .setContentTitle("Screenshot reminder")
                    .setContentText(title ?: "Tap to open your screenshot")
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .apply {
                        if (picture != null) {
                            setLargeIcon(picture)
                            setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?))
                        }
                    }
                    .build()
                @Suppress("MissingPermission")
                NotificationManagerCompat.from(context).notify(reminderId.toInt(), notification)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms don't survive a reboot; WorkManager jobs do, so only reminders need restoring. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as DotScreenshotsApp).container
                val now = System.currentTimeMillis()
                container.repo.activeReminders().forEach { r ->
                    ReminderScheduler.schedule(context, r.id, r.shotId, maxOf(r.at, now + 5_000))
                }
                container.scheduler.watchForNewScreenshots()
            } finally {
                pending.finish()
            }
        }
    }
}
