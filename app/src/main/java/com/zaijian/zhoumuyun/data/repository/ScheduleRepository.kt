package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.agent.ToolResult
import com.zaijian.zhoumuyun.data.db.dao.JobResultDao
import com.zaijian.zhoumuyun.data.db.dao.ScheduledJobDao
import com.zaijian.zhoumuyun.data.db.entity.JobResultEntity
import com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity
import com.zaijian.zhoumuyun.data.remote.CloudJobResult
import com.zaijian.zhoumuyun.data.remote.SupabaseClient
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.UUID

/**
 * Phase 29 · 调度任务 Repository
 *
 * 职责：
 *   1. 创建任务（本地写 + 云端同步）
 *   2. App 启动时从云端拉取已完成结果
 *   3. 本地补跑（云端漏跑时，App 打开后执行）
 */
class ScheduleRepository(
    private val scheduledJobDao: ScheduledJobDao,
    private val jobResultDao: JobResultDao,
) {

    // ── 观察 Flow ─────────────────────────────────────────────

    fun observeJobs(characterId: Int): Flow<List<ScheduledJobEntity>> =
        scheduledJobDao.observeByCharacter(characterId)

    fun observeResults(characterId: Int): Flow<List<JobResultEntity>> =
        jobResultDao.observeByCharacter(characterId)

    fun observeUnreadCount(characterId: Int): Flow<Int> =
        jobResultDao.observeUnreadCount(characterId)

    // ── 创建任务 ──────────────────────────────────────────────

    /**
     * 创建定时任务。
     * 同时写入本地数据库和 Supabase 云端。
     *
     * @return 任务 ID
     */
    suspend fun createJob(
        characterId: Int,
        title: String,
        toolName: String,
        toolParams: Map<String, String>,
        repeatIntervalMs: Long?,
        nextRunAt: Long,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Fix-13-5：原来先云端后本地，云端成功但本地写失败时任务"存在于云但用户看不到"。
        // 改为：先写本地（cloudSynced=false）→ 再同步云端 → 成功则 markCloudSynced。
        // 云端失败时 retryPendingCloudSync 在下次启动时兜底重试。
        scheduledJobDao.insert(
            ScheduledJobEntity(
                id               = id,
                characterId      = characterId,
                title            = title,
                toolName         = toolName,
                toolParamsJson   = JSONObject(toolParams as Map<*, *>).toString(),
                repeatIntervalMs = repeatIntervalMs,
                nextRunAt        = nextRunAt,
                createdAt        = now,
                cloudSynced      = false,
            )
        )

        // 同步云端（失败不阻塞，留 cloudSynced=false，等 retryPendingCloudSync 重试）
        val synced = SupabaseClient.upsertScheduledJob(
            id               = id,
            characterId      = characterId,
            title            = title,
            toolName         = toolName,
            toolParams       = toolParams,
            repeatIntervalMs = repeatIntervalMs,
            nextRunAt        = nextRunAt,
        )
        if (synced) scheduledJobDao.markCloudSynced(id)

        return id
    }

    // ── 云同步失败重试 ────────────────────────────────────────

    /**
     * P1-32：扫描本地所有 cloudSynced = false 的任务，逐个重新调用
     * upsertScheduledJob（本身是 upsert，重试安全）；成功则标记已同步。
     *
     * 调用时机：ZaijianApp.onCreate，每次启动重试一轮。
     */
    suspend fun retryPendingCloudSync() {
        val pending = scheduledJobDao.findUnsyncedJobs()
        for (job in pending) {
            val toolParams: Map<String, String> = try {
                val json = JSONObject(job.toolParamsJson)
                json.keys().asSequence().associateWith { json.getString(it) }
            } catch (e: Exception) {
                emptyMap()
            }

            val synced = SupabaseClient.upsertScheduledJob(
                id               = job.id,
                characterId      = job.characterId,
                title            = job.title,
                toolName         = job.toolName,
                toolParams       = toolParams,
                repeatIntervalMs = job.repeatIntervalMs,
                nextRunAt        = job.nextRunAt,
            )

            if (synced) {
                scheduledJobDao.markCloudSynced(job.id)
            }
        }
    }

    // ── App 启动时：从云端同步已完成结果 ─────────────────────

    /**
     * 从 Supabase 拉取未读的云端执行结果，写入本地数据库。
     * 在 ZaijianApp.onCreate 中调用。
     */
    suspend fun syncCloudResults(characterId: Int) {
        val cloudResults: List<CloudJobResult> =
            SupabaseClient.fetchUnreadResults(characterId)

        for (result in cloudResults) {
            // U-2 修复：先检查本地是否已存在，已存在则跳过 insert，保留用户已读状态
            val existing = jobResultDao.findById(result.id)
            if (existing == null) {
                jobResultDao.insert(
                    JobResultEntity(
                        id           = result.id,
                        jobId        = result.jobId,
                        characterId  = result.characterId,
                        toolName     = result.toolName,
                        status       = result.status,
                        output       = result.output,
                        errorMessage = result.errorMsg,
                        executedBy   = result.executedBy,
                        startedAt    = result.startedAt,
                        completedAt  = result.completedAt,
                        isRead       = false,
                        createdAt    = result.createdAt,
                    )
                )
            }
            // 无论本地是否已存在，均标记云端已读，避免下次重复拉取
            SupabaseClient.markResultRead(result.id)
        }
    }

    // ── 本地补跑 ──────────────────────────────────────────────

    companion object {
        /** H1 修复：与 ScheduledJobWorker.LOCK_TTL_MS 保持一致，共用同一把认领锁 */
        private const val LOCK_TTL_MS = 3 * 60 * 1000L
        private const val TAG = "ScheduleRepository"
    }

    /**
     * 扫描本地所有到期任务并执行（兜底机制）。
     *
     * 调用时机：ZaijianApp.onCreate
     * 作用：如果 Supabase Edge Function 因网络或配额问题未执行，
     *       用户打开 App 时由本地补跑，结果标记为 executedBy = "local"。
     *
     * H1 修复：每条任务执行前先通过 claimJob() 原子抢锁。
     * App 启动瞬间系统可能恰好也唤醒 ScheduledJobWorker 执行同一条任务，
     * 抢锁失败说明 Worker 已认领，跳过即可，彻底消除重复执行风险。
     */
    suspend fun runLocalCompensation() {
        val nowMs = System.currentTimeMillis()
        val dueJobs = scheduledJobDao.findDueJobs(nowMs)

        for (job in dueJobs) {
            // H1 修复：原子认领锁——仅当 lockedUntil 为 null 或已过期时才抢到
            val claimNow = System.currentTimeMillis()
            val lockExpiry = claimNow + LOCK_TTL_MS
            val claimed = scheduledJobDao.claimJob(job.id, claimNow, lockExpiry)
            if (claimed == 0) continue  // Worker 已认领，跳过

            val startedAt = System.currentTimeMillis()
            // P-2 修复：锁释放置于 finally，保证任意异常路径（tool.execute / jobResultDao.insert /
            // updateRunTime 等）都能释放锁，不再依赖 TTL 到期。单任务失败不传播，记录结果后继续循环。
            try {
                // 解析参数
                val baseParams: Map<String, String> = try {
                    val json = JSONObject(job.toolParamsJson)
                    json.keys().asSequence().associateWith { json.getString(it) }
                } catch (e: Exception) {
                    emptyMap()
                }
                // P-8 修复：注入 __character_id，工具执行时优先从 params 读取角色 ID，
                // 避免全局单例闭包读到前台会话角色（ChatViewModel.currentCharacterId）导致串数据。
                val params: Map<String, String> = baseParams + mapOf("__character_id" to job.characterId.toString())

                // 复用现有 AgentToolRegistry，不新增任何工具实现
                val tool = AgentToolRegistry.get(job.toolName)
                val toolResult = if (tool != null) {
                    try {
                        tool.execute(params)
                    } catch (e: Exception) {
                        // P-2 修复：单任务失败不传播（不再 throw），记录失败结果便于 UI 红点提示，继续下一条
                        ZLog.w(TAG, "[runLocalCompensation] 任务执行异常 jobId=${job.id}", e)
                        ToolResult(toolName = job.toolName, success = false, content = "", error = e.message)
                    }
                } else {
                    null
                }

                val now = System.currentTimeMillis()

                // 写入本地执行结果（成功/失败均写 job_result，便于 UI 红点提示）
                jobResultDao.insert(
                    JobResultEntity(
                        id           = UUID.randomUUID().toString(),
                        jobId        = job.id,
                        characterId  = job.characterId,
                        toolName     = job.toolName,
                        status       = if (toolResult?.success == true) "success" else "failed",
                        output       = toolResult?.content,
                        errorMessage = toolResult?.error,
                        executedBy   = "local",
                        startedAt    = startedAt,
                        completedAt  = now,
                        isRead       = false,
                        createdAt    = now,
                    )
                )

                // 更新本地任务的下次执行时间
                if (job.repeatIntervalMs != null) {
                    scheduledJobDao.updateRunTime(
                        id        = job.id,
                        lastRunAt = now,
                        nextRunAt = now + job.repeatIntervalMs,
                    )
                } else {
                    scheduledJobDao.disable(job.id)
                }
            } finally {
                // Fix-LockRelease：无论成功或失败，执行完毕后立即释放锁，
                // 不依赖 TTL 到期（updateRunTime/disable 本身不清锁）。
                scheduledJobDao.releaseLock(job.id)
            }
        }
    }

    // ── 删除任务 ──────────────────────────────────────────────

    /**
     * 删除定时任务（本地 + 云端）。
     *
     * @param id 任务 ID
     */
    suspend fun deleteJob(id: String) {
        // 删本地
        scheduledJobDao.deleteById(id)
        // 同步云端（失败不阻塞）
        SupabaseClient.deleteScheduledJob(id)
    }

    // ── 更新任务 ──────────────────────────────────────────────

    /**
     * 更新定时任务字段（本地 + 云端）。
     */
    suspend fun updateJob(
        id: String,
        title: String,
        toolName: String,
        toolParamsJson: String,
        repeatIntervalMs: Long?,
        nextRunAt: Long,
    ) {
        scheduledJobDao.updateFields(
            id               = id,
            title            = title,
            toolName         = toolName,
            toolParamsJson   = toolParamsJson,
            repeatIntervalMs = repeatIntervalMs,
            nextRunAt        = nextRunAt,
        )
        // 将 toolParamsJson 还原为 Map 用于云端同步
        val toolParams: Map<String, String> = try {
            val json = org.json.JSONObject(toolParamsJson)
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (_: Exception) { emptyMap() }

        SupabaseClient.updateScheduledJob(
            id               = id,
            title            = title,
            toolName         = toolName,
            toolParams       = toolParams,
            repeatIntervalMs = repeatIntervalMs,
            nextRunAt        = nextRunAt,
        )
    }

    // ── 查询单个任务 ───────────────────────────────────────────

    /**
     * 按 ID 查询单个任务，不存在返回 null。
     */
    suspend fun getJob(id: String) = scheduledJobDao.findById(id)

    // ── 列出任务 ──────────────────────────────────────────────

    /**
     * 列出指定角色、在 [beforeMs] 之前将执行的任务。
     *
     * @param characterId  角色 ID
     * @param beforeMs     截止时间戳（只返回 nextRunAt <= beforeMs 的任务）
     * @param enabledOnly  true = 只返回启用中的任务
     */
    suspend fun listJobs(
        characterId: Int,
        beforeMs: Long,
        enabledOnly: Boolean = true,
    ) = scheduledJobDao.findByCharacterBefore(
        characterId = characterId,
        beforeMs    = beforeMs,
        enabledOnly = enabledOnly,
    )

    // ── 已读标记 ──────────────────────────────────────────────

    suspend fun markResultRead(resultId: String) {
        jobResultDao.markRead(resultId)
    }

    suspend fun markAllResultsRead(characterId: Int) {
        jobResultDao.markAllRead(characterId)
    }
}
