package io.github.polarfox0317.sikboonge.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.polarfox0317.sikboonge.MainActivity
import kotlin.math.max

/** Keeps a user-started APK download in the foreground while UpdateController performs the transfer. */
internal class UpdateDownloadService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            UpdateController.cancelDownload()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val current = synchronized(progressLock) { latestProgress }
        startForeground(NOTIFICATION_ID, progressNotification(this, current.first, current.second))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CANCEL = "io.github.polarfox0317.sikboonge.update.CANCEL_DOWNLOAD"
        private const val CHANNEL_ID = "app_update"
        private const val NOTIFICATION_ID = 42
        private const val COMPLETED_NOTIFICATION_ID = 43
        private val progressLock = Any()
        private var latestProgress = 0L to 0L

        fun start(context: Context) {
            synchronized(progressLock) { latestProgress = 0L to 0L }
            ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UpdateDownloadService::class.java))
        }

        fun showProgress(context: Context, downloaded: Long, total: Long) {
            synchronized(progressLock) { latestProgress = max(0L, downloaded) to max(0L, total) }
            createChannel(context)
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, progressNotification(context, downloaded, total))
        }

        fun showCompleted(context: Context, version: String) {
            createChannel(context)
            val open = PendingIntent.getActivity(
                context,
                COMPLETED_NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .putExtra("open_update", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("업데이트 다운로드 완료")
                .setContentText("버전 $version 설치를 계속할 수 있어요")
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(COMPLETED_NOTIFICATION_ID, notification)
        }

        fun clear(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            stop(context)
        }

        private fun progressNotification(context: Context, downloaded: Long, total: Long): Notification {
            val cancelIntent = PendingIntent.getService(
                context,
                NOTIFICATION_ID,
                Intent(context, UpdateDownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val knownTotal = total.takeIf { it > 0L }
            val progress = if (knownTotal != null) {
                ((downloaded.coerceAtLeast(0L).toDouble() / knownTotal).coerceIn(0.0, 0.99) * 100).toInt()
            } else 0
            val text = if (knownTotal == null) "APK 다운로드 중" else "APK 다운로드 중 · $progress%"
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("앱 업데이트")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setProgress(100, progress, knownTotal == null)
                .addAction(0, "취소", cancelIntent)
                .build()
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "앱 업데이트", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
