package com.zaijian.zhoumuyun.ui.screen.briefing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zaijian.zhoumuyun.data.model.BriefingCharacterEntry
import com.zaijian.zhoumuyun.data.model.CyclePhase
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.design.BondRibbon
import com.zaijian.zhoumuyun.ui.design.BondStage
import com.zaijian.zhoumuyun.ui.design.InfoChip
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  BriefingCharacterCard —— 离线简报单个角色卡片
//  整合方案 v2.1 4.10.3 节，本轮按《离线简报 UI 改版交接文档》重做（不是原版
//  4.8 节那版）。相对更早版本的关键变化：
//
//  · 布局从纯竖直堆叠改为「头像 + 右侧信息列」横向布局，卡片 fillMaxWidth()，
//    不再挤成窄竖条。
//  · 不使用 mood/energy（心情蜡烛）：BriefingRepository 走离线批量生成场景，
//    不会传入真实 CharacterStateLayer，mood 会退化成按"当前几点钟 + 目标进度"
//    规则瞎猜的伪信息，已被用户明确否决，不再提议。
//  · 改用 entry.daysSinceContact（真实字段）展示"距上次联系天数"，
//    days == 0 显示"今天联系过"；>= 7 天（与 BriefingRepository.
//    buildAttentionList() 的 noContactThresholdDays 阈值保持一致）用
//    Palette.SemanticReminder 提醒色标出。
//  · WorldCard 开启 accentWash = true，视觉参照 briefing_preview_v3.jsx。
// ─────────────────────────────────────────────────────────────

@Composable
fun BriefingCharacterCard(entry: BriefingCharacterEntry, modifier: Modifier = Modifier) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val accent = entry.character.accentColor

    WorldCard(
        modifier    = modifier.fillMaxWidth(),
        ownerAccent = accent,
        isMilestone = entry.hasRecentGoodMilestone,
        accentWash  = true,
    ) {
        Column(Modifier.padding(Spacing.cardPadding)) {
            // 头像 + 右侧信息列（横向布局）
            Row(verticalAlignment = Alignment.Top) {
                BreathingAvatar(
                    imageUrl        = entry.character.avatarUrl,
                    breathColor     = entry.character.breathColor,
                    statusType      = StatusType.OFFLINE,
                    enableBreath    = false,
                    size            = AvatarSize.shelf,
                    // 简报页头像是"过去 7 天"的静态历史汇总，不代表角色当前
                    // 是否在线，之前传 StatusType.OFFLINE 只是为了不显示状态环，
                    // 但 BreathingAvatar 内部 OFFLINE 会触发 50% 灰蓝遮罩
                    // （Palette.OfflineOverlay），把角色专属色（蒂法酒红、
                    // 露娜浅蓝等）全部拉灰拉暗——这是简报页头像看起来
                    // 寡淡无生气的直接原因。showStatusIndicator = false 会让
                    // BreathingAvatar 完全跳过状态环/状态点/离线灰遮罩三者
                    // （见 BreathingAvatar.kt 第 470/489 行判断），效果上和
                    // "不显示状态环"这个原始意图完全一致，但不再连带拉灰头像。
                    showStatusIndicator = false,
                )

                Spacer(Modifier.width(Spacing.sm))

                Column(Modifier.weight(1f)) {
                    // 名字行：名字 + 排卵期/怀孕 chip（右上角）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.character.name, style = type.cardTitle)

                        if (entry.isPregnant) {
                            InfoChip(text = "怀孕中", color = Palette.SemanticReminder)
                        } else if (entry.cyclePhase == CyclePhase.FERTILE) {
                            InfoChip(text = "排卵期", color = Palette.SemanticReminder)
                        } else if (entry.cyclePhase == CyclePhase.MENSTRUAL) {
                            // A6-3 修复: 补齐经期 chip，此前只覆盖怀孕和排卵期，
                            // cyclePhase 为 MENSTRUAL 时不显示任何标识，与状态点配色
                            // （经期红点）存在信息缺失。文案"经期"与 BriefingAttentionSection
                            // 及 MenstrualCycleState 注释口径一致。
                            InfoChip(text = "经期", color = Palette.SemanticReminder)
                        }
                    }

                    Spacer(Modifier.height(Spacing.xs))

                    // 关系刻度：完整五格 + 阶段文字（交接文档排版示意图 ▮▮▯▯▯ 陌生）
                    BondRibbon(
                        stage       = BondStage.entries.firstOrNull { it.name == entry.relation?.stage } ?: BondStage.STRANGER,
                        accentColor = accent,
                        showLabels  = true,
                        suppression = entry.relation?.suppression,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = colors.border.copy(alpha = 0.4f))
            Spacer(Modifier.height(Spacing.sm))

            // 距上次联系天数 + 任务数/评分，收进一行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val days = entry.daysSinceContact
                val contactText = when {
                    days == null -> "还没联系过"
                    days == 0L   -> "今天联系过"
                    else         -> "$days 天没联系了"
                }
                val contactColor = if (days != null && days >= 7L) {
                    Palette.SemanticReminder
                } else {
                    colors.textSecondary
                }
                Text(text = contactText, style = type.labelMono, color = contactColor)

                val scoreText = entry.competitionScore?.let { "%.1f".format(it) }
                val taskLine = if (scoreText != null) {
                    "任务 ${entry.completedTaskCount} · 评分 $scoreText"
                } else {
                    "任务 ${entry.completedTaskCount}"
                }
                Text(text = taskLine, style = type.labelMono, color = colors.textSecondary)
            }

            if (entry.projectNames.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "参与项目：${entry.projectNames.joinToString("、")}",
                    style = type.labelMono,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
