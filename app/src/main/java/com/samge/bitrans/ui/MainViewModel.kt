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
    val ltEndpoint: String,
    val llmBase: String,
    val llmModel: String,
    val tts: Boolean,
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
                onPartialLevel = { _level.value = it },
            ).also {
                it.start()
                _listening.value = true
            }
        }
    }

    private fun handleSegment(samples: FloatArray) {
        if (samples.size < 16000 * 3 / 10) return // <0.3s noise
        viewModelScope.launch(Dispatchers.Default) {
            val (text, lang) = try {
                AsrEngine.decode(ctx(), samples)
            } catch (t: Throwable) {
                _status.value = "ASR: ${t.message}"
                return@launch
            }
            if (text.isBlank()) return@launch
            val cap = Caption(source = text, langTag = lang)
            _captions.value = listOf(cap) + _captions.value.take(199)
            translateCaption(cap)
        }
    }

    private fun translateCaption(cap: Caption) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetCode = TranslateConfig.targetLang(ctx())
            val engine = TranslateConfig.currentEngine(ctx())
            val skip = cap.langTag == targetCode ||
                (cap.langTag == "yue" && targetCode == "zh") ||
                (cap.langTag == "zh" && targetCode == "yue")
            val result = if (skip) Result.success(cap.source) else engine.translate(cap.source, cap.langTag, targetCode)
            val updated = _captions.value.map {
                if (it.id == cap.id) it.copy(target = result.getOrDefault(""), pending = false) else it
            }
            _captions.value = updated
            result.onSuccess { t ->
                if (t.isNotBlank() && TranslateConfig.ttsEnabled(ctx())) speak(t, targetCode)
            }.onFailure { e ->
                _status.value = "翻译(${engine.name}): ${e.message}"
            }
        }
    }

    fun clearCaptions() {
        _captions.value = emptyList()
    }

    fun updateSettings(s: AppSettings) {
        TranslateConfig.setEngineKind(ctx(), s.engineKind)
        TranslateConfig.setTargetLang(ctx(), s.target)
        TranslateConfig.setLtEndpoint(ctx(), s.ltEndpoint)
        TranslateConfig.setLlmBaseUrl(ctx(), s.llmBase)
        TranslateConfig.setLlmModel(ctx(), s.llmModel)
        TranslateConfig.setTtsEnabled(ctx(), s.tts)
        _settings.value = loadSettings()
    }

    private fun loadSettings(): AppSettings = AppSettings(
        engineKind = TranslateConfig.engineKind(ctx()),
        target = TranslateConfig.targetLang(ctx()),
        ltEndpoint = TranslateConfig.ltEndpoint(ctx()),
        llmBase = TranslateConfig.llmBaseUrl(ctx()),
        llmModel = TranslateConfig.llmModel(ctx()),
        tts = TranslateConfig.ttsEnabled(ctx()),
    )

    override fun onCleared() {
        mic?.stop()
        tts?.shutdown()
        AsrEngine.shutdown()
        super.onCleared()
    }
}
