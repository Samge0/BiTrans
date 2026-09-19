package com.samge.bitrans.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad

/**
 * Continuous microphone capture at 16kHz mono PCM16, feeding silero VAD.
 * Emits complete speech segments (float [-1,1]) when silence is detected.
 */
class MicListener(
    private val context: android.content.Context,
    private val onSegment: (samples: FloatArray, startSec: Float) -> Unit,
    private val onPartialLevel: (level: Float) -> Unit = {},
) {
    private val TAG = "MicListener"
    private val SAMPLE_RATE = 16000

    @Volatile private var running = false
    private var thread: Thread? = null
    private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val vad = AsrEngine.getVad(context)
            val window = 512
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, window * 4 * 2)
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
            )
            audioRecord = record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                running = false
                return@Thread
            }
            record.startRecording()
            Log.i(TAG, "recording started")
            val pcmBuf = ShortArray(window)
            val floatBuf = FloatArray(window)
            while (running) {
                val n = record.read(pcmBuf, 0, window)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val v = pcmBuf[i] / 32768.0f
                    floatBuf[i] = v
                    sum += (v.toDouble() * v.toDouble())
                }
                onPartialLevel(Math.sqrt(sum / n).toFloat())
                vad.acceptWaveform(floatBuf.copyOf(n))
                drain(vad)
            }
            // final flush
            vad.flush()
            drain(vad)
            try { record.stop() } catch (_: Exception) {}
            record.release()
            audioRecord = null
            Log.i(TAG, "recording stopped")
        }, "mic-listener").apply {
            isDaemon = true
            start()
        }
    }

    private fun drain(vad: Vad) {
        while (!vad.empty()) {
            val seg: SpeechSegment = vad.front()
            onSegment(seg.samples, seg.start / 16000f)
            vad.pop()
        }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }
}
