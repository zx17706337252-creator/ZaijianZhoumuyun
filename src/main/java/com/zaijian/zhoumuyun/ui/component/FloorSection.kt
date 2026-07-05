package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.FloorEnum
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  FloorSection  — 一个楼层区块（楼层标签 + 3个窗口）
// ─────────────────────────────────────────────────────────────

@Composable
fun FloorSection(
    floor: FloorEnum,
    characters: List<CharacterConfig>,     // 该楼层的 3 个角色
    presenceMap: Map<Int, PresenceState>,
    onWindowClick: (Int) -> Unit,
    onWindowLongClick: (Int) -> Unit,
    archWidth: Dp,
    archHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(modifier = modifier.fillMaxWidth()) {

        // ── 楼层标签 ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧短线装饰
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(0.5.dp)
                    .background(floorDividerColor(floor, colors.isDark))
            )

            Text(
                text      = floor.labelChinese(),
                style     = type.label,
                color     = floorLabelColor(floor, colors.isDark),
                modifier  = Modifier.padding(horizontal = Spacing.sm),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(0.5.dp)
                    .background(floorDividerColor(floor, colors.isDark))
            )
        }

        // ── 三个窗口 ──────────────────────────────────────────
        // 整个楼层用楼层背景色做大容器，增加建筑感
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(floorContainerBrush(floor, colors.isDark))
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        ) {
            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // 确保刚好3列，空缺时展示锁定占位
                val slots = (1..3).map { col -> characters.find { it.shelfCol == col } }

                slots.forEach { char ->
                    if (char != null) {
                        val presence = presenceMap[char.id]
                            ?: PresenceState(
                                characterId = char.id,
                                statusText  = "…",
                                statusType  = com.zaijian.zhoumuyun.data.model.StatusType.OFFLINE,
                                lastUpdated = 0L,
                            )

                            WindowCard(
                                character     = char,
                                presence      = presence,
                                archWidth     = archWidth,
                                archHeight    = archHeight,
                                onClick       = { onWindowClick(char.id) },
                                onLongClick   = { onWindowLongClick(char.id) },
                                modifier      = Modifier.weight(1f),
                            )
                    } else {
                        // 空槽（理论上不存在，安全保护）
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  FloorEnum helpers
// ─────────────────────────────────────────────────────────────

fun FloorEnum.labelChinese(): String = when (this) {
    FloorEnum.SECOND   -> "二楼"
    FloorEnum.FIRST    -> "一楼"
    FloorEnum.BASEMENT -> "地下室"
}

private fun floorLabelColor(floor: FloorEnum, isDark: Boolean): Color =
    if (isDark) when (floor) {
        FloorEnum.SECOND   -> Color(0xFFD4BC88)  // 暖金（二楼最亮）
        FloorEnum.FIRST    -> Color(0xFFC0A070)  // 暖铜（一楼）
        FloorEnum.BASEMENT -> Color(0xFF9898B8)  // 冷紫（地下室）
    } else when (floor) {
        FloorEnum.SECOND   -> Color(0xFFC4A46A)  // 做旧金
        FloorEnum.FIRST    -> Color(0xFFAA8860)  // 暖棕
        FloorEnum.BASEMENT -> Color(0xFF9898B8)  // 冷紫
    }

private fun floorDividerColor(floor: FloorEnum, isDark: Boolean): Color =
    floorLabelColor(floor, isDark).copy(alpha = 0.30f)

@Composable
private fun floorContainerBrush(floor: FloorEnum, isDark: Boolean): Brush =
    if (isDark) {
        Brush.verticalGradient(when (floor) {
            FloorEnum.SECOND   -> listOf(Color(0x22D4BC88), Color(0x00000000))  // 暖金光
            FloorEnum.FIRST    -> listOf(Color(0x1EC0A070), Color(0x00000000))  // 暖铜光
            FloorEnum.BASEMENT -> listOf(Color(0x26000830), Color(0x00000000))  // 冷紫暗光
        })
    } else {
        Brush.verticalGradient(when (floor) {
            FloorEnum.SECOND   -> listOf(Color(0x14F5EDD8), Color(0x00FFFFFF))  // 暖米白
            FloorEnum.FIRST    -> listOf(Color(0x12F0E8D0), Color(0x00FFFFFF))  // 暖米黄
            FloorEnum.BASEMENT -> listOf(Color(0x12EEEEF5), Color(0x00FFFFFF))  // 淡紫白
        })
    }
