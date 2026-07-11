package com.zaijian.zhoumuyun.data.agent

import androidx.room.withTransaction
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordArchiveEntity
import com.zaijian.zhoumuyun.data.db.entity.StageDigestEntity
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionConfig
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionEngine
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.repository.SpecialtyProfileRepository
import java.util.UUID

/**
 * DistillationTrigger — P6 专长进化系统的容量驱动蒸馏入口
 *
 * 由 DailyPracticeWorker 每次修炼落库后调用，判断是否达到蒸馏阈值
 * （见 SpecialtyEvolutionConfig.RAW_TO_DIGEST_TRIGGER_COUNT /
 * DIGEST_TO_PROFILE_TRIGGER_COUNT），达到则依次执行：
 *   第1→2层：原始产出 → 阶段摘要（[runRawToDigest]）
 *   第2→3层：阶段摘要 → 并入 styleNotes（[runDigestToProfile]）
 *
 * 与设计方案的一处简化说明：
 *
 * 方案第3节原定"风格分歧（CONFLICTING 记录）至少保留到下两次阶段摘要周期之后
 * 才允许被蒸馏降级"。实现时发现这需要额外追踪"这条冲突记录经历过几次蒸馏
 * 周期"，要么新增字段、要么用近似时间戳判断，两种都增加了复杂度和出错可能。
 *
 * 这里采用更简单也更安全的策略：CONFLICTING 记录永久保持 RAW 状态，
 * 不参与任何自动降级判断，只能由用户在专长档案页主动处理（确认保留哪个
 * 方向 / 标记为已知悉）后才会被纳入下一轮蒸馏的候选范围。这比"自动两轮后
 * 放行"更保守，代价是分歧记录可能长期占着 RAW 状态拉高 countRawRecords，
 * 但好处是不会出现"AI 自己悄悄把用户该看到的分歧蒸馏掉了"的风险——
 * 风格分歧本质上是需要人决策的事，不该有任何自动放行路径。
 */
object DistillationTrigger {

    suspend fun checkAndRun(db: AppDatabase, provider: LLMProvider, specialtyId: String) {
        val engine = SpecialtyEvolutionEngine(provider)
        val repo = SpecialtyProfileRepository(
            db = db,
            specialtyProfileDao = db.specialtyProfileDao(),
            evolutionPlanDao = db.evolutionPlanDao(),
            practiceRecordDao = db.practiceRecordDao(),
            practiceRecordArchiveDao = db.practiceRecordArchiveDao(),
            stageDigestDao = db.stageDigestDao(),
            systemSuggestionDao = db.systemSuggestionDao(),
        )

        // 第1→2层：原始产出 → 阶段摘要
        val rawCount = db.practiceRecordDao().countRawRecords(specialtyId)
        if (rawCount >= SpecialtyEvolutionConfig.RAW_TO_DIGEST_TRIGGER_COUNT) {
            runRawToDigest(db, engine, specialtyId)
        }

        // 第2→3层：阶段摘要 → 并入 styleNotes
        val unmergedCount = db.stageDigestDao().countUnmerged(specialtyId)
        if (unmergedCount >= SpecialtyEvolutionConfig.DIGEST_TO_PROFILE_TRIGGER_COUNT) {
            runDigestToProfile(db, repo, engine, specialtyId)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  第1→2层
    // ─────────────────────────────────────────────────────────

    private suspend fun runRawToDigest(
        db: AppDatabase,
        engine: SpecialtyEvolutionEngine,
        specialtyId: String,
    ) {
        val profile = db.specialtyProfileDao().getById(specialtyId) ?: return

        // 只取最旧的一批 RAW 记录蒸馏（不是全部，避免一次性塞太多内容给 LLM；
        // 如果还有更多 RAW 记录，下次容量阈值再次达到时会继续处理）
        val candidates = db.practiceRecordDao()
            .getOldestRawRecords(specialtyId, SpecialtyEvolutionConfig.RAW_TO_DIGEST_TRIGGER_COUNT)

        // CONFLICTING 记录不参与本轮降级（见类注释的简化说明），
        // 但仍然作为上下文提供给 LLM 生成摘要——这样摘要里能体现"存在分歧"，
        // 即使这条记录本身不会被标记为已蒸馏
        val toDigest = candidates.filter { it.comparisonResult != "CONFLICTING" }
        if (toDigest.isEmpty()) return  // 这一批全是冲突记录，没有可降级的内容，跳过

        val digestResult = engine.digestRawRecords(
            domain = profile.domain,
            records = candidates.map {
                SpecialtyEvolutionEngine.RecordForDigest(
                    practiceTopic = it.practiceTopic,
                    content = it.content,
                    comparisonResult = it.comparisonResult,
                    comparisonNote = it.comparisonNote,
                )
            },
        )
        if (digestResult.digestContent.isBlank()) return  // 蒸馏失败，跳过，下次重试

        val digestId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        // U-6 修复：stageDigestDao.insert + 循环 markDigested 用 withTransaction 包裹。
        // LLM 调用（digestRawRecords）在事务外完成，只有结果写入在事务内，保证原子性。
        db.withTransaction {
        db.stageDigestDao().insert(
            StageDigestEntity(
                id = digestId,
                characterId = profile.characterId,
                specialtyId = specialtyId,
                digestContent = digestResult.digestContent,
                sourceRecordCount = candidates.size,
                periodStart = candidates.minOf { it.createdAt },
                periodEnd = candidates.maxOf { it.createdAt },
                hasConflict = digestResult.hasConflict,
                conflictSummary = digestResult.conflictSummary,
                createdAt = now,
            )
        )

        // 把非冲突、非里程碑的记录降级为 DIGESTED，原文转存归档表
        for (record in toDigest) {
            if (record.digestStatus == "MILESTONE") continue  // 里程碑永久保护，不降级
            db.practiceRecordArchiveDao().insert(
                PracticeRecordArchiveEntity(
                    recordId = record.id,
                    fullContent = record.content,
                    archivedAt = now,
                )
            )
            db.practiceRecordDao().markDigested(
                recordId = record.id,
                digestId = digestId,
                placeholder = "[已蒸馏，原文见阶段摘要 #$digestId]",
            )
        }
        } // end db.withTransaction
    }

    // ─────────────────────────────────────────────────────────
    //  第2→3层
    // ─────────────────────────────────────────────────────────

    private suspend fun runDigestToProfile(
        db: AppDatabase,
        repo: SpecialtyProfileRepository,
        engine: SpecialtyEvolutionEngine,
        specialtyId: String,
    ) {
        val profile = db.specialtyProfileDao().getById(specialtyId) ?: return
        val unmerged = db.stageDigestDao().getUnmerged(specialtyId)
        if (unmerged.isEmpty()) return

        val previousStyleNotes = profile.styleNotes

        val mergeResult = engine.mergeStageDigestsIntoProfile(
            currentStyleNotes = previousStyleNotes,
            stageDigests = unmerged.map { it.digestContent },
        )
        if (mergeResult.updatedStyleNotes.isBlank()) return  // 合并失败，跳过，保留旧 styleNotes

        // U-6 修复：overwriteStyleNotes + markMergedBatch + setConflictState 用 withTransaction
        // 包裹。LLM 调用（mergeStageDigestsIntoProfile）在事务外，只有结果写入在事务内。
        db.withTransaction {
            repo.overwriteStyleNotes(specialtyId, mergeResult.updatedStyleNotes)
            db.stageDigestDao().markMergedBatch(unmerged.map { it.id })

            if (mergeResult.hasUnresolvedConflict) {
                repo.setConflictState(specialtyId, true, mergeResult.conflictDescription)
            }
        }

        // 晋升判定检查（独立模块，见 IdentityPromotionEvaluator）：
        // 每次完成第2→3层合并后，都是一个"是否有特征已经够稳定可以晋升"的
        // 检查时机。晋升判定本身需要 LLM 辅助（比较两版styleNotes找稳定特征），
        // 这里只负责调用入口，具体判定逻辑保持独立模块的单一职责。
        try {
            IdentityPromotionEvaluator.evaluate(
                db = db,
                engine = engine,
                profileId = specialtyId,
                previousStyleNotes = previousStyleNotes,
            )
        } catch (e: Exception) {
            ZLog.w("DistillationTrigger", "晋升判定失败 specialtyId=$specialtyId", e)
        }

        // AI 自我提案检查（独立模块，低频，见 SystemSuggestionGenerator）
        try {
            SystemSuggestionGenerator.maybeGenerate(db, engine, specialtyId)
        } catch (e: Exception) {
            ZLog.w("DistillationTrigger", "AI自我提案生成失败 specialtyId=$specialtyId", e)
        }
    }
}
