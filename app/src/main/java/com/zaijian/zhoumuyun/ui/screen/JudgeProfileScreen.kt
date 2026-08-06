package com.zaijian.zhoumuyun.ui.screen

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.design.WorldCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COMPLETED
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_AWAITING_USER
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTING
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTING_IN_PROGRESS
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTED
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_JUDGING
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_CANCELLED
import com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity
import com.zaijian.zhoumuyun.data.db.entity.JudgeProfileEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.theme.*
import com.zaijian.zhoumuyun.ui.viewmodel.JudgeProfileViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.SimpleSavedStateViewModelFactory
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle // P1-11-2
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.GoldPrimaryButton
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.SecondaryGoldButton

// ─────────────────────────────────────────────────────────────
//  JudgeProfileScreen — 裁判档案训练页（窗口 5B）
//
//  导航结构（本地状态，不另开路由）：
//    列表层（selectedId == null）：该角色所有裁判档案
//    详情层（selectedId != null） ：三 Section 展示
//
//  无 FAB（档案由 CompetitionRoundManager 懒创建，不需要手动新建）。
//
//  入口：CompetitionScreen.LaunchRoundDialog 里「查看/训练标准」→
//        AppNavigation.JudgeProfile 路由 → 这里（窗口 6 接线）。
// ─────────────────────────────────────────────────────────────

@Composable
fun JudgeProfileScreen(
    characterId: Int,
    onBack: () -> Unit,
    onNavigateToCompetition: (domain: String) -> Unit = {},
    viewModel: JudgeProfileViewModel = viewModel(
        factory = SimpleSavedStateViewModelFactory(
            application = LocalContext.current.applicationContext as Application,
            owner       = LocalSavedStateRegistryOwner.current,
            defaultArgs = android.os.Bundle().apply { putInt("judge_profile_character_id", characterId) },
            create      = ::JudgeProfileViewModel,
        ),
    ),
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val profiles     by viewModel.profiles.collectAsStateWithLifecycle()
    val selectedId   by viewModel.selectedProfileId.collectAsStateWithLifecycle()
    val detail       by viewModel.detail.collectAsStateWithLifecycle()
    val snackbar     by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    // 审查报告问题10修复：isLoading 此前在 ViewModel 中已实现（updateAnchorIntent/
    // confirmCorrection 写入时置 true），但 Screen 从未订阅，用户点确认后完全
    // 看不到操作正在进行，容易误以为没反应而重复点击。现在接入。
    val actionLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    // P2-7-6 修复：Judge 列表级加载三态（区别于 action 的 isLoading）。
    val listLoading by viewModel.listLoading.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbar) {
        val msg = snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    // Fix-12-3：系统返回键在详情层时返回列表，而非直接退出页面。
    BackHandler(enabled = selectedId != null) { viewModel.selectProfile(null) }

    // 角色名（顶栏用）
    // P2-7-9 修复：改用合并列表（DefaultCharacters + 女儿）查名，女儿裁判也能显示真名。
    val allCharacters by viewModel.characters.collectAsStateWithLifecycle()
    val charName = remember(characterId, allCharacters) {
        allCharacters.find { it.id == characterId }?.name ?: "角色"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.bgBase,
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // ── 顶部栏 ────────────────────────────────────────
            // D-2 统一顶栏：内联 Row → DetailTopBar
            DetailTopBar(
                title    = if (selectedId == null) "$charName · 裁判标准训练"
                           else "${detail.profile?.domain ?: ""} · 裁判档案",
                onBack   = { if (selectedId != null) viewModel.selectProfile(null) else onBack() },
                headerBg = colors.bgBase,
            )

            GoldDivider()

            // ── 内容层切换 ─────────────────────────────────────
            if (selectedId == null) {
                JudgeListContent(
                    profiles = profiles,
                    // P2-7-6 修复：传入列表级加载三态。
                    isLoading = listLoading,
                    // P2-7-9 修复：传入合并角色列表供卡片查主题色。
                    allCharacters = allCharacters,
                    onSelect = { viewModel.selectProfile(it) },
                )
            } else {
                JudgeDetailContent(
                    detail = detail,
                    viewModel = viewModel,
                    onNavigateToCompetition = onNavigateToCompetition,
                    actionLoading = actionLoading,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  列表层
// ─────────────────────────────────────────────────────────────

@Composable
private fun JudgeListContent(
    profiles: List<JudgeProfileEntity>,
    // P2-7-6 修复：新增列表级加载三态。
    isLoading: Boolean,
    // P2-7-9 修复：合并角色列表（DefaultCharacters + 女儿），供卡片查主题色。
    allCharacters: List<CharacterConfig>,
    onSelect: (String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = colors.accent)
        }
        return
    }

    if (profiles.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.screenHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = "还没有任何裁判档案。\n在竞赛发起时指定她当裁判，系统会自动为她建立档案。",
                style = type.body,
                color = colors.textDisabled,
            )
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
        items(profiles, key = { it.id }) { profile ->
            JudgeProfileCard(
                profile = profile,
                allCharacters = allCharacters,
                onClick = { onSelect(profile.id) },
            )
        }
    }
}

@Composable
private fun JudgeProfileCard(
    profile: JudgeProfileEntity,
    // P2-7-9 修复：合并角色列表（DefaultCharacters + 女儿），供查主题色。
    allCharacters: List<CharacterConfig>,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val (stageText, stageColor) = when (profile.maturityStage) {
        "EXPLORING" -> "摸索期" to colors.textDisabled
        "FORMING"   -> "成型期" to Palette.Gold
        "STABLE"    -> "稳定期" to colors.accent
        else        -> profile.maturityStage to colors.textDisabled
    }

    // P2-7-9 修复：改用合并列表查女儿裁判主题色。
    val characterColor = allCharacters.find { it.id == profile.characterId }?.accentColor

    // WorldCard 接入（精修方案 v1.3）：评委即角色，characterId 现成可查 accentColor。
    WorldCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        ownerAccent = characterColor,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        // 成熟度圆点
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(stageColor),
        )
        Spacer(Modifier.width(Spacing.sm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = profile.domain,
                style = type.body.copy(fontWeight = FontWeight.Medium),
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text  = "裁判 ${profile.judgeCount} 次",
                    style = type.caption,
                    color = colors.textDisabled,
                )
                if (profile.lastJudgedAt > 0) {
                    Text(
                        text  = "最近：${TimeFormatUtils.formatMonthDaySlashTime(profile.lastJudgedAt)}",
                        style = type.caption,
                        color = colors.textDisabled,
                    )
                }
            }
        }

        Spacer(Modifier.width(Spacing.sm))

        // 成熟度徽章
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.xs))
                .background(stageColor.copy(alpha = 0.12f))
                .padding(horizontal = Spacing.sm, vertical = 3.dp),
        ) {
            Text(
                text  = stageText,
                style = type.caption.copy(fontWeight = FontWeight.Medium),
                color = stageColor,
            )
        }

        Spacer(Modifier.width(Spacing.xs))

        Icon(
            imageVector        = AppIcons.ChevronRight,
            contentDescription = null,
            tint               = colors.textDisabled,
            modifier           = Modifier.size(18.dp),
        )
    }
    }
}

// ─────────────────────────────────────────────────────────────
//  详情层
// ─────────────────────────────────────────────────────────────

@Composable
private fun JudgeDetailContent(
    detail: JudgeProfileViewModel.JudgeProfileDetail,
    viewModel: JudgeProfileViewModel,
    onNavigateToCompetition: (String) -> Unit,
    actionLoading: Boolean,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    if (detail.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
        return
    }

    val profile = detail.profile
    if (profile == null) {
        // P2-7-7 修复：加载已结束但 profile 为 null（档案被删/失效）时，渲染明确的
        // "档案不存在"提示 + 返回按钮，避免页面空白/死循环转圈。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("档案不存在或已失效", style = type.body, color = colors.textSecondary)
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text  = "返回列表",
                    style = type.label,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable { viewModel.selectProfile(null) }
                        .padding(Spacing.md),
                )
            }
        }
        return
    }

    // 编辑评判标准 Dialog 的显示状态
    var showEditDialog by remember { mutableStateOf(false) }

    // 候选修正池解析（每次 profile 变化时重算，开销极小）
    val candidateCorrections = remember(profile.candidateCorrectionsJson) {
        viewModel.parseCandidateCorrectionsForDisplay(profile.candidateCorrectionsJson)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = Spacing.screenHorizontal,
            vertical   = Spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // ── Section 1：当前评判标准 ───────────────────────────
        item {
            JudgeSectionCard(title = "当前评判标准") {
                // 冲突警告
                if (profile.hasUnresolvedConflict) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(Palette.SemanticDanger.copy(alpha = 0.08f))  // P3-53 修复：colorScheme.error → Palette.SemanticDanger
                            .border(0.5.dp, Palette.SemanticDanger.copy(alpha = 0.3f), RoundedCornerShape(Radius.sm))  // P3-53 修复
                            .padding(Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            AppIcons.Warning,
                            contentDescription = null,
                            tint     = Palette.SemanticDanger,  // P3-53 修复
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text  = "她的评判标准最近出现了分歧，可能需要再校准一下",
                            style = type.caption,
                            color = Palette.SemanticDanger,  // P3-53 修复
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }

                // 标准内容
                if (profile.standardNotes.isBlank()) {
                    Text(
                        text  = "还在用本能打分，可以把你的评判偏好告诉她",
                        style = type.caption,
                        color = colors.textDisabled,
                    )
                } else {
                    Text(
                        text  = profile.standardNotes,
                        style = type.body,
                        color = colors.textPrimary,
                    )
                }

                Spacer(Modifier.height(Spacing.sm))

                // 编辑按钮
                // 审查报告问题10修复：isLoading 此前无 UI 消费方。这里用来在
                // updateAnchorIntent/confirmCorrection 写入进行中禁用按钮，
                // 避免用户看不到反馈而重复点击、触发并发写入。
                SecondaryGoldButton(
                    text = "编辑评判偏好",
                    onClick = { showEditDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── Section 2：候选修正池 ──────────────────────────────
        item {
            JudgeSectionCard(title = "候选修正池") {
                if (candidateCorrections.isEmpty()) {
                    Text(
                        text  = "暂时没有候选修正。每次竞赛结算后，系统会根据裁判与用户评分的偏差生成修正建议。",
                        style = type.caption,
                        color = colors.textDisabled,
                    )
                } else {
                    Text(
                        text  = "以下是系统观察到的评判倾向，达到3次后可以写进标准：",
                        style = type.label,
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    candidateCorrections.forEach { (trait, count) ->
                        CandidateCorrectionRow(
                            trait   = trait,
                            count   = count,
                            isLoading = actionLoading,
                            onConfirm = {
                                viewModel.confirmCorrection(profile.id, trait, trait)
                            },
                            onDecline = {
                                viewModel.declineCorrection(profile.id, trait)
                            },
                        )
                        Spacer(Modifier.height(Spacing.xs))
                    }
                }
            }
        }

        // ── Section 3：裁判统计 ────────────────────────────────
        item {
            JudgeSectionCard(title = "裁判统计") {
                // 成熟度描述
                val (stageLabel, stageDesc) = when (profile.maturityStage) {
                    "EXPLORING" -> "摸索期" to "她刚开始当裁判，评分权重较低（信任系数 0.5），先观察几场再说。"
                    "FORMING"   -> "成型期" to "她的评判风格正在成型，评分权重提升中（信任系数 0.8）。"
                    "STABLE"    -> "稳定期" to "她已经形成了稳定的评判标准，享有完整评分权重（信任系数 1.0）。"
                    else        -> profile.maturityStage to ""
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    com.zaijian.zhoumuyun.ui.design.IconBadge(
                        icon               = AppIcons.Gavel,
                        contentDescription = null,
                        tint               = Palette.Gold,
                        background         = Palette.Gold.copy(alpha = 0.12f),
                        size               = 16.dp,
                    )
                    Text(
                        text  = "已主持 ${profile.judgeCount} 次竞赛 · $stageLabel",
                        style = type.body.copy(fontWeight = FontWeight.Medium),
                        color = colors.textPrimary,
                    )
                }

                if (stageDesc.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(stageDesc, style = type.caption, color = colors.textSecondary)
                }

                // 最近相关竞赛轮次
                if (detail.recentRounds.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.sm))
                    GoldDivider()
                    Spacer(Modifier.height(Spacing.sm))
                    Text("最近裁判的竞赛", style = type.label, color = colors.textSecondary)
                    Spacer(Modifier.height(Spacing.xs))
                    detail.recentRounds.forEach { round ->
                        RecentRoundRow(
                            round = round,
                            onClick = { onNavigateToCompetition(round.projectDomain) },
                        )
                        Spacer(Modifier.height(Spacing.xs))
                    }
                }
            }
        }
    }

    // ── 编辑评判偏好 Dialog ────────────────────────────────────
    if (showEditDialog) {
        EditStandardDialog(
            initialText = profile.standardNotes,
            onDismiss   = { showEditDialog = false },
            onConfirm   = { text ->
                showEditDialog = false
                viewModel.updateAnchorIntent(profile.id, text)
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  候选修正行
// ─────────────────────────────────────────────────────────────

@Composable
private fun CandidateCorrectionRow(
    trait: String,
    count: Int,
    // 审查报告问题10修复：接入之前无 UI 消费方的 JudgeProfileViewModel.isLoading，
    // 写入进行中禁用「写进标准」按钮，避免用户重复点击触发并发写入。
    isLoading: Boolean = false,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.accent.copy(alpha = 0.05f))
            .border(0.5.dp, colors.accent.copy(alpha = 0.2f), RoundedCornerShape(Radius.sm))
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        // 次数徽章 + 内容
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(colors.accent.copy(alpha = 0.12f))
                    .padding(horizontal = Spacing.sm, vertical = 2.dp),
            ) {
                Text("已观察${count}次", style = type.label, color = colors.accent)
            }
            Text(
                text     = trait,
                style    = type.caption,
                color    = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }
        // 操作按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            GhostGoldButton(
                text = "先不用",
                onClick = { if (!isLoading) onDecline() },
                modifier = Modifier.alpha(if (!isLoading) 1f else 0.4f),
            )
            Spacer(Modifier.width(Spacing.xs))
            GoldPrimaryButton(
                text = "写进标准",
                onClick = { if (!isLoading) onConfirm() },
                modifier = Modifier.alpha(if (!isLoading) 1f else 0.4f),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  最近竞赛轮次行
// ─────────────────────────────────────────────────────────────

@Composable
private fun RecentRoundRow(
    round: CompetitionRoundEntity,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = round.topic,
                style = type.body,
                color = colors.textPrimary,
            )
            Text(
                text  = TimeFormatUtils.formatMonthDaySlashTime(round.createdAt),
                style = type.caption,
                color = colors.textDisabled,
            )
        }
        // 状态徽章
        // B4审查报告【序号7】修复：原先仅 COMPLETED/AWAITING_USER 有中文文案，
        // 其余 5 态落入 else，把原始英文状态串（如"COLLECTING"）直接展示给用户。
        // 现补全全部 7 态的中文文案；else 分支保留但不再是常规可达路径，仅作兜底。
        val (statusText, statusColor) = when (round.status) {
            STATUS_COMPLETED -> "已完成" to colors.statusActive
            STATUS_AWAITING_USER -> "待打分" to Palette.Gold
            STATUS_COLLECTING -> "创作中" to colors.textDisabled
            STATUS_COLLECTING_IN_PROGRESS -> "创作中" to colors.textDisabled
            STATUS_COLLECTED -> "待评审" to colors.textDisabled
            STATUS_JUDGING -> "评审中" to colors.textDisabled
            STATUS_CANCELLED -> "已取消" to colors.textDisabled
            else -> "进行中" to colors.textDisabled
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.xs))
                .background(statusColor.copy(alpha = 0.12f))
                .padding(horizontal = Spacing.sm, vertical = 2.dp),
        ) {
            Text(
                text  = statusText,
                style = type.caption,
                color = statusColor,
            )
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint     = colors.textDisabled,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  编辑评判偏好 Dialog
// ─────────────────────────────────────────────────────────────

@Composable
private fun EditStandardDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = if (colors.isDark) colors.bgElevated else colors.bgCard,
        tonalElevation   = 0.dp,
        title = {
            Text("编辑评判偏好", style = type.cardTitle, color = colors.textPrimary)
        },
        text = {
            Column {
                Text(
                    text  = "告诉她你希望她关注哪些评分维度，她会把这些写进评判标准。",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value         = text,
                    onValueChange = { if (it.length <= 500) text = it },
                    placeholder   = { Text("例如：注重逻辑严密性，语言要有感染力，创意优先于执行细节…") },
                    minLines      = 4,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = colors.accent,
                        unfocusedBorderColor = colors.border,
                        cursorColor          = colors.accent,
                    ),
                    supportingText = {
                        Text(
                            text = "${text.length}/500",
                            style = type.caption,
                            color = colors.textDisabled,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    },
                )
            }
        },
        confirmButton = {
            GoldPrimaryButton(
                text = "确定",
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                modifier = Modifier.alpha(if (text.isNotBlank()) 1f else 0.4f),
            )
        },
        dismissButton = {
            GhostGoldButton(text = "取消", onClick = onDismiss)
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  私有子组件：SectionCard（与 SpecialtyEvolutionScreen 同名但独立定义）
// ─────────────────────────────────────────────────────────────

@Composable
private fun JudgeSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // WorldCard 接入（精修方案 v1.3）：通用 Section 容器，无角色归属，不传 ownerAccent。
    WorldCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.cardPadding)) {
            Text(title, style = type.cardTitle, color = colors.textPrimary)
            Spacer(Modifier.height(Spacing.sm))
            content()
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  工具
// ─────────────────────────────────────────────────────────────

