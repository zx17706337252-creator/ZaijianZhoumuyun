package com.zaijian.zhoumuyun.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  AppIcons — 图标统一收口（精修方案 v2.1 2.4）
//
//  总原则：不重新画一套图标库，用"色板+等宽数字+色块背景"包装 Material
//  默认图标提升识别度，不建分模块的图标文件体系——这是唯一的一个
//  AppIcons.kt，不按模块拆分成子文件。
//
//  现状：全项目 141 处 Icons.Outlined.XXX 直接引用，分布在 30 个文件里，
//  一次性全部收拢风险高、验证难度大。方案原文本身也是"逐步把散落引用收拢
//  进来"，不要求一次性覆盖。这里先收拢本次改造实际用到的几个，其余留待
//  后续顺手迁移——新增图标常量时，照此处格式追加即可，不必成批处理。
// ─────────────────────────────────────────────────────────────

object AppIcons {
    // 已收拢（2.1 任务页预览卡示范迁移用到的图标）。
    // 新增图标常量时，照此处格式追加即可，一次迁移一处，不必成批处理。
    val Folder: ImageVector        = Icons.Outlined.Folder
    val CalendarMonth: ImageVector = Icons.Outlined.CalendarMonth

    // 2.4 收口第二批：CharacterDetailAbility「可用工具」四列网格固定用到
    // 的 5 个工具图标，本身就是一份固定枚举（ToolItem 列表），比起留在
    // 调用处裸写更适合收进这里统一管理。
    val ToolSearch: ImageVector      = Icons.Outlined.Search
    val ToolDescription: ImageVector = Icons.Outlined.Description
    val ToolCode: ImageVector        = Icons.Outlined.Code
    val ToolTable: ImageVector       = Icons.Outlined.TableChart
    val ToolEmail: ImageVector       = Icons.Outlined.Email
}

/**
 * 图标 + 圆角小色块背景（精修方案 v2.1 2.4）。
 *
 * 取代"默认灰/无背景"的裸 Icon，统一套 Radius.xs 圆角小色块背景。
 * 背景色默认取 colors.accentSoft（主题感知的强调色柔和版）；
 * 语义色场景（如成功/失败/警告）传入对应 Palette.SemanticXxx.copy(alpha = 0.12f)。
 *
 * @param icon      图标本体
 * @param tint      图标颜色（默认 colors.accent）
 * @param background 色块背景色（默认 colors.accentSoft）
 * @param size      图标本身尺寸（不含色块内边距）
 * @param badgeSize 色块整体尺寸；null 时按 size + 内边距自适应
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    background: Color? = null,
    size: Dp = 16.dp,
    badgeSize: Dp? = null,
) {
    val colors = ZaijianTheme.colors
    val resolvedTint = tint ?: colors.accent
    val resolvedBg   = background ?: colors.accentSoft

    Box(
        modifier = modifier
            .let { if (badgeSize != null) it.size(badgeSize) else it }
            .clip(RoundedCornerShape(Radius.xs))
            .background(resolvedBg)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDescription,
            modifier           = Modifier.size(size),
            tint               = resolvedTint,
        )
    }
}
