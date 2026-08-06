package com.zaijian.zhoumuyun.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────
//  ZaijianModifiers.kt  — 公馆装饰工具集 v2.0
//  设计规范：光的方向性 / 材质分层 / 角色颜色是情绪
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  §5.1  parchmentSurface — 三层羊皮纸质感背景
// ─────────────────────────────────────────────────────────────
fun Modifier.parchmentSurface(isDark: Boolean): Modifier {
    val baseColor  = if (isDark) Palette.NightCard else Palette.Parchment
    val glowColor  = if (isDark) Palette.NightElevated else Palette.ParchmentGlow       // 批次7 7-3修复：原 Color(0xFFFEFCF8) 改为引用 Palette.ParchmentGlow
    val cornerDark = if (isDark) Palette.ParchmentCornerDark else Palette.ParchmentCornerLight  // 批次7 7-3修复：原 Color(0xFF0A0806)/Color(0xFFD8C9B0) 改为引用 Palette token

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
//  §3.3  角色专属色情绪调制器
//
//  activated() — 饱和度 +15%，亮度 +5%（在线/说话时）
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

// P3-15 修复：移除 dimmed()（零调用方死代码）

// ─────────────────────────────────────────────────────────────
//  §5.5  noiseTexture — 程序化噪点纹理叠加层
//
//  背景：WorldOSComponents.kt 里此前有条注释写"真正的逐像素噪点需要
//  Shader/RenderEffect 或预生成噪点贴图，超出本组件单文件实现的合理范围"，
//  即该效果被主动放弃、只用渐变不透明度变化做近似。网页版渐变面上那层
//  极淡噪点正是"高级感"的重要来源之一，这里补上。
//
//  实现方式：不需要 API 33+ 的 AGSL RuntimeShader，也不需要打包一张 PNG
//  贴图资源（那样反而增大 APK、且分辨率适配麻烦）。做法是：
//    1. 用 kotlin.random.Random 生成一张很小的位图（如 64×64），每个像素
//       是随机灰度、极低 alpha 的颜色——这就是"噪点"本身。
//    2. 用 Android 原生 BitmapShader(TileMode.REPEAT, TileMode.REPEAT)
//       把这张小图在整个绘制区域内平铺重复，效果等同于"一张循环平铺的
//       噪点 PNG 配 BitmapShader"，只是这张小图是运行时生成而不是打包资源，
//       兼容所有 API Level（BitmapShader 是 Android 1.0 就有的 API）。
//    3. remember 缓存生成结果，避免每次重组都重新生成随机噪点（那样会
//       导致噪点闪烁/性能浪费）——同一个 Composable 生命周期内噪点图案固定。
//
//  用法：叠加在其它 background/drawBehind 渐变层之上即可，
//  alpha 建议控制在 0.02~0.05 之间（太高会看起来像屏幕脏了/雪花噪点，
//  不是"细腻颗粒感"）。
// ─────────────────────────────────────────────────────────────

/**
 * 生成一张 [sizePx] × [sizePx] 的随机灰度噪点位图，每个像素独立取随机灰度值
 * （高斯分布近似，用两次 Random.nextFloat() 做简易近似而非真正 Box-Muller，
 * 对视觉颗粒感而言足够，没必要为此引入额外计算开销）。
 * alpha 在这里不预乘进像素——不透明度统一交给调用方通过 Paint/ShaderBrush
 * 的整体透明度控制，職责更清晰、复用性更好。
 */
private fun generateNoiseBitmap(sizePx: Int, seed: Long): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val random = Random(seed)
    val pixels = IntArray(sizePx * sizePx)
    for (i in pixels.indices) {
        // 灰度噪点：R=G=B，只有明暗变化，不引入彩色噪点（更接近纸张颗粒质感）
        val gray = random.nextInt(256)
        // alpha 固定给足（255），实际显示透明度由外层 ShaderBrush/Paint 的
        // alpha 统一控制，这里只负责提供"哪些像素亮、哪些暗"的图案。
        pixels[i] = android.graphics.Color.argb(255, gray, gray, gray)
    }
    bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    return bitmap
}

/**
 * 在当前 Modifier 链上叠加一层可平铺的噪点纹理。
 *
 * @param alpha 噪点整体不透明度，建议 0.02~0.05（渐变卡片面用低值，避免糊成雪花屏）。
 * @param tileSizePx 噪点贴图的原始像素边长（越小颗粒越细密，越大颗粒越粗），默认 64。
 * @param seed 随机种子，同一个 seed 在同一次进程生命周期内产生同一张噪点图案
 *             （不同卡片各自 remember 各自的 seed，不会共享同一张位图实例，
 *             但视觉上都是"均匀随机灰度点"，看不出差异）。
 */
fun Modifier.noiseTexture(
    alpha: Float = 0.035f,
    tileSizePx: Int = 64,
    seed: Long = 1L,
): Modifier = this.drawWithCache {
    val noiseBitmap = generateNoiseBitmap(tileSizePx, seed)
    val shader = BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    val brush = ShaderBrush(shader)
    onDrawBehind {
        drawRect(
            brush = brush,
            alpha = alpha,
        )
    }
}
