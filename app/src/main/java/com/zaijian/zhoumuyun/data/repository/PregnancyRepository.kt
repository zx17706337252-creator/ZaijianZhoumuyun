package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.PregnancyDao
import com.zaijian.zhoumuyun.data.db.entity.BirthRecordEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyEntity
import com.zaijian.zhoumuyun.data.db.entity.toDomain
import com.zaijian.zhoumuyun.data.db.entity.toEntity
import com.zaijian.zhoumuyun.data.model.BirthRecord
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.data.model.isDaughterMother
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────
//  PregnancyRepository — D2.5
//
//  新增：
//  - updateFailCount()        更新连续失败次数
//  - updateLastInjectedAt()   更新跨周期背景注入冷却时间戳
//  - startPregnancy() 怀孕成功时同步重置 consecutiveFailCount = 0
// ─────────────────────────────────────────────────────────────

class PregnancyRepository(
    private val dao: PregnancyDao,
) {
    fun observePregnancy(characterId: Int): Flow<PregnancyState> =
        dao.observePregnancy(characterId).map { entity ->
            entity?.toDomain() ?: PregnancyState(characterId = characterId)
        }

    suspend fun getPregnancy(characterId: Int): PregnancyState =
        dao.getPregnancy(characterId)?.toDomain() ?: PregnancyState(characterId = characterId)

    /** 开始怀孕；同步重置 consecutiveFailCount = 0，清零 miscarriedAt。 */
    suspend fun startPregnancy(characterId: Int, now: Long = System.currentTimeMillis()) {
        dao.upsertPregnancy(
            PregnancyState(
                characterId           = characterId,
                isPregnant            = true,
                pregnancyStartedAt    = now,
                consecutiveFailCount  = 0,
                lastFailureInjectedAt = null,
                miscarriedAt          = null,  // D2.6：新怀孕清除上次流产记录
            ).toEntity()
        )
    }

    /** D2.5：更新连续失败次数（失败后 +1；成功怀孕时在 startPregnancy 里归零）。 */
    suspend fun updateFailCount(characterId: Int, count: Int) {
        // 确保行存在（首次可能没有记录），先 upsert 默认行再更新
        val current = dao.getPregnancy(characterId)
        if (current == null) {
            dao.upsertPregnancy(PregnancyState(characterId = characterId).toEntity())
        }
        dao.updateFailCount(characterId, count)
    }

    /** D2.5：记录跨周期背景情绪注入的时间戳，用于 48h 冷却门控。 */
    suspend fun updateLastInjectedAt(characterId: Int, ts: Long) {
        val current = dao.getPregnancy(characterId)
        if (current == null) {
            dao.upsertPregnancy(PregnancyState(characterId = characterId).toEntity())
        }
        dao.updateLastInjectedAt(characterId, ts)
    }

    /**
     * 怀孕弹窗触发重构：标记/清除"本排卵期窗口是否已弹过同意弹窗"。
     * - asked = true  ：用户点击过弹窗按钮（无论同意/拒绝）后调用，本排卵期窗口消费完毕
     * - asked = false ：离开排卵期（CyclePhase 不再是 FERTILE）时调用，为下次排卵期重置
     */
    suspend fun markFertileWindowConsentAsked(characterId: Int, asked: Boolean) {
        val current = dao.getPregnancy(characterId)
        if (current == null) {
            dao.upsertPregnancy(PregnancyState(characterId = characterId).toEntity())
        }
        dao.updateFertileWindowConsentAsked(characterId, asked)
    }

    /**
     * D2.6：触发流产。
     *
     * - isPregnant → false，pregnancyStartedAt → null
     * - miscarriedAt 记录流产时间戳（用于跨周期悲伤余波门控，5 天内生效）
     * - consecutiveFailCount 保持不变（流产不重置失败计数，等待下次排卵期）
     *
     * 情绪副作用（CharacterStateLayer 写入）由调用方 PregnancyTriggerManager 负责。
     *
     * @param now 流产时间戳（测试可注入，默认当前时间）
     */
    suspend fun triggerMiscarriage(characterId: Int, now: Long = System.currentTimeMillis()) {
        val current = dao.getPregnancy(characterId)
            ?: PregnancyState(characterId = characterId).toEntity()
        dao.upsertPregnancy(
            current.copy(
                isPregnant                 = false,
                pregnancyStartedAt         = null,
                miscarriedAt               = now,
                // consecutiveFailCount 保持不变
                // 流产意味着本次排卵期窗口的生命事件已终结，
                // 重置 fertileWindowConsentAsked 为 false，让下一个排卵期窗口可重新弹同意弹窗。
                fertileWindowConsentAsked  = false,
            )
        )
    }

    /**
     * D2.6：生产完成后清算（原子版本）。
     *
     * - isPregnant → false，pregnancyStartedAt → null
     * - consecutiveFailCount → 0（成功生育后清零）
     * - miscarriedAt → null（清除流产记录，生命事件互斥）
     * - 写入 BirthRecordEntity
     *
     * ★ 修复（模块三审查 3-5）：
     *   原实现分两步调用 insertBirthRecord() + upsertPregnancy()，不在同一事务内，
     *   若进程在两步之间被杀，会出现 birth_records 有记录但 pregnancy_state 仍
     *   显示 isPregnant=true 的脏数据。
     *   现改为调用 PregnancyDao.completeBirthAtomic()，两步在同一 @Transaction 内
     *   原子执行，任一步失败均整体回滚。
     *
     * 注意：长期记忆写入（writeEternalMemory）由调用方在此方法返回后执行，
     * 以便调用方可以获取 daughterName 等信息填充记忆内容。
     *
     * @return 写入的 BirthRecord 领域对象
     */
    suspend fun completeBirth(
        characterId: Int,
        isDaughter: Boolean,
        now: Long = System.currentTimeMillis(),
    ): BirthRecord {
        val record = BirthRecordEntity(
            characterId = characterId,
            bornAt      = now,
            isDaughter  = isDaughter,
        )
        val clearedState = PregnancyEntity(
            characterId           = characterId,
            isPregnant            = false,
            pregnancyStartedAt    = null,
            consecutiveFailCount  = 0,
            lastFailureInjectedAt = null,
            miscarriedAt          = null,
        )
        // 原子写入：birth_records 插入 + pregnancy_state 清零，同一事务保证一致性
        dao.completeBirthAtomic(record, clearedState)
        return record.toDomain()
    }

    fun observeBirthRecords(characterId: Int): Flow<List<BirthRecord>> =
        dao.observeBirthRecords(characterId).map { list -> list.map { it.toDomain() } }

    suspend fun getBirthRecords(characterId: Int): List<BirthRecord> =
        dao.getBirthRecords(characterId).map { it.toDomain() }

    suspend fun getBirthCount(characterId: Int): Int = dao.getBirthCount(characterId)

    /**
     * 扫描所有怀孕中的角色，对已到期（第 30 天）的角色结算。
     * D2.6：改为调用 completeBirth 统一处理（含 miscarriedAt 清零）。
     * 长期记忆写入由上层在收到返回值后执行。
     */
    suspend fun settleDueDeliveries(now: Long = System.currentTimeMillis()): List<BirthRecord> {
        val results = mutableListOf<BirthRecord>()
        dao.getAllPregnant().forEach { entity ->
            val state = entity.toDomain()
            if (state.isDueToday(now)) {
                val isDaughter = isDaughterMother(state.characterId)
                results += completeBirth(state.characterId, isDaughter, now)
            }
        }
        return results
    }
}
