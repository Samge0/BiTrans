package com.samge.bitrans.listen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.samge.bitrans.MainActivity
import com.samge.bitrans.R

/**
 * Keeps mic listening alive when user switches to Hilokal (or any other app).
 * The activity holds the pipeline; this service only keeps the process
 * foreground so Android does not reclaim the mic while backgrounded.
 */
class ListenService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1, buildNotification("实时翻译监听中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stop = intent?.getBooleanExtra(EXTRA_STOP, false) ?: false
        if (stop) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "实时翻译", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("BiTrans")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val CHANNEL = "bistrans_listen"
        const val EXTRA_STOP = "stop"

        fun start(ctx: Context) {
            val i = Intent(ctx, ListenService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ListenService::class.java).putExtra(EXTRA_STOP, true))
        }
    }
}
