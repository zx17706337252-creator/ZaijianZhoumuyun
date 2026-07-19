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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
// P3-35 修复：升级为 lifecycle-aware 版本
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
// ─────────────────────────────────────────────────────────────

@Composable
fun BriefingScreen(
    onEnterWorld: () -> Unit,
    viewModel: BriefingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    // ── 分支 1：加载中 ────────────────────────────────────────
    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.primary)
        }
        return
    }

    // ── 分支 2：加载失败（W14 修复：原逻辑合并到 isLoading 分支导致永久卡死）──
    if (uiState.error != null) {
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
            Button(
                onClick = { viewModel.refresh() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            ) {
                Text("重新加载")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onEnterWorld,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("跳过，直接进入公馆")
            }
        }
        return
    }

    // ── 分支 3：data 仍为 null 但没有 isLoading 也没有 error ──
    //  理论上不会到达此分支（init 中要么成功设 data，要么失败设 error），
    //  保留作为兜底防护。
    if (uiState.data == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.primary)
        }
        return
    }
    val data = uiState.data!!

    // P3-36 修复：底部安全区缺失，添加 navigationBarsPadding
    // Fix-Briefing-StatusBar：顶部安全区同样缺失，标题（BriefingIntroSection）
    // 作为第一个 item 直接贴到屏幕最顶端，露在状态栏里。补上 statusBarsPadding。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        item { BriefingIntroSection(periodStart = data.periodStart, periodEnd = data.periodEnd) }

        if (data.attentionItems.isNotEmpty()) {
            item {
                BriefingAttentionSection(
                    items = data.attentionItems,
                    daughterNameMap = uiState.daughterNameMap,
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
                )
            }
        }

        items(data.characters, key = { it.character.id }) { entry ->
            BriefingCharacterCard(
                entry = entry,
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
            )
        }

        item {
            BriefingRankingSection(
                ranking = data.affectionRanking,
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
            )
        }

        item {
            Button(
                onClick = onEnterWorld,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.screenHorizontal),
                // P3-54 修复（重做）："推门进入公馆" 是主操作 CTA，语义上是强调/引导操作，
                // 不应使用 error（红色/危险语义）。改用 colors.accent 作为主强调色。
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            ) {
                Text("推门进入公馆")
            }
        }
    }
}