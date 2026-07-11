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
import androidx.compose.material.icons.outlined.Delete
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
import com.zaijian.zhoumuyun.ui.viewmodel.PersonalScheduleViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.RepeatPreset
import com.zaijian.zhoumuyun.ui.viewmodel.ScheduleDraft
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

    var jobToDelete by remember { mutableStateOf<ScheduledJobEntity?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
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
                        job          = job,
                        accentColor  = accentColor,
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
            onTitleChange       = viewModel::onDraftTitleChange,
            onToolNameChange    = viewModel::onDraftToolNameChange,
            onParamsTextChange  = viewModel::onDraftParamsTextChange,
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
                    Text("删除", color = MaterialTheme.colorScheme.error)
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

private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

@Composable
private fun PersonalScheduleCard(
    job: ScheduledJobEntity,
    accentColor: Color,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors   = ZaijianTheme.colors
    val type     = ZaijianTheme.typography
    val isDisabled = !job.enabled
    val repeatLbl = job.repeatLabel()
    val nextRunDesc = remember(job.nextRunAt) { sdf.format(Date(job.nextRunAt)) }

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
                        text  = job.toolName,
                        style = type.label.copy(fontSize = 11.sp),
                        color = colors.textDisabled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
    onTitleChange: (String) -> Unit,
    onToolNameChange: (String) -> Unit,
    onParamsTextChange: (String) -> Unit,
    onRepeatChange: (RepeatPreset) -> Unit,
    onDelayHoursChange: (Double) -> Unit,
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
            .background(Color.Black.copy(alpha = 0.45f))
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
                        items(availableToolNames) { toolName ->
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
                                    color = if (selected) Color.White else accentColor,
                                )
                            }
                        }
                    }
                }
            }

            // 工具参数（可选，格式与 Agent 标签一致）
            ScheduleTextField(
                label         = "工具参数（可选）",
                placeholder   = "key=\"value\"，例如 query=\"今天天气\"",
                value         = draft.toolParamsText,
                onValueChange = onParamsTextChange,
                accentColor   = accentColor,
                minLines      = 1,
            )

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
                                color = if (selected) Color.White else accentColor,
                            )
                        }
                    }
                }
            }

            // 延迟时间（距现在多少小时后首次/重新执行）
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text  = if (draft.id == null) "首次执行延迟（小时）" else "重新调度延迟（小时）",
                    style = type.label,
                    color = colors.textSecondary,
                )
                ScheduleTextField(
                    label         = "",
                    placeholder   = "0 = 立即纳入调度",
                    value         = if (draft.delayHours == 0.0) "" else draft.delayHours.toString(),
                    onValueChange = { text ->
                        onDelayHoursChange(text.toDoubleOrNull() ?: 0.0)
                    },
                    accentColor   = accentColor,
                    minLines      = 1,
                )
            }

            // 保存按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (draft.title.isNotBlank()) accentColor else colors.border)
                    .clickable(enabled = draft.title.isNotBlank()) { onSave() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "保存日程", style = type.button, color = Color.White)
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
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) {
            Text(text = label, style = type.label, color = colors.textSecondary)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                .border(width = 0.5.dp, color = colors.border, shape = RoundedCornerShape(Radius.sm))
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
    }
}
