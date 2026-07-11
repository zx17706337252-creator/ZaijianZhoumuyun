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
// ─────────────────────────────────────────────────────────────

@Composable
fun BriefingAttentionSection(items: List<BriefingAttentionItem>, modifier: Modifier = Modifier) {
    WorldCard(modifier = modifier, isMilestone = true) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            Text("需要关注", style = ZaijianTheme.typography.cardTitle, color = Palette.Velvet)
            items.forEach { item ->
                val text = when (item) {
                    is BriefingAttentionItem.NoContact ->
                        "${item.character.name}：已经 ${item.days} 天没有联系了"
                    is BriefingAttentionItem.Pregnancy ->
                        "${item.character.name}：怀孕中，记得多关心"
                    is BriefingAttentionItem.Tension -> {
                        val fromName = characterNameById(item.fromId)
                        val toName = characterNameById(item.toId)
                        "$fromName 和 $toName：关系紧张度较高（${item.tension}）"
                    }
                    is BriefingAttentionItem.RelationWorsened ->
                        "${characterNameById(item.fromId)}：${item.description}"
                }
                Text(text, style = ZaijianTheme.typography.body, color = Palette.VelvetSoft)
            }
        }
    }
}

/**
 * fromId/toId 是字符串形式的角色ID，这里统一转名字，找不到时兜底显示原始ID。
 *
 * 目前只查了 DefaultCharacters（9 位母亲），没查女儿——如果 Tension/
 * RelationWorsened 涉及女儿角色间的紧张关系，会显示成裸 ID 而不是名字。
 * 母亲之间的紧张关系是目前 Bot↔Bot 互动的主要场景，女儿间互动如果后续
 * 接入圆桌，需要把这里改成挂起函数去查 daughterCharacterRepo（整合方案
 * v2.1 4.10.3 节原文标注，此处按最小实现处理，不代为实现）。
 */
private fun characterNameById(id: String): String =
    DefaultCharacters.firstOrNull { it.id.toString() == id }?.name ?: id
