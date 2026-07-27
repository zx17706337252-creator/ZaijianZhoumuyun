package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.ui.screen.filepreview.PreviewContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * 文件预览解析器（v1.48 应用内预览编辑）。
 *
 * 从 [FileReadTool]（BuiltinTools.kt）抽离 docx/xlsx 解析逻辑，供 UI 预览层和
 * Agent 工具共用一份，避免解析逻辑分叉。
 *
 * ## 支持类型
 * - md/txt/json/xml/log/yml/yaml → [PreviewContent.Textual]（可编辑）
 * - csv → [PreviewContent.Tabular]（可编辑）
 * - xlsx → [PreviewContent.Tabular]（只读，支持多 sheet 切换）
 * - docx → 真实 docx（zip 容器）→ [PreviewContent.Textual]（只读，解析为纯文本）；
 *   若不是合法 zip（如 docx_gen 产出的"伪 docx"，实际是 HTML）→ 嗅探为 HTML 时
 *   兜底成 [PreviewContent.Html]，否则 [PreviewContent.Unsupported]
 * - html/htm → [PreviewContent.Html]（可编辑源码）
 * - 其他 → [PreviewContent.Unsupported]
 *
 * ## 编码检测
 * 复用 [detectFileCharset]（仍在 BuiltinTools.kt，本对象调用它），解决中文 CSV GBK 乱码。
 */
object FilePreviewParser {

    // P2-17 修复：XLSX/DOCX 的 XML 文本捕获未解码 XML 实体（&amp; &lt; &gt; &quot; &apos;），
    // 导致预览显示原始实体编码而非实际字符。此函数对捕获文本执行 XML 实体解码。
    //
    // P2-17 返工：原实现用链式 .replace() 逐个替换具名实体，存在二次解码问题——
    // 例如原文中 "&amp;lt;" 先被 "&amp;" → "&" 变成 "&lt;"，再被 "&lt;" → "<"
    // 错误地变成 "<"，而原文本意是显示字面量 "<"。
    // 改为单次正则遍历，一次扫描中按出现位置逐段解码，已解码的内容不会被二次处理。
    private val NAMED_ENTITIES = mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&apos;" to "'",
    )
    private val ENTITY_REGEX = Regex("&(?:amp|lt|gt|quot|apos|#(\\d+)|#x([0-9a-fA-F]+));")

    private fun decodeXmlEntities(text: String): String {
        if (!text.contains('&')) return text
        return ENTITY_REGEX.replace(text) { m ->
            // 数值字符引用优先（group 1 = 十进制，group 2 = 十六进制）
            m.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()?.toChar()?.toString()
                ?: m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull(16)?.toChar()?.toString()
                ?: NAMED_ENTITIES[m.value]  // 具名实体
                ?: m.value                  // 未识别的 &xxx;，原样保留
        }
    }

    /** 文本类扩展名（可编辑）。 */
    private val TEXTUAL_EXTS = setOf("md", "txt", "json", "xml", "log", "yml", "yaml")

    /** Markdown 扩名（预览模式用 MarkdownText 渲染）。 */
    private val MARKDOWN_EXTS = setOf("md")

    /** HTML 扩展名。 */
    private val HTML_EXTS = setOf("html", "htm")

    // Excel 闪退根因修复：parseXlsx/parseDocxText/CSV 分支此前无条件把整个
    // 文件读进内存（readBytes/readText）再做正则全量扫描——md/txt 这类小文件
    // 不会暴露问题，但用户点开一个几 MB～十几 MB 的 xlsx（尤其 AI 生成的大表）时，
    // 一次性字符串拼接 + Regex.findAll 在 Android 有限堆内存下会直接抛
    // OutOfMemoryError。OOM 是 Error 不是 Exception，下面 `catch (e: Exception)`
    // 接不住，异常会从这个协程冒出去，直接打崩进程——这才是"md 修复了但 Excel
    // 还是崩"的真正原因，而不是预览通道本身没走到。
    //
    // 两层防护：
    //  1. 文件级：超过 MAX_PARSE_FILE_BYTES 直接不解析，走「用其他应用打开」兜底。
    //  2. 行级（xlsx）：即使文件不算大，行数也封顶 MAX_PARSE_ROWS，避免宽表/
    //     多 sheet 元数据把单个 sheet1.xml 撑得很大时仍然爆内存或界面卡死。
    /** 应用内预览允许处理的最大文件体积（15MB，对文本/表格类文件足够宽裕）。 */
    private const val MAX_PARSE_FILE_BYTES = 15L * 1024 * 1024

    /** xlsx 预览的最大行数（超出仅显示前 N 行，只读场景截断不会丢数据）。 */
    private const val MAX_PARSE_ROWS = 5000

    /**
     * P2 修复：单个 XML 条目（解压后）允许 readBytes() 读入内存的最大体积。
     *
     * xlsx 内部的 sharedStrings.xml / sheet1.xml 是文本类内容，解压后体积可能远大于
     * 压缩包本身（文本类内容压缩比高）。即使原始 xlsx 不超过 [MAX_PARSE_FILE_BYTES]
     * 门槛（15MB 压缩包），宽表/多行表解压后单条目仍可能撑得很大，一次性 readBytes()
     * 会有 OOM 风险。此处对单条目再设一道 50MB 上限，超过则跳过解析。
     *
     * 残留风险说明：parseXlsx 仍用 readBytes() + 正则全量扫描，未改为 XmlPullParser
     * 流式读取。原因是当前正则解析与 sharedStrings 索引查找逻辑深度耦合，流式改造
     * 改动量大、回归风险高。已通过本常量 + [MAX_PARSE_FILE_BYTES] + [MAX_PARSE_ROWS]
     * 三重限流把风险压到可接受范围；真正的流式解析留待后续重构。
     *
     * 注意：[java.util.zip.ZipEntry.getSize] 在部分实现下返回 -1（未知大小），此时
     * 无法预判体积，校验表达式设计为 `size > MAX_XML_ENTRY_BYTES`（size==-1 时为
     * false），不会误拦未知大小条目，只能依赖外层 [MAX_PARSE_FILE_BYTES] 兜底。
     */
    private const val MAX_XML_ENTRY_BYTES = 50L * 1024 * 1024  // 50MB

    /**
     * 解析文件为 [PreviewContent]。
     *
     * 在 IO 协程执行，解析失败时回退 [PreviewContent.Unsupported]（不抛异常）。
     */
    suspend fun parse(file: File): PreviewContent = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext PreviewContent.Unsupported(
                filePath = file.absolutePath,
                fileName = file.name,
                mimeType = "application/octet-stream",
            )
        }

        val ext = file.extension.lowercase()

        // 大文件保护（见上方常量注释）：不读、不解析，直接引导用户走外部应用/导出。
        if (file.length() > MAX_PARSE_FILE_BYTES) {
            return@withContext PreviewContent.Unsupported(
                filePath = file.absolutePath,
                fileName = file.name,
                mimeType = guessMimeType(ext),
                reason   = "文件过大（${file.length() / (1024 * 1024)}MB），暂不支持应用内预览，请用其他应用打开或导出后查看",
            )
        }

        val charset = detectFileCharset(file)

        try {
            when {
                ext in TEXTUAL_EXTS -> {
                    val text = file.readText(charset)
                    PreviewContent.Textual(
                        text = text,
                        isMarkdown = ext in MARKDOWN_EXTS,
                        sourceFilePath = file.absolutePath,
                    )
                }

                ext == "csv" -> {
                    val text = file.readText(charset)
                    val (columns, rows) = parseCsv(text)
                    PreviewContent.Tabular(
                        columns = columns,
                        rows = rows,
                        editable = true,
                        sourceFilePath = file.absolutePath,
                    )
                }

                ext == "xlsx" -> {
                    val result = parseXlsx(file, sheetIndex = 0)
                    PreviewContent.Tabular(
                        columns = result.columns,
                        rows = result.rows,
                        editable = false,
                        sourceFilePath = null,  // xlsx 只读，不保存
                        isTruncated = result.truncated,
                        sheetNames = result.sheetNames,
                        activeSheetIndex = result.activeSheetIndex,
                    )
                }

                ext == "docx" -> parseDocxOrFallback(file, charset)

                ext in HTML_EXTS -> {
                    val source = file.readText(charset)
                    PreviewContent.Html(
                        source = source,
                        sourceFilePath = file.absolutePath,
                    )
                }

                else -> {
                    PreviewContent.Unsupported(
                        filePath = file.absolutePath,
                        fileName = file.name,
                        mimeType = guessMimeType(ext),
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Excel 闪退根因修复：原 `catch (e: Exception)` 接不住 OutOfMemoryError
            // （它是 Error 不是 Exception），大文件/宽表解析时一旦真的逼近内存上限，
            // 异常会从这个协程直接冒出去打崩进程。broaden 到 Throwable 后，任何解析期
            // 异常（含 OOM、StackOverflowError 等）都会被这里兜住，退化为「不支持预览」
            // 而不是崩溃。配合上方 MAX_PARSE_FILE_BYTES + MAX_PARSE_ROWS 两层限流，
            // 这里理论上很少会再被 OOM 触发，留作最后一道防线。
            com.zaijian.zhoumuyun.util.AgentLog.error("FilePreviewParser", "解析失败：${file.name}", e)
            PreviewContent.Unsupported(
                filePath = file.absolutePath,
                fileName = file.name,
                mimeType = "application/octet-stream",
            )
        }
    }

    // ── CSV 解析（与 DataVisTools.parseCsv 同构，支持引号转义）──────────────

    /**
     * 解析 CSV 文本为列头 + 数据行。
     *
     * 与 [com.zaijian.zhoumuyun.data.agent.CsvAnalyzeTool] 的 parseCsv 逻辑一致，
     * 支持双引号包裹的字段（含逗号、换行、转义引号）。
     */
    private fun parseCsv(text: String): Pair<List<String>, List<List<String>>> {
        val rows = mutableListOf<List<String>>()
        val currentField = StringBuilder()
        var inQuotes = false
        var row = mutableListOf<String>()

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    // 转义引号 ""
                    currentField.append('"')
                    i += 2
                    continue
                }
                c == '"' -> {
                    inQuotes = !inQuotes
                }
                !inQuotes && c == ',' -> {
                    row.add(currentField.toString())
                    currentField.clear()
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    // 行结束
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(currentField.toString())
                    currentField.clear()
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> currentField.append(c)
            }
            i++
        }
        // 最后一行（无换行结尾）
        if (currentField.isNotEmpty() || row.isNotEmpty()) {
            row.add(currentField.toString())
            if (row.any { it.isNotEmpty() }) rows.add(row)
        }

        val columns = rows.firstOrNull() ?: emptyList()
        val dataRows = if (rows.size > 1) rows.subList(1, rows.size) else emptyList()
        return columns to dataRows
    }

    /**
     * 把表格序列化为 CSV 文本（编辑保存用）。
     */
    fun toCsv(columns: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append(columns.joinToString(",") { escapeCsvField(it) })
        sb.append('\n')
        for (row in rows) {
            sb.append(row.joinToString(",") { escapeCsvField(it) })
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun escapeCsvField(field: String): String {
        if (field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r')) {
            return "\"${field.replace("\"", "\"\"")}\""
        }
        return field
    }

    // ── XLSX 解析（从 BuiltinTools.readXlsxContents 抽离）──────────────────

    /** [parseXlsx] 的返回值：列头/数据行/截断标记 + 多 sheet 元信息。 */
    private data class XlsxParseResult(
        val columns: List<String>,
        val rows: List<List<String>>,
        val truncated: Boolean,
        /** 工作簿内全部 sheet 的显示名，按标签顺序；解析失败/非标准文件时为空列表。 */
        val sheetNames: List<String>,
        /** 本次返回的 columns/rows 对应第几个 sheet（0-based）。 */
        val activeSheetIndex: Int,
    )

    /** 从形如 `<tag attr1="v1" attr2="v2"/>` 的单个标签文本里提取属性表，不关心属性顺序。 */
    // 注意：不用三引号写法——内容末尾的字面 `"` 紧贴三引号收尾定界符会连成 4 个引号，
    // Kotlin 词法分析会把前 3 个当作收尾定界符提前截断字符串，导致编译错误。
    // 改用普通字符串 + 转义，规避这个三引号写法的经典坑。
    private val ATTR_REGEX = Regex("([\\w:]+)=\"([^\"]*)\"")
    private fun parseTagAttrs(tag: String): Map<String, String> =
        ATTR_REGEX.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * xlsx 多 sheet 支持：读取工作簿里全部 sheet 的（显示名, 内部 xml 路径）列表，
     * 按 `xl/workbook.xml` 里 `<sheet>` 标签出现的顺序（即 Excel 标签栏顺序）排列。
     *
     * 为什么不能直接假设"第 N 个标签 = xl/worksheets/sheetN.xml"：sheet 的显示顺序
     * 由 workbook.xml 决定，每个 `<sheet>` 通过 r:id 关联到
     * `xl/_rels/workbook.xml.rels` 里的 Relationship，Target 才是真正的文件名——
     * 一旦工作簿的 sheet 被重命名/重新排序/删除过（很常见的编辑历史），
     * sheetN.xml 与第 N 个标签就不再一一对应，硬编码 "sheet1.xml" 时旧实现
     * 展示的也不一定是用户在 Excel 里看到的第一页。
     *
     * 解析失败（找不到 workbook.xml / 条目过大 / 非标准生成器导致属性缺失）时返回
     * 空列表，调用方据此回退到"只认 xl/worksheets/sheet{index+1}.xml"的旧行为，
     * 保证至少能看到点内容而不是直接报错。
     */
    private fun listXlsxSheetEntries(zip: ZipFile): List<Pair<String, String>> {
        val workbookEntry = zip.getEntry("xl/workbook.xml") ?: return emptyList()
        if (workbookEntry.size > MAX_XML_ENTRY_BYTES) return emptyList()

        val relMap = mutableMapOf<String, String>()  // r:id -> Target
        val relsEntry = zip.getEntry("xl/_rels/workbook.xml.rels")
        if (relsEntry != null && relsEntry.size <= MAX_XML_ENTRY_BYTES) {
            val relsXml = zip.getInputStream(relsEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            Regex("""<Relationship\b[^>]*/?>""").findAll(relsXml).forEach { m ->
                val attrs = parseTagAttrs(m.value)
                val id = attrs["Id"]
                val target = attrs["Target"]
                if (id != null && target != null) relMap[id] = target
            }
        }
        if (relMap.isEmpty()) return emptyList()  // 没有 rels 映射，交给调用方走旧行为兜底

        val workbookXml = zip.getInputStream(workbookEntry).use { it.readBytes().toString(Charsets.UTF_8) }
        val result = mutableListOf<Pair<String, String>>()
        Regex("""<sheet\b[^>]*/?>""").findAll(workbookXml).forEach { m ->
            val attrs = parseTagAttrs(m.value)
            val name = attrs["name"]?.let { decodeXmlEntities(it) } ?: return@forEach
            val rId = attrs["r:id"] ?: return@forEach
            val target = relMap[rId] ?: return@forEach
            // Target 通常是相对 xl/ 目录的路径（如 "worksheets/sheet2.xml"）。
            val cleaned = target.removePrefix("/")
            val path = if (cleaned.startsWith("xl/")) cleaned else "xl/$cleaned"
            result.add(name to path)
        }
        return result
    }

    /**
     * 解析 xlsx 文件的指定 sheet 为表格。
     *
     * @param sheetIndex 要展示第几个 sheet（0-based，按 [listXlsxSheetEntries] 的标签顺序）。
     *
     * Excel 闪退根因修复（两处）：
     *  1. 资源泄漏：原实现 `val zip = ZipFile(file)` 靠手动散落的 `zip.close()`
     *     收尾——一旦中途抛异常（文件损坏、正则匹配异常等），close() 走不到，
     *     ZipFile 持有的文件描述符泄漏，多次触发后可能导致后续文件操作失败甚至
     *     "Too many open files"。改用 `ZipFile(file).use { }`，无论正常返回还是
     *     异常都保证关闭。
     *  2. 行数没有上限：sheet 解压后的体积会远大于 xlsx 压缩包本身
     *     （文本类内容压缩比高），即使原始文件不超过 [MAX_PARSE_FILE_BYTES] 门槛，
     *     行数极多的宽表仍可能在正则全量扫描 + 构建 `List<List<String>>` 时占用
     *     大量堆内存直至 OOM。这里解析时超过 [MAX_PARSE_ROWS] 直接停止扫描（不会
     *     把多余的行读进内存），并通过返回值 truncated 告知调用方"已截断"，
     *     供 UI 提示——只读场景截断不会丢数据（用户改用其他应用仍能看到完整内容）。
     *
     * P2 修复（条目级大小校验）：在 readBytes() 前对 sharedStrings.xml / 目标 sheet
     * 的解压后体积做校验，超过 [MAX_XML_ENTRY_BYTES] 直接跳过，避免单条目解压后
     * 体积远大于压缩包导致 OOM。详见 [MAX_XML_ENTRY_BYTES] 注释中的残留风险说明。
     *
     * 多 sheet 支持：切换 sheet 时只解析目标 sheet（不会一次性把所有 sheet 都读进
     * 内存），延续本文件一贯的限流防 OOM 设计——宽表场景切多个 sheet 同样有风险。
     */
    private fun parseXlsx(file: File, sheetIndex: Int): XlsxParseResult {
        return ZipFile(file).use { zip ->
            val sheetEntries = listXlsxSheetEntries(zip)
            val sheetNames = sheetEntries.map { it.first }
            // sheetEntries 为空（非标准生成器/解析失败）时回退旧行为：只认
            // xl/worksheets/sheet{index+1}.xml，保证至少能看到第一页内容。
            val targetPath = sheetEntries.getOrNull(sheetIndex)?.second
                ?: "xl/worksheets/sheet${sheetIndex + 1}.xml"

            // 1. 提取共享字符串表
            val sharedStrings = mutableListOf<String>()
            val ssEntry = zip.getEntry("xl/sharedStrings.xml")
            if (ssEntry != null) {
                // P2 修复：在 readBytes() 前添加大小校验，超大 XML 条目拒绝解析，
                // 避免解压后体积远大于压缩包的 sharedStrings.xml 一次性读入内存导致 OOM。
                // size==-1（未知）时不拦截（表达式为 false），依赖外层 MAX_PARSE_FILE_BYTES 兜底。
                if (ssEntry.size <= MAX_XML_ENTRY_BYTES) {
                    val ssXml = zip.getInputStream(ssEntry).use { it.readBytes().toString(Charsets.UTF_8) }
                    val siPattern = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
                    val tPattern = Regex("<t[^>]*>([^<]*)</t>")
                    siPattern.findAll(ssXml).forEach { siMatch ->
                        val text = tPattern.findAll(siMatch.groupValues[1])
                            .joinToString("") { decodeXmlEntities(it.groupValues[1]) }
                        sharedStrings.add(text)
                    }
                }
                // 超大或未知大小跳过：sharedStrings 保持空，下方单元格匹配到 t="s" 时
                // 因 idx 不在 sharedStrings.indices 内会回退到原始 value，不会崩溃。
            }

            // 2. 读取目标工作表
            val sheetEntry = zip.getEntry(targetPath)
                ?: return@use XlsxParseResult(emptyList(), emptyList(), false, sheetNames, sheetIndex)
            // P2 修复：同 sharedStrings，目标 sheet 解压后体积可能远大于压缩包，
            // 超过 MAX_XML_ENTRY_BYTES 直接返回空表，避免 readBytes() 触发 OOM。
            if (sheetEntry.size > MAX_XML_ENTRY_BYTES) {
                return@use XlsxParseResult(emptyList(), emptyList(), false, sheetNames, sheetIndex)
            }
            val sheetXml = zip.getInputStream(sheetEntry).use { it.readBytes().toString(Charsets.UTF_8) }

            // 3. 解析行和单元格（行级封顶：一旦达到上限立即 break，超出的行不会被
            //    构建进 rows 列表，从源头避免内存占用随行数无限增长）
            val rowPattern = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
            val cellPattern = Regex("""<c\s+r="([A-Z]+)\d+"([^>]*)>\s*(?:<v>([^<]*)</v>)?""")

            val rows = mutableListOf<List<String>>()
            var truncated = false
            for (rowMatch in rowPattern.findAll(sheetXml)) {
                if (rows.size >= MAX_PARSE_ROWS) {
                    truncated = true
                    break
                }
                val rowContent = rowMatch.groupValues[1]
                val cells = cellPattern.findAll(rowContent).map { cellMatch ->
                    val attrs = cellMatch.groupValues[2]
                    val value = cellMatch.groupValues[3]
                    if (attrs.contains("t=\"s\"") && value.isNotEmpty()) {
                        val idx = value.toIntOrNull() ?: -1
                        if (idx in sharedStrings.indices) sharedStrings[idx] else value
                    } else {
                        value
                    }
                }.toList()
                if (cells.isNotEmpty()) rows.add(cells)
            }

            val columns = rows.firstOrNull() ?: emptyList()
            val dataRows = if (rows.size > 1) rows.subList(1, rows.size) else emptyList()
            XlsxParseResult(columns, dataRows, truncated, sheetNames, sheetIndex)
        }
    }

    /**
     * 切换 xlsx 展示的 sheet（多 sheet 支持）。供 [com.zaijian.zhoumuyun.ui.viewmodel.FilePreviewViewModel]
     * 在用户点击 sheet 标签时调用，只重新解析目标 sheet。
     */
    suspend fun parseXlsxSheet(file: File, sheetIndex: Int): PreviewContent.Tabular = withContext(Dispatchers.IO) {
        val result = try {
            parseXlsx(file, sheetIndex)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.zaijian.zhoumuyun.util.AgentLog.error("FilePreviewParser", "切换 sheet 解析失败：${file.name}", e)
            XlsxParseResult(emptyList(), emptyList(), false, emptyList(), sheetIndex)
        }
        PreviewContent.Tabular(
            columns = result.columns,
            rows = result.rows,
            editable = false,
            sourceFilePath = null,
            isTruncated = result.truncated,
            sheetNames = result.sheetNames,
            activeSheetIndex = result.activeSheetIndex,
        )
    }

    // ── DOCX 解析（从 BuiltinTools.readDocxContents 抽离）──────────────────

    /**
     * 解析 docx 文件为纯文本。
     *
     * 提取 word/document.xml 里的 `<w:t>` 标签内容，`<w:p>` 段落分隔用换行。
     */
    private fun parseDocxText(file: File): String {
        // 同 parseXlsx：ZipFile 改用 .use{}，避免异常路径下 zip.close() 走不到
        // 造成文件描述符泄漏（与 Excel 闪退根因同一类问题，一并修复）。
        return ZipFile(file).use { zip ->
            val docEntry = zip.getEntry("word/document.xml") ?: return@use ""
            val xmlContent = zip.getInputStream(docEntry).use { it.readBytes().toString(Charsets.UTF_8) }

            val textBuilder = StringBuilder()
            val wTPattern = Regex("<w:t[^>]*>([^<]*)</w:t>")
            val wPPattern = Regex("<w:p[^>]*>")
            var pos = 0
            while (pos < xmlContent.length) {
                val wPMatch = wPPattern.find(xmlContent, pos)
                if (wPMatch == null) {
                    wTPattern.findAll(xmlContent, pos).forEach { textBuilder.append(decodeXmlEntities(it.groupValues[1])) }
                    break
                }
                wTPattern.findAll(xmlContent, pos).takeWhile { it.range.first < wPMatch.range.first }
                    .forEach { textBuilder.append(decodeXmlEntities(it.groupValues[1])) }
                textBuilder.append('\n')
                pos = wPMatch.range.last + 1
            }

            textBuilder.toString().trim()
        }
    }

    /** 嗅探文本开头是否像 HTML（用于伪 docx 兜底判断，见 [parseDocxOrFallback]）。 */
    private val HTML_SNIFF_REGEX =
        Regex("<\\s*(!doctype|html|head|body|div|p|table|span|h[1-6])[\\s>]", RegexOption.IGNORE_CASE)

    /**
     * docx 解析入口，带"伪 docx"兜底（对话框内 docx 无法预览的根因修复）。
     *
     * 背景：`docx_gen`/`pdf_export` 等委托生成工具（[CreativeDocTools]）产出的
     * "docx"实际内容是纯 HTML，只是文件名后缀写成了 `.docx`（见 [CreativeDocTools]
     * 里的 openHint 机制，本意是提示用户拿浏览器/WPS 打开另存，而不是真正的 Word
     * 二进制格式）。这类文件不是合法的 zip 容器，[parseDocxText] 内部
     * `ZipFile(file)` 会直接抛 `ZipException`。此前这里没有区分"真解析失败"和
     * "根本不是真 docx"，异常一路冒到 [parse] 最外层的 `catch(Throwable)`，
     * 统一退化成"该文件类型暂不支持应用内预览"，用户只看到预览失败、看不出原因。
     *
     * 现在分两步：
     *  1. 先按真实 docx（zip 容器）解析；成功则正常返回纯文本预览。
     *  2. 失败后把文件当纯文本读出来嗅探是否像 HTML——是则按 [PreviewContent.Html]
     *     渲染（WebView 能正常显示真实内容，与 docx_gen 生成的 html_gen 文件走
     *     同一套渲染器），不是则才真正判定为不支持预览。
     */
    private fun parseDocxOrFallback(file: File, charset: Charset): PreviewContent {
        val realText = try {
            parseDocxText(file)
        } catch (e: Throwable) {
            com.zaijian.zhoumuyun.util.AgentLog.error(
                "FilePreviewParser", "docx 按 zip 容器解析失败，尝试伪 docx 兜底：${file.name}", e,
            )
            null
        }
        if (realText != null) {
            return PreviewContent.Textual(
                text = realText,
                isMarkdown = false,
                sourceFilePath = null,  // docx 只读，不保存
            )
        }

        val text = try {
            file.readText(charset)
        } catch (e: Throwable) {
            null
        }
        return if (text != null && HTML_SNIFF_REGEX.containsMatchIn(text.take(1000))) {
            PreviewContent.Html(
                source = text,
                // 伪 docx 文件名后缀是 .docx，原地保存会破坏"这是 HTML"这一事实，
                // 只允许查看/另存为新文件，不允许覆盖写回原文件。
                sourceFilePath = null,
            )
        } else {
            PreviewContent.Unsupported(
                filePath = file.absolutePath,
                fileName = file.name,
                mimeType = guessMimeType("docx"),
                reason = "无法解析该 docx 文件的内容",
            )
        }
    }

    // ── MIME 类型猜测 ─────────────────────────────────────────────────────

    /** 根据扩展名猜测 MIME 类型（与 FileVaultViewModel.guessMimeType 一致）。 */
    fun guessMimeType(ext: String): String = when (ext.lowercase()) {
        "txt", "log"  -> "text/plain"
        "md"          -> "text/markdown"
        "csv"         -> "text/csv"
        "html", "htm" -> "text/html"
        "json"        -> "application/json"
        "xml"         -> "application/xml"
        "yml", "yaml" -> "application/x-yaml"
        "xlsx"        -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "docx"        -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx"        -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "pdf"         -> "application/pdf"
        "zip"         -> "application/zip"
        else          -> "application/octet-stream"
    }

    /** 判断扩展名是否支持应用内预览。 */
    fun isPreviewable(ext: String): Boolean =
        ext.lowercase() in TEXTUAL_EXTS || ext.lowercase() in MARKDOWN_EXTS ||
        ext.lowercase() in HTML_EXTS || ext.lowercase() in setOf("csv", "xlsx", "docx")
}
