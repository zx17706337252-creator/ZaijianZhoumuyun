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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import com.zaijian.zhoumuyun.ui.design.WorldCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity
import com.zaijian.zhoumuyun.data.db.entity.JudgeProfileEntity
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.theme.*
import com.zaijian.zhoumuyun.ui.viewmodel.JudgeProfileViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.SimpleSavedStateViewModelFactory
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle // P1-11-2
import java.text.SimpleDateFormat
import java.util.*

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

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbar) {
        val msg = snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    // Fix-12-3：系统返回键在详情层时返回列表，而非直接退出页面。
    BackHandler(enabled = selectedId != null) { viewModel.selectProfile(null) }

    // 角色名（顶栏用）
    val charName = remember(characterId) {
        DefaultCharacters.find { it.id == characterId }?.name ?: "角色"
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(Spacing.topBarHeight)
                    .padding(horizontal = Spacing.screenHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (selectedId != null) viewModel.selectProfile(null) else onBack()
                }) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint               = colors.textPrimary,
                    )
                }
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text     = if (selectedId == null) "$charName · 裁判标准训练"
                               else "${detail.profile?.domain ?: ""} · 裁判档案",
                    style    = type.cardTitle,
                    color    = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }

            GoldDivider()

            // ── 内容层切换 ─────────────────────────────────────
            if (selectedId == null) {
                JudgeListContent(
                    profiles = profiles,
                    onSelect = { viewModel.selectProfile(it) },
                )
            } else {
                JudgeDetailContent(
                    detail = detail,
                    viewModel = viewModel,
                    onNavigateToCompetition = onNavigateToCompetition,
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
    onSelect: (String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

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
                onClick = { onSelect(profile.id) },
            )
        }
    }
}

@Composable
private fun JudgeProfileCard(
    profile: JudgeProfileEntity,
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

    val characterColor = DefaultCharacters.find { it.id == profile.characterId }?.accentColor

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
                        text  = "最近：${_judgeListDateFmt.format(Date(profile.lastJudgedAt))}",
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
            imageVector        = Icons.Outlined.ChevronRight,
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
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    if (detail.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
        return
    }

    val profile = detail.profile ?: return

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
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                            .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), RoundedCornerShape(Radius.sm))
                            .padding(Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text  = "她的评判标准最近出现了分歧，可能需要再校准一下",
                            style = type.caption,
                            color = MaterialTheme.colorScheme.error,
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
                OutlinedButton(
                    onClick = { showEditDialog = true },
                    border  = androidx.compose.foundation.BorderStroke(0.5.dp, colors.accent.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        tint     = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("编辑评判偏好", color = colors.accent)
                }
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
                    Icon(
                        Icons.Outlined.Gavel,
                        contentDescription = null,
                        tint     = Palette.Gold,
                        modifier = Modifier.size(16.dp),
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
            TextButton(
                onClick        = onDecline,
                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
            ) {
                Text("先不用", style = type.caption, color = colors.textDisabled)
            }
            Spacer(Modifier.width(Spacing.xs))
            TextButton(
                onClick        = onConfirm,
                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
            ) {
                Text("写进标准", style = type.caption, color = colors.accent)
            }
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
                text  = _judgeListDateFmt.format(Date(round.createdAt)),
                style = type.caption,
                color = colors.textDisabled,
            )
        }
        // 状态徽章
        val (statusText, statusColor) = when (round.status) {
            "COMPLETED" -> "已完成" to colors.statusActive
            "AWAITING_USER" -> "待打分" to Palette.Gold
            else -> round.status to colors.textDisabled
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
            Icons.Outlined.ChevronRight,
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
                    onValueChange = { text = it },
                    placeholder   = { Text("例如：注重逻辑严密性，语言要有感染力，创意优先于执行细节…") },
                    minLines      = 4,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = colors.accent,
                        unfocusedBorderColor = colors.border,
                        cursorColor          = colors.accent,
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick  = { if (text.isNotBlank()) onConfirm(text) },
                enabled  = text.isNotBlank(),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor   = colors.bgBase,
                ),
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
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

private val _judgeListDateFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
