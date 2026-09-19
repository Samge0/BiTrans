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
    private val lock = Any()

    fun ready(ctx: Context): Boolean = ModelStore.asrReady(ctx)

    /**
     * Decode one utterance (float samples 16k mono, range [-1,1]).
     * Returns (text, langTag) — langTag like "zh" "en" "ja" "ko" "yue" from SenseVoice.
     */
    fun decode(ctx: Context, samples: FloatArray): Pair<String, String> {
        val rec = getRecognizer(ctx)
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

    fun getRecognizer(ctx: Context): OfflineRecognizer {
        recognizer?.let { return it }
        synchronized(lock) {
            recognizer?.let { return it }
            val dir = ModelStore.asrModelDir(ctx)
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                        language = "", // auto-detect among zh/en/ja/ko/yue
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 2,
                    modelType = "sense_voice",
                ),
            )
            val r = OfflineRecognizer(assetManager = null, config = config)
            recognizer = r
            return r
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
