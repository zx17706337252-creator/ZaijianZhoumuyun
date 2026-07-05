package com.zaijian.zhoumuyun.ui.component

// ─────────────────────────────────────────────────────────────────────────────
//  SharedScheduleComponents — 日程卡片共享组件
//
//  P1-12-2 修复：GlobalScheduleScreen.ScheduleJobCard 与
//  PersonalScheduleScreen.PersonalScheduleCard 各自私有实现了约 130 行
//  视觉相同的卡片 UI，RepeatChip 也各自独立（RepeatChip / RepeatChipMini）。
//
//  本文件抽取两者共用的基础元素：
//    · ScheduleRepeatChip  — 统一重复标签 Chip（取代 RepeatChip + RepeatChipMini）
//    · ScheduleCardShell   — 卡片外壳（Card + border + clickable 展开）
//
//  两个 Screen 的差异通过槽位参数（headerSlot / expandedActionsSlot）表达：
//    · GlobalScheduleScreen 的卡片含角色头像 + 角色名
//    · PersonalScheduleScreen 的卡片含「下次执行时间」+ 「编辑」按钮
//
//  调用方保留各自私有卡片函数（可逐步迁移），本文件提供新的共用入口。
// ─────────────────────────────────────────────────────────────────────────────

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaijian.zhoumuyun.ui.viewmodel.RepeatLabel
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

/**
 * 统一重复标签 Chip，取代 GlobalScheduleScreen 的 [RepeatChip] 与
 * PersonalScheduleScreen 的 [RepeatChipMini]。
 *
 * 两者视觉完全一致，仅入参类型不同（[RepeatLabel] vs [String]），
 * 统一接受 [RepeatLabel]，调用方按需取 [RepeatLabel.text]。
 */
@Composable
fun ScheduleRepeatChip(label: RepeatLabel, accent: Color, disabled: Boolean) {
    val colors    = ZaijianTheme.colors
    val type      = ZaijianTheme.typography
    val chipColor = if (disabled) colors.borderSubtle else accent.copy(alpha = 0.12f)
    val textColor = if (disabled) colors.textDisabled else accent.copy(alpha = 0.85f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(chipColor)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label.text, style = type.label.copy(fontSize = 10.sp), color = textColor)
    }
}

/**
 * 日程卡片外壳——处理 Card 样式、边框、点击展开/收起和展开区动画。
 *
 * @param accentColor  卡片强调色（来自所属角色）
 * @param isDisabled   任务是否已暂停
 * @param headerContent 始终可见的卡片头部内容
 * @param expandedActions 展开后显示的操作按钮行（删除/启用暂停/编辑等）
 */
// WorldCard 接入（精修方案 v1.3 第2/6节）：原 Card+border 手写外壳改用 WorldCard
// 承担 L0-L2 常态层。accentColor 本身语义就是"卡片强调色（来自所属角色）"，
// 与 ownerAccent（L3 身份脊）完全对应，直接传入。isDisabled 时原实现是
// Card containerColor 调浅 + border 变浅两处分别处理，WorldCard 没有暴露
// 整体透明度参数（不为单个调用点改组件签名），改用 Modifier.alpha() 在
// 外层统一调淡，视觉上覆盖原效果（文字也随之变淡，更直观地传达"已禁用"）。
@Composable
fun ScheduleCardShell(
    accentColor: Color,
    isDisabled: Boolean,
    headerContent: @Composable ColumnScope.() -> Unit,
    expandedActions: @Composable RowScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    com.zaijian.zhoumuyun.ui.design.WorldCard(
        modifier    = Modifier
            .fillMaxWidth()
            .alpha(if (isDisabled) 0.5f else 1f),
        ownerAccent = accentColor,
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            headerContent()

            AnimatedVisibility(
                visible = expanded,
                enter   = fadeIn(tween(150)),
                exit    = fadeOut(tween(150)),
            ) {
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    expandedActions()
                }
            }
        }
    }
}

/**
 * 展开操作区的「删除」图标按钮（两个 Screen 完全相同，抽取复用）。
 */
@Composable
fun ScheduleDeleteButton(onDelete: () -> Unit) {
    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector        = Icons.Outlined.Delete,
            contentDescription = "删除",
            tint               = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
            modifier           = Modifier.size(18.dp),
        )
    }
}

/**
 * 展开操作区的「启用/暂停」文字按钮。
 */
@Composable
fun ScheduleToggleButton(isDisabled: Boolean, accentColor: Color, onToggle: () -> Unit) {
    val colors = ZaijianTheme.colors
    TextButton(onClick = onToggle) {
        Text(
            text  = if (isDisabled) "启用" else "暂停",
            style = ZaijianTheme.typography.label,
            color = if (isDisabled) accentColor else colors.textSecondary,
        )
    }
}
