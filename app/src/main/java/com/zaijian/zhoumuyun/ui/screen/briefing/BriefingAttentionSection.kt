package com.zaijian.zhoumuyun.ui.screen.briefing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zaijian.zhoumuyun.data.model.BriefingAttentionItem
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  BriefingAttentionSection —— "需要关注" 板块
//  整合方案 v2.1 4.10.3 节。四种文案格式钉死，不做自由发挥，
//  以保持与"公馆语言"（蜡封/Velvet 语汇）的语气一致。
//
//  S8-窗口01 修复：原先在 Composable 内自行 LaunchedEffect +
//  AppContainer.instance.daughterCharacterRepo 查询女儿角色名映射，是 UI 层
//  绕过 ViewModel 直接访问 Repository 的分层违规。该查询逻辑已搬迁至
//  BriefingViewModel.loadDaughterNameMap()，由 BriefingUiState.daughterNameMap
//  统一持有；本组件现在是纯展示组件，只接收数据、不做任何数据访问。
// ─────────────────────────────────────────────────────────────

@Composable
fun BriefingAttentionSection(
    items: List<BriefingAttentionItem>,
    daughterNameMap: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    WorldCard(modifier = modifier, isMilestone = true) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            Text("需要关注", style = ZaijianTheme.typography.cardTitle, color = Palette.Velvet)
            items.forEach { item ->
                val text = when (item) {
                    is BriefingAttentionItem.NoContact ->
                        "${item.character.name}：已经 ${item.days} 天没有联系了"
                    is BriefingAttentionItem.NeverContacted ->
                        "${item.character.name}：还没有联系过"
                    is BriefingAttentionItem.Pregnancy ->
                        "${item.character.name}：怀孕中，记得多关心"
                    // A6-1 修复: 新增排卵期/经期两类条目的展示文案，
                    // 与 BriefingCharacterCard 的 chip 文案（排卵期/经期）保持口径一致。
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
                Text(text, style = ZaijianTheme.typography.body, color = Palette.VelvetSoft)
            }
        }
    }
}

/**
 * fromId/toId 是字符串形式的角色ID，这里统一转名字，找不到时兜底显示原始ID。
 *
 * P1-18 修复：增加 daughterNameMap 参数，优先查 DefaultCharacters（9 位母亲），
 * 查不到时回退到预加载的女儿角色名映射，再查不到才兜底显示原始 ID。
 */
private fun characterNameById(id: String, daughterNameMap: Map<String, String> = emptyMap()): String =
    DefaultCharacters.firstOrNull { it.id.toString() == id }?.name
        ?: daughterNameMap[id]
        ?: id
