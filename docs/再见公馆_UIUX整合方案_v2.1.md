# 《再见公馆》UI/UX 整合方案 v2.1

本方案整合了两类输入素材：
1. `zaijian_visual_design_upgrade_v1.md`（原版方案）—— 基于实际源码写成，作为本方案的**主干和最高优先级原则**。
2. ChatGPT 生成的 10 份通用重构文档（Part 01-09、12、21-24）—— 逐份对照 v106 源码核实后，**去除了与现有架构冲突或重复造轮子的部分**，只保留少量确认有效的点子。

另外新增一节独立设计：**开场"离线简报"（Briefing）页面**，这是本次讨论中新产生的、原两类素材都没有的内容。

**v2.1 变更说明**：第四节新增 4.10 小节，把"离线简报"的组件拆分、聚合查询函数逐一对照 v106 源码核实到方法签名级别，并修正了 v2.0 稿里几处方法名/类型误写（详见 4.10 开头的"本次核实修正的坑"列表）。原 4.1-4.9 保持不变，4.10 是在此基础上的施工级补充，接手窗口应以 4.10 为最终准绳，4.3/4.5 表格里和 4.10 冲突的地方以 4.10 为准。

---

## 施工进度追踪（本节随实际进度更新，不属于原方案内容）

对照 4.10.4 施工顺序建议的 5 步：

| 步骤 | 内容 | 状态 |
|---|---|---|
| 1 | 4.10.1 DAO 新增方法 + `AppContainer` diff | ✅ 已完成并合入 |
| 2 | `BriefingRepository`（4.10.2） | ✅ 已完成并合入 |
| 3 | `BriefingDataStore`（4.4）+ `BriefingViewModel`（4.7） | ✅ 已完成并合入 |
| 4 | `ui/screen/briefing/` 四个子文件（4.10.3）→ `BriefingScreen.kt` 组装 | ✅ 已完成并合入 |
| 5 | 导航接入（4.2） | ✅ 已完成并合入 |

**5 步全部完成**：离线简报（Briefing）功能已按 4.10.4 施工顺序全部落地，
`Splash → Briefing → World` 的开场流程已接通。沙盒环境无 Android SDK，
`compileDebugKotlin`/`assembleDebug` 编译验证仍需本地 Gradle 完成。

**第二节「逐模块具体改法」施工进度**（本次新增）：

| 模块 | 状态 |
|---|---|
| 2.1 任务页顶部（`TaskCenterScreen.kt`） | ✅ 已完成——删除「目标」按钮；「项目」「日程」升级为 `WorldCard` 预览卡，显示真实数据（活跃项目数+完成率、今日待办数） |
| 2.2 "我"页进化项目卡 | ✅ 已完成——直接砍掉（2.1 的项目预览卡已覆盖同样作用，避免同一信息在多个 Tab 重复摆放） |
| 2.3 圆桌入口 | 不做（用户明确跳过） |
| 2.4 图标统一收口 | 🔶 基础设施已搭建，示范迁移 1 处，其余 139 处留待后续逐步迁移 |
| 2.5 全项目 token 审查 | 暂不做 |

2.4 说明：全项目 `Icons.Outlined.XXX` 直接引用共 141 处，分布在 30 个文件、
69 种不同图标，一次性全改风险高、验证难度大，方案原文本身也是"逐步收拢"。
本次新建 `ui/design/AppIcons.kt`（单一文件，未按模块拆分），包含：
- `AppIcons` object：图标常量收拢入口，目前收拢了 `Folder`/`CalendarMonth`
  两个（本次示范迁移实际用到的），新增时照此格式追加，一次迁移一处即可
- `IconBadge` composable：图标 + `Radius.xs` 圆角小色块背景的可复用组件，
  背景色默认 `colors.accentSoft`，语义色场景可传入对应
  `Palette.SemanticXxx.copy(alpha = 0.12f)`

示范迁移：2.1 任务页「项目/日程」预览卡的图标，从裸 `Icon`（默认无背景）
改为 `IconBadge`（色块背景），图标引用改用 `AppIcons.Folder`/
`AppIcons.CalendarMonth`。其余 139 处引用未动，留待后续按同样模式逐步迁移。

2.1 完成后额外清理的死代码：`TaskCenterScreen` 的 `onNavigateToGoals` 参数、
`AppNavigation.kt` 里配套的 `showGoalsCharacterPicker` 状态与其触发的
`CharacterPickerSheet` 弹层——三者只服务于已删除的「目标」按钮，与角色详情页
自己的目标入口、底部「成长」Tab 都是独立路径，确认互不影响后一并删除。
`CharacterPickerSheet` 组件本体是通用角色选择器，未删除，仅清理了调用链路。

2.2 完成后额外清理的死代码：`EvolutionProjectsEntry` 组件本体（`ProfileMisc.kt`，
纯 UI 一次性代码、非通用组件，唯一调用点已删）、`ProfileScreen` 的
`onNavigateToProjects` 参数、`AppNavigation.kt` 里对应的参数传递，以及
`ProfileScreen.kt`/`ProfileMisc.kt` 两个文件里因此变成死引用的
`Icons.Outlined.Spa`/`ChevronRight` import。

**步骤 1、2 完成后，复核过程中额外发现并处理的问题**（不在原方案范围内，
详见项目根目录 `docs/CHANGES_phase3.md` 对应章节）：

- **女儿角色数据损坏会拖垮整个开场页**：`BriefingRepository.generateBriefing()`
  聚合九位母亲简报时，单个女儿数据损坏原会导致整体抛异常、App 卡在开场页
  进不去。已修复为单角色 try-catch 隔离，跳过损坏角色，不连累其余角色。
- **女儿数据损坏的根治**：进一步排查发现损坏数据的真正来源是写库端
  （`DaughterCharacterGenerator.parseAndValidate()`）校验范围比读库端窄，
  LLM 生成的不完整/自相矛盾数据能绕过写库校验、直到读库时才第一次被拦截。
  已将写库端校验补齐到与读库端一致（含读库端此前也遗漏的三处 key 存在性
  校验），从数据源头堵住，而非仅在读取时兜底。
- **`MenstrualCycleRepository.initIfAbsent()` 零调用点**：已接入
  `ZaijianApp.onCreate()` 启动流程。
- **`competition_rounds` 表缺 `completedAt` 索引**：已补充索引 +
  `MIGRATION_47_48`，DB 版本 47 → 48（`48.json` 需本地 Gradle build 生成）。

**步骤 4 施工中额外发现的问题**（4.10.0 坑表未覆盖，4.10.3 示例代码本身与源码不符）：

- **`ZaijianTheme.typography.titleM` / `bodyM` 不存在**：`AppTypography.kt`
  实际字段是 `titleBold`/`cardTitle`/`navTitle`/`body`/`caption`/`label`/
  `labelMono`/`button`/`presence`/`bodyBold`，没有 `titleM`/`bodyM`。4.10.3
  全篇示例代码用的这两个 token 会直接编译不过。已将区块级标题（"需要关注"
  "亲密度排行"、角色名）改用 `cardTitle`，正文文案改用 `body`。
- **`Spacing.screenPadding` 不存在**：真实字段名是 `Spacing.screenHorizontal`
  （语义一致，"页面左右边距"），已在四个子文件里改用正确字段名。
- **`InfoChip` 组件项目里不存在**：4.8/4.10.3 都只写了用法，没有确认是否已有
  同名组件（4.8 节原文承认"若项目已有同名组件请直接复用，否则用现有 Chip
  惯例新建"）。已排查项目里唯一相关的 `WrapChipGroup` 是"可点击+可选中"的
  筛选标签组，语义与"怀孕中"这种静态状态标签不符，未复用；改为在
  `WorldOSComponents.kt` 新增最小实现 `InfoChip(text, color)`，与 `WorldCard`
  等基础组件放在同一文件，供后续其他 Screen 复用。
- **`BriefingScreen.kt` loading 态**：4.10.3 示例代码写的是加载中直接
  `return`（会渲染一片空白），与方案注释"复用项目里其余 Screen 已有的
  loading 占位惯例"矛盾。已核实 `ProjectScreen.kt` 的真实写法（居中
  `CircularProgressIndicator(color = colors.primary)`），按此补全。

---

## 一、总原则（最高优先级，来自原版方案）

1. **不重新发明设计语言，只把已有的"公馆语言"铺满掉队的模块。** `WorldCard`（纸面底+光斑+黄铜描边+身份脊+蜡封角标五层）、`BondRibbon`、`MoodCandle`，以及 `theme/` 包下的 `Palette`/`AppColors`/`AppTypography`/`AppDimens`（Spacing/Radius/Elevation/AnimDuration）/`AppAnimations`/`ZaijianModifiers` 这套体系已经足够独特和完整。缺的是**覆盖率**，不是**新体系**。
2. **明确禁止：**
   - 新建第二套 design token 系统（如 `ZdsToken`/`LocalZdsToken`），与现有 `theme/` 包并存造成分裂
   - 给所有页面强加统一的 Hero→Dashboard→QuickAction→Timeline 大模板
   - 把 World 页面从"插画拱门定位"改造成可滚动的 LazyColumn 仪表盘
   - 为不存在独立页面的模块（Memory、Settings）凭空设计"独立 OS 页面"
3. **卡片语言只有一个底座：** 新卡片一律用 `WorldCard` 包裹，禁止再手写 `Surface`+`RoundedCornerShape`+`border` 三件套。
4. **图标语言：** 不重新画一套图标库，用"色板+等宽数字+色块背景"包装 Material 默认图标提升识别度，不建分模块的图标文件体系。

---

## 二、逐模块具体改法（原版方案 3.1-3.4，确认保留）

### 2.1 任务页顶部（`TaskCenterScreen.kt`）
- 删除"目标"按钮（底部已有"成长"Tab 一步直达，多余）
- "项目""日程"从纯跳转按钮升级为两张迷你 `WorldCard` 预览卡，放在 Tab 栏下方、任务列表上方，显示真实数据（进行中项目数/最新完成率、今日待办数），数字用 `labelMono`

### 2.2 "我"页"进化项目"卡片（`ProfileScreen.kt` 的 `EvolutionProjectsEntry`）
- 二选一：接入真实数据改用 `WorldCard`（副标题换成"3个进行中·2个待推进"这类真实统计，达成里程碑时 `isMilestone = true`），或直接砍掉（如果 2.1 的"进行中项目"卡已覆盖此入口作用）
- 推荐砍掉，避免同一信息在多个 Tab 重复摆放

### 2.3 圆桌入口（`WorldScreen.kt` 478行）—— 唯一"大胆"的例外
- 现状：完全透明的隐藏热区，用户只能靠手滑碰到，是严重的可用性问题
- 改法：在公馆大门台阶做一个**可见的蜡封样式图标**，复用 `Palette.Velvet`/`VelvetSoft`（与 `WorldCard` 的 `isMilestone` 红点同一色彩语义）。未解锁足够角色时为空心描边，解锁后为实心。点击有轻微"揭开蜡封"式缩放反馈（复用 `AnimDuration.instant`）
- 这是全 App 唯一允许"显眼"的地方，其余模块继续保持克制，统一走同一套卡片语言

### 2.4 图标统一收口（本方案新增，整合自 ChatGPT Part 22 的唯一有效诉求）
- 新建**单一** `AppIcons.kt`（不按模块拆分成 11 个子文件），逐步把项目里散落直接引用的 `Icons.Outlined.XXX` 收拢进来
- 图标底色不再用默认灰/无背景，统一套 `Radius.xs` 圆角小色块背景，取自 `Palette.AccentSoft` 或对应语义色的 `.copy(alpha = 0.12f)`（复用现有色板，不新建 `IconColors` 体系）
- 图标旁数字一律 `labelMono`
- "新增"类操作统一为 `+` 图标 + 黄铜色虚线边框的完整可点击行（`WorldCard` 变体）

### 2.5 全项目 token 审查（整合自 ChatGPT Part 01/21/23/24 的唯一有效诉求）
- 审查全项目是否有直接写 `Color(0xFF...)` / `padding(13.dp)` 这类未使用现有 token 的硬编码值，逐个替换为已有的 `Palette`/`Spacing`/`Radius`/`Elevation`
- **不新建任何新的 token 对象**，只是给现有 `theme/` 包补漏

---

## 三、实施顺序建议（承接原版方案第四节）

1. **圆桌入口**（2.3）—— 工作量最小，观感提升最大
2. **任务页头部**（2.1）—— 不涉及新数据源
3. **"我"页项目卡**（2.2）—— 建议直接砍掉，省一步开发
4. **图标统一收口**（2.4）—— 工作量分散，适合放在前几项验证过视觉效果后再批量处理
5. **全项目 token 审查**（2.5）—— 长期、零散进行，可作为其他开发任务顺手做的清理项

---

## 四、离线简报（Briefing）—— 新增开场页面

### 4.1 产品定位

不是"可选查看的详情页"，而是**每次启动 App 的开场仪式**：

```
Splash（Logo呼吸动画）→ Briefing（离线简报）→ World（推门进入公馆）
```

核心问题："我不在的这段时间，这个世界发生了什么、谁需要我"。这与 ChatGPT Part 03 提议的"给 World 页面常驻叠加天气/时间等氛围信息"是完全不同的产品概念，因此没有采纳 Part 03 的方案，改为独立设计。

以下内容已逐项对照 v106 源码核实（文件路径、方法签名、字段名均为真实存在，不是示意），目的是让接手的开发窗口**不需要再做架构判断，只需要照做**。

---

### 4.2 导航接入（改动范围明确且局部）

**现状代码**（`ui/screen/AppNavigation.kt`）：

```kotlin
sealed class AppRoute(val route: String) {
    object Splash : AppRoute("splash")
    object World  : AppRoute("world")
    // ...
}
```

```kotlin
composable(
    route           = AppRoute.Splash.route,
    enterTransition = { fadeIn(tween(AnimDuration.fast)) },
    exitTransition  = { fadeOut(tween(AnimDuration.pageSwitch)) },
) {
    SplashScreen(
        onFinished = {
            navController.navigate(AppRoute.World.route) {
                popUpTo(AppRoute.Splash.route) { inclusive = true }
            }
        },
    )
}
```

**改动 1** —— 在 `sealed class AppRoute` 里新增一行：

```kotlin
object Briefing : AppRoute("briefing")
```

不加入 `bottomNavRoutes`（第168行附近那个 `listOf(...)`），不加入 `detailRoutes`（第177行附近），因为它既不是底部Tab，也不是"从Tab点进去的详情页"（不需要 slideIn/slideOut 那套详情页转场），它是 Splash 之后的第二段过场，应该有自己独立的转场定义，参照 Splash 自己覆盖 `enterTransition`/`exitTransition` 的写法（不依赖 NavHost 全局的 `isDetailRoute()` 判断逻辑）。

**改动 2** —— 把 `SplashScreen.onFinished` 里的跳转目标从 `World` 改成 `Briefing`：

```kotlin
composable(
    route           = AppRoute.Splash.route,
    enterTransition = { fadeIn(tween(AnimDuration.fast)) },
    exitTransition  = { fadeOut(tween(AnimDuration.pageSwitch)) },
) {
    SplashScreen(
        onFinished = {
            navController.navigate(AppRoute.Briefing.route) {
                popUpTo(AppRoute.Splash.route) { inclusive = true }
            }
        },
    )
}

// ── Briefing（新增）──────────────────────────────────
composable(
    route           = AppRoute.Briefing.route,
    enterTransition = { fadeIn(tween(AnimDuration.pageSwitch)) },
    exitTransition  = { fadeOut(tween(AnimDuration.pageSwitch)) },
) {
    BriefingScreen(
        onEnterWorld = {
            navController.navigate(AppRoute.World.route) {
                popUpTo(AppRoute.Briefing.route) { inclusive = true }
            }
        },
    )
}
```

原来 `Splash → World` 那行 `navigate` 代码原样保留，只是搬到了 `BriefingScreen` 的 `onEnterWorld` 回调里触发，`popUpTo` 目标改成 `Briefing`（保证 World 是新的 back stack 起点，物理返回键不会退回简报页或 Splash）。

---

### 4.3 数据层：需要新增的查询方法（逐条列出，其余全部复用现有方法）

以下是对照 v106 源码逐个 DAO 核实后的结论——**大部分数据已有现成查询方法，只有 3 处需要新增**：

| 需要的数据 | 现状 | 需要的动作 |
|---|---|---|
| 每个角色的关系状态（六维数值+stage） | `RelationshipDao.observeFrom(fromId: String)` 已存在，`fromId = "user"` 即可拿到用户对所有角色的关系列表 | 直接复用，无需新增 |
| 角色间关系矩阵（谁和谁紧张） | `RelationshipEngine.getInterCharacterMatrix(characterIds: List<Int>)` 已存在，返回 `Map<String, RelationshipEntity>`，可读 `.tension`/`.conflict` | 直接复用，无需新增 |
| 关系转折点（最近发生了"变好"或"变差"） | `RelationshipMilestoneDao` 现有 `getRecent(fromId, toId, limit)` 是**单对角色**查询，没有"取某个时间点之后，全部角色的转折点"的查询 | **新增** `RelationshipMilestoneDao.getAllSince(after: Long): List<RelationshipMilestoneEntity>`，SQL：`SELECT * FROM relationship_milestones WHERE createdAt >= :after ORDER BY createdAt DESC` |
| 怀孕状态 | `PregnancyRepository`（`AppContainer.instance.pregnancyRepo`）已存在按角色查询的方法 | 直接复用 |
| 排卵期/经期阶段 | `MenstrualCycleRepository.get(characterId): MenstrualCycleState`，阶段由 `MenstrualCycleState.currentPhase()` 实时算出 | 直接复用，无需新增 |
| 参与的项目 | `ProjectDao` 有 `observeAll()`（项目列表）+ `getMembers(projectId)`（`ProjectRepository.getMembers`，读 `project_members` 表） | 直接复用，按 `characterId` 反查需要在聚合层做一次 in-memory group，不需要新 SQL |
| 完成的任务 | `TaskDao.getByCharacter(characterId, limit)` 已存在，但**不带时间过滤和状态过滤组合** | **新增** `TaskDao.getCompletedByCharacterSince(characterId: Int, since: Long): List<TaskEntity>`，SQL：`SELECT * FROM tasks WHERE characterId = :characterId AND status = 'COMPLETED' AND completedAt >= :since ORDER BY completedAt DESC` |
| 交付评分 | `CompetitionEntryDao.getAllForRound(roundId)` 按轮次查，`CompetitionRoundDao` 应有类似 `observeAll`/`getRecent` 按状态或时间查轮次的方法（实现前请再核实一遍 `CompetitionRoundDao.kt` 现有方法，本方案未逐行核对该文件） | 若无按时间过滤的轮次查询，需新增 `CompetitionRoundDao.getCompletedSince(after: Long): List<CompetitionRoundEntity>`，再用 `roundId` 逐个查 `CompetitionEntryDao.getAllForRound` 取该角色的 entry |
| 最后联系时间 | `MessageDao.getLastMessageAt(characterId: Int): Long?` | **已有现成方法，直接复用**，无需新增 |
| 亲密度排行榜 | 同"关系状态"数据源，`RelationshipEntity.affection`（`fromId = "user"`）排序即可 | 直接复用 |
| "上次打开 App 是什么时候"（决定简报的时间跨度） | 项目里没有任何地方记录过"最后一次打开App的时间戳"（已搜索 `lastOpenAt`/`lastAppOpen`/`lastSessionAt`/`appLastOpened` 等关键词，只有 `EvaluationEngine.kt` 里一个语义完全不同的内存态 `lastSessionAt`） | **新增一个 DataStore**，见 4.4 |

### 4.4 新增文件 1：`data/datastore/BriefingDataStore.kt`

完全参照项目里已有的 `AppearanceDataStore.kt` 写法（同样用 `preferencesDataStore` + `safeData()`/`safeEdit()`）：

```kotlin
package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.briefingDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "briefing")

object BriefingKeys {
    val LAST_OPEN_AT = longPreferencesKey("last_open_at")
}

class BriefingDataStore(private val context: Context) {

    /** null 表示从未记录过（App 首次安装后第一次启动） */
    val lastOpenAtFlow: Flow<Long?> = context.briefingDataStore.safeData()
        .map { it[BriefingKeys.LAST_OPEN_AT] }

    suspend fun setLastOpenAt(timestamp: Long) {
        context.briefingDataStore.safeEdit { it[BriefingKeys.LAST_OPEN_AT] = timestamp }
    }
}
```

**读写时机**：`BriefingViewModel` 初始化时先**读取**上一次的 `lastOpenAtFlow`（作为本次简报"统计起点"），生成完简报数据后再**写入**当前时间戳（作为下一次简报的起点）。首次安装、没有历史记录时（`null`），起点回退为"7天前"或"角色解锁以来"（具体回退窗口是产品判断，建议先用 7 天，可调）。

---

### 4.5 新增文件 2：`data/repository/BriefingRepository.kt`

聚合层，职责是"逐个角色，把上面 4.3 列的各类数据 group 到一起"，不新增数据库表，只做读取和内存聚合。参照项目里 `ProjectRepository`/`MenstrualCycleRepository` 的构造方式（构造函数接收所需的 DAO/Repository，不在内部自己 new `AppDatabase`）：

```kotlin
package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.*
import com.zaijian.zhoumuyun.data.db.entity.*
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.domain.RelationshipEngine

/**
 * 离线简报聚合层。只读，不修改任何业务数据，每次生成简报时按需查询。
 */
class BriefingRepository(
    private val relationshipDao: RelationshipDao,
    private val relationshipMilestoneDao: RelationshipMilestoneDao,
    private val relationshipEngine: RelationshipEngine,
    private val pregnancyRepo: PregnancyRepository,
    private val menstrualCycleRepo: MenstrualCycleRepository,
    private val projectDao: ProjectDao,
    private val taskDao: TaskDao,
    private val messageDao: MessageDao,
    // CompetitionRoundDao/CompetitionEntryDao 视 4.3 表格核实结果决定是否注入
) {
    suspend fun generateBriefing(since: Long, now: Long = System.currentTimeMillis()): BriefingData {
        val characters = DefaultCharacters.filter { it.isUnlocked }
        val relations = relationshipDao.observeFrom("user") // 一次性 collect 首个值，或改用 suspend 版本
        val interMatrix = relationshipEngine.getInterCharacterMatrix(characters.map { it.id })
        val worsenedMilestones = relationshipMilestoneDao.getAllSince(since)
            .filter { it.direction == RelationshipMilestoneDirection.WORSENED.name }

        val perCharacter = characters.map { config ->
            val relation = /* 从 relations 里按 toId == config.id.toString() 找 */ TODO()
            val lastMessageAt = messageDao.getLastMessageAt(config.id)
            val pregnancy = pregnancyRepo.get(config.id) // 方法名以 PregnancyRepository 实际签名为准
            val cycle = menstrualCycleRepo.get(config.id)
            val completedTasks = taskDao.getCompletedByCharacterSince(config.id, since)
            // projectMembership: 在 projectDao.observeAll() 结果里按 members 反查 config.id 所属项目
            BriefingCharacterEntry(
                character         = config,
                relation          = relation,
                lastMessageAt     = lastMessageAt,
                daysSinceContact  = lastMessageAt?.let { (now - it) / 86_400_000L },
                isPregnant        = pregnancy?.isPregnant == true,
                cyclePhase        = cycle?.currentPhase(now),
                completedTaskCount = completedTasks.size,
                // projectNames, competitionScore 按实际聚合结果填充
            )
        }

        val attentionItems = buildAttentionList(perCharacter, interMatrix, worsenedMilestones)
        val ranking = perCharacter.sortedByDescending { it.relation?.affection ?: 0 }

        return BriefingData(
            periodStart      = since,
            periodEnd        = now,
            characters       = perCharacter,
            attentionItems   = attentionItems,
            affectionRanking = ranking,
        )
    }

    /** 「需要关注」判定逻辑集中在这一个函数，方便你后续单独调阈值，不用满文件找散落的 if。 */
    private fun buildAttentionList(
        entries: List<BriefingCharacterEntry>,
        interMatrix: Map<String, RelationshipEntity>,
        worsened: List<RelationshipMilestoneEntity>,
    ): List<BriefingAttentionItem> {
        val items = mutableListOf<BriefingAttentionItem>()

        // 阈值均为建议默认值，可调，集中写在这里方便找：
        val noContactThresholdDays = 7L
        val tensionThreshold       = 60

        entries.forEach { entry ->
            if ((entry.daysSinceContact ?: 0) >= noContactThresholdDays) {
                items += BriefingAttentionItem.NoContact(entry.character, entry.daysSinceContact!!)
            }
            if (entry.isPregnant) {
                items += BriefingAttentionItem.Pregnancy(entry.character)
            }
        }
        interMatrix.values.filter { it.tension >= tensionThreshold }.forEach { rel ->
            items += BriefingAttentionItem.Tension(rel.fromId, rel.toId, rel.tension)
        }
        worsened.forEach { m ->
            items += BriefingAttentionItem.RelationWorsened(m.fromId, m.toId, m.description)
        }
        return items
    }
}
```

> 上面标 `TODO()` 和"方法名以实际签名为准"的地方，是需要实现者打开对应文件核实一次现有方法签名后再填的位置——本方案已经把该查哪个文件、大致怎么组织都定好了，不需要重新设计结构，只是最后拼装时可能要对一两个方法名做字面校正。

**数据模型**（同文件或新建 `data/model/BriefingData.kt`）：

```kotlin
data class BriefingData(
    val periodStart: Long,
    val periodEnd: Long,
    val characters: List<BriefingCharacterEntry>,
    val attentionItems: List<BriefingAttentionItem>,
    val affectionRanking: List<BriefingCharacterEntry>,
)

data class BriefingCharacterEntry(
    val character: CharacterConfig,
    val relation: RelationshipEntity?,
    val lastMessageAt: Long?,
    val daysSinceContact: Long?,
    val isPregnant: Boolean,
    val cyclePhase: Any?, // 类型以 MenstrualCycleState.currentPhase() 实际返回类型为准
    val completedTaskCount: Int,
    val projectNames: List<String> = emptyList(),
    val competitionScore: Float? = null,
)

sealed class BriefingAttentionItem {
    data class NoContact(val character: CharacterConfig, val days: Long) : BriefingAttentionItem()
    data class Pregnancy(val character: CharacterConfig) : BriefingAttentionItem()
    data class Tension(val fromId: String, val toId: String, val tension: Int) : BriefingAttentionItem()
    data class RelationWorsened(val fromId: String, val toId: String, val description: String) : BriefingAttentionItem()
}
```

---

### 4.6 AppContainer 接入

在 `data/AppContainer.kt` 里新增一行（参照现有 `taskRepo`/`pregnancyRepo` 的声明方式）：

```kotlin
val briefingRepo: BriefingRepository = BriefingRepository(
    relationshipDao         = db.relationshipDao(),
    relationshipMilestoneDao = db.relationshipMilestoneDao(),
    relationshipEngine      = relationshipEngine,   // 复用上面已经构造好的实例
    pregnancyRepo           = pregnancyRepo,        // 同上
    menstrualCycleRepo      = /* 若 AppContainer 尚未持有，需要新增构造，参照 pregnancyRepo 的写法 */,
    projectDao              = db.projectDao(),
    taskDao                 = db.taskDao(),
    messageDao              = db.messageDao(),
)
```

---

### 4.7 新增文件 3：`ui/viewmodel/BriefingViewModel.kt`

参照 `PresenceViewModel` 的写法（`AndroidViewModel` + `MutableStateFlow<XxxUiState>` + `asStateFlow()`）：

```kotlin
package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.ZaijianApp
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.datastore.BriefingDataStore
import com.zaijian.zhoumuyun.data.model.BriefingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class BriefingUiState(
    val isLoading: Boolean = true,
    val data: BriefingData? = null,
)

class BriefingViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BriefingUiState())
    val uiState: StateFlow<BriefingUiState> = _uiState.asStateFlow()

    private val store = BriefingDataStore(application)

    init {
        viewModelScope.launch {
            val lastOpenAt = store.lastOpenAtFlow.first()
            val since = lastOpenAt ?: (System.currentTimeMillis() - 7 * 86_400_000L)
            val data = AppContainer.instance.briefingRepo.generateBriefing(since)
            _uiState.value = BriefingUiState(isLoading = false, data = data)
            store.setLastOpenAt(System.currentTimeMillis())
        }
    }
}
```

---

### 4.8 新增文件 4-7：`ui/screen/briefing/` 子目录（参照 `characterdetail/` 拆分惯例）

项目里 `CharacterDetailScreen` 已经示范了"大页面拆成多个子文件"的惯例（`CharacterDetailHeader.kt`/`CharacterDetailMemory.kt`/... 各自是私有 `@Composable` 函数，由主 Screen 文件组装），Briefing 页面照此惯例：

```
ui/screen/
├── BriefingScreen.kt                    ← 主入口，负责整体 LazyColumn 编排
└── briefing/
    ├── BriefingAttentionSection.kt      ← "需要关注"板块
    ├── BriefingCharacterCard.kt         ← 单个角色卡片
    └── BriefingRankingSection.kt        ← 亲密度排行榜
```

**`BriefingScreen.kt` 组件树**：

```
BriefingScreen(onEnterWorld: () -> Unit, viewModel: BriefingViewModel = viewModel())
└── LazyColumn
    ├── item { 引导语（"你离开的这 X 天里..."，periodStart~periodEnd 换算成"X天"）}
    ├── item { BriefingAttentionSection(attentionItems) }   // attentionItems 为空时该 item 不加入 LazyColumn，等同整块隐藏
    ├── items(characters) { entry -> BriefingCharacterCard(entry) }
    ├── item { BriefingRankingSection(affectionRanking) }
    └── item { 「推门进入公馆」按钮 → onEnterWorld() }
```

**`BriefingCharacterCard.kt`**（核心卡片，复用现有组件，不新建视觉逻辑）：

```kotlin
@Composable
fun BriefingCharacterCard(entry: BriefingCharacterEntry, modifier: Modifier = Modifier) {
    WorldCard(
        modifier    = modifier,
        ownerAccent = entry.character.accentColor,
        isMilestone = /* 本次统计周期内该角色是否有 RelationshipMilestoneEntity.REPAIRED 或 stage 提升 */ false,
    ) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            Text(entry.character.name, style = ZaijianTheme.typography.titleM /* 实际 token 名以 AppTypography.kt 为准 */)

            // 情感阶段：复用 BondRibbon，stage 需要把 RelationshipEntity.stage（String）
            // 转成 BondStage 枚举（两者字段名一致：STRANGER/FAMILIAR/TRUSTED/IMPORTANT/CORE，
            // 直接 BondStage.valueOf(entry.relation?.stage ?: "STRANGER") 即可）
            BondRibbon(
                stage       = BondStage.valueOf(entry.relation?.stage ?: "STRANGER"),
                accentColor = entry.character.accentColor,
                showLabels  = true,
                suppression = entry.relation?.suppression,
            )

            // 数字统一 labelMono（呼应原版方案 2.2 节字体规则）
            Text("完成任务 ${entry.completedTaskCount} 个", style = ZaijianTheme.typography.labelMono)

            if (entry.projectNames.isNotEmpty()) {
                Text("参与项目：${entry.projectNames.joinToString("、")}", style = ZaijianTheme.typography.labelMono)
            }

            // 怀孕/排卵期等特殊状态：复用 Palette 语义色，不新建颜色
            if (entry.isPregnant) {
                InfoChip(text = "怀孕中", color = Palette.SemanticReminder) // InfoChip 若项目已有同名组件请直接复用，否则用现有 Chip 惯例新建
            }
        }
    }
}
```

**`BriefingAttentionSection.kt`**：同样用 `WorldCard`，但配色走 `Velvet`/`VelvetSoft`（`isMilestone = true` 或直接在 content 内部用 `Palette.Velvet` 给关键文字上色），逐条渲染 `BriefingAttentionItem` 的 `sealed class` 分支，四种类型（NoContact/Pregnancy/Tension/RelationWorsened）各自一行文案，具体措辞可以直接照抄本方案 4.3 表格设计草图里的例子（"明媚：关系降温（紧张度+18）"这种格式）。

**"推门进入公馆"按钮**：视觉参照本方案 2.3 节"圆桌入口"的蜡封语言（`Palette.Velvet`/`VelvetSoft`，点击有 `AnimDuration.instant` 时长的缩放反馈），做成一个横跨屏宽的按钮而不是小图标，因为这里是主要出口而不是隐藏彩蛋。

---

### 4.9 暂不做的事 / 留给实现者判断的空间

- **"需要关注"的判定阈值**（多少天算太久没联系、tension 涨多少算需要关注）：4.5 节代码里已给出建议默认值（7天/tension≥60）并集中写在 `buildAttentionList` 一个函数里，方便后续单独调整，但最终数值请你在实际用起来之后手感调整，本方案不代为拍板
- **`CompetitionRoundDao` 是否已有按时间过滤的查询**：本方案未逐行核对该 DAO 文件的现有方法，4.3 表格里已标注需要实现前再核实一次，若已有等价方法直接复用，不必新增
- **`MenstrualCycleState.currentPhase()` 的返回类型**：本方案未展开核对该函数签名，`BriefingCharacterEntry.cyclePhase` 暂用 `Any?` 占位，实现时替换为准确类型
- **排行榜、任务、项目、评分等内容如果一屏放不下**：可以先做"需要关注 + 角色卡 + 排行榜"这个最小版本上线验证，任务/项目/评分明细留到点开角色卡片后的详情态（可复用 `CharacterDetailScreen` 现有的 Tab 结构），不必第一版全部塞进首屏

---

### 4.10 施工级细化：组件拆分 + 聚合查询函数（本次新增，已逐条对照 v106 源码核实到方法签名级别）

以下内容是对 4.1-4.9 的**施工级补充**，目的是让接手窗口不用再打开任何源文件做架构判断，照抄即可。同时，核实过程中发现 4.3/4.5 有几处需要修正，先列在最前面：

#### 4.10.0 本次核实修正的坑（4.3/4.5 与源码不符的地方）

| 坑 | 4.3/4.5 原文写法 | 源码实际情况 | 影响 |
|---|---|---|---|
| **女儿角色完全没被覆盖** | `characters = DefaultCharacters.filter { it.isUnlocked }` | `DefaultCharacters` 是写死的 9 人母亲列表，**不包含** characterId ≥ 1000 的女儿角色。女儿走独立的 `DaughterCharacterRepository`，有 `getAllDaughterCharacterIds(): List<Int>` 和 `getCharacterConfig(daughterCharacterId): CharacterConfig?` 两个方法，后者能把女儿转成和母亲同一个 `CharacterConfig` 类型 | 严重：如果不接入女儿数据源，简报会重蹈项目历史上"B-class: DefaultCharacters.forEach 排除女儿"同一个坑（见本方案记忆里 6 月的 P0/P1/P2 修复记录） |
| `PregnancyRepository` 方法名 | `pregnancyRepo.get(config.id)`，判空写 `pregnancy?.isPregnant == true` | 实际方法是 **`getPregnancy(characterId: Int): PregnancyState`**，返回值非空（查不到记录时给默认值 `PregnancyState(characterId, isPregnant=false)`），不需要 `?.` | 编译不通过，需按 4.10.2 改 |
| `MenstrualCycleState.cyclePhase` 类型 | `BriefingCharacterEntry.cyclePhase: Any?` | 实际是 **`CyclePhase` 枚举**（`MENSTRUAL`/`SAFE`/`FERTILE`/`PREGNANT`），且 `currentPhase(isPregnant, now)` **必须传入 `isPregnant`** 才能拿到含"怀孕中"这一态的正确结果——不能脱离怀孕状态单独调用 | 类型可以直接钉死，不用留 `Any?` 占位；调用顺序有先后依赖（先查怀孕态，再传给周期计算） |
| 项目反查角色 | "按 characterId 反查需要在聚合层做一次 in-memory group" | `ProjectDao` 已有现成方法 **`getActiveProjectsForCharacter(characterId: String): List<ProjectEntity>`**（注意参数是 `String`），一次 SQL join 搞定，不需要拿全部项目在内存里 group | 可以省掉一层内存聚合逻辑，见 4.10.2 |
| `BondStage` 转换 | `BondStage.valueOf(entry.relation?.stage ?: "STRANGER")` | 能跑通，但这是"名字巧合相同"：数据层实际存的是另一个独立枚举 **`RelationshipStage`**（`RelationshipEntity.stage: String` 存的是 `RelationshipStage.name`），和 UI 层的 `BondStage` 是两个不同类型，只是五个值的拼写完全一样。**不要**改成 `RelationshipStage.valueOf(...).let { BondStage.valueOf(it.name) }` 这种多余写法，也不要引入 `RelationshipStage` 到 UI 层——原方案的写法本身没错，这里只是提醒不要"顺手重构成看起来更规范"的版本，两个枚举保持解耦是有意为之 | 无需改动，仅供实现时理解不要"修正"出错 |
| `AppContainer` 接入方式 | 示例代码里 `db.relationshipDao()` 等直接在 `briefingRepo` 构造处引用 | `AppContainer` 的 `db` 字段是 **`private val`**，只能在 `AppContainer.kt` 文件内部访问；4.6 节的示例代码本身是写在 `AppContainer.kt` 里的（符合），只是这里额外强调：不要把这段构造逻辑抽到别的文件里再引用 `db`，会编译不过 | 4.6 节代码位置本身没问题，仅作强调 |
| `menstrualCycleRepo` | 写了 `/* 若尚未持有，需要新增构造 */` 占位 | 确认 `AppContainer` 目前**没有** `menstrualCycleRepo` 字段，`AppDatabase` 已有 `menstrualCycleDao()`，需要真的新增一行 `val menstrualCycleRepo = MenstrualCycleRepository(db.menstrualCycleDao())` | 见 4.10.1 完整 `AppContainer` diff |
| `CompetitionRoundDao` 时间过滤查询 | 标注"待核实" | 已核实：`CompetitionRoundDao` 现有 `getAllPendingRounds()`（查未完成）、`observeAllForDomain()`、`observeRoundsAsJudge()`，**没有**按 `completedAt`/`status='COMPLETED'` 时间过滤的查询，确认需要新增 | 见 4.10.1 |

---

#### 4.10.1 数据层新增代码（可直接照抄，替换 4.3/4.4/4.6 里对应的占位/待核实部分）

**新增 DAO 方法 1** —— `RelationshipMilestoneDao.kt` 追加：

```kotlin
/** 取所有角色在某时间点之后的关系转折点，供离线简报聚合用。 */
@Query("""
    SELECT * FROM relationship_milestones
    WHERE createdAt >= :after
    ORDER BY createdAt DESC
""")
suspend fun getAllSince(after: Long): List<RelationshipMilestoneEntity>
```

**新增 DAO 方法 2** —— `TaskDao.kt` 追加：

```kotlin
/** 某角色在某时间点之后完成的任务，供离线简报统计"完成任务数"用。 */
@Query("""
    SELECT * FROM tasks
    WHERE characterId = :characterId
      AND status = 'COMPLETED'
      AND completedAt >= :since
    ORDER BY completedAt DESC
""")
suspend fun getCompletedByCharacterSince(characterId: Int, since: Long): List<TaskEntity>
```

**新增 DAO 方法 3** —— `CompetitionRoundDao.kt` 追加（已核实现有方法均不满足，确认需要新增）：

```kotlin
/** 某时间点之后已完成的竞赛轮次，供离线简报统计交付评分用。 */
@Query("""
    SELECT * FROM competition_rounds
    WHERE status = 'COMPLETED' AND completedAt >= :after
    ORDER BY completedAt DESC
""")
suspend fun getCompletedSince(after: Long): List<CompetitionRoundEntity>
```

**`AppContainer.kt` 完整 diff**（在现有 `daughterCharacterRepo` 字段之后追加，其余字段原样不动）：

```kotlin
    // 离线简报（Briefing）聚合层。只读，见整合方案 v2.1 第四节。
    val menstrualCycleRepo: MenstrualCycleRepository =
        MenstrualCycleRepository(db.menstrualCycleDao())

    val briefingRepo: BriefingRepository = BriefingRepository(
        relationshipDao          = db.relationshipDao(),
        relationshipMilestoneDao = db.relationshipMilestoneDao(),
        relationshipEngine       = relationshipEngine,
        pregnancyRepo            = pregnancyRepo,
        menstrualCycleRepo       = menstrualCycleRepo,
        projectDao               = db.projectDao(),
        taskDao                  = db.taskDao(),
        messageDao               = db.messageDao(),
        competitionRoundDao      = db.competitionRoundDao(),
        competitionEntryDao      = db.competitionEntryDao(),
        daughterCharacterRepo    = daughterCharacterRepo,
    )
```

需要在文件顶部追加两个 import：`MenstrualCycleRepository`、`BriefingRepository`（其余用到的类型都已在原文件 import 过）。

---

#### 4.10.2 `BriefingRepository.generateBriefing()` 完整实现（替换 4.5 节里带 `TODO()` 的版本）

```kotlin
package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.*
import com.zaijian.zhoumuyun.data.db.entity.*
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.CyclePhase
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.domain.RelationshipEngine
import kotlinx.coroutines.flow.first

/**
 * 离线简报聚合层。只读，不修改任何业务数据，每次生成简报时按需查询。
 * 角色范围 = 9 位母亲（已解锁）+ 全部已完成注册的女儿（1000+）。
 */
class BriefingRepository(
    private val relationshipDao: RelationshipDao,
    private val relationshipMilestoneDao: RelationshipMilestoneDao,
    private val relationshipEngine: RelationshipEngine,
    private val pregnancyRepo: PregnancyRepository,
    private val menstrualCycleRepo: MenstrualCycleRepository,
    private val projectDao: ProjectDao,
    private val taskDao: TaskDao,
    private val messageDao: MessageDao,
    private val competitionRoundDao: CompetitionRoundDao,
    private val competitionEntryDao: CompetitionEntryDao,
    private val daughterCharacterRepo: DaughterCharacterRepository,
) {
    suspend fun generateBriefing(since: Long, now: Long = System.currentTimeMillis()): BriefingData {
        // ── 角色范围：9 位母亲 + 全部已注册女儿 ──────────────────
        val mothers = DefaultCharacters.filter { it.isUnlocked }
        val daughterIds = daughterCharacterRepo.getAllDaughterCharacterIds()
        val daughters = daughterIds.mapNotNull { daughterCharacterRepo.getCharacterConfig(it) }
        val characters = mothers + daughters

        // ── 关系数据：一次性 collect Flow 首个值 ─────────────────
        val relations = relationshipDao.observeFrom("user").first()
        val relationByCharId = relations.associateBy { it.toId }

        val interMatrix = relationshipEngine.getInterCharacterMatrix(characters.map { it.id })
        val worsenedMilestones = relationshipMilestoneDao.getAllSince(since)
            .filter { it.direction == RelationshipMilestoneDirection.WORSENED.name }

        // ── 竞赛评分：先取本周期完成的轮次，再按角色反查条目 ──────
        val completedRounds = competitionRoundDao.getCompletedSince(since)
        val entriesByCharacter = mutableMapOf<Int, MutableList<CompetitionEntryEntity>>()
        completedRounds.forEach { round ->
            competitionEntryDao.getAllForRound(round.id).forEach { entry ->
                entriesByCharacter.getOrPut(entry.characterId) { mutableListOf() }.add(entry)
            }
        }

        val perCharacter = characters.map { config ->
            val relation = relationByCharId[config.id.toString()]
            val lastMessageAt = messageDao.getLastMessageAt(config.id)
            val pregnancy = pregnancyRepo.getPregnancy(config.id)
            val cyclePhase = menstrualCycleRepo.get(config.id)
                .currentPhase(isPregnant = pregnancy.isPregnant, now = now)
            val completedTasks = taskDao.getCompletedByCharacterSince(config.id, since)
            val projects = projectDao.getActiveProjectsForCharacter(config.id.toString())
            val entries = entriesByCharacter[config.id].orEmpty()
            val avgScore = entries.mapNotNull { it.compositeScore }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

            BriefingCharacterEntry(
                character          = config,
                relation           = relation,
                lastMessageAt      = lastMessageAt,
                daysSinceContact   = lastMessageAt?.let { (now - it) / 86_400_000L },
                isPregnant         = pregnancy.isPregnant,
                cyclePhase         = cyclePhase,
                completedTaskCount = completedTasks.size,
                projectNames       = projects.map { it.name },
                competitionScore   = avgScore,
            )
        }

        val attentionItems = buildAttentionList(perCharacter, interMatrix, worsenedMilestones)
        val ranking = perCharacter.sortedByDescending { it.relation?.affection ?: 0 }

        return BriefingData(
            periodStart      = since,
            periodEnd        = now,
            characters       = perCharacter,
            attentionItems   = attentionItems,
            affectionRanking = ranking,
        )
    }

    /** 「需要关注」判定逻辑集中在这一个函数，方便你后续单独调阈值，不用满文件找散落的 if。 */
    private fun buildAttentionList(
        entries: List<BriefingCharacterEntry>,
        interMatrix: Map<String, RelationshipEntity>,
        worsened: List<RelationshipMilestoneEntity>,
    ): List<BriefingAttentionItem> {
        val items = mutableListOf<BriefingAttentionItem>()

        val noContactThresholdDays = 7L
        val tensionThreshold       = 60

        entries.forEach { entry ->
            if ((entry.daysSinceContact ?: 0) >= noContactThresholdDays) {
                items += BriefingAttentionItem.NoContact(entry.character, entry.daysSinceContact!!)
            }
            if (entry.isPregnant) {
                items += BriefingAttentionItem.Pregnancy(entry.character)
            }
        }
        interMatrix.values.filter { it.tension >= tensionThreshold }.forEach { rel ->
            items += BriefingAttentionItem.Tension(rel.fromId, rel.toId, rel.tension)
        }
        worsened.forEach { m ->
            items += BriefingAttentionItem.RelationWorsened(m.fromId, m.toId, m.description)
        }
        return items
    }
}
```

**与 4.5 节旧版的差异**：
- `characters` 改为母亲+女儿合并列表（修正 4.10.0 第一条）
- `pregnancyRepo.get` → `pregnancyRepo.getPregnancy`，且不再需要 `?.`（修正第二条）
- `cyclePhase` 类型钉死为 `CyclePhase`，调用时显式传入 `isPregnant`（修正第三条）
- `projectNames` 改用 `projectDao.getActiveProjectsForCharacter(config.id.toString())` 直接拿，去掉内存 group（修正第四条）
- 补上了 `competitionScore` 的实际聚合逻辑（原方案该字段留空未实现）
- `relations` 从 Flow 换成 `.first()` 取一次性快照，避免在一次性聚合函数里持有长生命周期 Flow

**数据模型微调**（`BriefingCharacterEntry.cyclePhase` 类型收窄）：

```kotlin
data class BriefingCharacterEntry(
    val character: CharacterConfig,
    val relation: RelationshipEntity?,
    val lastMessageAt: Long?,
    val daysSinceContact: Long?,
    val isPregnant: Boolean,
    val cyclePhase: CyclePhase,   // 原方案是 Any? 占位，现钉死类型
    val completedTaskCount: Int,
    val projectNames: List<String> = emptyList(),
    val competitionScore: Float? = null,
)
```

---

#### 4.10.3 Compose 组件文件拆分（细化 4.8 节，明确每个文件的入参和职责边界）

沿用 4.8 节定的目录结构，以下把每个文件的函数签名钉死：

```
ui/screen/
├── BriefingScreen.kt
└── briefing/
    ├── BriefingIntroSection.kt       ← 新增：引导语区块单独抽出（4.8原方案是内联在item{}里，改抽为独立函数，理由见下）
    ├── BriefingAttentionSection.kt
    ├── BriefingCharacterCard.kt
    └── BriefingRankingSection.kt
```

把引导语从内联 `item { ... }` 改成独立小文件的理由：这段文案要做"X天"的时间换算（`periodStart`/`periodEnd` → 天数），逻辑不是纯 UI 摆放，独立出来方便单测，且与其余三个子文件的拆分粒度保持一致，不要出现"三个区块各自独立文件、唯一一段引导语却裸写在主文件"的不一致。

**`BriefingScreen.kt`**：

```kotlin
@Composable
fun BriefingScreen(
    onEnterWorld: () -> Unit,
    viewModel: BriefingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.isLoading || uiState.data == null) {
        // 加载态：复用项目里其余 Screen 已有的 loading 占位惯例
        // （参照 ProjectScreen/ProfileScreen 现有 loading Box 写法，不新建加载组件）
        return
    }
    val data = uiState.data!!

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { BriefingIntroSection(periodStart = data.periodStart, periodEnd = data.periodEnd) }

        if (data.attentionItems.isNotEmpty()) {
            item { BriefingAttentionSection(items = data.attentionItems) }
        }

        items(data.characters, key = { it.character.id }) { entry ->
            BriefingCharacterCard(
                entry = entry,
                modifier = Modifier.padding(horizontal = Spacing.screenPadding, vertical = Spacing.xs),
            )
        }

        item { BriefingRankingSection(ranking = data.affectionRanking) }

        item {
            Button(
                onClick = onEnterWorld,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.screenPadding),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Velvet),
            ) {
                Text("推门进入公馆")
            }
        }
    }
}
```

**`BriefingIntroSection.kt`**：

```kotlin
@Composable
fun BriefingIntroSection(periodStart: Long, periodEnd: Long, modifier: Modifier = Modifier) {
    val days = ((periodEnd - periodStart) / 86_400_000L).coerceAtLeast(0)
    val text = if (days == 0L) "你刚离开不久，公馆里一切如常" else "你离开的这 $days 天里，公馆发生了这些事"
    Text(
        text = text,
        style = ZaijianTheme.typography.titleM,
        modifier = modifier.padding(Spacing.screenPadding),
    )
}
```

**`BriefingCharacterCard.kt`**（对 4.8 节原版做两处修正：`isMilestone` 落实为真实判断而非硬编码 `false`；补上 `cyclePhase`/`competitionScore` 的展示）：

```kotlin
@Composable
fun BriefingCharacterCard(entry: BriefingCharacterEntry, modifier: Modifier = Modifier) {
    // isMilestone 判定：本周期内该角色是否有 REPAIRED 方向的转折点。
    // 数据源是 BriefingData.attentionItems 里的 RelationWorsened（只含变差），
    // REPAIRED 未落在 attentionItems 里（那是需要关注的坏消息，不是好消息），
    // 所以这里改为由调用方（BriefingScreen）在组装时一次性查好传入，
    // 避免每张卡片各自重新查一次 milestone。
    WorldCard(
        modifier    = modifier,
        ownerAccent = entry.character.accentColor,
        isMilestone = entry.hasRecentGoodMilestone,
    ) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            Text(entry.character.name, style = ZaijianTheme.typography.titleM)

            BondRibbon(
                stage       = BondStage.valueOf(entry.relation?.stage ?: "STRANGER"),
                accentColor = entry.character.accentColor,
                showLabels  = true,
                suppression = entry.relation?.suppression,
            )

            Text("完成任务 ${entry.completedTaskCount} 个", style = ZaijianTheme.typography.labelMono)

            if (entry.projectNames.isNotEmpty()) {
                Text("参与项目：${entry.projectNames.joinToString("、")}", style = ZaijianTheme.typography.labelMono)
            }

            entry.competitionScore?.let { score ->
                Text("最近评分 ${"%.1f".format(score)}", style = ZaijianTheme.typography.labelMono)
            }

            if (entry.isPregnant) {
                InfoChip(text = "怀孕中", color = Palette.SemanticReminder)
            } else if (entry.cyclePhase == CyclePhase.FERTILE) {
                InfoChip(text = "排卵期", color = Palette.SemanticReminder)
            }
        }
    }
}
```

对应地，`BriefingCharacterEntry` 需要再加一个字段（在 `generateBriefing` 里用 `worsenedMilestones` 同批数据算出"是否有 REPAIRED"，避免卡片组件自己再查一次库）：

```kotlin
data class BriefingCharacterEntry(
    // ...原字段不变...
    val hasRecentGoodMilestone: Boolean = false,
)
```

对应 `generateBriefing()` 里在算 `worsenedMilestones` 那一段旁边，多查一次 `REPAIRED` 方向并按角色 group：

```kotlin
val repairedMilestones = relationshipMilestoneDao.getAllSince(since)
    .filter { it.direction == RelationshipMilestoneDirection.REPAIRED.name }
val repairedCharIds = repairedMilestones.map { it.toId }.toSet()
// 组装 perCharacter 时：hasRecentGoodMilestone = config.id.toString() in repairedCharIds
```

**`BriefingAttentionSection.kt`**（钉死四种文案格式，避免实现者自己发挥导致语气跳出"公馆语言"）：

```kotlin
@Composable
fun BriefingAttentionSection(items: List<BriefingAttentionItem>, modifier: Modifier = Modifier) {
    WorldCard(modifier = modifier, isMilestone = true) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            Text("需要关注", style = ZaijianTheme.typography.titleM, color = Palette.Velvet)
            items.forEach { item ->
                val text = when (item) {
                    is BriefingAttentionItem.NoContact ->
                        "${item.character.name}：已经 ${item.days} 天没有联系了"
                    is BriefingAttentionItem.Pregnancy ->
                        "${item.character.name}：怀孕中，记得多关心"
                    is BriefingAttentionItem.Tension -> {
                        val fromName = characterNameById(item.fromId)
                        val toName = characterNameById(item.toId)
                        "$fromName 和 $toName：关系紧张度较高（${item.tension}）"
                    }
                    is BriefingAttentionItem.RelationWorsened ->
                        "${characterNameById(item.fromId)}：${item.description}"
                }
                Text(text, style = ZaijianTheme.typography.bodyM, color = Palette.VelvetSoft)
            }
        }
    }
}

/** fromId/toId 是字符串形式的角色ID，这里统一转名字，找不到时兜底显示原始ID。 */
private fun characterNameById(id: String): String =
    (DefaultCharacters.firstOrNull { it.id.toString() == id })?.name ?: id
```

`characterNameById` 目前只查了 `DefaultCharacters`（9 位母亲），没查女儿——如果 `Tension`/`RelationWorsened` 涉及女儿角色间的紧张关系，会显示成裸 ID 而不是名字。这里先按最小实现处理（母亲之间的紧张关系是目前 Bot↔Bot 互动的主要场景），女儿间互动如果后续接入圆桌，需要把这个函数改成挂起函数去查 `daughterCharacterRepo`，本方案不代为实现，留给你判断是否现在就需要。

**`BriefingRankingSection.kt`**：

```kotlin
@Composable
fun BriefingRankingSection(ranking: List<BriefingCharacterEntry>, modifier: Modifier = Modifier) {
    WorldCard(modifier = modifier) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            Text("亲密度排行", style = ZaijianTheme.typography.titleM)
            ranking.take(5).forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${index + 1}. ${entry.character.name}", style = ZaijianTheme.typography.bodyM)
                    Text("${entry.relation?.affection ?: 0}", style = ZaijianTheme.typography.labelMono)
                }
            }
        }
    }
}
```

---

#### 4.10.4 施工顺序建议（细化原 4.9 节）

1. 先落 4.10.1 的 DAO 新增方法 + `AppContainer` diff（编译验证：三个新查询方法能跑通即可，不用等 UI）
2. 落 `BriefingRepository`（4.10.2），可以先写个简单单测或临时在某个已有 Screen 里打日志验证 `generateBriefing()` 返回的数据是否符合预期，再动 UI
3. `BriefingDataStore`（4.4 节原方案）+ `BriefingViewModel`（4.7 节原方案）不变，可与第 2 步并行
4. `ui/screen/briefing/` 四个子文件（4.10.3）→ `BriefingScreen.kt` 组装
5. 最后接导航（4.2 节原方案不变）

第 1-3 步是纯数据层，出问题容易定位；第 4-5 步纯 UI 组装，前面数据没问题的话这一步基本是照抄粘贴。

---

## 五、暂缓事项（涉及产品/架构决策，不属于视觉整合范围）

以下内容在 ChatGPT 素材中出现，但核实后发现涉及**产品功能或数据架构决策**，而非单纯视觉问题，本方案不代为决定，留待你后续单独评估：

| 事项 | 现状 | 需要你决定的问题 |
|---|---|---|
| 独立 Memory 全局页面 | 现在 Memory 只是角色详情页内的 `CharacterDetailMemory`（单角色维度），无全局入口 | 是否需要"跨角色全局记忆检索"功能？这决定要不要做独立页面 |
| 独立 Settings 页面 | 现在 Settings 是 Profile 页面内的 `SettingGroup` 区块，非独立 Screen | 现有设置项是否已经多到难以维护？如果没有，不必现在拆分 |
| Project 层级重构（Sprint/SubTask） | 现有 `TaskEntity`/`ProjectEntity`/`ProjectMilestoneEntity` 已构成"Task/Goal/Project 三层"，与 ChatGPT 提议的"Project→Milestone→Task→SubTask 四层+Sprint"不同 | 是否需要引入 SubTask 这一级？Sprint/甘特图/燃尽图这类敏捷开发工具是否真的贴合角色养成类 App 的产品调性？ |
| Dialog 页面的调试信息（Token/Prompt/Memory注入可视化） | 现有 `ChatScreen` 已模块化（Header/InputBar/MessageBubble/SettingsSheet/EvaluationCard） | 这类信息更适合做成仅开发模式可见的悬浮调试面板，还是要做进正式用户可见 UI？ |

---

## 六、ChatGPT 十份文档去留总表（供追溯依据）

| Part | 主题 | 判断 | 说明 |
|---|---|---|---|
| 01 | Design System | 弃 | 与现有 `theme/` 包重复，图标统一思路已并入本方案 2.4 |
| 02 | Home 重构 | 弃 | 项目无 Home 页，架构假设不成立 |
| 03 | World 重构 | 弃，改为独立设计 | 见本方案第四节"离线简报" |
| 04 | Character OS | 弃 | `CharacterDetailScreen` 已模块化，覆盖诉求 |
| 05 | Dialog OS | 弃 | Token/Prompt 可视化列入第五节待决策事项 |
| 06 | Task/Mission OS | 部分弃 | 与 Part12 矛盾，视觉层面已并入本方案 2.1 |
| 07 | Profile/Personal OS | 弃 | ProfileScreen 已是内容丰富的 LazyColumn |
| 08 | Settings/System OS | 弃 | 列入第五节待决策事项 |
| 09 | Memory OS | 弃 | 列入第五节待决策事项 |
| 12 | Project OS | 弃 | Sprint/甘特图/燃尽图不适配产品调性 |
| 21/22/23/24 | 组件库/图标/动画/Token | 整体弃，提炼一条任务 | 已并入本方案 2.4、2.5 |
