package com.zaijian.zhoumuyun.domain

import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.dao.RelationshipDao
import com.zaijian.zhoumuyun.data.db.dao.RelationshipMilestoneDao
import androidx.room.withTransaction
import com.zaijian.zhoumuyun.data.db.entity.EventType
import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneDirection
import com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity
import com.zaijian.zhoumuyun.data.db.entity.RelationshipStage
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import com.zaijian.zhoumuyun.data.repository.EventRepository
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

// ─────────────────────────────────────────────────────────────
//  关系摩擦系数（情感惰性）Phase 2（zaijian）
//  stage 映射：STRANGER(0) FAMILIAR(1) TRUSTED(2) IMPORTANT(3) CORE(4)
// ─────────────────────────────────────────────────────────────

private fun posMultiplier(stage: RelationshipStage) = when (stage) {
    RelationshipStage.STRANGER   -> 1.00f
    RelationshipStage.FAMILIAR   -> 0.70f
    RelationshipStage.TRUSTED    -> 0.45f
    RelationshipStage.IMPORTANT  -> 0.25f
    RelationshipStage.CORE       -> 0.12f
}

private fun negMultiplier(stage: RelationshipStage) = when (stage) {
    RelationshipStage.STRANGER   -> 1.00f
    RelationshipStage.FAMILIAR   -> 0.80f
    RelationshipStage.TRUSTED    -> 0.60f
    RelationshipStage.IMPORTANT  -> 0.40f
    RelationshipStage.CORE       -> 0.25f
}

/** affection 地板：阶段越深，感情值有下限保护 */
private fun affectionFloor(stage: RelationshipStage): Int = when (stage) {
    RelationshipStage.STRANGER   -> 0
    RelationshipStage.FAMILIAR   -> 15
    RelationshipStage.TRUSTED    -> 30
    RelationshipStage.IMPORTANT  -> 45
    RelationshipStage.CORE       -> 60
}

class RelationshipEngine(
    private val db: AppDatabase,
    private val relationshipDao: RelationshipDao,
    private val eventRepo: EventRepository,
    private val milestoneDao: RelationshipMilestoneDao? = null,
) {

    companion object {
        /**
         * P1-13-7 修复：角色间关系（inter-character）在数据库里按字典序
         * min(fromId,toId) → max(fromId,toId) 归一化存储为一条记录
         * （见下方 getOrCreateInterCharacter，M7 修复）。
         *
         * 任何"按角色对查询关系"的调用方（目前是 TurnScheduler.computeScores）
         * 都必须用同一条归一化规则拼 key，否则 idA/idB 顺序与库内存储顺序
         * 不一致时，Map 里查不到对应记录，rel 恒为 null，相关加分逻辑悄悄失效
         * （13-7 复核命中的正是这个问题：TurnScheduler 原先直接拼
         * "${other.id}_${bot.id}"，未归一化）。
         *
         * 统一抽到这里作为唯一归一化入口，避免两处各写一份、日后改归一化
         * 规则时漏改一处又重新出现同样的 bug。
         *
         * 注意：归一化按字符串字典序比较（与 getOrCreateInterCharacter 保持
         * 一致），不是数值大小比较——"10" 字典序小于 "9"。这里特意保持与
         * 现有库内数据相同的（字典序）比较方式，不改成数值比较，否则会与
         * 已经写入的历史数据顺序不一致，导致旧数据全部查不到。
         */
        fun relKey(idA: Int, idB: Int): String {
            val a = idA.toString()
            val b = idB.toString()
            return if (a <= b) "${a}_${b}" else "${b}_${a}"
        }

        /** 单次 delta 的关系转折点判定阈值（trust/affection 绝对值达到此值才记录） */
        const val MILESTONE_DELTA_THRESHOLD = 15
    }

    /**
     * 按 "fromId_toId" key 细粒度串行化 applyDelta，防止并发 read-modify-write 覆盖。
     * 使用单一 Mutex 全局串行（关系更新频率低，竞争罕见，单锁足够）。
     */
    private val deltaMutex = Mutex()

    // ─────────────────────────────────────────────────────────
    //  用户↔角色 关系
    // ─────────────────────────────────────────────────────────

    suspend fun getOrCreate(fromId: String, toId: String, interCharacter: Boolean = false): RelationshipEntity {
        return relationshipDao.get(fromId, toId) ?: run {
            val initial = RelationshipEntity(
                id               = UUID.randomUUID().toString(),
                fromId           = fromId,
                toId             = toId,
                isInterCharacter = interCharacter,
            )
            relationshipDao.upsert(initial)
            initial
        }
    }

    suspend fun applyDelta(fromId: String, toId: String, delta: RelationshipDelta, sourceEventId: String? = null) =
        deltaMutex.withLock {
        val current = getOrCreate(fromId, toId)
        val currentStage = RelationshipStage.valueOf(current.stage)

        // ── ① 摩擦系数 ──────────────────────────────────────────
        val pos = posMultiplier(currentStage)
        val neg = negMultiplier(currentStage)
        fun scale(v: Int) = if (v >= 0) (v * pos).toInt() else (v * neg).toInt()

        // ── ② 带摩擦的 delta ────────────────────────────────────
        val newTrust      = (current.trust      + scale(delta.trust)     ).coerceIn(0, 100)
        val newRespect    = (current.respect    + scale(delta.respect)   ).coerceIn(0, 100)
        val newAffection0 = (current.affection  + scale(delta.affection) ).coerceIn(0, 100)
        val newCuriosity  = (current.curiosity  + scale(delta.curiosity) ).coerceIn(0, 100)
        val newDependence = (current.dependence + scale(delta.dependence)).coerceIn(0, 100)
        val newConflict   = (current.conflict   + scale(delta.conflict)  ).coerceIn(0, 100)

        // ── ③ 棘轮：stage 只增不减 ──────────────────────────────
        val rawStage = calcStage(newTrust, newAffection0)
        val newStage = if (rawStage.ordinal >= currentStage.ordinal) rawStage else currentStage

        // ── ④ affection 地板 ────────────────────────────────────
        val newAffection = newAffection0.coerceAtLeast(affectionFloor(newStage))

        // ── ⑤ suppression（P1-6-3 修复）────────────────────────
        // 原先：applyDelta 写完六维后返回，ChatViewModel 再单独 get → compute → updateSuppression，
        // 两步之间无锁保护，有竞态窗口（另一个协程可能在此期间写入关系）。
        // 修复：suppressionDelta 并入 RelationshipDelta，在 deltaMutex 内与六维合并为一次 SQL。
        // suppressionDelta == 0 时不影响原值（向后兼容：未传该字段的调用方行为不变）。
        val newSuppression = if (delta.suppressionDelta != 0) {
            (current.suppression + delta.suppressionDelta).coerceIn(0, 100)
        } else {
            current.suppression
        }

        val now = System.currentTimeMillis()
        // U-4 修复：updateAllWithSuppression → eventRepo.append → maybeRecordMilestoneFromDelta
        // 三步原先无事务边界，updateAllWithSuppression 成功后若 append 崩溃，关系数据已变更
        // 但事件永久丢失，导致世界事件流与关系数据不一致。
        // 用 db.withTransaction 将三步包裹为原子操作（deltaMutex 仍保留，保护读-改-写顺序）。
        db.withTransaction {
        relationshipDao.updateAllWithSuppression(
            fromId, toId,
            newTrust, newRespect, newAffection, newCuriosity, newDependence, newConflict,
            newSuppression,
            newStage.name, sourceEventId, now,
        )

        eventRepo.append(WorldEventEntity(
            id         = UUID.randomUUID().toString(),
            type       = EventType.RELATIONSHIP_CHANGED.name,
            actorId    = fromId,
            targetId   = toId,
            domain     = "PERSONAL",
            projectId  = null,
            payload    = """{"trust":$newTrust,"affection":$newAffection,"conflict":$newConflict,"curiosity":$newCuriosity,"dependence":$newDependence,"stage":"${newStage.name}"}""",
            importance = 3,
            createdAt  = now,
        ))

        maybeRecordMilestoneFromDelta(fromId, toId, delta, sourceEventId, now)
        } // end db.withTransaction
    }

    private suspend fun maybeRecordMilestoneFromDelta(
        fromId: String, toId: String, delta: RelationshipDelta, sourceEventId: String?, now: Long,
    ) {
        val dao = milestoneDao ?: return
        val worsened = delta.affection <= -MILESTONE_DELTA_THRESHOLD || delta.trust <= -MILESTONE_DELTA_THRESHOLD
        val repaired = delta.affection >= MILESTONE_DELTA_THRESHOLD || delta.trust >= MILESTONE_DELTA_THRESHOLD
        if (!worsened && !repaired) return

        // 修复：同一 delta 可能同时触发 worsened 和 repaired（例如 affection 大幅下降
        // 但 trust 大幅上升），分别独立记录，不互斥。原逻辑 if(worsened) else REPAIRED
        // 导致双向变化时 repaired 方向被静默丢弃。
        if (worsened) {
            try {
                dao.insert(
                    RelationshipMilestoneEntity(
                        id            = UUID.randomUUID().toString(),
                        fromId        = fromId,
                        toId          = toId,
                        direction     = RelationshipMilestoneDirection.WORSENED.name,
                        description   = "关系出现明显裂痕",
                        sourceEventId = sourceEventId,
                        createdAt     = now,
                    )
                )
            } catch (e: Exception) {
                ZLog.w("RelationshipEngine", "记录关系转折点(WORSENED)失败（fromId=$fromId, toId=$toId）", e)
            }
        }
        if (repaired) {
            try {
                dao.insert(
                    RelationshipMilestoneEntity(
                        id            = UUID.randomUUID().toString(),
                        fromId        = fromId,
                        toId          = toId,
                        direction     = RelationshipMilestoneDirection.REPAIRED.name,
                        description   = "关系明显缓和",
                        sourceEventId = sourceEventId,
                        createdAt     = now,
                    )
                )
            } catch (e: Exception) {
                ZLog.w("RelationshipEngine", "记录关系转折点(REPAIRED)失败（fromId=$fromId, toId=$toId）", e)
            }
        }
    }

    suspend fun recordMilestone(
        fromId: String,
        toId: String,
        direction: RelationshipMilestoneDirection,
        description: String,
        sourceEventId: String? = null,
    ) {
        val dao = milestoneDao ?: return
        dao.insert(
            RelationshipMilestoneEntity(
                id            = UUID.randomUUID().toString(),
                fromId        = fromId,
                toId          = toId,
                direction     = direction.name,
                description   = description,
                sourceEventId = sourceEventId,
                createdAt     = System.currentTimeMillis(),
            )
        )
    }

    // S8-窗口10 结论3清理：onConversationEnd() 原为对话结束时的关系数值更新入口，
    // 全项目 grep 确认零调用点——逻辑已被 ChatMessageOrchestrator.kt（第471行附近，
    // 注释"原 onConversationEnd 逻辑内联"）内联替代，该方法成为纯死代码，
    // 予以删除，避免继续增加维护困惑。

    suspend fun buildPromptSnapshot(characterId: Int): String {
        val rel = relationshipDao.get("user", characterId.toString()) ?: return ""
        val stage = RelationshipStage.valueOf(rel.stage)
        val stageLabel = when (stage) {
            RelationshipStage.STRANGER  -> "陌生"
            RelationshipStage.FAMILIAR  -> "熟悉"
            RelationshipStage.TRUSTED   -> "信任"
            RelationshipStage.IMPORTANT -> "重要"
            RelationshipStage.CORE      -> "核心"
        }

        val suppressionHint = when {
            rel.suppression <= 30 -> "（内心防线较高，不轻易袒露）"
            rel.suppression >= 75 -> "（心防松动，真实情感容易透出来）"
            else -> ""
        }
        val conflictHint   = if (rel.conflict   >= 60) "，当前存在分歧，可提出不同意见" else ""
        val dependenceHint = if (rel.dependence >= 70) "，依赖程度较高，主动关注频率高" else ""

        val milestoneHint = milestoneDao?.let { dao ->
            try {
                val recent = dao.getRecent("user", characterId.toString(), limit = 2)
                if (recent.isEmpty()) null
                else recent.joinToString("；") { it.description }
            } catch (e: Exception) {
                ZLog.w("RelationshipEngine", "读取关系转折点失败（characterId=$characterId）", e)
                null
            }
        }

        return buildString {
            appendLine("与用户的关系阶段：$stageLabel$suppressionHint")
            appendLine("Trust ${rel.trust}  Affection ${rel.affection}  Conflict ${rel.conflict}  Dependence ${rel.dependence}  Curiosity ${rel.curiosity}")
            val curiosityHint = if (rel.curiosity >= 70) "，对你充满好奇，主动发问" else ""
            if (conflictHint.isNotEmpty() || dependenceHint.isNotEmpty() || curiosityHint.isNotEmpty()) {
                appendLine("行为提示：${conflictHint}${dependenceHint}${curiosityHint}".trimStart('，'))
            }
            if (!milestoneHint.isNullOrEmpty()) {
                appendLine("关系历史：$milestoneHint")
            }
        }.trimEnd()
    }

    // ─────────────────────────────────────────────────────────
    //  Phase 14：角色↔角色 关系（圆桌调度专用）
    // ─────────────────────────────────────────────────────────

    /**
     * 获取或初始化两个角色之间的关系（双向）。
     * M7 修复：强制归一化为 minId → maxId 存储（字典序较小者为 from），
     * 保证同一对角色始终只有一条记录，不会因调用方传参顺序不同而写出两条。
     */
    suspend fun getOrCreateInterCharacter(fromId: String, toId: String): RelationshipEntity {
        // 注意：保持原始的字符串字典序比较，不转 Int，与库内历史数据的
        // 归一化规则严格一致（relKey 同样按字典序，但此处直接用字符串
        // 入参，避免无意义的 String→Int→String 往返）。
        val (normFrom, normTo) = if (fromId <= toId) fromId to toId else toId to fromId
        return relationshipDao.get(normFrom, normTo)
            ?: getOrCreate(normFrom, normTo, interCharacter = true)
    }

    /**
     * 批量获取圆桌成员之间的所有关系，返回 key 已归一化（见 [relKey]）的 Map。
     * 供 TurnScheduler 使用——调用方必须用 relKey(idA, idB) 同样的归一化方式
     * 查询，不能再自行拼 "${fromId}_${toId}"，否则查不到（P1-13-7）。
     */
    suspend fun getInterCharacterMatrix(characterIds: List<Int>): Map<String, RelationshipEntity> {
        val all = relationshipDao.getAllInterCharacter()
        val idSet = characterIds.map { it.toString() }.toSet()
        return all
            .filter { it.fromId in idSet && it.toId in idSet }
            .associateBy { relKey(it.fromId.toInt(), it.toId.toInt()) }
    }

    /**
     * 圆桌发言后更新角色间动态（嫉妒度 + 紧张度）。
     *
     * 触发规则：
     * - 某 Bot 发言 → 其他 Bot 的 jealousy 对该 Bot 微升（+3）
     * - 某 Bot 的观点与另一 Bot 存在高 conflict → tension +2
     * - 每轮结束后所有角色间关系 jealousy/tension 自然衰减（在 WorldSimulation 中触发）
     */
    suspend fun onRoundtableRoundEnd(
        speakerIds: List<Int>,
        allMemberIds: List<Int>,
        conflictPairs: List<Pair<Int, Int>> = emptyList(),
    ) = deltaMutex.withLock {
        val now = System.currentTimeMillis()
        // ① 对所有非发言者，嫉妒度微升 +3
        allMemberIds.forEach { watcherId ->
            speakerIds.forEach { speakerId ->
                if (watcherId != speakerId) {
                    val rel = getOrCreateInterCharacter(watcherId.toString(), speakerId.toString())
                    val newJealousy = (rel.jealousy + 3).coerceAtMost(100)
                    // P2-1 修复：getOrCreateInterCharacter 内部已归一化 fromId/toId（字典序），
                    // 但 updateInterCharacterDynamics 的 SQL WHERE 条件要求 fromId/toId
                    // 与库里存储的顺序一致。用 rel.fromId/rel.toId（已归一化）替代
                    // watcherId.toString()/speakerId.toString() 原值，避免 UPDATE 0 行。
                    relationshipDao.updateInterCharacterDynamics(
                        fromId    = rel.fromId,
                        toId      = rel.toId,
                        jealousy  = newJealousy,
                        tension   = rel.tension,
                        updatedAt = now,
                    )
                }
            }
        }

        // ② 冲突对双向写 tension +2
        conflictPairs.forEach { (idA, idB) ->
            listOf(idA to idB, idB to idA).forEach { (from, to) ->
                val rel = getOrCreateInterCharacter(from.toString(), to.toString())
                val newTension = (rel.tension + 2).coerceAtMost(100)
                relationshipDao.updateInterCharacterDynamics(
                    fromId    = rel.fromId,
                    toId      = rel.toId,
                    jealousy  = rel.jealousy,
                    tension   = newTension,
                    updatedAt = now,
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Phase 3（zaijian）：圆桌人格张力 — 角色间关系快照
    // ─────────────────────────────────────────────────────────

    /**
     * 构建角色 [forCharacterId] 视角的角色间关系快照，
     * 注入到该角色的 System Prompt，让它感知与圆桌其他成员的关系史。
     *
     * @param forCharacterId  当前被构建 prompt 的角色 ID
     * @param memberIds       圆桌其他成员的 ID 列表（不含 forCharacterId）
     * @param nameMap         角色 ID → 角色名称的映射
     */
    suspend fun buildInterCharacterSnapshot(
        forCharacterId: Int,
        memberIds: List<Int>,
        nameMap: Map<Int, String>,
    ): String {
        if (memberIds.isEmpty()) return ""

        val lines = memberIds.mapNotNull { otherId ->
            // P2-3 修复：getBetween 查询用的是 fromId = :idA AND toId = :idB 精确匹配，
            // 但 getOrCreateInterCharacter 内部按字典序归一化存储（min(from,to) → max(from,to)），
            // 导致 getBetween 用未归一化 ID 查询时可能返回空列表。
            // 改用 getOrCreateInterCharacter（内部已归一化），
            // 再通过 rel.fromId == forCharacterId.toString() 判断方向。
            val rel = getOrCreateInterCharacter(forCharacterId.toString(), otherId.toString())
            val otherName = nameMap[otherId] ?: return@mapNotNull null
            val stage = RelationshipStage.valueOf(rel.stage)
            val stageLabel = when (stage) {
                RelationshipStage.STRANGER  -> "尚不熟悉"
                RelationshipStage.FAMILIAR  -> "有所了解"
                RelationshipStage.TRUSTED   -> "彼此信任"
                RelationshipStage.IMPORTANT -> "重要的存在"
                RelationshipStage.CORE      -> "极为重要"
            }
            val tensionNote  = if (rel.tension  >= 60) "，当前存在明显张力" else ""
            val jealousyNote = if (rel.jealousy >= 50) "，内心有些在意对方的注意力" else ""
            val milestoneNote = milestoneDao?.let { dao ->
                try {
                    val recent = dao.getRecent(forCharacterId.toString(), otherId.toString(), limit = 1)
                    recent.firstOrNull()?.description?.let { "，$it" } ?: ""
                } catch (e: Exception) {
                    ZLog.w("RelationshipEngine", "读取角色间转折点失败（from=$forCharacterId, to=$otherId）", e)
                    ""
                }
            } ?: ""
            "与${otherName}（${stageLabel}）：信任${rel.trust}  亲密${rel.affection}  冲突${rel.conflict}  依赖${rel.dependence}  好奇${rel.curiosity}${tensionNote}${jealousyNote}${milestoneNote}"
        }

        if (lines.isEmpty()) return ""
        return "【与在场其他人的关系（仅你可见，影响你对他们的反应方式）】\n" + lines.joinToString("\n")
    }

    // ─────────────────────────────────────────────────────────
    //  私有工具
    // ─────────────────────────────────────────────────────────

    private fun calcStage(trust: Int, affection: Int): RelationshipStage {
        // 用浮点除法避免整数除法截断：原 (trust + affection) / 2 在和为奇数时会向下取整，
        // 例如 trust=89/affection=90 → 89（应 89.5），可能让临界值判定偏移一档。
        // 阈值统一用浮点字面量做直接浮点比较，避免 Float 与 Int 跨类型比较的歧义。
        val score = (trust + affection) / 2f
        return when {
            score >= 90f -> RelationshipStage.CORE
            score >= 75f -> RelationshipStage.IMPORTANT
            score >= 60f -> RelationshipStage.TRUSTED
            score >= 40f -> RelationshipStage.FAMILIAR
            else         -> RelationshipStage.STRANGER
        }
    }
}

data class RelationshipDelta(
    val trust: Int      = 0,
    val respect: Int    = 0,
    val affection: Int  = 0,
    val curiosity: Int  = 0,
    val dependence: Int = 0,
    val conflict: Int   = 0,
    // P1-6-3 修复：suppression 原先在 ChatViewModel 中独立读改写（deltaMutex 外），
    // 存在竞态窗口。现并入 RelationshipDelta，由 applyDelta 在 deltaMutex 内一并更新。
    val suppressionDelta: Int = 0,
)
