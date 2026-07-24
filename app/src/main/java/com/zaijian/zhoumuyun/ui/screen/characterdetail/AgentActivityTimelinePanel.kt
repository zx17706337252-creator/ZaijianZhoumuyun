package com.zaijian.zhoumuyun.ui.screen.characterdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaijian.zhoumuyun.ui.component.ContentBlockRenderer
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.AgentActivityViewModel

// ═══════════════════════════════════════════════════════════════
//  AgentActivityTimelinePanel（窗口7贯通 · 心迹面板 UI）
//
//  挂载位置：CharacterDetailScreen abilityTab==3（"心迹"子Tab）
//  数据来源：AgentActivityRepository.observeTimeline()
//
//  贯通链路：
//    observeTimeline() → ContentBlockAdapter.fromTimelineItems()
//      → ContentBlockRenderer 渲染（含 ToolCall/WorkflowStep/SkillActivity 等
//        Agent 过程类块，窗口7定稿的5个结构化渲染器在此被真正调用）
//
//  这是窗口7的"贯通性入口"——之前 ContentBlockAdapter 和5个 Agent 过程类
//  渲染器虽然已实现但无任何调用方（孤岛代码），本面板把它们接入真实数据流。
// ═══════════════════════════════════════════════════════════════

@Composable
internal fun AgentActivityTimelinePanel(
    characterId: Int,
    accentColor: Color,
    agentActivityViewModel: AgentActivityViewModel,
) {
    val uiState by agentActivityViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(characterId) {
        agentActivityViewModel.init(characterId)
    }

    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    when {
        uiState.isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = accentColor)
            }
        }

        uiState.error != null -> {
            Text(
                text = uiState.error!!,
                style = type.body,
                color = Palette.SemanticError,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp, horizontal = Spacing.md),
            )
        }

        uiState.blocks.isEmpty() -> {
            Text(
                text = "暂无心迹记录",
                style = type.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
            )
        }

        else -> {
            // 贯通核心：ContentBlockRenderer 在此接收经 ContentBlockAdapter
            // 转换的 Agent 过程类块（ToolCall/Thinking/MemoryUpdate/
            // WorkflowStep/SkillActivity），5个结构化渲染器被真正触发。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ContentBlockRenderer(
                    blocks = uiState.blocks,
                    textColor = colors.textPrimary,
                    style = type.body,
                )
            }
        }
    }
}
