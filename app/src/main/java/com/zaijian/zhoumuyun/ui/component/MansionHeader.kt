package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.design.AppIcons

// ─────────────────────────────────────────────────────────────
//  MansionHeader  — 公馆顶部 Header（实色背景，44dp）
//  窗口4修正：旧注释写"毛玻璃 56dp"与实际实现不符（实际为实色背景 44dp）
// ─────────────────────────────────────────────────────────────

@Composable
fun MansionHeader(
    title: String = "永恒之家",
    // P3-49 修复（重做）：hasNewNotification 当前无动态数据源。
    // 待确认：通知数据来源（未读任务数/未读消息数/系统通知等），
    // 确认后接入 BottomNavBadgeViewModel 或 PresenceViewModel 的通知标记。
    // 在此之前，该参数无实际效果，红点通知功能不可用。
    hasNewNotification: Boolean = false,
    onNotificationClick: () -> Unit = {},
    onTaskCenterClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 公馆页顶部：无背景框，仅悬浮显示内容行。
    // v54 修复：statusBarsPadding() 已移到 WorldScreen.kt 外层内容
    // Box 统一处理（背景图和 Header 需要避开同一段状态栏高度），
    // 这里不再重复调用，否则状态栏高度会被消费两次，Header 被
    // 多余地进一步下移。MansionHeader 目前只在 WorldScreen 内挂载
    // （已核实项目内无其他调用点），如未来在别处独立使用，需要
    // 调用方自己保证外层已处理好状态栏 inset。
    Box(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)   // 大字体下不截字
                .padding(horizontal = Spacing.screenHorizontal),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // ── 左侧占位 ──────────────────────────────────────
            // M12 修复：去掉 size(40.dp) 覆盖，让 IconButton 使用默认 48×48dp 最小触控区。
            IconButton(onClick = onTaskCenterClick) {
                Icon(
                    imageVector        = AppIcons.CheckCircle,
                    contentDescription = "任务中心",
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(22.dp),
                )
            }

            // ── 中间标题（单行，不显示副标题）────────────────
            Text(
                text  = title,
                style = type.navTitle,
                color = colors.textPrimary,
            )

            // ── 右侧铃铛 ──────────────────────────────────────
            Box {
                // M12 修复：去掉 size(40.dp) 覆盖，让 IconButton 使用默认 48×48dp 最小触控区。
                IconButton(onClick = onNotificationClick) {
                    Icon(
                        imageVector        = AppIcons.Notifications,
                        contentDescription = "通知",
                        tint               = colors.textSecondary,
                        modifier           = Modifier.size(22.dp),
                    )
                }
                // 未读红点
                if (hasNewNotification) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .background(Palette.TaskFailed, CircleShape)
                    )
                }
            }
        }
    }
}
