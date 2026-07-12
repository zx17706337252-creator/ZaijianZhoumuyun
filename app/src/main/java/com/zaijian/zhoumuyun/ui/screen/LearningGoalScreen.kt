package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle // P1-11-2
import coil.compose.AsyncImage
import com.zaijian.zhoumuyun.data.db.entity.LearningGoalEntity
import com.zaijian.zhoumuyun.data.db.entity.LearningGoalStatus
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskStatus
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.theme.*
import com.zaijian.zhoumuyun.ui.viewmodel.GoalWithRules
import com.zaijian.zhoumuyun.ui.viewmodel.GrowthSummaryData
import com.zaijian.zhoumuyun.ui.viewmodel.LearningGoalViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.ProjectGrowthData
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────
//  LearningGoalScreen（Phase 23 新增，Phase 27 扩展）
//
//  Phase 27 新增功能：
//  - 目标卡片底部可展开「规则面板」：
//      - 已锁定规则（🔒 isLocked=true）：固化进入 Rule Layer 的能力规律
//      - 候选规则（⏳ isLocked=false）：Agent 通过 rule_distill 写入，待积累锁定
//  - 面板展开/收起状态由 ViewModel 管理（角色切换时重置）
//  - 规则计数徽章显示在目标卡片底部
//  - 空规则状态提示 Agent 通过对话积累经验
//
//  入口：ProfileScreen 或 CharacterDetailScreen 跳转，
//        也可从底部导航「任务」Tab 旁新增入口（Phase 27 整合）。
// ─────────────────────────────────────────────────────────────

@Composable
fun LearningGoalScreen(
    initialCharacterId: Int        = 1,
    showBackButton: Boolean        = true,
    onBack: () -> Unit             = {},
    onNavigateToChat: (Int) -> Unit = {},
    onNavigateToProject: (String) -> Unit = {},
    viewModel: LearningGoalViewModel = viewModel(),
) {
    val colors       = ZaijianTheme.colors
    val type         = ZaijianTheme.typography
    val uiState      by viewModel.uiState.collectAsStateWithLifecycle()
    val draft        by viewModel.draft.collectAsStateWithLifecycle()

    // 当前选中角色（本地状态驱动 ViewModel 切换）
    var selectedCharacterId by remember(initialCharacterId) { mutableIntStateOf(initialCharacterId) }

    // Avatar 同步修复（B2 完整修复）：从 ViewModel uiState 读取，
    // 彻底消除 Composable 内直连 AppDatabase 的架构违规。
    val avatarOverrides = uiState.avatarOverrides

    // 长按删除确认
    var goalToDelete by remember { mutableStateOf<LearningGoalEntity?>(null) }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // 初始化 ViewModel（角色切换时重新订阅）
    LaunchedEffect(selectedCharacterId) {
        viewModel.init(selectedCharacterId)
    }

    // Snackbar 消息
    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.bgBase,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            // [v43 修复，v44 更新] 本页有自己独立的 Scaffold，跟 AppNavigation.kt 里
            // App 级别手绘的底部导航栏完全是两套不相干的布局系统——这个内层
            // Scaffold 的 contentWindowInsets 已清零，Material3 默认 FAB
            // 定位逻辑不知道外面还有一条底部导航栏，所以 FAB 贴着屏幕真实
            // 底边摆放，正好被外层导航栏盖住（2026-07-06 用户反馈截图，
            // 该问题在 CompetitionScreen/SpecialtyEvolutionScreen 里不存在，
            // 因为那两个是详情页，不在 bottomNavRoutes 里、没有外层导航栏）。
            // v44 改用 LocalBottomBarHeight（唯一权威来源）而不是自己重新
            // 读 WindowInsets.navigationBars，跟本文件其他地方口径统一。
            // 用 offset 把 FAB 向上抬高"外层导航栏高度 + 一点呼吸间距"，
            // 让它稳稳落在导航栏上方。
            val bottomBarHeight = LocalBottomBarHeight.current
            FloatingActionButton(
                onClick            = { viewModel.openNewDraft() },
                containerColor     = colors.accent,
                contentColor       = colors.bgBase,
                shape              = CircleShape,
                elevation          = FloatingActionButtonDefaults.elevation(4.dp),
                modifier           = Modifier.offset(y = -(bottomBarHeight + Spacing.sm)),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "新建学习目标")
            }
        },
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {

            // ── 顶部栏 ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(Spacing.topBarHeight)
                    .padding(horizontal = Spacing.screenHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBackButton) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                            tint               = colors.textPrimary,
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(
                    text  = "学习目标",
                    style = type.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                // 目标数量徽章
                val activeCount = uiState.goals.count { it.isActive && it.status != LearningGoalStatus.COMPLETED.name }
                if (activeCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colors.accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = "$activeCount",
                            // P3-32 修复：移除硬编码 fontSize
                            style = type.caption.copy(fontWeight = FontWeight.Bold),
                            color = colors.accent,
                        )
                    }
                }
            }

            // ── 角色选择器（横向滚动） ─────────────────────────
            CharacterSelectorRow(
                selectedId     = selectedCharacterId,
                onSelect       = { selectedCharacterId = it },
                avatarOverrides = avatarOverrides,
            )

            Spacer(Modifier.height(Spacing.sm))

            // ── 主内容列表 ────────────────────────────────────
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            } else {
                val selectedCharacter = DefaultCharacters.find { it.id == selectedCharacterId }
                val characterName = selectedCharacter?.name ?: "角色"

                LazyColumn(
                    contentPadding = PaddingValues(
                        start  = Spacing.screenHorizontal,
                        end    = Spacing.screenHorizontal,
                        top    = Spacing.sm,
                        // [v44 修复] 改用 LocalBottomBarHeight（唯一权威来源，见
                        // AppNavigation.kt 定义处说明），不再自己重新读取
                        // WindowInsets.navigationBars 计算。
                        bottom = LocalBottomBarHeight.current + Spacing.md,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    // ── 今日概览卡 ─────────────────────────────
                    item(key = "summary_card") {
                        GrowthSummaryCard(
                            characterName = characterName,
                            summary       = uiState.growthSummary,
                            accentColor   = selectedCharacter?.accentColor,
                        )
                    }

                    // ── 进化项目区块标题 ───────────────────────
                    item(key = "project_header") {
                        GrowthSectionHeader(
                            icon  = Icons.Outlined.Spa,
                            label = "进化项目",
                            color = Palette.GrowthGreen,
                        )
                    }

                    if (uiState.projectCards.isEmpty()) {
                        item(key = "project_empty") {
                            ProjectEmptyHint(characterName = characterName)
                        }
                    } else {
                        items(uiState.projectCards, key = { "proj_${it.project.id}" }) { card ->
                            ProjectGrowthCard(
                                data        = card,
                                onClick     = { onNavigateToProject(card.project.id) },
                                accentColor = selectedCharacter?.accentColor,
                            )
                        }
                    }

                    // ── 学习目标区块标题 ───────────────────────
                    item(key = "goal_header") {
                        Spacer(Modifier.height(Spacing.xs))
                        GrowthSectionHeader(
                            icon  = Icons.Outlined.EmojiEvents,
                            label = "学习目标",
                            color = colors.accent,
                        )
                    }

                    if (uiState.goalsWithRules.isEmpty()) {
                        item(key = "goal_empty") {
                            EmptyGoalHint(characterName = characterName)
                        }
                    } else {
                        val sorted = uiState.goalsWithRules.sortedWith(
                            compareByDescending<GoalWithRules> { it.goal.isActive }
                                .thenByDescending { it.goal.updatedAt }
                        )
                        items(sorted, key = { it.goal.id }) { goalWithRules ->
                            GoalCard(
                                goalWithRules       = goalWithRules,
                                isRulePanelExpanded = goalWithRules.goal.id in uiState.expandedRulePanels,
                                onToggleRulePanel   = { viewModel.toggleRulePanel(goalWithRules.goal.id) },
                                onEdit              = { viewModel.openEditDraft(goalWithRules.goal) },
                                onDelete            = { goalToDelete = goalWithRules.goal },
                                onToggle            = { viewModel.toggleActive(goalWithRules.goal) },
                            )
                        }
                    }

                    // 底部留出 FAB 空间
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    // ── 创建 / 编辑 Bottom Sheet ───────────────────────────────
    if (draft != null) {
        GoalEditSheet(
            draft            = draft!!,
            onTitleChange    = viewModel::onDraftTitleChange,
            onDescChange     = viewModel::onDraftDescriptionChange,
            onDismiss        = viewModel::dismissDraft,
            onSave           = viewModel::saveDraft,
        )
    }

    // ── 删除确认 Dialog ─────────────────────────────────────────
    goalToDelete?.let { goal ->
        AlertDialog(
            onDismissRequest  = { goalToDelete = null },
            title             = { Text("删除目标", style = ZaijianTheme.typography.titleBold) },
            text              = {
                Text(
                    "确定要删除「${goal.title}」？删除后进度记录和关联规则将无法恢复。",
                    style = ZaijianTheme.typography.body,
                    color = ZaijianTheme.colors.textSecondary,
                )
            },
            confirmButton     = {
                TextButton(onClick = {
                    viewModel.delete(goal.id, goal.title)
                    goalToDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton     = {
                TextButton(onClick = { goalToDelete = null }) {
                    Text("取消")
                }
            },
            containerColor    = ZaijianTheme.colors.bgCard,
            titleContentColor = ZaijianTheme.colors.textPrimary,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  GrowthSummaryCard — 角色今日概览卡（P1-B）
// ─────────────────────────────────────────────────────────────

@Composable
private fun GrowthSummaryCard(
    characterName: String,
    summary: GrowthSummaryData,
    accentColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    // P3-55 修复：硬编码绿色统一为主题常量 Palette.GrowthGreen
    val growthGreen = Palette.GrowthGreen

    // WorldCard 接入（精修方案 v1.3 第2/6节）：L0-L2 常态层 + L3 身份脊。
    // 本卡片是「当前选中角色」的今日概览，ownerAccent 取该角色 accentColor
    // （由调用方 LearningGoalScreen 用 selectedCharacterId 查出 DefaultCharacters
    // 对应项传入；理论上恒非 null，但保留可空兜底，未匹配到时不显示身份脊）。
    com.zaijian.zhoumuyun.ui.design.WorldCard(
        modifier    = modifier.fillMaxWidth(),
        ownerAccent = accentColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.cardPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Outlined.Spa,
                    contentDescription = null,
                    tint               = growthGreen,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text  = "$characterName · 今日概览",
                    style = type.label.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
            }

            Spacer(Modifier.height(Spacing.sm))

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SummaryStatItem(
                    icon  = Icons.Outlined.Spa,
                    label = "活跃项目",
                    value = summary.activeProjectCount.toString(),
                    color = growthGreen,
                )
                SummaryStatItem(
                    icon  = Icons.Outlined.Assignment,
                    label = "今日任务",
                    value = summary.todayTaskTotal.toString(),
                    color = colors.accent,
                )
                SummaryStatItem(
                    icon  = Icons.Outlined.CheckCircle,
                    label = "已完成",
                    value = summary.todayTaskDone.toString(),
                    color = Palette.SemanticSuccess,
                )
                SummaryStatItem(
                    icon  = Icons.Outlined.EmojiEvents,
                    label = "学习目标",
                    value = summary.activeGoalCount.toString(),
                    color = colors.accent,
                )
            }
        }
    }
}

@Composable
private fun SummaryStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = color,
            modifier           = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text  = value,
            style = type.body.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
        )
        Text(
            text  = label,
            style = type.small,
            color = colors.textSecondary,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  GrowthSectionHeader — 区块标题行（P1-B）
// ─────────────────────────────────────────────────────────────

@Composable
private fun GrowthSectionHeader(
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
//  ProjectGrowthCard — 单个进化项目今日状态卡（P1-B）
// ─────────────────────────────────────────────────────────────

@Composable
private fun ProjectGrowthCard(
    data: ProjectGrowthData,
    onClick: () -> Unit,
    accentColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    val colors      = ZaijianTheme.colors
    val type        = ZaijianTheme.typography
    // P3-55 修复：硬编码绿色统一为主题常量 Palette.GrowthGreen
    val growthGreen = Palette.GrowthGreen

    val doneCount  = data.todayDoneCount
    val totalCount = data.todayTotalCount
    val progress   = if (totalCount > 0) doneCount.toFloat() / totalCount else 0f

    // WorldCard 接入（精修方案 v1.3 第2/6节）：L0-L2 常态层 + L3 身份脊。
    // ProjectEntity 本身不带角色归属字段，但本页是单角色视图（按
    // selectedCharacterId 切换），ownerAccent 复用调用方传入的当前角色色。
    // 原 Surface 自带 onClick 参数（点击态有 Material 涟漪），WorldCard
    // 无此参数，改为在内层 Column 用 clickable 承接，涟漪效果改由
    // clickable 默认 indication 提供。
    com.zaijian.zhoumuyun.ui.design.WorldCard(
        modifier    = modifier.fillMaxWidth(),
        ownerAccent = accentColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(Spacing.cardPadding),
        ) {
            // ── 标题行 ────────────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.zaijian.zhoumuyun.ui.design.IconBadge(
                    icon               = Icons.Outlined.Spa,
                    contentDescription = null,
                    tint               = growthGreen,
                    size               = 14.dp,
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text     = data.project.title,
                    style    = type.label.copy(fontWeight = FontWeight.SemiBold),
                    color    = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector        = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint               = colors.textDisabled,
                    modifier           = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.height(Spacing.xs))

            // ── 今日任务预览 ──────────────────────────────────
            if (totalCount == 0) {
                Text(
                    text  = "今日尚未规划",
                    style = type.small,
                    color = colors.textDisabled,
                )
            } else {
                // 最多显示 2 条任务预览
                val preview = data.todayTasks.take(2)
                preview.forEach { task ->
                    val isDone = task.status == TaskStatus.COMPLETED.name
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.padding(vertical = 1.dp),
                    ) {
                        Icon(
                            imageVector        = if (isDone) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint               = if (isDone) Palette.SemanticSuccess else colors.textDisabled,
                            modifier           = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text  = task.title,
                            style = type.small.copy(
                                textDecoration = if (isDone)
                                    androidx.compose.ui.text.style.TextDecoration.LineThrough
                                else null,
                            ),
                            color    = if (isDone) colors.textDisabled else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (totalCount > 2) {
                    Text(
                        text  = "+${totalCount - 2} 件",
                        style = type.small,
                        color = colors.textDisabled,
                    )
                }

                Spacer(Modifier.height(Spacing.xs))

                // ── 今日完成率进度条（3dp 细线）────────────────
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress   = { progress },
                        modifier   = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color      = growthGreen,
                        trackColor = colors.border,
                        strokeCap  = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text  = "$doneCount/$totalCount",
                        style = type.small.copy(fontWeight = FontWeight.Medium),
                        color = growthGreen,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ProjectEmptyHint — 进化项目空状态（P1-B）
// ─────────────────────────────────────────────────────────────

@Composable
private fun ProjectEmptyHint(characterName: String) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    Box(
        modifier         = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.bgElevated)
            .padding(Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector        = Icons.Outlined.Spa,
                contentDescription = null,
                tint               = colors.textDisabled,
                modifier           = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text  = "$characterName 还没有参与进化项目",
                style = type.small,
                color = colors.textDisabled,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  角色选择器横向 Row
// ─────────────────────────────────────────────────────────────

@Composable
private fun CharacterSelectorRow(
    selectedId: Int,
    onSelect: (Int) -> Unit,
    avatarOverrides: Map<Int, String> = emptyMap(),
) {
    val colors = ZaijianTheme.colors

    androidx.compose.foundation.lazy.LazyRow(
        contentPadding      = PaddingValues(horizontal = Spacing.screenHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(DefaultCharacters, key = { it.id }) { char ->
            val isSelected = char.id == selectedId
            val borderColor by animateColorAsState(
                targetValue   = if (isSelected) colors.accent else Color.Transparent,
                animationSpec = tween(200),
                label         = "borderColor",
            )

            Column(
                modifier            = Modifier
                    .clickable { onSelect(char.id) }
                    .padding(Spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .border(2.dp, borderColor, CircleShape)
                        .padding(2.dp)
                        .clip(CircleShape),
                ) {
                    AsyncImage(
                        model              = avatarOverrides[char.id]?.takeIf { it.isNotBlank() } ?: char.avatarUrl,
                        contentDescription = char.name,
                        modifier           = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = char.name,
                    // P3-32 修复：移除硬编码 fontSize，11.sp 改用 type.label
                    style = ZaijianTheme.typography.label.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (isSelected) colors.accent else colors.textSecondary,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  目标卡片（Phase 27：新增 isRulePanelExpanded + RulePanel）
// ─────────────────────────────────────────────────────────────

@Composable
private fun GoalCard(
    goalWithRules:       GoalWithRules,
    isRulePanelExpanded: Boolean,
    onToggleRulePanel:   () -> Unit,
    onEdit:              () -> Unit,
    onDelete:            () -> Unit,
    onToggle:            () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val goal   = goalWithRules.goal

    val isCompleted = goal.status == LearningGoalStatus.COMPLETED.name
    val isInactive  = !goal.isActive

    // 进度动画
    val animProgress by animateFloatAsState(
        targetValue   = goal.progress,
        animationSpec = tween(600),
        label         = "progress",
    )

    // 卡片透明度（停用目标）
    val cardAlpha = if (isInactive) 0.5f else 1f

    // WorldCard 接入（精修方案 v1.3 第2/6节）：L0-L2 常态层 + L3 身份脊。
    // ownerAccent 用 goal.characterId（Entity 自带字段）在本函数内部直接
    // 查询 DefaultCharacters，不依赖调用方传参——每个目标自己知道归属谁，
    // 比借用页面级 selectedCharacterId 更严谨，不假设"列表里显示的目标
    // 一定都属于当前选中角色"这个外部上下文。
    val ownerAccent = DefaultCharacters.find { it.id == goal.characterId }?.accentColor

    com.zaijian.zhoumuyun.ui.design.WorldCard(
        modifier    = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha),
        ownerAccent = ownerAccent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.cardPadding),
        ) {
            // ── 标题行 ─────────────────────────────────────────
            Row(
                modifier            = Modifier.fillMaxWidth(),
                verticalAlignment   = Alignment.CenterVertically,
            ) {
                GoalStatusIcon(status = goal.status, isActive = goal.isActive)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text     = goal.title,
                    style    = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color    = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.xs))
                GoalCardMenu(
                    onEdit   = onEdit,
                    onDelete = onDelete,
                    onToggle = onToggle,
                    isActive = goal.isActive,
                )
            }

            // ── 描述（非空时显示） ─────────────────────────────
            if (goal.description.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text     = goal.description,
                    style    = type.caption,
                    color    = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(Spacing.md))

            // ── 进度条 ────────────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val progressColor = when {
                    isCompleted -> Palette.TaskDone
                    isInactive  -> colors.textDisabled
                    else        -> colors.accent
                }

                LinearProgressIndicator(
                    progress      = { animProgress },
                    modifier      = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape),
                    color         = progressColor,
                    trackColor    = colors.border,
                    strokeCap     = StrokeCap.Round,
                )

                Spacer(Modifier.width(Spacing.sm))

                Text(
                    text  = "%.0f%%".format(goal.progress * 100),
                    // P3-32 修复：移除硬编码 fontSize
                    style = type.caption.copy(fontWeight = FontWeight.Bold),
                    color = progressColor,
                )
            }

            Spacer(Modifier.height(Spacing.xs))

            // ── 底部元信息行 ──────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GoalStatusChip(status = goal.status, isActive = goal.isActive)

                Spacer(Modifier.weight(1f))

                val dateStr = remember(goal.updatedAt) {
                    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(goal.updatedAt))
                }
                Text(
                    text  = dateStr,
                    // P3-32 修复：移除硬编码 fontSize，11.sp 改用 type.label
                    style = type.label,
                    color = colors.textDisabled,
                )
            }

            // ── 最近备注（非空时显示） ────────────────────────
            if (!goal.lastUpdateNote.isNullOrEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector        = Icons.Outlined.Notes,
                        contentDescription = null,
                        tint               = colors.textDisabled,
                        modifier           = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text     = goal.lastUpdateNote,
                        // P3-32 修复：移除硬编码 fontSize，11.sp 改用 type.label
                        style    = type.label,
                        color    = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ── Phase 27：规则面板切换按钮 ────────────────────
            if (goalWithRules.totalRuleCount > 0 || goal.isActive) {
                Spacer(Modifier.height(Spacing.sm))
                HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
                Spacer(Modifier.height(Spacing.xs))
                RulePanelToggleRow(
                    lockedCount    = goalWithRules.lockedCount,
                    totalCount     = goalWithRules.totalRuleCount,
                    isExpanded     = isRulePanelExpanded,
                    onToggle       = onToggleRulePanel,
                )
            }

            // ── Phase 27：规则面板内容（可展开） ─────────────
            AnimatedVisibility(
                visible = isRulePanelExpanded,
                enter   = expandVertically(tween(250)) + fadeIn(tween(200)),
                exit    = shrinkVertically(tween(200)) + fadeOut(tween(150)),
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.sm))
                    RulePanel(
                        lockedRules    = goalWithRules.lockedRules,
                        candidateRules = goalWithRules.candidateRules,
                        isGoalActive   = goal.isActive,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Phase 27：规则面板切换行（锁定规则数 + 展开/收起按钮）
// ─────────────────────────────────────────────────────────────

@Composable
private fun RulePanelToggleRow(
    lockedCount: Int,
    totalCount:  Int,
    isExpanded:  Boolean,
    onToggle:    () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val chevronAngle by animateFloatAsState(
        targetValue   = if (isExpanded) 180f else 0f,
        animationSpec = tween(250),
        label         = "chevron",
    )

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Outlined.Shield,
            contentDescription = null,
            tint               = if (lockedCount > 0) colors.accent else colors.textDisabled,
            modifier           = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text  = when {
                totalCount == 0  -> "暂无规则"
                lockedCount == 0 -> "$totalCount 条候选规则"
                else             -> "$lockedCount 条锁定 · $totalCount 条总计"
            },
            // P3-32 修复：移除硬编码 fontSize
            style = type.caption.copy(fontWeight = FontWeight.Medium),
            color = if (lockedCount > 0) colors.accent else colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector        = Icons.Outlined.ExpandMore,
            contentDescription = if (isExpanded) "收起" else "展开",
            tint               = colors.textDisabled,
            modifier           = Modifier
                .size(18.dp)
                .rotate(chevronAngle),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Phase 27：规则面板内容
// ─────────────────────────────────────────────────────────────

@Composable
private fun RulePanel(
    lockedRules:    List<MemoryEntity>,
    candidateRules: List<MemoryEntity>,
    isGoalActive:   Boolean,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.bgBase.copy(alpha = 0.6f))
            .padding(Spacing.sm),
    ) {
        // ── 已锁定规则 ────────────────────────────────────────
        if (lockedRules.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint               = colors.accent,
                    modifier           = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = "已锁定规则（已注入 System Prompt）",
                    // P3-32 修复：移除硬编码 fontSize，11.sp 改用 type.label
                    style = type.label.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.accent,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            lockedRules.forEachIndexed { i, rule ->
                RuleItem(
                    index    = i + 1,
                    content  = rule.content,
                    isLocked = true,
                    importance = rule.importance,
                )
                if (i < lockedRules.lastIndex) Spacer(Modifier.height(4.dp))
            }
        }

        // ── 两组之间的间隔 ─────────────────────────────────
        if (lockedRules.isNotEmpty() && candidateRules.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(
                color    = colors.border.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
            Spacer(Modifier.height(Spacing.sm))
        }

        // ── 候选规则 ──────────────────────────────────────────
        if (candidateRules.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Outlined.Pending,
                    contentDescription = null,
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = "候选规则（积累 ${4 - (candidateRules.firstOrNull()?.importance ?: 3).coerceAtMost(3)} 次后锁定）",
                    // P3-32 修复：移除硬编码 fontSize，11.sp 改用 type.label
                    style = type.label.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            candidateRules.forEachIndexed { i, rule ->
                RuleItem(
                    index      = i + 1,
                    content    = rule.content,
                    isLocked   = false,
                    importance = rule.importance,
                )
                if (i < candidateRules.lastIndex) Spacer(Modifier.height(4.dp))
            }
        }

        // ── 空规则提示 ────────────────────────────────────────
        if (lockedRules.isEmpty() && candidateRules.isEmpty()) {
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint               = colors.textDisabled,
                    modifier           = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text  = if (isGoalActive)
                        "与 Agent 多次对话后，规律将自动提炼为规则"
                    else
                        "目标已停用，规则不再注入",
                    // P3-32 修复：移除硬编码 fontSize，11.sp 改用 type.label
                    style = type.label,
                    color = colors.textDisabled,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Phase 27：单条规则条目
// ─────────────────────────────────────────────────────────────

@Composable
private fun RuleItem(
    index:      Int,
    content:    String,
    isLocked:   Boolean,
    importance: Int,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // 锁定/候选图标
        Text(
            text  = if (isLocked) "🔒" else "⏳",
            // P3-32 修复：移除硬编码 fontSize
            style = type.caption,
            modifier = Modifier.padding(top = 1.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text     = content,
            // P3-32 修复：移除硬编码 fontSize
            style    = type.caption,
            color    = if (isLocked) colors.textPrimary else colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        // 重要度小圆点（仅候选规则显示，辅助判断距离锁定的距离）
        if (!isLocked) {
            Spacer(Modifier.width(4.dp))
            Row(modifier = Modifier.padding(top = 3.dp)) {
                repeat(5) { i ->
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                if (i < importance) colors.accent.copy(alpha = 0.6f)
                                else colors.border
                            ),
                    )
                    if (i < 4) Spacer(Modifier.width(2.dp))
                }
            }
        }
    }
}

// ── 目标状态图标 ──────────────────────────────────────────────

@Composable
private fun GoalStatusIcon(status: String, isActive: Boolean) {
    val colors = ZaijianTheme.colors
    val (icon, tint) = when {
        !isActive                                    -> Icons.Outlined.PauseCircle to colors.textDisabled
        status == LearningGoalStatus.COMPLETED.name  -> Icons.Outlined.CheckCircle to Palette.TaskDone
        status == LearningGoalStatus.PAUSED.name     -> Icons.Outlined.PauseCircle to Palette.TaskPaused
        status == LearningGoalStatus.ABANDONED.name  -> Icons.Outlined.Cancel to Palette.TaskFailed
        else                                         -> Icons.Outlined.RadioButtonUnchecked to colors.accent
    }
    Icon(
        imageVector        = icon,
        contentDescription = null,
        tint               = tint,
        modifier           = Modifier.size(20.dp),
    )
}

// ── 目标状态标签 ──────────────────────────────────────────────

@Composable
private fun GoalStatusChip(status: String, isActive: Boolean) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val (label, chipBg, chipText) = when {
        !isActive                                    -> Triple("已停用",   colors.border,                    colors.textDisabled)
        status == LearningGoalStatus.COMPLETED.name  -> Triple("已完成",   Palette.TaskDone.copy(alpha = 0.12f), Palette.TaskDone)
        status == LearningGoalStatus.PAUSED.name     -> Triple("暂停中",   Palette.TaskPaused.copy(alpha = 0.12f), Palette.TaskPaused)
        status == LearningGoalStatus.ABANDONED.name  -> Triple("已放弃",   Palette.TaskFailed.copy(alpha = 0.12f), Palette.TaskFailed)
        else                                         -> Triple("进行中",   colors.accent.copy(alpha = 0.12f), colors.accent)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(chipBg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            // P3-32 修复：移除硬编码 fontSize，11.sp 改用 type.label
            style = type.label.copy(fontWeight = FontWeight.Medium),
            color = chipText,
        )
    }
}

// ── 操作菜单（编辑 / 停用 / 删除） ───────────────────────────

@Composable
private fun GoalCardMenu(
    onEdit:   () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    isActive: Boolean,
) {
    val colors = ZaijianTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick  = { expanded = true },
            modifier = Modifier
                .size(32.dp)
                .minimumInteractiveComponentSize(),
        ) {
            Icon(
                imageVector        = Icons.Outlined.MoreVert,
                contentDescription = "更多操作",
                tint               = colors.textSecondary,
                modifier           = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text    = { Text("编辑") },
                onClick = { expanded = false; onEdit() },
                leadingIcon = {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
            DropdownMenuItem(
                text    = { Text(if (isActive) "停用" else "激活") },
                onClick = { expanded = false; onToggle() },
                leadingIcon = {
                    val icon = if (isActive) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text    = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDelete() },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  空状态提示
// ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyGoalHint(characterName: String) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    // [v43 修复] 原来用 Modifier.fillMaxSize()：这个 composable 是作为
    // LazyColumn 的一个 item{} 渲染的，而 fillMaxSize() 在 lazy item 里
    // 会撑满整个可视区域高度，且无视外层 LazyColumn 的 contentPadding.bottom
    // ——导致这块内容的居中点被拉低到"包含底部导航栏遮挡区"的那部分空间
    // 里，实际视觉效果就是提示文字被压在底部导航栏后面（2026-07-06
    // 用户反馈截图）。改成固定内边距的自然高度（不撑满），跟随内容自身
    // 大小即可，不再跟 LazyColumn 的可视区高度产生任何关联。
    Box(
        modifier         = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector        = Icons.Outlined.EmojiEvents,
                contentDescription = null,
                tint               = colors.textDisabled,
                modifier           = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text  = "$characterName 暂无学习目标",
                style = type.titleBold,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text  = "点击右下角「+」新建目标\nAgent 将通过 goal_update 工具推进进度\n高分对话后自动提炼规律为规则",
                style = type.caption,
                color = colors.textDisabled,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  创建 / 编辑 Bottom Sheet
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalEditSheet(
    draft:           com.zaijian.zhoumuyun.ui.viewmodel.LearningGoalDraft,
    onTitleChange:   (String) -> Unit,
    onDescChange:    (String) -> Unit,
    onDismiss:       () -> Unit,
    onSave:          () -> Unit,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    val isNew   = draft.id == null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = colors.bgCard,
        dragHandle       = {
            Box(
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(colors.border)
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
        ) {
            // 标题
            Text(
                text  = if (isNew) "新建学习目标" else "编辑学习目标",
                style = type.titleBold,
                color = colors.textPrimary,
            )

            Spacer(Modifier.height(Spacing.md))

            // 目标名称
            OutlinedTextField(
                value         = draft.title,
                onValueChange = { if (it.length <= 50) onTitleChange(it) },
                label         = { Text("目标名称（必填，≤50字）") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Next,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = colors.accent,
                    focusedLabelColor    = colors.accent,
                    unfocusedBorderColor = colors.border,
                    unfocusedLabelColor  = colors.textSecondary,
                    cursorColor          = colors.accent,
                ),
                supportingText = {
                    Text(
                        text  = "${draft.title.length}/50",
                        style = type.caption,
                        color = colors.textDisabled,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                },
            )

            Spacer(Modifier.height(Spacing.sm))

            // 目标描述
            OutlinedTextField(
                value         = draft.description,
                onValueChange = { if (it.length <= 200) onDescChange(it) },
                label         = { Text("目标描述（选填，描述意图和方向）") },
                minLines      = 3,
                maxLines      = 5,
                modifier      = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Done,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = colors.accent,
                    focusedLabelColor    = colors.accent,
                    unfocusedBorderColor = colors.border,
                    unfocusedLabelColor  = colors.textSecondary,
                    cursorColor          = colors.accent,
                ),
                supportingText = {
                    Text(
                        text  = "${draft.description.length}/200",
                        style = type.caption,
                        color = colors.textDisabled,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                },
            )

            Spacer(Modifier.height(Spacing.sm))

            // 提示信息
            if (isNew) {
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(colors.accentSoft)
                        .padding(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.Info,
                        contentDescription = null,
                        tint               = colors.accent,
                        modifier           = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text  = "目标创建后，Agent 通过对话积累规律，高分 Session 后自动提炼为规则",
                        // P3-32 修复：移除硬编码 fontSize，11.sp 改用 type.label
                        style = type.label,
                        color = colors.accent,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            Spacer(Modifier.height(Spacing.sm))

            // 按钮行
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    border  = BorderStroke(1.dp, colors.border),
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = colors.textSecondary),
                ) {
                    Text("取消")
                }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick  = onSave,
                    enabled  = draft.title.trim().isNotEmpty(),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor  = colors.accent,
                        contentColor    = colors.bgBase,
                        disabledContainerColor = colors.border,
                    ),
                ) {
                    Text(if (isNew) "创建" else "保存")
                }
            }
        }
    }
}
