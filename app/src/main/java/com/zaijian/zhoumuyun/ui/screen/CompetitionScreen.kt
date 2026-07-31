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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                TextButton(onClick = {
                    showCancelConfirmDialog = false
                    if (roundIdToCancel != null) viewModel.cancelRound(roundIdToCancel)
                }) {
                    Text("确认取消", color = Palette.TaskFailed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmDialog = false }) {
                    Text("再想想")
                }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  列表层
// ─────────────────────────────────────────────────────────────

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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = Spacing.screenHorizontal,
            vertical   = Spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(rounds, key = { it.id }) { round ->
            RoundSummaryCard(round = round, onClick = { onSelect(round.id) })
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

            Button(
                onClick  = {
                    if (canConfirm) {
                        onConfirm(
                            topic.trim(),
                            selectedJudgeId!!,
                            selectedParticipantIds.toList(),
                        )
                    }
                },
                enabled  = canConfirm,
                colors   = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor   = colors.bgBase,
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.bgBase,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("发起中…")
                } else {
                    Text("发起竞赛")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick  = onDismiss,
                enabled  = !isLoading,
            ) {
                Text("取消", color = colors.textSecondary)
            }
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
                    TextButton(
                        onClick      = { onViewProfile(char.id) },
                        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = 0.dp),
                    ) {
                        Text(
                            text  = "查看/训练标准",
                            style = type.caption,
                            color = colors.accent,
                        )
                    }
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
            val canRetry = (round.status == STATUS_COLLECTING
                || round.status == STATUS_COLLECTING_IN_PROGRESS
                || round.status == STATUS_COLLECTED
                || round.status == STATUS_JUDGING)
                && entries.isNotEmpty() && !isLoading
            if (canRetry) {
                item {
                    RetryJudgingBanner(onRetry = { onRetryRound(round.id, round.status) })
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
                Button(
                    onClick  = { onFinalize(round.id) },
                    enabled  = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor   = colors.bgBase,
                    ),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color       = colors.bgBase,
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text("结算中…")
                    } else {
                        Icon(AppIcons.EmojiEvents, contentDescription = null)
                        Spacer(Modifier.width(Spacing.xs))
                        Text("提交并结算")
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  重试评审横幅（P0-2 修复）
//  仅在 status==COLLECTING && entries.isNotEmpty() && !isLoading 时显示。
//  runJudging 失败后状态回退到 COLLECTING（Manager 层已保证），
//  此横幅给用户一个明确的操作入口，而不是让"收集中"转圈永远不停。
// ─────────────────────────────────────────────────────────────

@Composable
private fun RetryJudgingBanner(onRetry: () -> Unit) {
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
            text     = "裁判评审未能完成，作品已收集完毕，可重新发起评审。",
            style    = type.caption,
            color    = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRetry,
            colors  = ButtonDefaults.textButtonColors(contentColor = Palette.TaskFailed),
        ) {
            Text("重试评审", style = type.caption.copy(fontWeight = FontWeight.Medium))
        }
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
