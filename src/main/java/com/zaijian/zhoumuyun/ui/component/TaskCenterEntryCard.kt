package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.theme.parchmentSurface

// ─────────────────────────────────────────────────────────────
//  TaskCenterEntryCard  — 公馆底部任务中心入口
//  v2.1 — parchmentSurface + 金边（去除 CornerOrnamentBox）
//  角落花纹为稀有装饰，只用于 CharacterDetailScreen
//  顶部画框级别的信息卡，功能型卡片不加
// ─────────────────────────────────────────────────────────────

@Composable
fun TaskCenterEntryCard(
    activeTaskCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .parchmentSurface(isDark = colors.isDark)
            .border(
                width = 0.5.dp,
                color = Palette.Gold.copy(alpha = if (colors.isDark) 0.18f else 0.28f),
                shape = RoundedCornerShape(Radius.sm),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = 8.dp),   // 从 vertical=Spacing.sm(8) 改为 6dp，更紧凑
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector        = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint               = colors.accent,
                modifier           = Modifier.size(16.dp),   // 从 20dp 缩小
            )
            // 单行：「任务中心」+ 状态文案合并
            val statusText = if (activeTaskCount > 0) "$activeTaskCount 个进行中" else "暂无进行中的任务"
            Text(
                text  = "任务中心  ·  $statusText",
                style = type.label,
                color = colors.textSecondary,
            )
        }

        Icon(
            imageVector        = Icons.Outlined.ArrowForwardIos,
            contentDescription = null,
            tint               = colors.textDisabled,
            modifier           = Modifier.size(12.dp),
        )
    }
}
