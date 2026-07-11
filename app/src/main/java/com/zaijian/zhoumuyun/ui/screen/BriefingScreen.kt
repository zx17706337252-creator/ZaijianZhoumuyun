package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.ui.screen.briefing.BriefingAttentionSection
import com.zaijian.zhoumuyun.ui.screen.briefing.BriefingCharacterCard
import com.zaijian.zhoumuyun.ui.screen.briefing.BriefingIntroSection
import com.zaijian.zhoumuyun.ui.screen.briefing.BriefingRankingSection
import com.zaijian.zhoumuyun.ui.theme.Palette
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
    val uiState by viewModel.uiState.collectAsState()
    val colors = ZaijianTheme.colors

    if (uiState.isLoading || uiState.data == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.primary)
        }
        return
    }
    val data = uiState.data!!

    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Velvet),
            ) {
                Text("推门进入公馆")
            }
        }
    }
}
