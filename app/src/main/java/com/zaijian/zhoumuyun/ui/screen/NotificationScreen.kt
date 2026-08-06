package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.model.BriefingAttentionItem
import com.zaijian.zhoumuyun.data.repository.GoodNewsItem
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.design.SecondaryGoldButton
import com.zaijian.zhoumuyun.ui.screen.notification.NotificationAttentionSection
import com.zaijian.zhoumuyun.ui.screen.notification.NotificationGoodNewsSection
import com.zaijian.zhoumuyun.ui.theme.AppBrushes
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.WcAlpha
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.NotificationViewModel

// ─────────────────────────────────────────────────────────────
//  NotificationScreen — 通知中心
//  通知中心设计方案 全文。
//
//  P0修复（E2 批次1）：
//  1. ON_RESUME 时自动刷新数据——此前 ViewModel 只在 init {} 里 load()
//     一次，用户标记已读后离开再回来，数据是旧的（已读状态没有刷新）。
//  2. "好消息"条目可点击跳转角色详情——此前只是纯文本，无法导航。
//  3. 顶栏增加"全部已读"按钮——批量标记"需要关注"条目为已读。
// ─────────────────────────────────────────────────────────────

@Composable
fun NotificationScreen(
    onBack: () -> Unit,
    // Tension/RelationWorsened → 角色详情页关系 Tab（characterId, tab=RELATION_TAB_INDEX）
    onNavigateToCharacterRelationTab: (characterId: Int) -> Unit,
    // NoContact/NeverContacted/Pregnancy → 直接进聊天
    onNavigateToChat: (characterId: Int) -> Unit,
    // GoodNews → 角色详情页（默认 Tab）
    onNavigateToCharacterDetail: (characterId: Int) -> Unit = {},
    viewModel: NotificationViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = ZaijianTheme.colors

    // P0修复1：每次 ON_RESUME 时刷新数据，避免回到通知中心看到的是旧快照。
    // ViewModel 由 viewModel() 绑定到 NavBackStackEntry，同一 entry 下
    // 不会重建，init {} 只执行一次。必须用生命周期观察者补上后续刷新。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            DetailTopBar(
                title    = "通知中心",
                onBack   = onBack,
                headerBg = colors.bgBase,
                actions = {
                    // P0修复3：全部已读按钮——仅有未读条目时显示
                    val hasUnread = uiState.attentionItems.any { it !in uiState.readItems }
                    if (hasUnread && !uiState.isLoading) {
                        SecondaryGoldButton(
                            text    = "全部已读",
                            onClick = { viewModel.markAllRead() },
                        )
                    }
                },
            )
        },
        containerColor = colors.bgBase,
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { Text(uiState.error!!, color = colors.textSecondary) }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .drawBehind {
                            drawRect(
                                brush = AppBrushes.watercolorWash(
                                    color = Palette.Gold,
                                    alpha = WcAlpha.page,
                                    center = Offset(size.width * 0.9f, 0f),
                                    radius = size.maxDimension * 0.8f,
                                ),
                            )
                        },
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.screenHorizontal),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
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
                            NotificationGoodNewsSection(
                                items = uiState.goodNewsItems,
                                onItemClick = { item ->
                                    routeGoodNewsItemClick(
                                        item                      = item,
                                        onNavigateToCharacterDetail = onNavigateToCharacterDetail,
                                    )
                                },
                            )
                        }
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
        // A6-1 修复: 排卵期/经期与 Pregnancy 同属角色级"关心状态"，点击跳聊天页
        // （进去看看角色本身）。这两类只持 characterId，无完整 CharacterConfig，
        // 直接用 item.characterId。
        is BriefingAttentionItem.FertileAttention -> onNavigateToChat(item.characterId)
        is BriefingAttentionItem.MenstrualAttention -> onNavigateToChat(item.characterId)
        is BriefingAttentionItem.Tension -> {
            item.fromId.toIntOrNull()?.let(onNavigateToCharacterRelationTab)
        }
        is BriefingAttentionItem.RelationWorsened -> {
            item.fromId.toIntOrNull()?.let(onNavigateToCharacterRelationTab)
        }
        // 叙事类：点击进该角色聊天
        is BriefingAttentionItem.QuoteReference -> onNavigateToChat(item.character.id)
        is BriefingAttentionItem.AgreementDue -> onNavigateToChat(item.character.id)
    }
}

/**
 * 好消息点击跳转：统一跳转到角色详情页（默认 Tab）。
 * MilestoneRepaired → 角色详情页（看关系修复进展）
 * HighCompetitionScore → 角色详情页（看竞赛评分详情）
 */
private fun routeGoodNewsItemClick(
    item: GoodNewsItem,
    onNavigateToCharacterDetail: (Int) -> Unit,
) {
    val characterId = when (item) {
        is GoodNewsItem.MilestoneRepaired -> item.entry.character.id
        is GoodNewsItem.HighCompetitionScore -> item.entry.character.id
    }
    onNavigateToCharacterDetail(characterId)
}
