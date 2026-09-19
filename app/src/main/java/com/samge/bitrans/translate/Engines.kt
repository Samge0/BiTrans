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
            val src = TranslateLanguage.fromLanguageTag(Locale.forLanguageTag(from).toLanguageTag())
                ?: return Result.failure(IllegalArgumentException("mlkit unsupported source $from"))
            val tgt = TranslateLanguage.fromLanguageTag(Locale.forLanguageTag(to).toLanguageTag())
                ?: return Result.failure(IllegalArgumentException("mlkit unsupported target $to"))
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
            val url = java.net.URI(endpoint).resolve("/translate").toURL()
            val conn = url.openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/json")
                val body = buildString {
                    append("{\"q\":")
                    append(jsonEscape(text))
                    append(",\"source\":\"").append(from).append("\",\"target\":\"").append(to).append("\",\"format\":\"text\"")
                    if (apiKey.isNotBlank()) append(",\"api_key\":\"").append(jsonEscape(apiKey)).append("\"")
                    append("}")
                }
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
                val prompt = "You are a professional interpreter. Translate the following " +
                    "$from speech transcript into $to. Output ONLY the translation, no explanations.\n\n$text"
                val body = "{\"model\":\"" + jsonEscape(model) + "\",\"messages\":[{\"role\":\"system\",\"content\":\"You are a translator.\"}," +
                    "{\"role\":\"user\",\"content\":" + jsonEscape(prompt) + "}],\"temperature\":0.2,\"max_tokens\":512}"
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val resp = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                else conn.errorStream?.bufferedReader()?.readText() ?: ""
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
    val i = json.indexOf("\"content\"")
    if (i < 0) return null
    return parseJsonField(json.substring(i), "content")
}

internal fun jsonEscape(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}
