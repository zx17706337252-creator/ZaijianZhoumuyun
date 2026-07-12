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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.ImeAction
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
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.DayGrowthSummary
import com.zaijian.zhoumuyun.ui.viewmodel.ProjectViewModel

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBase) // P3-17 修复：统一使用 bgBase/bgCard 替代 background/surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── 顶栏 ────────────────────────────────────────
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
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "项目",
                    color = colors.onBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── 项目列表 ─────────────────────────────────────
            // G2.5 修复：加载中显示 loading 指示器，只有确认加载完成且列表
            // 为空时才展示"暂无项目"提示，避免加载期间闪一下空状态。
            if (listState.isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.primary)
                }
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
                            onComplete = { viewModel.completeProject(project.id) },
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
            Icon(Icons.Default.Add, contentDescription = "新建项目", tint = Color.White)
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
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors

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
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = colors.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
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
                )
            }
        }
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
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyProjectsHint(modifier: Modifier = Modifier) {
    val colors = ZaijianTheme.colors
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                tint = colors.onBackground.copy(alpha = 0.2f),
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "还没有项目",
                color = colors.onBackground.copy(alpha = 0.35f),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "点击右下角 + 创建第一个",
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
            TextButton(
                onClick = { if (title.isNotBlank()) onConfirm(title.trim(), description.trim()) },
            ) { Text("创建", color = colors.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.onBackground.copy(alpha = 0.5f)) }
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
