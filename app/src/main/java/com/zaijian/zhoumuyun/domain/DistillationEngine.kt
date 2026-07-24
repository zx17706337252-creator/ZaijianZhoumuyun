package com.zaijian.zhoumuyun.domain

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.dao.EvaluationSessionDao
import com.zaijian.zhoumuyun.data.db.dao.LearningGoalDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryDao
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.util.UUID

/**
 * DistillationEngine — Phase 26 MEMORY→RULE 定向提炼引擎
 *
 * ## 职责
 *
 * 在每次用户完成打分（[EvaluationEngine.submitUserScore]）后，
 * [ChatViewModel] 调用 [maybeDistill] 判断是否满足提炼条件。
 * 满足时触发以下完整链路：
 *
 * ```
 * 高分 Session 积累（≥ 5 次，compositeScore ≥ 3.5）
 *         │
 *         ▼
 * [maybeDistill] 触发判断
 *         │
 *         ▼
 * 读取近 N 个高分 Session 的 agentComment + reportText
 *         │
 *         ▼
 * LLM 提炼共同规律（Agent C，temperature=0.2）
 * 输出 2-4 条行为规则（每条 ≤ 50 字）
 *         │
 *         ▼
 * [lockOrWriteRules]
 *   - 规则已存在（content 相似）→ 更新 confidence，达标则 lockRule()
 *   - 规则不存在              → 写入新 RULE 记忆（isLocked=false）
 *         │
 *         ▼
 * goal_update 推进目标进度 +10%（goal_id 对应的激活目标）
 *         │
 *         ▼
 * 返回 DistillResult（供 ChatViewModel 通知 UI）
 * ```
 *
 * ## 锁定条件
 *
 * 一条 RULE 记忆在以下条件同时满足时被锁定（isLocked=true）：
 *   - [RuleConfidence.score] ≥ [LOCK_CONFIDENCE_THRESHOLD]（默认 4.0）
 *   - 该规则在高分 Session 中出现次数 ≥ [LOCK_OCCURRENCE_THRESHOLD]（默认 3）
 *
 * 锁定后该规则会注入 Rule Layer（Phase 25），对 Agent 的对话产生实际影响。
 *
 * ## 线程安全
 *
 * 所有 DB/LLM 操作均在 [Dispatchers.IO] 执行。
 * [maybeDistill] 设计为幂等：同一 goalId 在 [DISTILL_COOLDOWN_MS] 内不会重复触发。
 */
class DistillationEngine(
    private val db: AppDatabase,
    private val evaluationSessionDao: EvaluationSessionDao,
    private val learningGoalDao: LearningGoalDao,
    private val memoryDao: MemoryDao,
    private val provider: LLMProvider,
    /**
     * M3 修复：注入 MemoryRepository，lockOrWriteRules 中的新规则写入
     * 改为调用 repo.save()（主表 + FTS 原子写入），而非直接 memoryDao.insert()
     * （只写主表，FTS 不同步，导致全文检索召回漏掉新规则）。
     * 可空以保持向后兼容，为 null 时降级到 memoryDao.insert()（旧行为）。
     */
    private val memoryRepo: MemoryRepository? = null,
) {

    // ── 配置常量 ───────────────────────────────────────────────

    companion object {
        /** 触发提炼所需的最少高分 Session 数量 */
        const val DISTILL_TRIGGER_COUNT = 5

        /** "高分" Session 的 compositeScore 阈值 */
        const val HIGH_SCORE_THRESHOLD = 3.5f

        /** 每次提炼最多生成的规则条数 */
        const val MAX_RULES_PER_DISTILL = 4

        /** 每次提炼最少生成的规则条数（LLM 输出少于此数则视为失败） */
        const val MIN_RULES_PER_DISTILL = 1

        /** 每条规则最大字符数（LLM 输出超长时截断） */
        const val MAX_RULE_CHARS = 50

        /** 规则锁定所需的最低置信度（0.0–5.0） */
        const val LOCK_CONFIDENCE_THRESHOLD = 4.0f

        /** 规则锁定所需的最少出现次数（在高分 Session 的提炼结果中） */
        const val LOCK_OCCURRENCE_THRESHOLD = 3

        /** 目标进度提炼奖励 delta（每次成功提炼 +10%） */
        const val DISTILL_PROGRESS_DELTA = 0.1f

        /** 同一目标两次提炼之间的最小冷却时间（毫秒），防止频繁触发 */
        const val DISTILL_COOLDOWN_MS = 10 * 60 * 1000L  // 10 分钟

        /** 每次提炼读取的高分 Session 数量上限（提供给 LLM 的上下文） */
        const val SESSIONS_FOR_CONTEXT = 8
    }

    /** 记录各 goalId 上次提炼时间（内存缓存，重启后重置；ConcurrentHashMap 保证并发安全） */
    private val lastDistillAt = java.util.concurrent.ConcurrentHashMap<String, Long>()



    // ── 主入口 ────────────────────────────────────────────────

    /**
     * 在用户完成打分后调用，判断是否触发提炼。
     *
     * @param characterId 当前角色 ID
     * @param goalId      刚完成打分的 Session 关联的学习目标 ID
     * @return [DistillResult]，其中 [DistillResult.triggered] 指示是否真正执行了提炼
     */
    suspend fun maybeDistill(
        characterId: Int,
        goalId: String,
    ): DistillResult = withContext(Dispatchers.IO) {

        // ── 1. 冷却检查（原子预占）────────────────────────────
        // P1-6-5 修复：原 get + put 是两步非原子操作，两个并发调用均能通过 get 检查并同时
        // 进入 LLM 流程，产生重复提炼。改用 compute 原子预占：只有"当前时间 - 旧值 ≥ 冷却时间"
        // 时才写入新时间戳并返回 true（表示"我抢到了本轮提炼权"）；否则保留旧值返回 false。
        // compute 在 ConcurrentHashMap 内部持有 bin 级锁，整个 lambda 原子执行，不会两个
        // 线程同时通过。
        // P1-6-5 修复：用 compute 原子预占冷却时间戳，完全避免 get+put 两步竞态。
        // compute 返回值：写入成功时返回 now，冷却中时返回旧值（!= now）。
        // 不在 lambda 内修改外部变量（避免 CHM 文档警告的副作用），
        // 完全依赖返回值判断是否抢到本轮提炼权。
        val now = System.currentTimeMillis()
        val claimed = lastDistillAt.compute(goalId) { _, prev ->
            val last = prev ?: 0L
            if (now - last >= DISTILL_COOLDOWN_MS) now else prev
        }
        if (claimed != now) {
            return@withContext DistillResult(triggered = false, reason = "冷却中")
        }

        // ── 2. 高分 Session 数量检查 ──────────────────────────
        val highScoreCount = evaluationSessionDao.countHighScoreByGoal(
            goalId    = goalId,
            threshold = HIGH_SCORE_THRESHOLD,
        )
        if (highScoreCount < DISTILL_TRIGGER_COUNT) {
            return@withContext DistillResult(
                triggered = false,
                reason    = "高分 Session 数量不足（当前 $highScoreCount / 需要 $DISTILL_TRIGGER_COUNT）",
            )
        }

        // ── 3. 验证目标存在且激活 ────────────────────────────
        val goal = learningGoalDao.getById(goalId)
            ?: return@withContext DistillResult(triggered = false, reason = "目标不存在")
        if (!goal.isActive) {
            // 防御性清理：已停用目标基本不会再产生新评分 Session，也不会再走到这里，
            // 清掉冷却时间戳避免 lastDistillAt 长期累积已失效目标的记录。
            // remove 是原子操作，不影响其他 goalId 的并发 compute。
            lastDistillAt.remove(goalId)
            return@withContext DistillResult(triggered = false, reason = "目标已停用")
        }

        // ── 4. 读取近期高分 Session 作为 LLM 上下文 ──────────
        // P1-27 修复：原 getScoredByGoal 只取最近 N 条 SCORED Session 再内存 filter
        // compositeScore ≥ threshold，当高分 Session 不在最近 N 条时会被截断，
        // 导致蒸馏上下文为空、静默失效。改用 SQL 层直接过滤的 getHighScoreByGoal，
        // 保证取到的是最近 N 条高分 Session，不受低分 Session 占位影响。
        val recentSessions = evaluationSessionDao.getHighScoreByGoal(
            goalId    = goalId,
            threshold = HIGH_SCORE_THRESHOLD,
            limit     = SESSIONS_FOR_CONTEXT,
        )

        if (recentSessions.isEmpty()) {
            return@withContext DistillResult(triggered = false, reason = "无高分 Session 可用")
        }

        // ── 5. LLM 提炼（Agent C）────────────────────────────
        val distilledRules = runDistillLlm(
            goalTitle  = goal.title,
            sessions   = recentSessions.map { session ->
                SessionSummary(
                    agentComment = session.agentComment ?: "",
                    agentScore   = session.agentScore   ?: 3f,
                    userScore    = session.userScore    ?: 3,
                    composite    = session.compositeScore ?: 3f,
                )
            },
        )

        if (distilledRules.size < MIN_RULES_PER_DISTILL) {
            return@withContext DistillResult(
                triggered = false,
                reason    = "LLM 提炼结果为空或解析失败",
            )
        }

        // ── 6. 写入/升级规则，判断是否锁定 ───────────────────
        val lockResults = lockOrWriteRules(
            characterId    = characterId,
            goalId         = goalId,
            rules          = distilledRules,
        )

        // ── 7. 目标进度 +10%（引擎层检查上限，DAO 层 MIN(1.0) 兜底）────────
        if (goal.progress < 1.0f) {
            learningGoalDao.incrementProgress(
                goalId      = goalId,
                characterId = characterId,
                delta       = DISTILL_PROGRESS_DELTA,
                note        = "本轮提炼新增 ${lockResults.newlyLocked} 条锁定规则",
            )
        }

        // P1-6-5 修复：冷却时间戳已在入口 compute 预占时写入，此处无需二次写入。
        // lastDistillAt[goalId] = System.currentTimeMillis()  ← 已删除，避免覆盖预占值

        DistillResult(
            triggered        = true,
            goalTitle        = goal.title,
            newRules         = distilledRules,
            newlyLockedCount = lockResults.newlyLocked,
            progressDelta    = DISTILL_PROGRESS_DELTA,
            reason           = "成功提炼 ${distilledRules.size} 条规则，其中 ${lockResults.newlyLocked} 条已锁定",
        )
        // end removed
    }

    // ── LLM 提炼调用 ──────────────────────────────────────────

    /**
     * 调用 LLM（Agent C）从高分 Session 评语中提炼共同行为规律。
     *
     * Prompt 策略（v1.2）：
     *   - 给 LLM 提供高分 Session 的 agentComment 作为原始素材
     *   - 要求输出 2–4 条可操作的行为规则（每条 ≤ 50 字）
     *   - temperature=0.2（低随机性，保持规则稳定一致）
     *   - 每行一条，不加编号或其他前缀
     */
    private suspend fun runDistillLlm(
        goalTitle: String,
        sessions: List<SessionSummary>,
    ): List<String> {
        val systemPrompt = """
            你是能力提炼 Agent（Agent C）。
            你的任务是从多次对话的评审记录中，归纳出可复用的行为准则。
            
            要求：
            - 输出 2–4 条行为规则，每条不超过 ${MAX_RULE_CHARS} 字
            - 规则必须具体可操作（"做X以达到Y"），不要泛泛而谈
            - 每条规则一行，不加编号、序号或标点前缀
            - 只输出规则列表，不加任何说明或前言
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("学习目标：$goalTitle")
            appendLine()
            appendLine("以下是 ${sessions.size} 次高分对话的评审评语（综合得分均 ≥ $HIGH_SCORE_THRESHOLD）：")
            appendLine()
            sessions.forEachIndexed { i, s ->
                appendLine("第 ${i + 1} 次（综合分 ${"%.1f".format(s.composite)}）：${s.agentComment}")
            }
            appendLine()
            append("请从以上评语中归纳 2–4 条共同的行为规律，作为可复用的能力规则：")
        }

        return try {
            val response = provider.chatSyncWithRetry(
                messages     = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config       = LLMConfig(
                    model       = "",
                    maxTokens   = 400,
                    temperature = 0.2f,
                    stream      = false,
                ),
            )
            response.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length >= 5 }  // 过滤过短的无效行
                .take(MAX_RULES_PER_DISTILL)
                .map { if (it.length > MAX_RULE_CHARS) it.take(MAX_RULE_CHARS) else it }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── 规则写入/升级/锁定 ────────────────────────────────────

    /**
     * 将提炼出的规则逐条写入 DB，并判断是否满足锁定条件。
     *
     * 逻辑：
     * 1. 查询同一 (characterId, goalId, domain=RULE) 下的已有规则
     * 2. 对每条新规则：
     *    a. 与现有规则做相似度匹配（简单包含关键词检查）
     *    b. 匹配到 → 更新 importance（+1，上限 5），重新判断锁定条件
     *    c. 未匹配 → 新建 RULE 记忆（importance=3，isLocked=false）
     * 3. importance ≥ LOCK_OCCURRENCE_THRESHOLD 且 importance * 1.0 ≥ LOCK_CONFIDENCE_THRESHOLD
     *    → 调用 [MemoryDao.lockRule]（isLocked=true）
     *
     * importance 字段在此被复用为"出现次数"指标（1-5），importance=5 即出现 ≥5 次。
     */
    private suspend fun lockOrWriteRules(
        characterId: Int,
        goalId: String,
        rules: List<String>,
    ): LockResult {
        val now = System.currentTimeMillis()
        var newlyLocked = 0

        // seen 集合：记录本次循环已处理过的关键词，防止同次提炼内重复写入相似规则
        val seenKeywords = mutableSetOf<String>()

        // P2-2' 修复：existingRules 的加载移入事务内部，消除 TOCTOU 窗口。
        // 原先在事务外 load，事务内查重 + 写入，load 和 insert 之间有竞态：
        // 并发 Distill 调用可能同时读到"不存在"，同时写入重复 RULE 记忆。
        db.withTransaction {
        // 性能 M2 修复：改用 getRulesByGoal 在数据库层按 goalId 过滤，
        // 替代 getAllRules(characterId) 全量加载该角色所有 RULE 记忆后再内存 filter。
        val existingRules = memoryDao.getRulesByGoal(characterId, goalId)
        for (ruleContent in rules) {
            // U-5 修复：seenKeywords 和 existing 去重改精确 == 全文比较。
            // 原 contains 子串匹配会因短关键词命中长规则文本而误判为重复。

            // 先查 seenKeywords，跳过本轮已处理过的相同规则
            if (seenKeywords.contains(ruleContent)) continue
            seenKeywords.add(ruleContent)

            val existing = existingRules.find { existing ->
                existing.content == ruleContent
            }

            if (existing != null) {
                // 规则已存在 → 原子 importance +1（避免 read-modify-write 竞态）
                // 使用 DAO 的原子递增，返回更新后的实际值
                val newImportance = memoryDao.incrementImportance(existing.id, now)

                // 检查锁定条件
                val confidenceScore = newImportance.toFloat()
                if (!existing.isLocked
                    && newImportance >= LOCK_OCCURRENCE_THRESHOLD
                    && confidenceScore >= LOCK_CONFIDENCE_THRESHOLD
                ) {
                    memoryDao.lockRule(existing.id, now)
                    newlyLocked++
                }
            } else {
                // 全新规则 → 写入 DB（isLocked=false，importance=3 起步）
                // M3 修复：改用 memoryRepo.save()（主表 + FTS 原子写入），
                // 原 memoryDao.insert() 只写主表，FTS 虚拟表不同步，
                // 导致新规则永远无法被全文检索召回。
                val memoryId = UUID.randomUUID().toString()
                val newRuleEntity = MemoryEntity(
                    id             = memoryId,
                    characterId    = characterId,
                    domain         = MemoryDomain.RULE.name,
                    content        = ruleContent,
                    importance     = 3,         // 首次出现，importance=3（等同出现3次起步）
                    keywords       = ruleContent.take(20),
                    sourceEventId  = null,
                    isCore         = false,
                    isLocked       = false,
                    goalId         = goalId,
                    accessCount    = 0,
                    createdAt      = now,
                    updatedAt      = now,
                    lastAccessedAt = now,
                )
                // M3：优先走 MemoryRepository.save()（主表+FTS 原子写入），
                // 无 repo 时降级到 memoryDao.insert()（旧行为，FTS 不同步）
                if (memoryRepo != null) {
                    memoryRepo.save(newRuleEntity)
                } else {
                    memoryDao.insert(newRuleEntity)
                }
                // importance=3 已达到 LOCK_OCCURRENCE_THRESHOLD=3，但 confidence 需 ≥4.0
                // 新规则 importance=3 < 4.0，不立即锁定，需再出现一次升至 4 才锁定
            }
        }
        } // end db.withTransaction

        return LockResult(newlyLocked = newlyLocked)
    }

    // ── 数据类 ────────────────────────────────────────────────

    private data class SessionSummary(
        val agentComment: String,
        val agentScore: Float,
        val userScore: Int,
        val composite: Float,
    )

    private data class LockResult(val newlyLocked: Int)
}

// ─────────────────────────────────────────────────────────────
//  DistillResult — maybeDistill() 的返回值
// ─────────────────────────────────────────────────────────────

/**
 * 提炼操作的结果，由 [DistillationEngine.maybeDistill] 返回。
 *
 * @param triggered        是否真正执行了提炼（false = 条件未满足或冷却中）
 * @param goalTitle        目标标题（triggered=true 时有效）
 * @param newRules         本次提炼出的规则列表（triggered=true 时有效）
 * @param newlyLockedCount 本次新增的已锁定规则数量
 * @param progressDelta    本次目标进度提升量（如 0.1 = +10%）
 * @param reason           简短说明（供日志/调试用）
 */
data class DistillResult(
    val triggered: Boolean,
    val goalTitle: String = "",
    val newRules: List<String> = emptyList(),
    val newlyLockedCount: Int = 0,
    val progressDelta: Float = 0f,
    val reason: String = "",
)
