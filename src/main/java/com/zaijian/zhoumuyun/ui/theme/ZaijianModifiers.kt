package com.zaijian.zhoumuyun.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box

// ─────────────────────────────────────────────────────────────
//  ZaijianModifiers.kt  — 公馆装饰工具集 v2.0
//  设计规范：光的方向性 / 材质分层 / 角色颜色是情绪
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  §5.1  parchmentSurface — 三层羊皮纸质感背景
// ─────────────────────────────────────────────────────────────
fun Modifier.parchmentSurface(isDark: Boolean): Modifier {
    val baseColor  = if (isDark) Palette.NightCard else Palette.Parchment
    val glowColor  = if (isDark) Palette.NightElevated else Color(0xFFFEFCF8)
    val cornerDark = if (isDark) Color(0xFF0A0806) else Color(0xFFD8C9B0)

    return this.drawBehind {
        // [1] 底色
        drawRect(baseColor)

        // [2] 中心高光（左上偏移，模拟顶部来光）
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    glowColor.copy(alpha = if (isDark) 0.40f else 0.60f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.40f, size.height * 0.28f),
                radius = size.width * 0.85f,
            )
        )

        // [3] 四角暗角
        val vigRadius = size.minDimension * 0.55f
        val vigAlpha  = if (isDark) 0.30f else 0.12f
        listOf(
            Offset(0f,         0f),
            Offset(size.width, 0f),
            Offset(0f,         size.height),
            Offset(size.width, size.height),
        ).forEach { corner ->
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cornerDark.copy(alpha = vigAlpha), Color.Transparent),
                    center = corner,
                    radius = vigRadius,
                ),
                radius = vigRadius,
                center = corner,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  §5.4  presenceGlow — 在场光晕（与呼吸动画联动）
// ─────────────────────────────────────────────────────────────
fun Modifier.presenceGlow(
    color: Color,
    isActive: Boolean,
    breathAlpha: Float = 0.35f,
): Modifier {
    if (!isActive) return this
    return this.drawBehind {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = breathAlpha),
                    color.copy(alpha = breathAlpha * 0.3f),
                    Color.Transparent,
                ),
                radius = size.minDimension * 0.80f,
            ),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  §5.2  CornerOrnamentBox — 四角金色古典花纹容器
// ─────────────────────────────────────────────────────────────
@Composable
fun CornerOrnamentBox(
    modifier: Modifier = Modifier,
    ornamentSize: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = ZaijianTheme.colors.isDark
    val gold   = if (isDark) Palette.Gold.copy(alpha = 0.50f)
                 else        Palette.Gold.copy(alpha = 0.65f)

    Box(modifier = modifier.drawWithContent {
        drawContent()

        val s   = ornamentSize.toPx()
        val sw  = 0.9.dp.toPx()
        val dia = 2.4.dp.toPx()

        // 四角配置：(originX, originY, scaleX, scaleY)
        listOf(
            floatArrayOf(0f,         0f,           1f,  1f),
            floatArrayOf(size.width, 0f,          -1f,  1f),
            floatArrayOf(0f,         size.height,  1f, -1f),
            floatArrayOf(size.width, size.height, -1f, -1f),
        ).forEach { c ->
            withTransform({
                translate(c[0], c[1])
                scale(c[2], c[3], pivot = Offset.Zero)
            }) {
                drawLine(gold, Offset(0f, s * 0.15f), Offset(s * 0.65f, s * 0.15f), sw)
                drawLine(gold, Offset(s * 0.15f, 0f), Offset(s * 0.15f, s * 0.65f), sw)
                drawLine(
                    gold,
                    Offset(s * 0.58f, s * 0.15f),
                    Offset(s * 0.15f, s * 0.58f),
                    sw * 0.7f,
                )
                val diamond = Path().apply {
                    moveTo(s * 0.15f,       s * 0.15f - dia)
                    lineTo(s * 0.15f + dia, s * 0.15f)
                    lineTo(s * 0.15f,       s * 0.15f + dia)
                    lineTo(s * 0.15f - dia, s * 0.15f)
                    close()
                }
                drawPath(diamond, gold)
            }
        }
    }) {
        content()
    }
}

// ─────────────────────────────────────────────────────────────
//  §5.3  GoldDivider — 古典金线分割器（渐隐 + 菱形中心点）
// ─────────────────────────────────────────────────────────────
@Composable
fun GoldDivider(
    modifier: Modifier = Modifier,
    withDiamond: Boolean = true,
    fadeEdges: Boolean = true,
) {
    val isDark = ZaijianTheme.colors.isDark
    val gold   = Palette.Gold.copy(alpha = if (isDark) 0.28f else 0.42f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(if (withDiamond) 12.dp else 6.dp)
            .padding(horizontal = Spacing.lg)
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val sw = 0.8.dp.toPx()

        if (fadeEdges) {
            val midLeft  = if (withDiamond) cx - 10.dp.toPx() else cx
            val midRight = if (withDiamond) cx + 10.dp.toPx() else cx

            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, gold),
                    startX = 0f, endX = midLeft,
                ),
                start = Offset(0f, cy), end = Offset(midLeft, cy),
                strokeWidth = sw,
            )
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(gold, Color.Transparent),
                    startX = midRight, endX = size.width,
                ),
                start = Offset(midRight, cy), end = Offset(size.width, cy),
                strokeWidth = sw,
            )
        } else {
            drawLine(gold, Offset(0f, cy), Offset(size.width, cy), sw)
        }

        if (withDiamond) {
            val d = 3.2.dp.toPx()
            drawPath(
                Path().apply {
                    moveTo(cx, cy - d); lineTo(cx + d, cy)
                    lineTo(cx, cy + d); lineTo(cx - d, cy)
                    close()
                },
                gold,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  §5.6  goldBottomBorder — 顶栏金线底边 Modifier
// ─────────────────────────────────────────────────────────────
fun Modifier.goldBottomBorder(isDark: Boolean): Modifier {
    val gold = Palette.Gold.copy(alpha = if (isDark) 0.28f else 0.38f)
    return this.drawWithContent {
        drawContent()
        val lineY = size.height - 0.5.dp.toPx()
        val inset = 20.dp.toPx()
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, gold, gold, Color.Transparent),
                startX = 0f, endX = size.width,
                tileMode = TileMode.Clamp,
            ),
            start       = Offset(inset, lineY),
            end         = Offset(size.width - inset, lineY),
            strokeWidth = 0.8.dp.toPx(),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  §3.3  角色专属色情绪调制器
//
//  activated() — 饱和度 +15%，亮度 +5%（在线/说话时）
//  dimmed()    — 饱和度 -50%，亮度微降（离线时）
// ─────────────────────────────────────────────────────────────
fun Color.activated(): Color {
    val hsl  = FloatArray(3)
    val alphaInt = (alpha * 255).toInt()
    val argb = android.graphics.Color.argb(
        alphaInt, (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )
    android.graphics.Color.colorToHSV(argb, hsl)
    hsl[1] = (hsl[1] * 1.15f).coerceAtMost(1f)
    hsl[2] = (hsl[2] * 1.05f).coerceAtMost(1f)
    return Color(android.graphics.Color.HSVToColor(alphaInt, hsl))
}

fun Color.dimmed(): Color {
    val hsl  = FloatArray(3)
    val alphaInt = (alpha * 255).toInt()
    val argb = android.graphics.Color.argb(
        alphaInt, (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )
    android.graphics.Color.colorToHSV(argb, hsl)
    hsl[1] = hsl[1] * 0.50f
    hsl[2] = hsl[2] * 0.90f
    return Color(android.graphics.Color.HSVToColor(alphaInt, hsl))
}
