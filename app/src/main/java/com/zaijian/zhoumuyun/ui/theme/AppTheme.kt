package com.zaijian.zhoumuyun.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────
//  暗黑模式 configChanges 说明（UI M1 已验证）
//
//  AndroidManifest.xml 中 MainActivity 已声明：
//    android:configChanges="uiMode|orientation|screenSize|..."
//  因此系统亮暗切换（uiMode 变化）不会重建 Activity，
//  而是触发 Compose 的重组（recomposition）。
//  ZaijianTheme 中 isSystemInDarkTheme() 是一个 Composable 读取，
//  会随 uiMode 变化自动重组，无需手动监听——设计符合预期。
//
//  手动切换（AppTheme.LIGHT/DARK）同理，通过 CompositionLocal
//  传播，不触发 Activity 重建，主题切换平滑无跳转。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  Material3 color scheme bridges
//  We keep a thin M3 scheme so Compose internals (ripple,
//  surface, etc.) still work correctly.
// ─────────────────────────────────────────────────────────────
private val M3LightScheme = lightColorScheme(
    primary          = Palette.Slate,
    onPrimary        = Color.White,
    primaryContainer = Palette.AccentSoft,
    onPrimaryContainer = Palette.Ink900,
    background       = Palette.Snow,
    onBackground     = Palette.Ink900,
    surface          = Palette.Paper,
    onSurface        = Palette.Ink900,
    surfaceVariant   = Palette.AccentSoft,
    onSurfaceVariant = Palette.Ink600,
    outline          = Palette.Border,
)

private val M3DarkScheme = darkColorScheme(
    primary          = Palette.Slate,
    onPrimary        = Color.White,
    primaryContainer = Palette.Slate.copy(alpha = 0.20f),
    onPrimaryContainer = Palette.NightText,
    background       = Palette.Night,
    onBackground     = Palette.NightText,
    surface          = Palette.NightCard,
    onSurface        = Palette.NightText,
    surfaceVariant   = Palette.NightElevated,
    onSurfaceVariant = Palette.NightTextSub,
    outline          = Palette.NightBorder,
)

// ─────────────────────────────────────────────────────────────
//  Enum for manual theme override
// ─────────────────────────────────────────────────────────────
enum class AppTheme { LIGHT, DARK, SYSTEM }

// ─────────────────────────────────────────────────────────────
//  Font size scaling  (Fix-02)
// ─────────────────────────────────────────────────────────────
/**
 * 返回按 [scale] 等比缩放后的排版系统。
 * scale: 0.88f = 小，1.0f = 标准，1.15f = 大
 */
// P3-25 修复：抽取 scaledTextStyle() 辅助函数，减少样板代码。
// 注意：AppTypography 新增字段时仍需手动添加到下方列表，
// 但单个字段的 fontSize/lineHeight 缩放逻辑已统一，不再重复写 copy() 表达式。
private fun scaledTextStyle(style: TextStyle, scale: Float): TextStyle =
    style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale)

fun scaledTypography(scale: Float): AppTypography = AppTypography(
    titleBold  = scaledTextStyle(DefaultTypography.titleBold, scale),
    cardTitle  = scaledTextStyle(DefaultTypography.cardTitle, scale),
    navTitle   = scaledTextStyle(DefaultTypography.navTitle, scale),
    body       = scaledTextStyle(DefaultTypography.body, scale),
    caption    = scaledTextStyle(DefaultTypography.caption, scale),
    label      = scaledTextStyle(DefaultTypography.label, scale),
    labelMono  = scaledTextStyle(DefaultTypography.labelMono, scale),
    button     = scaledTextStyle(DefaultTypography.button, scale),
    presence   = scaledTextStyle(DefaultTypography.presence, scale),
    bodyBold   = scaledTextStyle(DefaultTypography.bodyBold, scale),
)

// ─────────────────────────────────────────────────────────────
//  Theme entry point
// ─────────────────────────────────────────────────────────────
@Composable
fun ZaijianTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    fontSizeScale: Float = 1.0f,
    content: @Composable () -> Unit,
) {
    val useDark = when (appTheme) {
        AppTheme.LIGHT  -> false
        AppTheme.DARK   -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }

    val colors     = if (useDark) DarkColors else LightColors
    val m3Scheme   = if (useDark) M3DarkScheme else M3LightScheme
    val typography = if (fontSizeScale == 1.0f) DefaultTypography else scaledTypography(fontSizeScale)

    // 主题切换：直接 provide，不再用 AnimatedContent 包裹整个 content。
    // 原因：AnimatedContent 在 targetState 变化时会销毁旧子树、新建新子树，
    // 导致 NavController/NavHost 被重建，App 回到 Splash 起始页。
    // 改为直接 CompositionLocalProvider 后，Compose 仅触发重组（recomposition），
    // 不会销毁 NavController，主题切换平滑无重启。
    CompositionLocalProvider(
        LocalAppColors     provides colors,
        LocalAppTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = m3Scheme,
            content     = content,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Convenience accessors  (use anywhere inside ZaijianTheme)
// ─────────────────────────────────────────────────────────────
object ZaijianTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable
        get() = LocalAppColors.current

    val typography: AppTypography
        @Composable @ReadOnlyComposable
        get() = LocalAppTypography.current
}
