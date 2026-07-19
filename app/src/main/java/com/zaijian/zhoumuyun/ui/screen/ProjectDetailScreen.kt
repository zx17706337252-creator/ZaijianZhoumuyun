package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.ProjectKnowledgeEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMilestoneEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskStatus
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.component.SingleInputDialog
import com.zaijian.zhoumuyun.ui.viewmodel.DayGrowthSummary
import com.zaijian.zhoumuyun.ui.viewmodel.ProjectViewModel

// ─────────────────────────────────────────────────────────────
//  Project Detail Screen
//
//  3.8 修复：原先与 ProjectScreen（列表页）共处一个文件，二者
//  职责不同（列表 vs 详情），且详情页体量本身已经不小（含里程碑/
//  知识库/今日成长/历史成长等多个子模块），拆分为独立文件便于
//  单独维护。import 集合曾与 ProjectScreen.kt 共享，Phase 6（6.1）
//  已核实并清理本文件未使用的 import 项。
// ─────────────────────────────────────────────────────────────


@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBack: () -> Unit = {},
    viewModel: ProjectViewModel = viewModel(),
) {
    val detail by viewModel.detailState.collectAsStateWithLifecycle()
    // P3-32 修复：硬编码 fontSize 替换为 ZaijianTheme.typography
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 审查报告问题10修复：DefaultCharacters 只覆盖 ID 1-9，已注册的女儿角色
    // （ID>=1000）此前无法出现在"成员选择器""成员名字/头像反查"任何一处。
    // allSelectableCharacters 是 DefaultCharacters 与已注册女儿角色的合并列表，
    // 本文件所有原先 `DefaultCharacters.find { it.id == charId }` /
    // `DefaultCharacters.filter { ... }` 均改用这份合并列表。
    val daughterCharacters by viewModel.daughterCharacters.collectAsStateWithLifecycle()
    val allSelectableCharacters = remember(daughterCharacters) { DefaultCharacters + daughterCharacters }

    LaunchedEffect(projectId) { viewModel.openProject(projectId) }

    var showAddMilestoneDialog by remember { mutableStateOf(false) }
    var showAddKnowledgeDialog by remember { mutableStateOf(false) }
    var showAddMemberPicker    by remember { mutableStateOf(false) }
    // Audit-v1.33 P1-3 修复：项目标题/描述编辑对话框显示状态
    var showEditProjectDialog  by remember { mutableStateOf(false) }

    // Phase 31：文件选择器（TXT / MD / DOCX / PDF）
    val context = androidx.compose.ui.platform.LocalContext.current
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.importFile(context, it, projectId) }
    }

    // G2.6 修复：区分"正在加载详情"“项目不存在"“正常展示"三种状态，
    // 加载中先展示 loading 指示器，避免在数据到达前短暂看到空标题/空里程碑等
    // 半初始化界面；project 确实查不到时展示明确的错误提示而不是空白页。
    if (detail.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bgBase), // P3-17 修复：统一使用 bgBase 替代 background
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = colors.primary)
        }
        return
    }

    if (detail.project == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bgBase), // P3-17 修复：统一使用 bgBase 替代 background
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.onBackground)
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = detail.error ?: "项目不存在或已被删除",
                    color = colors.onBackground.copy(alpha = 0.5f),
                    style = type.body,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBase), // P3-17 修复：统一使用 bgBase 替代 background
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        // ── 顶栏 ───────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.onBackground)
                }
                Text(
                    text = detail.project?.title ?: "项目详情",
                    color = colors.onBackground,
                    // P3-32 修复：移除硬编码 fontSize 覆写
                    style = type.titleBold,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                // Audit-v1.33 P1-3/P1-4 修复：项目标题/描述编辑此前无 UI 入口
                // （updateProject 定义但从未调用），归档/暂停/恢复流转路径
                // 此前在 UI 层完全不可达（archiveProject 无入口，
                // pauseProject/reactivateProject 甚至未在 ViewModel 暴露）。
                // 菜单风格与 LearningGoalScreen.GoalCardMenu 保持一致
                // （IconButton(MoreVert) + DropdownMenu）。
                detail.project?.let { project ->
                    ProjectDetailMenu(
                        status = project.status,
                        onEdit = { showEditProjectDialog = true },
                        onArchive = { viewModel.archiveProject(project.id) },
                        onPause = { viewModel.pauseProject(project.id) },
                        onReactivate = { viewModel.reactivateProject(project.id) },
                    )
                }
            }
        }

        // ── 项目描述 ───────────────────────────────────────
        item {
            val desc = detail.project?.description ?: ""
            if (desc.isNotEmpty()) {
                Text(
                    text = desc,
                    color = colors.onBackground.copy(alpha = 0.6f),
                    style = type.body,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screenHorizontal)
                        .padding(bottom = Spacing.md),
                )
            }
        }

        // ── 项目概览：进度 + 统计 ───────────────────────────
        item {
            val totalMs       = detail.milestones.size
            val completedMs   = detail.milestones.count { it.isCompleted }
            val progress      = if (totalMs > 0) completedMs.toFloat() / totalMs else 0f
            val knowledgeCnt  = detail.knowledge.size
            val memberCnt     = detail.members.size

            // WorldCard 接入（精修方案 v1.3）：项目概览统计卡，混合展示
            // 进度/知识/成员等多类数据，不归属单一角色，不传 ownerAccent。
            WorldCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(bottom = Spacing.lg),
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
            ) {
                // 进度标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = "完成进度",
                        color    = colors.onBackground.copy(alpha = 0.6f),
                        style    = type.caption,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text     = "$completedMs / $totalMs",
                        color    = colors.primary,
                        style    = type.body,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                // 进度条
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colors.primary,
                    trackColor = colors.onBackground.copy(alpha = 0.08f),
                )
                Spacer(Modifier.height(Spacing.md))
                // 三列统计
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatItem(label = "里程碑", value = totalMs)
                    StatItem(label = "知识条目", value = knowledgeCnt)
                    StatItem(label = "参与角色", value = memberCnt)
                }
            }
            }
        }

        // ── P2-A：今日规划 ─────────────────────────────────
        item {
            TodayGrowthSection(
                byCharacter  = detail.todayGrowthByCharacter,
                members      = detail.members,
                allCharacters = allSelectableCharacters,
                onTaskToggle = { taskId -> viewModel.toggleGrowthTask(taskId) },
            )
        }

        // ── P2-B：成长记录 ─────────────────────────────────
        if (detail.recentGrowthSummary.isNotEmpty()) {
            item {
                GrowthHistorySection(
                    summaries = detail.recentGrowthSummary,
                    members   = detail.members,
                    allCharacters = allSelectableCharacters,
                )
            }
        }

        // ── 里程碑区域 ──────────────────────────────────────
        item {
            SectionHeader(
                title = "里程碑",
                actionLabel = "+ 添加",
                onAction = { showAddMilestoneDialog = true },
            )
        }

        if (detail.milestones.isEmpty()) {
            item {
                Text(
                    text = "还没有里程碑",
                    color = colors.onBackground.copy(alpha = 0.35f),
                    style = type.caption,
                    modifier = Modifier.padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical = Spacing.sm,
                    ),
                )
            }
        } else {
            items(detail.milestones, key = { "m_${it.id}" }) { milestone ->
                MilestoneRow(
                    milestone = milestone,
                    onComplete = { viewModel.completeMilestone(milestone.id) },
                )
            }
        }

        // ── 知识库区域 ──────────────────────────────────────
        item {
            Spacer(Modifier.height(Spacing.lg))
            // 标题行：「项目知识」+ 「导入文件」+ 「手动添加」
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "项目知识",
                    color = colors.onBackground.copy(alpha = 0.45f),
                    // P3-32 修复：移除硬编码 fontSize
                    style = type.caption,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                // 导入文件按钮
                if (detail.isImporting) {
                    LinearProgressIndicator(
                        modifier = Modifier.width(60.dp).height(2.dp),
                        color = colors.primary,
                    )
                } else {
                    TextButton(
                        onClick = {
                            filePicker.launch(
                                arrayOf(
                                    "text/plain",
                                    "text/markdown",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/pdf",
                                )
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = "导入文件",
                            modifier = Modifier.size(14.dp),
                            tint = colors.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("导入", color = colors.primary, style = type.label)
                    }
                }
                TextButton(
                    onClick = { showAddKnowledgeDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("+ 添加", color = colors.primary, style = type.label)
                }
            }
            // 导入错误提示
            if (detail.importError != null) {
                Text(
                    text = "导入失败：${detail.importError}",
                    color = MaterialTheme.colorScheme.error,
                    style = type.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screenHorizontal)
                        .clickable { viewModel.clearImportError() },
                )
            }
            // 审查报告问题9：知识库 FTS 搜索接入 UI。
            // repo.searchKnowledge() 此前已有完整实现但从未被调用，此处补齐入口。
            // 仅当知识条目数 > 0 时展示搜索框，避免在空知识库时展示一个无意义的搜索入口。
            if (detail.knowledge.isNotEmpty() || detail.knowledgeSearchQuery.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                KnowledgeSearchField(
                    query = detail.knowledgeSearchQuery,
                    onQueryChange = { viewModel.searchKnowledge(projectId, it) },
                    onClear = { viewModel.clearKnowledgeSearch() },
                    isSearching = detail.isSearchingKnowledge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screenHorizontal),
                )
                val searchError = detail.knowledgeSearchError
                if (searchError != null) {
                    Text(
                        text = searchError,
                        color = MaterialTheme.colorScheme.error,
                        style = type.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.screenHorizontal, vertical = 2.dp),
                    )
                }
            }
        }

        // 搜索模式：knowledgeSearchQuery 非空时，展示 knowledgeSearchResults 而非全量列表。
        val isSearchMode = detail.knowledgeSearchQuery.isNotEmpty()
        val displayedKnowledge = if (isSearchMode) detail.knowledgeSearchResults else detail.knowledge

        if (isSearchMode && !detail.isSearchingKnowledge && displayedKnowledge.isEmpty() && detail.knowledgeSearchError == null) {
            item {
                Text(
                    text = "没有找到匹配「${detail.knowledgeSearchQuery}」的知识条目",
                    color = colors.onBackground.copy(alpha = 0.35f),
                    style = type.caption,
                    modifier = Modifier.padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical = Spacing.sm,
                    ),
                )
            }
        } else if (!isSearchMode && detail.knowledge.isEmpty()) {
            item {
                Text(
                    text = "还没有知识条目",
                    color = colors.onBackground.copy(alpha = 0.35f),
                    style = type.caption,
                    modifier = Modifier.padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical = Spacing.sm,
                    ),
                )
            }
        } else {
            items(displayedKnowledge, key = { "k_${it.id}" }) { entry ->
                KnowledgeRow(
                    entry = entry,
                    onDelete = { viewModel.deleteKnowledge(entry.id) },
                )
            }
        }

        // ── 成员区域 ───────────────────────────────────────
        item {
            Spacer(Modifier.height(Spacing.lg))
            SectionHeader(
                title       = "参与角色",
                actionLabel = if (detail.members.size < allSelectableCharacters.size) "+ 添加" else null,
                onAction    = { showAddMemberPicker = true },
            )
        }
        if (detail.members.isEmpty()) {
            item {
                Text(
                    text     = "还没有参与角色",
                    color    = colors.onBackground.copy(alpha = 0.35f),
                    style    = type.caption,
                    modifier = Modifier.padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.sm,
                    ),
                )
            }
        } else {
            items(detail.members, key = { "mem_${it.id}" }) { member ->
                // 用合并后的角色列表（DefaultCharacters + 已注册女儿）查真实名字
                val charId   = member.characterId
                val charName = allSelectableCharacters.find { it.id == charId }?.name
                    ?: member.characterId.toString()
                val roleLabel = when (member.role) {
                    "OWNER"       -> "主导"
                    "CONTRIBUTOR" -> "参与"
                    else          -> member.role
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screenHorizontal, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 角色颜色标记
                    val accentColor = allSelectableCharacters.find { it.id == charId }?.accentColor
                        ?: Palette.SemanticNeutral
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text     = charName,
                        color    = colors.onBackground.copy(alpha = 0.85f),
                        style    = type.body,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text     = roleLabel,
                        color    = colors.onBackground.copy(alpha = 0.38f),
                        // P3-32 修复：移除硬编码 fontSize
                        style    = type.caption,
                    )
                    Spacer(Modifier.width(8.dp))
                    // 移除按钮（视觉28dp，触摸区扩展至48dp）
                    IconButton(
                        onClick  = { viewModel.removeMember(projectId, member.characterId) },
                        modifier = Modifier
                            .size(28.dp)
                            .minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "移除",
                            tint     = colors.onBackground.copy(alpha = 0.28f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }

    // ── 对话框 ─────────────────────────────────────────────
    if (showAddMilestoneDialog) {
        SingleInputDialog(
            title = "新建里程碑",
            placeholder = "里程碑标题",
            maxLength = 50,
            onConfirm = { text ->
                viewModel.addMilestone(projectId, text)
                showAddMilestoneDialog = false
            },
            onDismiss = { showAddMilestoneDialog = false },
        )
    }
    if (showAddKnowledgeDialog) {
        SingleInputDialog(
            title = "添加知识",
            placeholder = "知识内容（将注入 AI 上下文）",
            multiline = true,
            maxLength = 500,
            onConfirm = { text ->
                viewModel.addKnowledge(projectId, text)
                showAddKnowledgeDialog = false
            },
            onDismiss = { showAddKnowledgeDialog = false },
        )
    }

    // ── 角色选择器 ─────────────────────────────────────────
    if (showAddMemberPicker) {
        val alreadyAdded = detail.members.map { it.characterId }.toSet()
        val available    = allSelectableCharacters.filter { it.id !in alreadyAdded }
        AlertDialog(
            onDismissRequest = { showAddMemberPicker = false },
            title = { Text("添加参与角色", color = colors.onBackground) },
            text = {
                if (available.isEmpty()) {
                    Text("所有角色都已参与", color = colors.onBackground.copy(alpha = 0.5f))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        available.forEach { char ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .clickable {
                                        viewModel.addMember(projectId, char.id, "CONTRIBUTOR")
                                        showAddMemberPicker = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(char.accentColor),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text  = char.name,
                                    color = colors.onBackground,
                                    style = type.body,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddMemberPicker = false }) {
                    Text("取消", color = colors.onBackground.copy(alpha = 0.5f))
                }
            },
            containerColor = colors.bgCard, // P3-17 修复：统一使用 bgCard 替代 surface
        )
    }

    // ── 编辑项目标题/描述（Audit-v1.33 P1-3 修复）───────────────
    if (showEditProjectDialog) {
        detail.project?.let { project ->
            EditProjectDialog(
                initialTitle = project.title,
                initialDescription = project.description,
                onConfirm = { newTitle, newDescription ->
                    viewModel.updateProject(
                        project.copy(title = newTitle, description = newDescription)
                    )
                    showEditProjectDialog = false
                },
                onDismiss = { showEditProjectDialog = false },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Composable helpers
// ─────────────────────────────────────────────────────────────

// Audit-v1.33 P1-3/P1-4 修复：项目详情页操作菜单。
//
// 状态机（ProjectStatus）支持 ACTIVE/PAUSED/COMPLETED/ARCHIVED 四种状态，
// 此前 UI 只能从 ACTIVE 流转到 COMPLETED（ProjectCard 的"完成"按钮），
// 其余流转路径（暂停/恢复/归档、标题描述编辑）在 Repository/ViewModel
// 层均已定义，但从未接入任何 UI 入口。
//
// 菜单项按当前状态动态显示，只暴露状态机语义上合法的下一步操作：
// - ACTIVE：编辑 / 暂停 / 归档
// - PAUSED：编辑 / 恢复 / 归档
// - COMPLETED：编辑 / 归档（已完成的项目不再需要暂停/恢复）
// - ARCHIVED：仅编辑（归档是终态，不提供"取消归档"——审查报告未要求
//   此项，且状态机定义中 ARCHIVED 没有回退边，保持与既有状态机定义一致，
//   不擅自新增回退路径）
@Composable
private fun ProjectDetailMenu(
    status: String,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onPause: () -> Unit,
    onReactivate: () -> Unit,
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
                contentDescription = "项目操作",
                tint               = colors.onBackground.copy(alpha = 0.7f),
                modifier           = Modifier.size(20.dp),
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
            if (status == "ACTIVE" || status == "PAUSED") {
                HorizontalDivider()
                if (status == "ACTIVE") {
                    DropdownMenuItem(
                        text    = { Text("暂停") },
                        onClick = { expanded = false; onPause() },
                        leadingIcon = {
                            Icon(Icons.Outlined.PauseCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text    = { Text("恢复") },
                        onClick = { expanded = false; onReactivate() },
                        leadingIcon = {
                            Icon(Icons.Outlined.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }
            if (status != "ARCHIVED") {
                HorizontalDivider()
                DropdownMenuItem(
                    text    = { Text("归档") },
                    onClick = { expanded = false; onArchive() },
                    leadingIcon = {
                        Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
            }
        }
    }
}

// Audit-v1.33 P1-3 修复：编辑项目标题/描述对话框。
// 与 ProjectScreen.CreateProjectDialog 结构一致（复用同款双输入框布局），
// 区别在于本对话框预填当前项目的 title/description 作为初始值。
@Composable
private fun EditProjectDialog(
    initialTitle: String,
    initialDescription: String,
    onConfirm: (title: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf(initialDescription) }
    val colors = ZaijianTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑项目", color = colors.onBackground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 50) title = it },
                    label = { Text("项目名称（≤50字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.onBackground,
                        unfocusedTextColor = colors.onBackground,
                    ),
                    supportingText = {
                        Text(
                            text = "${title.length}/50",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { if (it.length <= 500) description = it },
                    label = { Text("描述（可选，≤500字）") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.onBackground,
                        unfocusedTextColor = colors.onBackground,
                    ),
                    supportingText = {
                        Text(
                            text = "${description.length}/500",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank()) onConfirm(title.trim(), description.trim()) },
            ) { Text("保存", color = colors.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.onBackground.copy(alpha = 0.5f)) }
        },
        containerColor = colors.bgCard,
    )
}

@Composable
private fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    // P3-32 修复：添加 type 引用，使用主题排印系统
    val type   = ZaijianTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = colors.onBackground.copy(alpha = 0.45f),
            // P3-32 修复：移除硬编码 fontSize
            style = type.caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                color = colors.primary.copy(alpha = 0.8f),
                style = type.caption,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: Int) {
    val colors = ZaijianTheme.colors
    // P3-32 修复：添加 type 引用，使用主题排印系统
    val type   = ZaijianTheme.typography
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text     = value.toString(),
            color    = colors.onBackground.copy(alpha = 0.85f),
            style    = type.titleBold,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text     = label,
            color    = colors.onBackground.copy(alpha = 0.4f),
            style    = type.label,
        )
    }
}

@Composable
private fun MilestoneRow(
    milestone: ProjectMilestoneEntity,
    onComplete: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    // P3-32 修复：添加 type 引用，使用主题排印系统
    val type   = ZaijianTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { if (!milestone.isCompleted) onComplete() },
            modifier = Modifier
                .size(32.dp)
                .minimumInteractiveComponentSize(),
        ) {
            Icon(
                imageVector = if (milestone.isCompleted) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (milestone.isCompleted) Palette.SemanticSuccess else colors.onBackground.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = milestone.title,
            color = if (milestone.isCompleted) colors.onBackground.copy(alpha = 0.35f)
                    else colors.onBackground,
            style = type.body,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  知识库搜索框（审查报告问题9：searchKnowledge FTS 接入 UI）
// ─────────────────────────────────────────────────────────────

@Composable
private fun KnowledgeSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    isSearching: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("搜索知识库…", style = ZaijianTheme.typography.caption) },
        singleLine = true,
        textStyle = ZaijianTheme.typography.caption,
        leadingIcon = {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = colors.onBackground.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.primary,
                )
            } else if (query.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "清空搜索",
                        tint = colors.onBackground.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        shape = RoundedCornerShape(Radius.md),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.onBackground,
            unfocusedTextColor = colors.onBackground,
            focusedBorderColor = colors.primary.copy(alpha = 0.5f),
            unfocusedBorderColor = colors.onBackground.copy(alpha = 0.15f),
        ),
    )
}

@Composable
private fun KnowledgeRow(
    entry: ProjectKnowledgeEntity,
    onDelete: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    // P3-32 修复：添加 type 引用，使用主题排印系统
    val type   = ZaijianTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.bgCard.copy(alpha = GlassOpacity.low)) // P3-17 修复：统一使用 bgCard 替代 surface
            .padding(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        // 重要度指示
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when (entry.importance) {
                        5    -> Palette.SemanticDanger   // 最高优先级 · 红橙
                        4    -> Palette.SemanticWarning  // 高优先级 · 橙
                        3    -> Palette.SemanticInfo     // 中优先级 · 蓝
                        else -> colors.onBackground.copy(alpha = 0.2f)
                    }
                ),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (entry.title.isNotEmpty()) {
                Text(entry.title, color = colors.onBackground, style = type.caption, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = entry.content,
                color = colors.onBackground.copy(alpha = 0.65f),
                style = type.caption,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            // Phase 31：来源徽章 + 字数
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val sourceLabel = when (entry.source) {
                    "FILE_IMPORT"  -> "📄 文件"
                    "AUTO_EXTRACT" -> "🤖 自动"
                    "URL_IMPORT"   -> "🔗 链接"
                    else           -> "✍️ 手动"
                }
                Text(
                    text = sourceLabel,
                    color = colors.onBackground.copy(alpha = 0.35f),
                    style = type.label,
                )
                val charCount = if (entry.charCount > 0) entry.charCount else entry.content.length
                if (charCount > 0) {
                    Text(
                        text = "  ·  ${charCount} 字",
                        color = colors.onBackground.copy(alpha = 0.3f),
                        style = type.label,
                    )
                }
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp).minimumInteractiveComponentSize()) {
            Icon(
                Icons.Default.Close,
                contentDescription = "删除",
                tint = colors.onBackground.copy(alpha = 0.3f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// SingleInputDialog 已收敛至 ui/component/CommonDialogs.kt（架构瘦身 Phase 1 第4项）

// ─────────────────────────────────────────────────────────────
//  P2-A：今日规划区块
// ─────────────────────────────────────────────────────────────

/**
 * 展示本项目今日由各角色规划的 project_growth 任务。
 * 按角色分组，每组用角色 24dp 圆形头像作为分隔标签。
 * 空状态显示「将在21:00自动生成」提示。
 *
 * @param byCharacter   characterId → 今日成长任务列表（来自 detailState.todayGrowthByCharacter）
 * @param members       项目参与成员列表，用于确定展示顺序
 * @param onTaskToggle  用户勾选/取消勾选某条任务时的回调
 */
@Composable
private fun TodayGrowthSection(
    byCharacter: Map<Int, List<TaskEntity>>,
    members: List<com.zaijian.zhoumuyun.data.db.entity.ProjectMemberEntity>,
    allCharacters: List<com.zaijian.zhoumuyun.data.model.CharacterConfig>,
    onTaskToggle: (taskId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors      = ZaijianTheme.colors
    // P3-32 修复：添加 type 引用，使用主题排印系统
    val type        = ZaijianTheme.typography
    // P3-55 扩展：硬编码绿色统一为主题常量 Palette.GrowthGreen
    val growthGreen = Palette.GrowthGreen

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal)
            .padding(bottom = Spacing.lg),
    ) {
        // ── 区块标题行 ────────────────────────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector        = Icons.Outlined.Spa,
                contentDescription = null,
                tint               = growthGreen,
                modifier           = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text      = "今日规划",
                color     = colors.onBackground.copy(alpha = 0.55f),
                // P3-32 修复：移除硬编码 fontSize
                style     = type.caption,
                fontWeight = FontWeight.Medium,
                modifier  = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        if (byCharacter.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(colors.onBackground.copy(alpha = 0.04f))
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector        = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint               = colors.onBackground.copy(alpha = 0.25f),
                        modifier           = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = "今日规划将在 21:00 自动生成",
                        color    = colors.onBackground.copy(alpha = 0.35f),
                        style    = type.caption,
                    )
                }
            }
        } else {
            // 按成员顺序展示（有成员列表则按成员顺序，否则按 characterId 升序）
            val orderedIds = if (members.isNotEmpty()) {
                members.map { it.characterId }
                    .filter { it in byCharacter }
            } else {
                byCharacter.keys.sorted()
            }

            orderedIds.forEach { charId ->
                val tasks    = byCharacter[charId] ?: return@forEach
                val charConf = allCharacters.find { it.id == charId }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.sm),
                ) {
                    // ── 角色头像行 ───────────────────────────
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.padding(bottom = 4.dp),
                    ) {
                        // 24dp 角色颜色圆点（规格：Section 9-C）
                        Box(
                            modifier         = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    charConf?.accentColor
                                        ?: colors.onBackground.copy(alpha = 0.2f)
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text     = charConf?.name?.take(1) ?: "?",
                                // W10问题5修复：改用 Palette.White 而非裸 Color.White，
                                // 统一颜色定义入口。背景色为 charConf.accentColor（角色
                                // 主题色，饱和度通常较高），当前对比度可接受；若未来
                                // accentColor 允许浅色取值，需改为按背景亮度动态选字色。
                                color    = Palette.White,
                                style    = type.label,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text     = charConf?.name ?: "角色 $charId",
                            color    = colors.onBackground.copy(alpha = 0.7f),
                            style    = type.caption,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    // ── 任务列表 ─────────────────────────────
                    tasks.forEach { task ->
                        val isDone = task.status == TaskStatus.COMPLETED.name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTaskToggle(task.id) }
                                .padding(vertical = 3.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (isDone)
                                    Icons.Outlined.CheckBox
                                else
                                    Icons.Outlined.CheckBoxOutlineBlank,
                                contentDescription = if (isDone) "已完成" else "待完成",
                                tint     = if (isDone) growthGreen else colors.onBackground.copy(alpha = 0.35f),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text  = task.title,
                                color = if (isDone)
                                    colors.onBackground.copy(alpha = 0.35f)
                                else
                                    colors.onBackground.copy(alpha = 0.82f),
                                style = if (isDone)
                                    type.body.copy(
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                                    )
                                else
                                    type.body,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  P2-B：成长记录区块
// ─────────────────────────────────────────────────────────────

/**
 * 展示近7天的成长记录摘要。
 * 每行一天：日期 + 各参与角色的规划条数。
 *
 * @param summaries 按日期倒序的每日摘要列表
 * @param members   参与成员，用于显示角色名称
 */
@Composable
private fun GrowthHistorySection(
    summaries: List<DayGrowthSummary>,
    members: List<com.zaijian.zhoumuyun.data.db.entity.ProjectMemberEntity>,
    allCharacters: List<com.zaijian.zhoumuyun.data.model.CharacterConfig>,
    modifier: Modifier = Modifier,
) {
    val colors      = ZaijianTheme.colors
    // P3-32 修复：添加 type 引用，使用主题排印系统
    val type        = ZaijianTheme.typography
    // P3-55 扩展：硬编码绿色统一为主题常量 Palette.GrowthGreen
    val growthGreen = Palette.GrowthGreen

    // 最多展示 5 天，防止区块过高
    val displaySummaries = summaries.take(5)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal)
            .padding(bottom = Spacing.lg),
    ) {
        // ── 区块标题 ──────────────────────────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector        = Icons.Outlined.History,
                contentDescription = null,
                tint               = colors.onBackground.copy(alpha = 0.45f),
                modifier           = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text      = "成长记录",
                color     = colors.onBackground.copy(alpha = 0.55f),
                // P3-32 修复：移除硬编码 fontSize
                style     = type.caption,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // WorldCard 接入（精修方案 v1.3）：成长记录历史卡，混合展示
        // 多天多角色统计，不归属单一角色，不传 ownerAccent。
        WorldCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            displaySummaries.forEach { summary ->
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 日期标签
                    Text(
                        text     = summary.dateLabel,
                        color    = colors.onBackground.copy(alpha = 0.45f),
                        // P3-32 修复：移除硬编码 fontSize
                        style    = type.caption,
                        modifier = Modifier.width(52.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))

                    // 各角色规划数（用角色颜色圆点 + 数量）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        summary.countByCharacter.entries
                            .sortedBy { it.key }
                            .forEach { (charId, count) ->
                                val charConf = allCharacters.find { it.id == charId }
                                val charName = charConf?.name ?: "角色$charId"
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                charConf?.accentColor
                                                    ?: colors.onBackground.copy(alpha = 0.3f)
                                            ),
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        text     = "$charName 规划了 $count 件",
                                        color    = colors.onBackground.copy(alpha = 0.62f),
                                        // P3-32 修复：移除硬编码 fontSize
                                        style    = type.caption,
                                    )
                                }
                            }
                    }

                    // 当天总完成率（用绿色文字标注）
                    Text(
                        text     = "${summary.totalCount} 件",
                        color    = growthGreen,
                        // P3-32 修复：移除硬编码 fontSize
                        style    = type.caption,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // 超过5天提示
            if (summaries.size > 5) {
                Text(
                    text     = "仅显示近5天记录",
                    color    = colors.onBackground.copy(alpha = 0.25f),
                    style    = type.label,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        }
    }
}
