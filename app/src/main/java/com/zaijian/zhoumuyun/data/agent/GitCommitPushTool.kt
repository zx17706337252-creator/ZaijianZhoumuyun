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
        // #60 修复：原先 file.content 编码前没有任何大小上限校验，超大文件内容
        // 会在内存里同时存在「原字符串／字节数组／Base64字符串／JSON字符串」多份
        // 拷贝，理论上可致 OOM。这里设一个宽松但明确的单文件上限（2MB，对代码/文本
        // 文件足够宽裕），编码前先兜底拒绝，而不是等真的内存溢出崩溃。
        const val MAX_FILE_CONTENT_BYTES = 2 * 1024 * 1024
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@withContext toolFailure(name, "files_json 格式错误，请检查后重试。", "files_json_parse_failed", e)
            }

            if (files.isEmpty()) {
                return@withContext ToolResult(name, false, "", "files_json 中没有有效的文件条目")
            }

            // #60 修复：Base64 编码前先校验单文件大小，避免超大内容在编码链路中
            // 同时占用多份内存拷贝。
            val oversizedFile = files.firstOrNull {
                it.content.toByteArray(Charsets.UTF_8).size > MAX_FILE_CONTENT_BYTES
            }
            if (oversizedFile != null) {
                return@withContext ToolResult(
                    toolName = name, success = false, content = "",
                    error = "文件「${oversizedFile.path}」内容过大（超过 ${MAX_FILE_CONTENT_BYTES / 1024 / 1024}MB），已拒绝提交，请拆分或精简内容后重试。",
                    userHint = "文件内容过大",
                )
            }

            try {
                val commitSha = pushFiles(config, branch, message, files)
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "已提交 ${files.size} 个文件到 $branch 分支，commit: $commitSha",
                    userHint = "正在提交代码…",
                )
            } catch (e: PartialCommitException) {
                // #36 修复：区分"整批都没提交"和"提交到一半失败"，把已成功的文件列出来，
                // 避免 LLM/用户拿整份 files_json 重新调用一遍导致已成功的文件重复提交。
                com.zaijian.zhoumuyun.util.AgentLog.error("GitCommitPush",
                    "提交中断，已成功 ${e.succeededPaths.size}/${files.size} 个文件", e)
                val detail = if (e.succeededPaths.isEmpty()) {
                    "文件「${e.failedPath}」提交失败：${e.cause?.message?.take(150)}"
                } else {
                    "已成功提交 ${e.succeededPaths.size} 个文件（${e.succeededPaths.joinToString("、")}）" +
                        (e.commitSha?.let { "，最后一次成功 commit: $it" } ?: "") + "，" +
                        "文件「${e.failedPath}」提交失败：${e.cause?.message?.take(150)}。" +
                        "重试时请只重新提交失败及之后的文件，避免已成功的文件重复提交。"
                }
                ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "",
                    error    = detail,
                    userHint = "提交未完成",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "提交代码到 GitHub 失败，请稍后重试。", "git_commit_push_failed", e)
            }
        }

    private class FilesJsonParseException(message: String) : Exception(message)

    // #36 修复：GitHub Contents API 一次只能 PUT 一个文件，pushFiles 对多文件是逐个
    // 提交（每个文件各自成为一次独立 commit），本质上做不到真正的原子性——要做到
    // 真原子提交需要改用 Git Data API（blob/tree/commit 三步），是更大的改造，这里
    // 不做。但至少要让"部分成功后中断"这件事对调用方可见：原来第 N 个文件 PUT 失败
    // 时整个异常直接从 pushFiles 抛出，前 N-1 个文件其实已经提交成功，execute() 的
    // catch 块只返回一句"git_commit_push_failed"，完全不提示前面已经成功的文件——
    // LLM/用户据此重试整个 files_json，会给已成功的文件重复生成一次 commit。
    // 用专门异常类型携带"已成功提交的路径列表 + 失败文件 + 原因"，execute() 据此
    // 拼出准确的错误信息。
    private class PartialCommitException(
        val succeededPaths: List<String>,
        val failedPath: String,
        val commitSha: String?,
        cause: Throwable,
    ) : Exception(cause.message, cause)

    /**
     * P1 修复（批次4审查报告问题1）：暴露为非 private，便于 cicd_start.execute() 复用
     * 同一套解析逻辑做入口前置校验（见问题2修复），避免两处实现漂移。
     */
    internal fun parseFilesJson(json: String): List<FileToCommit> {
        val arr = try {
            org.json.JSONArray(json)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.zaijian.zhoumuyun.util.ZLog.w("GitCommitPush", "files_json 不是合法的 JSON 数组", e)
            throw FilesJsonParseException("files_json 不是合法的 JSON 数组：${e.message?.take(80)}")
        }
        return (0 until arr.length()).map { i ->
            val obj = try {
                arr.getJSONObject(i)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.zaijian.zhoumuyun.util.ZLog.w("GitCommitPush", "files_json 第 ${i + 1} 个元素不是合法的 JSON 对象", e)
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
        // #36 修复：记录已经成功 PUT 的文件路径，供中途失败时告知调用方
        val succeededPaths = mutableListOf<String>()

        for (file in files) {
            val existingSha = try {
                getFileSha(config, file.path, branch)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.zaijian.zhoumuyun.util.ZLog.w("GitCommitPush", "获取文件 sha 失败：${file.path}", e)
                throw PartialCommitException(succeededPaths.toList(), file.path, latestCommitSha, e)
            }

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
                    } catch (_: Throwable) { null }
                    throw RuntimeException("PUT ${file.path} 返回 $code: ${errorBody ?: conn.responseMessage}")
                }
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val commit = JSONObject(resp).optJSONObject("commit")
                latestCommitSha = commit?.optString("sha")
                succeededPaths += file.path
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 该文件失败前面的文件已经各自成功提交（每个文件是一次独立 commit），
                // 不是"这次提交全部回滚"，把已成功的路径带出去，避免调用方误判为
                // "整体失败、可以整批重试"而对已成功文件重复提交。
                com.zaijian.zhoumuyun.util.ZLog.w("GitCommitPush", "PUT 文件失败：${file.path}", e)
                throw PartialCommitException(succeededPaths.toList(), file.path, latestCommitSha, e)
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
