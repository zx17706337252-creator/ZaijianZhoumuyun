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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.zaijian.zhoumuyun.domain.DistillationEngine
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.component.RootTabTopBar
import com.zaijian.zhoumuyun.ui.theme.*
import com.zaijian.zhoumuyun.ui.viewmodel.GoalWithRules
import com.zaijian.zhoumuyun.ui.viewmodel.GrowthSummaryData
import com.zaijian.zhoumuyun.ui.viewmodel.LearningGoalViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.ProjectGrowthData
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.DangerVelvetButton
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.GoldPrimaryButton
import com.zaijian.zhoumuyun.ui.design.GoldPillSegmentedControl

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
    onNavigateToCompetition: (String) -> Unit = {},
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

    // UI 升级 v2.0（§IA整合）：成长Tab三段式分段控件
    var growthTab by rememberSaveable { mutableIntStateOf(0) }

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
                elevation          = FloatingActionButtonDefaults.elevation(Elevation.elevated),
                modifier           = Modifier.offset(y = -(bottomBarHeight + Spacing.sm)),
            ) {
                Icon(AppIcons.Add, contentDescription = "新建学习目标")
            }
        },
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {

            // ── 顶部栏 ─────────────────────────────────────────
            // D-2 统一顶栏：内联 Row → DetailTopBar / RootTabTopBar
            val activeCount = remember(uiState.goals) {
                uiState.goals.count { it.isActive && it.status != LearningGoalStatus.COMPLETED.name }
            }
            val allExpanded = uiState.goals.isNotEmpty() &&
                uiState.goals.all { it.id in uiState.expandedRulePanels }
            val topBarActions: @Composable RowScope.() -> Unit = {
                // [C2#6 修复] 跳转到当前选中角色的聊天页
                IconButton(onClick = { onNavigateToChat(selectedCharacterId) }) {
                    Icon(
                        imageVector        = AppIcons.PrivateChat,
                        contentDescription = "去聊天",
                        tint               = colors.textSecondary,
                    )
                }
                // 目标数量徽章
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
                            style = type.caption.copy(fontWeight = FontWeight.Bold),
                            color = colors.accent,
                        )
                    }
                }
                // 展开全部/折叠全部规则面板
                if (uiState.goals.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            if (allExpanded) viewModel.collapseAllRulePanels()
                            else viewModel.expandAllRulePanels()
                        },
                    ) {
                        Icon(
                            imageVector = if (allExpanded) {
                                AppIcons.UnfoldLess
                            } else {
                                AppIcons.UnfoldMore
                            },
                            contentDescription = if (allExpanded) "折叠全部规则" else "展开全部规则",
                            tint = colors.textSecondary,
                        )
                    }
                }
            }
            if (showBackButton) {
                DetailTopBar(
                    title    = "学习目标",
                    onBack   = onBack,
                    headerBg = colors.bgBase,
                    actions  = topBarActions,
                )
            } else {
                RootTabTopBar(
                    title    = "学习目标",
                    headerBg = colors.bgBase,
                    actions  = topBarActions,
                )
            }

            // ── 角色选择器（横向滚动） ─────────────────────────
            CharacterSelectorRow(
                selectedId     = selectedCharacterId,
                onSelect       = { selectedCharacterId = it },
                avatarOverrides = avatarOverrides,
            )

            Spacer(Modifier.height(Spacing.sm))

            // ── 成长Tab三段式分段控件（目标/专长/竞赛）─────────
            GoldPillSegmentedControl(
                items        = listOf("目标", "专长", "竞赛"),
                selectedIndex = growthTab,
                onSelect      = { growthTab = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
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
                    // ── 今日概览卡（三段共用）─────────────────────
                    item(key = "summary_card") {
                        GrowthSummaryCard(
                            characterName = characterName,
                            summary       = uiState.growthSummary,
                            accentColor   = selectedCharacter?.accentColor,
                        )
                    }

                    // ── 分段内容 ─────────────────────────────────
                    if (growthTab == 0) {
                        // ═══ 目标段 ═══
                        item(key = "goal_header") {
                            GrowthSectionHeader(
                                icon  = AppIcons.EmojiEvents,
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
                    } else if (growthTab == 1) {
                        // ═══ 专长段 ═══
                        item(key = "project_header") {
                            GrowthSectionHeader(
                                icon  = AppIcons.Spa,
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
                    } else {
                        // ═══ 竞赛段 ═══
                        item(key = "competition_header") {
                            GrowthSectionHeader(
                                icon  = AppIcons.EmojiEvents,
                                label = "竞赛挑战",
                                color = Palette.CompetitionOrange,
                            )
                        }
                        item(key = "competition_entry") {
                            CompetitionEntryCard(
                                characterName = characterName,
                                accentColor   = selectedCharacter?.accentColor ?: colors.accent,
                                onNavigate    = { domain -> onNavigateToCompetition(domain) },
                            )
                        }
                    }

                    // 底部留出 FAB 空间
                    item(key = "bottom_spacer") { Spacer(Modifier.height(80.dp)) }
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
                DangerVelvetButton(
                    text = "删除",
                    onClick = {
                        viewModel.delete(goal.id, goal.title)
                        goalToDelete = null
                    },
                )
            },
            dismissButton     = {
                GhostGoldButton(text = "取消", onClick = { goalToDelete = null })
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
        // UI 升级 v2.0（帧15 拱檐造型）：卡顶 3dp 黄铜渐变拱形檐口装饰，
        // 满宽贴 WorldCard 顶边（RoundedCornerShape 999 顶部 → 拱形）。
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        AppBrushes.goldGradient(),
                        RoundedCornerShape(topStart = 999.dp, topEnd = 999.dp),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.cardPadding),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector        = AppIcons.Spa,
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
                        icon  = AppIcons.Spa,
                        label = "活跃项目",
                        value = summary.activeProjectCount.toString(),
                        color = growthGreen,
                    )
                    SummaryStatItem(
                        icon  = AppIcons.Assignment,
                        label = "今日任务",
                        value = summary.todayTaskTotal.toString(),
                        color = colors.accent,
                    )
                    SummaryStatItem(
                        icon  = AppIcons.CheckCircle,
                        label = "已完成",
                        value = summary.todayTaskDone.toString(),
                        color = Palette.SemanticSuccess,
                    )
                    SummaryStatItem(
                        icon  = AppIcons.EmojiEvents,
                        label = "学习目标",
                        value = summary.activeGoalCount.toString(),
                        color = colors.accent,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  CompetitionEntryCard — 竞赛入口卡（成长Tab · 竞赛段）
//  UI 升级 v2.0（§IA整合）：成长Tab三段式之"竞赛"段的内容卡，
//  展示竞赛机制简介并提供入口。竞赛按专长方向(domain)组织，
//  点击后跳转 CompetitionScreen。
// ─────────────────────────────────────────────────────────────

@Composable
private fun CompetitionEntryCard(
    characterName: String,
    accentColor: Color,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.bgCard)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.md))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 标题行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(
                imageVector        = AppIcons.EmojiEvents,
                contentDescription = null,
                tint               = accentColor,
                modifier           = Modifier.size(20.dp),
            )
            Text(
                text  = "竞赛挑战",
                style = type.cardTitle,
                color = colors.textPrimary,
            )
        }

        // 说明文字
        Text(
            text  = "$characterName 可参与按专长方向组织的竞赛挑战，" +
                    "通过裁判评分与对手对决来检验成长成果。",
            style = type.body,
            color = colors.textSecondary,
        )

        // 默认入口
        GoldPrimaryButton(
            text     = "进入竞赛",
            onClick  = { onNavigate("综合") },
            modifier = Modifier.fillMaxWidth(),
        )
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
        // 2dp 是图标与数值间的贴近间距，比最小档 Spacing.xs(4dp) 更紧，
        // 套用会让间距翻倍、视觉变松，故保留裸值
        Spacer(Modifier.height(2.dp))
        // UI 升级 v2.0（帧15）：四列统计数字改金色（accentDeep）+ 思源宋体
        Text(
            text       = value,
            style      = type.body.copy(fontWeight = FontWeight.Bold),
            color      = colors.accentDeep,
            fontFamily = SerifSC,
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
                    icon               = AppIcons.Spa,
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
                    imageVector        = AppIcons.ChevronRight,
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
                        // 1dp 用于2条任务预览间的极窄行距，同上，小于 Spacing.xs
                        // 且换成token会让预览区变高，保留裸值
                        modifier          = Modifier.padding(vertical = 1.dp),
                    ) {
                        Icon(
                            imageVector        = if (isDone) AppIcons.CheckBox else AppIcons.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint               = if (isDone) Palette.SemanticSuccess else colors.textDisabled,
                            modifier           = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
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
                            // Radius.xs(6dp) ≥ 高度一半，clip 效果与原裸值 2dp 一致（都是胶囊形），故可安全替换
                            .clip(RoundedCornerShape(Radius.xs)),
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
                imageVector        = AppIcons.Spa,
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

            Column(
                modifier            = Modifier
                    .clickable { onSelect(char.id) }
                    .padding(Spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        // UI 升级 v2.0（帧15）：选中态从角色色单色边框改为黄铜渐变金环
                        .then(
                            if (isSelected) Modifier.border(2.dp, AppBrushes.goldGradient(), CircleShape)
                            else Modifier
                        )
                        // 2dp 内边距与上面2dp边框宽度对应，做出等宽的头像内缩效果，
                        // 换成 Spacing.xs(4dp) 会让缩进比边框宽一倍、视觉不对称，保留裸值
                        .padding(2.dp)
                        .clip(CircleShape),
                ) {
                    AsyncImage(
                        model              = avatarOverrides[char.id]?.takeIf { it.isNotBlank() } ?: char.avatarUrl,
                        contentDescription = char.name,
                        modifier           = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
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
                    TimeFormatUtils.formatMonthDaySlashTime(goal.updatedAt)
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
                        imageVector        = AppIcons.Notes,
                        contentDescription = null,
                        tint               = colors.textDisabled,
                        modifier           = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
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
                // 用极细 accent 色块替代 HorizontalDivider，
                // 与 RulePanel 内部两组规则之间的过渡方式保持一致。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(colors.accent.copy(alpha = 0.12f)),
                )
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
//  Phase 27：规则面板切换行
//
//  v2.0 重写：原实现为"盾牌图标 + 文字 + 旋转箭头"，是典型的
//  iOS 设置页手势提示范式。现改为以规则数量徽章作为视觉锚点，
//  锁定数用 accent 色小圆徽章呈现，折叠/展开指示改用
//  UnfoldMore/UnfoldLess 图标对（替代旋转箭头），整体更克制。
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

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 规则数量徽章（视觉锚点）───────────────────────────
        if (totalCount > 0) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (lockedCount > 0) colors.accent.copy(alpha = 0.15f)
                        else colors.border.copy(alpha = 0.3f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = "$totalCount",
                    style = type.label.copy(fontWeight = FontWeight.Bold),
                    color = if (lockedCount > 0) colors.accent else colors.textSecondary,
                )
            }
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            text  = when {
                totalCount == 0  -> "暂无规则"
                lockedCount == 0 -> "候选规则"
                else             -> "已锁定 $lockedCount 条"
            },
            style = type.caption.copy(fontWeight = FontWeight.Medium),
            color = if (lockedCount > 0) colors.accent else colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        // ── 折叠/展开指示（图标对，非旋转箭头）─────────────────
        Icon(
            imageVector        = if (isExpanded) AppIcons.UnfoldLess else AppIcons.UnfoldMore,
            contentDescription = if (isExpanded) "收起规则" else "展开规则",
            tint               = colors.textDisabled,
            modifier           = Modifier.size(16.dp),
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
                    imageVector        = AppIcons.Lock,
                    contentDescription = null,
                    tint               = colors.accent,
                    modifier           = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text  = "已锁定规则（已注入 System Prompt）",
                    // P3-32 修复：移除硬编码 fontSize，11.sp 改用 type.label
                    style = type.label.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.accent,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            // W10问题1修复：本 Column 嵌套在外层 LazyColumn 内（GoalCard 是
            // items 的一项），不能在此再套一层 LazyColumn（会导致嵌套可滚动
            // 容器的无限高度测量异常）。改为给每个 RuleItem 调用包一层
            // key(rule.id)，让 Compose 能按 MemoryEntity 主键追踪身份，规则
            // 增删时只重组变化的条目，而不是整个 forEachIndexed 循环体。
            lockedRules.forEachIndexed { i, rule ->
                key(rule.id) {
                    RuleItem(
                        index    = i + 1,
                        content  = rule.content,
                        isLocked = true,
                        importance = rule.importance,
                    )
                    if (i < lockedRules.lastIndex) Spacer(Modifier.height(Spacing.xs))
                }
            }
        }

        // ── 两组之间的色块过渡 ─────────────────────────────
        if (lockedRules.isNotEmpty() && candidateRules.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            // 用一条极细的 accent 色块替代 HorizontalDivider，
            // 视觉上更柔和，与卡片整体金色基调一致，不引入"设置页分隔线"联想。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colors.accent.copy(alpha = 0.12f)),
            )
            Spacer(Modifier.height(Spacing.sm))
        }

        // ── 候选规则 ──────────────────────────────────────────
        if (candidateRules.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = AppIcons.Pending,
                    contentDescription = null,
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    // P2-21 修复（返工）：原方案只在组标题统一显示一个"剩余次数"，
                    // 取的是 candidateRules.firstOrNull()?.importance，列表中不同重要度
                    // 的规则看到的是同一个数字，没有真正解决问题。现将组标题简化为
                    // 纯标题，剩余次数移入每条 RuleItem 内部按各自 importance 逐条计算。
                    text  = "候选规则",
                    // P3-32 修复：移除硬编码 fontSize，11.sp 改用 type.label
                    style = type.label.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            candidateRules.forEachIndexed { i, rule ->
                key(rule.id) {
                    RuleItem(
                        index      = i + 1,
                        content    = rule.content,
                        isLocked   = false,
                        importance = rule.importance,
                    )
                    if (i < candidateRules.lastIndex) Spacer(Modifier.height(Spacing.xs))
                }
            }
        }

        // ── 空规则提示 ────────────────────────────────────────
        if (lockedRules.isEmpty() && candidateRules.isEmpty()) {
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = AppIcons.AutoAwesome,
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

    // P2-21 修复（返工）：每条候选规则按各自 importance 计算剩余锁定次数，
    // 不再依赖组标题统一显示一个数字。
    val remainingToLock = if (!isLocked) {
        (DistillationEngine.LOCK_CONFIDENCE_THRESHOLD.toInt() - importance).coerceAtLeast(0)
    } else 0

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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = content,
                // P3-32 修复：移除硬编码 fontSize
                style    = type.caption,
                color    = if (isLocked) colors.textPrimary else colors.textSecondary,
            )
            // P2-21：候选规则逐条显示剩余锁定次数
            if (!isLocked && remainingToLock > 0) {
                Text(
                    text  = "还需 $remainingToLock 次后锁定",
                    style = type.label,
                    color = colors.textDisabled,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // 重要度小圆点（仅候选规则显示，辅助判断距离锁定的距离）
        if (!isLocked) {
            Spacer(Modifier.width(Spacing.xs))
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
        !isActive                                    -> AppIcons.PauseCircle to colors.textDisabled
        status == LearningGoalStatus.COMPLETED.name  -> AppIcons.CheckCircle to Palette.TaskDone
        status == LearningGoalStatus.PAUSED.name     -> AppIcons.PauseCircle to Palette.TaskPaused
        status == LearningGoalStatus.ABANDONED.name  -> AppIcons.Cancel to Palette.TaskFailed
        else                                         -> AppIcons.RadioButtonUnchecked to colors.accent
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
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
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
                imageVector        = AppIcons.MoreVert,
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
                    Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
            DropdownMenuItem(
                text    = { Text(if (isActive) "停用" else "激活") },
                onClick = { expanded = false; onToggle() },
                leadingIcon = {
                    val icon = if (isActive) AppIcons.PauseCircle else AppIcons.PlayCircle
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text    = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDelete() },
                leadingIcon = {
                    Icon(
                        AppIcons.Delete,
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
                imageVector        = AppIcons.EmojiEvents,
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
                        imageVector        = AppIcons.Info,
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
                GhostGoldButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.sm))
                GoldPrimaryButton(
                    text = if (isNew) "创建" else "保存",
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
