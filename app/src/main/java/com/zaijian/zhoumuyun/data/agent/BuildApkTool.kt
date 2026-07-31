package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class BuildApkTool(
    private val githubConfigStore: GithubConfigDataStore,
) : AgentTool {

    override val name = "build_apk"
    override val description = "触发GitHub远程编译APK（指定分支/构建类型），用于「帮我打个包」"
    override val paramKeys = listOf("branch", "build_type", "commit_sha")
    override val usageNotes = "build_type 可选 release/debug，默认 debug；commit_sha 为可选的40字符Git提交哈希"

    private companion object {
        const val WORKFLOW_FILE = "build.yml"
        const val API_BASE = "https://api.github.com"
    }

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val config = githubConfigStore.getConfig()
            if (!config.isConfigured) {
                return@withContext ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "",
                    error    = "GitHub 配置未完成，请先在设置中填写 owner / repo / token。",
                    userHint = "GitHub 未配置",
                )
            }

            val branch    = params["branch"]?.trim().takeIf { !it.isNullOrBlank() } ?: "main"
            val buildType = params["build_type"]?.trim()?.lowercase().let {
                if (it == "release") "release" else "debug"
            }
            val commitSha = params["commit_sha"]?.trim().takeIf { it?.length == 40 }

            try {
                val runId = triggerWorkflowDispatch(config, branch, buildType, commitSha)
                if (runId != null) {
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "已触发 $branch 分支 $buildType 构建，Run ID: $runId。" +
                                   "GitHub Actions 正在排队，可用 build_status_check 查询进度。",
                        userHint = "正在触发编译…",
                    )
                } else {
                    ToolResult(
                        toolName = name,
                        success  = false,
                        content  = "",
                        error    = "GitHub API 返回非 204，请检查 Token 权限或 workflow 文件是否存在。",
                        userHint = "触发失败",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "触发编译失败，请稍后重试。", "build_apk_trigger_failed", e)
            }
        }

    /**
     * POST /repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches
     * 返回 run_id（通过反查获取），失败返回 null。
     */
    private suspend fun triggerWorkflowDispatch(
        config: com.zaijian.zhoumuyun.data.datastore.GithubConfig,
        branch: String,
        buildType: String,
        commitSha: String?,
    ): String? {
        val dispatchUrl = "$API_BASE/repos/${config.owner}/${config.repo}/actions/workflows/$WORKFLOW_FILE/dispatches"

        val body = JSONObject().apply {
            put("ref", commitSha ?: branch)
            put("inputs", JSONObject().apply {
                put("branch",     branch)
                put("build_type", buildType)
            })
        }.toString()

        // B5-Fix6: 接入 githubHttpRetry，对 429/5xx 和网络异常自动指数退避重试。
        // workflow_dispatch 成功返回 204（无响应体）；4xx（除 429）属不可重试错误
        // （如 404 workflow 不存在 / 403 权限不足），直接返回 false 不重试。
        // 原 #58 修复的「写入失败补充阶段信息」由 githubHttpRetry 统一捕获 IOException
        // 并重试来覆盖，不再需要手动包装。
        val dispatched = githubHttpRetry(
            onRetry = { attempt, e ->
                com.zaijian.zhoumuyun.util.ZLog.w(
                    "BuildApk",
                    "触发 workflow_dispatch 第 $attempt 次重试（HTTP ${e.statusCode}）：${e.responseBody.take(100)}",
                )
            },
        ) {
            val conn = (URL(dispatchUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout    = 15_000
                setRequestProperty("Accept",              "application/vnd.github+json")
                setRequestProperty("Authorization",       "Bearer ${config.token}")
                setRequestProperty("Content-Type",         "application/json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                doOutput = true
            }

            try {
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                val code = conn.responseCode
                when {
                    code == 204 -> true // 触发成功
                    code == 429 || code >= 500 -> {
                        // 可重试错误 → 抛出触发 helper 重试
                        val err = try {
                            conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                        } catch (_: Throwable) { null }
                        throw GithubHttpException(
                            statusCode = code,
                            responseBody = err ?: conn.responseMessage,
                        )
                    }
                    else -> false // 不可重试业务错误（404/403 等），不重试
                }
            } finally {
                conn.disconnect()
            }
        }

        return if (dispatched) lookupRunId(config, branch) else null
    }

    /**
     * workflow_dispatch 不直接返回 run_id，需要反查。
     * GET /repos/{owner}/{repo}/actions/runs?event=workflow_dispatch&branch={branch}
     *
     * P0-3 修复：原实现计算了 since 和 createdAt 但从未用于过滤，
     * per_page=1 直接取最新一条——若 GitHub Actions API 存在最终一致性延迟
     * （新 run 尚未索引），返回的是上一次旧 run_id，导致下载旧 APK。
     *
     * 修复方案：
     * 1. 记录 dispatch 前时间点（触发前 5s 的 epoch 秒，容忍服务器时钟偏差）
     * 2. per_page=5 取多条，逐条比对 created_at >= sinceEpochSec
     * 3. 最多重试 3 次（间隔 2s），等待 GitHub API 最终一致性追上
     */
    private suspend fun lookupRunId(config: com.zaijian.zhoumuyun.data.datastore.GithubConfig, branch: String): String? {
        // dispatch 发生前约 5s，容忍服务器时钟偏差与本地调用耗时
        val sinceEpochSec = (System.currentTimeMillis() - 5_000L) / 1000L
        val url = "$API_BASE/repos/${config.owner}/${config.repo}/actions/runs" +
                  "?event=workflow_dispatch&branch=${Uri.encode(branch)}&per_page=5"

        // 复审修复：原用 repeat(3) { return null }，响应非 200 或 JSON 解析失败时
        // 会直接 return 跳出整个函数，导致"最多重试 3 次"的保证只在
        // "请求成功但本批次未匹配"时生效，对网络抖动/限流一次失败就提前放弃。
        // 改为 for 循环 + continue，把"本轮失败"和"重试次数耗尽"分开处理：
        // 任何一轮请求失败（非 200 / JSON 解析失败）都视为可重试的瞬时故障，
        // continue 进入下一轮，仍享受完整的 3 次 + 2s 间隔重试预算；
        // 只有 3 次全部用尽（无论是失败还是未匹配）才返回 null。
        attemptLoop@ for (attempt in 0 until 3) {
            // #57 修复：原 Thread.sleep(2_000L) 虽跑在 Dispatchers.IO 的弹性线程池上，
            // 不会真的 ANR 主线程，但仍会占住一个 IO 线程什么都不干地阻塞 2 秒，
            // 在高并发调用下会挤占线程池资源。lookupRunId 已经是 suspend 函数，
            // 改用 delay() 让协程在等待期间挂起而不占用线程，符合协程最佳实践。
            if (attempt > 0) delay(2_000L)

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout    = 10_000
                setRequestProperty("Accept",               "application/vnd.github+json")
                setRequestProperty("Authorization",        "Bearer ${config.token}")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }

            try {
                if (conn.responseCode != 200) continue@attemptLoop
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val runs = JSONObject(json).optJSONArray("workflow_runs") ?: continue@attemptLoop

                for (i in 0 until runs.length()) {
                    val run = runs.getJSONObject(i)
                    val createdAtStr = run.optString("created_at", "")
                    val runId = run.optLong("id", -1L)
                    if (runId < 0) continue

                    // ISO-8601 "2024-06-29T12:34:56Z" → epoch 秒
                    val createdEpochSec = parseIso8601ToEpochSec(createdAtStr)
                    if (createdEpochSec >= sinceEpochSec) {
                        return runId.toString()
                    }
                }
                // 当前批次无符合条件的 run，继续重试
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // IO 异常（连接超时/读超时/JSON 格式异常等）同样视为瞬时故障，
                // 不让单次异常提前终止整个查找流程。
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /**
     * 将 GitHub API 返回的 ISO-8601 UTC 时间串解析为 epoch 秒。
     * [重构-01] 收敛到 TimeFormatUtils.parseIso8601UtcToEpochSeconds（Instant.parse 原生支持
     * 该格式，无需手动指定 pattern/时区），解析失败同样返回 0（视为非常旧，不通过时间窗口校验）。
     */
    private fun parseIso8601ToEpochSec(iso: String): Long =
        com.zaijian.zhoumuyun.util.TimeFormatUtils.parseIso8601UtcToEpochSeconds(iso)
}
