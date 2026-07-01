# LiveBuddy (Android)

实时翻译悬浮字幕条，基于 Google Gemini Live API。捕获麦克风或系统声音，实时转写并翻译成目标语言，以**悬浮窗**形式叠加在所有 App 之上。

本仓库是 [`livebuddy-windows/`](../livebuddy-windows/) 的 Android 移植版，保留了 Windows 版的功能与 UI 设计语言：

- 浮动 HUD 字幕条（透明置顶、可拖动、可缩放、状态指示）
- 麦克风或系统音频（loopback via MediaProjection）双音源
- Gemini Live WebSocket 实时翻译
- 可选 TTS 回放（24 kHz Float32）
- 16 种目标语言
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

- Android 8.0 (API 26) 及以上
- 编译：Android Studio Hedgehog+ / Gradle 8.5+ / JDK 17
- 目标 API 34

## 权限说明

| 权限                          | 用途                                   |
|------------------------------|----------------------------------------|
| `RECORD_AUDIO`               | 麦克风采集                              |
| `SYSTEM_ALERT_WINDOW`        | 浮动字幕窗口（Display over other apps） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | 后台持续采集麦克风 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 系统音频 loopback（仅当选择 "system" 音源时） |
| `POST_NOTIFICATIONS`         | 前台服务通知（Android 13+）             |
| `INTERNET`                   | 连接 Gemini Live API                   |

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
   - 填入 Gemini API Key（[在 Google AI Studio 申请](https://aistudio.google.com/apikey)）
   - 选择目标语言（默认简体中文）
   - 选择音源：
     - **Microphone** — 直接采集麦克风
     - **System audio (loopback)** — 通过 MediaProjection 捕获系统输出（屏幕录制权限，只录音频）
   - Save
3. 回到主页，点 **Start**
4. 首次启动会依次请求：
   - 显示悬浮窗权限（跳转到系统设置 → "显示在其他应用上层"，授权后返回 App 会继续启动流程）
   - 麦克风权限
   - 通知权限（Android 13+）
   - 屏幕录制权限（仅当音源选 "system" 时；**只采集音频**，不录屏）
5. 通过后，悬浮字幕条会出现在屏幕上方，可拖动到任意位置
6. 翻译结果实时更新；点悬浮窗上的 **Pause** 暂停、**Clear** 清空、**⚙** 进入设置

## 浮动字幕条功能

- **拖动**：按住字幕条任意位置拖到任意位置
- **缩放**：拖动四角缩放手柄调整浮窗大小
- **状态指示**：左上角圆点颜色对应状态（灰色=空闲，黄色=连接中，绿色=已连接，红色=错误）
- **目标语言徽章**：右上角显示当前翻译目标语言
- **主字幕区**：翻译结果，最多保留 5 行
- **原文区**：可选显示源语言原文（在 Settings 中开启）
- **控制条**：Pause/Start · Clear · Settings

## 配置

所有配置保存在：

```
/data/data/com.faqxd.livesub.android/shared_prefs/livebuddy_settings.xml
```

**注意**：API key 以明文存储。App 已禁用 Android 系统备份，避免配置随 Auto Backup 进入云端备份；但 root 设备上的其他进程理论上仍可读取。

## 与 Windows 版的差异

| 项                  | Windows                              | Android                                       |
|--------------------|--------------------------------------|-----------------------------------------------|
| GUI 框架            | PySide6 (Qt)                         | Android View 系统                              |
| 浮动窗口            | `Qt.WindowStaysOnTopHint`            | `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` |
| 系统音频捕获        | WASAPI loopback（PyAudioWPatch）     | `MediaProjection` + `AudioPlaybackCaptureConfiguration` |
| 缩放                | 四角 QSizeGrip                       | 四角拖拽缩放手柄                                  |
| 托盘图标            | QSystemTrayIcon                      | 前台服务通知                                    |
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
│       │   ├── LiveBuddyApp.kt                # Application 入口
│       │   ├── MainActivity.kt                # 启动 Activity + 权限流
│       │   ├── SettingsActivity.kt            # 设置页
│       │   ├── data/
│       │   │   ├── AppSettings.kt             # 配置 + SharedPreferences 持久化
│       │   │   └── Languages.kt               # 16 种目标语言
│       │   ├── audio/
│       │   │   ├── PCM16Processor.kt          # 重采样 + 分块
│       │   │   ├── AudioCapture.kt            # 麦克风 / 系统音频采集
│       │   │   └── AudioPlayer.kt             # 24 kHz Float32 播放
│       │   ├── gemini/
│       │   │   └── GeminiClient.kt            # Gemini Live WebSocket 客户端
│       │   └── service/
│       │       ├── LiveTranslateService.kt    # 前台服务 + 流程编排
│       │       └── CaptionOverlayView.kt      # 浮动字幕 View
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
| `androidx.lifecycle`            | Lifecycle-aware service                    |
| `material`                      | Material Components（Button / CardView）   |
| `kotlinx-coroutines-android`   | 协程                                       |
| `okhttp3`                       | Gemini Live WebSocket 客户端                |
| `org.json`                      | JSON 解析                                  |

## 已知行为与限制

- **系统音源静音时不发数据**：和 Windows 一样，若系统未在播放任何声音，AudioRecord 不会返回有效数据，字幕不动。开始播放音频后会自动恢复。
- **MediaProjection 需要屏幕录制权限**：选择 "system" 音源时，系统会弹出屏幕录制权限请求。App **只采集音频，不录制屏幕**，但 Android 系统的提示文案无法修改。
- **前台服务通知**：Android 8+ 强制要求前台服务显示常驻通知，无法隐藏。
- **悬浮窗与状态栏**：浮窗位置基于屏幕左上角，不受状态栏遮挡影响（使用 `FLAG_LAYOUT_NO_LIMITS`）。
- **浮窗位置与大小暂不持久化**：重启服务后会回到默认位置与尺寸；字幕字号可在 Settings 中修改。
- **国内网络**：如直连 Google API 不通，可在 Settings 的 **API Base URL** 填入自建代理或区域镜像地址。

## 故障排查

**悬浮窗不出现**：检查 系统设置 → 应用 → LiveBuddy → 显示在其他应用上层 是否已开启。

**点 Start 没反应**：检查 API key 是否已填；查看通知栏是否有错误提示。

**系统音源无字幕**：MediaProjection 权限可能被吊销；停止后重新点 Start 会重新请求权限。

**报错 "Capture error"**：麦克风权限可能未授予；到 系统设置 → 应用 → LiveBuddy → 权限 中手动开启。

## License

仅供学习与个人使用。Gemini API 的使用受 Google 服务条款约束。
