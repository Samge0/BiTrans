package com.samge.bitrans.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads ASR/VAD models on first run with resume + mirror fallback.
 *
 * Strategy: download LOOSE FILES (model.int8.onnx, tokens.txt, silero_vad.onnx)
 * instead of the tar.bz2 archive — Android has no tar, and hf-mirror hosts the
 * same files unpacked. GitHub release "asr-models" hosts silero_vad.onnx loose;
 * the SenseVoice loose files come from HuggingFace mirrors of the same archive.
 */
object ModelStore {
    private const val TAG = "ModelStore"

    /** primary + fallback urls for one file */
    class Src(val urls: List<String>)

    // Base repos (same content, different hosts) — verified 2026-09-20:
    // chris-cao/sherpa-onnx-sense-voice-...-int8-2024-07-17 hosts LOOSE files (LFS),
    // file sizes byte-identical to the official tar.bz2 archive.
    private const val HF1 = "https://huggingface.co/chris-cao/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/resolve/main"
    private const val HFM = "https://hf-mirror.com/chris-cao/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/resolve/main"

    private val FILES = listOf(
        // big model file — try CN mirror first for mainland users, then HF, then GH archive-less fallback
        Src(listOf("$HFM/model.int8.onnx", "$HF1/model.int8.onnx")),
        Src(listOf("$HFM/tokens.txt", "$HF1/tokens.txt")),
    )

    private const val VAD_GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    private const val VAD_HFM = "https://hf-mirror.com/mgonzs13/silero-vad-onnx/resolve/main/silero_vad.onnx"
    private const val VAD_HF = "https://huggingface.co/mgonzs13/silero-vad-onnx/resolve/main/silero_vad.onnx"

    fun modelsRoot(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models")

    fun asrModelDir(ctx: Context): File = File(modelsRoot(ctx), "sense-voice-int8")

    fun vadModelFile(ctx: Context): File = File(modelsRoot(ctx), "silero_vad.onnx")

    fun asrReady(ctx: Context): Boolean =
        File(asrModelDir(ctx), "model.int8.onnx").exists() &&
            File(asrModelDir(ctx), "tokens.txt").exists() &&
            vadModelFile(ctx).exists()

    /** total bytes already on disk (for UI display) */
    fun downloadedBytes(ctx: Context): Long {
        var sum = 0L
        File(asrModelDir(ctx), "model.int8.onnx").takeIf { it.exists() }?.let { sum += it.length() }
        File(asrModelDir(ctx), "tokens.txt").takeIf { it.exists() }?.let { sum += it.length() }
        vadModelFile(ctx).takeIf { it.exists() }?.let { sum += it.length() }
        return sum
    }

    /**
     * Download all missing files. onProgress gets (doneBytes, totalBytesEstimate).
     * Expected total ≈ 239MB model + 0.3MB tokens + 0.6MB vad.
     */
    fun downloadAll(ctx: Context, onProgress: (Long, Long) -> Unit): Result<Unit> {
        return try {
            val dir = asrModelDir(ctx)
            dir.mkdirs()
            val targets = listOf(
                Triple(File(dir, "model.int8.onnx"), FILES[0], 239_233_841L),
                Triple(File(dir, "tokens.txt"), FILES[1], 315_894L),
                Triple(vadModelFile(ctx), Src(listOf(VAD_HFM, VAD_HF, VAD_GH)), 643_854L),
            )
            val total = targets.sumOf { it.third }
            var done = targets.sumOf { if (it.first.exists()) it.first.length() else 0L }
            for ((dest, src, expected) in targets) {
                if (dest.exists() && dest.length() >= expected) {
                    continue
                }
                downloadFile(src.urls, dest, expected) { d ->
                    onProgress(done + d, total)
                }
                done += dest.length()
            }
            onProgress(total, total)
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "model download failed", t)
            Result.failure(t)
        }
    }

    private fun downloadFile(urls: List<String>, dest: File, expected: Long, onBytes: (Long) -> Unit) {
        if (dest.exists() && dest.length() >= expected) return
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        var lastErr: Throwable? = null
        outer@ for (url in urls) {
            // fresh start per SOURCE: different mirrors may serve different builds
            // (e.g. silero v5 is 2.3MB vs official 643KB) — appending across
            // sources would corrupt the file, so drop any partial from a previous URL.
            if (tmp.exists()) tmp.delete()
            var attempt = 0
            while (attempt < 4) {
                attempt++
                try {
                    fetchWithResume(url, tmp, expected, onBytes)
                    if (tmp.length() >= expected) {
                        if (dest.exists()) dest.delete()
                        check(tmp.renameTo(dest)) { "rename failed ${dest.name}" }
                        Log.i(TAG, "downloaded ${dest.name} ${dest.length()}B from $url")
                        return
                    } else if (attempt >= 4) {
                        // last attempt under this source may still be the v5-sized
                        // mirror file which legitimately exceeds `expected`
                        if (tmp.length() > 0 && tmp.length() != expected && looksComplete(tmp)) {
                            if (dest.exists()) dest.delete()
                            check(tmp.renameTo(dest)) { "rename failed ${dest.name}" }
                            Log.i(TAG, "downloaded ${dest.name} ${dest.length()}B from $url (alt size)")
                            return
                        }
                        throw IOException("incomplete ${tmp.length()}/$expected")
                    }
                } catch (t: Throwable) {
                    lastErr = t
                    Log.w(TAG, "attempt $attempt failed $url: ${t.message}")
                }
            }
        }
        throw lastErr ?: IOException("all sources failed ${dest.name}")
    }

    /** cheap completeness heuristic: ONNX/tokens files are never tiny-truncated */
    private fun looksComplete(f: File): Boolean = f.length() > 100_000

    private fun fetchWithResume(urlStr: String, dest: File, expected: Long, onBytes: (Long) -> Unit) {
        val offset = if (dest.exists()) dest.length() else 0L
        if (offset >= expected) return
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $urlStr")
            val body = conn.inputStream
            FileOutputStream(dest, true).use { out ->
                body.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        onBytes(dest.length())
                    }
                    out.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
