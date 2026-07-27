package com.zaijian.zhoumuyun.domain

import com.zaijian.zhoumuyun.data.model.ContentBlock
import com.zaijian.zhoumuyun.data.model.ContentBlockMetadata
import com.zaijian.zhoumuyun.data.model.TextSegment
import com.zaijian.zhoumuyun.data.model.TextSegmentType

// ═══════════════════════════════════════════════════════════════
//  ContentBlockParser — 内容与渲染分离机制解析器（窗口3报告 6.5/6.6 节）
//
//  职责：把 AI 输出的原始文本按 Markdown 语法特征切分成 ContentBlock 列表。
//
//  与 ChatTagParser 的分层衔接（窗口3报告 6.6 节）：
//    1. [mood:xxx]     → 已由 ChatTagParser.stripMoodTag 在上游剥离，不进入本解析器
//    2. [thinking:xxx] → 已由 ChatTagParser.stripThinkingTag 在上游剥离，不进入本解析器
//    3. [action:...]   → 本解析器处理，解析为 TextSegment(ACTION)
//    4. [thought:...]  → 本解析器处理，解析为 TextSegment(THOUGHT)
//
//  解析顺序：
//    代码块(```) → 标题(#) → 表格(|) → 引用(>) → 列表(-/1.) → 段落(其余)
//    代码块优先级最高，``` 包裹的内容不做任何后续解析。
//
//  与 Markwon 的分层（窗口3报告 6.6 节）：
//    块级类型（heading/list/code/table/quote）由 ContentBlock 结构化组件直接渲染，
//    不再经过 Markwon；块内部的行内格式（加粗/斜体/删除线）继续由 Markwon 处理，
//    即 paragraph/quote 等文字类块的内容仍可内嵌行内 Markdown 语法，
//    渲染时局部调用 Markwon（而非整段调用）。
// ═══════════════════════════════════════════════════════════════

object ContentBlockParser {

    // ── 行内语义标记正则（窗口3报告 6.4 节）──────────────────
    // [action:...] 和 [thought:...]，中英文冒号兼容，内部不含方括号
    // 合并正则统一扫描两种标签，capture group 1 区分 action/thought
    private val INLINE_TAG_REGEX = Regex(
        """\[(action|thought)[:：]\s*([^\[\]]*?)\s*]""",
        RegexOption.DOT_MATCHES_ALL,
    )

    // ── Markdown 语法正则 ──────────────────────────────────
    private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
    private val CODE_FENCE_REGEX = Regex("""^```(\w*)\s*$""")
    private val TABLE_ROW_REGEX = Regex("""^\|(.+)\|\s*$""")
    private val TABLE_SEPARATOR_REGEX = Regex("""^\|[\s:|-]+\|\s*$""")
    private val QUOTE_REGEX = Regex("""^>\s?(.*)$""")
    private val UNORDERED_LIST_REGEX = Regex("""^[-*+]\s+(.+)$""")
    private val ORDERED_LIST_REGEX = Regex("""^\d+\.\s+(.+)$""")

    /**
     * 主入口：把文本解析为 ContentBlock 列表
     *
     * @param text AI 输出文本（已由上游 ChatTagParser 剥离 mood/thinking 标签）
     * @return ContentBlock 列表（可能为空列表，表示输入为空或纯空白）
     */
    fun parse(text: String): List<ContentBlock> {
        if (text.isBlank()) return emptyList()

        // Fix-预览闪退：本函数直接在 Compose 组合期间被调用（TextPreviewEditor 的
        // md 预览、ChatMessageBubble 的每条角色消息），此前没有任何异常保护——
        // 调用方传入的是文件原文或 AI 原始输出，内容完全不可控，本解析器内部任何
        // 一处未覆盖到的边界情况（正则/索引）抛出，都会让 Compose 组合直接崩溃，
        // 且与文档具体是 md/txt 无关。外层包一层 try-catch，解析失败时至少把原文
        // 整段当一个普通段落兜底展示，不让用户看到白屏/闪退。
        return try {
            parseInternal(text)
        } catch (e: Throwable) {
            com.zaijian.zhoumuyun.util.ZLog.e("ContentBlockParser", "解析异常，回退为纯文本段落", e)
            listOf(ContentBlock.Paragraph(listOf(TextSegment(text, TextSegmentType.DIALOGUE))))
        }
    }

    private fun parseInternal(text: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val lines = text.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // 1. 代码块（优先级最高，``` 内部不做任何解析）
            val codeFenceMatch = CODE_FENCE_REGEX.matchEntire(line.trim())
            if (codeFenceMatch != null) {
                val language = codeFenceMatch.groupValues[1].ifBlank { null }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !CODE_FENCE_REGEX.matches(lines[i].trim())) {
                    codeLines.add(lines[i])
                    i++
                }
                i++ // 跳过闭合 ```
                val content = codeLines.joinToString("\n")
                val metadata = ContentBlockMetadata(
                    collapsible = content.count { it == '\n' } + 1 > 15,
                    collapseThreshold = 15,
                )
                blocks.add(ContentBlock.Code(content, language, metadata))
                continue
            }

            // 2. 标题
            val headingMatch = HEADING_REGEX.matchEntire(line.trim())
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val headingText = headingMatch.groupValues[2].trim()
                blocks.add(ContentBlock.Heading(headingText, level))
                i++
                continue
            }

            // 3. 表格（连续的 | ... | 行，第一组为表头，第二组为分隔行 |---|）
            val tableMatch = TABLE_ROW_REGEX.matchEntire(line.trim())
            if (tableMatch != null) {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && TABLE_ROW_REGEX.matches(lines[i].trim())) {
                    tableLines.add(lines[i].trim())
                    i++
                }
                val parsed = parseTable(tableLines)
                if (parsed != null) {
                    blocks.add(parsed)
                }
                continue
            }

            // 4. 引用（连续的 > ... 行合并为一条引用）
            val quoteMatch = QUOTE_REGEX.matchEntire(line.trim())
            if (quoteMatch != null) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && QUOTE_REGEX.matches(lines[i].trim())) {
                    quoteLines.add(QUOTE_REGEX.replace(lines[i].trim(), "$1"))
                    i++
                }
                blocks.add(ContentBlock.Quote(quoteLines.joinToString("\n").trim()))
                continue
            }

            // 5. 无序列表
            val unorderedMatch = UNORDERED_LIST_REGEX.matchEntire(line.trim())
            if (unorderedMatch != null) {
                val items = mutableListOf<String>()
                while (i < lines.size && UNORDERED_LIST_REGEX.matches(lines[i].trim())) {
                    items.add(UNORDERED_LIST_REGEX.replace(lines[i].trim(), "$1"))
                    i++
                }
                blocks.add(ContentBlock.ListBlock(items, ordered = false))
                continue
            }

            // 6. 有序列表
            val orderedMatch = ORDERED_LIST_REGEX.matchEntire(line.trim())
            if (orderedMatch != null) {
                val items = mutableListOf<String>()
                while (i < lines.size && ORDERED_LIST_REGEX.matches(lines[i].trim())) {
                    items.add(ORDERED_LIST_REGEX.replace(lines[i].trim(), "$1"))
                    i++
                }
                blocks.add(ContentBlock.ListBlock(items, ordered = true))
                continue
            }

            // 7. 段落（连续的非空行且不匹配以上任何语法 → 合并为一个段落块）
            if (line.isBlank()) {
                i++
                continue
            }

            val paragraphLines = mutableListOf<String>()
            while (i < lines.size &&
                lines[i].isNotBlank() &&
                !CODE_FENCE_REGEX.matches(lines[i].trim()) &&
                !HEADING_REGEX.matches(lines[i].trim()) &&
                !TABLE_ROW_REGEX.matches(lines[i].trim()) &&
                !QUOTE_REGEX.matches(lines[i].trim()) &&
                !UNORDERED_LIST_REGEX.matches(lines[i].trim()) &&
                !ORDERED_LIST_REGEX.matches(lines[i].trim())
            ) {
                paragraphLines.add(lines[i])
                i++
            }

            if (paragraphLines.isNotEmpty()) {
                val paragraphText = paragraphLines.joinToString("\n").trim()
                val segments = parseInlineSegments(paragraphText)
                blocks.add(ContentBlock.Paragraph(segments))
            }
        }

        return blocks
    }

    /**
     * 解析段落内的行内语义标记（窗口3报告 6.4 节）
     *
     * 将 [action:...] 和 [thought:...] 标签解析为 TextSegment，
     * 标签之外的文字为 DIALOGUE 类型。
     *
     * @param text 段落文本（可能包含 [action:...]/[thought:...] 标签）
     * @return TextSegment 列表
     */
    fun parseInlineSegments(text: String): List<TextSegment> {
        if (text.isBlank()) return listOf(TextSegment(text, TextSegmentType.DIALOGUE))

        val segments = mutableListOf<TextSegment>()
        var lastIndex = 0

        for (match in INLINE_TAG_REGEX.findAll(text)) {
            // 标签之前的普通对话文字
            if (match.range.first > lastIndex) {
                val before = text.substring(lastIndex, match.range.first)
                if (before.isNotBlank()) {
                    segments.add(TextSegment(before, TextSegmentType.DIALOGUE))
                }
            }

            val tagType = match.groupValues[1] // "action" or "thought"
            val content = match.groupValues[2].trim()
            val semanticType = when (tagType) {
                "action" -> TextSegmentType.ACTION
                "thought" -> TextSegmentType.THOUGHT
                else -> TextSegmentType.DIALOGUE
            }
            segments.add(TextSegment(content, semanticType))

            lastIndex = match.range.last + 1
        }

        // 末尾剩余的普通对话文字
        if (lastIndex < text.length) {
            val remaining = text.substring(lastIndex)
            if (remaining.isNotBlank()) {
                segments.add(TextSegment(remaining, TextSegmentType.DIALOGUE))
            }
        }

        // 如果没有任何标签，整段都是对话
        if (segments.isEmpty()) {
            segments.add(TextSegment(text, TextSegmentType.DIALOGUE))
        }

        return segments
    }

    /**
     * 解析表格行
     *
     * @param tableLines 形如 ["| H1 | H2 |", "|---|---|", "| a | b |"]
     * @return Table 块，如果格式不符合则 null
     */
    private fun parseTable(tableLines: List<String>): ContentBlock.Table? {
        if (tableLines.size < 2) return null

        // 第一行：表头
        val headers = splitTableCells(tableLines[0])
        if (headers.isEmpty()) return null

        // 跳过分隔行（|---|---|）
        var startIndex = 1
        if (startIndex < tableLines.size && TABLE_SEPARATOR_REGEX.matches(tableLines[startIndex])) {
            startIndex++
        }

        // 数据行
        val rows = mutableListOf<List<String>>()
        for (j in startIndex until tableLines.size) {
            val cells = splitTableCells(tableLines[j])
            if (cells.isNotEmpty()) {
                rows.add(cells)
            }
        }

        return ContentBlock.Table(headers, rows)
    }

    /**
     * 分割表格单元格：| a | b | c | → ["a", "b", "c"]
     */
    private fun splitTableCells(line: String): List<String> {
        return line.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
