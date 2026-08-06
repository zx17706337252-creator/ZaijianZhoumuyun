package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zaijian.zhoumuyun.data.agent.FilePreviewParser
import com.zaijian.zhoumuyun.data.agent.VaultCallContext
import com.zaijian.zhoumuyun.data.agent.VaultScope
import com.zaijian.zhoumuyun.data.agent.resolveVaultPath
import com.zaijian.zhoumuyun.data.agent.VaultPathResolution
import com.zaijian.zhoumuyun.data.agent.withVaultContext
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

    /**
     * xlsx 多 sheet 支持：文件模式下当前预览文件的引用，切换 sheet 标签时用它
     * 重新调用 [FilePreviewParser.parseXlsxSheet]（只解析目标 sheet，不会一次性
     * 把所有 sheet 都读进内存）。暂存模式（[loadFromMemory]/[loadFromTable]）
     * 没有源文件，保持 null，UI 侧 sheetNames 本就是空列表不会展示切换标签。
     */
    private var currentFile: File? = null

    /** 统一设置 Loaded 状态，同时记录 [lastLoadedContent] 供后续恢复使用。 */
    private fun setLoaded(content: PreviewContent) {
        lastLoadedContent = content
        _uiState.value = UiState.Loaded(content)
    }

    // ── 加载 ─────────────────────────────────────────────────

    /**
     * 从文件路径推断保险库权限上下文。
     *
     * FilePreviewViewModel 不经路由接收 characterId（FilePreview 路由仅传 encodedPath），
     * 而 resolveVaultPath 依赖 VaultCallContext 做权限校验。此前所有 resolveVaultPath
     * 调用都未包裹 withVaultContext，导致 currentVaultContext() 回退到进程级
     * VaultCallContextHolder（可能为 UNINITIALIZED，characterId=-1），对
     * vault/personal/{X}/ 路径因 X≠-1 被拒，用户无法预览/保存/导出角色私库文件。
     *
     * 本函数从 vault 路径段推断正确的 scope/characterId/roundtableId：
     * - vault/personal/{id}/  → PERSONAL + id
     * - vault/shared/roundtable/{rtId}/  → ROUNDTABLE + rtId 的首个参与者
     * - vault/shared/project/  → PROJECT（对所有角色开放）
     * - 其他路径（非 vault）  → PERSONAL + -1（resolveVaultPath 对非 vault 路径直接放行）
     */
    private fun inferVaultContextFromPath(path: String): VaultCallContext {
        val personalMatch = Regex("vault/personal/(\\d+)/").find(path)
        if (personalMatch != null) {
            val cid = personalMatch.groupValues[1].toIntOrNull() ?: -1
            return VaultCallContext(cid, VaultScope.PERSONAL)
        }
        val rtMatch = Regex("vault/shared/roundtable/([^/]+)/").find(path)
        if (rtMatch != null) {
            val rtId = rtMatch.groupValues[1]
            val cid = rtId.split("_").firstNotNullOfOrNull { it.toIntOrNull() } ?: -1
            return VaultCallContext(cid, VaultScope.ROUNDTABLE, rtId)
        }
        if (path.contains("vault/shared/project/")) {
            return VaultCallContext(-1, VaultScope.PROJECT)
        }
        return VaultCallContext(-1, VaultScope.PERSONAL)
    }

    /** 从文件路径加载（文件模式）。 */
    fun loadFromPath(path: String) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                val resolution = try {
                    withVaultContext(inferVaultContextFromPath(path)) {
                        resolveVaultPath(getApplication(), path)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    com.zaijian.zhoumuyun.util.ZLog.e("FilePreview", "路径解析失败: $path", e)
                    _uiState.value = UiState.Error("加载失败：路径解析异常")
                    return@withContext null
                }
                val file = when (resolution) {
                    is VaultPathResolution.Denied -> {
                        _uiState.value = UiState.Error("无权访问：${resolution.reason}")
                        return@withContext null
                    }
                    is VaultPathResolution.Allowed -> resolution.file
                }
                currentFile = file  // xlsx 多 sheet 切换用
                try {
                    FilePreviewParser.parse(file)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    com.zaijian.zhoumuyun.util.ZLog.e("FilePreview", "解析失败: ${file.name}", e)
                    _uiState.value = UiState.Error("加载失败：${e.message?.take(80)}")
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
        currentFile = null  // 暂存模式无源文件，避免残留上一次文件模式的引用
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
        currentFile = null  // 暂存模式无源文件，避免残留上一次文件模式的引用
        setLoaded(
            PreviewContent.Tabular(
                columns = columns,
                rows = rows,
                editable = true,
                sourceFilePath = null,
            )
        )
    }

    /**
     * P1-24 修复：暂存（memory）模式缓存 miss 时置 Error 终态。
     *
     * PreviewMemoryCache 是静态内存 Map，进程被杀后清空；导航恢复 back stack 到
     * file_preview/memory?tempKey=... 路由时 tempKey 已失效，consume 返回 null。
     * 此前 Screen 在 consume 为 null 时既不 loadFromMemory 也不 loadFromTable，uiState
     * 保持初始 Loading，用户永久卡在空白加载页。这里显式置 Error，让 UI 显示可退出的提示。
     */
    fun setMemoryCacheMiss() {
        _uiState.value = UiState.Error("预览内容已失效，请返回重新打开")
    }

    // ── xlsx 多 sheet 切换 ───────────────────────────────────────

    /**
     * 切换 xlsx 展示的 sheet（多 sheet 支持）。
     *
     * 只有文件模式（[currentFile] 非 null）且目标内容确实是 xlsx（sheetNames 非空）
     * 才有意义；暂存模式/csv/docx 等场景 UI 侧不会展示 sheet 切换标签，理论上不会
     * 调到这里，这里的空校验是双重保险。
     */
    fun switchXlsxSheet(sheetIndex: Int) {
        val current = (_uiState.value as? UiState.Loaded)?.content as? PreviewContent.Tabular ?: return
        if (sheetIndex == current.activeSheetIndex) return
        val file = currentFile ?: return
        if (sheetIndex !in current.sheetNames.indices) return
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    FilePreviewParser.parseXlsxSheet(file, sheetIndex)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    com.zaijian.zhoumuyun.util.ZLog.e("FilePreview", "切换 sheet 失败: ${file.name}", e)
                    null
                }
            }
            if (content != null) {
                setLoaded(content)
            } else {
                _uiState.value = UiState.Error("切换工作表失败，请重试")
            }
        }
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
                        // P2-47 修复：收口到 resolveVaultPath 权限校验（含 withVaultContext 身份注入）
                        val resolution = withVaultContext(inferVaultContextFromPath(current.sourceFilePath)) {
                            resolveVaultPath(getApplication(), current.sourceFilePath)
                        }
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
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
                        // P2-47 修复：收口到 resolveVaultPath 权限校验（含 withVaultContext 身份注入）
                        val resolution = withVaultContext(inferVaultContextFromPath(current.sourceFilePath)) {
                            resolveVaultPath(getApplication(), current.sourceFilePath)
                        }
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
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
                        // P2-47 修复：收口到 resolveVaultPath 权限校验（含 withVaultContext 身份注入）
                        val resolution = withVaultContext(inferVaultContextFromPath(current.sourceFilePath)) {
                            resolveVaultPath(getApplication(), current.sourceFilePath)
                        }
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
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
            // 修复：用可空 String 区分"已设置错误消息"与"成功"。
            // 此前 Denied 分支在 withContext 内部设置了具体错误消息后 return null，
            // 外部 _uiState.update 统一覆盖成"导出失败，请检查存储权限"——
            // 用户看不到真正的权限拒绝原因。现在 null 表示"错误消息已设置，
            // 不要覆盖"；非 null 表示导出成功，值为文件名。
            var errorMsg: String? = null
            val result = withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    // P2-47 修复：收口到 resolveVaultPath 权限校验（含 withVaultContext 身份注入）
                    val resolution = withVaultContext(inferVaultContextFromPath(filePath)) {
                        resolveVaultPath(context, filePath)
                    }
                    val srcFile = when (resolution) {
                        is VaultPathResolution.Denied -> {
                            errorMsg = "无权导出：${resolution.reason}"
                            return@withContext null
                        }
                        is VaultPathResolution.Allowed -> resolution.file
                    }
                    if (!srcFile.exists()) {
                        errorMsg = "源文件不存在，可能已被删除"
                        return@withContext null
                    }

                    // #9 复核修复：与 FileVaultViewModel.exportToDownloads 同一处
                    // 核实结论——copyTo() 是 8KB 分块流式拷贝，不是"一次性读入内存"，
                    // 不存在报告描述的 OOM 机制；真实风险是磁盘空间不足导致导出到
                    // 一半失败、留下半截文件。同款可用空间预检查。
                    val downloadsDirForSpaceCheck = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        context.filesDir
                    } else {
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS,
                        )
                    }
                    val freeSpace = downloadsDirForSpaceCheck.usableSpace
                    if (freeSpace in 1 until srcFile.length()) {
                        errorMsg = "存储空间不足，无法导出（文件 ${srcFile.length() / 1024 / 1024}MB，可用 ${freeSpace / 1024 / 1024}MB）"
                        return@withContext null
                    }

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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    com.zaijian.zhoumuyun.util.ZLog.e("FilePreview", "导出失败：$fileName", e)
                    null
                }
            }
            // 修复：仅当 result 为 null 且 errorMsg 也为 null（即 catch 分支兜住的异常）
            // 时才使用通用消息；Denied/文件不存在等已设置具体 errorMsg 的场景不覆盖。
            if (result != null) {
                _uiState.value = UiState.Saved("已导出到下载目录：$result")
            } else if (errorMsg != null) {
                _uiState.value = UiState.Error(errorMsg!!)
            } else {
                _uiState.value = UiState.Error("导出失败，请检查存储权限")
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
