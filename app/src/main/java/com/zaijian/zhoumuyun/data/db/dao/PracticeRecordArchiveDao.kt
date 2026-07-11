package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordArchiveEntity

/**
 * PracticeRecordArchive DAO（P6 专长进化系统 · 蒸馏后冷存储）
 *
 * 仅供"专长档案页点开已蒸馏的历史记录查看原文"这一个场景使用，
 * 不参与任何日常业务查询主路径。
 */
@Dao
interface PracticeRecordArchiveDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(archive: PracticeRecordArchiveEntity)

    @Query("SELECT * FROM practice_records_archive WHERE recordId = :recordId LIMIT 1")
    suspend fun getByRecordId(recordId: String): PracticeRecordArchiveEntity?
}
