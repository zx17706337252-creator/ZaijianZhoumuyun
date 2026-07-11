package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.zaijian.zhoumuyun.data.db.entity.CharacterStateEntity
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────
//  CharacterStateDao
//  Phase 4 新增。
//
//  三个操作：
//    observeState  — 监听单角色状态变化（Flow<T?>，不存在返回 null）
//    upsertState   — 插入或更新（INSERT OR REPLACE 语义，@Upsert 处理）
//    resetState    — 删除该角色行；Repository 层 fallback 回 initialState
//
//  不提供 getAll / observeAll，状态是角色级独立的，
//  不需要批量读取——有需要时再加。
// ─────────────────────────────────────────────────────────────

@Dao
interface CharacterStateDao {

    /**
     * 监听指定角色的当前状态。
     * 数据库中不存在该行时发射 null，
     * Repository 层负责 fallback 到 CharacterConfig.initialState。
     */
    @Query("SELECT * FROM character_state WHERE characterId = :id")
    fun observeState(id: Int): Flow<CharacterStateEntity?>

    /**
     * 写入或更新角色状态（INSERT OR REPLACE）。
     * Phase 5 的每次状态变更都通过此方法持久化。
     */
    @Upsert
    suspend fun upsertState(state: CharacterStateEntity)

    /**
     * 删除指定角色的状态行，使其 fallback 回 initialState。
     * 用于「重置角色状态」功能（Phase 5 可选触发点）。
     */
    @Query("DELETE FROM character_state WHERE characterId = :id")
    suspend fun resetState(id: Int)
}
