package com.zaijian.zhoumuyun.ui.screen.filepreview

import androidx.compose.material3.ExperimentalMaterial3Api
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zaijian.zhoumuyun.data.agent.FilePreviewParser
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.FilePreviewViewModel
import java.io.File
import java.net.URLEncoder
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.design.AppIcons
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 统一文件预览编辑页（v1.48 应用内预览编辑）。
 *
 * 路由：`file_preview/{encodedPath}` 或 `file_preview/memory/{tempKey}`
 *
 * 按文件类型分发渲染器：
 * - [PreviewContent.Textual] → [TextPreviewEditor]
 * - [PreviewContent.Tabular] → [TablePreviewEditor]
 * - [PreviewContent.Html] → [HtmlPreviewEditor]
 * - [PreviewContent.Unsupported] → 引导导出/用其他应用打开
 *
 * @param encodedPath 文件路径（URL 编码），或 "memory" 表示暂存模式
 * @param tempKey 暂存模式时的临时 key（从 [PreviewMemoryCache] 取数据）
 * @param viewModel
 * @param onBack 返回回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewEditorScreen(
    encodedPath: String,
    tempKey: String?,
    viewModel: FilePreviewViewModel,
    onBack: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // ── 加载 ─────────────────────────────────────────────────
    // 路由：file_preview/{encodedPath} 或 file_preview/memory/{tempKey}
    LaunchedEffect(encodedPath, tempKey) {
        if (encodedPath == "memory" && tempKey != null) {
            val mem = PreviewMemoryCache.consume(tempKey)
            if (mem != null) {
                when (mem) {
                    is PreviewMemoryCache.MemoryItem.MemoryText -> {
                        viewModel.loadFromMemory(mem.text, mem.isMarkdown)
                    }
                    is PreviewMemoryCache.MemoryItem.MemoryTable -> {
                        viewModel.loadFromTable(mem.columns, mem.rows)
                    }
                }
            }
        } else if (encodedPath != "memory") {
            val decodedPath = java.net.URLDecoder.decode(encodedPath, "UTF-8")
            viewModel.loadFromPath(decodedPath)
        }
    }

    // ── Snackbar 处理 ─────────────────────────────────────────
    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is FilePreviewViewModel.UiState.Saved -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.clearStatus()
            }
            is FilePreviewViewModel.UiState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.clearStatus()
            }
            else -> {}
        }
    }

    // ── 提取文件名 ─────────────────────────────────────────────
    val fileName = remember(encodedPath, tempKey, uiState) {
        when {
            encodedPath == "memory" -> "预览"
            else -> runCatching {
                File(java.net.URLDecoder.decode(encodedPath, "UTF-8")).name
            }.getOrDefault("文件")
        }
    }

    Scaffold(
        topBar = {
            // E2 P2 一致性修正：Material3 TopAppBar → DetailTopBar，与其余详情页统一
            DetailTopBar(
                title    = fileName,
                onBack   = onBack,
                headerBg = colors.bgBase,
                actions = {
                    // 更多菜单
                    IconButton(onClick = { showMenu = true }) {
                        Icon(AppIcons.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        // 导出到 Downloads
                        val content = (uiState as? FilePreviewViewModel.UiState.Loaded)?.content
                        val filePath = when (content) {
                            is PreviewContent.Textual -> content.sourceFilePath
                            is PreviewContent.Html -> content.sourceFilePath
                            is PreviewContent.Unsupported -> content.filePath
                            else -> null
                        }
                        if (filePath != null) {
                            DropdownMenuItem(
                                text = { Text("导出到下载") },
                                onClick = {
                                    showMenu = false
                                    viewModel.exportToDownloads(filePath, File(filePath).name)
                                },
                                leadingIcon = { Icon(AppIcons.Download, contentDescription = null) },
                            )
                            // 用其他应用打开（兜底）
                            DropdownMenuItem(
                                text = { Text("用其他应用打开") },
                                onClick = {
                                    showMenu = false
                                    try {
                                        val file = File(filePath)
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file,
                                        )
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, FilePreviewParser.guessMimeType(file.extension))
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "打开 ${file.name}"))
                                    } catch (e: Throwable) {
                                        com.zaijian.zhoumuyun.util.ZLog.e("FilePreview", "外部打开失败", e)
                                    }
                                },
                                leadingIcon = { Icon(AppIcons.OpenInNew, contentDescription = null) },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val state = uiState) {
                is FilePreviewViewModel.UiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = colors.accent,
                    )
                }

                is FilePreviewViewModel.UiState.Loaded -> {
                    val content = state.content
                    when (content) {
                        is PreviewContent.Textual -> {
                            TextPreviewEditor(
                                text = content.text,
                                isMarkdown = content.isMarkdown,
                                editable = content.sourceFilePath != null,
                                onSave = { newText -> viewModel.saveText(newText) },
                            )
                        }

                        is PreviewContent.Tabular -> {
                            TablePreviewEditor(
                                columns = content.columns,
                                rows = content.rows,
                                editable = content.editable,
                                // Excel 闪退修复：把解析层的截断标记透传给渲染层，
                                // 让用户知道"看到的不是全部数据"而不是以为文件本身只有这么点内容。
                                isTruncated = content.isTruncated,
                                // xlsx 多 sheet 支持：透传 sheet 列表/当前索引，点击标签时
                                // 交给 ViewModel 重新解析目标 sheet。
                                sheetNames = content.sheetNames,
                                activeSheetIndex = content.activeSheetIndex,
                                onSheetSelect = { index -> viewModel.switchXlsxSheet(index) },
                                onSave = { cols, rows -> viewModel.saveTable(cols, rows) },
                            )
                        }

                        is PreviewContent.Html -> {
                            HtmlPreviewEditor(
                                source = content.source,
                                editable = content.sourceFilePath != null,
                                onSave = { src -> viewModel.saveHtml(src) },
                            )
                        }

                        is PreviewContent.Unsupported -> {
                            UnsupportedView(
                                fileName = content.fileName,
                                reason = content.reason,
                                onExport = {
                                    viewModel.exportToDownloads(content.filePath, content.fileName)
                                },
                                onOpenExternal = {
                                    try {
                                        val file = File(content.filePath)
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file,
                                        )
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, content.mimeType)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "打开 ${content.fileName}"))
                                    } catch (e: Throwable) {
                                        com.zaijian.zhoumuyun.util.ZLog.e("FilePreview", "外部打开失败", e)
                                    }
                                },
                            )
                        }
                    }
                }

                is FilePreviewViewModel.UiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = state.message,
                            style = type.body,
                            color = colors.textSecondary,
                        )
                    }
                }

                is FilePreviewViewModel.UiState.Saved -> {
                    // 死代码-09 修复后：上方 LaunchedEffect 展示完 snackbar 会立即调用
                    // viewModel.clearStatus() 恢复到 Loaded，这里短暂显示空即可。
                }
            }
        }
    }
}

/**
 * 不支持类型的占位视图。
 */
@Composable
private fun UnsupportedView(
    fileName: String,
    // Excel 闪退修复：大文件场景（超过 FilePreviewParser.MAX_PARSE_FILE_BYTES）
    // 现在会带上具体原因（如"文件过大（20MB）"），reason 为 null 时才回退到
    // 笼统的"该文件类型暂不支持应用内预览"文案。
    reason: String? = null,
    onExport: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = reason ?: "该文件类型暂不支持应用内预览",
            style = type.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = fileName,
            style = type.label,
            color = colors.textDisabled,
        )
        Spacer(Modifier.height(Spacing.lg))
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Text("导出到下载目录")
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(onClick = onOpenExternal, modifier = Modifier.fillMaxWidth()) {
            Text("用其他应用打开")
        }
    }
}

// ── 暂存缓存（对话框内存文本/表格传给预览页）──────────────────────────

/**
 * 暂存缓存：对话框气泡点击全屏查看时，把内存内容暂存到这里，路由传 tempKey。
 *
 * 用静态 Map + consume 模式：预览页加载后消费一次即清除，避免内存泄漏。
 */
// P2-20 修复：改用 ConcurrentHashMap 保证线程安全，AtomicLong 生成唯一序号
// 避免 cache.size 在 consume 后回退导致 key 碰撞。
object PreviewMemoryCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, MemoryItem>()
    private val idCounter = java.util.concurrent.atomic.AtomicLong(0)

    sealed class MemoryItem {
        data class MemoryText(val text: String, val isMarkdown: Boolean) : MemoryItem()
        data class MemoryTable(val columns: List<String>, val rows: List<List<String>>) : MemoryItem()
    }

    /** 存入并返回 tempKey。 */
    fun put(item: MemoryItem): String {
        val key = "mem_${System.currentTimeMillis()}_${idCounter.incrementAndGet()}"
        cache[key] = item
        return key
    }

    /** 消费一次（取出后删除）。 */
    fun consume(key: String): MemoryItem? = cache.remove(key)
}
