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
import androidx.compose.ui.draw.alpha
import com.zaijian.zhoumuyun.data.model.BriefingAttentionItem
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.design.AppIcons

// ─────────────────────────────────────────────────────────────
//  NotificationAttentionSection — 通知中心"需要关注"区块
//  通知中心设计方案 第二、五节。
//
//  文案措辞与 BriefingAttentionSection.kt 保持一致（同样"钉死"，
//  不做自由发挥），区别在于本组件额外支持点击跳转 + 已读/未读视觉态。
//
//  已读判定直接用 `item in readItems`（技术债清理，见
//  CHANGES_S9_window01_notification_center.md 技术债第 2 条）：
//  ViewModel 传入的是"已读条目集合"本身，而不是字符串 key 集合，
//  本组件不再需要拼接一份 itemKey 跟 NotificationRepository.buildItemKey()
//  手动保持同步。
// ─────────────────────────────────────────────────────────────

@Composable
fun NotificationAttentionSection(
    items: List<BriefingAttentionItem>,
    readItems: Set<BriefingAttentionItem>,
    daughterNameMap: Map<String, String> = emptyMap(),
    onItemClick: (BriefingAttentionItem) -> Unit,
) {
    // 空数据时不再用 isMilestone=true 的强调色卡片——那套配色是为"有事项需要
    // 用户注意"设计的视觉警示，事项为空时继续用它，反而在传递错误的紧张感。
    // 改用默认卡片样式，标题也换成柔和色，视觉上和"一切安好"的语义对上。
    WorldCard(isMilestone = items.isNotEmpty()) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            Text(
                "需要关注",
                style = ZaijianTheme.typography.cardTitle,
                color = if (items.isNotEmpty()) Palette.Velvet else Palette.VelvetSoft,
            )
            if (items.isEmpty()) {
                EmptyStateView(
                    icon  = AppIcons.Notifications,
                    title = "暂无需要关注的事项 ✿",
                )
            }
            items.forEach { item ->
                val isRead = item in readItems
                val text = attentionItemText(item, daughterNameMap)

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
                        modifier = Modifier.weight(1f).alpha(if (isRead) 0.5f else 1f),
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

// 复用 BriefingAttentionSection.kt 已有的文案格式，原样照抄，
// 不重新措辞——设计稿明确这些句子是"钉死"的，保持公馆语言语气一致。
private fun attentionItemText(
    item: BriefingAttentionItem,
    daughterNameMap: Map<String, String>,
): String = when (item) {
    is BriefingAttentionItem.NoContact ->
        "${item.character.name}：已经 ${item.days} 天没有联系了"
    is BriefingAttentionItem.NeverContacted ->
        "${item.character.name}：还没有联系过"
    is BriefingAttentionItem.Pregnancy ->
        "${item.character.name}：怀孕中，记得多关心"
    // A6-1 修复: 排卵期/经期文案与 BriefingAttentionSection 保持一致，
    // 通知中心与简报页两处展示口径统一。
    is BriefingAttentionItem.FertileAttention ->
        "${item.characterName}：排卵期中，留意易孕窗口"
    is BriefingAttentionItem.MenstrualAttention ->
        "${item.characterName}：经期中，记得多关心"
    is BriefingAttentionItem.Tension -> {
        val fromName = characterNameById(item.fromId, daughterNameMap)
        val toName = characterNameById(item.toId, daughterNameMap)
        "$fromName 和 $toName：关系紧张度较高（${item.tension}）"
    }
    is BriefingAttentionItem.RelationWorsened ->
        "${characterNameById(item.fromId, daughterNameMap)}：${item.description}"
}

private fun characterNameById(id: String, daughterNameMap: Map<String, String>): String =
    DefaultCharacters.firstOrNull { it.id.toString() == id }?.name
        ?: daughterNameMap[id]
        ?: id