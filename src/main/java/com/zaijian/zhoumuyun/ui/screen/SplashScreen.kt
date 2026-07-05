package com.zaijian.zhoumuyun.ui.screen

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
//  Logo 结构：
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
        onFinished()
    }

    // ── 背景色：暗色取 bgBase，亮色取稍深的 Snow ──────────────
    val splashBg = if (colors.isDark) colors.bgBase else Palette.SplashLightBg

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(splashBg)
                // 中心金色光晕（极淡，配合 Logo 呼吸）
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
            },
        contentAlignment = Alignment.Center,
    ) {
        // 用 graphicsLayer alpha 控制整屏淡入淡出
        // （直接在 Column 上 .alpha() 即可，无需 graphicsLayer）
        Column(
            modifier              = Modifier
                .scale(1f)          // anchor for future motion
                .then(
                    Modifier.background(Color.Transparent) // transparent wrapper
                ),
            horizontalAlignment   = Alignment.CenterHorizontally,
        ) {
            // ── Logo ───────────────────────────────────────────
            LogoMark(accentColor = colors.accent, alpha = screenAlpha.value)

            Spacer(Modifier.height(24.dp))

            // ── 应用名 ─────────────────────────────────────────
            Text(
                text  = "再见周慕云",
                style = ZaijianTheme.typography.titleBold.copy(
                    fontSize     = 22.sp,
                    fontWeight   = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
                color = colors.textPrimary.copy(alpha = screenAlpha.value),
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text  = "九个人，九段故事，属于我们的世界。",
                style = ZaijianTheme.typography.caption,
                color = colors.textSecondary.copy(alpha = screenAlpha.value * 0.8f),
            )
        }
    }
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
