package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.zaijian.zhoumuyun.data.db.entity.ImpersonationPresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImpersonationPresetDao {

    @Upsert
    suspend fun upsert(preset: ImpersonationPresetEntity)

    @Delete
    suspend fun delete(preset: ImpersonationPresetEntity)

    @Query("DELETE FROM impersonation_presets WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("SELECT * FROM impersonation_presets WHERE name = :name LIMIT 1")
    suspend fun get(name: String): ImpersonationPresetEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM impersonation_presets WHERE name = :name)")
    suspend fun exists(name: String): Boolean

    @Query("SELECT * FROM impersonation_presets ORDER BY name ASC")
    suspend fun getAll(): List<ImpersonationPresetEntity>

    @Query("SELECT * FROM impersonation_presets ORDER BY name ASC")
    fun observeAll(): Flow<List<ImpersonationPresetEntity>>
}
