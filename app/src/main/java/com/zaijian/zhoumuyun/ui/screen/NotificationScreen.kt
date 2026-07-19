package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.model.BriefingAttentionItem
import com.zaijian.zhoumuyun.ui.screen.notification.NotificationAttentionSection
import com.zaijian.zhoumuyun.ui.screen.notification.NotificationGoodNewsSection
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.NotificationViewModel

// ─────────────────────────────────────────────────────────────
//  NotificationScreen — 通知中心
//  通知中心设计方案 全文。
// ─────────────────────────────────────────────────────────────

@Composable
fun NotificationScreen(
    onBack: () -> Unit,
    // Tension/RelationWorsened → 角色详情页关系 Tab（characterId, tab=RELATION_TAB_INDEX）
    onNavigateToCharacterRelationTab: (characterId: Int) -> Unit,
    // NoContact/NeverContacted/Pregnancy → 直接进聊天
    onNavigateToChat: (characterId: Int) -> Unit,
    viewModel: NotificationViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = ZaijianTheme.colors

    Scaffold(containerColor = colors.bgBase) { innerPadding ->
        when {
            uiState.isLoading -> {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            uiState.error != null -> {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { Text(uiState.error!!, color = colors.textSecondary) }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.screenHorizontal),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.md),
                ) {
                    item {
                        NotificationAttentionSection(
                            items           = uiState.attentionItems,
                            readItems       = uiState.readItems,
                            daughterNameMap = uiState.daughterNameMap,

                            onItemClick     = { item ->
                                viewModel.markItemRead(item)
                                routeAttentionItemClick(
                                    item                             = item,
                                    onNavigateToCharacterRelationTab = onNavigateToCharacterRelationTab,
                                    onNavigateToChat                 = onNavigateToChat,
                                )
                            },
                        )
                    }
                    item {
                        NotificationGoodNewsSection(items = uiState.goodNewsItems)
                    }
                }
            }
        }
    }
}

/**
 * 点击跳转分流：关系类（Tension/RelationWorsened）→ 角色详情页关系 Tab，
 * 联系类（NoContact/NeverContacted）→ 直接进聊天。
 * Pregnancy 语义上更接近"关心状态"，不强制归到某个跳转分类，这里先
 * 归到聊天（进去看看角色本身），如果之后有专门的孕期页面入口，
 * 在此单独加分支即可。
 */
private fun routeAttentionItemClick(
    item: BriefingAttentionItem,
    onNavigateToCharacterRelationTab: (Int) -> Unit,
    onNavigateToChat: (Int) -> Unit,
) {
    when (item) {
        is BriefingAttentionItem.NoContact -> onNavigateToChat(item.character.id)
        is BriefingAttentionItem.NeverContacted -> onNavigateToChat(item.character.id)
        is BriefingAttentionItem.Pregnancy -> onNavigateToChat(item.character.id)
        is BriefingAttentionItem.Tension -> {
            item.fromId.toIntOrNull()?.let(onNavigateToCharacterRelationTab)
        }
        is BriefingAttentionItem.RelationWorsened -> {
            item.fromId.toIntOrNull()?.let(onNavigateToCharacterRelationTab)
        }
    }
}