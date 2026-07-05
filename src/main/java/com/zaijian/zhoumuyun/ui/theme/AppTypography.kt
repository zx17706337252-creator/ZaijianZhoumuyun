package com.zaijian.zhoumuyun.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────
//  Typography tokens
//  See design spec Appendix B for the full spec table.
//
//  Naming convention:
//    [role][Weight?]  →  e.g.  titleBold, bodyRegular, labelSmall
// ─────────────────────────────────────────────────────────────
@Immutable
data class AppTypography(
    /** 页面主标题  Bold 700 / 20sp / lh 1.3× */
    val titleBold: TextStyle,
    /** 卡片标题  Medium 500 / 16sp / lh 1.4× */
    val cardTitle: TextStyle,
    /** 导航栏标题  Bold 700 / 17sp */
    val navTitle: TextStyle,
    /** 正文 / 消息  Regular 400 / 14sp / lh 1.6× */
    val body: TextStyle,
    /** 辅助文字  Regular 400 / 13sp / lh 1.5× */
    val caption: TextStyle,
    /** 小标签 / 时间戳  Regular 400 / 11sp / lh 1.4× */
    val label: TextStyle,
    /**
     * 等宽数据标签——label 的等宽字体变体，仅用于纯数字/拉丁字符场景
     * （时间戳"23:41"、编号"NO.0049"、日期"06.30"），尺寸/字重/行高与 label 完全一致，
     * 只是 fontFamily 换成等宽字体。
     * ⚠️ 禁止用于中文文案：等宽字体不含 CJK 字形，中文会回退系统默认字体，
     * 与同一行内其他中文造成字体不统一。这是新增字段，不影响现有 label 的任何调用方，
     * 哪个场景要换成等宽效果，由调用方自己显式切换到 labelMono（精修方案 v1.3 第147-149行约束）。
     */
    val labelMono: TextStyle,
    /** 按钮文字  Medium 500 / 14sp */
    val button: TextStyle,
    /** 状态文案  Regular 400 / 13sp，限 ≤10 字 */
    val presence: TextStyle,
    /** 正文加粗  Bold 700 / 14sp（角色名、强调文案） */
    val bodyBold: TextStyle,
) {
    // ── 兼容属性映射 ────────────────────────────────
    val titleLarge: TextStyle get() = titleBold
    val small:      TextStyle get() = label
    val secondary: TextStyle get() = caption
}

val DefaultTypography = AppTypography(
    titleBold = TextStyle(
        fontFamily = SerifSC,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,    // 20 × 1.3
        letterSpacing = (-0.3).sp,
    ),
    cardTitle = TextStyle(
        fontFamily = SerifSC,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = (16 * 1.4).sp,
        letterSpacing = (-0.2).sp,
    ),
    navTitle = TextStyle(
        fontFamily = SerifSC,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp,
    ),
    body = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = (14 * 1.6).sp,
        letterSpacing = 0.sp,
    ),
    caption = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = (13 * 1.5).sp,
        letterSpacing = 0.sp,
    ),
    label = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = (11 * 1.4).sp,
        letterSpacing = 0.sp,
    ),
    labelMono = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = (11 * 1.4).sp,
        letterSpacing = 0.sp,
    ),
    button = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    presence = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = (13 * 1.5).sp,
        letterSpacing = 0.sp,
    ),
    bodyBold = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = (14 * 1.6).sp,
        letterSpacing = 0.sp,
    ),
)

val LocalAppTypography = staticCompositionLocalOf { DefaultTypography }
