package com.samge.bitrans.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import kotlin.math.abs

/**
 * Continuous capture at 16kHz mono PCM16 feeding the streaming pipeline:
 *  - growing "tail" buffer decoded every ~1.2s as PARTIAL results;
 *  - silero VAD closed segments decoded as authoritative FINALs;
 *  - force-commit (8s) for endless speech; single sample-clock timeline with
 *    committed-offset overlap trimming (no swallow, no duplication).
 *
 * Capture source is pluggable:
 *  - default: device microphone (VOICE_RECOGNITION priority — survives
 *    concurrent-capture silencing on many ROMs);
 *  - injected: any reader delivering 16k-mono blocks (playback capture).
 */
class MicListener(
    private val context: android.content.Context,
    private val onSegment: (samples: FloatArray, startSec: Float) -> Unit,
    private val onPartial: (samples: FloatArray) -> Unit = {},
    private val onPartialLevel: (level: Float) -> Unit = {},
    /** called when the mic is silenced by the system (concurrent capture) or fails to open */
    private val onSilenced: () -> Unit = {},
    /**
     * Pluggable recorder: read one block of ~window 16k-mono samples into pcmBuf
     * (already downsampled); return count, 0 when暂时无数据, or -1 to end.
     * Default = device microphone; playback capture injects its own.
     */
    private val externalRecorder: ((pcmBuf: ShortArray, window: Int) -> Int)? = null,
) {
    private val TAG = "MicListener"
    private val SAMPLE_RATE = 16000

    private val SOURCES = listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.CAMCORDER,
    )

    private val RMS_ACTIVE = 0.006f
    private val MIN_TAIL_SEC = 1.0f
    private val PARTIAL_EVERY_MS = 1200L
    private val MAX_TAIL_SEC = 8.0f
    private val MIN_FINAL_SEC = 0.35f
    private val TAIL_LIMIT = SAMPLE_RATE * 30
    private val SILENT_ALERT_WINDOWS = (4 * SAMPLE_RATE / 512)

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val vad = AsrEngine.getVad(context)
            val window = 512

            if (externalRecorder != null) {
                Log.i(TAG, "recorder started (external capture source)")
                runPipeline(vad, window) { pcmBuf -> externalRecorder.invoke(pcmBuf, window) }
                return@Thread
            }

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, window * 4 * 2)
            var record: AudioRecord? = null
            var usedSource = -1
            for (src in SOURCES) {
                val r = AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    record = r
                    usedSource = src
                    break
                }
                r.release()
            }
            if (record == null) {
                Log.e(TAG, "AudioRecord init failed for all sources")
                onSilenced()
                running = false
                return@Thread
            }
            Log.i(TAG, "recorder started with source=$usedSource")
            val rec = record!!
            rec.startRecording()
            runPipeline(vad, window) { pcmBuf -> rec.read(pcmBuf, 0, window) }
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            Log.i(TAG, "recorder stopped")
        }, "mic-stream").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The whole streaming pipeline, fed by [reader]. Verified logic — see git
     * history for the swallow-bug war stories before touching the timeline code.
     */
    private fun runPipeline(
        vad: Vad,
        window: Int,
        reader: (ShortArray) -> Int,
    ) {
        val pcmBuf = ShortArray(window)
        val floatWin = FloatArray(window)
        val tail = ArrayDeque<Float>(SAMPLE_RATE * 10)
        var totalSamples = 0L
        var committed = 0L
        var lastPartialAt = 0L
        var silentWindows = 0
        var frontLenPrev = -1
        var frontStableRounds = 0

        fun tailStart(): Long = totalSamples - tail.size

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
                return false
            }
            frontStableRounds++
            if (frontStableRounds < 8) return false
            if (vad.isSpeechDetected()) return false
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
            val n = reader(pcmBuf)
            if (n < 0) break
            if (n == 0) continue
            var sum = 0.0
            var allZero = true
            for (i in 0 until n) {
                val v = pcmBuf[i] / 32768.0f
                floatWin[i] = v
                sum += (v.toDouble() * v.toDouble())
                tail.addLast(v)
                if (pcmBuf[i].toInt() != 0) allZero = false
            }
            val rms = kotlin.math.sqrt(sum / n).toFloat()
            onPartialLevel(rms)
            if (allZero) {
                silentWindows++
                if (silentWindows == SILENT_ALERT_WINDOWS) {
                    Log.w(TAG, "capture silenced (all-zero ~4s)")
                    onSilenced()
                }
            } else {
                if (silentWindows >= SILENT_ALERT_WINDOWS) Log.i(TAG, "capture audio restored")
                silentWindows = 0
            }
            totalSamples += n
            while (tail.size > TAIL_LIMIT) tail.removeFirst()

            vad.acceptWaveform(floatWin.copyOf(n))
            tryDrainFinal()

            val tailSec = tail.size / SAMPLE_RATE.toFloat()

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

            val now = System.currentTimeMillis()
            if (tailSec >= MIN_TAIL_SEC && now - lastPartialAt >= PARTIAL_EVERY_MS && rms > RMS_ACTIVE) {
                val arr = FloatArray(tail.size)
                var k = 0
                for (v in tail) arr[k++] = v
                onPartial(arr)
                lastPartialAt = now
            }
        }
        Log.i(TAG, "pipeline stopped")
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }
}
