package com.zaijian.zhoumuyun.ui.screen

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.design.WorldCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity
import com.zaijian.zhoumuyun.data.db.entity.SystemSuggestionEntity
import com.zaijian.zhoumuyun.ui.theme.*
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.ui.viewmodel.SimpleSavedStateViewModelFactory
import com.zaijian.zhoumuyun.ui.viewmodel.SpecialtyEvolutionViewModel
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle // P1-11-2
import kotlinx.coroutines.launch
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.DangerVelvetButton
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.GoldPrimaryButton
import com.zaijian.zhoumuyun.ui.design.SecondaryGoldButton

// ─────────────────────────────────────────────────────────────
//  SpecialtyEvolutionScreen（P6 专长进化系统）
//
//  对应设计方案第7.3节"专长档案页"三个区块：
//    ① 当前进化方案（含历史版本翻看）
//    ② 风格说明书 + 候选观察池 + 已晋升特征标记
//    ③ 修炼历程（PracticeRecord + StageDigest 按时间穿插）
//
//  以及顶部的"待处理建议"角标——候选转正确认 / 晋升请求 / AI自我提案，
//  三类待办统一走 SystemSuggestionEntity，按 content 前缀区分类型展示。
//
//  入口：CharacterDetailScreen，与"人设"编辑入口并列。
//  导航结构：本 Screen 内部用本地状态切换"专长列表" ↔ "专长详情"两级，
//  不另开 Compose Navigation 路由——专长档案本身是角色详情下的子页面，
//  不需要支持深链接跳转到某个具体专长，简单的本地状态切换足够。
// ─────────────────────────────────────────────────────────────

@Composable
fun SpecialtyEvolutionScreen(
    characterId: Int,
    onBack: () -> Unit = {},
    onNavigateToCompetition: (String) -> Unit = {},
    viewModel: SpecialtyEvolutionViewModel = viewModel(
        factory = SimpleSavedStateViewModelFactory(
            application = LocalContext.current.applicationContext as Application,
            owner       = LocalSavedStateRegistryOwner.current,
            create      = ::SpecialtyEvolutionViewModel,
        ),
    ),
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedProfileId.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    // P2-7-9 修复：角色合并列表（DefaultCharacters + 女儿），供查名/查主题色。
    val allCharacters by viewModel.characters.collectAsStateWithLifecycle()
    val snackbar by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(characterId) { viewModel.init(characterId) }

    // Fix-12-3：系统返回键在详情层时返回列表，而非直接退出页面。
    BackHandler(enabled = selectedId != null) { viewModel.selectProfile(null) }

    LaunchedEffect(snackbar) {
        val msg = snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<SpecialtyProfileEntity?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.bgBase,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            if (selectedId == null) {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = colors.accent,
                    contentColor = colors.bgBase,
                    shape = CircleShape,
                ) {
                    Icon(AppIcons.Add, contentDescription = "新建专长方向")
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // ── 顶部栏 ─────────────────────────────────────────
            // D-2 统一顶栏：内联 Row → DetailTopBar
            DetailTopBar(
                title    = if (selectedId == null) "专长养成" else "专长档案",
                onBack   = { if (selectedId != null) viewModel.selectProfile(null) else onBack() },
                headerBg = colors.bgBase,
            )

            if (selectedId == null) {
                SpecialtyListContent(
                    profiles = profiles,
                    // P2-7-6 修复：传入加载中三态。
                    isLoading = isLoading,
                    // P2-7-9 修复：传入合并角色列表供卡片查主题色。
                    allCharacters = allCharacters,
                    onSelect = { viewModel.selectProfile(it.id) },
                    onToggleActive = { viewModel.setActive(it.id, !it.isActive) },
                    onLongPressDelete = { profileToDelete = it },
                )
            } else {
                SpecialtyDetailContent(
                    viewModel = viewModel,
                    profileId = selectedId!!,
                    onNavigateToCompetition = onNavigateToCompetition,
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateSpecialtyDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { domain, intent ->
                viewModel.createSpecialty(domain, intent)
                showCreateDialog = false
            },
        )
    }

    profileToDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("删除「${profile.domain}」？") },
            text = { Text("已积累的修炼记录、风格说明书都会被一并清空，无法恢复。") },
            confirmButton = {
                DangerVelvetButton(
                    text = "删除",
                    onClick = {
                        viewModel.deleteSpecialty(profile.id, profile.domain)
                        profileToDelete = null
                    },
                )
            },
            dismissButton = {
                GhostGoldButton(text = "取消", onClick = { profileToDelete = null })
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  专长列表
// ─────────────────────────────────────────────────────────────

@Composable
private fun SpecialtyListContent(
    profiles: List<SpecialtyProfileEntity>,
    // P2-7-6 修复：新增 isLoading 三态，慢查询时显示加载中而非误导性的"空态"。
    isLoading: Boolean,
    // P2-7-9 修复：角色合并列表（DefaultCharacters + 女儿），供卡片查主题色。
    allCharacters: List<CharacterConfig>,
    onSelect: (SpecialtyProfileEntity) -> Unit,
    onToggleActive: (SpecialtyProfileEntity) -> Unit,
    onLongPressDelete: (SpecialtyProfileEntity) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    if (isLoading) {
        // 查询进行中：显示进度指示器，避免把"仍在加载"误认为"没有专长"的空态。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
        return
    }

    if (profiles.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    AppIcons.AutoAwesome,
                    contentDescription = null,
                    tint = colors.textDisabled,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "还没有设定专长方向",
                    style = type.body,
                    color = colors.textSecondary,
                )
                Text(
                    text = "点击右下角，告诉她你想让她往哪个方向养成",
                    style = type.caption,
                    color = colors.textDisabled,
                )
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(profiles, key = { it.id }) { profile ->
            SpecialtyCard(
                profile = profile,
                allCharacters = allCharacters,
                onClick = { onSelect(profile) },
                onToggleActive = { onToggleActive(profile) },
                onLongPress = { onLongPressDelete(profile) },
            )
        }
    }
}

@Composable
private fun SpecialtyCard(
    profile: SpecialtyProfileEntity,
    // P2-7-9 修复：角色合并列表（DefaultCharacters + 女儿），供查主题色。
    allCharacters: List<CharacterConfig>,
    onClick: () -> Unit,
    onToggleActive: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    val stageLabel = when (profile.maturityStage) {
        "EXPLORING" -> "摸索期"
        "FORMING" -> "成型期"
        else -> "稳定期"
    }
    // UI 升级 v2.0（帧25 稳定期墨绿色）：摸索期(灰) → 成型期(金) → 稳定期(墨绿)
    // 三阶段配色拉开区分度，稳定期不再与成型期同用 accent 金色，靠颜色即可辨识阶段。
    val stableGreen = Color(0xFF5C8A6E)
    val stageColor = when (profile.maturityStage) {
        "EXPLORING" -> colors.textDisabled
        "FORMING" -> colors.accent
        else -> stableGreen  // 稳定期墨绿
    }

    // P2-7-9 修复：改用合并列表查女儿角色主题色。
    val characterColor = allCharacters.find { it.id == profile.characterId }?.accentColor

    // WorldCard 接入（精修方案 v1.3）：SpecialtyProfileEntity.characterId 现成字段，明确归属角色。
    WorldCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        ownerAccent = characterColor,
    ) {
        Column(modifier = Modifier.padding(Spacing.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.domain,
                    style = type.cardTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (profile.promotedToIdentity) {
                    Icon(
                        AppIcons.Favorite,
                        contentDescription = "已有特征晋升为本能",
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(stageColor.copy(alpha = 0.12f))
                        .padding(horizontal = Spacing.sm, vertical = 2.dp),
                ) {
                    Text(text = stageLabel, style = type.label, color = stageColor)
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "已修炼 ${profile.practiceCount} 次",
                style = type.caption,
                color = colors.textSecondary,
            )
            if (profile.hasUnresolvedConflict) {
                Spacer(Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        AppIcons.ErrorOutline,
                        contentDescription = null,
                        tint = Palette.SemanticDanger,  // P3-53 修复
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "有未处理的风格分歧",
                        style = type.caption,
                        color = Palette.SemanticDanger,  // P3-53 修复
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = profile.isActive,
                    onCheckedChange = { onToggleActive() },
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = if (profile.isActive) "每日修炼进行中" else "已暂停",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onLongPress, modifier = Modifier.size(32.dp).minimumInteractiveComponentSize()) {
                    Icon(
                        AppIcons.DeleteOutline,
                        contentDescription = "删除",
                        tint = colors.textDisabled,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  创建专长方向对话框
// ─────────────────────────────────────────────────────────────

@Composable
private fun CreateSpecialtyDialog(
    onDismiss: () -> Unit,
    onConfirm: (domain: String, anchorIntent: String) -> Unit,
) {
    var domain by remember { mutableStateOf("") }
    var intent by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设定一个专长方向") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { if (it.length <= 30) domain = it },
                    label = { Text("方向（如：文学创作，≤30字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            text = "${domain.length}/30",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    },
                )
                OutlinedTextField(
                    value = intent,
                    onValueChange = { if (it.length <= 200) intent = it },
                    label = { Text("具体想让她往哪个样子养成（≤200字）") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            text = "${intent.length}/200",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    },
                )
            }
        },
        confirmButton = {
            val startEnabled = domain.isNotBlank() && intent.isNotBlank()
            GoldPrimaryButton(
                text = "开始",
                onClick = { if (startEnabled) onConfirm(domain, intent) },
                modifier = Modifier.alpha(if (startEnabled) 1f else 0.4f),
            )
        },
        dismissButton = {
            GhostGoldButton(text = "取消", onClick = onDismiss)
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  专长详情：方案 + 风格说明书 + 修炼历程，三区块
// ─────────────────────────────────────────────────────────────

@Composable
private fun SpecialtyDetailContent(
    viewModel: SpecialtyEvolutionViewModel,
    profileId: String,
    onNavigateToCompetition: (String) -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    LaunchedEffect(profileId) { viewModel.selectProfile(profileId) }
    val detail by viewModel.profileDetail.collectAsStateWithLifecycle()

    if (detail.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
        return
    }
    // P2-7-7 修复：加载已结束但档案为 null（被删/失效）时，给出明确提示 + 返回列表，
    // 避免页面空白/死循环转圈（与 JudgeProfileScreen 同款修法）。
    if (detail.profile == null) {
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
    val profile = detail.profile!!

    // P2-7-9 修复：改用合并列表（DefaultCharacters + 女儿）查主题色。
    val allCharacters by viewModel.characters.collectAsStateWithLifecycle()
    // WorldCard ownerAccent（精修方案 v1.3）：本页按 characterId 进入，
    // PracticeRecordRow 明确归属该角色，与 264 行 SpecialtyCard 处同一取法。
    val characterColor = allCharacters.find { it.id == profile.characterId }?.accentColor

    // UI S4 修复：折叠/展开状态在进程死亡后应能恢复，改用 rememberSaveable
    var planHistoryExpanded by rememberSaveable { mutableStateOf(false) }
    var recordsExpanded by rememberSaveable { mutableStateOf(true) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // ── 待处理建议（候选转正/晋升请求/AI自我提案）──────────
        if (detail.pendingSuggestions.isNotEmpty()) {
            item {
                Text("待处理", style = type.label, color = colors.textSecondary)
            }
            items(detail.pendingSuggestions, key = { it.id }) { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    profileId = profileId,
                    viewModel = viewModel,
                )
            }
        }

        // ── 区块①：当前进化方案 ──────────────────────────────
        item {
            SectionCard(title = "她给自己定的修炼计划") {
                Text(
                    text = detail.activePlan?.content ?: "暂无方案",
                    style = type.body,
                    color = colors.textPrimary,
                )
                if (detail.planHistory.size > 1) {
                    Spacer(Modifier.height(Spacing.sm))
                    GhostGoldButton(
                        text = if (planHistoryExpanded) "收起历史版本" else "查看历史版本（${detail.planHistory.size}个）",
                        onClick = { planHistoryExpanded = !planHistoryExpanded },
                    )
                    if (planHistoryExpanded) {
                        detail.planHistory.filter { !it.isActive }.forEach { old ->
                            Column(modifier = Modifier.padding(top = Spacing.xs)) {
                                Text(
                                    text = "v${old.version} · ${TimeFormatUtils.formatShortDate(old.createdAt)}",
                                    style = type.label,
                                    color = colors.textDisabled,
                                )
                                Text(old.content, style = type.caption, color = colors.textSecondary)
                                Spacer(Modifier.height(Spacing.sm))
                            }
                        }
                    }
                }
            }
        }

        // ── 区块②：风格说明书 + 候选观察池 ───────────────────
        item {
            SectionCard(title = "风格说明书") {
                if (profile.styleNotes.isBlank()) {
                    Text(
                        text = "还在摸索阶段，暂时还没有沉淀出稳定的风格描述。",
                        style = type.caption,
                        color = colors.textDisabled,
                    )
                } else {
                    Text(profile.styleNotes, style = type.body, color = colors.textPrimary)
                }

                val candidates = remember(profile.candidateObservationsJson) {
                    parseCandidatesForDisplay(profile.candidateObservationsJson)
                }
                if (candidates.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text("正在观察的倾向", style = type.label, color = colors.textSecondary)
                    candidates.forEach { (trait, count) ->
                        Row(
                            modifier = Modifier.padding(top = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.xs))
                                    .background(colors.accent.copy(alpha = 0.1f))
                                    .padding(horizontal = Spacing.sm, vertical = 2.dp),
                            ) {
                                Text("已观察${count}次", style = type.label, color = colors.accent)
                            }
                            Spacer(Modifier.width(Spacing.xs))
                            Text(trait, style = type.caption, color = colors.textSecondary)
                        }
                    }
                }
            }
        }

        // ── 修炼历程标题 + 折叠开关 ───────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { recordsExpanded = !recordsExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("修炼历程", style = type.label, color = colors.textSecondary)
                Spacer(Modifier.weight(1f))
                Icon(
                    AppIcons.ExpandMore,
                    contentDescription = null,
                    tint = colors.textDisabled,
                    modifier = Modifier.rotate(if (recordsExpanded) 180f else 0f),
                )
            }
        }

        // ── 区块③：修炼历程（按时间倒序，已蒸馏的展示占位） ──────
        if (recordsExpanded) {
            items(detail.practiceRecords, key = { it.id }) { record ->
                PracticeRecordRow(
                    record = record,
                    onMarkMilestone = { viewModel.markMilestone(record.id) },
                    onViewArchived = { viewModel.getArchivedContent(record.id) },
                    ownerAccent = characterColor,
                )
            }
        }

        // ── 窗口6：发起一轮竞赛入口 ──────────────────────────
        item {
            SecondaryGoldButton(
                text = "发起一轮竞赛",
                onClick = { onNavigateToCompetition(profile.domain) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 从 candidateObservationsJson 解析出 (特征描述, 出现次数) 列表，仅供展示，
 *  不做任何写操作——与 SpecialtyProfileRepository.parseCandidateObservations
 *  逻辑相同但独立实现，避免 Compose 层直接依赖 Repository 实例。 */
private fun parseCandidatesForDisplay(json: String): List<Pair<String, Int>> {
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            obj.getString("trait") to obj.getInt("occurrenceCount")
        }
    } catch (e: Throwable) {
        ZLog.w("SpecialtyEvolutionScreen", "解析 candidateObservationsJson 失败，原始内容：$json", e)
        emptyList()
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    // WorldCard 接入（精修方案 v1.3）：通用 Section 容器，无角色归属，不传 ownerAccent。
    WorldCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.cardPadding)) {
            Text(title, style = type.cardTitle, color = colors.textPrimary)
            Spacer(Modifier.height(Spacing.sm))
            content()
        }
    }
}

@Composable
private fun PracticeRecordRow(
    record: com.zaijian.zhoumuyun.data.db.entity.PracticeRecordEntity,
    onMarkMilestone: () -> Unit,
    onViewArchived: suspend () -> String?,
    ownerAccent: Color? = null,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    var expanded by remember { mutableStateOf(false) }
    var archivedContent by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val tagColor = when (record.comparisonResult) {
        "EMERGING" -> colors.accent
        "CONFLICTING" -> Palette.SemanticDanger  // P3-53 修复
        else -> colors.textDisabled
    }
    val tagLabel = when (record.comparisonResult) {
        "EMERGING" -> "新发现"
        "CONFLICTING" -> "分歧"
        else -> "巩固"
    }

    // WorldCard 接入（精修方案 v1.3）：时间线式独立列表项卡片，逻辑与
    // TimelineEventCard 一致；与 TimelineEventCard 不同的是，本页是按
    // characterId 进入的单角色详情页，记录明确归属该角色，故传 ownerAccent。
    WorldCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        ownerAccent = ownerAccent,
    ) {
        Column(
            modifier = Modifier
                .clickable {
                    expanded = !expanded
                    if (expanded && record.digestStatus == "DIGESTED" && archivedContent == null) {
                        scope.launch { archivedContent = onViewArchived() }
                    }
                }
                .padding(Spacing.cardPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(tagColor.copy(alpha = 0.12f))
                        .padding(horizontal = Spacing.sm, vertical = 2.dp),
                ) {
                    Text(tagLabel, style = type.label, color = tagColor)
                }
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = record.practiceTopic,
                    style = type.caption.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = TimeFormatUtils.formatShortDate(record.createdAt),
                    style = type.label,
                    color = colors.textDisabled,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(Spacing.sm))
                val displayContent = if (record.digestStatus == "DIGESTED") {
                    archivedContent ?: "加载中…"
                } else {
                    record.content
                }
                Text(displayContent, style = type.caption, color = colors.textSecondary)
                if (record.digestStatus == "RAW") {
                    Spacer(Modifier.height(Spacing.xs))
                    SecondaryGoldButton(text = "标记为里程碑（不会被自动蒸馏）", onClick = onMarkMilestone)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  待处理建议卡片：候选转正 / 晋升请求 / AI自我提案，三类统一渲染
// ─────────────────────────────────────────────────────────────

@Composable
private fun SuggestionCard(
    suggestion: SystemSuggestionEntity,
    profileId: String,
    viewModel: SpecialtyEvolutionViewModel,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    // COMPETITION_FEEDBACK:: 是竞赛结算后写入的裁判点评，用稍重的金色边框与其他建议区分
    val isCompFeedback = suggestion.content.startsWith("COMPETITION_FEEDBACK::")

    val (displayText, icon) = when {
        suggestion.content.startsWith("CANDIDATE_CONFIRM::") ->
            suggestion.content.removePrefix("CANDIDATE_CONFIRM::") to AppIcons.Lightbulb
        suggestion.content.startsWith("PROMOTION_REQUEST::") ->
            suggestion.content.removePrefix("PROMOTION_REQUEST::") to AppIcons.Favorite
        isCompFeedback ->
            suggestion.content.removePrefix("COMPETITION_FEEDBACK::") to AppIcons.EmojiEvents
        else -> suggestion.content to AppIcons.TipsAndUpdates
    }

    // COMPETITION_FEEDBACK 背景/边框加重，与候选确认类建议形成层次区分
    val cardBg     = if (isCompFeedback) colors.accent.copy(alpha = 0.11f)
                     else                 colors.accent.copy(alpha = 0.06f)
    val cardBorder = if (isCompFeedback) colors.accent.copy(alpha = 0.50f)
                     else                 colors.accent.copy(alpha = 0.30f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(cardBg)
            .border(0.5.dp, cardBorder, RoundedCornerShape(Radius.md))
            .padding(Spacing.cardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(displayText, style = type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        }
        if (suggestion.reasoning.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(suggestion.reasoning, style = type.label, color = colors.textDisabled)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            when {
                suggestion.content.startsWith("CANDIDATE_CONFIRM::") -> {
                    SecondaryGoldButton(
                        text = "写进风格里",
                        onClick = { viewModel.confirmCandidate(profileId, suggestion) },
                    )
                    GhostGoldButton(
                        text = "先不用",
                        onClick = { viewModel.declineCandidate(profileId, suggestion) },
                    )
                }
                suggestion.content.startsWith("PROMOTION_REQUEST::") -> {
                    SecondaryGoldButton(
                        text = "写进她的本质里",
                        onClick = { viewModel.confirmPromotion(profileId, suggestion) },
                    )
                    GhostGoldButton(
                        text = "暂不",
                        onClick = { viewModel.declinePromotion(suggestion) },
                    )
                }
                else -> {
                    SecondaryGoldButton(
                        text = "采纳",
                        onClick = { viewModel.adoptSuggestion(suggestion.id) },
                    )
                    GhostGoldButton(
                        text = "忽略",
                        onClick = { viewModel.ignoreSuggestion(suggestion.id) },
                    )
                }
            }
        }
    }
}
