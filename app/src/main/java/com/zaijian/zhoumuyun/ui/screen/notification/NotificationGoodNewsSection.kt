package com.zaijian.zhoumuyun.ui.screen.notification

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zaijian.zhoumuyun.data.repository.GoodNewsItem
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.design.AppIcons

// ─────────────────────────────────────────────────────────────
//  NotificationGoodNewsSection — 通知中心"好消息"区块
//  通知中心设计方案 第二节。视觉上与"需要关注"明确分区，避免
//  混在一起冲淡警示性——因此不复用 WorldCard(isMilestone = true)
//  那套强调色，用默认（非 milestone）卡片样式区分开。
//
//  之前 items 为空时整块 return，连标题都不渲染，和"需要关注"
//  区块（空标题卡片照样画出来）呈现方式正好相反，观感上不一致，
//  容易被误读成没做完。现在统一保留标题，空数据时换成占位文案，
//  而不是让卡片凭空消失。
//
//  P0修复：好消息条目可点击跳转到对应角色详情页，与"需要关注"
//  区块的点击交互对齐，不再只是静态文本。
// ─────────────────────────────────────────────────────────────

@Composable
fun NotificationGoodNewsSection(
    items: List<GoodNewsItem>,
    onItemClick: (GoodNewsItem) -> Unit = {},
) {
    WorldCard(isMilestone = false) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            Text("好消息", style = ZaijianTheme.typography.cardTitle, color = Palette.Velvet)
            if (items.isEmpty()) {
                EmptyStateView(
                    icon  = AppIcons.SentimentSatisfied,
                    title = "暂时还没有新的好消息，公馆一切如常 ✿",
                )
            }
            items.forEach { item ->
                val text = when (item) {
                    is GoodNewsItem.MilestoneRepaired ->
                        "${item.entry.character.name}：关系有了新的修复"
                    is GoodNewsItem.HighCompetitionScore ->
                        "${item.entry.character.name}：本周期竞赛评分表现出色（${"%.1f".format(item.score)}）"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(item) }
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text     = text,
                        style    = ZaijianTheme.typography.body,
                        color    = Palette.VelvetSoft,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector        = AppIcons.ChevronRight,
                        contentDescription = "去看看",
                        tint               = Palette.VelvetSoft,
                    )
                }
            }
        }
    }
}
