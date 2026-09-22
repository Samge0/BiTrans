package com.samge.bitrans.translate

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

/** Supported direction presets */
enum class TargetLang(val code: String, val display: String, val mlkit: String, val lt: String) {
    ZH("zh", "中文", TranslateLanguage.CHINESE, "zh"),
    EN("en", "English", TranslateLanguage.ENGLISH, "en"),
    JA("ja", "日本語", TranslateLanguage.JAPANESE, "ja"),
    KO("ko", "한국어", TranslateLanguage.KOREAN, "ko"),
    YUE("yue", "粤语", TranslateLanguage.CHINESE, "yue");

    companion object {
        fun fromCode(c: String): TargetLang = entries.firstOrNull { it.code == c } ?: ZH
    }
}

/**
 * Translation engine abstraction.
 *
 * Engines:
 *  1. MlKit — on-device neural MT (free, needs GMS + per-language model download ~30MB)
 *  2. LibreTranslate — self-hostable open-source endpoint (default public mirrors listed in settings)
 *  3. OpenAI-compatible LLM — any /v1/chat/completions endpoint incl. LAN vLLM
 */
interface TranslateEngine {
    val name: String
    suspend fun translate(text: String, from: String, to: String): Result<String>
    suspend fun prepare(): Result<Unit> = Result.success(Unit)
}

class MlKitEngine(private val context: Context) : TranslateEngine {
    override val name = "MLKit(离线)"

    override suspend fun translate(text: String, from: String, to: String): Result<String> {
        return try {
            // SenseVoice "yue" has no ML Kit tag; Chinese covers it
            val srcCode = if (from == "yue") "zh" else from
            val tgtCode = if (to == "yue") "zh" else to
            val src = TargetLang.fromCode(srcCode).mlkit
            val tgt = TargetLang.fromCode(tgtCode).mlkit
            val opts = TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
            val tr = Translation.getClient(opts)
            try {
                tr.downloadModelIfNeeded().await()
                val out = tr.translate(text).await()
                Result.success(out)
            } finally {
                tr.close()
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}

class LibreTranslateEngine(
    private val endpoint: String,
    private val apiKey: String = "",
) : TranslateEngine {
    override val name = "LibreTranslate"

    override suspend fun translate(text: String, from: String, to: String): Result<String> {
        return try {
            // join paths safely: URI.resolve("/translate") would REPLACE any path the
            // user entered (e.g. https://host/lt -> https://host/translate), breaking
            // reverse-proxied instances.
            val base = endpoint.trim().trimEnd('/')
            val url = java.net.URI(base + "/translate").toURL()
            val conn = url.openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/json")
                // LibreTranslate has no "yue"; zh covers Cantonese adequately
                val srcCode = if (from == "yue") "zh" else from
                val tgtCode = if (to == "yue") "zh" else to
                val body = org.json.JSONObject().apply {
                    put("q", text)
                    put("source", srcCode)
                    put("target", tgtCode)
                    put("format", "text")
                    if (apiKey.isNotBlank()) put("api_key", apiKey)
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val resp = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                else conn.errorStream?.bufferedReader()?.readText() ?: ""
                val translated = parseJsonField(resp, "translatedText")
                if (code in 200..299 && translated != null) Result.success(translated)
                else Result.failure(java.io.IOException("LT HTTP $code $resp"))
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}

class LlmEngine(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String = "",
    private val noThinkMode: String = "all", // all | chat_template_kwargs | none
) : TranslateEngine {
    override val name = "LLM"

    override suspend fun translate(text: String, from: String, to: String): Result<String> {
        return try {
            val url = java.net.URI(trimSlash(baseUrl) + "/v1/chat/completions").toURL()
            val conn = url.openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 30000
                conn.setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
                val prompt = "Translate this ${nameOf(from)} sentence into ${nameOf(to)}. " +
                    "Reply with the translation ONLY — no pinyin, no notes, no quotes.\n\n$text"
                // org.json escapes & validates for us (hand-rolled body had a quote-doubling bug -> vLLM 400)
                val body = org.json.JSONObject().apply {
                    put("model", model)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "system")
                            put("content", "You are a professional subtitle translator. Output only the translation.")
                        })
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                    put("temperature", 0.1)
                    put("max_tokens", 256)
                    put("stream", false)
                    if (noThinkMode != "none") {
                        // "quiet" = vLLM-family only (safe default; OpenAI's strict
                        // API 400s on unknown fields, so full arsenal is opt-in).
                        if (noThinkMode == "all" || noThinkMode == "quiet") {
                            put("chat_template_kwargs", org.json.JSONObject().put("enable_thinking", false))
                        }
                        if (noThinkMode == "all") {
                            // belt-and-suspenders extras for lenient gateways
                            put("reasoning_effort", "low")
                            put("thinking", org.json.JSONObject().put("type", "disabled"))
                            put("reasoning", org.json.JSONObject().put("enabled", false))
                            put("disable_thinking", true)
                        }
                    }
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                var code = conn.responseCode
                var resp = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                else conn.errorStream?.bufferedReader()?.readText() ?: ""
                // Some strict OpenAI-compatible servers reject unknown fields (400).
                // Retry once with a minimal body (no no-think extras) before failing.
                if (code == 400 && noThinkMode != "none") {
                    runCatching {
                        val minimal = org.json.JSONObject().apply {
                            put("model", model)
                            put("messages", org.json.JSONArray().apply {
                                put(org.json.JSONObject().apply {
                                    put("role", "system")
                                    put("content", "You are a professional subtitle translator. Output only the translation.")
                                })
                                put(org.json.JSONObject().apply {
                                    put("role", "user")
                                    put("content", prompt)
                                })
                            })
                            put("temperature", 0.1)
                            put("max_tokens", 256)
                        }
                        val retry = (url.openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "POST"
                            doOutput = true
                            connectTimeout = 8000
                            readTimeout = 30000
                            setRequestProperty("Content-Type", "application/json")
                            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                        }
                        retry.outputStream.use { it.write(minimal.toString().toByteArray(Charsets.UTF_8)) }
                        code = retry.responseCode
                        resp = if (code in 200..299) retry.inputStream.bufferedReader().readText()
                        else retry.errorStream?.bufferedReader()?.readText() ?: ""
                        retry.disconnect()
                    }
                }
                val content = extractChatContent(resp)
                if (code in 200..299 && content != null) Result.success(content.trim())
                else Result.failure(java.io.IOException("LLM HTTP $code $resp"))
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun trimSlash(s: String) = s.trimEnd('/')

    private fun nameOf(code: String) = when (code) {
        "zh" -> "Chinese"; "en" -> "English"; "ja" -> "Japanese"
        "ko" -> "Korean"; "yue" -> "Cantonese"; else -> code
    }
}

/** Naive JSON field extraction without deps (LLM/LT responses are flat enough for this) */
internal fun parseJsonField(json: String, field: String): String? {
    val needle = "\"$field\""
    val i = json.indexOf(needle)
    if (i < 0) return null
    var j = json.indexOf(':', i + needle.length)
    if (j < 0) return null
    j++
    while (j < json.length && json[j] == ' ') j++
    if (j >= json.length) return null
    return when {
        json[j] == '"' -> {
            val sb = StringBuilder()
            j++
            while (j < json.length) {
                val c = json[j]
                if (c == '\\' && j + 1 < json.length) {
                    when (json[j + 1]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'u' -> {
                            if (j + 5 < json.length) {
                                sb.append(json.substring(j + 2, j + 6).toInt(16).toChar()); j += 4
                            }
                        }
                        else -> sb.append(json[j + 1])
                    }
                    j += 2
                } else if (c == '"') break
                else { sb.append(c); j++ }
            }
            sb.toString()
        }
        else -> {
            var k = j
            while (k < json.length && json[k] != ',' && json[k] != '}') k++
            if (k == j) null else json.substring(j, k).trim()
        }
    }
}

internal fun extractChatContent(json: String): String? {
    return try {
        val arr = org.json.JSONObject(json).getJSONArray("choices")
        arr.getJSONObject(0).getJSONObject("message").optString("content")
    } catch (_: Exception) {
        // fallback: naive scan (keep old behavior for non-standard payloads)
        val i = json.indexOf("\"content\"")
        if (i < 0) null else parseJsonField(json.substring(i), "content")
    }
}
