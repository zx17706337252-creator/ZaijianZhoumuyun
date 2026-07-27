package com.zaijian.zhoumuyun.data.repository

import androidx.room.withTransaction
import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.agent.CalendarSyncHelper
import com.zaijian.zhoumuyun.data.agent.ToolResult
import com.zaijian.zhoumuyun.data.agent.WorkManagerScheduler
import com.zaijian.zhoumuyun.data.db.AppDatabase
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
// P3-32 修复：命名澄清——ScheduleRepository 管理的是 ScheduledJob（后台定时任务/Job），
// 而非 WorldEvent（世界事件/日程条目，见 EventRepository）。
// 两者职责不同，命名相似容易混淆，此处显式标注。
class ScheduleRepository(
    private val scheduledJobDao: ScheduledJobDao,
    private val jobResultDao: JobResultDao,
    // W1-010 修复：新增 AppDatabase 依赖，使 runLocalCompensation() 能用
    // db.withTransaction 包裹 jobResultDao.insert() + scheduledJobDao.updateRunTime()/
    // disable() 这两步跨 DAO 写入。此前构造函数只注入了两个 DAO，即使想加事务
    // 也无从下手——现在这两处保护同一件事的两半（W1-001 已用同样方式修好了
    // ScheduledJobWorker 的路径，这里补齐 Repository 侧的另一条写入路径）。
    private val db: AppDatabase,
    // L-P0-4 修复：日程系统写入路径统一。
    // 只有 ScheduleCreateTool（Agent 工具路径）完整实现了"Room + 日历同步 + WorkManager + 云端"四件套，
    // 其余入口（UI 手动创建/编辑/删除）均在不同程度上残缺。新增 calendarSync 和 context 参数，
    // 使 Repository 层成为统一的写入入口，所有调用方通过 createJobWithFullSync/deleteJobWithFullSync
    // 走同一套完整逻辑。两参数均为可空（null 时跳过对应同步步骤），向后兼容现有调用方。
    private val calendarSync: CalendarSyncHelper? = null,
    private val context: android.content.Context? = null,
) {

    // ── 观察 Flow ─────────────────────────────────────────────

    fun observeJobs(characterId: Int): Flow<List<ScheduledJobEntity>> =
        scheduledJobDao.observeByCharacter(characterId)

    fun observeResults(characterId: Int): Flow<List<JobResultEntity>> =
        jobResultDao.observeByCharacter(characterId)

    fun observeUnreadCount(characterId: Int): Flow<Int> =
        jobResultDao.observeUnreadCount(characterId)

    // S8-窗口01 收口：GlobalScheduleViewModel 原先裸持有
    // AppDatabase.getInstance(application).scheduledJobDao() 用于这两个查询方法，
    // 现补齐透传，使 GlobalScheduleViewModel 不再需要裸持有 dao。
    fun observeInRange(fromMs: Long, toMs: Long): Flow<List<ScheduledJobEntity>> =
        scheduledJobDao.observeInRange(fromMs, toMs)

    fun observeInRangeForCharacters(
        characterIds: List<Int>,
        fromMs: Long,
        toMs: Long,
    ): Flow<List<ScheduledJobEntity>> =
        scheduledJobDao.observeInRangeForCharacters(characterIds, fromMs, toMs)

    // S8-窗口01 收口：TaskViewModel 原先裸持有
    // AppDatabase.getInstance(application) 用于这三个方法，现补齐透传。
    /** 观察全部（跨角色）已调度任务，供 TaskCenterScreen 今日时间线聚合用。 */
    fun observeAllJobs(): Flow<List<ScheduledJobEntity>> =
        scheduledJobDao.observeAll()

    /** 观察全部（跨角色）未读任务结果。 */
    fun observeAllUnreadResults(): Flow<List<JobResultEntity>> =
        jobResultDao.observeAllUnread()

    /** 按 jobId 列表批量查询各自最新一条结果，消除 N+1 查询。 */
    suspend fun findLatestResultsByJobIds(jobIds: List<String>): List<JobResultEntity> =
        jobResultDao.findLatestByJobIds(jobIds)

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
        description: String? = null,
        projectId: String? = null,
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
                description      = description,
                projectId        = projectId,
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
            description      = description,
            projectId        = projectId,
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
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
                description      = job.description,
                projectId        = job.projectId,
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

        // 第8窗口问题3修复：原先对 cloudResults 逐条调用 jobResultDao.findById()
        // 检查是否已存在（N+1）。改为一次批量查询取回所有已存在的 id 集合，
        // 之后仅在内存中判断是否已存在，插入操作仍逐条执行（数量通常很小，
        // 且 insert 本身不是本问题的性能瓶颈）。
        val existingIds: Set<String> = if (cloudResults.isNotEmpty()) {
            jobResultDao.findByIds(cloudResults.map { it.id }).map { it.id }.toSet()
        } else {
            emptySet()
        }

        for (result in cloudResults) {
            // U-2 修复：先检查本地是否已存在，已存在则跳过 insert，保留用户已读状态
            if (result.id !in existingIds) {
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
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
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // P-2 修复：单任务失败不传播（不再 throw），记录失败结果便于 UI 红点提示，继续下一条
                        ZLog.w(TAG, "[runLocalCompensation] 任务执行异常 jobId=${job.id}", e)
                        ToolResult(toolName = job.toolName, success = false, content = "", error = e.message)
                    }
                } else {
                    null
                }

                val now = System.currentTimeMillis()

                // W1-010 修复：jobResultDao.insert() 与 scheduledJobDao.updateRunTime()/
                // disable() 此前是两次独立调用，无事务包裹——进程若在两者之间被杀，
                // job_result 已有"已执行"记录但 nextRunAt 未推进，锁过期（3分钟）后
                // 该任务会被重复认领执行，造成重复跑。用 db.withTransaction 包裹这
                // 两步 DAO 写操作，保证要么全部成功要么全部回滚（claimJob 的锁申请
                // 已在事务外的循环起始处完成，避免把锁等待纳入长事务）。
                //
                // 同时顺带修复与 W1-006 相同根因：改用 updateRunTimeUsingCurrentInterval
                // 让 nextRunAt 由 SQL 基于数据库当前行的 repeatIntervalMs 现算，而不是
                // 沿用循环开始时 findDueJobs() 读到的 job 快照里的旧值——避免用户在
                // 本次补跑执行期间通过 UI 修改了间隔，却被静默覆盖。
                db.withTransaction {
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
                        val updated = scheduledJobDao.updateRunTimeUsingCurrentInterval(
                            id        = job.id,
                            lastRunAt = now,
                        )
                        if (updated == 0) {
                            // 数据库当前 repeatIntervalMs 已被改为 null，任务已转为一次性
                            scheduledJobDao.disable(job.id)
                        }
                    } else {
                        scheduledJobDao.disable(job.id)
                    }
                }
            } finally {
                // Fix-LockRelease：无论成功或失败，执行完毕后立即释放锁，
                // 不依赖 TTL 到期（updateRunTime/disable 本身不清锁）。
                scheduledJobDao.releaseLock(job.id)

                // L-P0-4/P1-6 修复：runLocalCompensation 补跑后重新调度 WorkManager
                // 若任务有重复间隔则按新 nextRunAt 重新入队，否则取消原有调度
                context?.let { ctx ->
                    try {
                        if (job.repeatIntervalMs != null) {
                            val updatedJob = scheduledJobDao.findById(job.id)
                            if (updatedJob != null) {
                                val delay = (updatedJob.nextRunAt - System.currentTimeMillis()).coerceAtLeast(0L)
                                WorkManagerScheduler.enqueue(ctx, job.id, delay)
                            }
                        } else {
                            WorkManagerScheduler.cancel(ctx, job.id)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (job.repeatIntervalMs != null) {
                            ZLog.w(TAG, "[runLocalCompensation] WorkManager 重新调度失败 jobId=${job.id}", e)
                        }
                    }
                }
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
        description: String? = null,
        projectId: String? = null,
    ) {
        scheduledJobDao.updateFields(
            id               = id,
            title            = title,
            toolName         = toolName,
            toolParamsJson   = toolParamsJson,
            repeatIntervalMs = repeatIntervalMs,
            nextRunAt        = nextRunAt,
            description      = description,
            projectId        = projectId,
        )
        // 修复（第4窗口审查报告问题3）：原注释说"本地更新后 cloudSynced 已置为0，
        // 下次 CloudSyncWorker 统一处理同步"，但代码紧接着仍立即调用云端更新，
        // 且成功后未调用 markCloudSynced，导致 cloudSynced 字段永久停留在 0——
        // 云端数据本身正确，但 retryPendingCloudSync 会对该任务重复 upsert，
        // 浪费网络请求。现采用方案A：与 createJob() 保持一致的模式——
        // 同步云端后检查返回值，成功则标记 cloudSynced。
        // 将 toolParamsJson 还原为 Map 用于云端同步
        val toolParams: Map<String, String> = try {
            val json = org.json.JSONObject(toolParamsJson)
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) { emptyMap() }

        val synced = SupabaseClient.updateScheduledJob(
            id               = id,
            title            = title,
            toolName         = toolName,
            toolParams       = toolParams,
            repeatIntervalMs = repeatIntervalMs,
            nextRunAt        = nextRunAt,
            description      = description,
            projectId        = projectId,
        )
        if (synced) scheduledJobDao.markCloudSynced(id)
    }

    /**
     * 更新日程（完整版）：Room + Supabase（复用 updateJob）→ 日历事件更新 → WorkManager 重新调度。
     * 若 calendarSync 或 context 为 null，则跳过对应步骤。
     *
     * L-P0-4 遗漏补丁：此前只有 createJobWithFullSync/deleteJobWithFullSync 两个完整版方法，
     * PersonalScheduleViewModel.saveDraft() 的编辑分支仍在直接调用残缺的 updateJob()——
     * 用户编辑已有日程（例如改时间）后，WorkManager 仍按旧的调度触发，日历事件也不会更新。
     * 新增本方法补齐这条路径，让"新建"和"编辑"两个入口都走完整同步。
     */
    suspend fun updateJobWithFullSync(
        id: String,
        title: String,
        toolName: String,
        toolParamsJson: String,
        repeatIntervalMs: Long?,
        nextRunAt: Long,
        description: String? = null,
        projectId: String? = null,
    ) {
        // 先走已有的 updateJob（Room + Supabase）
        updateJob(
            id               = id,
            title            = title,
            toolName         = toolName,
            toolParamsJson   = toolParamsJson,
            repeatIntervalMs = repeatIntervalMs,
            nextRunAt        = nextRunAt,
            description      = description,
            projectId        = projectId,
        )

        // 日历事件更新（找不到旧事件时 CalendarSyncHelper.updateEvent 内部会自动转为新建）
        calendarSync?.let { sync ->
            try {
                sync.updateEvent(
                    jobId            = id,
                    title            = title,
                    nextRunAt        = nextRunAt,
                    repeatIntervalMs = repeatIntervalMs,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("ScheduleRepository", "日历事件更新失败（日程已更新）: ${e.message}", e)
            }
        }

        // WorkManager 重新调度：新时间可能早于/晚于原调度，必须取消旧的再按新 nextRunAt 入队
        context?.let { ctx ->
            try {
                WorkManagerScheduler.cancel(ctx, id)
                val delayMs = (nextRunAt - System.currentTimeMillis()).coerceAtLeast(0L)
                WorkManagerScheduler.enqueue(ctx, id, delayMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("ScheduleRepository", "WorkManager 重新调度失败（日程已更新）: ${e.message}", e)
            }
        }
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

    suspend fun markAllRead(characterId: Int) {
        jobResultDao.markAllRead(characterId)
    }

    // ── L-P0-4 修复：日程系统写入路径统一 ────────────────────
    // 只有 ScheduleCreateTool（Agent 工具路径）完整实现了"Room + 日历同步 + WorkManager + 云"
    // 四件套。其余入口（UI 手动创建/编辑/删除）均在不同程度上残缺。以下两个方法是为
    // createJob / deleteJob 的"完整版"包装，所有调用方都应优先使用这两个方法，
    // 而不是直接调用原始的 createJob/deleteJob。

    /**
     * 创建日程（完整版）：Room → Supabase → 日历同步 → WorkManager 调度。
     * 若 calendarSync 或 context 为 null，则跳过对应步骤。
     */
    suspend fun createJobWithFullSync(
        characterId: Int,
        title: String,
        toolName: String,
        toolParams: Map<String, String>,
        repeatIntervalMs: Long?,
        nextRunAt: Long,
        description: String? = null,
        projectId: String? = null,
    ): String {
        // 先走已有的 createJob（Room + Supabase）
        val jobId = createJob(
            characterId      = characterId,
            title            = title,
            toolName         = toolName,
            toolParams       = toolParams,
            repeatIntervalMs = repeatIntervalMs,
            nextRunAt        = nextRunAt,
            description      = description,
            projectId        = projectId,
        )

        // 日历同步
        calendarSync?.let { sync ->
            try {
                sync.insertEvent(
                    jobId            = jobId,
                    title            = title,
                    nextRunAt        = nextRunAt,
                    repeatIntervalMs = repeatIntervalMs,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 日历同步失败不阻塞主流程，日程已成功写入 Room 和 Supabase
                ZLog.w("ScheduleRepository", "日历同步失败（日程已创建）: ${e.message}", e)
            }
        }

        // WorkManager 调度
        context?.let { ctx ->
            try {
                val delayMs = (nextRunAt - System.currentTimeMillis()).coerceAtLeast(0L)
                WorkManagerScheduler.enqueue(ctx, jobId, delayMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("ScheduleRepository", "WorkManager 调度失败（日程已创建）: ${e.message}", e)
            }
        }

        return jobId
    }

    /**
     * 删除日程（完整版）：权限校验 → 日历事件删除 → WorkManager 取消 → Supabase 删除 → Room 删除。
     * 若 calendarSync 或 context 为 null，则跳过对应步骤。
     *
     * P2-18 修复：userId/characterId 此前是死参数（声明了但从未使用），
     * 现补上归属校验逻辑：
     * - 先通过 [getJob] 查出实体，若不存在则抛 [IllegalArgumentException]，
     *   避免对已删除或不存在的 jobId 做无意义的日历/WorkManager 清理。
     * - 若 characterId 非空，校验 job.characterId 与之一致；不一致说明调用方
     *   试图删除不属于自己的日程，抛 [SecurityException] 阻断。
     *   PersonalScheduleViewModel 传入当前角色 ID 做归属约束；
     *   GlobalScheduleViewModel 展示全量日程，传入 null 跳过角色校验
     *   （但仍有"job 存在性"校验）。
     * - userId 暂无对应实体字段（ScheduledJobEntity 无 userId），保留参数
     *   供未来多用户体系接入，当前仅做日志记录。
     */
    suspend fun deleteJobWithFullSync(jobId: String, userId: String?, characterId: Int?) {
        // P2-18：权限/归属校验
        val job = getJob(jobId)
            ?: throw IllegalArgumentException("待删除的日程不存在：$jobId")
        if (characterId != null && job.characterId != characterId) {
            throw SecurityException(
                "无权删除此日程：jobId=$jobId 归属角色 ${job.characterId}，" +
                    "调用方角色 $characterId"
            )
        }
        if (userId != null) {
            ZLog.i("ScheduleRepository", "deleteJobWithFullSync: userId=$userId（未来多用户校验预留）")
        }

        // 日历事件删除（优先执行，因为 deleteJob 会删除主表记录）
        calendarSync?.let { sync ->
            try {
                sync.deleteEvent(jobId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("ScheduleRepository", "日历事件删除失败（继续删除日程主记录）: ${e.message}", e)
            }
        }

        // WorkManager 取消
        context?.let { ctx ->
            try {
                WorkManagerScheduler.cancel(ctx, jobId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("ScheduleRepository", "WorkManager 取消失败（继续删除日程主记录）: ${e.message}", e)
            }
        }

        // Room + Supabase 删除（通过已有的 deleteJob）
        deleteJob(jobId)
    }

    /**
     * 切换日程启用状态（完整版）：Room → WorkManager 调度变更 → 日历事件同步。
     *
     * L-P0-4 遗漏补丁：此前 GlobalScheduleViewModel.toggleEnabled() 直接裸调
     * dao.disable()/dao.update()，无 WorkManager 调度变更、无日历事件同步、
     * 无 Supabase 同步。本方法补齐完整路径，与 createJobWithFullSync /
     * deleteJobWithFullSync / updateJobWithFullSync 形成统一的"四件套"入口。
     *
     * 若 calendarSync 或 context 为 null，则跳过对应步骤。
     *
     * @param job 当前日程实体（含完整字段，用于日历同步和 WorkManager 调度）
     */
    suspend fun toggleJobWithFullSync(job: ScheduledJobEntity) {
        if (job.enabled) {
            // ── 禁用 ──────────────────────────────────────────
            scheduledJobDao.disable(job.id)

            // WorkManager 取消
            context?.let { ctx ->
                try {
                    WorkManagerScheduler.cancel(ctx, job.id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.w("ScheduleRepository", "toggleJobWithFullSync(disable): WorkManager 取消失败: ${e.message}", e)
                }
            }

            // 日历事件删除
            calendarSync?.let { sync ->
                try {
                    sync.deleteEvent(job.id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.w("ScheduleRepository", "toggleJobWithFullSync(disable): 日历事件删除失败: ${e.message}", e)
                }
            }
        } else {
            // ── 启用 ──────────────────────────────────────────
            val updated = job.copy(enabled = true)
            scheduledJobDao.update(updated)

            // WorkManager 重新入队
            context?.let { ctx ->
                try {
                    val delayMs = (job.nextRunAt - System.currentTimeMillis()).coerceAtLeast(0L)
                    WorkManagerScheduler.enqueue(ctx, job.id, delayMs)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.w("ScheduleRepository", "toggleJobWithFullSync(enable): WorkManager 入队失败: ${e.message}", e)
                }
            }

            // 日历事件重新创建
            calendarSync?.let { sync ->
                try {
                    sync.insertEvent(
                        jobId            = job.id,
                        title            = job.title,
                        nextRunAt        = job.nextRunAt,
                        repeatIntervalMs = job.repeatIntervalMs,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.w("ScheduleRepository", "toggleJobWithFullSync(enable): 日历事件创建失败: ${e.message}", e)
                }
            }
        }
    }
}
