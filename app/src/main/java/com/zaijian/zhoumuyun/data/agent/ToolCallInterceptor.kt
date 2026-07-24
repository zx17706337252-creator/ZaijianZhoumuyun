package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.repository.AgentActivityRepository
import com.zaijian.zhoumuyun.util.ZLog
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
            provider.chat(messages, systemPrompt, config).collect { delta ->
                send(StreamEvent.TextDelta(delta))
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
            if (msg.role == "system" && msg.content.contains("用户导入了一个文件")) {
                val pathMatch = Regex("""路径[：:]\s*([^\s)）]+)""").find(msg.content)
                if (pathMatch != null) {
                    val filePath = pathMatch.groupValues[1]
                    // 只注入还没被读过的文件路径（检查消息历史里有没有对应的工具结果）
                    val alreadyRead = messages.any { m ->
                        m.role == "user" && m.content.contains("[工具执行结果]") &&
                        m.content.contains(filePath)
                    }
                    if (!alreadyRead) pendingFilePaths.add(filePath)
                }
            }
        }

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
                com.zaijian.zhoumuyun.util.AgentLog.error("LLM", "LLM 调用失败（第 ${round + 1} 轮）", e)
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
                        append("你刚才的回复没有调用 file_read 工具读取用户上传的文件。\n")
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
                            } catch (e: Exception) {
                                fallbackParts.add("[file_read 读取 $filePath 异常：${e.message}]")
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
                        append("请根据以上工具返回的信息，用你自己的语气回复用户。不要提及工具或搜索的过程。")
                    }

                    // 追加 AI 这轮的回复（不推倒重来——复核意见一第 4 条）
                    if (roundText.isNotEmpty()) {
                        currentMessages.add(LLMMessage("assistant", roundText.toString()))
                    }
                    // 追加兜底工具结果消息
                    currentMessages.add(LLMMessage("user", fallbackContent))

                    // 清空 pendingFilePaths，避免兜底重复触发
                    pendingFilePaths.clear()

                    roundText.clear()
                    round++
                    continue  // 让 LLM 基于注入的内容生成回复
                }

                break
            }

            // v1.48：如果 AI 调用了 file_read，从 pendingFilePaths 移除已读路径
            // （AI 主动读了，就不再强制了）
            for (call in pendingCalls) {
                if (call.toolName == "file_read") {
                    val readPath = call.params["path"]
                    if (readPath != null) {
                        pendingFilePaths.remove(readPath)
                        com.zaijian.zhoumuyun.util.AgentLog.info(
                            "FileReadLock", "✅ AI 主动调用了 file_read 读取：$readPath",
                        )
                    }
                }
            }

            // ── Phase 2：串行执行工具调用 ─────────────────────
            val toolResultParts = mutableListOf<String>()

            for (call in pendingCalls) {
                if (call.toolName in disabledToolNames) {
                    // 执行层强制拦截：该工具在当前场景被禁用（即使模型生成了标签，
                    // 也不真正执行）。与 prompt 层的描述过滤是同一份排除名单，
                    // 这里是兜底的第二道防线。
                    com.zaijian.zhoumuyun.util.AgentLog.warn("ToolCall", "⊘ ${call.toolName} 在当前场景被禁用，跳过执行")
                    toolResultParts.add("[工具 ${call.toolName} 在当前场景不可用]")
                    continue
                }
                val tool = AgentToolRegistry.get(call.toolName)
                if (tool == null) {
                    // 未注册的工具：记录并跳过
                    com.zaijian.zhoumuyun.util.AgentLog.warn("ToolCall", "⊘ ${call.toolName} 未注册（LLM 生成了该工具标签但 registry 里没有），跳过")
                    toolResultParts.add("[工具 ${call.toolName} 不可用]")
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
        val startTime = System.currentTimeMillis()
        com.zaijian.zhoumuyun.util.AgentLog.info("ToolCall", "▶ ${call.toolName} 开始\n  params: ${call.params.toString().take(500)}")
        try {
            kotlinx.coroutines.withTimeout(TOOL_TIMEOUT_MS) {
                tool.execute(call.params)
            }.also { result ->
                val elapsed = System.currentTimeMillis() - startTime
                if (result.success) {
                    com.zaijian.zhoumuyun.util.AgentLog.info(
                        "ToolCall",
                        "✔ ${call.toolName} 成功（用时 ${elapsed}ms）\n  result: ${result.content.take(300)}${if (result.tablePayloadJson != null) "\n  [附带 tablePayloadJson]" else ""}",
                    )
                } else {
                    com.zaijian.zhoumuyun.util.AgentLog.warn(
                        "ToolCall",
                        "⚠ ${call.toolName} 业务失败（用时 ${elapsed}ms）\n  error: ${result.error}",
                    )
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val elapsed = System.currentTimeMillis() - startTime
            com.zaijian.zhoumuyun.util.AgentLog.error(
                "ToolCall",
                "⏱ ${call.toolName} 超时（${TOOL_TIMEOUT_MS / 1000}s，实际 ${elapsed}ms）",
            )
            ToolResult(
                toolName = call.toolName,
                success  = false,
                content  = "[${call.toolName} 执行超时（${TOOL_TIMEOUT_MS / 1000}s）]",
                error    = "timeout",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            com.zaijian.zhoumuyun.util.AgentLog.error(
                "ToolCall",
                "✗ ${call.toolName} 异常（用时 ${elapsed}ms）",
                e,
            )
            ToolResult(
                toolName = call.toolName,
                success  = false,
                content  = "[${call.toolName} 执行异常：${e.message}]",
                error    = e.message,
            )
        }
    }

    // ─────────────────────────────────────────────────────────
    //  §2.1.2 降级策略状态机
    // ─────────────────────────────────────────────────────────

    /**
     * 降级策略最大重试次数（§2.1.2）。
     * 达到此上限后终态放弃，调用 MemoryEngine.onToolFailureExhausted。
     */
    private const val MAX_DEGRADE_ATTEMPTS = 2

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
    private suspend fun executeWithDegradation(
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
        lastResult = executeWithTimeout(currentCall, currentTool)
        if (lastResult.success) return lastResult

        // ── 判断失败类型 ──
        val isTransient = lastResult.error == "timeout" ||
            lastResult.content.startsWith("[${currentCall.toolName} 执行异常")

        if (isTransient) {
            // 瞬时类失败：原参数重试1次（不问 LLM）
            attempts++
            recordDegradeEvent(activityContext, "DEGRADE_RETRY", currentCall.toolName,
                "瞬时类失败（${lastResult.error}），原参数重试", startTime)
            lastResult = executeWithTimeout(currentCall, currentTool)
            if (lastResult.success) return lastResult
        }

        // ── 业务类降级 ──
        var degradeAttempts = 0
        while (degradeAttempts < MAX_DEGRADE_ATTEMPTS) {
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
                    lastResult = executeWithTimeout(currentCall, currentTool)
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
                    lastResult = executeWithTimeout(currentCall, currentTool)
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
                maxTokens    = 200,
                temperature  = 0.3f,
            )
            parseDegradeDecision(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
        val pattern = Regex("""(\w+)="((?:[^"\\]|\\.)*)"""")
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
