package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.dao.WorkflowJobDao
import com.zaijian.zhoumuyun.data.db.dao.WorkflowStepResultDao
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.delay
import org.json.JSONObject

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

/** P2-45：CI/CD 流水线步骤数超出 maxSteps 上限时抛出。 */
class CiCdStepLimitExceededException(message: String) : RuntimeException(message)

object CiCdPipelineRunner {

    private const val POLL_INTERVAL_MS = 15_000L
    private const val MAX_POLL_COUNT = 60
    // P-9 修复：删除死常量 MAX_PIPELINE_STEPS = 6（全局仅定义处引用，无使用）

    // #56 修复：recordStep 的 metadata 参数此前用手写字符串模板拼接
    // （如 """{"message":"${params.commitMessage}",...}"""），commitMessage/
    // branch 等字段若含双引号或反斜杠会破坏 JSON 结构，写入的 metadata 列
    // 变成非法 JSON，后续任何读取/解析该字段的地方都可能出错。改用
    // JSONObject 统一构造，交给 org.json 处理转义。
    private fun jsonMeta(vararg pairs: Pair<String, Any?>): String =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }.toString()

    suspend fun run(
        context: Context,
        jobId: String,
        params: CiCdParams,
        githubConfigStore: GithubConfigDataStore,
        db: AppDatabase,
        workflowJobDao: WorkflowJobDao,
        workflowStepResultDao: WorkflowStepResultDao,
    ): CiCdResult {
        val repo = WorkflowRepository(db, workflowJobDao, workflowStepResultDao, context)

        // P2-45 修复：接入 WorkflowJobEntity 的 maxSteps/deadlineAt 限制，
        // 不再仅依赖本地轮询上限。run() 开头读取 job 实体，后续每步检查。
        val jobEntity = workflowJobDao.findById(jobId)
        val maxSteps = jobEntity?.maxSteps ?: 8
        val deadlineAt = jobEntity?.deadlineAt ?: (System.currentTimeMillis() + 30 * 60 * 1000L)

        // P1-5-3 修复：原来 currentStep 永远从 0 开始，进程被杀后 WorkManager
        // 重新拉起 Worker 会从头重跑所有步骤，导致重复提交代码 / 重复创建仓库 /
        // 重复触发编译等有副作用的操作。改为先回放该 jobId 已记录的步骤历史，
        // 找出已经成功完成的「阶段」（按工具名识别，因为轮询步骤 build_status_check
        // 本身允许重复出现，不算"已完成阶段"），跳过对应阶段，只续跑后面的部分。
        val history = repo.getStepHistory(jobId)
        val completedTools = history.filter { it.success }.mapNotNull { it.toolName }.toSet()
        val maxRecordedStep = history.maxOfOrNull { it.stepIndex } ?: -1
        var currentStep = maxRecordedStep + 1

        // P2-45：步骤计数器超出 maxSteps 时终止
        fun stepIndex(): Int {
            if (currentStep >= maxSteps) {
                throw CiCdStepLimitExceededException("步骤数 ($currentStep) 已达上限 ($maxSteps)")
            }
            return currentStep++
        }
        fun alreadyDone(toolName: String) = toolName in completedTools

        try {
            // P2-45：检查 deadlineAt 是否已过期
            if (System.currentTimeMillis() > deadlineAt) {
                repo.markFailed(jobId, "任务已超过截止时间 (deadlineAt=$deadlineAt)")
                return CiCdResult.Failed("任务已超过截止时间")
            }

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
                // P2-46 修复：原 files_count 用字面量 "..." 占位，
                // 改为解析 filesJson 获取真实文件数量。
                val filesCount = try {
                    org.json.JSONArray(params.filesJson).length()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) { 0 }
                repo.recordStep(jobId, commitStep, "git_commit_push",
                    jsonMeta("message" to params.commitMessage, "branch" to params.branch, "files_count" to filesCount),
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
                        jsonMeta("branch" to params.branch, "build_type" to params.buildType),
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
                    jsonMeta("branch" to params.branch, "build_type" to params.buildType),
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
            var pollOutcome: String? = null  // "success" | "failed" | "timeout" | "deadline_exceeded"
            while (pollCount < MAX_POLL_COUNT) {
                delay(POLL_INTERVAL_MS)
                pollCount++
                // P2-45 修复（返工）：轮询循环最长可达 15 分钟（60×15s），期间必须
                // 持续检查 deadlineAt。原实现只在 run() 开头检查了一次，若任务
                // deadlineAt 被设得较紧、恰好在轮询阶段中途到期，旧代码会一直
                // 轮询到自然结束才退出，无法提前中止。现在每轮都检查。
                if (System.currentTimeMillis() > deadlineAt) {
                    pollOutcome = "deadline_exceeded"
                    break
                }
                val statusResult = statusTool.execute(mapOf("run_id" to runId))
                lastStatusResult = statusResult
                // P2 修复：单次查询失败时打印日志，避免用户看到 deadline_exceeded
                // 却不知道中途一直在失败。原实现只把最后一次结果留到循环外记录一条
                // 汇总，期间每次 statusTool.execute() 返回 success=false（如 token
                // 失效、API 限流、网络抖动、run_id 不存在等）都被静默吞掉。最终若
                // 因 deadline 到期退出，用户/排查者会误以为"编译一直在跑只是超时了"，
                // 实则可能根本没成功查到过一次状态。这里每轮失败都打一条 warning，
                // 便于事后从日志还原真实的轮询过程。
                if (lastStatusResult != null && !lastStatusResult.success) {
                    ZLog.w("CiCdPipelineRunner", "构建状态查询失败: ${lastStatusResult.error}")
                }
                val status = statusResult.content ?: ""

                if (status.contains("编译成功")) { pollOutcome = "success"; break }
                if (status.contains("编译失败") || status.contains("已取消")) { pollOutcome = "failed"; break }
                if (status.contains("已跳过") || status.contains("中性")) { pollOutcome = "failed"; break }
            }
            val pollStep = stepIndex()
            repo.recordStep(jobId, pollStep, "build_status_check",
                jsonMeta("run_id" to runId, "poll_attempts" to pollCount),
                pollOutcome == "success", lastStatusResult?.content, lastStatusResult?.error, null,
                System.currentTimeMillis(), System.currentTimeMillis())

            if (pollOutcome == "failed") {
                repo.markFailed(jobId, "编译失败")
                return CiCdResult.Failed("编译失败")
            }
            // P2-45：deadlineAt 在轮询期间到期，提前中止并标记失败
            if (pollOutcome == "deadline_exceeded") {
                repo.markFailed(jobId, "任务超过截止时间（编译轮询第 $pollCount 轮时到期）")
                return CiCdResult.Failed("任务超过截止时间")
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
                jsonMeta("run_id" to runId),
                downloadResult.success, downloadResult.content, downloadResult.error, null,
                System.currentTimeMillis(), System.currentTimeMillis())
            if (!downloadResult.success) {
                repo.markFailed(jobId, "下载 APK 失败：${downloadResult.error}")
                return CiCdResult.Failed("下载 APK 失败：${downloadResult.error}")
            }

            repo.markCompleted(jobId, "编译完成，APK 已下载到本地")
            return CiCdResult.Success

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
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
