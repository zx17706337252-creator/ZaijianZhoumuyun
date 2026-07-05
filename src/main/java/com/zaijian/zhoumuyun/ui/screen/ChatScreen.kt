package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import com.zaijian.zhoumuyun.ui.design.WorldCard
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.zaijian.zhoumuyun.data.model.ChatMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.component.FertileWindowConsentDialog
import com.zaijian.zhoumuyun.ui.component.MarkdownText
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.BubbleDimen
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.RingWidth
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.ChatViewModel

import com.zaijian.zhoumuyun.ui.viewmodel.KnowledgeInjectMode
import com.zaijian.zhoumuyun.ui.viewmodel.PresenceViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.ProjectViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf

import com.zaijian.zhoumuyun.ZaijianApp
import com.zaijian.zhoumuyun.data.engine.MoodType

// ─────────────────────────────────────────────────────────────
//  数据模型
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  本地消息 ID 生成（时间戳用途）
// ─────────────────────────────────────────────────────────────
private const val TIMESTAMP_INTERVAL_MS = 30 * 60 * 1000L

private fun formatTimestamp(ms: Long): String {
    val h = (ms / 3600000 % 24).toInt()
    val m = (ms / 60000 % 60).toInt()
    val ampm = if (h < 12) "上午" else "下午"
    val h12 = when {
        h == 0 -> 12
        h <= 12 -> h
        else -> h - 12
    }
    return "$ampm $h12:${m.toString().padStart(2, '0')}"
}

// ─────────────────────────────────────────────────────────────
//  ChatScreen  — 单聊页（Phase 4 Step 1）
//  设计规范 §13
//
//  结构（从后到前）：
//    [0] 背景色（bgBase）
//    [1] 消息列表（LazyColumn，可滚动）
//    [2] 顶部情绪卡（activityHint，可折叠）
//    [3] 顶部栏（毛玻璃，56dp）
//    [4] 底部输入栏（毛玻璃，imePadding）
// ─────────────────────────────────────────────────────────────

@Composable
fun ChatScreen(
    characterId: Int,
    onBack: () -> Unit = {},
    onNavigateToProfile: (Int) -> Unit = {},
    presenceViewModel: PresenceViewModel = viewModel(),
    projectViewModel: ProjectViewModel = viewModel(),
    // Bug1修复：ChatViewModel 只有 Application 参数（AndroidViewModel子类），
    // 标准 viewModel() 工厂可自动处理，无需自定义工厂。
    // 原来的 ChatViewModelFactory 传入新实例会干扰 Compose 的 ViewModel 缓存，
    // 导致 setChatMode() 更新的是游离实例，UI 读取的是另一个实例，状态无法同步。
    chatViewModel: ChatViewModel = viewModel(),
) {
    val colors   = ZaijianTheme.colors
    val type     = ZaijianTheme.typography
    val scope    = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // 初始化 ChatViewModel（绑定角色 ID）
    LaunchedEffect(characterId) {
        chatViewModel.init(characterId)
    }

    // 观察 UI 状态
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val activeProjects by projectViewModel.activeProjects.collectAsStateWithLifecycle(initialValue = emptyList())

    // 从 ViewModel 查找角色和 Presence
    // D4 触发点接入 Part 4：原先这里自己用 DefaultCharacters.find { ... } ?: return
    // 直接查，女儿（characterId 1000+）查不到就导致整个聊天界面渲染空白。
    // 现在改为读 chatViewModel.init() 已经查好（预设角色 + 女儿都覆盖）的
    // uiState.character，不再自己判断"是不是预设角色"。
    // character 为 null 有两种情况：① init() 还没跑完查询（短暂的加载态，
    // 通常只持续一帧，因为查询很快）；② 真正异常（既不是预设角色也不是
    // 女儿）。两种情况下都暂不渲染界面内容，等 uiState 更新后自动重组。
    val character = uiState.character ?: return

    // H-3 修复：presenceViewModel 此前只作为未用参数传入，在线状态文案
    // 永远读的是硬编码 DefaultPresenceStates，PresenceEngine 的实时更新
    // （聊天后状态变化、心情、女儿角色 presence 等）完全不会反映到此页。
    // 改为从 presenceViewModel.uiState.presenceMap 读取，查不到时（理论上
    // WorldUiState 默认值已覆盖所有预设角色）才回退到静态默认值。
    val presenceState by presenceViewModel.uiState.collectAsStateWithLifecycle()
    val presence = presenceState.presenceMap[characterId]
        ?: DefaultPresenceStates.find { it.characterId == characterId }

    // A-1 修复：关系状态从 ViewModel 收集，不再直连 DB
    val relForHeader by chatViewModel.relForHeader.collectAsStateWithLifecycle()
    val headerStageLabel = relForHeader?.let { rel ->
        when (rel.stage) {
            "STRANGER"  -> "陌生人"
            "FAMILIAR"  -> "熟悉"
            "TRUSTED"   -> "信任"
            "IMPORTANT" -> "重要"
            "CORE"      -> "核心"
            else        -> null
        }
    }
    // 消息列表（来自 DB + 流式 streaming 追加）
    // Fix-1.1：上移至此，原位置在 headerMood 之后导致前向引用编译错误
    val messages = uiState.messages
    // UI M3 ��复：心情直接读 uiState.currentMood，
    // ViewModel 在 parsedMood != null 时推送， init() 时从缓存种子。
    // 不再访问全局单例 ZaijianApp.sharedPresenceEngine。
    val headerMood = uiState.currentMood
    val headerSuppressionLabel = relForHeader?.suppression?.let { s ->
        when {
            s <= 30 -> "心防较高"
            s >= 75 -> "已放松"
            else    -> null
        }
    }

    // UI S4 修复：用户正在输入的文字在进程死亡后应能恢复，改用 rememberSaveable
    var inputText by rememberSaveable { mutableStateOf("") }
    var emotionCardVisible by remember { mutableStateOf(presence?.activityHint != null) }
    // Phase 16：聊天设置底部面板
    var showChatSettings by remember { mutableStateOf(false) }

    // 聊天背景图：从 uiState 读取当前角色背景 URI
    val backgroundImageUri = uiState.backgroundImageUri

    // Phase 18：文件分享 Intent（打开 file_export 生成的文件）
    val ctx2 = androidx.compose.ui.platform.LocalContext.current

    // 文件导入：系统文件选择器 → 复制到 filesDir/imports/ → 通知 Agent
    val importDir = remember {
        val dir = java.io.File(ctx2.filesDir, "imports")
        dir.mkdirs()
        dir
    }
    val fileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult Unit
        try {
            val fileName = resolveFileName(ctx2, uri)
            val safeName = fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(100)
            val dest = java.io.File(importDir, "${System.currentTimeMillis()}_$safeName")
            ctx2.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            chatViewModel.notifyFileImported(safeName, dest.absolutePath)
        } catch (e: Exception) {
            // UI M13 修复：原 catch (_: Exception) { } 完全静默吞掉异常——
            // 文件选择器返回 uri 后，复制失败时用户毫无反馈，只会觉得"点了没反应"。
            // 现在补 ZLog 留痕，并通过已有的 snackbarHostState 给出可见提示。
            com.zaijian.zhoumuyun.util.ZLog.w("ChatScreen", "文件导入失败: uri=$uri", e)
            scope.launch {
                snackbarHostState.showSnackbar("文件导入失败，请重试")
            }
        }
    }
    val openFile: (com.zaijian.zhoumuyun.ui.viewmodel.ExportedFile) -> Unit = { ef ->
        try {
            val file = java.io.File(ef.absolutePath)
            if (file.exists()) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx2,
                    "${ctx2.packageName}.fileprovider",
                    file,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, ef.mimeType)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx2.startActivity(android.content.Intent.createChooser(intent, "打开 ${ef.fileName}"))
            }
        } catch (_: Exception) { /* 无默认应用时静默忽略 */ }
    }

    // 聊天背景图选择器：持久化 URI 权限，确保下次打开仍能读取图片
    val bgImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            // 持久化 URI 读取权限（跨进程重启有效）
            ctx2.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            chatViewModel.setChatBackground(uri.toString())
        } catch (e: Exception) {
            com.zaijian.zhoumuyun.util.ZLog.w("ChatScreen", "背景图设置失败: uri=$uri", e)
            scope.launch { snackbarHostState.showSnackbar("背景图设置失败，请重试") }
        }
    }

    // A-5：预先计算所有需要显示的时间戳字符串，避免在 itemsIndexed 每次重组时反复执行字符串格式化。
    // remember(messages) 保证仅在列表引用变化时重算，Map 查表复杂度 O(1)。
    val timestampMap: Map<String, String> = remember(messages) {
        buildMap {
            messages.forEachIndexed { index, msg ->
                val prevMsg = if (index > 0) messages[index - 1] else null
                val showTimestamp = prevMsg == null ||
                        (msg.createdAt - prevMsg.createdAt) >= TIMESTAMP_INTERVAL_MS
                if (showTimestamp) {
                    put(msg.id, formatTimestamp(msg.createdAt))
                }
            }
        }
    }
    val isTyping = uiState.isTyping
    // H1 修复：streamingContent 不再在 ChatScreen 顶层直接读取。
    // 原来每个 token 都会更新 streamingContent，触发整个 500+ 行 ChatScreen 重组。
    // 现在实际气泡内容下沉到 StreamingMessageItem 子组件，子组件自己收集状态。
    // 顶层滚动逻辑改用 snapshotFlow 在协程内监听，不在重组作用域里读 streamingContent，
    // 彻底切断 streamingContent → ChatScreen 重组的链路。
    // Phase 13：工具执行提示（如「正在搜索…」），null = 无工具执行
    val streamingHint = uiState.streamingHint
    // Phase 24：打分评审状态
    val pendingEvaluationSessionId = uiState.pendingEvaluationSessionId
    // Phase 30 方案一：聊天模式
    val chatMode = uiState.chatMode
    val pendingEvaluationReport    = uiState.pendingEvaluationReport
    val pendingAgentScore          = uiState.pendingAgentScore

    // 新消息时自动滚动到底部（低频，有动画）
    LaunchedEffect(messages.size, isTyping, pendingEvaluationSessionId) {
        val totalItems = messages.size +
            (if (isTyping) 1 else 0) +
            (if (pendingEvaluationSessionId != null) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }
    // H1 修复：流式滚动改用 snapshotFlow，在协程里监听 streamingContent 长度变化并滚动，
    // 完全不在 Compose 重组作用域内读取 streamingContent，ChatScreen 顶层不再随 token 重组。
    // UI M14 修复保留：scrollToItem（无动画）避免动画积压。
    // P1-11-1 修复：原 snapshotFlow { chatViewModel.uiState.value.streamingContent } 读
    // StateFlow.value，StateFlow 不是 Compose State，snapshotFlow 只在首次快照时发射一次，
    // 后续 streamingContent 更新不触发重发。修复：改为读已通过 collectAsState() 绑定的
    // Compose State 变量 uiState，snapshotFlow 能正确感知每次重组产生的新快照值。
    LaunchedEffect(listState) {
        snapshotFlow { uiState.streamingContent?.length ?: 0 }
            .collect { len ->
                if (len > 0) {
                    val totalItems = listState.layoutInfo.totalItemsCount
                    if (totalItems > 0) {
                        listState.scrollToItem(totalItems - 1)
                    }
                }
            }
    }

    // 错误提示 Snackbar
    LaunchedEffect(uiState.error) {
        val err = uiState.error
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            chatViewModel.clearError()
        }
    }

    // Phase 26：提炼成功通知 Snackbar
    // 当 DistillationEngine 成功锁定新规则时，在消息流底部短暂展示一条通知。
    LaunchedEffect(uiState.pendingDistillResult) {
        val result = uiState.pendingDistillResult
        if (result != null && result.triggered && result.newlyLockedCount > 0) {
            val msg = "🔒 「${result.goalTitle}」新增 ${result.newlyLockedCount} 条锁定规则，目标进度 +${(result.progressDelta * 100).toInt()}%"
            snackbarHostState.showSnackbar(msg)
            chatViewModel.dismissDistillResult()
        }
    }

    // D4 女儿生成失败提示
    LaunchedEffect(uiState.pendingDaughterGenerationError) {
        val err = uiState.pendingDaughterGenerationError
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            chatViewModel.clearDaughterGenerationError()
        }
    }

    // 主动消息前台实时呈现（角色正在发消息时用户恰好开着聊天页）
    // Fix：先 showSnackbar 再 clear，避免 LaunchedEffect 取消时消息已清但用户没看到。
    // 与 error / pendingDaughterGenerationError 的顺序保持一致。
    LaunchedEffect(uiState.pendingProactiveMessage) {
        val msg = uiState.pendingProactiveMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            chatViewModel.clearProactiveMessage()
        }
    }

    // ── 1.1 受孕窗口同意对话框 ─────────────────────────────────
    val fertileDialogText = uiState.fertileWindowConsentDialogText
    if (fertileDialogText != null) {
        FertileWindowConsentDialog(
            characterName = uiState.fertileWindowCharacterName,
            accentColor   = character.accentColor,
            dialogText    = fertileDialogText,
            onAccept      = { chatViewModel.onFertileWindowDialogResult(accepted = true) },
            onReject      = { chatViewModel.onFertileWindowDialogResult(accepted = false) },
        )
    }

    // API Key 未配置提示
    // M-5 修复：LaunchedEffect 原先嵌套在 if 块内，属于条件性组合。
    // 提升到 if 外部，key 不变时 Compose 保证不重复触发，逻辑等价但更安全：
    // 即使 isApiKeyMissing 因竞态未被清除，下次重组也不会因为"if 块消失又出现"
    // 产生奇怪的组合树结构变化。
    LaunchedEffect(uiState.isApiKeyMissing) {
        if (uiState.isApiKeyMissing) {
            chatViewModel.clearApiKeyMissingFlag()
            onNavigateToProfile(characterId)
        }
    }

    // 顶部 Header 背景色
    val headerBg = if (colors.isDark)
        colors.bgBase.copy(alpha = GlassOpacity.topBarDark)
    else
        colors.bgBase.copy(alpha = GlassOpacity.topBarLight)

    // 底部输入栏背景色
    val inputBarBg = if (colors.isDark)
        colors.bgCard.copy(alpha = 0.92f)
    else
        colors.bgBase.copy(alpha = 0.95f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val base = if (colors.isDark) Palette.Night else Palette.Cream
                drawRect(base)
                // 角色氛围光：从正上方散出，模拟公馆顶部水晶灯/烛台
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            character.accentColor.copy(
                                alpha = if (colors.isDark) 0.09f else 0.07f
                            ),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.50f, size.height * 0.10f),
                        radius = size.width * 1.0f,
                    )
                )
                // 底部消散渐变（与输入框融合）
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, base.copy(alpha = 0.60f)),
                        startY = size.height * 0.70f,
                        endY   = size.height,
                    )
                )
            },
    ) {
        // ── [0] 自定义背景图（用户设置时覆盖默认渐变）──────────
        if (backgroundImageUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(backgroundImageUri)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 半透明遮罩，保证气泡/文字可读性
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (colors.isDark)
                            Color.Black.copy(alpha = 0.45f)
                        else
                            Color.White.copy(alpha = 0.30f)
                    )
            )
        }
        // imePadding()：与底部输入栏的 imePadding() 联动——键盘弹出时输入栏
        // 上移，若消息列表不跟着收缩可视区域，固定的 contentPadding.bottom
        // 就不够用，最新消息会被抬起来的输入框压住。
        LazyColumn(
            state            = listState,
            modifier         = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding   = PaddingValues(
                // Fix-ChatHeader: 有关系胶囊时头部多一行（约22dp），顶部 padding 同步增加
                top    = Spacing.topBarHeight +
                         (if (headerStageLabel != null || headerMood != null || headerSuppressionLabel != null) 22.dp else 0.dp) +
                         (if (emotionCardVisible && presence?.activityHint != null) 40.dp else 0.dp) +
                         Spacing.md,
                bottom = 80.dp + Spacing.xl + 20.dp,   // 底栏高度 + 安全区 + 评审卡片间距
                start  = Spacing.screenHorizontal,
                end    = Spacing.screenHorizontal,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
                // A-6：animateItem 是 LazyColumn 内置入场动画（foundation 1.7+），
                // 仅对真正新插入的 item 触发一次，不会像 AnimatedVisibility(visible=true)
                // 那样对列表中所有现有 item 持续运行状态机。
                // 时间戳和气泡用 Column 合并为单一 item 内容，动画作用于整体。
                Column(modifier = Modifier.animateItem(fadeInSpec = tween(180))) {
                    // A-5：直接查预计算表，O(1)，无字符串格式化开销
                    val timeStr = timestampMap[msg.id]
                    if (timeStr != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.sm),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text  = timeStr,
                                style = type.label,
                                color = colors.textDisabled,
                            )
                        }
                    }

                    MessageBubble(
                        message       = msg,
                        accentColor   = character.accentColor,
                        avatarUrl     = character.avatarUrl,
                        characterName = character.name,
                        onOpenFile    = openFile,
                    )
                }
            }
            // 流式打字机效果（AI 正在回复）
            // H1 修复：气泡内容下沉到 StreamingMessageItem 子组件，
            // 子组件内部自己收集 streamingContent，每个 token 只重组这一个小组件，
            // ChatScreen 顶层不再随 token 刷新。
            if (isTyping) {
                item(key = "streaming") {
                    StreamingMessageItem(
                        chatViewModel = chatViewModel,
                        accentColor   = character.accentColor,
                        avatarUrl     = character.avatarUrl,
                        characterName = character.name,
                    )
                }

                // Phase 13：工具执行提示行
                // streamingHint 非 null 时在打字机气泡下方显示一行小提示，
                // ToolDone 事件到达后 streamingHint 置 null，提示自动消失。
                if (streamingHint != null) {
                    item(key = "tool_hint") {
                        ToolHintRow(
                            hint        = streamingHint,
                            accentColor = character.accentColor,
                        )
                    }
                }
            }

            // ── Phase 24：打分卡片（评审汇报 + 用户打分）────────
            // 不放在 isTyping 分支内，Agent B 评审完成后独立展示
            if (pendingEvaluationSessionId != null && pendingEvaluationReport != null) {
                item(key = "evaluation_card") {
                    EvaluationCard(
                        reportText  = pendingEvaluationReport,
                        agentScore  = pendingAgentScore,
                        accentColor = character.accentColor,
                        onSubmit    = { stars -> chatViewModel.submitEvaluationScore(stars) },
                        onSkip      = { chatViewModel.skipEvaluation() },
                    )
                }
            }
        }

        // ── [3] 顶部情绪卡（可折叠，40dp）──────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
        ) {
            // Header 占位层
            Box(modifier = Modifier.height(Spacing.topBarHeight))

            // 情绪卡
            AnimatedVisibility(
                visible = emotionCardVisible && presence?.activityHint != null,
                enter   = fadeIn(tween(AnimDuration.fast)) +
                          slideInVertically(tween(AnimDuration.fast)) { -it },
            ) {
                if (presence?.activityHint != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)   // 大字体下允许向上撑开，不截字
                            .background(character.accentColor.copy(alpha = 0.12f))
                            .clickable { emotionCardVisible = false },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text     = presence.activityHint,
                            style    = type.caption,
                            color    = character.accentColor,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
                        )
                    }
                }
            }
        }

        // ── [2] 顶部栏（毛玻璃，56dp）────────────────────────
        ChatHeader(
            name         = character.name,
            avatarUrl    = character.avatarUrl,
            breathColor  = character.breathColor,
            accentColor  = character.accentColor,
            statusText   = presence?.statusText ?: "",
            statusType   = presence?.statusType ?: StatusType.OFFLINE,
            headerBg     = headerBg,
            onBack       = onBack,
            onAvatarClick = { onNavigateToProfile(characterId) },
            onMoreClick  = { showChatSettings = true },
            // 待办10：关系状态
            relStageLabel      = headerStageLabel,
            relMood            = headerMood,
            relSuppressionHint = headerSuppressionLabel,
            chatMode      = uiState.chatMode,
            onChatModeChange = { chatViewModel.setChatMode(it) },
            modifier     = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
        )

        // ── Phase 16：聊天设置底部面板 ────────────────────────
        if (showChatSettings) {
            ChatSettingsSheet(
                characterName      = character.name,
                accentColor        = character.accentColor,
                onNavigateToDetail = { onNavigateToProfile(characterId) },
                knowledgeMode      = uiState.knowledgeInjectMode,
                onKnowledgeModeChange = { chatViewModel.setKnowledgeInjectMode(it) },
                onManualKnowledgeTrigger = { chatViewModel.triggerManualKnowledgeInject() },
                onClearMessages    = { chatViewModel.clearMessages() },
                activeProjects     = uiState.activeProjects,
                currentProjectId   = uiState.activeProjectId,
                onSetProject       = { chatViewModel.setActiveProject(it) },
                hasCustomBackground = backgroundImageUri != null,
                onSetBackground    = {
                    bgImageLauncher.launch(arrayOf("image/*"))
                    showChatSettings = false
                },
                onClearBackground  = {
                    chatViewModel.clearChatBackground()
                    showChatSettings = false
                },
                onDismiss          = { showChatSettings = false },
            )
        }

        // ── [4] 底部输入栏 ────────────────────────────────────
        ChatInputBar(
            value       = inputText,
            onValueChange = { inputText = it },
            accentColor = character.accentColor,
            bgColor     = inputBarBg,
            isTyping    = isTyping,
            onSend      = {
                val text = inputText.trim()
                if (text.isNotEmpty()) {
                    chatViewModel.sendMessage(text)
                    inputText = ""
                    scope.launch {
                        listState.animateScrollToItem(messages.size)
                    }
                }
            },
            onImport    = { fileImportLauncher.launch(arrayOf("*/*")) },
            modifier    = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding(),
        )

        // ── [5] 错误 Snackbar ─────────────────────────────────
        SnackbarHost(
            hostState = snackbarHostState,
            modifier  = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 88.dp),
            snackbar = { data ->
                Snackbar(
                    snackbarData   = data,
                    containerColor = ZaijianTheme.colors.bgCard,
                    contentColor   = ZaijianTheme.colors.textPrimary,
                    shape          = RoundedCornerShape(12.dp),
                )
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  StreamingMessageItem — 流式打字机气泡
//  H1 修复：独立子组件，自己收集 streamingContent。
//  每个 token 只触发此组件重组，ChatScreen 顶层保持稳定。
// ─────────────────────────────────────────────────────────────

@Composable
private fun StreamingMessageItem(
    chatViewModel: ChatViewModel,
    accentColor: Color,
    avatarUrl: String,
    characterName: String,
) {
    // L-8 修复：原先收集完整 uiState，每个 token 都会让顶层 ChatScreen（同样收集
    // 完整 uiState）一并重组，H1 设计的隔离效果实际未生效。
    // 改为只收集 ChatViewModel 新暴露的独立 streamingContent: StateFlow<String?>，
    // 该流只在内容真正变化时更新，且不携带 uiState 其余字段，
    // 真正把高频重组限制在 StreamingMessageItem 内部。
    val streamingContent by chatViewModel.streamingContent.collectAsStateWithLifecycle()
    // 编译修复：isNullOrEmpty() 是扩展函数，不会对 streamingContent 触发智能转换收窄，
    // then 分支类型仍是 String?，导致整个表达式推断为 String?，与 content: String 不匹配。
    // 用局部 val 显式判空后取值，保证类型确定为 String。
    val currentStreaming = streamingContent
    val displayContent = if (!currentStreaming.isNullOrEmpty()) currentStreaming else "…"
    MessageBubble(
        message = com.zaijian.zhoumuyun.ui.viewmodel.ChatMessage(
            id        = "streaming",
            role      = "assistant",
            content   = displayContent,
            createdAt = System.currentTimeMillis(),
        ),
        accentColor   = accentColor,
        avatarUrl     = avatarUrl,
        characterName = characterName,
    )
}

// ─────────────────────────────────────────────────────────────
//  ChatHeader — 毛玻璃顶栏
//  规范 §13：返回箭头 / 头像+角色名+状态文案 / 更多图标
// ─────────────────────────────────────────────────────────────

@Composable
private fun ChatHeader(
    name: String,
    avatarUrl: String,
    breathColor: Color,
    accentColor: Color,
    statusText: String,
    statusType: StatusType,
    headerBg: Color,
    chatMode: ChatMode = ChatMode.WORK,
    onBack: () -> Unit,
    onAvatarClick: () -> Unit,
    onMoreClick: () -> Unit = {},
    onChatModeChange: (ChatMode) -> Unit = {},
    // 待办10：关系状态胶囊（均可为 null，null = 不展示）
    relStageLabel: String? = null,
    relMood: com.zaijian.zhoumuyun.data.engine.MoodType? = null,
    relSuppressionHint: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Box(
        modifier = modifier
            .background(headerBg)
            .border(
                width  = 0.5.dp,
                color  = colors.borderSubtle,
                shape  = RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp),
            )
            .statusBarsPadding(),
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.topBarHeight)  // Fix-ChatHeader: 改 height→heightIn，关系胶囊行存在时可撑开
                .padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回箭头
            IconButton(onClick = onBack) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint               = colors.textPrimary,
                    modifier           = Modifier.size(24.dp),
                )
            }

            Spacer(Modifier.width(Spacing.xs))

            // 头像（点击进入详情页）
            Box(
                modifier = Modifier
                    .size(AvatarSize.chat)
                    .clickable { onAvatarClick() },
            ) {
                BreathingAvatar(
                    imageUrl    = avatarUrl,
                    breathColor = breathColor,
                    statusType  = statusType,
                    modifier    = Modifier.fillMaxSize(),
                    size        = AvatarSize.chat,
                    ringWidth   = RingWidth.chat,
                    glowRadius  = 4.dp,
                    enableBreath = false,   // 顶栏不呼吸，减少干扰
                )
            }

            Spacer(Modifier.width(Spacing.sm))

            // 角色名 + 状态文案
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = name,
                    style = type.navTitle,
                    color = colors.textPrimary,
                )
                if (statusText.isNotEmpty()) {
                    Text(
                        text  = statusText,
                        style = type.label.copy(
                            fontStyle     = FontStyle.Italic,
                            letterSpacing = 0.3.sp,
                        ),
                        color   = accentColor.copy(alpha = if (colors.isDark) 0.80f else 0.70f),
                        maxLines = 1,
                    )
                }
                // 待办10：关系状态胶囊行（紧凑，仅有数据时显示）
                val moodLabel = when (relMood) {
                    com.zaijian.zhoumuyun.data.engine.MoodType.EXCITED    -> "✨ 兴奋"
                    com.zaijian.zhoumuyun.data.engine.MoodType.SATISFIED  -> "😊 愉快"
                    com.zaijian.zhoumuyun.data.engine.MoodType.CURIOUS    -> "🤔 好奇"
                    com.zaijian.zhoumuyun.data.engine.MoodType.FOCUSED    -> "🎯 专注"
                    com.zaijian.zhoumuyun.data.engine.MoodType.CALM       -> "🌿 平静"
                    com.zaijian.zhoumuyun.data.engine.MoodType.REFLECTIVE -> "💭 沉思"
                    com.zaijian.zhoumuyun.data.engine.MoodType.TIRED      -> "😴 疲惫"
                    com.zaijian.zhoumuyun.data.engine.MoodType.CONCERNED  -> "😟 担心"
                    null                -> null
                }
                val hasRelInfo = relStageLabel != null || moodLabel != null || relSuppressionHint != null
                if (hasRelInfo) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (relStageLabel != null) {
                            ChatRelCapsule(text = relStageLabel, color = accentColor)
                        }
                        if (moodLabel != null) {
                            ChatRelCapsule(text = moodLabel, color = colors.textSecondary)
                        }
                        if (relSuppressionHint != null) {
                            ChatRelCapsule(text = relSuppressionHint, color = colors.textDisabled)
                        }
                    }
                }
            }

            // 模式切换（工作 / 陪伴）— Phase 30 方案一
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.borderSubtle)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeChip(
                    label    = "工作",
                    icon     = Icons.Outlined.Work,
                    selected = chatMode == ChatMode.WORK,
                    accent   = accentColor,
                    onClick  = { onChatModeChange(ChatMode.WORK) },
                )
                ModeChip(
                    label    = "陪伴",
                    icon     = Icons.Outlined.Favorite,
                    selected = chatMode == ChatMode.COMPANION,
                    accent   = accentColor,
                    onClick  = { onChatModeChange(ChatMode.COMPANION) },
                )
            }

            // 更多图标
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector        = Icons.Outlined.MoreVert,
                    contentDescription = "更多",
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(24.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ToolHintRow — 工具执行提示行（Phase 13）
//  显示在打字机气泡下方，工具执行期间可见，ToolDone 后自动消失。
//
//  视觉设计：
//    - 左对齐，与角色气泡对齐（预留 32dp 头像位 + 8dp 间距）
//    - accentColor.copy(alpha=0.55f) 文字，低调不抢眼
//    - 小号字（label），无背景，无气泡
//    - 前置 ⚙ 图标，直径 14dp
// ─────────────────────────────────────────────────────────────

@Composable
private fun ToolHintRow(
    hint: String,
    accentColor: Color,
) {
    val colors = com.zaijian.zhoumuyun.ui.theme.LocalAppColors.current
    val type   = com.zaijian.zhoumuyun.ui.theme.LocalAppTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AvatarSize.bubbleAvatar + Spacing.sm + Spacing.sm,
                end    = Spacing.md,
                top    = 2.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text  = "⚙ $hint",
            style = type.label,
            color = accentColor.copy(alpha = 0.55f),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  MessageBubble — 单条消息
//  规范 §13：
//    角色气泡 圆角 20/20/20/4dp，左侧 32dp 头像
//    用户气泡 圆角 20/4/20/20dp，右对齐，accentColor 填充
//    最大宽度 屏幕宽 × 0.72
// ─────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: com.zaijian.zhoumuyun.ui.viewmodel.ChatMessage,
    accentColor: Color,
    avatarUrl: String,
    characterName: String,
    onOpenFile: (com.zaijian.zhoumuyun.ui.viewmodel.ExportedFile) -> Unit = {},
) {
    val colors         = ZaijianTheme.colors
    val type           = ZaijianTheme.typography
    val screenWidth    = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * BubbleDimen.maxWidthFraction

    if (message.role == "user") {
        // ── 用户气泡（右对齐）──────────────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(
                        RoundedCornerShape(
                            topStart    = Radius.md,
                            topEnd      = Radius.md,
                            bottomStart = Radius.md,
                            bottomEnd   = Radius.xs,
                        )
                    )
                    .background(accentColor)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                Text(
                    text  = message.content,
                    style = type.body,
                    color = Color.White,
                )
            }
        }
    } else {
        // ── 角色气泡（左对齐，带头像）───────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            // 头像占位（32dp）
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(avatarUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = characterName,
                modifier           = Modifier
                    .size(AvatarSize.chat)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.2f)),
                contentScale       = ContentScale.Crop,
                error              = rememberVectorPainter(Icons.Outlined.Person),
            )

            Spacer(Modifier.width(Spacing.sm))

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                // 文字气泡（有内容时显示）
                if (message.content.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = maxBubbleWidth)
                            .clip(
                                RoundedCornerShape(
                                    topStart    = Radius.md,
                                    topEnd      = Radius.md,
                                    bottomStart = Radius.xs,
                                    bottomEnd   = Radius.md,
                                )
                            )
                            .background(if (colors.isDark) colors.bgCard else colors.bgElevated)
                            .border(
                                width  = 0.5.dp,
                                color  = if (colors.isDark) Palette.Gold.copy(alpha = 0.18f) else Palette.Gold.copy(alpha = 0.28f),
                                shape  = RoundedCornerShape(
                                    topStart    = Radius.md,
                                    topEnd      = Radius.md,
                                    bottomStart = Radius.xs,
                                    bottomEnd   = Radius.md,
                                ),
                            )
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    ) {
                        // Phase 21：角色气泡使用 MarkdownText 渲染富文本
                        // 用户气泡（上方）保持原生 Text，FileExportCard 不受影响
                        MarkdownText(
                            markdown  = message.content,
                            textColor = colors.textPrimary,
                            style     = type.body,
                        )
                    }
                }

                // Phase 18：文件导出卡片（有 exportedFile 时显示）
                message.exportedFile?.let { ef ->
                    FileExportCard(
                        file        = ef,
                        accentColor = accentColor,
                        maxWidth    = maxBubbleWidth,
                        onOpen      = { onOpenFile(ef) },
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  FileExportCard — 文件下载卡片（Phase 18）
//
//  展示在角色气泡下方，样式参考 Telegram 文件卡片：
//    ┌──────────────────────────────────┐
//    │  📄  MD   周报草稿.md            │
//    │       1.2 KB                    │
//    │                        [打开] ▶ │
//    └──────────────────────────────────┘
// ─────────────────────────────────────────────────────────────

@Composable
private fun FileExportCard(
    file: com.zaijian.zhoumuyun.ui.viewmodel.ExportedFile,
    accentColor: Color,
    maxWidth: androidx.compose.ui.unit.Dp,
    onOpen: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // WorldCard 接入（精修方案 v1.3）：附件卡归属当前聊天角色，L3 身份脊用该角色 accentColor。
    WorldCard(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clickable(onClick = onOpen),
        ownerAccent = accentColor,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // 文件类型徽标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = file.extLabel,
                    style = type.label.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize   = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                    color = accentColor,
                )
            }

            // 文件名 + 大小
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = file.fileName,
                    style    = type.body,
                    color    = colors.textPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text  = file.sizeLabel,
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }

            // 打开按钮
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accentColor.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text  = "打开",
                    style = type.label,
                    color = accentColor,
                )
            }
        }
    }
}

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
private fun EvaluationCard(
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
            MarkdownText(
                markdown  = reportText,
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
                            modifier = Modifier.clickable { selectedStars = star },
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

// ─────────────────────────────────────────────────────────────
//  ChatInputBar — 底部输入栏
//  规范 §13：输入框圆角 28dp，发送按钮 accentColor 圆形 32dp
// ─────────────────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    accentColor: Color,
    bgColor: Color,
    isTyping: Boolean = false,
    onSend: () -> Unit,
    onImport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors       = ZaijianTheme.colors
    val type         = ZaijianTheme.typography
    val canSend      = value.trim().isNotEmpty() && !isTyping

    Row(
        modifier          = modifier
            .background(bgColor)
            .border(
                width  = 0.5.dp,
                color  = colors.borderSubtle,
                shape  = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
            )
            .padding(
                horizontal = Spacing.screenHorizontal,
                vertical   = Spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 输入框
        BasicTextField(
            value         = value,
            onValueChange = onValueChange,
            textStyle     = type.body.copy(color = colors.textPrimary),
            cursorBrush   = SolidColor(accentColor),
            modifier      = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    if (colors.isDark)
                        colors.bgElevated
                    else
                        colors.bgCard,
                )
                .padding(horizontal = Spacing.md, vertical = 10.dp),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text  = "说点什么…",
                        style = type.body,
                        color = colors.textDisabled,
                    )
                }
                innerTextField()
            },
        )

        // 导入文件按钮（视觉32dp，触摸区扩展至48dp）
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .wrapContentSize(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.textDisabled.copy(alpha = 0.1f))
                    .clickable { onImport() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.FolderOpen,
                    contentDescription = "导入文件",
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.width(Spacing.sm))

        // 发送按钮（视觉32dp，触摸区扩展至48dp）
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .wrapContentSize(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend)
                            Brush.linearGradient(
                                colors = listOf(
                                    Palette.Gold.copy(alpha = 0.90f),
                                    Palette.Gold.copy(alpha = 0.65f),
                                ),
                            )
                        else
                            Brush.linearGradient(
                                colors = listOf(
                                    colors.textDisabled.copy(alpha = 0.3f),
                                    colors.textDisabled.copy(alpha = 0.3f),
                                ),
                            )
                    )
                    .clickable(enabled = canSend) { onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Send,
                    contentDescription = "发送",
                    tint               = Color.White,
                    modifier           = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ChatSettingsSheet — 聊天设置底部面板（Phase 16）
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSettingsSheet(
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
                Icon(
                    imageVector        = Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "查看角色档案", style = type.body, color = colors.textPrimary)
                    Text(text = "记忆 · 人设 · 目标 · 关系", style = type.caption, color = colors.textSecondary)
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
                Icon(
                    imageVector        = Icons.Outlined.Wallpaper,
                    contentDescription = null,
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(20.dp),
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
                Icon(
                    imageVector        = Icons.Outlined.DeleteSweep,
                    contentDescription = null,
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(20.dp),
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
                    Icon(
                        imageVector        = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint               = colors.textSecondary,
                        modifier           = Modifier.size(20.dp),
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
                        imageVector        = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint               = colors.textDisabled,
                        modifier           = Modifier.size(18.dp),
                    )
                    DropdownMenu(
                        expanded         = projectDropdown,
                        onDismissRequest = { projectDropdown = false },
                        modifier         = Modifier.background(
                            if (colors.isDark) colors.bgCard else colors.bgElevated
                        ),
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
                                    imageVector = Icons.Outlined.Check,
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
                                        imageVector = Icons.Outlined.Check,
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

// ─────────────────────────────────────────────────────────────
//  Previews
// ─────────────────────────────────────────────────────────────

@Preview(
    name            = "ChatScreen · Dark",
    showBackground  = true,
    backgroundColor = 0xFF12131A,
    widthDp         = 390,
    heightDp        = 844,
)
@Composable
private fun PreviewChatDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) {
        ChatScreen(characterId = 1)
    }
}

private fun resolveFileName(context: Context, uri: Uri): String {
    var name = "imported_file"
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) name = c.getString(idx) ?: name
        }
    }
    return name
}

@Preview(
    name           = "ChatScreen · Dark",
    showBackground = true,
    widthDp        = 390,
    heightDp       = 844,
)
@Composable
private fun PreviewChatLight() {
    ZaijianTheme(appTheme = AppTheme.LIGHT) {
        ChatScreen(characterId = 6)
    }
}

// ─────────────────────────────────────────────────────────────
//  ModeChip — 工作 / 陪伴 模式切换按钮（Phase 30 方案一）
//
//  选中态：accentColor 填充 + 白色图标/文字
//  未选中：透明背景 + textSecondary 色
// ─────────────────────────────────────────────────────────────

@Composable
private fun ModeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val bg      = if (selected) accent else Color.Transparent
    val content = if (selected) Color.White else colors.textSecondary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = content,
            modifier           = Modifier.size(14.dp),
        )
        Text(
            text  = label,
            style = type.label,
            color = content,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  ChatRelCapsule — 顶栏关系状态胶囊（待办10）
// ─────────────────────────────────────────────────────────────

@Composable
private fun ChatRelCapsule(text: String, color: Color) {
    val colors = ZaijianTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text  = text,
            style = ZaijianTheme.typography.caption,
            color = color,
        )
    }
}
