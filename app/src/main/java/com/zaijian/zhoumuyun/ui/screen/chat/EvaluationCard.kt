package com.zaijian.zhoumuyun.ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.zaijian.zhoumuyun.ui.design.WorldCard
import androidx.compose.ui.unit.dp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.ripple
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color


import com.zaijian.zhoumuyun.domain.ContentBlockParser
import com.zaijian.zhoumuyun.ui.component.ContentBlockRenderer
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

import androidx.compose.runtime.mutableIntStateOf



// ─────────────────────────────────────────────────────────────
//  EvaluationCard — Agent B 汇报 + 用户打分卡片（Phase 24）
//  拆分自 ChatScreen.kt（v87 Phase 2）。
//  说明：审计报告的四文件方案未单列此组件；逐行核对后发现它既不属于
//  消息气泡簇，也不属于顶栏/输入栏/设置面板，是独立特性，故单独成文件。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  EvaluationCard — Agent B 汇报 + 用户打分卡片（Phase 24）
//
//  布局：
//    ┌─────────────────────────────────────────────┐
//    │  📊 Agent B 评审汇报（Markdown 渲染）        │
//    │                                              │
//    │  你的评分：  ☆ ☆ ☆ ☆ ☆                    │
//    │  [跳过]                        [提交打分]    │
//    └─────────────────────────────────────────────┘
//
//  用户选星后「提交打分」按钮变为 accentColor 激活状态。
//  「跳过」调用 skipEvaluation()，卡片消失，不记录分数。
// ─────────────────────────────────────────────────────────────

@Composable
internal fun EvaluationCard(
    reportText:  String,
    agentScore:  Float?,
    accentColor: Color,
    onSubmit:    (Int) -> Unit,
    onSkip:      () -> Unit,
    modifier:    Modifier = Modifier,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    var selectedStars by remember { mutableIntStateOf(0) }

    AnimatedVisibility(
        visible = true,
        enter   = fadeIn(tween(AnimDuration.pageSwitch)) +
                  slideInVertically(tween(AnimDuration.pageSwitch)) { it / 2 },
        exit    = fadeOut(tween(AnimDuration.fast)),
        modifier = modifier.fillMaxWidth(),
    ) {
        // WorldCard 接入（精修方案 v1.3）：单角色评审汇报卡，整卡内容均归属
        // 当前对话角色，L3 身份脊用该角色 accentColor。
        WorldCard(
            modifier = Modifier.fillMaxWidth(),
            ownerAccent = accentColor,
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // ── 标题行 ──────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text  = "📊",
                    style = type.body,
                )
                Text(
                    text  = "本次对话评审",
                    style = type.cardTitle,
                    color = accentColor,
                )
                if (agentScore != null) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(accentColor.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text  = "AI ${"%.1f".format(agentScore)}",
                            style = type.label,
                            color = accentColor,
                        )
                    }
                }
            }

            // ── Agent B 评审汇报文本 ──────────────────────
            // E2 统一内容渲染接入：评审汇报走 ContentBlockParser → ContentBlockRenderer，
            // AI 生成的结构化文本（标题/列表/引用等）获得块级渲染。
            val reportBlocks = remember(reportText) { ContentBlockParser.parse(reportText) }
            ContentBlockRenderer(
                blocks    = reportBlocks,
                textColor = colors.textSecondary,
                style     = type.caption,
            )

            // ── 分隔线 ────────────────────────────────────
            HorizontalDivider(
                color     = accentColor.copy(alpha = 0.15f),
                thickness = 0.5.dp,
            )

            // ── 用户打星区 ────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text  = "你的评分",
                    style = type.label,
                    color = colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { star ->
                        val filled = star <= selectedStars
                        Text(
                            text     = if (filled) "⭐" else "☆",
                            style    = type.body.copy(
                                fontSize = androidx.compose.ui.unit.TextUnit(
                                    22f, androidx.compose.ui.unit.TextUnitType.Sp
                                )
                            ),
                            color    = if (filled) accentColor else colors.textDisabled,
                            // P3-24 修复：为星级评分添加 ripple 点击反馈
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),  // 代码清洁：rememberRipple → ripple()
                            ) { selectedStars = star },
                        )
                    }
                }
            }

            // ── 操作按钮行 ────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // 跳过
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable(onClick = onSkip)
                        .padding(horizontal = Spacing.md, vertical = 6.dp),
                ) {
                    Text(
                        text  = "跳过",
                        style = type.label,
                        color = colors.textDisabled,
                    )
                }

                // 提交打分
                val canSubmit = selectedStars > 0
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(
                            if (canSubmit) accentColor
                            else colors.textDisabled.copy(alpha = 0.3f)
                        )
                        .clickable(enabled = canSubmit) { onSubmit(selectedStars) }
                        .padding(horizontal = Spacing.md, vertical = 6.dp),
                ) {
                    Text(
                        text  = "提交打分",
                        style = type.label,
                        color = Color.White,
                    )
                }
            }
        }
        }
    }
}
