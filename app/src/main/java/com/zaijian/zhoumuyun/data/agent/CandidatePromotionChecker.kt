package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import com.zaijian.zhoumuyun.data.db.entity.SystemSuggestionEntity
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionConfig
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionEngine
import com.zaijian.zhoumuyun.data.repository.SpecialtyProfileRepository
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CandidatePromotionChecker — P6 专长进化系统候选特征转正流程（方案第5.3节）
 *
 * 候选特征的 occurrenceCount 达到 SpecialtyEvolutionConfig.CANDIDATE_PROMOTION_THRESHOLD
 * 后，"该不该正式写入 styleNotes" 的处理方式按成熟度分两条路径：
 *
 *   摸索期 EXPLORING / 成型期 FORMING：
 *     生成圆桌播报明确询问用户"要不要把这个写进她的风格里"，用户确认后
 *     才正式写入 styleNotes，候选池移除该条。这一步顺带满足晋升判定
 *     条件3（"用户至少主动确认过一次"）——调用 markUserConfirmed。
 *
 *   稳定期 STABLE：
 *     不再用"达到3次就问用户"这套简单逻辑，转而调用 LLM 判断这条新特征
 *     与已有 styleNotes 的关系是"强化/补充/冲突"，三种结果分别走不同
 *     的处理路径（见 [handleStablePeriodCandidate]）。
 *
 * 调用时机：DailyPracticeWorker.updateCandidatePool 记录完观察后，
 * 紧接着调用本类的 [checkPromotion]。
 */
object CandidatePromotionChecker {

    private val promotionMutexes = ConcurrentHashMap<String, Mutex>()
    private fun getPromotionMutex(profileId: String): Mutex =
        promotionMutexes.computeIfAbsent(profileId) { Mutex() }

    suspend fun checkPromotion(
        db: AppDatabase,
        repo: SpecialtyProfileRepository,
        engine: SpecialtyEvolutionEngine,
        profileId: String,
        trait: String,
        occurrenceCount: Int,
    ) {
        if (occurrenceCount < SpecialtyEvolutionConfig.CANDIDATE_PROMOTION_THRESHOLD) return
        getPromotionMutex(profileId).withLock {
            checkPromotionInternal(db, repo, engine, profileId, trait, occurrenceCount)
        }
    }

    private suspend fun checkPromotionInternal(
        db: AppDatabase,
        repo: SpecialtyProfileRepository,
        engine: SpecialtyEvolutionEngine,
        profileId: String,
        trait: String,
        occurrenceCount: Int,
    ) {

        val profile = repo.getProfile(profileId) ?: return

        when (profile.maturityStage) {
            "STABLE" -> handleStablePeriodCandidate(db, repo, engine, profile, trait)
            else -> requestUserConfirmation(db, profile, trait)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  摸索期/成型期：询问用户确认
    // ─────────────────────────────────────────────────────────

    private suspend fun requestUserConfirmation(
        db: AppDatabase,
        profile: com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity,
        trait: String,
    ) {
        // 避免对同一条特征重复发起确认请求：检查是否已有未处理的同类待定建议
        val existing = db.systemSuggestionDao().countPending(profile.id)
        if (existing > 0) return  // 已经有一条待处理的建议，本次先不再叠加新的确认请求

        val roundtableId = db.roundtableMessageDao()
            .findMostRecentRoundtableIdForSpeaker(profile.characterId.toString())
            ?: return
        val config = com.zaijian.zhoumuyun.data.model.DefaultCharacters.firstOrNull { it.id == profile.characterId }
        val speakerName = config?.name ?: "角色${profile.characterId}"

        db.roundtableMessageDao().insert(
            RoundtableMessageEntity(
                id = UUID.randomUUID().toString(),
                roundtableId = roundtableId,
                speakerId = profile.characterId.toString(),
                speakerName = speakerName,
                content = "我发现自己最近几次创作都倾向于$trait，要把这个写进我的风格里吗？",
                createdAt = System.currentTimeMillis(),
            )
        )

        db.systemSuggestionDao().insert(
            SystemSuggestionEntity(
                id = UUID.randomUUID().toString(),
                characterId = profile.characterId,
                specialtyId = profile.id,
                content = "CANDIDATE_CONFIRM::$trait",
                reasoning = "该特征已被观察到${SpecialtyEvolutionConfig.CANDIDATE_PROMOTION_THRESHOLD}次以上，询问是否正式写入风格说明书",
                status = "PENDING",
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * 用户在专长档案页确认"要把这个写进风格里"后调用。
     * 直接整段重写 styleNotes（与 mergeStageDigestsIntoProfile 同样的整合
     * 思路，但这里是单条特征的轻量级追加，不需要走完整的多份摘要合并流程，
     * 简单拼接即可——候选转正的内容通常已经是一句完整的描述）。
     */
    suspend fun confirmCandidate(
        db: AppDatabase,
        repo: SpecialtyProfileRepository,
        profileId: String,
        trait: String,
        suggestionId: String,
    ) {
        val profile = repo.getProfile(profileId) ?: return
        val traitTrimmed = trait.trim()
        // 方案 2-9：新的 trait 必须完整保留，需要截断的是旧内容。
        // 原逻辑对整个拼接结果 .take(MAX_CHARS) 可能在 trait 中间截断，
        // 产生不完整的半句话。
        val maxOldLen = SpecialtyEvolutionConfig.STYLE_NOTES_MAX_CHARS - traitTrimmed.length - 1
        val oldPart = if (maxOldLen > 0) {
            profile.styleNotes.trim().take(maxOldLen)
        } else {
            // 如果 trait 本身就超长，旧内容全部丢弃
            ""
        }
        val newStyleNotes = if (oldPart.isEmpty()) traitTrimmed
            else "$oldPart\n$traitTrimmed"

        repo.overwriteStyleNotes(profileId, newStyleNotes)
        repo.removeCandidateObservation(profileId, trait)
        repo.markUserConfirmed(profileId)  // 满足晋升判定条件3
        db.systemSuggestionDao().updateStatus(suggestionId, "ADOPTED")
    }

    suspend fun declineCandidate(db: AppDatabase, repo: SpecialtyProfileRepository, profileId: String, trait: String, suggestionId: String) {
        repo.removeCandidateObservation(profileId, trait)
        db.systemSuggestionDao().updateStatus(suggestionId, "IGNORED")
    }

    // ─────────────────────────────────────────────────────────
    //  稳定期：强化 / 补充 / 冲突 三选一判断
    // ─────────────────────────────────────────────────────────

    private suspend fun handleStablePeriodCandidate(
        db: AppDatabase,
        repo: SpecialtyProfileRepository,
        engine: SpecialtyEvolutionEngine,
        profile: com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity,
        trait: String,
    ) {
        // 复用 mergeStageDigestsIntoProfile 的判断逻辑——把这条候选特征
        // 当作一份"只有一句话的阶段摘要"传入，让 LLM 用同一套"强化/补充/冲突"
        // 标准来处理，不需要再写一个专门的 Prompt（方案第5.5节本身就是
        // 稳定期处理新信息的标准流程，候选特征转正只是触发它的另一个入口）
        val mergeResult = engine.mergeStageDigestsIntoProfile(
            currentStyleNotes = profile.styleNotes,
            stageDigests = listOf(trait),
        )
        if (mergeResult.updatedStyleNotes.isBlank()) {
            // 方案 2-2：合并失败时移除候选，避免反复触发 LLM 调用形成死循环。
            // 每次新观察触发 checkPromotion 时都会再次调用 LLM 合并，每次都
            // 在同一处返回——移除候选后，下次不再触发。
            ZLog.w("CandidatePromotionChecker", "LLM 合并失败，移除候选特征 profileId=${profile.id} trait=${trait.take(80)}")
            repo.removeCandidateObservation(profile.id, trait)
            return
        }

        repo.overwriteStyleNotes(profile.id, mergeResult.updatedStyleNotes)
        repo.removeCandidateObservation(profile.id, trait)

        if (mergeResult.hasUnresolvedConflict) {
            repo.setConflictState(profile.id, true, mergeResult.conflictDescription)

            val roundtableId = db.roundtableMessageDao()
                .findMostRecentRoundtableIdForSpeaker(profile.characterId.toString())
            if (roundtableId != null) {
                val config = com.zaijian.zhoumuyun.data.model.DefaultCharacters.firstOrNull { it.id == profile.characterId }
                val speakerName = config?.name ?: "角色${profile.characterId}"
                db.roundtableMessageDao().insert(
                    RoundtableMessageEntity(
                        id = UUID.randomUUID().toString(),
                        roundtableId = roundtableId,
                        speakerId = profile.characterId.toString(),
                        speakerName = speakerName,
                        content = "我注意到自己最近的处理方式和之前确立的风格有点不一样：" +
                            "${mergeResult.conflictDescription}。要保留哪个方向，还是两者都留着，" +
                            "看场景用不同的笔法？",
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }
}
