package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.dao.WorkflowJobDao
import com.zaijian.zhoumuyun.data.db.dao.WorkflowStepResultDao
import com.zaijian.zhoumuyun.data.db.entity.WorkflowJobEntity
import com.zaijian.zhoumuyun.data.db.entity.WorkflowStepResultEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 多步骤工作流系统 · Repository（数据层骨架）
 *
 * Step 1 范围：仅提供创建任务 / 查询 / 状态流转的薄封装，不含任何执行逻辑。
 * Step 2 将新增 WorkflowEngine，编排"执行一步 → 判断完成 → 决定下一步"的循环，
 * 并调用本类的 recordStep() / finish() 落库。
 */
class WorkflowRepository(
    private val db: AppDatabase,
    private val workflowJobDao: WorkflowJobDao,
    private val workflowStepResultDao: WorkflowStepResultDao,
) {

    companion object {
        const val DEFAULT_MAX_STEPS = 8
        const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L  // 10 分钟

        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_TIMEOUT = "TIMEOUT"
    }

    // ── 观察 Flow ─────────────────────────────────────────────

    fun observeJobs(characterId: Int): Flow<List<WorkflowJobEntity>> =
        workflowJobDao.observeByCharacter(characterId)

    // ── 创建任务 ──────────────────────────────────────────────

    /**
     * 创建工作流任务，立即返回任务 ID，状态为 RUNNING。
     * 调用方（Step 4 接入的 ChatViewModel/ToolCallInterceptor）创建后
     * 应立即异步启动引擎，不等待本方法之外的任何阻塞操作。
     */
    suspend fun createJob(
        characterId: Int,
        goal: String,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        workflowJobDao.insert(
            WorkflowJobEntity(
                id = id,
                characterId = characterId,
                goal = goal,
                status = STATUS_RUNNING,
                currentStep = 0,
                maxSteps = maxSteps,
                startedAt = now,
                deadlineAt = now + timeoutMs,
                createdAt = now,
            )
        )
        return id
    }

    // ── 查询 ──────────────────────────────────────────────────

    suspend fun findById(id: String): WorkflowJobEntity? =
        workflowJobDao.findById(id)

    /** WorkManager Worker 续跑时用：找出进程被杀前未跑完的任务 */
    suspend fun findAllRunning(): List<WorkflowJobEntity> =
        workflowJobDao.findAllRunning()

    /** 引擎决策下一步时用：回放该任务已执行的所有步骤结果 */
    suspend fun getStepHistory(jobId: String): List<WorkflowStepResultEntity> =
        workflowStepResultDao.findByJob(jobId)

    /** ChatViewModel 发消息前调用：取出尚未在对话中告知用户的已完成/未完成任务 */
    suspend fun findUnreported(characterId: Int): List<WorkflowJobEntity> =
        workflowJobDao.findUnreported(characterId)

    suspend fun markReported(id: String) =
        workflowJobDao.markReported(id)

    // ── 状态流转（供 Step 2 WorkflowEngine 调用）─────────────────

    /**
     * 记录一步执行结果，并同步推进 currentStep。
     *
     * S2 修复：原本分两步调用两个 DAO，中间进程被杀会导致
     * 步骤已写入但 currentStep 未推进，Worker 续跑时重复执行。
     * 修复后改为调用 AppDatabase.recordStepAtomic()，
     * 两步操作在同一 @Transaction 事务内原子完成。
     */
    suspend fun recordStep(
        jobId: String,
        stepIndex: Int,
        toolName: String?,
        toolParamsJson: String = "{}",
        success: Boolean,
        output: String?,
        errorMessage: String?,
        decidedNextAction: String?,
        startedAt: Long,
        completedAt: Long?,
    ) {
        db.recordStepAtomic(
            stepResult = WorkflowStepResultEntity(
                id = UUID.randomUUID().toString(),
                jobId = jobId,
                stepIndex = stepIndex,
                toolName = toolName,
                toolParamsJson = toolParamsJson,
                success = success,
                output = output,
                errorMessage = errorMessage,
                decidedNextAction = decidedNextAction,
                startedAt = startedAt,
                completedAt = completedAt,
                createdAt = System.currentTimeMillis(),
            ),
            jobId = jobId,
            nextStepIndex = stepIndex + 1,
        )
    }

    /** 目标在限制内完成 */
    suspend fun markCompleted(id: String, resultSummary: String) {
        workflowJobDao.finish(
            id = id,
            status = STATUS_COMPLETED,
            completedAt = System.currentTimeMillis(),
            resultSummary = resultSummary,
            failReason = null,
        )
    }

    /** 达到 maxSteps / deadlineAt 仍未完成：如实呈现已完成部分 + 卡在哪一步 */
    suspend fun markTimeout(id: String, failReason: String) {
        workflowJobDao.finish(
            id = id,
            status = STATUS_TIMEOUT,
            completedAt = System.currentTimeMillis(),
            resultSummary = null,
            failReason = failReason,
        )
    }

    /** 不可恢复错误提前终止 */
    suspend fun markFailed(id: String, failReason: String) {
        workflowJobDao.finish(
            id = id,
            status = STATUS_FAILED,
            completedAt = System.currentTimeMillis(),
            resultSummary = null,
            failReason = failReason,
        )
    }
}
