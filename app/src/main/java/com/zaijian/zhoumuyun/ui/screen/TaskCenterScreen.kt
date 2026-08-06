package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle // P1-11-2
import coil.compose.AsyncImage
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskStatus
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
import com.zaijian.zhoumuyun.ui.component.RootTabTopBar
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.DangerVelvetButton
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.GoldPillSegmentedControl
import com.zaijian.zhoumuyun.ui.design.GridTabItem
import com.zaijian.zhoumuyun.ui.design.SecondaryGoldButton
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.*
import com.zaijian.zhoumuyun.ui.viewmodel.TaskViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.TodayJobUiItem
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.util.safeAnimateScrollToItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

// ─────────────────────────────────────────────────────────────
//  TaskCenterScreen（Phase 19 升级版）
//
//  接入 TaskViewModel，使用真实 DB 数据替换 sampleTasks。
//  顶部 Tab：进行中 X / 已完成 X / 失败 X
//  每条任务卡片：角色头像 + 角色名·任务名 + 进度条 + 状态标签 + 工具名 + 时间戳
//  长按任务 → 确认删除 Dialog
// ─────────────────────────────────────────────────────────────

@Composable
fun TaskCenterScreen(
    onNavigateToProjects:  () -> Unit = {},
    onNavigateToSchedule:  () -> Unit = {},
    /** 深链接携带的 jobId，非空时高亮定位对应任务（Fix：之前传入但无消费者） */
    pendingJobId:          String? = null,
    onPendingJobIdConsumed: () -> Unit = {},
    viewModel: TaskViewModel = viewModel(),
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // UI S4 修复：Tab 选中位置在进程死亡后应能恢复，改用 rememberSaveable
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // UI 升级 v2.0 融合方案帧12：IA 合并——事务 Tab 三段（任务/日程/项目）
    // GoldPillSegmentedControl 驱动，0=任务（默认）/1=日程/2=项目。
    // 日程/项目原先各自独立路由，现收编为页内段，保留 onNavigateToSchedule/
    // onNavigateToProjects 作为「查看全部」深链入口。
    var taskSegment by rememberSaveable { mutableIntStateOf(0) }
    var taskToDelete by remember { mutableStateOf<TaskEntity?>(null) }

    // Fix-pendingJobId：深链接跳入时高亮对应任务，消费后清空避免重复触发
    LaunchedEffect(pendingJobId) {
        val jobId = pendingJobId ?: return@LaunchedEffect
        viewModel.highlightTask(jobId)
        onPendingJobIdConsumed()
    }

    // BUG-7 修复：头像覆盖表改从 ViewModel uiState 读取，不再直连 AppDatabase。
    val avatarOverrides = uiState.avatarOverrides

    // 四个 Tab 各自独立的滚动 state，避免 tab 切换时滚动位置互相污染
    val todayListState     = rememberLazyListState()  // Tab 0：今日
    val activeListState    = rememberLazyListState()  // Tab 1：进行中
    val completedListState = rememberLazyListState()  // Tab 2：已完成
    val failedListState    = rememberLazyListState()  // Tab 3：失败

    // ── highlightedTaskId 滚动定位 ─────────────────────────────
    // M-8 修复：原实现拆成两个独立 LaunchedEffect（确定 Tab / 切换后滚动），
    // 当 Phase1 把 selectedTab 设为与当前值相同的值时（例如默认就是 0），
    // Phase2 的 key 不变，不会重新触发，导致高亮滚动可能失效。
    // 现合并为同一协程内顺序执行：确定目标 Tab → 用 snapshotFlow 等待
    // selectedTab 真正变为目标值（重组完成）→ 滚动 → 延迟 → 清空。
    // 单协程顺序执行从根本上消除了"两个 effect 各自独立触发"的竞态。
    LaunchedEffect(uiState.highlightedTaskId, uiState.todayJobs.isEmpty(), uiState.activeTasks.isEmpty()) {
        val targetId = uiState.highlightedTaskId ?: return@LaunchedEffect

        val targetTab = when {
            uiState.todayJobs.any { it.job.id == targetId }   -> 0
            uiState.activeTasks.any { it.id == targetId }     -> 1
            !uiState.isLoading -> {
                // 两张表都找不到，且数据已加载完成：目标任务不存在，直接清空
                viewModel.clearHighlightedTask()
                return@LaunchedEffect
            }
            else -> return@LaunchedEffect  // 仍在加载，等数据就绪后 key 变化自动重跑
        }

        if (selectedTab != targetTab) {
            selectedTab = targetTab
            // 等待 selectedTab 状态真正更新（Compose 重组是异步的），
            // 避免在 Tab 切换完成前就读取旧 Tab 对应的 LazyListState 滚动。
            snapshotFlow { selectedTab }.first { it == targetTab }
        }

        when (targetTab) {
            0 -> {
                // U-9 修复：通过纯函数 buildTodayListItems 统一建模列表序列，
                // 渲染与求索引共用同一数据源，消除跨段耦合的硬编码绝对索引。
                val listItems = buildTodayListItems(uiState.todayGrowthTasks, uiState.todayJobs)
                val absoluteIdx = listItems.indexOfFirst {
                    it is TodayListItem.ScheduledJob && it.item.job.id == targetId
                }
                // P1 崩溃修复：改用 safeAnimateScrollToItem，理由同 ChatScreen.kt。
                if (absoluteIdx >= 0) {
                    todayListState.safeAnimateScrollToItem(absoluteIdx, tag = "TaskCenterScreen")
                }
            }
            1 -> {
                val idx = uiState.activeTasks.indexOfFirst { it.id == targetId }
                if (idx >= 0) activeListState.safeAnimateScrollToItem(idx, tag = "TaskCenterScreen")
            }
        }
        delay(1200)  // 让高亮边框对用户可见足够长的时间
        viewModel.clearHighlightedTask()
    }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    val activeTasks    = uiState.activeTasks
    val completedTasks = uiState.completedTasks
    val failedTasks    = uiState.failedTasks
    val todayJobs      = uiState.todayJobs        // Phase 30 方案四
    val todayGrowthTasks = uiState.todayGrowthTasks  // P1-A

    // GridTabBar 接入（精修方案 v1.3 第6节）：原先数字硬编码拼进字符串
    // （如 "进行中 ${activeTasks.size}"），文字和数字混排在一起没有视觉区分；
    // 改成 GridTabItem(label, count) 结构化后，count 由 GridTabBar 内部
    // 单独套等宽字体 labelMono 渲染，文字与数字区分开来。
    // 「今日」Tab 没有对应的计数语义（不是「今日任务数」，今日 Tab 混合展示
    // 日程任务+成长任务两类内容，没有单一计数能代表它），保持 count = null。
    val tabItems = listOf(
        GridTabItem(label = "今日"),
        GridTabItem(label = "进行中", count = activeTasks.size),
        GridTabItem(label = "已完成", count = completedTasks.size),
        GridTabItem(label = "失败",   count = failedTasks.size),
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.bgBase,
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // 窗口4补充：统一为 RootTabTopBar（无返回箭头的根Tab页面）
            RootTabTopBar(
                title    = "任务",
                headerBg = colors.bgBase,
            )

            // ── UI 升级 v2.0 融合方案帧12：IA 合并 GoldPillSegmentedControl ──
            // 事务 Tab 三段（任务/日程/项目），日程/项目从独立路由收编为页内段。
            // GoldPillSegmentedControl 驱动视图切换，GridTabBar 是任务段内的筛选标签。
            GoldPillSegmentedControl(
                items         = listOf("任务", "日程", "项目"),
                selectedIndex = taskSegment,
                onSelect      = { taskSegment = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(top = Spacing.sm),
            )

            // ── 今日日程摘要卡（常驻：任务段内始终显示）──────────
            // 融合方案帧12：「今日日程摘要卡常驻」——任务段内无论 Tab 在哪个
            // 子分类，摘要卡始终在顶部展示今日待办总数，一眼可知今天还有多少事。
            if (taskSegment == 0) {
                TodayScheduleSummaryCard(
                    todayJobCount        = todayJobs.size,
                    todayGrowthCount     = todayGrowthTasks.size,
                    onNavigateToSchedule = onNavigateToSchedule,
                )

            // ── Tab 栏 ─────────────────────────────────────────
            com.zaijian.zhoumuyun.ui.design.GridTabBar(
                items         = tabItems,
                selectedIndex = selectedTab,
                onSelect      = { selectedTab = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(top = Spacing.sm, bottom = Spacing.sm),
            )

            HorizontalDivider(color = colors.border, thickness = 0.5.dp)

            // ── 任务列表 ──────────────────────────────────────
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(32.dp))
                }
            } else if (selectedTab == 0) {
                // P1-A：今日分组视图（成长任务 + 定时任务）
                if (todayGrowthTasks.isEmpty() && todayJobs.isEmpty()) {
                    TodayEmptyHint()
                } else {
                    TodayGroupedView(
                        growthTasks        = todayGrowthTasks,
                        scheduledJobs      = todayJobs,
                        avatarOverrides    = avatarOverrides,
                        onToggleGrowthTask = { taskId -> viewModel.toggleGrowthTask(taskId) },
                        highlightedJobId   = uiState.highlightedTaskId,
                        characterMap       = uiState.characterMap,
                        state              = todayListState,
                        modifier           = Modifier.fillMaxSize(),
                    )
                }
            } else {
                val displayedTasks = when (selectedTab) {
                    1    -> activeTasks
                    2    -> completedTasks
                    else -> failedTasks
                }
                if (displayedTasks.isEmpty()) {
                    EmptyTasksHint(selectedTab = selectedTab - 1)
                } else {
                    LazyColumn(
                        state = when (selectedTab) {
                            1    -> activeListState
                            2    -> completedListState
                            else -> failedListState
                            // Tab 0 今日由 TodayGroupedView 内部自持 todayListState
                        },
                        contentPadding = PaddingValues(
                            top    = Spacing.sm,
                            // [v44 修复] 改用 LocalBottomBarHeight（唯一权威来源，见
                            // AppNavigation.kt 定义处说明），不再自己重新读取
                            // WindowInsets.navigationBars 计算——避免多处口径不一致
                            // 导致内容仍能滚动进导航栏物理区域。
                            bottom = LocalBottomBarHeight.current + Spacing.md,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(displayedTasks, key = { it.id }) { task ->
                            TaskCard(
                                task            = task,
                                onDelete        = { taskToDelete = task },
                                avatarOverrides = avatarOverrides,
                                isHighlighted   = task.id == uiState.highlightedTaskId,
                                characterMap    = uiState.characterMap, // 批次3 3-7修复
                                modifier        = Modifier.padding(horizontal = Spacing.screenHorizontal),
                            )
                        }
                    }
                }
            }
            } else if (taskSegment == 1) {
                // ── 日程段：今日待办明细 + 查看全部日程 ──────────────
                TodayScheduleSegment(
                    todayJobs           = todayJobs,
                    todayGrowthTasks    = todayGrowthTasks,
                    characterMap        = uiState.characterMap,
                    onNavigateToSchedule = onNavigateToSchedule,
                )
            } else {
                // ── 项目段：进行中项目预览 + 查看全部项目 ─────────────
                TodayProjectSegment(
                    activeProjectCount   = uiState.activeProjectCount,
                    completionRate       = uiState.latestProjectCompletionRate,
                    onNavigateToProjects = onNavigateToProjects,
                )
            }
        }
    }

    // ── 取消/删除确认 Dialog（审查项 2.16）───────────────────────
    // PENDING/RUNNING 状态下按钮语义是「取消任务」，调 cancelTask；
    // 其余终态语义是「删除任务」，调 deleteTask。两者都需二次确认，
    // 取消同样会改变任务的最终结局（写入 CANCELLED 状态），不应无确认直接执行。
    taskToDelete?.let { task ->
        val isCancelAction = task.status == TaskStatus.PENDING.name ||
            task.status == TaskStatus.RUNNING.name
        AlertDialog(
            onDismissRequest  = { taskToDelete = null },
            containerColor    = ZaijianTheme.colors.bgCard,
            title             = {
                Text(
                    if (isCancelAction) "取消任务" else "删除任务",
                    color = ZaijianTheme.colors.textPrimary,
                )
            },
            text              = {
                Text(
                    if (isCancelAction)
                        "确认取消「${task.title}」？任务将停止执行，此操作不可撤销。"
                    else
                        "确认删除「${task.title}」？此操作不可撤销。",
                    color = ZaijianTheme.colors.textSecondary,
                    style = ZaijianTheme.typography.body,
                )
            },
            confirmButton     = {
                DangerVelvetButton(
                    text = if (isCancelAction) "取消任务" else "删除",
                    onClick = {
                        if (isCancelAction) {
                            viewModel.cancelTask(task.id)
                        } else {
                            viewModel.deleteTask(task.id)
                        }
                        taskToDelete = null
                    },
                )
            },
            dismissButton     = {
                GhostGoldButton(text = "再想想", onClick = { taskToDelete = null })
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  TaskCard
// ─────────────────────────────────────────────────────────────

@Composable
private fun TaskCard(
    task: TaskEntity,
    onDelete: () -> Unit,
    avatarOverrides: Map<Int, String> = emptyMap(),
    isHighlighted: Boolean = false,
    // 批次3 3-7修复：接收 characterMap 替代 DefaultCharacters 查找，支持女儿角色
    characterMap: Map<Int, CharacterConfig> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    val character = characterMap[task.characterId]

    // WorldCard 接入（精修方案 v1.3 第2/6节）：L0-L2 常态层由 WorldCard 内部承担，
    // L3 身份脊（ownerAccent）取该任务归属角色的 accentColor，未关联角色时不显示。
    // 高亮边框（深链接滚动定位用，animateScrollToItem 完成后由 clearHighlightedTask 消除）
    // 不是 WorldCard 的内置层，WorldCard 自身已经画了 L2 黄铜描边，这里在外层叠加一层
    // 可选高亮描边，平时（isHighlighted = false）不画，不与 L2 冲突。
    WorldCard(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isHighlighted)
                    Modifier.border(1.5.dp, colors.accent, RoundedCornerShape(Radius.md))
                else Modifier
            ),
        ownerAccent = character?.accentColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.cardPadding),
            verticalAlignment = Alignment.Top,
        ) {
            // 角色头像
            val avatarUrl = avatarOverrides[task.characterId]?.takeIf { it.isNotBlank() }
                ?: character?.avatarUrl
            if (avatarUrl != null) {
                AsyncImage(
                    model             = avatarUrl,
                    contentDescription = character?.name,
                    modifier          = Modifier
                        .size(AvatarSize.chat)
                        .clip(CircleShape)
                        .background((character?.accentColor ?: colors.accent).copy(alpha = 0.15f)),
                )
            } else {
                Box(
                    modifier          = Modifier
                        .size(AvatarSize.chat)
                        .clip(CircleShape)
                        .background(colors.bgElevated),
                    contentAlignment  = Alignment.Center,
                ) {
                    Text(
                        text  = "?",
                        style = type.label,
                        color = colors.textSecondary,
                    )
                }
            }

            Spacer(Modifier.width(Spacing.sm))

            Column(modifier = Modifier.weight(1f)) {
                // 角色名 · 任务名
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text  = character?.name ?: "未知",
                        style = type.label.copy(fontWeight = FontWeight.Bold),
                        color = character?.accentColor ?: colors.accent,
                    )
                    Text(
                        text  = " · ",
                        style = type.label,
                        color = colors.textDisabled,
                    )
                    Text(
                        text  = task.title,
                        style = type.label.copy(fontWeight = FontWeight.Medium),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(Spacing.xs))

                // 工具名标签 + 状态标签
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    task.toolName?.let { tool ->
                        TaskChip(
                            text  = tool,
                            color = colors.accent.copy(alpha = 0.15f),
                            textColor = colors.accent,
                        )
                    }
                    TaskStatusChip(status = task.status)
                }

                // 进度条（仅 RUNNING 显示）
                if (task.status == TaskStatus.RUNNING.name) {
                    Spacer(Modifier.height(Spacing.xs))
                    LinearProgressIndicator(
                        progress        = { task.progress },
                        modifier        = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color           = colors.accent,
                        trackColor      = colors.bgElevated,
                        strokeCap       = StrokeCap.Round,
                    )
                }

                // 结果摘要（已完成 / 失败时显示）
                task.resultSummary?.let { result ->
                    if (task.status != TaskStatus.RUNNING.name) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text     = result,
                            style    = type.small,
                            color    = colors.textSecondary,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.xs))

                // 时间戳
                Text(
                    text  = formatTimestamp(task.createdAt),
                    style = type.small,
                    color = colors.textDisabled,
                )
            }

            // 操作按钮（审查项 2.16）：PENDING/RUNNING 状态下任务尚未有最终结果，
            // 此时该按钮语义为「取消」（调 cancelTask，写 CANCELLED 状态 + TASK_CANCELLED 事件）；
            // 其余终态（COMPLETED/FAILED/CANCELLED）下语义为「删除」（调 deleteTask）。
            // 同一入口按状态切换，避免卡片上同时挂两个操作按钮。
            val isCancellable = task.status == TaskStatus.PENDING.name ||
                task.status == TaskStatus.RUNNING.name
            // P3-33 修复：触摸目标 < 48dp，移除 IconButton 的 size 限制，让 minimumInteractiveComponentSize 生效
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector        = if (isCancellable) AppIcons.Cancel else AppIcons.DeleteOutline,
                    contentDescription = if (isCancellable) "取消任务" else "删除任务",
                    modifier           = Modifier.size(16.dp),
                    tint               = colors.textDisabled,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  TaskStatusChip
// ─────────────────────────────────────────────────────────────

@Composable
private fun TaskStatusChip(status: String) {
    val colors = ZaijianTheme.colors
    val (label, bgColor, textColor) = when (status) {
        TaskStatus.RUNNING.name   -> Triple("进行中",   colors.accent.copy(alpha = 0.10f), colors.accent)
        TaskStatus.PENDING.name   -> Triple("等待中",   colors.bgElevated,                 colors.textSecondary)
        // UI M11 修复：原硬编码 Color(0xFF4CAF50)/Color(0xFFE57373) 绕过主题系统，
        // 改引用 AppColors.kt 已定义的 Palette.SemanticSuccess/SemanticDanger
        // （同色值，零视觉差异，仅集中管理，后续统一调色只需改一处）。
        TaskStatus.COMPLETED.name -> Triple("已完成",   Palette.SemanticSuccess.copy(alpha = 0.12f), Palette.SemanticSuccess)
        TaskStatus.FAILED.name    -> Triple("失败",     Palette.SemanticDanger.copy(alpha = 0.12f), Palette.SemanticDanger)
        TaskStatus.CANCELLED.name -> Triple("已取消",   colors.bgElevated,                 colors.textDisabled)
        else                      -> Triple(status,    colors.bgElevated,                 colors.textSecondary)
    }
    TaskChip(text = label, color = bgColor, textColor = textColor)
}

@Composable
private fun TaskChip(
    text: String,
    color: Color,
    textColor: Color,
) {
    val type = ZaijianTheme.typography
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text  = text,
            style = type.small.copy(fontWeight = FontWeight.Medium),
            color = textColor,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  EmptyTasksHint
// ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyTasksHint(selectedTab: Int) {
    // D-3 P3：空状态收口至统一组件 EmptyStateView，图标收口至 AppIcons。
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (selectedTab) {
            0    -> EmptyStateView(
                icon     = AppIcons.Bolt,
                title    = "还没有进行中的任务",
                subtitle = "对角色说「帮我……」开始你的第一个任务",
            )
            1    -> EmptyStateView(icon = AppIcons.CheckCircle, title = "还没有已完成的任务")
            else -> EmptyStateView(icon = AppIcons.ErrorOutline, title = "还没有失败的任务")
        }
    }
}


// ─────────────────────────────────────────────────────────────
//  TodayEmptyHint — 今日 Tab 全空状态（P3-A）
// ─────────────────────────────────────────────────────────────

@Composable
private fun TodayEmptyHint() {
    // D-3 P3：空状态收口至统一组件 EmptyStateView，图标收口至 AppIcons。
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyStateView(
            icon     = AppIcons.Spa,
            title    = "今天还没有任何任务",
            subtitle = "和角色聊聊，或去建立进化项目",
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  TodayGroupedView — 分组视图（P1-A）
//  成长任务分组 + 定时任务分组
// ─────────────────────────────────────────────────────────────

/**
 * U-9 修复：今日列表项的密封类建模。
 * 将列表序列抽象为单一数据源，渲染（TodayGroupedView 的 item 顺序）与
 * 高亮滚动索引计算（TaskCenterScreen 的 absoluteIdx）共用 [buildTodayListItems]，
 * 避免两处独立维护同一列表结构导致变更时错位。
 */
private sealed class TodayListItem {
    abstract val key: String

    data class GrowthHeader(val count: Int) : TodayListItem() {
        override val key = "growth_header"
    }
    data class GrowthTask(val task: TaskEntity) : TodayListItem() {
        override val key = "g_${task.id}"
    }
    object GrowthDivider : TodayListItem() {
        override val key = "growth_divider"
    }
    object ScheduledHeader : TodayListItem() {
        override val key = "scheduled_header"
    }
    data class ScheduledJob(val item: TodayJobUiItem) : TodayListItem() {
        override val key = "j_${item.job.id}"
    }
}

/**
 * U-9 修复：纯函数，根据成长任务和定时任务构建今日列表的统一序列。
 * 渲染顺序与 [TodayGroupedView] 的 LazyColumn item 顺序完全一致，
 * 高亮滚动通过 indexOfFirst 在该序列上查找目标 job 的绝对索引。
 *
 * 列表结构：
 *   [growth_header]      (仅当 growthTasks 非空)
 *   [growth items]       (growthTasks.size 条)
 *   [growth_divider]     (仅当 growthTasks 非空)
 *   [scheduled_header]   (仅当 scheduledJobs 非空)
 *   [scheduled items]    (scheduledJobs.size 条)
 */
private fun buildTodayListItems(
    growthTasks: List<TaskEntity>,
    scheduledJobs: List<TodayJobUiItem>,
): List<TodayListItem> {
    val items = mutableListOf<TodayListItem>()
    if (growthTasks.isNotEmpty()) {
        items += TodayListItem.GrowthHeader(growthTasks.size)
        items += growthTasks.map { TodayListItem.GrowthTask(it) }
        items += TodayListItem.GrowthDivider
    }
    if (scheduledJobs.isNotEmpty()) {
        items += TodayListItem.ScheduledHeader
        items += scheduledJobs.map { TodayListItem.ScheduledJob(it) }
    }
    return items
}

@Composable
private fun TodayGroupedView(
    growthTasks: List<TaskEntity>,
    scheduledJobs: List<TodayJobUiItem>,
    avatarOverrides: Map<Int, String> = emptyMap(),
    onToggleGrowthTask: (String) -> Unit = {},
    highlightedJobId: String? = null,
    // 批次3 3-7修复：TodayGroupedView 内部渲染时需要 characterMap 才能支持女儿角色
    // 查找（GrowthTaskItem / ScheduledJobCard 均已改为接收 characterMap），
    // 原先直接写 uiState.characterMap 但 uiState 是父级 TaskCenterScreen 的
    // 局部变量，这里访问不到，需作为参数显式传入。
    characterMap: Map<Int, CharacterConfig> = emptyMap(),
    state: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // U-9 修复：渲染侧同样接入 buildTodayListItems() 纯函数,与高亮索引计算共用唯一数据源,
    // 消除两处独立维护同一列表结构导致变更时错位的问题。
    val listItems = remember(growthTasks, scheduledJobs) {
        buildTodayListItems(growthTasks, scheduledJobs)
    }
    // 找出最后一个 ScheduledJob 的索引,用于判断 isLast（时间轴末端不画连线）
    // W10问题8修复：listItems 已经 remember(growthTasks, scheduledJobs) 缓存，
    // 这里同样按 listItems 引用缓存 indexOfLast 结果，避免与 listItems 无关的
    // 重组也重新遍历一次列表。
    val lastScheduledJobIdx = remember(listItems) {
        listItems.indexOfLast { it is TodayListItem.ScheduledJob }
    }

    LazyColumn(
        state    = state,
        modifier = modifier,
        contentPadding = PaddingValues(
            start  = Spacing.screenHorizontal,
            end    = Spacing.screenHorizontal,
            top    = Spacing.sm,
            // [v44 修复] 改用 LocalBottomBarHeight（唯一权威来源，见
            // AppNavigation.kt 定义处说明），不再自己重新读取
            // WindowInsets.navigationBars 计算。
            bottom = LocalBottomBarHeight.current + Spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        itemsIndexed(listItems, key = { _, item -> item.key }) { index, listItem ->
            when (listItem) {
                is TodayListItem.GrowthHeader -> {
                    TodaySectionHeader(
                        icon  = AppIcons.Spa,
                        label = "成长任务 (${listItem.count})",
                        color = Palette.GrowthGreen,
                    )
                }
                is TodayListItem.GrowthTask -> {
                    GrowthTaskItem(
                        task            = listItem.task,
                        avatarOverrides = avatarOverrides,
                        onToggle        = { onToggleGrowthTask(listItem.task.id) },
                        characterMap    = characterMap, // 批次3 3-7修复
                    )
                }
                is TodayListItem.GrowthDivider -> {
                    Spacer(Modifier.height(Spacing.sm))
                }
                is TodayListItem.ScheduledHeader -> {
                    TodaySectionHeader(
                        icon  = AppIcons.Schedule,
                        label = "定时任务 (${scheduledJobs.size})",
                        color = ZaijianTheme.colors.accent,
                    )
                }
                is TodayListItem.ScheduledJob -> {
                    val item = listItem.item
                    val isLast = index == lastScheduledJobIdx
                    // W10问题2修复：原先137行渲染逻辑全部内联在 itemsIndexed 的
                    // lambda 里，体量过大导致 Compose 编译器难以做跳过优化。
                    // 抽取为独立 ScheduledJobCard 函数（与已有的 GrowthTaskItem
                    // 抽取模式一致），使其拥有独立组合边界。
                    ScheduledJobCard(
                        item             = item,
                        isLast           = isLast,
                        isHighlighted    = item.job.id == highlightedJobId,
                        avatarOverrides  = avatarOverrides,
                        characterMap     = characterMap, // 批次3 3-7修复
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  TaskCenterPreviewCard — 精修方案 v2.1 2.1「项目/日程」迷你预览卡
//
//  取代原先纯跳转的 TextButton，用 WorldCard 包裹展示真实数据。
//  countText 为 null 时不显示数字行（如项目暂无里程碑可算完成率）。
// ─────────────────────────────────────────────────────────────

@Composable
private fun TaskCenterPreviewCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    countText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    WorldCard(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.zaijian.zhoumuyun.ui.design.IconBadge(
                    icon               = icon,
                    contentDescription = title,
                    size               = 14.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text  = title,
                    style = type.cardTitle,
                    color = colors.textPrimary,
                )
                if (countText != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text  = countText,
                        style = type.labelMono,
                        color = colors.accent,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text     = subtitle,
                style    = type.caption,
                color    = colors.textSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  TodaySectionHeader
// ─────────────────────────────────────────────────────────────

@Composable
private fun TodaySectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            modifier           = Modifier.size(14.dp),
            tint               = color,
        )
        Text(
            text  = label,
            style = type.caption.copy(fontWeight = FontWeight.Bold),
            color = color,
        )
        HorizontalDivider(
            modifier  = Modifier.weight(1f),
            color     = colors.border,
            thickness = 0.5.dp,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  ScheduledJobCard — 定时任务时间轴卡片（W10问题2修复：从
//  TodayGroupedView 的 itemsIndexed lambda 中抽取为独立组合边界，
//  与下方 GrowthTaskItem 的抽取模式一致）
// ─────────────────────────────────────────────────────────────

@Composable
private fun ScheduledJobCard(
    item: TodayJobUiItem,
    isLast: Boolean,
    isHighlighted: Boolean,
    avatarOverrides: Map<Int, String> = emptyMap(),
    // 批次3 3-7修复：接收 characterMap 替代 DefaultCharacters 查找，支持女儿角色
    characterMap: Map<Int, CharacterConfig> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val character = characterMap[item.job.characterId]
    val isPast    = item.job.nextRunAt <= System.currentTimeMillis()
    val timeLabel = TimeFormatUtils.formatTime(item.job.nextRunAt)

    Row(
        modifier          = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // 左侧时间 + 轴线
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(52.dp),
        ) {
            Text(
                text  = timeLabel,
                style = type.small.copy(fontWeight = FontWeight.Medium),
                color = if (isPast) colors.textSecondary else colors.textPrimary,
            )
            Spacer(Modifier.height(Spacing.xs))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            item.result?.status == "success" -> Palette.SemanticSuccess
                            item.result?.status == "failed"  -> Palette.SemanticDanger
                            isPast                            -> colors.accent.copy(alpha = 0.5f)
                            else                               -> colors.bgElevated
                        }
                    )
                    .border(1.5.dp, colors.accent.copy(alpha = 0.4f), CircleShape),
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(72.dp)
                        .background(colors.border),
                )
            }
        }

        Spacer(Modifier.width(Spacing.sm))

        // 右侧卡片
        WorldCard(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 12.dp)
                .then(
                    if (isHighlighted)
                        Modifier.border(1.5.dp, colors.accent, RoundedCornerShape(Radius.md))
                    else Modifier
                ),
            ownerAccent = character?.accentColor,
        ) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val avatarUrl = avatarOverrides[item.job.characterId]?.takeIf { it.isNotBlank() }
                    ?: character?.avatarUrl
                if (avatarUrl != null) {
                    AsyncImage(
                        model              = avatarUrl,
                        contentDescription = character?.name,
                        modifier           = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background((character?.accentColor ?: colors.accent).copy(alpha = 0.15f)),
                    )
                } else {
                    Box(
                        modifier         = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(colors.bgElevated),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("?", style = type.small, color = colors.textSecondary)
                    }
                }
                Spacer(Modifier.width(Spacing.xs))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text  = character?.name ?: "未知",
                            style = type.small.copy(fontWeight = FontWeight.Bold),
                            color = character?.accentColor ?: colors.accent,
                        )
                        Text(" · ", style = type.small, color = colors.textDisabled)
                        Text(
                            text     = item.job.title,
                            style    = type.small.copy(fontWeight = FontWeight.Medium),
                            color    = colors.textPrimary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    item.result?.let { res ->
                        val summary = res.output?.take(50) ?: res.errorMessage?.take(50)
                        if (!summary.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text     = summary,
                                style    = type.small,
                                color    = colors.textSecondary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(Spacing.xs))
                val (statusIcon, statusTint) = when {
                    item.result?.status == "success" -> AppIcons.CheckCircle to Palette.SemanticSuccess
                    item.result?.status == "failed"  -> AppIcons.ErrorOutline to Palette.SemanticDanger
                    isPast -> AppIcons.HourglassEmpty to colors.textDisabled
                    else   -> AppIcons.Schedule to colors.textDisabled
                }
                Icon(
                    imageVector        = statusIcon,
                    contentDescription = null,
                    modifier           = Modifier.size(16.dp),
                    tint               = statusTint,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  GrowthTaskItem — 成长任务行（P1-A）
// ─────────────────────────────────────────────────────────────

@Composable
private fun GrowthTaskItem(
    task: TaskEntity,
    avatarOverrides: Map<Int, String> = emptyMap(),
    onToggle: () -> Unit = {},
    // 批次3 3-7修复：接收 characterMap 替代 DefaultCharacters 查找，支持女儿角色
    characterMap: Map<Int, CharacterConfig> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val colors    = ZaijianTheme.colors
    val type      = ZaijianTheme.typography
    val character = characterMap[task.characterId]
    val isDone    = task.status == TaskStatus.COMPLETED.name
    // P3-55 扩展：硬编码绿色统一为主题常量 Palette.GrowthGreen
    val growthGreen = Palette.GrowthGreen

    WorldCard(
        modifier = modifier.fillMaxWidth().clickable { onToggle() },
        ownerAccent = character?.accentColor,
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 角色头像
            val avatarUrl = avatarOverrides[task.characterId]?.takeIf { it.isNotBlank() }
                ?: character?.avatarUrl
            if (avatarUrl != null) {
                AsyncImage(
                    model              = avatarUrl,
                    contentDescription = character?.name,
                    modifier           = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background((character?.accentColor ?: colors.accent).copy(alpha = 0.15f)),
                )
            } else {
                Box(
                    modifier         = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(colors.bgElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("?", style = type.small, color = colors.textSecondary)
                }
            }

            Spacer(Modifier.width(Spacing.xs))

            Column(modifier = Modifier.weight(1f)) {
                // 任务标题（完成时加删除线 + 降透明）
                Text(
                    text  = task.title,
                    style = type.small.copy(
                        fontWeight    = FontWeight.Medium,
                        textDecoration = if (isDone)
                            androidx.compose.ui.text.style.TextDecoration.LineThrough
                        else null,
                    ),
                    color = if (isDone) colors.textDisabled else colors.textPrimary,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                // 来源标签行：角色名 + 成长标签
                Row(
                    verticalAlignment      = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        text  = character?.name ?: "未知",
                        style = type.small,
                        color = (character?.accentColor ?: colors.accent).copy(alpha = 0.8f),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.xs))
                            .background(growthGreen.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text  = "成长",
                            style = type.small.copy(fontWeight = FontWeight.Medium),
                            color = growthGreen,
                        )
                    }
                }
            }

            Spacer(Modifier.width(Spacing.xs))

            // 完成状态图标
            Icon(
                imageVector        = if (isDone) AppIcons.CheckCircle else AppIcons.RadioButtonUnchecked,
                contentDescription = if (isDone) "已完成" else "待完成",
                modifier           = Modifier.size(18.dp),
                tint               = if (isDone) Palette.SemanticSuccess else colors.textDisabled,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  时间戳格式化
// ─────────────────────────────────────────────────────────────

private fun formatTimestamp(epochMs: Long): String = TimeFormatUtils.formatRelativeTime(epochMs)

// ─────────────────────────────────────────────────────────────
//  UI 升级 v2.0 融合方案帧12：IA 合并新增组件
//
//  TodayScheduleSummaryCard — 今日日程摘要卡（常驻于任务段顶部）
//  TodayScheduleSegment     — 日程段：今日待办明细 + 查看全部
//  TodayProjectSegment      — 项目段：进行中项目预览 + 查看全部
//  ScheduleItemCard         — 日程条目卡（日程段内复用）
// ─────────────────────────────────────────────────────────────

/** 今日日程摘要卡：常驻于任务段顶部，一眼可知今日待办总数。 */
@Composable
private fun TodayScheduleSummaryCard(
    todayJobCount: Int,
    todayGrowthCount: Int,
    onNavigateToSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val total  = todayJobCount + todayGrowthCount
    WorldCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal)
            .padding(top = Spacing.sm)
            .clickable(onClick = onNavigateToSchedule),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.zaijian.zhoumuyun.ui.design.IconBadge(
                icon               = AppIcons.CalendarMonth,
                contentDescription = "今日日程",
                size               = 14.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text  = "今日日程",
                style = type.cardTitle,
                color = colors.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text  = if (total > 0) "$total" else "—",
                style = type.labelMono,
                color = if (total > 0) colors.accent else colors.textDisabled,
            )
            if (total > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = "项待办",
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** 日程段：今日成长任务 + 定时任务明细，底部「查看全部日程」深链。 */
@Composable
private fun TodayScheduleSegment(
    todayJobs: List<TodayJobUiItem>,
    todayGrowthTasks: List<TaskEntity>,
    characterMap: Map<Int, CharacterConfig>,
    onNavigateToSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start  = Spacing.screenHorizontal,
            end    = Spacing.screenHorizontal,
            top    = Spacing.sm,
            bottom = LocalBottomBarHeight.current + Spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 成长任务
        if (todayGrowthTasks.isNotEmpty()) {
            item {
                TodaySectionHeader(
                    icon  = AppIcons.Psychology,
                    label = "成长任务 (${todayGrowthTasks.size})",
                    color = colors.accent,
                )
            }
            items(todayGrowthTasks, key = { it.id }) { task ->
                ScheduleItemCard(
                    title    = task.title,
                    subtitle = characterMap[task.characterId]?.name ?: "角色${task.characterId}",
                    timeText = null,
                )
            }
        }
        // 定时任务
        if (todayJobs.isNotEmpty()) {
            item {
                TodaySectionHeader(
                    icon  = AppIcons.CalendarMonth,
                    label = "定时任务 (${todayJobs.size})",
                    color = colors.accent,
                )
            }
            items(todayJobs, key = { it.job.id }) { jobItem ->
                ScheduleItemCard(
                    title    = jobItem.job.title,
                    subtitle = characterMap[jobItem.job.characterId]?.name
                        ?: "角色${jobItem.job.characterId}",
                    timeText = formatTimestamp(jobItem.job.nextRunAt),
                )
            }
        }
        // 空状态
        if (todayGrowthTasks.isEmpty() && todayJobs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyStateView(
                        icon  = AppIcons.CalendarMonth,
                        title = "今日暂无日程安排",
                    )
                }
            }
        }
        // 查看全部日程
        item {
            SecondaryGoldButton(
                text    = "查看全部日程",
                onClick = onNavigateToSchedule,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
            )
        }
    }
}

/** 项目段：进行中项目预览卡 + 「查看全部项目」深链。 */
@Composable
private fun TodayProjectSegment(
    activeProjectCount: Int,
    completionRate: Float?,
    onNavigateToProjects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start  = Spacing.screenHorizontal,
            end    = Spacing.screenHorizontal,
            top    = Spacing.sm,
            bottom = LocalBottomBarHeight.current + Spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item {
            WorldCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.cardPadding),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.zaijian.zhoumuyun.ui.design.IconBadge(
                            icon               = AppIcons.Folder,
                            contentDescription = "项目",
                            size               = 14.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text  = "进行中项目",
                            style = type.cardTitle,
                            color = colors.textPrimary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text  = "$activeProjectCount",
                            style = type.labelMono,
                            color = colors.accent,
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    if (completionRate != null) {
                        Text(
                            text  = "最新项目完成率：${(completionRate * 100).toInt()}%",
                            style = type.caption,
                            color = colors.textSecondary,
                        )
                    } else if (activeProjectCount == 0) {
                        Text(
                            text  = "暂无进行中项目，去创建一个吧",
                            style = type.caption,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
        item {
            SecondaryGoldButton(
                text    = "查看全部项目",
                onClick = onNavigateToProjects,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
            )
        }
    }
}

/** 日程条目卡：标题 + 角色名 + 可选时间。 */
@Composable
private fun ScheduleItemCard(
    title: String,
    subtitle: String,
    timeText: String?,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    WorldCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = title,
                    style    = type.body,
                    color    = colors.textPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = subtitle,
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }
            if (timeText != null) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text  = timeText,
                    style = type.labelMono,
                    color = colors.accent,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Preview
// ─────────────────────────────────────────────────────────────

@Preview(name = "TaskCenter · Dark", showBackground = true)
@Composable
private fun PreviewTaskCenterDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) { TaskCenterScreen() }
}

@Preview(name = "TaskCenter · Light", showBackground = true)
@Composable
private fun PreviewTaskCenterLight() {
    ZaijianTheme(appTheme = AppTheme.LIGHT) { TaskCenterScreen() }
}
