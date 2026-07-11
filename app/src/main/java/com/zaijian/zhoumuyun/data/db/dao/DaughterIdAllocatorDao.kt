package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.DaughterIdAllocatorEntity

@Dao
interface DaughterIdAllocatorDao {

    @Query("SELECT * FROM daughter_id_allocator WHERE id = 0")
    suspend fun getRow(): DaughterIdAllocatorEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: DaughterIdAllocatorEntity = DaughterIdAllocatorEntity())

    @Query("UPDATE daughter_id_allocator SET nextId = :nextId WHERE id = 0")
    suspend fun updateNextId(nextId: Int)
}
