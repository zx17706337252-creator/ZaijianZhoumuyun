package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.dao.WorkflowJobDao
import com.zaijian.zhoumuyun.data.db.dao.WorkflowStepResultDao
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import java.util.concurrent.TimeUnit

class CiCdStartTool(
    private val context: Context,
    private val githubConfigStore: GithubConfigDataStore,
    private val db: AppDatabase,
    private val workflowJobDao: WorkflowJobDao,
    private val workflowStepResultDao: WorkflowStepResultDao,
    private val characterId: () -> Int,
) : AgentTool {

    override val name = "cicd_start"
    override val paramKeys = listOf("files_json", "message", "branch", "build_type", "create_repo", "repo_name")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val filesJson = params["files_json"]?.trim()
        val message   = params["message"]?.trim()

        if (filesJson.isNullOrBlank()) {
            return ToolResult(name, false, "", "缺少 files_json 参数（要提交的文件列表 JSON）")
        }
        if (message.isNullOrBlank()) {
            return ToolResult(name, false, "", "缺少 message 参数（commit 信息）")
        }

        val branch    = params["branch"]?.trim().takeIf { !it.isNullOrBlank() } ?: "main"
        val buildType = params["build_type"]?.trim()?.lowercase().let {
            if (it == "release") "release" else "debug"
        }
        val createRepo = params["create_repo"]?.trim()?.lowercase() == "true"
        val repoName   = params["repo_name"]?.trim() ?: ""

        return try {
            val repo = WorkflowRepository(db, workflowJobDao, workflowStepResultDao)
            val goal = buildString {
                append("CI/CD: ")
                if (createRepo) append("创建仓库 + ")
                append("提交代码 → 编译($buildType) → 下载APK")
            }
            val jobId = repo.createJob(
                characterId = characterId(),
                goal = goal,
                maxSteps = 10,
                timeoutMs = 30 * 60 * 1000L,
            )

            val inputData = Data.Builder()
                .putString("job_id", jobId)
                .putString("files_json", filesJson)
                .putString("message", message)
                .putString("branch", branch)
                .putString("build_type", buildType)
                .putBoolean("create_repo", createRepo)
                .putString("repo_name", repoName)
                .build()

            // 性能 M1 修复：CiCdPipelineWorker 内部要调用 GitHub API 提交代码、
            // 触发编译、下载 APK，全程依赖网络。加约束后无网时系统会等有网再唤醒，
            // 而不是立即跑一次直接失败。
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<CiCdPipelineWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("cicd_$jobId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "cicd_$jobId",
                ExistingWorkPolicy.KEEP,
                request,
            )

            ToolResult(
                toolName = name,
                success  = true,
                content  = "已开始后台流水线：提交代码 → 触发编译 → 下载 APK，完成后会通知你。",
                userHint = "正在启动编译流水线…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "", "启动流水线失败：${e.message?.take(120)}")
        }
    }
}
