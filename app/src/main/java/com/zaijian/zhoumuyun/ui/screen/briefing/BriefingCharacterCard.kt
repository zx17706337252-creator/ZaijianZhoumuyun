package com.zaijian.zhoumuyun.ui.screen.briefing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zaijian.zhoumuyun.data.model.BriefingCharacterEntry
import com.zaijian.zhoumuyun.data.model.CyclePhase
import com.zaijian.zhoumuyun.ui.design.BondRibbon
import com.zaijian.zhoumuyun.ui.design.BondStage
import com.zaijian.zhoumuyun.ui.design.InfoChip
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  BriefingCharacterCard —— 离线简报单个角色卡片
//  整合方案 v2.1 4.10.3 节。相对 4.8 节原版的两处修正：
//  isMilestone 落实为 entry.hasRecentGoodMilestone（真实判断，而非
//  硬编码 false）；补上 cyclePhase / competitionScore 的展示。
// ─────────────────────────────────────────────────────────────

@Composable
fun BriefingCharacterCard(entry: BriefingCharacterEntry, modifier: Modifier = Modifier) {
    WorldCard(
        modifier    = modifier,
        ownerAccent = entry.character.accentColor,
        isMilestone = entry.hasRecentGoodMilestone,
    ) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            Text(entry.character.name, style = ZaijianTheme.typography.cardTitle)

            BondRibbon(
                stage       = BondStage.valueOf(entry.relation?.stage ?: "STRANGER"),
                accentColor = entry.character.accentColor,
                showLabels  = true,
                suppression = entry.relation?.suppression,
            )

            Text("完成任务 ${entry.completedTaskCount} 个", style = ZaijianTheme.typography.labelMono)

            if (entry.projectNames.isNotEmpty()) {
                Text("参与项目：${entry.projectNames.joinToString("、")}", style = ZaijianTheme.typography.labelMono)
            }

            entry.competitionScore?.let { score ->
                Text("最近评分 ${"%.1f".format(score)}", style = ZaijianTheme.typography.labelMono)
            }

            if (entry.isPregnant) {
                InfoChip(text = "怀孕中", color = Palette.SemanticReminder)
            } else if (entry.cyclePhase == CyclePhase.FERTILE) {
                InfoChip(text = "排卵期", color = Palette.SemanticReminder)
            }
        }
    }
}
