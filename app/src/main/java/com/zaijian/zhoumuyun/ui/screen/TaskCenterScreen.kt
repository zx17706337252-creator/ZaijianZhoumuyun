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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.design.GridTabItem
import com.zaijian.zhoumuyun.ui.theme.*
import com.zaijian.zhoumuyun.ui.viewmodel.TaskViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.TodayJobUiItem
import com.zaijian.zhoumuyun.util.TimeFormatUtils
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
                if (absoluteIdx >= 0) {
                    todayListState.animateScrollToItem(absoluteIdx)
                }
            }
            1 -> {
                val idx = uiState.activeTasks.indexOfFirst { it.id == targetId }
                if (idx >= 0) activeListState.animateScrollToItem(idx)
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
            // ── 顶部栏 ────────────────────────────────────────
            // 精修方案 v2.1 2.1：原右侧「目标/项目/日程」三个跳转按钮已删
            // （目标→底部成长Tab已覆盖；项目/日程升级为下方预览卡），
            // 顶栏现在只剩标题，SpaceBetween 排布已无意义，改回默认排布。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = 44.dp) // E fix: 改 height 为 heightIn，大字体缩放时标题不被截断
                    .padding(horizontal = Spacing.screenHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "任务",
                    style = type.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
            }

            // ── 精修方案 v2.1 2.1：项目/日程迷你预览卡 ──────────
            // 原「目标」按钮已删（底部「成长」Tab 一步直达，多余）；
            // 「项目」「日程」从纯跳转按钮升级为两张 WorldCard 预览卡，
            // 显示真实数据（进行中项目数/完成率、今日待办数），数字用 labelMono。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                TaskCenterPreviewCard(
                    modifier          = Modifier.weight(1f),
                    icon              = com.zaijian.zhoumuyun.ui.design.AppIcons.Folder,
                    title             = "项目",
                    subtitle          = if (uiState.activeProjectCount > 0) {
                        "${uiState.activeProjectCount}个进行中"
                    } else {
                        "暂无进行中项目"
                    },
                    countText         = uiState.latestProjectCompletionRate?.let { rate ->
                        "${(rate * 100).toInt()}%"
                    },
                    onClick           = onNavigateToProjects,
                )
                TaskCenterPreviewCard(
                    modifier          = Modifier.weight(1f),
                    icon              = com.zaijian.zhoumuyun.ui.design.AppIcons.CalendarMonth,
                    title             = "日程",
                    subtitle          = "今日待办",
                    countText         = (todayJobs.size + todayGrowthTasks.size).toString(),
                    onClick           = onNavigateToSchedule,
                )
            }

            // ── Tab 栏 ─────────────────────────────────────────
            // GridTabBar 接入（精修方案 v1.3 第6节）：原 ScrollableTabRow 已有
            // Material3 原生下划线指示器，这次替换主要解决「计数数字用等宽字体」
            // 这条规格——GridTabBar 把 label/count 拆开渲染，数字单独套 labelMono。
            // 4 个 Tab 固定走 GridTabBar 的单行等分分支（≤4），不会触发换行，
            // 视觉效果与原先单行平铺一致，不需要 ScrollableTabRow 的横向滚动能力
            // （Tab 数量固定为 4，不会超出屏宽）。
            com.zaijian.zhoumuyun.ui.design.GridTabBar(
                items         = tabItems,
                selectedIndex = selectedTab,
                onSelect      = { selectedTab = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
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
                                modifier        = Modifier.padding(horizontal = Spacing.screenHorizontal),
                            )
                        }
                    }
                }
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
                TextButton(onClick = {
                    if (isCancelAction) {
                        viewModel.cancelTask(task.id)
                    } else {
                        viewModel.deleteTask(task.id)
                    }
                    taskToDelete = null
                }) {
                    Text(
                        if (isCancelAction) "取消任务" else "删除",
                        color = Palette.SemanticDanger,
                    )
                }
            },
            dismissButton     = {
                TextButton(onClick = { taskToDelete = null }) {
                    Text("再想想", color = ZaijianTheme.colors.accent)
                }
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
    modifier: Modifier = Modifier,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    val character = DefaultCharacters.firstOrNull { it.id == task.characterId }

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

                Spacer(Modifier.height(4.dp))

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
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text     = result,
                            style    = type.small,
                            color    = colors.textSecondary,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

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
                    imageVector        = if (isCancellable) Icons.Outlined.Cancel else Icons.Outlined.DeleteOutline,
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
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val (icon, hint) = when (selectedTab) {
        0    -> Pair(Icons.Outlined.Bolt, "还没有进行中的任务\n对角色说「帮我……」开始你的第一个任务")
        1    -> Pair(Icons.Outlined.CheckCircle, "还没有已完成的任务")
        else -> Pair(Icons.Outlined.ErrorOutline, "还没有失败的任务")
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                modifier           = Modifier.size(36.dp),
                tint               = colors.textDisabled,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text      = hint,
                style     = type.body,
                color     = colors.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}


// ─────────────────────────────────────────────────────────────
//  TodayEmptyHint — 今日 Tab 全空状态（P3-A）
// ─────────────────────────────────────────────────────────────

@Composable
private fun TodayEmptyHint() {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector        = Icons.Outlined.Spa,
                contentDescription = null,
                modifier           = Modifier.size(36.dp),
                tint               = colors.textDisabled,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text      = "今天还没有任何任务",
                style     = type.body,
                color     = colors.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text      = "和角色聊聊，或去建立进化项目",
                style     = type.small,
                color     = colors.textDisabled,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
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
    val lastScheduledJobIdx = listItems.indexOfLast { it is TodayListItem.ScheduledJob }

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
                        icon  = Icons.Outlined.Spa,
                        label = "成长任务 (${listItem.count})",
                        color = Palette.GrowthGreen,
                    )
                }
                is TodayListItem.GrowthTask -> {
                    GrowthTaskItem(
                        task            = listItem.task,
                        avatarOverrides = avatarOverrides,
                        onToggle        = { onToggleGrowthTask(listItem.task.id) },
                    )
                }
                is TodayListItem.GrowthDivider -> {
                    Spacer(Modifier.height(Spacing.sm))
                }
                is TodayListItem.ScheduledHeader -> {
                    TodaySectionHeader(
                        icon  = Icons.Outlined.Schedule,
                        label = "定时任务 (${scheduledJobs.size})",
                        color = ZaijianTheme.colors.accent,
                    )
                }
                is TodayListItem.ScheduledJob -> {
                    val item = listItem.item
                    val character = DefaultCharacters.firstOrNull { it.id == item.job.characterId }
                    val isLast    = index == lastScheduledJobIdx
                    val isPast    = item.job.nextRunAt <= System.currentTimeMillis()
                    val timeLabel = TimeFormatUtils.formatHourMinute(item.job.nextRunAt)

                    Row(
                        modifier          = Modifier.fillMaxWidth(),
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
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            item.result?.status == "success" -> Palette.SemanticSuccess
                                            item.result?.status == "failed"  -> Palette.SemanticDanger
                                            isPast                           -> colors.accent.copy(alpha = 0.5f)
                                            else                             -> colors.bgElevated
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
                        val isJobHighlighted = item.job.id == highlightedJobId
                        WorldCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = if (isLast) 0.dp else 12.dp)
                                .then(
                                    if (isJobHighlighted)
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
                                    item.result?.status == "success" -> Icons.Outlined.CheckCircle to Palette.SemanticSuccess
                                    item.result?.status == "failed"  -> Icons.Outlined.ErrorOutline to Palette.SemanticDanger
                                    isPast -> Icons.Outlined.HourglassEmpty to colors.textDisabled
                                    else   -> Icons.Outlined.Schedule to colors.textDisabled
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
            style = type.small.copy(fontWeight = FontWeight.Bold),
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
//  GrowthTaskItem — 成长任务行（P1-A）
// ─────────────────────────────────────────────────────────────

@Composable
private fun GrowthTaskItem(
    task: TaskEntity,
    avatarOverrides: Map<Int, String> = emptyMap(),
    onToggle: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors    = ZaijianTheme.colors
    val type      = ZaijianTheme.typography
    val character = DefaultCharacters.firstOrNull { it.id == task.characterId }
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
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                imageVector        = if (isDone) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
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
