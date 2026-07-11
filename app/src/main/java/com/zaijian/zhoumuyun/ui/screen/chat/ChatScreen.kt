package com.zaijian.zhoumuyun.ui.screen.chat

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.tooling.preview.Preview
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
import com.zaijian.zhoumuyun.domain.MoodType
import com.zaijian.zhoumuyun.util.TimeFormatUtils


// ─────────────────────────────────────────────────────────────
//  ChatScreen 主壳 — 拆分自原 ui/screen/ChatScreen.kt（v87 Phase 2）
//  子组件已迁移至同包下：ChatHeader.kt / ChatMessageBubble.kt /
//  ChatInputBar.kt / ChatSettingsSheet.kt / EvaluationCard.kt
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  数据模型
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  本地消息 ID 生成（时间戳用途）
// ─────────────────────────────────────────────────────────────
private const val TIMESTAMP_INTERVAL_MS = 30 * 60 * 1000L

private fun formatTimestamp(ms: Long): String = TimeFormatUtils.formatClockTime(ms)

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

    // 聊天背景图选择器：持久化 URI 权限，确保下次打开仍能读取图片。
    // v55 修复：选完图不再直接调 setChatBackground 原样铺满，而是先进
    // requestChatBackgroundCrop 触发裁剪弹窗——此前这里完全没有裁剪
    // 环节，是"背景图无法拖动缩放"问题的根因。
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
            chatViewModel.requestChatBackgroundCrop(uri.toString())
        } catch (e: Exception) {
            com.zaijian.zhoumuyun.util.ZLog.w("ChatScreen", "背景图设置失败: uri=$uri", e)
            scope.launch { snackbarHostState.showSnackbar("背景图设置失败，请重试") }
        }
    }

    // 聊天背景图裁剪弹窗：pendingBackgroundCropUri 非空时显示，用户可
    // 拖拽平移 + 双指缩放调整取景范围，确认后一次性把 URI + 偏移/缩放
    // 写入持久化存储（见 ChatViewModel.confirmChatBackgroundCrop）。
    // v55 修复：这是本次新增的核心环节——此前选完图直接显示，完全没有
    // 用户可交互的裁剪步骤。
    uiState.pendingBackgroundCropUri?.let { pendingUriString ->
        com.zaijian.zhoumuyun.ui.component.AvatarCropDialog(
            uri       = android.net.Uri.parse(pendingUriString),
            shape     = com.zaijian.zhoumuyun.ui.component.CropShape.FULL_SCREEN,
            onConfirm = { params ->
                chatViewModel.confirmChatBackgroundCrop(
                    uri     = pendingUriString,
                    offsetX = params.normalizedOffsetX,
                    offsetY = params.normalizedOffsetY,
                    scale   = params.scale,
                )
            },
            onDismiss = { chatViewModel.cancelChatBackgroundCrop() },
        )
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
        // v55 修复：叠加用户在裁剪弹窗中拖拽/缩放产出的 offset/scale，
        // 不再是固定的居中 Crop。
        //
        // v57 修复（与 BreathingAvatar.kt 的 v56 修复保持同一坐标系）：
        // 此前这里用 AsyncImage 走 fillMaxSize()+Crop 铺满容器、再叠加
        // safeCropScale 的写法，隐含假设「图层已经等于容器大小，唯一的
        // 移动余量来自额外放大」——但 AvatarCropDialog 保存
        // offsetX/offsetY 时用的坐标系是「相对于图片按原始长宽比覆盖
        // 裁剪框的基准尺寸（baseWidthPx/baseHeightPx，可能远大于容器，
        // 取决于原图横竖比）」，两套坐标系对不上：保存的 offset 在弹窗里
        // 看着是「小幅拖动」，套到这里的简化公式却会被错误放大数倍，
        // 表现为背景图被过度放大、四周内容顶出屏幕外，只剩选中区域中间
        // 一小块。这正是 BreathingAvatar 那次 v56 修复过的同一个根因，
        // 之前只改了头像没同步改这里。
        //
        // 修复：改用跟 AvatarCropDialog／BreathingAvatar 完全相同的
        // 「较大边覆盖容器」基准尺寸公式 + rememberAsyncImagePainter 拿
        // 真实 intrinsicSize，两端坐标系统一，保存的 offset 和最终渲染
        // 效果必然一致。
        if (backgroundImageUri != null) {
            val bgPainter = coil.compose.rememberAsyncImagePainter(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(backgroundImageUri)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build(),
            )
            val bgPainterState = bgPainter.state
            val bgIntrinsicSize = (bgPainterState as? coil.compose.AsyncImagePainter.State.Success)
                ?.painter?.intrinsicSize
            val bgImageAspect = if (bgIntrinsicSize != null &&
                bgIntrinsicSize.width > 0f && bgIntrinsicSize.height > 0f
            ) {
                bgIntrinsicSize.width / bgIntrinsicSize.height
            } else {
                1f
            }
            val bgOffsetX = uiState.backgroundOffsetX
            val bgOffsetY = uiState.backgroundOffsetY
            val bgScale = uiState.backgroundScale

            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val frameHalfWPx = with(density) { (maxWidth / 2f).toPx() }
                val frameHalfHPx = with(density) { (maxHeight / 2f).toPx() }
                val frameAspect = if (frameHalfHPx > 0f) frameHalfWPx / frameHalfHPx else 1f

                val bgBaseWidthPx: Float
                val bgBaseHeightPx: Float
                if (bgImageAspect > frameAspect) {
                    bgBaseHeightPx = frameHalfHPx * 2f
                    bgBaseWidthPx  = bgBaseHeightPx * bgImageAspect
                } else {
                    bgBaseWidthPx  = frameHalfWPx * 2f
                    bgBaseHeightPx = bgBaseWidthPx / bgImageAspect
                }

                androidx.compose.foundation.Image(
                    painter            = bgPainter,
                    contentDescription = null,
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier
                        .size(
                            width  = with(density) { bgBaseWidthPx.toDp() },
                            height = with(density) { bgBaseHeightPx.toDp() },
                        )
                        .graphicsLayer {
                            scaleX       = bgScale
                            scaleY       = bgScale
                            translationX = bgOffsetX * frameHalfWPx
                            translationY = bgOffsetY * frameHalfHPx
                        },
                    alpha = if (bgPainterState is coil.compose.AsyncImagePainter.State.Error) 0f else 1f,
                )
            }
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

