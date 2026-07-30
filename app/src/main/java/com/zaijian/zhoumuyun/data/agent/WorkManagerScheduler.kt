package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object WorkManagerScheduler {

    fun enqueue(context: Context, jobId: String, delayMs: Long) {
        val inputData = Data.Builder()
            .putString(ScheduledJobWorker.KEY_JOB_ID, jobId)
            .build()

        val delay = delayMs.coerceAtLeast(0L)

        // 性能 M1 修复：ScheduledJobWorker 内部通过 AgentToolRegistry 执行工具，
        // 绝大多数工具都会调用 LLM（网络请求）。无网时直接执行只会立刻失败并触发
        // 指数退避重试，徒耗电量。加网络约束后系统等有网再唤醒，与 WorkflowJobWorker
        // / scheduleProactiveMessageCheck 保持一致。
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ScheduledJobWorker>()
            .setInputData(inputData)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS,
            )
            .setConstraints(constraints)
            .addTag(jobId)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            jobId,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context, jobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(jobId)
    }

    private fun workflowUniqueName(jobId: String) = "workflow_$jobId"

    fun enqueueWorkflow(context: Context, jobId: String) {
        val inputData = Data.Builder()
            .putString(WorkflowJobWorker.KEY_JOB_ID, jobId)
            .build()

        // 性能 M1 修复：WorkflowJobWorker 内部依赖 ProviderManager.activeProvider 调用 LLM，
        // 无网络时直接判 markFailed，而非等待。加约束后系统会等有网再唤醒，避免空跑浪费一次机会。
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<WorkflowJobWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag(workflowUniqueName(jobId))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workflowUniqueName(jobId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelWorkflow(context: Context, jobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workflowUniqueName(jobId))
    }

    // ── 灵活自动化编排 · ChainResumeWorker 延迟唤醒（§11.4）──────────────
    //
    // 对照上方 enqueue/enqueueWorkflow 两段：本段为 Wait 节点的"进程可重启后续跑"
    // 调度入口。ChainResumeWorker 到点后重新调用 ChainEngine.advance() 续跑链条。
    //
    // 策略选择 ExistingWorkPolicy.REPLACE（而非照抄 enqueueWorkflow 的 KEEP）：
    // 同一个 runId 会在链条生命周期里被多次调度——第一个 Wait 节点 scheduleResume
    // 后唤醒续跑，走到第二个 Wait 节点又 scheduleResume。更关键的是 BootReceiver
    // 恢复 RUNNING 态时会调用 enqueueChainResume(runId, delayMs=0) 做"立即恢复"，
    // 若此时该 runId 因上一个 Wait 仍残留一个未来才触发的旧 WorkSpec（§5.5 的
    // cancelChainResume 清理与本次入队之间存在竞态窗口），KEEP 会让开机恢复的
    // "立即执行"请求被旧的"未来执行"请求挡住，恢复失效。REPLACE"以最新一次调度
    // 为准"直接消除这一竞态类别，成本是极端情况下同一 runId 重复入队两次只有最后
    // 一次生效——这正是期望行为。

    private fun chainResumeUniqueName(runId: String) = "chain_resume_$runId"

    fun enqueueChainResume(context: Context, runId: String, delayMs: Long) {
        // 网络约束：与 enqueueWorkflow 同一理由——ChainResumeWorker 内部的 Action
        // 节点会调用 WorkflowEngine.run()，需要 LLM 网络请求；Wait/Check-only 场景
        // 虽不一定需要网络，但入队时无法预知本次唤醒会推进到哪类节点，统一加约束是
        // 安全默认值，代价是"纯 Check 节点续跑"也要等网络恢复，可接受。
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ChainResumeWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ChainResumeWorker.KEY_RUN_ID to runId))
            .setConstraints(constraints)
            .addTag(chainResumeUniqueName(runId))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            chainResumeUniqueName(runId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelChainResume(context: Context, runId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(chainResumeUniqueName(runId))
    }

    private const val PROACTIVE_WORK_NAME = "proactive_message_check"
    const val PROACTIVE_INTERVAL_MINUTES = 90L

    fun scheduleProactiveMessageCheck(context: Context) {
        // D-3 fix: 加 NETWORK_CONNECTED 约束，Worker 内部需要调用 LLM，无网时系统等有网再唤醒，
        // 避免无网时静默 Result.success() 导致日志误导调试。
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ProactiveMessageWorker>(
            PROACTIVE_INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PROACTIVE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelProactiveMessageCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PROACTIVE_WORK_NAME)
    }
}
