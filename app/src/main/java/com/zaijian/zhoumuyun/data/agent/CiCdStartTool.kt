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
    // P0/P1 修复（批次4审查报告 cicd_start 问题1/2，根因③）：原 description 没说
    // files_json 格式、build_type 只接受 release/debug、create_repo 只认 "true"，
    // 现补充说明，从源头减少 LLM 输出不合规值的概率。
    override val description = "一键启动完整CI/CD流水线（提交代码→触发构建→轮询状态），可选先创建新仓库"
    override val usageNotes = "files_json 是文件列表 JSON 数组（格式同 git_commit_push，例如 files_json=\"[{\"path\":\"a.txt\",\"content\":\"文件内容\"}]\"）；build_type 只接受 release 或 debug（默认 debug）；create_repo 只接受 true 或 false（默认 false，其它值一律视为 false 并返回警告）"
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

        // P0 修复（批次4审查报告 cicd_start 问题1，最严重的一条）：
        // 原逻辑只检查 filesJson 非空，从不实际解析它，直接把原始字符串塞进
        // WorkManager Data 并立即返回 success；真正的 JSON 解析被推迟到
        // CiCdPipelineWorker 后台执行。于是 files_json 格式错误（裸数组截断、
        // 转义错误等）会一路"绿灯"到后台，execute() 却已经告诉用户"已开始
        // 后台流水线"——用户以为正在编译，实际上流水线在后台早已注定失败。
        // 这是本次审查里最坏的失败模式：不可逆副作用（一旦真正提交/编译触发）
        // + execute 返回 success + 后台静默失败。
        // 现在入口处同步复用 GitCommitPushTool 的 parseFilesJson 做一次真实解析，
        // 解析失败直接返回 error，不再 enqueue Worker；解析成功也不重复利用
        // 结果（Worker 内部仍会走一遍完整流程，这里只是前置校验，避免创建一个
        // 注定失败的后台任务）。
        try {
            val parsed = GitCommitPushTool(githubConfigStore).parseFilesJson(filesJson)
            if (parsed.isEmpty()) {
                return ToolResult(name, false, "", "files_json 中没有有效的文件条目")
            }
        } catch (e: Exception) {
            return ToolResult(name, false, "", "files_json 格式错误，流水线未启动：${e.message?.take(120)}")
        }

        val branch = params["branch"]?.trim().takeIf { !it.isNullOrBlank() } ?: "main"

        // P2 修复（批次4审查报告 cicd_start 问题2）：原逻辑对不认识的 build_type/
        // create_repo 取值静默降级（"apk" 静默变 "debug"，"yes" 静默变 false 导致
        // 跳过建仓步骤），LLM 和用户都不会发现值没有被采纳。现在记录明确警告文案，
        // 附加到最终返回结果里，让降级"可见"，同时仍然保留一个可用的默认值兜底
        // （不因为枚举值写错就整体失败，只是把偏差告诉调用方）。
        val warnings = mutableListOf<String>()

        val rawBuildType = params["build_type"]?.trim()?.lowercase()
        val buildType = when (rawBuildType) {
            null, "" -> "debug"
            "release" -> "release"
            "debug" -> "debug"
            else -> {
                warnings.add("build_type=\"${params["build_type"]}\" 不是 release/debug，已按 debug 处理")
                "debug"
            }
        }

        val rawCreateRepo = params["create_repo"]?.trim()?.lowercase()
        val createRepo = when (rawCreateRepo) {
            null, "" -> false
            "true" -> true
            "false" -> false
            else -> {
                warnings.add("create_repo=\"${params["create_repo"]}\" 不是 true/false，已按 false（不建仓）处理")
                false
            }
        }
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

            val warningSuffix = if (warnings.isNotEmpty()) "\n⚠️ " + warnings.joinToString("；") else ""
            ToolResult(
                toolName = name,
                success  = true,
                content  = "已开始后台流水线：提交代码 → 触发编译 → 下载 APK，完成后会通知你。$warningSuffix",
                userHint = "正在启动编译流水线…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "", "启动流水线失败：${e.message?.take(120)}")
        }
    }
}
