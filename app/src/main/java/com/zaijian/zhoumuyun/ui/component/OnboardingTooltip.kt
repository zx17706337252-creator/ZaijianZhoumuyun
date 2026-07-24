package com.zaijian.zhoumuyun.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.Elevation
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────
//  OnboardingTooltip  — 首次引导气泡
//  设计规范 §12 连接逻辑
//
//  出现时机：用户第一次与公馆角色互动（长按预览关闭后 5s）
//  外观：
//    ┌────────────────────────────────────┐
//    │ 💡 长按公馆里的窗口，可以翻开她的档案  │   <- 圆角气泡
//    └───────────┬────────────────────────┘
//               ▼  <─── 三角指针对齐「书架」Tab
//  行为：3 秒后自动消失；点击立即消失；只出现一次
//
//  使用方式：在 WorldScreen 的 Box 最底层叠加，
//  modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
// ─────────────────────────────────────────────────────────────

/**
 * @param visible  由 PresenceViewModel.uiState.showOnboardingTooltip 驱动
 * @param onDismiss 3 秒倒计时结束或用户点击后调用
 */
@Composable
fun OnboardingTooltip(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 3 秒自动关闭
    LaunchedEffect(visible) {
        if (visible) {
            delay(3_000L)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible  = visible,
        enter    = fadeIn(tween(AnimDuration.fast)) +
                   slideInVertically(tween(AnimDuration.bottomSheet)) { it / 3 },
        exit     = fadeOut(tween(AnimDuration.fast)) +
                   slideOutVertically(tween(AnimDuration.fast)) { it / 3 },
        modifier = modifier,
    ) {
        // 整体左对齐，指针指向底部导航栏 书架 Tab（第 2 个 Tab，共 5 个，约 x ≈ screenWidth * 0.3）
        // 我们用 padding(start) 让三角对准该位置（约 88dp）
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.padding(
                start  = Spacing.screenHorizontal,
                end    = Spacing.screenHorizontal,
                bottom = Spacing.xs,
            ),
        ) {
            val bubbleBg = if (colors.isDark) colors.bgElevated else colors.bgCard
            val shadowElevation = Elevation.elevated

            // ── 气泡主体 ──────────────────────────────────────
            Box(
                modifier = Modifier
                    .shadow(elevation = shadowElevation, shape = RoundedCornerShape(Radius.sm))
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(bubbleBg)
                    .clickable { onDismiss() }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                Text(
                    text  = "💡  长按公馆里的窗口，可以翻开她的档案",
                    style = type.body,
                    color = colors.textPrimary,
                )
            }

            // ── 三角指针（向下，对齐「书架」Tab）─────────────
            // 书架 Tab 是第 2 个（共 5 个），左边距约 screenWidth * 0.3 - 8dp
            // P3-27 修复（重做）：改用屏幕宽度比例计算，而非对 390dp 屏幕的固定估算值
            // P2-42 修复：Tab 总数由 4 更正为 5，指针比例由 0.25 调整为 0.3
            val screenWidthDp = LocalConfiguration.current.screenWidthDp
            val pointerIndent = (screenWidthDp * 0.3f).dp - 8.dp
            Canvas(
                modifier = Modifier
                    .padding(start = pointerIndent)
                    .size(width = 16.dp, height = 8.dp),
            ) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(
                    path  = path,
                    color = bubbleBg,
                    style = Fill,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Previews
// ─────────────────────────────────────────────────────────────

@Preview(name = "OnboardingTooltip · Dark", showBackground = true,
    backgroundColor = 0xFF12131A.toLong(), widthDp = 390, heightDp = 120)
@Composable
private fun PreviewTooltipDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) {
        OnboardingTooltip(visible = true, onDismiss = {})
    }
}

@Preview(name = "OnboardingTooltip · Light", showBackground = true, widthDp = 390, heightDp = 120)
@Composable
private fun PreviewTooltipLight() {
    ZaijianTheme(appTheme = AppTheme.LIGHT) {
        OnboardingTooltip(visible = true, onDismiss = {})
    }
}
