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
        else                    -> "状态: $status / $conclusion"
    }
}

class BuildStatusCheckTool(
    private val githubConfigStore: GithubConfigDataStore,
) : AgentTool {

    override val name = "build_status_check"
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
            } catch (e: Exception) {
                ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "",
                    error    = "查询失败：${e.message?.take(120)}",
                    userHint = "查询失败",
                )
            }
        }

    suspend fun queryStatus(params: Map<String, String>): BuildStatus? {
        val config = githubConfigStore.getConfig()
        if (!config.isConfigured) return null
        val runId = params["run_id"]?.trim() ?: return null
        return try {
            queryRunStatus(config, runId)
        } catch (_: Exception) { null }
    }

    private fun queryRunStatus(
        config: com.zaijian.zhoumuyun.data.datastore.GithubConfig,
        runId: String,
    ): BuildStatus {
        val url = "$API_BASE/repos/${config.owner}/${config.repo}/actions/runs/${Uri.encode(runId)}"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout    = 10_000
            setRequestProperty("Accept",              "application/vnd.github+json")
            setRequestProperty("Authorization",       "Bearer ${config.token}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

        return try {
            if (conn.responseCode != 200) {
                throw RuntimeException("HTTP ${conn.responseCode}")
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            BuildStatus(
                status      = obj.optString("status", "unknown"),
                conclusion  = obj.optString("conclusion", null),
                htmlUrl     = obj.optString("html_url", ""),
            )
        } finally {
            conn.disconnect()
        }
    }
}
