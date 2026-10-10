package io.github.polarfox0317.sikboonge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.app.PendingIntent
import android.os.Build
import android.os.IBinder

/** Holds the app process while FFmpeg produces and an external player reads audio-only bytes. */
class BotStreamRelayService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BotStreamSessions.cancelAll(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (intent?.action != ACTION_START || !BotStreamSessions.isActiveToken(token)) {
            if (BotStreamSessions.isIdle()) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        BotStreamSessions.cancelAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "외부 오디오 재생", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "외부 플레이어에 오디오 트랙만 전달하는 동안 표시됩니다." }
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BotStreamRelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("외부 플레이어용 오디오 준비 중")
            .setContentText("재생이 끝나면 자동으로 종료됩니다.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "중지", stopIntent).build())
            .build()
    }

    companion object {
        private const val ACTION_START = "io.github.polarfox0317.sikboonge.action.START_BOT_STREAM_RELAY"
        private const val ACTION_STOP = "io.github.polarfox0317.sikboonge.action.STOP_BOT_STREAM_RELAY"
        private const val EXTRA_TOKEN = "stream_token"
        private const val CHANNEL_ID = "bot_external_playback"
        private const val NOTIFICATION_ID = 4107

        fun start(context: Context, token: String) {
            val intent = Intent(context, BotStreamRelayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TOKEN, token)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BotStreamRelayService::class.java).setAction(ACTION_STOP))
        }
    }
}
