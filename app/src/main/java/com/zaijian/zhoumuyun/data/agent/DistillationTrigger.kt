package com.zaijian.zhoumuyun.data.agent

import androidx.room.withTransaction
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordArchiveEntity
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordEntity
import com.zaijian.zhoumuyun.data.db.entity.StageDigestEntity
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionConfig
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionEngine
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.repository.SpecialtyProfileRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // #62 修复：与 CandidatePromotionChecker#53 / IdentityPromotionEvaluator#54
    // 同类问题——原先 ConcurrentHashMap + computeIfAbsent 只增不删，每个出现过的
    // specialtyId 永久占一条 Mutex 记录。改为带上限的 access-order LinkedHashMap：
    // 超过上限时淘汰最久未用的一条，但正被持有（isLocked）的条目跳过淘汰，避免
    // 破坏互斥语义。读写都在 synchronized 块里保证"查询已有 / 新建 / 淘汰"三步原子。
    private const val MAX_TRACKED_MUTEXES = 256
    private val mutexes = object : LinkedHashMap<String, Mutex>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mutex>): Boolean =
            size > MAX_TRACKED_MUTEXES && !eldest.value.isLocked
    }

    private fun getMutex(specialtyId: String): Mutex = synchronized(mutexes) {
        mutexes.getOrPut(specialtyId) { Mutex() }
    }

    suspend fun checkAndRun(db: AppDatabase, provider: LLMProvider, specialtyId: String) {
        getMutex(specialtyId).withLock {
            checkAndRunInternal(db, provider, specialtyId)
        }
    }

    private suspend fun checkAndRunInternal(
        db: AppDatabase, provider: LLMProvider, specialtyId: String
    ) {
        val engine = SpecialtyEvolutionEngine(provider)
        // 窗口02结论5修复：原先在此处独立 new 一份 SpecialtyProfileRepository，
        // 构造参数（7个字段）与 AppContainer.specialtyProfileRepo 完全一致
        // （AppContainer.init() 在 ZaijianApp.onCreate() 同步执行，早于任何
        // Worker/Trigger 执行，AppDatabase.getInstance() 全局单例，调用方
        // 传入的 db 与容器内部持有的是同一实例），改用容器共享实例，消除
        // 重复构造。
        val repo = com.zaijian.zhoumuyun.data.AppContainer.instance.specialtyProfileRepo

        // 第1→2层：原始产出 → 阶段摘要
        // W1-004 修复：countRawRecords + getOldestRawRecords 改为单次事务内快照读取
        val rawCandidates = db.practiceRecordDao().snapshotOldestRawRecordsIfThresholdMet(
            specialtyId = specialtyId,
            threshold = SpecialtyEvolutionConfig.RAW_TO_DIGEST_TRIGGER_COUNT,
            limit = SpecialtyEvolutionConfig.RAW_TO_DIGEST_TRIGGER_COUNT,
        )
        if (rawCandidates.isNotEmpty()) {
            runRawToDigest(db, engine, specialtyId, rawCandidates)
        }

        // 第2→3层：阶段摘要 → 并入 styleNotes
        // W1-005 修复：countUnmerged + getUnmerged 改为单次事务内快照读取
        val unmergedDigests = db.stageDigestDao().snapshotUnmergedIfThresholdMet(
            specialtyId = specialtyId,
            threshold = SpecialtyEvolutionConfig.DIGEST_TO_PROFILE_TRIGGER_COUNT,
        )
        if (unmergedDigests.isNotEmpty()) {
            runDigestToProfile(db, repo, engine, specialtyId, unmergedDigests)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  第1→2层
    // ─────────────────────────────────────────────────────────

    private suspend fun runRawToDigest(
        db: AppDatabase,
        engine: SpecialtyEvolutionEngine,
        specialtyId: String,
        candidates: List<PracticeRecordEntity>,
    ) {
        // P1 修复：顶层 try-catch，防止 LLM 调用或 DB 写入异常中断 DailyPracticeWorker
        try {
        val profile = db.specialtyProfileDao().getById(specialtyId) ?: return

        // candidates 由调用方（checkAndRunInternal）通过 snapshotOldestRawRecordsIfThresholdMet
        // 事务内一致性快照传入（W1-004 修复），不在此处重新查询，避免二次查询与
        // 快照之间出现新的不一致窗口。

        // CONFLICTING 记录不参与本轮降级（见类注释的简化说明），
        // 但仍然作为上下文提供给 LLM 生成摘要——这样摘要里能体现"存在分歧"，
        // 即使这条记录本身不会被标记为已蒸馏
        val toDigest = candidates.filter { it.comparisonResult != "CONFLICTING" }
        if (toDigest.isEmpty()) {
            // 方案 2-10：全 CONFLICTING 记录时标记为 CONFLICTING_PENDING_USER，
            // 避免被 countRawRecords 反复计入导致每轮触发空蒸馏。
            db.practiceRecordDao().markConflictingPendingUser(specialtyId)
            ZLog.d("DistillationTrigger", "specialtyId=$specialtyId 本轮全部为 CONFLICTING 记录，已标记待用户处理")
            return
        }

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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e("DistillationTrigger", "runRawToDigest 异常 specialtyId=$specialtyId", e)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  第2→3层
    // ─────────────────────────────────────────────────────────

    private suspend fun runDigestToProfile(
        db: AppDatabase,
        repo: SpecialtyProfileRepository,
        engine: SpecialtyEvolutionEngine,
        specialtyId: String,
        unmerged: List<StageDigestEntity>,
    ) {
        // P1 修复：顶层 try-catch，防止 LLM 调用或 DB 写入异常中断 DailyPracticeWorker
        try {
        val profile = db.specialtyProfileDao().getById(specialtyId) ?: return
        // unmerged 由调用方（checkAndRunInternal）通过 snapshotUnmergedIfThresholdMet
        // 事务内一致性快照传入（W1-005 修复），不在此处重新查询。
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("DistillationTrigger", "晋升判定失败 specialtyId=$specialtyId", e)
        }

        // AI 自我提案检查（独立模块，低频，见 SystemSuggestionGenerator）
        try {
            SystemSuggestionGenerator.maybeGenerate(db, engine, specialtyId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("DistillationTrigger", "AI自我提案生成失败 specialtyId=$specialtyId", e)
        }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e("DistillationTrigger", "runDigestToProfile 异常 specialtyId=$specialtyId", e)
        }
    }
}
