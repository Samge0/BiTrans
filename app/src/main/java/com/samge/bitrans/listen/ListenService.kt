package com.samge.bitrans.listen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.samge.bitrans.MainActivity

/**
 * Foreground service: keeps the process alive while listening (mic in background)
 * AND doubles as a caption surface — when the system overlay permission is
 * unavailable (HyperOS blocks it for sideloaded apps), the ongoing notification
 * shows live bilingual captions in the notification shade.
 */
class ListenService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification(this, "实时翻译监听中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stop = intent?.getBooleanExtra(EXTRA_STOP, false) ?: false
        if (stop) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "实时翻译", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.description = "后台监听状态与字幕兜底显示"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    companion object {
        const val CHANNEL = "bistrans_listen"
        const val EXTRA_STOP = "stop"
        const val NOTIF_ID = 1
        private val ui = Handler(Looper.getMainLooper())

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, ListenService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, ListenService::class.java).putExtra(EXTRA_STOP, true))
            } catch (_: Exception) {
            }
        }

        private fun buildNotification(ctx: Context, text: String): Notification {
            val pi = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("BiTrans")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(pi)
                .build()
        }

        /**
         * Fallback caption surface: refresh the ongoing notification with the
         * latest caption pair. Used when the system overlay is blocked.
         * Safe from any thread.
         */
        fun updateCaption(ctx: Context, source: String, target: String) {
            val text = if (target.isBlank() || target == "…") {
                source
            } else {
                "$source\n→ $target"
            }
            ui.post {
                try {
                    ctx.getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(ctx, text))
                } catch (_: Exception) {
                }
            }
        }
    }
}
