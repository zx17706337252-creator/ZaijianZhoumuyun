package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.design.AppIcons

// ─────────────────────────────────────────────────────────────
//  SendButton — 聊天/圆桌共享的发送按钮
//
//  窗口16审计【问题E1】修复：ChatInputBar 和 RoundtableInputBar 此前
//  各自实现发送按钮，结构不一致——ChatInputBar 有 P2-5 修复带来的
//  48dp 触摸区外壳（内层 32dp 视觉圆），RoundtableInputBar 只有裸的
//  32dp（触摸区=视觉区），未同步该无障碍修复。
//
//  这里只统一"结构"（触摸区尺寸、clip 位置、图标尺寸），不强行统一
//  视觉——两处背景配色本就不同（ChatInputBar 用 Gold 渐变，
//  RoundtableInputBar 用 colors.accent 纯色），通过 background 参数
//  传入，避免改动引入视觉回归。
// ─────────────────────────────────────────────────────────────
@Composable
internal fun SendButton(
    enabled: Boolean,
    background: Brush,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 16.dp,
    iconTint: Color = Palette.White,
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .wrapContentSize(Alignment.Center)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onSend() },
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = AppIcons.Send,
                contentDescription = "发送",
                tint               = iconTint,
                modifier           = Modifier.size(iconSize),
            )
        }
    }
}
