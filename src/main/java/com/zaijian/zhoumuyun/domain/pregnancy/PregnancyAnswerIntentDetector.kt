package com.zaijian.zhoumuyun.domain.pregnancy

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
//  PregnancyAnswerIntentDetector
//
//  D3 孕期共设系统 · 意图判定组件（任务4：判答 + 确认追问）
//
//  职责：
//    1. isAnswering — 判断用户本轮回复是否是在回答母亲角色挂起的问题
//       （而不是岔开话题/单纯闲聊）。
//    2. didAsk      — 判断 AI 本轮回复是否真的把注入的提问模板问出口
//       （AI 有时会因为剧情走向而忽略 system prompt 里的提问指令）。
//
//  触发时机：
//    均由 ChatViewModel.sendMessage 在孕期门控命中时调用，详见该文件中
//    步骤①（判答，记录用户消息事件之后）与步骤③（确认追问，记录角色回复之后）。
//
//  设计约束：
//    - 与 PregnancyAnswerConsistencyChecker 同风格：无内部状态、单次调用、
//      LLM 只返回精简 JSON，maxTokens=32 级别，控制 token 消耗。
//    - 解析失败时默认 NO（保守策略）：
//        isAnswering → NO  会被调用方视为「用户没在回答」，不会误存入答案；
//        didAsk      → NO  会被调用方视为「AI 没问出口」，不会误记 pending，
//                           留到下次门控窗口再试。
//    - 不持有 LLMProvider 实例，使用懒加载 providerFn: () -> LLMProvider?，
//      与 ConsistencyChecker / Phase28 工具组件保持一致，确保用户切换
//      provider/Key 后本组件始终使用最新实例。
// ─────────────────────────────────────────────────────────────────────────────

/** 意图判定结果，YES/NO 两态，解析失败统一兜底为 NO（保守策略） */
enum class IntentResult {
    YES,
    NO,
}

class PregnancyAnswerIntentDetector(
    private val providerFn: () -> LLMProvider?,
) {

    companion object {
        private const val TAG = "PregnancyIntentDetector"

        private val IS_ANSWERING_SYSTEM_PROMPT = """
            你是一个对话意图判定工具。
            场景：一位怀孕中的角色之前向用户提出了一个关于未来孩子的问题，
            现在用户发来了一条新消息。
            你的任务：判断用户这条新消息，是否是在回答/回应那个问题
            （哪怕答得简短、模糊、跑题一点点，只要能看出是在接话即可算回答；
            如果用户完全在聊别的事、忽略了问题、或者只是打招呼/闲聊，算没有回答）。

            判定标准：
            - 内容上看得出是在回应该问题的方向 → YES
            - 完全无关、岔开话题、未触及问题 → NO
            - 无法确定时 → NO（保守策略）

            输出格式（只输出 JSON，不要任何解释或 Markdown）：
            {"result":"YES"}
            或
            {"result":"NO"}
        """.trimIndent()

        private val DID_ASK_SYSTEM_PROMPT = """
            你是一个对话意图判定工具。
            场景：系统要求一个怀孕中的角色，在本轮回复中向用户提出某个
            关于未来孩子的问题（问题方向已给出）。现在你看到的是角色
            实际发出的回复原文。
            你的任务：判断这条回复，是否实际上把那个方向的问题问出口了
            （措辞可以和原始模板不同，只要语义上是在向用户提出同方向的
            问题即可算问了；如果角色回复中完全没有提出该问题，只是在
            讲别的剧情/情绪，算没问）。

            判定标准：
            - 回复中包含该方向的提问（哪怕换了说法）→ YES
            - 回复中没有提出该问题 → NO
            - 无法确定时 → NO（保守策略）

            输出格式（只输出 JSON，不要任何解释或 Markdown）：
            {"result":"YES"}
            或
            {"result":"NO"}
        """.trimIndent()
    }

    /**
     * 判断用户本轮回复 [userReply] 是否在回答挂起的问题 [pendingQuestionText]。
     *
     * @param pendingQuestionText 母亲角色之前实际问出的问题原文
     *   （来自 PregnancyPendingQuestionEntity.questionText）
     * @param userReply 用户本轮新消息原文
     * @return [IntentResult]；LLM 调用失败或解析失败时返回 [IntentResult.NO]
     */
    suspend fun isAnswering(
        pendingQuestionText: String,
        userReply: String,
    ): IntentResult {
        val provider = providerFn()
        if (provider == null) {
            ZLog.w(TAG, "LLMProvider not available, defaulting to NO (isAnswering)")
            return IntentResult.NO
        }

        val userPrompt = buildString {
            appendLine("【角色之前问的问题】")
            appendLine(pendingQuestionText.trim())
            appendLine()
            appendLine("【用户本轮回复】")
            appendLine(userReply.trim())
            appendLine()
            append("请判断用户本轮回复是否在回答上面这个问题，输出 JSON。")
        }

        return try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(
                    LLMMessage(role = "user", content = userPrompt),
                ),
                systemPrompt = IS_ANSWERING_SYSTEM_PROMPT,
                config = LLMConfig(
                    model = "",
                    maxTokens = 32,
                    temperature = 0.2f,
                    stream = false,
                ),
            )
            parseResult(response)
        } catch (e: Exception) {
            ZLog.e(TAG, "LLM call failed, defaulting to NO (isAnswering)", e)
            IntentResult.NO
        }
    }

    /**
     * 判断 AI 本轮回复 [aiReply] 是否实际问出了门控注入的问题
     * （问题方向由 [expectedQuestionTopic] 描述，通常是注入 system prompt
     * 的提问模板原文）。
     *
     * @param expectedQuestionTopic 本轮门控期望被问出的问题方向/模板原文
     * @param aiReply 角色本轮实际生成的回复原文
     * @return [IntentResult]；LLM 调用失败或解析失败时返回 [IntentResult.NO]
     */
    suspend fun didAsk(
        expectedQuestionTopic: String,
        aiReply: String,
    ): IntentResult {
        val provider = providerFn()
        if (provider == null) {
            ZLog.w(TAG, "LLMProvider not available, defaulting to NO (didAsk)")
            return IntentResult.NO
        }

        val userPrompt = buildString {
            appendLine("【期望问出的问题方向】")
            appendLine(expectedQuestionTopic.trim())
            appendLine()
            appendLine("【角色本轮实际回复】")
            appendLine(aiReply.trim())
            appendLine()
            append("请判断角色本轮回复是否实际问出了上面这个方向的问题，输出 JSON。")
        }

        return try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(
                    LLMMessage(role = "user", content = userPrompt),
                ),
                systemPrompt = DID_ASK_SYSTEM_PROMPT,
                config = LLMConfig(
                    model = "",
                    maxTokens = 32,
                    temperature = 0.2f,
                    stream = false,
                ),
            )
            parseResult(response)
        } catch (e: Exception) {
            ZLog.e(TAG, "LLM call failed, defaulting to NO (didAsk)", e)
            IntentResult.NO
        }
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    private fun parseResult(raw: String): IntentResult {
        // P1-2-3：委托共用工具清洗 JSON（消除重复代码）
        val cleaned = cleanLlmJson(raw)
        return try {
            when (parseResultField(cleaned)?.uppercase()) {
                "YES" -> IntentResult.YES
                else  -> IntentResult.NO
            }
        } catch (e: Exception) {
            ZLog.w(TAG, "Failed to parse LLM JSON: $cleaned", e)
            parseIntentFallback(cleaned)  // P1-2-3：委托共用词边界正则解析
        }
    }
}
