package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.PregnancyDao
import com.zaijian.zhoumuyun.data.db.entity.BirthRecordEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyEntity
import com.zaijian.zhoumuyun.data.db.entity.toDomain
import com.zaijian.zhoumuyun.data.db.entity.toEntity
import com.zaijian.zhoumuyun.data.model.BirthRecord
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.data.model.isDaughterMother
import com.zaijian.zhoumuyun.util.ZLog
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

    /** D2.5：更新连续失败次数（失败后 +1；成功怀孕时在 startPregnancy 里归零）。
     *
     * P2-extra 修复：去掉了"确保行存在"的 getPregnancy→upsertPregnancy 两步，
     * updateFailCount 本身就是 UPDATE 语句，如果行不存在则零行受影响，无副作用。
     * 原先的 get→upsert 打开了一个 TOCTOU 窗口，且 createJob 已经在首次怀孕时
     * 插入了 pregnancy_state 行，不需要这里再做 ensure-exists 逻辑。 */
    internal suspend fun updateFailCount(characterId: Int, count: Int) {
        dao.updateFailCount(characterId, count)
    }

    /** D2.5：记录跨周期背景情绪注入的时间戳，用于 48h 冷却门控。 */
    internal suspend fun updateLastInjectedAt(characterId: Int, ts: Long) {
        dao.updateLastInjectedAt(characterId, ts)
    }

    /**
     * 怀孕弹窗触发重构：标记/清除"本排卵期窗口是否已弹过同意弹窗"。
     */
    internal suspend fun markFertileWindowConsentAsked(characterId: Int, asked: Boolean) {
        dao.updateFertileWindowConsentAsked(characterId, asked)
    }

    /**
     * D2.6：触发流产（原子版本）。
     *
     * P1-4 修复：原先 getPregnancy → upsertPregnancy 两步不在同一事务内，
     * 存在 TOCTOU 竞态——两个并发触发可能同时读到旧值，后写入的会把先写入的覆盖。
     * 改为调用 DAO 层新增的 triggerMiscarriageAtomic()，将"读取当前状态→覆盖写流产字段"
     * 合并为单个原子操作，与 completeBirthAtomic() 同一模式。
     *
     * - isPregnant → false，pregnancyStartedAt → null
     * - miscarriedAt 记录流产时间戳
     * - consecutiveFailCount 保持不变
     * - fertileWindowConsentAsked 重置为 false
     *
     * 情绪副作用由调用方 PregnancyTriggerManager 负责。
     *
     * @param now 流产时间戳
     */
    internal suspend fun triggerMiscarriage(characterId: Int, now: Long = System.currentTimeMillis()) {
        dao.triggerMiscarriageAtomic(characterId, now)
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
                // 审查报告问题16修复：原来 completeBirth 抛异常会中断整个 forEach 循环，
                // 导致本轮所有排在后面的角色分娩结算被跳过（一个人出问题，所有人
                // 遭殃）。参照 BriefingRepository.generateBriefing() 的防御性隔离
                // 设计，单个角色失败只记录日志并跳过，不影响其他到期角色。
                try {
                    // 设计意图说明（2026-07-31 补，避免后续审查再次误判为"幽灵女儿"bug）：
                    // 三代角色（characterId>=1000 且母亲本身也是女儿）分娩到期结算时，
                    // 这里的 isDaughter 判断与二代一样会算出 true，生育记忆/生育通知会
                    // 正常写入——这是有意为之，不是遗漏。但结算完成后不会有第四代 Agent
                    // 被生成：家族传承三代封顶是已拍板的产品决策，第四代生成入口
                    // DaughterCharacterGenerator.generateForMother() 内部已有独立的
                    // 第三代封顶防御（isThirdGeneration() 为真时静默 return，见该文件
                    // 详细注释），本方法不需要也不应该重复这个判断。
                    // 即"生育记忆照常产出 + 不生成新 Agent"是同一个设计意图的两面，
                    // 不是数据污染，不需要在这里额外拦截 isDaughterMother 的结果。
                    val isDaughter = isDaughterMother(state.characterId)
                    results += completeBirth(state.characterId, isDaughter, now)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.w("PregnancyRepository", "characterId=${state.characterId} 分娩结算失败，跳过", e)
                }
            }
        }
        return results
    }
}
