package com.zaijian.zhoumuyun.ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color


import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

import com.zaijian.zhoumuyun.ui.viewmodel.KnowledgeInjectMode
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.DangerVelvetButton
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.WorldCard



// ─────────────────────────────────────────────────────────────
//  ChatSettingsSheet — 聊天设置底部面板（Phase 16）
//  拆分自 ChatScreen.kt（v87 Phase 2）。独立组件，含内部清空确认Dialog。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  ChatSettingsSheet — 聊天设置底部面板（Phase 16）
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSettingsSheet(
    characterName: String,
    accentColor: Color,
    onNavigateToDetail: () -> Unit,
    onDismiss: () -> Unit,
    knowledgeMode: KnowledgeInjectMode = KnowledgeInjectMode.AUTO,
    onKnowledgeModeChange: (KnowledgeInjectMode) -> Unit = {},
    onManualKnowledgeTrigger: () -> Unit = {},
    onClearMessages: () -> Unit = {},
    activeProjects: List<ProjectEntity> = emptyList(),
    currentProjectId: String? = null,
    onSetProject: (String?) -> Unit = {},
    hasCustomBackground: Boolean = false,
    onSetBackground: () -> Unit = {},
    onClearBackground: () -> Unit = {},
    // 2.4：导出本次对话（Agent附件下发方案 v2.0 P2）。走 1.1 打通的
    // exportedFileJson 回填链路，落地为一份可下载文件。默认空实现向后兼容。
    onExportConversation: () -> Unit = {},
    // 批次4新增：跳转到该角色的个人日程页（PersonalScheduleScreen）。
    // 默认空实现保持向后兼容——ChatScreen 一定会透传真实回调，默认值只是让
    // 其他潜在调用点（如预览）不必关心此参数。
    onNavigateToSchedule: () -> Unit = {},
    // v147（文件保险库改造）：跳转到文件库（FileVaultScreen），展示该角色的
    // 私库 + 参与的圆桌共享 + 项目共享。与"日程"条目同款 Row+IconBadge+Column
    // 视觉范式。vaultFileCount 用于副标题角标（为 0 时显示引导文案）。
    onNavigateToVault: () -> Unit = {},
    vaultFileCount: Int = 0,
    // 角色间私聊入口：跳转到私聊管理面板（PrivateChatScreen，全局配对列表，
    // 不带 characterId 参数——与 onNavigateToVault/onNavigateToSchedule 不同，
    // 该面板本身管理全部角色对，不是单一角色专属页面）。默认空实现向后兼容。
    onNavigateToPrivateChat: () -> Unit = {},
    // 文档发送方式：默认一起发（true），底部面板内可切换为"分开发"（false）。
    attachFilesTogether: Boolean = true,
    onAttachFilesTogetherChange: (Boolean) -> Unit = {},
) {
    val colors     = ZaijianTheme.colors
    val type       = ZaijianTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest  = onDismiss,
        sheetState        = sheetState,
        containerColor    = colors.bgCard,
        dragHandle        = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.border),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.xl),
        ) {
            // 标题
            Text(
                text     = characterName,
                style    = type.cardTitle,
                color    = colors.textPrimary,
                modifier = Modifier.padding(
                    horizontal = Spacing.screenHorizontal,
                    vertical   = Spacing.md,
                ),
            )

            // ════════════════════════════════════════════════
            //  Zone 1：导航区 — 角色相关页面跳转
            // ════════════════════════════════════════════════
            SectionLabel("角色")
            WorldCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
                cornerRadius = Radius.sm,
            ) {
                Column {
                    SettingsNavRow(
                        icon = AppIcons.AccountCircle,
                        title = "查看角色档案",
                        subtitle = "记忆 · 人设 · 目标 · 关系",
                        onClick = { onNavigateToDetail(); onDismiss() },
                    )
                    SettingsNavRow(
                        icon = AppIcons.Event,
                        title = "日程",
                        subtitle = "定时任务 · 工单提醒",
                        onClick = { onNavigateToSchedule(); onDismiss() },
                    )
                    SettingsNavRow(
                        icon = AppIcons.FolderOpen,
                        title = "文件",
                        subtitle = if (vaultFileCount > 0) "$vaultFileCount 个文件 · 预览/下载/编辑"
                                   else "角色生成的文件在此管理",
                        onClick = { onNavigateToVault(); onDismiss() },
                    )
                    SettingsNavRow(
                        icon = AppIcons.PrivateChat,
                        title = "角色私聊",
                        subtitle = "让角色之间私下聊天",
                        onClick = { onNavigateToPrivateChat(); onDismiss() },
                    )
                }
            }

            // ════════════════════════════════════════════════
            //  Zone 2：聊天设置区
            // ════════════════════════════════════════════════
            SectionLabel("聊天设置")
            WorldCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
                cornerRadius = Radius.sm,
            ) {
                Column {
                    // 聊天背景图
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetBackground() }
                            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        com.zaijian.zhoumuyun.ui.design.IconBadge(
                            icon               = AppIcons.Wallpaper,
                            contentDescription = null,
                            tint               = colors.accentDeep,
                            background         = colors.accent.copy(alpha = 0.12f),
                            size               = 20.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "更换聊天背景", style = type.body, color = colors.textPrimary)
                            Text(
                                text  = if (hasCustomBackground) "已设置自定义背景 · 点击更换" else "从相册选择背景图片",
                                style = type.caption,
                                color = colors.textSecondary,
                            )
                        }
                        if (hasCustomBackground) {
                            GhostGoldButton(text = "恢复默认", onClick = onClearBackground)
                        }
                    }

                    // 导出本次对话
                    SettingsNavRow(
                        icon = AppIcons.Download,
                        title = "导出本次对话",
                        subtitle = "生成文本文件，可在「文件」中下载分享",
                        onClick = { onExportConversation(); onDismiss() },
                    )

                    // 文档发送方式
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(text = "文档发送方式", style = type.body, color = colors.textPrimary)
                        Text(
                            text  = if (attachFilesTogether) "一起发 — 文件随文字合并成一个气泡"
                                    else "分开发 — 文件单独成一张卡片",
                            style = type.caption,
                            color = colors.textSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            listOf(true to "一起发", false to "分开发").forEach { (together, label) ->
                                val selected = attachFilesTogether == together
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) colors.accent
                                            else colors.surface.copy(alpha = GlassOpacity.low)
                                        )
                                        .clickable { onAttachFilesTogetherChange(together) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text  = label,
                                        style = type.label,
                                        color = if (selected) Palette.White else colors.textSecondary,
                                    )
                                }
                            }
                        }
                    }

                    // 关联项目选择器
                    if (activeProjects.isNotEmpty()) {
                        var projectDropdown by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { projectDropdown = true }
                                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            com.zaijian.zhoumuyun.ui.design.IconBadge(
                                icon               = AppIcons.FolderOpen,
                                contentDescription = null,
                                tint               = colors.accentDeep,
                                background         = colors.accent.copy(alpha = 0.12f),
                                size               = 20.dp,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "关联项目", style = type.body, color = colors.textPrimary)
                                Text(
                                    text  = activeProjects.firstOrNull { it.id == currentProjectId }?.title
                                                ?: "未关联",
                                    style = type.caption,
                                    color = colors.textSecondary,
                                )
                            }
                            Icon(
                                imageVector        = AppIcons.KeyboardArrowDown,
                                contentDescription = null,
                                tint               = colors.textDisabled,
                                modifier           = Modifier.size(18.dp),
                            )
                            DropdownMenu(
                                expanded         = projectDropdown,
                                onDismissRequest = { projectDropdown = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text  = "不关联",
                                            style = type.body,
                                            color = if (currentProjectId == null) colors.accent else colors.textPrimary,
                                        )
                                    },
                                    trailingIcon = if (currentProjectId == null) ({
                                        Icon(
                                            imageVector = AppIcons.Check,
                                            contentDescription = null,
                                            tint = colors.accent,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }) else null,
                                    onClick = {
                                        onSetProject(null)
                                        projectDropdown = false
                                    },
                                )
                                androidx.compose.material3.HorizontalDivider(color = colors.border)
                                activeProjects.forEach { project ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text  = project.title,
                                                style = type.body,
                                                color = if (project.id == currentProjectId) colors.accent
                                                        else colors.textPrimary,
                                            )
                                        },
                                        trailingIcon = if (project.id == currentProjectId) ({
                                            Icon(
                                                imageVector = AppIcons.Check,
                                                contentDescription = null,
                                                tint = colors.accent,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }) else null,
                                        onClick = {
                                            onSetProject(project.id)
                                            projectDropdown = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // 项目知识库
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(text = "项目知识库", style = type.body, color = colors.textPrimary)
                        val modeLabel = when (knowledgeMode) {
                            KnowledgeInjectMode.AUTO -> "自动 — 检测到关键词时注入"
                            KnowledgeInjectMode.MANUAL -> "手动"
                        }
                        Text(text = modeLabel, style = type.caption, color = colors.textSecondary)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            listOf(
                                KnowledgeInjectMode.AUTO to "自动",
                                KnowledgeInjectMode.MANUAL to "手动",
                            ).forEach { (mode, label) ->
                                val selected = knowledgeMode == mode
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) colors.accent
                                            else colors.surface.copy(alpha = GlassOpacity.low)
                                        )
                                        .clickable { onKnowledgeModeChange(mode) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text  = label,
                                        style = type.label,
                                        color = if (selected) Palette.White else colors.textSecondary,
                                    )
                                }
                            }
                        }
                        if (knowledgeMode == KnowledgeInjectMode.MANUAL) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.accent.copy(alpha = 0.15f))
                                    .clickable { onManualKnowledgeTrigger() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text  = "注入知识库（下一条消息生效）",
                                    style = type.label,
                                    color = colors.accentDeep,
                                )
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════
            //  Zone 3：危险操作区
            // ════════════════════════════════════════════════
            SectionLabel("管理")
            var showClearConfirm by remember { mutableStateOf(false) }
            WorldCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
                cornerRadius = Radius.sm,
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showClearConfirm = true }
                            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        com.zaijian.zhoumuyun.ui.design.IconBadge(
                            icon               = AppIcons.DeleteSweep,
                            contentDescription = null,
                            tint               = Palette.SemanticDanger,
                            background         = Palette.SemanticDanger.copy(alpha = 0.10f),
                            size               = 20.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "清空对话记录", style = type.body, color = colors.textPrimary)
                            Text(text = "不影响长期记忆与关系", style = type.caption, color = colors.textSecondary)
                        }
                    }
                }
            }
            if (showClearConfirm) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    containerColor   = colors.bgCard,
                    title = {
                        Text("清空对话记录？", style = type.cardTitle, color = colors.textPrimary)
                    },
                    text = {
                        Text(
                            "当前对话记录将全部删除，长期记忆与关系数据不受影响。",
                            style = type.body,
                            color = colors.textSecondary,
                        )
                    },
                    confirmButton = {
                        DangerVelvetButton(
                            text = "确认清空",
                            onClick = {
                                onClearMessages()
                                showClearConfirm = false
                                onDismiss()
                            },
                        )
                    },
                    dismissButton = {
                        GhostGoldButton(
                            text = "取消",
                            onClick = { showClearConfirm = false },
                        )
                    },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  UI v2.0 三区结构辅助组件
// ─────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    Text(
        text      = text,
        style     = type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        color     = colors.textSecondary,
        modifier  = Modifier.padding(
            horizontal = Spacing.screenHorizontal,
            vertical   = Spacing.sm,
        ),
    )
}

@Composable
private fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        com.zaijian.zhoumuyun.ui.design.IconBadge(
            icon               = icon,
            contentDescription = null,
            tint               = colors.accentDeep,
            background         = colors.accent.copy(alpha = 0.12f),
            size               = 20.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = type.body, color = colors.textPrimary)
            Text(text = subtitle, style = type.caption, color = colors.textSecondary)
        }
    }
}
