package com.zaijian.zhoumuyun.ui.screen.briefing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.model.BriefingAttentionItem
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────
//  BriefingAttentionSection —— "需要关注" 板块
//  整合方案 v2.1 4.10.3 节。四种文案格式钉死，不做自由发挥，
//  以保持与"公馆语言"（蜡封/Velvet 语汇）的语气一致。
// ─────────────────────────────────────────────────────────────

@Composable
fun BriefingAttentionSection(items: List<BriefingAttentionItem>, modifier: Modifier = Modifier) {
    // P1-18 修复：预加载女儿角色名映射，避免 Tension/RelationWorsened 中涉及
    // 女儿角色时显示裸 ID。收集所有 fromId/toId 中 >= 1000 的 ID，异步查询
    // DaughterCharacterRepository，填充到 daughterNameMap 中供 characterNameById 使用。
    var daughterNameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(items) {
        val daughterIds = items.flatMap { item ->
            when (item) {
                is BriefingAttentionItem.Tension -> listOf(item.fromId, item.toId)
                is BriefingAttentionItem.RelationWorsened -> listOf(item.fromId, item.toId)
                else -> emptyList()
            }
        }.mapNotNull { id ->
            id.toIntOrNull()?.takeIf { it >= 1000 }
        }.distinct()

        if (daughterIds.isNotEmpty()) {
            val map = withContext(Dispatchers.IO) {
                val repo = AppContainer.instance.daughterCharacterRepo
                daughterIds.mapNotNull { daughterId ->
                    try {
                        val config = repo.getCharacterConfig(daughterId)
                        config?.let { daughterId.toString() to it.name }
                    } catch (_: Exception) {
                        null
                    }
                }.toMap()
            }
            daughterNameMap = map
        }
    }

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
