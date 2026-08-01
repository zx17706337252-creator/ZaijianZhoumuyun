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
    // UI 升级 v2.0（融合方案帧02/19：需要关注 = 火漆角标卡，火漆刻字三种牵挂）：
    // 单卡多行改为「每条目一张火漆角标卡」，刻字按条目类型分配——
    //   念 = 牵挂（久未联系/从未联系）   期 = 周期（孕育/排卵/经期）   隙 = 裂隙（关系紧张/恶化）
    // 预算纪律：本区最多 3 处火漆（与通知中心共用同一套刻字语义），
    // 超过 3 条时多余的条目不再压印（仪式感滥用即贬值）。
    Column(modifier = modifier) {
        Text("需要关注", style = ZaijianTheme.typography.cardTitle, color = Palette.Velvet)
        items.forEachIndexed { index, item ->
            val text: String
            val waxChar: String
            when (item) {
                is BriefingAttentionItem.NoContact -> {
                    text = "${item.character.name}：已经 ${item.days} 天没有联系了"
                    waxChar = "念"
                }
                is BriefingAttentionItem.NeverContacted -> {
                    text = "${item.character.name}：还没有联系过"
                    waxChar = "念"
                }
                is BriefingAttentionItem.Pregnancy -> {
                    text = "${item.character.name}：怀孕中，记得多关心"
                    waxChar = "期"
                }
                // A6-1 修复: 新增排卵期/经期两类条目的展示文案，
                // 与 BriefingCharacterCard 的 chip 文案（排卵期/经期）保持口径一致。
                is BriefingAttentionItem.FertileAttention -> {
                    text = "${item.characterName}：排卵期中，留意易孕窗口"
                    waxChar = "期"
                }
                is BriefingAttentionItem.MenstrualAttention -> {
                    text = "${item.characterName}：经期中，记得多关心"
                    waxChar = "期"
                }
                is BriefingAttentionItem.Tension -> {
                    val fromName = characterNameById(item.fromId, daughterNameMap)
                    val toName = characterNameById(item.toId, daughterNameMap)
                    text = "$fromName 和 $toName：关系紧张度较高（${item.tension}）"
                    waxChar = "隙"
                }
                is BriefingAttentionItem.RelationWorsened -> {
                    text = "${characterNameById(item.fromId, daughterNameMap)}：${item.description}"
                    waxChar = "隙"
                }
            }
            WorldCard(
                modifier = Modifier.padding(top = Spacing.sm),
                // 火漆预算：只给前 3 条压印，超出条目回落为素卡（isMilestone 也不再使用，
                // 金红不同卡——火漆卡内不再出现金色按钮/蜡封点）。
                waxChar = if (index < 3) waxChar else null,
            ) {
                Text(
                    text,
                    style = ZaijianTheme.typography.body,
                    color = Palette.VelvetSoft,
                    modifier = Modifier.padding(Spacing.cardPadding),
                )
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
