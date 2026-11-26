# 计划：字幕播放跟随视频 App（MediaSession 同步）

状态：阶段 0 已在真机上跑过（第 3 节末尾）；阶段 1 按第三次修订（第 9 节）实现，待 CI 出包和真机验证（2026-11-26 起草，同日修订三次）

## 1. 目标与范围

**目标**：用户对齐一次后，字幕的播放 / 暂停、拖动、倍速自动跟着视频 App 走，不用再手动按 ▶、上一句 / 下一句。

**验收标准**

- 在至少一个主流视频 App（优先 YouTube、B 站）里：视频暂停 / 继续，字幕 0.5 秒内跟着暂停 / 继续；视频拖动进度后，字幕跳到对应位置；视频改倍速，字幕跟着改。
- 正常播放时，字幕不会因为位置上报的小幅抖动而闪烁或来回跳。
- 没开通知使用权、视频 App 没有媒体会话、会话中途消失、权限中途被撤销时，自动退回现在的手动时钟，当前位置不跳，App 不崩溃。
- 不读取、不保存任何通知内容。

**不做**

- 不识别视频内容、不做语音对齐（自动找出字幕和视频的偏移量）。
- 不改实时翻译那条链路。
- 不追求所有 App 都能跟随：做不到的 App 继续用手动模式。

## 2. 已核实的事实

**Android 参考文档**

| 事实 | 来源 |
|---|---|
| `MediaSessionManager.getActiveSessions(ComponentName)` 要求调用方持有 `MEDIA_CONTENT_CONTROL`（系统权限），或者是**已启用的通知监听器**并传入它的 `ComponentName` | [MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager) |
| `addOnActiveSessionsChangedListener(listener, ComponentName[, Handler])` 的条件相同，用来在会话出现 / 消失时切换跟随对象；Handler 为 null 时回调发到调用方的线程；回调参数 `controllers` **可能为 null** | 同上、[OnActiveSessionsChangedListener](https://developer.android.com/reference/android/media/session/MediaSessionManager.OnActiveSessionsChangedListener) |
| 不活跃（inactive）的会话对其他 App 不可见 | 同上 |
| `PlaybackState.getPosition()` 是位置（毫秒），`getLastPositionUpdateTime()` 是这个位置对应的 `SystemClock.elapsedRealtime()` 时刻，**从没设过位置时为 0**，另有 `getPlaybackSpeed()`；位置可能是 `PLAYBACK_POSITION_UNKNOWN`（-1）。`isActive()` 是 API 31 才有的，本项目 minSdk 26，不能直接调用 | [PlaybackState](https://developer.android.com/reference/android/media/session/PlaybackState) |
| 打开本 App 的通知使用权页面：`Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` + `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME`（API 30+，值是字符串形式的 ComponentName），否则 `ACTION_NOTIFICATION_LISTENER_SETTINGS`；文档提醒「有时没有对应的 Activity」，要防住；是否已授权：`NotificationManagerCompat.getEnabledListenerPackages(context)` | [Settings](https://developer.android.com/reference/android/provider/Settings)、[NotificationManagerCompat](https://developer.android.com/reference/androidx/core/app/NotificationManagerCompat) |
| 监听服务在清单里声明为 `android:exported="false"`、`android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`，intent-filter 的 action 为 `android.service.notification.NotificationListenerService`；Android 7 起回调都在主线程；`requestUnbind()`（API 24）要等 `onListenerConnected()` 之后再调 | [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) |
| 「受限设置」：被限制的 App 要到 应用信息 → ⋮ →「允许受限设置」放开后，才能打开受限的开关。这一页只有这个通用步骤，**没有点名通知使用权，也没说哪些 App 受限**；侧载安装（本项目走 GitHub Release 的 APK）从 Android 13 起受限是常见说法，下面的 AOSP 源码只证实通知使用权的开关会过这道检查，以真机为准（第 3 节第 6 项） | [Google 帮助页](https://support.google.com/android/answer/12623953) |
| 以 Android 11（API 30）及以上为目标时，查询其他 App 信息的方法（如 `getPackageInfo()`）默认被过滤；文档列出的「自动可见」情形里没有通知监听器，也没有媒体会话的控制方。所以查 YouTube、B 站的应用名大概率要在清单里声明 `<queries>` | [软件包可见性](https://developer.android.com/training/package-visibility)、[自动可见的 App](https://developer.android.com/training/package-visibility/automatic) |

**AOSP 源码**（frameworks/base，main 分支，2026-11-26 查阅）

| 事实 | 位置 |
|---|---|
| `getPlaybackSpeed()` 是相对正常速度的倍数：1 为正常速度，**暂停时为 0，倒放时为负** | [PlaybackState.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/session/PlaybackState.java) |
| 播放状态共 12 种：NONE、STOPPED、PAUSED、PLAYING、FAST_FORWARDING、REWINDING、BUFFERING、ERROR、CONNECTING 和三种 SKIPPING_TO_*；其中 PLAYING、BUFFERING、CONNECTING、快进、倒放、跳转算「播放活跃」（`isActive()`） | 同上 |
| `getActiveSessions()` 的顺序：播放活跃的会话排在前面，其余排在后面，各组内部保持系统会话栈的先后。会话进入快进、倒放、跳到上一个 / 下一个，或从其他状态进入 PLAYING / BUFFERING / CONNECTING 时，会被移到栈顶。所以排第一的通常是最近开始播放的那个 | [MediaSessionStack.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/media/MediaSessionStack.java)（`getPriorityList`、`onPlaybackStateChanged`）、[MediaSessionRecord.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/media/MediaSessionRecord.java) |
| 会话监听器只在会话创建、激活 / 失活、销毁和媒体按钮会话变化时收到列表变化；普通的播放状态变化只调整排序，不通知监听器。所以**别的会话开始播放时，不保证会收到 `onActiveSessionsChanged`** | [MediaSessionService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/media/MediaSessionService.java)（`onSessionPlaybackStateChanged`、`postSessionsChanged` 的调用处） |
| 权限不满足时，取会话列表、注册会话监听都会抛 `SecurityException` | 同上（`enforceMediaPermissions`） |
| 用户撤销通知使用权后，系统给已注册的会话监听器回调一个**空列表**，然后断开它 | 同上 |
| `MediaMetadata.getLong()` 取不到值时返回 0，所以没有时长的元数据读出来是 0 | [MediaMetadata.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/MediaMetadata.java) |
| `MediaController.getTransportControls()` 提供 `play()`、`pause()`、`seekTo()`；会话支持哪些，看 `PlaybackState.getActions()` 里的 `ACTION_PLAY`、`ACTION_PAUSE`、`ACTION_PLAY_PAUSE` | [MediaController.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/session/MediaController.java)、PlaybackState.java |
| 取会话列表时的权限检查只看通知使用权**是否已授权**，不看监听服务当前有没有绑定：`getSessions` → `verifySessionsRequest` → `enforceMediaPermissions` → NotificationManagerService 的 `hasEnabledNotificationListener` → ManagedServices 的 `isPackageAllowed`，最后只读已授权名单 `mApproved` | GitHub 镜像 aosp-mirror 的 main：MediaSessionService.java、NotificationManagerService.java、ManagedServices.java |
| 监听服务调 `requestUnbind()` 后：系统把它记为 snoozed（只在内存里）并解绑，已授权名单不变；之后只有 `requestRebind()`、删除用户或重启才会再绑定，在设置里关掉再打开通知使用权**不会**让它重新绑定 | 同上（`requestUnbindListener`、`setComponentState`、`populateComponentsToBind`） |
| 通知使用权的开关会过受限设置的检查（`ApprovalPreferenceController` 用 `RestrictedSwitchPreference`；新的 ECM 机制下按 `OPSTR_ACCESS_NOTIFICATIONS` 检查）。应用信息 ⋮ 菜单里的「允许受限设置」只在 app op `ACCESS_RESTRICTED_SETTINGS` 为 `MODE_IGNORED`（ECM 下为 `isClearRestrictionAllowed`）时显示；这个状态在哪里设上的没查到，常见说法是先点一次灰色的开关、看到「受限设置」提示后才会出现 | aosp-mirror 的 main：packages/apps/Settings 的 ApprovalPreferenceController.java、NotificationAccessDetails.java、AppInfoDashboardFragment.java（`shouldShowAccessRestrictedSettings`） |

**现有代码**

- `SubtitleClock` 支持倍速（`rate`）和按指定时刻播放 / 暂停（`play(at)` / `pause(at)`），时间基准就是 `elapsedRealtime`，和 `PlaybackState` 的时间基准一致。
- `SubtitleClock.rate` 只接受大于 0 的值，否则抛异常（`require`）。视频报的 0 或负数速度**不能**直接写进去，否则播放服务会崩溃。
- 字幕记录导出的 SRT 以按下「开始」的那一刻为 00:00:00（见 README），和视频的时间轴对不上。
- `SubtitleClock.seekTo()` 把负数按 0 算，播放服务的 `seekTo()` 还限制在 [0, 字幕时长]。
- `render()` 播过最后一句 1 秒后自动暂停并停在结尾；没有下一次字幕变化时，按「最后一句结束 + 1 秒」排下一次刷新，过了这一刻 `timeUntil()` 为 0，刷新间隔会落到 16 ms 的下限。手动模式靠自动暂停，走不到这一步。
- 通知栏的播放 / 暂停（`ACTION_TOGGLE`）、熄屏广播、锁定后的双击都走 `togglePlayback()`。

## 3. 只能真机验证的未知项

这些决定方案能不能用、阈值取多少，**必须先验证（阶段 0）**：

1. YouTube、B 站、Chrome 网页视频、本地播放器各自是否发布媒体会话；前台全屏播放时会话是否「活跃」；`getActions()` 里有没有播放 / 暂停。
2. 位置报得准不准：正常播放时多久回调一次，每次上报的位置和我们外推的位置差多少（决定防抖阈值）；上报到达时距它的 `lastPositionUpdateTime` 多久（看 App 有没有用错时间基准）；暂停、拖动、改倍速时是否及时更新；有没有 App 在 PLAYING 时把速度报成 0。
3. 刚起播时元数据是否先空着（标题为空、时长为 0）再补全，补全前后时长是否有小幅变化；广告期间（YouTube 前贴片 / 中插）报的是广告还是正片的位置和元数据。
4. 切到下一个视频、自动连播、画中画、切到后台、后台播放时熄屏（YouTube 会员）的表现。
5. 同时有音乐 App 的会话时谁排第一；微信语音消息、导航播报等是否会发布「正在播放」的会话，会不会把跟随抢走。
6. 受限设置在用户的设备（含国产 ROM）上的实际流程。
7. 监听服务 `requestUnbind()` 之后（4.7），在你的设备上取会话是否照常。AOSP 上应当照常（第 2 节）。
8. 不声明 `<queries>` 时能不能查到 YouTube、B 站的应用名（第 2 节，软件包可见性）。

**阶段 0 实测**（2026-11-26，Xiaomi 2407FRK8EC，Android 14，记录约 4.5 分钟，主要是 YouTube）

| 项 | 结果 |
|---|---|
| 1 | YouTube 发布会话，播放时排第一，动作有 play、pause，正片还有 seek。B 站国际版（`com.bilibili.app.in`）有会话，动作有 play、pause、play_pause，没有 seek；这次没在里面播放，上报情况未知 |
| 2 | YouTube 只在状态变化时回调，正常播放时最长 48 秒没有回调；上报到达时距 `lastPositionUpdateTime` 2–11 ms，时间基准没问题。按暂停时报的位置倒推：两次从头开始播放后，外推比视频快约 0.9 秒，另一次慢约 0.4 秒，即 YouTube 报「开始播放」时画面往往还没动。前后两次都在播放的上报只有 3 次：差 1 ms、拖回开头（−19.9 秒）、画面还没动（−0.36 秒） |
| 3 | 片头广告期间，标题是正片的，时长是广告的（24 秒），没有 seek；正片开始后时长变为 14:37，seek 出现。会话刚出现时标题为空、时长为 0。B 站有标题，时长为 0 |
| 4 | YouTube 换视频时旧会话消失，再新建一个会话（标题先空着） |
| 5 | 没测 |
| 6 | 授权在 12 秒内完成，有没有遇到受限设置待你确认 |
| 7 | 解绑后取会话一直正常 |
| 8 | 查不到应用名：YouTube、B 站都是 `label not visible` |

## 4. 方案

### 4.1 时间模型

- 跟随时：`字幕位置 = 视频位置 + 偏移量`。**字幕时钟记视频位置**，偏移量另存；凡是读字幕位置的地方（`render()` 和它排的刷新、上一句 / 下一句、进度条、关闭时记的位置）都用 `clock.position() + 偏移量`。手动模式下偏移量恒为 0，现有逻辑不变。
  - 不让时钟直接记字幕位置，是因为 `SubtitleClock.seekTo()` 把负数按 0 算（第 2 节）。偏移量为负很常见：字幕记录的 00:00 是按「开始」的那一刻，通常在视频中间；片头更长的视频版本也是。视频还没到偏移点时字幕位置是负数，写进时钟就成了 0，时钟从此比视频快这么多，字幕提前出现，要等下一次回调才纠正，而正常播放时可能一直没有回调；拿这个时钟算偏移量也会带上误差。视频位置不会是负数。
- 进入跟随时的偏移量：有这个字幕记住的偏移量就用（阶段 2），否则为 0，因为字幕文件的时间轴通常就是视频的时间轴。对不上时（字幕记录、版本不同的视频）用上一句 / 下一句、±0.5、拖进度条调一次。
- 视频位置按 `PlaybackState` 外推：播放中为 `position + (elapsedRealtime() - lastPositionUpdateTime) × playbackSpeed`。经过的时间小于 0 时按 0 算；`lastPositionUpdateTime` 为 0（App 从没设过）时也不外推，否则会从开机时刻算起。算作暂停时就是 `position`。
- 状态映射：**只有 `STATE_PLAYING` 且速度大于 0 才算播放**。其余状态（缓冲、快进、倒放、跳转、连接中、出错、停止、无）以及速度 ≤ 0 一律算暂停。速度只在大于 0 时写进 `clock.rate`。
- 每次收到 `onPlaybackStateChanged`：
  1. 外推出视频的当前位置；
  2. 映射出的播放 / 暂停和速度都和时钟当前的一样，且视频位置和 `clock.position()` 相差小于防抖阈值时，什么都不做，免得字幕在切换点附近闪烁或来回跳；
  3. 否则写入速度（大于 0 时）、`clock.seekTo(视频位置)`，再按映射播放或暂停。
- 视频拖动后位置差会超出阈值，所以拖动自然就跟上了，不用单独处理。
- 防抖阈值先按 300 ms 写，阶段 0 按实测的抖动幅度定。
- 不轮询：只靠回调。`render()` 照旧按字幕切换时刻调度刷新，跟随时改两处：
  - 播完最后一句不自动暂停，播不播由视频决定；
  - 过了最后一次字幕变化，就不再按字幕排刷新，只留控制栏展开时 250 ms 一次的进度刷新。否则按现在的写法会 16 ms 刷新一次（第 2 节）。

### 4.2 选跟随哪个会话

- 开始跟随时取 `getActiveSessions()` 的第一个：系统已经把播放活跃的排在前面、最近开始播放的排最前（第 2 节），不用自己再排。
- **粘性**：当前跟随的会话还在列表里，就一直跟它；只有它不在播放、而另一个会话正在播放时，才自动切到那个会话。「在播放」用 4.1 的状态映射，不用 `isActive()`（API 31，第 2 节）。
  - 每当任何一个会话的状态变化时，都按这条规则检查一次。别的会话开始播放时系统不保证通知列表变化（第 2 节），所以要给**每个活跃会话**都注册回调，不能只注册当前跟随的那个。
  - 被临时的播放（比如语音消息）抢走时，视频本来就是暂停的；语音放完、视频恢复播放后，按同一条规则又会切回来。
- 当前会话不在新列表里（包括撤销权限后系统给的空列表；回调给的列表为 null 时按空列表算）或 `onSessionDestroyed` 时，按开始跟随的规则重新选；一个都没有就退回手动时钟（4.6），但「跟随视频」开关仍开着：之后一出现会话就接着跟随，偏移量不变，是不是同一个视频由 4.4 判断（YouTube 换视频会新建会话）。只有用户自己关掉开关，才一直是手动。
- 用户点 App 名选中的会话不再自动切走，直到它从列表里消失。
- 控制栏显示正在跟随的 App 名（`MediaController.getPackageName()` 查应用标签；受软件包可见性限制可能查不到，查不到就显示包名，见第 2 节、第 3 节第 8 项）。**点 App 名在当前活跃的会话之间轮换**，自动选错时手动改。

### 4.3 手动控件在跟随模式下的含义

| 控件 | 跟随模式下 |
|---|---|
| ▶ / ⏸ | 让**视频**播放 / 暂停（`getTransportControls().play()` / `pause()`），字幕随状态回调跟着走；会话不支持（`getActions()` 里没有对应动作）时禁用 |
| 通知栏的播放 / 暂停 | 和 ▶ / ⏸ 一样控制视频，会话不支持时不显示；视频播放 / 暂停时更新通知文字 |
| 上一句 / 下一句、−0.5 / +0.5、拖进度条 | 只改**偏移量**，不动视频 |
| 倍速按钮 | 禁用（倍速来自视频） |
| 锁定后的双击暂停 | 关闭 |
| 熄屏自动暂停 | 关闭 |
| 从上次的位置继续 | 打开时不适用（位置来自视频）；关闭时照常记下字幕位置（时钟 + 偏移量），下次手动播放时用。偏移量另记，见阶段 2 |
| 「跟随视频」开关 | 关掉即切回手动，保留当前字幕位置：时钟改记字幕位置（为负时按 0）；偏移量留着，再打开跟随接着用 |

- **偏移量用字幕时钟算，不用 `PlaybackState`**：跟随时时钟的当前值就是视频的实时位置（4.1）。所以 ±0.5 就是偏移量 ±500 ms；上一句 / 下一句、拖进度条就是 `偏移量 = 目标位置 − clock.position()`，目标位置是那一句的开始时间或拖到的位置（上一句 / 下一句按 `clock.position() + 偏移量` 去找）。这样不会用到上次回调时的旧位置。
- **为什么关掉双击**：锁定时触摸本来就传给视频 App，B 站双击会自己暂停。我们的双击要等约 0.4 秒才确认，那时视频已经停了，再按当前状态切换就会把它重新播起来。
- **为什么关掉熄屏暂停**：后台播放（比如 YouTube 会员）熄屏后视频照常播放、状态不变，不会有新的回调；字幕时钟要是被熄屏暂停，亮屏后就一直停着。暂停与否交给视频决定。

偏移量按字幕记住（和「从上次的位置继续」共用 `SubtitlePositions` 的 key），下次打开同一个字幕自动套用（阶段 2）。

### 4.4 广告和换视频

按阶段 0 实测（第 3 节末尾）改过，并提前到阶段 1：YouTube 换视频会新建会话，不判断的话，开关开着时会把上一个视频的偏移量套到新视频上。

- **广告**：报过 `ACTION_SEEK_TO` 的 App（记在设置里），哪次上报没有 seek，就算在放广告：字幕停在原处，不跟。从没报过 seek 的 App（比如这次的 B 站国际版）不做这个判断。所以只有第一次遇到的片头广告会跟错一下，正片一开始就纠正。
- **换视频**：按 App 记下第一次拿到的非空标题（广告期间不记）。之后同一个 App 报的标题不同，就算换了视频：字幕停在原处，控制栏提示「换了视频 · 点这里接着跟」；标题变回来就自动接着跟。点了就改记新标题，偏移量不变。
- 只比标题、不比时长：广告期间时长是广告的，B 站没有时长；标题为空（还没加载完）不算变化。
- 记下的标题按 App 分开：4.2 换到另一个 App（比如被语音消息抢走又切回来）不算换视频。换字幕时清空，之后先放的那个视频就是新字幕的视频。

### 4.5 开关、权限和说明

- 控制栏加一个「跟随视频」按钮。第一次点：先弹说明（为什么需要通知使用权、App 不读通知内容），再跳到系统的通知使用权页面。悬浮窗是服务里的窗口，弹不了普通对话框，说明参照 `ExportActivity` 用透明 Activity 显示（阶段 3）。
- 打开设置页：先试本 App 的详情页（API 30+），打不开退到列表页，都打不开就提示用户自己去找；打开前把控制栏收成小胶囊，免得两个悬浮窗挡住开关。
- Android 13+ 侧载安装时，说明里附上「允许受限设置」的步骤：开关是灰的，先点一下（弹出「受限设置」提示），再到 应用信息 → ⋮ →「允许受限设置」，回来打开开关。「先点一下」来自常见说法（第 2 节），阶段 0 真机确认。
- 授权后进入跟随模式：回到视频后点「跟随视频」或展开控制栏时检查授权。不靠监听服务的 `onListenerConnected()`，因为解绑后（4.7）在设置里关掉再打开授权不会再触发它。
- 默认跟随（第 8 节第 3 项按建议）：开关状态记在设置里，默认开；打开播放器时已授权就开始跟随。没授权时点「跟随」打开设置页，状态显示「需要通知使用权」。关掉开关时偏移量留着，再打开接着用，换字幕才清零。
- 用户随时可以切回手动模式。

### 4.6 退回手动和异常处理

- 没有授权、找不到会话、会话消失、位置未知（`PLAYBACK_POSITION_UNKNOWN`）时，退回现在的手动时钟，保留当前字幕位置（时钟改记字幕位置，为负时按 0；跟随的偏移量暂存，4.2 接着跟随时还用它）；控制栏显示「手动」，原有按钮恢复原来的含义。
- 所有 `MediaSessionManager` 调用都包 `try-catch (SecurityException)`：权限可能在两次调用之间被撤销，抛异常就直接退回手动。
- 撤销权限时系统会回调空列表（第 2 节），按「当前会话不在列表里」处理，同样退回手动。
- 注销：换会话、会话从列表里消失时，先对旧的 `MediaController` 调 `unregisterCallback`；退出跟随、播放服务销毁时，注销 `OnActiveSessionsChangedListener` 和所有会话回调。

### 4.7 监听服务只要授权，不常驻

- 打开通知使用权后，系统会一直绑定监听服务：本 App 的进程因此常驻，设备上的每条通知也都会发进进程（虽然不处理）。
- 取会话只看是否已授权，不看是否绑定（第 2 节），所以监听服务在 `onListenerConnected()` 里立刻 `requestUnbind()`：之后收不到任何通知，进程也不因它常驻。重启后系统会再绑一次，它再解绑。
- 代价：解绑后在设置里关掉再打开授权不会重新绑定（第 2 节），所以「授权后进入跟随」不靠 `onListenerConnected()`（4.5）。
- 阶段 0 验证解绑后取会话照常（第 3 节第 7 项）；不行就删掉这一行，退回一直绑定。

## 5. 改动清单

| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` | 新增通知监听服务声明（`exported="false"`、`BIND_NOTIFICATION_LISTENER_SERVICE` 权限 + `android.service.notification.NotificationListenerService` intent-filter）；阶段 1 视第 3 节第 8 项加 `<queries>` |
| `player/MediaListenerService.kt`（新） | 空的 `NotificationListenerService`，只为拿到会话列表的资格，连上就 `requestUnbind()`（4.7），不处理任何通知；附带判断是否已授权、打开设置页（4.5） |
| `player/MediaSessionProbe.kt`（新，阶段 0） | 调试读数和测量（第 6 节）；阶段 3 删掉 |
| `player/MediaSessionWatcher.kt`（新，阶段 1） | 读数和 `VideoFollower` 共用的部分：监听会话列表、给每个会话注册 / 注销回调、捕获 `SecurityException` |
| `data/AppSettings.kt` | 「跟随视频」开关、报过 seek 的 App（4.4） |
| `player/VideoFollower.kt`（新） | 选会话（粘性、点 App 名轮换）；给所有活跃会话注册 / 注销回调；捕获 `SecurityException`；把 ▶ / ⏸ 通过 TransportControls 转给视频；把状态、位置、速度、元数据交给播放服务 |
| `subtitle/VideoTime.kt`（新，纯 Kotlin，只用到 `PlaybackState` 的常量） | 位置外推、状态和速度到播放 / 暂停的映射（阶段 0 先有这两项）、防抖判断、偏移量换算、元数据有效性和变化判断，便于单元测试 |
| `player/SubtitlePlayerService.kt` | 跟随模式和手动模式切换；跟随时时钟记视频位置、读字幕位置统一加偏移量，`render()` 按 4.1 不自动暂停、不空转；按 4.3 解释控件和通知栏按钮，停用熄屏暂停、倍速和双击；保存 / 读取偏移量。阶段 0 只接上读数 |
| `player/SubtitlePlayerOverlay.kt`、`overlay_subtitle_controls.xml` | 「跟随视频」按钮、可点的 App 名、状态提示；阶段 0 的「会话」按钮和调试读数 |
| `player/SubtitlePositions.kt` | 每个字幕多存一个偏移量 |
| `values/strings.xml`、`values-zh/strings.xml` | 说明文案、状态提示 |
| `README.md` | 使用说明、权限说明、受限设置步骤、已知限制 |
| `app/src/test/.../VideoTimeTest.kt`（新） | 见第 7 节 |

## 6. 分阶段

1. **阶段 0：可行性验证（先做，改动最小）**
   - 只加通知监听服务（4.7）和一个调试读数，不改字幕时钟。控制栏标题行加「会话」按钮开关读数；没授权时点读数打开通知使用权页面（按 4.5 的退路）。
   - 读数按 `getActiveSessions()` 当前的顺序列出每个会话：包名、状态、外推到此刻的位置、速度、`getActions()` 里的播放 / 暂停 / 跳转、标题和时长、回调次数和位置差；另显示是否已授权、监听服务是否连着、取会话是否被拒（`SecurityException`）。
   - 同时累计第 3 节要的测量值，每个会话一份：回调间隔、上报到达时距 `lastPositionUpdateTime` 多久、前后两次都在同速播放时上报位置和外推位置的差（平均、最大）。事件记录留最近 100 条：状态 / 速度变化、差超过 300 ms 的上报、元数据变化、列表变化回调、会话顺序变化、会话出现 / 消失 / 销毁（出现时记下能不能查到应用名）、授权和监听服务的变化；平稳的上报只计数，不进记录。长按读数复制完整记录（含设备型号和 Android 版本），方便把结果发回来。
   - 走 CI 出包（不勾「创建 GitHub Release」），你在 YouTube、B 站上按第 3 节逐项看读数，决定继续还是放弃，并据此定防抖阈值和时长容差。
2. **阶段 1：完整跟随**
   - 按 4.1 跟随播放 / 暂停 / 倍速 / 拖动，含防抖和状态映射；4.2 的会话粘性和点 App 名切换；4.3 的控件含义；4.4 的广告和换视频判断；4.5 的开关；4.6 的异常处理和注销。
   - 清单声明 `<queries>`（有启动器入口的 App），显示应用名；查不到时显示包名。
   - 读数保留：前一次上报在播放时，之后的每次上报（包括暂停）都算一次位置差，3 秒以上算跳转、另计次数。约 1 秒的启动误差先不补偿，用这个数据再定。
   - 偏移量只在本次播放内有效。
3. **阶段 2**：偏移量按字幕保存。
4. **阶段 3**：说明文案、受限设置引导、README、清理调试读数。

每个阶段单独提交，阶段 0 不通过就整体放弃，删掉调试代码。

## 7. 测试计划

- **单元测试（`VideoTimeTest`，CI 上跑）**
  - 外推：播放中按速度前进；经过的时间为负、`lastPositionUpdateTime` 为 0 时不前进（阶段 0 已有）；
  - 状态映射：只有 PLAYING 且速度大于 0 算播放；缓冲、快进、倒放等算暂停（阶段 0 已有）；速度为 0 或负数时不会写进时钟；
  - 防抖：播放 / 暂停和速度不变、差值小于阈值时不重新定位；播放 / 暂停变化、速度变化或差值超出阈值（拖动）时重新定位（阶段 1 已有）。
- **真机检查**（这些逻辑在播放服务和 `VideoFollower` 里，要靠 Android 运行时，单元测试覆盖不到）
  - 偏移量：±0.5、上一句 / 下一句、拖进度条只改偏移量；偏移量为负、视频还没到偏移点时不显示字幕，到偏移点正好从第一句开始；
  - 广告和换视频：片头 / 中插广告时字幕停住；换视频时提示「换了视频」，点一下接着跟；
  - 位置未知、没有会话时退回手动。
- **真机矩阵**：YouTube、B 站、Chrome 网页视频、本地播放器 × 暂停 / 继续 / 拖动 / 倍速 / 广告 / 换视频 / 画中画 / 熄屏（含后台播放）/ 来电 / 同时开着音乐 App / 视频暂停时插播语音消息 / 视频比字幕长（播过最后一句后不暂停、不空转）。

## 8. 风险与需要你决定的事

**风险**

- 通知使用权很敏感（系统会提示「可读取所有通知」），只能做成可选，说明要写清楚。
- 侧载 App 在 Android 13+ 多一步「允许受限设置」，部分国产 ROM 入口不同。
- 各 App 的会话行为不受我们控制，App 更新后可能失效，手动模式要一直保留。
- 视频暂停期间，别的 App 一开始播放（比如语音消息）就会把跟随抢走；视频恢复播放后会自动切回来，也可以点 App 名切回。
- 将来如果上架 Google Play，通知监听器和 specialUse 前台服务都要单独申报说明。
- 阶段 0 复制出来的读数含视频标题（截断到 24 个字符）和设备型号，发回前可以删掉。

**需要你决定**（第 1、2 项已按先做阶段 0 处理；第 3–5 项阶段 1 先按建议做：默认跟随、跟随时关掉双击、▶ / ⏸ 控制视频，真机试过再调）

1. 能接受申请通知使用权吗？不能接受的话，这个方案整体不做。
2. 是否同意先做阶段 0（只加读数），用真机结果决定后续？
3. 授权后打开播放器是否默认跟随？还是每次手动点「跟随视频」？建议默认跟随，前提是阶段 1 的会话粘性、点 App 名切换和 App 名显示都做好，否则一打开就跟到音乐 App 会很烦。
4. 跟随模式下，锁定后的双击暂停是关掉（建议，理由见 4.3），还是保留？
5. 跟随模式下 ▶ / ⏸ 是直接控制视频（建议，见 4.3），还是只冻结 / 恢复字幕？选后者的话，还要另外定恢复时字幕跳到哪里。

## 9. 修订记录

- 2026-11-26：起草。
- 2026-11-26：按评审意见修订。
  - 补充 AOSP 源码核实的事实：速度和状态的取值、会话排序、列表变化何时通知、撤销权限时的回调、元数据的缺省值、播放控制。
  - 新增防抖阈值、状态和速度的映射、会话粘性（给所有活跃会话注册回调）和点 App 名切换、元数据有效性规则、异常处理和注销。
  - 偏移量改用字幕时钟换算；补上进入跟随时偏移量的取值。
  - 跟随时 ▶ / ⏸ 改为控制视频，并停用双击、熄屏暂停和倍速按钮。
  - 原阶段 1 和阶段 2 的跟随逻辑合并为新阶段 1；阶段 0 增加测量项。
- 2026-11-26：复核后第二次修订，并实现阶段 0。
  - 时间模型：字幕时钟改记视频位置，字幕位置 = 时钟 + 偏移量，免得负偏移量（字幕记录很常见）被时钟截成 0；补上 `lastPositionUpdateTime` 为 0 时不外推；跟随时 `render()` 播完不自动暂停、不 16 ms 空转。
  - 第 2 节：更正受限设置的来源（Google 帮助页没点名通知使用权）；补充 AOSP 核实的「取会话只看授权、不看绑定」、`requestUnbind()` 的效果、受限设置菜单项的显示条件，以及清单声明、软件包可见性和几处文档细节（`isActive()` 是 API 31、回调列表可能为 null、设置页可能不存在）。
  - 新增 4.7：监听服务连上就解绑，不常驻、不收通知。
  - 4.2–4.5 补充：「在播放」的定义、开关开着但暂时没有会话时自动接着跟随、应用名查不到时显示包名、通知栏按钮、关闭时记的位置、元数据按会话记、打开设置页的退路和时机、说明放在哪里。
  - 阶段 0 补上授权入口、新的测量项（上报时差、应用名是否可见、解绑后是否可用）和读数的具体内容；CI 出包不建 Release。
- 2026-11-26：按阶段 0 实测第三次修订，并实现阶段 1。
  - 第 3 节末尾加上实测结果。
  - 4.4 重写并提前到阶段 1：有过 seek 的 App 没有 seek 时算广告；按 App 只比标题判断换视频，可以点一下接着跟。
  - 4.2：点 App 名选中的会话不再自动切走；4.5：开关记在设置里、默认开，关掉时偏移量留着。
  - 阶段 1 加上 `<queries>` 和读数的新统计（暂停时的位置差、跳转另计）；第 8 节第 3–5 项先按建议做。
  - 读数和跟随共用 `MediaSessionWatcher`。
