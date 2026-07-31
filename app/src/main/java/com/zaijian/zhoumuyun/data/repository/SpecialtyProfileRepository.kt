package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.EvolutionPlanDao
import com.zaijian.zhoumuyun.data.db.dao.PracticeRecordArchiveDao
import com.zaijian.zhoumuyun.data.db.dao.PracticeRecordDao
import com.zaijian.zhoumuyun.data.db.dao.SpecialtyProfileDao
import com.zaijian.zhoumuyun.data.db.dao.StageDigestDao
import com.zaijian.zhoumuyun.data.db.dao.SystemSuggestionDao
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.EvolutionPlanEntity
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordEntity
import com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionConfig
import com.zaijian.zhoumuyun.util.ZLog
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 专长档案 Repository（P6 专长进化系统）
 *
 * 职责：
 *   ① 专长档案的创建/查询/启停（用户在专长档案页操作的入口）
 *   ② 进化方案的版本管理（创建新版本时自动归档旧版本，不覆盖删除）
 *   ③ 候选观察池的读写（JSON 序列化/反序列化集中在这一层，
 *      DailyPracticeWorker 和晋升判定都通过这里操作，不直接摸 JSON 字符串）
 *   ④ 成熟度阶段的判定与更新（阈值来自 SpecialtyEvolutionConfig，单一来源）
 *
 * 不在这一层做的事：LLM 调用（风格比对、蒸馏摘要等）放在对应的 Engine 里，
 * Repository 只负责数据的读写和简单的派生计算，保持职责边界清晰。
 */
class SpecialtyProfileRepository(
    private val db: AppDatabase,
    private val specialtyProfileDao: SpecialtyProfileDao,
    private val evolutionPlanDao: EvolutionPlanDao,
    private val practiceRecordDao: PracticeRecordDao,
    private val practiceRecordArchiveDao: PracticeRecordArchiveDao,
    private val stageDigestDao: StageDigestDao,
    private val systemSuggestionDao: SystemSuggestionDao,
) {

    // 性能 L4 修复：候选观察池的 recordCandidateObservation / removeCandidateObservation
    // 都是经典的 读取JSON → 内存修改 → 整体写回 模式，无任何加锁。同一 profileId 并发触发
    // （例如竞赛反哺与 DailyPracticeWorker 的日常观察几乎同时发生）时，后写入的一方会
    // 整体覆盖先写入的一方，丢失中间的修改（lost update）。
    // 与 DistillationEngine.getMutex(goalId) 同一套路：按 profileId 维护独立 Mutex，
    // 保证同一档案的候选池读改写整体串行化；不同档案之间互不阻塞。
    private val candidateMutexes = ConcurrentHashMap<String, Mutex>()
    private fun getCandidateMutex(profileId: String): Mutex =
        candidateMutexes.computeIfAbsent(profileId) { Mutex() }

    // ── 候选观察池数据结构 ───────────────────────────────────────

    data class CandidateObservation(
        val trait: String,
        val firstSeenAt: Long,
        var occurrenceCount: Int,
        var lastSeenAt: Long,
    )

    // ── 专长档案 CRUD ────────────────────────────────────────────

    fun observeProfilesForCharacter(characterId: Int): Flow<List<SpecialtyProfileEntity>> =
        specialtyProfileDao.observeAllForCharacter(characterId)

    fun observeProfile(id: String): Flow<SpecialtyProfileEntity?> =
        specialtyProfileDao.observeById(id)

    suspend fun getProfile(id: String): SpecialtyProfileEntity? =
        specialtyProfileDao.getById(id)

    /**
     * 创建专长方向。
     * 这是整套系统的起点：用户在群里布置一次方向，对应调用本方法，
     * anchorIntent 整段保留用户原话，作为后续所有蒸馏的校准基准。
     */
    suspend fun createProfile(characterId: Int, domain: String, anchorIntent: String): SpecialtyProfileEntity {
        val now = System.currentTimeMillis()
        val profile = SpecialtyProfileEntity(
            id = UUID.randomUUID().toString(),
            characterId = characterId,
            domain = domain,
            anchorIntent = anchorIntent,
            createdAt = now,
            updatedAt = now,
        )
        specialtyProfileDao.insert(profile)
        return profile
    }

    suspend fun setActive(profileId: String, active: Boolean) {
        specialtyProfileDao.setActive(profileId, active)
    }

    /**
     * 漏调用-01 修复配套：供 SpecialtyEvolutionViewModel 在 setActive/deleteProfile 之后
     * 判断"是否已无任何启用中的专长档案"，为空则应停掉每日修炼闹钟
     * （DailyPracticeScheduler.cancel），避免闹钟持续唤醒设备做无用功。
     */
    suspend fun getAllActiveProfiles(): List<SpecialtyProfileEntity> =
        specialtyProfileDao.getAllActiveProfiles()

    /**
     * 彻底删除专长档案及其全部关联记录。
     * 级联范围：evolution_plans / stage_digests 通过 specialtyId 关联，
     * 一并清理；practice_records 及其归档为避免误删用户想保留的创作产出，
     * 默认不在本方法里删除，调用方如确需彻底清空可单独处理。
     *
     * 审查报告问题15修复：此前 evolutionPlanDao 和 specialtyProfileDao 两步
     * 删除不在同一事务内，若第二步失败会留下"进化方案已删但档案仍在"的半删
     * 状态；且注释称级联范围包含 stage_digests，但实现里一直没有清理它。
     * 现用 db.withTransaction 包裹三步操作，保证要么全部成功要么全部回滚，
     * 并补上 stageDigestDao 的清理调用，使实现与注释一致。
     */
    suspend fun deleteProfile(profileId: String) {
        db.withTransaction {
            evolutionPlanDao.deleteAllForSpecialty(profileId)
            stageDigestDao.deleteAllForSpecialty(profileId)
            specialtyProfileDao.deleteById(profileId)
        }
    }

    // ── 成熟度判定（阈值来自 SpecialtyEvolutionConfig，单一来源）──

    fun resolveMaturityStage(practiceCount: Int): String = when {
        practiceCount < 0 -> "EXPLORING" // 防御性：负数视为数据异常，回退到摸索期
        practiceCount <= SpecialtyEvolutionConfig.EXPLORING_MAX_COUNT -> "EXPLORING"
        practiceCount <= SpecialtyEvolutionConfig.FORMING_MAX_COUNT -> "FORMING"
        else -> "STABLE"
    }

    /**
     * 记录一次修炼完成，递增计数并按需更新成熟度阶段。
     * @return 更新后的 maturityStage（供调用方判断是否刚好跨越了阶段边界）
     */
    suspend fun recordPracticeCompleted(profileId: String): String {
        // 方案 2-11：三步包裹在事务中，消除 increment→read→maturity 间的时序竞态。
        return db.withTransaction {
            specialtyProfileDao.incrementPracticeCount(profileId)
            val profile = specialtyProfileDao.getById(profileId) ?: return@withTransaction "EXPLORING"
            val newStage = resolveMaturityStage(profile.practiceCount)
            if (newStage != profile.maturityStage) {
                specialtyProfileDao.updateMaturityStage(profileId, newStage)
            }
            newStage
        }
    }

    // ── 候选观察池：JSON 序列化集中在这一层 ─────────────────────

    /**
     * 候选池 JSON 损坏时抛出的专用异常。
     *
     * 使用自定义类型而非 IllegalStateException，让调用方可以精确 catch，
     * 不会误拦同一调用栈上其他代码抛出的 IllegalStateException。
     */
    class CandidatePoolCorruptedException(
        val corruptedJson: String,
        cause: Throwable,
    ) : Exception("候选池JSON损坏: ${cause.message}", cause)

    /**
     * U-3 修复：候选池 JSON 损坏时的备份日志辅助函数。
     *
     * 只负责记录日志（"备份"），不写 DB——调用方已持有空列表，
     * 后续统一由 updateCandidateObservations 写入，避免双写。
     */
    private fun logCorruptedPool(profileId: String, corruptedJson: String, e: CandidatePoolCorruptedException) {
        ZLog.e(
            TAG,
            "候选池JSON损坏，将以空池继续执行。profileId=$profileId " +
            "原始JSON（可从日志恢复）：$corruptedJson",
            e,
        )
    }

    fun parseCandidateObservations(json: String): MutableList<CandidateObservation> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CandidateObservation(
                    trait = obj.getString("trait"),
                    firstSeenAt = obj.getLong("firstSeenAt"),
                    occurrenceCount = obj.getInt("occurrenceCount"),
                    lastSeenAt = obj.getLong("lastSeenAt"),
                )
            }.toMutableList()
        } catch (e: Throwable) {
            // U-3 修复：抛专用异常，调用方精确 catch 后备份日志 + 以空列表重置。
            // 用 CandidatePoolCorruptedException 而非 IllegalStateException，
            // 避免误拦调用栈上其他代码的 IllegalStateException。
            throw CandidatePoolCorruptedException(
                corruptedJson = json,
                cause         = e,
            )
        }
    }

    fun serializeCandidateObservations(list: List<CandidateObservation>): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("trait", c.trait)
                put("firstSeenAt", c.firstSeenAt)
                put("occurrenceCount", c.occurrenceCount)
                put("lastSeenAt", c.lastSeenAt)
            })
        }
        return arr.toString()
    }

    /**
     * 记录一次新的特征观察。
     *
     * 调用方（DailyPracticeWorker）需先用 LLM 判断"这个新观察到的特征，
     * 是不是候选池里已有某条的另一种表述"（语义匹配，见方案第5.3节），
     * 如果判断为已有候选的重复表述，应传入 matchedExistingTrait 对应的
     * 原始 trait 字符串以便正确递增计数；如果是全新特征，
     * matchedExistingTrait 传 null，作为新条目追加。
     *
     * @param initialCount 本次观察的初始/增量权重，默认为 1（一次正常观察）。
     *   竞赛反哺时传 2，等价于该特征被自然观察到两次——赢一次比赛在转正路径上
     *   有轻微加速效果，但不会绕过阈值直接转正（执行方案第6节·赢家反哺）。
     * @return 更新后该特征的 occurrenceCount，达到转正阈值时调用方据此触发后续流程
     */
    suspend fun recordCandidateObservation(
        profileId: String,
        newTrait: String,
        matchedExistingTrait: String?,
        timestamp: Long = System.currentTimeMillis(),
        initialCount: Int = 1,
    ): Int = getCandidateMutex(profileId).withLock {
        val profile = specialtyProfileDao.getById(profileId) ?: return@withLock 0
        // U-3 修复：parseCandidateObservations 损坏时抛 CandidatePoolCorruptedException；
        // catch 后记录日志（含完整原始 JSON，可从 logcat 恢复），以空列表继续执行。
        // 不在此处单独写 DB——后续 updateCandidateObservations 统一写入空列表，避免双写。
        //
        // 复查说明（C7-#29 审查意见未采纳）：审查报告认为这里"清空后写回"会
        // 不可逆销毁历史候选观察数据，建议改为跳过写入。经核实，这个清空-继续策略
        // 是本项目既有且被明确依赖的设计契约：DailyPracticeWorker.updateCandidatePool
        // 和 CompetitionRoundManager 的赢家反哺逻辑都会在外层预先 catch 同一个
        // CandidatePoolCorruptedException，并且注释明确写"以空池继续、本次观察当全新
        // 候选写入，不会丢失本次观察本身"——如果这里改为跳过写入，会让这两处上游的
        // "保留当次观察"这个前提落空，且它们各自又会因为同一份损坏 JSON 再次触发
        // 同一异常、重复记录日志。候选池只是每日修炼累积的观察计数缓存（可自愈：
        // 未转正的特征会在后续修炼中重新被观察到），不是唯一权威数据源，清零只是让
        // 转正进度倒退，不是真正意义上的数据丢失，保留原有行为。
        val list = try {
            parseCandidateObservations(profile.candidateObservationsJson)
        } catch (e: CandidatePoolCorruptedException) {
            logCorruptedPool(profileId, profile.candidateObservationsJson, e)
            mutableListOf()
        }

        val updatedCount: Int
        if (matchedExistingTrait != null) {
            val existing = list.find { it.trait == matchedExistingTrait }
            if (existing != null) {
                existing.occurrenceCount += initialCount
                existing.lastSeenAt = timestamp
                updatedCount = existing.occurrenceCount
            } else {
                // matchedExistingTrait 指向的条目已不存在（可能已转正被移除），按新条目处理
                list.add(CandidateObservation(newTrait, timestamp, initialCount, timestamp))
                updatedCount = initialCount
            }
        } else {
            list.add(CandidateObservation(newTrait, timestamp, initialCount, timestamp))
            updatedCount = initialCount
        }

        specialtyProfileDao.updateCandidateObservations(profileId, serializeCandidateObservations(list))
        updatedCount
    }

    /** 候选特征转正后，从候选池移除（已并入 styleNotes，不再需要继续计数观察） */
    suspend fun removeCandidateObservation(profileId: String, trait: String) = getCandidateMutex(profileId).withLock {
        val profile = specialtyProfileDao.getById(profileId) ?: return@withLock
        // U-3 修复：同 recordCandidateObservation（见上方复查说明，C7-#29 未采纳）
        val list = try {
            parseCandidateObservations(profile.candidateObservationsJson)
        } catch (e: CandidatePoolCorruptedException) {
            logCorruptedPool(profileId, profile.candidateObservationsJson, e)
            mutableListOf()
        }
        list.removeAll { it.trait == trait }
        specialtyProfileDao.updateCandidateObservations(profileId, serializeCandidateObservations(list))
    }

    // ── 风格说明书直接覆盖写（蒸馏引擎调用） ────────────────────

    suspend fun overwriteStyleNotes(profileId: String, newStyleNotes: String) {
        specialtyProfileDao.updateStyleNotes(profileId, newStyleNotes)
    }

    suspend fun setConflictState(profileId: String, hasConflict: Boolean, description: String) {
        specialtyProfileDao.updateConflictState(profileId, hasConflict, description)
    }

    suspend fun markUserConfirmed(profileId: String) {
        specialtyProfileDao.markUserConfirmed(profileId)
    }

    // ── 进化方案版本管理 ─────────────────────────────────────────

    /**
     * 写入新版本的进化方案。
     * 与 AgentPlanDao.archiveActive 不同：旧版本不会从查询结果中消失，
     * 只是不再是 isActive=true，专长档案页可以翻看完整版本链。
     */
    // P1-6-1 修复：archiveActive → getLatestVersionNumber → insert 三步原先无事务保护。
    // 并发调用（如专长升级与 DistillationWorker 同时触发）时，两个协程均可通过
    // getLatestVersionNumber 读到相同 version，写入相同 version 号的两条记录（重复版本）；
    // 或者 archiveActive 与 insert 中间被另一个协程打断，留下两条 isActive=1 的方案。
    // 用 db.withTransaction 将三步包裹为原子操作，Room 在同一 SQLite 事务内串行执行。
    suspend fun createNewPlanVersion(
        characterId: Int,
        specialtyId: String,
        content: String,
        revisionReason: String,
    ): EvolutionPlanEntity = db.withTransaction {
        evolutionPlanDao.archiveActive(specialtyId)
        val nextVersion = evolutionPlanDao.getLatestVersionNumber(specialtyId) + 1
        val plan = EvolutionPlanEntity(
            id = UUID.randomUUID().toString(),
            characterId = characterId,
            specialtyId = specialtyId,
            version = nextVersion,
            content = content,
            revisionReason = revisionReason,
            isActive = true,
            createdAt = System.currentTimeMillis(),
        )
        evolutionPlanDao.insert(plan)
        plan
    }

    suspend fun getActivePlan(specialtyId: String): EvolutionPlanEntity? =
        evolutionPlanDao.getActivePlan(specialtyId)

    fun observePlanHistory(specialtyId: String): Flow<List<EvolutionPlanEntity>> =
        evolutionPlanDao.observeAllVersions(specialtyId)

    // ── 修炼记录与阶段摘要的只读访问（专长档案页"修炼历程"用）────

    fun observePracticeRecords(specialtyId: String): Flow<List<PracticeRecordEntity>> =
        practiceRecordDao.observeAllForSpecialty(specialtyId)

    fun observeStageDigests(specialtyId: String) =
        stageDigestDao.observeAllForSpecialty(specialtyId)

    suspend fun getArchivedFullContent(recordId: String): String? =
        practiceRecordArchiveDao.getByRecordId(recordId)?.fullContent

    suspend fun markMilestone(recordId: String) {
        practiceRecordDao.markMilestone(recordId)
    }

    // ── 系统建议（仅查询/状态更新，不涉及生成逻辑）───────────────

    fun observePendingSuggestions(specialtyId: String) =
        systemSuggestionDao.observePending(specialtyId)

    suspend fun adoptSuggestion(suggestionId: String) {
        systemSuggestionDao.updateStatus(suggestionId, "ADOPTED")
    }

    suspend fun ignoreSuggestion(suggestionId: String) {
        systemSuggestionDao.updateStatus(suggestionId, "IGNORED")
    }

    companion object {
        private const val TAG = "SpecialtyProfileRepo"
    }
}
