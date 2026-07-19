# S8 窗口07 修复：Goal 链路4处功能缺口打通

> 对应 `zaijian_s8_window07_goal_audit.md`。窗口07报告7条主结论中，
> 结论1/4/6/7（新增/删除/DI收敛/Tier2自动推进）代码本身无问题，
> 不需要动。结论2（更新进度无UI入口）、结论3（停用无UI入口）、
> 结论5（ProjectEntity.goalId反向关联未接入），以及复核阶段的
> 新发现1（编辑弹窗无法调整进度）、新发现2（删除无二次确认），
> 这五处本轮全部打通。

## 结论2 + 新发现1：进度调整——两个入口都补上

原报告发现进度更新的 ViewModel→Repo→DAO 链路正确，但 UI 层完全没有
触发路径；复核阶段进一步发现编辑弹窗（`GoalDraftSheet`）里也没有
进度字段。这两处根因不同，分别处理：

### 新发现1：编辑弹窗补进度字段（同时修了一个静默数据丢失 bug）

`GoalViewModel.saveDraft()` 构造 `CharacterGoalEntity` 时从未传
`progress`，而实体类默认值是 `0f`——**每次编辑保存都会把已有进度
悄悄清零**，用户毫无感知。修复：

- `GoalDraft` 新增 `progress: Float = 0f`
- `openEditDraft()` 从 `goal.progress` 回填
- 新增 `onDraftProgressChange()`（`coerceIn(0f, 1f)`，与其余
  `onDraft*Change` 系列同一模式）
- `saveDraft()` 补上 `progress = d.progress`
- `GoalDraftSheet` 在"优先级"和"关联项目"之间新增进度区块：
  百分比文字 + `Slider`（配色用 `accentColor`，与卡片进度条呼应）

### 结论2：GoalCard 上的进度条本身也做成可交互

编辑弹窗里调整进度需要先点"编辑"进弹窗，路径较长；同时报告
明确指出"用户无法通过UI主动调整进度"是常见触发场景。为了让
`GoalCard` 直接可操作，把原先纯展示的 `drawBehind` 进度条包了一层
`pointerInput`：

- 外层加一个 24.dp 高的透明触摸区（原视觉高度仍是 4.dp，只是扩大
  命中范围，避免细长进度条不好点中）
- `detectTapGestures` 处理点按跳转到指定进度
- `detectDragGestures` 处理拖拽调整，`change.consume()` 防止手势
  被外层 `Column.clickable { onEdit() }` 抢走
- 触摸位置换算用 `drawBehind` 里读到的 `size.width` 缓存到
  `barWidthPx`（`remember { mutableStateOf(0f) }`），未采用
  `onSizeChanged` 是因为 `drawBehind` 本身已经拿得到宽度，不用
  再加一层 modifier
- 原有的渐变绘制逻辑（角色色→Gold）完全不变，只是叠加了交互层

两个入口都调用同一个 `GoalViewModel.updateProgress()` /
`onDraftProgressChange()`，不会互相冲突——卡片上的滑动是"即时生效"
（直接调 `onProgressChange` 走 DB 更新），编辑弹窗里的是"草稿态"
（要点保存才落库），语义符合各自入口的心智模型。

## 结论3：GoalPanel 收到了 onDeactivate 却从未下传

`GoalPanel` 函数签名里一直有 `onDeactivate: (String) -> Unit` 参数，
`CharacterDetailScreen.kt` 也正确绑定了 `goalViewModel::deactivate`，
但 `GoalPanel` 函数体内从未使用这个参数，`GoalCard` 操作行也只有
"编辑"和"删除"。修复：

- `GoalCard` 新增 `onDeactivate: () -> Unit` 参数
- 操作行在"编辑"和"删除"之间插入"停用"按钮
- `GoalPanel` 调用 `GoalCard` 时补上
  `onDeactivate = { onDeactivate(goal.id) }`

"停用"不弹确认框——它是 `isActive = 0`（软操作，数据不丢，随时可以
在数据层面恢复），跟"删除"的物理 DELETE 风险不对等，不需要跟删除
一样的二次确认摩擦。

## 新发现2：GoalCard 删除按钮无二次确认

`CharacterGoalDao.delete()` 是物理 DELETE，误触无法恢复。`GoalCard`
新增 `showDeleteConfirm` 状态 + `AlertDialog`：点击"删除"先弹确认
（标题"删除目标"，正文带上目标标题），确认后才真正调用
`onDelete()`。按钮/配色沿用 `CommonDialogs.kt` 里
`OptionPickerDialog` 的既有写法，未提炼成新组件（目前只有一处用）。

## 结论5：ProjectEntity.goalId 反向关联补全

`goalId` 字段自 Migration 41→52 加入 schema 后，一直没有代码读写。
`CharacterGoalEntity.relatedProjectId`（Goal→Project 正向）早已接入，
但反向查询"这个项目挂在哪个目标下"始终缺失。修复：

### DAO / Repository 层

`ProjectDao` 新增：
- `setGoalId(id, goalId, now)` — `UPDATE projects SET goalId = ...`
- `getByGoalId(goalId)` — 反查某目标当前挂载的项目，用于换绑/解绑时
  先找到旧项目

`ProjectRepository` 透传上述两个方法。

### 写入时机：GoalViewModel.saveDraft()

用户在目标编辑弹窗选"关联项目"（写 `relatedProjectId`）的同一次
保存里，同步维护反向链接：

1. 先查这个 goalId 之前有没有挂在别的项目下（`getByGoalId`）
2. 如果挂的项目和这次选的不一样，先把旧项目的 `goalId` 清空
   （避免一个项目的 `goalId` 指向一个已经改挂别处的目标——脏反向
   链接）
3. 如果这次选了新项目，把新项目的 `goalId` 设成这个目标的 id

### 删除时的清理：GoalViewModel.delete()

`delete()` 是物理 DELETE，如果被删的目标有反向链接的项目，删除前
先解绑该项目的 `goalId`，避免留下指向不存在目标的悬空引用。
`deactivate()` 不做同样处理——它只是 `isActive = 0`，目标记录本身
还在，反向链接依然指向一个真实存在的目标，不需要解绑。

### 未做的部分

审查报告原文明确指出"UI 上不存在展示该字段的误导性入口"——即
`ProjectEntity.goalId` 缺失只是数据层未接入，不是"UI 展示了却读不到
数据"那类问题。本轮只打通数据层的读写（DAO + Repository +
ViewModel 写入时机），没有在 `ProjectDetailScreen` 或
`ProjectScreen` 加"关联目标"展示卡片——那需要额外查询目标标题、
跨 Repository 拼装 UI 数据，属于新增功能而非修复缺口，界面展示
如果需要可以单独排期。

## 改动文件

- `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/GoalViewModel.kt`
  - `GoalDraft` 新增 `progress` 字段
  - `openEditDraft()` 回填 `progress`
  - 新增 `onDraftProgressChange()`
  - `saveDraft()` 补上 `progress = d.progress`，并同步维护
    `ProjectEntity.goalId` 反向链接
  - `delete()` 删除前解绑反向链接的项目
- `app/src/main/java/com/zaijian/zhoumuyun/ui/screen/characterdetail/CharacterDetailGoal.kt`
  - `GoalDraftSheet` 新增 `onProgressChange` 参数 + 进度 Slider 区块
  - `GoalCard` 新增 `onDeactivate` 参数 + "停用"操作按钮
  - `GoalCard` 进度条改为可点按/拖拽交互（视觉不变）
  - `GoalCard` 新增删除二次确认 `AlertDialog`
  - `GoalPanel` 调用 `GoalCard` 时补上 `onDeactivate` 绑定
- `app/src/main/java/com/zaijian/zhoumuyun/ui/screen/characterdetail/CharacterDetailScreen.kt`
  - `GoalDraftSheet` 调用处补上 `onProgressChange` 绑定
- `app/src/main/java/com/zaijian/zhoumuyun/data/db/dao/ProjectDao.kt`
  - 新增 `setGoalId()`、`getByGoalId()`
- `app/src/main/java/com/zaijian/zhoumuyun/data/repository/ProjectRepository.kt`
  - 新增 `setGoalId()`、`getByGoalId()` 透传

## 未处理项

窗口07报告结论1（新增目标链路完整）、结论4（删除目标链路完整）、
结论6（GoalViewModel DI 已收敛）、结论7（WorldSimulation Tier2
自动推进正确）——代码本身无问题，本轮未动。
