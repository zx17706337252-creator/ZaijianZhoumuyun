package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.design.SecondaryGoldButton
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ═══════════════════════════════════════════════════════════════
//  EmptyStateView — 统一空状态组件（窗口3报告 4.2 节）
//
//  替代项目中4套互不相通的空状态实现：
//    - NotificationEmptyState（独立文件，仅文字）
//    - CharacterDetailAbility 内 EmptyState（internal，仅文字）
//    - PersonalScheduleEmptyState（页内私有，图标+文字）
//    - ScheduleEmptyState（页内私有，图标+文字）
//
//  设计要点：
//    - 视觉外壳延续 Design System 令牌（Spacing/Radius）
//    - 插画位预留 illustration: Painter? 可选参数，资源到位前传 null
//    - 走"图标+文字"形式，插画资源到位后无需改组件签名
//
//  UI 升级 v2.0（融合方案帧32 空状态族规范）：
//    图标从裸露 48dp textDisabled 改为「64dp 浅金圆容器 + 金色图标」，
//    与世界卡/简报/通知中心等页面的空状态视觉统一。标题沿用 cardTitle
//    （SerifSC 思源宋体），行动按钮从 Material3 OutlinedButton 迁移到
//    SecondaryGoldButton（12% 金底 + 深金字 + 金边），符合金色军规。
// ═══════════════════════════════════════════════════════════════

/**
 * 统一空状态组件
 *
 * @param icon        场景图标（收拢自 AppIcons 或 Material 默认图标）
 * @param title       主提示文案，如"暂无任务"
 * @param subtitle    次要说明文案（可选）
 * @param actionLabel 可执行下一步按钮文案（可选，如"新建任务"）
 * @param onAction    按钮点击回调（可选）
 * @param illustration 插画资源（可选，预留位，当前传 null 走图标+文字形式）
 * @param modifier    布局修饰符
 */
@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    illustration: Painter? = null,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 插画位：资源到位时显示插画，否则显示图标
        if (illustration != null) {
            androidx.compose.foundation.Image(
                painter = illustration,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
            )
        } else {
            // 帧32 空状态族：64dp 浅金圆容器 + 金色图标
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Palette.GoldSoft.copy(alpha = 0.50f))
                    .border(1.dp, Palette.Gold.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = title,
            style = type.cardTitle,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        if (subtitle != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = subtitle,
                style = type.label,
                color = colors.textDisabled,
                textAlign = TextAlign.Center,
            )
        }

        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.md))
            SecondaryGoldButton(
                text = actionLabel,
                onClick = onAction,
            )
        }
    }
}
