package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.dao.TaskDao
import com.zaijian.zhoumuyun.data.db.dao.WorldEventDao
import com.zaijian.zhoumuyun.data.db.entity.EventDomain
import com.zaijian.zhoumuyun.data.db.entity.EventType
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskStatus
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.UUID

/**
 * Task Engine Repository（Phase 19）
 *
 * 职责：
 * 1. 任务 CRUD（+ 同步写 WorldEvent）
 * 2. 提供各状态任务的 Flow 给 TaskViewModel
 * 3. TASK_COMPLETED 事件触发 MemoryCandidate 生成（下游 MemoryEngine 订阅）
 *
 * 强制规则：所有任务状态变化必须同时写入 world_events。
 */
class TaskRepository(
    private val db: AppDatabase,
    private val taskDao: TaskDao,
    private val eventDao: WorldEventDao,
) {

    // ── 观察 Flow ────────────────────────────────────────────

    fun observeAll(): Flow<List<TaskEntity>> = taskDao.observeAll()
    fun observeActive(): Flow<List<TaskEntity>> = taskDao.observeActive()
    fun observeCompleted(): Flow<List<TaskEntity>> = taskDao.observeCompleted()
    fun observeFailed(): Flow<List<TaskEntity>> = taskDao.observeFailed()

    /**
     * 观察今日成长任务（source="project_growth"，今天 00:00:00 之后创建）。
     * P1-A TaskCenterScreen 今日Tab成长任务分组使用。
     */
    fun observeGrowthTasksToday(): Flow<List<TaskEntity>> {
        val todayStart = startOfToday()
        return taskDao.observeBySourceAfter(source = "project_growth", after = todayStart)
    }

    // ── 内部工具 ─────────────────────────────────────────────

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    // ── 查询 ────────────────────────────────────────────────

    suspend fun getById(id: String): TaskEntity? = taskDao.getById(id)
    suspend fun getByCharacter(characterId: Int, limit: Int = 20) =
        taskDao.getByCharacter(characterId, limit)
    suspend fun getByProject(projectId: String, limit: Int = 30) =
        taskDao.getByProject(projectId, limit)
    suspend fun getRecentCompleted(limit: Int = 10) = taskDao.getRecentCompleted(limit)

    // ── 创建任务 ─────────────────────────────────────────────

    /**
     * 创建新任务并产生 TASK_CREATED 事件。
     *
     * M9 修复：taskDao.insert() 和 appendTaskEvent() 包在同一事务内，
     * 保证任务行与事件行原子落库——任一步失败均整体回滚，
     * 不会出现任务写入成功但事件丢失（或反之）的状态。
     *
     * @return 新任务的 ID
     */
    suspend fun createTask(
        title: String,
        description: String,
        characterId: Int,
        toolName: String? = null,
        projectId: String? = null,
        source: String = "chat_tool",
        sourceMessageId: String? = null,
    ): String = db.withTransaction {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val task = TaskEntity(
            id              = id,
            title           = title,
            description     = description,
            characterId     = characterId,
            status          = TaskStatus.RUNNING.name,
            progress        = 0f,
            toolName        = toolName,
            resultSummary   = null,
            projectId       = projectId,
            source          = source,
            sourceMessageId = sourceMessageId,
            createdAt       = now,
            updatedAt       = now,
        )
        taskDao.insert(task)

        // 写入 TASK_CREATED 事件
        appendTaskEvent(
            eventType   = EventType.TASK_CREATED,
            taskId      = id,
            characterId = characterId,
            title       = title,
            toolName    = toolName,
            projectId   = projectId,
            importance  = 2,
        )

        id
    }

    // ── 完成任务 ─────────────────────────────────────────────

    /**
     * 标记任务完成，产生 TASK_COMPLETED 事件（触发 MemoryCandidate 生成）。
     */
    suspend fun completeTask(
        id: String,
        resultSummary: String,
        projectId: String? = null,
    ) = db.withTransaction {
        val now = System.currentTimeMillis()
        val task = taskDao.getById(id) ?: return@withTransaction

        taskDao.updateStatus(
            id            = id,
            status        = TaskStatus.COMPLETED.name,
            progress      = 1f,
            resultSummary = resultSummary,
            completedAt   = now,
            updatedAt     = now,
        )

        // 写入 TASK_COMPLETED 事件（importance=3，进入长期记忆）
        appendTaskEvent(
            eventType   = EventType.TASK_COMPLETED,
            taskId      = id,
            characterId = task.characterId,
            title       = task.title,
            toolName    = task.toolName,
            projectId   = projectId ?: task.projectId,
            resultSummary = resultSummary,
            importance  = 3,
        )
    }

    // ── 失败任务 ─────────────────────────────────────────────

    /**
     * 标记任务失败，产生 TASK_FAILED 事件。
     */
    suspend fun failTask(
        id: String,
        errorSummary: String,
    ) = db.withTransaction {
        val now = System.currentTimeMillis()
        val task = taskDao.getById(id) ?: return@withTransaction

        taskDao.updateStatus(
            id            = id,
            status        = TaskStatus.FAILED.name,
            progress      = task.progress,
            resultSummary = errorSummary,
            completedAt   = null,
            updatedAt     = now,
        )

        appendTaskEvent(
            eventType   = EventType.TASK_FAILED,
            taskId      = id,
            characterId = task.characterId,
            title       = task.title,
            toolName    = task.toolName,
            projectId   = task.projectId,
            resultSummary = errorSummary,
            importance  = 2,
        )
    }

    // ── 取消任务 ─────────────────────────────────────────────

    // Fix-13-3：原 cancelTask 只更新状态，缺 appendTaskEvent，其他订阅 WorldEvent 的
    // 引擎（MemoryEngine、WorldSimulation 等）感知不到任务被取消。
    // 修复：包事务 + 补写 TASK_CANCELLED 事件，与 completeTask/failTask 保持一致。
    suspend fun cancelTask(id: String) = db.withTransaction {
        val now = System.currentTimeMillis()
        val task = taskDao.getById(id) ?: return@withTransaction
        taskDao.updateStatus(
            id            = id,
            status        = TaskStatus.CANCELLED.name,
            progress      = task.progress,
            resultSummary = null,
            completedAt   = null,
            updatedAt     = now,
        )
        appendTaskEvent(
            eventType     = EventType.TASK_CANCELLED,
            taskId        = id,
            characterId   = task.characterId,
            title         = task.title,
            toolName      = task.toolName,
            projectId     = task.projectId,
            resultSummary = null,
            importance    = 1,
        )
    }

    // ── 更新进度 ─────────────────────────────────────────────

    suspend fun updateProgress(id: String, progress: Float) {
        taskDao.updateProgress(id, progress, System.currentTimeMillis())
    }

    /**
     * 2.3 工作台任务跟踪修复：更新任务描述/进度备注（task_update 工具用）。
     * 不改变 status（仍是 RUNNING/PENDING），只追加最新进展。
     * description 传空字符串表示只更新 progress，不动原描述。
     */
    suspend fun updateDescription(id: String, description: String, progress: Float) {
        taskDao.updateDescription(id, description, progress, System.currentTimeMillis())
    }

    /**
     * 2.3 工作台任务跟踪修复：按角色查"当前正在进行"的任务（RUNNING/PENDING），
     * 供 task_update/task_complete/task_cancel 在用户/角色没有明确指定 task_id 时，
     * 按标题模糊匹配或回退到"最近一条进行中任务"。
     */
    suspend fun findActiveTask(characterId: Int, titleHint: String? = null): TaskEntity? {
        val active = taskDao.getByCharacter(characterId, limit = 20)
            .filter { it.status == TaskStatus.RUNNING.name || it.status == TaskStatus.PENDING.name }
        if (active.isEmpty()) return null
        if (!titleHint.isNullOrBlank()) {
            active.firstOrNull { it.title.contains(titleHint, ignoreCase = true) }?.let { return it }
        }
        return active.first() // getByCharacter 已按 createdAt DESC 排序，第一条即最近的
    }

    // ── 删除 ─────────────────────────────────────────────────

    suspend fun deleteTask(id: String) {
        taskDao.deleteById(id)
    }

    // ── 内部：写 WorldEvent ──────────────────────────────────

    private suspend fun appendTaskEvent(
        eventType: EventType,
        taskId: String,
        characterId: Int,
        title: String,
        toolName: String?,
        projectId: String?,
        resultSummary: String? = null,
        importance: Int = 2,
    ) {
        val payload = JSONObject().apply {
            put("taskId", taskId)
            put("title", title)
            if (toolName != null) put("toolName", toolName)
            if (resultSummary != null) put("resultSummary", resultSummary.take(200))
        }.toString()

        eventDao.append(
            WorldEventEntity(
                id         = UUID.randomUUID().toString(),
                type       = eventType.name,
                actorId    = characterId.toString(),
                targetId   = "user",
                domain     = EventDomain.WORK.name,
                projectId  = projectId,
                payload    = payload,
                importance = importance,
                createdAt  = System.currentTimeMillis(),
            )
        )
    }
}
