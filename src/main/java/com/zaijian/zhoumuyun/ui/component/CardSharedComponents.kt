package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.model.dotColor
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  CardSharedComponents — BookCard / WindowCard 公共子组件
//
//  UI M5/M6 修复：将两个卡片组件中重复出现的：
//    [1] 角色名带半透明背景条  → CharacterNameLabel
//    [2] 在线状态彩色小圆点    → StatusDot
//  提取为独立 internal Composable，消除重复代码。
//
//  调用方（BookCard / WindowCard）按需传入 isDark 以匹配
//  自身的主题感知逻辑，不在这里再读 CompositionLocal，
//  保持组件单纯可预测。
// ─────────────────────────────────────────────────────────────

/**
 * 角色名 + 半透明背景条。
 *
 * @param name      角色名字符串
 * @param isDark    是否深色主题（用于背景色 / 文字颜色切换）
 * @param modifier  外部追加 Modifier（如 align / offset）
 * @param bgAlphaDark   深色模式背景 alpha，BookCard 偏低（0.32f），WindowCard 偏高（0.40f）
 * @param bgAlphaLight  浅色模式背景 alpha
 * @param hPad      水平内边距（书架 6.dp，公馆 8.dp）
 */
@Composable
internal fun CharacterNameLabel(
    name: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    bgAlphaDark: Float = 0.36f,
    bgAlphaLight: Float = 0.45f,
    hPad: Int = 6,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (isDark) Color.Black.copy(alpha = bgAlphaDark)
                else Color.White.copy(alpha = bgAlphaLight)
            )
            .padding(horizontal = hPad.dp, vertical = 2.dp),
    ) {
        Text(
            text       = name,
            color      = if (isDark) Color.White.copy(alpha = 0.92f)
                         else Palette.CardNameTextLight.copy(alpha = 0.88f),
            fontSize   = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            textAlign  = TextAlign.Center,
        )
    }
}

/**
 * 在线状态彩色小圆点（8×8 dp）。
 *
 * 颜色由 [StatusType.dotColor()] 扩展函数统一决定（§设计规范 8），
 * 调用方只需传 statusType，无需自行指定颜色。
 *
 * @param statusType 当前在线状态；OFFLINE 时不显示，调用方应在 if 条件内使用本组件。
 * @param modifier   外部追加 Modifier（如 align / padding）
 * @param sizeDp     圆点直径，默认 8dp
 */
@Composable
internal fun StatusDot(
    statusType: StatusType,
    modifier: Modifier = Modifier,
    sizeDp: Int = 8,
) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(statusType.dotColor()),
    )
}
