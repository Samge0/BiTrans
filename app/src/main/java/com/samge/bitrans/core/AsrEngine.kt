package com.samge.bitrans.core

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Manages sherpa-onnx native resources: SenseVoice ASR model + silero VAD.
 * Models are downloaded at first run into filesDir/models (see ModelStore).
 */
object AsrEngine {
    private const val TAG = "AsrEngine"

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var vad: Vad? = null
    @Volatile private var currentLangKey: String = "auto"
    private val lock = Any()
    /** serializes native decode calls (partial/final coroutines race otherwise) */
    private val decodeLock = Any()

    fun ready(ctx: Context): Boolean = ModelStore.asrReady(ctx)

    /**
     * Decode one utterance (float samples 16k mono, range [-1,1]).
     * langHint: "auto" (SenseVoice detects) or a fixed code (zh/en/ja/ko/yue).
     * Returns (text, langTag).
     *
     * THREAD-SAFETY: the native OfflineRecognizer is NOT safe for concurrent
     * decode calls (partial + final decode race from different coroutines).
     * All decodes are serialized on this monitor.
     */
    fun decode(ctx: Context, samples: FloatArray, langHint: String = "auto"): Pair<String, String> {
        val rec = getRecognizer(ctx, langHint)
        synchronized(decodeLock) {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                rec.decode(stream)
                val res = rec.getResult(stream)
                val lang = res.lang.replace("<|", "").replace("|>", "")
                return Pair(res.text.trim(), lang)
            } finally {
                stream.release()
            }
        }
    }

    /**
     * Recognizer is built per language mode; switching source-language setting
     * triggers an async rebuild (model load takes a few seconds, done off-thread).
     */
    fun getRecognizer(ctx: Context, langHint: String = "auto"): OfflineRecognizer {
        val key = if (langHint == "auto" || langHint.isBlank()) "auto" else langHint
        recognizer?.let { if (currentLangKey == key) return it }
        synchronized(lock) {
            recognizer?.let { if (currentLangKey == key) return it }
            val dir = ModelStore.asrModelDir(ctx)
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                        // empty = auto-detect; fixed code forces the language token
                        language = if (key == "auto") "" else key,
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 2,
                    modelType = "sense_voice",
                ),
            )
            val r = OfflineRecognizer(assetManager = null, config = config)
            // publish NEW instance BEFORE releasing the old one: an in-flight
            // decode may still hold a reference, and releasing under it crashes natively.
            val old = recognizer
            recognizer = r
            currentLangKey = key
            old?.release()
            Log.i(TAG, "recognizer built for lang=$key")
            return r
        }
    }

    /** Pre-warm in background (called when listening starts / source lang changes). */
    fun warmUp(ctx: Context, langHint: String) {
        synchronized(lock) {
            val key = if (langHint == "auto" || langHint.isBlank()) "auto" else langHint
            if (currentLangKey == key && recognizer != null) return
        }
        Thread {
            try { getRecognizer(ctx, langHint) } catch (t: Throwable) {
                Log.e(TAG, "warmUp failed", t)
            }
        }.apply {
            isDaemon = true
            name = "asr-warmup"
            start()
        }
    }

    fun getVad(ctx: Context): Vad {
        vad?.let { return it }
        synchronized(lock) {
            vad?.let { return it }
            val cfg = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = ModelStore.vadModelFile(ctx).absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                ),
                sampleRate = 16000,
            )
            val v = Vad(assetManager = null, config = cfg)
            vad = v
            return v
        }
    }

    fun vadWindowSize(ctx: Context): Int = 512

    fun shutdown() {
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
            // VAD has no release in kotlin api; drop reference
            vad = null
        }
        Log.i(TAG, "ASR engine shut down")
    }
}
