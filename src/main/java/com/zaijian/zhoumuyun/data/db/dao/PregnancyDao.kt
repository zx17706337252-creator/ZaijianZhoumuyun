package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zaijian.zhoumuyun.data.db.entity.BirthRecordEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyEntity
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────
//  PregnancyDao
//
//  注意：改为 abstract class 以支持带方法体的 @Transaction 复合操作。
//  completeBirthAtomic() 将"写入生育记录 + 清空怀孕状态"包在同一事务：
//  若进程在两步之间被杀，SQLite WAL 保证同时回滚，不会出现
//  "birth_records 有记录但 pregnancy_state 仍显示 isPregnant=true" 的脏状态。
// ─────────────────────────────────────────────────────────────

@Dao
abstract class PregnancyDao {

    // ── @Transaction 复合操作（原子性保证）────────────────────

    /**
     * 原子完成生育：在同一事务内同时写入 birth_records 并清零 pregnancy_state。
     *
     * 替代 Repository 层的 insertBirthRecord() + upsertPregnancy() 两步调用，
     * 杜绝中途进程被杀导致数据不一致（birth_records 有记录但 isPregnant 仍为 true）。
     */
    @Transaction
    open suspend fun completeBirthAtomic(record: BirthRecordEntity, clearedState: PregnancyEntity) {
        insertBirthRecord(record)
        upsertPregnancy(clearedState)
    }

    // ── 底层单步操作 ──────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPregnancy(state: PregnancyEntity)

    @Query("SELECT * FROM pregnancy_state WHERE characterId = :characterId LIMIT 1")
    abstract suspend fun getPregnancy(characterId: Int): PregnancyEntity?

    @Query("SELECT * FROM pregnancy_state WHERE characterId = :characterId LIMIT 1")
    abstract fun observePregnancy(characterId: Int): Flow<PregnancyEntity?>

    /** 取所有当前怀孕中的角色（供每日/启动检查点扫描是否到期结算） */
    @Query("SELECT * FROM pregnancy_state WHERE isPregnant = 1")
    abstract suspend fun getAllPregnant(): List<PregnancyEntity>

    /** D2.5：更新连续失败次数 */
    @Query("UPDATE pregnancy_state SET consecutiveFailCount = :count WHERE characterId = :characterId")
    abstract suspend fun updateFailCount(characterId: Int, count: Int)

    /** D2.5：更新上次跨周期背景情绪注入时间戳 */
    @Query("UPDATE pregnancy_state SET lastFailureInjectedAt = :ts WHERE characterId = :characterId")
    abstract suspend fun updateLastInjectedAt(characterId: Int, ts: Long)

    /** D2.6：记录流产时间戳（写入后 isPregnant 由 upsertPregnancy 一并清零） */
    @Query("UPDATE pregnancy_state SET miscarriedAt = :ts WHERE characterId = :characterId")
    abstract suspend fun updateMiscarriedAt(characterId: Int, ts: Long)

    /** 怀孕弹窗触发重构：更新"本排卵期窗口是否已弹过同意弹窗"标记 */
    @Query("UPDATE pregnancy_state SET fertileWindowConsentAsked = :asked WHERE characterId = :characterId")
    abstract suspend fun updateFertileWindowConsentAsked(characterId: Int, asked: Boolean)

    @Insert
    abstract suspend fun insertBirthRecord(record: BirthRecordEntity)

    /** 某角色的全部生育记录，按时间倒序 */
    @Query("SELECT * FROM birth_records WHERE characterId = :characterId ORDER BY bornAt DESC")
    abstract fun observeBirthRecords(characterId: Int): Flow<List<BirthRecordEntity>>

    @Query("SELECT * FROM birth_records WHERE characterId = :characterId ORDER BY bornAt DESC")
    abstract suspend fun getBirthRecords(characterId: Int): List<BirthRecordEntity>

    @Query("SELECT COUNT(*) FROM birth_records WHERE characterId = :characterId")
    abstract suspend fun getBirthCount(characterId: Int): Int
}
