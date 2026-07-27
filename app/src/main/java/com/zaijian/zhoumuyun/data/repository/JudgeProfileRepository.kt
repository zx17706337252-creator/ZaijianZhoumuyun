package com.zaijian.zhoumuyun.data.repository

import androidx.room.withTransaction
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.dao.CompetitionRoundDao
import com.zaijian.zhoumuyun.data.db.dao.JudgeProfileDao
import com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity
import com.zaijian.zhoumuyun.data.db.entity.JudgeProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * 裁判档案 Repository（阶段2 S-1 收尾：JudgeProfileViewModel DI 迁移）。
 *
 * 包装 [JudgeProfileDao]，逐方法透传，不改变任何行为。`JudgeProfileViewModel`
 * 原先裸持有 `db = AppDatabase.getInstance(application)`，通过
 * `db.judgeProfileDao()` / `db.competitionRoundDao()` 直接访问两个 DAO，
 * 现改走此层从 `AppContainer.instance` 取用。
 *
 * 方法集合覆盖 `JudgeProfileDao` 全部接口（不只 JudgeProfileViewModel 用到的
 * observeAllForCharacter/observeById/getById/updateStandardNotes/
 * updateCandidateCorrections），供其他仍裸持有该 DAO 的调用点
 * （`CompetitionRoundManager` 的懒创建逻辑）未来收敛时复用；本次改动本身
 * 只涉及 `JudgeProfileViewModel`，不改动 `CompetitionRoundManager`——后者是
 * Domain/Agent 层持有 DAO 构造依赖的既定模式，不在 S-1"ViewModel 绕过
 * AppContainer"的范围内。
 *
 * `ensureProfile` 的事务语义（查询+插入原子化，配合唯一索引杜绝并发 TOCTOU）
 * 完整保留在此处透传，未做任何改写。
 *
 * `observeRoundsAsJudge` 是 `CompetitionRoundDao` 的方法，但语义上属于
 * "从裁判视角看竞赛轮次"，是 JudgeProfileScreen Section3 的专用数据源
 * （唯一调用方即 JudgeProfileViewModel），因此收纳在此处而非另建
 * CompetitionRoundRepository——后者会牵连 CompetitionViewModel 对
 * `competitionRoundDao()` 的其余裸调用，超出本次 GoalViewModel/
 * JudgeProfileViewModel 迁移范围，留待未来批次统一处理。
 *
 * `db` 仅用于 [confirmCorrection] 内的 `withTransaction`（P2-12 修复的原子性
 * 保护：追加写入 standardNotes + 移除候选池条目两步必须同成同败，避免留下
 * "已写进评判标准但候选池未清"的不一致状态）。ViewModel 迁移前这段事务逻辑
 * 直接写在 `JudgeProfileViewModel.confirmCorrection()` 里，现整体收敛进
 * Repository——事务边界本就该由持久层封装，不应该让 ViewModel 握着 `db`
 * 实例自己开事务。
 */
class JudgeProfileRepository(
    private val db: AppDatabase,
    private val dao: JudgeProfileDao,
    private val competitionRoundDao: CompetitionRoundDao,
) {


    suspend fun insert(profile: JudgeProfileEntity) = dao.insert(profile)

    suspend fun getById(id: String): JudgeProfileEntity? = dao.getById(id)

    fun observeById(id: String): Flow<JudgeProfileEntity?> = dao.observeById(id)

    fun observeAllForCharacter(characterId: Int): Flow<List<JudgeProfileEntity>> =
        dao.observeAllForCharacter(characterId)

    suspend fun getByCharacterAndDomain(characterId: Int, domain: String): JudgeProfileEntity? =
        dao.getByCharacterAndDomain(characterId, domain)

    suspend fun ensureProfile(profile: JudgeProfileEntity): JudgeProfileEntity =
        dao.ensureProfile(profile)

    suspend fun getAllActiveProfiles(): List<JudgeProfileEntity> = dao.getAllActiveProfiles()

    suspend fun incrementJudgeCount(id: String) = dao.incrementJudgeCount(id)

    suspend fun updateMaturityStage(id: String, stage: String) = dao.updateMaturityStage(id, stage)

    suspend fun updateCandidateCorrections(id: String, json: String) =
        dao.updateCandidateCorrections(id, json)

    suspend fun updateStandardNotes(id: String, standardNotes: String) =
        dao.updateStandardNotes(id, standardNotes)

    suspend fun updateConflictState(id: String, hasConflict: Boolean, description: String) =
        dao.updateConflictState(id, hasConflict, description)

    suspend fun setActive(id: String, active: Boolean) = dao.setActive(id, active)

    suspend fun deleteById(id: String) = dao.deleteById(id)

    // ── 裁判视角的竞赛轮次（JudgeProfileScreen Section3）───────────

    fun observeRoundsAsJudge(characterId: Int): Flow<List<CompetitionRoundEntity>> =
        competitionRoundDao.observeRoundsAsJudge(characterId)

    // ── 候选修正池：确认写入（P2-12 事务保护）──────────────────

    /**
     * 确认一条候选修正：将其内容追加写入 standardNotes，然后从候选池移除。
     * 两步写入包在同一事务里，保证原子执行——避免第一条写入成功但第二条
     * 写入失败时，留下"已写入评判标准但候选池未移除"的不一致状态
     * （P2-12 修复，原逻辑等价搬迁自 `JudgeProfileViewModel.confirmCorrection()`）。
     *
     * @param profileId 目标裁判档案 ID
     * @param trait     要写入 standardNotes 的修正内容
     * @param entryText 候选池 JSON 中该条目的 text 字段（用于精确匹配删除）
     * 若 profile 不存在则静默跳过（return@withTransaction），其余异常由调用方 catch。
     */
    suspend fun confirmCorrection(profileId: String, trait: String, entryText: String) {
        db.withTransaction {
            val profile = dao.getById(profileId) ?: return@withTransaction
            // 1. 追加写入 standardNotes
            val newNotes = if (profile.standardNotes.isBlank()) trait.trim()
            else "${profile.standardNotes.trimEnd()}\n${trait.trim()}"
            dao.updateStandardNotes(profileId, newNotes)
            // 2. 从候选池移除该条目
            val updatedJson = removeCandidateEntry(profile.candidateCorrectionsJson, entryText)
            dao.updateCandidateCorrections(profileId, updatedJson)
        }
    }

    /**
     * 拒绝一条候选修正：仅从候选池移除，不写入 standardNotes。
     * 单步写入，无需事务包裹（原逻辑等价搬迁自
     * `JudgeProfileViewModel.declineCorrection()`）。
     */
    suspend fun declineCorrection(profileId: String, entryText: String) {
        val profile = dao.getById(profileId) ?: return
        val updatedJson = removeCandidateEntry(profile.candidateCorrectionsJson, entryText)
        dao.updateCandidateCorrections(profileId, updatedJson)
    }

    /**
     * 从 candidateCorrectionsJson 中移除 text 字段匹配的条目。
     * 格式与 candidateObservationsJson 一致：[{"trait":"...","occurrenceCount":N,...}, ...]
     * 采用 org.json.JSONArray 解析，与 SpecialtyProfileRepository 对齐。
     */
    private fun removeCandidateEntry(json: String, entryText: String): String {
        return try {
            val arr = org.json.JSONArray(json)
            val newArr = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val trait = obj.optString("trait", "")
                if (trait != entryText) newArr.put(obj)
            }
            newArr.toString()
        } catch (_: Throwable) {
            json
        }
    }
}


