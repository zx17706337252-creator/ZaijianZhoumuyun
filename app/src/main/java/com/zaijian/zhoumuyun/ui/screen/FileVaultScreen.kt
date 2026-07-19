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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import com.zaijian.zhoumuyun.ui.design.WorldCard
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TableChart
import com.zaijian.zhoumuyun.ui.design.IconBadge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.FileVaultViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.VaultFile

// ─────────────────────────────────────────────────────────────
//  FileVaultScreen — 待办5：文件管理页
//
//  入口：
//    CharacterDetailScreen 主 Tab「文件」→ FileVaultScreen(characterId=N, roundtableId=null)
//
//  功能：
//    - 列出 filesDir/exports/ 下所有文件（角色维度：角色名在文件名中含的优先展示）
//    - 支持分享、导出到下载目录（系统 Downloads）、删除
//    - Tab 切换：工作文件 | 全部
//
//  S-7：数据模型（VaultFile / FileVaultUiState）与 FileVaultViewModel
//  已提取到 ui/viewmodel/FileVaultViewModel.kt，本文件只保留 UI 层。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  Screen
// ─────────────────────────────────────────────────────────────

@Composable
fun FileVaultScreen(
    characterId: Int,        // 来源角色（当前仅展示 exports/，未来可按角色过滤）
    onBack: () -> Unit = {},
    viewModel: FileVaultViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography

    LaunchedEffect(Unit) { viewModel.load() }
    BackHandler { onBack() }

    // ── Snackbar 自动消失 ──────────────────────────────────
    LaunchedEffect(uiState.snackbarMessage) {
        if (uiState.snackbarMessage != null) {
            kotlinx.coroutines.delay(2_500)
            viewModel.clearSnackbar()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bgBase)
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── 顶栏 ──────────────────────────────────────
            VaultHeader(onBack = onBack)

            // ── Tab ───────────────────────────────────────
            VaultTabRow(
                selectedIndex = uiState.tabIndex,
                onSelect      = viewModel::setTab,
            )

            Spacer(Modifier.height(Spacing.sm))

            // ── 文件列表 ───────────────────────────────────
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            } else if (uiState.files.isEmpty()) {
                EmptyVaultHint()
            } else {
                val displayed = when (uiState.tabIndex) {
                    0    -> uiState.files.filter {
                        // W10问题3修复：extension 字段在 scanExports() 构造时已算好
                        // lowercase 值（见 FileVaultViewModel.scanExports），直接用
                        // it.extension 即可，it.file.extension.lowercase() 是重复计算。
                        it.extension in listOf("xlsx", "csv", "docx", "pdf", "md", "txt")
                    }
                    else -> uiState.files  // 全部
                }
                LazyColumn(
                    modifier       = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.xs,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(displayed, key = { it.file.absolutePath }) { vFile ->
                        VaultFileCard(
                            vaultFile = vFile,
                            onShare   = {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    vFile.file,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    this.type = "*/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "分享文件"))
                            },
                            onExport  = { viewModel.exportToDownloads(vFile) },
                            onDelete  = { viewModel.requestDelete(vFile) },
                        )
                    }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }

        // ── 删除确认对话框 ──────────────────────────────────
        uiState.deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = viewModel::cancelDelete,
                title   = { Text("删除文件") },
                text    = { Text("确定删除「${target.displayName}」？此操作不可恢复。") },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmDelete) {
                        Text("删除", color = Palette.TaskFailed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelDelete) {
                        Text("取消")
                    }
                },
            )
        }

        // ── Snackbar ───────────────────────────────────────
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
                // P3-32 修复：硬编码 fontSize 替换为 ZaijianTheme.typography
                Text(msg, color = colors.textPrimary, style = type.caption)
            }
        }
    }
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
//  Tab 栏
// ─────────────────────────────────────────────────────────────

@Composable
private fun VaultTabRow(selectedIndex: Int, onSelect: (Int) -> Unit) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val tabs   = listOf("工作文件", "全部")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = selectedIndex == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        if (selected) colors.accent
                        else if (colors.isDark) colors.bgElevated else colors.bgCard
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = label,
                    // P3-32 修复：硬编码 fontSize 替换为 ZaijianTheme.typography
                    style      = type.caption,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color      = if (selected) Palette.Ink900 else colors.textSecondary,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  单条文件卡片
// ─────────────────────────────────────────────────────────────

@Composable
private fun VaultFileCard(
    vaultFile: VaultFile,
    onShare:   () -> Unit,
    onExport:  () -> Unit,
    onDelete:  () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // WorldCard 接入（精修方案 v1.3）：VaultFile 无 characterId，文件库不归属单一角色，不传 ownerAccent。
    WorldCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        // 文件类型图标（2.4 图标统一收口：手写 Box+clip+background 迁移为 IconBadge）
        IconBadge(
            icon               = fileIcon(vaultFile.extension),
            contentDescription = null,
            background         = colors.bgBase,
            size               = 22.dp,
            badgeSize          = 40.dp,
        )

        Spacer(Modifier.width(Spacing.sm))

        // 名称 + 元信息
        Column(Modifier.weight(1f)) {
            Text(
                text      = vaultFile.displayName,
                style     = type.body,
                color     = colors.textPrimary,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = "${vaultFile.sizeLabel}  ·  ${vaultFile.dateLabel}",
                // P3-32 修复：硬编码 fontSize 替换为 ZaijianTheme.typography
                style = type.label,
                color = colors.textSecondary,
            )
        }

        // 操作按钮组
        Row {
            IconButton(onClick = onShare, modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                Icon(
                    imageVector        = Icons.Outlined.Share,
                    contentDescription = "分享",
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onExport, modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                Icon(
                    imageVector        = Icons.Outlined.Download,
                    contentDescription = "导出到下载",
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                Icon(
                    imageVector        = Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint               = Palette.TaskFailed,
                    modifier           = Modifier.size(18.dp),
                )
            }
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
    // P3-32 修复：添加 type 引用，使用主题排印系统
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
        Text(
            text     = "还没有文件",
            // P3-32 修复：硬编码 fontSize 替换为主题排印，移除 copy 覆写
            style    = type.body,
            color    = colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text     = "让角色用 file_export 工具生成文件后会出现在这里",
            // P3-32 修复：硬编码 fontSize 替换为主题排印，移除 copy 覆写
            style    = type.caption,
            color    = colors.textSecondary.copy(alpha = 0.6f),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  辅助：文件扩展名 → 图标
// ─────────────────────────────────────────────────────────────

// bug 顺手修复：原实现两个分支返回同一图标，when 的扩展名分支形同虚设，
// 所有文件类型在列表里视觉上无法区分。按实际会出现的扩展名（见 VaultFile.
// extension 用法，第291行）分三类给出有区分度的图标。
private fun fileIcon(ext: String) = when (ext.lowercase()) {
    "xlsx", "csv"        -> Icons.Outlined.TableChart
    "pdf"                 -> Icons.Outlined.PictureAsPdf
    "md", "txt", "html", "json", "xml" -> Icons.Outlined.Code
    else                  -> Icons.Outlined.Description
}
