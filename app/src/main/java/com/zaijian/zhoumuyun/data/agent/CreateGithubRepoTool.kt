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
    // P0/P1 修复（批次4审查报告问题1/2）：原 description 没说 private/auto_init
    // 只认 "true"，LLM 自然会填 yes/1/y 等常见布尔写法，静默创建公开仓库
    // （不可逆操作）。现补充明确取值范围。
    override val description = "在GitHub上创建一个新仓库（可设为私有/自动初始化）"
    override val usageNotes = "private 和 auto_init 只接受 true 或 false（不区分大小写）；private 不填时默认私有（true），因为创建仓库不可逆，宁可默认保守；写其它值（如 yes/1）会返回错误而不是静默当作 false，请明确使用 true/false"
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

            // P0 修复（批次4审查报告问题1，最严重的一条）：
            // 原逻辑 `params["private"]?.lowercase() == "true"` 对任何非精确 "true"
            // 的取值（"yes"/"1"/"Y" 等 LLM 很自然会写的布尔表达）一律静默当 false，
            // 即"未声明私有"→默认公开创建。仓库一旦在 GitHub 上公开创建，撤销需要
            // 手动删除，属于不可逆副作用，静默降级为公开是不可接受的。
            // 现在改为：① 不传 private 时默认 true（私有）——不可逆操作宁可默认保守；
            // ② 传了但无法识别成 true/false 的值，直接返回明确错误，不再悄悄当 false。
            val rawPrivate = params["private"]?.trim()?.lowercase()
            val isPrivate: Boolean = when (rawPrivate) {
                null, "" -> true  // 未提供：默认私有，而不是默认公开
                "true" -> true
                "false" -> false
                else -> return@withContext ToolResult(
                    name, false, "",
                    "private 参数值 \"${params["private"]}\" 无法识别，请使用 true 或 false" +
                        "（为避免误建公开仓库，此参数不接受 yes/1 等其它写法）",
                )
            }

            val rawAutoInit = params["auto_init"]?.trim()?.lowercase()
            val autoInit: Boolean = when (rawAutoInit) {
                null, "" -> false
                "true" -> true
                "false" -> false
                else -> return@withContext ToolResult(
                    name, false, "",
                    "auto_init 参数值 \"${params["auto_init"]}\" 无法识别，请使用 true 或 false",
                )
            }

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
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Throwable) { null }
                        val msg = when (code) {
                            422 -> "仓库名已存在或名称不合法"
                            else -> "HTTP $code: ${err ?: conn.responseMessage}"
                        }
                        ToolResult(name, false, "", msg)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "创建仓库失败，请稍后重试。", "create_github_repo_failed", e)
            }
        }
}
