# BiTrans — 免费实时双语语音翻译 (Android)

> 🌐 **[在线宣传页](https://samge0.github.io/BiTrans/)** — 工作链路、实测数据、特性总览一页看懂

开源技术栈实现的实时语音翻译客户端，配合 Hilokal 等语言陪练 App 使用：
麦克风持续监听 → silero VAD 自动断句 → SenseVoice 离线识别（中/英/日/韩/粤）→ 翻译 → 双语字幕 + 可选朗读。

| 视频 1 | 视频 2 |
| --- | --- |
| https://github.com/user-attachments/assets/0f0f1f87-9aeb-4b97-961f-8042709fca10 | https://github.com/user-attachments/assets/b0d6c2e6-5776-4120-ab91-03810f7e995e |


## 技术栈与许可证

| 组件 | 作用 | 许可证 |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v1.13.8 | 端侧推理引擎 (ONNX Runtime) | Apache-2.0 |
| SenseVoice int8 (FunAudioLLM) | 离线 ASR，五语自动判别 | 模型许可(基于FunASR, 推理使用无限制) |
| silero-vad | 语音活动检测/断句 | CC BY 4.0 (代码 MIT) |
| ML Kit Translation / LibreTranslate / 任意 OpenAI 兼容 LLM | 文本翻译（三引擎可切换） | 免费使用 / AGPL-3.0 / 自托管 |

**费用：0 元。** ASR 完全离线；翻译层默认尝试 ML Kit 离线包（需谷歌服务），也可切换到自托管 LibreTranslate 或局域网 LLM（如 vLLM qwen），彻底无 API 费用。

## 构建与安装

```bash
# 前置: JDK 17+, Android SDK (platform 35, build-tools 35)
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

AAR 已内置 arm64-v8a/armeabi-v7a/x86/x86_64 全 ABI 的 onnxruntime。

## 使用

1. 首启点「下载模型」(~240MB，hf-mirror 优先，失败自动回退 HuggingFace/GitHub)
2. 设置目标语言与翻译引擎
3. 点「开始同传」→ 切到 Hilokal，说外语，字幕实时双语显示
4. 前台服务保活，切后台麦克风持续工作

## 目录结构

```
app/src/main/java/com/samge/bitrans/
├── core/       AsrEngine(MicListener/ModelStore)  sherpa-onnx 封装
├── translate/  Engines(MlKit/LibreTranslate/LLM) + TranslateConfig
├── listen/     ListenService 前台保活
├── data/       Caption 数据模型
├── ui/         Compose 界面 + MainViewModel
└── MainActivity.kt
```

## 调研结论（为什么是这套）

深度调研过的替代方案：
- **Vosk**：纯离线 ASR 可用，但中文/韩文识别质量与标点/ITN 明显弱于 SenseVoice，无多语自动判别
- **whisper.cpp / whisper-streaming**：质量好但中端手机 RTF>1（实时性不够），流式方案复杂
- **sherpa-onnx 内置流式 zipformer 双语模型**：仅 zh-en，体积更大，无日韩
- **sherpa-onnx NLLB/OpusMT 离线翻译**：**已在 v1.13.x 移除**（Kotlin API 无 Translator 类），故翻译层采用 ML Kit/LibreTranslate/LLM 三引擎抽象
- **Mozilla Bergamot (firefox-translations)**：Android 集成仅面向 GeckoView，独立接入成本过高

最终链路在 Windows 桌面端已做 E2E 冒烟验证（RTF 0.12-0.14，五语识别全对，VAD 58ms 断句）。

## 已完成的真实验证（2026-09-20）

| 验证项 | 结果 |
|---|---|
| SenseVoice int8 五语识别（官方 test_wavs） | zh/en/ja/ko/yue 全部正确，含标点+ITN 数字 |
| 解码延迟 | RTF 0.12–0.14（8 倍实时，CPU 单线程） |
| silero VAD 断句 | 58ms 完成 zh.wav 分段，起点 0.73s 精准 |
| hf-mirror 镜像散文件 | model.int8.onnx 239,233,841B 与官方 tar.bz2 内容一致 |
| mirror 版 silero v5 | sherpa-onnx 加载+断句正常（备选源） |
| gradle assembleDebug | BUILD SUCCESSFUL，产出 app-debug.apk (99.9MB, arm64+armv7) |

## 已知边界

- ML Kit 翻译需要 Google Play Services；无 GMS 设备请切换 LibreTranslate 或 LLM 引擎
- LLM 引擎默认指向 `http://192.168.50.48:16868/v1`（LAN vLLM 别名，401 需在设置里填 key）
- 首次模型下载约 240MB，下载源已按 hf-mirror → huggingface → github 顺序容错

