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

/**
 * 1.3：往 FileExportTool 产出的 content（"文件已生成：xxx（大小）\n{metaJson}" 格式）
 * 里的 metaJson 追加 openHint 字段，用于标记"委托生成的伪二进制"文件
 * （docx_gen/pdf_export，实际内容是 HTML，需要浏览器打开另存）。
 *
 * 不改 FileExportTool 本身（它是 file_export 工具，被 LLM 直接调用，改签名/输出
 * 格式影响面更大），只在这两个工具拿到 exportResult 之后原地改写 JSON 段——
 * ExportedFileMeta.extractExportedFileJson 用的是"从末尾往前配平花括号"定位
 * metaJson（见该文件 KDoc），这里同步改用同一套定位逻辑，不再用 \{.*\} 正则。
 *
 * 文件卡片消失根因修复（与 ExportedFileMeta.kt 同批）：原来的 `\{.*\}` 贪婪正则
 * 会被 content 前缀里的杂散花括号（如 title 写成"我的{产品}介绍"生成的文件名）
 * 带偏——要么整段匹配失败，openHint 静默不写入（返回原内容，尚属无害）；要么
 * 更糟：匹配到"从杂散 { 一路到真正 JSON 收尾 }"这段错误范围，
 * `content.replaceRange(match.range, obj.toString())` 会用重新构造的 JSON
 * 替换掉这段错误范围，把本该保留的人类可读前缀文字也一起吞掉、损坏 content 结构，
 * 导致下游 ExportedFileMeta.extractExportedFileJson 即使改好了也解析不出正确结果
 * （因为 content 本身已经被写坏）。改为从末尾定位真正的 JSON 起点后，不会再被
 * 前缀文字里的杂散花括号误导。
 * 解析失败时原样返回，不让 openHint 注入失败影响主流程。
 */
private fun withOpenHint(content: String, openHint: String): String {
    val start = findTrailingJsonObjectStart(content) ?: return content
    val jsonPart = content.substring(start)
    return try {
        val obj = org.json.JSONObject(jsonPart)
        obj.put("openHint", openHint)
        content.substring(0, start) + obj.toString()
    } catch (_: Throwable) {
        content
    }
}

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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "写作批评失败，请稍后重试。", "writing_critique_failed", e)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "大纲生成失败，请稍后重试。", "outline_gen_failed", e)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "图片描述词生成失败，请稍后重试。", "image_prompt_failed", e)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "创意素材拉取失败，请稍后重试。", "inspiration_fetch_failed", e)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "邮件起草失败，请稍后重试。", "email_draft_failed", e)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "会议纪要整理失败，请稍后重试。", "meeting_minutes_failed", e)
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
    // P0 修复：description 只留正面表述，实现细节（HTML 落盘、WPS 另存）挪到 usageNotes。
    // 原文"（实际为HTML，可用WPS打开）"每次随工具列表展示给 LLM，等于每次暗示"这工具不太行"。
    override val description = "根据描述生成Word文档并导出到对话框"
    override val usageNotes = "生成 .docx 扩展名文件（内容为 HTML 格式，可用 WPS/LibreOffice 打开后另存为标准 .docx）。" +
        "title 为文档标题，description 为内容描述或 Markdown 原文，工具会自动生成完整文档内容。"
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
                    ToolResult(name, false, "文档生成失败：文件写入错误。", "file_write_failed")
                } else {
                    // 1.3：docx_gen 产出的是 HTML（伪 .docx），卡片需要提示用户
                    // 走浏览器/WPS 打开另存，不能像真 .docx 那样被 Word 直接识别。
                    val contentWithHint = withOpenHint(
                        content  = exportResult.content,
                        openHint = "提示：需用浏览器或 WPS 打开后另存",
                    )
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[文档已生成：$title.docx]\n$contentWithHint",
                        userHint = "正在生成文档…",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "文档生成失败，请稍后重试。", "docx_gen_failed", e)
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
 * 实现（Fix-RealPdf，v1 起替代"HTML 伪 PDF"方案）：
 *   Step1: Markdown（或 LLM 生成）→ [SimplePdfWriter] 真排版
 *   Step2: writeVaultStream 落盘为真正的 .pdf 二进制文件（application/pdf）
 *
 * 旧实现产出 "$title.pdf.html"（HTML 内容伪装 PDF 文件名，用户需浏览器打开再
 * 「打印→另存为PDF」）——用户反馈"生成的 pdf 是 html 后缀"，且文件管理器/
 * WPS 无法直接识别。现改用 android.graphics.pdf.PdfDocument 本地排版生成真 PDF，
 * 纯 Kotlin 绘制、无 WebView/Activity 依赖、IO 线程即可完成。
 * content 为空时 LLM 自动生成内容。
 */
class PdfExportTool(
    private val providerFn:    () -> LLMProvider?,
    private val fileExportTool: FileExportTool,
    private val context: Context,
) : AgentTool {

    override val name = "pdf_export"
    // P0 修复：description 只留正面表述，实现细节（浏览器打印另存）挪到 usageNotes。
    // 原文"（需通过浏览器打印另存为PDF完成转换）"每次随工具列表展示给 LLM，等于每次暗示"这工具不太行"。
    override val description = "根据内容生成PDF文档并导出到对话框"
    override val usageNotes = "导出真正的 .pdf 文件（application/pdf），可直接在应用内预览或用 WPS/系统阅读器打开。" +
        "title 为文档标题，content 为 Markdown 内容（留空则由 LLM 自动生成），" +
        "orientation 可选 portrait(纵向)/landscape(横向)，默认 portrait。"
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

                // Fix-RealPdf：用 PdfDocument 本地排版生成真 PDF 二进制，
                // writeVaultStream 落盘（与 zip_export 同款二进制写入路径）。
                val metaJson = writeVaultStream(
                    context  = context,
                    rawFileName = "$title.pdf",
                    mimeType = "application/pdf",
                ) { out ->
                    SimplePdfWriter.write(
                        out       = out,
                        title     = title,
                        markdown  = markdown,
                        landscape = orientation == "landscape",
                    )
                }
                val fileName  = org.json.JSONObject(metaJson).optString("fileName", "$title.pdf")
                val sizeBytes = org.json.JSONObject(metaJson).optLong("sizeBytes", 0L)

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[PDF 文档已生成：$title]\n文件已生成：$fileName（${formatSizeLabel(sizeBytes)}）\n$metaJson",
                    userHint = "正在生成 PDF…",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // 协程取消必须重新抛出，不能当成业务失败吞掉
            } catch (e: Throwable) {
                // 与 excel_gen 同批修复：原 catch (e: Exception) 抓不住 Error
                // 子类（如底层渲染/字体相关库触发的 NoClassDefFoundError），
                // 会击穿到 ToolCallInterceptor/ChatMessageOrchestrator 外层，
                // 导致整轮回复静默终止。改为 Throwable 后由这里就近兜住。
                toolFailure(name, "PDF 生成失败，请稍后重试。", "pdf_export_failed", e)
            }
        }
}

/**
 * 文件大小展示（与 BuiltinTools.FileExportTool 内部 formatSize 同款规则，
 * 避免跨文件引用 private 实现）。
 */
private fun formatSizeLabel(bytes: Long): String = when {
    bytes < 1024        -> "${bytes} B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else                -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}

// ─────────────────────────────────────────────────────────────
//  SimplePdfWriter — 真·PDF 生成器（Fix-RealPdf）
//
//  用 android.graphics.pdf.PdfDocument 把 Markdown 文本排版成多页 PDF：
//    - A4 纸（595×842pt，横向时互换），48pt 页边距
//    - 标题 20pt 粗体；# / ## / ### 三级标题分别 16/13.5/12pt 粗体
//    - 正文 11pt；列表项加 "• " 前缀与缩进；自动换行（StaticLayout）与分页
//    - 剥离行内 Markdown 记号（** __ ` 等），保证纯文本展示干净
//  中文走系统默认字体（Typeface.DEFAULT），无需内嵌字体文件。
// ─────────────────────────────────────────────────────────────
private object SimplePdfWriter {

    private const val PAGE_W_PORTRAIT = 595
    private const val PAGE_H_PORTRAIT = 842
    private const val MARGIN = 48f
    private const val PARA_GAP = 7f
    private const val HEADING_GAP_BEFORE = 10f

    private class LineSpec(
        val text: String,
        val paint: android.text.TextPaint,
        val indent: Float,
        val gapBefore: Float,
    )

    fun write(out: java.io.OutputStream, title: String, markdown: String, landscape: Boolean) {
        val pageW = if (landscape) PAGE_H_PORTRAIT else PAGE_W_PORTRAIT
        val pageH = if (landscape) PAGE_W_PORTRAIT else PAGE_H_PORTRAIT
        val contentWidth = (pageW - MARGIN * 2).toInt()

        fun textPaint(size: Float, bold: Boolean, color: Int): android.text.TextPaint =
            android.text.TextPaint().apply {
                isAntiAlias = true
                textSize = size
                typeface = if (bold) android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD,
                ) else android.graphics.Typeface.DEFAULT
                this.color = color
            }

        val titlePaint = textPaint(20f, true, android.graphics.Color.rgb(0x1a, 0x25, 0x2f))
        val h1Paint    = textPaint(16f, true, android.graphics.Color.rgb(0x1a, 0x25, 0x2f))
        val h2Paint    = textPaint(13.5f, true, android.graphics.Color.rgb(0x2c, 0x3e, 0x50))
        val h3Paint    = textPaint(12f, true, android.graphics.Color.rgb(0x34, 0x49, 0x5e))
        val bodyPaint  = textPaint(11f, false, android.graphics.Color.rgb(0x2c, 0x3e, 0x50))
        val quotePaint = textPaint(11f, false, android.graphics.Color.rgb(0x6b, 0x74, 0x80))

        // 行内 Markdown 记号清理：**bold** __bold__ *em* _em_ `code`
        fun cleanInline(raw: String): String = raw
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("__(.+?)__"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace(Regex("`([^`]*)`"), "$1")
            .trim()

        // 把 Markdown 行解析成排版片段
        val specs = mutableListOf<LineSpec>()
        specs.add(LineSpec(cleanInline(title).ifBlank { "文档" }, titlePaint, 0f, 0f))
        var inCodeBlock = false
        for (line in markdown.lines()) {
            when {
                line.startsWith("```") -> { inCodeBlock = !inCodeBlock; continue }
                inCodeBlock -> {
                    if (line.isNotBlank()) specs.add(LineSpec(line, quotePaint, 12f, 0f))
                    continue
                }
                line.startsWith("### ") -> specs.add(LineSpec(cleanInline(line.drop(4)), h3Paint, 0f, HEADING_GAP_BEFORE))
                line.startsWith("## ")  -> specs.add(LineSpec(cleanInline(line.drop(3)), h2Paint, 0f, HEADING_GAP_BEFORE))
                line.startsWith("# ")   -> specs.add(LineSpec(cleanInline(line.drop(2)), h1Paint, 0f, HEADING_GAP_BEFORE))
                line.startsWith("> ")   -> specs.add(LineSpec(cleanInline(line.drop(2)), quotePaint, 12f, 0f))
                line.matches(Regex("^[-*+] .+")) -> specs.add(LineSpec("• " + cleanInline(line.drop(2)), bodyPaint, 14f, 0f))
                line.matches(Regex("^\\d+\\. .+")) -> specs.add(LineSpec(cleanInline(line), bodyPaint, 14f, 0f))
                line.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> specs.add(LineSpec("────────────────", quotePaint, 0f, 0f))
                line.isBlank() -> specs.add(LineSpec("", bodyPaint, 0f, 0f))
                else -> specs.add(LineSpec(cleanInline(line), bodyPaint, 0f, 0f))
            }
        }

        val doc = android.graphics.pdf.PdfDocument()
        try {
            var pageNum = 1
            var page = doc.startPage(
                android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
            )
            var canvas = page.canvas
            var y = MARGIN

            fun startNewPage() {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(
                    android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
                )
                canvas = page.canvas
                y = MARGIN
            }

            for (spec in specs) {
                // 空行：只推进段间距，不排版
                if (spec.text.isEmpty()) {
                    y += PARA_GAP
                    continue
                }
                val availableWidth = (contentWidth - spec.indent).toInt().coerceAtLeast(48)
                val layout = android.text.StaticLayout.Builder
                    .obtain(spec.text, 0, spec.text.length, spec.paint, availableWidth)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.25f)
                    .build()
                // 分页①：段落起始的 gapBefore 会把顶部推过页底，且当前页非空白 → 先换页。
                // 只判断"起始位置"而非整段能否放下——单段高度超过一整页可用高度的情况
                // 交给下面的逐行续排处理，避免整段被一次性 draw 到画布边界之外而丢失。
                if (y + spec.gapBefore > pageH - MARGIN && y > MARGIN) {
                    startNewPage()
                }
                y += spec.gapBefore
                // Fix-RealPdf②：超长段落跨页续排。
                // 原实现 layout.draw(canvas) 一次性画整段，当单段高度 > 当前页剩余空间时，
                // 超出页底的内容画到了该页独立 Canvas 边界之外——PdfDocument 每页是独立
                // Canvas，不会自动流入下页、也不会被裁剪显示，直接丢失（实测约 80 行连续
                // 文字会丢约 26 行）。改为用 StaticLayout 逐行 API 按行渲染：每画完一行检查
                // 是否越界，越界则 startNewPage() 后继续画剩余行，无论段落多长都能正确跨页。
                // 逐行平移 + clipRect 是 StaticLayout 单行渲染的标准做法（其单行 draw API 非公开）。
                val lineCount = layout.lineCount
                for (i in 0 until lineCount) {
                    val lineTop = layout.getLineTop(i)
                    val lineBottom = layout.getLineBottom(i)
                    val lineHeight = (lineBottom - lineTop).toFloat()
                    // 分页②：当前行底部将越出页底可用区域，且当前页非空白 → 换页。
                    // 不要求整行能完整放下（极端大字号单行也可能超一页），只保证已画
                    // 内容不越界；换页后从页顶起画这一行，至少头部可见，不会整段丢失。
                    if (y + lineHeight > pageH - MARGIN && y > MARGIN) {
                        startNewPage()
                    }
                    canvas.save()
                    // 平移到当前绘制位置；用 clipRect 限定只渲染第 i 行（layout 内部坐标），
                    // 这样调用 layout.draw 不会把其它行也画出来。lineTop/lineBottom 为该行
                    // 在 layout 内部的上下边界，平移 (y - lineTop) 后该行顶部正好落在 y。
                    canvas.translate(MARGIN + spec.indent, y - lineTop)
                    canvas.clipRect(
                        0f, lineTop.toFloat(),
                        availableWidth.toFloat(), lineBottom.toFloat(),
                    )
                    layout.draw(canvas)
                    canvas.restore()
                    y += lineHeight
                }
                y += PARA_GAP
            }
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
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
                    // Fix-HtmlBlank（浏览器打开空白页 根因之一）：2000 tokens 对
                    // "完整 HTML+大段内联 CSS" 经常不够，输出在 <style> 或 <body>
                    // 中途被 maxTokens 硬截断——残缺 HTML 在浏览器里就是白屏。
                    // 放宽到 4000，配合下方 ensureCompleteHtml 的完整性修复双保险。
                    maxTokens    = 4000,
                    temperature  = 0.5f,
                )

                // 清理可能残留的 ```html 包裹
                val cleanHtml = rawHtml
                    .replace(Regex("^```html\\s*\\n?", RegexOption.MULTILINE), "")
                    .replace(Regex("\\n?```\\s*$", RegexOption.MULTILINE), "")
                    .trim()

                // Fix-HtmlBlank：截断/缺骨架的 HTML 一律修复为可渲染的完整文档，
                // 修复后仍无实际内容则直接判失败（不产出空白文件误导用户）。
                val finalHtml = ensureCompleteHtml(cleanHtml, title)
                if (finalHtml == null) {
                    return@withContext ToolResult(name, false, "HTML 生成失败：模型未产出有效内容。", "html_empty")
                }

                val exportResult = fileExportTool.execute(
                    mapOf(
                        "name"    to "$title.html",
                        "content" to finalHtml,
                        "format"  to "html",
                    )
                )

                if (!exportResult.success) {
                    ToolResult(name, false, "HTML 生成失败：文件写入错误。", "file_write_failed")
                } else {
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[HTML 页面已生成：$title.html]\n${exportResult.content}",
                        userHint = "正在生成 HTML 页面…",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // 协程取消必须重新抛出，不能当成业务失败吞掉
            } catch (e: Throwable) {
                // 与 excel_gen 同批修复：catch Throwable 而非 Exception，避免
                // 意外的 Error 子类击穿到外层导致整轮回复静默终止。
                toolFailure(name, "HTML 生成失败，请稍后重试。", "html_gen_failed", e)
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
    private val context: Context,   // Fix-RealPdf：pdf 分支 writeVaultStream 落盘需要
) : AgentTool {

    override val name = "markdown_to_doc"
    override val description = "将已有的Markdown文本转换为HTML或PDF文档并导出"
    override val paramKeys = listOf("content", "format", "title")
    override val usageNotes = "format 可选 html/pdf"

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val content = params["content"]?.trim()
            if (content.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 content 参数（Markdown 文本）")
            }

            val format = params["format"]?.lowercase() ?: "html"
            val title  = params["title"]?.trim() ?: "文档"

            return@withContext try {
                if (format == "pdf") {
                    // Fix-RealPdf：与 pdf_export 同一条真 PDF 路径，
                    // 不再产出 "$title.pdf.html" 伪 PDF。
                    val metaJson = writeVaultStream(
                        context  = context,
                        rawFileName = "$title.pdf",
                        mimeType = "application/pdf",
                    ) { out ->
                        SimplePdfWriter.write(out, title, content, landscape = false)
                    }
                    val fileName  = org.json.JSONObject(metaJson).optString("fileName", "$title.pdf")
                    val sizeBytes = org.json.JSONObject(metaJson).optLong("sizeBytes", 0L)
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[Markdown 已转换为 pdf 格式：$fileName]\n文件已生成：$fileName（${formatSizeLabel(sizeBytes)}）\n$metaJson",
                        userHint = "正在转换 Markdown…",
                    )
                } else {
                    val html = markdownToStyledHtml(title, content)
                    val exportResult = fileExportTool.execute(
                        mapOf(
                            "name"    to "$title.html",
                            "content" to html,
                            "format"  to "html",
                        )
                    )

                    if (!exportResult.success) {
                        ToolResult(name, false, "Markdown 转换失败：文件写入错误。", "file_write_failed")
                    } else {
                        ToolResult(
                            toolName = name,
                            success  = true,
                            content  = "[Markdown 已转换为 html 格式：$title.html]\n${exportResult.content}",
                            userHint = "正在转换 Markdown…",
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // 协程取消必须重新抛出，不能当成业务失败吞掉
            } catch (e: Throwable) {
                // 与 excel_gen 同批修复：catch Throwable 而非 Exception。
                toolFailure(name, "Markdown 转换失败，请稍后重试。", "md_to_doc_failed", e)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  内部工具函数：HTML 完整性修复（Fix-HtmlBlank）
// ─────────────────────────────────────────────────────────────

/**
 * 把模型输出的 HTML 修复为"浏览器必然可渲染"的完整文档。
 *
 * 背景（用户反馈"html 用浏览器打开无任何渲染内容、空白页面，但对话预览里
 * 能看到源码"）：html_gen 让 LLM 直接输出完整 HTML，两种典型坏产出——
 *   1) maxTokens 截断：文档断在 <style> 块或 <body> 中途，浏览器按"样式未闭合/
 *      正文缺失"解析，白屏；
 *   2) 骨架缺失：模型只输出了内容片段（没有 <html>/<head>/<body> 骨架），
 *      部分浏览器对裸片段渲染异常。
 *
 * 修复策略（按序执行）：
 *   1. 砍掉末尾未闭合的半个标签（"<div class=" 这种截断尾巴）；
 *   2. 完全没有骨架标签时，把内容当 body 片段包进标准骨架；
 *   3. 补齐未闭合的 </style> / </body> / </html>；
 *   4. body 实际内容为空（纯样式/纯标签无文字）→ 返回 null 让上层判失败。
 */
private fun ensureCompleteHtml(raw: String, title: String): String? {
    var html = raw.trim()
    if (html.isEmpty()) return null

    // 1. 砍掉末尾未闭合的半个标签（最后一个 "<" 之后没有 ">"）
    val lastLt = html.lastIndexOf('<')
    val lastGt = html.lastIndexOf('>')
    if (lastLt > lastGt) {
        html = html.substring(0, lastLt).trimEnd()
    }
    if (html.isEmpty()) return null

    // 2. 骨架缺失：无 <html/<!doctype/<body 任一 → 按 body 片段包骨架
    val lower = html.lowercase()
    if (!lower.contains("<html") && !lower.contains("<!doctype") && !lower.contains("<body")) {
        html = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>${escape(title)}</title>
<style>
  body { font-family: "PingFang SC","Noto Sans SC","Microsoft YaHei",system-ui,sans-serif;
         font-size: 15px; line-height: 1.8; color: #2c3e50;
         max-width: 800px; margin: 0 auto; padding: 32px 24px; }
</style>
</head>
<body>
$html
</body>
</html>
        """.trimIndent()
    } else {
        // 3. 补齐未闭合标签（先 style 后 body/html，顺序不能反）
        val sb = StringBuilder(html)
        val hasStyleOpen = Regex("<style[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(sb)
        val hasStyleClose = sb.contains("</style>", ignoreCase = true)
        if (hasStyleOpen && !hasStyleClose) sb.append("\n</style>")
        val hasBodyOpen = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(sb)
        if (hasBodyOpen && !sb.contains("</body>", ignoreCase = true)) sb.append("\n</body>")
        if (sb.contains("<html", ignoreCase = true) && !sb.contains("</html>", ignoreCase = true)) {
            sb.append("\n</html>")
        }
        html = sb.toString()
    }

    // 4. 有效内容校验：剥掉所有标签与空白后必须还有文字，否则视为无效产出
    val visibleText = html
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<[^>]+>"), "")
        .trim()
    return if (visibleText.isEmpty()) null else html
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
<title>${escape(title)}</title>
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
 * HTML 转义（& / < / >）。
 * P3审查批次1修复：原为 convertMarkdownToHtml 内部局部函数，
 * markdownToStyledHtml 中的 `<title>$title</title>` 访问不到，导致 title
 * 未转义直接拼入 HTML（self-XSS 风险，虽低但文件本地打开仍可能触发）。
 * 提升为文件级私有函数，供两处复用。
 */
private fun escape(text: String) = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

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
                    // #16 修复：代码块 language 标签直接拼入 HTML class 属性（class="language-$lang"），
                    // 未做转义/过滤。恶意输入如 ```"><script>alert(1)</script> 可闭合引号注入任意
                    // HTML/JS（XSS 风险）。现对 lang 值做白名单过滤，只保留字母数字和少量安全字符
                    // （- _ + ，覆盖 c++/objective-c/jsx 等常见语言标识），其余字符全部剔除，
                    // 过滤后为空则回退到 "text"。
                    val rawLang = line.removePrefix("```").trim()
                    val lang = rawLang.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '+' }.ifEmpty { "text" }
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
        MarkdownToDocTool(providerFn = providerFn, fileExportTool = fileExport, context = context),
    )
}
