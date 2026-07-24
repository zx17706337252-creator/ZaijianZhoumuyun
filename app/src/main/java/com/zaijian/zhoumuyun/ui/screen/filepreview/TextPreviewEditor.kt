package com.zaijian.zhoumuyun.ui.screen.filepreview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.domain.ContentBlockParser
import com.zaijian.zhoumuyun.ui.component.ContentBlockRenderer
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

/**
 * 文本类渲染器（v1.48 应用内预览编辑）。
 *
 * 支持预览/编辑双模式切换：
 * - 预览模式：Markdown 用 MarkdownText 渲染，纯文本用可滚动 Text
 * - 编辑模式：BasicTextField 多行编辑，等宽字体
 *
 * @param text 文本内容
 * @param isMarkdown true=预览模式用 MarkdownText 渲染
 * @param editable true=显示编辑按钮，false=只读（docx 来源）
 * @param onSave 保存回调（编辑模式下显示保存按钮）
 */
@Composable
internal fun TextPreviewEditor(
    text: String,
    isMarkdown: Boolean,
    editable: Boolean,
    onSave: (String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember(text) { mutableStateOf(text) }
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 模式切换 Tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            FilterChip(
                selected = !isEditing,
                onClick = { isEditing = false },
                label = { Text("预览") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.accent.copy(alpha = 0.15f),
                    selectedLabelColor = colors.accent,
                ),
            )
            if (editable) {
                FilterChip(
                    selected = isEditing,
                    onClick = { isEditing = true },
                    label = { Text("编辑") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accent.copy(alpha = 0.15f),
                        selectedLabelColor = colors.accent,
                    ),
                )
            }
        }

        // 内容区
        if (isEditing && editable) {
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
                textStyle = type.body.copy(fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                ),
            )
            // 保存按钮
            Button(
                onClick = { onSave(editText) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
            ) {
                Text("保存")
            }
        } else {
            // 预览模式
            if (isMarkdown) {
                // E2 统一内容渲染接入：Markdown 预览走 ContentBlockParser → ContentBlockRenderer，
                // 支持标题/列表/代码块/表格等结构化渲染。
                val blocks = remember(text) { ContentBlockParser.parse(text) }
                ContentBlockRenderer(
                    blocks    = blocks,
                    textColor = colors.textPrimary,
                    style     = type.body,
                    modifier  = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                )
            } else {
                Text(
                    text = text,
                    style = type.body,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                )
            }
        }
    }
}
