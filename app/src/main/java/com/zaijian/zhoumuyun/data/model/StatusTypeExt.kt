package com.zaijian.zhoumuyun.data.model

import androidx.compose.ui.graphics.Color
import com.zaijian.zhoumuyun.ui.theme.Palette

// ─────────────────────────────────────────────────────────────
//  StatusType → dot color  (设计规范 §8)
//  P2-40 修复：硬编码色值改为引用 Palette token，消除数值级重复。
// ─────────────────────────────────────────────────────────────

fun StatusType.dotColor(): Color = when (this) {
    StatusType.ACTIVE  -> Palette.Online   // 活跃 · 绿
    StatusType.IDLE    -> Palette.Idle     // 空闲 · 黄
    StatusType.FOCUSED -> Palette.Focused  // 专注 · 蓝
    StatusType.OFFLINE -> Palette.Offline  // 离线 · 灰
}

fun StatusType.labelChinese(): String = when (this) {
    StatusType.ACTIVE  -> "活跃"
    StatusType.IDLE    -> "空闲"
    StatusType.FOCUSED -> "专注"
    StatusType.OFFLINE -> "离线"
}
