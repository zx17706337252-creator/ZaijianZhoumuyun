package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.dao.WorkflowJobDao
import com.zaijian.zhoumuyun.data.db.dao.WorkflowStepResultDao
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import kotlinx.coroutines.delay

data class CiCdParams(
    val filesJson: String,
    val commitMessage: String,
    val branch: String = "main",
    val buildType: String = "debug",
    val createRepo: Boolean = false,
    val repoName: String = "",
)

sealed class CiCdResult {
    data object Success : CiCdResult()
    data class Failed(val reason: String) : CiCdResult()
}

object CiCdPipelineRunner {

    private const val POLL_INTERVAL_MS = 15_000L
    private const val MAX_POLL_COUNT = 60
    // P-9 修复：删除死常量 MAX_PIPELINE_STEPS = 6（全局仅定义处引用，无使用）

    suspend fun run(
        context: Context,
        jobId: String,
        params: CiCdParams,
        githubConfigStore: GithubConfigDataStore,
        db: AppDatabase,
        workflowJobDao: WorkflowJobDao,
        workflowStepResultDao: WorkflowStepResultDao,
    ): CiCdResult {
        val repo = WorkflowRepository(db, workflowJobDao, workflowStepResultDao)

        // P1-5-3 修复：原来 currentStep 永远从 0 开始，进程被杀后 WorkManager
        // 重新拉起 Worker 会从头重跑所有步骤，导致重复提交代码 / 重复创建仓库 /
        // 重复触发编译等有副作用的操作。改为先回放该 jobId 已记录的步骤历史，
        // 找出已经成功完成的「阶段」（按工具名识别，因为轮询步骤 build_status_check
        // 本身允许重复出现，不算"已完成阶段"），跳过对应阶段，只续跑后面的部分。
        val history = repo.getStepHistory(jobId)
        val completedTools = history.filter { it.success }.mapNotNull { it.toolName }.toSet()
        val maxRecordedStep = history.maxOfOrNull { it.stepIndex } ?: -1
        var currentStep = maxRecordedStep + 1

        fun stepIndex() = currentStep++
        fun alreadyDone(toolName: String) = toolName in completedTools

        try {
            // 步骤 0：创建仓库（可选）
            if (params.createRepo && params.repoName.isNotBlank() && !alreadyDone("create_github_repo")) {
                val step = stepIndex()
                val createResult = CreateGithubRepoTool(githubConfigStore).execute(
                    mapOf("name" to params.repoName, "auto_init" to "true")
                )
                repo.recordStep(jobId, step, "create_github_repo", "{}",
                    createResult.success, createResult.content, createResult.error, null,
                    System.currentTimeMillis(), System.currentTimeMillis())
                if (!createResult.success) {
                    repo.markFailed(jobId, "创建仓库失败：${createResult.error}")
                    return CiCdResult.Failed("创建仓库失败：${createResult.error}")
                }
            }

            // 步骤 1：提交代码（已成功提交过的不重复提交，避免重复 commit）
            if (!alreadyDone("git_commit_push")) {
                val commitStep = stepIndex()
                val commitTool = GitCommitPushTool(githubConfigStore)
                val commitResult = commitTool.execute(mapOf(
                    "message"    to params.commitMessage,
                    "files_json" to params.filesJson,
                    "branch"     to params.branch,
                ))
                repo.recordStep(jobId, commitStep, "git_commit_push",
                    """{"message":"${params.commitMessage}","branch":"${params.branch}","files_count":"..."}""",
                    commitResult.success, commitResult.content, commitResult.error, null,
                    System.currentTimeMillis(), System.currentTimeMillis())
                if (!commitResult.success) {
                    repo.markFailed(jobId, "提交代码失败：${commitResult.error}")
                    return CiCdResult.Failed("提交代码失败：${commitResult.error}")
                }
            }

            // 步骤 2：触发编译。若历史中已有成功的 build_apk，复用其 runId 继续轮询，
            // 不重新触发一次新的编译任务（避免续跑时浪费一次 CI 构建额度）。
            val runId: String = if (alreadyDone("build_apk")) {
                val prevBuild = history.lastOrNull { it.toolName == "build_apk" && it.success }
                extractRunId(prevBuild?.output) ?: run {
                    // 历史记录里取不到 runId（数据异常），保险起见重新触发一次编译
                    val buildStep = stepIndex()
                    val buildTool = BuildApkTool(githubConfigStore)
                    val buildResult = buildTool.execute(mapOf(
                        "branch"     to params.branch,
                        "build_type" to params.buildType,
                    ))
                    repo.recordStep(jobId, buildStep, "build_apk",
                        """{"branch":"${params.branch}","build_type":"${params.buildType}"}""",
                        buildResult.success, buildResult.content, buildResult.error, null,
                        System.currentTimeMillis(), System.currentTimeMillis())
                    if (!buildResult.success) {
                        repo.markFailed(jobId, "触发编译失败：${buildResult.error}")
                        return CiCdResult.Failed("触发编译失败：${buildResult.error}")
                    }
                    buildResult.content?.let { extractRunId(it) }
                        ?: run {
                            repo.markFailed(jobId, "无法获取编译任务 ID")
                            return CiCdResult.Failed("无法获取编译任务 ID")
                        }
                }
            } else {
                val buildStep = stepIndex()
                val buildTool = BuildApkTool(githubConfigStore)
                val buildResult = buildTool.execute(mapOf(
                    "branch"     to params.branch,
                    "build_type" to params.buildType,
                ))
                repo.recordStep(jobId, buildStep, "build_apk",
                    """{"branch":"${params.branch}","build_type":"${params.buildType}"}""",
                    buildResult.success, buildResult.content, buildResult.error, null,
                    System.currentTimeMillis(), System.currentTimeMillis())
                if (!buildResult.success) {
                    repo.markFailed(jobId, "触发编译失败：${buildResult.error}")
                    return CiCdResult.Failed("触发编译失败：${buildResult.error}")
                }
                buildResult.content?.let { extractRunId(it) }
                    ?: run {
                        repo.markFailed(jobId, "无法获取编译任务 ID")
                        return CiCdResult.Failed("无法获取编译任务 ID")
                    }
            }

            // 步骤 3：轮询编译状态
            // P1-5-2 修复：原逻辑每次轮询都调用一次 recordStep()，最多 60 次产生
            // 60 条几乎无信息量的轮询记录，把 currentStep 计数器迅速耗尽（影响后续
            // 步骤编号的可读性，也让 workflow_step_results 表无谓膨胀）。改为轮询期间
            // 只在内存里跟踪最后一次状态，循环结束后只记一条汇总记录。
            val statusTool = BuildStatusCheckTool(githubConfigStore)
            var pollCount = 0
            var lastStatusResult: ToolResult? = null
            var pollOutcome: String? = null  // "success" | "failed" | "timeout"
            while (pollCount < MAX_POLL_COUNT) {
                delay(POLL_INTERVAL_MS)
                pollCount++
                val statusResult = statusTool.execute(mapOf("run_id" to runId))
                lastStatusResult = statusResult
                val status = statusResult.content ?: ""

                if (status.contains("编译成功")) { pollOutcome = "success"; break }
                if (status.contains("编译失败") || status.contains("已取消")) { pollOutcome = "failed"; break }
            }
            val pollStep = stepIndex()
            repo.recordStep(jobId, pollStep, "build_status_check",
                """{"run_id":"$runId","poll_attempts":$pollCount}""",
                pollOutcome == "success", lastStatusResult?.content, lastStatusResult?.error, null,
                System.currentTimeMillis(), System.currentTimeMillis())

            if (pollOutcome == "failed") {
                repo.markFailed(jobId, "编译失败")
                return CiCdResult.Failed("编译失败")
            }
            if (pollOutcome != "success") {
                repo.markFailed(jobId, "编译超时（已等待 ${MAX_POLL_COUNT * POLL_INTERVAL_MS / 1000} 秒）")
                return CiCdResult.Failed("编译超时")
            }

            // 步骤 4：下载 APK
            val downloadStep = stepIndex()
            val downloadTool = BuildApkDownloadTool(
                context = context,
                githubConfigStore = githubConfigStore,
            )
            val downloadResult = downloadTool.execute(mapOf("run_id" to runId))
            repo.recordStep(jobId, downloadStep, "build_apk_download",
                """{"run_id":"$runId"}""",
                downloadResult.success, downloadResult.content, downloadResult.error, null,
                System.currentTimeMillis(), System.currentTimeMillis())
            if (!downloadResult.success) {
                repo.markFailed(jobId, "下载 APK 失败：${downloadResult.error}")
                return CiCdResult.Failed("下载 APK 失败：${downloadResult.error}")
            }

            repo.markCompleted(jobId, "编译完成，APK 已下载到本地")
            return CiCdResult.Success

        } catch (e: Exception) {
            repo.markFailed(jobId, "流水线异常：${e.message?.take(120)}")
            return CiCdResult.Failed("流水线异常：${e.message?.take(120)}")
        }
    }

    private fun extractRunId(content: String?): String? {
        if (content == null) return null
        val match = Regex("Run ID: (\\d+)").find(content)
        return match?.groupValues?.get(1)
    }
}
