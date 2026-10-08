package com.samge.bitrans.i18n

/** English dictionary: Chinese source string → English. */
internal val DictEn: Map<String, String> = mapOf(
    // ── common actions ──────────────────────────────────────────────
    "确定" to "OK",
    "取消" to "Cancel",
    "保存" to "Save",
    "删除" to "Delete",
    "发送" to "Send",
    "复制" to "Copy",
    "返回" to "Back",
    "设置" to "Settings",
    "历史" to "History",
    "历史记录" to "History",
    "重命名" to "Rename",
    "总结" to "Summarize",
    "清空" to "Clear",
    "清空会话" to "Clear conversation",
    "生成中…" to "Generating…",
    "翻译中…" to "Translating…",
    "已复制" to "Copied",
    "下载中 {0}%  ({1}/{2} MB)" to "Downloading {0}%  ({1}/{2} MB)",
    "失败: {0}" to "Failed: {0}",
    "跟随系统" to "Follow system",
    "未命名" to "Untitled",

    // ── main pane / header ──────────────────────────────────────────
    "聆听中" to "Listening",
    "麦克风" to "Mic",
    "播放声" to "Playback",
    "最新" to "Latest",
    "手动" to "Manual",
    "停止" to "Stop",
    "开始同传" to "Start Live",
    "已保存并开始自测" to "Saved & self-test started",
    "反向采集需要 Android 10+" to "Reverse capture requires Android 10+",
    "请先到设置页配置 LLM 引擎" to "Configure an LLM engine in Settings first",

    // ── model download pane ─────────────────────────────────────────
    "首次使用需下载语音识别模型" to "Download the speech model to get started",
    "SenseVoice int8（约240MB，中/英/日/韩/粤）\n下载一次后完全离线运行，无任何订阅费" to
        "SenseVoice int8 (~240MB, zh/en/ja/ko/yue)\nDownload once, runs fully offline, no subscription",
    "下载模型" to "Download model",

    // ── settings: direction ─────────────────────────────────────────
    "翻译方向" to "Translation direction",
    "源语言（说出来的话）" to "Source language (what you speak)",
    "目标语言（翻译成）" to "Target language (translate into)",
    "自动" to "Auto",
    "中文" to "Chinese",
    "粤语" to "Cantonese",

    // ── settings: engine ────────────────────────────────────────────
    "翻译引擎" to "Translation engine",
    "ML Kit 离线（需谷歌服务，免费）" to "ML Kit offline (needs Google services, free)",
    "LibreTranslate（开源自托管）" to "LibreTranslate (open-source, self-hosted)",
    "LLM（OpenAI 兼容 / 局域网 vLLM）" to "LLM (OpenAI-compatible / LAN vLLM)",
    "服务地址" to "Server URL",
    "Base URL（含 http://）" to "Base URL (with http://)",
    "模型名" to "Model name",
    "API Key（无鉴权可留空）" to "API Key (leave empty if no auth)",
    "禁用思考（推理模型会拖慢翻译）" to "Disable thinking (reasoning models slow translation)",
    "标准(默认)" to "Standard (default)",
    "全量字段" to "All fields",
    "不禁用" to "Don't disable",
    "MLKit(离线)" to "ML Kit (offline)",

    // ── settings: general ───────────────────────────────────────────
    "通用" to "General",
    "启用翻译" to "Enable translation",
    "关闭则只显示原文" to "Off shows source text only",
    "朗读译文" to "Read translation aloud",
    "系统 TTS" to "System TTS",
    "自动滚动" to "Auto-scroll",
    "新字幕到达时跟随" to "Follow new captions",
    "语言" to "Language",
    "界面语言，即时生效" to "Display language, applies instantly",

    // ── settings: overlay ───────────────────────────────────────────
    "悬浮字幕" to "Floating captions",
    "启用悬浮窗" to "Enable floating overlay",
    "需要悬浮窗权限" to "Overlay permission required",
    "可拖动 · 点按折叠" to "Draggable · Tap to collapse",
    "系统限制了侧载应用的浮窗权限，按品牌解锁：\n" +
        "OPPO/一加：应用权限设置页右上角点「验证」，通过后解除所有限制\n" +
        "小米/红米：开发者选项开「USB 调试(安全设置)」后电脑执行 adb shell appops set com.samge.bitrans SYSTEM_ALERT_WINDOW allow\n" +
        "其他：设置→应用→BiTrans→悬浮窗/后台弹出界面 允许\n" +
        "未解锁时开启监听，字幕将显示在通知栏（下拉可见）" to
        "The system restricts overlay permission for sideloaded apps. Unlock by brand:\n" +
            "OPPO/OnePlus: tap \"Verify\" at the top-right of the app permission page to lift all restrictions\n" +
            "Xiaomi/Redmi: enable \"USB debugging (Security settings)\" in Developer options, then run: adb shell appops set com.samge.bitrans SYSTEM_ALERT_WINDOW allow\n" +
            "Others: Settings → Apps → BiTrans → allow Display pop-up windows\n" +
            "If unlocked is impossible, captions appear in the notification shade (swipe down)",
    "显示行数（原文+译文为一组）" to "Visible lines (source+target as one pair)",
    "浮窗宽度" to "Overlay width",
    "字号" to "Font size",
    "背景不透明度" to "Background opacity",
    "组" to "pairs",
    "屏宽" to "screen width",
    "行" to "lines",
    "关" to "Off",

    // ── settings: config ────────────────────────────────────────────
    "配置" to "Config",
    "导出配置" to "Export config",
    "导入配置" to "Import config",
    "导出不包含 API Key（安全考虑）；导入后自动应用" to
        "Export excludes the API key (security); import applies immediately",
    "配置已导出" to "Config exported",
    "导出失败: " to "Export failed: ",
    "配置已导入" to "Config imported",
    "导入失败：文件格式不正确" to "Import failed: invalid file format",
    "开源免费: sherpa-onnx (Apache-2.0) · SenseVoice · silero-vad · ML Kit / LibreTranslate / 自托管 LLM" to
        "Free & open-source: sherpa-onnx (Apache-2.0) · SenseVoice · silero-vad · ML Kit / LibreTranslate / self-hosted LLM",

    // ── save dialog / history ───────────────────────────────────────
    "保存到历史记录？" to "Save to history?",
    "本次共 {0} 段字幕。保存后可在历史中查看，并自动生成摘要标题。" to
        "{0} captions captured. Saved sessions appear in history with an auto summary title.",
    "不保存" to "Discard",
    "记录已保存到历史" to "Saved to history",
    "暂无历史记录\n开始一次同传并停止后会自动保存" to
        "No history yet\nStart live translation and stop to save",
    "删除这条记录？" to "Delete this record?",
    "「{0}」将被永久删除，含全部字幕与总结对话。" to
        "\"{0}\" will be permanently deleted, including all captions and chat.",
    "段" to "captions",
    "段 · " to "captions · ",

    // ── rename dialog ───────────────────────────────────────────────
    "AI 起名" to "AI name",
    "请先配置 LLM 引擎" to "Configure an LLM engine first",

    // ── chat / summary ──────────────────────────────────────────────
    "AI 总结 · {0}" to "AI Summary · {0}",
    "基于本次记录的对话" to "Chat grounded on this record",
    "继续提问…" to "Ask a follow-up…",
    "总结失败：{0}" to "Summary failed: {0}",
    "请总结这段对话记录：讨论的主题、关键信息点、结论。用中文分点输出。" to
        "Summarize this conversation: topics, key points, conclusions. Output as bullet points in Chinese.",
    "请先在设置页配置 LLM 引擎后再使用总结" to "Configure an LLM engine in Settings before summarizing",

    // ── LLM prompts (kept Chinese-only for model quality; UI status) ─
    "引擎自测中…" to "Engine self-test…",
    "自测通过({0}): {1}" to "Self-test passed ({0}): {1}",
    "自测失败({0}): {1}" to "Self-test failed ({0}): {1}",
    "已注入测试句(不经过ASR)" to "Test sentence injected (bypassing ASR)",
    "翻译({0}): {1}" to "Translate ({0}): {1}",

    // ── services / notifications ────────────────────────────────────
    "实时翻译监听中" to "Live translation listening",
    "实时翻译" to "Live translation",
    "后台监听状态与字幕兜底显示" to "Background listening status & caption fallback",
    "正在采集手机播放的声音（反向翻译模式）" to "Capturing phone playback (reverse mode)",
    "回放采集" to "Playback capture",

    // ── ViewModel status ────────────────────────────────────────────
    "未采集到播放声。请先用浏览器播放任意视频测试：若视频能出字幕而语音房不能，说明 Hilokal 的声音被标记为通话类（系统禁止捕获，需换方案）" to
        "No playback audio captured. Test with any video in a browser first: if captions appear there but not in the voice room, Hilokal's audio is flagged as a voice call (OS blocks capture; a different approach is needed)",
    "反向采集模式：翻译手机播放的声音" to "Reverse mode: translating phone playback",
    "麦克风被前台应用占用（你开了语音房麦克风）。语言房正确姿势：手机外放 + 听对方时关自己的麦，BiTrans 会自动恢复翻译对方的声音" to
        "Mic is held by a foreground app (voice-room mic is on). Correct setup: phone speaker on + mute your mic while listening; BiTrans will resume translating automatically",

    // ── TargetLang display names (dropdown) ─────────────────────────
    "English" to "English",
    "日本語" to "Japanese",
    "한국어" to "Korean",
)
