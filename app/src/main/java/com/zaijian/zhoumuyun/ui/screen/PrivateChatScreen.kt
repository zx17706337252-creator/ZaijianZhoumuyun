package com.zaijian.zhoumuyun.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatMessageEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatSessionEntity
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.PrivateChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ═══════════════════════════════════════════════════════════════
//  PrivateChatScreen — 角色间私聊管理面板（方案_角色间私聊_v2-5）
//
//  两个入口 Composable：
//    1. PrivateChatScreen      — 私聊管理面板（全局开关 / 新建角色对 / 配对列表）
//    2. PrivateChatDetailScreen — 单个角色对详情（发起 / 导出 / 消息记录 / 参数）
// ═══════════════════════════════════════════════════════════════

/**
 * 异步解析角色显示名。`resolveCharacterName` 是 suspend 函数，
 * 用 produceState 在协程中解析，初始展示占位名避免空窗。
 */
@Composable
fun rememberCharacterName(characterId: Int, viewModel: PrivateChatViewModel): String {
    return produceState(initialValue = "角色$characterId", characterId) {
        value = viewModel.resolveCharacterName(characterId)
    }.value
}

// ─────────────────────────────────────────────────────────────
//  1. 私聊管理面板
// ─────────────────────────────────────────────────────────────

@Composable
fun PrivateChatScreen(
    onBack: () -> Unit,
    onOpenPair: (String) -> Unit,
    viewModel: PrivateChatViewModel = viewModel(),
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val context = LocalContext.current

    val allPairs by viewModel.allPairs.collectAsStateWithLifecycle()
    val killSwitchOn by viewModel.killSwitchOn.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        containerColor = colors.bgBase,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DetailTopBar(
                title = "私聊管理",
                onBack = onBack,
                headerBg = ZaijianTheme.colors.surface,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = Spacing.screenHorizontal,
                    vertical = Spacing.md,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // ── 全局 kill switch ────────────────────────────────
                item {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "全局私聊开关",
                                    style = type.body,
                                    color = colors.textPrimary,
                                )
                                Text(
                                    text = "关闭后所有角色对都不会发起新的私聊",
                                    style = type.caption,
                                    color = colors.textSecondary,
                                )
                            }
                            Spacer(Modifier.width(Spacing.sm))
                            Switch(
                                checked = killSwitchOn,
                                onCheckedChange = { viewModel.toggleKillSwitch(it) },
                            )
                        }
                    }
                }

                // ── 新建角色对 ──────────────────────────────────────
                item {
                    Button(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.bgBase,
                        ),
                    ) {
                        Text("新建角色对")
                    }
                }

                // ── 配对列表 ────────────────────────────────────────
                if (allPairs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xl),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "还没有角色对，点击上方按钮新建",
                                style = type.body,
                                color = colors.textDisabled,
                            )
                        }
                    }
                } else {
                    items(allPairs, key = { it.pairId }) { pair ->
                        PairRow(
                            pair = pair,
                            viewModel = viewModel,
                            onClick = { onOpenPair(pair.pairId) },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePairDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { idA, idB ->
                showCreateDialog = false
                viewModel.createPair(idA, idB)
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  配对列表行
// ─────────────────────────────────────────────────────────────

@Composable
private fun PairRow(
    pair: PrivateChatPairEntity,
    viewModel: PrivateChatViewModel,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    val nameA = rememberCharacterName(pair.characterIdA, viewModel)
    val nameB = rememberCharacterName(pair.characterIdB, viewModel)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = colors.bgCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$nameA × $nameB",
                        style = type.cardTitle,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "今日已聊 ${pair.sessionsUsedToday}/${pair.maxSessionsPerDay} 次",
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Switch(
                    checked = pair.enabled,
                    onCheckedChange = { viewModel.toggleEnabled(pair.pairId, it) },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  新建角色对 Dialog
// ─────────────────────────────────────────────────────────────

@Composable
private fun CreatePairDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    var selectedA by remember { mutableStateOf<Int?>(null) }
    var selectedB by remember { mutableStateOf<Int?>(null) }

    val nameA = DefaultCharacters.find { it.id == selectedA }?.name ?: "未选择"
    val nameB = DefaultCharacters.find { it.id == selectedB }?.name ?: "未选择"
    val canCreate = selectedA != null && selectedB != null && selectedA != selectedB

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (colors.isDark) colors.bgElevated else colors.bgCard,
        tonalElevation = 0.dp,
        title = {
            Text("新建角色对", style = type.cardTitle, color = colors.textPrimary)
        },
        text = {
            Column {
                Text(
                    text = "角色 A：$nameA",
                    style = type.body,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "角色 B：$nameB",
                    style = type.body,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "选择角色：",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.xs))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    DefaultCharacters.forEach { ch ->
                        val isA = selectedA == ch.id
                        val isB = selectedB == ch.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.bgBase.copy(alpha = 0.4f))
                                .padding(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ch.name,
                                style = type.body,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    selectedA = ch.id
                                    if (selectedB == ch.id) selectedB = null
                                },
                                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
                            ) {
                                Text(
                                    text = "选为A",
                                    style = type.caption,
                                    color = if (isA) colors.accent else colors.textSecondary,
                                )
                            }
                            TextButton(
                                onClick = {
                                    selectedB = ch.id
                                    if (selectedA == ch.id) selectedA = null
                                },
                                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
                            ) {
                                Text(
                                    text = "选为B",
                                    style = type.caption,
                                    color = if (isB) colors.accent else colors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canCreate) onConfirm(selectedA!!, selectedB!!)
                },
                enabled = canCreate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.bgBase,
                ),
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        },
    )
}

// ═══════════════════════════════════════════════════════════════
//  2. 角色对详情页
// ═══════════════════════════════════════════════════════════════

@Composable
fun PrivateChatDetailScreen(
    pairId: String,
    onBack: () -> Unit,
    viewModel: PrivateChatViewModel = viewModel(),
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val allPairs by viewModel.allPairs.collectAsStateWithLifecycle()
    val messages by viewModel.selectedPairMessages.collectAsStateWithLifecycle()
    val sessions by viewModel.selectedPairSessions.collectAsStateWithLifecycle()
    val exportResult by viewModel.exportResult.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    // 当前配对（用于读取参数与发起私聊时的角色 id）
    val pair = allPairs.find { it.pairId == pairId }

    // 进入页面时加载该配对的消息 / 会话流
    LaunchedEffect(pairId) {
        viewModel.loadPairDetail(pairId)
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    var showTriggerDialog by remember { mutableStateOf(false) }

    // 参数编辑字段：随 pair 变化（加载完成 / 保存后）重新初始化
    var maxTurns by remember(pair) {
        mutableStateOf(pair?.maxTurnsPerSession?.toString() ?: "")
    }
    var maxSessions by remember(pair) {
        mutableStateOf(pair?.maxSessionsPerDay?.toString() ?: "")
    }
    var cooldown by remember(pair) {
        mutableStateOf(pair?.cooldownMinutes?.toString() ?: "")
    }

    val sessionMap = remember(sessions) { sessions.associateBy { it.sessionId } }
    val groupedMessages = remember(messages) { messages.groupBy { it.sessionId } }
    val exportText = exportResult

    Scaffold(
        containerColor = colors.bgBase,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DetailTopBar(
                title = "私聊详情",
                onBack = onBack,
                headerBg = ZaijianTheme.colors.surface,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = Spacing.screenHorizontal,
                    vertical = Spacing.md,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // ── 操作区 ──────────────────────────────────────────
                item {
                    SectionCard(title = "操作") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Button(
                                onClick = { showTriggerDialog = true },
                                enabled = pair != null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accent,
                                    contentColor = colors.bgBase,
                                ),
                            ) {
                                Text("发起私聊")
                            }
                            OutlinedButton(
                                onClick = { viewModel.exportMarkdown(pairId) },
                                border = androidx.compose.foundation.BorderStroke(
                                    0.5.dp, colors.accent.copy(alpha = 0.4f),
                                ),
                            ) {
                                Text("导出Markdown", color = colors.accent)
                            }
                            OutlinedButton(
                                onClick = { viewModel.exportPlainText(pairId) },
                                border = androidx.compose.foundation.BorderStroke(
                                    0.5.dp, colors.accent.copy(alpha = 0.4f),
                                ),
                            ) {
                                Text("导出纯文本", color = colors.accent)
                            }
                        }
                    }
                }

                // ── 导出结果 ────────────────────────────────────────
                if (exportText != null) {
                    item {
                        SectionCard(title = "导出结果") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    text = exportText,
                                    style = type.body,
                                    color = colors.textPrimary,
                                )
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                Button(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(exportText))
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.accent,
                                        contentColor = colors.bgBase,
                                    ),
                                ) {
                                    Text("复制到剪贴板")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.clearExportResult() },
                                    border = androidx.compose.foundation.BorderStroke(
                                        0.5.dp, colors.border,
                                    ),
                                ) {
                                    Text("关闭", color = colors.textSecondary)
                                }
                            }
                        }
                    }
                }

                // ── 参数设置 ────────────────────────────────────────
                item {
                    SectionCard(title = "参数设置") {
                        ParamField(
                            label = "每轮最大对话数",
                            value = maxTurns,
                            onValueChange = { if (it.all(Char::isDigit)) maxTurns = it },
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        ParamField(
                            label = "每日最大会话数",
                            value = maxSessions,
                            onValueChange = { if (it.all(Char::isDigit)) maxSessions = it },
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        ParamField(
                            label = "冷却时间（分钟）",
                            value = cooldown,
                            onValueChange = { if (it.all(Char::isDigit)) cooldown = it },
                        )
                        Spacer(Modifier.height(Spacing.md))
                        Button(
                            onClick = {
                                val mt = maxTurns.toIntOrNull()
                                val ms = maxSessions.toIntOrNull()
                                val cd = cooldown.toIntOrNull()
                                if (mt != null && ms != null && cd != null) {
                                    viewModel.updateParams(pairId, mt, ms, cd)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = colors.bgBase,
                            ),
                        ) {
                            Text("保存参数")
                        }
                    }
                }

                // ── 消息记录（按 sessionId 分组）─────────────────────
                item {
                    Text(
                        text = "消息记录",
                        style = type.cardTitle,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = "还没有私聊消息，点击上方「发起私聊」开始",
                            style = type.caption,
                            color = colors.textDisabled,
                        )
                    }
                } else {
                    groupedMessages.forEach { (sessionId, msgs) ->
                        item(key = "header_$sessionId") {
                            SessionHeader(
                                sessionId = sessionId,
                                session = sessionMap[sessionId],
                            )
                        }
                        items(msgs, key = { it.id }) { msg ->
                            MessageRow(msg, viewModel)
                        }
                    }
                }
            }
        }
    }

    // ── 发起私聊 Dialog（选择谁先说）──────────────────────────────
    if (showTriggerDialog && pair != null) {
        val nameA = rememberCharacterName(pair.characterIdA, viewModel)
        val nameB = rememberCharacterName(pair.characterIdB, viewModel)
        TriggerSessionDialog(
            idA = pair.characterIdA,
            nameA = nameA,
            idB = pair.characterIdB,
            nameB = nameB,
            onDismiss = { showTriggerDialog = false },
            onConfirm = { initiatorId ->
                showTriggerDialog = false
                viewModel.triggerSession(pairId, initiatorId)
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  会话分组标题
// ─────────────────────────────────────────────────────────────

@Composable
private fun SessionHeader(
    sessionId: String,
    session: PrivateChatSessionEntity?,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    val startedAt = session?.startedAt ?: 0L
    val timeText = if (startedAt > 0) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(startedAt))
    } else {
        sessionId
    }
    val (statusText, statusColor) = when (session?.status) {
        "completed" -> "已完成" to colors.taskDone
        "in_progress" -> "进行中" to colors.taskPaused
        "interrupted" -> "已中断" to colors.taskFailed
        else -> "会话" to colors.textSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(statusColor.copy(alpha = 0.12f))
                .padding(horizontal = Spacing.sm, vertical = 2.dp),
        ) {
            Text(statusText, style = type.label, color = statusColor)
        }
        Text(
            text = timeText,
            style = type.label,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (session != null) {
            Text(
                text = "${session.turnCount} 轮",
                style = type.label,
                color = colors.textDisabled,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  单条消息
// ─────────────────────────────────────────────────────────────

@Composable
private fun MessageRow(
    message: PrivateChatMessageEntity,
    viewModel: PrivateChatViewModel,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    val senderName = rememberCharacterName(message.senderCharacterId, viewModel)
    val timeText = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = senderName,
                style = type.bodyBold,
                color = colors.accent,
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = "第${message.turnIndexInSession}轮",
                style = type.label,
                color = colors.textDisabled,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = timeText,
                style = type.label,
                color = colors.textDisabled,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = message.content,
            style = type.body,
            color = colors.textPrimary,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  发起私聊 Dialog
// ─────────────────────────────────────────────────────────────

@Composable
private fun TriggerSessionDialog(
    idA: Int,
    nameA: String,
    idB: Int,
    nameB: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (colors.isDark) colors.bgElevated else colors.bgCard,
        tonalElevation = 0.dp,
        title = {
            Text("发起私聊", style = type.cardTitle, color = colors.textPrimary)
        },
        text = {
            Column {
                Text(
                    text = "选择谁先开口：",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.sm))
                TriggerOption(
                    text = "$nameA 先说",
                    onClick = { onConfirm(idA) },
                )
                Spacer(Modifier.height(Spacing.xs))
                TriggerOption(
                    text = "$nameB 先说",
                    onClick = { onConfirm(idB) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = colors.textSecondary)
            }
        },
    )
}

@Composable
private fun TriggerOption(
    text: String,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.accent.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = type.body, color = colors.textPrimary)
    }
}

// ─────────────────────────────────────────────────────────────
//  参数输入字段
// ─────────────────────────────────────────────────────────────

@Composable
private fun ParamField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val colors = ZaijianTheme.colors

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.border,
            cursorColor = colors.accent,
        ),
    )
}

// ─────────────────────────────────────────────────────────────
//  通用 Section 卡片
// ─────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.bgCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.cardPadding)) {
            if (title != null) {
                Text(title, style = type.cardTitle, color = colors.textPrimary)
                Spacer(Modifier.height(Spacing.sm))
            }
            content()
        }
    }
}
