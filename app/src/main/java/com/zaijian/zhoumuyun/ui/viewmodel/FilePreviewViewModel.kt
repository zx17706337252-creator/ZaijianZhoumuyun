package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zaijian.zhoumuyun.data.agent.FilePreviewParser
import com.zaijian.zhoumuyun.data.agent.resolveVaultPath
import com.zaijian.zhoumuyun.data.agent.VaultPathResolution
import com.zaijian.zhoumuyun.data.agent.writeVaultText
import com.zaijian.zhoumuyun.ui.screen.filepreview.PreviewContent
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件预览编辑页 ViewModel（v1.48 应用内预览编辑）。
 *
 * 两种加载模式：
 * - **文件模式**：[loadFromPath] 从文件路径加载，[FilePreviewParser.parse] 解析
 * - **暂存模式**：[loadFromMemory] 从内存文本加载（对话框气泡点击全屏查看）
 *
 * 保存：
 * - 有 sourceFilePath → 覆盖写回原文件（UTF-8）
 * - 无 sourceFilePath（暂存模式）→ 另存为新文件到 vault（用 [writeVaultText]）
 */
class FilePreviewViewModel(
    app: Application,
) : AndroidViewModel(app) {

    sealed class UiState {
        /** 加载中。 */
        object Loading : UiState()
        /** 加载完成。 */
        data class Loaded(val content: PreviewContent) : UiState()
        /** 加载/保存失败。 */
        data class Error(val message: String) : UiState()
        /** 保存成功。 */
        data class Saved(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 最近一次进入 Loaded 状态时的内容，供 [clearStatus] 从 Saved/Error 恢复时使用。 */
    private var lastLoadedContent: PreviewContent? = null

    /** 统一设置 Loaded 状态，同时记录 [lastLoadedContent] 供后续恢复使用。 */
    private fun setLoaded(content: PreviewContent) {
        lastLoadedContent = content
        _uiState.value = UiState.Loaded(content)
    }

    // ── 加载 ─────────────────────────────────────────────────

    /** 从文件路径加载（文件模式）。 */
    fun loadFromPath(path: String) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                // P2-47 修复：收口到 resolveVaultPath 权限校验，
                // 防止通过导航参数访问无权访问的 vault 路径。
                val resolution = resolveVaultPath(getApplication(), path)
                val file = when (resolution) {
                    is VaultPathResolution.Denied -> {
                        _uiState.value = UiState.Error("无权访问：${resolution.reason}")
                        return@withContext null
                    }
                    is VaultPathResolution.Allowed -> resolution.file
                }
                runCatching {
                    FilePreviewParser.parse(file)
                }.getOrElse {
                    _uiState.value = UiState.Error("加载失败：${it.message?.take(80)}")
                    return@withContext null
                }
            }
            if (content != null) {
                setLoaded(content)
            }
        }
    }

    /** 从内存文本加载（暂存模式，对话框气泡点击全屏查看）。 */
    fun loadFromMemory(text: String, isMarkdown: Boolean) {
        setLoaded(
            PreviewContent.Textual(
                text = text,
                isMarkdown = isMarkdown,
                sourceFilePath = null,
            )
        )
    }

    /** 从表格数据加载（暂存模式，对话框表格点击全屏查看）。 */
    fun loadFromTable(columns: List<String>, rows: List<List<String>>) {
        setLoaded(
            PreviewContent.Tabular(
                columns = columns,
                rows = rows,
                editable = true,
                sourceFilePath = null,
            )
        )
    }

    // ── 保存 ─────────────────────────────────────────────────

    /** 保存文本（覆盖写回原文件或另存为新文件）。 */
    fun saveText(newText: String) {
        val current = (_uiState.value as? UiState.Loaded)?.content as? PreviewContent.Textual
            ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (current.sourceFilePath != null) {
                        // P2-47 修复：收口到 resolveVaultPath 权限校验
                        val resolution = resolveVaultPath(getApplication(), current.sourceFilePath)
                        val saveFile = when (resolution) {
                            is VaultPathResolution.Denied -> {
                                com.zaijian.zhoumuyun.util.ZLog.w("FilePreview", "保存被拒：${resolution.reason}")
                                return@withContext null
                            }
                            is VaultPathResolution.Allowed -> resolution.file
                        }
                        // 覆盖写回原文件（UTF-8）
                        saveFile.writeText(newText, Charsets.UTF_8)
                        "已保存"
                    } else {
                        // 暂存模式：另存为新文件到 vault
                        val context = getApplication<Application>()
                        val isMd = current.isMarkdown
                        val ext = if (isMd) "md" else "txt"
                        val stamp = TimeFormatUtils.formatFileStamp(System.currentTimeMillis())
                        val humanName = "文本_${stamp}.$ext"
                        val mimeType = if (isMd) "text/markdown" else "text/plain"
                        writeVaultText(context, humanName, newText, mimeType)
                        "已另存为 $humanName"
                    }
                } catch (e: Exception) {
                    com.zaijian.zhoumuyun.util.ZLog.e("FilePreview", "保存文本失败", e)
                    null
                }
            }
            if (result != null) {
                // 同文件-09 修复：此前这里紧接着调用 loadFromPath()/setLoaded()，
                // 与刚设置的 UiState.Saved 在同一协程内无挂起点地连续 emit，
                // StateFlow 会合并中间值，UI 侧的 collectAsStateWithLifecycle
                // 还没来得及观察到 Saved 就被覆盖，用户看不到"已保存"提示。
                // 现改为只更新 lastLoadedContent（供 clearStatus() 后续恢复用），
                // 不在此处触发状态切换——真正的 Saved→Loaded 由 Screen 侧
                // LaunchedEffect(uiState) 展示完 snackbar 后调用 clearStatus() 完成。
                lastLoadedContent = PreviewContent.Textual(
                    text = newText,
                    isMarkdown = current.isMarkdown,
                    sourceFilePath = current.sourceFilePath,  // 保持原有模式（文件/暂存）不变
                )
                _uiState.value = UiState.Saved(result)
            } else {
                _uiState.value = UiState.Error("保存失败，请重试")
            }
        }
    }

    /** 保存表格（CSV 序列化写回）。 */
    fun saveTable(columns: List<String>, rows: List<List<String>>) {
        val current = (_uiState.value as? UiState.Loaded)?.content as? PreviewContent.Tabular
            ?: return
        if (!current.editable) return  // 只读不保存
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val csvText = FilePreviewParser.toCsv(columns, rows)
                    if (current.sourceFilePath != null) {
                        // P2-47 修复：收口到 resolveVaultPath 权限校验
                        val resolution = resolveVaultPath(getApplication(), current.sourceFilePath)
                        val saveFile = when (resolution) {
                            is VaultPathResolution.Denied -> {
                                com.zaijian.zhoumuyun.util.ZLog.w("FilePreview", "保存被拒：${resolution.reason}")
                                return@withContext null
                            }
                            is VaultPathResolution.Allowed -> resolution.file
                        }
                        saveFile.writeText(csvText, Charsets.UTF_8)
                        "已保存"
                    } else {
                        // 暂存模式：另存为新 csv
                        val context = getApplication<Application>()
                        val stamp = TimeFormatUtils.formatFileStamp(System.currentTimeMillis())
                        val humanName = "表格_${stamp}.csv"
                        writeVaultText(context, humanName, csvText, "text/csv")
                        "已另存为 $humanName"
                    }
                } catch (e: Exception) {
                    com.zaijian.zhoumuyun.util.ZLog.e("FilePreview", "保存表格失败", e)
                    null
                }
            }
            if (result != null) {
                // 同文件-09 修复：同 saveText，不再立即覆盖 Saved 状态。
                lastLoadedContent = PreviewContent.Tabular(
                    columns = columns,
                    rows = rows,
                    editable = true,
                    sourceFilePath = current.sourceFilePath,
                )
                _uiState.value = UiState.Saved(result)
            } else {
                _uiState.value = UiState.Error("保存失败，请重试")
            }
        }
    }

    /** 保存 HTML 源码。 */
    fun saveHtml(source: String) {
        val current = (_uiState.value as? UiState.Loaded)?.content as? PreviewContent.Html
            ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (current.sourceFilePath != null) {
                        // P2-47 修复：收口到 resolveVaultPath 权限校验
                        val resolution = resolveVaultPath(getApplication(), current.sourceFilePath)
                        val saveFile = when (resolution) {
                            is VaultPathResolution.Denied -> {
                                com.zaijian.zhoumuyun.util.ZLog.w("FilePreview", "保存被拒：${resolution.reason}")
                                return@withContext null
                            }
                            is VaultPathResolution.Allowed -> resolution.file
                        }
                        saveFile.writeText(source, Charsets.UTF_8)
                        "已保存"
                    } else {
                        val context = getApplication<Application>()
                        val stamp = TimeFormatUtils.formatFileStamp(System.currentTimeMillis())
                        val humanName = "网页_${stamp}.html"
                        writeVaultText(context, humanName, source, "text/html")
                        "已另存为 $humanName"
                    }
                } catch (e: Exception) {
                    com.zaijian.zhoumuyun.util.ZLog.e("FilePreview", "保存 HTML 失败", e)
                    null
                }
            }
            if (result != null) {
                // 同文件-09 修复：同 saveText，不再立即覆盖 Saved 状态。
                lastLoadedContent = PreviewContent.Html(
                    source = source,
                    sourceFilePath = current.sourceFilePath,
                )
                _uiState.value = UiState.Saved(result)
            } else {
                _uiState.value = UiState.Error("保存失败，请重试")
            }
        }
    }

    // ── 导出/分享 ─────────────────────────────────────────────

    /** 导出到系统 Downloads（复用 FileVaultViewModel 的 MediaStore 逻辑）。 */
    fun exportToDownloads(filePath: String, fileName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    // P2-47 修复：收口到 resolveVaultPath 权限校验
                    val resolution = resolveVaultPath(context, filePath)
                    val srcFile = when (resolution) {
                        is VaultPathResolution.Denied -> {
                            _uiState.value = UiState.Error("无权导出：${resolution.reason}")
                            return@withContext null
                        }
                        is VaultPathResolution.Allowed -> resolution.file
                    }
                    if (!srcFile.exists()) return@withContext null

                    val ext = fileName.substringAfterLast(".", "")
                    val mimeType = FilePreviewParser.guessMimeType(ext)

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val resolver = context.contentResolver
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(
                                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                                android.os.Environment.DIRECTORY_DOWNLOADS,
                            )
                        }
                        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        val uri = resolver.insert(collection, values) ?: return@withContext null
                        resolver.openOutputStream(uri)?.use { out ->
                            srcFile.inputStream().use { it.copyTo(out) }
                        } ?: return@withContext null
                        fileName
                    } else {
                        @Suppress("DEPRECATION")
                        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS,
                        )
                        downloads.mkdirs()
                        val dest = File(downloads, fileName)
                        srcFile.copyTo(dest, overwrite = true)
                        dest.name
                    }
                } catch (e: Throwable) {
                    com.zaijian.zhoumuyun.util.ZLog.e("FilePreview", "导出失败：$fileName", e)
                    null
                }
            }
            _uiState.update {
                if (result != null) UiState.Saved("已导出到下载目录：$result")
                else UiState.Error("导出失败，请检查存储权限")
            }
        }
    }

    /**
     * 清除 Saved/Error 状态回到 Loaded。
     *
     * 死代码-09 修复（阶段2·批次1）：原实现为空函数，注释声称"UI层用
     * LaunchedEffect 消费 Saved 后自动清除"，但 Screen 侧从未实现这一步——
     * 除文件模式保存后会经 [loadFromPath] 重新加载外，[exportToDownloads]
     * 导出成功后 uiState 停在 Saved 永不恢复，编辑区永久空白（同文件-10）。
     * 现补齐实现：若当前处于 Saved/Error 且存在可恢复的 [lastLoadedContent]，
     * 则恢复为 Loaded；否则保持不变（例如初次加载失败，没有可回退的内容）。
     */
    fun clearStatus() {
        val content = lastLoadedContent
        if (content != null && (_uiState.value is UiState.Saved || _uiState.value is UiState.Error)) {
            _uiState.value = UiState.Loaded(content)
        }
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY] as Application)
                FilePreviewViewModel(app)
            }
        }
    }
}
