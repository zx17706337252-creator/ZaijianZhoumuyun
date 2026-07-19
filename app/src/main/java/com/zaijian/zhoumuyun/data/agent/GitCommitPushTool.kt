package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class FileToCommit(
    val path: String,
    val content: String,
)

class GitCommitPushTool(
    private val githubConfigStore: GithubConfigDataStore,
) : AgentTool {

    override val name = "git_commit_push"
    override val description = "把文件内容提交并推送到GitHub仓库指定分支"
    override val paramKeys = listOf("message", "files_json", "branch")

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

            val message = params["message"]?.trim()
            if (message.isNullOrBlank()) {
                return@withContext ToolResult(name, false, "", "缺少 message 参数（commit 信息）")
            }

            val filesJson = params["files_json"]?.trim()
            if (filesJson.isNullOrBlank()) {
                return@withContext ToolResult(name, false, "", "缺少 files_json 参数（文件列表 JSON）")
            }

            val branch = params["branch"]?.trim().takeIf { !it.isNullOrBlank() } ?: "main"

            val files: List<FileToCommit>
            try {
                files = parseFilesJson(filesJson)
            } catch (e: Exception) {
                return@withContext ToolResult(name, false, "", "files_json 格式错误：${e.message?.take(80)}")
            }

            if (files.isEmpty()) {
                return@withContext ToolResult(name, false, "", "files_json 中没有有效的文件条目")
            }

            try {
                val commitSha = pushFiles(config, branch, message, files)
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "已提交 ${files.size} 个文件到 $branch 分支，commit: $commitSha",
                    userHint = "正在提交代码…",
                )
            } catch (e: Exception) {
                ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "",
                    error    = "提交失败：${e.message?.take(120)}",
                    userHint = "提交失败",
                )
            }
        }

    private fun parseFilesJson(json: String): List<FileToCommit> {
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            FileToCommit(
                path    = obj.getString("path"),
                content = obj.getString("content"),
            )
        }
    }

    /**
     * 使用 GitHub Contents API 逐文件 PUT。
     * 每个文件 PUT 前先 GET 当前 sha（若文件已存在）。
     * 返回本次 commit SHA。
     */
    private fun pushFiles(
        config: com.zaijian.zhoumuyun.data.datastore.GithubConfig,
        branch: String,
        message: String,
        files: List<FileToCommit>,
    ): String {
        var latestCommitSha: String? = null

        for (file in files) {
            val existingSha = getFileSha(config, file.path, branch)

            val putUrl = "$API_BASE/repos/${config.owner}/${config.repo}/contents/${encodeGithubPath(file.path)}"
            val body = JSONObject().apply {
                put("message", message)
                put("content", android.util.Base64.encodeToString(
                    file.content.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP,
                ))
                put("branch", branch)
                if (existingSha != null) {
                    put("sha", existingSha)
                }
            }.toString()

            val conn = (URL(putUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
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
                if (code !in 200..201) {
                    val errorBody = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                    } catch (_: Exception) { null }
                    throw RuntimeException("PUT ${file.path} 返回 $code: ${errorBody ?: conn.responseMessage}")
                }
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val commit = JSONObject(resp).optJSONObject("commit")
                latestCommitSha = commit?.optString("sha")
            } finally {
                conn.disconnect()
            }
        }

        return latestCommitSha ?: "unknown"
    }

    /**
     * GET /repos/{owner}/{repo}/contents/{path}?ref={branch}
     * 文件存在返回 sha，不存在返回 null。
     */
    private fun getFileSha(
        config: com.zaijian.zhoumuyun.data.datastore.GithubConfig,
        path: String,
        branch: String,
    ): String? {
        val url = "$API_BASE/repos/${config.owner}/${config.repo}/contents/${encodeGithubPath(path)}?ref=${Uri.encode(branch)}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout    = 8_000
            setRequestProperty("Accept",              "application/vnd.github+json")
            setRequestProperty("Authorization",       "Bearer ${config.token}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

        return try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) return null
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            if (obj.isNull("sha")) null else obj.optString("sha")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 安全修复 L-3：对仓库内文件路径做 URL 编码，但保留 '/' 以维持目录层级语义，
     * 防止路径中出现 '?'、'#' 等字符篡改请求语义。
     */
    private fun encodeGithubPath(path: String): String =
        path.split("/").joinToString("/") { Uri.encode(it) }
}
