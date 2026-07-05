package com.zaijian.zhoumuyun.ui.screen

import android.app.Application
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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  数据模型
// ─────────────────────────────────────────────────────────────

data class VaultFile(
    val file: File,
    val displayName: String,   // 去掉 timestamp 前缀后的人读名称
    val sizeLabel: String,
    val dateLabel: String,
    val extension: String,
)

data class FileVaultUiState(
    val files: List<VaultFile> = emptyList(),
    val isLoading: Boolean = true,
    val tabIndex: Int = 0,       // 0 = 工作文件（exports），1 = 全部
    val deleteTarget: VaultFile? = null,
    val snackbarMessage: String? = null,
)

// ─────────────────────────────────────────────────────────────
//  ViewModel
// ─────────────────────────────────────────────────────────────

class FileVaultViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(FileVaultUiState())
    val uiState: StateFlow<FileVaultUiState> = _uiState.asStateFlow()

    private val exportsDir by lazy {
        File(app.filesDir, "exports").also { it.mkdirs() }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val files = withContext(Dispatchers.IO) { scanExports() }
            _uiState.update { it.copy(files = files, isLoading = false) }
        }
    }

    fun setTab(index: Int) {
        _uiState.update { it.copy(tabIndex = index) }
    }

    fun requestDelete(file: VaultFile) {
        _uiState.update { it.copy(deleteTarget = file) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDelete() {
        val target = _uiState.value.deleteTarget ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { target.file.delete() }
            // M-03: 在 update lambda 内读取当前 files，避免 withContext 期间
            // scanFiles 更新列表导致先读后写竞态（stale read）。
            _uiState.update { current ->
                current.copy(
                    files           = current.files.filter { it.file.absolutePath != target.file.absolutePath },
                    deleteTarget    = null,
                    snackbarMessage = "「${target.displayName}」已删除",
                )
            }
        }
    }

    /**
     * 将文件复制到系统 Downloads 目录（MediaStore），供用户从文件管理器访问。
     * 在 IO 线程异步执行，结果通过 snackbarMessage 反馈给 UI。
     */
    fun exportToDownloads(file: VaultFile) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    downloads.mkdirs()
                    val dest = File(downloads, file.displayName).let { dest ->
                        // 若同名文件已存在，在扩展名前加_(1)
                        if (!dest.exists()) dest
                        else {
                            val noExt = file.displayName.substringBeforeLast(".")
                            val ext   = file.displayName.substringAfterLast(".", "")
                            val suffix = if (ext.isNotEmpty()) ".$ext" else ""
                            File(downloads, "${noExt}_(1)${suffix}")
                        }
                    }
                    file.file.copyTo(dest, overwrite = true)
                    dest.name
                } catch (e: Exception) {
                    null
                }
            }
            if (success != null) {
                _uiState.update { it.copy(snackbarMessage = "已保存到下载目录：$success") }
            } else {
                _uiState.update { it.copy(snackbarMessage = "导出失败") }
            }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // ── 扫描 exports/ 目录 ────────────────────────────────────

    private fun scanExports(): List<VaultFile> {
        val files = exportsDir.listFiles()?.filter { it.isFile } ?: return emptyList()
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        return files
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val displayName = stripTimestampPrefix(f.name)
                val ext = displayName.substringAfterLast(".", "").lowercase()
                VaultFile(
                    file        = f,
                    displayName = displayName,
                    sizeLabel   = formatSize(f.length()),
                    dateLabel   = fmt.format(Date(f.lastModified())),
                    extension   = ext,
                )
            }
    }

    /**
     * FileExportTool 写入格式：{timestamp}_{originalName}
     * 去掉前面的毫秒时间戳前缀，还原人读文件名。
     */
    private fun stripTimestampPrefix(name: String): String {
        val underscoreIdx = name.indexOf('_')
        if (underscoreIdx < 1) return name
        val prefix = name.substring(0, underscoreIdx)
        return if (prefix.all { it.isDigit() } && prefix.length >= 10) {
            name.substring(underscoreIdx + 1)
        } else {
            name
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024        -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024f)} KB"
        else                -> "${"%.1f".format(bytes / 1024f / 1024f)} MB"
    }
}

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
                        it.file.extension.lowercase() in listOf("xlsx", "csv", "docx", "pdf", "md", "txt")
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
                Text(msg, color = colors.textPrimary, fontSize = 13.sp)
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
                    fontSize   = 13.sp,
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
        // 文件类型图标
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(colors.bgBase),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = fileIcon(vaultFile.extension),
                contentDescription = null,
                tint               = colors.accent,
                modifier           = Modifier.size(22.dp),
            )
        }

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
                fontSize = 11.sp,
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
            fontSize = 15.sp,
            color    = colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text     = "让角色用 file_export 工具生成文件后会出现在这里",
            fontSize = 12.sp,
            color    = colors.textSecondary.copy(alpha = 0.6f),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  辅助：文件扩展名 → 图标
// ─────────────────────────────────────────────────────────────

private fun fileIcon(ext: String) = when (ext) {
    "md", "txt", "html", "csv", "json", "xml" -> Icons.Outlined.Description
    else                                        -> Icons.Outlined.Description
}
