package com.samge.bitrans.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.samge.bitrans.translate.TargetLang
import java.util.Locale

// Apple tokens (see ui/theme/BiTransTheme.kt)
private val PillShape = RoundedCornerShape(999.dp)
private val CardShape = RoundedCornerShape(18.dp)

@Composable
private fun PillButton(
    label: String,
    filled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = PillShape,
        colors = if (filled) ButtonDefaults.buttonColors()
        else ButtonDefaults.outlinedButtonColors(),
        border = if (filled) null else ButtonDefaults.outlinedButtonBorder,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 11.dp),
    ) { Text(label, fontSize = 15.sp) }
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

    // system/gesture back: from settings -> back to main (autosave)
    BackHandlerCompat(enabled = showSettings) {
        if (showSettings) {
            vm.updateSettings(settings) // save current edits
            showSettings = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (showSettings) "设置" else "BiTrans",
                        fontWeight = FontWeight(600),
                        fontSize = 17.sp,
                    )
                },
                navigationIcon = {
                    if (showSettings) {
                        IconButton(onClick = {
                            vm.updateSettings(settings)
                            showSettings = false
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (!showSettings) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
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
                is UiState.Ready -> {
                    if (showSettings) {
                        SettingsPane(vm, settings)
                    } else {
                        TranscriptPane(captions, Modifier.weight(1f), settings.autoScroll)
                        AutoScrollToggle(
                            autoScroll = settings.autoScroll,
                            onChange = { vm.setAutoScroll(it) },
                        )
                        Spacer(Modifier.height(8.dp))
                        StatusRow(status, level, listening)
                        Spacer(Modifier.height(8.dp))
                        Controls(
                            listening = listening,
                            onToggle = { requestAndToggle() },
                            onClear = { vm.clearCaptions() },
                            onProbe = { vm.injectTestUtterances() },
                        )
                    }
                }
            }
        }
    }
}

/** BackHandler without an extra activity dependency */
@Composable
private fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
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
        Text("首次使用需下载语音识别模型", fontSize = 21.sp, fontWeight = FontWeight(600))
        Spacer(Modifier.height(10.dp))
        Text(
            "SenseVoice int8（约240MB，中/英/日/韩/粤）\n下载一次后完全离线运行，无任何订阅费",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(24.dp))
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
        Spacer(Modifier.height(16.dp))
        Text("下载中 ${"%.0f".format(pct * 100)}%  (${s.done / 1000000}/${s.total / 1000000} MB)", fontSize = 14.sp)
        if (s.error != null) {
            Spacer(Modifier.height(12.dp))
            Text("失败: ${s.error}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TranscriptPane(
    captions: List<com.samge.bitrans.data.Caption>,
    modifier: Modifier,
    autoScroll: Boolean,
) {
    val listState = rememberLazyListState()
    var userScrolling by remember { mutableStateOf(false) }

    val newestId = captions.firstOrNull()?.id

    LaunchedEffect(newestId, autoScroll) {
        if (autoScroll && newestId != null && !userScrolling) {
            listState.animateScrollToItem(0)
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
            .padding(horizontal = 20.dp),
        reverseLayout = true,
    ) {
        items(captions, key = { it.id }) { cap ->
            CaptionCard(cap)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CaptionCard(cap: com.samge.bitrans.data.Caption) {
    // Apple utility-card: 18px radius, hairline border, parchment fill, no shadow
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LangChip(cap.langTag)
                Spacer(Modifier.width(8.dp))
                Text(
                    cap.source,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight(400),
                )
            }
            if (cap.pending) {
                Spacer(Modifier.height(6.dp))
                Text("翻译中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (cap.target.isNotBlank() && cap.target != cap.source) {
                Spacer(Modifier.height(6.dp))
                Text(
                    cap.target,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
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
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun AutoScrollToggle(autoScroll: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = autoScroll, onCheckedChange = onChange, modifier = Modifier.height(28.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (autoScroll) "自动滚动到最新" else "手动浏览模式",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusRow(status: String, level: Float, listening: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (listening) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        Color.Red.copy(alpha = 0.3f + (level * 4).coerceIn(0f, 0.7f)),
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text("聆听中", fontSize = 13.sp)
        }
        if (status.isNotBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 1)
        }
    }
}

@Composable
private fun Controls(
    listening: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onProbe: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(label = "清空", filled = false, onClick = onClear)
        Spacer(Modifier.width(24.dp))
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
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, null)
            Spacer(Modifier.width(6.dp))
            Text(if (listening) "停止" else "开始同传", fontSize = 17.sp)
        }
    }
}

// ---------------- Settings (Apple grouped-list style) ----------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight(600),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SettingsPane(vm: MainViewModel, cur: AppSettings) {
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
    var overlayOn by remember { mutableStateOf(cur.overlayOn) }
    var overlayW by remember { mutableStateOf(cur.overlayW.toFloat()) }
    var overlayFont by remember { mutableStateOf(cur.overlayFont.toFloat()) }
    var overlayAlpha by remember { mutableStateOf(cur.overlayAlpha.toFloat()) }
    var overlayLines by remember { mutableStateOf(cur.overlayLines.toFloat()) }
    var autoScroll by remember { mutableStateOf(cur.autoScroll) }

    fun buildSettings() = AppSettings(
        engine, target, source, ltEndpoint, llmBase, llmModel, llmKey, noThink, tts,
        overlayOn, overlayW.toInt(), overlayFont.toInt(), overlayAlpha.toInt(),
        overlayLines.toInt(), autoScroll,
    )

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.exportSettings(ctx, uri, buildSettings())
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importSettings(ctx, uri) { imported ->
            if (imported) {
                Toast.makeText(ctx, "配置已导入", Toast.LENGTH_SHORT).show()
                vm.refreshAll()
            } else {
                Toast.makeText(ctx, "导入失败：文件格式不正确", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        SectionTitle("翻译方向")
        SettingsCard {
            Text("源语言（说出来的话）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = source == "auto",
                    onClick = { source = "auto" },
                    label = { Text("自动") },
                    shape = PillShape,
                )
                TargetLang.entries.forEach { tl ->
                    FilterChip(
                        selected = source == tl.code,
                        onClick = { source = tl.code },
                        label = { Text(tl.display) },
                        shape = PillShape,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("目标语言（翻译成）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TargetLang.entries.forEach { tl ->
                    FilterChip(
                        selected = target == tl.code,
                        onClick = { target = tl.code },
                        label = { Text(tl.display) },
                        shape = PillShape,
                    )
                }
            }
        }

        SectionTitle("翻译引擎")
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = engine == "mlkit", onClick = { engine = "mlkit" })
                Text("ML Kit 离线（需谷歌服务，免费）", fontSize = 15.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = engine == "libre", onClick = { engine = "libre" })
                Text("LibreTranslate（开源自托管）", fontSize = 15.sp)
            }
            if (engine == "libre") {
                OutlinedTextField(
                    value = ltEndpoint, onValueChange = { ltEndpoint = it },
                    label = { Text("LibreTranslate 地址") },
                    modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = engine == "llm", onClick = { engine = "llm" })
                Text("LLM（OpenAI 兼容 / 局域网 vLLM）", fontSize = 15.sp)
            }
            if (engine == "llm") {
                OutlinedTextField(
                    value = llmBase, onValueChange = { llmBase = it },
                    label = { Text("Base URL（含 http://）") },
                    modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp),
                )
                OutlinedTextField(
                    value = llmModel, onValueChange = { llmModel = it },
                    label = { Text("模型名") },
                    modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp),
                )
                OutlinedTextField(
                    value = llmKey, onValueChange = { llmKey = it },
                    label = { Text("API Key（无鉴权可留空）") },
                    modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("禁用思考（推理模型会导致翻译慢/超时）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = noThink == "all",
                        onClick = { noThink = "all" },
                        label = { Text("全部禁用(默认)") },
                        shape = PillShape,
                    )
                    FilterChip(
                        selected = noThink == "none",
                        onClick = { noThink = "none" },
                        label = { Text("不禁用") },
                        shape = PillShape,
                    )
                }
            }
        }

        SectionTitle("通用")
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = tts, onCheckedChange = { tts = it })
                Spacer(Modifier.width(8.dp))
                Text("朗读译文（系统 TTS）", fontSize = 15.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
                Spacer(Modifier.width(8.dp))
                Text("字幕自动滚动到最新", fontSize = 15.sp)
            }
        }

        SectionTitle("悬浮字幕")
        SettingsCard {
            val canDraw = Settings.canDrawOverlays(ctx)
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                )
                Spacer(Modifier.width(8.dp))
                Text(if (canDraw) "启用悬浮窗（可拖动，点按折叠）" else "需要悬浮窗权限")
            }
            if (!canDraw) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "系统限制了侧载应用的浮窗权限，按品牌解锁：\n" +
                        "OPPO/一加：应用权限设置页右上角点「验证」，通过后解除所有限制\n" +
                        "小米/红米：开发者选项开「USB 调试(安全设置)」后电脑执行 adb shell appops set com.samge.bitrans SYSTEM_ALERT_WINDOW allow\n" +
                        "其他：设置→应用→BiTrans→悬浮窗/后台弹出界面 允许\n" +
                        "未解锁时开启监听，字幕将显示在通知栏（下拉可见）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 16.sp,
                )
            }
            Text("显示行数（原文+译文为一组）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = overlayLines,
                onValueChange = { overlayLines = it },
                valueRange = 1f..10f,
                steps = 8,
            )
            Text("${overlayLines.toInt()} 组", fontSize = 11.sp)
            Text("浮窗宽度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = overlayW, onValueChange = { overlayW = it }, valueRange = 40f..100f, steps = 11)
            Text("${overlayW.toInt()}% 屏宽", fontSize = 11.sp)
            Text("字号", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = overlayFont, onValueChange = { overlayFont = it }, valueRange = 10f..28f, steps = 17)
            Text("${overlayFont.toInt()}sp", fontSize = 11.sp)
            Text("背景不透明度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = overlayAlpha, onValueChange = { overlayAlpha = it }, valueRange = 20f..95f, steps = 14)
            Text("${overlayAlpha.toInt()}%", fontSize = 11.sp)
        }

        SectionTitle("配置")
        SettingsCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(label = "导出配置", filled = false) {
                    exportLauncher.launch("bitrans-config.json")
                }
                PillButton(label = "导入配置", filled = false) {
                    importLauncher.launch(arrayOf("application/json"))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "导出包含引擎/地址/语言偏好；API Key 不会导出（安全考虑）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        Row {
            PillButton(label = "保存") { vm.updateSettings(buildSettings()) }
            Spacer(Modifier.width(12.dp))
            PillButton(label = "保存并测试", filled = false) {
                vm.updateSettings(buildSettings())
                vm.runEngineSelfTest()
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "开源免费: sherpa-onnx (Apache-2.0) · SenseVoice · silero-vad · ML Kit / LibreTranslate / 自托管 LLM",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(32.dp))
    }
}
