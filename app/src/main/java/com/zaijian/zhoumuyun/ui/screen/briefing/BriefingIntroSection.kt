package com.zaijian.zhoumuyun.ui.screen.briefing

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  BriefingIntroSection —— 简报开场引导语
//  整合方案 v2.1 4.10.3 节。
//
//  单独抽成文件（而非内联在 BriefingScreen 的 item {} 里）的原因：
//  这段文案要做 periodStart/periodEnd → "X天" 的时间换算，逻辑不是
//  纯 UI 摆放，独立出来方便单测，也和其余三个子文件的拆分粒度保持一致。
// ─────────────────────────────────────────────────────────────

@Composable
fun BriefingIntroSection(periodStart: Long, periodEnd: Long, modifier: Modifier = Modifier) {
    val days = ((periodEnd - periodStart) / 86_400_000L).coerceAtLeast(0)
    val text = if (days == 0L) "你刚离开不久，公馆里一切如常" else "你离开的这 $days 天里，公馆发生了这些事"
    Text(
        text = text,
        style = ZaijianTheme.typography.cardTitle,
        modifier = modifier.padding(Spacing.screenHorizontal),
    )
}
