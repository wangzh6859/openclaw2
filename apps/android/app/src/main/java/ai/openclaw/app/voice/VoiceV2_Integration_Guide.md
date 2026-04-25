# Voice V2 集成指南

## 背景
Android 系统 SpeechRecognizer 模块损坏（ERROR_CLIENT code=5），导致语音无法上屏。Voice V2 使用 `AudioRecord` 直接采集音频，绕过系统 STT，通过后端 ASR 进行语音识别。

## 核心组件

### 1. VoiceInputCapture
- 使用 `AudioRecord` 直接采集 PCM 音频
- 16kHz, 16-bit, 单声道
- 实时音量级别检测
- 输出：`FloatArray` 帧（10ms/帧）

### 2. VadAwareBuffer
- 语音活动检测（VAD）
- 自动识别语音开始/结束
- 预缓冲 ~500ms 避免丢失句首
- 输出：完整语音片段的音频帧列表

### 3. BackendAsrClient
- 将音频发送到网关进行 ASR
- 调用 `voice.asr` RPC 方法
- 返回转写文本

### 4. HybridVoiceCapture
- 整合层，管理主备通道
- 主通道：`VoiceInputCapture` + 后端 ASR
- 备援：系统 SpeechRecognizer（当前损坏）

## 集成步骤

### Step 1: 在 NodeRuntime.kt 中创建 HybridVoiceCapture

```kotlin
// 在 NodeRuntime.kt 中添加
private val hybridVoiceCapture: HybridVoiceCapture by lazy {
    HybridVoiceCapture(
        context = appContext,
        scope = scope,
        session = operatorSession,  // 传入 GatewaySession
        onTranscription = { text ->
            // 处理转写结果
            Log.d("VoiceV2", "Transcribed: $text")
            // 可以调用 micCapture.queueRecognizedMessage(text) 或类似逻辑
        },
        onStatus = { status ->
            // 更新 UI 状态
            _micStatusText.value = status
        },
        onLevelChanged = { level ->
            // 更新音量级别 UI
            _micInputLevel.value = level
        }
    )
}
```

### Step 2: 替换 MicCaptureManager 的 start/stop

```kotlin
// 原 MicCaptureManager 的 start() 方法
private fun start() {
    // 旧逻辑：使用 SpeechRecognizer
    // 新逻辑：使用 HybridVoiceCapture
    hybridVoiceCapture.start()
}

// 原 MicCaptureManager 的 stop() 方法
private fun stop() {
    hybridVoiceCapture.stop()
}
```

### Step 3: 修改 MicCaptureManager 构造函数

添加 `HybridVoiceCapture` 作为可选依赖：

```kotlin
class MicCaptureManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sendToGateway: suspend (message: String, onRunIdKnown: (String) -> Unit) -> String?,
    private val speakAssistantReply: suspend (String) -> Unit = {},
    private val hybridVoiceCapture: HybridVoiceCapture? = null, // 新增
) {
    // 在 start() 中判断
    fun start() {
        if (hybridVoiceCapture != null && useVoiceV2) {
            hybridVoiceCapture.start()
        } else {
            // 使用旧逻辑
        }
    }
}
```

### Step 4: 在 NodeRuntime 中初始化

```kotlin
private val micCapture: MicCaptureManager by lazy {
    MicCaptureManager(
        context = appContext,
        scope = scope,
        sendToGateway = { message, onRunIdKnown ->
            // ... 现有逻辑
        },
        speakAssistantReply = { text ->
            voiceReplySpeaker.speakAssistantReply(text)
        },
        hybridVoiceCapture = hybridVoiceCapture // 传入 HybridVoiceCapture
    )
}
```

## 网关 RPC 实现

后端需要实现 `voice.asr` 方法：

```json
// Request
{
  "method": "voice.asr",
  "params": {
    "audio_base64": "<base64 encoded PCM16>",
    "sample_rate": 16000,
    "encoding": "pcm16",
    "language": "zh-CN"
  }
}

// Response
{
  "ok": true,
  "payload": {
    "text": "转写的文本"
  }
}
```

## 测试验证

1. 点击麦克风按钮
2. 说话
3. 观察日志：
   - `VoiceInputCapture`: 应该看到 "capture started"
   - `VadAwareBuffer`: 应该看到 "Speech started" 和 "Speech ended"
   - `BackendAsrClient`: 应该看到 "Sending audio to ASR"
4. 检查转写文本是否正确

## 故障排除

### 问题：没有声音采集
- 检查麦克风权限：`RECORD_AUDIO`
- 检查 `AudioRecord` 初始化是否成功

### 问题：ASR 无响应
- 检查网关连接状态
- 检查 `voice.asr` RPC 方法是否实现
- 查看日志中的错误信息

### 问题：VAD 不触发
- 调整 `speechThreshold`（默认 0.15f）
- 检查音频级别计算是否正确

## 下一步

- [ ] 实现网关 `voice.asr` RPC 方法
- [ ] 真机测试
- [ ] 性能优化（批量发送、压缩等）
- [ ] 支持多语言
- [ ] 支持离线 ASR（可选）
