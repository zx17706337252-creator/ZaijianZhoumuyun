package com.zaijian.zhoumuyun.data.repository

import android.content.Context
import com.zaijian.zhoumuyun.data.agent.WorkManagerScheduler
import com.zaijian.zhoumuyun.data.db.dao.ChainDefinitionDao
import com.zaijian.zhoumuyun.data.db.dao.ChainRunDao
import com.zaijian.zhoumuyun.data.db.dao.PendingEventDao
import com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunStatus
import com.zaijian.zhoumuyun.data.db.entity.PendingEventEntity
import com.zaijian.zhoumuyun.util.ZLog

/**
 * 灵活自动化编排 · 链条运行仓库生产环境实现
 *
 * 将 [ChainRunDao] / [ChainDefinitionDao] 的 Room 操作封装为 [ChainRunRepository] 接口，
 * 供 ChainEngine.advance() 使用。所有方法都是对 DAO 的薄封装，不包含业务逻辑。
 *
 * 对照 [WorkflowRepository]：同样是对 DAO 的薄封装 + WorkManager 清理逻辑，
 * 但链条系统暂不做云端同步（§11.11 范围决策）。
 *
 * 注意：本类依赖 Room DAO，无法在纯 JVM 环境测试（需要 Robolectric 或 instrumented test）。
 * ChainEngine 的状态机逻辑通过 [FakeChainRunRepository] 做纯 JVM 单测验证。
 */
class ChainRunRepositoryImpl(
    private val chainRunDao: ChainRunDao,
    private val chainDefinitionDao: ChainDefinitionDao,
    private val pendingEventDao: PendingEventDao,
    context: Context,
) : ChainRunRepository {

    // 漏调用-02 修复配套（对照 WorkflowRepository）：持有 applicationContext 而非
    // 调用方传入的 context 本身，避免误传 Activity/Receiver context 导致泄漏。
    private val appContext = context.applicationContext

    override suspend fun findById(runId: String): ChainRunEntity? =
        chainRunDao.findById(runId)

    override suspend fun findDefinition(chainDefId: String): ChainDefinitionEntity? =
        chainDefinitionDao.findById(chainDefId)

    // ── §11.2 数据库级认领锁 ────────────────────────────────

    override suspend fun claimRun(runId: String, claimNow: Long, lockExpiry: Long): Int =
        chainRunDao.claimRun(runId, claimNow, lockExpiry)

    override suspend fun releaseLock(runId: String) =
        chainRunDao.releaseLock(runId)

    // ── §11.7 原子推进 ──────────────────────────────────────

    override suspend fun advanceAtomic(runId: String, newContext: String, newNodeIndex: Int) {
        chainRunDao.advanceAtomic(runId, newContext, newNodeIndex, System.currentTimeMillis())
    }

    // ── §11.6 推进计数 ──────────────────────────────────────

    override suspend fun incrementVisitCount(runId: String) {
        chainRunDao.incrementVisitCount(runId, System.currentTimeMillis())
    }

    // ── 状态流转 ──────────────────────────────────────────

    override suspend fun markWaiting(runId: String, wakeAtMs: Long) {
        chainRunDao.markWaiting(runId, wakeAtMs, System.currentTimeMillis())
    }

    override suspend fun markRunning(runId: String) {
        chainRunDao.markRunning(runId, System.currentTimeMillis())
    }

    override suspend fun markCompleted(runId: String, outcome: String) {
        // outcome 取值 COMPLETED / CANCELLED，直接写入 status 字段
        chainRunDao.finish(runId, outcome, System.currentTimeMillis())
        // §11.8：终态写入后取消可能仍排队的 ChainResumeWorker WorkSpec，对照
        // WorkflowRepository.markCompleted() 末尾的 cancelPendingWork()
        cancelPendingWork(runId)
    }

    override suspend fun markFailed(runId: String, reason: String) {
        // ChainRunEntity 没有 failReason 字段（对照 WorkflowJobEntity 有），
        // 失败原因通过 finishWithContext 写入 context._failReason，便于后续排查。
        // 对照 WorkflowRepository.markFailed() 调用 workflowJobDao.finish(id, FAILED, ...)
        val run = chainRunDao.findById(runId)
        if (run != null) {
            val ctx = try {
                org.json.JSONObject(run.context)
            } catch (e: Exception) {
                org.json.JSONObject()
            }
            ctx.put("_failReason", reason)
            chainRunDao.finishWithContext(runId, ChainRunStatus.FAILED, ctx.toString(), System.currentTimeMillis())
        } else {
            chainRunDao.finish(runId, ChainRunStatus.FAILED, System.currentTimeMillis())
        }
        // §11.8：markFailed 有多个调用点（deadline超时/visitCount超限/占位符缺失/
        // Action异常/Action失败），取消逻辑封装在 Repository 内部可一次覆盖全部调用点，
        // 不需要在 ChainEngine 每个 markFailed 调用点后逐个补一行。
        cancelPendingWork(runId)
    }

    /**
     * 取消 WorkManager 中可能仍排队/等待约束满足的 ChainResumeWorker WorkSpec。
     *
     * 对照 [WorkflowRepository.cancelPendingWork]：健壮性优化，取消失败不影响已写库的
     * 终态数据，不抛异常向上传播。ChainResumeWorker.doWork() 起始处已有"若已终结则
     * 直接 success 早退"的防御，因此即便此处 cancel 因故未生效，也不会造成链条被
     * 重复推进，本调用属于健壮性/省电优化而非纠正数据错误。
     */
    private fun cancelPendingWork(runId: String) {
        try {
            WorkManagerScheduler.cancelChainResume(appContext, runId)
        } catch (e: Throwable) {
            ZLog.w("ChainRunRepositoryImpl", "cancelChainResume 失败 runId=$runId", e)
        }
    }

    // ── §11.3 开机恢复 ──────────────────────────────────────

    override suspend fun findAllByStatus(status: String): List<ChainRunEntity> =
        chainRunDao.findAllByStatus(status)

    // ── §11.10 未播报机制 ────────────────────────────────────

    override suspend fun findUnreported(characterId: Int): List<ChainRunEntity> =
        chainRunDao.findUnreported(characterId)

    override suspend fun markReported(runId: String) =
        chainRunDao.markReported(runId)

    // ── §6 ChainTriggerMatcher：按事件名查询匹配的链条定义 ──────

    override suspend fun findDefinitionsByTriggerEvent(eventName: String): List<ChainDefinitionEntity> =
        chainDefinitionDao.findByTriggerEventEnabled(eventName)

    // ── §11.1 待处理事件持久化 ────────────────────────────

    override suspend fun insertPendingEvent(event: PendingEventEntity) =
        pendingEventDao.insert(event)

    override suspend fun findUnprocessedPendingEvents(): List<PendingEventEntity> =
        pendingEventDao.findUnprocessed()

    override suspend fun markPendingEventProcessed(id: String) =
        pendingEventDao.markProcessed(id)

    // ── 写入 ──────────────────────────────────────────────

    override suspend fun insertRun(run: ChainRunEntity) =
        chainRunDao.insert(run)

    override suspend fun insertDefinition(def: ChainDefinitionEntity) =
        chainDefinitionDao.insert(def)
}
