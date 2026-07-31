package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.ChainRunStatus
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.ChainRunRepositoryImpl
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 灵活自动化编排 · Wait 节点延迟唤醒 Worker（§11.4）
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责：Wait 节点把链条状态持久化为 WAITING + wakeAtMs 后，由
 *   [WorkManagerScheduler.enqueueChainResume] 把本 Worker 排入 WorkManager 队列。
 *   到点（或网络约束满足）后系统拉起本 Worker，重新调用 [ChainEngine.advance]
 *   续跑链条——即便 App 进程在等待期间被杀，WorkManager 持久化的 WorkSpec 也会
 *   在到点后重新拉起进程执行，这是"进程可重启后继续跑"这个核心承诺的落地
 *   （Step5 之前用协程 delay()，App 被杀即丢失）。
 *
 * 对照 [WorkflowJobWorker]：结构照抄，换类名与调用目标。CoroutineWorker 由
 *   WorkManager 反射构造，不支持自定义构造参数注入（项目现有两个 Worker 都未引入
 *   WorkerFactory），故 Repository 现场用 [AppDatabase.getInstance] + 各 DAO 构造，
 *   不经由 AppContainer——两份实例无共享状态问题，ChainRunRepositoryImpl 是无状态
 *   DAO 薄封装，数据一致性由数据库层（claimRun 原子锁）保证。
 *
 * 续跑幂等：doWork() 起始处对已终结状态（非 WAITING/RUNNING）直接 success 早退，
 *   防止"上次执行已收敛、本次只是 WorkRequest 还没来得及被 cancelChainResume 清理"
 *   时重复推进。
 * ═══════════════════════════════════════════════════════════════
 */
class ChainResumeWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.success()
        // 对照 WorkflowJobWorker：取不到 runId 用 success 而非 failure——
        // 这种情况只可能是 inputData 本身构造错误（Bug），重试无意义。

        val db = AppDatabase.getInstance(context)
        val chainRunRepository = ChainRunRepositoryImpl(
            chainRunDao = db.chainRunDao(),
            chainDefinitionDao = db.chainDefinitionDao(),
            pendingEventDao = db.pendingEventDao(),
            context = context.applicationContext,
        )

        val run = chainRunRepository.findById(runId) ?: return Result.success()
        if (run.status != ChainRunStatus.WAITING && run.status != ChainRunStatus.RUNNING) {
            // 已终结（COMPLETED/FAILED/CANCELLED）：上次执行已收敛，本次只是
            // WorkRequest 还没来得及被 cancelChainResume 清理，直接返回，不是异常
            return Result.success()
        }

        // §11.9：Provider 未配置检查，对照 WorkflowJobWorker.doWork()
        if (ProviderManager.instance.activeProvider == null) {
            chainRunRepository.markFailed(runId, "未配置可用的 LLM Provider")
            return Result.success()
            // 同 WorkflowJobWorker：success 而非 failure，避免 WorkManager 指数退避
            // 重试——配置问题重试多少次结果都一样。
        }

        // 注意：这里不能传 AppContainer 持有的 appScope——ChainResumeWorker 运行
        // 在系统随时可能回收的短生命周期里，用独立 scope 更安全，不依赖
        // AppContainer 是否已完成初始化。CoroutineWorker 由 WorkManager 反射构造，
        // 与 ZaijianApp.onCreate 的调用顺序无关，doWork() 内部现场
        // AppDatabase.getInstance(context) + DAO 构造 Repository，不依赖
        // AppContainer.instance 是否已 init。
        //
        // 该 scope 仅作接口占位（构造函数要求）：Step5 把 scheduleResume 改为调用
        // WorkManagerScheduler 之后，ProductionChainEngineDeps.scheduleResume 内部不再
        // launch，此 scope 在 ChainResumeWorker 场景下实际不会被用到。
        val deps = ProductionChainEngineDeps(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            chainEngine = ChainEngine,
            repository = chainRunRepository,
            context = context.applicationContext,
        )

        return try {
            if (run.status == ChainRunStatus.WAITING) {
                chainRunRepository.markRunning(runId)
            }
            // RUNNING 态（BootReceiver 恢复场景）不需要 markRunning，已经是 RUNNING
            ChainEngine.advance(runId, chainRunRepository, deps)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e(TAG, "ChainResumeWorker 执行失败 runId=$runId", e)
            // C7-#26 修复：原逻辑直接返回 Result.success()，注释声称异常已被
            // advance() 内部各 handle* 分支的 markFailed 兜底——但这里能捕获到的
            // 恰恰是 advance() 内部兜底覆盖不到的场景（claimRun/releaseLock 等
            // DB 层异常直接从 advance() 逃逸），此时 run 状态仍是 WAITING/RUNNING，
            // 返回 success 会让 run 永久卡死且不再被任何 Worker 触碰。
            //
            // 区分瞬时故障（DB 短暂异常，值得重试）和永久故障（重试次数耗尽后放弃）：
            // 参照本项目 ScheduledJobWorker/CiCdPipelineWorker 的既有模式，用
            // runAttemptCount 限制重试上限，避免 markFailed 本身持续失败（如 DB
            // 已损坏/磁盘写满）时无限 retry 空耗电且永远不收敛。
            try {
                chainRunRepository.markFailed(runId, "ChainResumeWorker 执行异常: ${e.message}")
                Result.success()
            } catch (markFailedError: Throwable) {
                ZLog.e(TAG, "ChainResumeWorker markFailed 兜底也失败 runId=$runId，第 $runAttemptCount 次尝试", markFailedError)
                if (runAttemptCount < MAX_RETRY_COUNT) {
                    Result.retry()
                } else {
                    // 重试耗尽仍无法写入 FAILED 状态：DB 层大概率已不可用，继续 retry
                    // 没有意义。此时 run 会停留在 WAITING/RUNNING 且没有 WorkSpec 再唤醒它，
                    // 需要人工介入排查 DB 状态；返回 failure 而非 success，
                    // 避免掩盖"这次真的没能收敛"这一事实。
                    Result.failure()
                }
            }
        }
    }

    companion object {
        const val KEY_RUN_ID = "runId"
        private const val TAG = "ChainResumeWorker"
        private const val MAX_RETRY_COUNT = 3
    }
}
