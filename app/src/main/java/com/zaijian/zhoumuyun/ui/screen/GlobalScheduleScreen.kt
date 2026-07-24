package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle // P1-11-2
import coil.compose.AsyncImage
import com.zaijian.zhoumuyun.data.agent.AgentTaskJobExecutor
import com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.ui.theme.*
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
import com.zaijian.zhoumuyun.ui.component.ScheduleRepeatChip
import com.zaijian.zhoumuyun.ui.component.ScheduleCardShell
import com.zaijian.zhoumuyun.ui.component.ScheduleDeleteButton
import com.zaijian.zhoumuyun.ui.component.ScheduleToggleButton
import com.zaijian.zhoumuyun.ui.viewmodel.GlobalScheduleViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.RepeatLabel
import com.zaijian.zhoumuyun.ui.viewmodel.ScheduleJobItem
import com.zaijian.zhoumuyun.ui.viewmodel.ScheduleTimeSlot
import com.zaijian.zhoumuyun.ui.viewmodel.repeatLabel
import kotlinx.coroutines.launch
import java.util.Calendar
import com.zaijian.zhoumuyun.ui.design.AppIcons

// ─────────────────────────────────────────────────────────────
//  GlobalScheduleScreen  —  全局日程视图（Stage A+B）
//
//  层级：
//    [顶栏] 日期导航（← 今天 →）
//    [筛选] 角色单选横排（AdaptiveAvatarRow，null = 全选/全部）
//    [内容] 竖向时间轴（按分钟分组，同时间点多角色堆叠）
// ─────────────────────────────────────────────────────────────

@Composable
fun GlobalScheduleScreen(
    onBack: () -> Unit = {},
    viewModel: GlobalScheduleViewModel = viewModel(),
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope   = rememberCoroutineScope()

    // 日程系统第七节：批量查项目标题，供卡片展示侧使用（避免 N+1）。
    // 从 timeSlots 里 collect 所有 job 的非空 projectId，一次性查回映射表。
    // 与 PersonalScheduleScreen 同款实现，区别只是数据源（timeSlots vs jobs）。
    var projectTitleMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(uiState.timeSlots) {
        val ids = uiState.timeSlots
            .flatMap { it.items }
            .mapNotNull { it.job.projectId }
            .distinct()
        if (ids.isEmpty()) {
            projectTitleMap = emptyMap()
        } else {
            val mapped = mutableMapOf<String, String>()
            for (id in ids) {
                viewModel.getProjectTitle(id)?.let { mapped[id] = it }
            }
            projectTitleMap = mapped
        }
    }

    var jobToDelete by remember { mutableStateOf<ScheduledJobEntity?>(null) }

    // 删除确认 Dialog
    jobToDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { jobToDelete = null },
            title = { Text("删除日程") },
            text  = { Text("确认删除「${job.title}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { viewModel.deleteJob(job.id) }
                    jobToDelete = null
                }) {
                    Text("删除", color = Palette.SemanticDanger)  // P3-53 修复：colorScheme.error → Palette.SemanticDanger
                }
            },
            dismissButton = {
                TextButton(onClick = { jobToDelete = null }) { Text("取消") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ── 顶栏 ─────────────────────────────────────────────
        // D-2 统一顶栏：ScheduleTopBar → DetailTopBar（日期导航移入 actions）
        val dateLabel = remember(uiState.dayOffset) {
            when (uiState.dayOffset) {
                -1   -> "昨天"
                0    -> "今天"
                1    -> "明天"
                else -> {
                    val cal = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, uiState.dayOffset)
                    }
                    val m = cal.get(Calendar.MONTH) + 1
                    val d = cal.get(Calendar.DAY_OF_MONTH)
                    val w = when (cal.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.SUNDAY    -> "日"
                        Calendar.MONDAY    -> "一"
                        Calendar.TUESDAY   -> "二"
                        Calendar.WEDNESDAY -> "三"
                        Calendar.THURSDAY  -> "四"
                        Calendar.FRIDAY    -> "五"
                        Calendar.SATURDAY  -> "六"
                        else               -> ""
                    }
                    "${m}月${d}日 $w"
                }
            }
        }
        DetailTopBar(
            title    = "日程",
            subtitle = dateLabel,
            onBack   = onBack,
            headerBg = colors.bgBase,
            actions  = {
                IconButton(onClick = { viewModel.setDayOffset(uiState.dayOffset - 1) }) {
                    Icon(
                        imageVector        = AppIcons.ArrowBack,
                        contentDescription = "前一天",
                        tint               = colors.textSecondary,
                    )
                }
                if (uiState.dayOffset != 0) {
                    IconButton(onClick = { viewModel.setDayOffset(0) }) {
                        Icon(
                            imageVector        = AppIcons.CalendarMonth,
                            contentDescription = "回今天",
                            tint               = colors.textSecondary,
                        )
                    }
                }
                IconButton(onClick = { viewModel.setDayOffset(uiState.dayOffset + 1) }) {
                    Icon(
                        imageVector        = AppIcons.ArrowForward,
                        contentDescription = "后一天",
                        tint               = colors.textSecondary,
                    )
                }
            },
        )

        // ── 角色筛选器 ────────────────────────────────────────
        CharacterFilterRow(
            characters  = uiState.allCharacters,
            selectedId  = uiState.selectedCharacterId,
            onSelect    = { viewModel.selectCharacter(it) },
            onClearAll  = { viewModel.clearFilter() },
        )

        HorizontalDivider(
            thickness = 0.5.dp,
            color     = colors.borderSubtle,
        )

        // ── 时间轴内容 ────────────────────────────────────────
        // P3-13 修复：展示操作失败的错误信息，用户操作失败时不再无感知
        if (uiState.error != null) {
            Snackbar(
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                action = {
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("关闭", color = Color.White)
                    }
                },
            ) {
                Text(uiState.error!!)
            }
        }

        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
        } else if (uiState.timeSlots.isEmpty()) {
            val dayText = when (uiState.dayOffset) {
                -1   -> "昨天"
                0    -> "今天"
                1    -> "明天"
                else -> "这一天"
            }
            EmptyStateView(
                icon     = AppIcons.CalendarMonth,
                title    = "${dayText}没有日程",
                subtitle = "角色可通过 schedule_create 工具添加",
            )
        } else {
            TimelineContent(
                slots            = uiState.timeSlots,
                projectTitleMap  = projectTitleMap,
                onDeleteJob = { jobToDelete = it },
                onToggle    = { scope.launch { viewModel.toggleEnabled(it) } },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  角色筛选器横滚条
// ─────────────────────────────────────────────────────────────

// WorldCard 接入（精修方案 v1.3 第5.5/6节）：原手写 LazyRow 头像筛选条改用
// AdaptiveAvatarRow（单选、按楼层 3-3-3 分组、选中态描边取角色 accentColor）。
// AdaptiveAvatarRow 本身不含"全部"选项、不含角色名文字、不自带滚动（固定9个
// 角色不需要懒加载），这些都是该场景需要但组件本身不该承担的部分，因此在外层
// 单独包一个"全部"按钮 + 角色名文字行，不为了凑这一个场景改组件签名。
//
// 注意：原 CharacterFilterRow 是多选（Set<Int>），可以同时筛选多个角色的日程；
// 这次接入 AdaptiveAvatarRow 时一并改为单选（Int?），是产品侧确认的简化决策，
// 不是技术限制——AdaptiveAvatarRow 的 selectedId 参数本身就是单选语义。
@Composable
private fun CharacterFilterRow(
    characters: List<CharacterConfig>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    onClearAll: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val isAllSelected = selectedId == null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 「全部」快捷按钮：AdaptiveAvatarRow 不含此选项，独立放在最前面
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier            = Modifier.clickable(
                    indication        = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick           = onClearAll,
                ),
            ) {
                Box(
                    modifier          = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isAllSelected) colors.accent.copy(alpha = 0.15f)
                            else colors.bgCard
                        )
                        .border(
                            width = if (isAllSelected) 2.dp else 1.dp,
                            color = if (isAllSelected) colors.accent else colors.borderSubtle,
                            shape = CircleShape,
                        ),
                    contentAlignment  = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = AppIcons.FilterList,
                        contentDescription = "全部",
                        tint               = if (isAllSelected) colors.accent else colors.textSecondary,
                        modifier           = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text  = "全部",
                    style = type.label, // 伪接入修复：type.label 本身即 11sp
                    color = if (isAllSelected) colors.accent else colors.textSecondary,
                )
            }

            com.zaijian.zhoumuyun.ui.design.AdaptiveAvatarRow(
                items = characters.map { char ->
                    com.zaijian.zhoumuyun.ui.design.AvatarRowItem(
                        id          = char.id,
                        avatarUrl   = char.avatarUrl,
                        accentColor = char.accentColor,
                        floor       = when (char.floor) {
                            com.zaijian.zhoumuyun.data.model.FloorEnum.SECOND   -> com.zaijian.zhoumuyun.ui.design.AvatarRowFloor.SECOND
                            com.zaijian.zhoumuyun.data.model.FloorEnum.FIRST    -> com.zaijian.zhoumuyun.ui.design.AvatarRowFloor.FIRST
                            com.zaijian.zhoumuyun.data.model.FloorEnum.BASEMENT -> com.zaijian.zhoumuyun.ui.design.AvatarRowFloor.BASEMENT
                        },
                    )
                },
                selectedId = selectedId,
                onSelect   = onSelect,
                avatarSize = 44.dp,
            )
        }

        // 角色名文字行：AdaptiveAvatarRow 是纯头像组件，不含文字标签，
        // 该场景需要"头像下方显示名字"，故在外层单独叠加一行文字，
        // 与上方头像行用相同的横向滚动状态保持对齐困难（两个独立 Row
        // 各自滚动不同步），改为不在头像下方放名字，选中态靠头像描边色
        // 和下方单独展示的"当前筛选：XXX"文案表达，避免双行错位的视觉 bug。
        if (selectedId != null) {
            val selectedChar = characters.firstOrNull { it.id == selectedId }
            if (selectedChar != null) {
                Text(
                    text     = "当前筛选：${selectedChar.name}",
                    style    = type.label, // 伪接入修复：type.label 本身即 11sp
                    color    = selectedChar.accentColor,
                    modifier = Modifier.padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.xs,
                    ),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  时间轴内容
// ─────────────────────────────────────────────────────────────

@Composable
private fun TimelineContent(
    slots: List<ScheduleTimeSlot>,
    projectTitleMap: Map<String, String>,
    onDeleteJob: (ScheduledJobEntity) -> Unit,
    onToggle: (ScheduledJobEntity) -> Unit,
) {
    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start  = Spacing.screenHorizontal,
            end    = Spacing.screenHorizontal,
            top    = Spacing.md,
            bottom = 80.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(slots, key = { it.nextRunAt }) { slot ->
            TimeSlotRow(
                slot             = slot,
                projectTitleMap  = projectTitleMap,
                onDeleteJob = onDeleteJob,
                onToggle    = onToggle,
            )
        }
    }
}

@Composable
private fun TimeSlotRow(
    slot: ScheduleTimeSlot,
    projectTitleMap: Map<String, String>,
    onDeleteJob: (ScheduledJobEntity) -> Unit,
    onToggle: (ScheduledJobEntity) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // ── 时间轴左侧：时间标签 + 竖线 ──────────────────────
        Column(
            modifier            = Modifier.width(52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text  = slot.timeLabel,
                style = type.caption.copy(fontWeight = FontWeight.Bold), // 语义修正：13sp 对应 caption，非 label
                color = colors.textSecondary,
            )
            // 竖线延伸
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .fillMaxHeight()
                    .background(colors.borderSubtle),
            )
        }

        Spacer(Modifier.width(12.dp))

        // ── 右侧：卡片列表 ────────────────────────────────────
        Column(
            modifier            = Modifier
                .weight(1f)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                slot.items.forEach { item ->
                ScheduleJobCard(
                    item             = item,
                    projectTitleMap  = projectTitleMap,
                    onDelete    = { onDeleteJob(item.job) },
                    onToggle    = { onToggle(item.job) },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  单条日程卡片
// ─────────────────────────────────────────────────────────────

@Composable
private fun ScheduleJobCard(
    item: ScheduleJobItem,
    projectTitleMap: Map<String, String>,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors    = ZaijianTheme.colors
    val type      = ZaijianTheme.typography
    val char      = item.character
    val job       = item.job
    val accentColor = char?.accentColor ?: colors.accent
    val isDisabled  = !job.enabled
    val repeatLbl = job.repeatLabel()

    // P1-12-2 修复：卡片外壳/重复标签/操作按钮全部委托共享组件
    // （SharedScheduleComponents.kt），消除与 PersonalScheduleScreen 的
    // ~130 行重复实现。仅 headerContent 与 expandedActions 的具体内容
    // （角色头像 + 角色名 vs 编辑按钮）通过槽位参数表达差异。
    ScheduleCardShell(
        accentColor = accentColor,
        isDisabled  = isDisabled,
        headerContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 角色头像（小）
                CharacterMiniAvatar(char = char, size = 32.dp, accentColor = accentColor)

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 角色名 · 任务标题
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (char != null) {
                            // 12sp 不在现有 label(11)/caption(13) 令牌之间，为真实覆写（非伪接入），保留 .copy() 写法以维持可追踪性
                            Text(
                                text  = char.name,
                                style = type.label.copy(fontSize = 12.sp),
                                color = accentColor.copy(alpha = if (isDisabled) 0.5f else 0.85f),
                            )
                            Text(
                                text  = " · ",
                                style = type.label.copy(fontSize = 12.sp),
                                color = colors.textDisabled,
                            )
                        }
                        Text(
                            text     = job.title,
                            style    = type.label.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize   = 13.sp,
                            ),
                            color    = if (isDisabled) colors.textDisabled else colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    // 重复标签 + 工具名
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        ScheduleRepeatChip(label = repeatLbl, accent = accentColor, disabled = isDisabled)
                        Text(
                            // 批次4（方案8.1）：与 PersonalScheduleScreen 第372行同款分叉。
                            // 工单型展示 description 预览（take(20) + 超长省略号），
                            // 不展示内部哨兵值字面量；工具型保持原样。
                            text  = if (job.toolName == AgentTaskJobExecutor.SENTINEL) {
                                val desc = job.description
                                if (desc.isNullOrBlank()) "工单"
                                else if (desc.length > 20) desc.take(20) + "…"
                                else desc
                            } else {
                                job.toolName
                            },
                            style = type.label, // 伪接入修复：type.label 本身即 11sp
                            color = colors.textDisabled,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 日程系统第七节：若关联了项目，加一行项目标签。
                    // 与 PersonalScheduleScreen 卡片侧同款实现（FolderOpen 图标 + 标题）。
                    job.projectId?.let { pid ->
                        val title = projectTitleMap[pid] ?: pid.take(8)
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector        = AppIcons.FolderOpen,
                                contentDescription = null,
                                modifier           = Modifier.size(12.dp),
                                tint               = if (isDisabled) colors.textDisabled else accentColor.copy(alpha = 0.7f),
                            )
                            Text(
                                text  = title,
                                style = type.label,
                                color = colors.textDisabled,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        expandedActions = {
            // 启用/禁用切换
            ScheduleToggleButton(isDisabled = isDisabled, accentColor = colors.accent, onToggle = onToggle)
            // 删除
            ScheduleDeleteButton(onDelete = onDelete)
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  角色迷你头像
// ─────────────────────────────────────────────────────────────

@Composable
private fun CharacterMiniAvatar(
    char: CharacterConfig?,
    size: androidx.compose.ui.unit.Dp,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Box(
        modifier         = Modifier
            .size(size)
            .clip(CircleShape)
            .background(accentColor.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        if (char?.avatarUrl?.isNotEmpty() == true) {
            AsyncImage(
                model              = char.avatarUrl,
                contentDescription = char.name,
                modifier           = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            Text(
                text  = (char?.name ?: "?").take(2),
                style = type.label.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize   = (size.value * 0.32f).sp,
                ),
                color = accentColor,
            )
        }
    }
}

