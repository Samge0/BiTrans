package com.samge.bitrans.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.samge.bitrans.translate.TargetLang
import java.util.Locale

// compact Apple-like metrics (Android-appropriate, not px conversions)
private val PillShape = RoundedCornerShape(999.dp)
private val CardShape = RoundedCornerShape(14.dp)

@Composable
private fun PillButton(
    label: String,
    filled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = PillShape,
        colors = if (filled) ButtonDefaults.buttonColors()
        else ButtonDefaults.outlinedButtonColors(),
        border = if (filled) null else ButtonDefaults.outlinedButtonBorder,
        contentPadding = if (compact) PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        else PaddingValues(horizontal = 18.dp, vertical = 9.dp),
    ) { Text(label, fontSize = if (compact) 12.sp else 14.sp, maxLines = 1) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiTransApp(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val captions by vm.captions.collectAsState()
    val listening by vm.listening.collectAsState()
    val level by vm.level.collectAsState()
    val status by vm.status.collectAsState()
    val settings by vm.settings.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    // independent history pages: LIST / DETAIL(session) / CHAT(session).
    // Only ONE is composed at a time — no hidden placeholder views.
    var historyPage by remember { mutableStateOf<HistoryPage>(HistoryPage.List) }
    // live-edited settings snapshot kept current by SettingsPane (used on back = autosave)
    var pendingEdits by remember { mutableStateOf<AppSettings?>(null) }

    fun exitSettingsSavingEdits() {
        vm.updateSettings(pendingEdits ?: settings)
        showSettings = false
    }

    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) vm.toggleListening()
    }
    // MediaProjection consent for playback (reverse) capture
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val svc = android.content.Intent(ctx, com.samge.bitrans.listen.PlaybackCaptureService::class.java)
                .putExtra(
                    com.samge.bitrans.listen.PlaybackCaptureService.EXTRA_RESULT_CODE,
                    result.resultCode,
                )
                .putExtra(
                    com.samge.bitrans.listen.PlaybackCaptureService.EXTRA_RESULT_DATA,
                    result.data,
                )
            runCatching { ctx.startForegroundService(svc) }
            // give the service a beat to build the capture AudioRecord
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                vm.startListeningWithPlaybackCapture()
            }, 600)
        }
    }

    val captureMode by vm.captureMode.collectAsState()

    fun requestAndToggle() {
        if (vm.listening.value) {
            vm.toggleListening()
            return
        }
        if (captureMode == "playback") {
            if (android.os.Build.VERSION.SDK_INT < 29) {
                android.widget.Toast.makeText(ctx, "反向采集需要 Android 10+", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val mpm = ctx.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
            return
        }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val granted = need.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) vm.toggleListening()
        else permLauncher.launch(need.toTypedArray())
    }

    androidx.activity.compose.BackHandler(enabled = showSettings) {
        exitSettingsSavingEdits()
    }
    androidx.activity.compose.BackHandler(enabled = showHistory && historyPage !is HistoryPage.List) {
        historyPage = when (val hp = historyPage) {
            is HistoryPage.Chat -> HistoryPage.Detail(hp.session)
            is HistoryPage.Detail -> HistoryPage.List
            else -> HistoryPage.List
        }
    }
    androidx.activity.compose.BackHandler(enabled = showHistory && historyPage is HistoryPage.List) {
        showHistory = false
    }

    val inHistorySubPage = showHistory && historyPage !is HistoryPage.List
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // sub-pages draw their own status-bar inset; the scaffold must not also
        // reserve one (double inset = the blank strip above their titles).
        // IME inset is passed through so the chat input can sit above the keyboard.
        // NOTE: IME inset is NOT passed here — the chat page applies its own
        // imePadding(); passing it in BOTH places stacked into a huge gap
        // between the input row and the keyboard.
        contentWindowInsets = if (inHistorySubPage) WindowInsets(0, 0, 0, 0)
        else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!inHistorySubPage) CenterAlignedTopAppBar(
                title = {
                    Text(
                        when {
                            showHistory -> "历史记录"
                            showSettings -> "设置"
                            else -> "BiTrans"
                        },
                        fontWeight = FontWeight(600),
                        fontSize = 16.sp,
                    )
                },
                navigationIcon = {
                    if (showSettings) {
                        IconButton(onClick = { exitSettingsSavingEdits() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    } else if (showHistory) {
                        IconButton(onClick = { showHistory = false; historyPage = HistoryPage.List }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    when {
                        showSettings -> {
                            val ctxAct = LocalContext.current
                            TextButton(onClick = {
                                vm.saveAndTest(pendingEdits ?: settings)
                                android.widget.Toast.makeText(ctxAct, "已保存并开始自测", android.widget.Toast.LENGTH_SHORT).show()
                            }) {
                                Text("保存", fontSize = 14.sp)
                            }
                        }
                        !showHistory -> {
                            IconButton(onClick = { showHistory = true }) {
                                Icon(Icons.Default.History, contentDescription = "历史")
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "设置")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            when (val s = ui) {
                is UiState.NeedsModels -> ModelDownloadPane(vm)
                is UiState.Downloading -> DownloadingPane(s)
                is UiState.Ready -> when {
                    showSettings -> SettingsPane(vm, settings) { edited ->
                        // back/gesture exit hands us the LIVE edited state (not the persisted snapshot)
                        pendingEdits = edited
                    }
                    showHistory -> when (val hp = historyPage) {
                        is HistoryPage.List -> HistoryListPage(vm) { historyPage = HistoryPage.Detail(it) }
                        is HistoryPage.Detail -> SessionDetailPage(vm, hp.session) {
                            historyPage = if (it == null) HistoryPage.List else HistoryPage.Chat(hp.session)
                        }
                        is HistoryPage.Chat -> SummaryChatPage(vm, hp.session) { historyPage = HistoryPage.Detail(hp.session) }
                    }
                    else -> MainPane(
                        vm, captions, settings, status, level, listening,
                        captureMode = captureMode,
                        onToggle = { requestAndToggle() },
                        onCaptureMode = { vm.setCaptureMode(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MainPane(
    vm: MainViewModel,
    captions: List<com.samge.bitrans.data.Caption>,
    settings: AppSettings,
    status: String,
    level: Float,
    listening: Boolean,
    captureMode: String,
    onToggle: () -> Unit,
    onCaptureMode: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TranscriptList(
            captions = captions,
            autoScroll = settings.autoScroll,
            modifier = Modifier.weight(1f),
        )
        // single compact row: status/capture-chips (left) + auto-scroll (right)
        StatusAndScrollRow(
            status = status,
            level = level,
            listening = listening,
            autoScroll = settings.autoScroll,
            captureMode = captureMode,
            onToggleScroll = { vm.setAutoScroll(it) },
            onCaptureMode = onCaptureMode,
        )
        // stop-with-content -> ask whether to save to history
        val askSave by vm.askSaveSession.collectAsState()
        if (askSave) {
            AlertDialog(
                onDismissRequest = { vm.discardSession() },
                title = { Text("保存到历史记录？", fontSize = 15.sp) },
                text = { Text("本次共 ${captions.size} 段字幕。保存后可在历史中查看，并自动生成摘要标题。", fontSize = 13.sp) },
                confirmButton = {
                    TextButton(onClick = { vm.confirmSaveSession() }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.discardSession() }) { Text("不保存") }
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        ControlButtons(
            listening = listening,
            onToggle = onToggle,
            onProbe = { vm.injectTestUtterances() },
            onClear = { vm.clearCaptions() },
        )
    }
}

@Composable
private fun TranscriptList(
    captions: List<com.samge.bitrans.data.Caption>,
    autoScroll: Boolean,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    var userScrolling by remember { mutableStateOf(false) }
    val newestId = captions.firstOrNull()?.id

    // #1: enabling auto-scroll jumps to newest IMMEDIATELY; new captions keep following
    LaunchedEffect(newestId, autoScroll) {
        if (autoScroll && newestId != null && !userScrolling) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx -> userScrolling = idx > 0 }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        reverseLayout = true,
    ) {
        items(captions, key = { it.id }) { cap ->
            CaptionCard(cap)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusAndScrollRow(
    status: String,
    level: Float,
    listening: Boolean,
    autoScroll: Boolean,
    captureMode: String,
    onToggleScroll: (Boolean) -> Unit,
    onCaptureMode: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (listening) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            Color.Red.copy(alpha = 0.3f + (level * 4).coerceIn(0f, 0.7f)),
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(6.dp))
                Text("聆听中", fontSize = 12.sp)
            }
            if (!listening && status.isBlank()) {
                // capture-source chips only when idle and no status message
                FilterChip(
                    selected = captureMode == "mic",
                    onClick = { onCaptureMode("mic") },
                    label = { Text("麦克风", fontSize = 11.sp) },
                    shape = PillShape,
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = captureMode == "playback",
                    onClick = { onCaptureMode("playback") },
                    label = { Text("播放声", fontSize = 11.sp) },
                    shape = PillShape,
                )
            }
            if (status.isNotBlank()) {
                Text(
                    status,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (autoScroll) "最新" else "手动",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = autoScroll,
                onCheckedChange = onToggleScroll,
                modifier = Modifier.height(24.dp),
            )
        }
    }
}

@Composable
private fun ControlButtons(
    listening: Boolean,
    onToggle: () -> Unit,
    onProbe: () -> Unit,
    onClear: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        var tapCount by remember { mutableStateOf(0) }
        var lastTap by remember { mutableStateOf(0L) }
        Button(
            onClick = {
                val now = System.currentTimeMillis()
                if (!listening) {
                    if (now - lastTap < 600) tapCount++ else tapCount = 1
                    lastTap = now
                    if (tapCount >= 5) {
                        tapCount = 0
                        onProbe()
                        return@Button
                    }
                }
                onToggle()
            },
            shape = PillShape,
            colors = if (listening) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp),
            modifier = Modifier.heightIn(min = 34.dp),
        ) {
            Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, null, Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (listening) "停止" else "开始同传", fontSize = 13.sp)
        }
        OutlinedButton(
            onClick = onClear,
            shape = PillShape,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp),
            modifier = Modifier.heightIn(min = 34.dp),
        ) {
            Icon(Icons.Default.Delete, null, Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("清空", fontSize = 13.sp)
        }
    }
}

@Composable
private fun ModelDownloadPane(vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("首次使用需下载语音识别模型", fontSize = 18.sp, fontWeight = FontWeight(600))
        Spacer(Modifier.height(8.dp))
        Text(
            "SenseVoice int8（约240MB，中/英/日/韩/粤）\n下载一次后完全离线运行，无任何订阅费",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(20.dp))
        PillButton(label = "下载模型") { vm.downloadModels() }
    }
}

@Composable
private fun DownloadingPane(s: UiState.Downloading) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val pct = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { pct },
            modifier = Modifier.fillMaxWidth(),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        Spacer(Modifier.height(12.dp))
        Text("下载中 ${"%.0f".format(pct * 100)}%  (${s.done / 1000000}/${s.total / 1000000} MB)", fontSize = 13.sp)
        if (s.error != null) {
            Spacer(Modifier.height(8.dp))
            Text("失败: ${s.error}", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CaptionCard(cap: com.samge.bitrans.data.Caption) {
    val ctx = LocalContext.current
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LangChip(cap.langTag)
                Spacer(Modifier.width(6.dp))
                Text(cap.source, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                // one-tap copy: original + translation
                IconButton(
                    onClick = { copyCaption(ctx, cap.source, cap.target) },
                    modifier = Modifier.size(26.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (cap.pending) {
                Text("翻译中…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (cap.target.isNotBlank() && cap.target != cap.source) {
                Spacer(Modifier.height(2.dp))
                Text(
                    cap.target,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Copy "source\ntarget" to clipboard with a toast. */
private fun copyCaption(ctx: android.content.Context, source: String, target: String) {
    val text = if (target.isBlank() || target == source) source else "$source\n$target"
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("BiTrans", text))
    android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun LangChip(lang: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Text(
            lang.uppercase(Locale.ROOT),
            Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

// ---------------- Settings: collapsible groups ----------------

@Composable
private fun GroupCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight(600), modifier = Modifier.weight(1f))
                if (summary != null && !expanded) {
                    Text(
                        summary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), content = content)
            }
        }
    }
}

@Composable
private fun FlowChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    // #6: flow layout wraps instead of stretching the last item
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(4).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { (code, label) ->
                    FilterChip(
                        selected = selected == code,
                        onClick = { onSelect(code) },
                        label = { Text(label, fontSize = 12.sp) },
                        shape = PillShape,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPane(vm: MainViewModel, cur: AppSettings, onEdits: (AppSettings) -> Unit) {
    val ctx = LocalContext.current
    var engine by remember { mutableStateOf(cur.engineKind) }
    var target by remember { mutableStateOf(cur.target) }
    var source by remember { mutableStateOf(cur.source) }
    var ltEndpoint by remember { mutableStateOf(cur.ltEndpoint) }
    var llmBase by remember { mutableStateOf(cur.llmBase) }
    var llmModel by remember { mutableStateOf(cur.llmModel) }
    var llmKey by remember { mutableStateOf(cur.llmKey) }
    var noThink by remember { mutableStateOf(cur.llmNoThink) }
    var tts by remember { mutableStateOf(cur.tts) }
    var translateOn by remember { mutableStateOf(cur.translateOn) }
    var overlayOn by remember { mutableStateOf(cur.overlayOn) }
    var overlayW by remember { mutableStateOf(cur.overlayW.toFloat()) }
    var overlayFont by remember { mutableStateOf(cur.overlayFont.toFloat()) }
    var overlayAlpha by remember { mutableStateOf(cur.overlayAlpha.toFloat()) }
    var overlayLines by remember { mutableStateOf(cur.overlayLines.toFloat()) }
    var autoScroll by remember { mutableStateOf(cur.autoScroll) }

    var openGroup by remember { mutableStateOf("direction") }

    fun buildSettings() = AppSettings(
        engine, target, source, ltEndpoint, llmBase, llmModel, llmKey, noThink, tts,
        overlayOn, overlayW.toInt(), overlayFont.toInt(), overlayAlpha.toInt(),
        overlayLines.toInt(), autoScroll, translateOn,
    )

    // keep the parent's "pending edits" current so back/gesture-exit saves the
    // LIVE values the user typed (not the previously persisted snapshot)
    LaunchedEffect(engine, target, source, ltEndpoint, llmBase, llmModel, llmKey, noThink, tts, translateOn, overlayOn, overlayW, overlayFont, overlayAlpha, overlayLines, autoScroll) {
        onEdits(buildSettings())
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportSettings(ctx, uri, buildSettings()) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importSettings(ctx, uri) { ok ->
            Toast.makeText(ctx, if (ok) "配置已导入" else "导入失败：文件格式不正确", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        GroupCard("翻译方向", openGroup == "direction", { openGroup = if (openGroup == "direction") "" else "direction" },
            summary = "${if (source == "auto") "自动" else source} → $target") {
            Text("源语言（说出来的话）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            FlowChips(
                listOf("auto" to "自动") + TargetLang.entries.map { it.code to it.display },
                source,
            ) { source = it }
            Spacer(Modifier.height(8.dp))
            Text("目标语言（翻译成）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            FlowChips(TargetLang.entries.map { it.code to it.display }, target) { target = it }
        }

        Spacer(Modifier.height(8.dp))
        GroupCard("翻译引擎", openGroup == "engine", { openGroup = if (openGroup == "engine") "" else "engine" },
            summary = when (engine) { "mlkit" -> "ML Kit 离线"; "libre" -> "LibreTranslate"; else -> "LLM" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = engine == "mlkit", onClick = { engine = "mlkit" }, modifier = Modifier.size(32.dp))
                Text("ML Kit 离线（需谷歌服务，免费）", fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = engine == "libre", onClick = { engine = "libre" }, modifier = Modifier.size(32.dp))
                Text("LibreTranslate（开源自托管）", fontSize = 13.sp)
            }
            if (engine == "libre") {
                OutlinedTextField(
                    value = ltEndpoint, onValueChange = { ltEndpoint = it },
                    label = { Text("服务地址", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                    singleLine = true, textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(9.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = engine == "llm", onClick = { engine = "llm" }, modifier = Modifier.size(32.dp))
                Text("LLM（OpenAI 兼容 / 局域网 vLLM）", fontSize = 13.sp)
            }
            if (engine == "llm") {
                OutlinedTextField(
                    value = llmBase, onValueChange = { llmBase = it },
                    label = { Text("Base URL（含 http://）", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                    singleLine = true, textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(9.dp),
                )
                OutlinedTextField(
                    value = llmModel, onValueChange = { llmModel = it },
                    label = { Text("模型名", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                    singleLine = true, textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(9.dp),
                )
                OutlinedTextField(
                    value = llmKey, onValueChange = { llmKey = it },
                    label = { Text("API Key（无鉴权可留空）", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                    singleLine = true, textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(9.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text("禁用思考（推理模型会拖慢翻译）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = noThink == "quiet", onClick = { noThink = "quiet" },
                        label = { Text("标准(默认)", fontSize = 11.sp) }, shape = PillShape)
                    FilterChip(selected = noThink == "all", onClick = { noThink = "all" },
                        label = { Text("全量字段", fontSize = 11.sp) }, shape = PillShape)
                    FilterChip(selected = noThink == "none", onClick = { noThink = "none" },
                        label = { Text("不禁用", fontSize = 11.sp) }, shape = PillShape)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        GroupCard("通用", openGroup == "general", { openGroup = if (openGroup == "general") "" else "general" }) {
            SettingRow("启用翻译", "关闭则只显示原文") {
                Switch(checked = translateOn, onCheckedChange = { translateOn = it }, modifier = Modifier.height(24.dp))
            }
            SettingRow("朗读译文", "系统 TTS") {
                Switch(checked = tts, onCheckedChange = { tts = it }, modifier = Modifier.height(24.dp))
            }
            SettingRow("自动滚动", "新字幕到达时跟随") {
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it }, modifier = Modifier.height(24.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        GroupCard("悬浮字幕", openGroup == "overlay", { openGroup = if (openGroup == "overlay") "" else "overlay" },
            summary = if (overlayOn) "${overlayLines.toInt()} 行" else "关") {
            val canDraw = Settings.canDrawOverlays(ctx)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (canDraw) "启用悬浮窗" else "需要悬浮窗权限",
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "可拖动 · 点按折叠",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Switch(
                    checked = overlayOn,
                    onCheckedChange = { want ->
                        when {
                            want && canDraw -> overlayOn = true
                            want -> {
                                try {
                                    ctx.startActivity(com.samge.bitrans.overlay.OverlayPermissionHelp.miuiIntent())
                                } catch (_: Exception) {
                                    ctx.startActivity(com.samge.bitrans.overlay.OverlayPermissionHelp.genericIntent(ctx))
                                }
                            }
                            else -> overlayOn = false
                        }
                    },
                    modifier = Modifier.height(24.dp),
                )
            }
            if (!canDraw) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "系统限制了侧载应用的浮窗权限，按品牌解锁：\n" +
                        "OPPO/一加：应用权限设置页右上角点「验证」，通过后解除所有限制\n" +
                        "小米/红米：开发者选项开「USB 调试(安全设置)」后电脑执行 adb shell appops set com.samge.bitrans SYSTEM_ALERT_WINDOW allow\n" +
                        "其他：设置→应用→BiTrans→悬浮窗/后台弹出界面 允许\n" +
                        "未解锁时开启监听，字幕将显示在通知栏（下拉可见）",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 14.sp,
                )
            }
            SettingSlider("显示行数（原文+译文为一组）", overlayLines, 1f..10f, 8) { overlayLines = it }
            Text("${overlayLines.toInt()} 组", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingSlider("浮窗宽度", overlayW, 40f..100f, 11) { overlayW = it }
            Text("${overlayW.toInt()}% 屏宽", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingSlider("字号", overlayFont, 10f..28f, 17) { overlayFont = it }
            Text("${overlayFont.toInt()}sp", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingSlider("背景不透明度", overlayAlpha, 20f..95f, 14) { overlayAlpha = it }
            Text("${overlayAlpha.toInt()}%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(8.dp))
        GroupCard("配置", openGroup == "config", { openGroup = if (openGroup == "config") "" else "config" }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(label = "导出配置", filled = false, compact = true) {
                    exportLauncher.launch("bitrans-config.json")
                }
                PillButton(label = "导入配置", filled = false, compact = true) {
                    importLauncher.launch(arrayOf("application/json"))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "导出不包含 API Key（安全考虑）；导入后自动应用",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "开源免费: sherpa-onnx (Apache-2.0) · SenseVoice · silero-vad · ML Kit / LibreTranslate / 自托管 LLM",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 14.sp,
        )
        Spacer(Modifier.height(16.dp))
        // version + releases link (opens browser)
        val ctxFooter = LocalContext.current
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val versionName = runCatching {
                ctxFooter.packageManager.getPackageInfo(ctxFooter.packageName, 0).versionName
            }.getOrNull() ?: "?"
            Text(
                "v$versionName · ",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "github.com/Samge0/BiTrans",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                modifier = Modifier.clickable {
                    runCatching {
                        ctxFooter.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Samge0/BiTrans/releases"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        modifier = Modifier.height(30.dp),
    )
}

// ---------------- History: three INDEPENDENT pages ----------------

/** History navigation: only ONE page composed at a time. */
sealed interface HistoryPage {
    data object List : HistoryPage
    data class Detail(val session: com.samge.bitrans.data.Session) : HistoryPage
    data class Chat(val session: com.samge.bitrans.data.Session) : HistoryPage
}

/** Page 1: session list. Own header lives in the outer top bar. */
@Composable
private fun HistoryListPage(vm: MainViewModel, onOpen: (com.samge.bitrans.data.Session) -> Unit) {
    val sessions by vm.sessions.collectAsState()
    // caption counts per session (for "xx 段" in list rows)
    val countsBySession by vm.sessionCaptionCounts.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(sessions, key = { it.id }) { s ->
            Surface(
                shape = CardShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(s) },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontSize = 14.sp, fontWeight = FontWeight(500))
                        Text(
                            java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                .format(java.util.Date(s.startedAt)) +
                                " · " + (countsBySession[s.id]?.let { "${it}段" } ?: "…"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    var confirmDelete by remember { mutableStateOf(false) }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                    if (confirmDelete) {
                        AlertDialog(
                            onDismissRequest = { confirmDelete = false },
                            title = { Text("删除这条记录？", fontSize = 15.sp) },
                            text = { Text("「${s.title}」将被永久删除，含全部字幕与总结对话。", fontSize = 13.sp) },
                            confirmButton = {
                                TextButton(onClick = {
                                    vm.deleteSession(s.id)
                                    confirmDelete = false
                                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                            },
                        )
                    }
                }
            }
        }
        if (sessions.isEmpty()) {
            item {
                Text(
                    "暂无历史记录\n开始一次同传并停止后会自动保存",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
    }
}

/** Page 2: session detail. Fully independent page with its OWN header. */
@Composable
private fun SessionDetailPage(
    vm: MainViewModel,
    session: com.samge.bitrans.data.Session,
    onNavigate: (com.samge.bitrans.data.Session?) -> Unit, // null=back, non-null=open chat
) {
    val ctx = LocalContext.current
    val items by vm.itemsOf(session.id).collectAsState(initial = emptyList())
    // LIVE session row: renames (manual or AI) show immediately in the header
    val sessions by vm.sessions.collectAsState()
    val live = sessions.firstOrNull { it.id == session.id } ?: session
    var renameDialog by remember { mutableStateOf(false) }
    var renameText by remember(live.id) { mutableStateOf(live.title) }
    var aiNaming by remember { mutableStateOf(false) }
    val sbInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(Modifier.fillMaxSize().padding(top = sbInset)) {
        // OWN header row: back + title + meta + summary button
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onNavigate(null) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(live.title, fontSize = 15.sp, fontWeight = FontWeight(600), maxLines = 1)
                Text("${items.size} 段", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { renameDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = "重命名")
            }
            PillButton(label = "总结", compact = true) {
                if (vm.llmConfigured()) {
                    vm.bindChat(session.id)
                    vm.maybeAutoSummarize(session.id)
                    onNavigate(session)
                } else {
                    android.widget.Toast.makeText(ctx, "请先到设置页配置 LLM 引擎", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (renameDialog) {
            AlertDialog(
                onDismissRequest = { renameDialog = false },
                title = { Text("重命名", fontSize = 15.sp) },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.renameSession(session.id, renameText)
                        renameDialog = false
                    }) { Text("确定") }
                },
                dismissButton = {
                    Row {
                        if (vm.llmConfigured() && !aiNaming) {
                            TextButton(onClick = {
                                aiNaming = true
                                vm.generateTitleWithLlmCallback(session.id) { generated ->
                                    aiNaming = false
                                    if (generated != null) renameText = generated
                                }
                            }) { Text("AI 起名") }
                        }
                        if (aiNaming) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("生成中…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { renameDialog = false }) { Text("取消") }
                    }
                },
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(items, key = { it.id }) { item ->
                Surface(
                    shape = CardShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LangChip(item.langTag)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                    .format(java.util.Date(item.ts)),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { copyCaption(ctx, item.source, item.target) },
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "复制",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(item.source, fontSize = 14.sp, lineHeight = 20.sp)
                        if (item.target.isNotBlank()) {
                            Text(
                                item.target,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                lineHeight = 19.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Page 3: AI summary chat. Independent page, typewriter built in. */
@Composable
private fun SummaryChatPage(
    vm: MainViewModel,
    session: com.samge.bitrans.data.Session,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val streaming by vm.chatStreaming.collectAsState()
    val liveDelta by vm.chatLiveDelta.collectAsState()
    val persisted by vm.chatMessages.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val sbInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // ---- streaming display: render deltas DIRECTLY (mainstream pattern —
    // no synthetic typewriter; scrolling follows layout changes) ----
    var streamText by remember { mutableStateOf("") }
    LaunchedEffect(liveDelta?.content) {
        streamText = liveDelta?.content ?: ""
    }
    // pending echo: local user bubble at tap time (zero-latency feedback).
    // Retire once the SAME user text is persisted in Room; the streaming
    // assistant bubble covers the reply side on its own.
    var pendingUser by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(persisted, pendingUser) {
        val pu = pendingUser ?: return@LaunchedEffect
        if (persisted.any { it.role == "user" && it.content == pu.first && it.ts >= pu.second }) {
            pendingUser = null
        }
    }
    val msgs: List<com.samge.bitrans.data.ChatMessage> = remember(persisted, streamText, pendingUser) {
        val base = persisted.filter { it.id != -777L }
        val pend = pendingUser
        val waitingBubble = com.samge.bitrans.data.ChatMessage(
            id = Long.MAX_VALUE - 1, sessionId = session.id, ts = 0,
            role = "assistant", content = "",
        )
        when {
            pend != null -> base + listOf(
                com.samge.bitrans.data.ChatMessage(
                    id = Long.MIN_VALUE, sessionId = session.id, ts = pend.second,
                    role = "user", content = pend.first,
                ),
                waitingBubble,
            )
            streaming && streamText.isNotBlank() -> base + listOf(
                com.samge.bitrans.data.ChatMessage(
                    id = Long.MAX_VALUE, sessionId = session.id, ts = 0,
                    role = "assistant", content = streamText,
                )
            )
            streaming -> base + listOf(waitingBubble)
            else -> base
        }
    }

    // ---- follow-bottom (gpt_mobile/ChatGPT-style) ----
    val isUserDragging by remember { mutableStateOf(false) }
    val followBottom = remember { mutableStateOf(true) }

    // user scrolled BACK to the bottom (by any means) -> resume following;
    // scrolling away backward while following -> stop following
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            Triple(
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                listState.layoutInfo.totalItemsCount,
                listState.lastScrolledBackward,
            )
        }.collect { state ->
            val (lastIdx, total, scrolledBack) = state
            val atBottom = lastIdx >= total - 1 && !listState.canScrollForward
            if (atBottom) {
                followBottom.value = true
            } else if (scrolledBack && listState.isScrollInProgress) {
                followBottom.value = false
            }
        }
    }
    // a NEW message (sent/received) always re-engages following
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) followBottom.value = true }

    // auto-scroll: reactive to LAYOUT changes (text growth included).
    // KEY INSIGHT: requestScrollToItem(last) pins the last item's TOP to the
    // viewport top — once a streaming bubble is TALLER than the viewport, its
    // top is pinned at the header and further growth is NOT followed.
    // Fix: while following, keep the last item's BOTTOM pinned to the viewport
    // bottom via a scroll position computed from the layout info.
    LaunchedEffect(listState, followBottom.value) {
        androidx.compose.runtime.snapshotFlow {
            // observe count + follow + last-item geometry so any growth re-fires
            val lastInfo = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            Triple(
                listState.layoutInfo.totalItemsCount,
                followBottom.value,
                (lastInfo?.index ?: -1) to (lastInfo?.size ?: 0),
            )
        }.collect { state ->
            val (total, following, lastGeom) = state
            if (!following) return@collect
            val (lastIdx, lastSize) = lastGeom
            val last = total - 1
            if (last < 0) return@collect

            val li = listState.layoutInfo
            val viewportH = li.viewportEndOffset - li.viewportStartOffset
            val lastInfo = li.visibleItemsInfo.lastOrNull()
            if (lastInfo != null && lastInfo.index == last) {
                // scroll offset so that the item's BOTTOM sits at the viewport
                // BOTTOM: itemTop must be at (viewportH - itemSize) from the
                // viewport top => scrollOffset = itemSize - viewportH (>=0 when
                // taller than viewport; clamps naturally otherwise)
                val scrollOffset = (lastInfo.size - viewportH).coerceAtLeast(0) +
                    li.afterContentPadding
                listState.requestScrollToItem(last, scrollOffset)
            } else {
                // last item fully off-screen: jump to it (top-align first;
                // next layout pass bottom-pins via the branch above)
                listState.requestScrollToItem(last)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = sbInset)
            .imePadding()          // input row stays above the soft keyboard
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AI 总结 · ${session.title}", fontSize = 13.sp, fontWeight = FontWeight(600), maxLines = 1)
                Text(if (streaming) "生成中…" else "基于本次记录的对话", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.clearChat(session.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "清空会话", tint = MaterialTheme.colorScheme.error)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        followBottom.value = false // touch on the list = manual browsing
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(msgs, key = { it.id }) { m ->
                val mine = m.role == "user"
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 12.dp, topEnd = 12.dp,
                            bottomStart = if (mine) 12.dp else 2.dp,
                            bottomEnd = if (mine) 2.dp else 12.dp,
                        ),
                        color = if (mine) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            val body = m.content.ifBlank { if (streaming || m.id == Long.MAX_VALUE - 1) "…" else "" }
                            val contentColor = if (mine) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                            if (mine) {
                                Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = contentColor)
                            } else {
                                MarkdownBody(body, contentColor)
                            }
                            if ((m.id == Long.MAX_VALUE || m.id == Long.MAX_VALUE - 1) && (streaming || pendingUser != null)) {
                                Text("▍", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("继续提问…", fontSize = 13.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(18.dp),
                maxLines = 3,
                enabled = !streaming,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        followBottom.value = true // sending always re-follows
                        pendingUser = text to System.currentTimeMillis()
                        vm.sendChat(session.id, text)
                        input = ""
                    }
                },
                enabled = !streaming && input.isNotBlank(),
                shape = PillShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("发送", fontSize = 13.sp) }
        }
    }
}

/** Renders MiniMarkdown nodes in a chat bubble. */
@Composable
private fun MarkdownBody(src: String, baseColor: androidx.compose.ui.graphics.Color) {
    val nodes = remember(src) { MiniMarkdown.parse(src) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        nodes.forEach { node ->
            when (node) {
                is MdNode.Heading -> Text(
                    node.text,
                    fontSize = when (node.level.coerceIn(1, 3)) {
                        1 -> 16.sp; 2 -> 15.sp; else -> 14.sp
                    },
                    fontWeight = androidx.compose.ui.text.font.FontWeight(600),
                    color = baseColor,
                )
                is MdNode.Paragraph -> SpanText(node.spans, 13.sp, baseColor)
                is MdNode.Bullet -> Row {
                    Text(
                        if (node.ordered) "${node.index}. " else "• ",
                        fontSize = 13.sp,
                        color = baseColor,
                    )
                    SpanText(node.spans, 13.sp, baseColor, Modifier.weight(1f))
                }
                is MdNode.Quote -> Surface(
                    color = androidx.compose.ui.graphics.Color(baseColor.red, baseColor.green, baseColor.blue, 0.08f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    SpanText(node.spans, 13.sp, baseColor, Modifier.padding(8.dp))
                }
                is MdNode.Code -> Surface(
                    color = androidx.compose.ui.graphics.Color(baseColor.red, baseColor.green, baseColor.blue, 0.10f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        node.text,
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = baseColor,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpanText(
    spans: List<MdSpan>,
    size: androidx.compose.ui.unit.TextUnit,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = androidx.compose.ui.text.buildAnnotatedString {
            spans.forEach { sp ->
                when (sp) {
                    is MdSpan.Plain -> withStyle(
                        androidx.compose.ui.text.SpanStyle(fontSize = size, color = color)
                    ) { append(sp.text) }
                    is MdSpan.Bold -> withStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontSize = size, color = color,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    ) { append(sp.text) }
                    is MdSpan.Italic -> withStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontSize = size, color = color,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        )
                    ) { append(sp.text) }
                    is MdSpan.Code -> withStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontSize = size,
                            color = color,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            background = androidx.compose.ui.graphics.Color(
                                color.red, color.green, color.blue, 0.12f,
                            ),
                        )
                    ) { append(sp.text) }
                }
            }
        },
        modifier = modifier,
    )
}
