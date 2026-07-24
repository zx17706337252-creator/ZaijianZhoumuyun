package com.zaijian.zhoumuyun.ui.screen.characterdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.SkillEntity
import com.zaijian.zhoumuyun.data.db.entity.SkillEditLogEntity
import com.zaijian.zhoumuyun.data.db.entity.SkillStatus
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.SkillFilter
import com.zaijian.zhoumuyun.ui.viewmodel.SkillListViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.SkillUiState
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.ui.design.AppIcons

// ═══════════════════════════════════════════════════════════════
//  Window C 缺口 2 · 技能管理面板 UI
//
//  设计方案 v1.2 §6 "可见面板"——用户侧管理入口。
//  与 CharacterDetailAbility.kt / CharacterDetailGoal.kt 同目录同粒度。
//
//  组件层级：
//    SkillTabContent  →  顶部过滤 Chip + 技能卡片列表
//    SkillCard        →  名称 + 描述 + 状态徽标 + 使用次数 + 最近使用时间
//    SkillDetailSheet →  ModalBottomSheet：完整内容 + 变更日志时间线 + 操作入口
//    SkillEditDialog  →  AlertDialog：全字段编辑表单
//    ConfirmDialog    →  二次确认弹窗（废弃/恢复/删除）
// ═══════════════════════════════════════════════════════════════

/**
 * 技能 Tab 内容入口，由 CharacterDetailScreen 的 `when (abilityTab)` 分支调用。
 * 不新增导航路由，复用外层 LazyColumn 的 item{} 嵌套方式（与 AbilityPanel 同款）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillTabContent(
    characterId: Int,
    accentColor: Color,
    skillListViewModel: SkillListViewModel = viewModel(),
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // P2-15 修复：配合 SkillListViewModel 改为属性模式，
    // characterId 变化时通过 setCharacterId 驱动 flatMapLatest 切换数据源，
    // 不再每次重组创建新 StateFlow。
    LaunchedEffect(characterId) {
        skillListViewModel.setCharacterId(characterId)
    }
    val uiState by skillListViewModel.uiState
        .collectAsStateWithLifecycle(initialValue = SkillUiState())

    // 详情 Sheet 选中状态
    var selectedSkill by remember { mutableStateOf<SkillEntity?>(null) }
    // 编辑弹窗
    var editingSkill by remember { mutableStateOf<SkillEntity?>(null) }
    // 操作确认弹窗
    var pendingAction by remember { mutableStateOf<PendingSkillAction?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        // ── 过滤 Chip 行 ──────────────────────────────────────
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement   = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.padding(vertical = Spacing.sm),
        ) {
            SkillFilter.entries.forEach { filter ->
                FilterChip(
                    selected = uiState.filter == filter,
                    onClick  = { skillListViewModel.setFilter(filter) },
                    label    = {
                        Text(
                            text  = when (filter) {
                                SkillFilter.ALL        -> "全部"
                                SkillFilter.ACTIVE     -> "使用中"
                                SkillFilter.DEPRECATED -> "已废弃"
                            },
                            style = type.caption,
                        )
                    },
                )
            }
        }

        // ── 技能列表 ──────────────────────────────────────────
        if (uiState.isLoading) {
            Text(
                text  = "加载中…",
                style = type.body,
                color = colors.textDisabled,
                modifier = Modifier.padding(vertical = Spacing.lg),
            )
        } else if (uiState.skills.isEmpty()) {
            EmptyStateView(
                icon  = AppIcons.SentimentDissatisfied,
                title = "还没有技能",
                subtitle = "这个角色还没沉淀出技能，多交给她做点复杂任务试试",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            uiState.skills.forEach { skill ->
                SkillCard(
                    skill       = skill,
                    accentColor = accentColor,
                    onClick     = { selectedSkill = skill },
                )
                Spacer(Modifier.height(Spacing.sm))
            }
        }
    }

    // ── 详情 Bottom Sheet ─────────────────────────────────────
    selectedSkill?.let { skill ->
        SkillDetailSheet(
            skill              = skill,
            accentColor        = accentColor,
            skillListViewModel = skillListViewModel,
            onDismiss          = { selectedSkill = null },
            onEdit             = {
                selectedSkill = null
                editingSkill = skill
            },
            onDeprecate        = {
                selectedSkill = null
                pendingAction = PendingSkillAction.Deprecate(skill)
            },
            onRestore          = {
                selectedSkill = null
                pendingAction = PendingSkillAction.Restore(skill)
            },
            onDelete           = {
                selectedSkill = null
                pendingAction = PendingSkillAction.Delete(skill)
            },
        )
    }

    // ── 编辑弹窗 ──────────────────────────────────────────────
    editingSkill?.let { skill ->
        SkillEditDialog(
            skill              = skill,
            onDismiss          = { editingSkill = null },
            onSave             = { name, shortDesc, fullContent, category ->
                skillListViewModel.saveEdit(skill, name, shortDesc, fullContent, category)
                editingSkill = null
            },
        )
    }

    // ── 操作确认弹窗 ──────────────────────────────────────────
    pendingAction?.let { action ->
        ConfirmActionDialog(
            action             = action,
            skillListViewModel = skillListViewModel,
            onDismiss          = { pendingAction = null },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  技能卡片
// ─────────────────────────────────────────────────────────────

@Composable
private fun SkillCard(
    skill: SkillEntity,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val isActive = skill.status == SkillStatus.ACTIVE.name

    WorldCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        cornerRadius = Radius.sm,
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier          = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text       = skill.name,
                    style      = type.cardTitle,
                    color      = if (isActive) colors.textPrimary else colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                    modifier   = Modifier.weight(1f),
                )
                // 状态徽标
                StatusBadge(isActive = isActive, accentColor = accentColor)
            }

            Spacer(Modifier.height(Spacing.xs))

            Text(
                text  = skill.shortDescriptor,
                style = type.caption,
                color = colors.textSecondary,
                maxLines = 2,
            )

            // 使用统计
            Row(
                modifier = Modifier.padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    text  = "使用 ${skill.usageCount} 次",
                    style = type.label,
                    color = colors.textDisabled,
                )
                skill.lastUsedAt?.let { ts ->
                    Text(
                        text  = "最近 ${TimeFormatUtils.formatShortDate(ts)}",
                        style = type.label,
                        color = colors.textDisabled,
                    )
                } ?: Text(
                    text  = "未使用过",
                    style = type.label,
                    color = colors.textDisabled,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(isActive: Boolean, accentColor: Color) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val bgColor = if (isActive) accentColor.copy(alpha = 0.12f) else colors.bgElevated
    val textColor = if (isActive) accentColor else colors.textDisabled

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(bgColor)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
    ) {
        Text(
            text  = if (isActive) "使用中" else "已废弃",
            style = type.label.copy(fontWeight = FontWeight.Medium),
            color = textColor,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  详情 Bottom Sheet（完整内容 + 变更日志 + 操作入口）
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillDetailSheet(
    skill: SkillEntity,
    accentColor: Color,
    skillListViewModel: SkillListViewModel,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDeprecate: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val editLog by skillListViewModel.observeEditLog(skill.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val isActive = skill.status == SkillStatus.ACTIVE.name

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(bottom = Spacing.xl),
        ) {
            // 标题行
            Row(
                modifier          = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text       = skill.name,
                    style      = type.cardTitle,
                    color      = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.weight(1f),
                )
                StatusBadge(isActive = isActive, accentColor = accentColor)
            }

            Spacer(Modifier.height(Spacing.xs))

            // shortDescriptor
            Text(
                text  = skill.shortDescriptor,
                style = type.body,
                color = colors.textSecondary,
            )

            skill.category?.let { cat ->
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text  = "分类：$cat",
                    style = type.label,
                    color = colors.textDisabled,
                )
            }

            Spacer(Modifier.height(Spacing.md))

            // 完整内容
            Text(
                text  = "完整方法",
                style = type.cardTitle,
                color = accentColor,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text  = skill.fullContent,
                style = type.body,
                color = colors.textPrimary,
            )

            // 统计信息
            Spacer(Modifier.height(Spacing.md))
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text("v${skill.version}", style = type.label, color = colors.textDisabled)
                Text("使用 ${skill.usageCount} 次", style = type.label, color = colors.textDisabled)
                Text("成功 ${skill.successCount}", style = type.label, color = colors.textDisabled)
                Text("失败 ${skill.failureCount}", style = type.label, color = colors.textDisabled)
                skill.lastUsedAt?.let {
                    Text("最近 ${TimeFormatUtils.formatShortDate(it)}", style = type.label, color = colors.textDisabled)
                }
            }

            // ── 变更日志时间线 ────────────────────────────────
            if (editLog.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text  = "变更日志",
                    style = type.cardTitle,
                    color = accentColor,
                )
                Spacer(Modifier.height(Spacing.xs))
                editLog.forEach { log ->
                    EditLogRow(log = log)
                    Spacer(Modifier.height(Spacing.xs))
                }
            }

            // ── 操作入口 ──────────────────────────────────────
            Spacer(Modifier.height(Spacing.lg))
            Row(
                modifier          = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ActionButton(
                    label       = "编辑",
                    icon        = AppIcons.Edit,
                    color       = accentColor,
                    onClick     = onEdit,
                    modifier    = Modifier.weight(1f),
                )
                if (isActive) {
                    ActionButton(
                        label    = "废弃",
                        icon     = AppIcons.Block,
                        color    = colors.textSecondary,
                        onClick  = onDeprecate,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    ActionButton(
                        label    = "恢复",
                        icon     = AppIcons.Restore,
                        color    = accentColor,
                        onClick  = onRestore,
                        modifier = Modifier.weight(1f),
                    )
                }
                ActionButton(
                    label       = "删除",
                    icon        = AppIcons.Delete,
                    color       = colors.taskFailed,
                    onClick     = onDelete,
                    modifier    = Modifier.weight(1f),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  变更日志行
// ─────────────────────────────────────────────────────────────

@Composable
private fun EditLogRow(log: SkillEditLogEntity) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    WorldCard(cornerRadius = Radius.xs) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Row(
                modifier          = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text  = log.changeSummary,
                    style = type.caption.copy(fontWeight = FontWeight.Medium),
                    color = colors.textPrimary,
                )
                Text(
                    text  = TimeFormatUtils.formatDateTime(log.timestamp),
                    style = type.label,
                    color = colors.textDisabled,
                )
            }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text  = "由 ${if (log.actor == "AGENT") "Agent" else "用户"}",
                    style = type.label,
                    color = colors.textSecondary,
                )
                log.reason?.let { reason ->
                    if (reason.isNotEmpty()) {
                        Text(
                            text  = "原因：$reason",
                            style = type.label,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  编辑弹窗
// ─────────────────────────────────────────────────────────────

@Composable
private fun SkillEditDialog(
    skill: SkillEntity,
    onDismiss: () -> Unit,
    onSave: (name: String, shortDesc: String, fullContent: String, category: String?) -> Unit,
) {
    var name        by remember { mutableStateOf(skill.name) }
    var shortDesc   by remember { mutableStateOf(skill.shortDescriptor) }
    var fullContent by remember { mutableStateOf(skill.fullContent) }
    var category    by remember { mutableStateOf(skill.category ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text("编辑技能") },
        text   = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value       = name,
                    onValueChange = { name = it },
                    label       = { Text("技能名称") },
                    singleLine  = true,
                    modifier    = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value       = shortDesc,
                    onValueChange = { shortDesc = it },
                    label       = { Text("一句话描述") },
                    singleLine  = true,
                    modifier    = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value       = category,
                    onValueChange = { category = it },
                    label       = { Text("分类（可选）") },
                    singleLine  = true,
                    modifier    = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value       = fullContent,
                    onValueChange = { fullContent = it },
                    label       = { Text("完整方法") },
                    modifier    = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 120.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cat = category.trim().takeIf { it.isNotEmpty() }
                    onSave(name.trim(), shortDesc.trim(), fullContent.trim(), cat)
                },
                enabled = name.isNotBlank() && shortDesc.isNotBlank() && fullContent.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  操作确认弹窗
// ─────────────────────────────────────────────────────────────

private sealed class PendingSkillAction(val skill: SkillEntity) {
    class Deprecate(skill: SkillEntity) : PendingSkillAction(skill)
    class Restore(skill: SkillEntity)   : PendingSkillAction(skill)
    class Delete(skill: SkillEntity)    : PendingSkillAction(skill)
}

@Composable
private fun ConfirmActionDialog(
    action: PendingSkillAction,
    skillListViewModel: SkillListViewModel,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }

    val (title, message, confirmLabel, needsReason) = when (action) {
        is PendingSkillAction.Deprecate -> Quad(
            "废弃技能",
            "废弃后「${action.skill.name}」将不再出现在技能目录中，但记录保留，可随时恢复。",
            "废弃",
            true,
        )
        is PendingSkillAction.Restore -> Quad(
            "恢复技能",
            "将「${action.skill.name}」恢复为使用中状态。",
            "恢复",
            false,
        )
        is PendingSkillAction.Delete -> Quad(
            "彻底删除",
            "「${action.skill.name}」将被永久删除，包括所有变更日志，无法恢复。",
            "删除",
            false,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text(title) },
        text   = {
            Column {
                Text(message)
                if (needsReason) {
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value       = reason,
                        onValueChange = { reason = it },
                        label       = { Text("废弃原因（可选）") },
                        singleLine  = true,
                        modifier    = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (action) {
                        is PendingSkillAction.Deprecate ->
                            skillListViewModel.deprecate(action.skill.id, reason.trim().ifEmpty { "用户手动废弃" })
                        is PendingSkillAction.Restore ->
                            skillListViewModel.restore(action.skill.id)
                        is PendingSkillAction.Delete ->
                            skillListViewModel.delete(action.skill.id)
                    }
                    onDismiss()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  小组件
// ─────────────────────────────────────────────────────────────

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = ZaijianTheme.typography

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .border(0.5.dp, color.copy(alpha = 0.3f), RoundedCornerShape(Radius.sm))
            .clickable { onClick() }
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment    = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = color,
            modifier           = Modifier.padding(end = Spacing.xs),
        )
        Text(
            text  = label,
            style = type.caption,
            color = color,
        )
    }
}

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
