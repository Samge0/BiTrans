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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

    fun requestAndToggle() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val granted = need.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) vm.toggleListening()
        else permLauncher.launch(need.toTypedArray())
    }

    androidx.activity.compose.BackHandler(enabled = showSettings || showHistory) {
        if (showSettings) exitSettingsSavingEdits()
        if (showHistory) showHistory = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
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
                    if (showSettings || showHistory) {
                        IconButton(onClick = {
                            if (showSettings) exitSettingsSavingEdits()
                            if (showHistory) showHistory = false
                        }) {
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
                    showHistory -> HistoryPane(vm)
                    else -> MainPane(
                        vm, captions, settings, status, level, listening,
                        onToggle = { requestAndToggle() },
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
    onToggle: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TranscriptList(
            captions = captions,
            autoScroll = settings.autoScroll,
            modifier = Modifier.weight(1f),
        )
        // #2: status row and auto-scroll toggle share one row, spread out
        StatusAndScrollRow(status, level, listening, settings.autoScroll) { vm.setAutoScroll(it) }
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
    onToggleScroll: (Boolean) -> Unit,
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
            if (status.isNotBlank()) {
                Text(
                    status,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
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
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LangChip(cap.langTag)
                Spacer(Modifier.width(6.dp))
                Text(cap.source, fontSize = 14.sp, lineHeight = 20.sp)
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
        Spacer(Modifier.height(24.dp))
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

// ---------------- History ----------------

@Composable
private fun HistoryPane(vm: MainViewModel) {
    val ctx = LocalContext.current
    val sessions by vm.sessions.collectAsState()
    var openSession by remember { mutableStateOf<com.samge.bitrans.data.Session?>(null) }

    if (openSession != null) {
        val session = openSession!!
        val items by vm.itemsOf(session.id).collectAsState(initial = emptyList())
        var renameDialog by remember { mutableStateOf(false) }
        var renameText by remember(session.id) { mutableStateOf(session.title) }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { openSession = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(session.title, fontSize = 15.sp, fontWeight = FontWeight(600))
                    Text("${items.size} 段", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { renameDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "重命名")
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
                        TextButton(onClick = { renameDialog = false }) { Text("取消") }
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
                            Row {
                                LangChip(item.langTag)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                        .format(java.util.Date(item.ts)),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(sessions, key = { it.id }) { s ->
            Surface(
                shape = CardShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openSession = s },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontSize = 14.sp, fontWeight = FontWeight(500))
                        Text(
                            java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                .format(java.util.Date(s.startedAt)) + " · 点击查看",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.deleteSession(s.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
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
