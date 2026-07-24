package com.zaijian.zhoumuyun.ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.zaijian.zhoumuyun.ui.component.SendButton


import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.design.AppIcons




// ─────────────────────────────────────────────────────────────
//  ChatInputBar — 底部输入栏
//  拆分自 ChatScreen.kt（v87 Phase 2）。独立组件，无跨簇依赖。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  ChatInputBar — 底部输入栏
//  规范 §13：输入框圆角 28dp，发送按钮 accentColor 圆形 32dp
// ─────────────────────────────────────────────────────────────

@Composable
internal fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    accentColor: Color,
    bgColor: Color,
    isTyping: Boolean = false,
    onSend: () -> Unit,
    onImport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors       = ZaijianTheme.colors
    val type         = ZaijianTheme.typography
    val canSend      = value.trim().isNotEmpty() && !isTyping

    // 修复（与 RoundtableInputBar/RoundtableHeader 同一类问题）：navigationBarsPadding()
    // 此前在调用方（ChatScreen）的 modifier 里，排在 background()/border() 之前
    // （外层），导致输入栏背景覆盖不到底部导航栏/手势条安全区，消息滚动过去时会
    // 直接透出来，边框也没有顶到屏幕真正的底边。现在挪到这里、放在 background()/
    // border() 之后（内层），背景/边框先按传入的完整尺寸铺满到屏幕最底端。
    Row(
        modifier          = modifier
            .background(bgColor)
            .border(
                width  = 0.5.dp,
                color  = colors.borderSubtle,
                shape  = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
            )
            .navigationBarsPadding()
            .padding(
                horizontal = Spacing.screenHorizontal,
                vertical   = Spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 输入框
        BasicTextField(
            value         = value,
            onValueChange = onValueChange,
            textStyle     = type.body.copy(color = colors.textPrimary),
            cursorBrush   = SolidColor(accentColor),
            // P2-4 修复（重做）：限制最大行数（BasicTextField(String) 重载没有
            // scrollState 参数，内部滚动由该重载自动处理，此前误传导致重载
            // 决议失败、编译器报出一整串看似无关的 unresolved reference）
            maxLines      = 6,
            modifier      = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    if (colors.isDark)
                        colors.bgElevated
                    else
                        colors.bgCard,
                )
                // P2-25 修复：vertical padding 从硬编码 10.dp 改为主题 Spacing
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text  = "说点什么…",
                            style = type.body,
                            color = colors.textDisabled,
                        )
                    }
                    innerTextField()
                }
            },
        )

        // P2-5 修复：导入文件按钮——将 clickable 从内层 32dp Box 上移到外层 48dp Box
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .wrapContentSize(Alignment.Center)
                .clip(CircleShape)
                .clickable { onImport() },
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(colors.textDisabled.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = AppIcons.FolderOpen,
                    contentDescription = "导入文件",
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.width(Spacing.sm))

        // P2-5 修复：发送按钮——将 clickable 从内层 32dp Box 上移到外层 48dp Box
        // 窗口16审计【问题E1】修复：改用共享 SendButton 组件，与 RoundtableInputBar
        // 统一结构（48dp 触摸区），背景保留原有 Gold 渐变配色。
        SendButton(
            enabled = canSend,
            background = if (canSend)
                Brush.linearGradient(
                    // P2-41 修复（重做）：真正传入 start/end 坐标实现水平渐变
                    colors = listOf(
                        Palette.Gold.copy(alpha = 0.90f),
                        Palette.Gold.copy(alpha = 0.65f),
                    ),
                    start = Offset(0f, Float.POSITIVE_INFINITY),
                    end   = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                )
            else
                Brush.linearGradient(
                    colors = listOf(
                        colors.textDisabled.copy(alpha = 0.3f),
                        colors.textDisabled.copy(alpha = 0.3f),
                    ),
                ),
            onSend = onSend,
        )
    }
}
