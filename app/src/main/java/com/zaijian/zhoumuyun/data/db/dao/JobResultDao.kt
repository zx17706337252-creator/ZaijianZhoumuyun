package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.*
import com.zaijian.zhoumuyun.data.db.entity.JobResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JobResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: JobResultEntity)

    @Query("SELECT * FROM job_results WHERE characterId = :characterId ORDER BY createdAt DESC LIMIT :limit")
    fun observeByCharacter(characterId: Int, limit: Int = 50): Flow<List<JobResultEntity>>

    @Query("SELECT * FROM job_results WHERE characterId = :characterId AND isRead = 0 ORDER BY createdAt DESC")
    suspend fun findUnread(characterId: Int): List<JobResultEntity>

    /** Phase 30 方案二：监听全部角色的未读结果，新结果写入时立即触发 */
    @Query("SELECT * FROM job_results WHERE isRead = 0 ORDER BY createdAt DESC")
    fun observeAllUnread(): Flow<List<JobResultEntity>>

    /** Phase 30 方案四：按 jobId 查找最近一条结果（含已读），供今日时间线显示执行状态 */
    @Query("SELECT * FROM job_results WHERE jobId = :jobId ORDER BY createdAt DESC LIMIT 1")
    suspend fun findLatestByJobId(jobId: String): JobResultEntity?

    // ── 方案 4-4：批量查询，消除 TaskViewModel 的 N+1 问题 ──────

    @Query("""
        SELECT * FROM job_results
        WHERE jobId IN (:jobIds)
        GROUP BY jobId
        HAVING createdAt = MAX(createdAt)
    """)
    suspend fun findLatestByJobIds(jobIds: List<String>): List<JobResultEntity>

    /** U-2 修复：按主键 id 查找记录，供 syncCloudResults 幂等检查用 */
    @Query("SELECT * FROM job_results WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): JobResultEntity?

    // 第8窗口问题3修复：批量按主键 id 查找，供 syncCloudResults 消除 N+1——
    // 原先对云端结果列表逐条 findById，改为一次 IN 查询取回所有已存在的 id。
    @Query("SELECT * FROM job_results WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<JobResultEntity>

    @Query("UPDATE job_results SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE job_results SET isRead = 1 WHERE characterId = :characterId")
    suspend fun markAllRead(characterId: Int)

    @Query("SELECT COUNT(*) FROM job_results WHERE characterId = :characterId AND isRead = 0")
    fun observeUnreadCount(characterId: Int): Flow<Int>
}
