# S8 窗口02：SpecialtyProfileRepository 重复构造收敛 + reassembleCompetitionEngine 异常保护

> 对应 `window02_di_dependency_audit.md` 结论5、复核过程中新发现1。
> 结论6（`reassembleCompetitionEngine()` 不支持 Provider 切换后重新装配）
> 明确不在本批次范围内——报告原文自己建议"应作为单独的 bug 修复处理"，
> 涉及幂等判断逻辑的实际行为变更，风险和讨论空间都比结构性改动大，
> 留待专项处理。

## 变更内容

### 结论5：DailyPracticeWorker / DistillationTrigger 改用容器共享 Repository

两处原先各自独立构造一份与 `AppContainer.specialtyProfileRepo` 参数完全
一致（7 个字段逐一相同）的 `SpecialtyProfileRepository`：

- `data/agent/DailyPracticeWorker.kt`（`doWork()` 内）
- `data/agent/DistillationTrigger.kt`（`checkAndRunInternal()` 内）

均改为 `com.zaijian.zhoumuyun.data.AppContainer.instance.specialtyProfileRepo`。

**未改动的部分**：两个文件里的 `db`（`AppDatabase.getInstance(...)`）字段本身
保留——两处都还有大量直接使用 `db.xxxDao()` 的调用（补发播报、跨表事务、
候选池查询等），且 `DistillationTrigger.checkAndRun(db, ...)` 的 `db` 是从
`DailyPracticeWorker` 作为参数传入的，收敛范围严格限定在报告结论5点名的
"重复构造 SpecialtyProfileRepository" 这一处，不涉及 `db` 本身或其余调用。

`AppDatabase.getInstance()` 是 Room 单例工厂方法，`DailyPracticeWorker` 传入
的 `applicationContext` 与 `AppContainer` 内部持有的 `context` 最终指向同一个
`db` 实例（报告复核结论5已验证过这一点），因此本次替换对运行时行为无影响，
只是消除了重复构造。

### 复核新发现1：reassembleCompetitionEngine() 补齐 try-catch

原方法体内 `SpecialtyEvolutionEngine`/`CompetitionEngine`/`CompetitionRoundManager`
的构造逻辑完全没有异常保护——任一步骤抛出异常，会直接传播到
`ZaijianApp.kt` 里 `scope.launch { appContainer.reassembleCompetitionEngine() }`
所在的协程。该协程挂在 `SupervisorJob` 下不会波及其余协程，但会静默终止、
无任何日志，难以定位问题。

现补齐 try-catch，与 `ZaijianApp.kt` 内 `registerAgentTools`/调度补偿等处
的既有异常处理风格保持一致（`try-catch + ZLog.e`）：

- 装配失败时记录 `ZLog.e` 日志，`competitionEngine`/`competitionRoundManager`
  保持 `null`（与未捕获异常时的结果状态等价），不重新抛出——不应让装配
  失败拖垮 App 启动流程或 Provider 配置变更回调所在的协程。
- 单独 catch `CancellationException` 并重新抛出，遵循项目内
  `FertileWindowConsentJudge`/`UserConsentIntentJudge` 已确立的"结构化并发
  约定要求取消信号不能被吞掉"的同款处理方式（Kotlin 的
  `CancellationException` 继承自 `Exception`，若只用一个 `catch (e: Exception)`
  会连协程取消信号一起吞掉）。

**未改动的部分**：`if (competitionEngine != null) return@withLock` 这一行
幂等判断逻辑完全不动——这是报告结论6点名的问题（不支持 Provider 切换后
重新装配），本批次明确不处理，留待专项处理。本次改动只是在幂等判断
"放行"之后的构造代码外面包了一层 try-catch，不改变任何判断分支或返回
路径的行为。

## 明确排除在本次范围外（附理由）

- **结论6**（`reassembleCompetitionEngine()` 幂等判断导致 Provider 切换后
  不会重新装配）——报告原文明确建议"应作为单独的 bug 修复处理，不在本次
  结构性调整范围内"。这条涉及实际行为变更（要不要支持重新装配、重新装配
  时机是否需要额外保护旧引用还在使用中的场景等），跟本批次"结构性改动，
  不涉及业务逻辑变更"的范围定位不符，留待专项窗口处理。
- 窗口02报告结论1/2/3/4/7——均已确认解决或本来就是有意设计（无状态引擎
  分散构造、WONTFIX 裁定等），未触碰。
