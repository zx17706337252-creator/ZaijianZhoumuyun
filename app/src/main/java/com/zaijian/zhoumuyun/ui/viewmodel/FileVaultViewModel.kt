package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zaijian.zhoumuyun.data.agent.personalVaultDir
import com.zaijian.zhoumuyun.data.agent.projectVaultDir
import com.zaijian.zhoumuyun.data.agent.vaultRoot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────
//  FileVaultViewModel — v147 文件保险库改造（树形重写）
//
//  职责：
//    - 扫描 vault 三段目录树，按当前角色可见范围组织成 [VaultNode] 树：
//        · vault/personal/{characterId}/        →「角色私库」
//        · vault/shared/roundtable/{rtId}/      →「圆桌共享」（仅该角色参与的圆桌）
//        · vault/shared/project/                →「项目共享」
//    - 支持预览（文本类）、编辑（文本类覆盖写）、导出到 Downloads、删除。
//    - 文件夹树可展开/折叠。
//
//  可见范围（与 resolveVaultPath 权限层一致）：
//    - 自己的私库 + 自己参与的圆桌共享 + 项目共享。
//    - 不展示别的角色私库、不参与的圆桌共享。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  数据模型
// ─────────────────────────────────────────────────────────────

/**
 * 保险库树节点。
 */
sealed class VaultNode {
    abstract val name: String
    abstract val absolutePath: String

    /** 文件夹节点（含角色私库/圆桌共享/项目共享三类根，及子文件夹）。 */
    data class Folder(
        override val name: String,
        override val absolutePath: String,
        /** 人读的作用域标签，如「角色私库」「圆桌共享」「项目共享」或子文件夹名。 */
        val scopeLabel: String,
        val children: List<VaultNode>,
        /** 该文件夹下（递归）的文件总数，用于角标展示。 */
        val fileCount: Int,
    ) : VaultNode()

    /** 文件叶子节点。 */
    data class FileLeaf(
        override val name: String,          // 人读名（去掉时间戳前缀）
        override val absolutePath: String,
        val rawName: String,                // 磁盘上的真实文件名（含时间戳前缀）
        val sizeLabel: String,
        val dateLabel: String,
        val extension: String,
        val sizeBytes: Long,
    ) : VaultNode()
}

data class FileVaultUiState(
    val roots: List<VaultNode> = emptyList(),
    val isLoading: Boolean = true,
    /** 展开的文件夹 absolutePath 集合。根文件夹默认展开。 */
    val expandedPaths: Set<String> = emptySet(),
    val previewTarget: VaultNode.FileLeaf? = null,
    val previewContent: String? = null,
    val previewLoading: Boolean = false,
    val editTarget: VaultNode.FileLeaf? = null,
    val editContent: String = "",
    val deleteTarget: VaultNode? = null,
    val snackbarMessage: String? = null,
)

// ─────────────────────────────────────────────────────────────
//  ViewModel
// ─────────────────────────────────────────────────────────────

class FileVaultViewModel(
    app: Application,
    private val characterId: Int,
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(FileVaultUiState())
    val uiState: StateFlow<FileVaultUiState> = _uiState.asStateFlow()

    private val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val roots = withContext(Dispatchers.IO) { scanVaultTree() }
            // 根文件夹默认展开，方便用户直接看到文件。
            val defaultExpanded = roots.filterIsInstance<VaultNode.Folder>().map { it.absolutePath }.toSet()
            _uiState.update {
                it.copy(roots = roots, isLoading = false, expandedPaths = defaultExpanded)
            }
        }
    }

    fun toggleFolder(path: String) {
        _uiState.update { state ->
            val next = if (path in state.expandedPaths) state.expandedPaths - path else state.expandedPaths + path
            state.copy(expandedPaths = next)
        }
    }

    // ── 预览 ─────────────────────────────────────────────────

    fun openPreview(file: VaultNode.FileLeaf) {
        if (!isTextLike(file.extension)) {
            _uiState.update { it.copy(snackbarMessage = "该文件类型不支持预览，可导出后查看") }
            return
        }
        _uiState.update { it.copy(previewTarget = file, previewContent = null, previewLoading = true) }
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching { File(file.absolutePath).readText(Charsets.UTF_8) }.getOrNull()
            }
            _uiState.update {
                it.copy(previewContent = content, previewLoading = false)
            }
        }
    }

    fun closePreview() {
        _uiState.update { it.copy(previewTarget = null, previewContent = null, previewLoading = false) }
    }

    // ── 编辑 ─────────────────────────────────────────────────

    fun startEdit(file: VaultNode.FileLeaf) {
        if (!isTextLike(file.extension)) {
            _uiState.update { it.copy(snackbarMessage = "该文件类型不支持编辑") }
            return
        }
        _uiState.update { it.copy(editTarget = file, editContent = "") }
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching { File(file.absolutePath).readText(Charsets.UTF_8) }.getOrNull() ?: ""
            }
            _uiState.update { it.copy(editContent = content) }
        }
    }

    fun updateEditContent(text: String) {
        _uiState.update { it.copy(editContent = text) }
    }

    fun saveEdit() {
        val target = _uiState.value.editTarget ?: return
        val content = _uiState.value.editContent
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { File(target.absolutePath).writeText(content, Charsets.UTF_8) }.isSuccess
            }
            _uiState.update {
                it.copy(
                    editTarget = null,
                    editContent = "",
                    snackbarMessage = if (ok) "已保存「${target.name}」" else "保存失败，请重试",
                )
            }
            if (ok) load()
        }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(editTarget = null, editContent = "") }
    }

    // ── 删除 ─────────────────────────────────────────────────

    fun requestDelete(node: VaultNode) {
        _uiState.update { it.copy(deleteTarget = node) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDelete() {
        val target = _uiState.value.deleteTarget ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val f = File(target.absolutePath)
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
            _uiState.update {
                it.copy(
                    deleteTarget = null,
                    snackbarMessage = if (ok) "已删除「${target.name}」" else "删除失败，请重试",
                )
            }
            if (ok) load()
        }
    }

    // ── 导出到 Downloads ─────────────────────────────────────

    /**
     * 导出到系统 Downloads 目录。
     *
     * 原实现用 `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` 直接写入，
     * 该 API 在 Android 10+（API 29+）被废弃且受 scoped storage 限制——targetSdk=35 时
     * 没有 `WRITE_EXTERNAL_STORAGE` 权限（manifest 未声明）也没有 `requestLegacyExternalStorage`，
     * 直接写入会抛异常导致闪退。
     *
     * 修复：
     * - API 29+：用 `MediaStore.Downloads` + `ContentResolver.insert` + `openOutputStream`，
     *   这是 Android 10+ 写入系统 Downloads 的标准做法，无需任何存储权限。
     * - API 26-28：用废弃 API + `WRITE_EXTERNAL_STORAGE` 权限（已在 manifest 补声明）。
     * - catch 改成 Throwable，确保 Error 类型异常也能兜住（原来只 catch Exception）。
     */
    fun exportToDownloads(file: VaultNode.FileLeaf) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    val srcFile = File(file.absolutePath)
                    if (!srcFile.exists()) return@withContext null

                    val displayName = uniqueDisplayName(file.name)
                    val mimeType = guessMimeType(file.extension)

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        // ── Android 10+：MediaStore.Downloads（无需权限）──
                        val resolver = context.contentResolver
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            // RELATIVE_PATH 让文件出现在 Downloads/ 下
                            put(
                                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                                android.os.Environment.DIRECTORY_DOWNLOADS,
                            )
                        }
                        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        val uri = resolver.insert(collection, values)
                            ?: return@withContext null
                        resolver.openOutputStream(uri)?.use { out ->
                            srcFile.inputStream().use { it.copyTo(out) }
                        } ?: return@withContext null
                        displayName
                    } else {
                        // ── Android 9 及以下：废弃 API + WRITE_EXTERNAL_STORAGE 权限 ──
                        @Suppress("DEPRECATION")
                        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS,
                        )
                        downloads.mkdirs()
                        val dest = uniqueDest(downloads, file.name)
                        srcFile.copyTo(dest, overwrite = true)
                        dest.name
                    }
                } catch (e: Throwable) {
                    // 改成 Throwable：原来只 catch Exception，Error 类型（如 OutOfMemoryError）
                    // 会绕过 catch 导致协程未捕获异常 → 闪退。现在全部兜住。
                    com.zaijian.zhoumuyun.util.ZLog.e("FileVault", "导出到 Downloads 失败：${file.name}", e)
                    null
                }
            }
            _uiState.update {
                it.copy(snackbarMessage = if (result != null) "已保存到下载目录：$result" else "导出失败，请检查存储权限")
            }
        }
    }

    /** 根据 MIME 类型表猜测文件类型（MediaStore 需要 mimeType）。 */
    private fun guessMimeType(ext: String): String = when (ext.lowercase()) {
        "txt", "log"      -> "text/plain"
        "md"              -> "text/markdown"
        "csv"             -> "text/csv"
        "html", "htm"     -> "text/html"
        "json"            -> "application/json"
        "xml"             -> "application/xml"
        "yml", "yaml"     -> "application/x-yaml"
        "xlsx"            -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "docx"            -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx"            -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "pdf"             -> "application/pdf"
        "zip"             -> "application/zip"
        else             -> "application/octet-stream"
    }

    /** 生成唯一的显示名（避免覆盖 Downloads 里的同名文件）。 */
    private fun uniqueDisplayName(name: String): String {
        val noExt = name.substringBeforeLast(".")
        val ext = name.substringAfterLast(".", "")
        val suffix = if (ext.isNotEmpty()) ".$ext" else ""
        var i = 1
        var candidate = name
        val context = getApplication<Application>()
        // 查询 MediaStore 是否已有同名文件（Android 10+）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            while (true) {
                val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val args = arrayOf(candidate)
                context.contentResolver.query(collection, arrayOf(android.provider.MediaStore.MediaColumns._ID), selection, args, null)?.use {
                    if (it.moveToFirst()) {
                        candidate = "${noExt}_($i)$suffix"
                        i++
                    } else return candidate
                } ?: return candidate
            }
        }
        return candidate
    }

    private fun uniqueDest(dir: File, name: String): File {
        val base = File(dir, name)
        if (!base.exists()) return base
        val noExt = name.substringBeforeLast(".")
        val ext = name.substringAfterLast(".", "")
        val suffix = if (ext.isNotEmpty()) ".$ext" else ""
        var i = 1
        while (File(dir, "${noExt}_($i)$suffix").exists()) i++
        return File(dir, "${noExt}_($i)$suffix")
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // ── 扫描 vault 树 ───────────────────────────────────────

    /**
     * 按当前 [characterId] 的可见范围扫描 vault 树。
     * 返回根文件夹列表：角色私库 + 参与的圆桌共享 + 项目共享。
     */
    private fun scanVaultTree(): List<VaultNode> {
        val context = getApplication<Application>()
        val roots = mutableListOf<VaultNode>()

        // 1. 角色私库
        val personal = personalVaultDir(context, characterId)
        if (personal.exists()) {
            val node = buildFolder(personal, "角色私库")
            if (node.fileCount > 0 || node.children.isNotEmpty()) roots.add(node)
        }

        // 2. 参与的圆桌共享（roundtableId = sortedIds.joinToString("_")，
        //    凡 id 段含当前 characterId 的圆桌目录都算参与）
        val roundtableRoot = File(File(vaultRoot(context), "shared"), "roundtable")
        if (roundtableRoot.exists()) {
            roundtableRoot.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }?.forEach { rtDir ->
                val participants = rtDir.name.split("_")
                if (characterId.toString() in participants) {
                    val node = buildFolder(rtDir, "圆桌共享·${rtDir.name}")
                    if (node.fileCount > 0 || node.children.isNotEmpty()) roots.add(node)
                }
            }
        }

        // 3. 项目共享
        val project = projectVaultDir(context)
        if (project.exists()) {
            val node = buildFolder(project, "项目共享")
            if (node.fileCount > 0 || node.children.isNotEmpty()) roots.add(node)
        }

        return roots
    }

    /**
     * 递归构建文件夹节点。空文件夹（无文件也无子文件夹）会被剪枝，
     * 避免展示无意义的空层级。
     */
    private fun buildFolder(dir: File, label: String): VaultNode.Folder {
        val children = mutableListOf<VaultNode>()
        val subDirs = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val files = dir.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

        for (sub in subDirs) {
            val node = buildFolder(sub, sub.name)
            // 剪枝：跳过完全空的子文件夹
            if (node.fileCount > 0 || node.children.isNotEmpty()) children.add(node)
        }
        for (f in files) {
            children.add(buildFileLeaf(f))
        }

        val totalFiles = files.size + subDirs.sumOf { countFilesRecursive(it) }
        return VaultNode.Folder(
            name = label,
            absolutePath = dir.absolutePath,
            scopeLabel = label,
            children = children,
            fileCount = totalFiles,
        )
    }

    private fun countFilesRecursive(dir: File): Int {
        val files = dir.listFiles { f -> f.isFile }?.size ?: 0
        val subDirs: List<File> = dir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
        return files + subDirs.sumOf { countFilesRecursive(it) }
    }

    private fun buildFileLeaf(f: File): VaultNode.FileLeaf {
        val displayName = stripTimestampPrefix(f.name)
        val ext = displayName.substringAfterLast(".", "").lowercase()
        return VaultNode.FileLeaf(
            name = displayName,
            absolutePath = f.absolutePath,
            rawName = f.name,
            sizeLabel = formatSize(f.length()),
            dateLabel = fmt.format(Date(f.lastModified())),
            extension = ext,
            sizeBytes = f.length(),
        )
    }

    /**
     * 去掉文件名前的毫秒时间戳前缀（FileExportTool/saveViaStream 的命名约定）。
     * 兼容 "{ts}_{name}" 与 "{ts}_{uuid8}_{name}" 两种风格。
     */
    private fun stripTimestampPrefix(name: String): String {
        // 形如 1700000000000_xxx 或 1700000000000_ab12cd34_xxx
        val match = Regex("^(\\d{13})_(?:[0-9a-fA-F]{8}_)?(.+)$").matchEntire(name)
        return match?.groupValues?.get(2) ?: name
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024        -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024f)} KB"
        else                -> "${"%.1f".format(bytes / 1024f / 1024f)} MB"
    }

    /** 可预览/可编辑的文本类扩展名。 */
    private fun isTextLike(ext: String): Boolean =
        ext.lowercase() in setOf("md", "txt", "html", "htm", "json", "xml", "csv", "log", "yml", "yaml")

    companion object {
        /**
         * 工厂：传入 [characterId] 以确定可见范围。
         * 用法：viewModel(factory = FileVaultViewModel.factory(characterId))
         */
        fun factory(characterId: Int) = viewModelFactory {
            initializer {
                val app = (this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY] as Application)
                FileVaultViewModel(app, characterId)
            }
        }
    }
}
