package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.model.dotColor

// ─────────────────────────────────────────────────────────────
//  CharacterNameLabel / StatusDot
//  UI M5/M6 修复：从 WindowCard / BookCard 重复实现中提取的共享组件。
//
//  说明：WindowCard 在 v56 已改为内联纯文字+阴影（不带背景色块，见
//  WindowCard.kt 内 v56 注释），BookCard 仍保留半透明背景条方案
//  （见 BookCard.kt 235 行注释），因此 CharacterNameLabel 目前只被
//  BookCard 使用，按 BookCard 原有语义（bgAlphaDark/bgAlphaLight/hPad）
//  重建。StatusDot 与 BreathingAvatar.kt 中的 StatusDotOnly 视觉规格
//  一致（右下角状态点 + 白色描边），两处调用点签名与其完全匹配。
// ─────────────────────────────────────────────────────────────

@Composable
fun CharacterNameLabel(
    name: String,
    isDark: Boolean,
    bgAlphaDark: Float,
    bgAlphaLight: Float,
    hPad: Int,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isDark) {
        Color.Black.copy(alpha = bgAlphaDark)
    } else {
        Color.Black.copy(alpha = bgAlphaLight)
    }

    Text(
        text       = name,
        color      = Color.White,
        fontSize   = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines   = 1,
        overflow   = TextOverflow.Ellipsis,
        textAlign  = TextAlign.Center,
        style      = TextStyle(
            shadow = Shadow(
                color      = Color.Black.copy(alpha = 0.85f),
                offset     = Offset(0f, 1f),
                blurRadius = 3f,
            ),
        ),
        modifier   = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = hPad.dp, vertical = 1.dp),
    )
}

@Composable
fun StatusDot(
    statusType: StatusType,
    modifier: Modifier = Modifier,
) {
    val dotColor = statusType.dotColor()
    Canvas(modifier = modifier) {
        val dotRadius = 5.dp.toPx()
        val dotCenter = Offset(
            x = size.width  - dotRadius,
            y = size.height - dotRadius,
        )
        drawCircle(color = Color.White, radius = dotRadius + 1.5f, center = dotCenter)
        drawCircle(color = dotColor,    radius = dotRadius,         center = dotCenter)
    }
}
