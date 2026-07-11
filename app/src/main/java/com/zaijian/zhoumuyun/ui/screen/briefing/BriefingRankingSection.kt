package com.zaijian.zhoumuyun.ui.screen.briefing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zaijian.zhoumuyun.data.model.BriefingCharacterEntry
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  BriefingRankingSection —— 亲密度排行榜（Top 5）
//  整合方案 v2.1 4.10.3 节。
// ─────────────────────────────────────────────────────────────

@Composable
fun BriefingRankingSection(ranking: List<BriefingCharacterEntry>, modifier: Modifier = Modifier) {
    WorldCard(modifier = modifier) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            Text("亲密度排行", style = ZaijianTheme.typography.cardTitle)
            ranking.take(5).forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${index + 1}. ${entry.character.name}", style = ZaijianTheme.typography.body)
                    Text("${entry.relation?.affection ?: 0}", style = ZaijianTheme.typography.labelMono)
                }
            }
        }
    }
}
