package com.zaijian.zhoumuyun.data.agent

import androidx.room.withTransaction
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionConfig
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionEngine
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * IdentityPromotionEvaluator — P6 专长进化系统"晋升 Identity Layer"判定
 *
 * 对应设计方案第6章。这是用户要求"牢牢掌握、像本能"的核心落地：
 * 把某条已经足够稳定的专长特征，从 SpecialtyProfile.styleNotes（知识层，
 * 措辞"她最近习惯…"）搬进 CharacterIdentity.soulNote（本能层，措辞
 * "她本来就是…"），与角色天生人设同层级注入。
 *
 * 四个复合判定条件（方案第6.2节，缺一不可）：
 *   1. 专长进入稳定期（maturityStage == "STABLE"）—— 纯数据判断
 *   2. 该特征在 styleNotes 中已稳定存在（跨越至少
 *      PROMOTION_MIN_STABLE_MERGE_CYCLES 次合并周期未被冲突/压缩掉）
 *      —— 需要 LLM 辅助（见 SpecialtyEvolutionEngine.findStableTraits）
 *   3. 用户对该专长方向至少有过1次主动确认互动 —— 纯数据判断
 *      （hasUserConfirmedAtLeastOnce 字段）
 *   4. 无未解决的冲突标记 —— 纯数据判断（hasUnresolvedConflict）
 *
 * 四个条件全部满足后，不自动晋升，而是生成一条圆桌播报请用户确认
 * （见 [requestPromotionConfirmation]）。真正执行晋升写入的是
 * [executePromotion]，由用户在专长档案页确认后调用——这一步不在本类的
 * evaluate() 流程里自动触发，必须有一次明确的用户操作作为入口，
 * 这是刻意的设计（呼应方案"晋升这个动作本身不能是系统自己悄悄做的"）。
 */
object IdentityPromotionEvaluator {

    /**
     * 候选稳定特征的追踪状态。
     *
     * 已知限制（与 SpecialtyEvolutionConfig 末尾注释一致）：本类用一个
     * 简单的内存级近似——每次合并后调用 findStableTraits 比较新旧两版
     * styleNotes，如果某条特征连续 N 次都被判定为"在两版中都存在"，
     * 视为满足条件2。这个状态本身不持久化到数据库（v1 简化处理），
     * 意味着如果 App 进程在两次合并之间被杀死重启，连续计数会从0重新开始
     * ——这是已知的精度损失，但晋升门槛本来就高（需要多轮持续稳定），
     * 偶尔的计数中断不会让一个真正稳定的特征"永远晋升不了"，只是会
     * 略微延后，可以接受。如果未来需要更精确的版本，需要给 styleNotes
     * 拆分成可独立追踪的"特征单元"结构，这是更大的改动，v1 不做。
     */
    private val stableTraitMutexes = ConcurrentHashMap<String, Mutex>()
    private val stableTraitTracker = mutableMapOf<String, MutableMap<String, Int>>()

    private fun getStableTraitMutex(profileId: String): Mutex =
        stableTraitMutexes.computeIfAbsent(profileId) { Mutex() }

    /**
     * 在每次第2→3层合并完成后调用，检查是否有特征满足晋升条件。
     * 满足时生成圆桌播报请用户确认，不自动执行晋升写入。
     */
    suspend fun evaluate(
        db: AppDatabase,
        engine: SpecialtyEvolutionEngine,
        profileId: String,
        previousStyleNotes: String,
    ) {
        val profile = db.specialtyProfileDao().getById(profileId) ?: return

        // 条件1：稳定期
        if (profile.maturityStage != "STABLE") return
        // 条件4：无未解决冲突
        if (profile.hasUnresolvedConflict) return
        // 条件3：用户至少确认过一次
        if (!profile.hasUserConfirmedAtLeastOnce) return

        // 条件2：LLM 辅助判断哪些特征连续稳定
        val stability = engine.findStableTraits(previousStyleNotes, profile.styleNotes)
        if (stability.stableTraits.isEmpty()) return

        val tracker = getStableTraitMutex(profileId).withLock {
            val tracker = stableTraitTracker.getOrPut(profileId) { mutableMapOf() }
            // 本轮没有被判定为稳定的旧特征，计数清零（中断了就要重新攒）
            val keysToRemove = tracker.keys.filter { it !in stability.stableTraits }
            keysToRemove.forEach { tracker.remove(it) }
            // 本轮判定稳定的特征，计数+1
            stability.stableTraits.forEach { trait ->
                tracker[trait] = (tracker[trait] ?: 0) + 1
            }
            tracker
        }

        val readyToPromote = tracker.filter { it.value >= SpecialtyEvolutionConfig.PROMOTION_MIN_STABLE_MERGE_CYCLES }
        if (readyToPromote.isEmpty()) return

        // 满足全部四个条件，对每个达标特征生成一条确认请求
        // （一次只处理一个，避免同时给用户塞多条确认消息；
        // 处理完后从 tracker 移除，下次 evaluate 再检查剩余的）
        val (trait, _) = readyToPromote.entries.first()
        requestPromotionConfirmation(db, profile, trait)
        tracker.remove(trait)
    }

    // ─────────────────────────────────────────────────────────
    //  生成确认请求（圆桌播报，措辞强调"这次不一样"）
    // ─────────────────────────────────────────────────────────

    private suspend fun requestPromotionConfirmation(
        db: AppDatabase,
        profile: com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity,
        traitSummary: String,
    ) {
        val roundtableId = db.roundtableMessageDao()
            .findMostRecentRoundtableIdForSpeaker(profile.characterId.toString())
            ?: return

        val config = com.zaijian.zhoumuyun.data.model.DefaultCharacters.firstOrNull { it.id == profile.characterId }
        val speakerName = config?.name ?: "角色${profile.characterId}"

        val text = "她在「${profile.domain}」上的这个特点，已经稳定了很久——$traitSummary。" +
            "要不要把这个写进她的本质里？写进去之后，这就不再是『她最近这样』，" +
            "而是『她本来就是这样』，会变成她做任何事的默认习惯，不容易再改回去。" +
            "（这条建议会出现在专长档案页，你可以随时去确认或暂时搁置）"

        db.roundtableMessageDao().insert(
            RoundtableMessageEntity(
                id = UUID.randomUUID().toString(),
                roundtableId = roundtableId,
                speakerId = profile.characterId.toString(),
                speakerName = speakerName,
                content = text,
                createdAt = System.currentTimeMillis(),
            )
        )

        // 待确认的晋升请求本身也需要持久化供专长档案页展示（不只是一条会被
        // 历史记录淹没的群消息）。复用 SystemSuggestionEntity 的表结构存储
        // 这类"待用户决策"的事项，status 复用同一套 PENDING/ADOPTED/IGNORED
        // 语义（ADOPTED = 用户确认晋升，IGNORED = 用户选择暂不晋升）。
        // content 字段用特殊前缀标记类型，供专长档案页区分展示样式。
        db.systemSuggestionDao().insert(
            com.zaijian.zhoumuyun.data.db.entity.SystemSuggestionEntity(
                id = UUID.randomUUID().toString(),
                characterId = profile.characterId,
                specialtyId = profile.id,
                content = "PROMOTION_REQUEST::$traitSummary",
                reasoning = "该特征已连续${SpecialtyEvolutionConfig.PROMOTION_MIN_STABLE_MERGE_CYCLES}轮合并保持稳定，且专长已进入稳定期、无未解决冲突",
                status = "PENDING",
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    // ─────────────────────────────────────────────────────────
    //  执行晋升（用户在专长档案页确认后调用）
    // ─────────────────────────────────────────────────────────

    /**
     * 用户确认晋升后调用。执行步骤（方案第6.2/6.3节）：
     *   1. 调用 SpecialtyEvolutionEngine.integrateIntoSoulNote 自然融合文本
     *   2. 写入 character_identity.soulNote（复用已有的 updateSoulNote，
     *      自动获得备份+撤销能力——晋升这个动作本身是可撤销的）
     *   3. SpecialtyProfileEntity.promotedToIdentity = true，
     *      styleNotes 中已晋升的内容被移除（避免重复注入）
     *
     * @param traitSummary 已晋升的特征描述（用于从 styleNotes 中尝试移除对应内容）
     */
    suspend fun executePromotion(
        db: AppDatabase,
        engine: SpecialtyEvolutionEngine,
        profileId: String,
        suggestionId: String,
        traitSummary: String,
    ) {
        // 遗留裸调用修复：函数接收的是裸 AppDatabase 参数，这里就近包一层
        // IdentityRepository（薄包装、无自有事务，withTransaction 内使用安全）。
        val identityRepo = IdentityRepository(db.characterIdentityDao())
        val profile = db.specialtyProfileDao().getById(profileId) ?: return
        val identity = identityRepo.getById(profile.characterId)
        val currentSoulNote = identity?.soulNote ?: ""

        val integratedSoulNote = engine.integrateIntoSoulNote(currentSoulNote, traitSummary)

        // 擅长领域标签墙：把这次晋升的完整特征描述浓缩成2-4字短标签。
        // 与 integrateIntoSoulNote 同样在事务外完成（都是 LLM 调用，不应占用
        // 数据库事务时间）。这次 LLM 调用失败不影响晋升主流程——
        // distillSkillTag 内部已有兜底（失败时返回 domain 本身），
        // 这里不需要额外 try-catch。
        val skillTag = engine.distillSkillTag(profile.domain, traitSummary)

        // U-6 修复：upsertSoulNote → markPromoted → updateStatus 三步用 withTransaction 包裹。
        // 原先若 markPromoted 成功后 updateStatus 崩溃，角色 soulNote 已更新但 suggestion 仍为
        // PENDING，重入时会重复执行晋升流程，导致 soulNote 重复追加同一特征。
        // LLM 调用（integrateIntoSoulNote / distillSkillTag）在事务外完成，只有结果写入在事务内。
        db.withTransaction {
            // 方案 2-3：CAS 乐观锁写入 soulNote，防止 lost update。
            // 若当前 soulNote 已被其他协程修改，upsertSoulNoteCas 返回 false，
            // 记录日志后继续执行后续步骤（soulNote 本身不是核心数据，CAS 失败
            // 只是说"基于旧值生成的融合文本未写入"，不会导致数据损坏）。
            val casOk = identityRepo.upsertSoulNoteCas(
                characterId = profile.characterId,
                value = integratedSoulNote,
                expectedOldValue = currentSoulNote,
            )
            if (!casOk) {
                ZLog.w("IdentityPromotionEvaluator",
                    "CAS 写入 soulNote 失败（并发修改），characterId=${profile.characterId} " +
                    "新值长度=${integratedSoulNote.length}，已跳过写入")
            }

            // styleNotes 中尝试移除已晋升的内容：v1 简化为只标记 promotedToIdentity=true，
            // 下一轮蒸馏时自然清理，不做有风险的字符串强制删除（见原注释说明）。
            db.specialtyProfileDao().markPromoted(profileId, profile.styleNotes)

            // 擅长领域标签墙：本次晋升写入一条新标签记录，与 soulNote 更新
            // 同一事务内完成——避免"soulNote已更新但标签墙记录写入失败"
            // 这种不一致状态（虽然标签墙只是辅助展示，但既然都在事务里了，
            // 顺手保证一致性成本很低）。
            db.promotedSkillTagDao().insert(
                com.zaijian.zhoumuyun.data.db.entity.PromotedSkillTagEntity(
                    id = UUID.randomUUID().toString(),
                    characterId = profile.characterId,
                    specialtyId = profile.id,
                    tag = skillTag,
                    sourceTraitSummary = traitSummary,
                    createdAt = System.currentTimeMillis(),
                )
            )

            db.systemSuggestionDao().updateStatus(suggestionId, "ADOPTED")
        }
    }

    /** 用户选择暂不晋升时调用 */
    suspend fun declinePromotion(db: AppDatabase, suggestionId: String) {
        db.systemSuggestionDao().updateStatus(suggestionId, "IGNORED")
    }
}
