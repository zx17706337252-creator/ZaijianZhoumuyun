package com.zaijian.zhoumuyun.ui.screen

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────
//  SplashScreen  — 启动画面（设计规范 §18 导航架构）
//
//  流程：
//    进场淡入（300ms）→ Logo 呼吸动画 → 1200ms 后淡出（250ms）→ onFinished()
//
//  方案 A（全屏背景图，用户可自定义）：
//    通过「我」Tab → 外观 → 启动页背景图 设置，用户可上传自己的竖版图片，
//    用与聊天背景一致的 AvatarCropDialog(shape = FULL_SCREEN) 取景。
//    设置过自定义图时，全屏铺底图（"较大边覆盖容器"公式，与聊天背景/
//    头像裁剪同一套坐标系），文字整体下移贴近底部，压在图片上方，
//    加一层底部渐变遮罩保证可读性。
//    没有设置过自定义图时，保留原有品牌兜底视觉（呼吸光晕圆形 Logo +
//    居中文字），不会因为用户没设置就空白。
//
//  品牌兜底 Logo 结构：
//    外层呼吸光晕（blur 24dp，accent 35% alpha，1.0→1.04 scale）
//    内层圆形容器（64dp，accent 渐变）
//    文字「再」（白色，Bold 28sp）
//    下方应用名「再见周慕云」+ 副标题
// ─────────────────────────────────────────────────────────────

private const val SPLASH_HOLD_MS = 1200L   // logo 展示时长
private const val FADE_IN_MS     = 300
private const val FADE_OUT_MS    = 250

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val colors = ZaijianTheme.colors

    // ── 整屏 alpha（进场 + 离场）──────────────────────────────
    val screenAlpha = remember { Animatable(0f) }

    // W14 P2 问题4修复：onFinished() 会触发 navController.navigate() +
    // popUpTo(Splash){inclusive=true}（见 AppNavigation.kt）。LaunchedEffect(Unit)
    // 本身在配置变更时不会重新触发，但如果 SplashScreen 在动画协程执行中被
    // onDestroy 后重建（极端场景），旧协程可能仍在跑，存在重复调用 onFinished()
    // 的极小概率窗口——第二次调用会在 Splash 已经不在回退栈的情况下再触发一次
    // navigate，可能导致导航异常或重复入栈。加一个一次性标志防止重复触发。
    val hasFinished = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 进场淡入
        screenAlpha.animateTo(
            1f,
            animationSpec = tween(FADE_IN_MS, easing = FastOutSlowInEasing),
        )
        // 保持展示
        delay(SPLASH_HOLD_MS)
        // 离场淡出
        screenAlpha.animateTo(
            0f,
            animationSpec = tween(FADE_OUT_MS, easing = FastOutSlowInEasing),
        )
        if (hasFinished.value) return@LaunchedEffect
        hasFinished.value = true
        onFinished()
    }

    // ── 背景色：暗色取 bgBase，亮色取稍深的 Snow ──────────────
    val splashBg = if (colors.isDark) colors.bgBase else Palette.SplashLightBg

    // ── 门扉页自定义背景图（方案 A）──────────────────────────
    // 读取用户在「我」Tab → 外观 → 启动页背景图 里设置的配置。
    // config 为 null（用户从未设置过）时，下面完全走原有品牌兜底视觉，
    // 不会因为没设置就空白。
    val splashBgConfig by AppContainer.instance.splashBackgroundDataStore.configFlow
        .collectAsStateWithLifecycle(initialValue = null)

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(splashBg)
                if (splashBgConfig == null) {
                    // 中心金色光晕（极淡，配合 Logo 呼吸）——只在没有自定义
                    // 背景图时画，避免叠在用户图片上显得脏。
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Palette.Gold.copy(alpha = if (colors.isDark) 0.08f else 0.05f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width / 2f, size.height * 0.42f),
                            radius = size.width * 0.85f,
                        )
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val customBgUri = splashBgConfig?.uri
        if (customBgUri != null) {
            // ── 全屏自定义背景图 ─────────────────────────────
            // 与聊天背景（ChatScreen.kt）/头像裁剪同一套"较大边覆盖容器"
            // 基准尺寸公式 + graphicsLayer scale/translation，保证裁剪弹窗
            // 里看到的取景范围跟这里最终渲染效果一致。
            val bgPainter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(customBgUri)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build(),
            )
            val bgPainterState = bgPainter.state
            val bgIntrinsicSize = (bgPainterState as? AsyncImagePainter.State.Success)
                ?.painter?.intrinsicSize
            val bgImageAspect = if (bgIntrinsicSize != null &&
                bgIntrinsicSize.width > 0f && bgIntrinsicSize.height > 0f
            ) {
                bgIntrinsicSize.width / bgIntrinsicSize.height
            } else {
                1f
            }
            val bgOffsetX = splashBgConfig?.offsetX ?: 0f
            val bgOffsetY = splashBgConfig?.offsetY ?: 0f
            val bgScale   = splashBgConfig?.scale ?: 1f

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val frameHalfWPx = with(density) { (maxWidth / 2f).toPx() }
                val frameHalfHPx = with(density) { (maxHeight / 2f).toPx() }
                val frameAspect = if (frameHalfHPx > 0f) frameHalfWPx / frameHalfHPx else 1f

                val bgBaseWidthPx: Float
                val bgBaseHeightPx: Float
                if (bgImageAspect > frameAspect) {
                    bgBaseHeightPx = frameHalfHPx * 2f
                    bgBaseWidthPx  = bgBaseHeightPx * bgImageAspect
                } else {
                    bgBaseWidthPx  = frameHalfWPx * 2f
                    bgBaseHeightPx = bgBaseWidthPx / bgImageAspect
                }

                Image(
                    painter            = bgPainter,
                    contentDescription = null,
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier
                        .size(
                            width  = with(density) { bgBaseWidthPx.toDp() },
                            height = with(density) { bgBaseHeightPx.toDp() },
                        )
                        .graphicsLayer {
                            scaleX       = bgScale
                            scaleY       = bgScale
                            translationX = bgOffsetX * frameHalfWPx
                            translationY = bgOffsetY * frameHalfHPx
                            // 图片加载失败时整层透明，隐藏破损占位图（等价于
                            // ChatScreen.kt 原公式里 Image(alpha=...) 的效果，
                            // 这里改用同一个 graphicsLayer 一并处理，避免重复
                            // 声明两层各自独立的 alpha）。
                            alpha = screenAlpha.value *
                                (if (bgPainterState is AsyncImagePainter.State.Error) 0f else 1f)
                        },
                )
            }
            // 底部渐变遮罩，保证下移后的文字在图片上依然清晰可读。跟随
            // screenAlpha 一起淡入淡出——否则进场/离场动画期间会先出现
            // "图片还没显现、纯黑遮罩已经满强度"的一瞬间违和感。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = screenAlpha.value }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                            startY = 0f,
                            endY   = Float.POSITIVE_INFINITY,
                        )
                    ),
            )
            // 文字下移，贴近底部，压在图片和渐变遮罩上方
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                SplashTitleText(alpha = screenAlpha.value, onImage = true)
            }
        } else {
            // ── 品牌兜底视觉（原有呼吸光晕圆形 Logo，未设置自定义图时）──
            Column(
                modifier              = Modifier
                    .scale(1f)          // anchor for future motion
                    .then(
                        Modifier.background(Color.Transparent) // transparent wrapper
                    ),
                horizontalAlignment   = Alignment.CenterHorizontally,
            ) {
                LogoMark(accentColor = colors.accent, alpha = screenAlpha.value)
                Spacer(Modifier.height(24.dp))
                SplashTitleText(alpha = screenAlpha.value, onImage = false)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  SplashTitleText — 应用名 + 副标题
//  onImage = true 时（铺了自定义背景图）文案改用白色系，保证在任意
//  用户上传的图片上都可读；onImage = false 时沿用主题色文案。
// ─────────────────────────────────────────────────────────────

@Composable
private fun SplashTitleText(alpha: Float, onImage: Boolean) {
    val colors = ZaijianTheme.colors
    val titleColor    = if (onImage) Palette.White else colors.textPrimary
    val subtitleColor = if (onImage) Palette.White.copy(alpha = 0.85f) else colors.textSecondary

    // 22sp 为启动页品牌展示专属尺寸（基准 titleBold=20sp），有意设计，非硬编码遗留
    Text(
        text  = "再见周慕云",
        style = ZaijianTheme.typography.titleBold.copy(
            fontSize     = 22.sp,
            fontWeight   = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        ),
        color = titleColor.copy(alpha = alpha),
    )

    Spacer(Modifier.height(6.dp))

    Text(
        text  = "九个人，九段故事，属于我们的世界。",
        style = ZaijianTheme.typography.caption,
        color = subtitleColor.copy(alpha = alpha * 0.8f),
    )
}

// ─────────────────────────────────────────────────────────────
//  LogoMark  — 呼吸光晕 + 圆形 Logo
// ─────────────────────────────────────────────────────────────

@Composable
private fun LogoMark(accentColor: Color, alpha: Float) {
    // 呼吸动画：scale 1.00 → 1.04，glow alpha 0.25 → 0.45
    val infiniteTransition = rememberInfiniteTransition(label = "splash_breath")

    val breathScale by infiniteTransition.animateFloat(
        initialValue = 1.00f,
        targetValue  = 1.04f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathScale",
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.20f,
        targetValue  = 0.40f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    Box(
        modifier         = Modifier.size(96.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 外层呼吸光晕（API 31+ 有 blur，低版本降级为纯 alpha 圆）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(breathScale * 1.28f)
                    .blur(20.dp)
                    .clip(CircleShape)
                    .background(
                        accentColor.copy(alpha = glowAlpha * alpha),
                    ),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(breathScale * 1.5f)
                    .clip(CircleShape)
                    .background(
                        accentColor.copy(alpha = glowAlpha * alpha * 0.5f),
                    ),
            )
        }

        // 内层圆形 Logo 容器
        Box(
            modifier         = Modifier
                .size(64.dp)
                .scale(breathScale)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            accentColor.copy(alpha = alpha),
                            accentColor.copy(alpha = alpha * 0.75f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 28sp 为 Logo 圆形容器内单字展示专属尺寸，有意设计，非硬编码遗留
            Text(
                text  = "再",
                style = ZaijianTheme.typography.titleBold.copy(
                    fontSize   = 28.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Palette.White.copy(alpha = alpha),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Previews
// ─────────────────────────────────────────────────────────────

@Preview(
    name            = "Splash · Dark",
    showBackground  = true,
    backgroundColor = 0xFF12131A,
    widthDp         = 390,
    heightDp        = 844,
)
@Composable
private fun PreviewSplashDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) {
        SplashScreen(onFinished = {})
    }
}

@Preview(
    name           = "Splash · Light",
    showBackground = true,
    widthDp        = 390,
    heightDp       = 844,
)
@Composable
private fun PreviewSplashLight() {
    ZaijianTheme(appTheme = AppTheme.LIGHT) {
        SplashScreen(onFinished = {})
    }
}
