package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
// P3-35 修复：升级为 lifecycle-aware 版本
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
//  loading 态：4.10.3 示例代码写的是「直接 return」，会在加载期间渲染
//  一片空白；改为复用 ProjectScreen 等现有 Screen 的 loading 惯例
//  （居中 CircularProgressIndicator），符合方案 4.10.3 注释里"复用现有
//  loading Box 写法"的意图。
// ─────────────────────────────────────────────────────────────

@Composable
fun BriefingScreen(
    onEnterWorld: () -> Unit,
    viewModel: BriefingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = ZaijianTheme.colors

    if (uiState.isLoading || uiState.data == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.primary)
        }
        return
    }
    val data = uiState.data!!

    // P3-36 修复：底部安全区缺失，添加 navigationBarsPadding
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item { BriefingIntroSection(periodStart = data.periodStart, periodEnd = data.periodEnd) }

        if (data.attentionItems.isNotEmpty()) {
            item {
                BriefingAttentionSection(
                    items = data.attentionItems,
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
