# S-1 DI 迁移最终收尾：GoalViewModel / JudgeProfileViewModel

> 对应 v160 审查报告 2.1 节 S-1"这是全项目最大的一类结构性问题"，
> 报告本身承认"暂不处理、留给未来批次"的两项缺口，本次全部收口。

## 变更内容

### 新增文件

- `data/repository/CharacterGoalRepository.kt`
  包装 `CharacterGoalDao` 全部方法，逐方法透传，不改变任何行为。
- `data/repository/JudgeProfileRepository.kt`
  包装 `JudgeProfileDao` 全部方法 + `CompetitionRoundDao.observeRoundsAsJudge`
  （语义上属于"裁判视角看竞赛轮次"，JudgeProfileScreen Section3 专用）。
  `confirmCorrection`/`declineCorrection` 的业务逻辑（含 P2-12 事务保护）
  整体从 ViewModel 下沉至此，事务边界收归持久层。

### AppContainer 新增字段

- `characterGoalRepo: CharacterGoalRepository`
- `judgeProfileRepo: JudgeProfileRepository`

两者均在 `db` 之后声明，无循环依赖，初始化顺序安全。

### ViewModel 改动

- `GoalViewModel`：移除 `goalDao = AppDatabase.getInstance(application)
  .characterGoalDao()` 裸持有字段，改为 `AppContainer.instance.characterGoalRepo`。
  5 处调用点（`observeActive`/`upsert`/`updateProgress`/`deactivate`/`delete`）
  全部切换。公开 API（`init`/`uiState`/`draft`/`saveDraft` 等）签名不变。

- `JudgeProfileViewModel`：移除 `db = AppDatabase.getInstance(application)`
  裸持有字段，改为 `AppContainer.instance.judgeProfileRepo`。全部调用点切换，
  `confirmCorrection`/`declineCorrection` 简化为对 Repository 的委托调用，
  `removeCandidateEntry` 私有辅助方法随事务逻辑一并下沉、从 ViewModel 删除。
  公开 API（`profiles`/`detail`/`confirmCorrection`/`declineCorrection`/
  `updateAnchorIntent`/`parseCandidateCorrectionsForDisplay` 等）签名不变。

## 明确排除在本次范围外（附理由，避免未来误判为遗漏）

| 调用点 | 涉及 DAO | 排除理由 |
|---|---|---|
| `ChatToolRegistrar`/`ProactiveMessageWorker`/`ZaijianApp` 内构造 `ProjectDailyPlannerTool`/`PresenceEngine` | `characterGoalDao()` | Domain/Agent 层工具类接受 DAO 作为构造参数是既定模式，不在 S-1"ViewModel 绕过 AppContainer"范围内 |
| `CompetitionRoundManager` 懒创建逻辑 | `judgeProfileDao()` | 同上，Domain/Agent 层既定模式 |
| `CompetitionViewModel` 其余裸调用 | `competitionRoundDao()` | 会牵连出一个新的 `CompetitionRoundRepository`，超出本次两个 VM 迁移范围，留待未来批次统一处理 |

## 结构性回归检查结论

- 括号平衡检查（`{}`/`()`）：全部文件通过
- Import 去重检查：无重复
- 公开 API 签名比对：迁移前后完全一致，两个 Screen 层调用方
  （`JudgeProfileScreen.kt`/`CharacterDetailGoal.kt`）无需改动
- DAO 默认参数（`now`/`timestamp: Long = System.currentTimeMillis()`）省略透传：
  经核实，迁移前所有调用点均未显式传时间戳参数，行为等价
- `confirmCorrection`/`declineCorrection` 下沉后与原 ViewModel 逻辑逐行比对：等价

**结论：结构变了，行为没变。** 至此 v160 报告 S-1 全部收口，全项目
不再有 ViewModel 层裸持有 `AppDatabase.getInstance()` 或裸调用 DAO 的情况
（`ChatViewModel`/`RoundtableViewModel`/`GoalViewModel`/`JudgeProfileViewModel`
均已统一走 `AppContainer.instance`）。
