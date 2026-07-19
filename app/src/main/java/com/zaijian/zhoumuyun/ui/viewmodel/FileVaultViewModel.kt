package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
//  FileVaultViewModel — S-7 从 FileVaultScreen.kt 提取到独立文件
//
//  职责：
//    - 扫描 filesDir/exports/ 下所有文件（角色维度：角色名在文件名中含的优先展示）
//    - 提供分享、导出到下载目录（系统 Downloads）、删除的业务逻辑
//    - Tab 状态（工作文件 | 全部）管理
//
//  UI 层（Screen + Composables）仍在 ui/screen/FileVaultScreen.kt。
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
            // 批次4-5 修复：target.file.delete() 返回 false 时表示删除失败
            // （文件不存在/权限不足/被其他进程占用），原先无论成功失败都从 UI 列表
            // 移除并显示"已删除"toast，导致用户以为删除成功但文件实际仍在磁盘上。
            val deleted = withContext(Dispatchers.IO) { target.file.delete() }
            _uiState.update { current ->
                current.copy(
                    files           = if (deleted) current.files.filter { it.file.absolutePath != target.file.absolutePath } else current.files,
                    deleteTarget    = null,
                    snackbarMessage = if (deleted) "「${target.displayName}」已删除" else "删除「${target.displayName}」失败，请重试",
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
