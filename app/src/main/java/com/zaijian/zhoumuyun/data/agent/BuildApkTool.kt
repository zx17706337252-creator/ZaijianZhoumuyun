package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import android.net.Uri
import kotlinx.coroutines.Dispatchers
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
            } catch (e: Exception) {
                ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "",
                    error    = "触发编译失败：${e.message?.take(120)}",
                    userHint = "触发失败",
                )
            }
        }

    /**
     * POST /repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches
     * 返回 run_id（通过反查获取），失败返回 null。
     */
    private fun triggerWorkflowDispatch(
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
            if (code != 204) return null
        } finally {
            conn.disconnect()
        }

        return lookupRunId(config, branch)
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
    private fun lookupRunId(config: com.zaijian.zhoumuyun.data.datastore.GithubConfig, branch: String): String? {
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
            if (attempt > 0) Thread.sleep(2_000L)

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
            } catch (_: Exception) {
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
     * 格式固定为 "yyyy-MM-dd'T'HH:mm:ss'Z'"，手动解析避免引入额外依赖。
     * 解析失败返回 0（视为非常旧，不通过时间窗口校验）。
     */
    private fun parseIso8601ToEpochSec(iso: String): Long {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(iso)?.time?.div(1000L) ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
