package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class CreateGithubRepoTool(
    private val githubConfigStore: GithubConfigDataStore,
) : AgentTool {

    override val name = "create_github_repo"
    override val description = "在GitHub上创建一个新仓库（可设为私有/自动初始化）"
    override val paramKeys = listOf("name", "description", "private", "auto_init")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val config = githubConfigStore.getConfig()
            if (!config.isConfigured) {
                return@withContext ToolResult(
                    toolName = name, success = false, content = "",
                    error = "GitHub 配置未完成",
                    userHint = "GitHub 未配置",
                )
            }

            val repoName = params["name"]?.trim()
            if (repoName.isNullOrBlank()) {
                return@withContext ToolResult(name, false, "", "缺少 name 参数（仓库名）")
            }

            val description = params["description"]?.trim() ?: ""
            val isPrivate   = params["private"]?.lowercase() == "true"
            val autoInit    = params["auto_init"]?.lowercase() == "true"

            try {
                val url = "https://api.github.com/user/repos"
                val body = JSONObject().apply {
                    put("name", repoName)
                    put("description", description)
                    put("private", isPrivate)
                    put("auto_init", autoInit)
                }.toString()

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout    = 15_000
                    setRequestProperty("Accept",              "application/vnd.github+json")
                    setRequestProperty("Authorization",       "Bearer ${config.token}")
                    setRequestProperty("Content-Type",         "application/json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    doOutput = true
                }

                return@withContext try {
                    OutputStreamWriter(conn.outputStream).use { it.write(body) }
                    val code = conn.responseCode
                    if (code in 200..201) {
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        val cloneUrl = JSONObject(resp).optString("clone_url", "")
                        ToolResult(
                            toolName = name,
                            success  = true,
                            content  = "仓库已创建：$cloneUrl",
                            userHint = "正在创建仓库…",
                        )
                    } else {
                        val err = try {
                            conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                        } catch (_: Exception) { null }
                        val msg = when (code) {
                            422 -> "仓库名已存在或名称不合法"
                            else -> "HTTP $code: ${err ?: conn.responseMessage}"
                        }
                        ToolResult(name, false, "", msg)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                ToolResult(name, false, "", "创建仓库失败：${e.message?.take(120)}")
            }
        }
}
