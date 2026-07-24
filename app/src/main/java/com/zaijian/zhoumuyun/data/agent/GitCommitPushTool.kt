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
    // P0/P1 修复（批次4审查报告问题1，根因③）：原 description 完全没说 files_json
    // 该怎么写，LLM 自然会输出「裸」JSON 数组（files_json="[{"path":...}]"），过去在
    // ToolParser 里会被第一个内引号截断。现 ToolParser 的 findBalancedJsonEnd 已支持
    // `[` 数组配平，裸 JSON 数组可以直接开箱使用，这里补充说明让 LLM 知道两种写法都
    // 支持、优先推荐裸写法（不需要手动转义，最不容易出错）。
    override val description = "把文件内容提交并推送到GitHub仓库指定分支"
    override val usageNotes = "files_json 是文件列表的 JSON 数组，每个元素含 path 和 content 两个字段，例如 files_json=\"[{\"path\":\"a.txt\",\"content\":\"文件内容\"}]\"；直接这样写裸 JSON 即可，不需要额外转义，工具会自动识别并解析"
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
            } catch (e: FilesJsonParseException) {
                // P1 修复（批次4审查报告问题1，根因③）：原来统一报「files_json 格式错误」，
                // 对 LLM/用户毫无诊断价值，看不出是哪个文件、哪个字段出的问题。
                // 现在区分「整体不是合法 JSON 数组」和「第 N 个元素缺字段」两类，给出具体定位。
                return@withContext ToolResult(name, false, "", e.message)
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

    private class FilesJsonParseException(message: String) : Exception(message)

    /**
     * P1 修复（批次4审查报告问题1）：暴露为非 private，便于 cicd_start.execute() 复用
     * 同一套解析逻辑做入口前置校验（见问题2修复），避免两处实现漂移。
     */
    internal fun parseFilesJson(json: String): List<FileToCommit> {
        val arr = try {
            org.json.JSONArray(json)
        } catch (e: Exception) {
            throw FilesJsonParseException("files_json 不是合法的 JSON 数组：${e.message?.take(80)}")
        }
        return (0 until arr.length()).map { i ->
            val obj = try {
                arr.getJSONObject(i)
            } catch (e: Exception) {
                throw FilesJsonParseException("files_json 第 ${i + 1} 个元素不是合法的 JSON 对象")
            }
            val path = if (obj.has("path")) obj.optString("path") else null
            val content = if (obj.has("content")) obj.optString("content") else null
            if (path.isNullOrBlank()) {
                throw FilesJsonParseException("files_json 第 ${i + 1} 个元素缺少 path 字段")
            }
            if (content.isNullOrBlank()) {
                throw FilesJsonParseException("files_json 第 ${i + 1} 个元素（path=$path）缺少 content 字段或内容为空")
            }
            FileToCommit(path = path, content = content)
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
