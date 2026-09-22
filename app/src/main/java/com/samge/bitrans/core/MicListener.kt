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
 * Single authoritative timeline (fixes "later speech swallows earlier captions"):
 *  - `totalSamples`    : global sample clock (== silero's clock, we feed it everything)
 *  - `committedSamples`: everything before this offset has been emitted as a FINAL
 *  - `audioTail`       : uncommitted recent audio (ring, 30s) used for PARTIAL decodes
 *
 * Segments closed by silero are authoritative finals; any overlap with already
 * committed audio is trimmed, so re-opened/late segments never duplicate or
 * swallow content. Force-commit (endless speech) advances the commit point
 * WITHOUT resetting the VAD, so silero's own follow-up segments stay usable.
 */
class MicListener(
    private val context: android.content.Context,
    private val onSegment: (samples: FloatArray, startSec: Float) -> Unit,
    private val onPartial: (samples: FloatArray) -> Unit = {},
    private val onPartialLevel: (level: Float) -> Unit = {},
) {
    private val TAG = "MicListener"
    private val SAMPLE_RATE = 16000

    private val RMS_ACTIVE = 0.006f    // decode partials only when above this
    private val MIN_TAIL_SEC = 1.0f    // first partial threshold
    private val PARTIAL_EVERY_MS = 1200L
    private val MAX_TAIL_SEC = 8.0f    // force-commit endless speech
    private val MIN_FINAL_SEC = 0.35f  // discard tiny finals (noise)
    private val TAIL_LIMIT = SAMPLE_RATE * 30

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
            Log.i(TAG, "timeline recorder started")

            val pcmBuf = ShortArray(window)
            val floatWin = FloatArray(window)
            val tail = ArrayDeque<Float>(SAMPLE_RATE * 10)
            var totalSamples = 0L       // global clock
            var committed = 0L          // commit point (samples)
            var lastPartialAt = 0L
            // front-stability tracking (silero queues a segment while it is still
            // GROWING; popping early truncates it -> the original swallow bug)
            var frontLenPrev = -1
            var frontStableRounds = 0

            fun tailStart(): Long = totalSamples - tail.size   // tail covers [tailStart, totalSamples)

            fun dropCommitted() {
                val over = (committed - tailStart()).toInt()
                if (over > 0) repeat(over.coerceAtMost(tail.size)) { tail.removeFirst() }
            }

            fun tryDrainFinal(): Boolean {
                if (vad.empty()) return false
                val cur = vad.front().samples.size
                if (cur != frontLenPrev) {
                    frontLenPrev = cur
                    frontStableRounds = 0
                    return false // still growing
                }
                frontStableRounds++
                if (frontStableRounds < 8) return false
                if (vad.isSpeechDetected()) return false // speech may continue
                // stable AND speech over: safe to take the authoritative final
                val seg: SpeechSegment = vad.front()
                vad.pop()
                frontLenPrev = -1
                frontStableRounds = 0
                val segEnd = seg.start + seg.samples.size
                val overlap = (committed - seg.start).toInt()
                if (overlap < seg.samples.size) {
                    val fresh = if (overlap > 0) seg.samples.copyOfRange(overlap, seg.samples.size) else seg.samples
                    if (fresh.size >= (MIN_FINAL_SEC * SAMPLE_RATE).toInt()) {
                        onSegment(fresh, seg.start / SAMPLE_RATE.toFloat())
                    }
                }
                if (segEnd > committed) committed = segEnd.toLong()
                dropCommitted()
                return true
            }

            while (running) {
                val n = record.read(pcmBuf, 0, window)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val v = pcmBuf[i] / 32768.0f
                    floatWin[i] = v
                    sum += (v.toDouble() * v.toDouble())
                    tail.addLast(v)
                }
                val rms = kotlin.math.sqrt(sum / n).toFloat()
                onPartialLevel(rms)
                totalSamples += n
                while (tail.size > TAIL_LIMIT) tail.removeFirst()

                vad.acceptWaveform(floatWin.copyOf(n))
                tryDrainFinal()

                val tailSec = tail.size / SAMPLE_RATE.toFloat()

                // ---- force-commit endless speech (multi-speaker / nonstop rooms) ----
                // No vad.reset(): silero keeps tracking; any later segment is overlap-trimmed above.
                if (tailSec >= MAX_TAIL_SEC) {
                    val arr = FloatArray(tail.size)
                    var k = 0
                    for (v in tail) arr[k++] = v
                    if (arr.size >= (MIN_FINAL_SEC * SAMPLE_RATE).toInt()) {
                        onSegment(arr, tailStart() / SAMPLE_RATE.toFloat())
                    }
                    committed = totalSamples
                    tail.clear()
                    lastPartialAt = System.currentTimeMillis()
                    continue
                }

                // ---- streaming partial over the uncommitted tail ----
                val now = System.currentTimeMillis()
                if (tailSec >= MIN_TAIL_SEC && now - lastPartialAt >= PARTIAL_EVERY_MS && rms > RMS_ACTIVE) {
                    val arr = FloatArray(tail.size)
                    var k = 0
                    for (v in tail) arr[k++] = v
                    onPartial(arr)
                    lastPartialAt = now
                }
            }
            try { record.stop() } catch (_: Exception) {}
            record.release()
            Log.i(TAG, "timeline recorder stopped")
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
