package com.zaijian.zhoumuyun.domain

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.db.dao.EvaluationSessionDao
import com.zaijian.zhoumuyun.data.db.dao.LearningGoalDao
import com.zaijian.zhoumuyun.data.db.entity.EvaluationSessionEntity
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

// P3-2 修复：统一状态枚举，替代硬编码字符串常量
private enum class SessionStatus { PENDING, REVIEWED, SCORED, SKIPPED }

/**
 * EvaluationEngine — Phase 24 打分机制核心
 *
 * 职责：
 * ① [maybeCreateSession]  — 判断是否满足触发条件，满足则创建 EvaluationSession（PENDING）
 * ② [runAgentReview]      — 以 Agent B 角色调用 LLM 精简评审，结果写回 DB（REVIEWED）
 * ③ [submitUserScore]     — 用户打分后计算综合分，完成整个 Session（SCORED）
 * ④ [skipSession]         — 用户跳过打分（SKIPPED，不纳入 Phase 26 提炼统计）
 *
 * 触发条件（由 ChatViewModel.sendMessage 在 AI 回复完成后调用）：
 *   - AI 回复字符数 ≥ 150
 *   - 当前角色存在 isActive=true 的学习目标
 *
 * Agent B 精简 Prompt 策略（v1.2 修订）：
 *   - 评的是本次对话内容，而非规则文本本身
 *   - 三个维度：目标相关性（1–5）、内容深度（1–5）、表达风格（1–5）
 *   - Temperature=0.2，maxTokens=250，防止冗长
 *   - 输出要求：严格 JSON，避免自由文本，便于解析
 */
class EvaluationEngine(
    private val evaluationSessionDao: EvaluationSessionDao,
    private val learningGoalDao: LearningGoalDao,
    private val provider: LLMProvider,
) {

    companion object {
        /** 同一角色两次 Session 创建之间的最小冷却时间（毫秒），防止快速对话堆积 PENDING */
        const val SESSION_COOLDOWN_MS = 5 * 60 * 1000L  // 5 分钟
    }

    /** 记录各 characterId 上次创建 Session 的时间（内存缓存，重启后重置） */
    private val lastSessionAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    // ── ① 触发判断 + Session 创建 ─────────────────────────────

    /**
     * 在 AI 回复完成后调用。
     * 仅做触发条件判断（字数 + 冷却 + 激活目标），满足时插入一条 PENDING
     * Session 并返回其 ID；本函数不会调用 Agent B 评审，Agent B 评审
     * （[runAgentReview]）由调用方（ChatViewModel）在拿到 sessionId 后
     * 另行发起。
     *
     * @param characterId   当前角色 ID
     * @param replyContent  AI 的完整回复文本
     * @param messageId     AI 回复的消息 ID（存入 triggerMessageId）
     * @return 新建的 EvaluationSession ID 列表，空列表 = 未触发
     */
    suspend fun maybeCreateSessions(
        characterId: Int,
        replyContent: String,
        messageId: String,
    ): List<String> = withContext(Dispatchers.IO) {

        // 条件一：回复 ≥ 150 字
        if (replyContent.length < 150) return@withContext emptyList<String>()

        // 冷却检查：同一角色 5 分钟内不重复创建 Session。
        // M-4 修复：原先 get（读）和最后的 set（写）是两个独立操作，
        // 两个并发协程可能同时通过冷却检查后都执行 insert，产生重复 Session。
        // 改用 compute() 原子化"检查 + 预占"，与 DistillationEngine 的修复模式一致：
        // 不在 lambda 内修改外部变量，完全依赖返回值判断是否抢到本次创建权。
        val now = System.currentTimeMillis()
        val claimed = lastSessionAt.compute(characterId) { _, prev ->
            val last = prev ?: 0L
            if (now - last >= SESSION_COOLDOWN_MS) now else prev
        }
        if (claimed != now) return@withContext emptyList<String>()

        // 条件二：有激活目标
        val activeGoals = learningGoalDao.getActive(characterId)
        if (activeGoals.isEmpty()) return@withContext emptyList<String>()

        // P2-4 修复：为每个激活目标创建一个 Session，不再只取第一条。
        // 原先只取 activeGoals.first() 意味着同一角色存在多个激活目标时
        // 只有第一个目标会触发 Agent B 评审，其他目标被悄悄忽略。
        // 改为遍历所有激活目标，每个目标一个独立 Session。
        val sessionIds = mutableListOf<String>()
        for (goal in activeGoals) {
            val sessionId = UUID.randomUUID().toString()
            sessionIds.add(sessionId)
            val session = EvaluationSessionEntity(
                id               = sessionId,
                characterId      = characterId,
                goalId           = goal.id,
                triggerMessageId = messageId,
                status           = SessionStatus.PENDING.name,
                createdAt        = now,
                updatedAt        = now,
            )
            evaluationSessionDao.insert(session)
        }
        sessionIds
    }

    // ── ② Agent B 精简评审 ────────────────────────────────────

    /**
     * 以 Agent B（评审角色）调用 LLM 对本次对话内容进行三维评分。
     * 结果写入 DB，状态流转 PENDING → REVIEWED。
     *
     * @param sessionId     需要评审的 Session ID
     * @param goalTitle     激活目标标题（注入 Prompt 提供评分依据）
     * @param replyContent  AI 本轮回复全文
     * @param userMessage   用户本轮消息（可选，提供对话上下文）
     */
    suspend fun runAgentReview(
        sessionId: String,
        goalTitle: String,
        replyContent: String,
        userMessage: String = "",
    ): Boolean = withContext(Dispatchers.IO) {

        val session = evaluationSessionDao.getById(sessionId) ?: return@withContext false
        if (session.status != SessionStatus.PENDING.name) return@withContext false

        // Agent B 精简评审 Prompt
        val systemPrompt = """
            你是学习评审 Agent（Agent B）。
            请根据用户的学习目标，对本次对话内容进行客观打分。
            
            评分维度（各 1.0–5.0，保留一位小数）：
            - relevance（目标相关性）：本次回复与学习目标的匹配程度
            - depth（内容深度）：知识密度、逻辑层次、是否有实质收获
            - style（表达风格）：清晰度、易读性、是否符合学习场景
            
            综合评分 overall = (relevance + depth + style) / 3
            
            严格按以下 JSON 格式输出，不加任何其他文字：
            {"relevance":X.X,"depth":X.X,"style":X.X,"overall":X.X,"comment":"≤80字中文评语"}
        """.trimIndent()

        val userPrompt = buildString {
            append("学习目标：$goalTitle\n\n")
            if (userMessage.isNotBlank()) {
                append("用户提问：${userMessage.take(200)}\n\n")
            }
            append("AI 回复（被评审内容）：\n${replyContent.take(800)}")
        }

        return@withContext try {
            val response = provider.chatSyncWithRetry(
                messages     = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config       = LLMConfig(
                    model       = "",
                    maxTokens   = 250,
                    temperature = 0.2f,
                    stream      = false,
                ),
            )

            // 解析 JSON 结果
            val jsonStr = response.trim()
                .let { raw ->
                    // 防止 LLM 在 JSON 前后加 Markdown 代码块
                    val start = raw.indexOf('{')
                    val end   = raw.lastIndexOf('}')
                    if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
                }

            val obj         = JSONObject(jsonStr)
            val relevance   = obj.optDouble("relevance", 3.0).toFloat().coerceIn(1f, 5f)
            val depth       = obj.optDouble("depth", 3.0).toFloat().coerceIn(1f, 5f)
            val style       = obj.optDouble("style", 3.0).toFloat().coerceIn(1f, 5f)
            val overall     = obj.optDouble("overall", ((relevance + depth + style) / 3).toDouble()).toFloat().coerceIn(1f, 5f)
            val comment     = obj.optString("comment", "").take(100)

            // 重新构造标准 JSON（防止原始 JSON 字段顺序不一致）
            val agentScoreJson = JSONObject().apply {
                put("relevance", relevance)
                put("depth", depth)
                put("style", style)
                put("overall", overall)
            }.toString()

            // 生成 Agent A 汇报文本（用户可见）
            val reportText = buildReportText(
                goalTitle  = goalTitle,
                relevance  = relevance,
                depth      = depth,
                style      = style,
                overall    = overall,
                comment    = comment,
            )

            evaluationSessionDao.markReviewed(
                sessionId      = sessionId,
                agentScore     = overall,
                agentScoreJson = agentScoreJson,
                agentComment   = comment,
                reportText     = reportText,
            )
            true
        } catch (e: Exception) {
            // 解析失败时写入兜底分（避免 Session 永久卡在 PENDING）
            val fallbackScore   = 3.0f
            val fallbackJson    = JSONObject().apply {
                put("relevance", fallbackScore)
                put("depth", fallbackScore)
                put("style", fallbackScore)
                put("overall", fallbackScore)
            }.toString()
            val fallbackReport  = buildReportText(
                goalTitle  = goalTitle,
                relevance  = fallbackScore,
                depth      = fallbackScore,
                style      = fallbackScore,
                overall    = fallbackScore,
                comment    = "评审结果解析异常，使用默认分",
            )
            evaluationSessionDao.markReviewed(
                sessionId      = sessionId,
                agentScore     = fallbackScore,
                agentScoreJson = fallbackJson,
                agentComment   = "评审结果解析异常",
                reportText     = fallbackReport,
            )
            false
        }
    }

    // ── ③ 用户打分 ────────────────────────────────────────────

    /**
     * 提交用户打分（1–5 星），计算综合分后完成 Session。
     *
     * @param sessionId Session ID
     * @param userScore 用户评分 1–5
     * @param userNote  用户补充说明（可选）
     * @return 最终综合分，null = 操作失败
     */
    suspend fun submitUserScore(
        sessionId: String,
        userScore: Int,
        userNote: String? = null,
    ): Float? = withContext(Dispatchers.IO) {
        val session = evaluationSessionDao.getById(sessionId) ?: return@withContext null
        if (session.status != SessionStatus.REVIEWED.name) return@withContext null

        val safeUserScore    = userScore.coerceIn(1, 5)
        val agentScore       = session.agentScore ?: 3.0f
        val compositeScore   = (agentScore * 0.4f + safeUserScore * 0.6f)
            .coerceIn(1f, 5f)
            .let { "%.2f".format(it).toFloat() }  // 保留两位小数

        evaluationSessionDao.markScored(
            sessionId      = sessionId,
            userScore      = safeUserScore,
            userNote       = userNote,
            compositeScore = compositeScore,
        )
        compositeScore
    }

    // ── ④ 跳过 ───────────────────────────────────────────────

    /**
     * 用户跳过本次打分（Session 标记为 SKIPPED，不纳入 Phase 26 提炼统计）。
     */
    suspend fun skipSession(sessionId: String) = withContext(Dispatchers.IO) {
        evaluationSessionDao.markSkipped(sessionId)
    }

    // ── 私有工具 ──────────────────────────────────────────────

    /**
     * 生成 Agent A 向用户展示的评审汇报文本。
     * 格式简洁，突出分数与亮点，引导用户打分。
     */
    private fun buildReportText(
        goalTitle: String,
        relevance: Float,
        depth: Float,
        style: Float,
        overall: Float,
        comment: String,
    ): String {
        val stars = overallToStars(overall)
        return buildString {
            appendLine("📊 **本次对话评审** · 目标：$goalTitle")
            appendLine()
            appendLine("| 维度 | 得分 |")
            appendLine("|------|------|")
            appendLine("| 目标相关性 | ${"%.1f".format(relevance)} / 5 |")
            appendLine("| 内容深度 | ${"%.1f".format(depth)} / 5 |")
            appendLine("| 表达风格 | ${"%.1f".format(style)} / 5 |")
            appendLine()
            appendLine("**综合评分：${"%.1f".format(overall)} / 5** $stars")
            if (comment.isNotBlank()) {
                appendLine()
                appendLine("💬 $comment")
            }
        }.trimEnd()
    }

    /** 将 1–5 分转换为 ⭐ 星形展示（最多 5 颗，向下取整） */
    private fun overallToStars(score: Float): String {
        val full  = score.toInt().coerceIn(0, 5)
        val empty = 5 - full
        return "⭐".repeat(full) + "☆".repeat(empty)
    }
}
