package com.zaijian.zhoumuyun.ui.screen

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.design.GoldPrimaryButton
import com.zaijian.zhoumuyun.ui.design.SecondaryGoldButton
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.DangerVelvetButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle // P1-11-2
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.theme.*
import com.zaijian.zhoumuyun.ui.viewmodel.CompetitionViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.SimpleSavedStateViewModelFactory
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTING
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTING_IN_PROGRESS
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTED
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_JUDGING
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_AWAITING_USER
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COMPLETED
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_CANCELLED
import com.zaijian.zhoumuyun.ui.design.AppIcons

// ─────────────────────────────────────────────────────────────
//  CompetitionScreen — 裁判与竞争机制 · 竞赛屏幕（窗口3+4）
//
//  导航结构：
//    列表层（selectedRoundId == null）：历史轮次 + FAB 发起
//    详情层（selectedRoundId != null） ：轮次结果 + 打分 + 结算
//
//  窗口3 负责：列表层 + LaunchRoundDialog（发起表单）
//  窗口4 负责：详情层（EntryCard + ScoreInputSection + 结算按钮）
// ─────────────────────────────────────────────────────────────

@Composable
fun CompetitionScreen(
    domain: String,
    onBack: () -> Unit,
    onNavigateToJudgeProfile: (characterId: Int) -> Unit,
    viewModel: CompetitionViewModel = viewModel(
        factory = SimpleSavedStateViewModelFactory(
            application = LocalContext.current.applicationContext as Application,
            owner       = LocalSavedStateRegistryOwner.current,
            create      = ::CompetitionViewModel,
        ),
    ),
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val rounds        by viewModel.rounds.collectAsStateWithLifecycle()
    val selectedId    by viewModel.selectedRoundId.collectAsStateWithLifecycle()
    val roundDetail   by viewModel.roundDetail.collectAsStateWithLifecycle()
    val isLoading     by viewModel.isLoading.collectAsStateWithLifecycle()
    val snackbar      by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val scoreInputMap by viewModel.scoreInputMap.collectAsStateWithLifecycle()
    val daughterCharacters by viewModel.daughterCharacters.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(domain) { viewModel.init(domain) }

    // Fix-P2：拦截系统返回手势。详情层时退回列表层，列表层时退出页面。
    // 与顶部栏返回按钮行为保持一致（selectedId != null → selectRound(null)，否则 onBack）。
    BackHandler(enabled = selectedId != null) {
        viewModel.selectRound(null)
    }

    LaunchedEffect(snackbar) {
        val msg = snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    var showLaunchDialog by remember { mutableStateOf(false) }
    // W4-5：取消竞赛二次确认弹窗——取消是不可逆操作，需要用户显式确认后
    // 才调用 viewModel.cancelRound，避免顶部栏图标误触直接生效。
    var showCancelConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.bgBase,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            if (selectedId == null) {
                FloatingActionButton(
                    onClick = { if (!isLoading) showLaunchDialog = true },
                    containerColor = if (isLoading) colors.textDisabled else colors.accent,
                    contentColor   = colors.bgBase,
                    shape          = CircleShape,
                ) {
                    Icon(AppIcons.EmojiEvents, contentDescription = "发起竞赛")
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // ── 顶部栏 ────────────────────────────────────────
            DetailTopBar(
                title    = if (selectedId == null) "$domain · 竞赛"
                           else roundDetail.round?.topic ?: "$domain · 竞赛",
                onBack   = { if (selectedId != null) viewModel.selectRound(null) else onBack() },
                headerBg = colors.bgBase,
                actions  = {
                    // ── 取消竞赛入口（W4-5） ─────────────────────
                    // 仅详情层显示；轮次状态为 COMPLETED（已结算，取消没有意义）
                    // 或 CANCELLED（已经是取消状态）时不显示——与
                    // CompetitionRoundManager.cancelRound 的状态守卫条件保持一致。
                    val currentRoundStatus = roundDetail.round?.status
                    val canCancel = selectedId != null
                        && currentRoundStatus != null
                        && currentRoundStatus != STATUS_COMPLETED
                        && currentRoundStatus != STATUS_CANCELLED
                    if (canCancel) {
                        IconButton(onClick = { showCancelConfirmDialog = true }) {
                            Icon(
                                imageVector        = AppIcons.Close,
                                contentDescription = "取消竞赛",
                                tint               = colors.textDisabled,
                            )
                        }
                    }
                },
            )

            GoldDivider()

            // ── 内容层切换 ─────────────────────────────────────
            if (selectedId == null) {
                // 列表层
                CompetitionListContent(
                    rounds   = rounds,
                    onSelect = { viewModel.selectRound(it) },
                    isLoading = isLoading,
                )
            } else {
                // 详情层（窗口4续写）
                CompetitionDetailContent(
                    detail        = roundDetail,
                    scoreInputMap = scoreInputMap,
                    isLoading     = isLoading,
                    onScoreInputChanged = { entryId, state ->
                        viewModel.updateScoreInput(entryId, state)
                    },
                    onFinalize = { roundId ->
                        viewModel.finalizeRound(
                            roundId      = roundId,
                            entries      = roundDetail.entries,
                            scoreInputMap = scoreInputMap,
                        )
                    },
                    onRetryRound = { roundId, status ->
                        viewModel.retryRound(roundId, status)
                    },
                    daughterCharacters = daughterCharacters,
                )
            }
        }
    }

    // ── 发起竞赛对话框 ────────────────────────────────────────
    if (showLaunchDialog) {
        LaunchRoundDialog(
            onDismiss = { showLaunchDialog = false },
            onConfirm = { topic, judgeId, participantIds ->
                showLaunchDialog = false
                viewModel.startRound(topic, judgeId, participantIds)
            },
            onNavigateToJudgeProfile = onNavigateToJudgeProfile,
            isLoading = isLoading,
            daughterCharacters = daughterCharacters,
        )
    }

    // ── 取消竞赛二次确认弹窗（W4-5） ────────────────────────
    if (showCancelConfirmDialog) {
        val roundIdToCancel = selectedId
        AlertDialog(
            onDismissRequest = { showCancelConfirmDialog = false },
            title = { Text("取消这轮竞赛？") },
            text  = { Text("取消后这轮竞赛将无法继续（不能再重试或结算），且无法恢复。已收集的作品和打分记录仍会保留，仅供查看。") },
            confirmButton = {
                DangerVelvetButton(
                    text = "确认取消",
                    onClick = {
                        showCancelConfirmDialog = false
                        if (roundIdToCancel != null) viewModel.cancelRound(roundIdToCancel)
                    },
                )
            },
            dismissButton = {
                GhostGoldButton(
                    text = "再想想",
                    onClick = { showCancelConfirmDialog = false },
                )
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  列表层
//
//  UI 升级 v2.0 帧16：编年体分组 —— 列表层按年月分组展示，
//  每组以 sticky header 标注"2026年8月"等时间标签，组内按
//  创建时间倒序排列。编年体（annalistic）即以时间为经、事件
//  为纬，让用户快速定位"哪个月做了哪些竞赛"。
// ─────────────────────────────────────────────────────────────

private data class ChronologicalGroup(
    val label: String,
    val rounds: List<com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity>,
)

private fun groupRoundsChronologically(
    rounds: List<com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity>,
): List<ChronologicalGroup> {
    val zone = ZoneId.systemDefault()
    return rounds
        .groupBy { round ->
            YearMonth.from(Instant.ofEpochMilli(round.createdAt).atZone(zone))
        }
        .toSortedMap(reverseOrder())
        .map { (ym, rs) ->
            ChronologicalGroup(
                label  = "${ym.year}年${ym.monthValue}月",
                rounds = rs.sortedByDescending { it.createdAt },
            )
        }
}

@Composable
private fun CompetitionListContent(
    rounds: List<com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity>,
    onSelect: (String) -> Unit,
    isLoading: Boolean = false,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    if (rounds.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.screenHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = colors.accent)
            } else {
                // D-3 P3：空状态收口至统一组件 EmptyStateView
                EmptyStateView(
                    icon     = AppIcons.EmojiEvents,
                    title    = "还没有任何竞赛记录",
                    subtitle = "点击右下角按钮，发起第一轮",
                )
            }
        }
        return
    }

    val groups = remember(rounds) { groupRoundsChronologically(rounds) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = Spacing.screenHorizontal,
            vertical   = Spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        groups.forEach { group ->
            // ── 编年体分组标题 ──────────────────────────────
            // 金色衬线标题 + 底部 1px 金线，构成"编年"视觉锚点。
            item(key = "header_${group.label}") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.xs),
                ) {
                    Text(
                        text       = group.label,
                        style      = type.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                        color      = Palette.Gold,
                        textAlign  = TextAlign.Start,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Palette.GoldLine),
                    )
                }
            }
            // ── 组内轮次列表 ────────────────────────────────
            items(group.rounds, key = { it.id }) { round ->
                RoundSummaryCard(round = round, onClick = { onSelect(round.id) })
            }
        }
    }
}

@Composable
private fun RoundSummaryCard(
    round: com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val (statusText, statusColor) = when (round.status) {
        STATUS_COLLECTING             -> "收集中"   to colors.textDisabled
        STATUS_COLLECTING_IN_PROGRESS -> "收集中"   to colors.textDisabled
        STATUS_COLLECTED              -> "待评审"   to colors.textDisabled
        STATUS_JUDGING                -> "评审中"   to Palette.Focused
        STATUS_AWAITING_USER          -> "待打分"   to Palette.Gold
        STATUS_COMPLETED              -> "已完成"   to colors.statusActive
        STATUS_CANCELLED              -> "已取消"   to colors.textDisabled
        else            -> round.status to colors.textDisabled
    }

    // WorldCard 接入（精修方案 v1.3）：竞赛轮次总览列表项，逻辑与
    // TimelineEventCard 一致——不归属单一角色（一轮竞赛含多名参赛角色），
    // 故不传 ownerAccent。
    WorldCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(Spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = round.topic,
                    style = type.body.copy(fontWeight = FontWeight.Medium),
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text  = formatTimestamp(round.createdAt),
                    style = type.caption,
                    color = colors.textDisabled,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            // 状态徽章
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(statusColor.copy(alpha = 0.12f))
                    .padding(horizontal = Spacing.sm, vertical = 3.dp),
            ) {
                Text(
                    text  = statusText,
                    style = type.caption.copy(fontWeight = FontWeight.Medium),
                    color = statusColor,
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector    = AppIcons.ChevronRight,
                contentDescription = null,
                tint           = colors.textDisabled,
                modifier       = Modifier.size(18.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  发起竞赛对话框
// ─────────────────────────────────────────────────────────────

@Composable
private fun LaunchRoundDialog(
    onDismiss: () -> Unit,
    onConfirm: (topic: String, judgeId: Int, participantIds: List<Int>) -> Unit,
    onNavigateToJudgeProfile: (characterId: Int) -> Unit,
    isLoading: Boolean,
    daughterCharacters: List<CharacterConfig> = emptyList(),
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    var topic by remember { mutableStateOf("") }
    var selectedJudgeId by remember { mutableStateOf<Int?>(null) }
    var selectedParticipantIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // 所有可选角色：内置角色（1-9）+ 已注册女儿角色（ID ≥ 1000）。
    // Audit-v1.33 P1-1 修复：此前仅用 DefaultCharacters，女儿角色无法参赛。
    // CompetitionRoundManager 底层原本就支持女儿角色（daughterRepo + resolveCharacterName
    // 回退逻辑），此处补齐 UI 层的可选项来源，与底层能力对齐。
    val allCharacters = remember(daughterCharacters) { DefaultCharacters + daughterCharacters }

    // 参赛者候选：排除已选的裁判
    val participantCandidates = remember(selectedJudgeId) {
        allCharacters.filter { it.id != selectedJudgeId }
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        containerColor   = if (colors.isDark) colors.bgElevated else colors.bgCard,
        tonalElevation   = 0.dp,
        title = {
            Text("发起新一轮竞赛", style = type.cardTitle, color = colors.textPrimary)
        },
        text = {
            // AlertDialog text slot 高度有限，直接用 Column + verticalScroll 避免嵌套
            // LazyColumn 引发的 "Cannot measure infinite height" crash
            val scrollState = rememberScrollState()
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
            ) {
                // ── 字段1：题目 ──────────────────────────────
                OutlinedTextField(
                    value         = topic,
                    onValueChange = { topic = it },
                    label         = { Text("命题内容") },
                    placeholder   = { Text("这次要比什么…") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = colors.accent,
                        unfocusedBorderColor = colors.border,
                        focusedLabelColor    = colors.accent,
                        unfocusedLabelColor  = colors.textDisabled,
                        cursorColor          = colors.accent,
                    ),
                )

                // ── 字段2：裁判选择器 ─────────────────────────
                Column {
                    Text(
                        text  = "选择裁判",
                        style = type.caption.copy(fontWeight = FontWeight.Medium),
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    CharacterPickerRow(
                        characters    = allCharacters,
                        selectedId    = selectedJudgeId,
                        onSelect      = { id ->
                            selectedJudgeId = id
                            // 若裁判在参赛者里，移除
                            selectedParticipantIds = selectedParticipantIds - id
                        },
                        onViewProfile = { id -> onNavigateToJudgeProfile(id) },
                    )
                }

                // ── 字段3：参赛者多选 ─────────────────────────
                Column {
                    Text(
                        text  = "选择参赛者（可多选）",
                        style = type.caption.copy(fontWeight = FontWeight.Medium),
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    CharacterMultiPickerSection(
                        characters  = participantCandidates,
                        selectedIds = selectedParticipantIds,
                        onToggle    = { id ->
                            selectedParticipantIds =
                                if (id in selectedParticipantIds)
                                    selectedParticipantIds - id
                                else
                                    selectedParticipantIds + id
                        },
                    )
                }
            }
        },
        confirmButton = {
            val canConfirm = topic.isNotBlank()
                && selectedJudgeId != null
                && selectedParticipantIds.isNotEmpty()
                && !isLoading

            GoldPrimaryButton(
                text    = if (isLoading) "发起中…" else "发起竞赛",
                onClick = { if (canConfirm) { onConfirm(topic.trim(), selectedJudgeId!!, selectedParticipantIds.toList()) } },
                modifier = Modifier.fillMaxWidth().alpha(if (canConfirm) 1f else 0.4f),
            )
        },
        dismissButton = {
            GhostGoldButton(
                text = "取消",
                onClick = { if (!isLoading) onDismiss() },
                modifier = Modifier.alpha(if (!isLoading) 1f else 0.4f),
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  私有子组件：CharacterPickerRow（裁判单选）
// ─────────────────────────────────────────────────────────────

@Composable
private fun CharacterPickerRow(
    characters: List<CharacterConfig>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    onViewProfile: (Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        characters.forEach { char ->
            val isSelected = char.id == selectedId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        if (isSelected) char.accentColor.copy(alpha = 0.08f)
                        else Color.Transparent
                    )
                    .clickable { onSelect(char.id) }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick  = { onSelect(char.id) },
                    colors   = RadioButtonDefaults.colors(
                        selectedColor   = char.accentColor,
                        unselectedColor = colors.textDisabled,
                    ),
                )
                Spacer(Modifier.width(Spacing.xs))
                // 角色颜色圆点
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(char.accentColor),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text     = char.name,
                    style    = type.body,
                    color    = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                // 查看/训练标准按钮（仅选中时显示）
                if (isSelected) {
                    SecondaryGoldButton(
                        text = "查看/训练标准",
                        onClick = { onViewProfile(char.id) },
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  私有子组件：CharacterMultiPickerSection（参赛者多选）
// ─────────────────────────────────────────────────────────────

@Composable
private fun CharacterMultiPickerSection(
    characters: List<CharacterConfig>,
    selectedIds: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        characters.forEach { char ->
            val isChecked = char.id in selectedIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        if (isChecked) char.accentColor.copy(alpha = 0.08f)
                        else Color.Transparent
                    )
                    .clickable { onToggle(char.id) }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked  = isChecked,
                    onCheckedChange = { onToggle(char.id) },
                    colors   = CheckboxDefaults.colors(
                        checkedColor   = char.accentColor,
                        uncheckedColor = colors.textDisabled,
                        checkmarkColor = colors.bgBase,
                    ),
                )
                Spacer(Modifier.width(Spacing.xs))
                // 角色颜色圆点
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(char.accentColor),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text  = char.name,
                    style = type.body,
                    color = colors.textPrimary,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  工具函数
// ─────────────────────────────────────────────────────────────

private fun formatTimestamp(ts: Long): String = TimeFormatUtils.formatDate(ts)

// ─────────────────────────────────────────────────────────────
//  详情层（窗口4）
// ─────────────────────────────────────────────────────────────

@Composable
internal fun CompetitionDetailContent(
    detail: CompetitionViewModel.RoundDetail,
    scoreInputMap: Map<String, CompetitionViewModel.ScoreInputState>,
    isLoading: Boolean,
    onScoreInputChanged: (String, CompetitionViewModel.ScoreInputState) -> Unit,
    onFinalize: (String) -> Unit,
    onRetryRound: (String, String) -> Unit = { _, _ -> },
    daughterCharacters: List<CharacterConfig> = emptyList(),
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    if (detail.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
        return
    }

    val round   = detail.round   ?: return
    val entries = detail.entries

    // 结算可用条件：AWAITING_USER 且有参赛条目即可。
    //
    // 修复 P0-4：原先要求 entries.all { it.id in scoreInputMap }，但 scoreInputMap
    // 只在用户实际拖动滑杆/切 Tab/输入评语时才会写入——用户若满意某条的默认
    // 50 分滑杆值而不去触碰它，该 entry 永远不会进 map，按钮就永远不出现。
    // 而 finalizeRound 提交时本就用 scoreInputMap[entry.id] ?: ScoreInputState()
    // 兜底默认值（CompetitionViewModel.kt），也就是说真正提交并不要求 map 齐全——
    // 这里的激活条件比实际提交条件更严，是多余的卡点，去掉即可。
    val canFinalize = round.status == STATUS_AWAITING_USER
        && entries.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start    = Spacing.screenHorizontal,
                end      = Spacing.screenHorizontal,
                top      = Spacing.md,
                // 给底部结算按钮留空间
                bottom   = if (canFinalize) 88.dp else Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // ── 状态横幅 ──────────────────────────────────────
            item {
                RoundStatusBanner(status = round.status, isLoading = isLoading)
            }

            // A8-1 修复: 裁判无圆桌历史导致播报被跳过时，在 COMPLETED 展示区
            // 提示用户评审结果仅在此页展示，不会出现在圆桌聊天中。
            // judgeRoundtableBroadcastSkipped 由 CompetitionRoundManager.postJudgeResultToRoundtable
            // 在 roundtableId 为 null 时置 true，经 DB 持久化后由本 UI 读取。
            if (round.status == STATUS_COMPLETED && round.judgeRoundtableBroadcastSkipped) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(colors.accent.copy(alpha = 0.08f))
                            .padding(Spacing.cardPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(
                            imageVector        = AppIcons.Warning,
                            contentDescription = null,
                            tint               = colors.textSecondary,
                            modifier           = Modifier.size(16.dp),
                        )
                        Text(
                            text  = "裁判暂无关联圆桌，评审结果仅在此页展示",
                            style = type.caption,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            // ── 重试评审按钮（P0-2 修复 + P0-1 崩溃恢复 + W4-1 修复） ─────
            // 条件：COLLECTING（runJudging 失败回退）、COLLECTING_IN_PROGRESS
            // （runCollecting 中途崩溃/失败）、COLLECTED（runCollecting 已完成
            // 但 runJudging 未执行，如协程被取消或 App 崩溃）、JUDGING
            // （App 崩溃后重启，状态卡在 JUDGING）+ 已有参赛条目 + 当前不在
            // loading 中。四个状态统一交给 ViewModel.retryRound 按状态分派
            // 到 runCollecting 或 runJudging。
            // 修复：entries.isNotEmpty() 曾统一套用在全部四个状态上，但
            // COLLECTING/COLLECTING_IN_PROGRESS 走的是 retryCollecting→
            // runCollecting，而 runCollecting 的状态守卫只看 round.status，
            // 并不要求已有 entries（重新生成作品前 entries 本就可能是空的，
            // 例如上一轮全体生成失败）。若仍要求 entries.isNotEmpty()，
            // 恰恰是"最需要重试"的这种全军覆没场景会被挡在按钮之外，用户
            // 除了取消没有别的路可走。COLLECTED/JUDGING 走的是 retryJudging→
            // runJudging，评审确实需要已有作品，这两个状态保留原有条件。
            val canRetryCollecting = (round.status == STATUS_COLLECTING
                || round.status == STATUS_COLLECTING_IN_PROGRESS) && !isLoading
            val canRetryJudging = (round.status == STATUS_COLLECTED
                || round.status == STATUS_JUDGING)
                && entries.isNotEmpty() && !isLoading
            val canRetry = canRetryCollecting || canRetryJudging
            if (canRetry) {
                item {
                    RetryJudgingBanner(
                        // entries 为空时说明上一轮生成全部失败，用更准确的文案
                        // 提示"需要重新生成"，而不是沿用"作品已收集完毕"的误导性文案
                        entriesEmpty = entries.isEmpty(),
                        onRetry = { onRetryRound(round.id, round.status) },
                    )
                }
            }

            // ── 每位参赛角色一张 EntryCard ─────────────────────
            // P2-15 修复：把排序提到 items 块外，避免每渲染一个 EntryCard
            // 就对全量 entries 执行一次 O(NlogN) 排序（原来是 O(N²logN)）。
            // 仅在 COMPLETED 时才需要名次，其余状态 rankMap 为空 Map，无开销。
            //
            // 批次6 6-1修复：原 rankMap 对全部 entries 排序分配名次，未评分条目
            // （compositeScore 默认值 0f）也会显示"第N名"但不显示综合分。而
            // finalizeRound 的最终排名记忆只对 scoredFinal（三项分数均非空）写入，
            // 名次分母也是 scoredFinal.size。两处口径的分母不一致，用户对排名
            // 含义会产生困惑。改为只对 compositeScore > 0f 的条目分配名次，
            // 与 scoredFinal 口径对齐（compositeScore=0f 表示未完成三项评分，
            // 不应参与排名）。
            val rankMap: Map<String, Int> = if (round.status == STATUS_COMPLETED) {
                entries.filter { it.compositeScore > 0f }
                    .sortedByDescending { it.compositeScore }
                    .mapIndexed { index, e -> e.id to (index + 1) }
                    .toMap()
            } else emptyMap()

            // ── 拱形领奖台（帧16）──────────────────────────────
            // COMPLETED 时取前三名构造 PodiumEntry 列表，传给 ArchPodium。
            // 角色名/色解析与下方 EntryCard 内一致（DefaultCharacters →
            // daughterCharacters → 占位符），确保领奖台与卡片显示同名。
            //
            // 注意：此处不能使用 remember()——LazyColumn 的 content lambda
            // 是 LazyListScope（非 @Composable 作用域），remember 是
            // @Composable 函数，在 LazyListScope 中调用会编译报错。
            // 与上方 rankMap 同理，直接用 val 计算即可（数据量小，无性能问题）。
            val podiumEntries: List<PodiumEntry> = if (round.status == STATUS_COMPLETED && rankMap.isNotEmpty()) {
                rankMap.entries
                    .sortedBy { it.value }
                    .take(3)
                    .mapNotNull { (entryId, rank) ->
                        val entry = entries.find { it.id == entryId } ?: return@mapNotNull null
                        val name = DefaultCharacters.find { it.id == entry.characterId }?.name
                            ?: daughterCharacters.find { it.id == entry.characterId }?.name
                            ?: "角色 #${entry.characterId}"
                        val color = DefaultCharacters.find { it.id == entry.characterId }?.accentColor
                            ?: daughterCharacters.find { it.id == entry.characterId }?.accentColor
                            ?: Palette.Gold
                        PodiumEntry(entry = entry, name = name, color = color, rank = rank)
                    }
            } else emptyList()

            if (podiumEntries.isNotEmpty()) {
                item(key = "arch_podium") {
                    ArchPodium(podiumEntries = podiumEntries)
                }
            }

            items(entries, key = { it.id }) { entry ->
                // Audit-v1.33 P1-1 修复：补充女儿角色回退查找。此前仅查 DefaultCharacters，
                // 女儿角色参赛条目会显示为 "角色 #1001" 占位符而非真实昵称/主题色。
                val charName = remember(entry.characterId, daughterCharacters) {
                    DefaultCharacters.find { it.id == entry.characterId }?.name
                        ?: daughterCharacters.find { it.id == entry.characterId }?.name
                        ?: "角色 #${entry.characterId}"
                }
                val charColor = remember(entry.characterId, daughterCharacters) {
                    DefaultCharacters.find { it.id == entry.characterId }?.accentColor
                        ?: daughterCharacters.find { it.id == entry.characterId }?.accentColor
                        ?: Palette.Gold
                }
                val rank = rankMap[entry.id]

                EntryCard(
                    entry            = entry,
                    characterName    = charName,
                    characterColor   = charColor,
                    roundStatus      = round.status,
                    participantCount = entries.size,
                    scoreInputState  = scoreInputMap[entry.id]
                        ?: CompetitionViewModel.ScoreInputState(),
                    rank             = rank,
                    onScoreInputChanged = { state ->
                        onScoreInputChanged(entry.id, state)
                    },
                )
            }
        }

        // ── 底部结算按钮（fixed） ──────────────────────────────
        if (canFinalize) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(colors.bgBase)
                    .padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.md,
                    ),
            ) {
                GoldPrimaryButton(
                    text    = if (isLoading) "结算中…" else "提交并结算",
                    onClick = { onFinalize(round.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  重试横幅（P0-2 修复；修复：entries 为空场景改用不同文案/按钮字样）
//  status==COLLECTING/COLLECTING_IN_PROGRESS/COLLECTED/JUDGING 之一
//  && !isLoading 时显示（entries 是否为空只影响文案，见 canRetryCollecting/
//  canRetryJudging 的调用处）。runJudging 失败后状态回退到 COLLECTING
//  （Manager 层已保证），此横幅给用户一个明确的操作入口，而不是让
//  "收集中"转圈永远不停。
// ─────────────────────────────────────────────────────────────

@Composable
private fun RetryJudgingBanner(entriesEmpty: Boolean = false, onRetry: () -> Unit) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(Palette.TaskFailed.copy(alpha = 0.08f))
            .border(0.5.dp, Palette.TaskFailed.copy(alpha = 0.25f), RoundedCornerShape(Radius.md))
            .padding(Spacing.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector        = AppIcons.Warning,
            contentDescription = null,
            tint               = Palette.TaskFailed,
            modifier           = Modifier.size(16.dp),
        )
        Text(
            text     = if (entriesEmpty)
                "作品未能生成，可重新发起收集。"
            else
                "裁判评审未能完成，作品已收集完毕，可重新发起评审。",
            style    = type.caption,
            color    = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        SecondaryGoldButton(
            text = if (entriesEmpty) "重新收集" else "重试评审",
            onClick = onRetry,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  状态横幅
// ─────────────────────────────────────────────────────────────

@Composable
private fun RoundStatusBanner(status: String, isLoading: Boolean) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val (text, showSpinner) = when (status) {
        STATUS_COLLECTING             -> "正在收集作品中…" to true
        STATUS_COLLECTING_IN_PROGRESS -> "正在收集作品中…" to true
        STATUS_COLLECTED              -> "作品已收集，等待评审…" to false
        STATUS_JUDGING                -> "裁判评审中…"     to true
        STATUS_AWAITING_USER          -> "请为每位参赛者打分，评完所有人后点击底部「提交并结算」" to false
        STATUS_COMPLETED              -> null to false
        STATUS_CANCELLED              -> "该轮竞赛已取消。" to false
        else            -> status to false
    }

    if (text == null && !isLoading) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.accent.copy(alpha = 0.08f))
            .padding(Spacing.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (showSpinner || isLoading) {
            CircularProgressIndicator(
                modifier    = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color       = colors.accent,
            )
        }
        Text(
            text  = text ?: "处理中…",
            style = type.caption,
            color = colors.textSecondary,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  ArchPodium — 拱形领奖台（帧16）
//
//  UI 升级 v2.0 帧16：竞赛结算后以拱形领奖台展示前三名，
//  中心最高（冠军）、左次高（亚军）、右最低（季军），
//  构成"拱形"视觉轮廓。领奖台底座使用金色渐变，与方案
//  GoldGradient 135° 三段一致；名次数字用衬线大字号，
//  呼应方案"金数字"设计语言。
// ─────────────────────────────────────────────────────────────

private data class PodiumEntry(
    val entry: com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity,
    val name: String,
    val color: Color,
    val rank: Int,
)

@Composable
private fun ArchPodium(podiumEntries: List<PodiumEntry>) {
    if (podiumEntries.isEmpty()) return

    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    WorldCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // ── 标题行 ──────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = AppIcons.EmojiEvents,
                    contentDescription = null,
                    tint               = Palette.Gold,
                    modifier           = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text  = "领奖台",
                    style = type.cardTitle,
                    color = colors.textPrimary,
                )
            }

            // ── 拱形领奖台主体 ──────────────────────────────
            // 排列：亚军(左, 中等高) · 冠军(中, 最高) · 季军(右, 最低)
            // 用 Row + Alignment.Bottom 让三个底座底部对齐，
            // 高度差形成"拱形"轮廓（中间凸起、两侧递降）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom,
            ) {
                // 亚军（第2名）— 左侧
                if (podiumEntries.size >= 2) {
                    PodiumStep(
                        podiumEntry = podiumEntries[1],
                        stepHeight  = 72.dp,
                        isChampion  = false,
                        modifier    = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Spacer(Modifier.width(Spacing.sm))

                // 冠军（第1名）— 中央，最高
                PodiumStep(
                    podiumEntry = podiumEntries[0],
                    stepHeight  = 108.dp,
                    isChampion  = true,
                    modifier    = Modifier.weight(1f),
                )

                Spacer(Modifier.width(Spacing.sm))

                // 季军（第3名）— 右侧
                if (podiumEntries.size >= 3) {
                    PodiumStep(
                        podiumEntry = podiumEntries[2],
                        stepHeight  = 52.dp,
                        isChampion  = false,
                        modifier    = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PodiumStep(
    podiumEntry: PodiumEntry,
    stepHeight: Dp,
    isChampion: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 角色名 ──────────────────────────────────────
        Text(
            text       = podiumEntry.name,
            style      = type.caption.copy(fontWeight = FontWeight.Medium),
            color      = colors.textPrimary,
            maxLines   = 1,
            overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign  = TextAlign.Center,
            modifier   = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.xs))

        // ── 综合分 ──────────────────────────────────────
        Text(
            text  = "%.1f".format(podiumEntry.entry.compositeScore),
            style = type.cardTitle.copy(
                fontSize   = if (isChampion) 20.sp else 16.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = if (isChampion) Palette.Gold else colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.xs))

        // ── 领奖台底座（金色渐变矩形 + 名次数字）──────────
        // 冠军底座用三段金渐变（GoldBright→Gold→GoldDeep），
        // 亚军/季军底座用淡金渐变（Gold@55%→GoldDeep@40%），
        // 视觉上冠军明显更亮，呼应"金数字"设计语言。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stepHeight)
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .drawBehind {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = if (isChampion) {
                                listOf(Palette.GoldBright, Palette.Gold, Palette.GoldDeep)
                            } else {
                                listOf(
                                    Palette.Gold.copy(alpha = 0.55f),
                                    Palette.GoldDeep.copy(alpha = 0.40f),
                                )
                            },
                            start = androidx.compose.ui.geometry.Offset(0f, 0f),
                            end   = androidx.compose.ui.geometry.Offset(size.width, size.height),
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = "${podiumEntry.rank}",
                style = type.titleBold.copy(
                    fontSize   = if (isChampion) 36.sp else 28.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Color.White.copy(alpha = if (isChampion) 0.95f else 0.7f),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  EntryCard
// ─────────────────────────────────────────────────────────────

@Composable
private fun EntryCard(
    entry: com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity,
    characterName: String,
    characterColor: Color,
    roundStatus: String,
    participantCount: Int,
    scoreInputState: CompetitionViewModel.ScoreInputState,
    rank: Int?,
    onScoreInputChanged: (CompetitionViewModel.ScoreInputState) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    var contentExpanded by remember(entry.id) { mutableStateOf(false) }

    // WorldCard 接入（精修方案 v1.3）：参赛作品卡已有 characterColor 字段，明确归属角色。
    WorldCard(
        modifier = Modifier.fillMaxWidth(),
        ownerAccent = characterColor,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
        // ── 角色标题行 ─────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(characterColor),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text     = characterName,
                style    = type.cardTitle,
                color    = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            // 名次角标（COMPLETED 时）
            if (rank != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(Palette.Gold.copy(alpha = 0.15f))
                        .padding(horizontal = Spacing.sm, vertical = 2.dp),
                ) {
                    Text(
                        text  = "第 $rank 名",
                        style = type.caption.copy(fontWeight = FontWeight.Bold),
                        color = Palette.Gold,
                    )
                }
            }
        }

        // ── 参赛作品正文（可展开） ──────────────────────────
        Text(
            text       = entry.content,
            style      = type.body,
            color      = colors.textSecondary,
            maxLines   = if (contentExpanded) Int.MAX_VALUE else 4,
            overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier   = Modifier.clickable { contentExpanded = !contentExpanded },
        )
        if (!contentExpanded && entry.content.length > 120) {
            Text(
                text     = "展开全文",
                style    = type.caption,
                color    = colors.accent,
                modifier = Modifier.clickable { contentExpanded = true },
            )
        }

        // ── 裁判评分区 ──────────────────────────────────────
        if (entry.judgeScore != null) {
            GoldDivider()
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        AppIcons.Gavel,
                        contentDescription = null,
                        tint     = Palette.Gold,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("裁判评分", style = type.caption, color = colors.textDisabled)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text  = "${entry.judgeScore}",
                        style = type.cardTitle,
                        color = Palette.Gold,
                    )
                }
                if (entry.judgeReasoning.isNotBlank()) {
                    Text(
                        text  = entry.judgeReasoning,
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        // ── 自评区 ──────────────────────────────────────────
        if (entry.selfScore != null) {
            GoldDivider()
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        AppIcons.SelfImprovement,
                        contentDescription = null,
                        tint     = colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("自评", style = type.caption, color = colors.textDisabled)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text  = "${entry.selfScore}",
                        style = type.cardTitle,
                        color = colors.accent,
                    )
                }
                if (entry.selfReasoning.isNotBlank()) {
                    Text(
                        text  = entry.selfReasoning,
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        // ── 用户打分区 ──────────────────────────────────────
        when {
            roundStatus == STATUS_AWAITING_USER -> {
                GoldDivider()
                ScoreInputSection(
                    state            = scoreInputState,
                    participantCount = participantCount,
                    onChanged        = onScoreInputChanged,
                )
            }
            roundStatus == STATUS_COMPLETED && entry.userScore != null -> {
                GoldDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        AppIcons.Person,
                        contentDescription = null,
                        tint     = colors.textDisabled,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("你的评分", style = type.caption, color = colors.textDisabled)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text  = "${entry.userScore}",
                        style = type.cardTitle,
                        color = colors.textPrimary,
                    )
                }
            }
        }

        // ── 综合分（COMPLETED） ─────────────────────────────
        if (roundStatus == STATUS_COMPLETED && entry.compositeScore > 0f) {
            GoldDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text("综合分", style = type.caption, color = colors.textDisabled)
                // 综合分需突出展示，22sp 已通过 .copy() 挂载接入，非裸写，保留现状
                Text(
                    text  = "%.1f".format(entry.compositeScore),
                    style = type.cardTitle.copy(
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Palette.Gold,
                )
            }
        }
    }
    }
}

// ─────────────────────────────────────────────────────────────
//  ScoreInputSection — 三Tab打分组件
// ─────────────────────────────────────────────────────────────

@Composable
private fun ScoreInputSection(
    state: CompetitionViewModel.ScoreInputState,
    participantCount: Int,
    onChanged: (CompetitionViewModel.ScoreInputState) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("你的评分", style = type.caption, color = colors.textDisabled)

        // ── Tab 切换 ──────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            CompetitionViewModel.ScoreInputMode.entries.forEach { mode ->
                val isActive = mode == state.activeMode
                val label = when (mode) {
                    CompetitionViewModel.ScoreInputMode.SLIDER  -> "滑杆"
                    CompetitionViewModel.ScoreInputMode.RANK    -> "排名"
                    CompetitionViewModel.ScoreInputMode.COMMENT -> "评语"
                }
                FilterChip(
                    selected = isActive,
                    onClick  = { onChanged(state.copy(activeMode = mode)) },
                    label    = { Text(label, style = type.caption) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accent.copy(alpha = 0.15f),
                        selectedLabelColor     = colors.accent,
                    ),
                )
            }
        }

        // ── 各 Tab 内容 ───────────────────────────────────
        when (state.activeMode) {
            CompetitionViewModel.ScoreInputMode.SLIDER -> {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("0", style = type.label, color = colors.textDisabled)
                        Text(
                            text  = "${state.sliderValue.toInt()}",
                            style = type.cardTitle,
                            color = colors.accent,
                        )
                        Text("100", style = type.label, color = colors.textDisabled)
                    }
                    Slider(
                        value         = state.sliderValue,
                        onValueChange = { onChanged(state.copy(sliderValue = it)) },
                        valueRange    = 0f..100f,
                        colors        = SliderDefaults.colors(
                            thumbColor       = colors.accent,
                            activeTrackColor = colors.accent,
                        ),
                    )
                }
            }

            CompetitionViewModel.ScoreInputMode.RANK -> {
                // 排名调节器：显示当前名次，↑/↓ 调整
                val n = participantCount.coerceAtLeast(1)
                val k = state.rankPosition.coerceIn(1, n)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        text     = "第 $k 名",
                        style    = type.cardTitle,
                        color    = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text  = "（共 $n 人）",
                        style = type.caption,
                        color = colors.textDisabled,
                    )
                    IconButton(
                        onClick  = { onChanged(state.copy(rankPosition = (k - 1).coerceAtLeast(1))) },
                        enabled  = k > 1,
                    ) {
                        Icon(
                            AppIcons.KeyboardArrowUp,
                            contentDescription = "名次提前",
                            tint = if (k > 1) colors.accent else colors.textDisabled,
                        )
                    }
                    IconButton(
                        onClick  = { onChanged(state.copy(rankPosition = (k + 1).coerceAtMost(n))) },
                        enabled  = k < n,
                    ) {
                        Icon(
                            AppIcons.KeyboardArrowDown,
                            contentDescription = "名次推后",
                            tint = if (k < n) colors.accent else colors.textDisabled,
                        )
                    }
                }
                // 预览换算分
                val previewScore = if (n <= 0) 50
                else (100 - (k - 1) * (100.0 / n)).toInt().coerceIn(0, 100)
                Text(
                    text  = "换算分：$previewScore",
                    style = type.label,
                    color = colors.textDisabled,
                )
            }

            CompetitionViewModel.ScoreInputMode.COMMENT -> {
                OutlinedTextField(
                    value         = state.comment,
                    onValueChange = { onChanged(state.copy(comment = it)) },
                    placeholder   = { Text("写几个字，系统自动换算成分数") },
                    minLines      = 2,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = colors.accent,
                        unfocusedBorderColor = colors.border,
                        cursorColor          = colors.accent,
                    ),
                )
            }
        }
    }
}
