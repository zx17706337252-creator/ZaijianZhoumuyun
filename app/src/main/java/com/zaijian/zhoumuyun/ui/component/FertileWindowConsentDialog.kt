package com.zaijian.zhoumuyun.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.GoldDivider
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.theme.activated
import com.zaijian.zhoumuyun.ui.theme.appSpring
import com.zaijian.zhoumuyun.ui.theme.breathAlphaSpec
import com.zaijian.zhoumuyun.ui.theme.fastTween
import com.zaijian.zhoumuyun.ui.theme.parchmentSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
//  FertileWindowConsentDialog — 孕期同意/拒绝弹窗（自定义视觉版）
//
//  替代 ChatScreen 中原先的原生 androidx.compose.material3.AlertDialog。
//  这是全 App 情感浓度最高的一个交互节点，文案库（FertileWindowDialogText.kt）
//  已经写得很有画面感，但原生 AlertDialog 的默认遮罩/默认圆角/默认弹出动画
//  和"清空对话记录"这种工具性确认框用的是同一套视觉语言——本组件让呈现层
//  配得上文案的分量，同时严格复用已有设计系统素材，不引入新的视觉语言：
//
//    - 容器质感   → parchmentSurface()（羊皮纸+四角暗角，公馆统一底色语言）
//    - 郑重感标记  → GoldDivider(withDiamond = true)（系统里"被认真对待的瞬间"符号）
//    - 角色身份感  → accentColor 描边光晕 + activated() 情绪调制（"角色颜色是情绪"规范）
//    - 呼吸动效   → breathAlphaSpec（复用 BookCard/WindowCard 同款呼吸曲线）
//    - 遮罩      → GlassOpacity.fullscreenDim（系统预留值，此前一直没被实际使用）
//
//  动画节奏（区别于默认 AlertDialog 的"弹出感"）：
//    进入：遮罩淡入(150ms) → 容器 scale 0.92→1.0 + alpha 淡入(220ms)
//          → 文字延迟 80ms 浮现（制造"话说出口前的停顿"）
//    停留：描边光晕呼吸明暗（4000ms 半周期，与公馆其他"在场"视觉一致）
//    退出：
//      同意 → 容器轻微膨胀(1.0→1.03) + 快速淡出（appSpring，情绪溢出感）
//      拒绝 → 容器轻微下沉 + 淡出（fastTween，更克制收敛）
//
//  不允许点击遮罩外部关闭——必须明确选择"同意"或"拒绝"，与原实现保持一致。
// ─────────────────────────────────────────────────────────────

private const val TEXT_REVEAL_DELAY_MS = 80L
private const val EXIT_ACCEPT_DURATION_MS = 180
private const val EXIT_REJECT_DURATION_MS = 150

@Composable
fun FertileWindowConsentDialog(
    characterName: String,
    accentColor: Color,
    dialogText: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val isDark = colors.isDark
    val scope = rememberCoroutineScope()

    // ── 进入动画驱动值 ────────────────────────────────────────
    val overlayAlpha = remember { Animatable(0f) }
    val containerScale = remember { Animatable(0.92f) }
    val containerAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }

    // ── 呼吸光晕（停留期间持续，退出动画开始后不再继续呼吸，由 containerAlpha 整体接管淡出） ──
    val breathAlpha = remember { Animatable(0.25f) }

    // 防重复点击：退出动画开始后锁定，避免回调被多次触发
    var isDismissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { overlayAlpha.animateTo(GlassOpacity.fullscreenDim, fastTween) }
        launch {
            containerScale.animateTo(1f, tween(AnimDuration.bottomSheet, easing = FastOutSlowInEasing))
        }
        launch { containerAlpha.animateTo(1f, tween(AnimDuration.bottomSheet)) }
        launch {
            delay(TEXT_REVEAL_DELAY_MS)
            textAlpha.animateTo(1f, fastTween)
        }
        launch {
            // 呼吸：0.25f ↔ 0.45f，复用系统呼吸曲线节奏
            breathAlpha.animateTo(0.45f, breathAlphaSpec)
        }
    }

    // ── 退出动画：触发后驱动 scale/alpha，完成后才真正调用回调 ──
    fun handleAccept() {
        if (isDismissing) return
        isDismissing = true
        scope.launch {
            launch { containerScale.animateTo(1.03f, appSpring) }
            launch {
                overlayAlpha.animateTo(0f, tween(EXIT_ACCEPT_DURATION_MS))
                containerAlpha.animateTo(0f, tween(EXIT_ACCEPT_DURATION_MS))
            }
            delay(EXIT_ACCEPT_DURATION_MS.toLong())
            onAccept()
        }
    }

    fun handleReject() {
        if (isDismissing) return
        isDismissing = true
        scope.launch {
            launch { containerScale.animateTo(0.97f, tween(EXIT_REJECT_DURATION_MS)) }
            launch {
                overlayAlpha.animateTo(0f, tween(EXIT_REJECT_DURATION_MS))
                containerAlpha.animateTo(0f, tween(EXIT_REJECT_DURATION_MS))
            }
            delay(EXIT_REJECT_DURATION_MS.toLong())
            onReject()
        }
    }

    Dialog(
        onDismissRequest = { /* 不允许点外部取消，必须明确选择，留空即可 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // ── 遮罩：径向渐变，中心略亮、边缘更暗，呼应"光的方向性" ──
        // center 用 Offset.Unspecified 由系统按当前容器自动取几何中心，
        // 避免误用比例坐标当像素坐标（Brush.radialGradient 的 center 是 px，不是 0~1 比例）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = overlayAlpha.value * 0.75f),
                            Color.Black.copy(alpha = overlayAlpha.value),
                        ),
                        center = Offset.Unspecified,
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = Spacing.xl)
                    .graphicsLayer {
                        scaleX = containerScale.value
                        scaleY = containerScale.value
                        alpha = containerAlpha.value
                    }
                    // 角色专属描边光晕（呼吸明暗），呼应 presenceGlow 的写法但用作静态描边
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accentColor.activated().copy(alpha = breathAlpha.value * 0.5f),
                                Color.Transparent,
                            ),
                        ),
                        shape = RoundedCornerShape(Radius.lg + 4.dp),
                    )
                    .padding(2.dp)
                    .clip(RoundedCornerShape(Radius.lg))
                    .parchmentSurface(isDark = isDark),
            ) {
                // P2-10 修复（重做）：将内容与按钮拆分为两个独立区域。
                // 文本内容区域可滚动（heightIn(max=480.dp) + verticalScroll），
                // 同意/拒绝按钮 Row 放在滚动区域之外，始终常驻显示。
                Column(
                    modifier = Modifier
                        .padding(horizontal = Spacing.lg, vertical = Spacing.lg)
                        .alpha(textAlpha.value),
                ) {
                    // ── 可滚动内容区 ──────────────────────────
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        GoldDivider(withDiamond = true, fadeEdges = true)

                        Spacer(modifier = Modifier.height(Spacing.sm))

                        Text(
                            text = "$characterName…",
                            style = type.cardTitle,
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(modifier = Modifier.height(Spacing.md))

                        Text(
                            text = dialogText,
                            style = type.body,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Start,
                        )

                        Spacer(modifier = Modifier.height(Spacing.lg))

                        GoldDivider(withDiamond = false, fadeEdges = true)
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // ── 按钮区（常驻，不参与滚动）──────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Radius.sm))
                                .clickable { handleReject() }
                                .padding(vertical = Spacing.sm),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "拒绝", style = type.button, color = colors.textSecondary)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(accentColor.activated())
                                .clickable { handleAccept() }
                                .padding(vertical = Spacing.sm),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "同意", style = type.button, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
