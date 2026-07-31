package com.zaijian.zhoumuyun.ui.screen.filepreview

import androidx.compose.material3.ExperimentalMaterial3Api
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    // 此前以整个 uiState（sealed class）作 LaunchedEffect key：Loaded→Saved→Loaded、
    // xlsx 切 sheet（同为 Loaded 但 content 不同实例）、导出等任意状态切换都会让
    // uiState 结构变化，导致 effect 反复重启（B7 审查报告 序号3）。当前因 when 仅对
    // Saved/Error 分支生效、其余 else->{} 故无用户可见故障，但仍属重复触发反模式，
    // 收窄为仅在 Saved/Error 的瞬时消息内容变化时才重启；其余状态统一归并为同一个
    // key（"none"），不会再触发/打断 effect。
    val snackbarEffectKey = when (val s = uiState) {
        is FilePreviewViewModel.UiState.Saved -> "saved:${s.message}"
        is FilePreviewViewModel.UiState.Error -> "error:${s.message}"
        else -> "none"
    }
    LaunchedEffect(snackbarEffectKey) {
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
    // fileName 只依赖 encodedPath，与 uiState 无关；此前把 uiState 也塞进 key 会导致
    // 每次状态切换都重算一次（B7 审查报告 序号3），现移除。
    val fileName = remember(encodedPath, tempKey) {
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
                            // Fix-PptxPreview / Fix-RealPdf：幻灯片/PDF 预览页同样支持导出与外部打开
                            is PreviewContent.Slides -> content.sourceFilePath
                            is PreviewContent.Pdf -> content.filePath
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

                        // Fix-PptxPreview：PPT 应用内文字版预览（逐页幻灯片卡片）
                        is PreviewContent.Slides -> {
                            SlidesPreviewEditor(slides = content.slides)
                        }

                        // Fix-RealPdf 配套：PDF 应用内位图预览（PdfRenderer）
                        is PreviewContent.Pdf -> {
                            PdfPreviewEditor(filePath = content.filePath)
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
 * Fix-PptxPreview：PPTX 文字版预览——逐页幻灯片卡片列表。
 *
 * 应用内预览的定位是"不离开 App 确认 PPT 内容对不对"，不还原排版
 * （排版预览交给 WPS/Office）。每页一张卡片：页码 + 提取出的文本行
 * （首行按标题样式强调，与 PPT 每页"标题+要点"的典型结构一致）。
 */
@Composable
private fun SlidesPreviewEditor(slides: List<List<String>>) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        items(slides.size) { index ->
            val lines = slides[index]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.bgElevated)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(Spacing.md),
            ) {
                Text(
                    text = "第 ${index + 1} 页 / 共 ${slides.size} 页",
                    style = type.label,
                    color = colors.textDisabled,
                )
                Spacer(Modifier.height(Spacing.xs))
                lines.forEachIndexed { lineIdx, line ->
                    Text(
                        text = line,
                        style = if (lineIdx == 0) type.cardTitle else type.body,
                        color = colors.textPrimary,
                    )
                    if (lineIdx < lines.size - 1) Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** PDF 应用内预览的最大页数（防几百页文档撑爆内存/列表）。 */
private const val MAX_PDF_PREVIEW_PAGES = 30

/**
 * Fix-RealPdf 配套：PDF 位图预览。
 *
 * 用 android.graphics.pdf.PdfRenderer（API 21+，minSdk 26 覆盖）逐页渲染。
 * 内存安全设计：
 *   - 页数探测与单页渲染各自独立开/关渲染器，不常驻；
 *   - 单页位图在 LazyColumn item 进入组合时才渲染（produceState），
 *     划出屏幕的 item 随组合销毁释放位图，避免整本 PDF 位图常驻内存；
 *   - 渲染宽度固定 1080px、页数封顶 [MAX_PDF_PREVIEW_PAGES]。
 */
@Composable
private fun PdfPreviewEditor(filePath: String) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    // 页数探测（打开一次渲染器即关闭，代价极小）
    val pageCount by produceState(initialValue = -1, key1 = filePath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.os.ParcelFileDescriptor.open(
                    File(filePath), android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { pfd ->
                    android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount }
                }
            }.getOrDefault(0)
        }
    }

    when {
        pageCount < 0 -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
        }
        pageCount == 0 -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "无法解析该 PDF 文件（可能已损坏或加密）",
                    style = type.body,
                    color = colors.textSecondary,
                )
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm),
            ) {
                items(pageCount.coerceAtMost(MAX_PDF_PREVIEW_PAGES)) { index ->
                    PdfPageImage(filePath = filePath, pageIndex = index)
                }
                if (pageCount > MAX_PDF_PREVIEW_PAGES) {
                    item {
                        Text(
                            text = "仅预览前 $MAX_PDF_PREVIEW_PAGES 页（共 $pageCount 页），完整文档请导出后查看",
                            style = type.caption,
                            color = colors.textSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.md),
                        )
                    }
                }
            }
        }
    }
}

/** 单页 PDF 位图渲染（进入组合才渲染，离开组合随 produceState 释放）。 */
@Composable
private fun PdfPageImage(filePath: String, pageIndex: Int) {
    val colors = ZaijianTheme.colors
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = filePath, key2 = pageIndex) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.os.ParcelFileDescriptor.open(
                    File(filePath), android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { pfd ->
                    android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                        val page = renderer.openPage(pageIndex)
                        val targetWidth = 1080
                        val scale = targetWidth.toFloat() / page.width
                        val bmp = android.graphics.Bitmap.createBitmap(
                            targetWidth,
                            (page.height * scale).toInt().coerceAtLeast(1),
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        bmp
                    }
                }
            }.getOrNull()
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "第 ${pageIndex + 1} 页",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = colors.accent)
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
