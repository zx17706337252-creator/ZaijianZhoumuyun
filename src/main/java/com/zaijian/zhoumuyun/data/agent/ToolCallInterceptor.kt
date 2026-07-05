package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
 * ChatViewModel 按事件类型分发处理：
 *   - [TextDelta]   → 追加到 streamingContent（打字机）
 *   - [ToolStarted] → 更新 UI hint（"正在搜索…"）
 *   - [ToolDone]    → 清除 UI hint，记录工具结果到 workbenchTask
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
}

// ─────────────────────────────────────────────────────────────
//  拦截器主体
// ─────────────────────────────────────────────────────────────

object ToolCallInterceptor {

    /**
     * 最大工具执行轮数。
     * 第二次 LLM 回复中仍有工具调用时，最多再执行一轮。
     * 超过此轮数后，直接返回最后一次 LLM 的原始输出（含标签），不再拦截。
     */
    const val MAX_TOOL_ROUNDS = 2

    /**
     * 单个工具调用超时（毫秒）。
     * 超过此时间后返回超时 ToolResult，不中断整体流程。
     */
    const val TOOL_TIMEOUT_MS = 30_000L

    /**
     * 流式调用 LLM 并自动处理工具调用。
     *
     * 当注册表为空（未注册任何工具）时，直接透传 provider.chat() 的流，
     * 无任何额外开销。
     *
     * @param provider      LLM 提供商
     * @param messages      当前消息历史（不含 system）
     * @param systemPrompt  系统提示（应已包含工具能力描述块）
     * @param config        LLM 配置
     * @param maxRounds     最大工具执行轮数（默认 [MAX_TOOL_ROUNDS]）
     * @return              [StreamEvent] 的 Flow
     */
    fun streamWithTools(
        provider: LLMProvider,
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
        maxRounds: Int = MAX_TOOL_ROUNDS,
    ): Flow<StreamEvent> = channelFlow {

        // 快速路径：注册表为空，直接透传
        if (AgentToolRegistry.allNames().isEmpty()) {
            provider.chat(messages, systemPrompt, config).collect { delta ->
                send(StreamEvent.TextDelta(delta))
            }
            return@channelFlow
        }

        // 工具执行轮次循环
        var currentMessages = messages.toMutableList()
        var round = 0

        while (round < maxRounds) {
            val parser = ToolParser()
            val pendingCalls = mutableListOf<ToolCall>()
            val roundText = StringBuilder()

            // ── Phase 1：流式接收 LLM 输出 ─────────────────────
            try {
                provider.chat(currentMessages, systemPrompt, config).collect { delta ->
                    val result = parser.feed(delta)

                    // 立即输出纯文本（打字机效果）
                    if (result.cleanText.isNotEmpty()) {
                        send(StreamEvent.TextDelta(result.cleanText))
                        roundText.append(result.cleanText)
                    }

                    // 收集本轮发现的工具调用
                    pendingCalls.addAll(result.detectedCalls)
                }
            } catch (e: CancellationException) {
                throw e  // 协程取消必须重新抛出
            } catch (e: Exception) {
                // LLM 调用失败：emit 错误提示后退出
                send(StreamEvent.TextDelta("\n\n[抱歉，遇到了一些问题，稍后再试？]"))
                break
            }

            // 处理 flush（流结束后的剩余 buffer）
            // L4 修复：原 flush() 只截断未闭合的 <tool: 前缀，但 buffer 里可能已有
            // 完整的工具标签（流最后一行恰好是完整标签但还没被 feed 处理完），
            // 改为先再 feed 一次空串让 processBuf 扫尽 buffer，再 flush 截断尾部碎片。
            val preFeedResult = parser.feed("")
            if (preFeedResult.cleanText.isNotEmpty()) {
                send(StreamEvent.TextDelta(preFeedResult.cleanText))
                roundText.append(preFeedResult.cleanText)
            }
            pendingCalls.addAll(preFeedResult.detectedCalls)

            val flushResult = parser.flush()
            if (flushResult.cleanText.isNotEmpty()) {
                send(StreamEvent.TextDelta(flushResult.cleanText))
                roundText.append(flushResult.cleanText)
            }

            // 本轮没有工具调用 → 正常结束
            if (pendingCalls.isEmpty()) break

            // ── Phase 2：串行执行工具调用 ─────────────────────
            val toolResultParts = mutableListOf<String>()

            for (call in pendingCalls) {
                val tool = AgentToolRegistry.get(call.toolName)
                if (tool == null) {
                    // 未注册的工具：记录并跳过
                    toolResultParts.add("[工具 ${call.toolName} 不可用]")
                    continue
                }

                // 通知 UI 工具开始
                send(StreamEvent.ToolStarted(
                    toolName = call.toolName,
                    params   = call.params,
                    hint     = null,
                ))

                // 执行工具（带超时）
                val toolResult = executeWithTimeout(call, tool)

                // 通知 UI 工具完成
                send(StreamEvent.ToolDone(toolResult))

                toolResultParts.add(
                    if (toolResult.success) toolResult.content
                    else "[${call.toolName} 执行失败: ${toolResult.error ?: "未知错误"}]"
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
            val toolResultContent = buildString {
                appendLine("[工具执行结果]")
                toolResultParts.forEachIndexed { i, r ->
                    if (i > 0) appendLine()
                    append(r)
                }
                appendLine()
                appendLine()
                append("请根据以上工具返回的信息，用你自己的语气回复用户。不要提及工具或搜索的过程。")
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
            try {
                val parser = ToolParser()
                provider.chat(currentMessages, systemPrompt, config).collect { delta ->
                    val result = parser.feed(delta)
                    if (result.cleanText.isNotEmpty()) send(StreamEvent.TextDelta(result.cleanText))
                }
                // L4 修复同主循环：先 feed("") 扫尽 buffer 里已完整的标签，
                // 再 flush() 截断尾部未闭合碎片，避免末轮完整工具标签被丢弃。
                val preFeed = parser.feed("")
                if (preFeed.cleanText.isNotEmpty()) send(StreamEvent.TextDelta(preFeed.cleanText))
                val final = parser.flush()
                if (final.cleanText.isNotEmpty()) send(StreamEvent.TextDelta(final.cleanText))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(StreamEvent.TextDelta("\n\n[抱歉，遇到了一些问题，稍后再试？]"))
            }
        }
    }

    /**
     * 带超时的工具执行。
     *
     * 超过 [TOOL_TIMEOUT_MS] 返回超时 ToolResult，不抛出异常。
     */
    private suspend fun executeWithTimeout(
        call: ToolCall,
        tool: AgentTool,
    ): ToolResult = withContext(Dispatchers.IO) {
        // U-7 修复：执行前检查协程是否已被取消，避免外层已取消后仍启动工具副作用。
        // 注：ensureActive 只能提供入口处的快速退出保护；阻塞式工具（POI wb.write 等）
        // 内部无挂起点，withTimeout 取消信号在阻塞期间无法生效，这是协作式取消的固有限制。
        currentCoroutineContext().ensureActive()
        try {
            kotlinx.coroutines.withTimeout(TOOL_TIMEOUT_MS) {
                tool.execute(call.params)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ToolResult(
                toolName = call.toolName,
                success  = false,
                content  = "[${call.toolName} 执行超时（${TOOL_TIMEOUT_MS / 1000}s）]",
                error    = "timeout",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(
                toolName = call.toolName,
                success  = false,
                content  = "[${call.toolName} 执行异常：${e.message}]",
                error    = e.message,
            )
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
