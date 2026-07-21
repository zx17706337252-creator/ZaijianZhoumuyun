package com.zaijian.zhoumuyun.ui.screen.filepreview

import androidx.compose.material3.ExperimentalMaterial3Api
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zaijian.zhoumuyun.data.agent.FilePreviewParser
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.FilePreviewViewModel
import java.io.File
import java.net.URLEncoder

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
    val uiState by viewModel.uiState.collectAsState()
    val refreshSignal by viewModel.refreshSignal.collectAsState()

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
            }
            is FilePreviewViewModel.UiState.Error -> {
                snackbarHostState.showSnackbar(s.message)
            }
            else -> {}
        }
    }

    // 通知文件库刷新（保存成功后）
    LaunchedEffect(refreshSignal) {
        if (refreshSignal > 0) {
            // 文件库 ViewModel 会自行监听 vault 目录变化，这里无需额外通知
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
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        style = type.titleBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 更多菜单
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
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
                                leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
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
                                    } catch (e: Exception) {
                                        com.zaijian.zhoumuyun.util.ZLog.e("FilePreview", "外部打开失败", e)
                                    }
                                },
                                leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textPrimary,
                ),
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
                                    } catch (e: Exception) {
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
                            .padding(24.dp),
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
                    // Saved 状态短暂显示后自动回到 Loaded（ViewModel 已重新加载）
                    // 这里简单显示空，等待 LaunchedEffect 处理 snackbar 后状态更新
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
    onExport: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "该文件类型暂不支持应用内预览",
            style = type.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = fileName,
            style = type.label,
            color = colors.textDisabled,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Text("导出到下载目录")
        }
        Spacer(Modifier.height(8.dp))
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
object PreviewMemoryCache {
    private val cache = mutableMapOf<String, MemoryItem>()

    sealed class MemoryItem {
        data class MemoryText(val text: String, val isMarkdown: Boolean) : MemoryItem()
        data class MemoryTable(val columns: List<String>, val rows: List<List<String>>) : MemoryItem()
    }

    /** 存入并返回 tempKey。 */
    fun put(item: MemoryItem): String {
        val key = "mem_${System.currentTimeMillis()}_${cache.size}"
        cache[key] = item
        return key
    }

    /** 消费一次（取出后删除）。 */
    fun consume(key: String): MemoryItem? = cache.remove(key)
}
