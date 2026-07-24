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
 * - xlsx → [PreviewContent.Tabular]（只读）
 * - docx → [PreviewContent.Textual]（只读，解析为纯文本）
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
                    val (columns, rows) = parseXlsx(file)
                    PreviewContent.Tabular(
                        columns = columns,
                        rows = rows,
                        editable = false,
                        sourceFilePath = null,  // xlsx 只读，不保存
                    )
                }

                ext == "docx" -> {
                    val text = parseDocxText(file)
                    PreviewContent.Textual(
                        text = text,
                        isMarkdown = false,
                        sourceFilePath = null,  // docx 只读，不保存
                    )
                }

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
        } catch (e: Exception) {
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

    /**
     * 解析 xlsx 文件为表格。
     *
     * 注意：当前只读取第一个工作表 sheet1。多 sheet 支持待二期。
     */
    private fun parseXlsx(file: File): Pair<List<String>, List<List<String>>> {
        val zip = ZipFile(file)

        // 1. 提取共享字符串表
        val sharedStrings = mutableListOf<String>()
        val ssEntry = zip.getEntry("xl/sharedStrings.xml")
        if (ssEntry != null) {
            val ssXml = zip.getInputStream(ssEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            val siPattern = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
            val tPattern = Regex("<t[^>]*>([^<]*)</t>")
            siPattern.findAll(ssXml).forEach { siMatch ->
                val text = tPattern.findAll(siMatch.groupValues[1])
                    .joinToString("") { decodeXmlEntities(it.groupValues[1]) }
                sharedStrings.add(text)
            }
        }

        // 2. 读取第一个工作表
        val sheetEntry = zip.getEntry("xl/worksheets/sheet1.xml")
            ?: run { zip.close(); return emptyList<String>() to emptyList() }
        val sheetXml = zip.getInputStream(sheetEntry).use { it.readBytes().toString(Charsets.UTF_8) }
        zip.close()

        // 3. 解析行和单元格
        val rowPattern = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val cellPattern = Regex("""<c\s+r="([A-Z]+)\d+"([^>]*)>\s*(?:<v>([^<]*)</v>)?""")

        val rows = mutableListOf<List<String>>()
        for (rowMatch in rowPattern.findAll(sheetXml)) {
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
        return columns to dataRows
    }

    // ── DOCX 解析（从 BuiltinTools.readDocxContents 抽离）──────────────────

    /**
     * 解析 docx 文件为纯文本。
     *
     * 提取 word/document.xml 里的 `<w:t>` 标签内容，`<w:p>` 段落分隔用换行。
     */
    private fun parseDocxText(file: File): String {
        val zip = ZipFile(file)
        val docEntry = zip.getEntry("word/document.xml")
            ?: run { zip.close(); return "" }
        val xmlContent = zip.getInputStream(docEntry).use { it.readBytes().toString(Charsets.UTF_8) }
        zip.close()

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

        return textBuilder.toString().trim()
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
