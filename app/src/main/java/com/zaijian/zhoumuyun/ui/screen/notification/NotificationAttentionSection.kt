package com.zaijian.zhoumuyun.ui.screen.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
//
//  UI 升级 v2.0 帧19：characterId UI 层聚合分组 ——
//  同一角色的多条关注事项聚合到一个角色分组下展示，分组头显示
//  角色名 + 角色色点 + 未读计数，组内条目紧凑排列。避免同一角色
//  的通知散落在列表各处，让用户一眼看清"谁需要关注"。
// ─────────────────────────────────────────────────────────────

/** 角色分组数据结构。 */
private data class CharacterAttentionGroup(
    val characterId: String,
    val characterName: String,
    val accentColor: androidx.compose.ui.graphics.Color,
    val items: List<BriefingAttentionItem>,
)

@Composable
fun NotificationAttentionSection(
    items: List<BriefingAttentionItem>,
    readItems: Set<BriefingAttentionItem>,
    daughterNameMap: Map<String, String> = emptyMap(),
    onItemClick: (BriefingAttentionItem) -> Unit,
) {
    // UI 升级 v2.0（融合方案帧19：通知中心"需要关注"对齐简报页火漆角标卡）：
    // 单卡多行改为「每条目一张火漆角标卡」，刻字按条目类型分配——
    //   念 = 牵挂（久未联系/从未联系）   期 = 周期（孕育/排卵/经期）   隙 = 裂隙（关系紧张/恶化）
    // 预算纪律：本区最多 3 处火漆（与简报页共用同一套刻字语义），
    // 超过 3 条时多余的条目不再压印（仪式感滥用即贬值）。

    // ── 帧19：按 characterId 聚合分组 ──────────────────────
    // 将扁平条目列表按角色 ID 分组，同一角色的通知聚到同一组下。
    val groups = remember(items, daughterNameMap) {
        groupByCharacter(items, daughterNameMap)
    }

    Column {
        Text(
            "需要关注",
            style = ZaijianTheme.typography.cardTitle,
            color = if (items.isNotEmpty()) Palette.Velvet else Palette.VelvetSoft,
        )
        if (items.isEmpty()) {
            WorldCard(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
                EmptyStateView(
                    icon  = AppIcons.Notifications,
                    title = "暂无需要关注的事项 ✿",
                )
            }
        }

        // ── 分组展示 ──────────────────────────────────────
        // 全局火漆计数器：跨分组累计，保证全区 ≤3 处火漆。
        var waxIndex = 0

        groups.forEach { group ->
            // ── 角色分组头 ────────────────────────────────
            // 角色色点 + 角色名 + 未读数徽章
            val unreadCount = group.items.count { it !in readItems }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 角色色点
                Spacer(Modifier.size(8.dp).clip(CircleShape).background(group.accentColor))
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    text  = group.characterName,
                    style = ZaijianTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = Palette.Velvet,
                )
                if (unreadCount > 0) {
                    Spacer(Modifier.size(Spacing.xs))
                    Text(
                        text  = "$unreadCount 条未读",
                        style = ZaijianTheme.typography.label,
                        color = Palette.VelvetSoft,
                    )
                    Spacer(Modifier.size(Spacing.xs))
                    // 未读红点徽章
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Palette.SemanticDanger),
                    )
                }
            }

            // ── 组内条目 ──────────────────────────────────
            group.items.forEach { item ->
                val isRead = item in readItems
                val text = attentionItemText(item, daughterNameMap)
                val waxChar = waxCharForItem(item)
                val shouldWax = waxIndex < 3
                if (shouldWax) waxIndex++

                WorldCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs)
                        .clickable { onItemClick(item) },
                    waxChar = if (shouldWax) waxChar else null,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.cardPadding),
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
}

// ─────────────────────────────────────────────────────────────
//  分组工具函数
// ─────────────────────────────────────────────────────────────

/**
 * 按 characterId 将关注条目分组。
 *
 * 分组键：
 *   - NoContact/NeverContacted/Pregnancy → character.id
 *   - FertileAttention/MenstrualAttention → characterId
 *   - Tension/RelationWorsened → fromId（主角色）
 *
 * 组间顺序：保持原列表中每个角色首次出现的位置（稳定分组）。
 * 组内顺序：保持原列表中的相对顺序。
 */
private fun groupByCharacter(
    items: List<BriefingAttentionItem>,
    daughterNameMap: Map<String, String>,
): List<CharacterAttentionGroup> {
    val groupOrder = mutableListOf<String>()
    val groupItems = mutableMapOf<String, MutableList<BriefingAttentionItem>>()

    items.forEach { item ->
        val cid = extractCharacterId(item)
        if (cid !in groupItems) {
            groupOrder.add(cid)
            groupItems[cid] = mutableListOf()
        }
        groupItems[cid]!!.add(item)
    }

    return groupOrder.map { cid ->
        val groupItemList = groupItems[cid]!!
        val name = extractCharacterName(groupItemList.first(), daughterNameMap)
        val color = resolveAccentColor(cid)
        CharacterAttentionGroup(
            characterId   = cid,
            characterName = name,
            accentColor   = color,
            items         = groupItemList,
        )
    }
}

/** 从 BriefingAttentionItem 提取角色 ID（统一为 String）。 */
private fun extractCharacterId(item: BriefingAttentionItem): String = when (item) {
    is BriefingAttentionItem.NoContact       -> item.character.id.toString()
    is BriefingAttentionItem.NeverContacted  -> item.character.id.toString()
    is BriefingAttentionItem.Pregnancy       -> item.character.id.toString()
    is BriefingAttentionItem.FertileAttention   -> item.characterId.toString()
    is BriefingAttentionItem.MenstrualAttention -> item.characterId.toString()
    is BriefingAttentionItem.Tension         -> item.fromId
    is BriefingAttentionItem.RelationWorsened -> item.fromId
    is BriefingAttentionItem.QuoteReference   -> item.character.id.toString()
    is BriefingAttentionItem.AgreementDue     -> item.character.id.toString()
}

/** 从 BriefingAttentionItem 提取角色显示名。 */
private fun extractCharacterName(
    item: BriefingAttentionItem,
    daughterNameMap: Map<String, String>,
): String = when (item) {
    is BriefingAttentionItem.NoContact       -> item.character.name
    is BriefingAttentionItem.NeverContacted  -> item.character.name
    is BriefingAttentionItem.Pregnancy       -> item.character.name
    is BriefingAttentionItem.FertileAttention   -> item.characterName
    is BriefingAttentionItem.MenstrualAttention -> item.characterName
    is BriefingAttentionItem.Tension         -> characterNameById(item.fromId, daughterNameMap)
    is BriefingAttentionItem.RelationWorsened -> characterNameById(item.fromId, daughterNameMap)
    is BriefingAttentionItem.QuoteReference   -> item.character.name
    is BriefingAttentionItem.AgreementDue     -> item.character.name
}

/** 通过角色 ID 查找主题色，用于分组头色点。 */
private fun resolveAccentColor(characterId: String): androidx.compose.ui.graphics.Color {
    val intId = characterId.toIntOrNull() ?: return Palette.Gold
    return DefaultCharacters.firstOrNull { it.id == intId }?.accentColor ?: Palette.Gold
}

/** 火漆刻字映射，与 BriefingAttentionSection 完全一致（念/期/隙）。 */
private fun waxCharForItem(item: BriefingAttentionItem): String = when (item) {
    is BriefingAttentionItem.NoContact -> "念"
    is BriefingAttentionItem.NeverContacted -> "念"
    is BriefingAttentionItem.Pregnancy -> "期"
    is BriefingAttentionItem.FertileAttention -> "期"
    is BriefingAttentionItem.MenstrualAttention -> "期"
    is BriefingAttentionItem.Tension -> "隙"
    is BriefingAttentionItem.RelationWorsened -> "隙"
    is BriefingAttentionItem.QuoteReference   -> "念"
    is BriefingAttentionItem.AgreementDue     -> "期"
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
    is BriefingAttentionItem.QuoteReference ->
        "${item.character.name}：上次听她说「${item.snippet}」"
    is BriefingAttentionItem.AgreementDue ->
        "${item.character.name}：有条约定的事在推进「${item.taskTitle}」"
}

private fun characterNameById(id: String, daughterNameMap: Map<String, String>): String =
    DefaultCharacters.firstOrNull { it.id.toString() == id }?.name
        ?: daughterNameMap[id]
        ?: id
