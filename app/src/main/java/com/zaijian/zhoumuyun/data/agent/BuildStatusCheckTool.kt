package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class BuildStatus(
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
) {
    val isCompleted: Boolean get() = status == "completed"
    val isSuccess: Boolean get() = isCompleted && conclusion == "success"
    val displayText: String get() = when {
        status == "queued"      -> "⏳ 排队中"
        status == "in_progress" -> "🔄 编译中"
        isSuccess               -> "✅ 编译成功"
        conclusion == "failure" -> "❌ 编译失败"
        conclusion == "cancelled" -> "🚫 已取消"
        conclusion == "skipped" -> "⏭️ 已跳过"
        conclusion == "neutral" -> "⚪ 中性"
        else                    -> "状态: $status / $conclusion"
    }
}

class BuildStatusCheckTool(
    private val githubConfigStore: GithubConfigDataStore,
) : AgentTool {

    override val name = "build_status_check"
    override val description = "查询编译任务当前状态（排队/进行中/成功/失败），需要run_id"
    override val paramKeys = listOf("run_id")

    private companion object {
        const val API_BASE = "https://api.github.com"
    }

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val config = githubConfigStore.getConfig()
            if (!config.isConfigured) {
                return@withContext ToolResult(
                    toolName = name, success = false, content = "",
                    error = "GitHub 配置未完成，请先在设置中填写 owner / repo / token。",
                    userHint = "GitHub 未配置",
                )
            }

            val runId = params["run_id"]?.trim()
            if (runId.isNullOrBlank()) {
                return@withContext ToolResult(name, false, "", "缺少 run_id 参数")
            }

            try {
                val status = queryRunStatus(config, runId)
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[构建状态 #$runId]\n${status.displayText}\n${status.htmlUrl}",
                    userHint = "正在查询编译进度…",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "查询编译状态失败，请稍后重试。", "build_status_check_failed", e)
            }
        }

    suspend fun queryStatus(params: Map<String, String>): BuildStatus? {
        val config = githubConfigStore.getConfig()
        if (!config.isConfigured) return null
        val runId = params["run_id"]?.trim() ?: return null
        return try {
            queryRunStatus(config, runId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) { null }
    }

    private suspend fun queryRunStatus(
        config: com.zaijian.zhoumuyun.data.datastore.GithubConfig,
        runId: String,
    ): BuildStatus {
        val url = "$API_BASE/repos/${config.owner}/${config.repo}/actions/runs/${Uri.encode(runId)}"

        // B5-Fix6: 接入 githubHttpRetry，对 429/5xx 和网络异常自动指数退避重试；
        // 4xx（除 429）属不可重试错误，立即抛出不浪费重试预算。
        return githubHttpRetry(
            onRetry = { attempt, e ->
                com.zaijian.zhoumuyun.util.ZLog.w(
                    "BuildStatusCheck",
                    "GitHub API 第 $attempt 次重试（HTTP ${e.statusCode}）：${e.responseBody.take(100)}",
                )
            },
        ) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout    = 10_000
                setRequestProperty("Accept",              "application/vnd.github+json")
                setRequestProperty("Authorization",       "Bearer ${config.token}")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }

            try {
                val code = conn.responseCode
                if (code != 200) {
                    val errorBody = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                    } catch (_: Throwable) { null }
                    // 429/5xx → isRetryable=true 由 helper 自动重试；
                    // 4xx（除 429）→ isRetryable=false 立即向上抛出
                    throw GithubHttpException(
                        statusCode = code,
                        responseBody = errorBody ?: conn.responseMessage,
                    )
                }
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                BuildStatus(
                    status      = obj.optString("status", "unknown"),
                    conclusion  = if (obj.isNull("conclusion")) null else obj.optString("conclusion"),
                    htmlUrl     = obj.optString("html_url", ""),
                )
            } finally {
                conn.disconnect()
            }
        }
    }
}
