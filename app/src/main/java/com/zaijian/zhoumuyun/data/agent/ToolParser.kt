package com.zaijian.zhoumuyun.data.agent

/**
 * Phase 13 · Tool Call Engine（Prompt-based Dispatch）
 *
 * ═══════════════════════════════════════════════════════════════
 * 文件 2/4 — ToolParser.kt
 * 从 LLM 流式输出中检测并解析工具标签
 * ═══════════════════════════════════════════════════════════════
 *
 * 工具标签格式（LLM 输出）：
 *   <tool:tool_name key1="value1" key2="value2"/>
 *
 * 示例：
 *   <tool:web_search query="今天北京天气"/>
 *   <tool:calculator expr="365 * 24 * 3600"/>
 *   <tool:datetime format="full"/>
 *   <tool:translate text="Good morning" target="zh" source="en"/>
 *   <tool:file_read path="readme.txt"/>
 *
 * ── 流式处理策略 ────────────────────────────────────────────────
 *
 * LLM 流式输出时，工具标签可能被拆分在多个 delta 中，例如：
 *   delta 1: "今天天气怎样？让我查一下 <tool:web_"
 *   delta 2: "search query=\"北京今天\"/>"
 *   delta 3: " 稍等片刻……"
 *
 * Phase 28 说明：
 * 新增的 27 个工具名无需修改 ToolParser，TAG_PATTERN 已支持所有符合
 * [a-z_][a-z0-9_]* 命名规范的工具名，自动解析。
 * 工具名过滤由 AgentToolRegistry.get(name) 的 null 检查完成（未注册即跳过），
 * ToolCallInterceptor 中不存在也不需要 KNOWN_TOOLS 白名单。
 *
 * ToolParser 维护一个内部缓冲区（[buffer]），每次 [feed] 时：
 *   1. 将 delta 追加到 buffer
 *   2. 扫描 buffer，提取已完整的标签（以 `/>` 结尾）
 *   3. 返回两部分：
 *      a. [ParseResult.cleanText] —— 去掉工具标签后的纯文本，可直接展示给用户
 *      b. [ParseResult.detectedCalls] —— 本次 feed 新发现的完整工具调用列表
 *
 * ── 边界情况处理 ────────────────────────────────────────────────
 *
 * ① 标签跨多个 delta：buffer 积累，等 `/>` 到达后再解析
 * ② 标签中含转义引号（\\"）：属性值提取支持转义
 * ③ 非工具标签（如 <br/>）：正则前缀匹配 `<tool:` 过滤
 * ④ 标签格式错误（缺属性引号等）：返回 null，跳过该标签，不崩溃
 * ⑤ 嵌套标签（罕见）：按顺序匹配最早出现的完整标签
 */

// ─────────────────────────────────────────────────────────────
//  数据模型
// ─────────────────────────────────────────────────────────────

/**
 * 解析出的单次工具调用。
 *
 * @param toolName  工具名称，如 "web_search"
 * @param params    参数 Map，key/value 均为字符串；value 已去除外层引号和转义
 * @param rawTag    原始标签文本（用于从流式输出中精准移除）
 */
data class ToolCall(
    val toolName: String,
    val params: Map<String, String>,
    val rawTag: String,
)

/**
 * 单次 [ToolParser.feed] 的返回结果。
 *
 * @param cleanText    去掉所有完整工具标签后的纯文本（可直接累积展示）
 * @param detectedCalls 本次新发现的完整工具调用列表（空列表 = 无工具调用）
 * @param hasPendingTag 是否仍有未完成的标签在 buffer 中（用于 UI loading 提示）
 */
data class ParseResult(
    val cleanText: String,
    val detectedCalls: List<ToolCall>,
    val hasPendingTag: Boolean,
)

// ─────────────────────────────────────────────────────────────
//  解析器
// ─────────────────────────────────────────────────────────────

/**
 * 流式工具标签解析器。
 *
 * 每个 ChatViewModel 流式会话对应一个独立实例（不跨会话复用）。
 * 线程模型：所有方法必须在同一协程中调用（非线程安全）。
 *
 * 典型用法：
 * ```kotlin
 * val parser = ToolParser()
 * provider.chat(...).collect { delta ->
 *     val result = parser.feed(delta)
 *     // 展示纯文本
 *     displayText += result.cleanText
 *     // 分发工具调用
 *     result.detectedCalls.forEach { call -> executeToolCall(call) }
 * }
 * // 流结束后处理剩余 buffer
 * val finalResult = parser.flush()
 * ```
 */
class ToolParser {

    // 内部 buffer：积累未完整的标签片段
    private val buffer = StringBuilder()

    companion object {
        // 匹配完整工具标签：<tool:名称 属性.../> 或 <tool:名称/>（无属性）
        // group(1) = 工具名, group(2) = 属性字符串（可为空）
        // 属性段使用 (?:[^"'>]|"\{[^}]*\}"|"[^"]*"|'[^']*')* 而非 [^>]*：
        //   - "\{[^}]*\}" 分支专门兼容"值是未转义 JSON"的场景（如
        //     params="{"to_character_id":"user"}"），花括号内允许出现裸引号；
        //     该分支必须排在 "[^"]*" 之前，避免被后者提前截断到第一个裸引号。
        //   - "[^"]*" / '[^']*' 分支处理普通引号包裹属性值。
        // 这样属性值内部包含 > 字符、或包含未转义裸引号的 JSON 时都不会被提前截断。
        // 根因排查结论：本正则本身在"一次性给到完整字符串"时就能正确匹配（回溯能凑出解），
        // 真正导致截图 bug 的是流式分片场景下 buffer 前缀判定的缺陷，已在
        // findPendingTagStart() 中修复；这里的 JSON 分支是同一问题的第二层防御。
        private val TAG_PATTERN = Regex(
            """<tool:([a-z_][a-z0-9_]*)(\s(?:[^"'>]|"\{[^}]*\}"|"[^"]*"|'[^']*')*)?\s*/>""",
            RegexOption.DOT_MATCHES_ALL,
        )

        // 匹配单个属性：key="value" 或 key='value'（value 中允许 \" 或 \' 转义）
        // group(1) = key
        // group(2) = 双引号包裹的 value（含转义），group(3) = 单引号包裹的 value（含转义）
        // 修复：原仅支持双引号，LLM 偶尔输出单引号属性值时静默跳过导致参数丢失。
        private val ATTR_PATTERN = Regex(
            """([a-z_][a-z0-9_]*)=(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)')""",
        )

        // 用于在属性字符串中定位 key=" 的起点，配合 findBalancedJsonEnd() 做花括号配平。
        // 只在值的第一个字符是 '{' 时才会触发 JSON 分支，否则交还给 ATTR_PATTERN 处理。
        private val KEY_EQUALS_QUOTE_PATTERN = Regex("""([a-z_][a-z0-9_]*)=\"""")

        // 标签开始的特征前缀，用于判断 buffer 是否在等待标签完成
        private const val TAG_PREFIX = "<tool:"

        /**
         * S-fix（第二轮，修复嵌套 JSON 丢失问题）：
         *
         * 最初的方案是用正则 `"(\{[^}]*\})"` 摘取未转义 JSON 属性值，但 `[^}]*` 只能
         * 匹配到**第一个** `}` 就停，遇到嵌套对象（如
         * `params="{"a":"b","nested":{"x":"y"}}"`）时完全无法命中，
         * 导致 `params` 静默丢失、schedule_create 拿到空参数——这是正则表达式的
         * 结构性局限：花括号配平本质是上下文无关文法，不是正则（不支持无限深度嵌套）
         * 能表达的。
         *
         * 改用手写扫描做真正的花括号配平（支持任意深度嵌套），对每个 `key="{` 起点，
         * 逐字符前进并维护 depth 计数器，直到 depth 归零才认为 JSON 值结束。
         *
         * @return 从 [searchFrom] 开始，值内容以 '{' 结尾配平后的结束下标（即闭合 '}'
         *         的下一个位置）；如果 [searchFrom] 处不是 '{' 或配平失败（数据不完整/
         *         格式错误），返回 -1。
         */
        private fun findBalancedJsonEnd(text: String, searchFrom: Int): Int {
            if (searchFrom >= text.length || text[searchFrom] != '{') return -1
            var depth = 0
            var i = searchFrom
            val n = text.length
            while (i < n) {
                when (text[i]) {
                    '\\' -> {
                        i += 2
                        continue
                    }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
                i++
            }
            return -1  // 配平未完成（数据不完整或格式错误）
        }

        /**
         * S-fix：查找 text 结尾处、可能是 TAG_PREFIX 部分前缀的最早起始位置。
         *
         * 根因：原实现只用 lastIndexOf(TAG_PREFIX) 判断"是否有未完成标签"，只能识别
         * "<tool:" 六个字符完整出现的情况。真实流式场景是逐 token/逐字符喂入的，
         * "<tool:" 本身也会被拆成多个 delta（例如先收到单独的 "<"，下一个 delta 才是
         * "t"）。在 "<tool:" 凑齐之前，lastIndexOf 永远返回 -1，于是 "<"、"<t"、"<to" ……
         * 这些字符会被逐个误判为"安全文本"提前吐给用户，一旦吐出就回不来了——即使后续
         * "tool:schedule_create.../>" 完整到达，也已经缺失了开头的 "<"，TAG_PATTERN
         * （强制要求 <tool: 前缀）永远无法再匹配上，整个标签退化成普通文本原样展示
         * （即截图中出现的现象）。
         *
         * 本函数额外检查 text 的每一个后缀是否是 TAG_PREFIX 的前缀（哪怕只有 1 个字
         * 符），只要成立就保留在 buffer 中继续等待，不提前输出。
         */
        private fun findPendingTagStart(text: String): Int {
            // 优先：TAG_PREFIX 完整出现过，直接沿用原逻辑
            val fullIdx = text.lastIndexOf(TAG_PREFIX)
            if (fullIdx >= 0) return fullIdx

            // 兜底：检查末尾 1..(TAG_PREFIX.length-1) 个字符是否恰好是 TAG_PREFIX 的前缀
            // 例如 text 以 "<", "<t", "<to", "<too", "<tool" 结尾时都应判定为 pending
            val maxLen = minOf(TAG_PREFIX.length - 1, text.length)
            for (len in maxLen downTo 1) {
                val suffix = text.substring(text.length - len)
                if (TAG_PREFIX.startsWith(suffix)) {
                    return text.length - len
                }
            }
            return -1
        }
    }


    // ─────────────────────────────────────────────────────────
    //  公开 API
    // ─────────────────────────────────────────────────────────

    /**
     * 喂入一个流式 delta，返回解析结果。
     *
     * @param delta  LLM 流式输出的增量文本片段
     * @return       本次解析结果
     */
    fun feed(delta: String): ParseResult {
        buffer.append(delta)
        return processBuf()
    }

    /**
     * 流式结束后调用（[OpenAICompatProvider] 的 channel.close() 之后）。
     *
     * 将 buffer 中的剩余内容作为纯文本返回（不尝试解析未完整标签）。
     * 调用后 buffer 被清空，parser 实例不应继续使用。
     */
    fun flush(): ParseResult {
        val remaining = buffer.toString()
        buffer.clear()
        // S4 修复：若剩余内容含未闭合的 <tool: 前缀（网络中断 / maxTokens 截断处），
        // 截掉该片段，避免把内部实现细节（如 `<tool:web_search query="北京`）暴露给用户。
        val pendingStart = findPendingTagStart(remaining)  // S-fix: 兜住 <tool: 部分前缀
        val safeText = if (pendingStart >= 0) remaining.substring(0, pendingStart) else remaining
        return ParseResult(
            cleanText     = safeText,
            detectedCalls = emptyList(),
            hasPendingTag = false,
        )
    }

    /**
     * 重置解析器状态（用于会话重建或单元测试）。
     */
    fun reset() {
        buffer.clear()
    }

    // ─────────────────────────────────────────────────────────
    //  内部实现
    // ─────────────────────────────────────────────────────────

    /**
     * 扫描当前 buffer，提取所有已完整的工具标签。
     *
     * 处理逻辑：
     *   1. 在 buffer 中查找所有匹配 TAG_PATTERN 的完整标签
     *   2. 将标签从 buffer 中移除（替换为空字符串）
     *   3. 将移除标签后的 buffer 内容作为 cleanText 输出
     *   4. 检查剩余 buffer 是否含未完整的 <tool: 前缀
     *
     * 注意：只处理 buffer 中 `/>` 已到达的完整标签；
     *       未完成标签（`<tool:` 已出现但 `/>` 未到）留在 buffer 中等待下一个 delta。
     *       但如果 `<tool:` 之前的内容已经是完整文本，可以提前清出。
     */
    private fun processBuf(): ParseResult {
        val currentBuf = buffer.toString()
        val detectedCalls = mutableListOf<ToolCall>()

        // 找出所有完整匹配的工具标签
        val matches = TAG_PATTERN.findAll(currentBuf).toList()

        if (matches.isEmpty()) {
            // 没有完整标签
            // 检查 buffer 是否包含未完成的 <tool: 片段
            val pendingStart = findPendingTagStart(currentBuf)  // S-fix: 兜住 <tool: 部分前缀
            return if (pendingStart >= 0) {
                // <tool: 之前的文本可以安全输出
                val safeText = currentBuf.substring(0, pendingStart)
                buffer.clear()
                buffer.append(currentBuf.substring(pendingStart))
                ParseResult(
                    cleanText     = safeText,
                    detectedCalls = emptyList(),
                    hasPendingTag = true,
                )
            } else {
                // buffer 中完全没有 <tool: 相关内容，全部输出
                buffer.clear()
                ParseResult(
                    cleanText     = currentBuf,
                    detectedCalls = emptyList(),
                    hasPendingTag = false,
                )
            }
        }

        // 解析每个完整标签
        for (match in matches) {
            val toolCall = parseMatch(match) ?: continue
            detectedCalls.add(toolCall)
        }

        // 从 buffer 中移除所有完整标签，得到纯文本
        var cleanBuf = currentBuf
        // 从后往前替换，避免 index 偏移
        for (match in matches.reversed()) {
            cleanBuf = cleanBuf.removeRange(match.range)
        }

        // 检查剩余内容是否含未完整的 <tool: 前缀
        val pendingStart = findPendingTagStart(cleanBuf)  // S-fix: 兜住 <tool: 部分前缀
        val hasPendingTag: Boolean
        val cleanText: String

        if (pendingStart >= 0) {
            cleanText = cleanBuf.substring(0, pendingStart)
            val pendingFragment = cleanBuf.substring(pendingStart)
            buffer.clear()
            buffer.append(pendingFragment)
            hasPendingTag = true
        } else {
            cleanText = cleanBuf
            buffer.clear()
            hasPendingTag = false
        }

        return ParseResult(
            cleanText     = cleanText,
            detectedCalls = detectedCalls,
            hasPendingTag = hasPendingTag,
        )
    }

    /**
     * 将一个正则匹配结果解析为 [ToolCall]。
     *
     * @return 解析成功返回 [ToolCall]，格式错误返回 null（跳过该标签）
     */
    private fun parseMatch(match: MatchResult): ToolCall? {
        return try {
            val toolName = match.groupValues[1].trim()
            if (toolName.isEmpty()) return null

            val attrString = match.groupValues[2]
            val params = parseAttributes(attrString)

            ToolCall(
                toolName = toolName,
                params   = params,
                rawTag   = match.value,
            )
        } catch (_: Exception) {
            null  // 解析失败时静默跳过，不中断流式处理
        }
    }

    /**
     * 解析属性字符串为 Map。
     *
     * 输入示例：` query="北京天气" limit="5"`
     * 输出示例：`{"query": "北京天气", "limit": "5"}`
     *
     * 支持 \" 转义，输出时还原（\"→ "）。
     *
     * S-fix（第二轮）：JSON 兜底改用花括号配平扫描（[findBalancedJsonEnd]）而非正则，
     * 因为正则的 `[^}]*` 只能匹配到第一层 `}`，遇到嵌套 JSON（如
     * `params="{"a":{"b":"c"}}"`）会静默截断丢失内层数据。先扫描出所有
     * `key="{...}"` 形态的 JSON 属性（支持任意深度嵌套），摘出对应 key 的原始 JSON
     * 文本，再用 [ATTR_PATTERN] 处理剩余的普通属性，避免重复解析同一段字符串。
     */
    private fun parseAttributes(attrString: String): Map<String, String> {
        if (attrString.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val jsonConsumedRanges = mutableListOf<IntRange>()

        // 第一遍：扫描所有 key="{ 起点，用花括号配平摘出完整 JSON 值（任意深度嵌套）
        KEY_EQUALS_QUOTE_PATTERN.findAll(attrString).forEach { keyMatch ->
            val valueStart = keyMatch.range.last + 1  // 紧跟在 key=" 之后
            val jsonEnd = findBalancedJsonEnd(attrString, valueStart)
            if (jsonEnd < 0) return@forEach  // 值不是 '{' 开头，或配平失败：交给 ATTR_PATTERN 处理

            // 配平结束后应紧跟闭合引号 " 才算合法（否则说明后面还有非 JSON 内容，保守放弃）
            if (jsonEnd >= attrString.length || attrString[jsonEnd] != '"') return@forEach

            val key = keyMatch.groupValues[1].trim()
            val jsonValue = attrString.substring(valueStart, jsonEnd)  // 含首尾花括号的完整 JSON 原文
            if (key in result) {
                com.zaijian.zhoumuyun.util.ZLog.w("ToolParser", "属性重复键 '$key'，已用后值覆盖前值（工具标签: ${attrString.take(80)}）")
            }
            result[key] = jsonValue
            jsonConsumedRanges.add(keyMatch.range.first..jsonEnd)  // 含收尾引号前的整段
        }

        // 第二遍：普通属性——跳过已被 JSON 分支处理过的区间，避免重复/错误解析
        ATTR_PATTERN.findAll(attrString).forEach { match ->
            if (jsonConsumedRanges.any { it.first <= match.range.first && match.range.last <= it.last }) {
                return@forEach  // 该区间已由 JSON 分支处理，跳过
            }
            val key = match.groupValues[1].trim()
            // group(2) = 双引号内容，group(3) = 单引号内容，取非空那个
            val rawValue = if (match.groupValues[2].isNotEmpty() || match.groupValues[3].isEmpty())
                match.groupValues[2] else match.groupValues[3]
            val value = rawValue
                .replace("\\\"", "\"")   // 还原转义双引号
                .replace("\\'", "'")     // 还原转义单引号
                .replace("\\n", "\n")    // 还原换行
                .replace("\\t", "\t")    // 还原 tab
            if (key in result) {
                // 重复键：后出现的值覆盖前面的，记日志便于排查 LLM 输出问题
                com.zaijian.zhoumuyun.util.ZLog.w("ToolParser", "属性重复键 '$key'，已用后值覆盖前值（工具标签: ${attrString.take(80)}）")
            }
            result[key] = value
        }
        return result
    }
}

// ─────────────────────────────────────────────────────────────
//  扩展工具函数（供 ToolCallInterceptor 使用）
// ─────────────────────────────────────────────────────────────

/**
 * 判断文本中是否可能包含工具调用标签（快速前缀检测，无正则开销）。
 * 用于在 delta 到达时做轻量判断，决定是否进入解析路径。
 */
fun String.mayContainToolTag(): Boolean = contains("<tool:")

/**
 * 从完整文本中统计工具标签数量（用于日志/调试）。
 */
fun String.countToolTags(): Int {
    var count = 0
    var idx = 0
    while (true) {
        idx = indexOf("<tool:", idx)
        if (idx < 0) break
        count++
        idx++
    }
    return count
}
