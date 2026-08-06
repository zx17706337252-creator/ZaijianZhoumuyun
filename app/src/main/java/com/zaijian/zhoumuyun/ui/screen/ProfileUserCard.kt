package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.GoldPrimaryButton
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme


// ─────────────────────────────────────────────────────────────
//  EditNicknameDialog —「称呼」编辑弹窗
//
//  窗口1《信息架构任务书》第二节【本次判断】方案B 执行：
//  原独立"用户信息模块"（UserCard：头像+昵称+签名卡片）整体撤销。
//  - 昵称：保留，降级为 AI 配置区块顶部的一个功能性设置项，
//    由本弹窗承担编辑能力（见 ProfileAiConfigSection.kt「称呼」行）。
//  - 签名：删除，不保留、不迁移、不做预留字段。
//  原 UserCard 组件与"编辑资料"弹窗（含签名字段）已一并移除。
// ─────────────────────────────────────────────────────────────

@Composable
internal fun EditNicknameDialog(
    initialName: String,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var name by remember { mutableStateOf(initialName) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = colors.bgCard,
        title = {
            Text(text = "编辑称呼", style = type.cardTitle, color = colors.textPrimary)
        },
        text = {
            Column {
                Text(text = "AI 怎么称呼你", style = type.label, color = colors.textSecondary)
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it.take(12) },
                    placeholder   = { Text("旅人", style = type.body, color = colors.textDisabled) },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = colors.accent,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor     = colors.textPrimary,
                        unfocusedTextColor   = colors.textPrimary,
                        cursorColor          = colors.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            GoldPrimaryButton(
                text = "保存",
                onClick = { onConfirm(name.ifBlank { "旅人" }) },
            )
        },
        dismissButton = {
            GhostGoldButton(text = "取消", onClick = onDismiss)
        },
    )
}
