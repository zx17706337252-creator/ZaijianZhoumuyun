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

    /**
     * Phase 30 方案二：监听全部角色的未读结果，新结果写入时立即触发。
     *
     * P2-3-2 修复：原查询无 LIMIT，跨角色聚合会把全表未读行读进内存，
     * 且每条新结果写入都触发一次全量重发。加 LIMIT 上限，未读堆积时
     * 只取最近的一批（按 createdAt 倒序），避免无界增长。
     */
    @Query("SELECT * FROM job_results WHERE isRead = 0 ORDER BY createdAt DESC LIMIT :limit")
    fun observeAllUnread(limit: Int = 200): Flow<List<JobResultEntity>>

    /** Phase 30 方案四：按 jobId 查找最近一条结果（含已读），供今日时间线显示执行状态 */
    @Query("SELECT * FROM job_results WHERE jobId = :jobId ORDER BY createdAt DESC LIMIT 1")
    suspend fun findLatestByJobId(jobId: String): JobResultEntity?

    // ── 方案 4-4：批量查询，消除 TaskViewModel 的 N+1 问题 ──────

    /**
     * 按 jobId 批量取每个 job 最新一条结果。
     *
     * P1-01/窗口0B审查 H-1 修复：原实现 `SELECT * ... GROUP BY jobId HAVING
     * createdAt = MAX(createdAt)` 语义错误——SQLite 里 SELECT * 配合 GROUP BY
     * 时非分组列（这里是 createdAt 以及其他所有列）取自该分组内被任意选中的
     * "代表行"（通常是 rowid 最小的一行），并不是 MAX(createdAt) 对应的那一行。
     * HAVING 里的 createdAt 引用的是代表行的值，几乎不可能恰好等于 MAX(createdAt)，
     * 导致整个分组被 HAVING 过滤掉——实测哪怕只传一个 jobId 也大概率返回空列表。
     *
     * 改为相关子查询：对每个 jobId，只保留 createdAt 等于该 jobId 分组内
     * MAX(createdAt) 的那一行，语义上不依赖 GROUP BY 的代表行选取规则。
     */
    @Query("""
        SELECT * FROM job_results j1
        WHERE j1.jobId IN (:jobIds)
          AND j1.createdAt = (
              SELECT MAX(j2.createdAt) FROM job_results j2 WHERE j2.jobId = j1.jobId
          )
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

    // ── B5 问题2修复：markResultRead 失败重试队列 ──────────────

    /** 云端 markResultRead 失败时调用，标记该条结果待重试 */
    @Query("UPDATE job_results SET cloudMarkReadSynced = 0 WHERE id = :id")
    suspend fun markCloudReadSyncPending(id: String)

    /** 重试成功后调用，清除待重试标记 */
    @Query("UPDATE job_results SET cloudMarkReadSynced = 1 WHERE id = :id")
    suspend fun markCloudReadSynced(id: String)

    /** App 启动时扫描所有未成功同步 is_read 状态到云端的结果，逐条重试 */
    @Query("SELECT * FROM job_results WHERE cloudMarkReadSynced = 0")
    suspend fun findPendingCloudMarkRead(): List<JobResultEntity>
}
