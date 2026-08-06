package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.GoldPrimaryButton
import androidx.compose.runtime.Composable
// P3-35 修复：升级为 lifecycle-aware 版本
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.ui.component.RootTabTopBar
import com.zaijian.zhoumuyun.ui.screen.briefing.BriefingAttentionSection
import com.zaijian.zhoumuyun.ui.screen.briefing.BriefingCharacterCard
import com.zaijian.zhoumuyun.ui.screen.briefing.BriefingIntroSection
import com.zaijian.zhoumuyun.ui.screen.briefing.BriefingRankingSection
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.BriefingViewModel

// ─────────────────────────────────────────────────────────────
//  BriefingScreen —— 离线简报开场页，主入口
//  整合方案 v2.1 4.10.3 节。
//
//  W14 修复：原 loading 条件 `isLoading || data == null` 在加载失败
//  （error 非空、data 为 null、isLoading 为 false）时导致用户永久卡在
//  loading 转圈界面。修复为三个独立分支：
//    1. isLoading → loading 指示器
//    2. error 非空 → 错误提示 + 重试 + 跳过按钮
//    3. data 非空 → 正常简报内容
//
//  D-3 P2 修复：补页面级标题结构。原先仅给 LazyColumn 加了 statusBarsPadding()，
//  只解决"第一项不贴状态栏"，但整页仍无标题识别——BriefingIntroSection 会随列表
//  滚走，不是持久标题栏。现统一接入 RootTabTopBar（简报是无返回箭头的开场落地页，
//  用根 Tab 顶栏语义而非 DetailTopBar），statusBarsPadding 由顶栏自身承担，
//  三分支（loading/error/content）恒定可见同一标题栏，页面级标题识别成立。
// ─────────────────────────────────────────────────────────────

@Composable
fun BriefingScreen(
    onEnterWorld: () -> Unit,
    viewModel: BriefingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    Column(modifier = Modifier.fillMaxSize()) {
        // D-3 P2：页面级标题栏，恒定可见（statusBarsPadding 由 RootTabTopBar 承担）
        RootTabTopBar(
            title    = "简报",
            headerBg = colors.bgBase,
        )

        when {
            // ── 分支 1：加载中 ───────────────────────────────────────
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.primary)
                }
            }

            // ── 分支 2：加载失败（W14 修复：原逻辑合并到 isLoading 分支导致永久卡死）──
            uiState.error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.screenHorizontal),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = uiState.error!!,
                        color = colors.textSecondary,
                        style = type.body, // 14sp 恰好等于 body，改用排印系统接入
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    GoldPrimaryButton(
                        text = "重新加载",
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    GhostGoldButton(
                        text = "跳过，直接进入公馆",
                        onClick = onEnterWorld,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── 分支 3：data 仍为 null 但没有 isLoading 也没有 error ──
            //  理论上不会到达此分支（init 中要么成功设 data，要么失败设 error），
            //  保留作为兜底防护。
            uiState.data == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.primary)
                }
            }

            // ── 分支 4：正常简报内容 ───────────────────────────────
            else -> {
                val data = uiState.data!!
                // P3-36 修复：底部安全区缺失，添加 navigationBarsPadding。
                // 顶部 statusBarsPadding 已由 RootTabTopBar 承担，此处不再重复。
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.lg),
                ) {
                    item {
                        BriefingIntroSection(
                            periodStart = data.periodStart,
                            periodEnd = data.periodEnd,
                            modifier = Modifier.padding(
                                horizontal = Spacing.screenHorizontal,
                                vertical = Spacing.sm,
                            ).padding(top = Spacing.sm),
                        )
                    }

                    if (data.attentionItems.isNotEmpty()) {
                        item {
                            BriefingAttentionSection(
                                items = data.attentionItems,
                                daughterNameMap = uiState.daughterNameMap,
                                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
                            )
                        }
                    }

                    items(data.characters, key = { it.character.id }) { entry ->
                        BriefingCharacterCard(
                            entry = entry,
                            modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
                        )
                    }

                    item {
                        BriefingRankingSection(
                            ranking = data.affectionRanking,
                            modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
                        )
                    }

                    item {
                        GoldPrimaryButton(
                            text = "推门进入公馆",
                            onClick = onEnterWorld,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                        )
                    }
                }
            }
        }
    }
}
