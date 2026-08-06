package com.zaijian.zhoumuyun.ui.screen.briefing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.model.BriefingCharacterEntry
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.AppBrushes
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  BriefingRankingSection —— 亲密度排行榜
//  整合方案 v2.1 4.10.3 节，本轮按《离线简报 UI 改版交接文档》去掉硬编码
//  take(5)：数据层（BriefingRepository.generateBriefing()）本来就是
//  "9位母亲 + 全部已注册女儿"，二代/三代注册后会自动出现在 ranking 里，
//  问题只在 UI 截断，这里改为展示 ranking 全部。
//  每行加角色专属色小圆点（呼应角色卡视觉语言），前三名名次数字用
//  accent 色高亮。
// ─────────────────────────────────────────────────────────────

@Composable
fun BriefingRankingSection(ranking: List<BriefingCharacterEntry>, modifier: Modifier = Modifier) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    WorldCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            Text("好感排行", style = type.cardTitle)
            // 最高分作为金条比例基准（第 1 名 100%，其余按实际分数 / 最高分）。
            val maxScore = ranking.maxOfOrNull { it.relation?.affection ?: 0 } ?: 0
            ranking.forEachIndexed { index, entry ->
                val score = entry.relation?.affection ?: 0
                val fraction = if (maxScore > 0)
                    (score.toFloat() / maxScore).coerceIn(0f, 1f) else 0f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xs / 2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧：名次 + 角色色圆点 + 名字
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val rankColor = if (index < 3) colors.accentDeep else colors.textSecondary
                        Text(
                            text = "${index + 1}.",
                            style = type.labelMono,
                            color = rankColor,
                            modifier = Modifier.width(20.dp),
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colors.accent)
                        )
                        Text(
                            text = entry.character.name,
                            style = type.body,
                            modifier = Modifier.padding(start = Spacing.xs),
                        )
                    }
                    // 金条进度条（名字与分数之间，weight(1f) 占据中间空间）
                    // 高 6dp、底 accentSoft、内层按分数比例填充 goldGradient。
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = Spacing.sm)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.accentSoft),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(AppBrushes.goldGradient()),
                        )
                    }
                    // 右侧：分数
                    Text(
                        text = "$score",
                        style = type.labelMono,
                        modifier = Modifier.padding(start = Spacing.xs),
                    )
                }
            }
        }
    }
}
