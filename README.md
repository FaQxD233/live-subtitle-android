# LiveBuddy (Android)

实时翻译悬浮字幕条，基于 Google Gemini Live API。捕获麦克风或系统声音，实时转写并翻译成目标语言，以**悬浮窗**形式叠加在所有 App 之上。

本仓库是 [`livebuddy-windows/`](../livebuddy-windows/) 的 Android 移植版，保留了 Windows 版的功能与 UI 设计语言：

- 浮动 HUD 字幕条（透明置顶、可拖动、可缩放、状态指示）
- 麦克风或系统音频（loopback via MediaProjection）双音源
- Gemini Live WebSocket 实时翻译
- 可选 TTS 回放（24 kHz Float32）
- 78 种目标语言（官方 Live Translate 支持列表，同时显示语言自身的名称和英文名，如「日本語 · Japanese」，可按两者或代码搜索）
- 默认单向翻译（全部翻成一种语言）；可选双向对话，一键在两种输出语言之间切换
- 字幕内容三档：仅译文 / 双语 / 仅原文；可只显示最新 1–3 句
- 字幕记录：每次运行结束后自动暂存（最多 3 次，不足 5 秒或没有字幕的不暂存），可在主界面的「字幕记录」里永久保存，再查看、导出、播放或删除
- 导出字幕：可选译文字幕（`.srt`）、原文字幕（`.srt`）或双语文本（`.txt`），通过系统分享发送，或「另存为」直接存到下载等目录
- 字幕播放：打开自己的 `.srt` / `.vtt` / `.ass` 字幕文件（或某条字幕记录），以视频字幕的样式叠加在 YouTube 等没有字幕的视频上，手动对齐
- 快捷设置磁贴一键开始 / 停止
- Live 模型可配置；设置页可测试连接
- API Base URL 可自定义（走代理或自建中转）
- 配置持久化（SharedPreferences）
- 界面支持英文和简体中文，默认跟随系统；Android 13+ 可在设置页的「界面语言」单独选择（跳转到系统的应用语言设置）

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
| `FOREGROUND_SERVICE_SPECIAL_USE` | 字幕播放（只显示字幕，不播放音视频，属于特殊用途前台服务） |
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

# 运行单元测试（字幕分段、SRT 生成、字幕文件解析、播放时钟）
gradle testDebugUnitTest
```

CI（`Build Android`）在打包前会先跑单元测试。

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
- **导出字幕**：点控制条的导出按钮，导出本次运行到目前为止的字幕。可选译文字幕（SRT）、原文字幕（SRT）或双语文本（TXT），没有内容的格式不出现；选「分享」走系统分享，选「另存为」直接存到下载等目录。会记住上次选的格式。停止后这次运行会进入「字幕记录」
- **控制条**：Pause/Start · Clear · 字幕内容 · 点击穿透 · 导出 · Settings

前台服务通知里有 **Pause/Start** 和 **Stop** 两个按钮。

## 字幕记录

从开始到停止算一次运行，中途的暂停、换线、重连都算在同一次里。主界面的 **字幕记录** 按运行管理字幕：

- **最近（未保存）**：运行结束时，有字幕且不少于 5 秒的会自动暂存在这里，最多 3 条，新的替换最旧的。每条可以 **保存**，「更多」菜单里还有 **播放**、**导出…** 和 **丢弃**。运行中 App 被强行停止的那次，下次打开 App 时补进来
- **已保存**：按开始时间倒序排列，每条可以 **播放**，「更多」菜单里有 **导出…** 和 **删除**。保存的是这次运行的完整记录（每句的起止时间、原文和译文），随时可以再导出成任意一种格式
- 旧版本保存的 `.txt` 也列在「已保存」里，可以查看、**分享**、删除，但不能播放（txt 里没有每句的结束时间）
- 点一条记录可以在 App 内查看全文，文字可选中复制

字幕按句记录：每路（原文、译文）满一句（至少 1.5 秒）就切一条；停顿超过 2 秒、一条超过 6 秒或约两行字也会切开。导出的 SRT 以按下「开始」的那一刻为 00:00:00，时间是文字到达手机的时间，比原声晚 1–3 秒（翻译延迟），暂停的时间也算在内，拿去配视频时需要整体对齐一次（见下一节的「上一句 / 下一句」）。

## 字幕播放

给没有字幕的视频（比如 YouTube）配上自己的字幕：

1. 主界面点 **播放字幕文件**，选一个 `.srt`、`.vtt`、`.ass` 或 `.ssa` 文件；也可以在「字幕记录」里点某条记录的 **播放**（同时有原文和译文时会问播放哪一路）
2. 字幕和控制栏是两个独立的悬浮窗，各自拖动、各自记住位置，互不推挤：
   - **字幕**：屏幕下方居中，白字黑边，只显示当前这一句，两句之间不显示。按屏幕宽度换行（左右各留一点边），横屏也能铺开。底边固定，两行字幕往上长。背景框的深浅跟随设置里的「背景不透明度」，调到 0 就只剩描边。没锁定时，字幕所在的那一行（和屏幕一样宽）都能上下拖动，点一下会展开控制栏；这一行也会挡住下层视频 App 的点击，看视频时建议锁定
   - **控制栏**：和实时翻译悬浮窗一样的深色卡片。第一行是时间、锁定、最小化、关闭，第二行是进度条，第三行是 上一句、−0.5、播放/暂停、+0.5、下一句、A−、A+。拖动卡片的空白处可以移动
3. 打开时处于暂停状态，控制栏展开。这时没有字幕的话，字幕位置上会显示一行淡淡的「字幕显示在这里」，方便先把字幕拖到视频下方
4. 切到视频 App，视频开始时点 **▶**。对齐：字幕慢了（人已经说了，字幕还没出来），听到那句话时点 **下一句**；字幕快了，等人开口说这句时点 **上一句**（当前这句已显示超过 1 秒时回到它的开头，否则回到上一句）。**−0.5 / +0.5** 是把时间往回 / 往前拨 0.5 秒（字幕推后 / 提前），进度条可以直接拖动
5. **A− / A+** 调字号，下次打开时保留
6. **最小化**：控制栏收成小胶囊（显示播放 / 暂停状态），点一下展开。播放时控制栏 4 秒没操作会自动收起，暂停时不收
7. **锁定**：点在字幕上的触摸会穿透到下层（照样能点视频 App 的控件），字幕略变透明（Android 12+ 对可穿透悬浮窗的要求），控制栏同时收起，胶囊上多一个锁形标记。要解锁，点胶囊展开控制栏再点解锁按钮，或用通知栏里的「解锁」
8. 通知栏有 **播放/暂停**、**解锁**、**停止**。播放到最后一句结束后自动暂停

字幕播放和实时翻译不能同时运行：开始其中一个会自动停止另一个，并弹出提示。

格式与编码：支持 SRT、WebVTT 和 ASS/SSA（ASS 只取文字，样式、位置、特效和绘图都忽略），保留斜体、粗体、下划线。编码自动识别：带 BOM 的 UTF-8 / UTF-16，不带 BOM 的 UTF-16、UTF-8，其余按 GB18030（兼容 GBK、GB2312）读取。文件不能超过 5 MB。

## 配置

所有配置保存在：

```
/data/data/com.faqxd.livesub.android/shared_prefs/livebuddy_settings.xml
```

字幕记录保存在：

```
/data/data/com.faqxd.livesub.android/files/session.jsonl                           # 进行中的运行
/data/data/com.faqxd.livesub.android/files/transcripts/recent/                     # 最近（未保存）
/data/data/com.faqxd.livesub.android/files/transcripts/saved/                      # 已保存
/storage/emulated/0/Android/data/com.faqxd.livesub.android/files/transcripts/      # 旧版本保存的 .txt
```

**注意**：API key 和字幕记录都以明文存储。App 已禁用 Android 系统备份，避免它们随 Auto Backup 进入云端备份；但 root 设备上的其他进程理论上仍可读取。旧版本保存的 `.txt` 在 `Android/data` 下：Android 11 起其他 App 无法访问，Android 10 及以下有存储权限的 App 可以读取。

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
│       │   ├── LiveBuddyApp.kt                # Application 入口（接管上次进程留下的字幕记录）
│       │   ├── MainActivity.kt                # 启动 Activity + 权限流 + 打开字幕文件
│       │   ├── TranscriptsActivity.kt         # 字幕记录：最近（保存 / 丢弃）+ 已保存（播放 / 导出 / 删除）
│       │   ├── ExportDialog.kt                # 导出对话框：SRT / TXT，分享或另存为
│       │   ├── ExportActivity.kt              # 悬浮窗导出时承载导出对话框的透明 Activity
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
│       │   │   ├── OverlayWindow.kt           # 悬浮窗公共部分：拖动、限制在屏幕内、点击穿透
│       │   │   ├── LiveTileService.kt         # 快捷设置磁贴
│       │   │   └── PipelineState.kt           # 服务 → 主界面的状态桥
│       │   ├── player/
│       │   │   ├── SubtitlePlayerService.kt   # 字幕播放前台服务：时钟、通知、与实时翻译互斥
│       │   │   ├── SubtitlePlayerOverlay.kt   # 字幕窗（视频字幕样式）+ 控制栏窗（可收起、自动隐藏）
│       │   │   ├── SubtitlePlayback.kt        # 交给播放服务的字幕（进程内）
│       │   │   └── SubtitleFiles.kt           # 读取用户选的字幕文件
│       │   ├── subtitle/
│       │   │   ├── SubtitleCue.kt             # 一条字幕（起止时间 + 文字）
│       │   │   ├── SubtitleParser.kt          # 解析 SRT / WebVTT / ASS
│       │   │   ├── SubtitleText.kt            # 编码识别、标签清理
│       │   │   ├── SrtWriter.kt               # 生成 SRT
│       │   │   ├── CueTimeline.kt             # 某一时刻显示哪几条、上一句 / 下一句
│       │   │   └── SubtitleClock.kt           # 播放时钟
│       │   └── transcript/
│       │       ├── TranscriptRecorder.kt      # 本次运行的字幕（按句切成带起止时间的字幕条）
│       │       ├── TranscriptStore.kt         # 进程级记录：运行日志、最近与已保存
│       │       ├── TranscriptCues.kt          # 记录 → 字幕条（导出 SRT、播放）
│       │       ├── TranscriptExporter.kt      # 渲染 SRT / txt + 系统分享 / 另存为
│       │       └── Sentences.kt               # 句子切分（录制分段、只显示最新字幕）
│       └── res/
│           ├── drawable/                      # 背景、按钮、图标
│           ├── layout/                        # 主界面 / 设置 / 字幕记录 / 悬浮窗 / 字幕播放
│           ├── mipmap-anydpi-v26/             # 启动器图标
│           └── values/                        # strings / colors / themes / styles
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```

JVM 单元测试在 `app/src/test/`。

## 依赖

| 库                              | 用途                                       |
|--------------------------------|--------------------------------------------|
| `androidx.core:core-ktx`       | AndroidX Kotlin 扩展                       |
| `androidx.appcompat`            | AppCompatActivity                          |
| `androidx.lifecycle`            | Lifecycle-aware service / lifecycleScope    |
| `material`                      | Material Components（Button / CardView）   |
| `kotlinx-coroutines-android`   | 协程                                       |
| `okhttp3`                       | Gemini Live WebSocket 客户端                |
| `junit`（仅测试）                | JVM 单元测试                                |

JSON 解析使用 Android 框架自带的 `org.json`。

## 已知行为与限制

- **系统音源静音时不发数据**：和 Windows 一样，若系统未在播放任何声音，AudioRecord 不会返回有效数据，字幕不动；持续约 8 秒后状态会提示「未检测到声音」。开始播放音频后会自动恢复。部分 App 禁止被录制，它们的声音同样采集不到。
- **MediaProjection 需要屏幕录制权限**：选择 "system" 音源时，系统会弹出屏幕录制权限请求。App **只采集音频，不录制屏幕**，但 Android 系统的提示文案无法修改。服务在暂停期间会保留授权，恢复时不再重复申请；如果在状态栏停止共享，管线会停下并提示重新授权。
- **前台服务通知**：Android 8+ 强制要求前台服务显示常驻通知，无法隐藏。
- **悬浮窗与状态栏**：浮窗位置基于屏幕左上角，不受状态栏遮挡影响（使用 `FLAG_LAYOUT_NO_LIMITS`）。
- **最小化和点击穿透状态不保存**：服务重新启动后悬浮窗总是展开且可触摸；位置和尺寸会保存。
- **字幕记录**：未保存的最多暂存 3 次运行，更早的会被自动删除，想留的要及时保存；悬浮窗的 Clear 只清屏幕上的字幕，不影响记录。已保存的记录在 App 私有目录里，**卸载 App 会一并删除**；要留文件，用「导出… → 另存为」存到下载等目录。
- **导出的 SRT 时间**：以按下「开始」为零点，用的是文字到达手机的时间，比原声晚 1–3 秒，暂停的那段也算在内。配视频用时先整体对齐。
- **字幕播放靠手动对齐**：播放器有自己的时钟，不会跟着视频 App 暂停或拖动；视频暂停或跳转后，要在播放器里同样操作，或用「上一句 / 下一句」重新对齐。不带 BOM 的日文 Shift-JIS、繁体 Big5 字幕会被当成 GB18030 读成乱码，先转成 UTF-8 再打开。
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
