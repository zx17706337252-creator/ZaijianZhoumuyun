package com.zaijian.zhoumuyun.data.manager

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.domain.pregnancy.cleanLlmJson
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
//  FertileWindowConsentJudge
//
//  怀孕弹窗触发重构 · 门3：AI 语义判定组件。
//
//  职责：
//    判断"最近 N 轮对话"是否已经到达"即将发生性关系的最后一步"
//    （明确的身体接触意图、场景已无退路），决定是否弹出同意弹窗。
//
//  调用时机（仅在门1+门2 都通过后调用，平时零 token 消耗）：
//    见 PregnancyTriggerManager.shouldEvaluateFertileWindowConsent()——
//    门1（关系阶段 CORE）+ 门2（CyclePhase.FERTILE）同时满足、且本排卵期
//    窗口尚未弹过弹窗时，调用方才会发起本组件的判定调用。
//    建议在每轮 AI 回复生成完成后，于后台协程异步触发，不阻塞消息展示。
//
//  设计约束：
//    与 PregnancyAnswerIntentDetector（domain/pregnancy）同风格：
//    无内部状态、单次调用、LLM 只返回精简 JSON，maxTokens 控制在很小的数值；
//    解析失败或 Provider 不可用时默认 NO（保守策略——错过一次弹窗机会，
//    好过误弹窗打断剧情节奏，下一轮对话仍会重新判定）。
//    不持有 LLMProvider 实例，使用懒加载 providerFn: () -> LLMProvider?，
//    确保用户切换 provider/Key 后本组件始终使用最新实例。
// ─────────────────────────────────────────────────────────────────────────────

class FertileWindowConsentJudge(
    private val providerFn: () -> LLMProvider?,
) {

    companion object {
        private const val TAG = "FertileWindowJudge"

        private val SYSTEM_PROMPT = """
            你是一个对话场景判定工具。
            根据接下来给出的最近对话片段，判断双方是否已经到达
            即将发生性关系的最后一步（明确的身体接触意图、场景已经没有退路）。

            判定标准：
            - 双方已经明确表达身体接触意图、场景已经发展到没有退路 -> YES
            - 还在试探、暗示、调情，但尚未到最后一步 -> NO
            - 无法确定时 -> NO（保守策略）

            输出格式（只输出 JSON，不要任何解释或 Markdown）：
            {"result":"YES"}
            或
            {"result":"NO"}
        """.trimIndent()
    }

    /**
     * @param recentTurns 最近 5 轮对话（用户 + AI 交替），调用方从消息历史截取，
     *   按时间正序传入（旧→新）。
     * @return true = AI 判定已到最后一步（门3通过，调用方可以弹出同意弹窗）；
     *   Provider 不可用、对话片段为空或解析失败时返回 false（保守策略，不弹窗）。
     */
    suspend fun judgeLastStep(recentTurns: List<LLMMessage>): Boolean {
        val provider = providerFn()
        if (provider == null) {
            ZLog.w(TAG, "LLMProvider not available, defaulting to NO")
            return false
        }
        if (recentTurns.isEmpty()) return false

        val userPrompt = buildString {
            appendLine("【最近对话片段】")
            recentTurns.forEach { msg ->
                val speaker = if (msg.role == "user") "用户" else "角色"
                appendLine("$speaker：${msg.content.trim()}")
            }
            appendLine()
            append("请判断以上对话是否已经到达即将发生性关系的最后一步，输出 JSON。")
        }

        return try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage(role = "user", content = userPrompt)),
                systemPrompt = SYSTEM_PROMPT,
                config = LLMConfig(
                    model = "",
                    maxTokens = 32,
                    temperature = 0.2f,
                    stream = false,
                ),
            )
            parseResult(response)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ZLog.w(TAG, "LLM 判定超时，默认返回 NO", e)
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 必须重新抛出：结构化并发约定要求取消信号不能被吞掉
            throw e
        } catch (e: Exception) {
            ZLog.e(TAG, "LLM call failed, defaulting to NO", e)
            false
        }
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    private fun parseResult(raw: String): Boolean {
        // 方案 5-8：复用 LlmIntentParse.cleanLlmJson()，统一清洗逻辑维护点
        val cleaned = cleanLlmJson(raw)

        return try {
            JSONObject(cleaned).getString("result").uppercase() == "YES"
        } catch (e: Exception) {
            ZLog.w(TAG, "Failed to parse LLM JSON: $cleaned", e)
            // P1-3 修复：词边界匹配兜底，复用 LlmIntentParse 的 \b 正则模式。
            // 原先 contains("YES") 会误匹配 "EYES" / "YESTERDAY" 等包含子串的单词；
            // 词边界正则 \bYES\b 只匹配独立的 YES 单词，与 LlmIntentParse 的判定逻辑一致。
            val hasYes = Regex("\\bYES\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)
            val hasNo  = Regex("\\bNO\\b",  RegexOption.IGNORE_CASE).containsMatchIn(cleaned)
            hasYes && !hasNo
        }
    }
}
