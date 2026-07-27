package com.zaijian.zhoumuyun.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

/**
 * Fix-BubbleTextSelect：角色气泡长按操作菜单。
 *
 * 背景：长按气泡此前是"秒复制整条消息"，无法框选局部文字（MarkdownText 内部的
 * TextView 走的是纯只读展示模式，isClickable/isLongClickable 全关，触摸事件
 * 透传给外层 Compose 手势，见 MarkdownText.kt 的 Fix-LongClickReset）。
 *
 * 现在长按改为先弹这个小菜单，用户自己选：
 *   ·"复制"     —— 保留原有秒复制整条消息的体验
 *   ·"选择文字" —— 把气泡内所有 MarkdownText 切到系统文字选择模式，
 *                   可拖手柄框选任意一段（由调用方驱动 selectable 状态，
 *                   本组件只负责菜单本身，不涉及 TextView 切换）。
 *
 * 视觉上不用 Material 默认 DropdownMenu（圆角矩形+纯白底，跟气泡的羊皮纸/黄铜
 * 语言不是一家人），照着气泡自己的配色手写一个小浮层：深色玄夜底 + 金线描边，
 * 两个选项之间用一条极细竖线分隔。
 *
 * @param anchorOffset 浮层左上角相对锚点的屏幕偏移（像素），由调用方在长按回调里
 *                     用 pointerInput 拿到的坐标算好传入，让菜单贴着手指长按的
 *                     位置出现，而不是固定挂在气泡角上。
 */
@Composable
fun BubbleActionMenu(
    visible: Boolean,
    anchorOffset: IntOffset,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    Popup(
        offset = anchorOffset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.92f),
            exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.92f),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(Palette.Night)
                    .border(
                        width = 1.dp,
                        color = Palette.Gold.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(Radius.sm),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuAction(
                    label = "复制",
                    icon = AppIcons.Copy,
                    onClick = { onCopy(); onDismiss() },
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .padding(vertical = 10.dp)
                        .fillMaxHeight()
                        .background(Palette.Gold.copy(alpha = 0.25f)),
                )
                MenuAction(
                    label = "选择文字",
                    icon = AppIcons.TextSelect,
                    onClick = { onSelectText(); onDismiss() },
                )
            }
        }
    }
}

@Composable
private fun MenuAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val type = ZaijianTheme.typography
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Palette.GoldSoft,
            modifier = Modifier
                .width(16.dp)
                .padding(end = Spacing.xs),
        )
        Text(
            text = label,
            style = type.label,
            color = Palette.NightText,
        )
    }
}
