package com.samge.bitrans.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad

/**
 * Continuous microphone capture at 16kHz mono PCM16.
 *
 * Streaming strategy (validated on desktop with the same model):
 *  - while speech energy is present, a growing "echo" buffer is decoded every
 *    ~1.2s as a PARTIAL result (fast appearing text, refined over time);
 *  - when silero VAD closes a segment, its samples are decoded as the
 *    AUTHORITATIVE final (replaces the partial);
 *  - a hard cap (MAX_PARTIAL_SEC) force-commits endless speech (multi-speaker
 *    rooms, background noise) so translation keeps flowing and nothing is lost.
 */
class MicListener(
    private val context: android.content.Context,
    private val onSegment: (samples: FloatArray, startSec: Float) -> Unit,
    private val onPartial: (samples: FloatArray) -> Unit = {},
    private val onPartialLevel: (level: Float) -> Unit = {},
) {
    private val TAG = "MicListener"
    private val SAMPLE_RATE = 16000

    private val RMS_ON = 0.010f     // start-of-speech gate
    private val RMS_OFF = 0.005f    // hysteresis floor (with VAD agreement)
    private val MIN_PARTIAL_SEC = 1.0f
    private val PARTIAL_EVERY_MS = 1200L
    private val MAX_PARTIAL_SEC = 8.0f

    @Volatile private var running = false
    private var thread: Thread? = null

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
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                running = false
                return@Thread
            }
            record.startRecording()
            Log.i(TAG, "streaming recorder started")

            val pcmBuf = ShortArray(window)
            val floatWin = FloatArray(window)
            val echo = ArrayList<Float>(16000 * 12)
            var inSpeech = false
            var lastPartialAt = 0L


            while (running) {
                val n = record.read(pcmBuf, 0, window)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val v = pcmBuf[i] / 32768.0f
                    floatWin[i] = v
                    sum += (v.toDouble() * v.toDouble())
                }
                val rms = kotlin.math.sqrt(sum / n).toFloat()
                onPartialLevel(rms)

                vad.acceptWaveform(floatWin.copyOf(n))

                // finalized segment from VAD is authoritative
                if (!vad.empty()) {
                    val seg: SpeechSegment = vad.front()
                    vad.pop()
                    if (seg.samples.size >= SAMPLE_RATE / 2) {
                        onSegment(seg.samples, seg.start / SAMPLE_RATE.toFloat())
                    } else {
                        echo.clear()
                    }
                    inSpeech = false
                    continue
                }

                val speechLikely = vad.isSpeechDetected()
                if (!inSpeech && (rms > RMS_ON || speechLikely)) {
                    inSpeech = true
                    lastPartialAt = System.currentTimeMillis()
                    echo.clear()
                } else if (inSpeech && rms < RMS_OFF && !speechLikely) {
                    // brief dip: only close if VAD also agrees; else keep buffering
                    inSpeech = false
                    if (echo.size >= SAMPLE_RATE / 2) {
                        onPartial(echo.toFloatArray())
                    }
                    echo.clear()
                }

                if (inSpeech) {
                    for (i in 0 until n) echo.add(floatWin[i])
                    val durSec = echo.size / SAMPLE_RATE.toFloat()
                    val now = System.currentTimeMillis()
                    if (echo.size >= SAMPLE_RATE && durSec >= MAX_PARTIAL_SEC) {
                        // force-commit endless speech (multi-speaker rooms / noise)
                        onSegment(echo.toFloatArray(), 0f)
                        echo.clear()
                        inSpeech = false
                        try { vad.reset() } catch (_: Exception) {}
                    } else if (durSec >= MIN_PARTIAL_SEC && now - lastPartialAt >= PARTIAL_EVERY_MS) {
                        onPartial(echo.toFloatArray())
                        lastPartialAt = now
                    }
                }
            }
            try { record.stop() } catch (_: Exception) {}
            record.release()
            Log.i(TAG, "streaming recorder stopped")
        }, "mic-stream").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }
}
