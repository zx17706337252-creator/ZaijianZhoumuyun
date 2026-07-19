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
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Memory Engine（Phase 8）
 *
 * 管道：Message → Event → MemoryCandidate → Memory
 *
 * 核心原则（§6）：
 * - Memory 不是聊天记录，禁止直接将聊天文本写入 memories 表
 * - 所有记忆必须经过 Candidate 层：先评分，再决定是否晋升
 * - 触发条件（§6.7）：MESSAGE / PROJECT_UPDATED / PROJECT_MILESTONE /
 *   TASK_COMPLETED / RELATIONSHIP_CHANGED / WORLD_SIMULATION
 *
 * 当前阶段（Phase 8）实现：
 * - 规则引擎提取关键事实（不依赖 LLM）
 * - 对话结束后批量处理候选（不阻塞 UI）
 * - Phase 9 可接入 LLM 做智能摘要提炼
 *
 * 四层演化方向（§6.2）：
 * Fact → Pattern → Preference → Trait
 * 当前 Phase 8 实现 Fact 层，后续版本升级。
 */
class MemoryEngine(
    private val db: AppDatabase,
    private val memoryRepo: MemoryRepository,
    private val eventRepo: EventRepository,
) {

    // ─────────────────────────────────────────────────────────
    //  1. 对话结束后触发：从最近 EVENT 提取候选
    // ─────────────────────────────────────────────────────────

    /**
     * 对话结束时调用（由 ChatViewModel 在 AI 回复完成后触发）。
     *
     * 流程：
     * 1. 取最近的 MESSAGE 事件（本轮对话的用户消息和角色回复）
     * 2. 规则引擎提取候选记忆
     * 3. 写入 memory_candidates 表
     * 4. 立即处理候选（不等待）
     *
     * @param characterId 当前对话的角色 ID
     * @param userMessage 用户的原始消息内容
     * @param assistantReply 角色的完整回复内容
     * @param userEventId 用户消息对应的 WorldEvent ID
     */
    suspend fun onConversationTurn(
        characterId: Int,
        userMessage: String,
        assistantReply: String,
        userEventId: String,
    ) = withContext(Dispatchers.IO) {
        // 提取候选
        val candidates = extractCandidates(
            characterId    = characterId,
            userMessage    = userMessage,
            assistantReply = assistantReply,
            sourceEventId  = userEventId,
        )

        // 写入候选表
        candidates.forEach { candidate ->
            try { memoryRepo.insertCandidate(candidate) } catch (e: Exception) {
                ZLog.w("MemoryEngine", "insertCandidate 失败 candidateId=${candidate.id}", e)
            }
        }

        // P1-6 修复：只处理本轮对话新增的候选（candidates），
        // 不处理所有 pending 候选，避免本对话轮次回退处理旧候选导致重复晋升。
        candidates.forEach { candidate ->
            try { processCandidate(candidate) } catch (e: Exception) {
                ZLog.w("MemoryEngine", "processCandidate 失败 candidateId=${candidate.id}", e)
            }
        }
    }

    /**
     * 待办3：圆桌每轮发言结束后，提取角色承诺/重要陈述写入群记忆。
     *
     * 插入位置：在 onConversationTurn 之后、onTaskCompleted 之前。
     * 写入策略：直接写入（不走 saveOrMerge 合并），与 writeEternalMemory 风格一致。
     *
     * @param roundtableId  圆桌 ID
     * @param speakerId     本轮发言角色 ID（来源追溯）
     * @param userMessage   用户原始消息
     * @param assistantReply 角色完整回复
     */
    suspend fun onRoundtableTurn(
        roundtableId: String,
        speakerId: Int,
        userMessage: String,
        assistantReply: String,
    ) = withContext(Dispatchers.IO) {
        val worldFact = extractWorldFact(assistantReply)
        if (worldFact == null) {
            // S2问题2修复：提取失败不再静默丢弃，记录 INFO 日志便于事后追溯
            // "哪些发言被提取了、哪些被跳过了"。extractWorldFact 目前仅匹配
            // "我会/我保证"等承诺模式，圆桌场景中大量非承诺类发言（观点表达、
            // 角色互动等）会被跳过，这是已知的规则引擎覆盖范围限制。
            com.zaijian.zhoumuyun.util.ZLog.i(
                "MemoryEngine",
                "onRoundtableTurn 未提取到群记忆：roundtableId=$roundtableId, " +
                    "speakerId=$speakerId, reply=\"${assistantReply.take(50)}\"",
            )
            return@withContext
        }
        val keywords = extractKeywords(worldFact.content)
        memoryRepo.writeGroupMemory(
            roundtableId = roundtableId,
            speakerId    = speakerId,
            content      = worldFact.content,
            keywords     = keywords,
            importance   = worldFact.score,
        )
    }

    /**
     * Phase 19：工具任务完成时生成 WORK domain 的 MemoryCandidate。
     *
     * 触发条件：TaskRepository.completeTask() 调用后，由 ChatViewModel 调用。
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

    /**
     * 处理所有未处理的候选（可独立调用，如 App 启动时补处理）。
     */
    suspend fun processPendingCandidates(characterId: Int) = withContext(Dispatchers.IO) {
        val pending = memoryRepo.getPendingCandidates(characterId)
        pending.forEach { candidate -> processCandidate(candidate) }
    }

    // ─────────────────────────────────────────────────────────
    //  2. 规则引擎：从对话中提取候选
    // ─────────────────────────────────────────────────────────

    /**
     * 规则引擎提取候选。
     *
     * Phase 8 使用规则匹配（不依赖 LLM）：
     * - 用户消息：提取用户关于自己的陈述（偏好、信息、情感表达）
     * - 角色回复：提取角色的重要行为和承诺
     *
     * Phase 9/10 可替换为 LLM 摘要：
     * 将 (userMessage, assistantReply) 发给模型，
     * 要求提取 3-5 条关键事实，指定 domain 和 score。
     */
    private fun extractCandidates(
        characterId: Int,
        userMessage: String,
        assistantReply: String,
        sourceEventId: String,
    ): List<MemoryCandidateEntity> {
        val now = System.currentTimeMillis()
        val results = mutableListOf<MemoryCandidateEntity>()

        // ── 规则 1：用户自我描述（偏好/习惯/感受）────────────
        val userFact = extractUserFact(userMessage)
        if (userFact != null) {
            results.add(
                MemoryCandidateEntity(
                    id            = UUID.randomUUID().toString(),
                    characterId   = characterId,
                    sourceEventId = sourceEventId,
                    content       = userFact.content,
                    score         = userFact.score,
                    domain        = MemoryDomain.PERSONAL.name,
                    projectId     = null,
                    createdAt     = now,
                )
            )
        }

        // ── 规则 2：情感倾向（高情感强度的对话）─────────────
        val emotionFact = extractEmotionFact(userMessage, assistantReply)
        if (emotionFact != null) {
            results.add(
                MemoryCandidateEntity(
                    id            = UUID.randomUUID().toString(),
                    characterId   = characterId,
                    sourceEventId = sourceEventId,
                    content       = emotionFact.content,
                    score         = emotionFact.score,
                    domain        = MemoryDomain.PERSONAL.name,
                    projectId     = null,
                    createdAt     = now,
                )
            )
        }

        // ── 规则 3：角色承诺/约定（会产生 World Memory）─────
        val worldFact = extractWorldFact(assistantReply)
        if (worldFact != null) {
            results.add(
                MemoryCandidateEntity(
                    id            = UUID.randomUUID().toString(),
                    characterId   = characterId,
                    sourceEventId = sourceEventId,
                    content       = worldFact.content,
                    score         = worldFact.score,
                    domain        = MemoryDomain.WORLD.name,
                    projectId     = null,
                    createdAt     = now,
                )
            )
        }

        return results
    }

    // ─────────────────────────────────────────────────────────
    //  3. 候选晋升：Candidate → Memory（或丢弃）
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
            // （extractCandidates/onTaskCompleted）的 scope 都是默认 PERSONAL，
            // 所以这个问题此刻不产生实际影响；但一旦未来有候选创建路径产生
            // GROUP scope 的候选，若不传播这两个字段，晋升后群记忆的归属信息
            // 会丢失。这里补齐传递，消除隐患。
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
    //  4. 规则引擎子方法
    // ─────────────────────────────────────────────────────────

    private data class ExtractedFact(val content: String, val score: Int)

    /**
     * 提取用户关于自己的陈述。
     * 匹配模式：第一人称 + 偏好/状态/信息词
     */
    private fun extractUserFact(message: String): ExtractedFact? {
        val text = message.trim()
        if (text.length < 5) return null

        // 高分模式：明确的个人信息
        val highPatterns = listOf(
            "我叫", "我是", "我的名字", "我的爱好", "我喜欢", "我不喜欢",
            "我最喜欢", "我讨厌", "我害怕", "我希望", "我梦想",
            "我住在", "我在", "我的工作", "我的职业",
            "我决定", "我打算", "我计划", "我准备", "我选择", "我放弃",
            "我小时候", "我以前", "我将来", "我出生",
            "我不会", "我不能", "我不愿意", "我做不到", "我从不",
            "我安排",
        )
        for (pattern in highPatterns) {
            // S2问题3修复：pattern 命中后需确认未被否定词（"不"/"没"等）修饰，
            // 否则"我不喜欢"会被"我喜欢"模式误匹配为正向偏好。
            if (com.zaijian.zhoumuyun.util.NegationUtils.containsUnnegated(text, pattern)) {
                val extracted = extractSentenceContaining(text, pattern)
                if (extracted != null) return ExtractedFact(extracted, 4)
            }
        }

        // 中分模式：情感/状态表达
        val midPatterns = listOf(
            "我觉得", "我感觉", "我认为", "我想", "我需要",
            "我最近", "我今天", "我一直", "我经常", "我总是",
            "我很", "我有点", "我比较", "我有些",
        )
        for (pattern in midPatterns) {
            if (com.zaijian.zhoumuyun.util.NegationUtils.containsUnnegated(text, pattern)) {
                val extracted = extractSentenceContaining(text, pattern)
                if (extracted != null) return ExtractedFact(extracted, 3)
            }
        }

        return null
    }

    /**
     * 提取对话中的情感事件（较高情感强度的互动）。
     */
    private fun extractEmotionFact(userMessage: String, assistantReply: String): ExtractedFact? {
        val combined = "$userMessage $assistantReply"

        // 高情感强度关键词
        val highEmotionKeywords = listOf(
            "谢谢你", "感谢你", "很感动", "好感动", "我爱", "我好喜欢你",
            "你真的很", "你让我", "太重要了", "忘不了",
            "我开心", "我兴奋", "我难过", "我生气", "我担心", "我失望",
            "我害怕", "我紧张", "我自豪", "很伤心", "太感动", "心疼",
        )
        for (kw in highEmotionKeywords) {
            if (com.zaijian.zhoumuyun.util.NegationUtils.containsUnnegated(combined, kw)) {
                // S2问题8修复：40字容易截断到情感关键词之前（用户常先铺垫背景
                // 再表达情感，如"谢谢你"出现在句尾），改为取前80字降低丢失概率。
                val content = "用户与角色之间发生了情感较深的互动：「${userMessage.take(80)}」"
                return ExtractedFact(content, 3)
            }
        }

        return null
    }

    /**
     * 提取角色做出的承诺、约定或重要陈述（World Memory）。
     */
    private fun extractWorldFact(assistantReply: String): ExtractedFact? {
        val text = assistantReply.trim()
        if (text.length < 10) return null

        val promisePatterns = listOf(
            "我会", "我会帮你", "我会记住", "下次", "我答应",
            "我保证", "我一定", "我来", "让我来",
        )
        for (pattern in promisePatterns) {
            if (com.zaijian.zhoumuyun.util.NegationUtils.containsUnnegated(text, pattern)) {
                val extracted = extractSentenceContaining(text, pattern)
                if (extracted != null) return ExtractedFact(extracted, 3)
            }
        }

        return null
    }

    /**
     * 提取包含特定关键词的完整句子。
     * 用标点符号做句子边界分割。
     */
    private fun extractSentenceContaining(text: String, keyword: String): String? {
        // S2问题5修复：中文逗号、分号是句中停顿而非句末，仅以句号、问号、感叹号、换行作为句子边界
        val sentences = text.split(Regex("[。！？.!?\\n]"))
        val sentence = sentences.firstOrNull { it.contains(keyword) }?.trim()
        return if (sentence != null && sentence.length >= 4) sentence else null
    }

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
