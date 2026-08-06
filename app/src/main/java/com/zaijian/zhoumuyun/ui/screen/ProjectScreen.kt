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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectKnowledgeEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMilestoneEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskStatus
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.AppBrushes
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.SerifSC
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.DayGrowthSummary
import com.zaijian.zhoumuyun.ui.viewmodel.ProjectCardSummary
import com.zaijian.zhoumuyun.ui.viewmodel.ProjectViewModel
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.GoldPrimaryButton

// ─────────────────────────────────────────────────────────────
//  Project List Screen
//
//  3.8 修复：ProjectDetailScreen 已拆分到独立文件
//  ProjectDetailScreen.kt（详情页 SectionHeader/里程碑/知识库/
//  今日成长/历史成长等专属子模块一并带走），本文件只保留列表页
//  自身用到的组件。
// ─────────────────────────────────────────────────────────────

@Composable
fun ProjectScreen(
    onNavigateToDetail: (projectId: String) -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: ProjectViewModel = viewModel(),
) {
    // G2.5 修复：改用 listState 以获取 isLoading，区分"正在加载"与"确实没有项目"。
    val listState by viewModel.listState.collectAsStateWithLifecycle()
    val activeProjects = listState.projects
    var showCreateDialog by remember { mutableStateOf(false) }
    val colors = ZaijianTheme.colors

    // 帧14 头像叠放需要把 member.characterId 反查为角色名/主题色：
    // 合并默认角色与已注册女儿角色（与 ProjectDetailScreen 一致的反查来源）。
    val daughterCharacters by viewModel.daughterCharacters.collectAsStateWithLifecycle()
    val allSelectableCharacters = remember(daughterCharacters) {
        DefaultCharacters + daughterCharacters
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBase) // P3-17 修复：统一使用 bgBase/bgCard 替代 background/surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── 顶栏（窗口4补充：统一为 DetailTopBar）────────────────
            DetailTopBar(
                title    = "项目",
                onBack   = onBack,
                headerBg = colors.bgBase,
            )

            // ── 项目列表 ─────────────────────────────────────
            // G2.5 修复：加载中显示 loading 指示器，只有确认加载完成且列表
            // 为空时才展示"暂无项目"提示，避免加载期间闪一下空状态。
            //
            // 审查报告问题9修复：listState.error 此前从未被读取——加载失败时
            // observeActive() 的 .catch{} 会 emit 一个空列表兜底，若只看
            // isLoading/isEmpty，会把"数据库故障"误判成"还没有项目"，展示
            // 引导用户创建项目的空状态，而不是真正的错误提示。这里优先判断
            // error，避免用户被"你还没有项目"误导。
            if (listState.isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.primary)
                }
            } else if (listState.error != null) {
                LoadFailedHint(message = listState.error!!, modifier = Modifier.weight(1f))
            } else if (activeProjects.isEmpty()) {
                EmptyProjectsHint(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        horizontal = Spacing.screenHorizontal,
                        vertical = Spacing.md,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(activeProjects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { onNavigateToDetail(project.id) },
                            viewModel = viewModel,
                            allCharacters = allSelectableCharacters,
                        )
                    }
                }
            }
        }

        // ── FAB ─────────────────────────────────────────────
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(Spacing.lg),
            containerColor = colors.primary,
        ) {
            Icon(AppIcons.AddFilled, contentDescription = "新建项目", tint = Color.White)
        }
    }

    // ── 新建项目对话框 ────────────────────────────────────────
    if (showCreateDialog) {
        CreateProjectDialog(
            onConfirm = { title, desc ->
                viewModel.createProject(title, desc) { projectId ->
                    onNavigateToDetail(projectId)
                }
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Project Card
// ─────────────────────────────────────────────────────────────

@Composable
private fun ProjectCard(
    project: ProjectEntity,
    onClick: () -> Unit,
    viewModel: ProjectViewModel,
    allCharacters: List<CharacterConfig>,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors

    // 帧14 摘要：里程碑 / 知识 / 成员一次性快照（列表项通常很少，一次性查询
    // 足够；项目增删会触发整表 Flow 刷新重建卡片，摘要自然重算）。
    val summary by produceState<ProjectCardSummary?>(
        null,
        project.id,
    ) {
        value = viewModel.getProjectCardSummary(project.id)
    }
    val s = summary

    // WorldCard 接入（精修方案 v1.3）：项目列表项，项目本身不归属单一
    // 角色（可含多名参与角色），不传 ownerAccent。
    WorldCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.FolderOpenFilled,
                        contentDescription = null,
                        tint = colors.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = project.title,
                        color = colors.onBackground,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                // 状态标签
                ProjectStatusChip(status = project.status)
            }

            if (project.description.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = project.description,
                    color = colors.onBackground.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── 帧14 金条进度（标题下方）──────────────────────────
            // 高度 3dp，黄铜渐变，宽度按里程碑完成度 fillMaxWidth(fraction)；
            // 仅当存在里程碑时展示，fraction = 已完成 / 总数。
            if (s != null && s.milestones.isNotEmpty()) {
                val fraction = (s.milestones.count { it.isCompleted }.toFloat() / s.milestones.size)
                    .coerceIn(0f, 1f)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.accent.copy(alpha = 0.12f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .background(AppBrushes.goldGradient()),
                    )
                }
            }

            // ── 帧14 三列统计：里程碑 / 知识 / 角色 ──────────────
            if (s != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ProjectStatColumn(count = s.milestones.size, label = "里程碑")
                    ProjectStatColumn(count = s.knowledgeCount, label = "知识")
                    ProjectStatColumn(count = s.memberCharacterIds.size, label = "角色")
                }
            }

            // ── 帧14 头像叠放 + 里程碑 chip（卡底）────────────────
            if (s != null && (s.memberCharacterIds.isNotEmpty() || s.milestones.isNotEmpty())) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // 头像叠放：最多 3 个 28dp 圆形头像，offset(-8.dp) 递进叠放，超出 +N
                    if (s.memberCharacterIds.isNotEmpty()) {
                        ProjectAvatarStack(
                            characterIds = s.memberCharacterIds,
                            allCharacters = allCharacters,
                        )
                    } else {
                        Spacer(Modifier.width(0.dp))
                    }
                    // 里程碑 chip：最多 2 个
                    if (s.milestones.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.milestones.take(2).forEach { ms ->
                                ProjectMilestoneChip(title = ms.title.ifBlank { "里程碑" })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 帧14 三列统计单元：数字（SerifSC Bold 深金）+ 标签（label 次要色）。
 */
@Composable
private fun ProjectStatColumn(count: Int, label: String) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            color = colors.accentDeep,
            fontFamily = SerifSC,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Text(
            text = label,
            style = type.label,
            color = colors.textSecondary,
        )
    }
}

/**
 * 帧14 头像叠放：最多 3 个 28dp 圆形头像按 -8dp 递进叠放，超出显示 +N。
 * 头像用角色主题色底 + 首字（SerifSC），与私聊配对卡的双头像风格一致。
 */
@Composable
private fun ProjectAvatarStack(
    characterIds: List<Int>,
    allCharacters: List<CharacterConfig>,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val display = characterIds.take(3)
    val overflow = characterIds.size - display.size

    Row {
        display.forEachIndexed { index, charId ->
            val config = allCharacters.find { it.id == charId }
            val accent = config?.accentColor ?: Palette.SemanticNeutral
            val initial = (config?.name?.firstOrNull() ?: '?').toString()
            Box(
                modifier = Modifier
                    .offset(x = if (index == 0) 0.dp else (-8).dp * index)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .border(1.dp, colors.bgCard, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontFamily = SerifSC,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .offset(x = (-8).dp * display.size)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.bgElevated)
                    .border(1.dp, colors.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflow",
                    color = colors.textSecondary,
                    style = type.label,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * 帧14 里程碑 chip：12% 金底 + 0.5dp 金边 + 深金字。
 */
@Composable
private fun ProjectMilestoneChip(title: String) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.accent.copy(alpha = 0.12f))
            .border(0.5.dp, colors.accent.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = title,
            style = type.label,
            color = colors.accentDeep,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProjectStatusChip(status: String) {
    val (label, color) = when (status) {
        "ACTIVE"    -> "进行中" to Palette.SemanticSuccess  // 活跃 · 绿
        "PAUSED"    -> "暂停"   to Palette.SemanticWarning  // 暂停 · 橙
        "COMPLETED" -> "已完成" to Palette.SemanticInfo     // 完成 · 蓝
        "ARCHIVED"  -> "已归档" to Palette.SemanticNeutral  // 归档 · 灰
        else        -> status   to Palette.SemanticNeutral
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
    ) {
        Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyProjectsHint(modifier: Modifier = Modifier) {
    // D-3 P3：空状态收口至统一组件 EmptyStateView
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        EmptyStateView(
            icon     = AppIcons.FolderOpenFilled,
            title    = "还没有项目",
            subtitle = "点击右下角 + 创建第一个",
        )
    }
}

/**
 * 审查报告问题9新增：区分"加载失败"与"确实没有项目"。
 * 不带重试按钮——listState 是 Room Flow 直接派生的响应式流，WhileSubscribed(5_000)
 * 会在退出页面重新进入时自动重新订阅查询，按钮点了也无法触发独立于此之外的重试，
 * 加了容易显得是摆设，故只做提示，引导用户返回重进。
 */
@Composable
private fun LoadFailedHint(message: String, modifier: Modifier = Modifier) {
    val colors = ZaijianTheme.colors
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                AppIcons.FolderOpenFilled,
                contentDescription = null,
                tint = Palette.SemanticDanger.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "加载失败",
                color = Palette.SemanticDanger,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = message,
                color = colors.onBackground.copy(alpha = 0.35f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "请返回重试",
                color = colors.onBackground.copy(alpha = 0.2f),
                fontSize = 13.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Dialog helpers
// ─────────────────────────────────────────────────────────────

@Composable
private fun CreateProjectDialog(
    onConfirm: (title: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val colors = ZaijianTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建项目", color = colors.onBackground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.onBackground,
                        unfocusedTextColor = colors.onBackground,
                    ),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（可选）") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.onBackground,
                        unfocusedTextColor = colors.onBackground,
                    ),
                )
            }
        },
        confirmButton = {
            // W14 P1 问题3修复：原按钮始终可点击，title 为空时 onClick 内部静默
            // 不执行 onConfirm，用户点击无任何视觉反馈，容易误以为功能失效。
            // 加 enabled 控制后 Compose 会在 enabled=false 时不触发 onClick，
            // 原先的 if (title.isNotBlank()) 守卫随之冗余，一并去掉。
            // 迁移说明：AppButtons 不支持 enabled，手动用 alpha(0.4f) 表达禁用态，
            // 并在 onClick 中跳过禁用态点击（等价于原 enabled=false 不触发）。
            val createEnabled = title.isNotBlank()
            GoldPrimaryButton(
                text     = "创建",
                onClick  = { if (createEnabled) onConfirm(title.trim(), description.trim()) },
                modifier = Modifier.alpha(if (createEnabled) 1f else 0.4f),
            )
        },
        dismissButton = {
            GhostGoldButton(
                text    = "取消",
                onClick = onDismiss,
            )
        },
        containerColor = colors.bgCard, // P3-17 修复：统一使用 bgCard 替代 surface
    )
}

// ─────────────────────────────────────────────────────────────
//  Previews
// ─────────────────────────────────────────────────────────────

@Preview(name = "ProjectScreen · Dark", showBackground = true,
    backgroundColor = 0xFF12131A.toLong(), widthDp = 390, heightDp = 844)
@Composable
private fun PreviewProjectScreenDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) {
        ProjectScreen()
    }
}
