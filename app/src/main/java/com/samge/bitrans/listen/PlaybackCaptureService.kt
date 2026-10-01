package com.samge.bitrans.listen

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.samge.bitrans.MainActivity
import java.util.concurrent.atomic.AtomicReference

/**
 * REVERSE CAPTURE: captures audio PLAYED BY OTHER APPS (e.g. the voice-room
 * remote speaker inside Hilokal) via AudioPlaybackCapture + MediaProjection.
 * Immune to the concurrent-capture MIC silencing — this is the official path
 * for "translate what the phone is playing".
 *
 * Flow: Activity obtains the projection (system consent dialog) and hands the
 * result here -> service goes foreground (required before getMediaProjection)
 * -> builds an AudioRecord with capture config -> injects its reader into the
 * same streaming pipeline MicListener already runs.
 */
class PlaybackCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<android.content.Intent>(EXTRA_RESULT_DATA)
        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIF_ID, buildNotification(), fgType())
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, resultData)
            projectionHolder.set(projection)
            startCapture(projection)
        } catch (t: Throwable) {
            Log.e(TAG, "projection/capture failed", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(projection: MediaProjection) {
        val captureRate = 48000 // playback captures commonly run at 48k
        val minBuf = AudioRecord.getMinBufferSize(
            captureRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(captureRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, 16384))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "playback-capture AudioRecord init failed")
            record.release()
            stopSelf()
            return
        }
        record.startRecording()
        captureRecord.set(record)
        Log.i(TAG, "playback capture started @${captureRate}Hz")
    }

    override fun onDestroy() {
        captureRecord.getAndSet(null)?.let { r ->
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        projectionHolder.getAndSet(null)?.stop()
        Log.i(TAG, "playback capture stopped")
        super.onDestroy()
    }

    private fun fgType(): Int =
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else 0

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "回放采集", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("BiTrans")
            .setContentText("正在采集手机播放的声音（反向翻译模式）")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val TAG = "PlaybackCapture"
        const val CHANNEL = "bistrans_playback"
        const val NOTIF_ID = 2
        const val ACTION_STOP = "stop_playback_capture"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private val projectionHolder = AtomicReference<MediaProjection?>(null)
        private val captureRecord = AtomicReference<AudioRecord?>(null)
        private val ui = android.os.Handler(android.os.Looper.getMainLooper())

        fun active(): Boolean = captureRecord.get() != null

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, PlaybackCaptureService::class.java).setAction(ACTION_STOP))
            }
            try {
                ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            } catch (_: Exception) {}
        }

        /**
         * Reader for MicListener's externalRecorder: reads playback audio and
         * downsamples 48k->16k (simple linear decimation by 3), block-aligned to
         * the pipeline's window.
         */
        fun reader16k(): (ShortArray, Int) -> Int {
            val CAP = 48000
            val ratio = CAP / 16000 // 3
            val raw = ShortArray(512 * ratio)
            val leftover = ArrayDeque<Short>()
            return lambda@{ pcmBuf, window ->
                val rec = captureRecord.get()
                if (rec == null) {
                    -1
                } else {
                    var outIdx = 0
                    // drain leftovers first
                    while (leftover.isNotEmpty() && outIdx < window) {
                        pcmBuf[outIdx++] = leftover.removeFirst()
                    }
                    while (outIdx < window) {
                        val n = rec.read(raw, 0, raw.size)
                        if (n <= 0) break
                        var i = 0
                        while (i + ratio <= n && outIdx < window) {
                            // average the 3 samples (crude but adequate anti-alias)
                            val avg = ((raw[i].toInt() + raw[i + 1].toInt() + raw[i + 2].toInt()) / 3).toShort()
                            pcmBuf[outIdx++] = avg
                            i += ratio
                        }
                        while (i < n) leftover.addLast(raw[i++])
                    }
                    outIdx
                }
            }
        }
    }
}
