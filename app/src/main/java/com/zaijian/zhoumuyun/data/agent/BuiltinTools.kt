package com.zaijian.zhoumuyun.data.agent

import android.content.Context
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Phase 13 · Tool Call Engine（Prompt-based Dispatch）
 *
 * ═══════════════════════════════════════════════════════════════
 * BuiltinTools.kt — 网络/IO 基础工具（7个）
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
                    ToolResult(
                        toolName = name,
                        success  = result.isNotEmpty(),
                        content  = result.ifEmpty { "未找到关于「$query」的相关信息。" },
                        userHint = "正在搜索「$query」…",
                    )
                } finally {
                    conn.disconnect()
                }

            } catch (e: Exception) {
                ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "搜索「$query」时遇到问题，请稍后再试。",
                    error    = e.message,
                    userHint = "搜索遇到问题",
                )
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
    override val paramKeys = listOf("format")

    // 修复（第3窗口审查报告问题4）：统一包裹 withContext(Dispatchers.IO)，
    // 与项目内其他 AgentTool（CalculatorTool/UnitConvertTool/CountdownTool 等）保持契约一致，
    // 即便本工具当前实现是纯本地计算，不依赖此调度也不产生功能性影响。
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val format = params["format"]?.trim()?.lowercase() ?: "full"
        val now = Calendar.getInstance()
        val locale = Locale.CHINESE

        val result = when (format) {
            "date" -> {
                val sdf = SimpleDateFormat("yyyy年M月d日 EEEE", locale)
                sdf.format(now.time)
            }
            "time" -> {
                val sdf = SimpleDateFormat("HH:mm:ss", locale)
                sdf.format(now.time)
            }
            "week" -> {
                val sdf = SimpleDateFormat("EEEE", locale)
                sdf.format(now.time)
            }
            "year" -> {
                now.get(Calendar.YEAR).toString()
            }
            "timestamp" -> {
                (now.timeInMillis / 1000L).toString()
            }
            else -> {
                val dateSdf = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm:ss", locale)
                val tz = now.timeZone
                val offsetHours = tz.rawOffset / 3_600_000
                val tzStr = if (offsetHours >= 0) "UTC+$offsetHours" else "UTC$offsetHours"
                "${dateSdf.format(now.time)} ($tzStr)"
            }
        }

        ToolResult(
            toolName = name,
            success  = true,
            content  = "[当前时间]\n$result",
        )
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
        val target = params["target"]?.trim()?.lowercase() ?: "zh"
        val source = params["source"]?.trim()?.lowercase()?.let {
            if (it == "auto") null else it
        }

        val truncated = if (text.length > MAX_TEXT_LENGTH) text.take(MAX_TEXT_LENGTH) else text

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
                    return@withContext ToolResult(
                        name, false, "未能获取翻译结果，请稍后再试。",
                    )
                }

                val sourceName = source?.let { LANG_NAMES[it] } ?: "自动检测"
                val targetName = LANG_NAMES[target] ?: target.uppercase()

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[翻译结果: $sourceName → $targetName]\n$translated",
                    userHint = "正在翻译…",
                )

            } catch (e: Exception) {
                ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "翻译时遇到问题：${e.message}",
                    error    = e.message,
                )
            } finally {
                conn?.disconnect()
            }
        }
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

        if (path.contains("../") || path.contains("..\\")) {
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

                val lines = file.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.take(maxLines).toList()
                }
                val totalLineCount = file.bufferedReader(Charsets.UTF_8).useLines { it.count() }

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

            } catch (e: Exception) {
                ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "读取文件时遇到问题：${e.message}",
                    error    = e.message,
                )
            }
        }
    }

    private suspend fun readZipContents(zipFile: java.io.File, maxLines: Int): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val zip = java.util.zip.ZipFile(zipFile)
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
                        val lines = zip.getInputStream(entry)
                            .bufferedReader(Charsets.UTF_8)
                            .useLines { it.take(maxLines).toList() }

                        val remaining = MAX_CHARS - totalChars
                        val fileContent = lines.joinToString("\n").take(remaining.coerceAtLeast(0))
                        contentBuilder.append(fileContent)
                        totalChars += fileContent.length
                    } catch (_: Exception) {
                        contentBuilder.appendLine("（无法读取该文件内容）")
                    }
                }

                zip.close()

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = contentBuilder.toString().take(MAX_CHARS),
                    userHint = "正在分析 ZIP 文件内容…",
                )
            } catch (e: Exception) {
                ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "无法解析 ZIP 文件：${e.message?.take(80)}",
                    error    = e.message,
                )
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
    override val paramKeys = listOf("city", "unit")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val city = params["city"]?.trim() ?: return@withContext ToolResult(name, false, "请告诉我要查询哪个城市的天气。")
        val useFahrenheit = params["unit"]?.lowercase() == "fahrenheit"

        try {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=1&language=zh&format=json"
            val geoJson = fetchUrl(geoUrl) ?: return@withContext ToolResult(name, false, "无法获取「$city」的位置信息，稍后再试？")
            val geoObj   = JSONObject(geoJson)
            val results  = geoObj.optJSONArray("results") ?: return@withContext ToolResult(name, false, "没有找到「$city」，试试输入更精确的城市名？")
            if (results.length() == 0) return@withContext ToolResult(name, false, "搜索城市未返回结果")
            val loc      = results.getJSONObject(0)
            val lat      = loc.getDouble("latitude")
            val lon      = loc.getDouble("longitude")
            val cityName = loc.optString("name", city)

            val tempUnit = if (useFahrenheit) "fahrenheit" else "celsius"
            val wxUrl    = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
                "&temperature_unit=$tempUnit&wind_speed_unit=kmh&timezone=auto"
            val wxJson   = fetchUrl(wxUrl) ?: return@withContext ToolResult(name, false, "获取天气数据时遇到问题，稍后再试？")
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

        } catch (e: Exception) {
            ToolResult(name, false, "查询天气时遇到点问题：${e.message?.take(80)}", e.message)
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
        // 同 UrlFetchTool 的 P1-8-2 修复：conn 声明在内层 try 外，finally 保证 disconnect
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout    = 8000
        conn.requestMethod  = "GET"
        try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) { null }
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
    override val paramKeys = listOf("url", "max_chars")

    private companion object {
        const val DEFAULT_MAX_CHARS = 3_000
        const val MAX_CHARS_LIMIT   = 8_000
        const val CONNECT_TIMEOUT   = 8_000
        const val READ_TIMEOUT      = 12_000
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val url = params["url"]?.trim()
        if (url.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 url 参数")
        }
        val maxChars = params["max_chars"]?.toIntOrNull()
            ?.coerceIn(100, MAX_CHARS_LIMIT) ?: DEFAULT_MAX_CHARS

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
                    return@withContext ToolResult(
                        name, false,
                        "无法访问该网页（HTTP $responseCode）。",
                    )
                }

                // P2 修复：从 Content-Type header 解析字符集，而非误用 Content-Encoding（后者是 gzip/deflate 压缩编码）
                val charset = conn.contentType?.charset()?.name() ?: "UTF-8"
                val html = conn.inputStream.bufferedReader(charsetFor(charset)).readText()
                val text = extractReadableText(html).take(maxChars)

                if (text.isBlank()) {
                    return@withContext ToolResult(name, false, "网页内容为空或无法提取正文。")
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
            ToolResult(name, false, "无法连接网络，请检查网络设置后重试。")
        } catch (e: java.net.SocketTimeoutException) {
            ToolResult(name, false, "网页响应超时，稍后再试。")
        } catch (e: Exception) {
            ToolResult(name, false, "抓取网页时遇到问题：${e.message?.take(80)}", e.message)
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
    } catch (_: Exception) {
        Charsets.UTF_8
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
 *   - 将 content 写入 filesDir/exports/{timestamp}_{name} 文件
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

        private const val EXPORT_DIR = "exports"
        private val UNSAFE_CHARS = Regex("[/\\\\:*?\"<>|]")
    }

    override val name      = "file_export"
    override val description = "将生成的内容写入文件并落盘导出，供用户下载查看"
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
        val ext = when {
            safeName.contains(".") -> ""
            format == "txt"        -> ".txt"
            else                   -> ".md"
        }
        val fileName  = "${safeName}${ext}"
        val mimeType  = if (fileName.endsWith(".md")) "text/markdown" else "text/plain"

        try {
            val exportDir = java.io.File(context.filesDir, EXPORT_DIR).also { it.mkdirs() }
            val timestamp = System.currentTimeMillis()
            val file = java.io.File(exportDir, "${timestamp}_${fileName}")

            file.writeText(content, Charsets.UTF_8)

            val sizeBytes = file.length()

            val metaJson = org.json.JSONObject().apply {
                put("fileName",     fileName)
                put("mimeType",     mimeType)
                put("sizeBytes",    sizeBytes)
                put("absolutePath", file.absolutePath)
            }.toString()

            ToolResult(
                toolName = name,
                success  = true,
                content  = "文件已生成：$fileName（${formatSize(sizeBytes)}）\n$metaJson",
                userHint = "正在生成文件…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "生成文件时出现问题：${e.message?.take(80)}", e.message)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024        -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else                -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    }
}

// ─────────────────────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────────────────────

/**
 * 注册所有内置网络/IO工具（7个）。
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
    )
}
