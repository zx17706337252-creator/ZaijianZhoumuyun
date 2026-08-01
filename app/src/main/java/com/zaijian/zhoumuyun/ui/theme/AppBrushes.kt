package com.zaijian.zhoumuyun.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────
//  AppBrushes.kt — UI 升级 v2.0（鎏金纸梦融合方案）渐变刷系统
//
//  融合方案令牌落点（渲染稿 final-render/index.html :root 一一对应）：
//    黄铜渐变      135° 三段  #DCC08A → #C4A46A → #A8894E
//    卡片渐变描边  同向三段，两端亮、中段收敛，视线落在卡的左上
//    火漆径向渐变  circle at 36% 30%：#B8546A → #8A2B3D 58% → #6E1F2F
//    水彩晕染      两层 radial-gradient，光源右上角（90% 0%），渐隐至透明
//    顶部高光线    90° 透明 → 纸白 .9 → 透明（暗色降 alpha）
//
//  金色军规（融合方案 §1.1）：
//    1. 金色一律走渐变或 12% 以下浅金底，禁止 #C4A46A 纯色平涂超 24px²；
//    2. 金色文字只用 GoldDeep（#A8894E），保证纸底对比度 ≥4.5:1；
//    3. 金色与绛红不同时出现在同一卡片上（蜡封卡内不再用金按钮）。
// ─────────────────────────────────────────────────────────────

object AppBrushes {

    // ── 黄铜主渐变：按钮 / 选中态 / 金条 / 进度 ─────────────────
    // 135° = Compose 里的对角（左上 → 右下）。Brush.linearGradient 用
    // start/end 坐标表达角度：start(0,0) → end(∞,∞) 即 135°。
    fun goldGradient(): Brush = Brush.linearGradient(
        colors = listOf(Palette.GoldBright, Palette.Gold, Palette.GoldDeep),
    )

    // ── 卡片黄铜渐变描边（L2）：两端亮、中段收敛 ─────────────────
    // 渲染稿写法：135deg, rgba(220,192,138,.75) → rgba(196,164,106,.22) 40%
    //             → rgba(168,137,78,.55)。暗色模式整体降 alpha 防发闷。
    fun cardBorderGradient(isDark: Boolean): Brush {
        val k = if (isDark) 0.65f else 1f
        return Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to Palette.GoldBright.copy(alpha = 0.75f * k),
                0.40f to Palette.Gold.copy(alpha = 0.22f * k),
                1.00f to Palette.GoldDeep.copy(alpha = 0.55f * k),
            ),
        )
    }

    // ── 火漆径向渐变：蜡封/刻字印章 ─────────────────────────────
    // 光点落在 36% 30%（非正圆心，模拟火漆被斜光照亮）。
    fun waxRadial(center: Offset, radius: Float): Brush = Brush.radialGradient(
        colorStops = arrayOf(
            0.00f to Palette.WaxHi,
            0.58f to Palette.Wax,
            1.00f to Palette.WaxDeep,
        ),
        center = center,
        radius = radius,
    )

    // ── 水彩晕染（方案C 插件，金色换 A 后并入）：自右上角晕开 ────
    // 公式：radial-gradient(58% 62% at 90% 0%, rgba(角色色, α), transparent 70%)
    // 调用处负责把 alpha 限制在 WcAlpha 预算内（页 6% / 列表 8% / 卡 ≤14%）。
    fun watercolorWash(color: Color, alpha: Float, center: Offset, radius: Float): Brush =
        Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to color.copy(alpha = alpha.coerceAtMost(WcAlpha.cardMax)),
                0.70f to color.copy(alpha = 0f),
            ),
            center = center,
            radius = radius,
        )

    // ── 卡内顶部 1px 高光线（纸被光照亮的檐口）──────────────────
    // 渲染稿：linear-gradient(90°, transparent, rgba(255,255,255,.9), transparent)
    fun topHighlight(isDark: Boolean): Brush = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            Color.White.copy(alpha = if (isDark) 0.16f else 0.90f),
            Color.Transparent,
        ),
    )
}

// ─────────────────────────────────────────────────────────────
//  水彩透明度预算（融合方案 §3.3 / 方案C §3.3，暗色上调见注释）
// ─────────────────────────────────────────────────────────────
object WcAlpha {
    /** 页面级氛围晕染（浅色 6% / 暗色 9%） */
    const val page     = 0.06f
    const val pageDark = 0.09f
    /** 列表页下限 */
    const val list     = 0.08f
    /** 卡片级上限（任何晕染不得超过，且必须渐隐至透明） */
    const val cardMax  = 0.14f
    /** 卡片级上限·暗色 */
    const val cardMaxDark = 0.18f
}
