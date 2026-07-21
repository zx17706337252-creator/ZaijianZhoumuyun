package com.zaijian.zhoumuyun.data.memory

import androidx.room.withTransaction
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.EventType
import com.zaijian.zhoumuyun.data.db.entity.MemoryCandidateEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import com.zaijian.zhoumuyun.data.repository.EventRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Memory Engine（Phase 8）
 *
 * 管道：结构化事件 → MemoryCandidate → Memory
 *
 * 核心原则（§6）：
 * - Memory 不是聊天记录，禁止直接将聊天文本写入 memories 表
 * - 结构化触发源产生的候选经过 Candidate 层：先评分，再决定是否晋升
 *
 * 当前实现：
 * - 候选→晋升管道仅服务于结构化触发源 onTaskCompleted（任务确实完成），
 *   不再扫描自由对话内容、不做正则/关键词猜测式自动写入。
 * - 自由对话中的记忆由 Agent 主动调用工具决定：
 *   memory_write（稀疏锚点）、narrative_memory_update / user_impression_update /
 *   soul_update（叙事层整体改写）。详见 PromptOrchestrator 的"记忆使用准则"。
 * - writeEternalMemory（怀孕/生育状态机）、DistillationEngine（LLM 提炼）
 *   等其他确定性触发源各自独立，不经此管道。
 */
class MemoryEngine(
    private val db: AppDatabase,
    private val memoryRepo: MemoryRepository,
    private val eventRepo: EventRepository,
) {

    // ─────────────────────────────────────────────────────────
    //  结构化触发：任务完成 → 生成 WORK domain 候选 → 晋升
    // ─────────────────────────────────────────────────────────

    /**
     * Phase 19：工具任务完成时生成 WORK domain 的 MemoryCandidate。
     *
     * 触发条件：TaskRepository.completeTask() 调用后，由 TaskCompleteTool 调用。
     * 结果记忆的 domain = WORK，importance >= 3（进入长期记忆）。
     *
     * @param characterId  执行任务的角色 ID
     * @param taskTitle    任务标题
     * @param resultSummary 任务结果摘要（≤120字）
     * @param toolName     工具名称（可空）
     * @param sourceEventId TASK_COMPLETED 事件 ID
     */
    suspend fun onTaskCompleted(
        characterId: Int,
        taskTitle: String,
        resultSummary: String,
        toolName: String?,
        sourceEventId: String,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val toolLabel = toolName ?: "工具"
        val candidate = MemoryCandidateEntity(
            id            = UUID.randomUUID().toString(),
            characterId   = characterId,
            sourceEventId = sourceEventId,
            content       = "完成任务「$taskTitle」（$toolLabel）：$resultSummary",
            score         = 3,            // 进入长期记忆
            domain        = MemoryDomain.WORK.name,
            projectId     = null,
            createdAt     = now,
        )
        memoryRepo.insertCandidate(candidate)
        processCandidate(candidate)
    }

    // ─────────────────────────────────────────────────────────
    //  结构化触发：工具调用降级穷尽 → 生成 WORK domain 候选 → 晋升
    //  （Window B §2.1.3 / Window A 接口确认回执 §二）
    // ─────────────────────────────────────────────────────────

    /**
     * 工具调用降级穷尽后，记录这次失败尝试。
     *
     * 触发时机：
     * - ToolCallInterceptor 降级状态机重试耗尽（MAX_DEGRADE_ATTEMPTS）且 LLM 判定 giveup 后
     * - WorkflowEngine 路径在 Job 终结为 FAILED/TIMEOUT/STUCK 时（以整个 job 的 goal 为 goalContext）
     *
     * 写入路径：构造一条 MemoryCandidate（domain=WORK, score=2），
     * 走 [processCandidate] 晋升管道——与 [onTaskCompleted] 同一条管道，
     * 不经过工具层，不经过 LLM。score=2 意味着 importance=2，
     * 随时间衰减，符合"失败教训会过时"的特性，不是永不衰减的核心锚点。
     *
     * @param characterId       执行工具的角色 ID
     * @param toolName          失败的工具名称
     * @param goalContext       触发这次工具调用的上下文摘要（调用方负责截断，非全文）
     * @param failureReason     失败原因
     * @param attemptsExhausted 已耗尽的重试次数
     */
    suspend fun onToolFailureExhausted(
        characterId: Int,
        toolName: String,
        goalContext: String,
        failureReason: String,
        attemptsExhausted: Int,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val candidate = MemoryCandidateEntity(
            id            = UUID.randomUUID().toString(),
            characterId   = characterId,
            sourceEventId = UUID.randomUUID().toString(), // 失败路径无对应 WorldEvent，用 UUID 占位
            content       = "工具「$toolName」调用失败（已重试 $attemptsExhausted 次）：$failureReason。上下文：${goalContext.take(120)}",
            score         = 2,            // 失败教训，随时间衰减（importance=2 < 5，非 isCore）
            domain        = MemoryDomain.WORK.name,
            projectId     = null,
            createdAt     = now,
        )
        memoryRepo.insertCandidate(candidate)
        processCandidate(candidate)
    }

    // ─────────────────────────────────────────────────────────
    //  候选晋升：Candidate → Memory（或丢弃）
    // ─────────────────────────────────────────────────────────

    private suspend fun processCandidate(candidate: MemoryCandidateEntity) {
        // score=1：直接丢弃
        if (candidate.score <= 1) {
            memoryRepo.markCandidateProcessed(candidate.id, null)
            return
        }

        val now = System.currentTimeMillis()
        val memory = MemoryEntity(
            id             = UUID.randomUUID().toString(),
            characterId    = candidate.characterId,
            domain         = candidate.domain,
            content        = candidate.content,
            importance     = candidate.score,
            keywords       = extractKeywords(candidate.content),
            sourceEventId  = candidate.sourceEventId,
            isCore         = candidate.score >= 5,
            projectId      = candidate.projectId,
            accessCount    = 0,
            createdAt      = now,
            updatedAt      = now,
            lastAccessedAt = now,
            // W3-6 修复：候选晋升为正式记忆时，此前遗漏了 scope 和 roundtableId
            // 两个字段的传递，导致晋升后的记忆一律落回 MemoryEntity 的默认值
            // （scope=PERSONAL, roundtableId=null）。当前所有候选创建路径
            // （onTaskCompleted）的 scope 都是默认 PERSONAL，所以这个问题此刻
            // 不产生实际影响；但一旦未来有候选创建路径产生 GROUP scope 的候选，
            // 若不传播这两个字段，晋升后群记忆的归属信息会丢失。这里补齐传递，
            // 消除隐患。
            scope          = candidate.scope,
            roundtableId   = candidate.roundtableId,
        )

        // M8 修复：saveOrMerge → markCandidateProcessed → appendMemoryEvent
        // 三步包在同一事务内。原先三步各自独立提交，若中间任一步失败
        // （如进程被杀、appendMemoryEvent 抛异常），会留下"记忆已写入但候选未标记"
        // 或"候选已标记但事件缺失"的不一致状态——前者导致下次轮询重复处理同一候选，
        // 后者破坏 Event Engine"所有写操作必须产生 Event"的不变量。
        db.withTransaction {
            // saveOrMerge：有相似记忆则 Merge，否则写入新记录
            val resultId = memoryRepo.saveOrMerge(memory)

            // 标记候选已处理
            memoryRepo.markCandidateProcessed(candidate.id, resultId)

            // 写 MEMORY_CREATED 事件（Event Engine 原则：所有写操作必须产生 Event）
            eventRepo.appendMemoryEvent(
                characterId = candidate.characterId,
                memoryId    = resultId,
                isUpdate    = resultId != memory.id,   // Merge 时 resultId 是已有 Memory 的 ID
                content     = candidate.content.take(80),
            )
        }
    }

    // ─────────────────────────────────────────────────────────
    //  关键词采样（供 FTS4 检索用）
    // ─────────────────────────────────────────────────────────

    /**
     * 从记忆内容提取关键词（供 FTS4 检索用）。
     *
     * 策略：从内容中均匀采样——每隔 content.length/10 个字符取一个 4 字符子串，最多取 10 个。
     *
     * 原实现从 i=0 起逐字符生成所有 2~6 字子串，存在两个问题：
     * 1. O(n²) 复杂度，长文本（如圆桌/任务摘要）下耗时明显；
     * 2. take(10) 后关键词全部集中在文本开头，覆盖不到中后段的事实。
     * 改为按等距步长采样后，关键词均匀覆盖全文，且复杂度降为 O(1) 级别（最多 10 次取子串）。
     */
    private fun extractKeywords(content: String): String {
        if (content.length < 4) return content.trim()
        // 步长 = 内容长度 / 10，保证整段内容被等分为约 10 段，每段取一个采样点
        val step = (content.length / 10).coerceAtLeast(1)
        val keywords = mutableListOf<String>()
        var i = 0
        while (i <= content.length - 4 && keywords.size < 10) {
            keywords.add(content.substring(i, i + 4))
            i += step
        }
        return keywords.joinToString(" ")
    }
}

// ─────────────────────────────────────────────────────────────
//  EventRepository 扩展：写 Memory 相关事件
// ─────────────────────────────────────────────────────────────

suspend fun EventRepository.appendMemoryEvent(
    characterId: Int,
    memoryId: String,
    isUpdate: Boolean,
    content: String,
) {
    val type = if (isUpdate)
        com.zaijian.zhoumuyun.data.db.entity.EventType.MEMORY_UPDATED
    else
        com.zaijian.zhoumuyun.data.db.entity.EventType.MEMORY_CREATED

    append(
        com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity(
            id         = UUID.randomUUID().toString(),
            type       = type.name,
            actorId    = characterId.toString(),
            targetId   = null,
            domain     = com.zaijian.zhoumuyun.data.db.entity.EventDomain.PERSONAL.name,
            projectId  = null,
            payload    = org.json.JSONObject().apply {
                put("memoryId", memoryId)
                put("preview", content)
            }.toString(),
            importance = 2,
            createdAt  = System.currentTimeMillis(),
        )
    )
}
