package com.zaijian.zhoumuyun.data.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.WorkflowJobEntity
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository

/**
 * WorkflowJobWorker — 多步骤工作流系统 Step 3：WorkManager 后台执行器
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责：在 App 不在前台、甚至进程被杀掉的情况下，由系统拉起，
 *   驱动 WorkflowEngine.run() 继续执行一个 RUNNING 状态的工作流任务。
 *
 * 与 ScheduledJobWorker 的关系：
 *   ScheduledJobWorker —— 到点触发一次工具调用，一次 doWork() 只跑一步
 *   WorkflowJobWorker   —— 一次 doWork() 内部循环跑完所有剩余步骤，
 *                          直到任务终结（COMPLETED/FAILED/TIMEOUT）或
 *                          本次 doWork() 被系统中断
 *
 * 续跑保证：
 *   WorkflowEngine.run() 每次迭代都从数据库重新读状态，不依赖内存。
 *   如果 doWork() 执行到一半被系统杀掉，WorkManager 会在下次合适的时机
 *   重新调度本 Worker（这是 WorkManager 对"被中断的工作"的标准保证），
 *   重新调用 run() 时会发现 status 仍是 RUNNING，从 currentStep 继续，
 *   不会重新执行已经记录在 workflow_step_results 里的步骤。
 *
 * Input Data：
 *   KEY_JOB_ID —— workflow_jobs 表中的任务 ID（String）
 *
 * Gradle 依赖：与 ScheduledJobWorker 共用 androidx.work:work-runtime-ktx，
 *   无需额外添加。
 * ═══════════════════════════════════════════════════════════════
 */
class WorkflowJobWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_JOB_ID   = "workflow_job_id"
        const val CHANNEL_ID   = "zaijian_workflow"
        const val CHANNEL_NAME = "再见公馆 · 多步骤任务"
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)
            ?: return Result.failure()

        val db = AppDatabase.getInstance(context)
        val repository = WorkflowRepository(
            db = db,
            workflowJobDao = db.workflowJobDao(),
            workflowStepResultDao = db.workflowStepResultDao(),
        )

        val job = repository.findById(jobId) ?: return Result.failure()
        if (job.status != WorkflowRepository.STATUS_RUNNING) {
            // 上一次执行已经把任务收敛到终结状态，本次只是 WorkRequest 还没来得及清理
            return Result.success()
        }

        val provider = ProviderManager.instance.activeProvider
        if (provider == null) {
            // 没配置可用的 LLM Provider，工作流无法做任何决策，直接判失败，不无限重试
            repository.markFailed(jobId, "未配置可用的 LLM Provider，工作流无法继续")
            notifyResult(repository.findById(jobId))
            return Result.failure()
        }

        // 核心循环：内部会跑到终结状态才返回，或者本协程被系统取消
        WorkflowEngine.run(jobId, repository, provider)

        val finalJob = repository.findById(jobId)
        if (finalJob != null && finalJob.status != WorkflowRepository.STATUS_RUNNING) {
            notifyResult(finalJob)
        }
        // finalJob.status 仍是 RUNNING 的情况：说明本次 doWork() 被系统中断
        // （理论上不会正常走到这一行，CoroutineWorker 被取消时 run() 内的挂起点会抛
        // CancellationException，doWork() 直接结束，不会执行到这里）。

        return Result.success()
    }

    // ─────────────────────────────────────────────────────────────
    //  通知：任务收敛到终结状态时告知用户
    // ─────────────────────────────────────────────────────────────

    private fun notifyResult(job: WorkflowJobEntity?) {
        if (job == null) return

        val title = when (job.status) {
            WorkflowRepository.STATUS_COMPLETED -> "✅ 任务完成"
            WorkflowRepository.STATUS_TIMEOUT -> "⏱️ 任务未在限制内完成"
            else -> "❌ 任务执行失败"
        }
        val text = job.resultSummary
            ?: job.failReason
            ?: "工作流已结束"

        sendNotification(title, text.take(120))
    }

    private fun sendNotification(title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "多步骤工作流任务执行结果通知" }
            nm.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notif)
    }
}
