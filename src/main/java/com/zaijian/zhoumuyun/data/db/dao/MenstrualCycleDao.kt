package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.MenstrualCycleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MenstrualCycleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MenstrualCycleEntity)

    @Query("SELECT * FROM menstrual_cycle WHERE characterId = :characterId LIMIT 1")
    suspend fun get(characterId: Int): MenstrualCycleEntity?

    @Query("SELECT * FROM menstrual_cycle WHERE characterId = :characterId LIMIT 1")
    fun observe(characterId: Int): Flow<MenstrualCycleEntity?>

    @Query("SELECT * FROM menstrual_cycle")
    suspend fun getAll(): List<MenstrualCycleEntity>

    /** 检查某角色是否已有记录（供 initIfAbsent 用，避免重复初始化覆盖已有锚点） */
    @Query("SELECT COUNT(*) FROM menstrual_cycle WHERE characterId = :characterId")
    suspend fun count(characterId: Int): Int
}
