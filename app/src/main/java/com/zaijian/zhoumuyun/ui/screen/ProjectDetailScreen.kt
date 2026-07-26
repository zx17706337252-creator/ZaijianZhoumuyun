package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.zaijian.zhoumuyun.ui.design.AppIcons
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
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
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
    // 知识条目预览/编辑弹窗：持有当前点开的条目，null = 不展示。
    // 复用同一个弹窗承担"预览"和"编辑"——点开就能看到未截断的完整内容，
    // 顺手改完直接保存，不单独做一个只读预览态。
    var editingKnowledgeEntry  by remember { mutableStateOf<ProjectKnowledgeEntity?>(null) }
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
            // 窗口4补充：统一为 DetailTopBar
            DetailTopBar(
                title    = "项目详情",
                onBack   = onBack,
                headerBg = colors.bgBase,
            )
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
        // ── 顶栏（窗口4补充：统一为 DetailTopBar）───────────────────
        item {
            DetailTopBar(
                title    = detail.project?.title ?: "项目详情",
                onBack   = onBack,
                headerBg = colors.bgBase,
                actions  = {
                    // Audit-v1.33 P1-3/P1-4 修复：项目标题/描述编辑此前无 UI 入口
                    // P1-22 修复：completeProject 此前仅在 ProjectCard 传入 onComplete
                    // 回调但 ProjectCard 从未使用该回调（死参数），详情页菜单也缺失
                    // "完成"入口，导致 ACTIVE→COMPLETED 流转在 UI 层完全不可达。
                    detail.project?.let { project ->
                        ProjectDetailMenu(
                            status = project.status,
                            onEdit = { showEditProjectDialog = true },
                            onArchive = { viewModel.archiveProject(project.id) },
                            onPause = { viewModel.pauseProject(project.id) },
                            onReactivate = { viewModel.reactivateProject(project.id) },
                            onComplete = { viewModel.completeProject(project.id) },
                        )
                    }
                },
            )
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
            // D-3 收口：内联 Text → 统一空状态组件 EmptyStateView
            item {
                EmptyStateView(
                    icon     = AppIcons.Flag,
                    title    = "还没有里程碑",
                    subtitle = "点击上方「+ 添加」创建第一个",
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
                        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
                    ) {
                        Icon(
                            AppIcons.FolderOpenFilled,
                            contentDescription = "导入文件",
                            modifier = Modifier.size(14.dp),
                            tint = colors.primary,
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text("导入", color = colors.primary, style = type.label)
                    }
                }
                TextButton(
                    onClick = { showAddKnowledgeDialog = true },
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
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
            // D-3 收口：内联 Text → 统一空状态组件 EmptyStateView
            // （上面搜索无结果的分支保留原文本样式——那是"搜不到"而不是
            //  "没有数据"，语义不同，不套用同一个空状态组件）
            item {
                EmptyStateView(
                    icon     = AppIcons.MenuBook,
                    title    = "还没有知识条目",
                    subtitle = "点击上方「+ 添加」或「导入」创建第一条",
                )
            }
        } else {
            items(displayedKnowledge, key = { "k_${it.id}" }) { entry ->
                KnowledgeRow(
                    entry = entry,
                    onClick = { editingKnowledgeEntry = entry },
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
            // D-3 收口：内联 Text → 统一空状态组件 EmptyStateView
            item {
                EmptyStateView(
                    icon     = AppIcons.Person,
                    title    = "还没有参与角色",
                    subtitle = "点击上方「+ 添加」邀请角色加入",
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
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
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
                    Spacer(Modifier.width(Spacing.sm))
                    // 移除按钮（视觉28dp，触摸区扩展至48dp）
                    IconButton(
                        onClick  = { viewModel.removeMember(projectId, member.characterId) },
                        modifier = Modifier
                            .size(28.dp)
                            .minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            AppIcons.CloseFilled,
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
    // 知识条目预览/编辑：点击 KnowledgeRow 打开，entry 非空即展示。
    editingKnowledgeEntry?.let { entry ->
        KnowledgeEditDialog(
            entry = entry,
            onConfirm = { title, content, importance ->
                viewModel.updateKnowledge(entry.id, title, content, importance)
                editingKnowledgeEntry = null
            },
            onDismiss = { editingKnowledgeEntry = null },
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
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        available.forEach { char ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .clickable {
                                        viewModel.addMember(projectId, char.id, "CONTRIBUTOR")
                                        showAddMemberPicker = false
                                    }
                                    .padding(horizontal = Spacing.sm, vertical = 10.dp),
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

// Audit-v1.33 P1-3/P1-4 修复 + P1-22 修复：项目详情页操作菜单。
//
// 状态机（ProjectStatus）支持 ACTIVE/PAUSED/COMPLETED/ARCHIVED 四种状态，
// 此前 UI 只能从 ACTIVE 流转到 PAUSED/ARCHIVED，其余流转路径（暂停/恢复/
// 归档/完成/标题描述编辑）在 Repository/ViewModel 层均已定义，但从未接入
// 任何 UI 入口。P1-22 补齐了"完成项目"入口。
//
// 菜单项按当前状态动态显示，只暴露状态机语义上合法的下一步操作：
// - ACTIVE：编辑 / 完成 / 暂停 / 归档
// - PAUSED：编辑 / 完成 / 恢复 / 归档
// - COMPLETED：编辑 / 归档（已完成的项目不再需要暂停/恢复/再次完成）
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
    onComplete: () -> Unit,
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
                    Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
            // P1-22 修复：完成项目入口，仅在 ACTIVE/PAUSED 状态显示。
            // COMPLETED 状态已经是"已完成"，不需要重复完成。
            if (status == "ACTIVE" || status == "PAUSED") {
                HorizontalDivider()
                DropdownMenuItem(
                    text    = { Text("完成项目") },
                    onClick = { expanded = false; onComplete() },
                    leadingIcon = {
                        Icon(AppIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )
            }
            if (status == "ACTIVE" || status == "PAUSED") {
                HorizontalDivider()
                if (status == "ACTIVE") {
                    DropdownMenuItem(
                        text    = { Text("暂停") },
                        onClick = { expanded = false; onPause() },
                        leadingIcon = {
                            Icon(AppIcons.PauseCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text    = { Text("恢复") },
                        onClick = { expanded = false; onReactivate() },
                        leadingIcon = {
                            Icon(AppIcons.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
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
                        Icon(AppIcons.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
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
                imageVector = if (milestone.isCompleted) AppIcons.CheckCircleFilled else AppIcons.Circle,
                contentDescription = null,
                tint = if (milestone.isCompleted) Palette.SemanticSuccess else colors.onBackground.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(Spacing.sm))
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
                AppIcons.Search,
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
                        AppIcons.CloseFilled,
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

// ─────────────────────────────────────────────────────────────
//  KnowledgeEditDialog — 知识条目预览/编辑
//
//  点击 KnowledgeRow 打开。之前知识条目导入/添加后只有删除入口，
//  KnowledgeRow 里 content 又 maxLines=4 截断，长文本既看不全也改不了——
//  这里复用同一个弹窗承担"预览"和"编辑"：打开就是未截断的完整内容，
//  顺手改完直接保存，不单独做一个只读预览态。
//  content 输入框不设 maxLength：文件导入的条目可能有几千字，
//  与 ProjectRepository.updateKnowledge()"content 不截断"的约定一致，
//  截断等于丢数据。改用 heightIn 限制可视高度，超出部分交给
//  OutlinedTextField 自带的内部滚动。
// ─────────────────────────────────────────────────────────────
@Composable
private fun KnowledgeEditDialog(
    entry: ProjectKnowledgeEntity,
    onConfirm: (title: String, content: String, importance: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // remember(entry.id)：弹窗是同一个 Composable 实例在不同 entry 间复用
    // （editingKnowledgeEntry 状态切换时不会重新创建这个函数调用），必须按
    // entry.id 重新初始化，否则切换到另一条目时会残留上一条的编辑内容。
    var title      by remember(entry.id) { mutableStateOf(entry.title) }
    var content    by remember(entry.id) { mutableStateOf(entry.content) }
    var importance by remember(entry.id) { mutableStateOf(entry.importance) }
    var isConfirming by remember(entry.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("知识条目", color = colors.onBackground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.onBackground,
                        unfocusedTextColor = colors.onBackground,
                    ),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("知识内容（将注入 AI 上下文）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 280.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.onBackground,
                        unfocusedTextColor = colors.onBackground,
                    ),
                    supportingText = {
                        Text(
                            text = "${content.length} 字",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    },
                )
                Column {
                    Text(
                        text  = "重要度",
                        color = colors.onBackground.copy(alpha = 0.45f),
                        style = type.caption,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        for (level in 1..5) {
                            val selected = level == importance
                            // 与 KnowledgeRow 重要度圆点同一套配色（5红橙/4橙/3蓝/其余灰），
                            // 保持列表和编辑弹窗里"重要度"的视觉语言一致。
                            val levelColor = when (level) {
                                5    -> Palette.SemanticDanger
                                4    -> Palette.SemanticWarning
                                3    -> Palette.SemanticInfo
                                else -> colors.onBackground.copy(alpha = 0.4f)
                            }
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) levelColor.copy(alpha = 0.18f) else Color.Transparent)
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) levelColor else colors.onBackground.copy(alpha = 0.15f),
                                        shape = CircleShape,
                                    )
                                    .clickable { importance = level },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text  = level.toString(),
                                    color = if (selected) levelColor else colors.onBackground.copy(alpha = 0.5f),
                                    style = type.label,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isConfirming || content.isBlank()) return@TextButton
                    isConfirming = true
                    onConfirm(title.trim(), content.trim(), importance)
                },
            ) { Text("保存", color = colors.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.onBackground.copy(alpha = 0.5f)) }
        },
        containerColor = colors.bgCard,
    )
}

@Composable
private fun KnowledgeRow(
    entry: ProjectKnowledgeEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    // P3-32 修复：添加 type 引用，使用主题排印系统
    val type   = ZaijianTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.bgCard.copy(alpha = GlassOpacity.low)) // P3-17 修复：统一使用 bgCard 替代 surface
            // 整行可点开预览/编辑（原来只有删除入口，内容截断成4行后既看不全
            // 也改不了）。删除按钮自己也是可点击区域，Compose 里子元素的
            // clickable 会拦截住点击、不会被这里的行级 clickable 抢先消费。
            .clickable { onClick() }
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
        Spacer(Modifier.width(Spacing.sm))
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
            Spacer(Modifier.height(Spacing.xs))
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
                AppIcons.CloseFilled,
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
                imageVector        = AppIcons.Spa,
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
            // D-3 复核结论：这里不套用 EmptyStateView。EmptyStateView 是"列表
            // 整体为空、引导用户去创建"的场景（48dp图标+标题+副标题，纵向居中，
            // 撑满一屏区域）；这里是区块内的一条紧凑状态提示条，语义是"内容会在
            // 21:00自动生成"而非"没有内容、点这里创建"，没有可执行的操作，也不
            // 适合占用这么大的视觉空间。维持现有内联实现。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(colors.onBackground.copy(alpha = 0.04f))
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector        = AppIcons.Schedule,
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
                        modifier          = Modifier.padding(bottom = Spacing.xs),
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
                        Spacer(Modifier.width(Spacing.sm))
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
                                .padding(vertical = 3.dp, horizontal = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (isDone)
                                    AppIcons.CheckBox
                                else
                                    AppIcons.CheckBoxOutlineBlank,
                                contentDescription = if (isDone) "已完成" else "待完成",
                                tint     = if (isDone) growthGreen else colors.onBackground.copy(alpha = 0.35f),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(Spacing.sm))
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
                imageVector        = AppIcons.History,
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
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
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
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
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
