package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

/**
 * 通用 Dialog 组件（架构瘦身 Phase 1 - 第4项）
 *
 * 收敛原先分散在两处的重复实现：
 *   - ProjectDetailScreen.kt#SingleInputDialog
 *   - ProfileScreen.kt#OptionPickerDialog
 *
 * 均为逻辑等价搬迁：参数签名、交互行为、视觉呈现与原实现保持一致。
 * 两处原实现分别使用 `colors.onBackground/primary/surface` 和
 * `colors.textPrimary/accent/bgCard` 两套写法，但在 AppColors.kt 中
 * 前者是后者的 get() 别名（同一份颜色），迁移到共享组件时统一改用
 * 别名背后的原始命名（textPrimary/accent/bgCard等），不改变任何实际取色。
 *
 * `EditProfileDialog`（编辑昵称+签名，双字段+差异化字数限制）结构与
 * 上述两者不同，本次不纳入收敛范围，仍保留在 ProfileScreen.kt 内。
 */

/**
 * 单输入框 Dialog：标题 + 一个可选多行的输入框 + 确定/取消。
 * 对应原 ProjectDetailScreen.kt#SingleInputDialog。
 */
@Composable
fun SingleInputDialog(
    title: String,
    placeholder: String,
    multiline: Boolean = false,
    maxLength: Int = 500,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val colors = ZaijianTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = colors.textPrimary) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= maxLength) text = it },
                placeholder = { Text(placeholder, color = colors.textPrimary.copy(alpha = 0.35f)) },
                maxLines = if (multiline) 6 else 1,
                singleLine = !multiline,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (text.isNotBlank()) onConfirm(text.trim()) },
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                ),
                supportingText = {
                    Text(
                        text = "${text.length}/$maxLength",
                        color = colors.textPrimary.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                },
            )
        },
        confirmButton = {
            var isConfirming by remember { mutableStateOf(false) }
            TextButton(
                onClick = {
                    if (isConfirming || text.isBlank()) return@TextButton
                    isConfirming = true
                    onConfirm(text.trim())
                },
            ) { Text("确定", color = colors.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.textPrimary.copy(alpha = 0.5f)) }
        },
        containerColor = colors.bgCard,
    )
}

/**
 * 单选列表 Dialog：标题 + 选项列表（当前项高亮+勾选图标）+ 取消。
 * 对应原 ProfileScreen.kt#OptionPickerDialog。
 */
@Composable
fun OptionPickerDialog(
    title: String,
    options: List<String>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    var isSelecting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgCard,
        title = {
            Text(text = title, style = type.cardTitle, color = colors.textPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(
                                if (index == current) colors.accent.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .clickable {
                                if (isSelecting) return@clickable
                                isSelecting = true
                                onSelect(index)
                            }
                            .padding(horizontal = Spacing.md, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = label,
                            style = type.body,
                            color = if (index == current) colors.accent else colors.textPrimary,
                        )
                        if (index == current) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.textSecondary)
            }
        },
    )
}
