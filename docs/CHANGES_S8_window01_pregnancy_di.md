# S8 窗口01：PregnancyTriggerManager 构造收敛（RoundtableViewModel / PregnancyViewModel）

> 对应 `zaijian-s8-w01-appcontainer-audit.md` 第三节表格、第七节"⚠️ 仍需关注 1"、
> 第八节复核 #3/#4。范围限定为该报告点名的"仅因 PregnancyTriggerManager 而
> 裸持 db"这一具体问题，不涉及 ChatViewModel 其余 9 处历史遗留局部构造
> （agentPlanRepo/learningGoalRepo/scheduleRepo/pregnancyAnswerRepo/
> agentRelationEngine/evaluationEngine/distillationEngine 等）。

## 变更内容

### AppContainer 新增

- 两个共享的无状态 Judge 单例：
  - `fertileWindowConsentJudge: FertileWindowConsentJudge`
  - `userConsentIntentJudge: UserConsentIntentJudge`
  两者均无内部状态、单次调用，构造参数完全一致
  （`providerFn = { ProviderManager.instance.activeProvider }`），原先仅
  ChatViewModel 每次构造 pregnancyTriggerManager 时各自 new 一份，收敛为
  容器共享实例。懒加载 providerFn 语义不变。

- 三个工厂方法（`cycleRepository`/`stateRepository` 均带默认值，指向容器
  自身共享实例，调用方不传时行为与迁移前完全一致）：
  - `createPregnancyTriggerManagerFull()` —— 对应原 ChatViewModel 用法
    （db + pregnancyRepo + cycleRepository + stateRepository +
    relationshipEngine + aiJudge + consentJudge）。**本批次未接入
    ChatViewModel**，仅新增此方法供未来单独批次使用。
  - `createPregnancyTriggerManagerForRoundtable()` —— 对应原
    RoundtableViewModel 用法（仅 relationshipEngine/aiJudge/consentJudge
    均不传）。
  - `createPregnancyTriggerManagerMinimal()` —— 对应原 PregnancyViewModel
    用法（三者都不传）。

三个方法均在 `db`/`pregnancyRepo`/`characterStateRepo`/`relationshipEngine`/
`menstrualCycleRepo`/两个 Judge 字段之后以成员函数形式声明——Kotlin 成员函数
默认参数在调用时按 `this` 解析，允许引用类体中声明顺序更靠后的 `val` 字段，
不受属性初始化顺序限制，无编译期风险。

### ViewModel 改动

- **RoundtableViewModel**：移除 `private val db = AppDatabase.getInstance(app)`
  裸持有字段（连同其头部说明该字段"仅用于构造 pregnancyTriggerManager"的
  注释一并删除），`pregnancyTriggerManager` 改为
  `container.createPregnancyTriggerManagerForRoundtable()`。同步删除文件内
  已无引用的 `AppDatabase`/`PregnancyTriggerManager` 两个 import。行为不变：
  不传 relationshipEngine/aiJudge/consentJudge，圆桌场景仍不触发受孕弹窗
  AI 判定链路。

- **PregnancyViewModel**：移除 `private val db = AppDatabase.getInstance
  (application)` 裸持有字段，`triggerManager` 改为
  `AppContainer.instance.createPregnancyTriggerManagerMinimal()`。连带清理
  两个仅为原构造调用传参而存在、本次改动后已无其他引用的局部字段
  `cycleRepo`/`characterStateRepo`（工厂方法内部默认参数已指向同一个
  容器共享实例，语义完全等价，非行为变更）。同步删除已无引用的
  `AppDatabase`/`PregnancyTriggerManager` 两个 import。行为不变：三个可选
  判定参数均不传，仅走终止妊娠（D2.6 主动流产）路径。

- **ChatViewModel**：本批次不改动。`db` 字段本身保留——文件内还有其余
  9 处真实依赖 db 的构造（scheduleRepo/toolRegistrar/evaluationEngine/
  distillationEngine/memoryDao 等），仅切换 pregnancyTriggerManager 一项
  构造方式无法消灭该字段，收益有限，容易引入与本批次目标无关的部分改动。
  `createPregnancyTriggerManagerFull()` 已就绪，留待专门批次处理
  ChatViewModel 整体收口时一并接入。

## 审计报告对照表更新（人工核对用，非自动生成）

| ViewModel | 变更前 | 变更后 |
|-----------|--------|--------|
| RoundtableViewModel | AppDatabase.getInstance() ❌ | 完全消灭，应更新为 ✅ |
| PregnancyViewModel | AppDatabase.getInstance() ❌ | 完全消灭，应更新为 ✅ |
| ChatViewModel | AppDatabase.getInstance() ❌（其余9处依赖） | 维持 ❌，需单独立项处理，不在本批次范围 |

## 明确排除在本次范围外（附理由，避免未来误判为遗漏）

- ChatViewModel 的其余 9 处 db 依赖构造（scheduleRepo/toolRegistrar/
  evaluationEngine/distillationEngine/memoryDao 等）——审计报告本身认定
  这是"最大待修项"里最难啃的一块，工作量/风险构成独立大工程，需要单独
  的审查窗口处理，不适合趁本次"PregnancyTriggerManager 收敛"顺手处理。
- UI 层 2 处 `AppDatabase.getInstance()` 裸调用（CharacterDetailScreen.kt:298、
  CharacterDetailRelationship.kt:143）——审计报告第七节点名的另一项独立
  待办，与本次 PregnancyTriggerManager 收敛无关，未触碰。
- AppNavigation/ProfileScreen 的 AppearanceDataStore 重复实例化——同上，
  独立问题，未触碰。

## S8-窗口01 收尾复核：报告表4/4.1/4.2 全量条目现状核对

本批次交付后，对照审计报告表4（UI 层裸拼装清单）、4.1（其他绕过模式）、
4.2（未使用 import）逐条重新核实源码现状，发现大部分条目在报告成文之后
已经被处理过（源码内可见 `S8-窗口01 修复` 字样的历史注释，行号与报告
不完全对应，是因为报告标注的是修复前的旧行号，报告文本本身未随代码更新
同步刷新）：

| # | 位置 | 报告原始判定 | 复核实际现状 |
|---|------|------|------|
| 1 | CharacterDetailScreen.kt:298 | ❌ AppDatabase.getInstance() | **已修复**——现为 `AppContainer.instance.relationshipReadRepo.observeRelationTo()`，内置 `.catch{}` 兜底 |
| 2 | CharacterDetailRelationship.kt:143 | ❌ AppDatabase.getInstance() | **已修复**——同上，Flow 侧 `.catch{}` + 挂起函数侧 try-catch 均已补齐 |
| 3 | CharacterDetailScreen.kt:190 | ⚠️ AppContainer.instance（结构性） | 未变——仍在 Composable 的 LaunchedEffect 内直接调用，但已有 try-catch 保护 `DaughterDataException`，无崩溃风险，只是语义上应属于 ViewModel |
| 4 | ProfileScreen.kt:167 | ⚠️ AppContainer.instance（结构性） | **已修复**——数据访问逻辑搬迁至 `ProfileViewModel.observeCharacterAvatarOverrides()`，Composable 侧只订阅 `uiState.characterAvatarOverrides` |
| 5 | ProfileStatsRow.kt:103-106 | ⚠️ AppContainer.instance ×4（结构性） | **已修复**——搬迁至 `ProfileViewModel.loadStats()`，组件降级为纯展示组件，只接收数值参数 |
| 6 | BriefingAttentionSection.kt:56 | ⚠️ AppContainer.instance（结构性） | **已修复**——搬迁至 `BriefingViewModel.loadDaughterNameMap()`，通过 `BriefingUiState.daughterNameMap` 传入 |
| 7 | ProfileAiConfigSection.kt:97 | ⚠️ ProviderManager.instance | 未变，**核实后判定非缺陷**（见下方说明） |
| 8 | AppNavigation.kt:346 | ⚠️ AppearanceDataStore 重复实例化 | **已修复**——改为优先复用 `ZaijianApp.sharedAppearanceDataStore`，取不到才 `remember` 兜底 |
| 9 | ProfileScreen.kt:144 | ⚠️ AppearanceDataStore 重复实例化 | **已修复**——同上 |
| 10 | ProfileScreen.kt:281-286 | ⚠️ WorkManagerScheduler 静态调用 | 未变，**核实后判定非缺陷**（见下方说明） |
| 4.2 | 3 处未使用 AppDatabase import | 历史遗留 | **已修复**——三个文件均已无残留 import |

**结论：表4/4.1/4.2 中除 #7、#10 外全部已处理完毕**（#3 虽未改动位置，但
崩溃风险已消除，剩余的只是"该不该挪进 ViewModel"这一层语义归属问题，
优先级低，本次未纳入）。

### #7 / #10 核实结论：判定为非缺陷，本次不做

审计报告对这两条只标了 ⚠️（轻微），且报告原文自己也承认：

- #7 原文："ProviderManager 独立于 AppContainer 的全局单例，Composable
  内直接访问"——只是客观描述现状，未给出具体修复方案。
- #10 原文："合理工具类调用，但应在 ViewModel 层管理"——报告自己先定性
  为"合理"，后半句只是软性建议。

本次复核逐一 grep 全项目调用点，确认两者都不是孤立的代码坏味道，而是
项目统一惯例：

- **`ProviderManager.instance`**：`ChatToolRegistrar`/`SpecialtyEvolutionViewModel`/
  `RoundtableMessageOrchestrator`/`ChatMessageOrchestrator`/`RoundtableIdleManager`/
  `ChatViewModel` 等全部 ViewModel 层调用点都是直接 `ProviderManager.instance`，
  从未经过 AppContainer 包装（AppContainer 本身构造 judge/engine 时也是直接
  引用 `ProviderManager.instance.activeProvider`，同一套用法）。
  `ProfileAiConfigSection.kt` 内 `pm` 实际使用面有 20 处，读写交织在大量
  本地 Compose 表单状态（Key 输入框、下拉选择、保存按钮回调）里，并非
  简单的只读 Flow 订阅——跟已修复的 #4/#5/#6（单向数据流、订阅即用）不是
  同一类问题，真要收敛需要一次完整的表单状态搬迁到 ViewModel 的重构，
  风险和工作量超出"低优先级顺手清理"的范围，不适合在收尾批次里做。

- **`WorkManagerScheduler`**：`ScheduleRepository`/`BootReceiver`/
  `ScheduleUpdateTool`/`ScheduleDeleteTool`/`ScheduledJobWorker`/
  `ScheduleCreateTool`/`WorkflowStartTool` 等 Repository/Worker/Agent Tool
  层全部是直接静态调用 `WorkManagerScheduler.xxx()`，从未有任何调用点
  过 AppContainer 或 ViewModel。`ProfileScreen.kt` 这一处的用法与全项目
  惯例完全一致，不是它自己的孤立问题。若只在这一个调用点单独包一层
  ViewModel 间接访问，反而会制造出全项目唯一一处"不一样"的调用方式，
  这是一次新的架构决策（要不要给 WorkManagerScheduler 建 DI 包装层），
  不该在收尾阶段针对单个调用点临时拍板。

两条均判定为**核实通过、非缺陷**，不在本次改动范围内，也不建议作为
"待办"遗留到下一审查窗口——除非项目后续对这两个全局 object 的访问方式
有统一的架构决策，否则单点修改没有意义。

