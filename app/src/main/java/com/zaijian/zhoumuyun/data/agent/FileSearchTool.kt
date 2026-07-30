package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * file_search 工具：按文件名或文件内容全文关键词搜索本地已索引文件。
 *
 * 权限过滤（方案 §4.5）：先按路径前缀圈定当前角色可见的目录范围，
 * 再在子集上做 FTS MATCH 查询。不可见的 scope 传 null，
 * SQLite 中 `x LIKE null || '%'` 结果为 null（即 false），天然排除。
 *
 * FTS 查询词做前缀匹配：query + "*"，支持"永*"这类部分匹配。
 */
class FileSearchTool(
    private val context: android.content.Context,
    private val characterIdProvider: () -> Int = { VaultCallContextHolder.get().characterId },
) : AgentTool {

    override val name = "file_search"
    override val description = "按文件名或文件内容全文关键词搜索本地已索引文件"
    override val usageNotes = """
        |搜索 vault 内已索引文件（PDF/docx/txt/md 的内容 + 所有文件的文件名）。
        |参数：query（关键词）、file_type（可选，如 pdf/docx/txt）、limit（默认20）。
        |仅搜索当前角色有权访问的目录（私库/项目共享/圆桌共享）。
    """.trimMargin()

    override val paramKeys = listOf("query", "file_type", "limit")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.trim()
        if (query.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", error = "missing query")
        }
        val fileType = params["file_type"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20

        try {
            // 从 VaultCallContext 算出当前角色可见的路径前缀
            val ctx = VaultCallContextHolder.get()
            val characterId = characterIdProvider().let { cid ->
                if (cid >= 0) cid else ctx.characterId
            }

            // personal scope：当前角色的私库
            val prefix1 = "vault/personal/$characterId/"
            // project scope：所有角色共享的项目目录
            val prefix2 = "vault/shared/project/"
            // roundtable scope：当前圆桌的共享目录（未参与圆桌时为 null）
            val prefix3 = if (ctx.scope == VaultScope.ROUNDTABLE && ctx.roundtableId != null) {
                "vault/shared/roundtable/${ctx.roundtableId}/"
            } else null

            // FTS 前缀匹配：query + "*"
            val ftsQuery = "$query*"

            val results = AppDatabase.getInstance(context).fileIndexDao().search(
                query = ftsQuery,
                fileType = fileType,
                prefix1 = prefix1,
                prefix2 = prefix2,
                prefix3 = prefix3,
                limit = limit,
            )

            if (results.isEmpty()) {
                return@withContext ToolResult(
                    toolName = name,
                    success = true,
                    content = "[未找到匹配文件]\n关键词：$query",
                    userHint = "正在搜索文件…",
                )
            }

            val content = buildString {
                appendLine("[找到 ${results.size} 个匹配文件]")
                results.forEachIndexed { i, file ->
                    appendLine("${i + 1}. ${file.fileName} (${file.fileType})")
                    appendLine("   路径：${file.filePath}")
                    // 提取匹配片段（前80字符）
                    val snippet = file.extractedText?.take(80)?.replace("\n", " ")
                    if (!snippet.isNullOrBlank()) {
                        appendLine("   内容片段：${snippet}…")
                    }
                }
            }

            ToolResult(
                toolName = name,
                success = true,
                content = content,
                userHint = "正在搜索文件…",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "搜索文件时遇到问题。", "file_search_failed", e)
        }
    }
}
