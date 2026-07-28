package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Calendar

/**
 * Phase 13 · Tool Call Engine（Prompt-based Dispatch）
 *
 * ═══════════════════════════════════════════════════════════════
 * BuiltinTools.kt — 网络/IO 基础工具（9个）
 * ═══════════════════════════════════════════════════════════════
 *
 * Fix-17 拆分后保留：
 *   ① WebSearchTool   — 网络搜索（web_search）
 *   ② DateTimeTool    — 本地时间（datetime）
 *   ③ TranslateTool   — 翻译（translate）
 *   ④ FileReadTool    — 文件读取（file_read）
 *   ⑤ WeatherTool     — 天气查询（weather）
 *   ⑥ UrlFetchTool    — 网页抓取（url_fetch）
 *   ⑦ FileExportTool  — 文件导出（file_export）
 *
 * 1.4（Agent附件下发方案 v2.0 P2）新增：
 *   ⑧ ArchiveExportTool — 压缩包打包（zip_export），把已导出的多个文件打包成
 *      zip 供用户一次性下载。走 1.1 打通的同一条 exportedFileJson 回填链路，
 *      不需要改 orchestrator。
 *   ⑨ DiagLogExportTool — 诊断日志导出（diag_export_log），原计数遗漏，E3 校验发现后补录。
 *
 * 已拆出到独立文件：
 *   CreativeTools.kt  → CodeGenTool, CodeReviewTool
 *   DataTools.kt      → CalculatorTool, UnitConvertTool, CountdownTool
 *   PersonalTools.kt  → NoteSaveTool, ReminderTool, ClipboardWriteTool, QrDecodeTool
 *
 * 注册方式（在 ZaijianApp.onCreate 中调用）：
 * ```kotlin
 * AgentToolRegistry.registerAll(
 *     WebSearchTool(),
 *     DateTimeTool(),
 *     TranslateTool(),
 *     FileReadTool(context),
 *     WeatherTool(),
 *     UrlFetchTool(),
 *     FileExportTool.getInstance(context),
 *     ArchiveExportTool(context),
 * )
 * ```
 * ═══════════════════════════════════════════════════════════════
 */

// ─────────────────────────────────────────────────────────────
//  ① WebSearchTool
// ─────────────────────────────────────────────────────────────

/**
 * 网络搜索工具。
 *
 * 标签格式：<tool:web_search query="搜索关键词"/>
 * 可选参数：limit="5"（最多返回条数，默认 5，最大 10）
 *
 * 实现：DuckDuckGo Instant Answer API（https://api.duckduckgo.com）
 *   - 完全免 Key
 *   - 返回 Abstract + RelatedTopics
 *   - 无法访问时 fallback 为友好错误文案
 */
class WebSearchTool : AgentTool {

    override val name = "web_search"
    override val description = "联网搜索关键词，返回摘要和相关话题，用于获取外部实时信息"
    override val usageNotes = "limit 可选，结果数量 1-10，默认 5"
    override val paramKeys = listOf("query", "limit")

    private companion object {
        const val BASE_URL = "https://api.duckduckgo.com/"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS    = 15_000
        const val DEFAULT_LIMIT      = 5
        const val MAX_LIMIT          = 10
        const val MAX_SNIPPET_CHARS  = 200
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val query = params["query"]?.trim()
        if (query.isNullOrEmpty()) {
            return ToolResult(
                toolName = name,
                success  = false,
                content  = "",
                error    = "缺少 query 参数",
                userHint = null,
            )
        }
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT

        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val urlStr = "$BASE_URL?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
                // P1-8-2 修复：conn 声明提升到 try 块外，使 finally 可以访问到它并保证 disconnect
                val conn: HttpURLConnection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod  = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout    = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "ZaijianApp/1.0")
                }
                try {
                    val responseCode = conn.responseCode
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        return@withContext ToolResult(
                            toolName = name,
                            success  = false,
                            content  = "",
                            error    = "HTTP $responseCode",
                            userHint = "搜索服务暂时不可用",
                        )
                    }

                    val json = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                        .use { it.readText() }

                    val result = parseDuckDuckGoResponse(json, query, limit)
                    // P1 修复（P2批次3审查报告问题B）：原实现空结果时 success=false 但
                    // error 未填充，ToolCallInterceptor 回注 LLM 时读不到 error，只能显示
                    // "[web_search 执行失败: 未知错误]"，丢失了 content 里"未找到关于...的
                    // 相关信息"这句友好文案，LLM 无法区分"没搜到"和"API挂了"。
                    // 补上 error，值与友好文案对齐，便于 LLM 判断该换关键词还是稍后重试。
                    val notFoundMsg = "未找到关于「$query」的相关信息。"
                    ToolResult(
                        toolName = name,
                        success  = result.isNotEmpty(),
                        content  = result.ifEmpty { notFoundMsg },
                        error    = if (result.isEmpty()) notFoundMsg else null,
                        userHint = "正在搜索「$query」…",
                    )
                } finally {
                    conn.disconnect()
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "搜索「$query」时遇到问题，请稍后再试。", "web_search_failed", e)
            }
        }
    }

    private fun parseDuckDuckGoResponse(json: String, query: String, limit: Int): String {
        val obj = JSONObject(json)
        val results = mutableListOf<String>()

        val abstract = obj.optString("Abstract", "").trim()
        val abstractSource = obj.optString("AbstractSource", "").trim()
        if (abstract.isNotEmpty()) {
            val snippet = if (abstract.length > MAX_SNIPPET_CHARS)
                abstract.take(MAX_SNIPPET_CHARS) + "…" else abstract
            val sourceNote = if (abstractSource.isNotEmpty()) "（来源：$abstractSource）" else ""
            results.add("$snippet$sourceNote")
        }

        val topics = obj.optJSONArray("RelatedTopics")
        if (topics != null) {
            for (i in 0 until topics.length()) {
                if (results.size >= limit) break
                val topic = topics.optJSONObject(i) ?: continue
                val text = topic.optString("Text", "").trim()
                if (text.isEmpty()) continue
                val snippet = if (text.length > MAX_SNIPPET_CHARS)
                    text.take(MAX_SNIPPET_CHARS) + "…" else text
                results.add(snippet)
            }
        }

        if (results.isEmpty()) return ""

        return buildString {
            appendLine("[搜索结果: \"$query\"]")
            results.forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
        }.trimEnd()
    }
}

// ─────────────────────────────────────────────────────────────
//  ② DateTimeTool
// ─────────────────────────────────────────────────────────────

/**
 * 本地时间工具。
 *
 * 标签格式：<tool:datetime format="full"/>
 *
 * format 参数：
 *   - "full"      （默认）完整信息：日期 + 时间 + 星期 + 时区
 *   - "date"      仅日期：2025年6月6日 星期五
 *   - "time"      仅时间：14:32:07
 *   - "week"      星期几：星期五
 *   - "year"      仅年份：2025
 *   - "timestamp" Unix 时间戳（秒）
 */
class DateTimeTool : AgentTool {

    override val name = "datetime"
    override val description = "获取本地当前日期/时间/星期/时区等信息"
    override val usageNotes = "format 可选值：full/date/time/week/year/timestamp，默认 full"
    override val paramKeys = listOf("format")

    // 修复（第3窗口审查报告问题4）：统一包裹 withContext(Dispatchers.IO)，
    // 与项目内其他 AgentTool（CalculatorTool/UnitConvertTool/CountdownTool 等）保持契约一致，
    // 即便本工具当前实现是纯本地计算，不依赖此调度也不产生功能性影响。
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        // P1-4 修复：datetime 工具此前完全没有 try-catch，TimeFormatUtils 任何异常
        // （含 Error 子类）会直接穿透 execute()，违反 AgentTool 契约。
        try {
        val format = params["format"]?.trim()?.lowercase() ?: "full"
        val now = Calendar.getInstance()

        val result = when (format) {
            "date" -> {
                TimeFormatUtils.formatChineseFullDateWithWeekday(now.timeInMillis)
            }
            "time" -> {
                TimeFormatUtils.formatTimeWithSeconds(now.timeInMillis)
            }
            "week" -> {
                TimeFormatUtils.getChineseWeekdayFull(now.timeInMillis)
            }
            "year" -> {
                now.get(Calendar.YEAR).toString()
            }
            "timestamp" -> {
                (now.timeInMillis / 1000L).toString()
            }
            else -> {
                val tz = now.timeZone
                val offsetHours = tz.rawOffset / 3_600_000
                val tzStr = if (offsetHours >= 0) "UTC+$offsetHours" else "UTC$offsetHours"
                "${TimeFormatUtils.formatChineseFullDateTimeWithWeekday(now.timeInMillis)} ($tzStr)"
            }
        }

        ToolResult(
            toolName = name,
            success  = true,
            content  = "[当前时间]\n$result",
        )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "获取时间失败，请稍后重试。", "datetime_failed", e)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ③ TranslateTool
// ─────────────────────────────────────────────────────────────

/**
 * 翻译工具。
 *
 * 标签格式：<tool:translate text="Hello World" target="zh" source="en"/>
 *
 * 参数：
 *   - text    （必须）需要翻译的文本
 *   - target  目标语言代码（默认 "zh"）
 *   - source  源语言代码（默认 "auto"，自动检测）
 *
 * 实现：MyMemory API（https://api.mymemory.translated.net）
 *   - 完全免 Key，日限额：5000 词
 */
class TranslateTool : AgentTool {

    override val name = "translate"
    override val description = "将文本在不同语言之间翻译"
    override val usageNotes = "text 最长 500 字，超长部分会被截断并在结果中提示；target/source 用语言代码如 zh/en/ja/ko，source 可选默认自动检测"
    override val paramKeys = listOf("text", "target", "source")

    private companion object {
        const val BASE_URL           = "https://api.mymemory.translated.net/get"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS    = 12_000
        const val MAX_TEXT_LENGTH    = 500

        val LANG_NAMES = mapOf(
            "zh" to "中文", "en" to "英语", "ja" to "日语", "ko" to "韩语",
            "fr" to "法语", "de" to "德语", "es" to "西班牙语", "ru" to "俄语",
            "ar" to "阿拉伯语", "pt" to "葡萄牙语",
        )
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val text = params["text"]?.trim()
        if (text.isNullOrEmpty()) {
            return ToolResult(name, false, "", "缺少 text 参数")
        }
        // P3 修复（P2批次2审查报告问题E）：原 `?: "zh"` 只在 target 为 null 时生效，
        // target="" 经 trim()/lowercase() 后仍是 ""，不为 null，会绕过默认值，
        // 带着空 target 构造 langPair 发给 MyMemory API 导致请求出错。
        // 改用 takeIf { isNotEmpty() } 让空字符串也走默认值。
        val target = params["target"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "zh"
        val source = params["source"]?.trim()?.lowercase()?.let {
            if (it == "auto") null else it
        }

        // P1 修复（批次3审查报告问题2）：原实现 text.take(MAX_TEXT_LENGTH) 裸截断，
        // 无句子边界感知、且 ToolResult 不带任何提示就返回 success，用户以为整段
        // 都翻了，实际只翻了前 500 字。改为按句子边界截断（与 memory 三工具的
        // truncateAtSentenceBoundary 同一策略），并在截断发生时把提示写入
        // ToolResult.content，而不是静默吞掉后半段。
        val wasTruncated = text.length > MAX_TEXT_LENGTH
        val truncated = if (wasTruncated) truncateAtSentenceBoundaryForTranslate(text, MAX_TEXT_LENGTH) else text

        return withContext(Dispatchers.IO) {
            // 批次4-1-3 修复：用 try-finally 包裹 HttpURLConnection，
            // 非200响应码分支原先直接 return@withContext 跳过了
            // conn.disconnect()，异常路径同样泄漏。与同文件 WeatherTool
            // 的 fetchUrl() 对齐处理方式。
            var conn: HttpURLConnection? = null
            try {
                val langPair = if (source != null) "$source|$target" else "autodetect|$target"
                val encodedText = URLEncoder.encode(truncated, "UTF-8")
                val urlStr = "$BASE_URL?q=$encodedText&langpair=$langPair"

                val httpConn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod  = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout    = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "ZaijianApp/1.0")
                }
                conn = httpConn

                val responseCode = httpConn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext ToolResult(
                        name, false, "", "HTTP $responseCode",
                        userHint = "翻译服务暂时不可用",
                    )
                }

                val json = BufferedReader(InputStreamReader(httpConn.inputStream, "UTF-8"))
                    .use { it.readText() }

                val obj = JSONObject(json)
                val responseData = obj.optJSONObject("responseData")
                val translated = responseData?.optString("translatedText", "")?.trim()

                if (translated.isNullOrEmpty()) {
                    // P1 修复（P2批次2审查报告问题B）：原先未传 error，回注 LLM 会显示
                    // "[translate 执行失败: 未知错误]"，丢失"未能获取翻译结果"这句友好文案。
                    return@withContext ToolResult(
                        name, false, "未能获取翻译结果，请稍后再试。",
                        error = "empty_translation_response",
                    )
                }

                val sourceName = source?.let { LANG_NAMES[it] } ?: "自动检测"
                val targetName = LANG_NAMES[target] ?: target.uppercase()
                // P1 修复（批次3审查报告问题2）：截断发生时显式提示，而非静默 success。
                val truncateNotice = if (wasTruncated) {
                    "\n[提示：原文超过 $MAX_TEXT_LENGTH 字，仅翻译了前 ${truncated.length} 字，后 ${text.length - truncated.length} 字未翻译]"
                } else ""

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[翻译结果: $sourceName → $targetName]\n$translated$truncateNotice",
                    userHint = "正在翻译…",
                )

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "翻译时遇到问题。", "translate_failed", e)
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * 按句子边界截断，避免 text.take(n) 在词/句中间硬切。
     * 与 SoulMemoryUserTools.kt 的 truncateAtSentenceBoundary 同一策略：
     * 在 maxChars 范围内找最后一个句子边界符（。！？\n），若找到的边界
     * 位置太靠前（不到 maxChars 一半）则说明文本本身没有合适的断点，
     * 退化为直接截断，避免截出一个只有几个字的结果。
     */
    private fun truncateAtSentenceBoundaryForTranslate(text: String, maxChars: Int): String {
        val cut = text.take(maxChars)
        val lastBoundary = cut.lastIndexOfAny(charArrayOf('。', '！', '？', '\n', '.', '!', '?'))
        return if (lastBoundary > maxChars / 2) cut.take(lastBoundary + 1) else cut
    }
}

// ─────────────────────────────────────────────────────────────
//  ④ FileReadTool
// ─────────────────────────────────────────────────────────────

/**
 * 文件读取工具。
 *
 * 标签格式：<tool:file_read path="notes.txt"/>
 * 可选参数：lines="50"（最多读取行数，默认 100）
 *
 * Phase 18 增强：支持读取 ZIP 文件内部文本文件内容。
 */
class FileReadTool(private val context: Context) : AgentTool {

    override val name = "file_read"
    override val description = "读取本地文件内容（含ZIP内部文本文件），用于查看已保存的文件"
    override val paramKeys = listOf("path", "lines")

    private companion object {
        const val MAX_CHARS = 8_000
        const val DEFAULT_LINES = 100
        const val MAX_LINES = 500
        val ZIP_TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "csv", "xml", "yaml", "yml",
            "kt", "java", "py", "js", "ts", "html", "css", "sh",
            "gradle", "properties", "toml", "ini", "conf", "log",
        )
        const val ZIP_MAX_FILE_SIZE = 500_000L
        const val ZIP_MAX_FILES     = 20
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val path = params["path"]?.trim()
        if (path.isNullOrEmpty()) {
            return ToolResult(name, false, "", "缺少 path 参数")
        }

        // P2-19 修复（同步）：原用 path.contains("../") 子串匹配，对含 "..."
        // 的合法路径误报。改为按路径分隔符分段后精确比较==".."，与
        // FileSystemTools.hasPathTraversal 的修复保持一致。
        val pathSegments = path.split("/", "\\")
        if (pathSegments.any { it == ".." }) {
            return ToolResult(
                toolName = name,
                success  = false,
                content  = "无法读取该路径。",
                error    = "路径包含非法字符",
            )
        }

        val maxLines = params["lines"]?.toIntOrNull()?.coerceIn(1, MAX_LINES) ?: DEFAULT_LINES

        return withContext(Dispatchers.IO) {
            try {
                val file = resolveFile(path)
                if (file == null || !file.exists()) {
                    return@withContext ToolResult(
                        toolName = name,
                        success  = false,
                        content  = "找不到文件「$path」。请确认文件路径是否正确。",
                    )
                }
                if (!file.isFile) {
                    return@withContext ToolResult(
                        name, false, "「$path」是一个目录，不是文件。",
                    )
                }
                if (file.length() == 0L) {
                    return@withContext ToolResult(
                        name, true, "[文件内容: ${file.name}]\n（文件为空）",
                    )
                }

                if (file.name.lowercase().endsWith(".zip")) {
                    return@withContext readZipContents(file, maxLines)
                }

                // v1.48 docx 读取修复：.docx 本质是 ZIP 压缩包（Office Open XML），
                // 直接当文本读会读到 PK 开头的二进制乱码。需解压后读 word/document.xml
                // 提取正文文本。
                if (file.name.lowercase().endsWith(".docx")) {
                    return@withContext readDocxContents(file, maxLines)
                }

                // v1.48 xlsx 读取修复：.xlsx 同理是 ZIP，内含 sharedStrings.xml +
                // sheetN.xml。提取出纯文本供 AI 阅读。
                if (file.name.lowercase().endsWith(".xlsx")) {
                    return@withContext readXlsxContents(file, maxLines)
                }

                // P1-8 修复：除 zip/docx/xlsx 外的二进制文件（apk/png/pdf 等）会落入通用文本
                // 读取路径，二进制内容解码成"单行超长字符串"可能 OOM。按扩展名拦截 + 文件大小限制。
                val binaryExts = setOf("apk","jar","dex","so","png","jpg","jpeg","gif","bmp","webp",
                    "mp3","mp4","avi","mov","wav","flac","ogg","pdf","class","exe","dll")
                val ext = file.name.lowercase().substringAfterLast('.', "")
                if (ext in binaryExts) {
                    return@withContext ToolResult(name, false, "",
                        error = "不支持直接读取二进制文件（.$ext），如需查看内容请先转换为文本格式")
                }
                val maxFileSize = 2L * 1024 * 1024
                if (file.length() > maxFileSize) {
                    return@withContext ToolResult(name, false, "",
                        error = "文件过大（${file.length() / 1024}KB），最大支持 ${maxFileSize / 1024}KB")
                }

                // 二进制内容探测（第三层防护）：扩展名黑名单只能拦截已知后缀，改名成 .txt
                // 或使用 .bin/.dat/无后缀等未列入黑名单的二进制文件仍会漏进下面的通用文本
                // 读取路径。读取前 8KB 探测是否含 null 字节，命中则视为二进制文件直接拒绝，
                // 避免 bufferedReader 把二进制内容解码成超长乱码字符串导致的潜在 OOM。
                val probe = ByteArray(8192)
                val probeRead = file.inputStream().use { it.read(probe) }
                if (probeRead > 0 && probe.copyOfRange(0, probeRead).any { it == 0.toByte() }) {
                    return@withContext ToolResult(name, false, "",
                        error = "检测到二进制文件内容，不支持文本读取")
                }

                // v147+ CSV 乱码修复：自动检测文件编码（UTF-8 / GBK / UTF-16），
                // 不再硬编码 UTF-8。Windows Excel 导出的中文 CSV 默认 GBK，硬编码
                // UTF-8 会导致乱码（AI 看到"鏉傞繝鍧?"之类的误解码产物）。
                val charset = detectFileCharset(file)
                com.zaijian.zhoumuyun.util.AgentLog.info("FileRead", "读取 ${file.name}，检测编码：$charset")

                val lines = file.bufferedReader(charset).useLines { seq ->
                    seq.take(maxLines).toList()
                }
                val totalLineCount = file.bufferedReader(charset).useLines { it.count() }

                var content = lines.joinToString("\n")
                var truncated = false
                if (content.length > MAX_CHARS) {
                    content = content.take(MAX_CHARS)
                    truncated = true
                }

                val header = buildString {
                    append("[文件内容: ${file.name}]")
                    append("\n（共 $totalLineCount 行")
                    if (lines.size < totalLineCount) append("，已读取前 ${lines.size} 行")
                    if (truncated) append("，已截断至 $MAX_CHARS 字符")
                    append("）")
                }

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "$header\n$content",
                    userHint = "正在读取「${file.name}」…",
                )

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "读取文件时遇到问题。", "file_read_failed", e)
            }
        }
    }

    private suspend fun readZipContents(zipFile: java.io.File, maxLines: Int): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                // 修复（ZipFile 资源泄漏 + zip 闪退根因）：
                // 原实现 `val zip = ZipFile(file)` 靠末尾 `zip.close()` 手动关闭，
                // 一旦中途抛异常（文件损坏、编码异常等），close() 走不到，
                // ZipFile 持有的文件描述符泄漏——多次触发后可能导致后续文件操作
                // 失败甚至 "Too many open files"。改用 .use{} 保证无论正常返回
                // 还是异常都关闭。
                java.util.zip.ZipFile(zipFile).use { zip ->
                    val entries = zip.entries().toList()

                val allNames = entries.map { it.name }.take(100)
                val dirTree = allNames.joinToString("\n") { "  $it" }

                val textEntries = entries
                    .filter { entry ->
                        !entry.isDirectory &&
                        entry.size <= ZIP_MAX_FILE_SIZE &&
                        entry.name.substringAfterLast(".", "").lowercase() in ZIP_TEXT_EXTENSIONS &&
                        !entry.name.contains("__MACOSX") &&
                        !entry.name.startsWith(".")
                    }
                    .sortedBy { it.size }
                    .take(ZIP_MAX_FILES)

                val contentBuilder = StringBuilder()
                contentBuilder.appendLine("[ZIP 文件: ${zipFile.name}]")
                contentBuilder.appendLine("── 目录结构（共 ${entries.size} 个条目）──")
                contentBuilder.appendLine(dirTree.take(2000))
                contentBuilder.appendLine()
                contentBuilder.appendLine("── 文本文件内容（${textEntries.size} 个）──")

                var totalChars = contentBuilder.length

                for (entry in textEntries) {
                    if (totalChars >= MAX_CHARS) break

                    val header = "\n[${entry.name}]（${entry.size} bytes）\n"
                    contentBuilder.append(header)
                    totalChars += header.length

                    try {
                        // v147+ CSV 乱码修复：ZIP 内文件也做编码检测
                        // 把 entry 内容读到临时字节数组再检测编码（ZIP entry 不能 seek）
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val tempFile = java.io.File.createTempFile("zip_entry", ".tmp")
                        tempFile.deleteOnExit()
                        tempFile.writeBytes(bytes)
                        val charset = detectFileCharset(tempFile)
                        tempFile.delete()

                        val lines = java.io.BufferedReader(java.io.InputStreamReader(
                            bytes.inputStream(), charset,
                        )).useLines { it.take(maxLines).toList() }

                        val remaining = MAX_CHARS - totalChars
                        val fileContent = lines.joinToString("\n").take(remaining.coerceAtLeast(0))
                        contentBuilder.append(fileContent)
                        totalChars += fileContent.length
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        contentBuilder.appendLine("（无法读取该文件内容）")
                    }
                }

                // zip.close() 已由 .use{} 自动执行

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = contentBuilder.toString().take(MAX_CHARS),
                    userHint = "正在分析 ZIP 文件内容…",
                )
                }  // .use { zip -> }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "无法解析 ZIP 文件。", "zip_parse_failed", e)
            }
        }

    /**
     * 读取 .docx 文件内容（Office Open XML）。
     *
     * .docx 本质是 ZIP 压缩包，正文在 `word/document.xml` 里，用 `<w:t>` 标签包裹文本。
     * 直接当文本读会读到 PK 开头的二进制乱码，需解压后解析 XML 提取纯文本。
     *
     * 实现：用 JDK ZipFile 解压，取 word/document.xml，用正则提取 `<w:t>` 标签内容，
     * `<w:p>` 段落分隔用换行——不依赖 Apache POI（项目未引入）。
     */
    private suspend fun readDocxContents(docxFile: java.io.File, maxLines: Int): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                // 修复（ZipFile 资源泄漏，与 readZipContents 同款问题）：
                // 原实现手动 zip.close()，且 docEntry==null 分支直接 return@withContext，
                // 连这行手动 close() 都走不到，句柄必然泄漏。改用 .use{} 包裹整个函数体，
                // 保证无论正常返回、提前 return 还是抛异常都会关闭（return@withContext
                // 在 inline 的 use{} 内部是合法的非局部返回，finally 里的 close 仍会执行）。
                java.util.zip.ZipFile(docxFile).use { zip ->
                    val docEntry = zip.getEntry("word/document.xml")
                        ?: return@withContext ToolResult(
                            name, false, "无法解析 docx：找不到 word/document.xml（可能不是标准 docx 格式）",
                        )

                    val xmlContent = zip.getInputStream(docEntry).use { it.readBytes().toString(Charsets.UTF_8) }

                    // 提取 <w:t> 标签内的文本（正文文字）
                    // <w:p> 是段落，用换行分隔
                    val textBuilder = StringBuilder()
                    val wTPattern = Regex("<w:t[^>]*>([^<]*)</w:t>")
                    val wPPattern = Regex("<w:p[^>]*>")
                    var pos = 0
                    while (pos < xmlContent.length) {
                        val wPMatch = wPPattern.find(xmlContent, pos)
                        if (wPMatch == null) {
                            // 剩余文本
                            wTPattern.findAll(xmlContent, pos).forEach { textBuilder.append(it.groupValues[1]) }
                            break
                        }
                        // 段落前的 <w:t>
                        wTPattern.findAll(xmlContent, pos).takeWhile { it.range.first < wPMatch.range.first }
                            .forEach { textBuilder.append(it.groupValues[1]) }
                        textBuilder.append('\n')  // 段落分隔
                        pos = wPMatch.range.last + 1
                    }

                    val extractedText = textBuilder.toString().trim()
                    if (extractedText.isEmpty()) {
                        return@withContext ToolResult(
                            name, true,
                            "[docx 文件: ${docxFile.name}]\n文档为空或正文无可提取文本（可能是图片型文档或加密文档）。",
                        )
                    }

                    val lineCount = extractedText.lines().size
                    val preview = extractedText.lines().take(maxLines).joinToString("\n")
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[docx 文件: ${docxFile.name}]\n── 正文内容（共 ${lineCount} 行，显示前 ${minOf(maxLines, lineCount)} 行）──\n$preview",
                        userHint = "正在解析 Word 文档…",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "无法解析 docx 文件。", "docx_parse_failed", e)
            }
        }

    /**
     * 读取 .xlsx 文件内容（Office Open XML）。
     *
     * .xlsx 本质是 ZIP，单元格文本在 xl/sharedStrings.xml 里（共享字符串表），
     * 每个 `<si>` 是一个字符串，内含 `<t>` 标签。工作表在 xl/worksheets/sheetN.xml。
     *
     * 实现：提取 sharedStrings.xml 的 `<t>` 标签内容作为单元格值列表，然后读
     * sheet1.xml 的 `<c>` 单元格，按行列拼成文本表格——不依赖 Apache POI。
     */
    private suspend fun readXlsxContents(xlsxFile: java.io.File, maxLines: Int): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                // 修复（ZipFile 资源泄漏，与 readZipContents/readDocxContents 同款问题）：
                // sheetEntry==null 分支原先直接 return@withContext，手动 close() 走不到。
                // 改用 .use{} 包裹整个函数体，任何路径都会关闭。
                java.util.zip.ZipFile(xlsxFile).use { zip ->
                    // 1. 提取共享字符串表
                    val sharedStrings = mutableListOf<String>()
                    val ssEntry = zip.getEntry("xl/sharedStrings.xml")
                    if (ssEntry != null) {
                        val ssXml = zip.getInputStream(ssEntry).use { it.readBytes().toString(Charsets.UTF_8) }
                        // <si> 是一个字符串项，内含一个或多个 <t> 标签（富文本可能有多个）
                        val siPattern = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
                        val tPattern = Regex("<t[^>]*>([^<]*)</t>")
                        siPattern.findAll(ssXml).forEach { siMatch ->
                            val text = tPattern.findAll(siMatch.groupValues[1])
                                .joinToString("") { it.groupValues[1] }
                            sharedStrings.add(text)
                        }
                    }

                    // 2. 读取第一个工作表
                    val sheetEntry = zip.getEntry("xl/worksheets/sheet1.xml")
                        ?: return@withContext ToolResult(
                            name, false, "无法解析 xlsx：找不到 xl/worksheets/sheet1.xml",
                        )
                    val sheetXml = zip.getInputStream(sheetEntry).use { it.readBytes().toString(Charsets.UTF_8) }

                    // 3. 解析行和单元格
                    // <row> 是行，<c r="A1" t="s"><v>0</v></c> 是单元格
                    // t="s" 表示值是共享字符串索引（查 sharedStrings），无 t 属性是数字
                    val rowPattern = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
                    val cellPattern = Regex("""<c\s+r="([A-Z]+)\d+"([^>]*)>\s*(?:<v>([^<]*)</v>)?""")
                    val colPattern = Regex("[A-Z]+")

                    val rows = mutableListOf<List<String>>()
                    for (rowMatch in rowPattern.findAll(sheetXml)) {
                        val rowContent = rowMatch.groupValues[1]
                        val cells = cellPattern.findAll(rowContent).map { cellMatch ->
                            val attrs = cellMatch.groupValues[2]
                            val value = cellMatch.groupValues[3]
                            if (attrs.contains("t=\"s\"") && value.isNotEmpty()) {
                                // 共享字符串索引
                                val idx = value.toIntOrNull() ?: -1
                                if (idx in sharedStrings.indices) sharedStrings[idx] else value
                            } else {
                                value
                            }
                        }.toList()
                        if (cells.isNotEmpty()) rows.add(cells)
                    }

                    if (rows.isEmpty()) {
                        return@withContext ToolResult(
                            name, true,
                            "[xlsx 文件: ${xlsxFile.name}]\n工作表为空或无数据。",
                        )
                    }

                    // 4. 格式化输出为文本表格
                    val preview = rows.take(maxLines).joinToString("\n") { it.joinToString(" | ") }
                    // 复核意见三：暂不支持多 sheet，必须在返回内容里显式提示，
                    // 不能让用户/AI 以为读到的是完整表格数据而实际读漏了其他 sheet。
                    // 未来若支持多 sheet（解析 xl/workbook.xml 的 sheet 列表），
                    // 移除此提示并改为列出可用 sheet 供 AI 选择读取。
                    val multiSheetHint = "（仅读取工作簿的第一个工作表 sheet1，如需其他工作表请说明）"
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[xlsx 文件: ${xlsxFile.name}]\n── 表格内容（共 ${rows.size} 行，显示前 ${minOf(maxLines, rows.size)} 行）──\n$multiSheetHint\n$preview",
                        userHint = "正在解析 Excel 文档…",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "无法解析 xlsx 文件。", "xlsx_parse_failed", e)
            }
        }

    private fun resolveFile(path: String): File? {
        if (path.startsWith("/")) {
            val file = File(path)
            val allowed = listOf(
                context.filesDir.absolutePath,
                context.cacheDir.absolutePath,
                context.getExternalFilesDir(null)?.absolutePath ?: "",
            )
            if (allowed.none { path.startsWith(it) }) return null
            return file
        }

        val internal = File(context.filesDir, path)
        if (internal.exists()) return internal

        val external = context.getExternalFilesDir(null)?.let { File(it, path) }
        if (external?.exists() == true) return external

        return null
    }
}

// ─────────────────────────────────────────────────────────────
//  ⑤ WeatherTool
// ─────────────────────────────────────────────────────────────

/**
 * 天气查询工具（Phase 17 新增）。
 *
 * 标签格式：<tool:weather city="城市名"/>
 * 可选参数：unit="celsius"（温度单位，默认摄氏度）
 *
 * 实现：open-meteo.com（完全免 Key，支持城市名解析）
 */
class WeatherTool : AgentTool {

    override val name     = "weather"
    override val description = "查询指定城市的天气情况"
    override val usageNotes = "unit 可选 celsius/fahrenheit，默认 celsius"
    override val paramKeys = listOf("city", "unit")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        // P3 修复（P2批次2审查报告问题E）：原 `params["city"]?.trim() ?: return error`
        // 对 null 有效，但 city="   " 经 trim() 后是 ""，不为 null，会通过校验进入网络
        // 请求，白白浪费一次 geocoding 调用。改用 takeIf { isNotEmpty() } 提前拦截。
        val city = params["city"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@withContext ToolResult(name, false, "请告诉我要查询哪个城市的天气。", error = "缺少 city 参数")
        val useFahrenheit = params["unit"]?.lowercase() == "fahrenheit"

        try {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=1&language=zh&format=json"
            // P1 修复（P2批次2审查报告问题B）：以下 4 处错误分支原先只传 content 友好文案，
            // error 留空。ToolCallInterceptor 失败时只回注 error，导致 LLM 看到的是
            // "[weather 执行失败: 未知错误]"，丢失了"无法获取位置信息"这类具体原因，
            // 无法判断该重试还是该让用户换个城市名。这里给每处补上对应 error。
            val geoJson = fetchUrl(geoUrl)
                ?: return@withContext ToolResult(name, false, "无法获取「$city」的位置信息，稍后再试？", error = "geocoding_fetch_failed")
            val geoObj   = JSONObject(geoJson)
            val results  = geoObj.optJSONArray("results")
                ?: return@withContext ToolResult(name, false, "没有找到「$city」，试试输入更精确的城市名？", error = "city_not_found")
            if (results.length() == 0) return@withContext ToolResult(name, false, "搜索城市未返回结果", error = "city_not_found")
            val loc      = results.getJSONObject(0)
            val lat      = loc.getDouble("latitude")
            val lon      = loc.getDouble("longitude")
            val cityName = loc.optString("name", city)

            val tempUnit = if (useFahrenheit) "fahrenheit" else "celsius"
            val wxUrl    = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
                "&temperature_unit=$tempUnit&wind_speed_unit=kmh&timezone=auto"
            val wxJson   = fetchUrl(wxUrl)
                ?: return@withContext ToolResult(name, false, "获取天气数据时遇到问题，稍后再试？", error = "weather_fetch_failed")
            val wxObj    = JSONObject(wxJson)
            val current  = wxObj.getJSONObject("current")

            val temp     = current.optDouble("temperature_2m", Double.NaN)
            val feels    = current.optDouble("apparent_temperature", Double.NaN)
            val humidity = current.optInt("relative_humidity_2m", -1)
            val wind     = current.optDouble("wind_speed_10m", Double.NaN)
            val wmoCode  = current.optInt("weather_code", -1)
            val unitSymbol = if (useFahrenheit) "°F" else "°C"

            val weatherDesc = wmoCodeToDesc(wmoCode)

            val content = buildString {
                appendLine("[天气查询: $cityName]")
                appendLine("天气：$weatherDesc")
                if (!temp.isNaN())  appendLine("气温：${"%.1f".format(temp)}$unitSymbol（体感 ${"%.1f".format(feels)}$unitSymbol）")
                if (humidity >= 0)  appendLine("湿度：$humidity%")
                if (!wind.isNaN())  appendLine("风速：${"%.1f".format(wind)} km/h")
            }.trimEnd()

            ToolResult(
                toolName = name,
                success  = true,
                content  = content,
                userHint = "正在查询「$cityName」天气…",
            )

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "查询天气时遇到点问题。", "weather_failed", e)
        }
    }

    private fun wmoCodeToDesc(code: Int): String = when (code) {
        0               -> "晴天 ☀️"
        1               -> "基本晴朗 🌤️"
        2               -> "局部多云 ⛅"
        3               -> "阴天 ☁️"
        in 45..48       -> "大雾 🌫️"
        in 51..53       -> "毛毛雨 🌦️"
        in 55..57       -> "冻毛毛雨 🌧️"
        in 61..63       -> "小到中雨 🌧️"
        65              -> "大雨 🌧️"
        in 71..75       -> "降雪 🌨️"
        77              -> "米雪 🌨️"
        in 80..82       -> "阵雨 ⛈️"
        in 85..86       -> "阵雪 🌨️"
        in 95..99       -> "雷暴 ⛈️"
        else            -> "未知天气（代码 $code）"
    }

    private fun fetchUrl(urlStr: String): String? = try {
        // P3 修复（P2批次2审查报告问题F）：weather 内部是两次串行请求（geocoding→forecast），
        // 原 8s connect + 8s read 意味着单次最坏 16s，两次最坏 32s，超过
        // ToolCallInterceptor 的 30s 总超时阈值，极端情况下第二次请求会被外层中断。
        // 降到 6s+6s（单次最坏 12s，两次最坏 24s），留出安全余量。
        // 同 UrlFetchTool 的 P1-8-2 修复：conn 声明在内层 try 外，finally 保证 disconnect
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout    = 6000
        conn.requestMethod  = "GET"
        try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    } catch (e: Throwable) { null }
}

// ─────────────────────────────────────────────────────────────
//  ⑥ UrlFetchTool
// ─────────────────────────────────────────────────────────────

/**
 * 网页正文抓取工具（Phase 18）。
 *
 * 标签格式：<tool:url_fetch url="https://..."/>
 * 可选参数：max_chars="3000"（默认 3000，最大 8000）
 */
class UrlFetchTool : AgentTool {

    override val name      = "url_fetch"
    override val description = "抓取网页正文内容，用于「帮我看看这个链接讲了什么」"
    override val usageNotes = "max_chars 可选，100-8000，默认 3000；不支持抓取内网地址"
    override val paramKeys = listOf("url", "max_chars")

    private companion object {
        const val DEFAULT_MAX_CHARS = 3_000
        const val MAX_CHARS_LIMIT   = 8_000
        const val CONNECT_TIMEOUT   = 8_000
        const val READ_TIMEOUT      = 12_000
        // #15 修复：readText() 将整个 HTTP 响应读入内存后再截断，大响应会 OOM。
        // 改为流式读取并限制最大读取字节数（1MB），超过即截断。
        const val MAX_READ_BYTES    = 1_048_576 // 1MB
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val url = params["url"]?.trim()
        if (url.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 url 参数")
        }
        val maxChars = params["max_chars"]?.toIntOrNull()
            ?.coerceIn(100, MAX_CHARS_LIMIT) ?: DEFAULT_MAX_CHARS

        // P3 修复（P2批次2审查报告问题G）：原实现未校验 url 指向的 host 是否为内网/回环
        // 地址，LLM 若被诱导抓取 http://192.168.1.1/admin 或 http://localhost:xxx 这类
        // 内网资源，工具会照常发起请求（SSRF）。这里在真正发起连接前，先解析 host 对应的
        // IP，命中私有/回环/链路本地地址段就拒绝。放在 DNS 解析之后而不是纯字符串匹配
        // host，是为了同时挡住"域名解析到内网 IP"这种绕过字符串黑名单的场景。
        try {
            val host = java.net.URL(url).host
            val addr = java.net.InetAddress.getByName(host)
            if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isAnyLocalAddress) {
                return@withContext ToolResult(
                    name, false, "该链接指向内网地址，出于安全考虑不予抓取。",
                    error = "ssrf_blocked_private_address",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext ToolResult(name, false, "网址格式不正确或无法解析。", error = "invalid_url")
        }

        try {
            // P1-8-2 修复：conn 声明在内层 try 外，使 finally 保证 disconnect
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; ZaijianBot/1.0)")
            conn.setRequestProperty("Accept", "text/html,text/plain;q=0.9")

            try {
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    // P1 修复（P2批次2审查报告问题B）：原先只传 content 未传 error，
                    // 回注 LLM 会丢失"HTTP $responseCode"这一具体原因，变成"未知错误"。
                    return@withContext ToolResult(
                        name, false,
                        "无法访问该网页（HTTP $responseCode）。",
                        error = "HTTP $responseCode",
                    )
                }

                // P2 修复：从 Content-Type header 解析字符集，而非误用 Content-Encoding（后者是 gzip/deflate 压缩编码）
                val charset = conn.contentType?.charset()?.name() ?: "UTF-8"
                // #15 修复：原 readText() 将整个 HTTP 响应读入内存后再 take(maxChars) 截断，
                // 抓取超大页面（如几十 MB 的 HTML）会 OOM。改为流式读取并限制最大字节数 1MB，
                // 超过即截断，内存占用可控。
                val html = readLimitedString(conn.inputStream, charsetFor(charset), MAX_READ_BYTES)
                val text = extractReadableText(html).take(maxChars)

                if (text.isBlank()) {
                    return@withContext ToolResult(name, false, "网页内容为空或无法提取正文。", error = "empty_content")
                }

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[网页内容: $url]\n$text",
                    userHint = "正在抓取网页…",
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: java.net.UnknownHostException) {
            // P1 修复（P2批次2审查报告问题B）：同上，补 error 让 LLM 知道具体是网络问题
            ToolResult(name, false, "无法连接网络，请检查网络设置后重试。", error = "unknown_host")
        } catch (e: java.net.SocketTimeoutException) {
            ToolResult(name, false, "网页响应超时，稍后再试。", error = "timeout")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "抓取网页时遇到问题。", "url_fetch_failed", e)
        }
    }

    private fun extractReadableText(html: String): String {
        var text = html
        text = Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE).replace(text, " ")
        text = Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE).replace(text, " ")
        text = Regex("<head[^>]*>[\\s\\S]*?</head>", RegexOption.IGNORE_CASE).replace(text, " ")
        text = Regex("<(br|p|div|li|tr|h[1-6])[^>]*/?>", RegexOption.IGNORE_CASE).replace(text, "\n")
        text = Regex("<[^>]+>").replace(text, "")
        text = text
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        text = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return text
    }

    private fun charsetFor(name: String): java.nio.charset.Charset = try {
        java.nio.charset.Charset.forName(name)
    } catch (_: Throwable) {
        Charsets.UTF_8
    }

    /**
     * #15 修复：流式读取输入流并限制最大读取字节数，超过即截断。
     *
     * 替代 [BufferedReader.readText]，避免将整个 HTTP 响应一次性读入内存。
     * 按字节累计读取，达到 [maxBytes] 后停止读取并返回已读部分解码后的字符串。
     * 截断发生在字节边界，多字节字符可能被截半，解码时末尾会出现替换字符，
     * 对"只取正文摘要"的场景可接受。
     */
    private fun readLimitedString(
        stream: java.io.InputStream,
        charset: java.nio.charset.Charset,
        maxBytes: Int,
    ): String {
        val baos = java.io.ByteArrayOutputStream(maxBytes.coerceAtMost(64 * 1024))
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read < 0) break
            if (total + read > maxBytes) {
                baos.write(buf, 0, maxBytes - total)
                break
            }
            baos.write(buf, 0, read)
            total += read
        }
        return baos.toString(charset)
    }

    /**
     * P2 修复：从 Content-Type 头（如 "text/html; charset=GBK"）解析出字符集。
     * [java.net.HttpURLConnection.getContentType] 返回的是 String，没有现成的 charset() 方法，
     * 这里用正则提取 `charset=xxx` 段，无法解析时返回 null（调用方回退 UTF-8）。
     */
    private fun String?.charset(): Charset? {
        if (this == null) return null
        val match = Regex("charset=([\\w\\-]+)", RegexOption.IGNORE_CASE).find(this) ?: return null
        return runCatching { Charset.forName(match.groupValues[1]) }.getOrNull()
    }
}

// ─────────────────────────────────────────────────────────────
//  文件编码检测（v147+ CSV 乱码修复）
// ─────────────────────────────────────────────────────────────

/**
 * 检测文件编码，解决中文 CSV/文本乱码问题。
 *
 * 背景：Windows 下 Excel 导出的 CSV 默认是 GBK 编码，但 [FileReadTool] 和
 * [com.zaijian.zhoumuyun.data.agent.TableExportTool] 原来硬编码用 UTF-8 读取，
 * 导致中文内容变成乱码（AI 看到"鏉傞繝鍧?”之类的 GBK 字节被 UTF-8 误解码的产物）。
 *
 * v148 修复（4096 字节采样截断误判 bug）：旧实现"读前 4096 字节按 UTF-8 解码，
 * 出现 U+FFFD 替换字符就判定 GBK"。问题在于 4096 是硬截断点——如果正好切在一个
 * 多字节 UTF-8 字符（中文字符是 3 字节）中间，被切断的那个字符解码时必然出现
 * U+FFFD，但这不代表文件真的不是 UTF-8，只是采样点切得不巧。只要 md/txt/csv 等
 * 文本文件超过 4KB 且中文内容较多，大概率（约 2/3 概率）会被误判成 GBK，导致
 * 全篇乱码。[FilePreviewParser]（预览）和本文件的 [FileReadTool]（agent 读取）
 * 共用这个函数，一旦误判两边同时乱码。
 *
 * 修复方法：改用真正的流式 [java.nio.charset.CharsetDecoder]，并把 endOfInput
 * 设为 false——这样解码器会把"样本末尾看起来不完整的多字节序列"当成正常的
 * "数据还没读完"（underflow），而不是"编码错误"，就不会再被采样边界坑。
 *
 * 检测策略（按优先级）：
 * 1. **BOM 检测**：前 3 字节 `EF BB BF` → UTF-8 BOM；前 2 字节 `FF FE`/`FE FF` → UTF-16
 * 2. **流式 UTF-8 校验**：无 BOM 时，用 CharsetDecoder（非 endOfInput）校验样本
 * 3. **回退 UTF-8**：校验通过 → 判定为 UTF-8；否则判定为 GBK
 *
 * @return 检测到的 [Charset]（UTF-8 / GBK / UTF-16）
 */
fun detectFileCharset(file: java.io.File): Charset {
    if (!file.exists() || file.length() == 0L) return Charsets.UTF_8

    return try {
        file.inputStream().use { fis ->
            val sample = ByteArray(4096)
            val sampleLen = fis.read(sample)
            detectCharsetFromBytes(sample, sampleLen.coerceAtLeast(0))
        }
    } catch (_: Throwable) {
        Charsets.UTF_8  // 检测失败时安全回退
    }
}

/**
 * 从内存中的字节内容检测编码，逻辑与 [detectFileCharset] 完全一致，供只有
 * `InputStream`/`ByteArray`（没有 [java.io.File] 对象）的场景复用——例如
 * [com.zaijian.zhoumuyun.data.repository.ProjectRepository.importFile] 从
 * `ContentResolver` 拿到的文件选择器 `InputStream`，无法像 [detectFileCharset]
 * 那样直接 seek 文件。避免出现"同一个乱码 bug 只在部分入口修了一半"的情况。
 *
 * @param bytes 文件的原始字节内容（或至少包含开头一段的字节数组）
 * @param len 实际有效字节数（默认整个数组）；只会取其中前 4096 字节做采样
 */
fun detectCharsetFromBytes(bytes: ByteArray, len: Int = bytes.size): Charset {
    if (len <= 0) return Charsets.UTF_8

    // 1. BOM 检测
    if (len >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return Charsets.UTF_8  // UTF-8 BOM
    }
    if (len >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return Charset.forName("UTF-16LE")  // UTF-16 LE BOM
    }
    if (len >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return Charset.forName("UTF-16BE")  // UTF-16 BE BOM
    }

    // 2. 流式 UTF-8 校验（采样最多前 4096 字节，与旧逻辑采样量保持一致）
    val sampleLen = minOf(len, 4096)
    return if (isValidUtf8Sample(bytes, sampleLen)) {
        Charsets.UTF_8
    } else {
        // 不是合法 UTF-8 → 大概率是 GBK（中文 Windows 默认编码）
        try {
            Charset.forName("GBK")
        } catch (_: Throwable) {
            Charsets.UTF_8  // GBK 不可用时回退
        }
    }
}

/**
 * 用真正的流式 CharsetDecoder 校验样本是否合法 UTF-8。
 * 关键：endOfInput = false —— 把样本末尾"看似不完整"的多字节序列当作
 * underflow（数据不够，不是错误），避免采样截断点切在字符中间导致的误判。
 */
private fun isValidUtf8Sample(bytes: ByteArray, len: Int): Boolean {
    val decoder = Charsets.UTF_8.newDecoder().apply {
        onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    }
    val input = java.nio.ByteBuffer.wrap(bytes, 0, len)
    val output = java.nio.CharBuffer.allocate(len)
    val result = decoder.decode(input, output, false)
    return !result.isError
}

// ─────────────────────────────────────────────────────────────
//  ⑦ FileExportTool
// ─────────────────────────────────────────────────────────────

/**
 * 文件生成与导出工具（Phase 18）。
 *
 * 标签格式：
 *   <tool:file_export name="周报草稿.md" content="文件内容"/>
 * 可选参数：
 *   format="md"（或 "txt"，默认 "md"）
 *
 * 实现：
 *   - v147（文件保险库改造）：将 content 经统一入口 [writeVaultFile] 写入
 *     vault/personal/{characterId}/ 或 vault/shared/roundtable/{rtId}/ 或
 *     vault/shared/project/（具体目录由 VaultCallContextHolder 决定）
 *   - 返回 JSON 元数据（供 ChatViewModel 解析为 ExportedFile）
 *   - UI 层（ChatScreen）在消息气泡中渲染下载卡片
 */
class FileExportTool(private val context: Context) : AgentTool {
    // P3-33 修复：单例共享，避免 DataVisTools / CreativeDocTools 各自创建独立实例
    // 修复：原代码在同一个类里写了两个 companion object（一个 public 一个 private），
    // Kotlin 每个类只允许一个 companion object，会导致编译失败。现合并为一个。
    companion object {
        @Volatile private var _instance: FileExportTool? = null
        fun getInstance(context: Context): FileExportTool =
            _instance ?: synchronized(this) {
                _instance ?: FileExportTool(context.applicationContext).also { _instance = it }
            }

        // v147：EXPORT_DIR 已废弃（落盘统一走 VaultIo），移除该常量。
        private val UNSAFE_CHARS = Regex("[/\\\\:*?\"<>|]")
    }

    override val name      = "file_export"
    override val description = "将生成的内容写入文件并落盘导出，供用户下载查看"
    override val usageNotes = "用户直接调用时 format 仅支持 md/txt，默认 md；html 是内部委托格式，供 docx_gen/pdf_export/html_gen/markdown_to_doc 等工具生成 HTML 内容时使用，不建议直接传入"
    override val paramKeys = listOf("name", "content", "format")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val rawName = params["name"]?.trim()
        val content = params["content"]
        val format  = params["format"]?.lowercase()?.trim() ?: "md"

        if (rawName.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 name 参数（文件名）")
        }
        if (content.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 content 参数（文件内容）")
        }

        val safeName = UNSAFE_CHARS.replace(rawName, "_").take(60)
        // P2 修复（批次3审查报告问题2）：原实现只认 "txt"，其他任何值（包括 "pdf" 这种
        // LLM 可能填的合理猜测）都静默落到 .md，无任何提示。改为显式白名单校验，
        // 非法值仍回退为 md（不阻断导出，容错优先），但在返回结果里明确告知发生了偏离，
        // 而不是让调用方以为 format 生效了。
        //
        // v66（1.7 P3）：把 "html" 补进合法值集合。docx_gen/pdf_export/html_gen/
        // markdown_to_doc 四个工具委托 file_export 落盘时固定传 format="html"，
        // 这是这几个工具与 file_export 之间早就存在的正常约定（内容确实是 HTML），
        // 不是 LLM 误填的非法值——此前 formatWasUnsupported 判断没跟上这个约定，
        // 导致这四个工具每次调用都会在结果里被硬塞一句"format="html"不受支持，
        // 仅支持 md/txt，已按 md 生成"的提示，而实际上文件名和内容都没有真的按
        // md 生成，这句提示本身就是错的，还可能被 LLM 转述给用户造成困惑。
        val formatWasUnsupported = format != "md" && format != "txt" && format != "html"
        val ext = when {
            // 委托调用：文件名（含后缀）理应由上层工具在 name 参数里完整给出
            // （docx_gen/pdf_export/html_gen/markdown_to_doc 目前都这么做）。
            // safeName.contains(".") 兜底正常生效；万一未来某个委托调用忘了
            // 带后缀，退化补一个 .html 兜底，好过产出完全没有扩展名的文件。
            format == "html" && safeName.contains(".") -> ""
            format == "html"        -> ".html"
            safeName.contains(".") -> ""
            format == "txt"        -> ".txt"
            else                   -> ".md"
        }
        val fileName  = "${safeName}${ext}"
        // v66（Agent附件下发方案 v2.0 · 1.7 P3）：mimeType bug 修复。
        //
        // 背景：此前只认 fileName.endsWith(".md")，其余一律落到 else 变成
        // "text/plain"。file_export 本身只产出 .md/.txt 两种，这个判断在它
        // 自己的调用范围内没问题；但 docx_gen/pdf_export/html_gen/
        // markdown_to_doc 这些"委托 file_export 落盘"的上层工具会传入
        // 非 md/txt 的文件名（如 "标题.docx"、"标题.pdf.html"、"标题.html"），
        // 这些调用点传的 format 参数固定是 "html"（内容确实是 HTML），
        // 但 mimeType 判断完全不看 format，只看文件名末尾是不是 ".md"——
        // 于是这些文件全部被错误标成 text/plain。
        //
        // docx_gen 这一路受害最深：文件名以 ".docx" 结尾（因为 safeName
        // 含"."，file_export 不会再追加 .md/.txt 后缀），内容其实是 HTML，
        // mimeType 却是 text/plain——三者互相矛盾，用真正的 Word/WPS
        // 打开这个"假.docx"大概率报错或乱码；FileExportCard 卡片上虽然
        // 靠 1.3 的 openHint 提示了"需用浏览器/WPS打开另存"，但那只是
        // 产品层面的规避说明，没有解决 mimeType 本身撒谎的问题——如果
        // 分享出去的 Intent 依赖 mimeType 做类型匹配（比如系统分享面板
        // 筛选"可以打开这个类型的App"），错误的 text/plain 会让能正确
        // 处理 HTML 的 App（浏览器）被排除在候选之外。
        //
        // 修复：按文件名真实后缀分派 mimeType，不再只认 .md。这里用
        // format 参数辅助判断——file_export 目前只有两种调用来源：
        //   1. 直接调用（format=md/txt，文件名以.md/.txt结尾）——行为不变
        //   2. 上层工具委托调用（format 固定传 "html"，文件名可能是
        //      .docx/.pdf.html/.html 等各种"表演性"后缀，但内容确实是 HTML）
        // 用 format 而不是纯后缀嗅探是因为后缀本身就不可靠（.docx 后缀
        // 但内容是 HTML 正是这个 bug 的起因），format 参数是调用方对
        // "这份 content 到底是什么" 的显式声明，比后缀猜测更可信。
        val mimeType = when {
            format == "html"        -> "text/html"
            fileName.endsWith(".md") -> "text/markdown"
            else                     -> "text/plain"
        }

        try {
            // v147（文件保险库改造）：落盘改走统一入口 [writeVaultFile]，
            // 由 VaultIo 依据 VaultCallContextHolder 决定写入
            // vault/personal/{characterId}/ 或 vault/shared/roundtable/{rtId}/ 或
            // vault/shared/project/。FileExportTool 自身不再感知目录与角色身份。
            // fileName 已是本函数上方计算好的安全人读名（含后缀），直接传给
            // writeVaultFile（它不会再二次 sanitize/截断，避免截掉扩展名）。
            val metaJson = writeVaultFile(context, fileName, content, mimeType)
            val sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong()

            // 根因修复（formatNotice 破坏 JSON 解析）：
            // 原 content 格式为 "前缀文字\n$metaJson$formatNotice"——当 format 不受
            // 支持时 formatNotice 追加在 metaJson 之后，末尾字符变成 ']' 而非 '}'，
            // 导致 ExportedFileMeta.findTrailingJsonObjectStart 因末尾不是 '}' 直接
            // 返回 null，文件卡片不渲染。文件已落盘但用户看不到下载入口。
            // 修复：formatNotice 移到 metaJson 之前（前缀区），保证 metaJson 永远在
            // content 最末尾，与 extractExportedFileJson "从末尾定位 JSON" 的设计契约一致。
            val formatNotice = if (formatWasUnsupported) {
                "[提示：format=\"$format\" 不受支持，仅支持 md/txt，已按 md 生成]\n"
            } else ""

            ToolResult(
                toolName = name,
                success  = true,
                content  = "$formatNotice文件已生成：$fileName（${formatSize(sizeBytes)}）\n$metaJson",
                userHint = "正在生成文件…",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 协程取消必须重新抛出，不能当成业务失败吞掉
        } catch (e: Throwable) {
            // 与 excel_gen 同批修复：catch Throwable 而非 Exception。
            // file_export 是 docx_gen/pdf_export/html_gen/markdown_to_doc 等
            // 工具的公共落盘出口，一旦这里被 Error 击穿，受影响面最广。
            toolFailure(name, "生成文件时出现问题。", "file_export_failed", e)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024        -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else                -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    }
}

// ─────────────────────────────────────────────────────────────
//  ⑧ ArchiveExportTool — 压缩包打包（1.4，Agent附件下发方案 v2.0 P2）
// ─────────────────────────────────────────────────────────────

/**
 * 压缩包打包工具。
 *
 * 标签格式：<tool:zip_export names="{逗号分隔的已导出文件名}"/>
 *
 * 把已经导出到文件保险库（vault/）下的多个文件打包成一个 zip，供用户一次性
 * 下载/分享——不重复实现"导出"，只做"打包"，前置文件必须已经由 file_export /
 * excel_gen / pptx_gen / docx_gen / pdf_export 等工具生成过。
 *
 * v147（文件保险库改造）：源文件不再固定从 filesDir/exports/ 找，而是从
 * "当前作用域目录 + 项目共享目录"两个可见范围内搜索（尊重权限边界——私聊
 * 场景只看得到当前角色私库 + 项目共享，看不到别的角色私库）。zip 产物也走
 * [writeVaultStream] 落到当前作用域目录。
 *
 * names 匹配用 endsWith 而非精确匹配：vault 下的真实文件名带前缀
 * （file_export 是 "{timestamp}_{name}"，excel_gen/pptx_gen 是
 * "{timestamp}_{uuid8}_{name}"，两种命名风格都在项目里并存），LLM/用户只会
 * 提供原始文件名，用 endsWith 兼容两种前缀模式。
 *
 * metaJson 走 1.1 打通的 extractExportedFileJson 同一条通用识别链路，不需要改 orchestrator。
 */
class ArchiveExportTool(private val context: Context) : AgentTool {

    override val name = "zip_export"
    override val description = "把已导出的多个文件打包成zip供用户一次性下载"
    override val paramKeys = listOf("names")
    override val usageNotes = "names 为逗号分隔的已导出文件名列表，如 file1.pdf,file2.docx"

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val names = params["names"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (names.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 names 参数（逗号分隔的文件名）")
        }

        // v147：在当前可见范围内搜索源文件。resolveVaultTargetDir 返回当前作用域目录
        // （私聊=角色私库，圆桌=圆桌共享）；projectVaultDir 是所有角色可见的项目共享。
        // 两者合并去重，保证用户能打包自己有权访问的全部文件。
        val searchDirs = linkedSetOf(resolveVaultTargetDir(context), projectVaultDir(context))
        val allVaultFiles = searchDirs
            .filter { it.exists() }
            .flatMap { dir -> dir.listFiles { f -> f.isFile }?.toList().orEmpty() }

        // Fix-6：精确追踪哪些请求文件找到了、哪些没找到。
        // 原实现用 endsWith 模糊匹配且不报告缺失项，导致 LLM 不知道哪个文件不存在，
        // 可能声称"压缩包已发送"但实际打包内容不完整或直接失败。
        val matched = mutableListOf<java.io.File>()
        val missing = mutableListOf<String>()
        for (name in names) {
            val found = allVaultFiles.filter { f -> f.name.endsWith(name) }
            if (found.isEmpty()) {
                missing.add(name)
            } else {
                matched.addAll(found)
            }
        }

        if (matched.isEmpty()) {
            // Fix-6：友好提示——告诉 LLM 哪些文件没找到 + 建议先生成。
            // 这让 LLM 能如实告知用户"文件还没生成，需要先调用对应工具"，
            // 而不是说"压缩包已发送"（实际什么都没打包）。
            val hint = "未找到匹配的已导出文件：${names.joinToString()}。" +
                "这些文件可能尚未生成。请先使用对应的工具（如 pdf_export/pptx_gen/excel_gen/" +
                "html_gen/file_export 等）生成文件，再调用 zip_export 打包。"
            return@withContext ToolResult(name, false, hint, "未找到源文件：${names.joinToString()}")
        }

        try {
            // 走统一落盘入口 [writeVaultStream]：命名风格（时间戳+短随机后缀）与原实现一致，
            // 由 VaultIo 决定写入哪个 vault 目录，metaJson 结构与 file_export 对齐。
            val humanName = "导出合集.zip"
            val metaJson = writeVaultStream(context, humanName, "application/zip") { out ->
                java.util.zip.ZipOutputStream(out).use { zos ->
                    matched.forEach { f ->
                        zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            // metaJson 里带了 sizeBytes，但为打 success 消息要个 Long，直接解析一下。
            val sizeBytes = JSONObject(metaJson).optLong("sizeBytes", 0L)
            // Fix-6：如果部分文件缺失，在成功消息中注明，让 LLM 知道打包不完整。
            val missingNote = if (missing.isNotEmpty()) {
                "⚠ 注意：以下请求的文件未找到，未包含在压缩包中：${missing.joinToString()}。\n"
            } else ""
            ToolResult(
                toolName = name,
                success  = true,
                content  = "${missingNote}压缩包已生成：$humanName（${formatZipSize(sizeBytes)}，含 ${matched.size} 个文件）\n$metaJson",
                userHint = "正在打包…",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 协程取消必须重新抛出，不能当成业务失败吞掉
        } catch (e: Throwable) {
            // 与 excel_gen 同批修复：catch Throwable 而非 Exception。
            toolFailure(name, "打包失败。", "archive_export_failed", e)
        }
    }

    private fun formatZipSize(bytes: Long): String = when {
        bytes < 1024        -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else                -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    }
}

// ─────────────────────────────────────────────────────────────
//  诊断日志导出工具（v147+ vault FileProvider 修复同步引入）
// ─────────────────────────────────────────────────────────────

/**
 * 导出 Agent 行为诊断日志。
 *
 * 当用户反馈"工具说存了但没文件"或"点文件没反应"时，让 Agent 调用本工具
 * 把 `filesDir/logs/agent_log.txt` 复制成一份可下载的文件，用户分享给开发者排查。
 *
 * 日志内容（由 [com.zaijian.zhoumuyun.util.AgentLog] 写入）：
 * - 所有工具调用的开始/成功/失败/超时（含 params 和 result 摘要）
 * - LLM 调用失败
 * - 工具未注册/被禁用
 * - 未捕获异常堆栈
 *
 * 走 [writeVaultStream] 落盘，与 [FileExportTool] 同款 metaJson，UI 层渲染成
 * [FileExportCard] 供用户下载。
 */
class DiagLogExportTool(private val context: Context) : AgentTool {

    override val name = "diag_export_log"
    override val description = "导出诊断日志，用于排查AI行为问题或用户反馈异常时"
    override val usageNotes = "包含Agent工具调用记录、异常堆栈等"
    override val paramKeys = emptyList<String>()

    private companion object {
        // #38 修复：diag_export_log 将敏感信息写入日志文件。
        // 导出日志时对敏感信息做脱敏处理（正则替换为 ***），避免用户把含密码/邮箱
        // 的日志分享给开发者时泄露隐私。
        // 邮箱地址：标准 email 格式
        val EMAIL_REGEX = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        // 密码/密钥字段：password=xxx / "pwd":"xxx" / api_key=xxx 等键值对，仅替换值部分
        val SENSITIVE_FIELD_REGEX = Regex(
            """(?i)((?:password|passwd|pwd|secret|token|api[_\-]?key|access[_\-]?token|authorization)\s*["']?\s*[:=]\s*["']?)([^"'\s,};]+)"""
        )
    }

    /** #38 修复：对日志文本中的敏感信息做脱敏，邮箱整体替换为 ***，密码字段值替换为 *** */
    private fun redactSensitiveInfo(text: String): String {
        var result = EMAIL_REGEX.replace(text, "***")
        result = SENSITIVE_FIELD_REGEX.replace(result) { mr ->
            mr.groupValues[1] + "***"
        }
        return result
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val logFile = com.zaijian.zhoumuyun.util.AgentLog.exportLog(context)
            if (logFile == null || !logFile.exists() || logFile.length() == 0L) {
                // 修复（diag_export_log 说发了但没落盘）：
                // 此前 success=true 让 LLM 误以为"导出成功"，回复用户"已发送"，
                // 但 content 里没有 metaJson，extractExportedFileJson 返回 null，
                // 文件卡片不显示——用户看到"说了发了但没文件"。
                // 改为 success=false，让 LLM 明确知道没有导出成功，不会误导用户。
                return@withContext ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "[诊断日志为空，无法导出。可能原因：应用刚启动日志尚未生成，" +
                               "或问题发生在 AgentLog 覆盖范围之外（如 UI 层或数据库层）。]",
                    error    = "诊断日志为空",
                )
            }

            // 复制到 vault 目录，生成可下载的文件
            val stamp = TimeFormatUtils.formatFileStamp(System.currentTimeMillis())
            val humanName = "诊断日志_$stamp.txt"
            // #38 修复：导出前对日志内容做脱敏（邮箱、密码字段替换为 ***），
            // 避免用户分享日志时泄露敏感信息。原实现直接 copyTo 落盘，不做任何处理。
            val redactedContent = redactSensitiveInfo(logFile.readText(Charsets.UTF_8))
            val metaJson = writeVaultStream(context, humanName, "text/plain") { out ->
                out.write(redactedContent.toByteArray(Charsets.UTF_8))
            }

            val sizeBytes = JSONObject(metaJson).optLong("sizeBytes", 0L)
            val sizeKb = sizeBytes / 1024.0
            ToolResult(
                toolName = name,
                success  = true,
                content  = "诊断日志已导出：$humanName（${String.format("%.1f", sizeKb)} KB）\n" +
                           "日志包含 Agent 工具调用记录、异常堆栈等。他下载后可分享给开发者排查。\n" +
                           "$metaJson",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 协程取消必须重新抛出，不能当成业务失败吞掉
        } catch (e: Throwable) {
            // 与 excel_gen 同批修复：catch Throwable 而非 Exception——诊断日志
            // 工具本身也不该因为一次意外的 Error 而让整轮回复静默终止。
            toolFailure(name, "导出诊断日志失败。", "diag_log_export_failed", e)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────────────────────

/**
 * 注册所有内置网络/IO工具（8个）。
 * 在 ZaijianApp.onCreate() 中调用，ProviderManager.init() 之后。
 */
fun AgentToolRegistry.registerBuiltinTools(context: Context) {
    registerAll(
        WebSearchTool(),
        DateTimeTool(),
        TranslateTool(),
        FileReadTool(context),
        WeatherTool(),
        UrlFetchTool(),
        FileExportTool.getInstance(context),
        ArchiveExportTool(context),
        DiagLogExportTool(context),
    )
}
