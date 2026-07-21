package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zaijian.zhoumuyun.data.agent.FilePreviewParser
import com.zaijian.zhoumuyun.data.agent.writeVaultText
import com.zaijian.zhoumuyun.ui.screen.filepreview.PreviewContent
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

    /** 保存成功后通知文件库刷新的信号。 */
    private val _refreshSignal = MutableStateFlow(0)
    val refreshSignal: StateFlow<Int> = _refreshSignal.asStateFlow()

    // ── 加载 ─────────────────────────────────────────────────

    /** 从文件路径加载（文件模式）。 */
    fun loadFromPath(path: String) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    FilePreviewParser.parse(File(path))
                }.getOrElse {
                    _uiState.value = UiState.Error("加载失败：${it.message?.take(80)}")
                    return@withContext null
                }
            }
            if (content != null) {
                _uiState.value = UiState.Loaded(content)
            }
        }
    }

    /** 从内存文本加载（暂存模式，对话框气泡点击全屏查看）。 */
    fun loadFromMemory(text: String, isMarkdown: Boolean) {
        _uiState.value = UiState.Loaded(
            PreviewContent.Textual(
                text = text,
                isMarkdown = isMarkdown,
                sourceFilePath = null,
            )
        )
    }

    /** 从表格数据加载（暂存模式，对话框表格点击全屏查看）。 */
    fun loadFromTable(columns: List<String>, rows: List<List<String>>) {
        _uiState.value = UiState.Loaded(
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
                        // 覆盖写回原文件（UTF-8）
                        File(current.sourceFilePath).writeText(newText, Charsets.UTF_8)
                        "已保存"
                    } else {
                        // 暂存模式：另存为新文件到 vault
                        val context = getApplication<Application>()
                        val isMd = current.isMarkdown
                        val ext = if (isMd) "md" else "txt"
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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
                _uiState.value = UiState.Saved(result)
                _refreshSignal.value += 1
                if (current.sourceFilePath != null) {
                    // 文件模式：重新加载已保存的内容
                    loadFromPath(current.sourceFilePath)
                } else {
                    // 暂存模式：保存后更新为 Loaded（用编辑后的文本，标记为已保存到文件库）
                    _uiState.value = UiState.Loaded(
                        PreviewContent.Textual(
                            text = newText,
                            isMarkdown = current.isMarkdown,
                            sourceFilePath = null,  // 保持暂存模式（已另存为新文件）
                        )
                    )
                }
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
                        File(current.sourceFilePath).writeText(csvText, Charsets.UTF_8)
                        "已保存"
                    } else {
                        // 暂存模式：另存为新 csv
                        val context = getApplication<Application>()
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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
                _uiState.value = UiState.Saved(result)
                _refreshSignal.value += 1
                if (current.sourceFilePath != null) {
                    loadFromPath(current.sourceFilePath)
                } else {
                    // 暂存模式：保存后更新为 Loaded
                    _uiState.value = UiState.Loaded(
                        PreviewContent.Tabular(
                            columns = columns,
                            rows = rows,
                            editable = true,
                            sourceFilePath = null,
                        )
                    )
                }
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
                        File(current.sourceFilePath).writeText(source, Charsets.UTF_8)
                        "已保存"
                    } else {
                        val context = getApplication<Application>()
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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
                _uiState.value = UiState.Saved(result)
                _refreshSignal.value += 1
                if (current.sourceFilePath != null) {
                    loadFromPath(current.sourceFilePath)
                } else {
                    // 暂存模式：保存后更新为 Loaded
                    _uiState.value = UiState.Loaded(
                        PreviewContent.Html(
                            source = source,
                            sourceFilePath = null,
                        )
                    )
                }
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
                    val srcFile = File(filePath)
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

    /** 清除 Saved/Error 状态回到 Loaded。 */
    fun clearStatus() {
        // 从 Saved/Error 回到 Loaded 需要重新取 content——简单实现：不动 uiState，
        // UI 层用 LaunchedEffect 消费 Saved 后自动清除 snackbar 即可
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
