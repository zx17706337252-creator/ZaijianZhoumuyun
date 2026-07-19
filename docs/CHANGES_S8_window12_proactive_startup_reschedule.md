# S8 窗口12 修复变更记录 — ProactiveMessageWorker 启动时调度恢复

**修复日期：** 2026-07-17
**依据：** `window12_workmanager_audit_report.md`（9条结论，7条通过，2条待补充）
**基准代码：** `zaijian_s8_window11_full.zip`

---

## 本窗口审计概览

窗口12审查全项目7个Worker的调度链路。结论1、3、4、5、6、7均为"已确认解决"，
逐条核实行号与代码结构完全吻合，代码原样保留：

- DailyPracticeWorker：AlarmManager→Worker→finally自我重调度，BootReceiver兜底，链路完整
- PregnancySettlementWorker：12h周期 + App启动/进入聊天立即检查，settlementMutex串行化
- CiCdPipelineWorker：事件驱动一次性任务，BootReceiver对CI/CD类型job正确标记失败而非误重入队
- ScheduledJobWorker：多入口覆盖（Create/Update/Repository四个方法/Worker自重调度），claimJob认领锁+事务保护
- WorkflowJobWorker：BootReceiver重启后对RUNNING job正确恢复，单条job独立try-catch
- FcmTokenUploadWorker：基于返回值判断而非吞异常，任务名含userId防止多用户覆盖

---

## 修复（P1，结论2+结论8，同一根因）：ProactiveMessageWorker 缺少启动时调度恢复

**问题：** `WorkManagerScheduler.scheduleProactiveMessageCheck()`（挂载90分钟周期
PeriodicWorkRequest）唯一调用点是 `ProfileScreen` 开关切换回调，`ZaijianApp.
onCreate()` 和 `BootReceiver` 均无调用。WorkManager 的 PeriodicWorkRequest 在
设备重启后会被系统清空，若用户此前已开启主动消息但重启后未手动重新切换开关，
主动消息检查会永久停止，且没有任何提示——用户会在不知情的情况下再也收不到
角色主动发来的消息。

对比 `PregnancySettlementScheduler.ensurePeriodicWork()`（`ZaijianApp.onCreate()`
第307行调用）和 `DailyPracticeScheduler.scheduleNext()`（第437行条件调用），
这两个周期性任务都有启动时的初始化调用，唯独 `ProactiveMessageWorker` 遗漏，
确认为遗漏而非统一设计决策。

**文件：** `app/src/main/java/com/zaijian/zhoumuyun/ZaijianApp.kt`

**修复内容：** 在 `onCreate()` 中新增一段（紧邻 `PregnancySettlementScheduler.
ensurePeriodicWork()` 之后）：
- 读取与 `ProfileScreen` 相同的 SharedPreferences（`"user_profile"` /
  `"proactive_enabled"`），默认值 `true`，与 `ProfileScreen` 初始化开关状态时
  的默认值保持一致。
- 若已开启，调用 `WorkManagerScheduler.scheduleProactiveMessageCheck()` 恢复
  周期任务。该方法内部使用 `enqueueUniquePeriodicWork` + `KEEP` 策略，任务已
  存在时本次调用是 no-op，重复 `onCreate()` 不会重复入队或打断现有周期，与
  `PregnancySettlementScheduler.ensurePeriodicWork()` 同一安全模式。
- 整体包在 `runCatching { }.onFailure { ZLog.e(...) }` 中，与文件内其余后台
  初始化逻辑同一风格，单次失败不影响 App 正常启动。

**为何不改动 BootReceiver：** `ProactiveMessageWorker` 走的是
`enqueueUniquePeriodicWork`（WorkManager 层面幂等注册），不同于 `DailyPractice`
走的 AlarmManager（重启后必须显式重新 `set` 闹钟）。只要用户最终会打开 App
（`ZaijianApp.onCreate()` 必然执行），周期任务就会被恢复，不存在"用户重启后
长期不开 App 也需要恢复"的诉求（该诉求只对"到期就必须执行一次"的一次性任务
如 ScheduledJobWorker 成立，BootReceiver 已经覆盖了那部分）。故本次修复只需
落在 `ZaijianApp.onCreate()`，不需要在 `BootReceiver` 重复处理。

**验证：** 括号平衡校验通过（Gradle 不可用于此环境）。

---

## 核实但未处理（P2，结论9，报告标记"证据不足待补充"）

**问题：** `ProactiveMessageWorker` 与前台 `WorldSimulation` 是否可能对同一
角色产生双重主动消息通知。

**核实结果：** 已查看 `ProactiveMessageNotifier.persistAndNotify()` 完整实现——
该方法本身**没有消息级去重逻辑**，只是无条件持久化+通知。真正的节流发生在
上一层：`PresenceEngine` 的 `lastProactiveAt` 状态是 companion object 级
`ConcurrentHashMap` + SharedPreferences 持久化（`D-6 fix`），进程级共享，
理论上前台/后台两条路径会读到同一份节流状态。

但节流检查是 `getLastProactiveAt()` 读 → 业务逻辑 → `setLastProactiveAt()`
写的**非原子操作**（`PresenceEngine.kt:468/483` 和 `562` 一带），如果前台
`WorldSimulation` 的 Tier 调用和后台 `ProactiveMessageWorker` 恰好在极窄的
时间窗口内几乎同时对同一角色做节流检查，理论上存在两者都读到"未触发"、
都各自触发一次主动消息的竞态可能——与报告的判断一致。

**为何本窗口不处理：** 报告本身将此列为"证据不足待补充"，建议留给窗口17
（跨窗口矛盾核查）或窗口13（Push链路）确认实际触发概率与后果后再决定修复
方案（例如把 get+业务判断+set 收进同一把锁）。这是一个需要结合更多上下文
（触发频率、Mutex粒度设计）才能定型的修复，不适合在本窗口单独拍板一个局部
方案，故本次只核实、不改代码，留待后续窗口处理。

---

## 未处理项

- P2/结论9：ProactiveMessageWorker 与前台 WorldSimulation 潜在双重通知竞态，
  按报告建议留给窗口17/窗口13处理。
