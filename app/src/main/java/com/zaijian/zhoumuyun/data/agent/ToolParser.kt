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
        // 属性段使用 (?:[^"'>]|"[^"]*"|'[^']*')* 而非 [^>]*，
        // 这样属性值（用双引号或单引号包裹）内部包含 > 字符时也不会被提前截断。
        private val TAG_PATTERN = Regex(
            """<tool:([a-z_][a-z0-9_]*)(\s(?:[^"'>]|"[^"]*"|'[^']*')*)?\s*/>""",
            RegexOption.DOT_MATCHES_ALL,
        )

        // 匹配单个属性：key="value" 或 key='value'（value 中允许 \" 或 \' 转义）
        // group(1) = key
        // group(2) = 双引号包裹的 value（含转义），group(3) = 单引号包裹的 value（含转义）
        // 修复：原仅支持双引号，LLM 偶尔输出单引号属性值时静默跳过导致参数丢失。
        private val ATTR_PATTERN = Regex(
            """([a-z_][a-z0-9_]*)=(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)')""",
        )

        // 标签开始的特征前缀，用于判断 buffer 是否在等待标签完成
        private const val TAG_PREFIX = "<tool:"
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
        val pendingStart = remaining.lastIndexOf(TAG_PREFIX)
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
            val pendingStart = currentBuf.lastIndexOf(TAG_PREFIX)
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
        val pendingStart = cleanBuf.lastIndexOf(TAG_PREFIX)
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
     */
    private fun parseAttributes(attrString: String): Map<String, String> {
        if (attrString.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        ATTR_PATTERN.findAll(attrString).forEach { match ->
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
