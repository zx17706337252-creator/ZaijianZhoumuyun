package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.provider.ChatStreamItem
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.repository.AgentActivityRepository
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 13 · Tool Call Engine（Prompt-based Dispatch）
 *
 * ═══════════════════════════════════════════════════════════════
 * 文件 4/4 — ToolCallInterceptor.kt
 * 流式输出拦截 + 工具执行 + 结果回注
 * ═══════════════════════════════════════════════════════════════
 *
 * ── 完整流程 ─────────────────────────────────────────────────
 *
 * ```
 * ChatViewModel.sendMessage()
 *   │
 *   ├─ 构建 systemPrompt（含工具能力描述）
 *   │   └─ PromptOrchestrator.buildTaskLayerBlock() + AgentToolRegistry.buildToolDescriptionBlock()
 *   │
 *   └─ ToolCallInterceptor.streamWithTools(provider, messages, systemPrompt, config)
 *         │
 *         ├─ Phase 1：调用 provider.chat()，开始接收 SSE 流
 *         │     每个 delta → ToolParser.feed(delta)
 *         │     cleanText → 立即 emit（打字机效果持续）
 *         │     detectedCall → 加入待处理队列
 *         │
 *         ├─ Phase 2：流结束后，串行执行所有工具调用
 *         │     ToolCall → AgentToolRegistry.get(name) → AgentTool.execute(params)
 *         │     emit 进度提示（"[正在搜索…]"，可选）
 *         │
 *         └─ Phase 3：将工具结果回注，触发第二次 LLM 调用
 *               构建 toolResultMessage（role="user"，content=工具结果）
 *               追加到消息历史，再次调用 provider.chat()
 *               第二次流的所有 cleanText → emit
 *               最终拼接为完整 fullReply 返回给 ChatViewModel
 * ```
 *
 * ── 与 ChatViewModel 的集成 ───────────────────────────────────
 *
 * ToolCallInterceptor 是对 [LLMProvider.chat] 的透明包装。
 * ChatViewModel 中：
 * ```kotlin
 * // 原来：
 * provider.chat(messages, systemPrompt, config).collect { delta -> ... }
 *
 * // 替换为（当 AgentToolRegistry 不为空时）：
 * ToolCallInterceptor.streamWithTools(
 *     provider, messages, systemPrompt, config,
 *     onToolStart = { call -> _uiState.update { it.copy(streamingHint = call.userHint) } },
 * ).collect { event -> ... }
 * ```
 *
 * ── 设计约束 ─────────────────────────────────────────────────
 *
 * ① 工具调用串行执行，不并发（避免 context 污染和 race condition）
 * ② 第二次 LLM 调用仍保持流式（打字机效果延续）
 * ③ 最多执行 [MAX_TOOL_ROUNDS] 轮工具调用（防止 LLM 无限循环）
 * ④ 单个工具超时 [TOOL_TIMEOUT_MS] 后返回超时错误，不中断整体流程
 * ⑤ 所有异常必须转化为错误 ToolResult，不向上抛出
 */

// ─────────────────────────────────────────────────────────────
//  流式事件（emit 到 Flow 的类型）
// ─────────────────────────────────────────────────────────────

/**
 * ToolCallInterceptor 向外 emit 的事件类型。
 *
 * ChatMessageOrchestrator 按事件类型分发处理：
 *   - [TextDelta]   → 累积到 fullReply（Task-2：不再逐 token 更新 streamingContent）
 *   - [ToolStarted] → 覆盖 streamingHint 为工具特定提示（如 "正在生成PDF…"）
 *   - [ToolDone]    → 恢复 streamingHint 为 "正在生成回复…"（Task-2：不再清除）
 *   - [RoundDone]   → 本轮工具全部执行完毕，第二次回复开始前的分隔点
 */
sealed class StreamEvent {
    /** LLM 输出的增量文本（已过滤工具标签） */
    data class TextDelta(val text: String) : StreamEvent()

    /** 工具开始执行 */
    data class ToolStarted(
        val toolName: String,
        val params: Map<String, String>,
        val hint: String?,
    ) : StreamEvent()

    /** 工具执行完毕 */
    data class ToolDone(val result: ToolResult) : StreamEvent()

    /** 一轮工具执行结束，第二次 LLM 调用即将开始 */
    object RoundDone : StreamEvent()

    /**
     * v1.49 修复（file_read 锁死机制复发性触发）：pendingFilePaths 的"已读"凭证
     * 此前只存在于本次 streamWithTools() 调用的局部变量里，函数返回后即丢失——
     * ChatMessageOrchestrator 只把最终 assistant 回复落库，中间的工具调用/工具
     * 结果消息从未写回数据库。导致下一条新消息重新组装 messages 时，alreadyRead
     * 检测永远找不到"已读过"的证据，每条新消息都会把两轮强制重试 + 兜底自动读取
     * 整套流程重新跑一遍——这正是"系统反复强制要求读取文件"这个复发bug的根因。
     *
     * 拦截器本身不持有 messageRepo（保持与具体持久化方式解耦，圆桌等场景复用同一
     * 拦截器），改为发出这个事件，由调用方（ChatMessageOrchestrator 等）决定
     * 如何把"文件已读取"这件事持久化，从而让下一轮 alreadyRead 检测能查到证据。
     *
     * 无论是 AI 主动调用 file_read，还是重试耗尽后程序兜底自动读取，都会发出这个
     * 事件——只要这个文件路径被处理过一次（不管成功与否），就不该无限期反复强制，
     * 与原有 pendingFilePaths.remove()/clear() 的"处理过一次就不再追"语义保持一致。
     */
    data class FileReadConfirmed(val filePath: String, val fileName: String) : StreamEvent()
}

// ─────────────────────────────────────────────────────────────
//  拦截器主体
// ─────────────────────────────────────────────────────────────

object ToolCallInterceptor {

    /**
     * 最大工具执行轮数。
     * 第二次 LLM 回复中仍有工具调用时，最多再执行一轮。
     * 超过此轮数后，直接返回最后一次 LLM 的原始输出（含标签），不再拦截。
     *
     * P0-7 修复：原值 2 对"一次性导出 5 种格式文件"这类多工具连续调用场景严重不足，
     * 模型往往在第 2、3 个工具标签还没写完就达到轮数上限被强制收尾。提升到 6，
     * 覆盖典型的多工具编排（5 份文件 + 1 轮收尾自查）。
     */
    const val MAX_TOOL_ROUNDS = 6

    /**
     * 单个工具调用超时（毫秒）。
     * 超过此时间后返回超时 ToolResult，不中断整体流程。
     */
    const val TOOL_TIMEOUT_MS = 30_000L

    /**
     * LLM 调用型工具的超时时间（毫秒）。
     *
     * 根因修复（PDF/PPT/HTML "说发了但看不到"核心根因）：
     * pdf_export/html_gen/pptx_gen/docx_gen 等工具内部通过 callLlm 发起二次
     * LLM 请求（生成文档内容/HTML/大纲JSON），单次 LLM 往返可能耗时 10-25s
     * （取决于提供商负载、maxTokens、网络延迟），加上文件写入和格式转换，
     * 30s 经常不够——工具被 withTimeout 强制取消后返回 timeout 失败结果，
     * LLM 收到失败信息后仍可能声称"已发送"，用户看到空头承诺但无文件。
     *
     * MD/TXT 不受影响：file_export 不调用 LLM，纯文本写入 <100ms。
     * Excel 不受影响：excel_gen 的 headers+rows 直传路径跳过 LLM。
     *
     * 对 LLM 调用型工具放宽到 90s，覆盖 p95 延迟；非 LLM 工具保持 30s。
     */
    const val LLM_TOOL_TIMEOUT_MS = 90_000L

    /**
     * 内部会发起 LLM 二次调用的工具集合。
     * 这些工具的超时使用 [LLM_TOOL_TIMEOUT_MS] 而非 [TOOL_TIMEOUT_MS]。
     * 判定依据：工具 execute() 内部是否调用 callLlm/chatSync。
     */
    val LLM_DEPENDENT_TOOLS = setOf(
        "pdf_export",    // callLlm 生成 Markdown 内容（content 为空时）
        "html_gen",      // callLlm 生成完整 HTML+CSS
        "pptx_gen",      // callLlm 生成大纲 JSON（最多重试2次）
        "docx_gen",      // callLlm 生成文档内容
        "writing_critique", "outline_gen", "email_draft",
        "meeting_minutes", "inspiration_fetch", "image_gen_prompt",
    )

    // ─────────────────────────────────────────────────────────
    //  Fix-孤儿文件 ③：工具执行中标记（供调用方在 cancel 前查询）
    // ─────────────────────────────────────────────────────────
    //
    // 背景：①②（executeWithTimeout 的 NonCancellable + 孤儿兜底）保证了
    // "即使被打断，文件也不会真的丢"，但打断本身仍然发生——被打断的这一轮
    // 回复文字会被腰斩，用户体验上仍然是"话说到一半没了"。①②处理的是
    // "取消已经发生之后怎么办"，这里补一道"能不能一开始就别取消"的防线。
    //
    // ChatMessageOrchestrator.sendMessage() 的 getReplyJob()?.cancel() 理论上
    // 应该只在 isTyping==false（发送按钮本就该被禁用）时才可能触发，属于
    // "万一 isTyping 门控失效"的兜底调用；ChatSessionDelegate.init() 已经改成
    // 只在真正切换角色时才取消（见该文件 Fix-孤儿文件 说明）。这道标记是给
    // 这两处（以及圆桌 RoundtableMessageOrchestrator.sendMessage() 同款场景）
    // 多一层依据：cancel 之前先问一句"这个角色/圆桌成员现在是不是正有工具在
    // 落盘"，是的话就别粗暴杀掉，改为提示用户稍候。
    //
    // key 用 "sceneType:characterId" 而不是单独 characterId——私聊和圆桌可能
    // 复用同一个角色 ID（圆桌成员本身也是一个正常角色），两边应该各自独立
    // 判断"是否正在执行"，不应互相影响。
    private val toolInFlightKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun toolInFlightKey(sceneType: String, characterId: Int) = "$sceneType:$characterId"

    /**
     * 供调用方（ChatMessageOrchestrator.sendMessage / RoundtableMessageOrchestrator.sendMessage
     * 等）在决定要不要 cancel 旧任务前查询：这个 (sceneType, characterId) 当前
     * 是不是正有工具在执行（[executeWithDegradation] 覆盖的整个尝试/降级周期，
     * 不只是最外层 LLM 文字流式阶段——文字流式阶段被打断是安全、廉价的，
     * 真正需要保护的是工具落盘这一段）。
     *
     * 返回 true 时，调用方应避免 cancel 当前 job，改为提示用户"上一个操作还在
     * 进行中，请稍候"，而不是强行打断。
     */
    fun isToolInFlight(sceneType: String, characterId: Int): Boolean =
        toolInFlightKeys.contains(toolInFlightKey(sceneType, characterId))

    // ═════════════════════════════════════════════════════════════════
    //  方案 A：句子级事前门控（send 前剥离空头承诺话术）
    // ═════════════════════════════════════════════════════════════════
    //
    // 核心思路（对照改造方案 v9 第 2.1~2.3 节）：
    //   不再逐 delta 立即 send，而是先攒到 sendBuffer，按句子边界切分，
    //   每凑够一个完整句子就和上一句（pendingSentence）拼成滑动窗口，
    //   跑一次 FALSE_COMPLETION_CLAIM_REGEX 判断——命中则丢弃、不 send；
    //   不命中则把上一句真正 send 出去，当前这句暂存为新的 pendingSentence。
    //
    // "迟一句发送"机制（坑6）：判断的是 pendingSentence（上一句），但正则
    // 跑在"上一句+当前句"拼接的窗口上——这样即使上一句单独测不命中，也能
    // 借当前句一起测出跨句假话。代价是打字机效果天然滞后一句。
    //
    // 判断条件（坑2/2.6 节订正）：
    //   !anyToolSucceeded && pendingCalls.isEmpty() && 正则命中
    // ——工具成功后的合法收尾语不会被误拦。
    //
    // 为什么用非 suspend 的"返回要 send 的文本"模式：
    //   channelFlow 的 send() 是 suspend，Kotlin 不支持 local suspend fun，
    //   也不支持在非 suspend 的普通函数里直接调 send。所以 gate 方法返回
    //   "该 send 什么"（String? ），由处于 suspend 上下文的调用方执行 send。
    //   日志用 ZLog.w（非 suspend，内部异步转发到 AgentLog）。
    private class SentenceGate(
        /** 跨轮状态：本请求此前是否有任意工具成功过（true 时不拦合法收尾语） */
        private val anyToolSucceeded: Boolean,
        /** 本轮待执行工具列表引用（live，随 feed 不断增长；isEmpty 判断用最新值） */
        private val pendingCalls: List<ToolCall>,
    ) {
        /** 上一句还没确认放行、暂存等待和下一句拼窗判断 */
        private var pendingSentence: String? = null
        /** 本次 feed 累积的、尚未凑够一整句的文本残片 */
        private val sendBuffer = StringBuilder()

        /**
         * 判断待测文本是否命中空头承诺正则。
         * 条件对齐 claimsFileCompletionWithoutToolCall（2.6 节订正）：
         * !anyToolSucceeded && pendingCalls.isEmpty() && 正则命中。
         */
        private fun isFalseClaim(text: String): Boolean {
            val shouldCheck = !anyToolSucceeded && pendingCalls.isEmpty()
            return shouldCheck && FALSE_COMPLETION_CLAIM_REGEX.containsMatchIn(text)
        }

        /**
         * 把 pendingSentence 和新句子拼起来判断，决定 pendingSentence 是否放行。
         * 无论放行与否都会把 pendingSentence 换成新句子（坑6 核心）。
         * 返回：应 send 的文本（= 放行的上一句），null 表示丢弃/暂无。
         */
        fun advanceWindow(nextSentence: String): String? {
            val prev = pendingSentence
            pendingSentence = nextSentence
            if (prev != null) {
                val window = prev + nextSentence
                if (isFalseClaim(window)) {
                    ZLog.w(
                        "ToolCall",
                        "🚫 gate 拦截疑似空头承诺（窗口命中）：${window.take(40)}",
                    )
                    return null  // 上一句不 send；roundText 已在别处完整 append
                }
                return prev  // 放行上一句
            }
            return null  // 第一句：没有上一句可 send
        }

        /**
         * round 结束（含异常）时的兜底吐出：pendingSentence 不再有"下一句"可拼。
         * 先把 sendBuffer 里未成句的残片喂给 advanceWindow，再单独测 pendingSentence。
         * 返回：应 send 的文本列表（调用方逐条 send）。
         * @param skipGateCheck 异常路径优先级是"不丢字 > 不误判"，直接跳过正则照单发出
         */
        fun flush(skipGateCheck: Boolean = false): List<String> {
            val toSend = mutableListOf<String>()
            // 异常路径可能跳过了 preFeed/flush 收尾，sendBuffer 里可能还有残片
            feedRemaining().forEach { toSend.add(it) }
            // flush 最后一句 pendingSentence（没有"下一句"可拼，只能单独测）
            val prev = pendingSentence
            pendingSentence = null
            if (prev != null) {
                if (!skipGateCheck && isFalseClaim(prev)) {
                    ZLog.w(
                        "ToolCall",
                        "🚫 gate 拦截疑似空头承诺（round 收尾单独测）：${prev.take(40)}",
                    )
                } else {
                    toSend.add(prev)
                }
            }
            return toSend
        }

        /**
         * 把 sendBuffer 里未成句的残片作为一整句喂给 advanceWindow。
         * 在 preFeed/flush 收尾文本处理之前调用，确保残片不丢失。
         * 返回：本次应 send 的文本列表。
         */
        fun feedRemaining(): List<String> {
            if (sendBuffer.isEmpty()) return emptyList()
            val remaining = sendBuffer.toString()
            sendBuffer.clear()
            val toSend = mutableListOf<String>()
            advanceWindow(remaining)?.let { toSend.add(it) }
            return toSend
        }

        /**
         * 喂入一段文本，按句子边界切分后逐句 advanceWindow。
         * 返回：本次应 send 的句子列表（调用方逐条 send）。
         */
        fun feed(text: String): List<String> {
            sendBuffer.append(text)
            val toSend = mutableListOf<String>()
            var lastCut = 0
            val buf = sendBuffer.toString()
            // 按中文句号、英文句号、感叹号、问号切分（保留标点在前一句末尾）
            val sentenceEndRegex = Regex("[。！？!?]")
            for (match in sentenceEndRegex.findAll(buf)) {
                val end = match.range.last + 1
                val sentence = buf.substring(lastCut, end)
                lastCut = end
                advanceWindow(sentence)?.let { toSend.add(it) }
            }
            // 把已切出的部分从 sendBuffer 移除，保留剩余残片
            if (lastCut > 0) {
                sendBuffer.setLength(0)
                sendBuffer.append(buf.substring(lastCut))
            }
            return toSend
        }
    }

    /**
     * 流式调用 LLM 并自动处理工具调用。
     *
     * 当注册表为空（未注册任何工具）时，直接透传 provider.chat() 的流，
     * 无任何额外开销。
     *
     * @param provider          LLM 提供商
     * @param messages          当前消息历史（不含 system）
     * @param systemPrompt      系统提示（应已包含工具能力描述块）
     * @param config            LLM 配置
     * @param maxRounds         最大工具执行轮数（默认 [MAX_TOOL_ROUNDS]）
     * @param disabledToolNames 执行层强制禁用的工具名集合（默认空，不影响现有调用点）。
     *   这是与 prompt 层描述过滤（[AgentToolRegistry.buildToolDescriptionBlock] 的
     *   `excludeNames`）配合使用的第二道防线：即使模型因为幻觉/历史学习生成了
     *   本不该出现的 `<tool:xxx>` 标签，只要标签名在此集合中，也会在 Phase 2
     *   执行前被拦截，不会真的调用该工具。两层防御缺一不可——只做 prompt 层
     *   过滤是"眼不见为净"，模型仍可能意外生成被排除工具的标签。
     * @param activityContext   §2.1.2 降级策略所需的活动上下文（characterId/sessionRef/sceneType）。
     *   传入时降级过程中的每次尝试会写入 agent_activity_events 表（eventType=DEGRADE_*），
     *   终态放弃时调用 MemoryEngine.onToolFailureExhausted。为 null 时不写心迹事件，
     *   降级过程只走 AgentLog——向后兼容不传此参数的现有调用方。
     * @return                  [StreamEvent] 的 Flow
     */
    fun streamWithTools(
        provider: LLMProvider,
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
        maxRounds: Int = MAX_TOOL_ROUNDS,
        disabledToolNames: Set<String> = emptySet(),
        activityContext: ActivityContext? = null,
    ): Flow<StreamEvent> = channelFlow {

        // 快速路径：注册表为空，直接透传
        // 注意：这里判断的是全局注册表 allNames()，不考虑 disabledToolNames。
        // 如果调用方传入了非空 disabledToolNames 但全局注册表本身非空，
        // 会走下面的慢路径（循环 + parser），即使排除后可用工具集合为空。
        // 这不会导致错误：pendingCalls 始终为空，第一轮 Phase 1 结束后就会
        // 在 pendingCalls.isEmpty() 处正常 break，只是多了一点不必要的
        // parser 构造开销，可接受。
        if (AgentToolRegistry.allNames().isEmpty()) {
            // P0-5: 使用 chatStream() 保持一致，但快速路径不关心 finish_reason
            provider.chatStream(messages, systemPrompt, config).collect { item ->
                if (item is ChatStreamItem.TextDelta) send(StreamEvent.TextDelta(item.text))
            }
            return@channelFlow
        }

        // 工具执行轮次循环
        var currentMessages = messages.toMutableList()
        var round = 0

        // ── v1.48 程序锁死文件读取（主动模式）─────────────────────
        // 用户要求"强制 AI 主动调用 file_read 工具"，不是程序帮它读好塞给它。
        // 方案：扫描消息历史里的"用户导入了一个文件：xxx（路径：yyy）"通知，
        // 提取待读文件路径。在 AI 第一轮回复后，如果它没调用 file_read（pendingCalls
        // 为空或不含 file_read），就拦截它的回复，注入一条强制指令要求它必须调用
        // file_read，然后重新发给 LLM 生成——程序锁死，不依赖 prompt 是否被执行。
        val pendingFilePaths = mutableSetOf<String>()
        for (msg in messages) {
            // Bug-fix（file_read 锁死失效）：ChatMessageOrchestrator.kt 的
            // Fix-FileImportBlindSpot 已把这条通知的 role 由 "system" 改成了
            // "user"——因为 OpenAICompatProvider.buildRequestBody 会把 role="system"
            // 的消息统一过滤掉（只放行 user/assistant），不改成 "user" 模型根本收不到
            // 这条通知。但这里的判断条件当时没有同步更新，一直卡在 msg.role == "system"，
            // 而实际传进来的 messages 里这条消息 role 已经是 "user"，导致条件恒为假——
            // pendingFilePaths 永远是空集合，file_read 强制锁死机制形同虚设：AI 不主动
            // 读文件时不会被打回重试，也不会有兜底自动读取，直接回复"看不到文件内容"。
            // 改为只按内容匹配，不再要求特定 role，兼容通知被上游包装成任意角色的情况。
            if (msg.content.contains("用户导入了一个文件")) {
                val pathMatch = Regex("""路径[：:]\s*([^\s)）]+)""").find(msg.content)
                if (pathMatch != null) {
                    val filePath = pathMatch.groupValues[1]
                    // Bug-fix（alreadyRead 误判）：file_read 成功时工具结果的表头只写
                    // "[文件内容: ${file.name}]"（纯文件名，见 BuiltinTools.FileReadTool），
                    // 不含目录前缀；这里原来却拿完整绝对路径 filePath 去匹配结果内容，
                    // 实际上永远匹配不上——文件哪怕已经被读过，也会被判定为"还没读"，
                    // 导致之后每条新消息都重新强制读一遍（重试或兜底）。改成用路径里的
                    // 文件名去匹配，与工具结果表头的实际格式对齐。
                    val fileNameOnly = filePath.substringAfterLast('/').substringAfterLast('\\')
                    // 只注入还没被读过的文件路径（检查消息历史里有没有对应的工具结果）
                    val alreadyRead = messages.any { m ->
                        m.role == "user" && m.content.contains("[工具执行结果]") &&
                        m.content.contains(fileNameOnly)
                    }
                    if (!alreadyRead) pendingFilePaths.add(filePath)
                }
            }
        }

        // Fix-3：跨轮失败追踪。
        // 记录之前轮次中失败过的工具名。下一轮如果 LLM 声称文件已发送，
        // 但没有重试之前失败的工具，说明它在撒谎——需要拦截。
        val previousFailedTools = mutableSetOf<String>()

        // Fix-DupFileGen①：跨轮成功追踪 + 完全重复调用去重。
        //
        // 根因（agent_log.txt 实测）：第 N 轮 file_export/excel_gen 真正执行成功后，
        // Phase 3 回注结果，第 N+1 轮模型按指示自然回复"文件已生成/发给你了"——
        // 这句是【合法的收尾确认】，但下方的"空头承诺检测"只查本轮 pendingCalls，
        // 把这句合法确认误判为"声称完成却没调工具"，打回重发；被打回的模型收到
        // "你并没有实际调用任何工具标签"的错误指控，只能再调一次工具自证——
        // 于是又生成一个内容不同的新文件。循环往复，一次用户请求产出 3-6 个文件，
        // 并伴随 5-6 轮无效 LLM 往返（用户视角：卡死几分钟）。
        //
        // 修复：任何一轮有工具真正成功过后，后续轮次的"完成确认"一律放行，
        // 空头承诺检测只在"整个请求没有任何工具成功过"时才生效。
        var anyToolSucceeded = false
        // 同请求内已成功执行的文件类调用签名（工具名|文件名|参数哈希），
        // 完全一致的重复调用直接跳过，防止任何路径下的重复落盘。
        val executedFileSignatures = mutableSetOf<String>()
        // Fix-DupFileGen③：同请求内【按工具名】的文件生成成功次数限流。
        //
        // 根因（agent_log.txt 实测）：Fix-DupFileGen② 的签名去重只拦"参数完全一致"的
        // 重复调用，但模型被打回重试时几乎都会换文件名/微调内容（公馆记录.xlsx →
        // _v2.xlsx → _v3.xlsx；顾澜的日常.txt → 碎碎念.txt → 日常.md），签名每次都
        // 不同，去重完全不触发，导致一次请求产出 3-6 个同主题不同版本的文件。
        //
        // 修复：在签名去重之上叠加"同 toolName 成功次数"上限——同一请求内同一个
        // file_producing 工具最多成功执行 MAX 次（默认 2：1 次正常生成 + 1 次容忍模型
        // 的修订重试），超过后一律拦截并提示模型"已生成过同类文件"。按 toolName 精确
        // 匹配，不误伤"同请求内调用不同文件工具"（Excel+PPT+PDF 各自独立计数）。
        val fileToolSuccessCount = mutableMapOf<String, Int>()

        while (round < maxRounds) {
            val parser = ToolParser()
            val pendingCalls = mutableListOf<ToolCall>()
            val roundText = StringBuilder()

            // v1.49 修复（出戏念旁白）：round<2 且还有未读文件时，本轮属于"文件强制
            // 锁死"的重试阶段——下面会给模型注入"不要回复任何其他内容，直接调用
            // file_read"的强制指令。但模型不一定听话，如果它没有老实吐工具标签、
            // 而是用大段文字复述/解释这条强制指令（如角色第一人称念出"系统要求我…"），
            // 这段文字此前会被 Phase 1 实时流式送到 UI、还会拼进最终存库的回复里，
            // 让用户看到的是"内部调度文字"而不是角色台词。这里在本轮开始前，按
            // pendingFilePaths 在本轮开始时的状态（还未被本轮结果修改）先判断是否
            // 处于锁死阶段，是的话本轮文字只内部保留（供下面拼 currentMessages 用），
            // 不再实时推给用户、也不拼进最终回复——真正的回复要等文件问题解决后
            // 的下一轮才展示。
            val isForcedLockRound = pendingFilePaths.isNotEmpty() && round < 2

            // 方案 A：非锁死轮次启用句子级事前门控（锁死轮次不维护 gate 状态）
            val gate = if (!isForcedLockRound) SentenceGate(anyToolSucceeded, pendingCalls) else null

            // ── Phase 1：流式接收 LLM 输出 ─────────────────────
            // P0-5 修复：使用 chatStream() 替代 chat()，以获取 finish_reason 截断信号。
            // finish_reason=="length" 表示 maxTokens 截断；同时 flush() 的 hasPendingTag
            // 表示有未闭合的 <tool: 标签。两个信号任一为真即判定本轮被截断。
            var truncatedThisRound = false
            try {
                provider.chatStream(currentMessages, systemPrompt, config).collect { item ->
                    when (item) {
                        is ChatStreamItem.TextDelta -> {
                            val result = parser.feed(item.text)

                            // 坑2：必须先 addAll detectedCalls 再做 gate 判断，
                            // 否则同一个 delta 里"标签+收尾语"同时到达时
                            // pendingCalls 还是旧值，会误拦合法收尾语
                            pendingCalls.addAll(result.detectedCalls)

                            if (result.cleanText.isNotEmpty()) {
                                // roundText 始终完整拼接（坑3：不删被拦截的句子）
                                roundText.append(result.cleanText)
                                if (isForcedLockRound) {
                                    // 锁死轮次：roundText 已 append，不 send、不跑 gate
                                } else {
                                    // 方案 A：句子级 gate（迟一句发送 + 滑动窗口）
                                    gate!!.feed(result.cleanText).forEach { send(StreamEvent.TextDelta(it)) }
                                }
                            }
                        }
                        is ChatStreamItem.FinishReason -> {
                            if (item.reason == "length") {
                                truncatedThisRound = true
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e  // 协程取消必须重新抛出
            } catch (e: Throwable) {
                // P1-5 修复：catch Throwable 而非 Exception——Error 子类（OOM 等）
                // 此前击穿 Exception 导致 channelFlow 异常退出、用户无错误提示。
                com.zaijian.zhoumuyun.util.AgentLog.error("LLM", "LLM 调用失败（第 ${round + 1} 轮）", e)
                // 坑7b：异常路径不丢字优先，跳过正则、照单发出 pending 暂存内容
                if (!isForcedLockRound) {
                    gate!!.flush(skipGateCheck = true).forEach { send(StreamEvent.TextDelta(it)) }
                }
                send(StreamEvent.TextDelta("\n\n[抱歉，遇到了一些问题，稍后再试？]"))
                break
            }

            // 处理 flush（流结束后的剩余 buffer）
            // L4 修复：原 flush() 只截断未闭合的 <tool: 前缀，但 buffer 里可能已有
            // 完整的工具标签（流最后一行恰好是完整标签但还没被 feed 处理完），
            // 改为先再 feed 一次空串让 processBuf 扫尽 buffer，再 flush 截断尾部碎片。
            val preFeedResult = parser.feed("")
            pendingCalls.addAll(preFeedResult.detectedCalls)
            if (!isForcedLockRound) {
                // 先 flush gate 的 sendBuffer 残片（未成句的文本），避免丢失
                gate!!.feedRemaining().forEach { send(StreamEvent.TextDelta(it)) }
            }
            if (preFeedResult.cleanText.isNotEmpty()) {
                roundText.append(preFeedResult.cleanText)
                if (!isForcedLockRound) {
                    // 方案 A（v7）：收尾文本走 advanceWindow，和正文同一套拼窗逻辑
                    gate!!.advanceWindow(preFeedResult.cleanText)?.let { send(StreamEvent.TextDelta(it)) }
                }
            }

            val flushResult = parser.flush()
            if (flushResult.cleanText.isNotEmpty()) {
                roundText.append(flushResult.cleanText)
                if (!isForcedLockRound) {
                    gate!!.advanceWindow(flushResult.cleanText)?.let { send(StreamEvent.TextDelta(it)) }
                }
            }

            // 坑7a：round 结束前 flush 最后一句（pendingSentence 里还压着一句没有"下一句"可拼）
            if (!isForcedLockRound) {
                gate!!.flush(skipGateCheck = false).forEach { send(StreamEvent.TextDelta(it)) }
            }

            // P0-5 修复：综合截断信号。两个来源：
            //   1. finish_reason == "length"（来自 chatStream 的 FinishReason 事件）
            //   2. flushResult.hasPendingTag（来自 ToolParser.flush()，P0-6 修复）
            // 任一为真即判定本轮被 maxTokens 截断。用于：
            //   - pendingCalls 为空时：注入续写指令而非直接结束
            //   - pendingCalls 非空时：收尾指令加自查提醒
            val wasTruncated = truncatedThisRound || flushResult.hasPendingTag
            if (wasTruncated) {
                com.zaijian.zhoumuyun.util.AgentLog.warn(
                    "ToolCall",
                    "✂ 本轮输出被截断（finish_reason=${if (truncatedThisRound) "length" else "n/a"}, " +
                        "hasPendingTag=${flushResult.hasPendingTag}，第 ${round + 1} 轮）",
                )
            }

            // 本轮没有工具调用 → 检查是否需要文件读取锁死
            //
            // ── v1.48 程序锁死文件读取（主动模式）── 复核意见一、二 ──────────
            //
            // 两个阶段（串行，不并行）：
            //   重试阶段（round < 2）：打回重发，要求 AI 主动调用 file_read
            //   兜底阶段（round >= 2）：重试耗尽，程序执行 file_read 并注入结果
            //
            // 副作用工具安全性（复核意见二确认）：
            // 本分支只在 pendingCalls.isEmpty() 时进入，即 AI 本轮没调用任何工具。
            // 如果 AI 调用了其他有副作用的工具（file_export / table_export 等），
            // pendingCalls 不为空，会走 Phase 2 正常执行，不会进入重试/兜底分支。
            // 因此：
            //   - 重试阶段：只追加 assistant 回复 + user 指令，不涉及工具执行，
            //     不会重复执行已执行过的副作用工具（走的是复核意见二的 (a) 方案：
            //     "只追加、不重来"）。
            //   - 兜底阶段：执行的是 file_read（只读无副作用），也安全。
            if (pendingCalls.isEmpty()) {
                if (pendingFilePaths.isNotEmpty() && round < 2) {
                    // ── 重试阶段（round 0、1）：打回重发（追加模式）──
                    com.zaijian.zhoumuyun.util.AgentLog.warn(
                        "FileReadLock",
                        "🔒 重试阶段：检测到 ${pendingFilePaths.size} 个未读文件，AI 没调用 file_read（第 ${round + 1} 轮），打回重发",
                    )
                    send(StreamEvent.ToolStarted(
                        toolName = "file_read",
                        params   = emptyMap(),
                        hint     = "🔒 检测到未读文件，正在要求 AI 读取…",
                    ))
                    send(StreamEvent.ToolDone(
                        com.zaijian.zhoumuyun.data.agent.ToolResult(
                            toolName = "file_read",
                            success  = true,
                            content  = "",
                        ),
                    ))
                    // 追加 AI 这轮的回复（不丢弃，不推倒重来——复核意见二 (a) 方案）
                    if (roundText.isNotEmpty()) {
                        currentMessages.add(LLMMessage("assistant", roundText.toString()))
                    }
                    // 注入强制指令，要求 AI 必须调用 file_read
                    val fileList = pendingFilePaths.joinToString("\n") { "  - $it" }
                    currentMessages.add(LLMMessage("user", buildString {
                        append("你刚才的回复没有调用 file_read 工具读取我上传的文件。\n")
                        append("以下文件尚未读取，你必须立即调用 file_read 工具读取它们的全部内容：\n")
                        append(fileList)
                        append("\n\n不要回复任何其他内容，不要解释，不要道歉，")
                        append("直接调用 file_read 工具读取上述文件。")
                        append("这是程序强制要求，不读取文件不允许回复其他内容。")
                    }))
                    roundText.clear()
                    round++
                    continue  // 重新进入循环，让 LLM 重新生成
                }

                if (pendingFilePaths.isNotEmpty() && round >= 2) {
                    // ── 兜底阶段：重试 2 次仍没调用 file_read ──
                    // 程序主动执行 file_read，把结果作为工具结果消息注入。
                    // 复核意见一要求：
                    //   1. 兜底消息格式与正常 file_read 工具结果完全一致（同 role、同结构）
                    //   2. 显式标注来源"（系统自动读取，AI 未主动调用工具）"
                    //   3. 只追加一条工具结果消息，不重新生成整轮回复
                    com.zaijian.zhoumuyun.util.AgentLog.warn(
                        "FileReadLock",
                        "⚠ 兜底阶段：重试 ${round} 次后 AI 仍未调用 file_read，程序自动读取 ${pendingFilePaths.size} 个文件",
                    )

                    val fileReadTool = AgentToolRegistry.get("file_read")
                    val fallbackParts = mutableListOf<String>()

                    for (filePath in pendingFilePaths) {
                        if (fileReadTool != null) {
                            send(StreamEvent.ToolStarted(
                                toolName = "file_read",
                                params   = mapOf("path" to filePath),
                                hint     = "⚠ AI 未主动读取，系统自动读取文件…",
                            ))
                            try {
                                val readResult = fileReadTool.execute(mapOf(
                                    "path"  to filePath,
                                    "lines" to "200",
                                ))
                                if (readResult.success && readResult.content.isNotEmpty()) {
                                    fallbackParts.add(readResult.content)
                                    com.zaijian.zhoumuyun.util.AgentLog.info(
                                        "FileReadLock", "兜底读取成功：$filePath（${readResult.content.length} 字符）",
                                    )
                                } else {
                                    fallbackParts.add("[file_read 读取 $filePath 失败：${readResult.error ?: "未知错误"}]")
                                    com.zaijian.zhoumuyun.util.AgentLog.warn(
                                        "FileReadLock", "兜底读取失败：$filePath（${readResult.error}）",
                                    )
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // P1-5 修复：catch Throwable 而非 Exception，防 Error 子类击穿。
                                // P2 修复：兜底消息不暴露 e.message（可能含绝对路径），异常详情已由下方 AgentLog.error 记录
                                fallbackParts.add("[file_read 读取 $filePath 异常]")
                                com.zaijian.zhoumuyun.util.AgentLog.error(
                                    "FileReadLock", "兜底读取异常：$filePath", e,
                                )
                            }
                            send(StreamEvent.ToolDone(
                                com.zaijian.zhoumuyun.data.agent.ToolResult(
                                    toolName = "file_read",
                                    success  = true,
                                    content  = "（系统自动读取，AI 未主动调用工具）",
                                ),
                            ))
                        }
                    }

                    // 构建兜底注入消息——与 Phase 3 正常工具结果格式完全一致
                    // （role="user"，"[工具执行结果]" 前缀，末尾引导语），
                    // 仅在内容前加来源标注（复核意见一第 2、3 条）
                    val fallbackContent = buildString {
                        appendLine("[工具执行结果]")
                        appendLine("（系统自动读取，AI 未主动调用工具）")
                        fallbackParts.forEachIndexed { i, r ->
                            if (i > 0) appendLine()
                            append(r)
                        }
                        appendLine()
                        appendLine()
                        append("请根据以上工具返回的信息，用你自己的语气回复我。不要提及工具或搜索的过程。")
                    }

                    // 追加 AI 这轮的回复（不推倒重来——复核意见一第 4 条）
                    if (roundText.isNotEmpty()) {
                        currentMessages.add(LLMMessage("assistant", roundText.toString()))
                    }
                    // 追加兜底工具结果消息
                    currentMessages.add(LLMMessage("user", fallbackContent))

                    // v1.49 修复：兜底读取（不管每个文件成功与否）也要通知调用方持久化
                    // "已处理过"的凭证——原先只在本次调用的 pendingFilePaths.clear() 里
                    // "记住"了，函数一返回就丢失，下条新消息又会从零重新判定"还没读"，
                    // 是"系统反复强制要求读取文件"这个复发bug的根因之一。
                    for (filePath in pendingFilePaths) {
                        send(StreamEvent.FileReadConfirmed(
                            filePath = filePath,
                            fileName = filePath.substringAfterLast('/').substringAfterLast('\\'),
                        ))
                    }

                    // 清空 pendingFilePaths，避免兜底重复触发
                    pendingFilePaths.clear()

                    roundText.clear()
                    round++
                    continue  // 让 LLM 基于注入的内容生成回复
                }

                // P0-5 修复：截断续写。如果本轮被 maxTokens 截断（wasTruncated=true）
                // 且没有完整的工具调用（pendingCalls 为空），说明模型正在写工具标签
                // 或正文时被截断。原行为：截断的半截标签被 flush 丢弃后，整轮被
                // 误判为"模型没调工具"，直接 break 结束——是"没有任何报错记录"的
                // 根因。改为注入续写指令，让模型在下一轮继续完成。
                if (wasTruncated && round < maxRounds - 1) {
                    if (roundText.isNotEmpty()) {
                        currentMessages.add(LLMMessage("assistant", roundText.toString()))
                    }
                    currentMessages.add(LLMMessage("user",
                        "你上一轮的回复被截断了，请继续完成。" +
                        "如果原本要调用工具，请重新发起完整的工具调用标签。" +
                        "不要重复已经输出过的内容，直接从截断处继续。"
                    ))
                    roundText.clear()
                    round++
                    continue
                }

                // Fix-3：增强虚假完成声明检测——工具失败后 LLM 仍声称成功。
                // 场景：上一轮 pdf_export 超时失败 → anyFailed → Phase 3 告知 LLM 失败，
                // 本轮 LLM 说"PDF已发送"但没有重试 pdf_export（可能调了别的工具如 zip_export，
                // pendingCalls 非空导致原嘴替检测的 pendingCalls.isNotEmpty() → false 被跳过）。
                // 判定：文本匹配完成声明正则 + 本轮 pendingCalls 不包含任何之前失败的工具 → 拦截。
                // 如果 LLM 正在重试之前失败的工具（pendingCalls 包含 previousFailedTools 中的工具），
                // 则放行——它在尝试修复，应该给机会（配合 Fix-5 的更长超时，重试更可能成功）。
                if (previousFailedTools.isNotEmpty() && round < maxRounds - 1) {
                    val claimsCompletion = FALSE_COMPLETION_CLAIM_REGEX.containsMatchIn(roundText.toString())
                    val retryingFailedTool = pendingCalls.any { it.toolName in previousFailedTools }
                    if (claimsCompletion && !retryingFailedTool) {
                        com.zaijian.zhoumuyun.util.AgentLog.warn(
                            "ToolCall",
                            "⚠ 检测到工具失败后的虚假完成声明（上轮失败: $previousFailedTools，本轮未重试），打回重发（第 ${round + 1} 轮）",
                        )
                        if (roundText.isNotEmpty()) {
                            currentMessages.add(LLMMessage("assistant", roundText.toString()))
                        }
                        currentMessages.add(LLMMessage("user",
                            "你刚才说文件已经生成/发送了，但上一轮的工具执行实际上失败了" +
                            "（失败的工具：${previousFailedTools.joinToString()}）。" +
                            "请重新调用对应的工具标签真正执行这个操作；" +
                            "如果暂时无法完成，请明确告诉我还没做成，不要再说「已经完成」。"
                        ))
                        roundText.clear()
                        round++
                        continue
                    }
                }

                // 兜底（嘴替检测）：本轮没有截断、也没有工具调用，但文字里却声称
                // 文件/导出已完成——判定为"空头承诺"，打回重发而不是放任这句话
                // 原样展示给用户（用户会看到"已生成"却没有任何文件卡片）。
                // 与上面的截断续写分支互斥（wasTruncated 已在前面处理并 continue），
                // 走到这里说明 wasTruncated 为 false，纯粹是模型主动选择不调用工具。
                //
                // Fix-DupFileGen①：加 anyToolSucceeded 前置条件——本请求此前已有工具
                // 真正成功过时，这句"已完成"是合法收尾确认，不是空头承诺，直接放行
                // （原实现误伤此场景导致重复生成 + 重试风暴，见上方声明处注释）。
                if (round < maxRounds - 1 && !anyToolSucceeded && claimsFileCompletionWithoutToolCall(roundText.toString(), pendingCalls)) {
                    com.zaijian.zhoumuyun.util.AgentLog.warn(
                        "ToolCall",
                        "⚠ 检测到疑似空头承诺（声称已生成/已发送但本轮无工具调用），打回重发（第 ${round + 1} 轮）",
                    )
                    if (roundText.isNotEmpty()) {
                        currentMessages.add(LLMMessage("assistant", roundText.toString()))
                    }
                    currentMessages.add(LLMMessage("user",
                        "你刚才的回复里说文件已经生成/发送了，但你这一轮并没有实际调用任何工具标签，" +
                        "文件并没有真正生成。请立即调用对应的工具标签（如 excel_gen/pptx_gen/pdf_export 等）" +
                        "真正执行这个操作；如果暂时无法完成，请明确告诉我还没做成，不要再说「已经完成」。"
                    ))
                    roundText.clear()
                    round++
                    continue
                }

                // 修复：空头承诺重试耗尽兜底。
                // 根因：上面的分支只处理 round < maxRounds - 1 的情况——若模型在
                // 最后一轮（round == maxRounds - 1）依然只字面声称"已生成/已发送"
                // 却从未真正吐出工具标签，原实现会直接 break，把这句假话原样
                // 透传给用户（第 140 行 KDoc 也写明"超过此轮数后直接返回最后一次
                // LLM 的原始输出，不再拦截"）。用户看到的是角色信誓旦旦"发给你了"，
                // 但工具从未被调用过一次——磁盘上没有文件，diag_export_log 里
                // 也不会有任何 pdf_export/pptx_gen/docx_gen/html_gen 相关记录，
                // 因为 ToolCall 的 ▶/✔/⚠ 日志只在真正 dispatch 到工具执行时才会写入。
                // 这正是"PDF/PPT/HTML 角色说发了，用户这边看不到、没落盘、
                // 导出日志也查不到（多次重试）"这个反馈的直接根因。
                //
                // 处理方式：
                //   1. 用 AgentLog.error 写一条明确的终态失败记录，之后用户导出
                //      诊断日志时至少能看到"确实尝试过、且最终判定失败"，而不是
                //      像现在这样连一条痕迹都没有。
                //   2. 追加一句诚实的更正文本推送给用户（此前几轮的假话已经在
                //      流式阶段实时吐给用户了，没法撤回，只能在后面补一句更正，
                //      不能让假话就这样成为最终定论）。
                //   3. 把更正文本一并并入 roundText，保证持久化到数据库的消息
                //      内容和用户在界面上实际看到的一致。
                // Fix-DupFileGen①：同上，已有工具成功过时这是合法收尾，不再追加更正话术。
                if (!anyToolSucceeded && claimsFileCompletionWithoutToolCall(roundText.toString(), pendingCalls)) {
                    com.zaijian.zhoumuyun.util.AgentLog.error(
                        "ToolCall",
                        "⛔ 空头承诺重试耗尽（已达 ${maxRounds} 轮上限仍未调用任何工具），" +
                            "已拦截虚假完成话术，原文前 200 字：${roundText.toString().take(200)}",
                    )
                    val correction = "\n\n（这个我还没能真的做成，工具没调用上，容我重新试一次，或者你再跟我说一声。）"
                    send(StreamEvent.TextDelta(correction))
                    roundText.append(correction)
                }

                // Fix-3 最终兜底：工具失败后 LLM 仍声称成功（最后一轮，重试已耗尽）。
                // 与上面的纯嘴替兜底互补：上面的只管 pendingCalls 为空的情况，
                // 这里管 pendingCalls 非空但不含失败工具重试的情况。
                if (previousFailedTools.isNotEmpty()) {
                    val claimsCompletion = FALSE_COMPLETION_CLAIM_REGEX.containsMatchIn(roundText.toString())
                    val retryingFailedTool = pendingCalls.any { it.toolName in previousFailedTools }
                    if (claimsCompletion && !retryingFailedTool) {
                        com.zaijian.zhoumuyun.util.AgentLog.error(
                            "ToolCall",
                            "⛔ 工具失败后虚假完成声明重试耗尽（${maxRounds} 轮上限），" +
                                "失败工具: $previousFailedTools，原文前 200 字：${roundText.toString().take(200)}",
                        )
                        val correction = "\n\n（这个之前尝试时出了点问题，还没真的做成，容我重新试一次，或者你再跟我说一声。）"
                        send(StreamEvent.TextDelta(correction))
                        roundText.append(correction)
                    }
                }

                break
            }

            // v1.48：如果 AI 调用了 file_read，从 pendingFilePaths 移除已读路径
            // （AI 主动读了，就不再强制了）
            for (call in pendingCalls) {
                if (call.toolName == "file_read") {
                    val readPath = call.params["path"]
                    if (readPath != null && pendingFilePaths.remove(readPath)) {
                        com.zaijian.zhoumuyun.util.AgentLog.info(
                            "FileReadLock", "✅ AI 主动调用了 file_read 读取：$readPath",
                        )
                        // v1.49 修复：通知调用方持久化"已读"凭证，避免下条新消息
                        // 重新判定这个文件"还没读"、重新触发强制锁死流程。
                        send(StreamEvent.FileReadConfirmed(
                            filePath = readPath,
                            fileName = readPath.substringAfterLast('/').substringAfterLast('\\'),
                        ))
                    }
                }
            }

            // ── Phase 2：串行执行工具调用 ─────────────────────
            // 方案 B（3.2 节）：调用工具轮次的纯文本篇幅统计埋点
            logVerboseToolCallRound(roundText.toString(), pendingCalls)

            val toolResultParts = mutableListOf<String>()
            // 根因修复（静默失败）：此前 Phase 3 不区分成败，一律指示 LLM
            // "不要提及工具或搜索的过程"——工具真失败时，这条指令连同失败原因
            // 一起把"这次操作没成功"这件事也一并瞒着用户，导致用户看到的是
            // 角色若无其事地继续聊天，没有文件、没有错误提示、什么都没发生，
            // 而实际上工具已经执行过且已失败。用本轮是否有任何工具失败/被禁用/
            // 未注册来决定 Phase 3 给 LLM 的收尾指令（见下方 anyFailed 分支）。
            var anyFailed = false
            // Fix-限流嘴替：Fix-DupFileGen③ 的去重限流拦截此前完全不设任何标志——
            // 走的是普通 continue，toolResultParts 里只留一句"本会话已生成过同类
            // 文件"，Phase 3 收尾指令按 anyFailed=false 处理，给的是中性的"用你自己
            // 的语气回复我"。但限流拦截意味着这次调用实际上【没有产出新文件】，
            // 语义上更接近失败而非成功，中性指令没有约束模型必须如实说明"这次没
            // 生成"，模型容易把限流提示和同批次里其它成功结果糅合成模糊的"最后
            // 还是生成了"（agent_log.txt 实测：excel_gen 先被拒后又成功一次，模型
            // 把两次结果混述成单一的"成功"结论）。单独标记出来，走专门的收尾指令。
            var anyRateLimited = false

            for (call in pendingCalls) {
                if (call.toolName in disabledToolNames) {
                    // 执行层强制拦截：该工具在当前场景被禁用（即使模型生成了标签，
                    // 也不真正执行）。与 prompt 层的描述过滤是同一份排除名单，
                    // 这里是兜底的第二道防线。
                    com.zaijian.zhoumuyun.util.AgentLog.warn("ToolCall", "⊘ ${call.toolName} 在当前场景被禁用，跳过执行")
                    toolResultParts.add("[工具 ${call.toolName} 在当前场景不可用]")
                    anyFailed = true
                    continue
                }
                val tool = AgentToolRegistry.get(call.toolName)
                if (tool == null) {
                    // 未注册的工具：记录并跳过
                    com.zaijian.zhoumuyun.util.AgentLog.warn("ToolCall", "⊘ ${call.toolName} 未注册（LLM 生成了该工具标签但 registry 里没有），跳过")
                    toolResultParts.add("[工具 ${call.toolName} 不可用]")
                    anyFailed = true
                    continue
                }

                // Fix-DupFileGen②：同一请求内【完全一致】的文件生成调用（同工具+同文件名+
                // 同参数）直接跳过——第一次已经成功落盘，再执行只会多产出一个内容雷同的
                // 新文件。只挡"完全一致"的重复，同文件名但参数/内容不同的调用视为
                // 模型的修订意图，正常放行。签名仅在成功后登记（见下方），
                // 失败后的重试不会被误挡。
                val fileSignature = fileCallSignature(call)
                if (fileSignature != null && fileSignature in executedFileSignatures) {
                    com.zaijian.zhoumuyun.util.AgentLog.warn(
                        "ToolCall", "⊘ ${call.toolName} 与此前成功调用完全重复，跳过（防止重复生成文件）",
                    )
                    toolResultParts.add("[${call.toolName}：相同文件已生成，无需重复执行]")
                    continue
                }
                // Fix-DupFileGen③：按工具名限流——同请求内同一 file_producing 工具成功
                // 次数已达上限，判定为"换名反复生成"（见 fileToolSuccessCount 顶部根因），
                // 直接拦截不再执行，告知模型已生成过同类文件、如需新增请先等用户确认。
                // 用 fileSignature != null 作守卫（等价于 call.toolName in FILE_PRODUCING_TOOLS，
                // 且复用已算好的值），非文件类工具不进入此分支。
                if (fileSignature != null &&
                    (fileToolSuccessCount[call.toolName] ?: 0) >= MAX_FILE_TOOL_SUCCESSES_PER_REQUEST
                ) {
                    com.zaijian.zhoumuyun.util.AgentLog.warn(
                        "ToolCall",
                        "⊘ ${call.toolName} 本请求已成功 ${MAX_FILE_TOOL_SUCCESSES_PER_REQUEST} 次，" +
                            "判定为换名反复生成，跳过执行（防止重复文件）",
                    )
                    toolResultParts.add(
                        "[${call.toolName}：本次调用被拦截，未生成新文件——" +
                            "本会话该类文件已生成过 ${MAX_FILE_TOOL_SUCCESSES_PER_REQUEST} 次，" +
                            "如确需新增不同内容的同类文件，请先告知用户并等待确认]",
                    )
                    anyRateLimited = true
                    continue
                }

                // 通知 UI 工具开始
                send(StreamEvent.ToolStarted(
                    toolName = call.toolName,
                    params   = call.params,
                    hint     = null,
                ))

                // 执行工具（带超时 + §2.1.2 降级策略状态机）
                val toolResult = executeWithDegradation(
                    call              = call,
                    tool              = tool,
                    provider          = provider,
                    disabledToolNames = disabledToolNames,
                    activityContext   = activityContext,
                    goalContext       = messages.lastOrNull()?.content?.take(200) ?: "",
                )

                // 通知 UI 工具完成
                send(StreamEvent.ToolDone(toolResult))

                if (!toolResult.success) {
                    anyFailed = true
                    // Fix-3：记录失败的工具名，供下一轮虚假完成声明检测使用
                    previousFailedTools.add(call.toolName)
                } else {
                    // Fix-DupFileGen：登记成功——放行后续轮次的合法完成确认，
                    // 并登记文件调用签名防止完全重复执行。
                    anyToolSucceeded = true
                    fileSignature?.let {
                        executedFileSignatures.add(it)
                        // Fix-DupFileGen③：按工具名累计成功次数，超限后下一轮拦截换名重生成。
                        fileToolSuccessCount[call.toolName] =
                            (fileToolSuccessCount[call.toolName] ?: 0) + 1
                    }
                }
                toolResultParts.add(
                    if (toolResult.success) toolResult.content
                    else "[${toolResult.toolName} 执行失败: ${toolResult.error ?: "未知错误"}]"
                )
            }

            // ── Phase 3：构建工具结果消息，回注 context ────────
            //
            // 回注格式：
            //   role = "user"（模拟用户把工具结果反馈给 LLM）
            //   content = 工具结果文本块，多个工具用空行分隔
            //
            // 为什么用 "user" 而非 "tool" role：
            //   OpenAI 兼容协议各提供商对 "tool" role 支持参差不齐；
            //   "user" role 所有提供商均支持，且语义清晰。
            //
            // 收尾指令按 anyFailed / wasTruncated 分支：
            //   全部成功且未截断 → 维持原指令，不解释工具/搜索过程，保持角色沉浸感。
            //   任一失败 → 明确要求 LLM 用角色口吻告知用户"这件事没做成"，
            //     不能假装已完成、也不能对失败只字不提；技术细节（工具名、
            //     报错信息）仍不暴露给用户，只是"失败"这件事本身必须被看见。
            //   被截断但已有工具成功（P0-5）→ 要求模型自查是否有遗漏的工具调用，
            //     而不是默认全部做完后自信地说"五个都发了"。截断发生在工具标签
            //     之间时，已完成的标签会被正常解析执行，但模型可能还计划了更多
            //     工具调用——原行为下模型顺着上下文自信地报告"全部完成"，实际
            //     只做了一半。
            val toolResultContent = buildString {
                appendLine("[工具执行结果]")
                toolResultParts.forEachIndexed { i, r ->
                    if (i > 0) appendLine()
                    append(r)
                }
                appendLine()
                appendLine()
                if (anyFailed) {
                    append("以上信息中有操作未成功。请用你自己的语气自然地告诉我这件事没有做成" +
                        "（不需要暴露工具名称、报错信息等技术细节，也不要堆砌道歉套话），" +
                        "可以问我要不要换个方式再试。禁止假装该操作已经完成，" +
                        "禁止对失败这件事只字不提、顾左右而言他。")
                } else if (anyRateLimited) {
                    // Fix-限流嘴替：以上工具结果里，"本次调用被拦截"和其它调用的
                    // "已生成"可能同时存在（比如同一批里一个文件被拦截、另一个
                    // 文件正常生成）。明确要求模型逐条对应，不能把两种不同结果
                    // 揉成一句笼统的"都做好了"或含糊带过限流的那一条。
                    append("以上信息中，有的操作是正常执行的结果，有的操作被系统按重复限制拦截、" +
                        "本次并没有真正生成新文件。请逐条对应着说清楚：哪些确实做好了，" +
                        "哪些这次没有生成（不需要暴露工具名称、报错信息等技术细节）。" +
                        "禁止把被拦截的那部分也说成'已完成'，禁止笼统地说'都发给你了'。")
                } else if (wasTruncated) {
                    // P0-5 修复：截断自查。模型上一轮输出被 maxTokens 截断，
                    // 已执行的工具可能只是计划中的一部分。要求模型检查是否
                    // 还有遗漏，而不是默认全部做完。
                    append("注意：你上一轮的输出被截断了，以上可能只是部分工具的执行结果。" +
                        "请检查我原始请求中要求的所有操作是否都已执行。" +
                        "如果还有遗漏的操作，请继续调用对应工具完成；" +
                        "如果已经全部完成，请用你自己的语气回复我，不要提及工具或搜索的过程。" +
                        "禁止在不确定的情况下声称所有操作都已完成。")
                } else {
                    append("请根据以上工具返回的信息，用你自己的语气回复我。不要提及工具或搜索的过程。")
                }
            }

            // 将本轮 LLM 输出 + 工具结果追加到消息历史
            if (roundText.isNotEmpty()) {
                currentMessages.add(LLMMessage("assistant", roundText.toString()))
            }
            currentMessages.add(LLMMessage("user", toolResultContent))

            send(StreamEvent.RoundDone)
            round++
        }

        // 收尾保护：若因达到 maxRounds 自然退出循环（而非 pendingCalls.isEmpty() 主动 break），
        // 说明最后一轮工具已执行、结果已 append 进 currentMessages 但没有下一次 LLM 消费，
        // 必须再发一次不解析新工具调用的收尾请求，保证用户总能看到回应。
        if (round == maxRounds) {
            // 此分支是独立于主循环的第二套 Phase 1 实现，此前直接 send 没有 gate。
            val tailPendingCalls = mutableListOf<ToolCall>()
            val tailGate = SentenceGate(anyToolSucceeded, tailPendingCalls)
            try {
                val parser = ToolParser()
                // P0-5: 使用 chatStream() 保持一致
                provider.chatStream(currentMessages, systemPrompt, config).collect { item ->
                    if (item is ChatStreamItem.TextDelta) {
                        val result = parser.feed(item.text)
                        tailPendingCalls.addAll(result.detectedCalls)
                        if (result.cleanText.isNotEmpty()) {
                            tailGate.feed(result.cleanText).forEach { send(StreamEvent.TextDelta(it)) }
                        }
                    }
                }
                // L4 修复同主循环：先 feed("") 扫尽 buffer 里已完整的标签，
                // 再 flush() 截断尾部未闭合碎片，避免末轮完整工具标签被丢弃。
                val preFeed = parser.feed("")
                tailPendingCalls.addAll(preFeed.detectedCalls)
                tailGate.feedRemaining().forEach { send(StreamEvent.TextDelta(it)) }
                if (preFeed.cleanText.isNotEmpty()) {
                    tailGate.advanceWindow(preFeed.cleanText)?.let { send(StreamEvent.TextDelta(it)) }
                }
                val final = parser.flush()
                if (final.cleanText.isNotEmpty()) {
                    tailGate.advanceWindow(final.cleanText)?.let { send(StreamEvent.TextDelta(it)) }
                }
                tailGate.flush(skipGateCheck = false).forEach { send(StreamEvent.TextDelta(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // P0-7 修复：末轮收尾 LLM 流式此前 catch(Exception) 抓不住 Error 子类
                // （如 OutOfMemoryError），且完全没有日志——异常被静默吞掉，
                // 用户只看到一句"[抱歉…]"却无法在日志中定位根因。改为 Throwable
                // 并补齐 AgentLog.error，与主循环 LLM catch（line 327）保持一致。
                com.zaijian.zhoumuyun.util.AgentLog.error(
                    "ToolCall", "末轮收尾 LLM 流式失败（round=$round）", e,
                )
                // 坑7b：异常路径不丢字优先，跳过正则、照单发出 pending 暂存内容
                tailGate.flush(skipGateCheck = true).forEach { send(StreamEvent.TextDelta(it)) }
                send(StreamEvent.TextDelta("\n\n[抱歉，遇到了一些问题，稍后再试？]"))
            }
        }
    }

    // ── P2 日志脱敏 ──────────────────────────────────────────
    /** 敏感参数 key：其 value 可能含大段文件内容/用户私密文本，日志中只记长度+预览。 */
    private val SENSITIVE_PARAM_KEYS = setOf("content", "text", "body", "data", "html")

    /**
     * P2 修复：对工具参数做脱敏后再写日志。
     *
     * 原先 `call.params.toString().take(500)` 会把 `file_export` 的 `content`（完整文件内容）、
     * `translate` 的 `text`（用户私密文本）等原样写进可导出的 agent_log.txt。现在对敏感 key
     * 只记录长度+前 20 字符预览，非敏感 key 限制 100 字符。
     */
    private fun sanitizeParams(params: Map<String, String>): String =
        params.entries.joinToString(", ", "{", "}") { (k, v) ->
            val display = if (k in SENSITIVE_PARAM_KEYS) {
                if (v.length <= 20) "***(${v.length}字符)"
                else "\"${v.take(20)}…***\"(${v.length}字符)"
            } else {
                v.take(100)
            }
            "$k=$display"
        }

    /**
     * 带超时的工具执行。
     *
     * 超过 [TOOL_TIMEOUT_MS] 返回超时 ToolResult，不抛出异常。
     *
     * ── Fix-孤儿文件（取消竞态）──────────────────────────────────────
     * 背景（详见 agent_log.txt 04:30:41 那次 excel_gen 事故复盘）：`sendMessage()`/
     * `ChatSessionDelegate.init()` 在新消息进入或切换角色时会无条件
     * `getReplyJob()?.cancel()`。若此时正巧有 excel_gen/pptx_gen 这类耗时工具在
     * 执行 POI 写文件（阻塞式调用，内部无挂起点），协作式取消在阻塞期间无法生效，
     * 文件本身会正常写完落盘——但 `withTimeout{}` 在把结果交还调用者的那一刻
     * （唯一的挂起点）发现外层 Job 已取消，直接抛出 `CancellationException`，
     * 原实现在这里 `throw e` 且不写任何日志：文件已经在磁盘上，但 metaJson 永远
     * 到不了 `send(StreamEvent.ToolDone)`，也就永远进不了数据库/文件卡——
     * 文件变成没人知道存在的孤儿，用户看到的是"什么都没发生"。
     *
     * 三层修复：
     * ①（本函数）取消必须留痕：不再对 CancellationException 静默 throw。
     * ②（本函数）用 [NonCancellable] 包住工具执行与日志记录整个过程，确保只要
     *   进了这个保护区就能拿到确定的 ToolResult（不再被外层取消打断到一半），
     *   跳出保护区后再补一次显式判断：若调用方（replyJob）确实已经取消，走
     *   [recoverOrphanedToolResult] 兜底（私聊场景直接落一条系统消息把文件卡片
     *   找补回来），再重新抛出取消信号，维持结构化并发语义不变。
     * ③ 从源头减少打断发生（[ChatMessageOrchestrator]/[ChatSessionDelegate] 等
     *   调用方在决定要不要 cancel 旧 job 前，先判断是否正有工具在执行）是更大的
     *   改动，本次先只做这里的兜底层，具体见随附说明。
     *
     * @param activityContext 非 null 时，孤儿恢复仅在 sceneType==CHAT（私聊）落库
     *   系统消息找补文件卡片；圆桌/后台工作流场景消息表结构不同，暂只保留
     *   AgentLog 里的完整 absolutePath 供人工找回，不贸然跨结构写入。
     */
    private suspend fun executeWithTimeout(
        call: ToolCall,
        tool: AgentTool,
        activityContext: ActivityContext? = null,
    ): ToolResult {
        // U-7 修复：执行前检查协程是否已被取消，避免外层已取消后仍启动工具副作用。
        currentCoroutineContext().ensureActive()
        // 必须在切换到 NonCancellable 之前拿到调用方（replyJob 一侧）的 Job 引用——
        // 进入 NonCancellable 保护区后，ambient Job 会变成 NonCancellable 本身，
        // 不再能反映外层协程真实的取消状态。
        val callerJob = currentCoroutineContext()[Job]

        // Fix-5：LLM 调用型工具使用更长超时，避免内部 LLM 往返导致 30s 超时误杀。
        val effectiveTimeout = if (call.toolName in LLM_DEPENDENT_TOOLS) LLM_TOOL_TIMEOUT_MS else TOOL_TIMEOUT_MS

        val result = withContext(Dispatchers.IO + NonCancellable) {
            val startTime = System.currentTimeMillis()
            val timeoutLabel = if (call.toolName in LLM_DEPENDENT_TOOLS) "LLM" else "标准"
            com.zaijian.zhoumuyun.util.AgentLog.info("ToolCall", "▶ ${call.toolName} 开始（${timeoutLabel}超时 ${effectiveTimeout / 1000}s）\n  params: ${sanitizeParams(call.params)}")
            try {
                // Fix-StuckTimeout（竞速超时，根因：agent_log "pptx_gen 超时（90s，实际 210497ms）"）：
                // 原实现 kotlinx.coroutines.withTimeout 直接包住 tool.execute——但这些
                // 工具内部是阻塞式调用（POI 写盘、chatSyncWithRetry 底层 HTTP），执行
                // 过程没有挂起点，协作式取消在阻塞期间无法生效：标称 90s 超时，实际
                // 硬生生等了 210s 直到阻塞调用自己返回才"触发超时"，用户全程卡在
                // "正在生成"。
                // 改为竞速模式：工具执行放进独立 Deferred（NonCancellable，不受超时
                // 取消影响），withTimeoutOrNull 只包裹"等待结果"这个可挂起动作——
                // 超时到点立即返回 null、主流程马上继续，不再被阻塞调用绑架。
                // 被甩下的后台执行若最终成功产出文件，由 watcher 走孤儿文件兜底投递，
                // 避免"磁盘有文件、用户看不见"。
                val deferred = async(kotlinx.coroutines.NonCancellable) {
                    tool.execute(call.params)
                }
                val raced = withTimeoutOrNull(effectiveTimeout) { deferred.await() }
                if (raced == null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    com.zaijian.zhoumuyun.util.AgentLog.error(
                        "ToolCall",
                        "⏱ ${call.toolName} 超时（${effectiveTimeout / 1000}s，实际 ${elapsed}ms），主流程已继续，后台执行若产出文件将走孤儿兜底",
                    )
                    kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
                        try {
                            val late = deferred.await()
                            if (late.success) {
                                com.zaijian.zhoumuyun.util.AgentLog.warn(
                                    "ToolCall",
                                    "⚠ ${call.toolName} 超时后最终执行成功，按孤儿文件处理",
                                )
                                recoverOrphanedToolResult(late, activityContext)
                            }
                        } catch (_: Throwable) {
                            // 后台 watcher 只做补救，任何异常都不再影响主流程
                        }
                    }
                    ToolResult(
                        toolName = call.toolName,
                        success  = false,
                        content  = "[${call.toolName} 执行超时（${effectiveTimeout / 1000}s）]",
                        error    = "timeout",
                    )
                } else {
                    raced.also { r ->
                        val elapsed = System.currentTimeMillis() - startTime
                        if (r.success) {
                            // P2 修复：file_read/url_fetch 等工具的成功结果是完整文件/网页内容，
                            // 原先 take(300) 会把前 300 字符写进可导出日志，现改为只记长度。
                            val resultPreview = if (call.toolName in setOf("file_read", "url_fetch")) {
                                "[内容长度: ${r.content.length}字符]"
                            } else {
                                r.content.take(300)
                            }
                            com.zaijian.zhoumuyun.util.AgentLog.info(
                                "ToolCall",
                                "✔ ${call.toolName} 成功（用时 ${elapsed}ms）\n  result: $resultPreview${if (r.tablePayloadJson != null) "\n  [附带 tablePayloadJson]" else ""}",
                            )
                        } else {
                            com.zaijian.zhoumuyun.util.AgentLog.warn(
                                "ToolCall",
                                "⚠ ${call.toolName} 业务失败（用时 ${elapsed}ms）\n  error: ${r.error}",
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 理论上不会走到这里：NonCancellable 屏蔽了"调用方 replyJob 被取消"
                // 这一信号源，withTimeout 自身的取消已经在上面单独 catch。保留这条
                // 兜底日志纯粹是防御性的——万一出现其它我们没预料到的取消来源，
                // 也不会退回"静默吞掉、死得不明不白"的老问题。
                val elapsed = System.currentTimeMillis() - startTime
                com.zaijian.zhoumuyun.util.AgentLog.error(
                    "ToolCall",
                    "⚠ ${call.toolName} 在 NonCancellable 保护区内意外收到取消信号（用时 ${elapsed}ms），已记录，重新抛出",
                )
                throw e
            } catch (e: Throwable) {
                // 修复（并发工具调用静默卡死）：原先是 catch (e: Exception)，抓不住
                // Error 子类（如 Apache POI 在 Android 上触发的 NoClassDefFoundError）。
                // 单个工具内部若踩到这类问题，异常会从这里直接向上击穿到
                // ChatMessageOrchestrator，导致整条回复协程静默终止——用户只看到
                // 流式气泡的"…"突然消失，没有任何错误提示。这里是"单个工具执行"
                // 这一层的最后防线，兜住 Throwable 后同样转成正常的失败 ToolResult，
                // 不让任何单个工具的意外崩溃拖垮整轮多工具调用。
                val elapsed = System.currentTimeMillis() - startTime
                com.zaijian.zhoumuyun.util.AgentLog.error(
                    "ToolCall",
                    "✗ ${call.toolName} 异常（用时 ${elapsed}ms, exceptionType=${e::class.qualifiedName ?: e.javaClass.name}）",
                    e,
                )
                // P2 修复：catch-all 异常处理不暴露 e.message（可能含绝对路径/堆栈），
                // 完整异常已由上方 AgentLog.error 记录，对外只回固定文案 + 稳定错误码。
                ToolResult(
                    toolName = call.toolName,
                    success  = false,
                    content  = "[${call.toolName} 执行异常]",
                    error    = "exception",
                )
            }
        }

        // 跳出 NonCancellable 保护区：工具执行 + 落盘（如果有）此时已经确定性地
        // 跑完，result 里的 metaJson（如果是文件类工具）是可信的。如果调用方
        // 早已被取消，正常的 send(StreamEvent.ToolDone(result)) 链路必然会在
        // 下一个挂起点被打断，这条结果永远不会被上层消费——这里做最后的兜底。
        if (callerJob?.isCancelled == true) {
            com.zaijian.zhoumuyun.util.AgentLog.warn(
                "ToolCall",
                "⚠ ${call.toolName} 执行完毕，但调用方在执行期间已被取消" +
                    "（新消息打断/切换角色/退出圆桌等）——正常投递链路已中断，尝试孤儿兜底",
            )
            recoverOrphanedToolResult(result, activityContext)
            // 兜底完成后把取消信号正常传播出去，维持结构化并发语义——
            // NonCancellable 只是保护了"落盘/记录结果"这一步，不代表这次
            // 工具调用在整条回复流程里仍然算数。
            throw CancellationException("caller job cancelled during ${call.toolName} execution")
        }

        return result
    }

    /**
     * 孤儿文件兜底（Fix-孤儿文件，见 [executeWithTimeout] 顶部说明）。
     *
     * 只在确认调用方已取消、且工具本身执行成功时才有意义——工具失败/超时
     * 不产生需要找补的文件，直接跳过。
     */
    private suspend fun recoverOrphanedToolResult(result: ToolResult, activityContext: ActivityContext?) {
        if (!result.success) return
        val metaJson = extractOrphanFileMetaJson(result.content) ?: return

        com.zaijian.zhoumuyun.util.AgentLog.warn(
            "ToolCall",
            "🗄 检测到孤儿文件（${result.toolName}），回复流程已被取消，正常投递链路中断\n  meta: $metaJson",
        )

        if (activityContext?.sceneType != AgentActivityRepository.SceneType.CHAT) {
            // 圆桌 / 后台工作流场景：消息表结构与私聊不同（RoundtableMessage /
            // WorkflowStepResult），贸然跨结构塞一条私聊 MessageEntity 风险更高
            // （角色归属、UI 渲染路径都对不上）。这里先只保证上面的 AgentLog 留下
            // 完整 absolutePath，需要时可以手动从 vault 目录找回文件；后续如果
            // 圆桌/后台任务这类孤儿文件也频繁出现，再单独为它们各自的持久化
            // 方式实现对应的找补逻辑（③ 从源头减少打断发生是更根本的解法）。
            return
        }

        try {
            AppContainer.instance.messageRepo.insert(
                com.zaijian.zhoumuyun.data.db.entity.MessageEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    characterId = activityContext.characterId,
                    role = "system",
                    content = "⚠ 上一条请求被打断，但下面这个文件已经生成好了：",
                    createdAt = System.currentTimeMillis(),
                    exportedFileJson = metaJson,
                    exportedFilesJson = "[$metaJson]",
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // P1-5 修复：catch Throwable 而非 Exception，防 Error 子类击穿
            com.zaijian.zhoumuyun.util.AgentLog.error("ToolCall", "孤儿文件兜底落库失败（不影响主流程，文件仍留在磁盘上）", e)
        }
    }

    /**
     * 从工具结果文本里提取文件元数据 JSON（fileName/absolutePath 字段齐全才算数）。
     *
     * P3-2（元数据解析三份副本统一）：唯一实现已下沉到同层的
     * `data.agent.ExportedFileMeta.kt`（[extractExportedFileJson]），本函数不再
     * 手写一份重复的正则+JSON校验逻辑，只做委托。
     */
    private fun extractOrphanFileMetaJson(content: String): String? =
        extractExportedFileJson(content)

    // ─────────────────────────────────────────────────────────
    //  §2.1.2 降级策略状态机
    // ─────────────────────────────────────────────────────────

    /**
     * 降级策略最大重试次数（§2.1.2）。
     * 达到此上限后终态放弃，调用 MemoryEngine.onToolFailureExhausted。
     */
    private const val MAX_DEGRADE_ATTEMPTS = 2

    /**
     * Fix-StuckGuard：单个工具"尝试 + 降级重试"全链路的总耗时预算。
     *
     * 根因（用户反馈"生成失败时卡在那里很久无法继续对话"）：第 1 次尝试 +
     * 瞬时重试 + 每轮降级（LLM 决策调用 + 再次执行）各自都可能耗时几十秒，
     * 最坏情况单个工具能拖 5-8 分钟（agent_log 实测 pptx_gen 单轮 210s）。
     * 期间用户只能干等"正在生成"，什么也做不了。
     *
     * 超过预算后立即终态放弃：失败结果照常回注，角色会在最终回复里自然告知
     * "没做成"，对话立即可继续，而不是无限卡在工具层。
     */
    private const val DEGRADE_TIME_BUDGET_MS = 150_000L

    /**
     * 降级策略上下文：传递给 [streamWithTools] 的活动上下文信息。
     *
     * 用于在降级过程中写入 [AgentActivityEventEntity]（eventType=DEGRADE_*）。
     * 如果为 null，降级过程只写 AgentLog，不写心迹事件表——
     * 向后兼容不传此参数的现有调用方。
     */
    data class ActivityContext(
        val characterId: Int,
        val sessionRef: String,
        val sceneType: String,
    )

    /**
     * 降级决策结果（由 LLM 通过 `<degrade:.../>` 标签输出）。
     */
    private sealed class DegradeDecision {
        data class Retry(val params: Map<String, String>) : DegradeDecision()
        data class Switch(val toolName: String, val params: Map<String, String>) : DegradeDecision()
        data class Giveup(val reason: String) : DegradeDecision()
        data object Invalid : DegradeDecision()
    }

    private val DEGRADE_RETRY_PATTERN = Regex(
        """<degrade:retry\s+params="((?:[^"\\]|\\.)*)"\s*/>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val DEGRADE_SWITCH_PATTERN = Regex(
        """<degrade:switch\s+tool="([^"]+)"\s+params="((?:[^"\\]|\\.)*)"\s*/>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val DEGRADE_GIVEUP_PATTERN = Regex(
        """<degrade:giveup\s+reason="((?:[^"\\]|\\.)*)"\s*/>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * 检测本轮回复是否"嘴替"了文件生成/发送结果——即文字里声称已完成导出/生成/发送，
     * 但本轮实际没有任何工具调用（[roundText] 有此类措辞但 pendingCalls 为空）。
     *
     * 背景：System Prompt 里已经写明"不能只在对话里说「已经发了」却没有实际执行"
     * （见 [AgentToolRegistry.buildToolDescriptionBlock]），但这只是软性约束，模型
     * 不总是遵守——尤其是被要求生成 Excel/PPT/PDF 这类专用工具、模型偶发选择直接
     * 用文字回应"已经生成好了"而不吐 `<tool:...>` 标签时，用户会看到一句空头承诺，
     * 却没有任何文件卡片、也没有任何 ToolCall 日志（因为 ToolParser 根本没检测到
     * 标签，Phase 2/3 都不会被触发）。
     *
     * 与截断续写（[wasTruncated] 分支）是两回事：截断是模型正在吐标签但被打断，
     * 这里是模型压根没打算吐标签、直接用自然语言"冒领"了结果，因此需要独立检测。
     *
     * 判定策略保持保守：只匹配"已经完成"式的过去时态措辞（已生成/已导出/已发送/
     * 生成好了 等），不匹配"我来生成"这类将要执行的表述，避免把正常的"我现在开始
     * 生成"话术误判为虚假声明而无谓打断对话。
     *
     * 根因修复（正则覆盖面不足）：原正则要求"已/已经"紧贴动词（如"已生成"），
     * 且"(生成|导出|发送)(好|完|完成)了"要求"好/完/完成"才能接"了"。但 LLM 实际
     * 输出的常见表述如"已经为您生成了PDF并发送给您了""已将文件打包发送给你了"
     * 都不匹配——"已经"和"生成"之间有"为您"，"生成了"的"了"前面没有"好/完/完成"。
     * 这导致大量 PDF/PPT/HTML/ZIP 的虚假完成声明逃逸检测，是"说发送了但看不到"
     * 反复出现的核心原因之一。
     * 修复：允许"已/已经"与动词之间有最多15字间隔；动词后接裸"了"也算完成态；
     * 新增"请查收"和"已...打包/发送"模式。
     */
    private val FALSE_COMPLETION_CLAIM_REGEX = Regex(
        // 模式1：已/已经 +（最多15字间隔）+ 完成动词
        // 覆盖"已经为您生成了""已将文件发送给你了"等间隔表述
        "已经?.{0,15}?(生成|导出|发送|发给你|做好|弄好|打包好|压缩好)|" +
        // 模式2：动词 + 完成后缀（好/完/完成/了）
        // 原正则遗漏了"生成了""发送了"等裸"了"完成态
        "(生成|导出|发送|做|弄|打包|压缩)(好|完|完成|了)|" +
        // 模式3：文件已 + 动词
        "文件已(生成|发|准备好|导出|发送)|" +
        // 模式4：已 +（最多15字间隔）+ 发送/打包完成
        // 覆盖"已将...打包发送给你了"等远程宾语表述
        "已.{0,15}?(发送|发给你|发过去|打包完成)|" +
        // 模式5：请查收——文件发送的标志性完成短语
        // 几乎不会在未实际发送时使用，误报率极低
        "请查收",
    )

    private fun claimsFileCompletionWithoutToolCall(roundText: String, pendingCalls: List<ToolCall>): Boolean {
        if (pendingCalls.isNotEmpty()) return false
        if (roundText.isBlank()) return false
        return FALSE_COMPLETION_CLAIM_REGEX.containsMatchIn(roundText)
    }

    /**
     * 方案 B（3.2 节）：调用工具轮次的纯文本篇幅统计——独立于 A 的 gate 逻辑，
     * 只做统计埋点，不拦截 send。只在"本轮 round 结束、pendingCalls 非空"时跑一次。
     *
     * 调用点：Phase 2（工具执行）之前。先跑两周日志收集"模型在调用工具的那一轮
     * 纯文本篇幅"的真实分布，再据此决定 3.1 节 prompt 规则要不要接入（3.3 节）。
     */
    private suspend fun logVerboseToolCallRound(roundText: String, pendingCalls: List<ToolCall>) {
        if (pendingCalls.isEmpty()) return  // 本轮没调用工具，不是 B 要观察的场景
        val plainTextLength = roundText.length  // 简化统计，不做分词，先看字符数量级
        com.zaijian.zhoumuyun.util.AgentLog.info(
            "ToolCallVerbosity",
            "本轮调用了 ${pendingCalls.size} 个工具，纯文本长度 $plainTextLength 字",
        )
    }

    /**
     * 方案 A 单元测试入口：驱动一次完整的 SentenceGate 生命周期
     * （feed 各 delta → feedRemaining → flush），返回最终放行的拼接文本。
     *
     * SentenceGate 是本类的 private 嵌套类，测试文件位于 app/src/test，
     * 与本文件是不同源码集，即使同包名也无法直接 `new SentenceGate(...)`。
     * 这个 internal 方法就是测试类访问它的唯一入口，本身不含任何断言逻辑，
     * 断言全部下放到 [SentenceGateTest] 里各自独立的 @Test 方法。
     *
     * @param skipFinalGateCheck 对应 flush(skipGateCheck=...)，默认 false（正常收尾路径）；
     *   传 true 用于验证"异常路径不丢字"（跳过最后一句的正则拦截，照单全发）。
     */
    internal fun runSentenceGate(
        deltas: List<String>,
        anyToolSucceeded: Boolean,
        pendingCalls: List<ToolCall> = emptyList(),
        skipFinalGateCheck: Boolean = false,
    ): String {
        val gate = SentenceGate(anyToolSucceeded, pendingCalls.toMutableList())
        val sent = StringBuilder()
        for (delta in deltas) { gate.feed(delta).forEach { sent.append(it) } }
        gate.feedRemaining().forEach { sent.append(it) }
        gate.flush(skipGateCheck = skipFinalGateCheck).forEach { sent.append(it) }
        return sent.toString()
    }

    /**
     * 方案 A 单元测试入口（旧）：验证 SentenceGate 核心行为，11 个场景全部塞在一个方法里，
     * 一旦某条断言失败只会报"自检应全部通过"，看不出具体是第几条。
     *
     * 保留此方法仅做兼容 / 回归对照，不再是主测试入口。
     * 新增测试请使用 [runSentenceGate]，在 [SentenceGateTest] 里写成独立的 @Test。
     */
    internal fun runSentenceGateSelfTest(): Boolean {
        fun runGate(deltas: List<String>, anyToolSucceeded: Boolean, pendingCallsList: List<ToolCall> = emptyList()): String =
            runSentenceGate(deltas, anyToolSucceeded, pendingCallsList)

        // 1. 正常文本全通过
        if (runGate(listOf("好的，我来帮你。这是安排。"), false) != "好的，我来帮你。这是安排。") return false

        // 2. 空头承诺被拦截
        if (runGate(listOf("好的。已经为您生成了。"), false) != "") return false

        // 3. 工具成功后合法收尾不拦
        if (runGate(listOf("好的。已经为您生成了。"), true) != "好的。已经为您生成了。") return false

        // 4. 本轮有 pendingCalls 不拦
        if (runGate(listOf("好的。已经为您生成了。"), false, listOf(ToolCall("pdf_export", emptyMap(), ""))) != "好的。已经为您生成了。") return false

        // 5. 跨句空头承诺滑动窗口拦截
        if (runGate(listOf("已经把这份数据整理了一下今天。发送给你了"), false) != "发送给你了") return false

        // 6. 迟一句发送：三句话全发出
        if (runGate(listOf("第一句话。第二句话。第三句话。"), false) != "第一句话。第二句话。第三句话。") return false

        // 7. 未成句残片不丢失
        if (runGate(listOf("这是没有句号的半句话"), false) != "这是没有句号的半句话") return false

        // 8. 多delta分片拼句
        if (runGate(listOf("好的，", "我来帮", "你看看。", "这是结果。"), false) != "好的，我来帮你看看。这是结果。") return false

        // 9. 请查收被拦
        if (runGate(listOf("文件已准备好，请查收"), false) != "") return false

        // 10. 异常路径不丢字
        if (runSentenceGate(listOf("已经为您生成了。"), false, skipFinalGateCheck = true) != "已经为您生成了。") return false

        // 11. 正常长文本不误伤
        val longNormal = runGate(listOf(
            "好的，我来帮你看看这个问题。",
            "根据我的分析，",
            "主要有以下几个原因。"
        ), false)
        if (longNormal != "好的，我来帮你看看这个问题。根据我的分析，主要有以下几个原因。") return false

        return true
    }

    /**
     * Fix-DupFileGen②：会真实落盘产出文件的工具集合。
     * 用于"同请求内完全重复调用"去重——只有这些工具的重复执行才会造成
     * 用户可见的重复文件，其他工具（搜索/记忆/日程等）重复执行无害，不干预。
     */
    private val FILE_PRODUCING_TOOLS = setOf(
        "file_export", "excel_gen", "pptx_gen", "docx_gen",
        "pdf_export", "html_gen", "markdown_to_doc", "table_export", "zip_export",
    )

    /**
     * Fix-DupFileGen③：同一请求内，同一个 file_producing 工具允许成功执行的最大次数。
     * 取 2 = 1 次正常生成 + 1 次容忍模型的修订重试；超过即视为"换名反复生成"而拦截。
     * 权衡：如需支持"一次合法生成多个同类文件"（如用户明确要 3 个 Excel），可调高此值，
     * 或改为按用户消息中显式声明的文件数动态放宽。
     */
    private val MAX_FILE_TOOL_SUCCESSES_PER_REQUEST = 2

    /**
     * 为文件类工具调用生成去重签名；非文件类工具返回 null（不参与去重）。
     * 签名 = 工具名 + 文件名（name/title/names 任一）+ 全参数哈希，
     * 只有"完全一致"的调用才会撞上同一签名，同名但内容不同的修订调用不受影响。
     */
    private fun fileCallSignature(call: ToolCall): String? {
        if (call.toolName !in FILE_PRODUCING_TOOLS) return null
        val fileName = call.params["name"] ?: call.params["title"] ?: call.params["names"] ?: ""
        return "${call.toolName}|$fileName|${call.params.toString().hashCode()}"
    }

    /**
     * §2.1.2 降级策略状态机对外入口：在 [executeWithDegradationCore] 之上包一层
     * "工具执行中"标记维护（Fix-孤儿文件 ③，见 [toolInFlightKeys] 顶部说明）。
     *
     * 标记覆盖的是整个尝试/降级周期（第1次尝试 + 瞬时重试 + 全部降级轮次），
     * 而不只是单次 [executeWithTimeout] 调用——因为调用方真正关心的是
     * "这个角色现在算不算正被工具占着"，降级重试期间同样应该被当作"占着"。
     *
     * 用 try/finally 包裹而不是直接改 [executeWithDegradationCore] 内部：
     * 那个函数有多处提前 return（成功 / 降级放弃 / 达到上限），在每处分别补
     * 标记清理容易漏改一处；外层包一层可以保证无论走哪个 return 分支，
     * finally 都会执行且只需要维护一处。
     */
    private suspend fun executeWithDegradation(
        call: ToolCall,
        tool: AgentTool,
        provider: LLMProvider,
        disabledToolNames: Set<String>,
        activityContext: ActivityContext?,
        goalContext: String,
    ): ToolResult {
        val inFlightKey = activityContext?.let { toolInFlightKey(it.sceneType, it.characterId) }
        inFlightKey?.let { toolInFlightKeys.add(it) }
        try {
            return executeWithDegradationCore(call, tool, provider, disabledToolNames, activityContext, goalContext)
        } finally {
            // 纯内存 Set 操作，非挂起调用，即使协程已被取消（含 NonCancellable
            // 保护区之外的正常取消路径）finally 块本身依然会执行，标记不会残留。
            inFlightKey?.let { toolInFlightKeys.remove(it) }
        }
    }

    /**
     * §2.1.2 降级策略状态机：在 [executeWithTimeout] 之上包装一层降级决策。
     *
     * 流程：
     * 1. 第1次尝试：调用 [executeWithTimeout]（原参数）
     * 2. 成功 → 返回
     * 3. 失败 → 判断失败类型：
     *    - 瞬时类（timeout/异常）→ 原参数重试1次（不问 LLM，纯程序判断）
     *    - 仍失败或业务类失败 → 进入业务类降级
     * 4. 业务类降级：构造极简降级决策 Prompt，LLM 用 `<degrade:.../>` 标签三选一
     * 5. 执行对应动作，若仍失败且未达 [MAX_DEGRADE_ATTEMPTS]，回到步骤4
     * 6. 达到上限或 giveup → 终态放弃，调用 [MemoryEngine.onToolFailureExhausted]
     *
     * 降级过程不作为 StreamEvent.TextDelta 输出给用户——用户只看到最终结果。
     * 每次尝试都写一条 AgentActivityEventEntity（如果 [activityContext] 非 null）。
     */
    private suspend fun executeWithDegradationCore(
        call: ToolCall,
        tool: AgentTool,
        provider: LLMProvider,
        disabledToolNames: Set<String>,
        activityContext: ActivityContext?,
        goalContext: String,
    ): ToolResult {
        val startTime = System.currentTimeMillis()
        var currentCall = call
        var currentTool = tool
        var attempts = 0
        var lastResult: ToolResult

        // ── 第1次尝试 ──
        attempts++
        lastResult = executeWithTimeout(currentCall, currentTool, activityContext)
        if (lastResult.success) return lastResult

        // ── 判断失败类型 ──
        val isTransient = lastResult.error == "timeout" ||
            lastResult.content.startsWith("[${currentCall.toolName} 执行异常")

        // Fix-StuckGuard：总耗时预算已耗尽时跳过重试，直接带着失败结果收尾，
        // 避免单个工具的降级链把整轮回复拖进几分钟级的"假死"。
        fun budgetExhausted(): Boolean =
            (System.currentTimeMillis() - startTime) >= DEGRADE_TIME_BUDGET_MS

        if (isTransient && !budgetExhausted()) {
            // 瞬时类失败：原参数重试1次（不问 LLM）
            attempts++
            recordDegradeEvent(activityContext, "DEGRADE_RETRY", currentCall.toolName,
                "瞬时类失败（${lastResult.error}），原参数重试", startTime)
            lastResult = executeWithTimeout(currentCall, currentTool, activityContext)
            if (lastResult.success) return lastResult
        }

        // ── 业务类降级 ──
        var degradeAttempts = 0
        while (degradeAttempts < MAX_DEGRADE_ATTEMPTS && !budgetExhausted()) {
            degradeAttempts++
            val decision = askDegradeDecision(
                provider, currentCall, lastResult, disabledToolNames
            )

            when (decision) {
                is DegradeDecision.Retry -> {
                    attempts++
                    recordDegradeEvent(activityContext, "DEGRADE_RETRY", currentCall.toolName,
                        "LLM 建议换参数重试：${decision.params}", startTime)
                    currentCall = ToolCall(toolName = currentCall.toolName, params = decision.params, rawTag = "")
                    lastResult = executeWithTimeout(currentCall, currentTool, activityContext)
                }
                is DegradeDecision.Switch -> {
                    val newTool = AgentToolRegistry.get(decision.toolName)
                    if (newTool == null || decision.toolName in disabledToolNames) {
                        recordDegradeEvent(activityContext, "DEGRADE_GIVEUP", currentCall.toolName,
                            "LLM 建议切换到 ${decision.toolName}，但该工具不可用或被禁用", startTime)
                        recordFailureExhausted(activityContext, currentCall.toolName, goalContext,
                            "目标工具 ${decision.toolName} 不可用（已尝试 $attempts 次）", attempts)
                        return lastResult
                    }
                    attempts++
                    recordDegradeEvent(activityContext, "DEGRADE_SWITCH", decision.toolName,
                        "LLM 建议从 ${currentCall.toolName} 切换到 ${decision.toolName}", startTime)
                    currentCall = ToolCall(toolName = decision.toolName, params = decision.params, rawTag = "")
                    currentTool = newTool
                    lastResult = executeWithTimeout(currentCall, currentTool, activityContext)
                }
                is DegradeDecision.Giveup -> {
                    recordDegradeEvent(activityContext, "DEGRADE_GIVEUP", currentCall.toolName,
                        "LLM 建议放弃：${decision.reason}", startTime)
                    recordFailureExhausted(activityContext, currentCall.toolName, goalContext,
                        "${decision.reason}（已尝试 $attempts 次）", attempts)
                    return lastResult
                }
                is DegradeDecision.Invalid -> {
                    recordDegradeEvent(activityContext, "DEGRADE_GIVEUP", currentCall.toolName,
                        "降级决策 Prompt 返回无效格式，放弃", startTime)
                    recordFailureExhausted(activityContext, currentCall.toolName, goalContext,
                        "降级决策无效（已尝试 $attempts 次）", attempts)
                    return lastResult
                }
            }

            if (lastResult.success) return lastResult
        }

        // 达到 MAX_DEGRADE_ATTEMPTS 上限，终态放弃
        recordDegradeEvent(activityContext, "DEGRADE_GIVEUP", currentCall.toolName,
            "达到降级重试上限（$MAX_DEGRADE_ATTEMPTS），放弃", startTime)
        recordFailureExhausted(activityContext, currentCall.toolName, goalContext,
            "降级重试耗尽（已尝试 $attempts 次）", attempts)
        return lastResult
    }

    /**
     * 构造极简降级决策 Prompt 并调用 LLM，解析 `<degrade:.../>` 标签。
     *
     * 不含人设、不含对话历史——降级决策是纯功能性判断，
     * 不需要角色人设参与，混进主流程反而会让降级过程"入戏"。
     */
    private suspend fun askDegradeDecision(
        provider: LLMProvider,
        failedCall: ToolCall,
        failedResult: ToolResult,
        disabledToolNames: Set<String>,
    ): DegradeDecision {
        // Window B-1 fix2：降级决策 Prompt 消费 usageNotes，帮助 LLM 做更合理的换工具/换参数决策
        val availableTools = AgentToolRegistry.buildDegradeDecisionToolBlock(
            excludeNames   = disabledToolNames,
            failedToolName = failedCall.toolName,
        )
        val systemPrompt = buildString {
            appendLine("你是一个工具调用降级决策助手。一个工具调用刚刚失败了，你需要决定下一步怎么做。")
            appendLine("只回复以下三种标签之一，不要任何其他文字：")
            appendLine()
            appendLine("<degrade:retry params=\"key1=\\\"val1\\\",key2=\\\"val2\\\"\"/>   换参数重试同一工具")
            appendLine("<degrade:switch tool=\"工具名\" params=\"key1=\\\"val1\\\"\"/>   换一个工具")
            appendLine("<degrade:giveup reason=\"放弃原因\"/>   放弃")
            appendLine()
            appendLine("可用工具列表（⚠️标记为已失败的工具，换参数重试或换其他工具）：")
            appendLine(availableTools)
        }
        val userPrompt = buildString {
            appendLine("失败的工具：${failedCall.toolName}")
            appendLine("调用参数：${failedCall.params}")
            appendLine("失败原因：${failedResult.error ?: "未知"}")
            appendLine("失败详情：${failedResult.content.take(200)}")
        }

        return try {
            val response = AgentTool.callLlm(
                providerFn   = { provider },
                systemPrompt = systemPrompt,
                userPrompt   = userPrompt,
                // P1 修复：原 200 tokens 对含较长 params 的 degrade:switch 标签极易截断，
                // 截断后 parseDegradeDecision 匹配失败返回 Invalid，降级流程静默放弃。
                maxTokens    = 512,
                temperature  = 0.3f,
            )
            parseDegradeDecision(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // P1-5 修复：catch Throwable 而非 Exception，防 Error 子类击穿
            ZLog.w("ToolCall", "[askDegradeDecision] LLM 调用失败: ${e.message}")
            DegradeDecision.Invalid
        }
    }

    private fun parseDegradeDecision(raw: String): DegradeDecision {
        DEGRADE_RETRY_PATTERN.find(raw)?.let { match ->
            val paramsStr = unescapeAttr(match.groupValues[1])
            return DegradeDecision.Retry(params = parseParamsString(paramsStr))
        }
        DEGRADE_SWITCH_PATTERN.find(raw)?.let { match ->
            val toolName = match.groupValues[1]
            val paramsStr = unescapeAttr(match.groupValues[2])
            return DegradeDecision.Switch(toolName = toolName, params = parseParamsString(paramsStr))
        }
        DEGRADE_GIVEUP_PATTERN.find(raw)?.let { match ->
            val reason = unescapeAttr(match.groupValues[1])
            return DegradeDecision.Giveup(reason = reason)
        }
        return DegradeDecision.Invalid
    }

    /**
     * 解析 `key1="val1",key2="val2"` 格式的参数字符串为 Map。
     * 复用 [ScheduleToolParamUtil.parseToolParams] 的同款正则。
     */
    private fun parseParamsString(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val pattern = Regex("""(\w+)="((?:[^"\\]|\\.)*)${'"'}""")
        pattern.findAll(raw).forEach { m ->
            result[m.groupValues[1]] = unescapeAttr(m.groupValues[2])
        }
        return result
    }

    /**
     * P1-10 修复（窗口3 P1-3 / 合并方案 P1-10）：
     *
     * 原实现用链式 `.replace("\\\"", "\"")` / `.replace("\\n", "\n")` 全局替换，
     * 与 ToolParser.kt:427 已修复的同类问题根因一致——链式 replace 不处理 `\\`
     * 本身且非单遍扫描，多层转义时会把 `\\\"` 误还原成 `\\"` 而非期望的 `\"`。
     * 现改为与 ToolParser.unescapeAttrValue 完全一致的单遍从左到右扫描实现。
     */
    private fun unescapeAttr(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        val n = value.length
        while (i < n) {
            val c = value[i]
            if (c == '\\' && i + 1 < n) {
                when (value[i + 1]) {
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

    /**
     * 写一条降级事件到心迹事件表（fire-and-forget）。
     */
    private suspend fun recordDegradeEvent(
        activityContext: ActivityContext?,
        eventType: String,
        toolName: String,
        note: String,
        startTime: Long,
    ) {
        if (activityContext == null) return
        try {
            AppContainer.instance.agentActivityRepo.recordEvent(
                characterId    = activityContext.characterId,
                sessionRef     = activityContext.sessionRef,
                sceneType      = activityContext.sceneType,
                eventType      = eventType,
                toolName       = toolName,
                outcome        = AgentActivityRepository.Outcome.FAIL,
                decisionNote   = note,
                startedAt      = startTime,
                completedAt    = System.currentTimeMillis(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // P1-5 修复：catch Throwable 而非 Exception，防 Error 子类击穿
            ZLog.w("ToolCall", "降级事件落库失败（不影响降级流程）", e)
        }
    }

    /**
     * 终态放弃：调用 [MemoryEngine.onToolFailureExhausted] 记录失败尝试。
     */
    private suspend fun recordFailureExhausted(
        activityContext: ActivityContext?,
        toolName: String,
        goalContext: String,
        failureReason: String,
        attemptsExhausted: Int,
    ) {
        if (activityContext == null) return
        try {
            AppContainer.instance.memoryEngine.onToolFailureExhausted(
                characterId       = activityContext.characterId,
                toolName          = toolName,
                goalContext       = goalContext.take(200),
                failureReason     = failureReason,
                attemptsExhausted = attemptsExhausted,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // P1-5 修复：catch Throwable 而非 Exception，防 Error 子类击穿
            ZLog.w("ToolCall", "失败写回记忆失败（不影响降级流程）", e)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ChatViewModel 集成辅助（扩展函数）
// ─────────────────────────────────────────────────────────────

/**
 * 将 [StreamEvent] Flow 折叠为简单的文本 delta Flow。
 *
 * 适用于不需要处理工具事件细节的调用场景（如 chatSync 的流式版本）。
 *
 * 工具进度提示以 "[⚙ hint]" 格式插入到文本流中（可选，由 [showHints] 控制）。
 */
fun Flow<StreamEvent>.asTextFlow(showHints: Boolean = false): Flow<String> =
    map { event ->
        when (event) {
            is StreamEvent.TextDelta  -> event.text
            is StreamEvent.ToolStarted ->
                if (showHints && event.hint != null) "\n[⚙ ${event.hint}]\n" else ""
            is StreamEvent.ToolDone   -> ""
            is StreamEvent.RoundDone  -> ""
            is StreamEvent.FileReadConfirmed -> ""
        }
    }.let { mappedFlow ->
        flow {
            mappedFlow.collect { text ->
                if (text.isNotEmpty()) emit(text)
            }
        }
    }

/**
 * 从 [StreamEvent] Flow 中提取最终完整文本（阻塞收集）。
 *
 * 用于需要完整回复文本的场景（记忆提取、关系更新等）。
 */
suspend fun Flow<StreamEvent>.collectFullText(): String {
    val sb = StringBuilder()
    collect { event ->
        if (event is StreamEvent.TextDelta) sb.append(event.text)
    }
    return sb.toString()
}
