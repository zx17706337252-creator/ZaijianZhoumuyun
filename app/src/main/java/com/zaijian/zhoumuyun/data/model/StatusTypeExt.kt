package com.zaijian.zhoumuyun.data.model

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────
//  StatusType → dot color  (设计规范 §8)
// ─────────────────────────────────────────────────────────────

fun StatusType.dotColor(): Color = when (this) {
    StatusType.ACTIVE  -> Color(0xFF6BCB8B)  // 活跃 · 绿
    StatusType.IDLE    -> Color(0xFFF6C858)  // 空闲 · 黄
    StatusType.FOCUSED -> Color(0xFF8FA8C9)  // 专注 · 蓝
    StatusType.OFFLINE -> Color(0xFFBCC3CE)  // 离线 · 灰
}

fun StatusType.labelChinese(): String = when (this) {
    StatusType.ACTIVE  -> "活跃"
    StatusType.IDLE    -> "空闲"
    StatusType.FOCUSED -> "专注"
    StatusType.OFFLINE -> "离线"
}
