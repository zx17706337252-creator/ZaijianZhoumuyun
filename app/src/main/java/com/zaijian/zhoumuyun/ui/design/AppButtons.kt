package com.zaijian.zhoumuyun.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaijian.zhoumuyun.ui.theme.AppBrushes
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.theme.snapSpring

// ─────────────────────────────────────────────────────────────
//  AppButtons.kt — UI 升级 v2.0（融合方案 §4.1）按钮族
//
//  五级按钮，全局唯一实现：
//    GoldPrimaryButton   主按钮：黄铜渐变底 + 白字 + inset 顶高光 + 金影
//                        （每屏 ≤1 个：推门进入公馆/保存）
//    RolePrimaryButton   角色主按钮：角色色渐变底 + 白字
//                        （角色语境唯一主行动：发起对话）
//    SecondaryGoldButton 次按钮：12% 金底 + 深金字 + 0.5px 金边
//    GhostGoldButton     幽灵按钮：8% 金底 + 金发丝边 + 次级字（低频操作）
//    DangerVelvetButton  危险按钮：8% 绛红底 + 绛红字 + 绛红边（清空对话）
//
//  金色军规 §1：金色一律走渐变（禁止 #C4A46A 纯色平涂超 24px²）——
//  本文件是军规在按钮上的强制执行点；Material3 Button 的
//  containerColor = colors.accent 纯色平涂写法应逐步迁到这里。
//
//  交互：按压 scale 0.97，80ms snapSpring（融合方案 §7 按压下沉）。
// ─────────────────────────────────────────────────────────────

@Composable
private fun AppButtonBase(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    height: Dp,
    cornerRadius: Dp,
    textColor: Color,
    background: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = snapSpring,
        label = "appBtnPress",
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        background()
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
        )
    }
}

/** 主按钮：黄铜渐变底 + 白字 + 顶部 inset 高光 + 金影。每屏 ≤1 个。 */
@Composable
fun GoldPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
    cornerRadius: Dp = 14.dp,
) {
    AppButtonBase(
        text = text,
        onClick = onClick,
        modifier = modifier,
        height = height,
        cornerRadius = cornerRadius,
        textColor = Color.White,
    ) {
        // 金影（向下偏移的深色底层）
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { translationY = 4.dp.toPx() }
                .background(Palette.GoldDeep.copy(alpha = 0.30f)),
        )
        Box(Modifier.matchParentSize().background(AppBrushes.goldGradient()))
        // inset 顶高光
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.TopCenter)
                .background(AppBrushes.topHighlight(isDark = false)),
        )
    }
}

/** 角色主按钮：角色色渐变底 + 白字。角色语境唯一主行动（发起对话）。 */
@Composable
fun RolePrimaryButton(
    text: String,
    roleColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
    cornerRadius: Dp = 14.dp,
) {
    AppButtonBase(
        text = text,
        onClick = onClick,
        modifier = modifier,
        height = height,
        cornerRadius = cornerRadius,
        textColor = Color.White,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { translationY = 4.dp.toPx() }
                .background(roleColor.copy(alpha = 0.30f)),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(roleColor.brighten(0.10f), roleColor),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.30f), Color.Transparent),
                    ),
                ),
        )
    }
}

/** 次按钮：12% 金底 + 深金字 + 0.5px 金边（查看完整档案/测试连接）。 */
@Composable
fun SecondaryGoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
) {
    val colors = ZaijianTheme.colors
    AppButtonBase(
        text = text,
        onClick = onClick,
        modifier = modifier
            .border(0.5.dp, colors.accent.copy(alpha = 0.40f), RoundedCornerShape(cornerRadius)),
        height = height,
        cornerRadius = cornerRadius,
        textColor = colors.accentDeep,
    ) {
        Box(Modifier.matchParentSize().background(colors.accent.copy(alpha = 0.12f)))
    }
}

/** 幽灵按钮：8% 金底 + 金发丝边 + 次级字（低频操作，可再降 12%/8% 分级）。 */
@Composable
fun GhostGoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
    cornerRadius: Dp = 12.dp,
) {
    val colors = ZaijianTheme.colors
    AppButtonBase(
        text = text,
        onClick = onClick,
        modifier = modifier
            .border(0.5.dp, colors.accent.copy(alpha = 0.32f), RoundedCornerShape(cornerRadius)),
        height = height,
        cornerRadius = cornerRadius,
        textColor = colors.textSecondary,
    ) {
        Box(Modifier.matchParentSize().background(colors.accent.copy(alpha = 0.08f)))
    }
}

/** 危险按钮：8% 绛红底 + 绛红字 + 绛红边（清空对话；文字亦用绛红，不用大红）。 */
@Composable
fun DangerVelvetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
) {
    AppButtonBase(
        text = text,
        onClick = onClick,
        modifier = modifier
            .border(0.5.dp, Palette.Velvet.copy(alpha = 0.35f), RoundedCornerShape(cornerRadius)),
        height = height,
        cornerRadius = cornerRadius,
        textColor = Palette.Velvet,
    ) {
        Box(Modifier.matchParentSize().background(Palette.Velvet.copy(alpha = 0.08f)))
    }
}

/** 角色色提亮（渐变高光端）：向白色插值。 */
private fun Color.brighten(fraction: Float): Color = Color(
    red = red + (1f - red) * fraction,
    green = green + (1f - green) * fraction,
    blue = blue + (1f - blue) * fraction,
    alpha = alpha,
)
