package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.util.ChineseTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * file_search 工具：按文件名或文件内容全文关键词搜索本地已索引文件。
 *
 * 权限过滤（方案 §4.5）：先按路径前缀圈定当前角色可见的目录范围，
 * 再在子集上做 FTS MATCH 查询。不可见的 scope 传 null，
 * SQLite 中 `x LIKE null || '%'` 结果为 null（即 false），天然排除。
 *
 * 修复 #6：原先直接对整个 query 做 "query*" 前缀匹配，中文查询词在
 * FTS4 索引里几乎不可能从第 0 个字符开始与被索引 token 重合（同一
 * 原因见 FileIndexEntity.keywords 字段注释），导致关键词明明存在于
 * 文件中却搜不到（"误判"为未找到）。改为与 MemoryRepository.buildFtsQuery
 * 同款做法：用 ChineseTokenizer 对 query 分词（含 bigram 扩展，兜底
 * 专有名词/未登录词的切分不一致），各词加 "*" 做前缀 OR 匹配，
 * 与写入侧 FileIndexEntity.keywords 的分词结果对齐。
 */
class FileSearchTool(
    private val context: android.content.Context,
    // 默认未注入（-1）：execute 时回退到协程局部的 ctx.characterId。
    // 不默认读 VaultCallContextHolder —— 那是进程级单一 AtomicReference，
    // 私聊+圆桌并发时会被后触发调用覆盖（专项审查报告 #6）。仅测试注入时传正值。
    private val characterIdProvider: () -> Int = { -1 },
) : AgentTool {

    override val name = "file_search"
    override val description = "按文件名或文件内容全文关键词搜索本地已索引文件"
    override val usageNotes = """
        |搜索 vault 内已索引文件（PDF/docx/txt/md 的内容 + 所有文件的文件名）。
        |参数：query（关键词）、file_type（可选，如 pdf/docx/txt）、limit（默认20）。
        |仅搜索当前角色有权访问的目录（私库/项目共享/圆桌共享）。
    """.trimMargin()

    override val paramKeys = listOf("query", "file_type", "limit")

    /**
     * 构建 FTS4 查询字符串（修复 #6）。
     *
     * 与 [com.zaijian.zhoumuyun.data.repository.MemoryRepository.buildFtsQuery]
     * 同款逻辑：[ChineseTokenizer.tokenizeForQuery] 分词 + bigram 扩展，取前 10
     * 个 token 各自加 "*" 做前缀 OR 匹配。分词结果为空（纯符号/纯单字/空白）时
     * 退化为"过滤出字母数字后整体加 *"，与原逻辑对纯英文/数字查询的行为一致
     * （unicode61 本就能正确切分这类查询，不需要走中文分词路径）。
     */
    internal fun buildFtsQuery(query: String): String {
        val tokens = ChineseTokenizer.tokenizeForQuery(query).take(10)
        if (tokens.isEmpty()) {
            val fallback = query.filter { it.isLetterOrDigit() }
            return if (fallback.isBlank()) query else "$fallback*"
        }
        return tokens.joinToString(" ") { "$it*" }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.trim()
        if (query.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", error = "missing query")
        }
        val fileType = params["file_type"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20

        try {
            // 从 VaultCallContext 算出当前角色可见的路径前缀
            // 修复（专项审查报告 #6）：用协程局部的 currentVaultContext()，而非进程级
            // VaultCallContextHolder.get() —— 后者是单一 AtomicReference，私聊+圆桌
            // 并发时会被后触发调用覆盖，导致搜索范围取错、误判文件不存在。
            val ctx = currentVaultContext()
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

            val ftsQuery = buildFtsQuery(query)

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
                    // 提取包含关键词的上下文片段（而非固定取文件开头80字符）
                    val snippet = file.extractedText?.let { text ->
                        val idx = text.indexOf(query, ignoreCase = true)
                        if (idx >= 0) {
                            val start = (idx - 30).coerceAtLeast(0)
                            val end = (idx + query.length + 50).coerceAtMost(text.length)
                            val prefix = if (start > 0) "…" else ""
                            val suffix = if (end < text.length) "…" else ""
                            (prefix + text.substring(start, end).replace("\n", " ") + suffix)
                        } else {
                            text.take(80).replace("\n", " ")
                        }
                    }
                    if (!snippet.isNullOrBlank()) {
                        // 避免在已有省略号结尾的片段后重复追加省略号
                        val suffix = if (snippet.endsWith("…")) "" else "…"
                        appendLine("   内容片段：${snippet}${suffix}")
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
