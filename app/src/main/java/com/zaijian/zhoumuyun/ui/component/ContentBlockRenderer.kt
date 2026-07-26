package com.zaijian.zhoumuyun.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaijian.zhoumuyun.data.model.ContentBlock
import com.zaijian.zhoumuyun.data.model.TextSegmentType
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.WorldBubble
import com.zaijian.zhoumuyun.ui.design.MatBadge
import com.zaijian.zhoumuyun.ui.design.BrassBadge

// ═══════════════════════════════════════════════════════════════
//  ContentBlockRenderer — ContentBlock 渲染组件（窗口3报告 6.3/6.6 节）
//
//  渲染策略（窗口3报告 6.6 节"与 Markwon 的分层衔接"）：
//    - 块级类型（heading/list/code/table/quote）→ 结构化组件直接渲染，不经过 Markwon
//    - 块内部的行内格式（加粗/斜体/删除线）→ 继续由 Markwon 处理（MarkdownText）
//    - paragraph 块内的 TextSegment 按 semanticType 分别渲染：
//      · DIALOGUE → MarkdownText 正常渲染（支持行内 Markdown）
//      · ACTION   → MarkdownText 斜体+浅色
//      · THOUGHT  → MarkdownText 引号+底纹
// ═══════════════════════════════════════════════════════════════

/**
 * ContentBlock 列表渲染器
 *
 * @param blocks    ContentBlockParser 产出的块列表
 * @param textColor 文字颜色（与气泡主题一致）
 * @param style     基础字体样式（通常为 type.body）
 * @param modifier  布局修饰符
 */
@Composable
fun ContentBlockRenderer(
    blocks: List<ContentBlock>,
    textColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (blocks.isEmpty()) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Heading -> HeadingBlock(block, textColor)
                is ContentBlock.Paragraph -> ParagraphBlock(block, textColor, style)
                is ContentBlock.ListBlock -> ListBlockRenderer(block, textColor, style)
                is ContentBlock.Code -> CodeBlockRenderer(block, textColor)
                is ContentBlock.Table -> TableBlockRenderer(block, textColor, style)
                is ContentBlock.Quote -> QuoteBlockRenderer(block, textColor, style)
                is ContentBlock.Document -> DocumentBlockRenderer(block, textColor)
                is ContentBlock.Image -> ImageBlockRenderer(block, textColor)
                is ContentBlock.TableFile -> TableFileBlockRenderer(block, textColor)
                is ContentBlock.Link -> LinkBlockRenderer(block, textColor)
                is ContentBlock.FileBlock -> FileBlockRenderer(block, textColor)
                // Agent 过程类块（窗口7定稿渲染器）
                is ContentBlock.ToolCall -> ToolCallBlockRenderer(block, textColor)
                is ContentBlock.Thinking -> ThinkingBlockRenderer(block, textColor)
                is ContentBlock.MemoryUpdate -> MemoryUpdateBlockRenderer(block, textColor)
                is ContentBlock.WorkflowStep -> WorkflowStepBlockRenderer(block, textColor)
                is ContentBlock.SkillActivity -> SkillActivityBlockRenderer(block, textColor)
            }
        }
    }
}

// ── 标题 ────────────────────────────────────────────────────────

@Composable
private fun HeadingBlock(block: ContentBlock.Heading, textColor: Color) {
    val type = ZaijianTheme.typography
    // H1-H6 降级为统一层级感，复用 AppTypography 字体分级
    val headingStyle = when (block.level) {
        1, 2 -> type.titleBold
        3, 4 -> type.cardTitle
        else -> type.bodyBold
    }
    Text(
        text = block.text,
        style = headingStyle,
        color = textColor,
    )
}

// ── 段落（层1三种语义标记的承载体）──────────────────────────────

@Composable
private fun ParagraphBlock(
    block: ContentBlock.Paragraph,
    textColor: Color,
    style: TextStyle,
) {
    // 优化：如果所有片段都是 DIALOGUE（最常见场景），合并为单个 MarkdownText
    if (block.segments.all { it.semanticType == TextSegmentType.DIALOGUE }) {
        val fullText = block.segments.joinToString("") { it.text }
        MarkdownText(
            markdown = fullText,
            textColor = textColor,
            style = style,
        )
        return
    }

    // 有 ACTION/THOUGHT 片段时，逐片段渲染
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        block.segments.forEach { segment ->
            when (segment.semanticType) {
                TextSegmentType.DIALOGUE -> {
                    if (segment.text.isNotBlank()) {
                        MarkdownText(
                            markdown = segment.text,
                            textColor = textColor,
                            style = style,
                        )
                    }
                }
                TextSegmentType.ACTION -> {
                    // 斜体，颜色降低透明度（比对话文字浅一档）
                    MarkdownText(
                        markdown = segment.text,
                        textColor = textColor.copy(alpha = 0.55f),
                        style = style.copy(fontStyle = FontStyle.Italic),
                    )
                }
                TextSegmentType.THOUGHT -> {
                    // 引号包裹+浅色底纹，行内展示不折叠
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.xs))
                            .background(textColor.copy(alpha = 0.06f))
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    ) {
                        MarkdownText(
                            markdown = "\u201C${segment.text}\u201D",
                            textColor = textColor.copy(alpha = 0.7f),
                            style = style.copy(fontStyle = FontStyle.Italic),
                        )
                    }
                }
            }
        }
    }
}

// ── 列表 ────────────────────────────────────────────────────────

@Composable
private fun ListBlockRenderer(
    block: ContentBlock.ListBlock,
    textColor: Color,
    style: TextStyle,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        block.items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (block.ordered) "${index + 1}." else "\u2022",
                    style = style,
                    color = textColor,
                    modifier = Modifier.width(20.dp),
                )
                MarkdownText(
                    markdown = item,
                    textColor = textColor,
                    style = style,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ── 代码块 ──────────────────────────────────────────────────────

@Composable
private fun CodeBlockRenderer(block: ContentBlock.Code, textColor: Color) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    // 等宽字体卡片，独立于普通文本流
    val scrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = (12 * 1.4).sp,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.bgElevated)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.sm))
            .padding(Spacing.md),
    ) {
        // 修复（代码块长行溢出/闪退）：
        // 原实现只有 verticalScroll 没有 horizontalScroll，长代码行（如 minified JS、
        // 长导入路径）会溢出卡片右边界。加上 horizontalScroll 让长行可横向滚动查看，
        // 同时设 softWrap=false 确保代码不自动换行（保持代码可读性，靠滚动查看完整行）。
        // 外层 Column 用 horizontalScroll + verticalScroll 实现双向滚动。
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .horizontalScroll(horizontalScrollState),
        ) {
            // 语言标签（如果有）
            if (!block.language.isNullOrBlank()) {
                Text(
                    text = block.language,
                    style = type.label,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = Spacing.xs),
                )
            }
            Text(
                text = block.content,
                style = codeStyle,
                color = textColor,
                // softWrap=false 让代码行不自动换行，靠 horizontalScroll 横向滚动查看
                softWrap = false,
            )
        }
    }
}

// ── 表格 ────────────────────────────────────────────────────────

@Composable
private fun TableBlockRenderer(
    block: ContentBlock.Table,
    textColor: Color,
    style: TextStyle,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    // Markdown 表格预览闪退排查连带发现的显示 bug：原先每个单元格只有
    // `widthIn(min = 80.dp)`，没有上限。外层 Column 套了 horizontalScroll，
    // 会给子内容传入"无限宽"的测量约束——Text 在无限宽约束下不会自动换行
    // （软换行只在有限宽度小于文本自然宽度时才触发），于是像"备注"这种长
    // 文本单元格会撑成一整行，把整张表往右无限拉长，用户只能靠横向拖动看，
    // 而不是像期望的那样自动换行、表格宽度贴合屏幕。
    //
    // 加上 max 上限后，即使外层约束是无限宽，widthIn 也会把传给 Text 的
    // maxWidth 钳制在 CELL_MAX_WIDTH 以内——这是个有限值，Text 就会在这个宽度
    // 内正常换行，单元格变高而不是整张表变宽。horizontalScroll 保留作为
    // 兜底：真正列数很多（超过屏幕能放下的列数）时仍然可以横向滚动查看。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .border(1.dp, colors.border, RoundedCornerShape(Radius.sm))
            .background(colors.bgElevated),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.sm),
        ) {
            // 表头
            Row {
                block.headers.forEach { header ->
                    Text(
                        text = header,
                        style = type.bodyBold,
                        color = colors.textPrimary,
                        modifier = Modifier
                            .widthIn(min = CELL_MIN_WIDTH, max = CELL_MAX_WIDTH)
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                }
            }
            // 分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.border),
            )
            // 数据行
            block.rows.forEach { row ->
                Row {
                    row.forEachIndexed { _, cell ->
                        Text(
                            text = cell,
                            style = style,
                            color = textColor,
                            modifier = Modifier
                                .widthIn(min = CELL_MIN_WIDTH, max = CELL_MAX_WIDTH)
                                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        )
                    }
                }
            }
        }
    }
}

/** Markdown 表格单元格宽度区间：短内容不硬撑到 80dp 以上，长内容超过 160dp 就换行而不是把表格拉宽。 */
private val CELL_MIN_WIDTH = 80.dp
private val CELL_MAX_WIDTH = 160.dp

// ── 引用 ────────────────────────────────────────────────────────

@Composable
private fun QuoteBlockRenderer(
    block: ContentBlock.Quote,
    textColor: Color,
    style: TextStyle,
) {
    val colors = ZaijianTheme.colors
    // 左侧竖线 + 缩进的引用样式
    // 用 IntrinsicSize.Min 让 Row 高度由内容决定，竖线 fillMaxHeight 撑满与文字等高
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(colors.accent.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(Spacing.sm))
        MarkdownText(
            markdown = block.text,
            textColor = textColor.copy(alpha = 0.8f),
            style = style,
        )
    }
}

// ── 文档卡片 ────────────────────────────────────────────────────

@Composable
private fun DocumentBlockRenderer(block: ContentBlock.Document, textColor: Color) {
    FileCardShell(
        title = block.title,
        subtitle = block.previewText,
        fileType = block.fileType,
    )
}

// ── 图片卡片 ────────────────────────────────────────────────────

@Composable
private fun ImageBlockRenderer(block: ContentBlock.Image, textColor: Color) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    // 图片卡片：预留位，实际图片加载需接入网络图片库（如 Coil）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.bgElevated)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.sm))
            .padding(Spacing.md),
    ) {
        Column {
            Text(
                text = "[图片] ${block.url}",
                style = type.caption,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!block.caption.isNullOrBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = block.caption,
                    style = type.label,
                    color = textColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ── 表格文件卡片 ────────────────────────────────────────────────

@Composable
private fun TableFileBlockRenderer(block: ContentBlock.TableFile, textColor: Color) {
    FileCardShell(
        title = block.title,
        subtitle = block.rowCount?.let { "$it 行" },
        fileType = "table",
    )
}

// ── 链接卡片 ────────────────────────────────────────────────────

@Composable
private fun LinkBlockRenderer(block: ContentBlock.Link, textColor: Color) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.bgElevated)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.sm))
            .padding(Spacing.md),
    ) {
        Column {
            Text(
                text = block.title,
                style = type.bodyBold,
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!block.description.isNullOrBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = block.description,
                    style = type.caption,
                    color = textColor.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = block.url,
                style = type.label,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── 通用文件卡片 ────────────────────────────────────────────────

@Composable
private fun FileBlockRenderer(block: ContentBlock.FileBlock, textColor: Color) {
    FileCardShell(
        title = block.title,
        subtitle = block.sizeLabel,
        fileType = block.fileType,
    )
}

// ── 通用文件卡片外壳 ────────────────────────────────────────────
//
// 细化方案第二节重做：
//   外壳      bgElevated 纯色 + 1dp border → WorldBubble（L0 渐变 + L1 光斑 + L2 黄铜描边）
//              不加投影——嵌在 WorldBubble 消息气泡内部的子元素，投影会像"贴纸糊在气泡上"
//   类型标识  文字缩写徽标（"PDF"/"TABLE"）→ MatBadge 微立体图标槽
//              图标取 fileIconForType，底色取 fileTypeSemanticColor（文件类型客观语义色）
//   打开动作  无独立触发点 → 右侧 BrassBadge 黄铜圆形箭头徽章
//   颜色语义  不再用 accentColor（原挪用聊天角色色），改用文件类型本身的语义色
@Composable
private fun FileCardShell(
    title: String,
    subtitle: String?,
    fileType: String,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    val fileIcon = AppIcons.fileIconForType(fileType)
    val semanticColor = AppIcons.fileTypeSemanticColor(fileType)

    WorldBubble(
        modifier = Modifier.fillMaxWidth(),
        topStart = Radius.sm,
        topEnd = Radius.sm,
        bottomStart = Radius.sm,
        bottomEnd = Radius.sm,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 文件类型图标槽：MatBadge 微立体徽章（语义色 tint）
            MatBadge(
                icon = fileIcon,
                contentDescription = fileType,
                color = semanticColor,
                badgeSize = 38.dp,
                iconSize = 19.dp,
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = type.bodyBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = type.label,
                        color = colors.textSecondary,
                    )
                }
            }
            // 黄铜"打开"徽章（固定 Gold，不随文件类型色变化——动作符号与内容符号分开）
            BrassBadge(
                size = 32.dp,
            )
        }
    }
}

// ── Agent 过程类块渲染器（窗口7定稿） ──────────────────────────

/**
 * 工具调用块渲染器。
 * 紧凑卡片：工具名 + 状态徽章（色彩区分）+ 可折叠的参数/产出/错误详情。
 */
@Composable
private fun ToolCallBlockRenderer(block: ContentBlock.ToolCall, textColor: Color) {
    var expanded by remember { mutableStateOf(false) }

    val statusColor = when (block.status) {
        ContentBlock.ToolCallStatus.SUCCESS -> Palette.SemanticSuccess
        ContentBlock.ToolCallStatus.FAIL -> Palette.SemanticError
        ContentBlock.ToolCallStatus.TIMEOUT -> Palette.SemanticWarning
        ContentBlock.ToolCallStatus.PENDING -> Palette.SemanticNeutral
    }
    val statusLabel = when (block.status) {
        ContentBlock.ToolCallStatus.SUCCESS -> "成功"
        ContentBlock.ToolCallStatus.FAIL -> "失败"
        ContentBlock.ToolCallStatus.TIMEOUT -> "超时"
        ContentBlock.ToolCallStatus.PENDING -> "进行中"
    }
    val statusIcon = when (block.status) {
        ContentBlock.ToolCallStatus.SUCCESS -> AppIcons.CheckCircle
        ContentBlock.ToolCallStatus.FAIL -> AppIcons.Error
        ContentBlock.ToolCallStatus.TIMEOUT -> AppIcons.HourglassEmpty
        ContentBlock.ToolCallStatus.PENDING -> AppIcons.HourglassEmpty
    }

    val hasDetail = !block.paramsSummary.isNullOrBlank() ||
        !block.outputSummary.isNullOrBlank() ||
        !block.errorMessage.isNullOrBlank() ||
        !block.decisionNote.isNullOrBlank()

    AgentProcessCard(
        iconVector = AppIcons.Build,
        iconTint = statusColor,
        title = block.toolName,
        titleColor = textColor,
        badge = { StatusBadge(text = statusLabel, color = statusColor, icon = statusIcon) },
        subtitle = block.durationMs?.let { formatDuration(it) },
        expandable = hasDetail,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        block.paramsSummary?.takeIf { it.isNotBlank() }?.let { param ->
            DetailLine(label = "参数", content = param, textColor = textColor)
        }
        block.outputSummary?.takeIf { it.isNotBlank() }?.let { output ->
            DetailLine(label = "产出", content = output, textColor = textColor)
        }
        block.errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
            DetailLine(label = "错误", content = err, textColor = textColor, isError = true)
        }
        block.decisionNote?.takeIf { it.isNotBlank() }?.let { note ->
            DetailLine(label = "决策", content = note, textColor = textColor)
        }
    }
}

/**
 * 思考过程块渲染器。
 * 可折叠卡片，默认收起。内容为斜体浅色文字。
 */
@Composable
private fun ThinkingBlockRenderer(block: ContentBlock.Thinking, textColor: Color) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    var expanded by remember { mutableStateOf(false) }

    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(250),
        label = "thinkingChevron",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.bgElevated.copy(alpha = 0.7f))
            .border(0.5.dp, colors.borderSubtle, RoundedCornerShape(Radius.sm)),
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(
                    imageVector = AppIcons.Psychology,
                    contentDescription = null,
                    tint = Palette.Gold.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "思考过程",
                    style = type.label,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (block.content.length > 80) {
                    Icon(
                        imageVector = AppIcons.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = colors.textDisabled,
                        modifier = Modifier.size(16.dp).rotate(chevronAngle),
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded || block.content.length <= 80,
                enter = expandVertically(tween(250)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
            ) {
                Column {
                    if (block.content.length > 80) Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = block.content,
                        style = type.caption.copy(fontStyle = FontStyle.Italic),
                        color = textColor.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * 记忆更新块渲染器。
 */
@Composable
private fun MemoryUpdateBlockRenderer(block: ContentBlock.MemoryUpdate, textColor: Color) {
    val actionLabel = when (block.actionType) {
        ContentBlock.MemoryActionType.CREATE -> "新建"
        ContentBlock.MemoryActionType.UPDATE -> "更新"
        ContentBlock.MemoryActionType.DELETE -> "删除"
    }
    val actionColor = when (block.actionType) {
        ContentBlock.MemoryActionType.CREATE -> Palette.SemanticSuccess
        ContentBlock.MemoryActionType.UPDATE -> Palette.SemanticInfo
        ContentBlock.MemoryActionType.DELETE -> Palette.SemanticError
    }

    AgentProcessCard(
        iconVector = AppIcons.Memory,
        iconTint = Palette.TimelineMemory,
        title = "记忆${actionLabel}",
        titleColor = textColor,
        badge = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (!block.domain.isNullOrBlank()) {
                    StatusBadge(text = block.domain, color = Palette.SemanticNeutral)
                }
                StatusBadge(text = actionLabel, color = actionColor)
            }
        },
        subtitle = null,
        expandable = false,
        expanded = false,
        onToggle = {},
    ) {
        Text(
            text = block.summary,
            style = ZaijianTheme.typography.caption,
            color = textColor.copy(alpha = 0.8f),
        )
    }
}

/**
 * 工作流步骤块渲染器。
 */
@Composable
private fun WorkflowStepBlockRenderer(block: ContentBlock.WorkflowStep, textColor: Color) {
    val statusColor = when (block.status) {
        ContentBlock.WorkflowStepStatus.SUCCESS -> Palette.SemanticSuccess
        ContentBlock.WorkflowStepStatus.FAIL -> Palette.SemanticError
        ContentBlock.WorkflowStepStatus.PENDING -> Palette.SemanticWarning
        ContentBlock.WorkflowStepStatus.SKIPPED -> Palette.SemanticNeutral
    }
    val statusLabel = when (block.status) {
        ContentBlock.WorkflowStepStatus.SUCCESS -> "完成"
        ContentBlock.WorkflowStepStatus.FAIL -> "失败"
        ContentBlock.WorkflowStepStatus.PENDING -> "执行中"
        ContentBlock.WorkflowStepStatus.SKIPPED -> "跳过"
    }

    val hasDetail = !block.outputSummary.isNullOrBlank() ||
        !block.errorMessage.isNullOrBlank() ||
        !block.nextAction.isNullOrBlank()

    var expanded by remember { mutableStateOf(false) }

    AgentProcessCard(
        iconVector = AppIcons.AutoAwesome,
        iconTint = statusColor,
        title = block.stepName,
        titleColor = textColor,
        badge = { StatusBadge(text = statusLabel, color = statusColor) },
        subtitle = null,
        expandable = hasDetail,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        block.outputSummary?.takeIf { it.isNotBlank() }?.let { output ->
            DetailLine(label = "产出", content = output, textColor = textColor)
        }
        block.errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
            DetailLine(label = "错误", content = err, textColor = textColor, isError = true)
        }
        block.nextAction?.takeIf { it.isNotBlank() }?.let { next ->
            DetailLine(label = "下一步", content = next, textColor = textColor)
        }
    }
}

/**
 * 技能活动块渲染器（窗口7·第五类 Agent 过程块）。
 */
@Composable
private fun SkillActivityBlockRenderer(block: ContentBlock.SkillActivity, textColor: Color) {
    val actionLabel = when (block.actionType) {
        ContentBlock.SkillActionType.CREATE -> "创建"
        ContentBlock.SkillActionType.INVOKE -> "调用"
        ContentBlock.SkillActionType.EDIT -> "编辑"
        ContentBlock.SkillActionType.DEACTIVATE -> "废弃"
    }
    val actionColor = when (block.actionType) {
        ContentBlock.SkillActionType.CREATE -> Palette.SemanticSuccess
        ContentBlock.SkillActionType.INVOKE -> Palette.SemanticInfo
        ContentBlock.SkillActionType.EDIT -> Palette.SemanticWarning
        ContentBlock.SkillActionType.DEACTIVATE -> Palette.SemanticError
    }
    val statusColor = when (block.status) {
        ContentBlock.SkillActivityStatus.SUCCESS -> Palette.SemanticSuccess
        ContentBlock.SkillActivityStatus.FAIL -> Palette.SemanticError
        ContentBlock.SkillActivityStatus.PENDING -> Palette.SemanticWarning
    }
    val statusLabel = when (block.status) {
        ContentBlock.SkillActivityStatus.SUCCESS -> "成功"
        ContentBlock.SkillActivityStatus.FAIL -> "失败"
        ContentBlock.SkillActivityStatus.PENDING -> "进行中"
    }

    AgentProcessCard(
        iconVector = AppIcons.School,
        iconTint = actionColor,
        title = block.skillName,
        titleColor = textColor,
        badge = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StatusBadge(text = actionLabel, color = actionColor)
                StatusBadge(text = statusLabel, color = statusColor)
            }
        },
        subtitle = block.description,
        expandable = false,
        expanded = false,
        onToggle = {},
    ) {
    }
}

// ── Agent 过程块共享组件 ───────────────────────────────────────

@Composable
private fun AgentProcessCard(
    iconVector: ImageVector,
    iconTint: Color,
    title: String,
    titleColor: Color,
    badge: @Composable () -> Unit,
    subtitle: String?,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    detailContent: @Composable () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(250),
        label = "agentCardChevron",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.bgElevated.copy(alpha = 0.6f))
            .border(0.5.dp, colors.borderSubtle, RoundedCornerShape(Radius.sm)),
    ) {
        Column(
            modifier = Modifier
                .let { if (expandable) it.clickable { onToggle() } else it }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = title,
                    style = type.bodyBold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                badge()
                if (expandable) {
                    Icon(
                        imageVector = AppIcons.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = colors.textDisabled,
                        modifier = Modifier.size(16.dp).rotate(chevronAngle),
                    )
                }
            }

            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = type.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }

            if (expandable) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(tween(250)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
                ) {
                    Column(
                        modifier = Modifier.padding(top = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        detailContent()
                    }
                }
            } else if (subtitle.isNullOrBlank()) {
                Column(
                    modifier = Modifier.padding(top = Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    detailContent()
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    color: Color,
    icon: ImageVector? = null,
) {
    val type = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = Spacing.xs + 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(10.dp),
            )
        }
        Text(
            text = text,
            style = type.label.copy(fontWeight = FontWeight.Medium),
            color = color,
        )
    }
}

@Composable
private fun DetailLine(
    label: String,
    content: String,
    textColor: Color,
    isError: Boolean = false,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val contentColor = if (isError) Palette.SemanticError else textColor.copy(alpha = 0.7f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = label,
            style = type.label,
            color = colors.textDisabled,
            modifier = Modifier.width(36.dp),
        )
        Text(
            text = content,
            style = type.caption,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${String.format("%.1f", ms / 1000.0)}s"
        else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    }
}
