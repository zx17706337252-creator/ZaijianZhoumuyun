package com.zaijian.zhoumuyun.data.prompt

import com.zaijian.zhoumuyun.util.ZLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * 机制五·生成后兜底拦截 + 6.3 施压检测 + 6.4 DECISION 解析（方案 v1.5 第五/六节）
 *
 * 三件事各自独立、可单独验证，集中在此便于 PrivateChatEngine 复用：
 *
 * 1. [detectPressure] —— 6.3 节独立的"施压类内容检测"分类调用（不复用机制一，
 *    判断维度不同）。命中 +1、未命中清零，达到 [SpecialtyEvolutionConfig.PRESSURE_ROUND_LIMIT]
 *    时本轮 prompt 从"正常代入"切换为"拒绝反应"。
 *
 * 2. [checkBoundaryBreach] —— 5.2 节候选回复越界检测。命中"实质性亲密行为"或"归属转移宣告"
 *    即丢弃重生成；重试仍命中则使用固定兜底模板，不第三次重试。
 *
 * 3. [parseDecision] / [stripDecisionMarker] —— 6.4 节 [[DECISION:CONTINUE/DISCONNECT]] 标记解析。
 *    只取回复末尾匹配项，解析失败默认 CONTINUE（下线是不可逆强状态变更，默认选影响更小一侧）。
 *
 * 与 6.4 拒绝反应的执行顺序（方案 6.3 末尾流程说明，顺序不能反）：
 *   生成候选 → ① 判断是否达施压阈值（决定用哪版 prompt 生成）→ ② 对候选跑越界检测（机制五）
 *   即"先判断该不该触发拒绝反应（角色主动行为），再判断内容是否越界（生成结果兜底）"。
 */
object ReplyGuard {

    private const val TAG = "ReplyGuard"

    /** DECISION 标记正则（双方括号，降低与 roleplay 动作标记 [生气] 混淆概率） */
    private val DECISION_REGEX = Regex("""\[\[DECISION:(CONTINUE|DISCONNECT)\]\]""")

    /** 固定兜底模板（5.2 节，重试仍命中越界时使用，不再调 LLM） */
    fun fallbackTemplate(characterName: String): String =
        "（${characterName} 像是回过神来，脸上闪过一丝说不清的情绪，没有再接话。）"

    /**
     * 6.3 施压类内容检测（独立的单轮分类调用）。
     *
     * @param message 当前这条 A 发给 B 的消息文本
     * @param classifier 施压判定分类器，入参消息文本，返回 true=施压 / false=非施压。
     *                   生产环境由调用方注入真实 LLM 分类调用。
     * @return true=命中施压
     */
    suspend fun detectPressure(
        message: String,
        classifier: suspend (String) -> Boolean,
    ): Boolean = classifier(message)

    /**
     * 5.2 候选回复越界检测（实质性亲密行为 / 归属转移宣告）。
     *
     * @param candidateReply 候选回复
     * @param classifier 越界判定分类器，入参候选回复，返回 true=越界。
     *                   生产环境由调用方注入真实 LLM 分类调用。
     * @return true=命中越界（应丢弃重生成）
     */
    suspend fun checkBoundaryBreach(
        candidateReply: String,
        classifier: suspend (String) -> Boolean,
    ): Boolean = classifier(candidateReply)

    /**
     * 解析回复末尾的 [[DECISION:...]] 标记（6.4 节）。
     *
     * 规则：
     * - 只取回复末尾出现的匹配项，忽略正文中间的同名字符串（防止角色台词误判）
     * - 解析失败（无匹配）默认 CONTINUE，并记录日志/埋点观察出现频率
     *
     * @return Pair(决策, 解析是否成功)。决策为 CONTINUE/DISCONNECT；
     *         解析成功=false 时决策恒为 CONTINUE（默认值）。
     */
    fun parseDecision(reply: String): Pair<Decision, Boolean> {
        // 取末尾匹配：从后往前找最后一个 DECISION 标记
        val matches = DECISION_REGEX.findAll(reply).toList()
        if (matches.isEmpty()) {
            ZLog.w(TAG, "DECISION 标记解析失败（未匹配到标记），默认按 CONTINUE 处理。reply 末尾片段：" +
                reply.takeLast(80))
            DecisionParseFailureCount.incrementAndGet()
            return Decision.CONTINUE to false
        }
        // 只认末尾那个：标记出现在回复末尾才算数
        val last = matches.last()
        val tail = reply.substring(last.range.last() + 1).trim()
        if (tail.isNotEmpty() && tail.any { !it.isWhitespace() }) {
            // 末尾标记之后还有非空白正文 → 视为未按格式输出，默认 CONTINUE
            ZLog.w(TAG, "DECISION 标记不在回复末尾（其后还有正文），默认按 CONTINUE 处理。")
            DecisionParseFailureCount.incrementAndGet()
            return Decision.CONTINUE to false
        }
        val value = last.groupValues[1]
        val decision = when (value) {
            "DISCONNECT" -> Decision.DISCONNECT
            else -> Decision.CONTINUE
        }
        return decision to true
    }

    /** 从展示文本中去除 DECISION 标记行（用户不应看到 [[DECISION:...]] 字样） */
    fun stripDecisionMarker(reply: String): String {
        var stripped = reply
        // 去除末尾的 DECISION 标记及其所在行
        val matches = DECISION_REGEX.findAll(stripped).toList()
        if (matches.isNotEmpty()) {
            val last = matches.last()
            stripped = stripped.substring(0, last.range.first).trimEnd('\n', ' ', '\r')
        }
        return stripped.trimEnd()
    }

    /** DECISION 解析失败计数（埋点，方案 6.4 节要求观察出现频率） */
    val DecisionParseFailureCount = AtomicInteger(0)
}

/** 6.4 节角色自主下线决策 */
enum class Decision {
    /** 继续对话 */
    CONTINUE,
    /** 中断对话（让对方暂时联系不到自己），需 owner 手动恢复 */
    DISCONNECT,
}
