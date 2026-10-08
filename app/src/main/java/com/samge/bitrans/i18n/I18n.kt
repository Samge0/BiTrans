package com.samge.bitrans.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.samge.bitrans.translate.TranslateConfig

/**
 * App display language. "system" follows the OS locale; others are fixed.
 */
enum class AppLang(val code: String, val nativeName: String) {
    SYSTEM("system", "Follow system"),
    ZH("zh", "中文"),
    EN("en", "English"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
    ZH_TW("zh-TW", "繁體中文");

    companion object {
        fun fromCode(c: String): AppLang = entries.firstOrNull { it.code == c } ?: SYSTEM
    }
}

/** Resolved concrete language for lookups (never SYSTEM). */
enum class DictLang { ZH, EN, JA, KO, ZH_TW }

/**
 * Compose-observable snapshot of the resolved display language.
 * Initialized by MainActivity BEFORE first composition (zero-flash);
 * updated live when the user changes the language in Settings.
 */
object I18nState {
    var dictLang: DictLang by mutableStateOf(DictLang.ZH)
        private set

    /** Resolve from a persisted pref code ("system" or concrete). */
    fun update(ctx: Context, prefCode: String) {
        dictLang = I18n.resolveFor(ctx, prefCode)
    }
}

object I18n {
    /** Current persisted preference ("system" or a concrete code). */
    fun prefCode(ctx: Context): String = TranslateConfig.appLang(ctx)

    fun pref(ctx: Context): AppLang = AppLang.fromCode(prefCode(ctx))

    /** Resolve the effective dictionary language for this device right now. */
    fun resolve(ctx: Context): DictLang = resolveFor(ctx, prefCode(ctx))

    /** Resolve from an explicit pref code (no prefs read). */
    fun resolveFor(ctx: Context, prefCode: String): DictLang = when (AppLang.fromCode(prefCode)) {
        AppLang.SYSTEM -> {
            val locale = ctx.resources.configuration.locales[0]
            when {
                locale.language == "zh" && locale.country in setOf("TW", "HK", "MO") -> DictLang.ZH_TW
                locale.language == "zh" -> DictLang.ZH
                locale.language == "ja" -> DictLang.JA
                locale.language == "ko" -> DictLang.KO
                else -> DictLang.EN  // zh/en/ja/ko handled; everything else → English
            }
        }
        AppLang.ZH -> DictLang.ZH
        AppLang.EN -> DictLang.EN
        AppLang.JA -> DictLang.JA
        AppLang.KO -> DictLang.KO
        AppLang.ZH_TW -> DictLang.ZH_TW
    }

    private fun lookup(lang: DictLang, key: String): String = when (lang) {
        DictLang.ZH -> key
        DictLang.EN -> DictEn[key] ?: key
        DictLang.JA -> DictJa[key] ?: key
        DictLang.KO -> DictKo[key] ?: key
        DictLang.ZH_TW -> DictZhTw[key] ?: key
    }

    /** Non-compose lookup (services, notifications, ViewModel status text). */
    fun t(ctx: Context, key: String): String = lookup(resolve(ctx), key)
}

/** Compose-side translate: Chinese source string is the key. */
@Composable
fun t(key: String): String {
    val lang = I18nState.dictLang
    return remember(lang, key) {
        when (lang) {
            DictLang.ZH -> key
            DictLang.EN -> DictEn[key] ?: key
            DictLang.JA -> DictJa[key] ?: key
            DictLang.KO -> DictKo[key] ?: key
            DictLang.ZH_TW -> DictZhTw[key] ?: key
        }
    }
}

/** With-args translate (non-compose). */
fun t(ctx: Context, key: String, vararg args: Any?): String = format(I18n.t(ctx, key), args)

/** Interpolate {0},{1}… after dictionary lookup. */
fun format(template: String, vararg args: Any?): String {
    var out = template
    args.forEachIndexed { i, a -> out = out.replace("{$i}", a.toString()) }
    return out
}

/** Locale for date formatting that matches the display language. */
fun dateLang(ctx: Context): java.util.Locale = when (I18n.resolve(ctx)) {
    DictLang.ZH -> java.util.Locale.CHINA
    DictLang.EN -> java.util.Locale.ENGLISH
    DictLang.JA -> java.util.Locale.JAPAN
    DictLang.KO -> java.util.Locale.KOREA
    DictLang.ZH_TW -> java.util.Locale.TAIWAN
}
