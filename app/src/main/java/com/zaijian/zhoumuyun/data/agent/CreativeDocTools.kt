package com.zaijian.zhoumuyun.data.agent

/**
 * CreativeDocTools.kt — 创作能力 / 文档生成工具
 *
 * 包含 10 个工具：
 *   WritingCritiqueTool   — 写作批改（writing_critique）
 *   OutlineGenTool        — 大纲生成（outline_gen）
 *   ImageGenPromptTool    — 图生文提示词（image_gen_prompt）
 *   InspirationFetchTool  — 灵感获取（inspiration_fetch）
 *   EmailDraftTool        — 邮件起草（email_draft）
 *   MeetingMinutesTool    — 会议纪要（meeting_minutes）
 *   DocxGenTool           — Word 文档生成（docx_gen）
 *   PdfExportTool         — PDF 导出（pdf_export）
 *   HtmlGenTool           — HTML 页面生成（html_gen）
 *   MarkdownToDocTool     — Markdown 转文档（markdown_to_doc）
 *
 * 注册入口：
 *   AgentToolRegistry.registerCreativeDocTools(context)
 */

import android.content.Context
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────
//  内部辅助：调用 LLM —— 已提取为 AgentTool.callLlm（2.17），此处直接复用
// ─────────────────────────────────────────────────────────────

private suspend fun callLlm(
    providerFn:  () -> LLMProvider?,
    systemPrompt: String,
    userPrompt:   String,
    maxTokens:    Int   = 800,
    temperature:  Float = 0.5f,
): String = AgentTool.callLlm(providerFn, systemPrompt, userPrompt, maxTokens, temperature)

// ─────────────────────────────────────────────────────────────
//  ① WritingCritiqueTool — 写作批评
// ─────────────────────────────────────────────────────────────

/**
 * 写作批评工具。
 *
 * 标签格式：<tool:writing_critique text="{待批评文本}"/>
 *
 * 输出格式（严格按此输出）：
 *   节奏: X.X | 情感: X.X | 人物: X.X | 结尾: X.X
 *   综合评语: （≤80字）
 *   改进建议:
 *   - （≤30字）
 *   - （≤30字）
 *   - （≤30字）
 *
 * text 超过 2000 字时截断，在结果中注明"已截断"。
 */
class WritingCritiqueTool(private val providerFn: () -> LLMProvider?) : AgentTool {

    override val name = "writing_critique"
    override val description = "对一段文字做写作层面的批评打分（节奏/情感/人物/结尾）并给改进建议"
    override val paramKeys = listOf("text")

    companion object {
        const val MAX_TEXT_CHARS = 2000
    }

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val rawText = params["text"]?.trim()
            if (rawText.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 text 参数")
            }

            val truncated = rawText.length > MAX_TEXT_CHARS
            val text = if (truncated) rawText.take(MAX_TEXT_CHARS) else rawText
            val truncateNote = if (truncated) "\n（注：原文超过 $MAX_TEXT_CHARS 字，已截断评分）" else ""

            val prompt = """
请对以下文本从四个维度评分（各 1.0-5.0 分，保留一位小数），并给出改进建议。

严格按如下格式输出，不要任何多余内容：
节奏: X.X | 情感: X.X | 人物: X.X | 结尾: X.X
综合评语: （≤80字）
改进建议:
- （≤30字）
- （≤30字）
- （≤30字）

维度说明：
- 节奏感：句子长短节奏、段落推进速度
- 情感密度：情绪张力是否到位
- 人物塑造：角色立体感与对话真实度
- 结尾处理：收束是否有力、余味如何

——待评文本——
$text
            """.trimIndent()

            return@withContext try {
                val resp = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是专业写作教练，评分客观严格，不吝给低分。",
                    userPrompt   = prompt,
                    maxTokens    = 300,
                    temperature  = 0.3f,
                )
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[写作批评]$truncateNote\n$resp",
                    userHint = "正在批评文本…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "写作批评失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  ② OutlineGenTool — 大纲生成
// ─────────────────────────────────────────────────────────────

/**
 * 大纲生成工具。
 *
 * 标签格式：<tool:outline_gen topic="{主题}" depth="{层级数, 默认2}" style="{essay|story|report}"/>
 *
 * 输出：Markdown 层级结构（# ## ###），可直接渲染。
 * depth 最大值限制为 4，超出时自动降为 4。
 */
class OutlineGenTool(private val providerFn: () -> LLMProvider?) : AgentTool {

    override val name = "outline_gen"
    override val description = "按主题生成分层大纲（Markdown结构），用于写作/报告前期规划"
    override val paramKeys = listOf("topic", "depth", "style")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val topic = params["topic"]?.trim()
            if (topic.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 topic 参数")
            }

            val depth = (params["depth"]?.toIntOrNull() ?: 2).coerceIn(1, 4)
            val style = when (params["style"]?.lowercase()) {
                "story"  -> "故事/小说"
                "report" -> "报告/研究"
                else     -> "文章/论文"
            }

            val prompt = """
请为以下主题生成一份详细大纲，风格：$style，层级深度：$depth 级。

主题：$topic

要求：
- 使用 Markdown 标题格式（# 一级，## 二级，### 三级，以此类推）
- 每个节点简短说明（5-15字）
- 层级控制在 $depth 级以内
- 只输出大纲，不要任何前言
            """.trimIndent()

            return@withContext try {
                val resp = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是专业写作策划师，擅长构建清晰的内容结构。",
                    userPrompt   = prompt,
                    maxTokens    = 600,
                    temperature  = 0.5f,
                )
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[大纲：$topic]\n$resp",
                    userHint = "正在生成大纲…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "大纲生成失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  ③ ImageGenPromptTool — 图片描述词生成
// ─────────────────────────────────────────────────────────────

/**
 * 图片描述词生成工具。
 *
 * 标签格式：<tool:image_gen_prompt description="{中文场景描述}" style="{midjourney|sd|dall-e}"/>
 *
 * 输出：英文 prompt（约 50-150 词）+ 中文说明（30字内）。
 * 未知 style 值降级为 midjourney。
 */
class ImageGenPromptTool(private val providerFn: () -> LLMProvider?) : AgentTool {

    override val name = "image_gen_prompt"
    override val description = "把中文场景描述转换为适合AI绘图工具使用的英文提示词"
    override val paramKeys = listOf("description", "style")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val description = params["description"]?.trim()
            if (description.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 description 参数")
            }

            val style = when (params["style"]?.lowercase()) {
                "sd"     -> "Stable Diffusion"
                "dall-e" -> "DALL-E"
                else     -> "Midjourney"
            }

            val prompt = """
请根据以下中文场景描述，生成一段适合 $style 使用的英文图像生成 prompt。

场景：$description

要求：
1. 英文 prompt 约 50-150 词，包含：画面主体、风格、光线、构图、情绪等关键词
2. 如是 Midjourney，在末尾加上 --ar 16:9 --v 6 等参数（可根据场景调整）
3. 最后一行用中文简述（≤30字）

输出格式（严格遵守）：
[英文 Prompt]
（英文内容）

[中文说明]
（≤30字的中文描述）
            """.trimIndent()

            return@withContext try {
                val resp = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是专业 AI 绘画 prompt 工程师，擅长将场景描述转化为精准的图像生成指令。",
                    userPrompt   = prompt,
                    maxTokens    = 300,
                    temperature  = 0.7f,
                )
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[图像生成 Prompt | $style]\n$resp",
                    userHint = "正在生成图片描述词…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "图片描述词生成失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  ④ InspirationFetchTool — 创意素材拉取
// ─────────────────────────────────────────────────────────────

/**
 * 创意素材拉取工具。
 *
 * 标签格式：<tool:inspiration_fetch theme="{主题}" type="{quote|story|case}" count="{1-5, 默认3}"/>
 *
 * 输出：编号列表，每条含素材正文 + 来源标注。
 * type 未指定时混合返回三类素材。
 */
class InspirationFetchTool(private val providerFn: () -> LLMProvider?) : AgentTool {

    override val name = "inspiration_fetch"
    override val description = "按主题拉取创意素材（名言/故事/案例），用于写作灵感参考"
    override val paramKeys = listOf("theme", "type", "count")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val theme = params["theme"]?.trim()
            if (theme.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 theme 参数")
            }

            val count = (params["count"]?.toIntOrNull() ?: 3).coerceIn(1, 5)
            val typeDesc = when (params["type"]?.lowercase()) {
                "quote" -> "名言/格言"
                "story" -> "历史故事/典故"
                "case"  -> "现实案例/实例"
                else    -> "名言、故事和案例混合"
            }

            val prompt = """
请围绕主题「$theme」，提供 $count 条创意素材，类型：$typeDesc。

每条格式：
序号. 【素材正文】
   来源：（作者/出处/年代）

要求：
- 每条素材控制在 100 字以内
- 来源尽量准确，如不确定可注明"（来源待考）"
- 直接列举，不要任何前言
            """.trimIndent()

            return@withContext try {
                val resp = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是博学的创意写作顾问，掌握大量名言、故事和案例。",
                    userPrompt   = prompt,
                    maxTokens    = 500,
                    temperature  = 0.8f,
                )
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[创意素材：$theme]\n$resp",
                    userHint = "正在拉取创意素材…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "创意素材拉取失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  ⑤ EmailDraftTool — 正式邮件起草
// ─────────────────────────────────────────────────────────────

/**
 * 正式邮件起草工具。
 *
 * 标签格式：<tool:email_draft purpose="{邮件目的}" recipient="{收件人称谓}" tone="{formal|semi-formal}"/>
 *
 * 输出：主题 + 正文 + 署名（三部分清晰分隔）。
 * tone 默认 formal。
 */
class EmailDraftTool(private val providerFn: () -> LLMProvider?) : AgentTool {

    override val name = "email_draft"
    override val description = "起草正式邮件（含主题、正文、署名），用于「帮我写封邮件」的草稿场景"
    override val paramKeys = listOf("purpose", "recipient", "tone")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val purpose = params["purpose"]?.trim()
            if (purpose.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 purpose 参数")
            }

            val recipient = params["recipient"]?.trim() ?: "对方"
            val tone = when (params["tone"]?.lowercase()) {
                "semi-formal" -> "半正式（友好而专业）"
                else          -> "正式（严肃、礼貌）"
            }

            val prompt = """
请起草一封中文邮件：

邮件目的：$purpose
收件人称谓：$recipient
语气风格：$tone

严格按以下格式输出：
主题：（一行，简洁明了）

正文：
（邮件正文，包含合适的开头问候、主体内容、结尾礼貌语）

署名：
（落款，格式：此致/敬上 + 发件人占位符）
            """.trimIndent()

            return@withContext try {
                val resp = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是专业商务写作助手，擅长起草正式和半正式中文邮件。",
                    userPrompt   = prompt,
                    maxTokens    = 600,
                    temperature  = 0.3f,
                )
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[邮件草稿]\n$resp",
                    userHint = "正在起草邮件…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "邮件起草失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  ⑥ MeetingMinutesTool — 会议纪要整理
// ─────────────────────────────────────────────────────────────

/**
 * 会议纪要整理工具。
 *
 * 标签格式：<tool:meeting_minutes content="{对话/要点文本}" title="{会议标题}" date="{日期，可选}"/>
 *
 * 输出：Markdown 格式：会议信息 + 讨论要点 + 决议 + 待办事项。
 * content 超过 3000 字时截断，注明提示。
 */
class MeetingMinutesTool(private val providerFn: () -> LLMProvider?) : AgentTool {

    override val name = "meeting_minutes"
    override val description = "把对话记录或要点整理成结构化的会议纪要"
    override val paramKeys = listOf("content", "title", "date")

    companion object {
        const val MAX_CONTENT_CHARS = 3000
    }

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val rawContent = params["content"]?.trim()
            if (rawContent.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 content 参数")
            }

            val title = params["title"]?.trim() ?: "会议纪要"
            val date  = params["date"]?.trim()  ?: ""

            val truncated = rawContent.length > MAX_CONTENT_CHARS
            val content = if (truncated) rawContent.take(MAX_CONTENT_CHARS) else rawContent
            val truncateNote = if (truncated) "\n\n> ⚠️ 内容已截断，建议分段整理。" else ""

            val dateHeader = if (date.isNotBlank()) "\n📅 日期：$date\n" else "\n"

            val prompt = """
请将以下会议内容整理成规范的 Markdown 会议纪要：

会议标题：$title
${if (date.isNotBlank()) "日期：$date" else ""}

——原始内容——
$content
——内容结束——

严格按以下格式输出：
# $title
$dateHeader
## 讨论要点
（3-8 条，每条简洁，用 - 开头）

## 达成决议
（若有共识事项，用 - 列出；若无则写"暂无明确决议"）

## 待办事项
（若有，用 - 列出，含负责人/截止时间；若无则写"无"）
            """.trimIndent()

            return@withContext try {
                val resp = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是专业会议记录员，擅长将零散内容整理为结构清晰的会议纪要。",
                    userPrompt   = prompt,
                    maxTokens    = 800,
                    temperature  = 0.2f,
                )
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "$resp$truncateNote",
                    userHint = "正在整理会议纪要…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "会议纪要整理失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  ⑦ DocxGenTool — 生成 .docx 文档
// ─────────────────────────────────────────────────────────────

/**
 * 生成 .docx 文档工具。
 *
 * 标签格式：<tool:docx_gen title="{文档标题}" description="{内容描述或 Markdown 原文}"/>
 *
 * 实现（spec 决策二：不引入 Apache POI 主路径）：
 *   Step1: LLM 将描述转为结构化 Markdown
 *   Step2: Markdown → 带 CSS 的独立 HTML（markdownToStyledHtml）
 *   Step3: file_export 落盘，扩展名 .docx（实际存储 HTML，可浏览器打开）
 *
 * 注：文件名以 .docx 结尾（用户期望），但内容为 HTML 格式，
 * 用户可通过 LibreOffice / WPS 打开后另存为真正 .docx。
 */
class DocxGenTool(
    private val providerFn:    () -> LLMProvider?,
    private val fileExportTool: FileExportTool,
) : AgentTool {

    override val name = "docx_gen"
    override val description = "根据描述生成Word文档并导出（实际为HTML，可用WPS打开）"
    override val paramKeys = listOf("title", "description")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val title       = params["title"]?.trim()
            val description = params["description"]?.trim()

            if (title.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 title 参数")
            }
            if (description.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 description 参数")
            }

            return@withContext try {
                // Step1: LLM 生成结构化 Markdown
                val mdPrompt = """
请根据以下描述，生成一份完整的 Markdown 格式文档内容：

标题：$title
描述/要求：$description

要求：
- 使用 Markdown 标题层级（# ## ###）
- 内容丰富、结构清晰
- 直接输出 Markdown，不要代码块包裹
                """.trimIndent()

                val markdown = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是专业文档撰写助手，输出规范的 Markdown 格式文档。",
                    userPrompt   = mdPrompt,
                    maxTokens    = 1200,
                    temperature  = 0.4f,
                )

                // Step2: Markdown → HTML
                val html = markdownToStyledHtml(title, markdown)

                // Step3: file_export 落盘（.docx 扩展名，内容为 HTML）
                val exportResult = fileExportTool.execute(
                    mapOf(
                        "name"    to "$title.docx",
                        "content" to html,
                        "format"  to "html",
                    )
                )

                if (!exportResult.success) {
                    ToolResult(name, false, "文档生成失败：文件写入错误。", exportResult.error)
                } else {
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[文档已生成：$title.docx]\n${exportResult.content}",
                        userHint = "正在生成文档…",
                    )
                }
            } catch (e: Exception) {
                ToolResult(name, false, "文档生成失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  ⑧ PdfExportTool — 生成 PDF
// ─────────────────────────────────────────────────────────────

/**
 * 生成 PDF 工具。
 *
 * 标签格式：<tool:pdf_export title="{文档标题}" content="{Markdown 内容}" orientation="{portrait|landscape}"/>
 *
 * 实现：
 *   Step1: Markdown（或 LLM 生成）→ 带 CSS 的 HTML
 *   Step2: file_export 落盘（.pdf.html），命名区分于普通 html
 *
 * 注：WebView.PrintDocumentAdapter 需要 Activity Context + 主线程，
 * 工具层以 HTML 落盘替代，用户通过浏览器「打印→另存为PDF」完成转换。
 * content 为空时 LLM 自动生成内容。
 */
class PdfExportTool(
    private val providerFn:    () -> LLMProvider?,
    private val fileExportTool: FileExportTool,
    @Suppress("unused") private val context: Context,   // 预留给未来 WebView 打印升级
) : AgentTool {

    override val name = "pdf_export"
    override val description = "根据内容生成PDF文档并导出（需通过浏览器打印另存为PDF完成转换）"
    override val paramKeys = listOf("title", "content", "orientation")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val title       = params["title"]?.trim()
            val rawContent  = params["content"]?.trim()
            val orientation = params["orientation"]?.lowercase() ?: "portrait"

            if (title.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 title 参数")
            }

            return@withContext try {
                val markdown = if (!rawContent.isNullOrEmpty()) {
                    rawContent
                } else {
                    callLlm(
                        providerFn   = providerFn,
                        systemPrompt = "你是专业文档撰写助手，输出规范的 Markdown 格式文档。",
                        userPrompt   = "请生成标题为「$title」的完整文档内容，Markdown 格式，直接输出不加代码块。",
                        maxTokens    = 1200,
                        temperature  = 0.4f,
                    )
                }

                val pageStyle = if (orientation == "landscape")
                    "size: A4 landscape;" else "size: A4 portrait;"

                val html = markdownToStyledHtml(title, markdown, pageStyle)

                val exportResult = fileExportTool.execute(
                    mapOf(
                        "name"    to "$title.pdf.html",
                        "content" to html,
                        "format"  to "html",
                    )
                )

                if (!exportResult.success) {
                    ToolResult(name, false, "PDF 生成失败：文件写入错误。", exportResult.error)
                } else {
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[PDF 文档已准备：$title]\n提示：通过浏览器打开后使用「打印 → 另存为 PDF」导出正式 PDF。\n${exportResult.content}",
                        userHint = "正在生成 PDF…",
                    )
                }
            } catch (e: Exception) {
                ToolResult(name, false, "PDF 生成失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  ⑨ HtmlGenTool — 生成排版 HTML
// ─────────────────────────────────────────────────────────────

/**
 * 生成排版 HTML 工具。
 *
 * 标签格式：<tool:html_gen title="{页面标题}" content="{内容描述}" theme="{light|dark|minimal}"/>
 *
 * 实现：LLM 直接生成完整 HTML+CSS（单文件，CSS 内联）→ file_export 落盘。
 * 要求 LLM 生成自包含 HTML，不依赖外部资源；theme 默认 light。
 */
class HtmlGenTool(
    private val providerFn:    () -> LLMProvider?,
    private val fileExportTool: FileExportTool,
) : AgentTool {

    override val name = "html_gen"
    override val description = "根据描述生成完整排版的独立HTML网页文件并导出"
    override val paramKeys = listOf("title", "content", "theme")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val title   = params["title"]?.trim()
            val content = params["content"]?.trim()
            val theme   = params["theme"]?.lowercase() ?: "light"

            if (title.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 title 参数")
            }
            if (content.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 content 参数")
            }

            val themeDesc = when (theme) {
                "dark"    -> "深色主题（深色背景、浅色文字、卡片风格）"
                "minimal" -> "极简主题（白底黑字、无多余装饰、大量留白）"
                else      -> "浅色主题（白底、适当色彩点缀、专业感）"
            }

            val prompt = """
请生成一个完整的 HTML 页面，要求：
- 标题：$title
- 内容：$content
- 视觉风格：$themeDesc
- CSS 完全内联在 <style> 标签中（不依赖外部 CDN、不引用外部字体 URL）
- 排版美观，支持移动端阅读（max-width + padding 响应式）
- 中文字体用 system-ui 或 "PingFang SC","Microsoft YaHei",sans-serif
- 只输出完整 HTML 代码，从 <!DOCTYPE html> 开始，不加任何解释或 Markdown 代码块
            """.trimIndent()

            return@withContext try {
                val rawHtml = callLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是专业前端工程师，生成自包含的精美 HTML 页面。只输出 HTML 代码，绝对不要解释，不要 Markdown 代码块包裹。",
                    userPrompt   = prompt,
                    maxTokens    = 2000,
                    temperature  = 0.5f,
                )

                // 清理可能残留的 ```html 包裹
                val cleanHtml = rawHtml
                    .replace(Regex("^```html\\s*\\n?", RegexOption.MULTILINE), "")
                    .replace(Regex("\\n?```\\s*$", RegexOption.MULTILINE), "")
                    .trim()

                val exportResult = fileExportTool.execute(
                    mapOf(
                        "name"    to "$title.html",
                        "content" to cleanHtml,
                        "format"  to "html",
                    )
                )

                if (!exportResult.success) {
                    ToolResult(name, false, "HTML 生成失败：文件写入错误。", exportResult.error)
                } else {
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[HTML 页面已生成：$title.html]\n${exportResult.content}",
                        userHint = "正在生成 HTML 页面…",
                    )
                }
            } catch (e: Exception) {
                ToolResult(name, false, "HTML 生成失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  ⑩ MarkdownToDocTool — Markdown 转文档
// ─────────────────────────────────────────────────────────────

/**
 * Markdown 转文档工具。
 *
 * 标签格式：<tool:markdown_to_doc content="{Markdown 文本}" format="{html|pdf}" title="{文件名}"/>
 *
 * 实现：复用 markdownToStyledHtml() + file_export，
 * 是 html_gen/pdf_export 的用户友好入口（已有 Markdown 内容时直接转换，无需 LLM 二次生成）。
 * format 默认 html。
 */
class MarkdownToDocTool(
    @Suppress("unused") private val providerFn: () -> LLMProvider?,   // 预留，当前不需要 LLM
    private val fileExportTool: FileExportTool,
) : AgentTool {

    override val name = "markdown_to_doc"
    override val description = "将已有的Markdown文本转换为HTML或PDF文档并导出"
    override val paramKeys = listOf("content", "format", "title")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val content = params["content"]?.trim()
            if (content.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 content 参数（Markdown 文本）")
            }

            val format = params["format"]?.lowercase() ?: "html"
            val title  = params["title"]?.trim() ?: "文档"

            return@withContext try {
                val pageStyle = if (format == "pdf") "size: A4 portrait;" else ""
                val html = markdownToStyledHtml(title, content, pageStyle)

                val fileName = when (format) {
                    "pdf"  -> "$title.pdf.html"
                    else   -> "$title.html"
                }

                val exportResult = fileExportTool.execute(
                    mapOf(
                        "name"    to fileName,
                        "content" to html,
                        "format"  to "html",
                    )
                )

                if (!exportResult.success) {
                    ToolResult(name, false, "转换失败：${exportResult.error}", exportResult.error)
                } else {
                    val formatNote = if (format == "pdf")
                        "\n提示：通过浏览器打开后使用「打印 → 另存为 PDF」导出正式 PDF。" else ""
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[Markdown 已转换为 $format 格式：$fileName]$formatNote\n${exportResult.content}",
                        userHint = "正在转换 Markdown…",
                    )
                }
            } catch (e: Exception) {
                ToolResult(name, false, "Markdown 转换失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  内部工具函数：Markdown → 带 CSS 的 HTML
// ─────────────────────────────────────────────────────────────

/**
 * 将 Markdown 转换为带排版 CSS 的独立 HTML 文件。
 *
 * 轻量级实现（不依赖第三方库），支持：
 *   - 标题（# ~ ######）
 *   - 粗体（**text**）、斜体（*text*）
 *   - 有序/无序列表
 *   - 代码块（```...```）和行内代码（`code`）
 *   - 引用（> text）
 *   - 水平线（---）
 *   - 段落
 */
internal fun markdownToStyledHtml(
    title:     String,
    markdown:  String,
    pageStyle: String = "",
): String {
    val body = convertMarkdownToHtml(markdown)
    val printStyle = if (pageStyle.isNotBlank()) "@page { $pageStyle margin: 2cm; }" else ""

    return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>$title</title>
<style>
  $printStyle
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    font-family: "PingFang SC","Noto Sans SC","Microsoft YaHei",system-ui,sans-serif;
    font-size: 15px;
    line-height: 1.8;
    color: #2c3e50;
    background: #fff;
    max-width: 800px;
    margin: 0 auto;
    padding: 32px 24px;
  }
  h1 { font-size: 2em; margin: 0.8em 0 0.4em; color: #1a252f; border-bottom: 2px solid #3498db; padding-bottom: 0.3em; }
  h2 { font-size: 1.5em; margin: 1.2em 0 0.4em; color: #2c3e50; border-bottom: 1px solid #ecf0f1; padding-bottom: 0.2em; }
  h3 { font-size: 1.2em; margin: 1em 0 0.3em; color: #34495e; }
  h4,h5,h6 { font-size: 1.05em; margin: 0.8em 0 0.2em; color: #4a5568; }
  p { margin: 0.6em 0; }
  ul,ol { margin: 0.6em 0 0.6em 1.5em; }
  li { margin: 0.25em 0; }
  blockquote { border-left: 4px solid #3498db; margin: 1em 0; padding: 0.5em 1em; background: #f0f7fb; color: #555; border-radius: 0 4px 4px 0; }
  code { background: #f5f5f5; padding: 0.1em 0.4em; border-radius: 3px; font-family: "Courier New",Courier,monospace; font-size: 0.9em; color: #c0392b; }
  pre { background: #2d2d2d; color: #f8f8f2; padding: 1em; border-radius: 6px; overflow-x: auto; margin: 1em 0; }
  pre code { background: none; color: inherit; padding: 0; font-size: 0.85em; }
  hr { border: none; border-top: 1px solid #ddd; margin: 1.5em 0; }
  table { border-collapse: collapse; width: 100%; margin: 1em 0; }
  th,td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }
  th { background: #f0f4f8; font-weight: bold; }
  tr:nth-child(even) { background: #fafafa; }
  @media (max-width: 600px) { body { padding: 16px; font-size: 14px; } }
</style>
</head>
<body>
$body
</body>
</html>
    """.trimIndent()
}

/**
 * 轻量 Markdown → HTML 转换（纯 Kotlin，无 Android 依赖，可用于文件导出）。
 */
private fun convertMarkdownToHtml(markdown: String): String {
    val lines = markdown.lines()
    val sb = StringBuilder()
    var inCodeBlock = false
    var inOrderedList = false
    var inUnorderedList = false

    fun closeList() {
        if (inOrderedList)   { sb.appendLine("</ol>"); inOrderedList   = false }
        if (inUnorderedList) { sb.appendLine("</ul>"); inUnorderedList = false }
    }

    fun escape(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun inlineFormat(raw: String): String {
        var t = escape(raw)
        // 粗体
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
        t = t.replace(Regex("__(.+?)__"))          { "<strong>${it.groupValues[1]}</strong>" }
        // 斜体
        t = t.replace(Regex("\\*(.+?)\\*"))        { "<em>${it.groupValues[1]}</em>" }
        t = t.replace(Regex("_([^_]+)_"))          { "<em>${it.groupValues[1]}</em>" }
        // 行内代码
        t = t.replace(Regex("`([^`]+)`"))          { "<code>${it.groupValues[1]}</code>" }
        return t
    }

    for (line in lines) {
        when {
            line.startsWith("```") -> {
                if (inCodeBlock) {
                    sb.appendLine("</code></pre>")
                    inCodeBlock = false
                } else {
                    closeList()
                    val lang = line.removePrefix("```").trim().ifEmpty { "text" }
                    sb.append("<pre><code class=\"language-$lang\">")
                    inCodeBlock = true
                }
                continue
            }
            inCodeBlock -> {
                sb.appendLine(escape(line))
                continue
            }
        }

        when {
            line.startsWith("###### ") -> { closeList(); sb.appendLine("<h6>${inlineFormat(line.drop(7))}</h6>") }
            line.startsWith("##### ")  -> { closeList(); sb.appendLine("<h5>${inlineFormat(line.drop(6))}</h5>") }
            line.startsWith("#### ")   -> { closeList(); sb.appendLine("<h4>${inlineFormat(line.drop(5))}</h4>") }
            line.startsWith("### ")    -> { closeList(); sb.appendLine("<h3>${inlineFormat(line.drop(4))}</h3>") }
            line.startsWith("## ")     -> { closeList(); sb.appendLine("<h2>${inlineFormat(line.drop(3))}</h2>") }
            line.startsWith("# ")      -> { closeList(); sb.appendLine("<h1>${inlineFormat(line.drop(2))}</h1>") }
            line.startsWith("> ")      -> { closeList(); sb.appendLine("<blockquote>${inlineFormat(line.drop(2))}</blockquote>") }
            line.matches(Regex("^[-*+] .+")) -> {
                if (!inUnorderedList) { closeList(); sb.appendLine("<ul>"); inUnorderedList = true }
                sb.appendLine("<li>${inlineFormat(line.drop(2))}</li>")
            }
            line.matches(Regex("^\\d+\\. .+")) -> {
                if (!inOrderedList) { closeList(); sb.appendLine("<ol>"); inOrderedList = true }
                val item = line.replace(Regex("^\\d+\\. "), "")
                sb.appendLine("<li>${inlineFormat(item)}</li>")
            }
            line.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> {
                closeList(); sb.appendLine("<hr/>")
            }
            line.isBlank() -> {
                closeList()
            }
            else -> {
                closeList()
                sb.appendLine("<p>${inlineFormat(line)}</p>")
            }
        }
    }

    if (inCodeBlock) sb.appendLine("</code></pre>")
    closeList()

    return sb.toString()
}

// ─────────────────────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────────────────────

/**
 * 注册 Phase 28 Part 1 工具（10个：创作能力支撑6 + 文档生成4）。
 * 在 ZaijianApp.onCreate() 中调用。
 */
fun AgentToolRegistry.registerCreativeDocTools(context: Context) {
    val fileExport = FileExportTool.getInstance(context)
    val providerFn: () -> LLMProvider? = AgentTool.defaultProviderFn()
    registerAll(
        WritingCritiqueTool(providerFn = providerFn),
        OutlineGenTool(providerFn = providerFn),
        ImageGenPromptTool(providerFn = providerFn),
        InspirationFetchTool(providerFn = providerFn),
        EmailDraftTool(providerFn = providerFn),
        MeetingMinutesTool(providerFn = providerFn),
        DocxGenTool(providerFn = providerFn, fileExportTool = fileExport),
        PdfExportTool(providerFn = providerFn, fileExportTool = fileExport, context = context),
        HtmlGenTool(providerFn = providerFn, fileExportTool = fileExport),
        MarkdownToDocTool(providerFn = providerFn, fileExportTool = fileExport),
    )
}
