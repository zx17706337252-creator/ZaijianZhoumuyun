package com.zaijian.zhoumuyun.domain.pregnancy

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.db.entity.PregnancyQuestionType
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
//  PregnancyAnswerConsistencyChecker
//
//  D3 孕期共设系统 · 语义一致性判定组件（优先级 3）
//
//  职责：
//    接收同一槽位的「上一条答案」与「本次新答案」，调用 LLM 判断两者
//    语义是否一致，返回 ConsistencyResult。
//
//  触发时机：
//    某槽位收到第 2 条（及后续）答案时，由 PregnancyAnswerRepository
//    或 ChatViewModel 的配对状态机调用。
//    第 1 条答案无历史可比，直接存入，不触发本组件。
//
//  设计约束：
//    - 单次调用；不维护内部状态。
//    - LLM 只返回 JSON {"result":"CONSISTENT"} 或 {"result":"CONTRADICTORY"}，
//      不需要解释文字。
//    - 解析失败时默认 CONTRADICTORY（保守策略：宁可多问一次，不草率锁定）。
//    - 本组件不做槽位锁定，锁定逻辑在调用方（PregnancyAnswerRepository）。
//
//  修正说明（v2）：
//    - 移除 @Singleton / @Inject（项目未引入 Hilt/Dagger，所有 Repository
//      均为普通构造函数，在 ChatViewModel 中手动实例化）。
//    - LLMProvider 参数改为懒加载函数 providerFn: () -> LLMProvider?，
//      与 Phase28Part1/2/3Tools.kt 等组件保持一致。
//      原因：避免初始化时 provider 未配置；确保用户切换 provider/Key 后
//      本组件始终使用最新实例，不持有过期引用。
// ─────────────────────────────────────────────────────────────────────────────

/** 语义一致性判定结果 */
enum class ConsistencyResult {
    /** 两条答案语义一致 → 调用方可执行 lockSlot() */
    CONSISTENT,

    /** 两条答案语义矛盾或无法确定 → 调用方保留记录，等待下一次机会 */
    CONTRADICTORY,
}

// 注：原 @Serializable ConsistencyJson 数据类已移除。
// 改为在 parseResult() 中直接使用 org.json.JSONObject 解析，
// 项目未引入 kotlinx.serialization 依赖。

class PregnancyAnswerConsistencyChecker(
    private val providerFn: () -> LLMProvider?,
) {

    companion object {
        private const val TAG = "AnswerConsistency"

        /** P2-3 修复：LLM 输入长度上限，避免超长用户输入超出模型上下文窗口导致请求失败。 */
        private const val MAX_INPUT_LENGTH = 500

        /**
         * LLM 系统提示：让模型只返回结构化 JSON，不带任何 Markdown 或
         * 前置说明。措辞刻意简短，降低 token 消耗。
         */
        private val SYSTEM_PROMPT = """
            你是一个语义一致性判定工具。
            你的任务：判断用户对「同一个问题」在两个不同时间给出的两条回答，
            语义上是否一致（即表达的核心偏好/期待是否相符），还是相互矛盾。
            
            判定标准：
            - 用词不同、表述风格不同，但核心含义相符 → CONSISTENT
            - 核心偏好/期待明显相反或无法调和 → CONTRADICTORY
            - 无法确定时 → CONTRADICTORY（保守策略）
            
            输出格式（只输出 JSON，不要任何解释或 Markdown）：
            {"result":"CONSISTENT"}
            或
            {"result":"CONTRADICTORY"}
        """.trimIndent()

        /** 各 questionType 的中文描述，帮助模型理解问题背景 */
        private fun questionTypeContext(type: PregnancyQuestionType): String = when (type) {
            PregnancyQuestionType.NAME_PREF ->
                "问题方向：关于未来孩子的名字偏好（风格、音感、含义等）"
            PregnancyQuestionType.PERSONA ->
                "问题方向：关于未来孩子的性格期待（活泼/安静、独立/依赖等）"
            PregnancyQuestionType.WORLDVIEW ->
                "问题方向：关于对孩子未来的世界观/人生态度的期待（乐观/谨慎、追梦/踏实等）"
            PregnancyQuestionType.WORRY ->
                "问题方向：关于对孩子未来最担心的事（安全、情感、人际等）"
        }
    }

    /**
     * 判断 [previousAnswer] 与 [newAnswer] 在语义上是否一致。
     *
     * @param questionType 槽位对应的问题类型，用于构建判定上下文
     * @param previousAnswer 该槽位的上一条（最近）答案原文
     * @param newAnswer 本次新收到的答案原文
     * @return [ConsistencyResult]；LLM 调用失败时返回 [ConsistencyResult.CONTRADICTORY]
     */
    suspend fun check(
        questionType: PregnancyQuestionType,
        previousAnswer: String,
        newAnswer: String,
    ): ConsistencyResult {

        val provider = providerFn()
        if (provider == null) {
            ZLog.w(TAG, "LLMProvider not available, defaulting to CONTRADICTORY")
            return ConsistencyResult.CONTRADICTORY
        }

        val userPrompt = buildUserPrompt(questionType, previousAnswer, newAnswer)

        return try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(
                    LLMMessage(role = "user", content = userPrompt),
                ),
                systemPrompt = SYSTEM_PROMPT,
                // 只需要极短的输出，控制 token 避免浪费
                config = LLMConfig(
                    // model 留空：由 Provider 实现类（如 OpenAICompatProvider）在
                    // P3-24 修复：model = "" 依赖 Provider 的 buildRequestBody 中
                    // config.model.ifEmpty { defaultModel } 非契约兜底行为。
                    // 当前行为正确（取用户配置的默认模型），但若未来 Provider 实现变更
                    // 可能失效。保留此注释标记依赖关系。
                    model = "",
                    maxTokens = 32,
                    temperature = 0.2f,
                    stream = false,
                ),
            )

            parseResult(response)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e(TAG, "LLM call failed, defaulting to CONTRADICTORY", e)
            ConsistencyResult.CONTRADICTORY
        }
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    private fun buildUserPrompt(
        questionType: PregnancyQuestionType,
        previousAnswer: String,
        newAnswer: String,
    ): String = buildString {
        appendLine(questionTypeContext(questionType))
        appendLine()
        appendLine("【上一次回答】")
        appendLine(previousAnswer.trim().take(MAX_INPUT_LENGTH))
        appendLine()
        appendLine("【本次回答】")
        appendLine(newAnswer.trim().take(MAX_INPUT_LENGTH))
        appendLine()
        append("请判断这两条回答的语义是否一致，输出 JSON。")
    }

    private fun parseResult(raw: String): ConsistencyResult {
        // P1-2-3：委托共用工具清洗 JSON（消除重复代码）
        val cleaned = cleanLlmJson(raw)
        return try {
            when (parseResultField(cleaned).uppercase()) {
                "CONSISTENT" -> ConsistencyResult.CONSISTENT
                else         -> ConsistencyResult.CONTRADICTORY
            }
        } catch (e: Throwable) {
            ZLog.w(TAG, "Failed to parse LLM JSON: $cleaned", e)
            parseConsistencyFallback(cleaned)  // P1-2-3：委托共用词边界正则解析
        }
    }
}
