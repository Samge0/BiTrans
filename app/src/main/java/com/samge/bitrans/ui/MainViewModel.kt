package com.samge.bitrans.ui

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.samge.bitrans.core.AsrEngine
import com.samge.bitrans.core.MicListener
import com.samge.bitrans.core.ModelStore
import com.samge.bitrans.data.Caption
import com.samge.bitrans.translate.TargetLang
import com.samge.bitrans.translate.TranslateConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

sealed interface UiState {
    data object NeedsModels : UiState
    data class Downloading(val done: Long, val total: Long, val error: String? = null) : UiState
    data object Ready : UiState
}

data class AppSettings(
    val engineKind: String,
    val target: String,
    val source: String,
    val ltEndpoint: String,
    val llmBase: String,
    val llmModel: String,
    val llmKey: String,
    val tts: Boolean,
    val overlayOn: Boolean,
    val overlayW: Int,
    val overlayFont: Int,
    val overlayAlpha: Int,
    val autoScroll: Boolean,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow<UiState>(UiState.NeedsModels)
    val ui: StateFlow<UiState> = _ui

    private val _captions = MutableStateFlow<List<Caption>>(emptyList())
    val captions: StateFlow<List<Caption>> = _captions

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings

    private var mic: MicListener? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    companion object {
        /** reserved id for the in-flight streaming (partial) caption */
        const val PROVISIONAL_ID = -1L
    }

    init {
        refreshState()
        initTts()
    }

    private fun ctx(): Context = getApplication()

    fun refreshState() {
        _ui.value = if (ModelStore.asrReady(ctx())) UiState.Ready else UiState.NeedsModels
    }

    fun downloadModels() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = UiState.Downloading(0, 1)
            val r = ModelStore.downloadAll(ctx()) { d, t ->
                _ui.value = UiState.Downloading(d, t)
            }
            r.onSuccess {
                withContext(Dispatchers.Main) { refreshState() }
            }.onFailure { t ->
                _ui.value = UiState.Downloading(0, 1, t.message ?: "error")
            }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(ctx()) { code ->
            ttsReady = code == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.US
            }
        }
    }

    private fun speak(text: String, lang: String) {
        if (!ttsReady) return
        try {
            tts?.language = when (lang) {
                "zh", "yue" -> Locale.CHINA
                "ja" -> Locale.JAPAN
                "ko" -> Locale.KOREA
                else -> Locale.US
            }
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "cap")
        } catch (_: Exception) {
        }
    }

    fun toggleListening() {
        if (_listening.value) {
            mic?.stop()
            mic = null
            _listening.value = false
            _status.value = ""
            com.samge.bitrans.listen.ListenService.stop(ctx())
        } else {
            // keep process alive while user switches to Hilokal
            try { com.samge.bitrans.listen.ListenService.start(ctx()) } catch (_: Exception) {}
            mic = MicListener(
                context = ctx(),
                onSegment = { samples, _ -> handleSegment(samples) },
                onPartial = { samples -> handlePartial(samples) },
                onPartialLevel = { _level.value = it },
            ).also {
                it.start()
                _listening.value = true
                // pre-build the recognizer for the configured source language
                AsrEngine.warmUp(ctx(), TranslateConfig.sourceLang(ctx()))
            }
        }
    }

    /** Streaming: decode the growing buffer, show/refresh a provisional caption.
     *  Translation follows dynamically with throttling (only when text actually changed). */
    private fun handlePartial(samples: FloatArray) {
        viewModelScope.launch(Dispatchers.Default) {
            val srcLang = TranslateConfig.sourceLang(ctx())
            val (text, lang) = try {
                AsrEngine.decode(ctx(), samples, srcLang)
            } catch (t: Throwable) {
                return@launch
            }
            if (text.isBlank()) return@launch
            // replace the current provisional caption (same growing utterance)
            val provisionalId = PROVISIONAL_ID
            val existing = _captions.value.firstOrNull { it.id == provisionalId }
            val prevText = existing?.source ?: ""
            val cap = if (existing != null) {
                existing.copy(source = text, langTag = lang, target = "", pending = true)
            } else {
                Caption(id = provisionalId, source = text, langTag = lang)
            }
            _captions.value = listOf(cap) + _captions.value.filter { it.id != provisionalId }
            if (TranslateConfig.overlayEnabled(ctx())) {
                com.samge.bitrans.overlay.OverlayService.push(text, "…")
            }
            // translate partials too — but only when the text meaningfully changed
            if (text != prevText) translateCaption(cap, isPartial = true)
        }
    }

    private fun handleSegment(samples: FloatArray) {
        if (samples.size < 16000 * 3 / 10) return // <0.3s noise
        viewModelScope.launch(Dispatchers.Default) {
            val srcLang = TranslateConfig.sourceLang(ctx())
            val (text, lang) = try {
                AsrEngine.decode(ctx(), samples, srcLang)
            } catch (t: Throwable) {
                _status.value = "ASR: ${t.message}"
                return@launch
            }
            if (text.isBlank()) return@launch
            // authoritative final: replace the provisional caption with a real one
            _captions.value = _captions.value.filter { it.id != PROVISIONAL_ID }
            val cap = Caption(source = text, langTag = lang)
            _captions.value = listOf(cap) + _captions.value.take(199)
            if (TranslateConfig.overlayEnabled(ctx())) {
                com.samge.bitrans.overlay.OverlayService.push(text, "")
            }
            translateCaption(cap)
        }
    }

    private fun translateCaption(cap: Caption, isPartial: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetCode = TranslateConfig.targetLang(ctx())
            val sourceCode = TranslateConfig.sourceLang(ctx())
            val engine = TranslateConfig.currentEngine(ctx())
            val effectiveSource = if (sourceCode == "auto") cap.langTag else sourceCode
            val skip = effectiveSource == targetCode ||
                (effectiveSource == "yue" && targetCode == "zh") ||
                (effectiveSource == "zh" && targetCode == "yue")
            val result = if (skip) Result.success(cap.source) else engine.translate(cap.source, effectiveSource, targetCode)
            val translated = result.getOrDefault("")
            val updated = _captions.value.map {
                // partial results update the provisional slot; finals match by real id
                if ((isPartial && it.id == PROVISIONAL_ID) || it.id == cap.id) {
                    it.copy(target = translated, pending = false)
                } else it
            }
            _captions.value = updated
            // push to global overlay if enabled
            if (TranslateConfig.overlayEnabled(ctx())) {
                com.samge.bitrans.overlay.OverlayService.push(cap.source, translated)
            }
            result.onSuccess { t ->
                if (!isPartial && t.isNotBlank() && TranslateConfig.ttsEnabled(ctx())) speak(t, targetCode)
            }.onFailure { e ->
                // stale-partial failures are noise; surface only final failures
                if (!isPartial) _status.value = "翻译(${engine.name}): ${e.message}"
            }
        }
    }

    /** Self-test the configured engine with a fixed probe sentence. */
    fun runEngineSelfTest() {
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = "引擎自测中…"
            val engine = TranslateConfig.currentEngine(ctx())
            val target = TranslateConfig.targetLang(ctx())
            val probe = "Hello world, this is a translation test."
            val r = engine.translate(probe, "en", target)
            r.onSuccess {
                _status.value = "自测通过(${engine.name}): $it"
            }.onFailure {
                _status.value = "自测失败(${engine.name}): ${it.message?.take(80)}"
            }
        }
    }

    /** E2E probe without mic: inject sentences as if ASR produced them (5 quick taps on start). */
    fun injectTestUtterances() {
        val probes = listOf(
            Caption(source = "今天天气不错，我们去公园散步吧。", langTag = "zh"),
            Caption(source = "The tribal chieftain called for the boy.", langTag = "en"),
        )
        _status.value = "已注入测试句(不经过ASR)"
        probes.forEach { cap ->
            _captions.value = listOf(cap) + _captions.value.take(199)
            translateCaption(cap)
        }
    }

    /** UI toggle: auto-follow newest caption vs manual browsing (persisted immediately) */
    fun setAutoScroll(v: Boolean) {
        TranslateConfig.setAutoScroll(ctx(), v)
        _settings.value = loadSettings()
    }

    fun clearCaptions() {
        _captions.value = emptyList()
    }

    fun updateSettings(s: AppSettings) {
        TranslateConfig.setEngineKind(ctx(), s.engineKind)
        TranslateConfig.setTargetLang(ctx(), s.target)
        TranslateConfig.setSourceLang(ctx(), s.source)
        TranslateConfig.setLtEndpoint(ctx(), s.ltEndpoint)
        TranslateConfig.setLlmBaseUrl(ctx(), s.llmBase)
        TranslateConfig.setLlmModel(ctx(), s.llmModel)
        TranslateConfig.setLlmApiKey(ctx(), s.llmKey)
        TranslateConfig.setTtsEnabled(ctx(), s.tts)
        TranslateConfig.setOverlayEnabled(ctx(), s.overlayOn)
        TranslateConfig.setOverlayWidth(ctx(), s.overlayW)
        TranslateConfig.setOverlayFont(ctx(), s.overlayFont)
        TranslateConfig.setOverlayAlpha(ctx(), s.overlayAlpha)
        TranslateConfig.setAutoScroll(ctx(), s.autoScroll)
        _settings.value = loadSettings()
        // sync overlay lifecycle with the setting
        if (s.overlayOn) {
            com.samge.bitrans.overlay.OverlayService.start(ctx())
        } else {
            com.samge.bitrans.overlay.OverlayService.stop(ctx())
        }
    }

    private fun loadSettings(): AppSettings = AppSettings(
        engineKind = TranslateConfig.engineKind(ctx()),
        target = TranslateConfig.targetLang(ctx()),
        source = TranslateConfig.sourceLang(ctx()),
        ltEndpoint = TranslateConfig.ltEndpoint(ctx()),
        llmBase = TranslateConfig.llmBaseUrl(ctx()),
        llmModel = TranslateConfig.llmModel(ctx()),
        llmKey = TranslateConfig.llmApiKey(ctx()),
        tts = TranslateConfig.ttsEnabled(ctx()),
        overlayOn = TranslateConfig.overlayEnabled(ctx()),
        overlayW = TranslateConfig.overlayWidth(ctx()),
        overlayFont = TranslateConfig.overlayFont(ctx()),
        overlayAlpha = TranslateConfig.overlayAlpha(ctx()),
        autoScroll = TranslateConfig.autoScroll(ctx()),
    )

    override fun onCleared() {
        mic?.stop()
        tts?.shutdown()
        AsrEngine.shutdown()
        super.onCleared()
    }
}
