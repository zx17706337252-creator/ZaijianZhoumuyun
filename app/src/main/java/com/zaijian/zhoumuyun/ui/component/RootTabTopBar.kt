package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ═══════════════════════════════════════════════════════════════
//  RootTabTopBar — 根 Tab 统一顶栏组件（窗口4报告 2.4 节）
//
//  抽取自 ProfileScreen 内联实现，适用于无返回按钮的根 Tab 页面。
//  特征：
//    · 仅标题，无返回箭头
//    · 背景 + borderSubtle 边框 + 点击穿透拦截
//    · statusBarsPadding() + topBarHeight
//    · 标题用 navTitle（17sp），居左对齐
// ═══════════════════════════════════════════════════════════════

/**
 * 根 Tab 统一顶栏
 *
 * @param title     标题文字
 * @param headerBg  顶栏背景色
 * @param modifier  布局修饰符
 * @param actions   右侧操作区（可选，D-2 新增：支持 LearningGoalScreen 等需要徽章/按钮的根 Tab 页）
 */
@Composable
fun RootTabTopBar(
    title: String,
    headerBg: Color,
    modifier: Modifier = Modifier,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(headerBg)
            .border(
                width = 0.5.dp,
                color = colors.borderSubtle,
                shape = RoundedCornerShape(0.dp),
            )
            .statusBarsPadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
            ) {},
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .height(Spacing.topBarHeight)
                .padding(horizontal = Spacing.screenHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = title,
                style    = type.navTitle,
                color    = colors.textPrimary,
            )
            if (actions != null) {
                androidx.compose.foundation.layout.Spacer(
                    Modifier.weight(1f),
                )
                actions()
            }
        }
    }
}
