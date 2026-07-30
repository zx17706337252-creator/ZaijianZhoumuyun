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
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

import com.zaijian.zhoumuyun.ui.viewmodel.KnowledgeInjectMode
import com.zaijian.zhoumuyun.ui.design.AppIcons



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

            HorizontalDivider(color = colors.border)

            // 条目：角色档案
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onNavigateToDetail()
                        onDismiss()
                    }
                    .padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.md,
                    ),
                verticalAlignment          = Alignment.CenterVertically,
                horizontalArrangement      = Arrangement.spacedBy(Spacing.md),
            ) {
                com.zaijian.zhoumuyun.ui.design.IconBadge(
                    icon               = AppIcons.AccountCircle,
                    contentDescription = null,
                    tint               = accentColor,
                    background         = accentColor.copy(alpha = 0.12f),
                    size               = 20.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "查看角色档案", style = type.body, color = colors.textPrimary)
                    Text(text = "记忆 · 人设 · 目标 · 关系", style = type.caption, color = colors.textSecondary)
                }
            }

            HorizontalDivider(color = colors.border)

            // 批次4新增条目：日程
            // 照抄上方"角色档案"条目的写法（Row + IconBadge + Column 主副标题），
            // 点击触发 onNavigateToSchedule 并关闭 Sheet——与"角色档案"条目
            // onNavigateToDetail() + onDismiss() 的范式严格一致。
            // 副标题"定时任务 · 工单提醒"同时覆盖工具型（定时任务）和工单型（工单提醒）
            // 两种模式，让用户一眼知道这里能管理两类日程。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onNavigateToSchedule()
                        onDismiss()
                    }
                    .padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.md,
                    ),
                verticalAlignment          = Alignment.CenterVertically,
                horizontalArrangement      = Arrangement.spacedBy(Spacing.md),
            ) {
                com.zaijian.zhoumuyun.ui.design.IconBadge(
                    icon               = AppIcons.Event,
                    contentDescription = null,
                    tint               = accentColor,
                    background         = accentColor.copy(alpha = 0.12f),
                    size               = 20.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "日程", style = type.body, color = colors.textPrimary)
                    Text(text = "定时任务 · 工单提醒", style = type.caption, color = colors.textSecondary)
                }
            }

            HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = Spacing.screenHorizontal))

            // v147（文件保险库改造）：文件条目，紧邻"日程"。
            // 视觉范式与"日程"/"角色档案"严格一致：Row + IconBadge + Column(主副标题)。
            // 点击触发 onNavigateToVault 并关闭 Sheet——与"日程"条目 onNavigateToSchedule()
            // + onDismiss() 的范式严格一致。副标题根据 vaultFileCount 显示数量或引导文案。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onNavigateToVault()
                        onDismiss()
                    }
                    .padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.md,
                    ),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                com.zaijian.zhoumuyun.ui.design.IconBadge(
                    icon               = AppIcons.FolderOpen,
                    contentDescription = null,
                    tint               = accentColor,
                    background         = accentColor.copy(alpha = 0.12f),
                    size               = 20.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "文件", style = type.body, color = colors.textPrimary)
                    Text(
                        text  = if (vaultFileCount > 0) "$vaultFileCount 个文件 · 预览/下载/编辑"
                                else "角色生成的文件在此管理",
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                }
            }

            HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = Spacing.screenHorizontal))

            // 角色间私聊入口：视觉范式与"文件"/"日程"/"角色档案"严格一致。
            // 点击触发 onNavigateToPrivateChat 并关闭 Sheet。这里不带 characterId——
            // PrivateChatScreen 本身是全局配对管理面板（列出全部角色对），
            // 不是当前角色专属页面，用户进去后自己选谁跟谁配对。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onNavigateToPrivateChat()
                        onDismiss()
                    }
                    .padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.md,
                    ),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                com.zaijian.zhoumuyun.ui.design.IconBadge(
                    icon               = AppIcons.PrivateChat,
                    contentDescription = null,
                    tint               = accentColor,
                    background         = accentColor.copy(alpha = 0.12f),
                    size               = 20.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "角色私聊", style = type.body, color = colors.textPrimary)
                    Text(text = "让角色之间私下聊天", style = type.caption, color = colors.textSecondary)
                }
            }

            HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = Spacing.screenHorizontal))

            // 条目：聊天背景图
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSetBackground() }
                    .padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.md,
                    ),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                com.zaijian.zhoumuyun.ui.design.IconBadge(
                    icon               = AppIcons.Wallpaper,
                    contentDescription = null,
                    tint               = accentColor,
                    background         = accentColor.copy(alpha = 0.12f),
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
                    androidx.compose.material3.TextButton(onClick = onClearBackground) {
                        Text("恢复默认", style = type.caption, color = accentColor)
                    }
                }
            }

            HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = Spacing.screenHorizontal))

            // 条目：导出本次对话（2.4）
            // 照抄"角色档案"/"日程"条目的 Row + IconBadge + Column 范式，
            // 点击触发 onExportConversation 并关闭 Sheet——不需要确认弹窗
            // （不是破坏性操作，导出失败会走 ChatScreen 已有的 snackbar 反馈）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onExportConversation()
                        onDismiss()
                    }
                    .padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.md,
                    ),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                com.zaijian.zhoumuyun.ui.design.IconBadge(
                    icon               = AppIcons.Download,
                    contentDescription = null,
                    tint               = accentColor,
                    background         = accentColor.copy(alpha = 0.12f),
                    size               = 20.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "导出本次对话", style = type.body, color = colors.textPrimary)
                    Text(text = "生成文本文件，可在「文件」中下载分享", style = type.caption, color = colors.textSecondary)
                }
            }

            HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = Spacing.screenHorizontal))

            // 条目：文档发送方式——角色用工具产出文件时，文件卡片是跟文字合并
            // 进同一个气泡（默认），还是像旧版一样各自独立成一张气泡/卡片。
            // 布局照抄下方"项目知识库"两选项横排范式，保持视觉一致。
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
                                    if (selected) accentColor
                                    else colors.surface.copy(alpha = GlassOpacity.low)
                                )
                                .clickable { onAttachFilesTogetherChange(together) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text  = label,
                                style = type.label,
                                color = if (selected) Color.White else colors.textSecondary,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = Spacing.screenHorizontal))

            // 条目：清空对话（含确认 Dialog）
            var showClearConfirm by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showClearConfirm = true }
                    .padding(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.md,
                    ),
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
                        androidx.compose.material3.TextButton(
                            onClick = {
                                onClearMessages()
                                showClearConfirm = false
                                onDismiss()
                            }
                        ) { Text("确认清空", color = Palette.SemanticDanger) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { showClearConfirm = false }
                        ) { Text("取消", color = colors.textSecondary) }
                    },
                )
            }

            HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = Spacing.screenHorizontal))

            // ── 关联项目选择器 ────────────────────────────────
            if (activeProjects.isNotEmpty()) {
                var projectDropdown by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { projectDropdown = true }
                        .padding(
                            horizontal = Spacing.screenHorizontal,
                            vertical   = Spacing.md,
                        ),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    com.zaijian.zhoumuyun.ui.design.IconBadge(
                        icon               = AppIcons.FolderOpen,
                        contentDescription = null,
                        tint               = accentColor,
                        background         = accentColor.copy(alpha = 0.12f),
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
                    // P3-20 修复：移除 DropdownMenu 的 Modifier.background() 双层叠加，
                    // DropdownMenu 自身已有 Surface 背景，额外 background 导致视觉重叠。
                    DropdownMenu(
                        expanded         = projectDropdown,
                        onDismissRequest = { projectDropdown = false },
                    ) {
                        // "不关联"选项
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
                HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = Spacing.screenHorizontal))
            }

            // ── Phase 31：知识库注入模式 ──────────────────────
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
                    // 两个选项横排
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
                                        if (selected) accentColor
                                        else colors.surface.copy(alpha = GlassOpacity.low)
                                    )
                                    .clickable { onKnowledgeModeChange(mode) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text  = label,
                                    style = type.label,
                                    color = if (selected) Color.White else colors.textSecondary,
                                )
                            }
                        }
                    }
                    // MANUAL 模式：显示"注入知识库"一次性触发按钮
                    if (knowledgeMode == KnowledgeInjectMode.MANUAL) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(accentColor.copy(alpha = 0.15f))
                                .clickable { onManualKnowledgeTrigger() }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text  = "注入知识库（下一条消息生效）",
                                style = type.label,
                                color = accentColor,
                            )
                        }
                    }
                }
            }
        }
    }
