# Phase 3 修复手册 · 改动清单（v93 + v94 累计）

## v93：AppContainer 落地 + 受孕弹窗链路修复

### 新增
- `app/src/main/java/com/zaijian/zhoumuyun/data/AppContainer.kt`

### 改动
- `app/src/main/java/com/zaijian/zhoumuyun/ZaijianApp.kt`
- `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/ChatViewModel.kt`
- `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/RoundtableViewModel.kt`
- `zaijian_架构瘦身审计报告-1.md`

### 内容摘要
1. `AppContainer` 按方案A（跟着 `onCreate()` 走）落地，持有6项共享实例：
   `eventRepo`/`memoryRepo`/`memoryEngine`/`relationshipEngine`（带 `milestoneDao`）/
   `pregnancyRepo`/`characterStateRepo`。`pregnancyTriggerManager` 不进容器，
   两个 ViewModel 继续各自构造，只是内部依赖改用容器共享实例。
2. **发现并修复一个真实功能缺口**：`PregnancyTriggerManager` 有 6 个参数，
   之前两个 ViewModel 都没传第 6 个 `relationshipEngine`，导致
   `shouldEvaluateFertileWindowConsent()` 对所有二代/三代女儿
   （`characterId>=1000`）恒返回 false——`ChatViewModel.kt` 里已经写好的
   三重门控受孕弹窗链路此前从未真正触发过。已在 `ChatViewModel` 构造时补传
   `relationshipEngine`，接通该链路；`RoundtableViewModel` 保持不传，圆桌场景
   继续不触发受孕弹窗（已确认这是预期行为）。

### 验收提醒（尚未做，需要实机走查）
- `relationshipEngine` 统一带 `milestoneDao`：走查一对一聊天页面，确认
  `affection`/`trust` 大幅变化时 `CharacterDetailRelationship.kt` 出现新里程碑记录。
- **受孕弹窗链路是本次修复后第一次真正跑通**，需要实机走查：单聊场景下
  二代/三代女儿关系达到 CORE 且处于排卵期窗口时，能否正常弹出受孕窗口
  同意对话框。此前编译一直通过，但功能从未被验证过。

---

## v94：ViewModel 裸DAO补Repository（报告第3条）

### 新增
- `app/src/main/java/com/zaijian/zhoumuyun/data/repository/MessageRepository.kt`
- `app/src/main/java/com/zaijian/zhoumuyun/data/repository/RoundtableMessageRepository.kt`
- `app/src/main/java/com/zaijian/zhoumuyun/data/repository/IdentityRepository.kt`
- `app/src/main/java/com/zaijian/zhoumuyun/data/repository/AgentPlanRepository.kt`

### 改动
- `ChatViewModel.kt`：`messageDao`/`identityDao`/`agentPlanDao` 三个字段右侧
  类型从裸 DAO 改为对应 Repository 包装，字段名不变，调用点不用改。
- `RoundtableViewModel.kt`：`roundtableMessageDao`/`identityDao`/`agentPlanDao`
  同样处理。
- `zaijian_架构瘦身审计报告-1.md`：标记第3条已完成，记录了报告原述"3个新文件"
  与实际"4个新文件"的差异原因，并重新核查了第4条"内联裸调用"的行号和实际数量。

### 内容摘要
1. 每个新 Repository 包装了对应 DAO 的**全部**方法（不只是两个 ViewModel 用到
   的那几个），供项目里其他仍裸持有同一 DAO 的文件（`WorldSimulation`/
   `AgentMetaTools`/`SoulMemoryUserTools`/`ZaijianApp`等）未来收敛复用——但
   本次改动只动了两个 ViewModel 文件，没有碰这些其他调用点。
2. `messageDao`/`roundtableMessageDao` 没有合并成一个 `MessageRepository`
   （报告原文字面上像是要合并），因为两者对应不同表（`messages`/
   `roundtable_messages`）和不同实体，已与检察官确认拆成两个类。
3. 两个 ViewModel 里的字段名保持不变，只改右侧构造表达式，调用点方法名与
   新 Repository 一一对应，改动面控制在字段声明那一行。

### 本次核查澄清（报告第4条，行号有偏移+性质需要区分）
`RoundtableViewModel.kt` 里的内联裸调用重新核查后发现是两类不同性质的问题：
- **982、1288行** `db.characterIdentityDao().getById(...)`：真正的"有字段却没
  复用"，可以直接改成调用 `identityDao.getById(...)`。
- **1017行** `db.memoryDao().getLockedRules(...)`：`MemoryRepository`根本没
  包装这个方法，不是"没复用"，是"当前只能走裸DAO"。处理这处时要先给
  `MemoryRepository`补上`getLockedRules`包装，再改调用点，顺序不能反——
  直接改调用点会因为`memoryRepo`没有该方法而编译失败。

---

## v95：内联裸调用收口（报告第4条）

### 改动
- `app/src/main/java/com/zaijian/zhoumuyun/data/repository/MemoryRepository.kt`
- `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/RoundtableViewModel.kt`
- `zaijian_架构瘦身审计报告-1.md`

### 内容摘要
1. `MemoryRepository.kt` 新增 `getLockedRules(characterId, goalId)` 包装方法
   （放在"读取：Prompt注入"分区，`getEternalMemories`之后、群记忆读写之前），
   原样透传 `MemoryDao.getLockedRules`，不改变查询逻辑本身。
2. `RoundtableViewModel.kt` 三处内联裸调用收口，按拍板顺序（先补
   Repository方法再改调用点）执行：
   - 982行：`db.characterIdentityDao().getById(bot.id)` → `identityDao.getById(bot.id)`
   - 1288行：`db.characterIdentityDao().getById(initiator.id)` → `identityDao.getById(initiator.id)`
   - 1017行：`db.memoryDao().getLockedRules(bot.id, goal.id)` → `memoryRepo.getLockedRules(bot.id, goal.id)`
3. 三处均只改调用表达式本身，未改动周边逻辑；`identityDao`/`memoryRepo`
   均为文件里已存在的字段（分别是`IdentityRepository`包装、`AppContainer`
   共享实例的委托属性），未新增字段、未新增文件。
4. 已做括号平衡自检（`{`/`}`、`(`/`)` 计数一致），未做实机编译（本环境无
   Android SDK/Gradle 网络访问）。

### 顺带发现、本次未处理
- `RoundtableViewModel.kt` 1015行 `db.learningGoalDao().getActive(bot.id)`
  与本次改动的1017行紧邻，同样是裸DAO调用，但`LearningGoalRepository`
  是否存在、是否已被其他文件包装尚未核查，不在报告第4条原范围内，
  未顺手处理。

---

## v96：sharedPresenceEngine 收敛（报告第5条）

### 改动
- `app/src/main/java/com/zaijian/zhoumuyun/data/AppContainer.kt`
- `app/src/main/java/com/zaijian/zhoumuyun/ZaijianApp.kt`
- `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/ChatViewModel.kt`
- `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/RoundtableViewModel.kt`
- `zaijian_架构瘦身审计报告-1.md`

### 内容摘要
1. 核查后确认`PresenceEngine`的构造参数（`goalDao`/`eventDao`）都能从
   `AppContainer`已持有的`db`直接拿到，不需要额外传参，因此选择让它自包含
   搬进`AppContainer`（新增`presenceEngine`字段，非空类型），而不是按报告
   原述"改走响应式订阅"——响应式订阅是给UI层用的模式（`ChatScreen`本来就是
   这么做的），ViewModel内部直接持有单例引用本身没问题，问题只在于"全局单例
   +多处独立访问"这个结构，容器化就能解决，改动面更小。
2. `AppContainer.companion object.init()`里新增`PresenceEngine.init(context)`
   调用（原先在`ZaijianApp.onCreate()`里单独调用的那次，随容器初始化一并完成）。
3. `ZaijianApp.onCreate()`删除原先单独`new PresenceEngine(...)`的代码，改为
   引用`AppContainer.instance.presenceEngine`，避免同一个东西被构造两份；
   `sharedPresenceEngine`字段本身保留（`PresenceViewModel`/
   `ZaijianMessagingService`仍在用），只是构造来源改了。
4. `ChatViewModel.kt`/`RoundtableViewModel.kt`的`presenceEngine`字段改为
   `get() = container.presenceEngine`（非空类型），内部散落的直接访问点全部
   改为复用该字段：
   - `ChatViewModel.kt`：原356/411行两处直接访问改为用类字段。
   - `RoundtableViewModel.kt`：原644/1222/1275行三处。其中644/1222两处是
     "局部变量重新声明、与类字段同名互相遮蔽"（`val presenceEngine = ...`
     盖住了同名类字段），改法是删掉局部变量声明而非只改右侧取值；1275行
     原本是`ZaijianApp.sharedPresenceEngine?.getCachedPresence(...) ?:
     presenceEngine?.refreshPresence(...)`，两边本质指向同一实例，现简化为
     `presenceEngine.getCachedPresence(...) ?: presenceEngine.refreshPresence(...)`
     单一来源两次调用。
5. 两个ViewModel文件里改完后`ZaijianApp`类名不再有非注释的实际代码引用，
   对应`import com.zaijian.zhoumuyun.ZaijianApp`已清理。`ZaijianApp.kt`本身
   因为`presenceEngine`构造改用全限定名`com.zaijian.zhoumuyun.domain.
   PresenceEngine`，导致原先的裸`import ...domain.PresenceEngine`变成死
   import，一并清理。
6. `PresenceViewModel.kt`/`ZaijianMessagingService.kt`本次未动，仍通过
   `ZaijianApp.sharedPresenceEngine`访问——不受影响，该字段依然存在且唯一，
   只是值来源变了。
7. 已做括号平衡自检（4个改动文件`{}`/`()`计数均一致），未做实机编译。

### 顺带确认、未处理
- `RoundtableViewModel.kt`1015行`db.learningGoalDao().getActive(...)`
  （上一轮v95记录过的遗留项）本轮未动，仍待评估。

---

## v97：第6条摸底核查（代码未动，额度限制，仅整理方案）

### 改动
- `zaijian_架构瘦身审计报告-1.md`（仅摸底记录，无代码改动）

### 核查结果
`CharacterDetailScreen.kt`（162/221行）+`CharacterDetailRelationship.kt`
（141行）共3处`AppDatabase.getInstance`直连，核查后发现**不能一刀切**：
- 162行（查女儿角色身份）：能收口，需新增`IdentityViewModel.resolveCharacter`。
- 221/141行（关系Flow订阅）：代码上方各自有"UI M4"注释，明确是团队权衡后
  **主动决定不提取到ViewModel**的现状，不是技术债。贸然改动等于推翻已有
  设计决策。

### 下个窗口建议
只做162行收口，221/141行维持现状。具体改法见报告第6条最新记录。

---

## v98：CharacterDetailScreen.kt 162行db直连收口（报告第6条·部分）

### 改动
- `app/src/main/java/com/zaijian/zhoumuyun/data/AppContainer.kt`
- `app/src/main/java/com/zaijian/zhoumuyun/ui/screen/characterdetail/CharacterDetailScreen.kt`
- `zaijian_架构瘦身审计报告-1.md`

### 内容摘要
1. 放弃v97摸底阶段设想的"改走`IdentityViewModel.resolveCharacter`"——
   `IdentityViewModel.uiState`是围绕人设编辑表单（`IdentityUiState`，persona/
   speechStyle/头像裁剪参数等一大堆字段）设计的，语义上跟"查一次
   `CharacterConfig`判断女儿角色是否存在"完全不相关，硬塞进去会污染现有state。
2. 改为把`DaughterCharacterRepository`并入`AppContainer`共享（新增
   `daughterCharacterRepo`字段）。核查确认`ChatViewModel`/
   `RoundtableViewModel`各自独立持有的`daughterRepo`/`daughterCharacterRepo`
   构造参数完全一致（都只是`db.daughterCharacterDao()`，无差异化配置），
   属于跟AppContainer已有6+1项共享实例同类的重复wiring，值得收口。
3. `CharacterDetailScreen.kt`的`LaunchedEffect`里原先两行
   （`AppDatabase.getInstance(context)` + 手动`new Repository`）改为一行
   `AppContainer.instance.daughterCharacterRepo.getCharacterConfig(characterId)`。
4. **`ChatViewModel.daughterRepo`/`RoundtableViewModel.daughterCharacterRepo`
   两个字段本身未改**——那是两个ViewModel内部把已有裸构造换成引用容器的
   顺带清理，跟本条"UI层不该摸DB"的问题性质不同，为控制改动面本次不做，
   容器里已经放了共享实例，以后要收口时直接引用即可。
5. `CharacterDetailScreen.kt`221行、`CharacterDetailRelationship.kt`141行
   两处"UI M4"关系Flow订阅按v97摸底结论维持现状，未动。
6. 括号平衡自检通过，.kt文件数293不变（只改2个已有文件）。

---

## v99：daughterRepo 收口补完（v98暂缓项，现已做）

### 改动
- `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/ChatViewModel.kt`
- `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/RoundtableViewModel.kt`
- `zaijian_架构瘦身审计报告-1.md`

### 内容摘要
1. `ChatViewModel.daughterRepo`（原`= DaughterCharacterRepository(db.daughterCharacterDao())`）
   改为`get() = container.daughterCharacterRepo`。
2. `RoundtableViewModel.daughterCharacterRepo`（原`= DaughterCharacterRepository(dao = db.daughterCharacterDao())`）
   同样改为`get() = container.daughterCharacterRepo`。
3. 两个字段名均保持不变，调用点不用改：`ChatViewModel.kt`6处方法调用
   （`updateDaughterCharacterId`×2、`getCharacterConfig`×2、
   `isThirdGeneration`×2）+ 1处作为构造参数传给`DaughterCharacterGenerator`
   （`repository = daughterRepo`）；`RoundtableViewModel.kt`3处方法调用
   （`getAllDaughterCharacterIds`、`getCharacterConfig`×2）。
4. 已确认`container`字段（`AppContainer.instance`）在两个文件里都声明在
   `daughterRepo`/`daughterCharacterRepo`之前，且`get()`委托属性本身不占用
   类初始化顺序位置，`daughterGenerator`等在类初始化阶段就会调用
   `daughterRepo`getter的地方，此时`container`早已完成赋值，无初始化时序问题。
5. 两个文件里原本的裸`import ...DaughterCharacterRepository`确认无其他
   引用后一并清理。
6. 括号平衡自检通过，.kt文件数293不变（只改2个已有文件）。

### 至此报告"要改的文件"1-6条全部完结，Out of scope 更新为：
- `CharacterDetailScreen.kt`221行 / `CharacterDetailRelationship.kt`141行：
  团队已拍板的"UI M4"设计决策，非技术债，明确不做。

---

## 下个窗口可继续（报告"要改的文件"仅剩7条·可选）
7.（可选）`ProjectViewModel.kt`绕过`TaskRepository`的3处调用

## 验收提醒（v96新增，尚未做，需要实机走查）
- 冷启动一次，确认`ZaijianApp.onCreate()`里`AppContainer.init(this)`→
  `presenceEngine`引用→`sharedPresenceEngine`赋值这条链路没有因为改动顺序
  产生空指针或初始化时机问题（理论上不会，因为`AppContainer.init()`内部
  同步完成全部构造，但没有自动化测试，这一步必须实机跑一次冷启动）。
- 单聊/圆桌两个场景下角色心情（`currentMood`/`moodMap`）显示是否正常，
  圆桌自发发言的情绪权重挑选逻辑（`pickSpontaneousInitiator`）是否受影响——
  这次改动虽然理论上是等价替换，但touch到的3处遮蔽变量原本可能存在细微的
  时序差异（比如遮蔽变量在函数入口读一次、类字段每次访问都重新走`get()`
  委托），需要走查确认心情相关UI没有出现异常。

## Out of scope（报告已标注，仍未处理）
- `ProfileScreen.kt`/`CharacterDetailScreen.kt` 的 Composable 直连数据库问题
- `WorkflowJobWorker.CHANNEL_NAME` 旧项目名残留
- 二代/三代女儿受孕弹窗链路的验收走查（v93已修复代码，尚未实机验证）

---

## 离线简报 第一步：DAO + AppContainer + BriefingRepository（复核后修订版，已合入）

对照《再见公馆》UI/UX 整合方案 v2.1 第 4.10.1/4.10.2 节，完成"施工顺序建议"第 1 步
（DAO + AppContainer）+ 第 2 步（BriefingRepository），两步强耦合，一并完成。

### 改动文件
- 新增 `data/model/BriefingData.kt`
- 新增 `data/repository/BriefingRepository.kt`
- `RelationshipMilestoneDao.kt`：+`getAllSince(after: Long)`
- `TaskDao.kt`：+`getCompletedByCharacterSince(characterId, since)`
- `CompetitionRoundDao.kt`：+`getCompletedSince(after: Long)`
- `AppContainer.kt`：新增 `menstrualCycleRepo`、`briefingRepo` 字段（纯新增，均在其依赖项之后声明，无初始化顺序问题）

以上均为纯新增 `@Query`/字段，未改动任何现有方法/表结构，无需 migration。

### 修复的问题：女儿角色数据损坏会崩掉整个开场页

`DaughterCharacterRepository.getCharacterConfig()` 对损坏的女儿数据（identityJson/
stateLayerJson 解析失败）故意抛 `DaughterDataException`——项目原有设计：宁可这一条
消息报错，不让角色带残缺人格说话。但这是"单条消息"粒度的设计。

最初实现用 `daughterIds.mapNotNull { getCharacterConfig(it) }` 批量转换全部女儿，
`mapNotNull` 只挡得住 null 返回值，挡不住抛异常——一个女儿数据损坏，`generateBriefing()`
整体向上抛异常，App 卡在开场页进不去，连累其余八九位角色的简报也生成不出来。

修复：改为单角色级别 `try-catch` 隔离 `DaughterDataException`，损坏的女儿跳过并用
`ZLog.w` 记录 `characterId` + 异常，不连累其余角色。

### 复核确认没问题（记录避免下一步重复排查）
- `observeFrom("user").first()` 一次性取快照，`WorldSimulation` 已有先例，验证过的模式
- `getInterCharacterMatrix` 返回 Map 的 key 是归一化字符串，本次代码只用 `.values`，不受影响
- `relationship_states` 表 `(fromId, toId)` 联合唯一索引，`associateBy { it.toId }` 不会丢数据
- `AppContainer` 字段声明顺序没问题，没有引用未初始化字段
- `ProjectEntity` 字段名是 `title` 不是方案文档写的 `name`，代码里已改正

### 复核发现的既有系统缺口，本次一并修复（不留待办）

最初提交版本把下面两条记录为"非本次引入、留给你判断是否处理"，后确认这两条
也要处理，不留待办，已在本次一并修复：

- **`MenstrualCycleRepository.initIfAbsent()` 全项目零调用点**：所有角色周期锚点
  为 null，"排卵期"提示不会真实触发（此前安全兜底成"安全期"，不会崩，但功能
  未接入启动流程）。设计文档本身写明的调用时机就是"App 启动时在 IO 协程中调用
  一次"。修复：在 `ZaijianApp.onCreate()` 里补上独立的 `scope.launch(Dispatchers.Default)`
  调用 `AppContainer.instance.menstrualCycleRepo.initIfAbsent()`，与文件里其它
  后台初始化同一模式——try-catch + ZLog，失败不影响冷启动、不连累其它子系统。
  时序上晚于 `AppContainer.init(this)`，不存在未初始化访问的问题。

- **`competition_rounds` 表缺 `completedAt` 索引**：`getCompletedSince(after)`
  原先先走 `status` 索引缩小范围、再对 `completedAt` 比较排序。当前数据量小
  不会有实际性能问题，但既然复核时发现了就一并修复，不留作技术债。
  修复：`CompetitionRoundEntity` 新增 `Index(value = ["completedAt"])`；
  新增 `MIGRATION_47_48`（纯 `CREATE INDEX`，不改表结构、不改数据、无需重建表）；
  `AppDatabase` 版本号 47 → 48。

  **需要你本地操作**：跟此前 46.json/47.json 一样，`exportSchema = true`，
  `48.json` 需要你本地跑一次 Gradle build 才能生成，Claude 这边没有 build
  环境无法代为生成。build 后记得把 `app/schemas/.../48.json` 一并提交。


---

## 女儿数据损坏根治：写库端校验补齐到与读库端一致

上一版修复（单角色 try-catch 隔离）解决的是"损坏数据读出来时不要连累其他
角色"，这次要解决的是"数据为什么会损坏"——根治源头，而不是继续加兜底。

### 排查结论

对比写库前 `DaughterCharacterGenerator.parseAndValidate()` 和读库时
`DaughterCharacterEntity.toDaughterCharacterData()`（含 `DaughterIdentity.kt`
内三个 `fromJson()`）的校验范围，发现两边并不是同一套规则，写库端明显更松：

| 校验项 | 写库前 | 读库时 |
|---|---|---|
| identity.persona/speechStyle/coreWound 非空 | ✅ | ✅ |
| stateLayer.maskKey 非空 | ✅ | ✅ |
| stateLayer.primaryEmotionKey/currentNeedKey/currentFearKey 非空 | ❌ | ✅ |
| customEnums 四套数组非空 | 只查 maskStates | 四套都查 |
| maskKey 存在于 maskStates 中 | ✅ | ❌（未查） |
| primaryEmotionKey/currentNeedKey/currentFearKey 存在于对应数组中 | ❌ | ❌（均未查） |

LLM 自由生成 JSON，`buildSystemPrompt()` 只是"要求"字段填满，没有结构化约束。
写库端校验有缺口，意味着 LLM 完全可能生成一份缺字段/key 对不上的 JSON，
畅通无阻写进库，直到某天读库时才第一次撞见完整校验、抛异常——这不是数据库
被意外污染，是写入端本该拦住却没拦住。

### 修复内容

**写库端**（`DaughterCharacterGenerator.parseAndValidate()`）：
补齐 `primaryEmotionKey`/`currentNeedKey`/`currentFearKey` 三个非空校验，
补齐 `emotionStates`/`needStates`/`fearStates` 三套数组非空校验，并仿照
已有的 `maskKey` 存在性校验模式，新增三处「key 是否真的能在对应枚举数组里
找到」的校验。写库端和读库端校验范围现在完全一致。

**读库端隐藏缺口一并补上**（`DaughterCharacterEntity.toDaughterCharacterData()`）：
此前读库端也只对 `maskKey` 做过存在性校验，`primaryEmotionKey`/`currentNeedKey`/
`currentFearKey` 只查非空、不查是否真能在 `customEnums` 里找到匹配项——这一层
查不到时不会抛异常，是运行时静默的行为异常：女儿说话时 `customEnums.findEmotion(key)`
返回 null，没人发现。现在 `toDaughterCharacterData()` 改为先解析出
`stateLayer`/`customEnums` 两个对象，再做四项跨对象 key 存在性校验，全部
通过后才组装返回。`DaughterStateLayer.fromJson()`/`DaughterCustomEnums.fromJson()`
各自的单一职责边界不变，跨对象校验只能在能同时拿到两者的这一层做。

### 影响与需要注意的点

这是补严校验，不是新增功能，理论上不改变任何"正常数据"的行为。但如果
线上已有女儿数据是当初侥幸绕过写库端漏洞生成、之前一直"能用但可能有点问题"
（比如某个 key 查不到对应枚举、说话时情绪状态一直是兜底默认值），本次
读库端补上跨对象校验之后，这类数据会从"能用"变成"读取时抛异常、走
单角色 try-catch 隔离、这个女儿的简报生成不出来"。这不是新 bug，是把
之前被漏过去的问题正确地暴露出来，但建议如果观察到某个女儿角色本次
更新后突然读不出来，先怀疑她是历史遗留的边缘数据，而不是当作新引入的 bug。

不改变任何异常处理路径本身——`DaughterDataException` 抛出后仍然由
`BriefingRepository`（单角色隔离）、`getFamilyChain()`（第二/三代跳过）
等既有 catch 块处理，这次改动的目标是让这些 catch 块在实践中不会被触发，
而不是移除它们。

## 未做的事（按方案 4.10.4 顺序留给下一步）
- `BriefingDataStore`（4.4 节）
- `BriefingViewModel`（4.7 节）
- `ui/screen/briefing/` 四个 Compose 文件 + `BriefingScreen.kt`（4.10.3）
- 导航接入（4.2 节）
