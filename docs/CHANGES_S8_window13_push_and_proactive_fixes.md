# S8 窗口13 修复变更记录 — Push链路缺口 + 主动消息竞态收口

**修复日期：** 2026-07-17
**依据：** `window13_push_audit_report.md`（14条结论，14条通过；新问题1、新问题2）+
`CHANGES_S8_window12_proactive_startup_reschedule.md` 遗留的"未处理项"（结论9）
**基准代码：** `zaijian_s8_window12_full.zip`

---

## 本次修复概览

共处理 5 项，前 4 项来自 window13 审计报告，第 5 项是核实 window12 遗留问题过程中
顺带发现的相邻缺口（非报告原文）：

1. window13新问题1：Fallback通知渠道 importance 与主注册不一致
2. window13结论5：Fallback通知缺少点击跳转 Intent
3. window13新问题2：FCM 路径 fakeResult 未持久化到 DB
4. window13结论7：前台跨角色主动消息被静默丢弃
5. 新发现：`refreshPresence()` 三处 ViewModel 直调路径绕开 `tier1Mutex`，
   与后台 Tier1 循环对同一角色可能重复触发主动消息

**关于 window12 遗留问题（结论9）的核实结论：** 原始描述——前台
`WorldSimulation` 与后台 `ProactiveMessageWorker` 可能对同一角色产生双重
主动消息通知——经核实**已在本次基准代码中修复**，无需改动。`WorldSimulation.
runTier1()` 现已被 `companion object` 级别的 `tier1Mutex` 完整包裹（注释标注
"P2-4修复"），前台常驻实例与后台每次新建的 Worker 实例共享同一把锁，完整
覆盖"读节流→判断→写节流"闭环。核实过程中顺着调用链发现了第5项相邻缺口
（`tier1Mutex` 保护范围之外的三处直调），一并处理。

---

## 修复1：Fallback通知渠道 importance 不一致（window13新问题1，P3）

**文件：** `data/push/ZaijianMessagingService.kt`

**问题：** `showFallbackNotification()` 冷启动兜底创建 `task_result` 渠道时用
`IMPORTANCE_HIGH`，与 `ZaijianApp.setupNotificationChannels()` 正常注册路径的
`IMPORTANCE_DEFAULT` 不一致。

**修复：** 兜底创建改为 `IMPORTANCE_DEFAULT`，与主注册路径保持一致。

---

## 修复2：Fallback通知缺少点击跳转 Intent（window13结论5）

**文件：** `data/push/ZaijianMessagingService.kt`

**问题：** `showFallbackNotification()` 构建的通知未设置 `setContentIntent()`，
用户点击后无任何跳转反应，仅消除通知。

**修复：**
- `showFallbackNotification()` 新增 `characterId: Int` 参数（三处调用点同步
  传入，`characterId` 已在 `onMessageReceived()` 中解析出来）。
- 补上与 `ProactiveMessageNotifier.sendNotification()` 同款的
  `zaijian://chat/{characterId}` 深链接 + `ACTION_VIEW`，`PendingIntent`
  用 `characterId` 作 requestCode（同角色新通知覆盖旧的），`FLAG_UPDATE_CURRENT
  or FLAG_IMMUTABLE`，与现有深链接路由复用同一套 `MainActivity` 解析逻辑，
  不新增机制。

---

## 修复3：FCM 路径 fakeResult 未持久化（window13新问题2，P2）

**文件：** `data/push/ZaijianMessagingService.kt`、`data/AppContainer.kt`

**问题：** FCM 前台路径构造的 `fakeResult`（`JobResultEntity`）只是内存对象，
未写入数据库。用户点击 Toast「立即查看」进入任务中心时查不到记录；Toast
展示期间进程被杀则该任务完成记录永久丢失。与 DB 轮询路径（通知的即是已
入库数据）行为不一致。

**修复：**
- `AppContainer` 新增公开只读属性 `jobResultDao: JobResultDao = db.jobResultDao()`，
  与 `scheduleRepo` 内部持有的是同一个 Room DAO 实例，无状态不一致风险。
- `ZaijianMessagingService` 在调用 `engine.notifyTaskCompletion()` 之前，先
  `AppContainer.instance.jobResultDao.insert(fakeResult)` 落库，包在
  `try/catch` 中，失败仅记录日志、不影响 Toast 正常展示（未劣化原有行为）。
- `JobResultDao.insert()` 是 `OnConflictStrategy.REPLACE`，后续 DB 同步写入
  权威数据时会自然覆盖这条临时记录，不产生冲突。

---

## 修复4：前台跨角色主动消息静默丢弃（window13结论7，P2）

**文件：** `data/AppContainer.kt`、`domain/PresenceEngine.kt`、
`ui/viewmodel/ChatViewModel.kt`

**问题：** `AppContainer` 构造 `PresenceEngine` 时未传 `onProactiveMessage`
回调（该参数默认 `null`）。前台场景下，非当前聊天页角色触发的主动消息只经由
`proactiveMessageFlow` 内存广播，而 `ChatViewModel` 只处理
`msg.characterId == currentCharacterId` 的消息——其余角色的消息既不落库也
不弹通知，用户完全无感知地永久丢失。对比后台 `ProactiveMessageWorker` 正确
传入了该回调，前后台行为不对称。

**修复：**
- `AppContainer` 新增 `proactiveMessageNotifier`（`ProactiveMessageNotifier`
  实例，`context`/`messageDao` 复用容器已有的 `appContext`/`messageRepo`，
  `daughterCharacterRepo` 单独构造一份局部实例——因声明顺序上 `presenceEngine`
  先于容器共享的 `daughterCharacterRepo` 字段，直接引用会拿到未初始化的值；
  构造参数与共享字段完全一致，做法与 `ProactiveMessageWorker` 同源）。
- `presenceEngine` 构造时传入 `onProactiveMessage = { msg -> ... }`：落库
  始终执行；是否弹系统通知取决于该角色是否正是当前前台聊天页角色
  （`PresenceEngine.foregroundChatCharacterId`），是则抑制（Snackbar 已经
  展示过），否则正常弹通知。
- `PresenceEngine.companion object` 新增 `@Volatile var foregroundChatCharacterId:
  Int? = null`，跨线程可见（写在主线程 ViewModel，读在 IO 线程的 emit 回调）。
- `ChatViewModel.initFor()` 进入聊天页时设置该值为当前 `characterId`；
  `onCleared()` 离开时清除——但仅当全局值仍等于自己设置的那个 `characterId`
  时才清，避免"新 ChatViewModel 已为角色B设置标记，旧角色A的 ViewModel 才
  姗姗来迟执行 onCleared()"这种时序下误清掉角色B的标记。

**效果：** 用户在前台浏览角色A的聊天页时，角色B的主动消息现在会正常落库
并弹出系统通知（点击可跳转到角色B的聊天页），不再静默丢失；角色A自己的
主动消息仍走 Snackbar，且不会额外重复弹一条系统通知。

---

## 修复5（新发现）：`refreshPresence()` 直调路径绕开 tier1Mutex 的节流竞态

**文件：** `domain/PresenceEngine.kt`

**问题：** 核实 window12 遗留的"前后台双重通知竞态"过程中，确认
`WorldSimulation.runTier1()`（前台 Tier1 循环 + 后台
`ProactiveMessageWorker.runProactiveCheckForCharacters()` 共用同一把
`companion object` 级 `tier1Mutex`）本身已无问题。但顺着 `refreshPresence()`
的调用链核查，发现还有三处调用完全绕开这把锁：
`RoundtableBotReplyGenerator.kt:146`、`ChatMessageOrchestrator.kt:253`、
`RoundtableIdleManager.kt:205`（均为"presence 缓存未命中时补算一次"的场景，
用户主动发消息触发聊天回复生成时可能进入）。`refreshPresence()` 内部
（`newEnergy > 60 && topGoal != null` 分支）与 `tryEmitContextualProactiveMessage()`
操作的是同一个节流键 `lastProactiveAt`，若这三处调用与后台 Tier1 循环对同一
`characterId` 几乎同时执行到各自的"读→判断→写"闭环，理论上都可能读到
"未触发"，各自构建并发出一条主动消息，导致同一角色收到重复消息。

**修复：**
- `PresenceEngine.companion object` 新增按 `characterId` 分片的锁：
  `proactiveThrottleLocks: ConcurrentHashMap<Int, Mutex>` +
  `proactiveThrottleLockFor(characterId): Mutex`（`computeIfAbsent` 惰性
  创建，不同角色互不阻塞，避免复用 `tier1Mutex` 造成全局无谓串行化）。
- `refreshPresence()` 内的主动消息触发闭环（读 `buildProactiveMessage` →
  非空则 `setLastProactiveAt` + `emitProactiveMessage`）整体包进
  `proactiveThrottleLockFor(characterId).withLock { }`。
- `tryEmitContextualProactiveMessage()` 同样包进同一把锁（两者共用同一
  `characterId` 对应的锁实例，保证跨路径互斥，不只是各自路径内部原子）。

**效果：** 无论主动消息触发是从 `WorldSimulation.runTier1()`（原本已有
`tier1Mutex` 外层保护，现在双重锁不冲突，只是略有冗余）还是三处 ViewModel
直调路径进入，同一角色的节流判断与发送现在都是原子操作，不会再产生重复
主动消息。

---

## 验证

- 括号平衡校验（Python 脚本逐字符扫描 `()[]{}` 配对）：4 个改动文件全部
  通过（Gradle 不可用于此环境）。
- `.kt` 文件总数核对：330（与 window13 报告声明的源码规模一致，本次未新增
  /删除文件）。
- 全项目 grep 核对：`showFallbackNotification` 旧签名（3参）无残留调用；
  `foregroundChatCharacterId` 四处引用（声明 + 设置 + 清除 + 读取）互相一致；
  `PresenceEngine(...)` 构造调用点（`AppContainer`/`ProactiveMessageWorker`）
  均已确认符合新签名要求（`onProactiveMessage` 为可选参数，未受影响的
  调用点不受影响）。

---

## 未处理项

无。本窗口 5 项均已修复。
