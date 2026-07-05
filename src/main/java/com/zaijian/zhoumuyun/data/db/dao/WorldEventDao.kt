package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun append(event: WorldEventEntity)

    @Query("SELECT * FROM world_events ORDER BY createdAt DESC LIMIT :limit")
    suspend fun queryLatest(limit: Int = 50): List<WorldEventEntity>

    @Query("SELECT * FROM world_events WHERE actorId = :actorId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun queryByActor(actorId: String, limit: Int = 50): List<WorldEventEntity>

    @Query("SELECT * FROM world_events WHERE domain = :domain ORDER BY createdAt DESC LIMIT :limit")
    suspend fun queryByDomain(domain: String, limit: Int = 50): List<WorldEventEntity>

    /** Phase 10 Project Engine 使用 */
    @Query("SELECT * FROM world_events WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun queryByProject(projectId: String, limit: Int = 50): List<WorldEventEntity>

    @Query("SELECT * FROM world_events WHERE type = :type ORDER BY createdAt DESC LIMIT :limit")
    suspend fun queryByType(type: String, limit: Int = 50): List<WorldEventEntity>

    /** 实时观察最近事件（Timeline 用） */
    @Query("SELECT * FROM world_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeLatest(limit: Int = 30): Flow<List<WorldEventEntity>>

    @Query("SELECT COUNT(*) FROM world_events")
    suspend fun count(): Int
}
