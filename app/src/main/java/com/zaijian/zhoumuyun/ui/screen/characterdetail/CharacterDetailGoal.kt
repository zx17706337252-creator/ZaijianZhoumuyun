package com.zaijian.zhoumuyun.ui.screen.characterdetail


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.CharacterGoalEntity
import com.zaijian.zhoumuyun.data.db.entity.GoalHorizon
import com.zaijian.zhoumuyun.ui.viewmodel.GoalDraft
import com.zaijian.zhoumuyun.ui.viewmodel.GoalViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.IdentityViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryFilter
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryUiItem
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.PregnancyViewModel
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.data.model.isDaughterMother
import com.zaijian.zhoumuyun.ui.theme.GoldDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.FloorEnum
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.model.accentLight
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.AppColors
import com.zaijian.zhoumuyun.ui.theme.AppTypography
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.Elevation
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.RingWidth
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.util.ZLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.compose.material3.FilterChip

@Composable
internal fun GoalPanel(
    state: com.zaijian.zhoumuyun.ui.viewmodel.GoalUiState,
    accentColor: Color,
    onOpenNew: () -> Unit,
    onOpenEdit: (CharacterGoalEntity) -> Unit,
    onDelete: (String) -> Unit,
    onDeactivate: (String) -> Unit,
    onProgressChange: (String, Float) -> Unit,
    // Phase 27：跳转到 LearningGoalScreen（含规则面板）的完整学习闭环管理页
    onNavigateToGoals: () -> Unit = {},
    // P6 专长进化系统：跳转到专长档案页
    onNavigateToSpecialty: () -> Unit = {},
    // U1 修复：从专长页直通竞赛页（domain 由专长档案决定）
    onNavigateToCompetition: (domain: String) -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // ── 说明文字 ─────────────────────────────────────────
        Text(
            text  = "目标驱动角色的状态与行为。Goal 越具体，Presence 生成越自然。",
            style = type.caption,
            color = colors.textSecondary,
        )

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color       = accentColor,
                    strokeWidth = 2.dp,
                    modifier    = Modifier.size(24.dp),
                )
            }
        } else {
            // ── 目标列表 ───────────────────────────────────────
            if (state.goals.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = "还没有目标，点击下方添加",
                        style = type.caption,
                        color = colors.textDisabled,
                    )
                }
            } else {
                state.goals.forEach { goal ->
                    GoalCard(
                        goal        = goal,
                        accentColor = accentColor,
                        onEdit      = { onOpenEdit(goal) },
                        onDelete    = { onDelete(goal.id) },
                        onProgressChange = { p -> onProgressChange(goal.id, p) },
                    )
                }
            }

            // ── 新增按钮 ───────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(Radius.sm))
                    .clickable { onOpenNew() }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Add,
                    contentDescription = "新增目标",
                    tint               = accentColor,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(text = "新增目标", style = type.label, color = accentColor)
            }

            // Phase 27：进入完整学习闭环管理（含规则面板、六步流程可视化）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accentColor.copy(alpha = 0.08f))
                    .clickable { onNavigateToGoals() }
                    .padding(vertical = 10.dp, horizontal = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "查看学习闭环与规则面板",
                    style = type.label,
                    color = accentColor,
                )
                Icon(
                    imageVector        = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint               = accentColor,
                    modifier           = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.height(6.dp))

            // P6 专长进化系统：专长养成入口（让她针对某个方向主动练习、积累风格）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accentColor.copy(alpha = 0.08f))
                    .clickable { onNavigateToSpecialty() }
                    .padding(vertical = 10.dp, horizontal = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "专长养成（她的自主练习与风格积累）",
                    style = type.label,
                    color = accentColor,
                )
                Icon(
                    imageVector        = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint               = accentColor,
                    modifier           = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))
    }
}

@Composable
private fun GoalCard(
    goal: CharacterGoalEntity,
    accentColor: Color,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onProgressChange: (Float) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val horizon = runCatching { GoalHorizon.valueOf(goal.timeHorizon) }.getOrDefault(GoalHorizon.MID_TERM)
    val horizonLabel = when (horizon) {
        GoalHorizon.SHORT_TERM -> "短期"
        GoalHorizon.MID_TERM   -> "中期"
        GoalHorizon.LONG_TERM  -> "长期"
    }
    val priorityColor = when {
        goal.priority >= 4 -> accentColor
        goal.priority == 3 -> accentColor.copy(alpha = 0.7f)
        else               -> colors.textDisabled
    }

    // WorldCard 接入（精修方案 v1.3 第2/6节）：L0-L2 常态层由 WorldCard 内部承担，
    // L3 身份脊取本页角色 accentColor——GoalCard 展示的目标始终归属"当前查看的这位角色"，
    // 与 TaskCenterScreen 里"按任务归属的角色各自不同"语义一致，只是这里恒定指向同一人。
    com.zaijian.zhoumuyun.ui.design.WorldCard(
        modifier = Modifier.fillMaxWidth(),
        ownerAccent = accentColor,
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 标题行
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                // 优先级指示点
                repeat(goal.priority.coerceIn(1, 5)) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(priorityColor)
                    )
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text  = goal.title.ifEmpty { "（未命名目标）" },
                    style = type.navTitle.copy(fontWeight = FontWeight.Medium),
                    color = colors.textPrimary,
                )
            }
            // 时间范围标签
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(accentColor.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(text = horizonLabel, style = type.caption, color = accentColor)
            }
        }

        // 描述
        if (goal.description.isNotEmpty()) {
            Text(
                text  = goal.description,
                style = type.caption,
                color = colors.textSecondary,
            )
        }

        // 进度条
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "进度", style = type.caption, color = colors.textDisabled)
                Text(
                    text  = "${(goal.progress * 100).toInt()}%",
                    style = type.caption,
                    color = accentColor,
                )
            }
            // 进度条：角色专属色 → Gold 横向渐变（§6.6 设计规范）
            val progressFraction = goal.progress.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .drawBehind {
                        // 轨道底色
                        drawRect(accentColor.copy(alpha = 0.15f))
                        // 渐变填充（角色色 → Gold）
                        if (progressFraction > 0f) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(accentColor, Palette.Gold),
                                    startX = 0f,
                                    endX   = size.width * progressFraction,
                                ),
                                size = Size(size.width * progressFraction, size.height),
                            )
                        }
                    },
            )
        }

        // 操作行
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                text     = "编辑",
                style    = type.caption,
                color    = accentColor,
                modifier = Modifier
                    .clickable { onEdit() }
                    .padding(4.dp),
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text     = "删除",
                style    = type.caption,
                color    = colors.textDisabled,
                modifier = Modifier
                    .clickable { onDelete() }
                    .padding(4.dp),
            )
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalDraftSheet(
    draft: GoalDraft,
    accentColor: Color,
    activeProjects: List<com.zaijian.zhoumuyun.data.db.entity.ProjectEntity> = emptyList(),
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPriorityChange: (Int) -> Unit,
    onHorizonChange: (GoalHorizon) -> Unit,
    onProjectChange: (String?) -> Unit = {},
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // P2-16 修复：从手工模拟的半透明遮罩+底部卡片改为系统的 ModalBottomSheet，
    // 恢复系统返回手势、拖拽关闭、键盘避让等标准行为。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = if (colors.isDark) colors.bgCard else colors.bgBase,
        shape            = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // 标题栏
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text  = if (draft.id == null) "新增目标" else "编辑目标",
                    style = type.navTitle,
                    color = colors.textPrimary,
                )
                Text(
                    text     = "取消",
                    style    = type.caption,
                    color    = colors.textSecondary,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(4.dp),
                )
            }

            // 目标名称
            IdentityField(
                label         = "目标名称",
                placeholder   = "例如「整理永恒之家的架构文档」",
                value         = draft.title,
                onValueChange = onTitleChange,
                accentColor   = accentColor,
                minLines      = 1,
            )

            // 目标描述
            IdentityField(
                label         = "描述（可选）",
                placeholder   = "为什么重要，具体要做什么…",
                value         = draft.description,
                onValueChange = onDescriptionChange,
                accentColor   = accentColor,
                minLines      = 2,
            )

            // 时间维度选择
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "时间维度", style = type.label, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalHorizon.values().forEach { h ->
                        val label = when (h) {
                            GoalHorizon.SHORT_TERM -> "短期"
                            GoalHorizon.MID_TERM   -> "中期"
                            GoalHorizon.LONG_TERM  -> "长期"
                        }
                        val selected = draft.timeHorizon == h
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(if (selected) accentColor else accentColor.copy(alpha = 0.1f))
                                .border(
                                    width = if (selected) 0.dp else 0.5.dp,
                                    color = accentColor.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(Radius.sm),
                                )
                                .clickable { onHorizonChange(h) }
                                .padding(horizontal = Spacing.md, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text  = label,
                                style = type.label,
                                color = if (selected) Color.White else accentColor,
                            )
                        }
                    }
                }
            }

            // 优先级选择（1-5 星）
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "优先级", style = type.label, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    (1..5).forEach { i ->
                        val active = i <= draft.priority
                        // E fix: 用 40dp Box 包裹 28dp 图标，触摸热区扩大到 40dp 防误触
                        Box(
                            modifier         = Modifier
                                .size(40.dp)
                                .clickable { onPriorityChange(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector        = if (active) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                contentDescription = "${i}星优先级",
                                tint               = if (active) accentColor else colors.textDisabled,
                                modifier           = Modifier.size(28.dp),
                            )
                        }
                    }
                }
            }

            // 关联项目下拉\uff08Step 2\uff09
            if (activeProjects.isNotEmpty()) {
                val growthGreen = Color(0xFF7BAE7F)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text  = "关联项目\uff08可选\uff09",
                        style = type.label,
                        color = colors.textSecondary,
                    )
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val options = listOf<com.zaijian.zhoumuyun.data.db.entity.ProjectEntity?>(null) + activeProjects
                        items(options) { proj ->
                            val isSelected = draft.relatedProjectId == proj?.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(
                                        if (isSelected) growthGreen
                                        else growthGreen.copy(alpha = 0.12f)
                                    )
                                    .clickable { onProjectChange(proj?.id) }
                                    .padding(horizontal = Spacing.md, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text  = proj?.title ?: "无",
                                    style = type.label,
                                    color = if (isSelected) Color.White else growthGreen,
                                )
                            }
                        }
                    }
                }
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
                Text(
                    text  = "保存目标",
                    style = type.button,
                    color = Color.White,
                )
            }
        }
    }
}

