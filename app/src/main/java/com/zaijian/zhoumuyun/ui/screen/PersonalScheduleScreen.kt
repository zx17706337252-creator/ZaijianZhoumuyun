package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
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
import com.zaijian.zhoumuyun.ui.component.ScheduleCardShell
import com.zaijian.zhoumuyun.ui.component.ScheduleRepeatChip
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle // P1-11-2
import com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.theme.*
import com.zaijian.zhoumuyun.ui.component.ScheduleDeleteButton
import com.zaijian.zhoumuyun.ui.component.ScheduleToggleButton
import com.zaijian.zhoumuyun.data.agent.AgentTaskJobExecutor
import com.zaijian.zhoumuyun.ui.viewmodel.PersonalScheduleViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.RepeatPreset
import com.zaijian.zhoumuyun.ui.viewmodel.ScheduleDraft
import com.zaijian.zhoumuyun.ui.viewmodel.TaskKind
import com.zaijian.zhoumuyun.ui.viewmodel.repeatLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────
//  PersonalScheduleScreen — Stage C + D；独立路由见下方 U2 修复
//
//  角色详情页「日程」Tab 的内容面板。展示该角色的全部日程
//  （不分日期，按下次执行时间升序排列），支持手动新增 / 编辑 / 删除。
//
//  数据来源与 Agent 的 schedule_create / schedule_update / schedule_delete
//  三个工具完全一致（同一个 ScheduleRepository），手动编辑和 Agent 自主调度
//  的任务在这里会混合展示，互不冲突。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  U2 修复：独立 Screen 包装
//
//  问题：PersonalScheduleTabContent 只是 CharacterDetailScreen「日程」Tab
//  内嵌的内容面板，没有自己的路由，通知 / 深链接无法直达。
//
//  方案：新增 PersonalScheduleScreen，带独立 onBack + Scaffold 顶栏，
//  内部直接复用 PersonalScheduleTabContent（数据层、草稿编辑、删除确认
//  全部沿用，不重复实现）。顶栏角色名 + accentColor 取法与
//  JudgeProfileScreen 一致：从 DefaultCharacters 按 id 查。
// ─────────────────────────────────────────────────────────────

@Composable
fun PersonalScheduleScreen(
    characterId: Int,
    onBack: () -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val character = remember(characterId) {
        DefaultCharacters.find { it.id == characterId }
    }
    val charName    = character?.name ?: "角色"
    val accentColor = character?.accentColor ?: colors.accent

    Scaffold(
        containerColor      = colors.bgBase,
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // ── 顶部栏 ────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(Spacing.topBarHeight)
                    .padding(horizontal = Spacing.screenHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint               = colors.textPrimary,
                    )
                }
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text     = "$charName · 日程",
                    style    = type.cardTitle,
                    color    = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── 内容：P0-5 修复，改为 Column + verticalScroll。
            //    原实现用 LazyColumn 单 item 包装，其 item 传入无界高度约束
            //    （Constraints.maxHeight = Infinity），ScheduleDraftSheet 根节点
            //    的 fillMaxSize() 在无界约束下展开到 Int.MAX_VALUE，导致
            //    Alignment.BottomCenter 将表单推至屏幕外，核心功能不可用。
            //    ScheduleDraftSheet 已改为窗口级 Dialog，彻底脱离宿主约束；
            //    此处宿主容器改为 Column + verticalScroll，高度约束有界，安全。
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                PersonalScheduleTabContent(
                    characterId = characterId,
                    accentColor = accentColor,
                )
            }
        }
    }
}

@Composable
fun PersonalScheduleTabContent(
    characterId: Int,
    accentColor: Color,
    viewModel: PersonalScheduleViewModel = viewModel(),
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    LaunchedEffect(characterId) { viewModel.init(characterId) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    // 日程系统第七节：collect 可选关联项目列表，供 ScheduleDraftSheet 选择器渲染。
    val availableProjects by viewModel.availableProjects.collectAsStateWithLifecycle(initialValue = emptyList())

    // 日程系统第七节：批量查项目标题，供卡片展示侧使用（避免 N+1）。
    // jobs 变化时 collect 所有非空 projectId，一次性调 viewModel.getProjectTitle
    // 查回映射表。查不到的 id 不放入 Map，卡片侧 fallback 显示前 8 位。
    // 注意：这里是逐条查而非 getByIds——ViewModel 只暴露了 getProjectTitle 单条接口，
    // 任务数通常 <20，逐条查的协程开销可接受；若后续任务数增长可改为 ViewModel 暴露批量接口。
    var projectTitleMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(uiState.jobs) {
        val ids = uiState.jobs.mapNotNull { it.projectId }.distinct()
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

    Column(modifier = Modifier.fillMaxWidth()) {
        // 审查报告问题10配套：toggleEnabled 失败时写入 uiState.error，这里用一条
        // 自动消失的行内提示条展示，避免"字段写了但 UI 从不读"（即问题9同类疏漏）。
        // 参照 PregnancyViewModel 的 errorMessage 模式：展示后主动清空，避免同一条
        // 错误在下次重组时重复出现。
        uiState.error?.let { errorMsg ->
            LaunchedEffect(errorMsg) {
                kotlinx.coroutines.delay(3000)
                viewModel.clearError()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs)
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(Palette.SemanticDanger.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = errorMsg, style = type.label, color = Palette.SemanticDanger)
            }
        }

        // ── 新增按钮 ─────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text  = "全部日程 · ${uiState.jobs.size}",
                style = type.label,
                color = colors.textSecondary,
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(accentColor.copy(alpha = 0.12f))
                    .clickable { viewModel.openNewDraft() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Add,
                    contentDescription = "新增日程",
                    tint               = accentColor,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(text = "新增日程", style = type.label, color = accentColor)
            }
        }

        // ── 列表内容 ─────────────────────────────────────────
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xxl),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = accentColor)
            }
        } else if (uiState.jobs.isEmpty()) {
            PersonalScheduleEmptyState(accentColor = accentColor)
        } else {
            // 静态宿主页面用 LazyColumn 会与外层 LazyColumn 嵌套冲突，
            // 这里改用普通 Column（数据量级是单角色日程，通常个位数到几十条）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                uiState.jobs.sortedBy { it.nextRunAt }.forEach { job ->
                    PersonalScheduleCard(
                        job              = job,
                        accentColor      = accentColor,
                        projectTitleMap  = projectTitleMap,
                        onEdit       = { viewModel.openEditDraft(job) },
                        onDelete     = { jobToDelete = job },
                        onToggle     = { viewModel.toggleEnabled(job) },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }

    // ── 新增/编辑 BottomSheet ────────────────────────────────
    draft?.let { d ->
        ScheduleDraftSheet(
            draft               = d,
            accentColor         = accentColor,
            availableToolNames  = viewModel.availableToolNames,
            availableProjects   = availableProjects,
            onTitleChange       = viewModel::onDraftTitleChange,
            onModeChange        = viewModel::onDraftModeChange,
            onToolNameChange    = viewModel::onDraftToolNameChange,
            onParamsTextChange  = viewModel::onDraftParamsTextChange,
            onDescriptionChange = viewModel::onDraftDescriptionChange,
            onProjectIdChange   = viewModel::onDraftProjectIdChange,
            onRepeatChange      = viewModel::onDraftRepeatChange,
            onDelayHoursChange  = viewModel::onDraftDelayHoursChange,
            onSave              = viewModel::saveDraft,
            onDismiss           = viewModel::dismissDraft,
        )
    }

    // ── 删除确认 Dialog ──────────────────────────────────────
    jobToDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { jobToDelete = null },
            title = { Text("删除日程") },
            text  = { Text("确认删除「${job.title}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteJob(job.id)
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
}

// ─────────────────────────────────────────────────────────────
//  空状态
// ─────────────────────────────────────────────────────────────

@Composable
private fun PersonalScheduleEmptyState(accentColor: Color) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector        = Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint               = colors.textDisabled,
            modifier           = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(text = "还没有日程", style = type.cardTitle, color = colors.textSecondary)
        Spacer(Modifier.height(4.dp))
        Text(
            text  = "点击右上角「新增日程」手动添加，\n或由角色通过工具自主创建",
            style = type.label,
            color = colors.textDisabled,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  单条日程卡片（与 GlobalScheduleScreen 的 ScheduleJobCard 视觉一致，
//  但展开后多了「编辑」入口）
// ─────────────────────────────────────────────────────────────

private fun formatNextRun(ts: Long): String {
    // 批次4-3 修复：SimpleDateFormat 非线程安全，顶层共享 val sdf 在
    // 多个协程并发调用时可能产生日期解析错误。改为每次调用新建实例。
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    return sdf.format(Date(ts))
}

@Composable
private fun PersonalScheduleCard(
    job: ScheduledJobEntity,
    accentColor: Color,
    // 日程系统第七节：项目标题映射表（id → title），由父 Composable 批量查一次后传入，
    // 避免每个卡片单独查（N+1）。查不到的 id 不在此 Map 中，卡片侧 fallback 显示前 8 位。
    projectTitleMap: Map<String, String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors   = ZaijianTheme.colors
    val type     = ZaijianTheme.typography
    val isDisabled = !job.enabled
    val repeatLbl = job.repeatLabel()
    val nextRunDesc = remember(job.nextRunAt) { formatNextRun(job.nextRunAt) }

    // P1-12-2 修复：卡片外壳/重复标签/操作按钮全部委托共享组件
    // （SharedScheduleComponents.kt），消除与 GlobalScheduleScreen 的
    // ~130 行重复实现。「编辑」按钮通过 expandedActions 槽位表达本屏特有差异。
    ScheduleCardShell(
        accentColor = accentColor,
        isDisabled  = isDisabled,
        headerContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text     = job.title,
                        style    = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                        color    = if (isDisabled) colors.textDisabled else colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ScheduleRepeatChip(label = repeatLbl, accent = accentColor, disabled = isDisabled)
                        Text(
                            text  = "下次 $nextRunDesc",
                            style = type.label.copy(fontSize = 11.sp),
                            color = colors.textDisabled,
                        )
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(
                        // 批次4（方案8.1）：按 toolName 分叉展示。
                        // 工单型（mode B）：展示 description 预览（take(20) + 超长省略号），
                        //   不展示内部哨兵值字面量 "agent_task"——它对用户无意义。
                        //   description 理论上非空（create/update 已强校验），这里兜底防御脏数据。
                        // 工具型（mode A）：保持原样展示 job.toolName。
                        text  = if (job.toolName == AgentTaskJobExecutor.SENTINEL) {
                            val desc = job.description
                            if (desc.isNullOrBlank()) "工单"
                            else if (desc.length > 20) desc.take(20) + "…"
                            else desc
                        } else {
                            job.toolName
                        },
                        style = type.label.copy(fontSize = 11.sp),
                        color = colors.textDisabled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 日程系统第七节：若关联了项目，加一行项目标签。
                    // 与上方"下次执行/工具/工单内容"行同款视觉权重（11sp + textDisabled），
                    // 前缀 FolderOpen 小图标（12dp）让"项目"信息与"工具/工单"信息有视觉区分。
                    // 查不到标题时 fallback 显示 projectId 前 8 位（与 ScheduleListTool 同款兜底）。
                    job.projectId?.let { pid ->
                        val title = projectTitleMap[pid] ?: pid.take(8)
                        Row(
                            modifier = Modifier.padding(top = 1.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector        = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier           = Modifier.size(12.dp),
                                tint               = if (isDisabled) colors.textDisabled else accentColor.copy(alpha = 0.7f),
                            )
                            Text(
                                text  = title,
                                style = type.label.copy(fontSize = 11.sp),
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
            TextButton(onClick = onEdit) {
                Text(text = "编辑", style = type.label, color = accentColor)
            }
            ScheduleToggleButton(isDisabled = isDisabled, accentColor = accentColor, onToggle = onToggle)
            ScheduleDeleteButton(onDelete = onDelete)
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  Stage D：新增/编辑日程 BottomSheet
//  仿 GoalDraftSheet 的「半透明遮罩 + 底部弹出卡片」实现，
//  不引入 ModalBottomSheet API，保持与现有代码风格一致。
// ─────────────────────────────────────────────────────────────

/**
 * P0-5 修复：ScheduleDraftSheet 改为窗口级 Dialog。
 *
 * 原实现：根节点 Box(fillMaxSize()) 作为全屏遮罩，直接 inline 嵌入宿主 Composable。
 * 当宿主是 LazyColumn 的 item 时，测量约束 maxHeight=Infinity，fillMaxSize()
 * 撑到 Int.MAX_VALUE；Alignment.BottomCenter 将表单推到屏幕外，核心功能不可用。
 *
 * 修复：改用 Dialog(usePlatformDefaultWidth=false)，弹出独立窗口层级，
 * 测量约束由系统窗口提供（有界），彻底脱离宿主容器约束。
 * 补 imePadding() 防软键盘遮挡，补 BackHandler 使系统返回键关闭面板。
 */
@Composable
private fun ScheduleDraftSheet(
    draft: ScheduleDraft,
    accentColor: Color,
    availableToolNames: List<String>,
    // 日程系统第七节：可选关联项目列表（由 ViewModel.observeActive Flow collect 而来）。
    // 只含 ACTIVE+PAUSED 项目（observeActive 已过滤），已结束项目不在此列表中——
    // 不允许为已结束项目新建日程关联是合理的产品约束。
    availableProjects: List<com.zaijian.zhoumuyun.data.db.entity.ProjectEntity>,
    onTitleChange: (String) -> Unit,
    onModeChange: (TaskKind) -> Unit,
    onToolNameChange: (String) -> Unit,
    onParamsTextChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    // 日程系统第七节：设置关联项目。传 null = 解除关联。
    onProjectIdChange: (String?) -> Unit,
    onRepeatChange: (RepeatPreset) -> Unit,
    onDelayHoursChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth  = false,
            decorFitsSystemWindows   = false,
        ),
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // W10问题6修复：改用 Palette.Night（深色调色板基色）而非裸 Color.Black，
            // 统一颜色定义入口，未来遮罩色需要随主题调整时只需改 Palette 一处。
            .background(Palette.Night.copy(alpha = 0.45f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(if (colors.isDark) colors.bgCard else colors.bgBase)
                .clickable(enabled = false) {}
                .imePadding()
                .padding(Spacing.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = if (draft.id == null) "新增日程" else "编辑日程",
                    style = type.navTitle,
                    color = colors.textPrimary,
                )
                Text(
                    text  = "取消",
                    style = type.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.clickable { onDismiss() }.padding(4.dp),
                )
            }

            // 日程标题
            ScheduleTextField(
                label         = "日程标题",
                placeholder   = "例如「每日复盘提醒」",
                value         = draft.title,
                onValueChange = onTitleChange,
                accentColor   = accentColor,
                minLines      = 1,
            )

            // 批次4（方案8.2）：模式切换。
            // 两段式 Chip，与下方"重复规则"Chip 完全同款写法（第534-558行）：
            // 选中 = accentColor 实底白字，未选中 = accentColor 0.1f 透明底 + 0.4f border。
            // 不用 TabRow（项目无先例、视觉权重过大）/ Switch（语义表达不了两个具名模式）。
            // 默认新建 = TOOL（保持现状），编辑 = 按 existing.toolName 自动选中。
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "模式", style = type.label, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskKind.values().forEach { mode ->
                        val selected = draft.mode == mode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(if (selected) accentColor else accentColor.copy(alpha = 0.1f))
                                .border(
                                    width = if (selected) 0.dp else 0.5.dp,
                                    color = accentColor.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(Radius.sm),
                                )
                                .clickable { onModeChange(mode) }
                                .padding(horizontal = Spacing.md, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text  = mode.label,
                                style = type.label,
                                // W10问题6修复：改用 Palette.White 统一颜色定义入口。
                                color = if (selected) Palette.White else accentColor,
                            )
                        }
                    }
                }
            }

            // 按模式分叉展示"执行内容"区——
            // 工具型：执行工具选择 + 工具参数（保持原逻辑，只是包进 if）
            // 工单型：工单描述多行文本框
            // 切换模式时不清除另一模式的字段（ViewModel onDraftModeChange 不动 toolName/
            // toolParamsText/description），用户切回来还能看到之前的输入。
            if (draft.mode == TaskKind.TOOL) {
                // 执行工具选择
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "执行工具", style = type.label, color = colors.textSecondary)
                    if (availableToolNames.isEmpty()) {
                        Text(
                            text  = "工具注册表为空，将使用默认工具",
                            style = type.label,
                            color = colors.textDisabled,
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(availableToolNames, key = { it }) { toolName ->
                                val selected = draft.toolName == toolName
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.sm))
                                        .background(if (selected) accentColor else accentColor.copy(alpha = 0.1f))
                                        .border(
                                            width = if (selected) 0.dp else 0.5.dp,
                                            color = accentColor.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(Radius.sm),
                                        )
                                        .clickable { onToolNameChange(toolName) }
                                        .padding(horizontal = Spacing.md, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text  = toolName,
                                        style = type.label,
                                        // W10问题6修复：改用 Palette.White 统一颜色定义入口。
                                        color = if (selected) Palette.White else accentColor,
                                    )
                                }
                            }
                        }
                    }
                }

                // 工具参数（可选，格式与 Agent 标签一致）
                // 审查报告问题9修复：新增 errorText，saveDraft() 校验格式失败时
                // 通过 draft.paramsError 就地提示，不再让参数被静默丢弃。
                ScheduleTextField(
                    label         = "工具参数（可选）",
                    placeholder   = "key=\"value\"，例如 query=\"今天天气\"",
                    value         = draft.toolParamsText,
                    onValueChange = onParamsTextChange,
                    accentColor   = accentColor,
                    minLines      = 1,
                    errorText     = draft.paramsError,
                )
            } else {
                // 工单描述（mode B 专用）：多行文本框。
                // minLines=3 比单行标题/参数更"重"，符合"描述一段较长任务说明"的语义。
                // maxLength=500 由 ViewModel onDraftDescriptionChange 的 take() 兜底，
                // 这里不重复限制，与 title/params 的处理范式一致。
                ScheduleTextField(
                    label         = "工单描述",
                    placeholder   = "描述这个日程要做的事，到点角色会按此推理回应。例如：提醒用户该喝水了",
                    value         = draft.description,
                    onValueChange = onDescriptionChange,
                    accentColor   = accentColor,
                    minLines      = 3,
                    errorText     = draft.descriptionError,
                )
            }

            // 重复规则
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "重复规则", style = type.label, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepeatPreset.values().forEach { preset ->
                        val selected = draft.repeatPreset == preset
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(if (selected) accentColor else accentColor.copy(alpha = 0.1f))
                                .border(
                                    width = if (selected) 0.dp else 0.5.dp,
                                    color = accentColor.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(Radius.sm),
                                )
                                .clickable { onRepeatChange(preset) }
                                .padding(horizontal = Spacing.md, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text  = preset.label,
                                style = type.label,
                                // W10问题6修复：改用 Palette.White 统一颜色定义入口。
                                color = if (selected) Palette.White else accentColor,
                            )
                        }
                    }
                }
            }

            // 日程系统第七节：关联项目选择器（精致版）。
            // 设计目标：与上方"模式 Chip"/"重复规则"等字段块视觉权重一致，但信息密度更高。
            // - 选中态：卡片实线 border（accentColor 0.4f）+ 圆角 + FolderOpen 图标 +
            //   项目标题 + 状态色点 + 描述预览（take(30) 省略号）
            // - 未选态：浅色 border + Add 图标 + "选择关联项目（可选）"占位
            // - 点击展开 DropdownMenu：顶部"无关联项目"清除选项 + 下方各项目条目
            // - 状态色点按 ProjectStatus 字符串比较（status 字段存的是 "ACTIVE"/"PAUSED"
            //   字符串而非强类型枚举，按你指出的注意事项处理）：
            //   ● ACTIVE = accentColor，◐ PAUSED = colors.textDisabled
            // - 只列 availableProjects（observeActive 已过滤 ACTIVE+PAUSED），ARCHIVED 不出现
            ProjectSelectorField(
                selectedProjectId = draft.projectId,
                availableProjects = availableProjects,
                accentColor       = accentColor,
                onProjectIdChange = onProjectIdChange,
            )

            // 延迟时间（距现在多少小时后首次/重新执行）
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text  = if (draft.id == null) "首次执行延迟（小时）" else "重新调度延迟（小时）",
                    style = type.label,
                    color = colors.textSecondary,
                )
                // 审查报告问题9修复：原先用 text.toDoubleOrNull() ?: 0.0 在 UI 层
                // 就静默把非法输入吞成0，用户打错字符时看不到任何提示，输入框还会
                // 立刻变回空白。现在直接回显 ViewModel 保留的原始文本
                // （draft.delayHoursText），非法输入时保留用户输入内容并展示错误。
                ScheduleTextField(
                    label         = "",
                    placeholder   = "0 = 立即纳入调度",
                    value         = draft.delayHoursText,
                    onValueChange = onDelayHoursChange,
                    accentColor   = accentColor,
                    minLines      = 1,
                    errorText     = draft.delayHoursError,
                )
            }

            // 保存按钮
            // 审查报告问题9修复：存在格式错误（延迟小时数非法 / 工具参数格式不对）
            // 时按钮直接置灰，而不是等用户点击后才在 saveDraft() 里被拦截。
            // 批次4补充：工单型的 descriptionError 也纳入 canSave 校验——
            // 虽然当前 saveDraft() 对空 description 是 return 而非预置 descriptionError，
            // 但 onDraftDescriptionChange 会清掉错误、saveDraft 才会重设，这里把
            // descriptionError == null 作为前置条件，保证"有错误就不让点"的一致性。
            val canSave = draft.title.isNotBlank() &&
                draft.delayHoursError == null &&
                draft.paramsError == null &&
                draft.descriptionError == null
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (canSave) accentColor else colors.border)
                    .clickable(enabled = canSave) { onSave() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                // W10问题6修复：改用 Palette.White 统一颜色定义入口。
                Text(text = "保存日程", style = type.button, color = Palette.White)
            }
        }
    }
    } // Dialog
}

/**
 * 简化版输入框，行为与 CharacterDetailScreen 内的 IdentityField 一致，
 * 在此单独定义一份避免依赖该文件的 private 符号。
 */
@Composable
private fun ScheduleTextField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    accentColor: Color,
    minLines: Int = 1,
    // 审查报告问题9修复：新增可选错误提示，非空时边框变为错误色并在下方展示
    // 错误文案，用于替代此前"非法输入静默回退"的做法。
    errorText: String? = null,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val isError = errorText != null

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) {
            Text(text = label, style = type.label, color = colors.textSecondary)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                .border(
                    width = if (isError) 1.dp else 0.5.dp,
                    color = if (isError) Palette.SemanticDanger else colors.border,
                    shape = RoundedCornerShape(Radius.sm),
                )
                .padding(horizontal = Spacing.md, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(text = placeholder, style = type.body, color = colors.textDisabled)
            }
            BasicTextField(
                value         = value,
                onValueChange = onValueChange,
                textStyle     = type.body.copy(color = colors.textPrimary),
                minLines      = minLines,
                modifier      = Modifier.fillMaxWidth(),
                cursorBrush   = androidx.compose.ui.graphics.SolidColor(accentColor),
            )
        }
        if (errorText != null) {
            Text(text = errorText, style = type.caption, color = Palette.SemanticDanger)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  日程系统第七节：关联项目选择器（精致版）
//  独立 Composable，便于复用与测试。设计稿见 batch7_scope_and_decision.txt 第四节。
// ─────────────────────────────────────────────────────────────

@Composable
private fun ProjectSelectorField(
    selectedProjectId: String?,
    availableProjects: List<com.zaijian.zhoumuyun.data.db.entity.ProjectEntity>,
    accentColor: Color,
    onProjectIdChange: (String?) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 当前选中的项目实体（从 availableProjects 里按 id 查；查不到说明项目已被归档/删除，
    // 但日程仍关联着旧 id——仍展示其 id 前 8 位作为 fallback，与 ScheduleListTool 同款兜底）
    val selectedProject = selectedProjectId?.let { id ->
        availableProjects.find { it.id == id }
    }

    var dropdownExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "关联项目（可选）", style = type.label, color = colors.textSecondary)

        Box {
            // 卡片主体：选中态 vs 未选态视觉分叉
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                    .border(
                        width  = 0.5.dp,
                        // 选中态用 accentColor 边框强化"已选"反馈，未选态用普通 border
                        color  = if (selectedProject != null) accentColor.copy(alpha = 0.4f) else colors.border,
                        shape  = RoundedCornerShape(Radius.sm),
                    )
                    .clickable { dropdownExpanded = true }
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
            ) {
                if (selectedProject != null) {
                    // 选中态：图标 + 标题 + 状态色点 + 描述预览
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector        = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier           = Modifier.size(16.dp),
                            tint               = accentColor,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text  = selectedProject.title,
                                    style = type.body,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 状态色点：按 status 字符串比较（字段存的是 "ACTIVE"/"PAUSED"
                                // 字符串，不是强类型枚举——按用户指出的注意事项处理）
                                ProjectStatusDot(status = selectedProject.status, accentColor = accentColor)
                            }
                            // 描述预览（take(30) 省略号，空描述不显示该行）
                            if (selectedProject.description.isNotBlank()) {
                                Text(
                                    text  = selectedProject.description.take(30).let {
                                        if (selectedProject.description.length > 30) "$it…" else it
                                    },
                                    style = type.caption,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(
                            imageVector        = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null,
                            modifier           = Modifier.size(16.dp),
                            tint               = colors.textDisabled,
                        )
                    }
                } else {
                    // 未选态：Add 图标 + 占位文本
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector        = Icons.Outlined.Add,
                            contentDescription = null,
                            modifier           = Modifier.size(16.dp),
                            tint               = colors.textDisabled,
                        )
                        Text(
                            text  = "选择关联项目（可选）",
                            style = type.body,
                            color = colors.textDisabled,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 下拉菜单：顶部"无关联项目"清除选项 + 下方各项目条目
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
            ) {
                // 顶部清除选项（当前已选中时才显示，未选中时无需展示）
                if (selectedProjectId != null) {
                    DropdownMenuItemClear(onClick = {
                        onProjectIdChange(null)
                        dropdownExpanded = false
                    })
                    HorizontalDivider()
                }
                if (availableProjects.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text  = "暂无可用项目",
                                style = type.caption,
                                color = colors.textDisabled,
                            )
                        },
                        onClick = {},
                    )
                } else {
                    availableProjects.forEach { project ->
                        DropdownMenuItemProject(
                            project    = project,
                            selected   = project.id == selectedProjectId,
                            accentColor = accentColor,
                            onClick    = {
                                onProjectIdChange(project.id)
                                dropdownExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 项目状态色点。按 status 字符串比较（字段存的是 "ACTIVE"/"PAUSED" 字符串）：
 * - "ACTIVE" → accentColor 实心圆点 ●
 * - "PAUSED" → textDisabled 实心圆点 ●（视觉上比 ACTIVE 弱）
 * - 其他/未知 → textDisabled（保守降级）
 */
@Composable
private fun ProjectStatusDot(status: String, accentColor: Color) {
    val colors = ZaijianTheme.colors
    val dotColor = when (status) {
        "ACTIVE" -> accentColor
        "PAUSED" -> colors.textDisabled
        else -> colors.textDisabled
    }
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(dotColor),
    )
}

@Composable
private fun DropdownMenuItemClear(onClick: () -> Unit) {
    val type = ZaijianTheme.typography
    val colors = ZaijianTheme.colors
    DropdownMenuItem(
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Close,
                    contentDescription = null,
                    modifier           = Modifier.size(14.dp),
                    tint               = colors.textSecondary,
                )
                Text(text = "无关联项目", style = type.body, color = colors.textSecondary)
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun DropdownMenuItemProject(
    project: com.zaijian.zhoumuyun.data.db.entity.ProjectEntity,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val type = ZaijianTheme.typography
    val colors = ZaijianTheme.colors
    DropdownMenuItem(
        text = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier           = Modifier.size(14.dp),
                        tint               = if (selected) accentColor else colors.textSecondary,
                    )
                    Text(
                        text  = project.title,
                        style = type.body,
                        color = if (selected) accentColor else colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ProjectStatusDot(status = project.status, accentColor = accentColor)
                }
                if (project.description.isNotBlank()) {
                    Text(
                        text  = project.description.take(30).let {
                            if (project.description.length > 30) "$it…" else it
                        },
                        style = type.caption,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onClick = onClick,
    )
}
