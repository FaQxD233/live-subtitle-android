# 计划：字幕播放跟随视频 App（MediaSession 同步）

状态：待确认（2026-11-26 起草，未开始实现）

## 1. 目标与范围

**目标**：用户对齐一次后，字幕的播放 / 暂停、拖动、倍速自动跟着视频 App 走，不用再手动按 ▶、上一句 / 下一句。

**验收标准**

- 在至少一个主流视频 App（优先 YouTube、B 站）里：视频暂停 / 继续，字幕 0.5 秒内跟着暂停 / 继续；视频拖动进度后，字幕跳到对应位置；视频改倍速，字幕跟着改。
- 没开通知使用权、视频 App 没有媒体会话、会话中途消失时，自动退回现在的手动时钟，当前位置不跳。
- 不读取、不保存任何通知内容。

**不做**

- 不识别视频内容、不做语音对齐（自动找出字幕和视频的偏移量）。
- 不改实时翻译那条链路。
- 不追求所有 App 都能跟随：做不到的 App 继续用手动模式。

## 2. 已核实的事实

| 事实 | 来源 |
|---|---|
| `MediaSessionManager.getActiveSessions(ComponentName)` 要求调用方持有 `MEDIA_CONTENT_CONTROL`（系统权限），或者是**已启用的通知监听器**并传入它的 `ComponentName` | [MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager) |
| `addOnActiveSessionsChangedListener(listener, ComponentName[, Handler])` 的条件相同，用来在会话出现 / 消失时切换跟随对象 | 同上 |
| 不活跃（inactive）的会话对其他 App 不可见 | 同上 |
| `PlaybackState.getPosition()` 是位置（毫秒），`getLastPositionUpdateTime()` 是这个位置对应的 `SystemClock.elapsedRealtime()` 时刻，另有 `getPlaybackSpeed()`；位置可能是 `PLAYBACK_POSITION_UNKNOWN` | [PlaybackState](https://developer.android.com/reference/android/media/session/PlaybackState) |
| 打开本 App 的通知使用权页面：`Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` + `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME`（API 30+），否则 `ACTION_NOTIFICATION_LISTENER_SETTINGS`；是否已授权：`NotificationManagerCompat.getEnabledListenerPackages(context)` | [Settings](https://developer.android.com/reference/android/provider/Settings)、[NotificationManagerCompat](https://developer.android.com/reference/androidx/core/app/NotificationManagerCompat) |
| Android 13 起，**侧载安装**（本项目走 GitHub Release 的 APK）的 App 打开通知使用权会被「受限设置」拦住，用户要先到 应用信息 → ⋮ →「允许受限设置」 | [Google 支持页](https://support.google.com/android/answer/12623953) |

现有代码已经具备的基础：`SubtitleClock` 支持倍速（`rate`）和按指定时刻播放 / 暂停（`play(at)` / `pause(at)`），时间基准就是 `elapsedRealtime`，和 `PlaybackState` 的时间基准一致。

## 3. 只能真机验证的未知项

这些决定方案能不能用，**必须先验证（阶段 0）**：

1. YouTube、B 站、Chrome 网页视频、本地播放器各自是否发布媒体会话；前台全屏播放时会话是否「活跃」。
2. 它们报的位置准不准：暂停、拖动、改倍速时是否及时更新 `PlaybackState`。
3. 广告期间（YouTube 前贴片 / 中插）报的是广告的位置还是正片的位置，元数据（标题、时长）会不会变。
4. 切到下一个视频、自动连播、画中画、切到后台时的表现。
5. 同时有音乐 App 的会话时，排序第一的是不是视频 App。
6. 受限设置在用户的设备（含国产 ROM）上的实际流程。

## 4. 方案

### 4.1 时间模型

- 跟随时：`字幕位置 = 视频位置 + 偏移量`。
- 视频位置按 `PlaybackState` 外推：播放中为 `position + (elapsedRealtime() - lastPositionUpdateTime) × playbackSpeed`，暂停 / 缓冲时就是 `position`。
- 每次收到 `onPlaybackStateChanged`：
  - `clock.rate = playbackSpeed`；
  - `clock.seekTo(外推后的视频位置 + 偏移量)`；
  - 状态是 PLAYING 就播放，PAUSED / BUFFERING / STOPPED 就暂停。
- 不轮询：只靠回调。现有的 `render()` 按字幕切换时刻调度刷新，不变。

### 4.2 选跟随哪个会话

- 取 `getActiveSessions()` 里第一个状态为 PLAYING 的会话；都不在播就取第一个。
- 控制栏显示正在跟随的 App 名（`MediaController.getPackageName()` 查应用标签），让用户看得出跟错了没有。
- 会话列表变化（`OnActiveSessionsChangedListener`）或当前会话销毁（`onSessionDestroyed`）时重新选。

### 4.3 手动控件在跟随模式下的含义

| 控件 | 跟随模式下 |
|---|---|
| ▶ / ⏸ | 暂停跟随（字幕冻结）/ 恢复跟随；视频本身不受影响 |
| 上一句 / 下一句、−0.5 / +0.5、拖进度条 | 改**偏移量**，不改视频 |
| 倍速按钮 | 跟随时隐藏或禁用（倍速来自视频 App） |
| 锁定后的双击暂停 | 关闭（暂停由视频决定，双击反而会弄反） |

偏移量按字幕记住（和「从上次的位置继续」共用 `SubtitlePositions` 的 key），下次打开同一个字幕自动套用。

### 4.4 广告和换视频

- 对齐时记下会话的元数据（标题、时长）。
- 元数据变了：判定为广告或换了视频，字幕暂停跟随，控制栏提示「视频已变化，跟随已暂停」。
- 元数据变回来：自动恢复跟随。
- 第 3 节第 3 项验证后可能要调整这个规则。

### 4.5 开关、权限和说明

- 控制栏加一个「跟随视频」按钮。第一次点：先弹说明（为什么需要通知使用权、App 不读通知内容），再跳到系统的通知使用权页面。
- Android 13+ 侧载安装时，说明里附上「允许受限设置」的步骤。
- 授权后自动进入跟随模式；之后每次打开播放器，如果已授权且找到了会话，默认跟随（见第 8 节待定）。
- 用户随时可以切回手动模式。

### 4.6 退回手动

- 没有授权、找不到会话、会话消失、位置未知（`PLAYBACK_POSITION_UNKNOWN`）时，退回现在的手动时钟，保留当前字幕位置。
- 控制栏显示「手动」状态，原有按钮恢复原来的含义。

## 5. 改动清单

| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` | 新增通知监听服务声明（`BIND_NOTIFICATION_LISTENER_SERVICE` 权限 + `android.service.notification.NotificationListenerService` intent-filter） |
| `player/MediaListenerService.kt`（新） | 空的 `NotificationListenerService`，只为拿到会话列表的资格，不处理任何通知 |
| `player/VideoFollower.kt`（新） | 选会话、注册 / 注销回调、把（状态、位置、倍速、元数据）交给播放服务；会话变化时重新选 |
| `subtitle/VideoTime.kt`（新，纯 Kotlin） | 位置外推、偏移量换算、状态到播放 / 暂停的映射，便于单元测试 |
| `player/SubtitlePlayerService.kt` | 跟随模式和手动模式切换；跟随时按 4.3 解释控件；保存 / 读取偏移量 |
| `player/SubtitlePlayerOverlay.kt`、`overlay_subtitle_controls.xml` | 「跟随视频」按钮、跟随的 App 名和状态提示 |
| `player/SubtitlePositions.kt` | 每个字幕多存一个偏移量 |
| `values/strings.xml`、`values-zh/strings.xml` | 说明文案、状态提示 |
| `README.md` | 使用说明、权限说明、受限设置步骤、已知限制 |
| `app/src/test/.../VideoTimeTest.kt`（新） | 外推、偏移、倍速变化、未知位置的单元测试 |

## 6. 分阶段

1. **阶段 0：可行性验证（先做，改动最小）**
   - 只加通知监听服务和一个调试读数：控制栏显示「跟随的 App / 状态 / 位置 / 倍速」，不改字幕时钟。
   - 走 CI 出包，你在 YouTube、B 站上按第 3 节逐项看读数，决定继续还是放弃。
2. **阶段 1**：跟随播放 / 暂停 / 倍速（不跟随拖动），偏移量只在本次播放内有效。
3. **阶段 2**：跟随拖动（按 4.1 重新锚定）、偏移量按字幕保存、元数据变化检测（4.4）。
4. **阶段 3**：说明文案、受限设置引导、README、清理调试读数。

每个阶段单独提交，阶段 0 不通过就整体放弃，删掉调试代码。

## 7. 测试计划

- **单元测试（CI 上跑）**
  - 外推：播放中按倍速前进；暂停、缓冲时不动；
  - 偏移量：上一句 / 下一句 / ±0.5 只改偏移；
  - 倍速中途变化；
  - 位置未知时退回手动。
- **真机矩阵**：YouTube、B 站、Chrome 网页视频、本地播放器 × 暂停 / 继续 / 拖动 / 倍速 / 广告 / 换视频 / 画中画 / 熄屏 / 来电。

## 8. 风险与需要你决定的事

**风险**

- 通知使用权很敏感（系统会提示「可读取所有通知」），只能做成可选，说明要写清楚。
- 侧载 App 在 Android 13+ 多一步「允许受限设置」，部分国产 ROM 入口不同。
- 各 App 的会话行为不受我们控制，App 更新后可能失效，手动模式要一直保留。
- 将来如果上架 Google Play，通知监听器和 specialUse 前台服务都要单独申报说明。

**需要你决定**

1. 能接受申请通知使用权吗？不能接受的话，这个方案整体不做。
2. 是否同意先做阶段 0（只加读数），用真机结果决定后续？
3. 授权后打开播放器是否默认跟随？还是每次手动点「跟随视频」？
4. 跟随模式下，锁定后的双击暂停是关掉（建议），还是保留？
