package com.zaijian.zhoumuyun.data.manager

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
//  UserConsentIntentJudge
//
//  问题17（UserConsentKeywords 短词误匹配）第二阶段修复：
//  AI 判定优先，关键词判定作为 LLM 调用失败/超时时的兜底。
//
//  背景（第一阶段修复，见 TriggerKeywords.kt / PregnancyTriggerManager.
//  detectUserConsent() 内联注释）：把单字高频词（好/嗯/行/想/要/不/别）从
//  子串匹配改成"整句严格相等"，解决了"你好"→"好"、"还好/不错/别的事"这类
//  误判，但关键词匹配终究只能识别"预先想到的表达方式"——用户真实的自然语言
//  同意/拒绝表达远比一张固定词表丰富（比如"那就试试看吧""我觉得可以""还是
//  算了吧，我还没想好"这类语义清晰但不含表内任何词的说法，关键词表天然
//  覆盖不到）。
//
//  本次修复：引入 AI 语义判定作为主判定路径，关键词表（含问题17第一阶段的
//  单字严格匹配收紧）降级为"LLM 不可用/调用异常/超时"时的兜底容错层——
//  两套判定逻辑同时存在、优先级明确、互不干扰：
//    ① 正常情况：调用本组件 judge()，LLM 返回 CONSENT/REFUSAL/UNCLEAR 三态语义判定；
//    ② LLM 调用失败（Provider 未配置、网络异常、限流重试后仍失败等）或
//       超过 [JUDGE_TIMEOUT_MS] 超时：捕获异常，退回
//       PregnancyTriggerManager.detectUserConsentByKeyword()（原关键词链路，
//       原样保留，不做任何删减）。
//
//  设计约束（与 FertileWindowConsentJudge / PregnancyAnswerIntentDetector
//  同风格，保持本项目 D2/D3 判定组件一致的写法）：
//    - 无内部状态、单次调用、LLM 只返回精简 JSON，maxTokens 控制在很小的数值；
//    - 不持有 LLMProvider 实例，使用懒加载 providerFn: () -> LLMProvider?，
//      确保用户切换 provider/Key 后本组件始终使用最新实例；
//    - 显式 withTimeout 包裹整个判定调用，把"期望响应时间"从 chatSync()
//      默认的 15s/60s（connectTimeout/readTimeout）收紧到 8s——多数网络异常
//      场景（DNS 失败、连接被拒绝等）能在 8s 内被真正中断；极少数"连接建立
//      但迟迟不吐数据"的病态情形，受限于底层 HttpURLConnection 同步阻塞
//      I/O，实际中断时机仍以 60s readTimeout 为准（withTimeout 只能在协程
//      挂起点生效），这是与本项目其它 chatSyncWithRetry 使用方共享的已知
//      边界，见 JUDGE_TIMEOUT_MS 的详细说明；
//    - 三态输出（CONSENT/REFUSAL/UNCLEAR）与 detectUserConsent(): Boolean? 的
//      true/false/null 语义一一对应，UNCLEAR 与"无法确定"合并处理（既非
//      明确同意也非明确拒绝，交由调用方走模糊分支），不强行二选一。
// ─────────────────────────────────────────────────────────────────────────────

/** AI 语义判定结果：三态，分别对应"明确同意"/"明确拒绝"/"无法判定（模糊）" */
enum class ConsentJudgeResult {
    CONSENT,
    REFUSAL,
    UNCLEAR,
}

class UserConsentIntentJudge(
    private val providerFn: () -> LLMProvider?,
) {

    companion object {
        private const val TAG = "UserConsentJudge"

        /**
         * 整体超时上限（毫秒）。判定链路是"发消息"主流程的同步一环，
         * 不能让一次异常慢的请求拖住整条消息发送链路——超时后
         * withTimeout 会在下一个协程挂起点抛出 TimeoutCancellationException，
         * 由调用方捕获并降级到关键词兜底，避免无限期等待。
         *
         * 取值参考：本组件 maxTokens=32，属于"判定型"极小输出，正常
         * 响应应在数秒内完成；8s 已经是相当宽松的上限，足以覆盖网络抖动，
         * 又不至于让用户在发送消息后长时间停滞等待判定结果。
         *
         * 已知边界（与本项目 FertileWindowConsentJudge / PregnancyAnswerIntentDetector
         * 共享同一底层限制，非本次修复引入）：底层 chatSync() 使用
         * HttpURLConnection 同步阻塞 I/O，withTimeout 只能在协程挂起点
         * 生效——如果线程正阻塞在一次同步 socket 读写中，实际中断时机
         * 以该次阻塞调用返回为准（上限为 OpenAICompatProvider 里配置的
         * connectTimeout=15s / readTimeout=60s），而不是精确在 8s 那一刻。
         * 8s 是"期望"超时而非"硬性抢占"超时，但仍能覆盖绝大多数场景
         * （网络确实不可达、DNS 失败、连接被拒绝等会在 connectTimeout 内
         * 快速失败），只有极少数"连接建立后迟迟不吐数据"的病态情形才会
         * 拖到 60s 量级——这与不加 withTimeout 相比仍是明确的改善。
         */
        private const val JUDGE_TIMEOUT_MS = 8_000L

        private val SYSTEM_PROMPT = """
            你是一个对话意图判定工具。
            场景：一个角色刚刚向用户提出了一个亲密/情感相关的请求或提议
            （比如是否愿意做某件两人共同的事），现在用户发来了回复。
            你的任务：判断用户这条回复，对该请求/提议表达的是同意、拒绝，
            还是无法明确判断（模糊、答非所问、顾左右而言他、单纯的问候或
            无关闲聊）。

            判定标准：
            - 用户明确表达愿意、答应、接受 -> CONSENT
              （例如：明确的肯定表态、清晰的"可以/愿意"类回应，哪怕说法
              不是固定套话，只要语义上是在明确答应即可）
            - 用户明确表达不愿意、拒绝、还没准备好 -> REFUSAL
              （例如：明确的否定表态、清晰的推脱/拒绝类回应）
            - 无法从字面明确判断是同意还是拒绝（包括单纯问候、闲聊、
              跑题、态度暧昧不清）-> UNCLEAR
            - 无法确定时一律选择 UNCLEAR（保守策略，不要猜测）

            注意：这不是在判断字面是否出现"同意""好"这类词，而是在判断
            真实语义意图——反问、反讽、否定之否定等情况请按真实语义理解，
            不要机械匹配字面词汇。

            输出格式（只输出 JSON，不要任何解释或 Markdown）：
            {"result":"CONSENT"}
            或
            {"result":"REFUSAL"}
            或
            {"result":"UNCLEAR"}
        """.trimIndent()
    }

    /**
     * 对用户回复 [userText] 做 AI 语义判定。
     *
     * @return [ConsentJudgeResult]
     * @throws Exception LLM 调用失败、超时或解析失败时抛出（不在内部吞掉，
     *   由调用方 [PregnancyTriggerManager.detectUserConsent] 捕获并降级到
     *   关键词兜底——这是"AI 判定优先，关键词兜底"设计的关键：本方法不做
     *   任何静默降级，保持"要么给出真实 AI 判定结果，要么让调用方知道
     *   判定失败"的清晰契约）。
     */
    suspend fun judge(userText: String): ConsentJudgeResult {
        val provider = providerFn()
            ?: throw IllegalStateException("LLMProvider not available")

        val userPrompt = buildString {
            appendLine("【用户回复原文】")
            appendLine(userText.trim())
            appendLine()
            append("请判断用户这条回复是同意、拒绝，还是无法明确判断，输出 JSON。")
        }

        val response = withTimeout(JUDGE_TIMEOUT_MS) {
            provider.chatSyncWithRetry(
                messages = listOf(LLMMessage(role = "user", content = userPrompt)),
                systemPrompt = SYSTEM_PROMPT,
                config = LLMConfig(
                    model = "",
                    maxTokens = 32,
                    temperature = 0.2f,
                    stream = false,
                ),
            )
        }
        return parseResult(response)
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    private fun parseResult(raw: String): ConsentJudgeResult {
        // 防御：有些模型会在 JSON 外包一层 ```json ... ```
        val cleaned = raw
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val fromJson = try {
            when (JSONObject(cleaned).getString("result").uppercase()) {
                "CONSENT" -> ConsentJudgeResult.CONSENT
                "REFUSAL" -> ConsentJudgeResult.REFUSAL
                else      -> ConsentJudgeResult.UNCLEAR
            }
        } catch (e: Exception) {
            null
        }
        if (fromJson != null) return fromJson

        ZLog.w(TAG, "Failed to parse LLM JSON, falling back to bare-text word-boundary match: $cleaned")
        // 裸文本回退：部分模型偶尔直接输出英文单词而非 JSON。用词边界正则
        // （而非 contains）避免 "REFUSAL" 子串误判——比如不能让 "UNCLEAR"
        // 里没有的东西影响判断，这里两个词本身互不为子串关系，但仍统一走
        // 正则以保持与本项目其它判定组件（LlmIntentParse.kt）一致的防御写法。
        val negativeMatch = Regex("""(?i)\bREFUSAL\b""").containsMatchIn(cleaned)
        val positiveMatch = Regex("""(?i)\bCONSENT\b""").containsMatchIn(cleaned)
        return when {
            negativeMatch && !positiveMatch -> ConsentJudgeResult.REFUSAL
            positiveMatch && !negativeMatch -> ConsentJudgeResult.CONSENT
            else                            -> ConsentJudgeResult.UNCLEAR
        }
    }
}
