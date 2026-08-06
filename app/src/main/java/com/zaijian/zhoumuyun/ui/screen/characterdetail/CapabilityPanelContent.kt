package com.zaijian.zhoumuyun.ui.screen.characterdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaijian.zhoumuyun.data.db.entity.AgentActivityEventEntity
import com.zaijian.zhoumuyun.data.db.entity.WorkflowJobEntity
import com.zaijian.zhoumuyun.data.repository.AgentActivityRepository
import com.zaijian.zhoumuyun.data.repository.CharacterCapabilitySnapshot
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.CapabilityPanelViewModel
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.WorldCard

// ═══════════════════════════════════════════════════════════════
//  CapabilityPanelContent（Window D-4 · 能力面板 UI）
//
//  挂载位置：CharacterDetailScreen abilityTab==1（"任务"子Tab）
//  数据来源：CapabilityPanelRepository（Window B §2.2.4 定稿契约）
//
//  三个展示区域：
//  1. 进行中的工作流（如有）——展示目标、进度、截止时间
//  2. 已启用工具列表——FlowRow 标签墙
//  3. 最近活动——时间线列表（最近20条心迹事件）
// ═══════════════════════════════════════════════════════════════

@Composable
internal fun CapabilityPanelContent(
    characterId: Int,
    accentColor: Color,
    capabilityPanelViewModel: CapabilityPanelViewModel,
) {
    val uiState by capabilityPanelViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(characterId) {
        capabilityPanelViewModel.load(characterId)
    }

    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        // ── 标题行 + 刷新按钮 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Agent 能力面板",
                style = type.cardTitle,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { capabilityPanelViewModel.refresh() },
                enabled = !uiState.isLoading,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = "刷新",
                    tint = if (uiState.isLoading) colors.textDisabled else accentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = accentColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            uiState.error != null -> {
                ErrorBanner(
                    message = uiState.error!!,
                    onRetry = { capabilityPanelViewModel.refresh() },
                )
            }
            uiState.snapshot != null -> {
                val snapshot = uiState.snapshot!!
                CapabilityPanelBody(
                    snapshot = snapshot,
                    accentColor = accentColor,
                )
            }
        }
    }
}

@Composable
private fun CapabilityPanelBody(
    snapshot: CharacterCapabilitySnapshot,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    // ── 1. 进行中的工作流 ──
    snapshot.runningWorkflowJob?.let { job ->
        RunningWorkflowCard(job = job, accentColor = accentColor)
        Spacer(Modifier.height(Spacing.md))
    }

    // ── 2. 已启用工具列表 ──
    SectionTitle(text = "已启用工具（${snapshot.enabledToolNames.size}）")
    Spacer(Modifier.height(Spacing.xs))

    if (snapshot.enabledToolNames.isEmpty()) {
        // UI 升级 v2.0（帧22）：裸 Text 收口为 EmptyStateView 统一空态组件
        EmptyStateView(
            icon     = AppIcons.Build,
            title    = "尚未注册任何工具",
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        ToolChipFlowRow(tools = snapshot.enabledToolNames, accentColor = accentColor)
    }

    Spacer(Modifier.height(Spacing.md))

    // ── 3. 最近活动 ──
    SectionTitle(text = "最近活动")
    Spacer(Modifier.height(Spacing.xs))

    if (snapshot.recentActivity.isEmpty()) {
        // UI 升级 v2.0（帧22）：裸 Text 收口为 EmptyStateView 统一空态组件
        EmptyStateView(
            icon     = AppIcons.SmartToy,
            title    = "暂无活动记录",
            subtitle = "Agent开始工作后会在这里显示",
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            snapshot.recentActivity.forEach { event ->
                ActivityRow(event = event, accentColor = accentColor)
            }
        }
    }
}

// ── 进行中的工作流卡片 ────────────────────────────────────────

@Composable
private fun RunningWorkflowCard(
    job: WorkflowJobEntity,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    // UI 升级 v2.0（帧22）：裸 Column+background+border 收口为 WorldCard，
    // L0-L2 常态层 + L3 身份脊（ownerAccent=角色色）由 WorldCard 承担，内部内容不变。
    WorldCard(
        modifier = Modifier.fillMaxWidth(),
        ownerAccent = accentColor,
        cornerRadius = Radius.sm,
        accentWash = true,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(
                    imageVector = AppIcons.PlayArrow,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "正在执行任务",
                    style = type.bodyBold,
                    color = accentColor,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = job.goal,
                style = type.body,
                color = colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.sm))

            // 进度条
            val progress = if (job.maxSteps > 0) {
                job.currentStep.toFloat() / job.maxSteps.toFloat()
            } else 0f
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = "进度 ${job.currentStep}/${job.maxSteps}",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.borderSubtle),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accentColor),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(
                    imageVector = AppIcons.Schedule,
                    contentDescription = null,
                    tint = colors.textDisabled,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "截止 ${TimeFormatUtils.formatDateTime(job.deadlineAt)}",
                    style = type.label,
                    color = colors.textDisabled,
                )
            }
        }
    }
}

// ── 工具标签墙 ────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolChipFlowRow(
    tools: List<String>,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        tools.forEach { toolName ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(colors.bgElevated)
                    .border(0.5.dp, colors.borderSubtle, RoundedCornerShape(Radius.xs))
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Build,
                    contentDescription = null,
                    tint = accentColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = toolName,
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

// ── 活动记录行 ────────────────────────────────────────────────

@Composable
private fun ActivityRow(
    event: AgentActivityEventEntity,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    val (eventLabel, eventIcon, eventTint) = when (event.eventType) {
        AgentActivityRepository.EventType.TOOL_CALL -> Triple(
            "工具调用",
            AppIcons.Build,
            accentColor.copy(alpha = 0.7f),
        )
        AgentActivityRepository.EventType.DEGRADE_RETRY -> Triple(
            "降级重试",
            AppIcons.HourglassEmpty,
            Palette.SemanticWarning,
        )
        AgentActivityRepository.EventType.DEGRADE_SWITCH -> Triple(
            "降级切换",
            AppIcons.Refresh,
            Palette.SemanticInfo,
        )
        AgentActivityRepository.EventType.DEGRADE_GIVEUP -> Triple(
            "降级放弃",
            AppIcons.Error,
            Palette.SemanticError,
        )
        AgentActivityRepository.EventType.SKILL_CREATE -> Triple(
            "创建技能",
            AppIcons.Build,
            Palette.SemanticSuccess,
        )
        AgentActivityRepository.EventType.SKILL_INVOKE -> Triple(
            "调用技能",
            AppIcons.Build,
            Palette.SemanticInfo,
        )
        else -> Triple(
            event.eventType,
            AppIcons.Schedule,
            colors.textDisabled,
        )
    }

    val (outcomeLabel, outcomeTint) = when (event.outcome) {
        AgentActivityRepository.Outcome.SUCCESS -> "成功" to Palette.SemanticSuccess
        AgentActivityRepository.Outcome.FAIL -> "失败" to Palette.SemanticError
        AgentActivityRepository.Outcome.TIMEOUT -> "超时" to Palette.SemanticWarning
        null -> "进行中" to Palette.SemanticNeutral
        else -> event.outcome to colors.textDisabled
    }

    // UI 升级 v2.0（帧22）：裸 Row+background 收口为 WorldCard(cornerRadius=Radius.sm)，
    // L0-L2 常态层由 WorldCard 承担，内部内容不变。
    WorldCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = Radius.sm,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = eventIcon,
                contentDescription = null,
                tint = eventTint,
                modifier = Modifier.size(14.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        text = eventLabel,
                        style = type.caption.copy(fontWeight = FontWeight.Medium),
                        color = colors.textPrimary,
                    )
                    event.toolName?.let { tool ->
                        Text(
                            text = "· $tool",
                            style = type.label,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                event.outputSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(
                        text = summary,
                        style = type.label,
                        color = colors.textDisabled,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = outcomeLabel,
                    style = type.label.copy(fontWeight = FontWeight.Medium),
                    color = outcomeTint,
                )
                Text(
                    text = formatRelativeTime(event.createdAt),
                    style = type.label,
                    color = colors.textDisabled,
                )
            }
        }
    }
}

// ── 共享小组件 ────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    Text(
        text = text,
        style = type.cardTitle,
        color = colors.textPrimary,
    )
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(Palette.SemanticError.copy(alpha = 0.08f))
            .border(0.5.dp, Palette.SemanticError.copy(alpha = 0.2f), RoundedCornerShape(Radius.sm))
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = AppIcons.Error,
            contentDescription = null,
            tint = Palette.SemanticError,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = message,
            style = type.caption,
            color = Palette.SemanticError,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = AppIcons.Refresh,
                contentDescription = "重试",
                tint = Palette.SemanticError,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 格式化相对时间（如"3分钟前"、"昨天 14:30"）。 */
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        diff < 172_800_000 -> "昨天"
        diff < 604_800_000 -> "${diff / 86_400_000}天前"
        else -> TimeFormatUtils.formatMonthDayDash(timestamp)
    }
}
