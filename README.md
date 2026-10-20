# LiveBuddy (Android)

实时翻译悬浮字幕条，基于 Google Gemini Live API。捕获麦克风或系统声音，实时转写并翻译成目标语言，以**悬浮窗**形式叠加在所有 App 之上。

本仓库是 [`livebuddy-windows/`](../livebuddy-windows/) 的 Android 移植版，保留了 Windows 版的功能与 UI 设计语言：

- 浮动 HUD 字幕条（透明置顶、可拖动、可缩放、状态指示）
- 麦克风或系统音频（loopback via MediaProjection）双音源
- Gemini Live WebSocket 实时翻译
- 可选 TTS 回放（24 kHz Float32）
- 78 种目标语言（官方 Live Translate 支持列表，可按名称或代码搜索）
- 默认单向翻译（全部翻成一种语言）；可选双向对话，一键在两种输出语言之间切换
- 字幕内容三档：仅译文 / 双语 / 仅原文；可只显示最新 1–3 句
- 字幕记录跨会话保存，按会话分段导出为 `.txt`，可在主界面清空
- 快捷设置磁贴一键开始 / 停止
- Live 模型可配置；设置页可测试连接
- API Base URL 可自定义（走代理或自建中转）
- 配置持久化（SharedPreferences）

## 与 Windows 版的对应关系

| Windows (`livebuddy-windows/`)        | Android (`livebuddy-android/`)                                          |
|--------------------------------------|-------------------------------------------------------------------------|
| `main.py` (QApplication + 托盘)      | `MainActivity.kt` + `LiveTranslateService.kt`                           |
| `hud_window.py` (HUDWindow)          | `service/CaptionOverlayView.kt` (WindowManager 浮窗)                    |
| `gemini_client.py` (QThread + asyncio) | `gemini/GeminiClient.kt` (OkHttp WebSocket)                           |
| `audio.py` (PyAudioWPatch)           | `audio/AudioCapture.kt` (AudioRecord + MediaProjection) + `AudioPlayer.kt` |
| `pcm_processor.py`                   | `audio/PCM16Processor.kt`                                                |
| `settings.py` (AppSettings + JSON)   | `data/AppSettings.kt` + `data/Languages.kt` (SharedPreferences)         |
| `settings_window.py` (QDialog)       | `SettingsActivity.kt`                                                    |
| `%APPDATA%\gemini-live-translate\`   | `SharedPreferences("livebuddy_settings")`                               |

## 系统要求

- Android 8.0 (API 26) 及以上（系统音频需要 Android 10+）
- 编译：Android Studio Hedgehog+ / Gradle 8.5+ / JDK 17
- 目标 API 34

## 权限说明

| 权限                          | 用途                                   |
|------------------------------|----------------------------------------|
| `RECORD_AUDIO`               | 麦克风采集（系统音频回环同样需要）       |
| `SYSTEM_ALERT_WINDOW`        | 浮动字幕窗口（Display over other apps） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | 后台持续采集麦克风 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 系统音频 loopback（仅当选择 "system" 音源时） |
| `POST_NOTIFICATIONS`         | 前台服务通知（Android 13+）             |
| `INTERNET`                   | 连接 Gemini Live API                   |
| `ACCESS_NETWORK_STATE`       | 断网恢复后立即重连                      |

## 编译与运行

### 通过 Android Studio（推荐）

1. Android Studio → **Open** → 选择 `livebuddy-android/` 目录
2. 等待 Gradle sync 完成
3. 连接 Android 设备（开启 USB 调试）→ 点击 **Run ▶**

### 通过命令行

```bash
cd livebuddy-android

# 编译 Debug APK（本仓库当前未提交 Gradle wrapper，需要本机已安装 Gradle）
gradle assembleDebug

# 安装到已连接设备
gradle installDebug
```

生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

## 首次使用

1. 启动 App
2. 进入 **Settings**：
   - 填入 Gemini API Key（[在 Google AI Studio 申请](https://aistudio.google.com/apikey)），可点 **测试连接** 确认 Key / 地址 / 模型可用
   - **翻译到**：输出语言（默认简体中文）。源语言由模型自动识别
   - **双向对话的另一种语言**：默认「不使用」，即单向翻译。面对面对话时设成对方的语言，悬浮窗和主界面会出现 **⇄**，在两种输出语言之间切换
   - 按需修改 Live Model（默认 `gemini-3.5-live-translate-preview`）
   - 选择音源：
     - **Microphone** — 直接采集麦克风
     - **System audio (loopback)** — 通过 MediaProjection 捕获系统输出（屏幕录制权限，只录音频）
   - Save
3. 回到主页，点 **Start**（或下拉快捷设置，点 LiveBuddy 磁贴）
4. 首次启动会依次请求：
   - 显示悬浮窗权限（跳转到系统设置 → "显示在其他应用上层"，授权后返回 App 会继续启动流程）
   - 麦克风权限
   - 通知权限（Android 13+）
   - 屏幕录制权限（仅当音源选 "system" 时；**只采集音频**，不录屏）
5. 通过后，悬浮字幕条会出现在屏幕上方，可拖动到任意位置
6. 翻译结果实时更新；点悬浮窗上的 **Pause** 暂停、**Clear** 清空、**⚙** 进入设置，其余按钮见下节

## 浮动字幕条功能

- **拖动**：按住字幕条标题栏或空白处拖到任意位置
- **缩放**：拖动四角缩放手柄调整浮窗大小（最小宽度 340dp）
- **位置记忆**：位置和尺寸会保存，下次启动时恢复；旋转屏幕或分辨率变化时自动拉回屏幕内
- **状态指示**：左上角圆点颜色对应状态（灰色=空闲，黄色=连接中，绿色=已连接，红色=错误）。已连接但持续约 8 秒采集不到声音时，状态显示「已连接 · 未检测到声音」
- **方向徽章**：右上角显示翻译方向，单向时为「→ EN」，双向时为「中 → EN」
- **方向切换**：设置了双向对话的另一种语言时，点 **⇄** 切换输出语言
- **最小化**：点标题栏的 **—** 收成小胶囊（状态点 + 方向标签），翻译在后台继续；胶囊可拖动，点一下展开并恢复原尺寸
- **主字幕区**：译文；「仅原文」模式下显示原文。往上翻看历史时不会被新字幕拽回，滚回底部后继续跟随
- **字幕内容**：控制条上的按钮在 **译文 → 双语 → 原文** 三档之间轮换（与 Settings 中的「字幕内容」是同一选项）；双语时原文显示在译文下方
- **只显示最新字幕**：Settings 中开启后只显示最新的 1–3 句（句数可选），按句末标点切分
- **背景不透明度**：只作用于卡片背景，字幕文字始终不透明；调到 0 即透明背景
- **点击穿透**：点控制条的锁形按钮后，触摸会穿过悬浮窗传到下层应用（例如视频播放器的控件），此时悬浮窗略变透明——这是 Android 12+ 对可穿透悬浮窗的要求（窗口不透明度不超过 80%）。点原位置出现的解锁按钮，或通知栏里的“退出点击穿透”恢复
- **导出字幕**：点控制条的导出按钮（或主界面的「导出字幕」），把原文和译文按会话、按停顿分段并带时间戳导出为 `.txt`，通过系统分享发送或保存
- **控制条**：Pause/Start · Clear · 字幕内容 · 点击穿透 · 导出 · Settings

前台服务通知里有 **Pause/Start** 和 **Stop** 两个按钮。

## 配置

所有配置保存在：

```
/data/data/com.faqxd.livesub.android/shared_prefs/livebuddy_settings.xml
```

字幕记录保存在：

```
/data/data/com.faqxd.livesub.android/files/transcript.jsonl
```

**注意**：API key 和字幕记录都以明文存储。App 已禁用 Android 系统备份，避免它们随 Auto Backup 进入云端备份；但 root 设备上的其他进程理论上仍可读取。

## 与 Windows 版的差异

| 项                  | Windows                              | Android                                       |
|--------------------|--------------------------------------|-----------------------------------------------|
| GUI 框架            | PySide6 (Qt)                         | Android View 系统                              |
| 浮动窗口            | `Qt.WindowStaysOnTopHint`            | `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` |
| 系统音频捕获        | WASAPI loopback（PyAudioWPatch）     | `MediaProjection` + `AudioPlaybackCaptureConfiguration` |
| 缩放                | 四角 QSizeGrip                       | 四角拖拽缩放手柄                                  |
| 托盘图标            | QSystemTrayIcon                      | 前台服务通知 + 快捷设置磁贴                       |
| 配置存储            | `%APPDATA%\settings.json`            | `SharedPreferences`                            |
| 后台运行            | 进程                                  | Foreground Service（必须显示常驻通知）           |
| 重采样              | `numpy` + 线性插值                    | `PCM16Downsampler`（同算法，纯 Kotlin 实现）     |

## 项目结构

```
livebuddy-android/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/faqxd/livesub/android/
│       │   ├── LiveBuddyApp.kt                # Application 入口（加载字幕记录）
│       │   ├── MainActivity.kt                # 启动 Activity + 权限流 + 字幕记录导出/清空
│       │   ├── SettingsActivity.kt            # 设置页
│       │   ├── ConnectionTester.kt            # 设置页「测试连接」
│       │   ├── data/
│       │   │   ├── AppSettings.kt             # 配置 + SharedPreferences 持久化
│       │   │   └── Languages.kt               # 78 种目标语言
│       │   ├── audio/
│       │   │   ├── PCM16Processor.kt          # 重采样 + 分块
│       │   │   ├── AudioCapture.kt            # 麦克风 / 系统音频采集 + 静音检测
│       │   │   └── AudioPlayer.kt             # 24 kHz Float32 播放
│       │   ├── gemini/
│       │   │   └── GeminiClient.kt            # Gemini Live WebSocket 客户端
│       │   ├── service/
│       │   │   ├── LiveTranslateService.kt    # 前台服务 + 流程编排（重连、goAway 换线）
│       │   │   ├── CaptionOverlayView.kt      # 浮动字幕 View
│       │   │   ├── LiveTileService.kt         # 快捷设置磁贴
│       │   │   └── PipelineState.kt           # 服务 → 主界面的状态桥
│       │   └── transcript/
│       │       ├── TranscriptRecorder.kt      # 字幕记录（按会话、按停顿分段）
│       │       ├── TranscriptStore.kt         # 进程级记录 + JSONL 追加落盘
│       │       ├── TranscriptExporter.kt      # 导出 txt + 系统分享
│       │       └── Sentences.kt               # 句子切分（只显示最新字幕）
│       └── res/
│           ├── drawable/                      # 背景、按钮、图标
│           ├── layout/                        # activity_main / activity_settings / overlay_caption
│           ├── mipmap-anydpi-v26/             # 启动器图标
│           └── values/                        # strings / colors / themes / styles
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```

## 依赖

| 库                              | 用途                                       |
|--------------------------------|--------------------------------------------|
| `androidx.core:core-ktx`       | AndroidX Kotlin 扩展                       |
| `androidx.appcompat`            | AppCompatActivity                          |
| `androidx.lifecycle`            | Lifecycle-aware service / lifecycleScope    |
| `material`                      | Material Components（Button / CardView）   |
| `kotlinx-coroutines-android`   | 协程                                       |
| `okhttp3`                       | Gemini Live WebSocket 客户端                |

JSON 解析使用 Android 框架自带的 `org.json`。

## 已知行为与限制

- **系统音源静音时不发数据**：和 Windows 一样，若系统未在播放任何声音，AudioRecord 不会返回有效数据，字幕不动；持续约 8 秒后状态会提示「未检测到声音」。开始播放音频后会自动恢复。部分 App 禁止被录制，它们的声音同样采集不到。
- **MediaProjection 需要屏幕录制权限**：选择 "system" 音源时，系统会弹出屏幕录制权限请求。App **只采集音频，不录制屏幕**，但 Android 系统的提示文案无法修改。服务在暂停期间会保留授权，恢复时不再重复申请；如果在状态栏停止共享，管线会停下并提示重新授权。
- **前台服务通知**：Android 8+ 强制要求前台服务显示常驻通知，无法隐藏。
- **悬浮窗与状态栏**：浮窗位置基于屏幕左上角，不受状态栏遮挡影响（使用 `FLAG_LAYOUT_NO_LIMITS`）。
- **最小化和点击穿透状态不保存**：服务重新启动后悬浮窗总是展开且可触摸；位置和尺寸会保存。
- **字幕记录**：保存在 App 私有目录，跨运行、进程被回收后都保留，每次服务运行是一个会话；悬浮窗的 Clear 只清屏幕上的字幕，不影响记录。在主界面点「清空记录」才会删除，最多保留 20000 条。
- **会话换线**：服务端约每 10 分钟发出 goAway，App 会自动换到新会话；换线期间采集不停，最多缓存 5 秒音频，新会话就绪后补发。
- **国内网络**：如直连 Google API 不通，可在 Settings 的 **API Base URL** 填入自建代理或区域镜像地址。注意 API Key 会随请求头发给该地址，只填自己信任的服务。

## 故障排查

**悬浮窗不出现**：检查 系统设置 → 应用 → LiveBuddy → 显示在其他应用上层 是否已开启。

**点 Start 没反应**：检查 API key 是否已填；在 Settings 点 **测试连接**；查看通知栏是否有错误提示。

**已连接但没有字幕**：状态显示「未检测到声音」时，说明采集到的是静音：系统音频模式下对方 App 可能禁止录制或没在出声；麦克风模式下可能正在通话或被其他 App 占用。

**系统音源无字幕**：MediaProjection 权限可能被吊销；停止后重新点 Start 会重新请求权限。

**报错 "Capture error"**：麦克风权限可能未授予；到 系统设置 → 应用 → LiveBuddy → 权限 中手动开启。

## License

仅供学习与个人使用。Gemini API 的使用受 Google 服务条款约束。
