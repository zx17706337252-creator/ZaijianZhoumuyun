package com.zaijian.zhoumuyun.data.agent

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import com.zaijian.zhoumuyun.data.db.AppDatabase
class CiCdPipelineWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "cicd_pipeline"
        const val CHANNEL_NAME = "编译流水线"
        const val MAX_RETRY_COUNT = 3
        // 这些关键词出现在失败原因中时，认为是网络抖动/服务临时不可用等
        // 瞬时性问题，值得重试；其余视为业务逻辑确定性失败，重试无意义。
        private val TRANSIENT_FAILURE_KEYWORDS = listOf(
            "网络", "超时", "timeout", "Timeout", "连接", "无法获取编译任务 ID",
        )
    }

    override suspend fun doWork(): Result {
        val jobId       = inputData.getString("job_id") ?: return Result.failure()
        val filesJson   = inputData.getString("files_json") ?: return Result.failure()
        val message     = inputData.getString("message") ?: return Result.failure()
        val branch      = inputData.getString("branch") ?: "main"
        val buildType   = inputData.getString("build_type") ?: "debug"
        val createRepo  = inputData.getBoolean("create_repo", false)
        val repoName    = inputData.getString("repo_name") ?: ""

        val db = AppDatabase.getInstance(appContext)
        val githubConfigStore = GithubConfigDataStore(appContext)

        val params = CiCdParams(
            filesJson     = filesJson,
            commitMessage = message,
            branch        = branch,
            buildType     = buildType,
            createRepo    = createRepo,
            repoName      = repoName,
        )

        val result = CiCdPipelineRunner.run(
            context               = appContext,
            jobId                 = jobId,
            params                = params,
            githubConfigStore     = githubConfigStore,
            db                    = db,
            workflowJobDao        = db.workflowJobDao(),
            workflowStepResultDao = db.workflowStepResultDao(),
        )

        // P1-5-4 修复：原逻辑无论 Success/Failed 分支结束后都 fall-through 到统一的
        // return Result.success()，导致 WorkManager 认为本次执行"成功"，永不触发
        // 内置的指数退避重试，即使失败原因是网络抖动等瞬时性问题。
        // 现在按失败原因区分：网络/超时类瞬时错误 → Result.retry()（交给 WorkManager
        // 按 BackoffPolicy 重试，下次 run() 会复用步骤历史续跑，不会重新开始）；
        // 其余（提交/编译/下载等明确业务失败）→ Result.failure()，不再无意义重试。
        return when (result) {
            is CiCdResult.Success -> {
                sendNotification("✅ 编译完成", "APK 已下载到本地，点击安装")
                Result.success()
            }
            is CiCdResult.Failed -> {
                sendNotification("❌ 编译失败", result.reason)
                val transient = TRANSIENT_FAILURE_KEYWORDS.any { result.reason.contains(it) }
                if (transient && runAttemptCount < MAX_RETRY_COUNT) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }
    }

    private fun sendNotification(title: String, text: String) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 修复（第4窗口审查报告问题4）：原在此处自行调用 createNotificationChannel()，
        // 与项目中其余 Worker 的渠道管理方式不一致。渠道已改为在
        // ZaijianApp.setupNotificationChannels() 中统一注册，此处不再重复创建。

        val notif = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notif)
    }
}
