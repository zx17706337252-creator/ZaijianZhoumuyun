package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.util.PdfExtractor
import com.zaijian.zhoumuyun.util.PdfExtractor.PdfMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * PdfReadTool — pdf_read 工具。
 *
 * 提取 PDF 的文字 / 元数据 / 生成首页缩略图。不支持生成或编辑 PDF（那是
 * pdf_export 的职责）。
 *
 * ═══════════════════════════════════════════════════════════════
 * 参数
 * ═══════════════════════════════════════════════════════════════
 * - file_path（必填）：PDF 文件路径
 * - mode（可选）：text(默认) / metadata / thumbnail
 *
 * ═══════════════════════════════════════════════════════════════
 * 安全
 * ═══════════════════════════════════════════════════════════════
 * 与 file_read 同一套规则：先 [hasPathTraversal] 拦穿越字符，再
 * [resolveVaultPath] 叠加保险库三段权限校验（角色不能读别人私库的 PDF）。
 *
 * ═══════════════════════════════════════════════════════════════
 * 异常处理
 * ═══════════════════════════════════════════════════════════════
 * 遵循金标准模式：先 rethrow CancellationException 保证协程取消信号不被
 * 吞掉，再 catch Throwable 兜底（含 OOM 等 Error），统一走 [toolFailure]
 * 返回稳定错误码 "pdf_read_failed"，详细堆栈只进 AgentLog。
 */
class PdfReadTool(
    private val context: Context,
    private val characterIdProvider: () -> Int = { VaultCallContextHolder.get().characterId },
) : AgentTool {

    override val name = "pdf_read"
    override val description = "提取PDF的文字/元数据/生成缩略图，不支持生成或编辑PDF"
    override val paramKeys = listOf("file_path", "mode")
    override val usageNotes: String =
        "mode 可选 text(默认,提取全文)/metadata(提取标题作者页数等)/thumbnail(生成首页缩略图)"

    private companion object {
        /** text 模式提取文本的最大字符数，防止超大 PDF 撑爆 LLM 上下文。 */
        const val MAX_TEXT_CHARS = 50000
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val filePath = params["file_path"]?.trim()
        if (filePath.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", error = "missing file_path")
        }

        val mode = params["mode"]?.trim()?.lowercase()?.ifEmpty { "text" } ?: "text"
        if (mode !in setOf("text", "metadata", "thumbnail")) {
            return@withContext ToolResult(
                name, false, "",
                error = "unsupported mode '$mode' (expected text/metadata/thumbnail)",
            )
        }

        if (hasPathTraversal(filePath)) {
            return@withContext ToolResult(name, false, "无法访问该路径。", error = "路径包含非法字符")
        }

        // v147：保险库权限校验——角色不能读别人私库的 PDF。
        val file = when (val r = resolveVaultPath(context, filePath, characterIdProvider)) {
            is VaultPathResolution.Allowed -> r.file
            is VaultPathResolution.Denied -> return@withContext ToolResult(name, false, "无法访问该路径。", r.reason)
        }

        try {
            if (!file.exists() || !file.isFile) {
                return@withContext ToolResult(name, false, "找不到文件「$filePath」。")
            }
            if (!file.name.lowercase().endsWith(".pdf")) {
                return@withContext ToolResult(name, false, "「$filePath」不是 PDF 文件。")
            }

            when (mode) {
                "text" -> {
                    val text = FileInputStream(file).use { stream ->
                        PdfExtractor.extractText(context, stream)
                    }
                    val truncated = text.length > MAX_TEXT_CHARS
                    // 截断时优先在段落边界（双换行）截断，避免切断句子/词组。
                    val body = if (truncated) {
                        val cut = text.take(MAX_TEXT_CHARS)
                        val lastPara = cut.lastIndexOf("\n\n")
                        if (lastPara > MAX_TEXT_CHARS / 2) cut.substring(0, lastPara) else cut
                    } else {
                        text
                    }
                    val suffix = if (truncated) "\n\n[已截断，原文共 ${text.length} 字符]" else ""
                    ToolResult(
                        toolName = name,
                        success = true,
                        content = "[PDF 文本]\n$body$suffix",
                        userHint = "正在读取PDF…",
                    )
                }

                "metadata" -> {
                    val meta: PdfMetadata = FileInputStream(file).use { stream ->
                        PdfExtractor.extractMetadata(context, stream)
                    }
                    val json = JSONObject().apply {
                        put("title", meta.title ?: JSONObject.NULL)
                        put("author", meta.author ?: JSONObject.NULL)
                        put("creationDate", meta.creationDate ?: JSONObject.NULL)
                        put("pageCount", meta.pageCount)
                    }.toString(2)
                    ToolResult(
                        toolName = name,
                        success = true,
                        content = "[PDF 元数据]\n$json",
                        userHint = "正在读取PDF…",
                    )
                }

                else -> { // thumbnail
                    val outputDir = File(context.cacheDir, "pdf_thumbnails").apply { mkdirs() }
                    val thumbPath = FileInputStream(file).use { stream ->
                        PdfExtractor.renderFirstPageThumbnail(context, stream, outputDir)
                    }
                    if (thumbPath == null) {
                        return@withContext ToolResult(
                            name, false, "无法为「$filePath」生成首页缩略图。",
                        )
                    }
                    ToolResult(
                        toolName = name,
                        success = true,
                        content = "[PDF 首页缩略图]\n$thumbPath",
                        userHint = "正在生成PDF缩略图…",
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "PDF读取时遇到问题。", "pdf_read_failed", e)
        }
    }
}
