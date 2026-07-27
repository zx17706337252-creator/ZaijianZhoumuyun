package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.util.ZLog

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
         * P1 修复（批次4审查报告问题1）：原实现只认 `{` 开头的 JSON **对象**，
         * `files_json` 这类 JSON **数组**参数（`[{"path":...},{"path":...}]`）
         * 第一个字符是 `[` 而非 `{`，直接被 `searchFrom` 处 `!= '{'` 判定为「非 JSON」，
         * 交还给 ATTR_PATTERN 处理，然后在数组内部第一个内引号处被截断
         * （`[{"path":"a.txt",...}]` 截成 `[{`）。现同时支持 `{`/`}` 和 `[`/`]` 配平：
         * 用同一个 depth 计数器对两种括号一起加减（合法 JSON 中二者天然配对，不会有
         * `{` 用 `]` 收尾这种情况，所以合并计数不会误判），只要求起始字符是 `{` 或 `[`。
         * 字符串字面量内部的 `{`/`}`/`[`/`]` 不参与计数——通过 inString 状态跟踪，
         * 避免把 `{"text":"a{b}c"}` 中字符串里的花括号误算进深度。
         *
         * @return 从 [searchFrom] 开始，值内容以 '{' 或 '[' 结尾配平后的结束下标（即闭合
         *         '}' 或 ']' 的下一个位置）；如果 [searchFrom] 处不是 '{'/'[' 或配平失败
         *         （数据不完整/格式错误），返回 -1。
         */
        private fun findBalancedJsonEnd(text: String, searchFrom: Int): Int {
            if (searchFrom >= text.length) return -1
            val opener = text[searchFrom]
            if (opener != '{' && opener != '[') return -1

            var depth = 0
            var i = searchFrom
            val n = text.length
            var inString = false
            while (i < n) {
                val c = text[i]
                if (inString) {
                    when (c) {
                        '\\' -> { i += 2; continue }
                        '"' -> inString = false
                    }
                    i++
                    continue
                }
                when (c) {
                    '\\' -> { i += 2; continue }
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
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
        // P0-6 修复：原先无论是否有未闭合片段都硬编码 hasPendingTag=false，调用方
        // （ToolCallInterceptor）据此判断"本轮没有工具调用"，maxTokens 截断产生的
        // 半截 <tool: 标签被静默丢弃后，整轮被误判为"模型没调工具"——是文档发送
        // 链路"没有任何报错记录"的根因之一。改为如实返回 pendingStart>=0，并记一条
        // 警告日志（用非 suspend 的 ZLog.w，因为 flush() 不是 suspend 函数，不能用
        // suspend 的 AgentLog.warn）。注意：ParseResult.hasPendingTag 字段早已存在
        // （feed() 已在用），此处只是一行赋值修正，无需改动调用方签名。
        if (pendingStart >= 0) {
            ZLog.w("ToolParser", "检测到未闭合的工具标签片段，已截断: ${remaining.take(80)}")
        }
        return ParseResult(
            cleanText     = safeText,
            detectedCalls = emptyList(),
            hasPendingTag = pendingStart >= 0,
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
        } catch (_: Throwable) {
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
    /**
     * P1 修复（批次4审查报告问题1，根因②）：
     *
     * 原实现用四次链式 `.replace("\\\"", "\"")` / `.replace("\\'", "'")` /
     * `.replace("\\n", "\n")` / `.replace("\\t", "\t")` 依次全局替换，每一次
     * `.replace()` 都是独立的、对整个字符串重新扫描的全局替换，**不是**按顺序
     * 从左到右逐字符消费转义序列。这在多层转义场景下会产生错误还原：
     *
     * 例如 LLM 为了把带引号的代码塞进 JSON 数组值里，按规范做了双重转义——
     * 先转义 JSON 内部的 `"` 为 `\"`，再把整个 JSON 字符串转义一层给标签属性用，
     * 得到 `\\\"`（转义反斜杠 + 转义引号）。用链式 replace 处理：
     * 第一步 `.replace("\\\"", "\"")` 会把 `\\\"` 中的 `\"` 部分吃掉，错误地
     * 变成 `\\"`（多余的反斜杠 + 裸引号），而不是期望的 `\"`（还原出的转义引号，
     * 留给内层 JSON 解析器处理）。同理 `\\n`（转义反斜杠 + 字母n）会被
     * `.replace("\\n", "\n")` 误当成"转义换行符"直接换行，而不是保留成
     * `\n` 两个字符交给内层 JSON 解析。
     *
     * 根本原因：链式 replace 没有处理 `\\`（转义反斜杠本身），且执行顺序
     * 是"全局做完一种替换，再全局做下一种"，而不是"从左到右一次扫描、
     * 每遇到一个 `\` 就只消费紧跟着的一个字符"。正确的转义还原必须是
     * 单遍扫描：遇到 `\` 就查看下一个字符决定输出什么，然后跳过这两个
     * 字符，绝不能对已经从转义序列中还原出的字符再次进行替换。
     *
     * 现改为单遍从左到右扫描，`\\`→`\`、`\"`→`"`、`\'`→`'`、`\n`→换行、
     * `\t`→tab；不在上述已知转义表中的 `\x` 保留原样（`\` 和 `x` 都原样输出），
     * 避免吞掉未来可能出现的其他转义写法。
     */
    private fun unescapeAttrValue(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            if (c == '\\' && i + 1 < n) {
                when (raw[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"'  -> { sb.append('"');  i += 2 }
                    '\'' -> { sb.append('\''); i += 2 }
                    'n'  -> { sb.append('\n'); i += 2 }
                    't'  -> { sb.append('\t'); i += 2 }
                    else -> { sb.append(c); i += 1 }  // 未知转义：保留反斜杠本身，不消费下一字符
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

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
            val rawJsonValue = attrString.substring(valueStart, jsonEnd)  // 含首尾括号的原文（可能仍带标签层转义）
            // P1 修复（批次4审查报告问题1，根因②的延伸）：
            // rawJsonValue 是从 attrString 直接切出的原始子串，如果 LLM 输出的是「裸」JSON
            // （标签属性值内部直接写未转义的 JSON，如 params="{"a":"b"}"），rawJsonValue 本身
            // 就是合法 JSON 原文，不需要再处理；但如果 LLM 按规范做了「双重转义」（先转义 JSON
            // 内部的引号，再对整个字符串转义一层给标签属性用，如 files_json 里
            // content 含引号+换行时），rawJsonValue 里会残留 \\\" / \\n 这类标签层转义序列，
            // 必须先用 unescapeAttrValue 还原一层，才能得到真正的 JSON 原文。
            // 用 unescapeAttrValue 处理不会破坏「裸 JSON」场景：裸 JSON 内部没有 `\` 开头的
            // 双字符序列（除非 JSON 值本身就含 \" 转义，这种情况下还原成 " 后仍是合法 JSON），
            // 所以同一处理对两种输出习惯都能正确工作。
            val jsonValue = unescapeAttrValue(rawJsonValue)
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
            val value = unescapeAttrValue(rawValue)
            if (key in result) {
                // 重复键：后出现的值覆盖前面的，记日志便于排查 LLM 输出问题
                com.zaijian.zhoumuyun.util.ZLog.w("ToolParser", "属性重复键 '$key'，已用后值覆盖前值（工具标签: ${attrString.take(80)}）")
            }
            result[key] = value
        }

        // P1 修复（批次3审查报告问题1，结构性修复）：
        // 原方案（批次2引入）只在检测到疑似截断时打 warning 日志，截断本身仍会发生——
        // translate/file_export/agent_message/soul_update/narrative_memory_update/
        // user_impression_update 六个长文本工具因此无一幸免（批次3审查报告核心结论）。
        //
        // 现改为真正修正：对每个被 ATTR_PATTERN 误截断的自由文本属性（判定逻辑复用
        // detectUnescapedQuoteTruncation 的"截断点之后是否像合法边界"），重新贪婪扫描——
        // 从同一个 key=" 起点出发，把值一直扩展到"最后一个看起来像真实收尾"的裸引号，
        // 而不是第一个裸引号就停。"看起来像真实收尾"定义为：该引号后紧跟（去空白后）
        // 下一个 `key="`/`key='` 起点，或直接是 attrString 末尾（标签的 /> 之前）。
        //
        // 为什么这样安全：TAG_PATTERN 已经保证 attrString 是"一个完整标签内、/> 之前"的
        // 内容，所以"某个裸引号 + 紧邻末尾或下一个属性"这个边界必然存在且可判定，
        // 不会像单纯"贪婪到最后一个引号"那样吞掉本该属于下一个属性的内容——因为判定
        // 条件同时要求引号之后紧跟属性名=的合法形状。对正常场景（值内本身没有裸引号）
        // 完全不生效：该 key 根本不会进入 correctedKeys 集合。
        // 已被 JSON 分支消费的 key 天然被排除（JSON 值内部裸引号是合法结构，不属于本修复范畴，
        // 理由同批次2注释）。
        val correctedKeys = mutableSetOf<String>()
        KEY_EQUALS_QUOTE_PATTERN.findAll(attrString).forEach { keyMatch ->
            val key = keyMatch.groupValues[1].trim()
            if (jsonConsumedRanges.any { it.first <= keyMatch.range.first && keyMatch.range.last <= it.last }) {
                return@forEach  // JSON 分支已处理，不参与修正
            }
            if (key !in result) return@forEach  // 未被 ATTR_PATTERN 捕获（如单引号变体），不在本修复范畴

            val valueStart = keyMatch.range.last + 1
            val fixedEnd = findGreedyQuoteEnd(attrString, valueStart)
            if (fixedEnd < 0) return@forEach  // 找不到合法收尾边界，保留 ATTR_PATTERN 原结果

            // fixedEnd 是收尾引号的下标；只有当它比 ATTR_PATTERN 原本认定的截断点更靠后时，
            // 才说明原结果确实被误截断，需要用扩展后的值覆盖。
            val originalTruncatedAt = findFirstUnescapedQuote(attrString, valueStart)
            if (originalTruncatedAt < 0 || fixedEnd <= originalTruncatedAt) return@forEach

            val rawValue = attrString.substring(valueStart, fixedEnd)
            result[key] = unescapeAttrValue(rawValue)
            correctedKeys.add(key)
        }

        // 保留原有 warning 检测：针对修正后仍判定为"疑似截断"的残余情况（如修正算法本身
        // 找不到合法边界时）记录日志，方便定位。已修正的 key 显式跳过，避免对已经修好的
        // 值重复告警——detectUnescapedQuoteTruncation 是基于 attrString 原文重新扫描，
        // 与 result 是否已被修正无关，所以需要用 skipKeys 参数主动排除。
        detectUnescapedQuoteTruncation(attrString, result, jsonConsumedRanges, skipKeys = correctedKeys)

        // P2 加固：剥离 LLM 标签中自带的 "__" 前缀属性键。
        // "__" 前缀是内部注入参数命名约定（如 __character_id），由工作流/调度层用
        // mapOf("__character_id" to ...) 注入，LLM 标签不允许自带——否则可能伪充内部
        // 参数绕过权限校验。当前各调用点的 + 运算符会用可信值覆盖同名键，此为纵深防御。
        val injectedKeys = result.keys.filter { it.startsWith("__") }
        if (injectedKeys.isNotEmpty()) {
            ZLog.w("ToolParser", "LLM 标签自带内部参数键，已剥离: $injectedKeys")
            injectedKeys.forEach { result.remove(it) }
        }

        return result
    }

    /**
     * 从 [searchFrom]（紧跟 key=" 之后）找到 ATTR_PATTERN 语义下的第一个未转义裸引号位置。
     * 与 [ATTR_PATTERN] 的匹配语义保持一致，用于判断"原结果是否被截断"。
     * @return 第一个未转义 " 的下标；未找到返回 -1（标签不完整）
     */
    private fun findFirstUnescapedQuote(text: String, searchFrom: Int): Int {
        var i = searchFrom
        val n = text.length
        while (i < n) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    /**
     * 从 [searchFrom] 开始贪婪查找"看起来像真实收尾"的裸引号：该引号（跳过转义序列后）
     * 之后紧跟（去除空白后）一个合法的下一属性起点 `[a-z_][a-z0-9_]*=["']`，或该引号
     * 就是 attrString 的最后一个字符（值一路到标签末尾）。
     *
     * 逐个候选裸引号从前往后检查，命中即返回——不是"最后一个引号"，而是"第一个满足
     * 边界条件的引号"，因为一旦满足条件说明后面确实是下一个属性或标签结尾，再往后
     * 找没有意义（且可能误吞下一个属性）。
     *
     * @return 收尾引号下标；找不到满足条件的候选时返回 -1
     */
    private fun findGreedyQuoteEnd(text: String, searchFrom: Int): Int {
        var i = searchFrom
        val n = text.length
        val nextAttrRegex = Regex("""^[a-z_][a-z0-9_]*=["']""")
        while (i < n) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> {
                    val after = text.substring(i + 1).trimStart()
                    if (after.isEmpty() || nextAttrRegex.containsMatchIn(after)) {
                        return i
                    }
                    i++  // 这个裸引号不是合法收尾，继续往后找下一个候选
                }
                else -> i++
            }
        }
        return -1
    }

    /**
     * 检测 attrString 中是否存在"值被未转义引号提前截断"的疑似情况，命中则记录 warning。
     *
     * 判定思路：对每个 `key="`，找到 ATTR_PATTERN 实际截断到的位置（第一个未转义 `"`）。
     * 如果从该截断点开始往后看，剩余内容既不是空白/字符串结尾，也不是紧跟着下一个
     * `key="` 或 `key='` 的合法属性起点，说明这段"剩余内容"很可能是被截断丢弃的原始
     * 文本残留，而不是下一个属性——即触发了报告中描述的静默截断。
     *
     * JSON 分支（jsonConsumedRanges）已被排除：JSON 值内部的裸引号是合法结构，不属于本检测范畴。
     */
    private fun detectUnescapedQuoteTruncation(
        attrString: String,
        parsed: Map<String, String>,
        jsonConsumedRanges: List<IntRange>,
        skipKeys: Set<String> = emptySet(),
    ) {
        KEY_EQUALS_QUOTE_PATTERN.findAll(attrString).forEach { keyMatch ->
            val key = keyMatch.groupValues[1].trim()
            if (key !in parsed) return@forEach  // 该 key 未被 JSON 分支或 ATTR_PATTERN 捕获，跳过
            if (key in skipKeys) return@forEach  // P1 结构性修复（批次3）：已被贪婪扫描修正，值已完整，跳过告警
            if (jsonConsumedRanges.any { it.first <= keyMatch.range.first && keyMatch.range.last <= it.last }) {
                return@forEach  // 该 key 是 JSON 分支处理的，值内部裸引号合法，跳过
            }

            val valueStart = keyMatch.range.last + 1
            // 找到 ATTR_PATTERN 语义下这个值实际截断的位置：从 valueStart 开始，
            // 跳过转义序列（\\ 后跟任意字符），遇到第一个裸 " 即为截断点。
            var i = valueStart
            val n = attrString.length
            var truncatedAt = -1
            while (i < n) {
                when (attrString[i]) {
                    '\\' -> i += 2
                    '"' -> { truncatedAt = i; i = n }
                    else -> i++
                }
            }
            if (truncatedAt < 0) return@forEach  // 未找到闭合引号，属于标签不完整，非本检测范畴

            val after = attrString.substring(truncatedAt + 1).trimStart()
            val looksLikeNextAttrOrEnd = after.isEmpty() ||
                Regex("""^[a-z_][a-z0-9_]*=["']""").containsMatchIn(after)

            if (!looksLikeNextAttrOrEnd) {
                com.zaijian.zhoumuyun.util.ZLog.w(
                    "ToolParser",
                    "疑似未转义引号导致属性值被截断：key='$key'，" +
                        "截断后残留内容 '${after.take(40)}' 既非属性结尾也非下一个属性，" +
                        "原始标签片段: ${attrString.take(120)}",
                )
            }
        }
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
