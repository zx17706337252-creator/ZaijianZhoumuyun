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
