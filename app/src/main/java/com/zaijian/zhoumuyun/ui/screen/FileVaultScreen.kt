package com.zaijian.zhoumuyun.ui.screen

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.ui.design.IconBadge
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.FileVaultViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.VaultNode

// ─────────────────────────────────────────────────────────────
//  FileVaultScreen — v147 文件保险库改造（树形重写）
//
//  入口：
//    - CharacterDetailScreen 主 Tab「文件」
//    - ChatSettingsSheet「文件」条目 → 角色私库
//    - AppNavigation FileVault 路由
//
//  功能：
//    - 树形展示 vault 三段目录（角色私库 / 圆桌共享 / 项目共享）
//    - 文件夹可展开/折叠
//    - 文件支持：预览（文本类）、编辑（文本类）、导出到 Downloads、分享、删除
// ─────────────────────────────────────────────────────────────

@Composable
fun FileVaultScreen(
    characterId: Int,
    onBack: () -> Unit = {},
    // v1.48：跳转到统一文件预览编辑页
    onNavigateToPreview: (String) -> Unit = {},
    viewModel: FileVaultViewModel = viewModel(
        factory = FileVaultViewModel.factory(characterId),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography

    LaunchedEffect(Unit) { viewModel.load() }
    BackHandler { onBack() }

    LaunchedEffect(uiState.snackbarMessage) {
        if (uiState.snackbarMessage != null) {
            kotlinx.coroutines.delay(2_500)
            viewModel.clearSnackbar()
        }
    }

    val flatNodes = remember(uiState.roots, uiState.expandedPaths) {
        flattenTree(uiState.roots, uiState.expandedPaths)
    }

    Box(Modifier.fillMaxSize().background(colors.bgBase)) {
        Column(Modifier.fillMaxSize()) {
            VaultHeader(onBack = onBack)

            Spacer(Modifier.height(Spacing.sm))

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            } else if (flatNodes.isEmpty()) {
                EmptyVaultHint()
            } else {
                LazyColumn(
                    modifier       = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.xs,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(flatNodes, key = { it.node.absolutePath }) { flat ->
                        when (val node = flat.node) {
                            is VaultNode.Folder -> VaultFolderRow(
                                folder      = node,
                                depth       = flat.depth,
                                isExpanded  = node.absolutePath in uiState.expandedPaths,
                                onToggle    = { viewModel.toggleFolder(node.absolutePath) },
                                onDelete    = { viewModel.requestDelete(node) },
                            )
                            is VaultNode.FileLeaf -> VaultFileRow(
                                file     = node,
                                depth    = flat.depth,
                                onPreview = { onNavigateToPreview(node.absolutePath) },
                                onEdit   = { onNavigateToPreview(node.absolutePath) },
                                onShare  = { shareFile(context, java.io.File(node.absolutePath)) },
                                onExport = { viewModel.exportToDownloads(node) },
                                onDelete = { viewModel.requestDelete(node) },
                            )
                        }
                    }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }

        // ── 预览对话框 ──
        uiState.previewTarget?.let { target ->
            AlertDialog(
                onDismissRequest = viewModel::closePreview,
                title = { Text(target.name, style = type.titleBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (uiState.previewLoading) {
                            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(20.dp))
                        } else {
                            Text(
                                text  = uiState.previewContent ?: "（无法读取内容）",
                                style = type.body,
                                color = colors.textPrimary,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = viewModel::closePreview) { Text("关闭") }
                },
            )
        }

        // ── 编辑对话框 ──
        uiState.editTarget?.let { target ->
            AlertDialog(
                onDismissRequest = viewModel::cancelEdit,
                title = { Text("编辑·${target.name}", style = type.titleBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    OutlinedTextField(
                        value    = uiState.editContent,
                        onValueChange = viewModel::updateEditContent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        textStyle = type.body,
                    )
                },
                confirmButton = { TextButton(onClick = viewModel::saveEdit) { Text("保存") } },
                dismissButton = { TextButton(onClick = viewModel::cancelEdit) { Text("取消") } },
            )
        }

        // ── 删除确认 ──
        uiState.deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = viewModel::cancelDelete,
                title   = { Text(if (target is VaultNode.Folder) "删除文件夹" else "删除文件") },
                text    = {
                    val hint = if (target is VaultNode.Folder) "将递归删除该文件夹下所有文件，且不可恢复。" else "此操作不可恢复。"
                    Text("确定删除「${target.name}」？$hint")
                },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmDelete) {
                        Text("删除", color = Palette.TaskFailed)
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("取消") } },
            )
        }

        // ── Snackbar ──
        uiState.snackbarMessage?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(bottom = Spacing.md)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(colors.bgElevated)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                Text(msg, color = colors.textPrimary, style = type.caption)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  树展平
// ─────────────────────────────────────────────────────────────

private data class FlatNode(val node: VaultNode, val depth: Int)

private fun flattenTree(roots: List<VaultNode>, expanded: Set<String>): List<FlatNode> {
    val out = mutableListOf<FlatNode>()
    fun walk(nodes: List<VaultNode>, depth: Int) {
        for (n in nodes) {
            out.add(FlatNode(n, depth))
            if (n is VaultNode.Folder && n.absolutePath in expanded) {
                walk(n.children, depth + 1)
            }
        }
    }
    walk(roots, 0)
    return out
}

// ─────────────────────────────────────────────────────────────
//  顶栏
// ─────────────────────────────────────────────────────────────

@Composable
private fun VaultHeader(onBack: () -> Unit) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint               = colors.textPrimary,
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text       = "文件库",
            style      = type.titleBold,
            color      = colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  文件夹行
// ─────────────────────────────────────────────────────────────

@Composable
private fun VaultFolderRow(
    folder: VaultNode.Folder,
    depth: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val horizontalPad = Spacing.screenHorizontal + (Spacing.sm * depth)

    WorldCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = horizontalPad + Spacing.sm, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text       = folder.scopeLabel,
                    style      = type.body,
                    color      = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                Text(
                    text  = "${folder.fileCount} 个文件",
                    style = type.label,
                    color = colors.textSecondary,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = if (isExpanded) "折叠" else "展开",
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除文件夹",
                    tint = Palette.TaskFailed,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  文件行
// ─────────────────────────────────────────────────────────────

@Composable
private fun VaultFileRow(
    file: VaultNode.FileLeaf,
    depth: Int,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val horizontalPad = Spacing.screenHorizontal + (Spacing.sm * depth)
    val canEditText = file.extension in TEXT_EDITABLE_EXT

    WorldCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPreview)
                .padding(start = horizontalPad + Spacing.sm, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon               = fileIcon(file.extension),
                contentDescription = null,
                background         = colors.bgBase,
                size               = 22.dp,
                badgeSize          = 40.dp,
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text       = file.name,
                    style      = type.body,
                    color      = colors.textPrimary,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = "${file.sizeLabel}  ·  ${file.dateLabel}",
                    style = type.label,
                    color = colors.textSecondary,
                )
            }
            if (canEditText) {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                    Icon(Icons.Outlined.Edit, "编辑", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onShare, modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                Icon(Icons.Outlined.Share, "分享", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onExport, modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                Icon(Icons.Outlined.Download, "导出", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                Icon(Icons.Outlined.Delete, "删除", tint = Palette.TaskFailed, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  空状态
// ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyVaultHint() {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Icon(
            imageVector        = Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint               = colors.textSecondary.copy(alpha = 0.4f),
            modifier           = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(text = "还没有文件", style = type.body, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text  = "让角色用 file_export 工具生成文件后会出现在这里",
            style = type.caption,
            color = colors.textSecondary.copy(alpha = 0.6f),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  辅助
// ─────────────────────────────────────────────────────────────

private val TEXT_EDITABLE_EXT = setOf("md", "txt", "html", "htm", "json", "xml", "csv", "log", "yml", "yaml")

private fun fileIcon(ext: String) = when (ext.lowercase()) {
    "xlsx", "csv"                  -> Icons.Outlined.TableChart
    "pdf"                          -> Icons.Outlined.PictureAsPdf
    "md", "txt", "html", "json", "xml", "log", "yml", "yaml" -> Icons.Outlined.Code
    "zip"                          -> Icons.Outlined.Folder
    else                           -> Icons.Outlined.Description
}

private fun shareFile(context: android.content.Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享文件")) }
}
