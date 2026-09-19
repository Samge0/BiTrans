package com.samge.bitrans.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BiTrans 实时双语翻译", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, contentDescription = "settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
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
                        TranscriptPane(captions, Modifier.weight(1f))
                        Spacer(Modifier.height(8.dp))
                        StatusRow(status, level, listening)
                        Spacer(Modifier.height(8.dp))
                        Controls(
                            listening = listening,
                            onToggle = { requestAndToggle() },
                            onClear = { vm.clearCaptions() },
                        )
                    }
                }
            }
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
        Text("首次使用需下载语音识别模型", fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            "SenseVoice int8 (约240MB, 中/英/日/韩/粤)\n下载一次后完全离线运行, 无任何订阅费",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { vm.downloadModels() }) { Text("下载模型") }
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
        CircularProgressIndicator(progress = { pct })
        Spacer(Modifier.height(16.dp))
        Text("下载中 ${"%.0f".format(pct * 100)}%  (${s.done / 1000000}/${s.total / 1000000} MB)")
        if (s.error != null) {
            Spacer(Modifier.height(12.dp))
            Text("失败: ${s.error}", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TranscriptPane(captions: List<com.samge.bitrans.data.Caption>, modifier: Modifier) {
    LazyColumn(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        reverseLayout = true,
    ) {
        items(captions, key = { it.id }) { cap ->
            CaptionCard(cap)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CaptionCard(cap: com.samge.bitrans.data.Caption) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LangChip(cap.langTag)
                Spacer(Modifier.width(8.dp))
                Text(cap.source, fontSize = 16.sp)
            }
            if (cap.pending) {
                Spacer(Modifier.height(6.dp))
                Text("翻译中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (cap.target.isNotBlank() && cap.target != cap.source) {
                Spacer(Modifier.height(6.dp))
                Text(cap.target, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun LangChip(lang: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            lang.uppercase(Locale.ROOT),
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
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
private fun Controls(listening: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onClear) {
            Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("清空")
        }
        Spacer(Modifier.width(24.dp))
        Button(
            onClick = onToggle,
            colors = if (listening) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, null)
            Spacer(Modifier.width(6.dp))
            Text(if (listening) "停止" else "开始同传")
        }
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
    var tts by remember { mutableStateOf(cur.tts) }
    var overlayOn by remember { mutableStateOf(cur.overlayOn) }
    var overlayW by remember { mutableStateOf(cur.overlayW.toFloat()) }
    var overlayFont by remember { mutableStateOf(cur.overlayFont.toFloat()) }
    var overlayAlpha by remember { mutableStateOf(cur.overlayAlpha.toFloat()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("翻译方向", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text("源语言（说出来的话）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = source == "auto",
                onClick = { source = "auto" },
                label = { Text("自动") },
            )
            TargetLang.entries.forEach { tl ->
                FilterChip(
                    selected = source == tl.code,
                    onClick = { source = tl.code },
                    label = { Text(tl.display) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("目标语言（翻译成）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TargetLang.entries.forEach { tl ->
                FilterChip(
                    selected = target == tl.code,
                    onClick = { target = tl.code },
                    label = { Text(tl.display) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("翻译引擎", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = engine == "mlkit", onClick = { engine = "mlkit" })
                Text("ML Kit 离线 (需谷歌服务, 免费)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = engine == "libre", onClick = { engine = "libre" })
                Text("LibreTranslate (开源自托管)")
            }
            if (engine == "libre") {
                OutlinedTextField(
                    value = ltEndpoint, onValueChange = { ltEndpoint = it },
                    label = { Text("LibreTranslate 地址") },
                    modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                    singleLine = true,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = engine == "llm", onClick = { engine = "llm" })
                Text("LLM (OpenAI 兼容 / 局域网 vLLM)")
            }
            if (engine == "llm") {
                OutlinedTextField(
                    value = llmBase, onValueChange = { llmBase = it },
                    label = { Text("Base URL (含 http://)") },
                    modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = llmModel, onValueChange = { llmModel = it },
                    label = { Text("模型名") },
                    modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = llmKey, onValueChange = { llmKey = it },
                    label = { Text("API Key（无鉴权可留空）") },
                    modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                    singleLine = true,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = tts, onCheckedChange = { tts = it })
            Spacer(Modifier.width(8.dp))
            Text("朗读译文 (系统 TTS)")
        }
        Spacer(Modifier.height(16.dp))

        Text("悬浮字幕（全局半透明浮窗）", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = overlayOn,
                onCheckedChange = { want ->
                    if (want && !Settings.canDrawOverlays(ctx)) {
                        // send user to system overlay-permission page once; they toggle again after granting
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + ctx.packageName),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } else {
                        overlayOn = want
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(if (Settings.canDrawOverlays(ctx)) "启用悬浮窗（可拖动，点按折叠）" else "需要悬浮窗权限")
        }
        Text("浮窗宽度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = overlayW, onValueChange = { overlayW = it }, valueRange = 40f..100f, steps = 11)
        Text("${overlayW.toInt()}% 屏宽", fontSize = 11.sp)
        Text("字号", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = overlayFont, onValueChange = { overlayFont = it }, valueRange = 10f..28f, steps = 17)
        Text("${overlayFont.toInt()}sp", fontSize = 11.sp)
        Text("背景不透明度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = overlayAlpha, onValueChange = { overlayAlpha = it }, valueRange = 20f..95f, steps = 14)
        Text("${overlayAlpha.toInt()}%", fontSize = 11.sp)

        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            vm.updateSettings(
                AppSettings(
                    engine, target, source, ltEndpoint, llmBase, llmModel, llmKey, tts,
                    overlayOn, overlayW.toInt(), overlayFont.toInt(), overlayAlpha.toInt(),
                )
            )
        }) { Text("保存") }
        Spacer(Modifier.height(24.dp))
        Text(
            "全部组件开源免费: sherpa-onnx (Apache-2.0) + SenseVoice (FunASR/AGPL模型许可, 仅推理不受限) + ML Kit 翻译 (免费) / LibreTranslate (AGPL) / 自托管 LLM",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp,
        )
    }
}
