package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.design.AppIcons

// ═══════════════════════════════════════════════════════════════
//  DetailTopBar — 详情页统一顶栏组件（窗口4报告 2.4 节）
//
//  融合 RoundtableHeader 与 DetailHeader 两套实现的共同模式：
//    · 返回箭头 + navTitle + topBarHeight
//    · 背景与 statusBarsPadding() 挂载在最外层 Box
//    · 点击穿透拦截（空 clickable + indication=null）
//    · 0.5dp borderSubtle 边框
//
//  替代范围：
//    - RoundtableHeader（RoundtableHeader.kt）
//    - DetailHeader（CharacterDetailHeader.kt）
//    - CompetitionScreen 内联顶栏
//    - TimelineScreen 内联顶栏
// ═══════════════════════════════════════════════════════════════

/**
 * 详情页统一顶栏
 *
 * @param title           标题文字
 * @param onBack          返回按钮回调
 * @param headerBg        顶栏背景色
 * @param modifier        布局修饰符
 * @param subtitle        副标题（可选，如成员数）
 * @param backgroundBrush 背景叠加渐变（可选，如楼层光氛围）
 * @param actions         右侧操作区（可选，如设置按钮/取消按钮）
 */
@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    headerBg: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backgroundBrush: Brush? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 外层 Box：背景 + 可选叠加渐变 + 边框 + 点击穿透拦截
    // background()/border() 放在最外层，让背景和边框从屏幕最顶端覆盖（含状态栏区域）
    Box(
        modifier = modifier
            .background(headerBg)
            .then(
                if (backgroundBrush != null)
                    Modifier.background(backgroundBrush, alpha = 0.35f)
                else
                    Modifier
            )
            .border(
                width = 0.5.dp,
                color = colors.borderSubtle,
                shape = RoundedCornerShape(0.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
            ) {},
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(Spacing.topBarHeight)
                .padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector        = AppIcons.ArrowBack,
                    contentDescription = "返回",
                    tint               = colors.textPrimary,
                    modifier           = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = title,
                    style = type.navTitle,
                    color = colors.textPrimary,
                )
                if (subtitle != null) {
                    Text(
                        text  = subtitle,
                        style = type.label,
                        color = colors.textSecondary,
                    )
                }
            }
            if (actions != null) {
                actions()
            }
        }
    }
}
