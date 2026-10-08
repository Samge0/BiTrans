package com.samge.bitrans.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.samge.bitrans.core.AsrEngine
import com.samge.bitrans.core.MicListener
import com.samge.bitrans.core.ModelStore
import com.samge.bitrans.data.Caption
import com.samge.bitrans.translate.TargetLang
import com.samge.bitrans.i18n.I18n
import com.samge.bitrans.i18n.format
import com.samge.bitrans.translate.TranslateConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import android.util.Log
import android.widget.Toast
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
    val llmNoThink: String,
    val tts: Boolean,
    val overlayOn: Boolean,
    val overlayW: Int,
    val overlayFont: Int,
    val overlayAlpha: Int,
    val overlayLines: Int,
    val autoScroll: Boolean,
    val translateOn: Boolean,
    val appLang: String = "system",
) {
    /** JSON for export — deliberately excludes apiKey (security) */
    fun toJson(): String = org.json.JSONObject().apply {
        put("app", "BiTrans")
        put("schema", 2)
        put("engineKind", engineKind)
        put("target", target)
        put("source", source)
        put("ltEndpoint", ltEndpoint)
        put("llmBase", llmBase)
        put("llmModel", llmModel)
        put("llmNoThink", llmNoThink)
        put("tts", tts)
        put("overlayOn", overlayOn)
        put("overlayW", overlayW)
        put("overlayFont", overlayFont)
        put("overlayAlpha", overlayAlpha)
        put("overlayLines", overlayLines)
        put("autoScroll", autoScroll)
        put("translateOn", translateOn)
        put("appLang", appLang)
    }.toString(2)

    companion object {
        fun fromJson(json: String): AppSettings? {
            return try {
                val o = org.json.JSONObject(json)
                if (o.optString("app") != "BiTrans") null else AppSettings(
                    engineKind = o.optString("engineKind", "mlkit"),
                    target = o.optString("target", "en"),
                    source = o.optString("source", "auto"),
                    ltEndpoint = o.optString("ltEndpoint", "https://translate.disroot.org"),
                    llmBase = o.optString("llmBase", "http://192.168.50.48:16868"),
                    llmModel = o.optString("llmModel", "qwen38"),
                    llmKey = "", // never imported
                    llmNoThink = o.optString("llmNoThink", "all"),
                    tts = o.optBoolean("tts", true),
                    overlayOn = o.optBoolean("overlayOn", false),
                    overlayW = o.optInt("overlayW", 92),
                    overlayFont = o.optInt("overlayFont", 14),
                    overlayAlpha = o.optInt("overlayAlpha", 60),
                    overlayLines = o.optInt("overlayLines", 1),
                    autoScroll = o.optBoolean("autoScroll", true),
                    translateOn = o.optBoolean("translateOn", true),
                    appLang = o.optString("appLang", "system"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

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

    /** one-shot: true when a stopped session has content awaiting a save decision */
    private val _askSaveSession = MutableStateFlow(false)
    val askSaveSession: StateFlow<Boolean> = _askSaveSession

    fun confirmSaveSession() {
        _askSaveSession.value = false
        saveSessionToDb()
    }

    fun discardSession() {
        _askSaveSession.value = false
        _captions.value = emptyList()
    }

    private var mic: MicListener? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sessionStartAt = 0L

    /** guards all read-modify-write cycles on _captions (partial/final/translate race) */
    private val captionsMutex = kotlinx.coroutines.sync.Mutex()

    companion object {
        /** reserved id for the in-flight streaming (partial) caption */
        const val PROVISIONAL_ID = -1L

        /** monotonic unique caption ids — currentTimeMillis collides within same ms (LazyColumn duplicate-key crash) */
        private val nextId = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        fun newCaptionId(): Long = nextId.incrementAndGet()

        /** bumped by every final: in-flight partial decodes whose epoch mismatches are stale */
        private val partialCounter = java.util.concurrent.atomic.AtomicLong(0)
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

    /** capture mode: "mic" (default) or "playback" (reverse capture of phone audio) */
    private val _captureMode = MutableStateFlow(
        runCatching { TranslateConfig.captureMode(getApplication()) }.getOrDefault("mic")
    )
    val captureMode: StateFlow<String> = _captureMode

    fun setCaptureMode(mode: String) {
        TranslateConfig.setCaptureMode(ctx(), mode)
        _captureMode.value = mode
    }

    /** Start listening using the PLAYBACK (reverse) capture pipeline. Called after
     *  the MediaProjection consent result arrives. */
    fun startListeningWithPlaybackCapture() {
        if (_listening.value) return
        try { com.samge.bitrans.listen.ListenService.start(ctx()) } catch (_: Exception) {}
        sessionStartAt = System.currentTimeMillis()
        _captions.value = emptyList()
        com.samge.bitrans.overlay.OverlayService.clear()
        mic = MicListener(
            context = ctx(),
            onSegment = { samples, _ -> handleSegment(samples) },
            onPartial = { samples -> handlePartial(samples, partialCounter.get()) },
            onPartialLevel = { _level.value = it },
            onSilenced = {
                _status.value = I18n.t(ctx(), "未采集到播放声。请先用浏览器播放任意视频测试：若视频能出字幕而语音房不能，说明 Hilokal 的声音被标记为通话类（系统禁止捕获，需换方案）")
            },
            externalRecorder = com.samge.bitrans.listen.PlaybackCaptureService.reader16k(),
        ).also {
            it.start()
            _listening.value = true
            _status.value = I18n.t(ctx(), "反向采集模式：翻译手机播放的声音")
        }
    }

    fun toggleListening() {
        if (_listening.value) {
            mic?.stop()
            mic = null
            _listening.value = false
            _status.value = ""  // v1.4.4: keep the status row empty so capture chips stay visible
            com.samge.bitrans.listen.ListenService.stop(ctx())
            if (_captureMode.value == "playback") {
                com.samge.bitrans.listen.PlaybackCaptureService.stop(ctx())
            }
            // ask before saving (UI shows a confirm dialog when there is content)
            _askSaveSession.value = _captions.value.isNotEmpty()
        } else {
            // keep process alive while user switches to Hilokal
            try { com.samge.bitrans.listen.ListenService.start(ctx()) } catch (_: Exception) {}
            sessionStartAt = System.currentTimeMillis()
            // fresh session: drop the previous transcript so different sessions
            // never merge into one saved record
            _captions.value = emptyList()
            com.samge.bitrans.overlay.OverlayService.clear()
            mic = MicListener(
                context = ctx(),
                onSegment = { samples, _ -> handleSegment(samples) },
                onPartial = { samples -> handlePartial(samples, partialCounter.get()) },
                onPartialLevel = { _level.value = it },
                onSilenced = {
                    _status.value = I18n.t(ctx(), "麦克风被前台应用占用（你开了语音房麦克风）。语言房正确姿势：手机外放 + 听对方时关自己的麦，BiTrans 会自动恢复翻译对方的声音")
                },
            ).also {
                it.start()
                _listening.value = true
                // pre-build the recognizer for the configured source language
                AsrEngine.warmUp(ctx(), TranslateConfig.sourceLang(ctx()))
            }
        }
    }

    /** Persist the finished session (captions incl. timestamps) to Room.
     *  v1.4.4: result is a Toast (not the status row) — writing the status row
     *  replaced the capture-source chips and visually blocked them. */
    private fun saveSessionToDb() {
        val caps = _captions.value
        if (caps.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var sid = -1L
            try {
                val dao = com.samge.bitrans.data.AppDatabase.get(ctx()).captionDao()
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val start = sessionStartAt.takeIf { it > 0 } ?: caps.last().ts
                sid = dao.insertSession(
                    com.samge.bitrans.data.Session(
                        title = fmt.format(java.util.Date(start)),
                        startedAt = start,
                        endedAt = System.currentTimeMillis(),
                    )
                )
                // captions list is newest-first; persist oldest-first
                dao.insertItems(caps.reversed().map { c ->
                    com.samge.bitrans.data.CaptionItem(
                        sessionId = sid,
                        ts = c.ts,
                        source = c.source,
                        langTag = c.langTag,
                        target = c.target,
                    )
                })
            } catch (t: Throwable) {
                Log.w("BiTrans", "saveSession failed", t)
            }
            if (sid > 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), I18n.t(getApplication(), "记录已保存到历史"), Toast.LENGTH_SHORT).show()
                }
                // auto-generate a short title with the LLM when configured
                if (llmConfigured()) generateTitleWithLlm(sid)
            }
        }
    }

    /** Streaming: decode the growing buffer, show/refresh a provisional caption.
     *  Translation follows dynamically with throttling (only when text actually changed). */
    private fun handlePartial(samples: FloatArray, partialEpoch: Long) {
        viewModelScope.launch(Dispatchers.Default) {
            val srcLang = TranslateConfig.sourceLang(ctx())
            val (text, lang) = try {
                AsrEngine.decode(ctx(), samples, srcLang)
            } catch (t: Throwable) {
                return@launch
            }
            if (text.isBlank()) return@launch
            // STALE-PARTIAL GUARD: a final (or newer utterance) landed while this
            // partial was decoding -> drop it instead of overwriting newer content
            if (partialEpoch != partialCounter.get()) return@launch
            var prevText = ""
            var cap: Caption
            captionsMutex.withLock {
                val provisionalId = PROVISIONAL_ID
                val existing = _captions.value.firstOrNull { it.id == provisionalId }
                prevText = existing?.source ?: ""
                cap = if (existing != null) {
                    existing.copy(source = text, langTag = lang, target = "", pending = true)
                } else {
                    Caption(id = provisionalId, source = text, langTag = lang)
                }
                _captions.value = listOf(cap) + _captions.value.filter { it.id != provisionalId }
            }
            val overlayOk = TranslateConfig.overlayEnabled(ctx()) &&
                android.provider.Settings.canDrawOverlays(ctx())
            if (overlayOk) {
                syncOverlay()
            } else if (_listening.value) {
                com.samge.bitrans.listen.ListenService.updateCaption(ctx(), text, "")
            }
            // translate partials too — but only when the text meaningfully changed
            if (text != prevText) translateCaption(cap!!, isPartial = true)
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
            // invalidate any in-flight partial decodes (they are stale now)
            partialCounter.incrementAndGet()
            val cap = Caption(id = newCaptionId(), source = text, langTag = lang)
            captionsMutex.withLock {
                _captions.value = listOf(cap) + _captions.value
                    .filter { it.id != PROVISIONAL_ID }
                    .take(199)
            }
            val overlayOk = TranslateConfig.overlayEnabled(ctx()) &&
                android.provider.Settings.canDrawOverlays(ctx())
            if (overlayOk) {
                syncOverlay()
            } else if (_listening.value) {
                com.samge.bitrans.listen.ListenService.updateCaption(ctx(), text, "")
            }
            translateCaption(cap)
        }
    }

    private fun translateCaption(cap: Caption, isPartial: Boolean = false) {
        // master switch: some users only want to see the original text
        if (!TranslateConfig.translationEnabled(ctx())) {
            viewModelScope.launch(Dispatchers.Default) {
                captionsMutex.withLock {
                    _captions.value = _captions.value.map {
                        if (it.id == cap.id) it.copy(target = "", pending = false) else it
                    }
                }
            }
            return
        }
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
            captionsMutex.withLock {
                // STALE-WRITE GUARD: for partials, the provisional slot may already
                // hold a DIFFERENT utterance (a final landed meanwhile) — only
                // write back if the slot still shows this partial's source text.
                val updated = _captions.value.map {
                    val slotMatches = when {
                        isPartial && cap.id == PROVISIONAL_ID ->
                            it.id == PROVISIONAL_ID && it.source == cap.source
                        else -> it.id == cap.id
                    }
                    if (slotMatches) it.copy(target = translated, pending = false) else it
                }
                _captions.value = updated
            }
            val overlayOk = TranslateConfig.overlayEnabled(ctx()) &&
                android.provider.Settings.canDrawOverlays(ctx())
            // push to global overlay if enabled and permitted
            if (overlayOk) {
                syncOverlay()
            }
            // notification-shade captions: fallback when overlay is blocked,
            // or when user runs backgrounded without the overlay
            if (!overlayOk && _listening.value) {
                com.samge.bitrans.listen.ListenService.updateCaption(ctx(), cap.source, translated)
            }
            result.onSuccess { t ->
                if (!isPartial && t.isNotBlank() && TranslateConfig.ttsEnabled(ctx())) speak(t, targetCode)
            }.onFailure { e ->
                // stale-partial failures are noise; surface only final failures
                if (!isPartial) _status.value = format(I18n.t(ctx(), "翻译({0}): {1}"), I18n.t(ctx(), engine.name), e.message ?: "")
            }
        }
    }

    /** Self-test the configured engine with a fixed probe sentence. */
    fun runEngineSelfTest() {
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = I18n.t(ctx(), "引擎自测中…")
            val engine = TranslateConfig.currentEngine(ctx())
            val target = TranslateConfig.targetLang(ctx())
            val probe = "Hello world, this is a translation test."
            val r = engine.translate(probe, "en", target)
            r.onSuccess {
                _status.value = format(I18n.t(ctx(), "自测通过({0}): {1}"), I18n.t(ctx(), engine.name), it)
            }.onFailure {
                _status.value = format(I18n.t(ctx(), "自测失败({0}): {1}"), I18n.t(ctx(), engine.name), it.message?.take(80) ?: "")
            }
        }
    }

    /** E2E probe without mic: inject sentences as if ASR produced them (5 quick taps on start). */
    fun injectTestUtterances() {
        val probes = listOf(
            Caption(id = newCaptionId(), source = "今天天气不错，我们去公园散步吧。", langTag = "zh"),
            Caption(id = newCaptionId(), source = "The tribal chieftain called for the boy.", langTag = "en"),
        )
        _status.value = I18n.t(ctx(), "已注入测试句(不经过ASR)")
        probes.forEach { cap ->
            _captions.value = listOf(cap) + _captions.value.take(199)
            translateCaption(cap)
        }
    }

    /** Mirror the main transcript (incl. in-flight partial, newest last) into the overlay. */
    private fun syncOverlay() {
        val pairs = _captions.value
            .reversed()
            .map { it.source to it.target }
        com.samge.bitrans.overlay.OverlayService.sync(pairs)
    }

    /** UI toggle: auto-follow newest caption vs manual browsing (persisted immediately) */
    fun setAutoScroll(v: Boolean) {
        TranslateConfig.setAutoScroll(ctx(), v)
        _settings.value = loadSettings()
    }

    /** Save settings from the top-right action, then self-test the engine. */
    fun saveAndTest(s: AppSettings) {
        updateSettings(s)
        runEngineSelfTest()
    }

    // ---------------- history (Room) ----------------

    val sessions: StateFlow<List<com.samge.bitrans.data.Session>> =
        com.samge.bitrans.data.AppDatabase.get(getApplication()).captionDao()
            .sessionsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** caption count per session id (for list rows "xx段") */
    val sessionCaptionCounts: StateFlow<Map<Long, Int>> =
        com.samge.bitrans.data.AppDatabase.get(getApplication()).captionDao()
            .countsFlow()
            .map { rows: List<com.samge.bitrans.data.SessionCount> -> rows.associate { it.sessionId to it.count } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Generate a short title via LLM and APPLY it directly. */
    fun generateTitleWithLlm(sessionId: Long) {
        generateTitleWithLlmCallback(sessionId) { title ->
            if (title != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    com.samge.bitrans.data.AppDatabase.get(ctx()).captionDao()
                        .renameSession(sessionId, title.take(20))
                }
            }
        }
    }

    /** Generate a short title via LLM and hand it back (dialog preview flow). */
    fun generateTitleWithLlmCallback(sessionId: Long, onDone: (String?) -> Unit) {
        if (!llmConfigured()) { onDone(null); return }
        viewModelScope.launch(Dispatchers.IO) {
            var result: String? = null
            try {
                val engine = TranslateConfig.currentEngine(ctx()) as com.samge.bitrans.translate.LlmEngine
                val transcript = transcriptContext(sessionId)
                if (transcript.isNotBlank()) {
                    val lang = com.samge.bitrans.i18n.I18n.resolve(ctx())
                    val msgs = when (lang) {
                        com.samge.bitrans.i18n.DictLang.ZH -> listOf(
                            "system" to "你起标题。只输出标题本身，不要引号不要解释，10字以内。",
                            "user" to "为以下对话记录起一个简短中文标题：\n$transcript",
                        )
                        com.samge.bitrans.i18n.DictLang.ZH_TW -> listOf(
                            "system" to "你起標題。只輸出標題本身，不要引號不要解釋，10字以內。",
                            "user" to "為以下對話記錄起一個簡短繁體中文標題：\n$transcript",
                        )
                        com.samge.bitrans.i18n.DictLang.EN -> listOf(
                            "system" to "You generate titles. Output only the title itself: no quotes, no explanation, max 10 words.",
                            "user" to "Give the following conversation transcript a short title in English:\n$transcript",
                        )
                        com.samge.bitrans.i18n.DictLang.JA -> listOf(
                            "system" to "タイトルを付けてください。タイトルのみを出力し、引用符や説明は不要、10文字以内。",
                            "user" to "次の対話記録に短い日本語のタイトルを付けてください：\n$transcript",
                        )
                        com.samge.bitrans.i18n.DictLang.KO -> listOf(
                            "system" to "제목을 지어주세요. 제목만 출력하고 따옴표나 설명은 넣지 마세요. 10자 이내.",
                            "user" to "다음 대화 기록에 짧은 한국어 제목을 지어주세요:\n$transcript",
                        )
                    }
                    val title = engine.chatStream(msgs) {}.getOrDefault("")
                        .trim().trim('"', '「', '」', ' ', '\n')
                    if (title.isNotBlank()) result = title.take(20)
                }
            } catch (_: Throwable) {
            }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    fun itemsOf(sessionId: Long): Flow<List<com.samge.bitrans.data.CaptionItem>> =
        com.samge.bitrans.data.AppDatabase.get(ctx()).captionDao().itemsFlow(sessionId)

    fun renameSession(id: Long, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.samge.bitrans.data.AppDatabase.get(ctx()).captionDao()
                .renameSession(id, newTitle.trim().ifBlank { I18n.t(ctx(), "未命名") })
        }
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = com.samge.bitrans.data.AppDatabase.get(ctx()).captionDao()
            dao.deleteItemsOf(id)
            dao.deleteSession(id)
        }
    }

    /** whether the LLM engine is configured (for summary-chat gating) */
    fun llmConfigured(): Boolean =
        TranslateConfig.engineKind(ctx()) == "llm" && TranslateConfig.llmBaseUrl(ctx()).isNotBlank()

    // ---------------- summary chat (LLM) ----------------

    private val _chatMessages = MutableStateFlow<List<com.samge.bitrans.data.ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<com.samge.bitrans.data.ChatMessage>> = _chatMessages
    private val _chatStreaming = MutableStateFlow(false)
    val chatStreaming: StateFlow<Boolean> = _chatStreaming

    /**
     * Streaming deltas live on their OWN channel, NOT in _chatMessages.
     * RACE (v1.4.4 fix): the provisional streaming entry used to live inside
     * _chatMessages, but bindChat's Room Flow collector re-emits the DB list
     * asynchronously right after the user message is persisted — WIPING the
     * provisional entry mid-stream, so the typewriter never saw deltas and the
     * reply appeared in one dump. Deltas now land here; the UI composes
     * DB history + this live entry, immune to Room re-emissions.
     */
    private val _chatLiveDelta = MutableStateFlow<com.samge.bitrans.data.ChatMessage?>(null)
    val chatLiveDelta: StateFlow<com.samge.bitrans.data.ChatMessage?> = _chatLiveDelta

    fun chatFlowOf(sessionId: Long): Flow<List<com.samge.bitrans.data.ChatMessage>> =
        com.samge.bitrans.data.AppDatabase.get(ctx()).captionDao().chatFlow(sessionId)

    fun bindChat(sessionId: Long) {
        viewModelScope.launch {
            chatFlowOf(sessionId).collect { _chatMessages.value = it }
        }
    }

    fun clearChat(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            com.samge.bitrans.data.AppDatabase.get(ctx()).captionDao().clearChat(sessionId)
        }
    }

    /** Build transcript context (source+target lines) for the LLM. */
    private suspend fun transcriptContext(sessionId: Long): String {
        val dao = com.samge.bitrans.data.AppDatabase.get(ctx()).captionDao()
        val items = dao.itemsFlow(sessionId).first()
        return items.joinToString("\n") { "- [${it.langTag}] ${it.source} | ${it.target}" }
    }

    /** Auto-run the initial summary if no chat exists yet. */
    fun maybeAutoSummarize(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = com.samge.bitrans.data.AppDatabase.get(ctx()).captionDao()
            val existing = dao.chatFlow(sessionId).first()
            if (existing.isNotEmpty()) return@launch
            sendChat(sessionId, when (com.samge.bitrans.i18n.I18n.resolve(ctx())) {
                com.samge.bitrans.i18n.DictLang.ZH -> "请总结这段对话记录：讨论的主题、关键信息点、结论。用中文分点输出。"
                com.samge.bitrans.i18n.DictLang.ZH_TW -> "請總結這段對話記錄：討論的主題、關鍵資訊點、結論。用繁體中文分點輸出。"
                com.samge.bitrans.i18n.DictLang.EN -> "Summarize this conversation: the topics discussed, key points, and conclusions. Output as bullet points in English."
                com.samge.bitrans.i18n.DictLang.JA -> "この対話を要約してください：議論のテーマ、重要なポイント、結論。日本語で箇条書きに出力してください。"
                com.samge.bitrans.i18n.DictLang.KO -> "이 대화를 요약하세요: 논의된 주제, 핵심 포인트, 결론. 한국어로 항목별로 출력하세요."
            })
        }
    }

    /** Send a user message + stream the assistant reply, persisting both. */
    fun sendChat(sessionId: Long, userText: String) {
        if (_chatStreaming.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val dao = com.samge.bitrans.data.AppDatabase.get(ctx()).captionDao()
            val engine = TranslateConfig.currentEngine(ctx())
            if (engine !is com.samge.bitrans.translate.LlmEngine) {
                _status.value = I18n.t(ctx(), "请先在设置页配置 LLM 引擎后再使用总结")
                return@launch
            }
            _chatStreaming.value = true
            try {
                dao.insertChat(
                    com.samge.bitrans.data.ChatMessage(
                        sessionId = sessionId, ts = System.currentTimeMillis(),
                        role = "user", content = userText,
                    )
                )
                // build message list: transcript context (once) + prior chat + new user msg
                val prior = dao.chatFlow(sessionId).first()
                val sysPrompt = when (com.samge.bitrans.i18n.I18n.resolve(ctx())) {
                    com.samge.bitrans.i18n.DictLang.ZH -> "你是一个对话记录分析助手。用户会提供一段语音翻译记录（原文|译文 每行一条），请基于它回答问题或做总结。回答使用中文。"
                    com.samge.bitrans.i18n.DictLang.ZH_TW -> "你是一個對話記錄分析助手。用戶會提供一段語音翻譯記錄（原文|譯文 每行一條），請基於它回答問題或做總結。回答使用繁體中文。"
                    com.samge.bitrans.i18n.DictLang.EN -> "You are a conversation-transcript analyst. The user provides a speech-translation transcript (source|target, one pair per line). Answer questions or summarize based on it. Reply in English."
                    com.samge.bitrans.i18n.DictLang.JA -> "あなたは対話記録の分析アシスタントです。ユーザーは音声翻訳の記録（原文|訳文 1行1件）を提供します。それに基づいて質問に答えたり要約したりしてください。日本語で回答してください。"
                    com.samge.bitrans.i18n.DictLang.KO -> "당신은 대화 기록 분석 어시스턴트입니다. 사용자가 음성 번역 기록(원문|번역, 한 줄에 한 쌍)을 제공합니다. 이를 바탕으로 질문에 답하거나 요약하세요. 한국어로 답변하세요."
                }
                val pre = when (com.samge.bitrans.i18n.I18n.resolve(ctx())) {
                    com.samge.bitrans.i18n.DictLang.ZH -> "对话记录如下："
                    com.samge.bitrans.i18n.DictLang.ZH_TW -> "對話記錄如下："
                    com.samge.bitrans.i18n.DictLang.EN -> "The transcript is as follows:"
                    com.samge.bitrans.i18n.DictLang.JA -> "対話記録は以下の通りです："
                    com.samge.bitrans.i18n.DictLang.KO -> "대화 기록은 다음과 같습니다:"
                }
                val msgs = mutableListOf("system" to sysPrompt)
                msgs.add("user" to pre + "\n${transcriptContext(sessionId)}")
                prior.forEach { msgs.add(it.role to it.content) }
                // stream deltas accumulate into the LIVE-DELTA channel (not
                // _chatMessages — Room re-emissions would wipe a provisional
                // entry there mid-stream); the UI runs its own typewriter.
                val acc = StringBuilder()
                // fresh stream: reset live entry (previous stream's tail has been
                // revealed & persisted by now)
                _chatLiveDelta.value = com.samge.bitrans.data.ChatMessage(
                    id = -777L, sessionId = sessionId, ts = System.currentTimeMillis(),
                    role = "assistant", content = "",
                )
                engine.chatStream(msgs) { delta ->
                    acc.append(delta)
                    _chatLiveDelta.value = _chatLiveDelta.value?.copy(content = acc.toString())
                }.onSuccess { full ->
                    // KEEP the live entry until the next stream starts: the UI's
                    // typewriter is still revealing it, and clearing here would let
                    // the final persisted row preempt the reveal (one-shot dump).
                    dao.insertChat(
                        com.samge.bitrans.data.ChatMessage(
                            sessionId = sessionId, ts = System.currentTimeMillis(),
                            role = "assistant", content = full,
                        )
                    )
                }.onFailure { e ->
                    _chatLiveDelta.value = _chatLiveDelta.value?.copy(content = format(I18n.t(ctx(), "总结失败：{0}"), e.message ?: ""))
                }
            } finally {
                _chatStreaming.value = false
            }
        }
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
        TranslateConfig.setLlmNoThinkMode(ctx(), s.llmNoThink)
        TranslateConfig.setTtsEnabled(ctx(), s.tts)
        TranslateConfig.setOverlayEnabled(ctx(), s.overlayOn)
        TranslateConfig.setOverlayWidth(ctx(), s.overlayW)
        TranslateConfig.setOverlayFont(ctx(), s.overlayFont)
        TranslateConfig.setOverlayAlpha(ctx(), s.overlayAlpha)
        TranslateConfig.setOverlayLines(ctx(), s.overlayLines)
        TranslateConfig.setAutoScroll(ctx(), s.autoScroll)
        TranslateConfig.setTranslationEnabled(ctx(), s.translateOn)
        TranslateConfig.setAppLang(ctx(), s.appLang)
        // live language switch: update the compose-observable snapshot so every
        // t() call site recomposes immediately (no app restart needed)
        com.samge.bitrans.i18n.I18nState.update(ctx(), s.appLang)
        _settings.value = loadSettings()
        // sync overlay lifecycle with the setting.
        // NOTE: start() is unconditional when enabled — even for an ALREADY-RUNNING
        // service this re-delivers onStartCommand, which re-applies style+rows.
        // That makes Save apply overlay changes (width/font/alpha/lines) instantly.
        if (s.overlayOn) {
            com.samge.bitrans.overlay.OverlayService.start(ctx())
        } else {
            com.samge.bitrans.overlay.OverlayService.stop(ctx())
        }
    }

    fun refreshAll() {
        _settings.value = loadSettings()
    }

    /** Export settings JSON (no API key) to a user-picked uri */
    fun exportSettings(ctx: Context, uri: Uri, s: AppSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(s.toJson().toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) { _status.value = I18n.t(ctx(), "配置已导出") }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { _status.value = format(I18n.t(ctx(), "导出失败: {0}"), t.message ?: "") }
            }
        }
    }

    /** Import settings JSON from a user-picked uri; applies via updateSettings */
    fun importSettings(ctx: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                val json = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                } ?: return@launch onDone(false)
                val parsed = AppSettings.fromJson(json) ?: return@launch onDone(false)
                // keep current API key (never imported)
                val merged = parsed.copy(llmKey = TranslateConfig.llmApiKey(ctx()))
                withContext(Dispatchers.Main) { updateSettings(merged) }
                true
            } catch (t: Throwable) {
                false
            }
            withContext(Dispatchers.Main) { onDone(ok) }
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
        llmNoThink = TranslateConfig.llmNoThinkMode(ctx()),
        tts = TranslateConfig.ttsEnabled(ctx()),
        overlayOn = TranslateConfig.overlayEnabled(ctx()),
        overlayW = TranslateConfig.overlayWidth(ctx()),
        overlayFont = TranslateConfig.overlayFont(ctx()),
        overlayAlpha = TranslateConfig.overlayAlpha(ctx()),
        overlayLines = TranslateConfig.overlayLines(ctx()),
        autoScroll = TranslateConfig.autoScroll(ctx()),
        translateOn = TranslateConfig.translationEnabled(ctx()),
        appLang = TranslateConfig.appLang(ctx()),
    )

    override fun onCleared() {
        mic?.stop()
        tts?.shutdown()
        // NOTE: do NOT AsrEngine.shutdown() here — on configuration change
        // (rotation) the ViewModel is destroyed and recreated; releasing the
        // native recognizer under an in-flight decode crashes natively, and
        // the model would need a full reload anyway. The engine is a process
        // singleton and is reclaimed with the process.
        super.onCleared()
    }
}
