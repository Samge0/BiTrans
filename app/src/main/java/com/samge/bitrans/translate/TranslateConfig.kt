package com.samge.bitrans.translate

import android.content.Context
import android.content.SharedPreferences

/**
 * Engine selection + params persistence. Order of preference built at runtime:
 * if ML Kit model download works → offline; else user-configured remote engine.
 */
object TranslateConfig {
    private const val PREFS = "bistrans"

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun engineKind(ctx: Context): String = prefs(ctx).getString("engine", "mlkit") ?: "mlkit"
    fun setEngineKind(ctx: Context, v: String) = prefs(ctx).edit().putString("engine", v).apply()

    fun targetLang(ctx: Context): String = prefs(ctx).getString("target", "en") ?: "en"
    fun setTargetLang(ctx: Context, v: String) = prefs(ctx).edit().putString("target", v).apply()

    fun ltEndpoint(ctx: Context): String = prefs(ctx).getString("lt_endpoint", "https://translate.disroot.org") ?: "https://translate.disroot.org"
    fun setLtEndpoint(ctx: Context, v: String) = prefs(ctx).edit().putString("lt_endpoint", v).apply()

    fun ltApiKey(ctx: Context): String = prefs(ctx).getString("lt_key", "") ?: ""
    fun setLtApiKey(ctx: Context, v: String) = prefs(ctx).edit().putString("lt_key", v).apply()

    fun llmBaseUrl(ctx: Context): String = prefs(ctx).getString("llm_base", "http://192.168.50.48:16869") ?: "http://192.168.50.48:16869"
    fun setLlmBaseUrl(ctx: Context, v: String) = prefs(ctx).edit().putString("llm_base", v).apply()

    fun llmModel(ctx: Context): String = prefs(ctx).getString("llm_model", "qwen38") ?: "qwen38"
    fun setLlmModel(ctx: Context, v: String) = prefs(ctx).edit().putString("llm_model", v).apply()

    fun llmApiKey(ctx: Context): String = prefs(ctx).getString("llm_key", "") ?: ""
    fun setLlmApiKey(ctx: Context, v: String) = prefs(ctx).edit().putString("llm_key", v).apply()

    fun ttsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("tts", true)
    fun setTtsEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("tts", v).apply()

    fun currentEngine(ctx: Context): TranslateEngine = when (engineKind(ctx)) {
        "libre" -> LibreTranslateEngine(ltEndpoint(ctx), ltApiKey(ctx))
        "llm" -> LlmEngine(llmBaseUrl(ctx), llmModel(ctx), llmApiKey(ctx))
        else -> MlKitEngine(ctx)
    }
}
