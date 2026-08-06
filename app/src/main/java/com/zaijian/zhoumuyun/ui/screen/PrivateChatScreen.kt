package com.zaijian.zhoumuyun.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatMessageEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatSessionEntity
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.DangerVelvetButton
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.GoldPrimaryButton
import com.zaijian.zhoumuyun.ui.design.SecondaryGoldButton
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.SerifSC
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
                    GoldPrimaryButton(
                        text = "新建角色对",
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ── 配对列表 ────────────────────────────────────────
                if (allPairs.isEmpty()) {
                    // 帧28 空状态：裸 Text 收口为统一 EmptyStateView（金色圆容器 +
                    // 行动按钮），与全 App 空状态族一致。
                    item {
                        EmptyStateView(
                            icon        = AppIcons.PrivateChat,
                            title       = "还没有角色对",
                            subtitle    = "点击上方按钮新建一对AI角色",
                            actionLabel = "新建角色对",
                            onAction    = { showCreateDialog = true },
                        )
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
    // 帧28 双头像交叠：取双方主题色作头像底色（私聊配对均来自 DefaultCharacters）。
    val colorA = DefaultCharacters.find { it.id == pair.characterIdA }?.accentColor ?: colors.accent
    val colorB = DefaultCharacters.find { it.id == pair.characterIdB }?.accentColor ?: Palette.Velvet
    // C8 #45：角色自主下线状态（方案 v1.5 6.4 节），此前只有写入路径没有 UI 展示/恢复入口
    val isDisconnected = pair.characterDisconnectState ==
        com.zaijian.zhoumuyun.data.privatechat.PrivateChatSessionStatus.DISCONNECTED_BY_CHARACTER.name

    // A10-5 修复：删除配对二次确认 Dialog 状态
    var showDeleteDialog by remember { mutableStateOf(false) }

    WorldCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cornerRadius = 16.dp,
    ) {
        Column(modifier = Modifier.padding(Spacing.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    // 帧28 双头像交叠：A 在前(0)、B 偏移 20dp 叠在右侧
                    Box(
                        modifier = Modifier.size(width = 56.dp, height = 36.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        PairAvatar(
                            initial = nameA.firstOrNull()?.toString() ?: "A",
                            color = colorA,
                        )
                        PairAvatar(
                            initial = nameB.firstOrNull()?.toString() ?: "B",
                            color = colorB,
                            modifier = Modifier.offset(x = 20.dp),
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Column {
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
                }
                Spacer(Modifier.width(Spacing.sm))
                Switch(
                    checked = pair.enabled,
                    onCheckedChange = { viewModel.toggleEnabled(pair.pairId, it) },
                )
            }
            if (isDisconnected) {
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.TaskFailed.copy(alpha = 0.08f))
                        .padding(horizontal = Spacing.sm, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "角色已下线，暂不会再回复",
                        style = type.caption,
                        color = Palette.TaskFailed,
                        modifier = Modifier.weight(1f),
                    )
                    GhostGoldButton(
                        text = "恢复",
                        onClick = { viewModel.resetDisconnect(pair.pairId) },
                    )
                }
            }
            // A10-5 修复：删除配对入口
            Spacer(Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                GhostGoldButton(
                    text = "删除配对",
                    onClick = { showDeleteDialog = true },
                )
            }
        }
    }

    // A10-5 修复：删除配对二次确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = if (colors.isDark) colors.bgElevated else colors.bgCard,
            tonalElevation = 0.dp,
            title = {
                Text("删除角色对", style = type.cardTitle, color = colors.textPrimary)
            },
            text = {
                Text(
                    text = "确定删除「$nameA × $nameB」的私聊配对？\n所有私聊消息和会话记录将一并删除，此操作不可撤销。",
                    style = type.body,
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                DangerVelvetButton(
                    text = "删除",
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deletePair(pair.pairId)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                GhostGoldButton(text = "取消", onClick = { showDeleteDialog = false })
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  帧28 双头像单元：角色色渐变底 + 首字（SerifSC），36dp 圆形
// ─────────────────────────────────────────────────────────────

@Composable
private fun PairAvatar(
    initial: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f))))
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontFamily = SerifSC,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
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
                            GhostGoldButton(
                                text = "选为A",
                                onClick = {
                                    selectedA = ch.id
                                    if (selectedB == ch.id) selectedB = null
                                },
                            )
                            GhostGoldButton(
                                text = "选为B",
                                onClick = {
                                    selectedB = ch.id
                                    if (selectedA == ch.id) selectedA = null
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            GoldPrimaryButton(
                text = "创建",
                onClick = {
                    if (canCreate) onConfirm(selectedA!!, selectedB!!)
                },
                modifier = Modifier.alpha(if (canCreate) 1f else 0.4f),
            )
        },
        dismissButton = {
            GhostGoldButton(text = "取消", onClick = onDismiss)
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

    // 参数编辑字段：仅按 pairId 锁定（切换到不同配对时才重新初始化）。
    // 此前用整个 pair（data class）作 key：后台 PrivateChatWorker 完成会话时会写
    // sessionsUsedToday/lastSessionAt/characterDisconnectState，Room Flow 重发导致
    // pair 结构变化 → remember(pair) re-key → 三个草稿被静默重置为 DB 值，用户正在
    // 输入但未点“保存参数”的内容会丢失（B7 审查报告 序号1）。
    // 改为 remember(pairId)，并用 paramsInitialized 标志位保证：只在该 pairId 下
    // 第一次拿到非空 pair 时用 DB 值填充一次草稿，此后 pair 再变化不会覆盖用户输入；
    // 只有用户主动点“保存参数”才会把草稿写回 DB。
    var maxTurns by remember(pairId) { mutableStateOf("") }
    var maxSessions by remember(pairId) { mutableStateOf("") }
    var cooldown by remember(pairId) { mutableStateOf("") }
    var paramsInitialized by remember(pairId) { mutableStateOf(false) }

    LaunchedEffect(pairId, pair != null) {
        if (!paramsInitialized && pair != null) {
            maxTurns = pair.maxTurnsPerSession.toString()
            maxSessions = pair.maxSessionsPerDay.toString()
            cooldown = pair.cooldownMinutes.toString()
            paramsInitialized = true
        }
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
                modifier = Modifier.weight(1f),
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
                            GoldPrimaryButton(
                                text = "发起私聊",
                                onClick = { showTriggerDialog = true },
                            )
                            SecondaryGoldButton(
                                text = "导出Markdown",
                                onClick = { viewModel.exportMarkdown(pairId) },
                                modifier = Modifier.weight(1f),
                            )
                            SecondaryGoldButton(
                                text = "导出纯文本",
                                onClick = { viewModel.exportPlainText(pairId) },
                                modifier = Modifier.weight(1f),
                            )
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
                                SecondaryGoldButton(
                                    text = "复制到剪贴板",
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(exportText))
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                GhostGoldButton(
                                    text = "关闭",
                                    onClick = { viewModel.clearExportResult() },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                // ── 参数设置 ────────────────────────────────────────
                item {
                    SectionCard(title = "参数设置") {
                        ParamField(
                            label = "每轮最大对话数（2-20）",
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
                        GoldPrimaryButton(
                            text = "保存参数",
                            onClick = {
                                val mt = maxTurns.toIntOrNull()?.coerceIn(
                                    PrivateChatViewModel.MIN_TURNS_LOWER_BOUND,
                                    PrivateChatViewModel.MAX_TURNS_UPPER_BOUND,
                                )
                                val ms = maxSessions.toIntOrNull()
                                val cd = cooldown.toIntOrNull()
                                if (mt != null && ms != null && cd != null) {
                                    maxTurns = mt.toString()
                                    viewModel.updateParams(pairId, mt, ms, cd)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().alpha(
                                if (maxTurns.toIntOrNull() != null && maxSessions.toIntOrNull() != null && cooldown.toIntOrNull() != null) 1f else 0.4f
                            ),
                        )
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
                            MessageRow(
                                message = msg,
                                viewModel = viewModel,
                                leftCharacterId = pair?.characterIdA ?: -1,
                            )
                        }
                    }
                }
            }

            // ── 帧29 底部管理条（固定底部，非输入框）──────────────
            PrivateChatDetailBottomBar(
                onTrigger = { showTriggerDialog = true },
                onExport = { viewModel.exportMarkdown(pairId) },
            )
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
            onConfirm = { initiatorId, directive ->
                showTriggerDialog = false
                viewModel.triggerSession(pairId, initiatorId, directive)
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
        // v2.7 新增：区分"角色主动下线"与系统异常中断。owner 在管理面板本就
        // 有权限看到真实状态，这里用"对方中断"而非"角色自主下线"这种技术
        // 措辞，与角色扮演的叙事口吻保持一致（不影响 6.4 节对角色本身隐藏
        // 下线状态的设计——那是对私聊对方视角的隐藏，跟这里 owner 查看自己
        // 的会话历史是两回事）。
        "disconnected" -> "对方中断" to colors.taskFailed
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
    leftCharacterId: Int,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    val senderName = rememberCharacterName(message.senderCharacterId, viewModel)
    val timeText = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }
    // 帧29 左右分列：A 角色(leftCharacterId)靠左、B 角色靠右；竖条用发送方角色色
    val isLeft = if (leftCharacterId > 0) message.senderCharacterId == leftCharacterId else true
    val senderColor = DefaultCharacters.find { it.id == message.senderCharacterId }?.accentColor
        ?: colors.accent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
    ) {
        // 非左侧消息先用 weight(1f) 占位把气泡推到右侧
        if (!isLeft) Spacer(Modifier.weight(1f))

        // 气泡（竖条卡）：3dp 角色色竖条 + 内容，宽度上限避免长消息撑满整行
        Row(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bgElevated),
        ) {
            // 左侧消息：竖条在左；右侧消息：竖条在右
            if (isLeft) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(senderColor),
                )
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = senderName,
                        style = type.bodyBold,
                        color = senderColor,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = "第${message.turnIndexInSession}轮",
                        style = type.label,
                        color = colors.textDisabled,
                    )
                    Spacer(Modifier.width(Spacing.xs))
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
            if (!isLeft) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(senderColor),
                )
            }
        }

        // 左侧消息末尾用 weight(1f) 占位把气泡留在左侧
        if (isLeft) Spacer(Modifier.weight(1f))
    }
}

// ─────────────────────────────────────────────────────────────
//  帧29 底部管理条（固定底部，非输入框）
// ─────────────────────────────────────────────────────────────

@Composable
private fun PrivateChatDetailBottomBar(
    onTrigger: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgCard)
            .navigationBarsPadding(),
    ) {
        // 顶部 0.5dp 发丝分隔线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.border),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryGoldButton(
                text = "发起新会话",
                onClick = onTrigger,
                modifier = Modifier.weight(1f),
            )
            GhostGoldButton(
                text = "导出",
                onClick = onExport,
            )
        }
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
    onConfirm: (Int, String?) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    var directiveText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (colors.isDark) colors.bgElevated else colors.bgCard,
        tonalElevation = 0.dp,
        title = {
            Text("发起私聊", style = type.cardTitle, color = colors.textPrimary)
        },
        text = {
            Column {
                // 可选：让先说的角色带着这个目的去聊，不填则自然对话不设目的。
                // 只对"先说"的那位生效——对方仍按自然反应应对，不知道这是任务。
                Text(
                    text = "想让先开口的角色带着什么目的聊？（可不填）",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.xs))
                OutlinedTextField(
                    value = directiveText,
                    onValueChange = { directiveText = it },
                    placeholder = { Text("比如：去试探一下对方对你的态度") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.border,
                        cursorColor = colors.accent,
                    ),
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "选择谁先开口：",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.sm))
                TriggerOption(
                    text = "$nameA 先说",
                    onClick = { onConfirm(idA, directiveText.trim().ifBlank { null }) },
                )
                Spacer(Modifier.height(Spacing.xs))
                TriggerOption(
                    text = "$nameB 先说",
                    onClick = { onConfirm(idB, directiveText.trim().ifBlank { null }) },
                )
            }
        },
        confirmButton = {
            GhostGoldButton(text = "关闭", onClick = onDismiss)
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

    WorldCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        Column(modifier = Modifier.padding(Spacing.cardPadding)) {
            if (title != null) {
                Text(title, style = type.cardTitle, color = colors.textPrimary)
                Spacer(Modifier.height(Spacing.sm))
            }
            content()
        }
    }
}
