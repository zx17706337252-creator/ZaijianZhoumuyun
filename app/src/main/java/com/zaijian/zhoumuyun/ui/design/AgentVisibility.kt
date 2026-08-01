package com.zaijian.zhoumuyun.ui.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaijian.zhoumuyun.ui.theme.AppBrushes
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.SerifSC
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  AgentVisibility.kt — UI 升级 v2.0（鎏金纸梦融合方案）新增组件族
//
//  「AI 工作过程可视化」+「火漆仪式印章」两组：
//
//    WaxSealBadge    火漆刻字角标（珍/念/期/隙/缔，全 App ≤8 处）
//    AiStatePill     顶栏状态胶囊（正在思考/正在使用工具/正在输入）
//    TypingDots      打字指示（三点金，1.2s 阶梯起伏）
//    StreamingCursor 流式光标（▍ 金色 1s 步进闪烁）
//    ToolStatusIcon  工具四态指示（运行中金圈 / ✓成功 / ✗失败 / ⏱超时）
//    ToolCallRowCard 工具调用行（图标块 + 名称 + 命令 + 状态 + 耗时）
//    shimmerEffect   思考进行态微光（1.6s 线性扫过）
//
//  设计原则（融合方案第四章）：
//    1. 过程可见 ≠ 过程打扰——状态原地替换，不打断气泡流；
//    2. 四态语义色全局唯一——运行中金 / 成功绿 / 失败红 / 超时黄；
//    3. 思考内容默认收起，主动点开，保留「只看结果」的清净。
// ─────────────────────────────────────────────────────────────

// ═════════════════════════════════════════════════════════════
//  WaxSealBadge — 火漆刻字角标
// ═════════════════════════════════════════════════════════════

/**
 * 火漆印：径向高光三档（circle at 36% 30%：WaxHi → Wax 58% → WaxDeep）+
 * 内圈 1px 浅刻痕 + 衬线刻字 + 微旋转（火漆从不端正）。
 *
 * 预算纪律（融合方案 §3.3）：全 App ≤8 处，仅置顶记忆「珍」、需要关注
 * 「念/期/隙」、关系升阶「缔」等稀缺时刻。调用点白名单制——新增一处
 * 必须先赎回一处旧的。
 */
@Composable
fun WaxSealBadge(
    char: String,
    modifier: Modifier = Modifier,
    size: Dp = 27.dp,
    rotateDeg: Float = -8f,
) {
    Box(
        modifier = modifier
            .size(size)
            .rotate(rotateDeg)
            // 投影：绛红晕 + 内嵌上下高光（模拟蜡的厚度）
            .drawBehind {
                drawCircle(
                    color = Palette.WaxDeep.copy(alpha = 0.40f),
                    radius = this.size.minDimension * 0.62f,
                    center = center.copy(y = center.y + 2.dp.toPx()),
                )
            }
            .clip(CircleShape)
            .drawBehind {
                val r = this.size.minDimension / 2f
                drawCircle(
                    brush = AppBrushes.waxRadial(
                        center = Offset(this.size.width * 0.36f, this.size.height * 0.30f),
                        radius = r * 2.2f,
                    ),
                    radius = r,
                )
                // 内嵌顶部高光 / 底部暗边
                drawCircle(
                    color = Color.White.copy(alpha = 0.28f),
                    radius = r * 0.86f,
                    center = Offset(this.size.width / 2f, this.size.height * 0.18f),
                    style = Stroke(width = 1.dp.toPx()),
                )
                // 内圈 1px 浅刻痕
                drawCircle(
                    color = Color(0xFFFFE2E2).copy(alpha = 0.38f),
                    radius = r * 0.72f,
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = char,
            fontFamily = SerifSC,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.46f).sp,
            color = Color(0xFFFFEEEE).copy(alpha = 0.92f),
        )
    }
}

// ═════════════════════════════════════════════════════════════
//  AiStatePill — AI 工作状态胶囊（顶栏/列表上方）
// ═════════════════════════════════════════════════════════════

/**
 * 状态胶囊：12% 金底 + 0.5px 金边 + 金点 pulse 1.2s。
 * 用于「顾澜正在思考… / 正在使用工具 · xxx / 正在输入…」，
 * 原地替换文本，不打断气泡流。
 */
@Composable
fun AiStatePill(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val infinite = rememberInfiniteTransition(label = "aiStatePulse")
    val dotAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aiStateDot",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.accent.copy(alpha = 0.12f))
            .border(0.5.dp, colors.accent.copy(alpha = 0.40f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = dotAlpha)),
        )
        Text(
            text = text,
            fontSize = 10.5.sp,
            color = colors.accentDeep,
            letterSpacing = 0.02.sp,
        )
    }
}

// ═════════════════════════════════════════════════════════════
//  TypingDots — 打字指示（正在输入）
// ═════════════════════════════════════════════════════════════

/** 三点金，1.2s 阶梯 0.2s 起伏；壳用角色气泡形（20/20/20/4）。 */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    dotColor: Color = Palette.Gold,
) {
    val infinite = rememberInfiniteTransition(label = "typingDots")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
            .background(ZaijianTheme.colors.bgCard)
            .border(0.5.dp, ZaijianTheme.colors.border, RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val offsetY by infinite.animateFloat(
                initialValue = 0f,
                targetValue = -3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .offset(y = offsetY.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════
//  StreamingCursor — 流式光标 ▍
// ═════════════════════════════════════════════════════════════

/** 金色流式光标，1s steps(1) 闪烁，跟随流式文本尾部。 */
@Composable
fun StreamingCursor(
    modifier: Modifier = Modifier,
    color: Color = Palette.Gold,
) {
    val infinite = rememberInfiniteTransition(label = "streamCursor")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    Box(
        modifier = modifier
            .width(8.dp)
            .height(15.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(color.copy(alpha = alpha)),
    )
}

// ═════════════════════════════════════════════════════════════
//  ToolStatusIcon — 工具调用四态指示
// ═════════════════════════════════════════════════════════════

/** 工具调用状态：运行中 / 成功 / 失败 / 超时。语义色全局唯一。 */
enum class ToolStatus { RUNNING, SUCCESS, FAILED, TIMEOUT }

@Composable
fun ToolStatusIcon(
    status: ToolStatus,
    modifier: Modifier = Modifier,
    size: Dp = 11.dp,
) {
    val colors = ZaijianTheme.colors
    when (status) {
        ToolStatus.RUNNING -> {
            val infinite = rememberInfiniteTransition(label = "toolSpin")
            val angle by infinite.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                ),
                label = "spinAngle",
            )
            Box(
                modifier = modifier
                    .size(size)
                    .rotate(angle)
                    .drawBehind {
                        drawArc(
                            color = colors.accentDeep,
                            startAngle = 0f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    },
            )
        }
        ToolStatus.SUCCESS -> Text("✓", fontSize = (size.value).sp, color = Palette.SemanticSuccess, modifier = modifier)
        ToolStatus.FAILED  -> Text("✗", fontSize = (size.value).sp, color = Palette.SemanticDanger, modifier = modifier)
        ToolStatus.TIMEOUT -> Text("⏱", fontSize = (size.value * 0.9f).sp, color = Palette.SemanticReminder, modifier = modifier)
    }
}

/** 状态对应的语义色（文字/耗时同用）。 */
@Composable
fun toolStatusColor(status: ToolStatus): Color = when (status) {
    ToolStatus.RUNNING -> ZaijianTheme.colors.accentDeep
    ToolStatus.SUCCESS -> Palette.SemanticSuccess
    ToolStatus.FAILED  -> Palette.SemanticDanger
    ToolStatus.TIMEOUT -> Palette.SemanticReminder
}

// ═════════════════════════════════════════════════════════════
//  ToolCallRowCard — 工具调用行（v2.0 样式）
// ═════════════════════════════════════════════════════════════

/**
 * 工具调用行：24px 图标块（12% 金底圆角方块）+ 名称 + 调用命令（等宽）+
 * 右侧状态（四态 + 耗时）。bgElevated 底 + 发丝边 + Radius 12。
 *
 * 融合方案 §4.1：四态语义——运行中(金圈转)/成功(✓绿)/失败(✗红)/超时(⏱黄)。
 */
@Composable
fun ToolCallRowCard(
    name: String,
    command: String,
    status: ToolStatus,
    durationText: String = "",
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = ZaijianTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.bgElevated)
            .border(0.5.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                icon()
            } else {
                Text("⚙", fontSize = 12.sp, color = colors.accentDeep)
            }
        }
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(name, fontSize = 12.5.sp, color = colors.textPrimary)
            if (command.isNotBlank()) {
                Text(command, fontSize = 10.5.sp, color = colors.textDisabled)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ToolStatusIcon(status = status)
            if (durationText.isNotBlank()) {
                Text(
                    durationText,
                    fontSize = 10.5.sp,
                    color = toolStatusColor(status),
                )
            } else if (status != ToolStatus.RUNNING) {
                Text(
                    when (status) {
                        ToolStatus.SUCCESS -> "成功"
                        ToolStatus.FAILED  -> "失败"
                        ToolStatus.TIMEOUT -> "超时"
                        else -> ""
                    },
                    fontSize = 10.5.sp,
                    color = toolStatusColor(status),
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════
//  shimmerEffect — 思考进行态微光扫过
// ═════════════════════════════════════════════════════════════

/**
 * 思考卡进行态：一道纸白高光 1.6s 线性扫过（从左到右，循环）。
 * 挂在思考卡标题行或整卡上，生成停止后移除本 Modifier 即可。
 */
fun Modifier.shimmerEffect(): Modifier = composed {
    val infinite = rememberInfiniteTransition(label = "thinkShimmer")
    val fraction by infinite.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
        ),
        label = "shimmerX",
    )
    drawBehind {
        val w = size.width
        val bandWidth = w * 0.35f
        val x = fraction * w
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.55f),
                    Color.Transparent,
                ),
                startX = x - bandWidth / 2f,
                endX = x + bandWidth / 2f,
            ),
        )
    }
}
