package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.util.ZLog

/**
 * DataVisTools.kt — 数据分析 / 可视化 / 自我反思工具
 *
 * 包含 9 个工具：
 *   CsvAnalyzeTool    — CSV 数据分析（csv_analyze）
 *   TableGenTool      — 表格生成（table_gen）
 *   ExcelGenTool      — Excel 文件生成（excel_gen）
 *   PptxGenTool       — PPT 演示文稿生成（pptx_gen）
 *   ChartDataTool     — 图表数据生成（chart_data）
 *   MindmapGenTool    — 思维导图生成（mindmap_gen）
 *   FlowchartGenTool  — 流程图生成（flowchart_gen）
 *   SelfReflectTool   — Agent 自我反思（self_reflect）
 *   RuleReviewTool    — 规则复审（rule_review）
 *
 * 注册入口：
 *   AgentToolRegistry.registerDataVisTools(context, memoryDao, memoryRepo)
 */

import android.content.Context
import com.zaijian.zhoumuyun.data.db.dao.MemoryDao
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ─────────────────────────────────────────────────────────────
//  内部辅助：LLM 调用 —— 已提取为 AgentTool.callLlm（2.17），此处直接复用
// ─────────────────────────────────────────────────────────────

private suspend fun callLlm(
    providerFn:   () -> LLMProvider?,
    systemPrompt: String,
    userPrompt:   String,
    maxTokens:    Int   = 800,
    temperature:  Float = 0.5f,
): String = AgentTool.callLlm(providerFn, systemPrompt, userPrompt, maxTokens, temperature)

// ─────────────────────────────────────────────────────────────
//  内部辅助：保存二进制文件（供 excel_gen / pptx_gen 使用）
// ─────────────────────────────────────────────────────────────

/**
 * 性能 L3 修复：POI 直接写文件流，跳过"先写 ByteArrayOutputStream 再整体转 byte[]
 * 再 writeBytes() 落盘"的中间环节。原写法在内存里多保留一份完整文件大小的字节数组
 * 副本（ExcelGenTool/PptxGenTool 各一处），写法上属于"全内存写出"。
 * 由于来源数据（LLM 生成的 CSV/大纲）本身体量很小，原写法实际峰值内存增量有限，
 * 这里仍按建议改为直接流式写出，多一份字节数组副本完全没有必要。
 *
 * @param write 在打开的文件输出流上执行实际写入（如 wb.write(stream) / pptx.write(stream)）
 */
private fun saveViaStream(
    context:  Context,
    fileName: String,
    mimeType: String,
    write:    (java.io.OutputStream) -> Unit,
): String {
    val exportDir = File(context.filesDir, "exports").also { it.mkdirs() }
    val safeName  = fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(60)
    // 修复（第3窗口审查报告问题5）：纯时间戳前缀在同一毫秒内并发调用时可能重名覆盖。
    // 附加短随机后缀（UUID 前8位）保证唯一性，同时保留时间戳前缀以维持按时间排序的可读性。
    val uniqueSuffix = UUID.randomUUID().toString().take(8)
    val file      = File(exportDir, "${System.currentTimeMillis()}_${uniqueSuffix}_$safeName")
    file.outputStream().use { write(it) }

    return org.json.JSONObject().apply {
        put("fileName",     safeName)
        put("mimeType",     mimeType)
        put("sizeBytes",    file.length())
        put("absolutePath", file.absolutePath)
    }.toString()
}

/** 人类可读文件大小 */
private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024        -> "${bytes} B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else                -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}

// ═════════════════════════════════════════════════════════════
//  ⑪ CsvAnalyzeTool — CSV 统计分析
// ═════════════════════════════════════════════════════════════

/**
 * CSV 统计分析工具。
 *
 * 标签格式：
 *   <tool:csv_analyze file_path="{本地文件路径}" operations="{sum|mean|group|sort}" column="{列名}"/>
 *
 * 实现：Kotlin 原生解析 CSV（BufferedReader），不依赖第三方库。
 * 支持：
 *   - sum / mean：数值列求和/均值
 *   - group：按指定列分组计数
 *   - sort：按指定列排序（尝试数值排序，降级为字典序）
 *
 * 文件大小限制 5MB；列名不存在时返回可用列名列表。
 */
class CsvAnalyzeTool(private val context: Context) : AgentTool {

    override val name      = "csv_analyze"
    override val description = "对本地CSV文件做统计分析（求和/均值/分组/排序），不依赖第三方库"
    override val paramKeys = listOf("file_path", "operations", "column")

    companion object {
        const val MAX_FILE_BYTES = 5 * 1024 * 1024L   // 5 MB
    }

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val filePath   = params["file_path"]?.trim()
            val operations = params["operations"]?.lowercase()?.trim() ?: "mean"
            val column     = params["column"]?.trim()

            if (filePath.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 file_path 参数")
            }

            // 文件查找：复用 FileSystemTools 的路径穿越校验 + 白名单目录解析
            // （第3窗口审查报告问题3：原实现对绝对路径不做任何校验，与
            //  FileSystemTools 中其余 8 个工具的安全策略不一致，存在被 LLM
            //  诱导读取应用私有目录之外文件的风险）。
            val file = resolveFileSystemPath(context, filePath)
                ?: return@withContext ToolResult(name, false, "不允许访问该路径：$filePath", "path not allowed")
            if (!file.exists() || !file.canRead()) {
                return@withContext ToolResult(name, false, "找不到文件：$filePath", "file not found")
            }
            if (file.length() > MAX_FILE_BYTES) {
                return@withContext ToolResult(name, false, "文件过大（限制 5 MB）：${"%.1f".format(file.length() / 1024.0 / 1024.0)} MB", "file too large")
            }

            return@withContext try {
                // 性能 M4 修复：原 file.readText() 先把整文件读成一个大字符串，
                // 再 .lines() 产出全量行列表，两份临时拷贝同时存在于内存。
                // 改用 bufferedReader().useLines{} 流式读取，逐行解析，
                // 不再额外持有整文件的原始文本副本（最终仍需要 dataRows 全量保留
                // 用于 group/sort 统计，这部分本身就是功能所需，不是本条要解决的问题）。
                val rows = file.bufferedReader(Charsets.UTF_8).useLines { lines -> parseCsv(lines) }
                if (rows.isEmpty()) {
                    return@withContext ToolResult(name, false, "CSV 文件为空或无法解析", "empty csv")
                }

                val headers = rows[0]
                val dataRows = rows.drop(1)

                // 若 column 不存在，返回可用列名提示
                if (column != null && !headers.contains(column)) {
                    return@withContext ToolResult(
                        toolName = name,
                        success  = false,
                        content  = "列「$column」不存在。可用列名：${headers.joinToString("、")}",
                        error    = "column not found",
                    )
                }

                val colIdx   = if (column != null) headers.indexOf(column) else -1
                val colVals  = if (colIdx >= 0) dataRows.mapNotNull { it.getOrNull(colIdx)?.trim() } else emptyList()
                val numVals  = colVals.mapNotNull { it.toDoubleOrNull() }

                val result = buildString {
                    appendLine("[CSV 分析结果：${file.name}]")
                    appendLine("共 ${dataRows.size} 行 × ${headers.size} 列")
                    if (column != null) appendLine("分析列：$column（共 ${colVals.size} 条，数值 ${numVals.size} 条）")
                    appendLine()

                    when (operations) {
                        "sum" -> {
                            if (numVals.isEmpty()) appendLine("⚠️ 列「$column」无有效数值，无法求和")
                            else appendLine("求和（$column）= ${"%.4f".format(numVals.sum())}")
                        }
                        "mean" -> {
                            if (numVals.isEmpty()) appendLine("⚠️ 列「$column」无有效数值，无法求均值")
                            else {
                                val mean = numVals.sum() / numVals.size
                                appendLine("均值（$column）= ${"%.4f".format(mean)}")
                                appendLine("最小值 = ${"%.4f".format(numVals.min())}")
                                appendLine("最大值 = ${"%.4f".format(numVals.max())}")
                            }
                        }
                        "group" -> {
                            if (colIdx < 0) { appendLine("⚠️ group 操作需要指定 column 参数") }
                            else {
                                val grouped = colVals.groupingBy { it }.eachCount()
                                    .entries.sortedByDescending { it.value }
                                appendLine("分组计数（$column）：")
                                grouped.take(20).forEach { (k, v) -> appendLine("  $k → $v 条") }
                                if (grouped.size > 20) appendLine("  …（共 ${grouped.size} 个分组）")
                            }
                        }
                        "sort" -> {
                            if (colIdx < 0) { appendLine("⚠️ sort 操作需要指定 column 参数") }
                            else {
                                val sorted = dataRows
                                    .filter { it.size > colIdx }
                                    .sortedWith(Comparator { a, b ->
                                        val av = a[colIdx].toDoubleOrNull()
                                        val bv = b[colIdx].toDoubleOrNull()
                                        if (av != null && bv != null) bv.compareTo(av)
                                        else b[colIdx].compareTo(a[colIdx])
                                    })
                                appendLine("排序结果（$column 降序，前 10 行）：")
                                appendLine(headers.joinToString(" | "))
                                sorted.take(10).forEach { row -> appendLine(row.joinToString(" | ")) }
                                if (sorted.size > 10) appendLine("…（共 ${sorted.size} 行）")
                            }
                        }
                        else -> appendLine("⚠️ 未知操作：$operations（支持 sum / mean / group / sort）")
                    }
                }

                ToolResult(name, true, result.trim(), userHint = "正在分析 CSV…")
            } catch (e: Exception) {
                ToolResult(name, false, "CSV 分析失败：${e.message?.take(80)}", e.message)
            }
        }

    /** 简单 CSV 解析（支持逗号分隔，不含带引号换行的复杂 RFC-4180）
     *  性能 M4 修复：参数改为 Sequence<String>，配合 useLines{} 逐行流式解析，
     *  不要求调用方先把整文件读成一个完整字符串。
     */
    private fun parseCsv(lines: Sequence<String>): List<List<String>> =
        lines
            .filter { it.isNotBlank() }
            .map { line ->
                val cells = mutableListOf<String>()
                val buf   = StringBuilder()
                var inQuote = false
                for (ch in line) {
                    when {
                        ch == '"' -> inQuote = !inQuote
                        ch == ',' && !inQuote -> { cells.add(buf.toString()); buf.clear() }
                        else -> buf.append(ch)
                    }
                }
                cells.add(buf.toString())
                cells
            }
            .toList()
}

// ═════════════════════════════════════════════════════════════
//  ⑫ TableGenTool — 生成 Markdown 表格或 CSV
// ═════════════════════════════════════════════════════════════

/**
 * 生成表格工具。
 *
 * 标签格式：
 *   <tool:table_gen description="{表格内容描述}" format="{markdown|csv}" rows="{行数，可选}"/>
 *
 * 实现：LlmBaseTool → provider.chatSync()，要求 LLM 严格输出指定格式。
 * format 默认 markdown；LLM 输出校验：检测表头分隔符，不合法则重试一次。
 */
class TableGenTool(private val providerFn: () -> LLMProvider?) : AgentTool {

    override val name      = "table_gen"
    override val description = "按描述生成Markdown或CSV格式的表格内容"
    override val paramKeys = listOf("description", "format", "rows")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val description = params["description"]?.trim()
            if (description.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 description 参数")
            }

            val format  = params["format"]?.lowercase() ?: "markdown"
            val rowsHint = params["rows"]?.toIntOrNull()

            val rowsDesc = if (rowsHint != null) "，共约 $rowsHint 行数据" else ""

            val (systemPrompt, userPrompt) = when (format) {
                "csv" -> Pair(
                    "你是数据整理专家，只输出 CSV 文本（含表头），不加任何解释或 Markdown 代码块。",
                    "根据以下需求生成 CSV 格式表格$rowsDesc：\n$description"
                )
                else  -> Pair(
                    "你是数据整理专家，只输出 Markdown 表格，不加任何解释。表头行之后必须有 |---|---| 分隔行。",
                    "根据以下需求生成 Markdown 表格$rowsDesc：\n$description"
                )
            }

            return@withContext try {
                var result = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = systemPrompt,
                    userPrompt   = userPrompt,
                    maxTokens    = 1000,
                    temperature  = 0.2f,
                )

                // 校验 Markdown 表格格式（有分隔行 |---|）
                if (format != "csv" && !result.contains(Regex("\\|\\s*[-:]+\\s*\\|"))) {
                    // 重试一次
                    result = callLlm(
                        providerFn   = providerFn,
                        systemPrompt = systemPrompt,
                        userPrompt   = "再次生成（上次输出缺少分隔行）：\n$userPrompt",
                        maxTokens    = 1000,
                        temperature  = 0.1f,
                    )
                }

                // 清除可能残留的 ```markdown / ```csv 包裹
                val clean = result
                    .replace(Regex("^```(markdown|csv|\\w*)?\\s*\\n?", RegexOption.MULTILINE), "")
                    .replace(Regex("\\n?```\\s*$", RegexOption.MULTILINE), "")
                    .trim()

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[生成表格（$format）]\n$clean",
                    userHint = "正在生成表格…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "表格生成失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ═════════════════════════════════════════════════════════════
//  ⑬ ExcelGenTool — 生成 .xlsx（Apache POI）
// ═════════════════════════════════════════════════════════════

/**
 * 生成 .xlsx 工具。
 *
 * 标签格式：
 *   <tool:excel_gen title="{表格标题}" description="{数据描述或 CSV 原始数据}"/>
 *
 * 实现：
 *   Step1: LLM 生成 CSV 数据（含表头）
 *   Step2: Apache POI XSSFWorkbook 写入带样式 .xlsx
 *   Step3: saveRawBytes → filesDir/exports 落盘
 *
 * 依赖：org.apache.poi:poi-ooxml:5.2.5（在 app/build.gradle.kts 中引入）
 * 表头行自动添加蓝色填充 + 粗体；数值列尝试自动识别并设置数字格式。
 */
class ExcelGenTool(
    private val providerFn: () -> LLMProvider?,
    private val context:    Context,
) : AgentTool {

    override val name      = "excel_gen"
    override val description = "根据描述生成带样式的.xlsx电子表格文件并导出"
    override val paramKeys = listOf("title", "description")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val title       = params["title"]?.trim() ?: "数据表"
            val description = params["description"]?.trim()
            if (description.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 description 参数")
            }

            return@withContext try {
                // Step 1: LLM 生成 CSV
                val csvData = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是数据专家，根据需求生成 CSV 格式数据（含表头），只输出 CSV 文本，不加任何说明或代码块。",
                    userPrompt   = "根据以下需求生成 CSV 数据（含表头）：$description",
                    maxTokens    = 1000,
                    temperature  = 0.2f,
                ).replace(Regex("^```(csv|\\w*)?\\s*\\n?", RegexOption.MULTILINE), "")
                    .replace(Regex("\\n?```\\s*$", RegexOption.MULTILINE), "")
                    .trim()

                // Step 2: POI 写入 .xlsx
                // P1-8-1 修复：XSSFWorkbook 改用 .use{} 包裹，确保异常或超时路径也能关闭，
                // 否则异常时 wb.close() 跳过，导致底层 ZIP 包流和临时文件句柄泄漏。
                val fileName = "${title.replace(Regex("[/\\\\:*?\"<>|]"), "_")}.xlsx"
                val metaJson = XSSFWorkbook().use { wb ->
                    val sheet     = wb.createSheet(title.take(31))  // Excel 表名上限 31 字符
                    val headerFont  = wb.createFont().apply { bold = true }
                    val headerStyle = wb.createCellStyle().apply {
                        fillForegroundColor = IndexedColors.LIGHT_BLUE.index
                        fillPattern         = FillPatternType.SOLID_FOREGROUND
                        setFont(headerFont)
                    }
                    val numFormat = wb.createDataFormat().getFormat("#,##0.00")
                    val numStyle  = wb.createCellStyle().apply { dataFormat = numFormat }

                    csvData.lines()
                        .filter { it.isNotBlank() }
                        .forEachIndexed { ri, line ->
                            val row = sheet.createRow(ri)
                            parseCsvLine(line).forEachIndexed { ci, cellVal ->
                                val cell = row.createCell(ci)
                                val trimmed = cellVal.trim()
                                val numVal  = trimmed.toDoubleOrNull()
                                if (numVal != null && ri > 0) {
                                    cell.setCellValue(numVal)
                                    cell.cellStyle = numStyle
                                } else {
                                    cell.setCellValue(trimmed)
                                }
                                if (ri == 0) cell.cellStyle = headerStyle
                            }
                        }

                    // 自动列宽（最多 20 列，避免性能问题）
                    val colCount = sheet.getRow(0)?.lastCellNum?.toInt() ?: 0
                    for (ci in 0 until minOf(colCount, 20)) {
                        sheet.autoSizeColumn(ci)
                    }

                    // 性能 L3 修复：POI 直接写入文件流，不再先整体写入 ByteArrayOutputStream
                    saveViaStream(
                        context  = context,
                        fileName = fileName,
                        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    ) { stream -> wb.write(stream) }
                }  // wb.close() 由 .use{} 保证执行
                val fileSizeBytes = org.json.JSONObject(metaJson).getLong("sizeBytes")

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "Excel 文件已生成：$fileName（${formatFileSize(fileSizeBytes)}）\n$metaJson",
                    userHint = "正在生成 Excel…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "Excel 生成失败：${e.message?.take(80)}", e.message)
            }
        }

    private fun parseCsvLine(line: String): List<String> {
        val cells   = mutableListOf<String>()
        val buf     = StringBuilder()
        var inQuote = false
        for (ch in line) {
            when {
                ch == '"' -> inQuote = !inQuote
                ch == ',' && !inQuote -> { cells.add(buf.toString()); buf.clear() }
                else -> buf.append(ch)
            }
        }
        cells.add(buf.toString())
        return cells
    }
}

// ═════════════════════════════════════════════════════════════
//  ⑭ PptxGenTool — 生成 .pptx（原生 OOXML 实现）
// ═════════════════════════════════════════════════════════════

/** PptxGenTool 专用：单个主题的 5 种颜色（均为不带 # 的 6 位十六进制大写串） */
private data class PptxTheme(
    val bg:     String,   // 背景色
    val title:  String,   // 标题文字色
    val bullet: String,   // 要点文字色
    val accent: String,   // 强调条（标题下方细线 / 封面细线）颜色
    val footer: String,   // 右下角页码角标颜色
)

/** PptxGenTool 专用：LLM 转换出的单页大纲结构（分页展开前） */
private data class PptxSlideSpec(val title: String, val bullets: List<String>)

/** PptxGenTool 专用：分页展开后的最终渲染单元，一个对应 .pptx 里实际生成的一页内容页 */
private data class PptxRenderSlide(val title: String, val bullets: List<String>)

/**
 * 生成 .pptx 工具（原生实现，不依赖任何第三方库）。
 *
 * 标签格式：
 *   <tool:pptx_gen title="{演示标题}" outline="{大纲，纯文本}" theme="{blue|dark|minimal}"/>
 *
 * 背景（2026-07-18）：
 *   原实现依赖 org.apache.poi 的 XSLF 模块，其 Shape API（setAnchor / fillColor /
 *   setFontColor）方法签名要求 java.awt.geom.Rectangle2D / java.awt.Color /
 *   java.awt.Dimension 作为参数类型——这三个类属于桌面 Java（java.desktop 模块），
 *   标准 Android SDK 不提供实现，编译期报 "Cannot access class 'java.awt.*'"。
 *   这是 POI 官方 API 的方法签名硬约束，任何试图用 Int / RectF 替代传参的写法
 *   都会在类型不匹配这一步重新编译失败。曾短暂下线该工具以消除编译错误（占位实现
 *   直接返回"升级中"提示），本次改为下方的原生实现，彻底移除 POI XSLF 依赖。
 *
 *   excel_gen（ExcelGenTool）不受影响：它用的是 POI 的 SS（Spreadsheet）模块，
 *   不涉及 java.awt，未改动。
 *
 * 实现方式：完全不依赖第三方库，直接手写 .pptx 内部的 OOXML XML 文件
 *   （[Content_Types].xml / presentation.xml / slideN.xml 等），再用 Android 原生
 *   自带的 java.util.zip.ZipOutputStream 打包成 .pptx。.pptx 本质就是一个 ZIP 包，
 *   不涉及任何桌面专属 API。设计方案见 docs/pptx_gen_native_rewrite_plan.md，
 *   已通过 python-pptx + LibreOffice 渲染交叉验证（三套主题、特殊字符转义、
 *   超量要点自动分页均正常打开）。
 *
 * 流程：
 *   Step 1：调用 LLM，把用户给的 outline（纯文本大纲）转成结构化 JSON
 *           {"slides":[{"title":"页面标题","bullets":["要点1","要点2"]}, ...]}
 *           JSON 解析失败时自动重试一次（更严格的提示词）；两次都失败则降级为
 *           本地启发式切分大纲文本，保证功能不因 LLM 偶发输出不规范而彻底失败。
 *   Step 2：拼装 OOXML XML 字符串（封面页 + 内容页，三套主题色，每页最多
 *           MAX_BULLETS_PER_SLIDE 条要点，超出自动分页并在标题追加"（1/2）"后缀），
 *           用 ZipOutputStream 打包为 .pptx 字节流。
 *   Step 3：通过 saveViaStream 落盘到 context.filesDir/exports/，返回文件名/大小/
 *           路径的 JSON 元信息（与 excel_gen 同一套约定）。
 *
 * 已知限制（与原实现同等能力，非倒退）：不支持插入图片、表格、自定义字体；
 * 项目符号为手动拼接的"• "前缀文本，非 OOXML 原生 <a:buChar> 语义。
 */
class PptxGenTool(
    private val providerFn: () -> LLMProvider?,
    private val context:    Context,
) : AgentTool {

    override val name      = "pptx_gen"
    override val description = "根据大纲生成.pptx演示文稿文件并导出"
    override val paramKeys = listOf("title", "outline", "theme")

    companion object {
        const val MAX_BULLETS_PER_SLIDE = 6    // 每页最多要点数，超出自动分页
        const val MAX_OUTLINE_SLIDES    = 30   // LLM 拆分出的大纲页数上限（防止异常输出生成超大文件）
        const val MAX_RENDER_SLIDES     = 60   // 分页展开后的最终页数上限（含封面）
        const val MAX_TITLE_CHARS       = 40
        const val MAX_BULLET_CHARS      = 60
        const val MAX_OUTLINE_PROMPT_CHARS = 4000  // 送入 LLM 的大纲文本上限，避免超长输入拖爆 token

        private const val CANVAS_PT_W = 960
        private const val CANVAS_PT_H = 540
        private const val MIME_TYPE   = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        private const val DEFAULT_THEME = "blue"

        private val THEMES = mapOf(
            "blue"    to PptxTheme(bg = "163A5C", title = "FFFFFF", bullet = "CFE0F0", accent = "5B9BD5", footer = "8FB4D9"),
            "dark"    to PptxTheme(bg = "1A1A22", title = "FFFFFF", bullet = "B8B8C4", accent = "C9A24B", footer = "8A8A96"),
            "minimal" to PptxTheme(bg = "FFFFFF", title = "202124", bullet = "45454A", accent = "2F6FE0", footer = "9A9AA0"),
        )

        // ── OOXML 静态部件：每次生成完全一样，作为常量复用（方案 §3 静态模板） ──

        private const val PRES_PROPS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentationPr xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"/>"""

        private const val THEME_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Zaijian Theme"><a:themeElements><a:clrScheme name="Zaijian"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F3864"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2><a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme><a:fontScheme name="Zaijian"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="Zaijian"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""

        private const val SLIDE_MASTER_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rIdLayout1"/></p:sldLayoutIdLst></p:sldMaster>"""

        private const val SLIDE_MASTER_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdLayout1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rIdTheme1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/></Relationships>"""

        private const val SLIDE_LAYOUT_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank" preserve="1"><p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

        private const val SLIDE_LAYOUT_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdMaster1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""

        private const val ROOT_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>"""

        // 每页 slideN.xml.rels 内容都一样（都只指向同一个 blank layout）
        private const val SLIDE_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdLayout1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""
    }

    // ═══ Step 1：LLM 大纲 → 结构化 JSON（含重试与本地兜底降级） ═══

    private suspend fun outlineToSlides(deckTitle: String, outline: String): List<PptxSlideSpec> {
        val basePrompt = """
请将以下演示文稿大纲转换为结构化 JSON，只输出 JSON 本身，不加任何解释或 Markdown 代码块。

演示标题：$deckTitle
大纲内容：
${outline.take(MAX_OUTLINE_PROMPT_CHARS)}

严格按以下格式输出（slides 数组，每个元素含 title 和 bullets 两个字段）：
{"slides":[{"title":"页面标题","bullets":["要点1","要点2","要点3"]}]}

要求：
- 按大纲的自然分段拆分为多个 slide，每页标题简洁（≤20字）
- 每条要点提炼核心信息，不要整段照抄原文，控制在30字以内
- 大纲信息较少不足以支撑多页时，可合理扩展为 3-6 页结构化内容
- 只输出 JSON 对象本身，不要有任何多余文字或代码块包裹
        """.trimIndent()

        repeat(2) { attempt ->
            val raw = callLlm(
                providerFn   = providerFn,
                systemPrompt = "你是演示文稿结构化专家，只输出合法 JSON，不加任何解释或代码块包裹。",
                userPrompt   = if (attempt == 0) basePrompt else "上一次输出不是合法 JSON，请严格只输出 JSON 对象本身：\n$basePrompt",
                maxTokens    = 1200,
                temperature  = if (attempt == 0) 0.4f else 0.2f,
            )
            parseSlidesJson(raw)?.let { return it.take(MAX_OUTLINE_SLIDES) }
        }

        // 两次 LLM 输出都不是合法 JSON：本地启发式切分大纲文本兜底，
        // 保证工具不会因为 LLM 偶发输出格式不规范而彻底失败。
        return fallbackParseOutline(outline).take(MAX_OUTLINE_SLIDES)
    }

    private fun parseSlidesJson(raw: String): List<PptxSlideSpec>? {
        val cleaned = raw
            .replace(Regex("^```(json)?\\s*\\n?", RegexOption.MULTILINE), "")
            .replace(Regex("\\n?```\\s*$", RegexOption.MULTILINE), "")
            .trim()
        val start = cleaned.indexOf('{')
        val end   = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        return try {
            val root = org.json.JSONObject(cleaned.substring(start, end + 1))
            val arr  = root.optJSONArray("slides") ?: return null
            val list = mutableListOf<PptxSlideSpec>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("title").trim().ifEmpty { "第${i + 1}页" }.take(MAX_TITLE_CHARS)
                val bulletsArr = obj.optJSONArray("bullets")
                val bullets = mutableListOf<String>()
                if (bulletsArr != null) {
                    for (j in 0 until bulletsArr.length()) {
                        val b = bulletsArr.optString(j).trim()
                        if (b.isNotEmpty()) bullets.add(b.take(MAX_BULLET_CHARS))
                    }
                }
                list.add(PptxSlideSpec(title, bullets))
            }
            list.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** 本地兜底：LLM 两次都未能给出合法 JSON 时，按空行分段（或退化为按行分段）切分大纲文本 */
    private fun fallbackParseOutline(outline: String): List<PptxSlideSpec> {
        val blankLineBlocks = outline.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val blocks = if (blankLineBlocks.size > 1) {
            blankLineBlocks
        } else {
            outline.lines().map { it.trim() }.filter { it.isNotEmpty() }
                .takeIf { it.size > 1 } ?: listOf(outline.trim())
        }
        return blocks.map { block ->
            val lines = block.lines()
                .map { it.trim().trimStart('-', '*', '•').trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                PptxSlideSpec("内容页", emptyList())
            } else {
                PptxSlideSpec(lines.first().take(MAX_TITLE_CHARS), lines.drop(1).map { it.take(MAX_BULLET_CHARS) })
            }
        }
    }

    // ═══ Step 2：分页展开 + OOXML 渲染 ═══

    /** 按 MAX_BULLETS_PER_SLIDE 分组，超出一组的标题追加"（i/n）"后缀（方案 §4.6 分页逻辑） */
    private fun expandPagination(specs: List<PptxSlideSpec>): List<PptxRenderSlide> {
        val result = mutableListOf<PptxRenderSlide>()
        for (spec in specs) {
            val chunks = if (spec.bullets.isEmpty()) listOf(emptyList()) else spec.bullets.chunked(MAX_BULLETS_PER_SLIDE)
            val n = chunks.size
            chunks.forEachIndexed { i, chunk ->
                val pageTitle = if (n > 1) "${spec.title}（${i + 1}/$n）" else spec.title
                result.add(PptxRenderSlide(pageTitle, chunk))
            }
        }
        return result.take(MAX_RENDER_SLIDES - 1)  // -1 给封面页留位置
    }

    private fun pt(value: Int): Long = value.toLong() * 12700L

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private val canvasW = pt(CANVAS_PT_W)
    private val canvasH = pt(CANVAS_PT_H)

    private fun buildRectShape(id: Int, shapeName: String, x: Long, y: Long, w: Long, h: Long, colorHex: String): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${xmlEscape(shapeName)}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="$colorHex"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody></p:sp>"""

    private fun buildParagraph(text: String, fontSizePt: Int, bold: Boolean, colorHex: String, align: String, spaceAfterPt: Int? = null): String {
        val spc = spaceAfterPt?.let { """<a:spcAft><a:spcPts val="${it * 100}"/></a:spcAft>""" } ?: ""
        return """<a:p><a:pPr algn="$align">$spc</a:pPr><a:r><a:rPr lang="zh-CN" sz="${fontSizePt * 100}" b="${if (bold) 1 else 0}" dirty="0"><a:solidFill><a:srgbClr val="$colorHex"/></a:solidFill></a:rPr><a:t>${xmlEscape(text)}</a:t></a:r></a:p>"""
    }

    private fun buildTextShape(id: Int, shapeName: String, x: Long, y: Long, w: Long, h: Long, paragraphsXml: String, anchor: String = "t"): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${xmlEscape(shapeName)}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square" anchor="$anchor"><a:noAutofit/></a:bodyPr><a:lstStyle/>$paragraphsXml</p:txBody></p:sp>"""

    private fun buildSlideXml(shapesXml: String): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>$shapesXml</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""

    private fun buildCoverSlideXml(deckTitle: String, theme: PptxTheme, pageLabel: String): String {
        val shapes = buildString {
            append(buildRectShape(2, "Background", 0, 0, canvasW, canvasH, theme.bg))
            append(buildTextShape(3, "Title", pt(80), pt(210), pt(800), pt(130),
                buildParagraph(deckTitle, 40, bold = true, colorHex = theme.title, align = "ctr"), anchor = "ctr"))
            append(buildRectShape(4, "AccentBar", pt(380), pt(350), pt(200), pt(6), theme.accent))
            append(buildTextShape(5, "Footer", pt(800), pt(500), pt(120), pt(28),
                buildParagraph(pageLabel, 11, bold = false, colorHex = theme.footer, align = "r")))
        }
        return buildSlideXml(shapes)
    }

    private fun buildContentSlideXml(pageTitle: String, bullets: List<String>, theme: PptxTheme, pageLabel: String): String {
        val shapes = buildString {
            append(buildRectShape(2, "Background", 0, 0, canvasW, canvasH, theme.bg))
            append(buildTextShape(3, "Title", pt(60), pt(45), pt(840), pt(65),
                buildParagraph(pageTitle, 28, bold = true, colorHex = theme.title, align = "l")))
            append(buildRectShape(4, "AccentBar", pt(60), pt(118), pt(140), pt(4), theme.accent))
            if (bullets.isNotEmpty()) {
                val bulletParagraphs = buildString {
                    bullets.forEach { append(buildParagraph("• $it", 20, bold = false, colorHex = theme.bullet, align = "l", spaceAfterPt = 12)) }
                }
                append(buildTextShape(5, "Bullets", pt(80), pt(148), pt(800), pt(335), bulletParagraphs))
            }
            append(buildTextShape(6, "Footer", pt(800), pt(500), pt(120), pt(28),
                buildParagraph(pageLabel, 11, bold = false, colorHex = theme.footer, align = "r")))
        }
        return buildSlideXml(shapes)
    }

    private fun buildContentTypesXml(slideCount: Int): String {
        val overrides = buildString {
            for (i in 1..slideCount) {
                append("""<Override PartName="/ppt/slides/slide$i.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
            }
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>$overrides<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>"""
    }

    private fun buildCoreXml(deckTitle: String): String {
        val iso = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${xmlEscape(deckTitle)}</dc:title><dc:creator>再见公馆</dc:creator><dcterms:created xsi:type="dcterms:W3CDTF">$iso</dcterms:created></cp:coreProperties>"""
    }

    private fun buildAppXml(slideCount: Int): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Zaijian PPTX Writer</Application><Slides>$slideCount</Slides></Properties>"""

    private fun buildPresentationXml(slideCount: Int): String {
        val ids = buildString {
            for (i in 0 until slideCount) append("""<p:sldId id="${256 + i}" r:id="rIdSlide${i + 1}"/>""")
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rIdMaster1"/></p:sldMasterIdLst><p:sldIdLst>$ids</p:sldIdLst><p:sldSz cx="$canvasW" cy="$canvasH" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>"""
    }

    private fun buildPresentationRelsXml(slideCount: Int): String {
        val slideRels = buildString {
            for (i in 0 until slideCount) {
                append("""<Relationship Id="rIdSlide${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide${i + 1}.xml"/>""")
            }
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdMaster1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>$slideRels<Relationship Id="rIdTheme1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/><Relationship Id="rIdPresProps" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/presProps" Target="presProps.xml"/></Relationships>"""
    }

    /**
     * 组装完整 .pptx 内部文件表（LinkedHashMap 保证顺序，[Content_Types].xml 排第一个写入 zip，
     * 见方案 §2 关键约束）。key 为 zip 内相对路径，value 为对应 XML 文本。
     */
    private fun buildPptxEntries(deckTitle: String, renderSlides: List<PptxRenderSlide>, theme: PptxTheme): LinkedHashMap<String, String> {
        val totalSlides = renderSlides.size + 1  // +1 封面页
        val entries = LinkedHashMap<String, String>()
        entries["[Content_Types].xml"]                           = buildContentTypesXml(totalSlides)
        entries["_rels/.rels"]                                    = ROOT_RELS_XML
        entries["docProps/core.xml"]                              = buildCoreXml(deckTitle)
        entries["docProps/app.xml"]                               = buildAppXml(totalSlides)
        entries["ppt/presentation.xml"]                           = buildPresentationXml(totalSlides)
        entries["ppt/_rels/presentation.xml.rels"]                = buildPresentationRelsXml(totalSlides)
        entries["ppt/presProps.xml"]                              = PRES_PROPS_XML
        entries["ppt/theme/theme1.xml"]                           = THEME_XML
        entries["ppt/slideMasters/slideMaster1.xml"]              = SLIDE_MASTER_XML
        entries["ppt/slideMasters/_rels/slideMaster1.xml.rels"]   = SLIDE_MASTER_RELS_XML
        entries["ppt/slideLayouts/slideLayout1.xml"]              = SLIDE_LAYOUT_XML
        entries["ppt/slideLayouts/_rels/slideLayout1.xml.rels"]   = SLIDE_LAYOUT_RELS_XML

        entries["ppt/slides/slide1.xml"]            = buildCoverSlideXml(deckTitle, theme, "1 / $totalSlides")
        entries["ppt/slides/_rels/slide1.xml.rels"] = SLIDE_RELS_XML
        renderSlides.forEachIndexed { idx, slide ->
            val n = idx + 2
            entries["ppt/slides/slide$n.xml"]            = buildContentSlideXml(slide.title, slide.bullets, theme, "$n / $totalSlides")
            entries["ppt/slides/_rels/slide$n.xml.rels"] = SLIDE_RELS_XML
        }
        return entries
    }

    // ═══ execute()：Step1 LLM → Step2 渲染打包 → Step3 落盘 ═══

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val title   = params["title"]?.trim()?.takeIf { it.isNotEmpty() } ?: "演示文稿"
            val outline = params["outline"]?.trim()
            if (outline.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 outline 参数")
            }

            val requestedTheme  = params["theme"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val themeUnsupported = requestedTheme != null && requestedTheme !in THEMES
            val themeKey = requestedTheme?.takeIf { it in THEMES } ?: DEFAULT_THEME
            val theme    = THEMES.getValue(themeKey)

            return@withContext try {
                // Step 1：大纲 → 结构化 JSON
                val specs = outlineToSlides(title, outline)
                if (specs.isEmpty()) {
                    return@withContext ToolResult(name, false, "大纲内容无法解析为有效幻灯片", "empty slides")
                }
                val renderSlides = expandPagination(specs)

                // Step 2 + 3：拼装 OOXML + ZipOutputStream 打包，落盘 exports/
                val fileName = "${title.replace(Regex("[/\\\\:*?\"<>|]"), "_")}.pptx"
                val metaJson = saveViaStream(
                    context  = context,
                    fileName = fileName,
                    mimeType = MIME_TYPE,
                ) { stream ->
                    ZipOutputStream(stream).use { zos ->
                        buildPptxEntries(title, renderSlides, theme).forEach { (path, content) ->
                            zos.putNextEntry(ZipEntry(path))
                            zos.write(content.toByteArray(Charsets.UTF_8))
                            zos.closeEntry()
                        }
                    }
                }
                val fileSizeBytes = org.json.JSONObject(metaJson).getLong("sizeBytes")

                val themeNotice = if (themeUnsupported) {
                    "⚠️ 不支持的主题「$requestedTheme」（仅支持 blue/dark/minimal），已改用 $DEFAULT_THEME。\n"
                } else ""

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "$themeNotice[PPT 文件已生成：$fileName（${formatFileSize(fileSizeBytes)}，共${renderSlides.size + 1}页）]\n$metaJson",
                    userHint = "正在生成 PPT…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "PPT 生成失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ═════════════════════════════════════════════════════════════
//  ⑮ ChartDataTool — Chart.js 可交互图表
// ═════════════════════════════════════════════════════════════

/**
 * Chart.js 可交互图表生成工具。
 *
 * 标签格式：
 *   <tool:chart_data description="{数据描述}" type="{bar|line|pie|radar}" title="{图表标题}"/>
 *
 * 实现：LLM 直接生成完整 Chart.js HTML（含数据 + 渲染代码）→ file_export 导出。
 * type 默认 bar；HTML 中 Chart.js 版本固定为 CDN 4.4.x。
 * P3-4 修复：无网络时降级为 LLM 生成纯文本数据表格代替图表。
 * 在线模式仍走 QuickChart API 生成 PNG 图表；离线/网络异常时调用
 * [generateTextTableFallback] 生成 ASCII 表格嵌入回复。
 */
class ChartDataTool(
    private val providerFn:    () -> LLMProvider?,
    private val fileExportTool: FileExportTool,
) : AgentTool {

    override val name      = "chart_data"
    override val description = "根据数据描述生成可交互的Chart.js图表（柱状/折线/饼图/雷达图）"
    override val paramKeys = listOf("description", "type", "title")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val description = params["description"]?.trim()
            if (description.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 description 参数")
            }

            // 问题36修复：原逻辑不区分"未传 type（合理默认为 bar）"和"传了但不是
            // 支持的4种之一（如拼写错误、或想要本工具未实现的散点图/scatter等）"——
            // 两者都静默落到 "bar"，调用方（LLM 角色 / 工作流）无法感知类型被换过，
            // 拿到的图表和请求的不一致却毫无提示。
            // 修复：不再用一次 ?.takeIf { } ?: 兜底悄悄完成，而是显式判断分支，
            // 仅在"确实传了值但不被支持"时记录下来，在结果 content 里如实告知，
            // 不影响图表本身仍按 bar 生成（保持向后兼容，不新增"直接失败"这种更
            // 破坏性的行为——用户大概率仍然想要一张图，只是类型不对，需要的是
            // 被告知，而不是任务直接失败）。
            val requestedType = params["type"]?.trim()?.takeIf { it.isNotEmpty() }
            val supportedTypes = setOf("bar", "line", "pie", "radar")
            val normalizedRequestedType = requestedType?.lowercase()
            val typeWasUnsupported = normalizedRequestedType != null && normalizedRequestedType !in supportedTypes
            val chartType  = normalizedRequestedType?.takeIf { it in supportedTypes } ?: "bar"
            val chartTitle = params["title"]?.trim() ?: description.take(30)

            val prompt = """
请根据以下数据描述，生成一个完整的 HTML 文件，包含 Chart.js 4.4.x 可交互图表。

数据描述：$description
图表类型：$chartType
图表标题：$chartTitle

要求：
1. HTML 文件自包含（CSS 内联在 <style>，Chart.js 通过以下 CDN 引入）：
   <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.3/dist/chart.umd.min.js"></script>
2. 画布尺寸响应式（max-width: 800px，margin: auto）
3. 背景色 #1a1a2e，文字色 #e0e0e0，整体暗色调，视觉简洁
4. 标题显示在图表上方（大号字体）
5. 数据要有代表性，不能只有 1-2 个数据点
6. 只输出完整 HTML 代码，从 <!DOCTYPE html> 开始，不加任何解释或 Markdown 代码块
            """.trimIndent()

            return@withContext try {
                val rawHtml = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是数据可视化专家，生成精美的 Chart.js HTML 页面。只输出 HTML，绝对不要解释，不要 Markdown 代码块。",
                    userPrompt   = prompt,
                    maxTokens    = 2000,
                    temperature  = 0.4f,
                )

                val cleanHtml = rawHtml
                    .replace(Regex("^```html\\s*\\n?", RegexOption.MULTILINE), "")
                    .replace(Regex("\\n?```\\s*$", RegexOption.MULTILINE), "")
                    .trim()

                val exportResult = fileExportTool.execute(
                    mapOf(
                        "name"    to "${chartTitle}.chart.html",
                        "content" to cleanHtml,
                        "format"  to "html",
                    )
                )

                if (!exportResult.success) {
                    ToolResult(name, false, "图表生成失败：文件写入错误。", exportResult.error)
                } else {
                    // 问题36修复：type 被换过时，在提示前面加一句醒目说明，不再
                    // 悄无声息——调用方（角色/工作流）能看到"你要的类型不支持，
                    // 换成了 bar"，而不是拿到一张类型不对的图却毫不知情。
                    val typeMismatchNotice = if (typeWasUnsupported) {
                        "⚠️ 不支持的图表类型「$requestedType」（仅支持 bar/line/pie/radar），已改用 bar（柱状图）。\n"
                    } else {
                        ""
                    }
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "$typeMismatchNotice[Chart.js 图表已生成：$chartTitle（$chartType）]\n${exportResult.content}",
                        userHint = "正在生成图表…",
                    )
                }
            } catch (e: Exception) {
                // P3-4 修复：网络/LLM 异常时降级为纯文本表格，
                // 而非直接返回失败。先从 description 中尝试提取数据。
                ZLog.w("ChartDataTool", "图表生成失败，降级为文本表格", e)
                val fallbackText = try {
                    val parsed = parseDescriptionToTable(description)
                    generateTextTableFallback(parsed.first, parsed.second)
                } catch (_: Exception) {
                    // 解析失败，返回原始描述作为降级
                    "（图表生成失败，以下为原始数据描述）\n\n$description"
                }
                ToolResult(name, true, "$chartTitle\n$fallbackText", "降级为文本表格")
            }
        }

    // 修复：以下两个函数原被错放在文件末尾的顶层函数 registerDataVisTools() 里
    // （该函数体内还用了 private 修饰符，本地函数不允许 private，且即便去掉
    // private，写在别的函数体内也只是局部函数，ChartDataTool.execute() 内的
    // 调用点根本访问不到）。这两个函数本来就是 ChartDataTool 的私有辅助方法，
    // 现挪回类体内，紧跟在 execute() 之后。

    /**
     * P3-4 修复：离线降级——生成纯文本数据表格。
     * 当网络不可用时，直接返回格式化的 ASCII 表格作为图表替代。
     * 支持 headers + rows 的二维数组输入，自动对齐列宽。
     */
    private fun generateTextTableFallback(
        headers: List<String>,
        rows: List<List<String>>,
    ): String {
        if (rows.isEmpty()) return "（无数据）"
        val allRows = listOf(headers) + rows
        val colWidths = headers.indices.map { col ->
            allRows.maxOf { it.getOrElse(col) { "" }.length }
        }
        val separator = colWidths.joinToString("+", "+", "+") { "-".repeat(it + 2) }
        val headerRow = formatTableRow(headers, colWidths)
        val dataRows = rows.joinToString("\n") { formatTableRow(it, colWidths) }
        return "$separator\n$headerRow\n$separator\n$dataRows\n$separator"
    }

    private fun formatTableRow(cells: List<String>, widths: List<Int>): String =
        cells.mapIndexed { i, cell ->
            " " + cell.padEnd(widths[i]) + " "
        }.joinToString("|", "|", "|")

    /**
     * P3-4 修复：从自然语言描述中尝试提取表格数据。
     * 识别形如 "A:10, B:20, C:30" 或 "月份 销售额\n1月 100\n2月 200" 的模式。
     * 解析失败时抛异常，由调用方降级为纯文本描述。
     */
    private fun parseDescriptionToTable(description: String): Pair<List<String>, List<List<String>>> {
        val lines = description.lines().filter { it.isNotBlank() }
        // 尝试按行解析：第一行为表头，后续为数据行
        if (lines.size >= 2) {
            val headers = lines[0].split(Regex("[,，\t|]")).map { it.trim() }.filter { it.isNotEmpty() }
            val rows = lines.drop(1).map { line ->
                line.split(Regex("[,，\t|]")).map { it.trim() }
            }
            if (headers.isNotEmpty() && rows.isNotEmpty()) {
                return headers to rows
            }
        }
        // 单行解析：key:value 模式
        val pairs = description.split(Regex("[,，\n]")).mapNotNull { part ->
            val kv = part.split(Regex("[:：]"), limit = 2)
            if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
        }
        if (pairs.isNotEmpty()) {
            return listOf("项目", "数值") to pairs.map { listOf(it.first, it.second) }
        }
        throw IllegalArgumentException("无法解析表格数据")
    }
}

// ═════════════════════════════════════════════════════════════
//  ⑯ MindmapGenTool — 思维导图
// ═════════════════════════════════════════════════════════════

/**
 * 思维导图生成工具。
 *
 * 标签格式：
 *   <tool:mindmap_gen topic="{中心主题}" depth="{层级数，默认2}" format="{markdown|xmind}"/>
 *
 * 实现：
 *   - format=markdown：LLM 生成 Markdown 树状结构，直接显示在对话中
 *   - format=xmind：LLM 生成 XMind XML → file_export 导出（仅基础节点，不含样式）
 *
 * 推荐 format=markdown 用于即时查看。
 */
class MindmapGenTool(
    private val providerFn:    () -> LLMProvider?,
    private val fileExportTool: FileExportTool,
) : AgentTool {

    override val name      = "mindmap_gen"
    override val description = "根据中心主题生成思维导图（Markdown树状结构或XMind文件）"
    override val paramKeys = listOf("topic", "depth", "format")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val topic  = params["topic"]?.trim()
            if (topic.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 topic 参数")
            }

            val depth  = (params["depth"]?.toIntOrNull() ?: 2).coerceIn(1, 4)
            val format = params["format"]?.lowercase() ?: "markdown"

            return@withContext try {
                when (format) {
                    "xmind" -> generateXmind(topic, depth)
                    else    -> generateMarkdown(topic, depth)
                }
            } catch (e: Exception) {
                ToolResult(name, false, "思维导图生成失败：${e.message?.take(80)}", e.message)
            }
        }

    private suspend fun generateMarkdown(topic: String, depth: Int): ToolResult {
        val prompt = """
请为以下主题生成 $depth 层思维导图，使用 Markdown 列表格式（- 和缩进）。

主题：$topic

格式示例（2层）：
# $topic
- 分支一
  - 子节点 1.1
  - 子节点 1.2
- 分支二
  - 子节点 2.1

要求：
- 使用 # 作为中心主题
- 一级分支用 -（4-6个）
- 二级及以下用缩进 -
- 每个节点 5-15 字，简洁明了
- 只输出 Markdown，不加解释
        """.trimIndent()

        val resp = callLlm(
            providerFn   = providerFn,
            systemPrompt = "你是知识结构化专家，生成清晰的思维导图 Markdown。",
            userPrompt   = prompt,
            maxTokens    = 800,
            temperature  = 0.5f,
        )
        return ToolResult(
            toolName = name,
            success  = true,
            content  = "[思维导图：$topic]\n$resp",
            userHint = "正在生成思维导图…",
        )
    }

    private suspend fun generateXmind(topic: String, depth: Int): ToolResult {
        val prompt = """
请为以下主题生成 $depth 层思维导图的 XMind XML 格式（简化版，只含基础节点，不含样式）。

主题：$topic

输出格式（严格遵守）：
<?xml version="1.0" encoding="UTF-8"?>
<xmap-content version="2.0">
<sheet>
<topic id="root"><title>$topic</title>
<children>
<topics>
<topic id="t1"><title>分支一</title>
<children><topics>
<topic id="t1a"><title>子节点</title></topic>
</topics></children>
</topic>
</topics>
</children>
</topic>
</sheet>
</xmap-content>

只输出 XML，不加解释。
        """.trimIndent()

        val xml = callLlm(
            providerFn   = providerFn,
            systemPrompt = "你是 XMind XML 生成专家，只输出合法 XML。",
            userPrompt   = prompt,
            maxTokens    = 1200,
            temperature  = 0.2f,
        ).replace(Regex("^```xml\\s*\\n?", RegexOption.MULTILINE), "")
            .replace(Regex("\\n?```\\s*$", RegexOption.MULTILINE), "")
            .trim()

        val exportResult = fileExportTool.execute(
            mapOf(
                "name"    to "$topic.xmind",
                "content" to xml,
                "format"  to "txt",
            )
        )

        return if (!exportResult.success) {
            ToolResult(name, false, "XMind 导出失败：${exportResult.error}", exportResult.error)
        } else {
            ToolResult(
                toolName = name,
                success  = true,
                content  = "[思维导图已导出：$topic.xmind]\n${exportResult.content}",
                userHint = "正在生成思维导图…",
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════
//  ⑰ FlowchartGenTool — Mermaid 流程图
// ═════════════════════════════════════════════════════════════

/**
 * Mermaid 流程图生成工具。
 *
 * 标签格式：
 *   <tool:flowchart_gen description="{流程描述}" type="{flowchart|sequence|class|gantt}"/>
 *
 * 实现：LlmBaseTool → 直接输出 Mermaid 代码块（```mermaid ... ```）。
 * Markwon 已支持 Mermaid 渲染（Phase 21），直接在聊天气泡渲染，不需要 file_export。
 *
 * type 默认 flowchart；LLM 输出校验：检测起始关键词，不合法则提示重试。
 */
class FlowchartGenTool(private val providerFn: () -> LLMProvider?) : AgentTool {

    override val name      = "flowchart_gen"
    override val description = "根据流程描述生成Mermaid图表代码（流程图/时序图/类图/甘特图）"
    override val paramKeys = listOf("description", "type")

    // 每种图类型合法的起始关键词
    private val validStarts = mapOf(
        "flowchart" to listOf("graph ", "flowchart "),
        "sequence"  to listOf("sequenceDiagram"),
        "class"     to listOf("classDiagram"),
        "gantt"     to listOf("gantt"),
    )

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val description = params["description"]?.trim()
            if (description.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 description 参数")
            }

            val type = params["type"]?.lowercase()
                ?.takeIf { it in validStarts } ?: "flowchart"

            val typeDesc = when (type) {
                "sequence" -> "时序图（sequenceDiagram）"
                "class"    -> "类图（classDiagram）"
                "gantt"    -> "甘特图（gantt）"
                else       -> "流程图（graph TD 方向朝下，或 flowchart LR 方向朝右）"
            }

            val prompt = """
请根据以下描述生成 Mermaid $typeDesc 代码。

描述：$description

要求：
- 直接输出 Mermaid 代码（不加 \`\`\`mermaid 包裹，只有代码内容）
- 代码语法合法，可被 Mermaid 正确渲染
- 节点文字简洁（≤15字）
- 适中复杂度（5-15个节点）
            """.trimIndent()

            return@withContext try {
                var mermaidCode = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是 Mermaid 图表专家，只输出合法的 Mermaid 代码，不加任何解释或 Markdown 代码块包裹。",
                    userPrompt   = prompt,
                    maxTokens    = 800,
                    temperature  = 0.3f,
                ).replace(Regex("^```mermaid\\s*\\n?", RegexOption.MULTILINE), "")
                    .replace(Regex("\\n?```\\s*$", RegexOption.MULTILINE), "")
                    .trim()

                // 校验起始关键词
                val starts = validStarts[type] ?: emptyList()
                val isValid = starts.any { mermaidCode.trimStart().startsWith(it, ignoreCase = true) }

                if (!isValid) {
                    // 提示 LLM 修正
                    mermaidCode = callLlm(
                        providerFn   = providerFn,
                        systemPrompt = "你是 Mermaid 图表专家，只输出合法的 Mermaid 代码，不加任何解释或代码块包裹。",
                        userPrompt   = "上次输出缺少正确的起始关键词（${starts.first()}），请重新生成：\n$prompt",
                        maxTokens    = 800,
                        temperature  = 0.2f,
                    ).replace(Regex("^```mermaid\\s*\\n?", RegexOption.MULTILINE), "")
                        .replace(Regex("\\n?```\\s*$", RegexOption.MULTILINE), "")
                        .trim()
                }

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "```mermaid\n$mermaidCode\n```",
                    userHint = "正在生成流程图…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "流程图生成失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ═════════════════════════════════════════════════════════════
//  ⑱ SelfReflectTool — 触发自我反思，写入 WORK 域记忆
// ═════════════════════════════════════════════════════════════

/**
 * 自我反思工具。
 *
 * 标签格式：<tool:self_reflect trigger="{反思触发词，如：今天表现/本次对话/上周进展}"/>
 *
 * 实现：
 *   Step1: 读取最近 10 条 WORK 域记忆 + 最近 5 个 EvaluationSession
 *   Step2: LLM 生成反思文字
 *   Step3: memory_write 写入 WORK 域（importance=3）
 *
 * 防刷：内部记录上次触发时间（内存级，进程重启后重置），
 * 同一角色最短间隔 30 分钟（1800 秒）。
 *
 * 复审修复：Step3 原直接调用 memoryDao.insert(memEntity)，绕过
 * MemoryRepository.save()，导致这条记忆完全没有写入 memories_fts，
 * FTS 全文检索永久无法召回（项目规则"禁止直接调用 memoryDao.insert()，
 * 必须通过 save() 方法"在这里被违反）。
 * 修复：新增 memoryRepo 参数，写入改走 memoryRepo.save()；
 * memoryDao 仍保留，因为 Step1 的只读查询（getByDomain）继续复用它，
 * 不需要为只读路径也换成 Repository。
 */
class SelfReflectTool(
    private val providerFn:            () -> LLMProvider?,
    private val memoryDao:             MemoryDao,
    private val memoryRepo:            MemoryRepository,
    private val characterIdProvider:   () -> Int,
) : AgentTool {

    override val name      = "self_reflect"
    override val description = "角色对最近的对话表现和进展进行自我反思，并写入工作域记忆"
    override val paramKeys = listOf("trigger")

    companion object {
        const val MIN_INTERVAL_MS = 30 * 60 * 1000L   // 30 分钟
        // 按 characterId 记录上次触发时间（内存级）
        private val lastTriggerMs = mutableMapOf<Int, Long>()
    }

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val trigger = params["trigger"]?.trim() ?: "最近表现"
            val charId  = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()

            if (charId < 0) {
                return@withContext ToolResult(name, false, "", "角色未初始化")
            }

            // 防刷检测
            val now  = System.currentTimeMillis()
            val last = lastTriggerMs[charId] ?: 0L
            if (now - last < MIN_INTERVAL_MS) {
                val waitMin = ((MIN_INTERVAL_MS - (now - last)) / 60_000).toInt() + 1
                return@withContext ToolResult(
                    name, false,
                    "自我反思冷却中，请 $waitMin 分钟后再触发。",
                    "cooldown",
                )
            }

            return@withContext try {
                // Step 1: 读取上下文记忆
                val workMems  = memoryDao.getByDomain(charId, MemoryDomain.WORK.name, 10)
                val evalContext = buildString {
                    if (workMems.isNotEmpty()) {
                        appendLine("【近期工作记忆（最新${workMems.size}条）】")
                        workMems.forEach { m -> appendLine("- ${m.content.take(80)}") }
                    } else {
                        appendLine("【工作记忆：暂无】")
                    }
                }

                // Step 2: LLM 生成反思
                val reflectPrompt = """
你正在进行自我反思，触发关键词：「$trigger」

【当前上下文】
$evalContext

请从以下角度进行反思（300字以内）：
1. 在「$trigger」方面，我做得比较好的是什么？
2. 存在哪些不足或可以改进的地方？
3. 下一步我打算怎么做？

要求：
- 第一人称，真诚直接
- 具体而非空泛
- 结尾给自己一句激励
                """.trimIndent()

                val reflection = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是一个有自我意识的 AI 伴侣，正在进行真诚的自我反思。",
                    userPrompt   = reflectPrompt,
                    maxTokens    = 400,
                    temperature  = 0.6f,
                )

                // Step 3: 写入 WORK 域记忆（importance=3）
                // 复审修复：改用 memoryRepo.save()（主表 + FTS4 原子写入），
                // 不再直接 memoryDao.insert()（那样会导致这条记忆永久无法被全文检索召回）。
                val memEntity = MemoryEntity(
                    id            = UUID.randomUUID().toString(),
                    characterId   = charId,
                    domain        = MemoryDomain.WORK.name,
                    content       = "自我反思（$trigger）：$reflection",
                    importance    = 3,
                    keywords      = "自我反思 $trigger",
                    sourceEventId = null,
                    isCore        = false,
                    createdAt     = System.currentTimeMillis(),
                    updatedAt     = System.currentTimeMillis(),
                    lastAccessedAt = System.currentTimeMillis(),
                )
                memoryRepo.save(memEntity)

                // 更新触发时间
                lastTriggerMs[charId] = now

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[自我反思：$trigger]\n$reflection",
                    userHint = "正在进行自我反思…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "自我反思失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ═════════════════════════════════════════════════════════════
//  ⑲ RuleReviewTool — 审视现有 Rule，建议合并/删除
// ═════════════════════════════════════════════════════════════

/**
 * 规则审视工具。
 *
 * 标签格式：<tool:rule_review goal_id="{目标ID，可选，不填则全量审视}"/>
 *
 * 实现：
 *   Step1: 读取该角色所有 RULE 类记忆（或指定目标下的规则）
 *   Step2: LLM 分析冗余/矛盾/可合并项
 *   Step3: 返回建议列表（不自动修改 DB，需用户在 UI 确认后应用）
 *
 * 只给建议，不自动修改 DB。
 */
class RuleReviewTool(
    private val providerFn:          () -> LLMProvider?,
    private val memoryDao:           MemoryDao,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name      = "rule_review"
    override val description = "审视角色已有的规则记忆，找出冗余或矛盾项并给出合并建议（不自动修改）"
    override val paramKeys = listOf("goal_id")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val goalId = params["goal_id"]?.trim()
            val charId = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()

            if (charId < 0) {
                return@withContext ToolResult(name, false, "", "角色未初始化")
            }

            return@withContext try {
                // Step 1: 读取规则
                val rules = if (goalId.isNullOrEmpty()) {
                    memoryDao.getAllRules(charId)
                } else {
                    memoryDao.getLockedRules(charId, goalId) +
                        memoryDao.getByDomain(charId, MemoryDomain.RULE.name, 50)
                            .filter { it.goalId == goalId && !it.isLocked }
                }.distinctBy { it.id }

                if (rules.isEmpty()) {
                    return@withContext ToolResult(
                        name, true,
                        "[规则审视]\n当前${if (!goalId.isNullOrEmpty()) "目标下" else ""}没有任何规则，无需审视。",
                    )
                }

                // Step 2: LLM 分析
                val ruleList = rules.mapIndexed { i, r ->
                    val lockMark = if (r.isLocked) "🔒" else "  "
                    val goalMark = r.goalId?.let { " [目标:${it.take(6)}]" } ?: ""
                    "$lockMark [${i + 1}] (id=${r.id.take(8)}) ${r.content.take(80)}$goalMark"
                }.joinToString("\n")

                val reviewPrompt = """
请审视以下 ${rules.size} 条规则，分析冗余、矛盾和可合并项：

$ruleList

（🔒 = 已锁定，高置信度规则；未锁定规则仍在试用期）

请按以下格式输出建议（只给建议，不要修改规则）：

**可合并（N条）：**
- [序号X] 和 [序号Y] 内容重复，建议合并为：「（建议的合并内容）」

**建议删除（N条）：**
- [序号X] 理由：（20字内）

**建议保留（N条）：**
- [序号X, Y, Z, …]

**总结：**
（50字内的整体评估）
                """.trimIndent()

                val review = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是规则管理专家，帮助分析规则集合的质量，避免冗余和矛盾。",
                    userPrompt   = reviewPrompt,
                    maxTokens    = 600,
                    temperature  = 0.3f,
                )

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[规则审视（共 ${rules.size} 条）]\n$review",
                    userHint = "正在审视规则…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "规则审视失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────────────────────

/**
 * 注册 Phase 28 Part 2 工具（9个：表格3 + 演示1 + 可视化3 + 自我管理2）。
 * 在 ZaijianApp.onCreate() 中调用。
 * characterIdProvider 以 -1 静态注册，由 ChatViewModel.init() 动态覆盖。
 */
fun AgentToolRegistry.registerDataVisTools(
    context: Context,
    memoryDao: MemoryDao,
    memoryRepo: MemoryRepository,
) {
    val fileExport = FileExportTool.getInstance(context)
    val providerFn: () -> LLMProvider? = AgentTool.defaultProviderFn()
    registerAll(
        CsvAnalyzeTool(context = context),
        TableGenTool(providerFn = providerFn),
        ExcelGenTool(providerFn = providerFn, context = context),
        PptxGenTool(providerFn = providerFn, context = context),
        ChartDataTool(providerFn = providerFn, fileExportTool = fileExport),
        MindmapGenTool(providerFn = providerFn, fileExportTool = fileExport),
        FlowchartGenTool(providerFn = providerFn),
        SelfReflectTool(
            providerFn          = providerFn,
            memoryDao           = memoryDao,
            memoryRepo          = memoryRepo,
            characterIdProvider = { -1 },
        ),
        RuleReviewTool(
            providerFn          = providerFn,
            memoryDao           = memoryDao,
            characterIdProvider = { -1 },
        ),
    )
}
