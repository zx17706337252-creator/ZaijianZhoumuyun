package com.zaijian.zhoumuyun.ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.produceState
import com.zaijian.zhoumuyun.data.agent.personalVaultDir
import com.zaijian.zhoumuyun.data.agent.projectVaultDir
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString

import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.component.FertileWindowConsentDialog
import com.zaijian.zhoumuyun.ui.design.AiStatePill
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.ChatViewModel

import com.zaijian.zhoumuyun.ui.viewmodel.PresenceViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.ProjectViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableIntStateOf

import com.zaijian.zhoumuyun.ZaijianApp
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.util.safeAnimateScrollToItem


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

private fun formatTimestamp(ms: Long): String = TimeFormatUtils.formatTime(ms)

/**
 * Fix-ChatVmScope（退出私聊生成中断/气泡消失的根因修复）：
 * ChatViewModel 类头注释自称"应用内单例"，但此前 `chatViewModel: ChatViewModel = viewModel()`
 * 解析到的是 NavBackStackEntry 的 ViewModelStore——退出私聊页（popBackStack）该 entry
 * 立即销毁，ViewModel.onCleared() → viewModelScope 整体取消 → 正在生成的 replyJob 被杀、
 * 流式气泡消失（尤其 excel_gen/pptx_gen 这类耗时文件生成必死）。
 *
 * 修复：把 ViewModel 挂到 Activity 的 ViewModelStore 上，作用域与 App 同寿，
 * 退出/重进私聊页不再取消生成任务；重进时 ChatSessionDelegate.init() 的
 * "同角色 + replyJob 仍活跃" 分支会保住流式气泡状态，消息落库后自动出现在列表里。
 *
 * 从 Context 链上安全解析宿主 Activity（Compose 下 LocalContext 可能被
 * ContextThemeWrapper 包若干层，不能强转）。
 */
private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper    -> baseContext.findComponentActivity()
    else                 -> null
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
    // 批次4新增：跳转到该角色的个人日程页。默认空实现保持向后兼容。
    // 与 onNavigateToProfile 同款签名 (Int) -> Unit，由 AppNavigation 透传
    // 实际导航逻辑（navigate 到 AppRoute.PersonalSchedule.createRoute(charId)）。
    onNavigateToSchedule: (Int) -> Unit = {},
    // v147（文件保险库改造）：跳转到文件库（FileVaultScreen）。与 onNavigateToSchedule
    // 同款签名 (Int) -> Unit，由 AppNavigation 透传 navigate 到 AppRoute.FileVault。
    onNavigateToVault: (Int) -> Unit = {},
    // 角色间私聊入口：跳转到私聊管理面板（PrivateChatScreen）。签名为
    // () -> Unit（不带 characterId）——与 onNavigateToVault/onNavigateToSchedule
    // 不同，该面板本身管理全部角色对，不是单一角色专属页面。
    onNavigateToPrivateChat: () -> Unit = {},
    // v1.48：跳转到统一文件预览编辑页（FilePreviewEditorScreen）。
    // 参数是文件绝对路径，由 AppNavigation 透传 navigate 到 AppRoute.FilePreview。
    onNavigateToFilePreview: (String) -> Unit = {},
    // v1.48：从内存内容进入预览页（暂存模式）
    onNavigateToFilePreviewMemory: (String) -> Unit = {},
    presenceViewModel: PresenceViewModel = viewModel(),
    projectViewModel: ProjectViewModel = viewModel(),
    // Bug1修复：ChatViewModel 只有 Application 参数（AndroidViewModel子类），
    // 标准 viewModel() 工厂可自动处理，无需自定义工厂。
    // 原来的 ChatViewModelFactory 传入新实例会干扰 Compose 的 ViewModel 缓存，
    // 导致 setChatMode() 更新的是游离实例，UI 读取的是另一个实例，状态无法同步。
    //
    // Fix-ChatVmScope：viewModelStoreOwner 从默认的 NavBackStackEntry 提升到宿主
    // Activity——退出私聊页不再销毁 ViewModel，生成中的回复/文件在后台继续跑完
    // （见文件上方 findComponentActivity 注释）。AndroidViewModel 的标准工厂
    // （AndroidViewModelFactory）对 Activity owner 同样适用，无需自定义工厂。
    // @Preview 设计时环境没有宿主 Activity，回退到默认 owner（与旧行为一致）。
    chatViewModel: ChatViewModel = run {
        val activity = LocalContext.current.findComponentActivity()
        if (activity != null) viewModel(viewModelStoreOwner = activity) else viewModel()
    },
) {
    val colors   = ZaijianTheme.colors
    val type     = ZaijianTheme.typography
    val scope    = rememberCoroutineScope()
    // 2.1 对话内容复制：长按气泡 → 写入系统剪贴板 → 复用现有 snackbarHostState
    // 反馈"已复制"，走 app 自己的羊皮纸风格 Snackbar，不用系统默认 Toast。
    val clipboardManager = LocalClipboardManager.current
    // Fix-ChatHeader-StatusBar：ChatHeader 外层 Box 自带 .statusBarsPadding()，
    // 顶栏真实占用高度 = 状态栏高度 + Row 的 Spacing.topBarHeight，
    // 而不是单独的 Spacing.topBarHeight。下方 LazyColumn 的 contentPadding.top
    // 与 Header 占位层都需要把状态栏高度一并算进去，否则顶栏会多盖住
    // 状态栏高度那一截内容（例如第一条消息的时间分隔线）。
    val statusBarHeightDp = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
    // W9问题3修复：launchSingleTop=true 复用同一 Composable 实例时，无 key 的
    // remember 状态不会随 characterId 变化重建。listState 尤其关键——切换角色
    // 后消息列表会在旧角色的滚动位置渲染新角色消息，可能停在中间看不到最新消息。
    // 与下方 emotionCardVisible/showChatSettings 已用的 remember(characterId) 策略对齐。
    val snackbarHostState = remember(characterId) { SnackbarHostState() }
    val listState = remember(characterId) { LazyListState() }

    // 初始化 ChatViewModel（绑定角色 ID）
    LaunchedEffect(characterId) {
        chatViewModel.init(characterId)
    }

    // Fix-ChatVmScope 连带修复：ViewModel 提升为 Activity 作用域后，onCleared()
    // 不再随"退出私聊页"触发，原先在 onCleared 里做的"清除前台角色标记"挪到这里——
    // composable 离开组合（退出聊天页/切换角色）时清理，语义与原来一致。
    DisposableEffect(characterId) {
        onDispose {
            // B1审查序号2修复：check-then-clear改用原子 compareAndSet，见 PresenceEngine 注释。
            com.zaijian.zhoumuyun.data.AppContainer.instance.exitChatScreen(characterId)
        }
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
    // 女儿）。情况①直接 return 等下一帧重组，情况②需要展示错误提示。
    // W14 修复：增加异常场景的错误提示，避免用户看到完全空白页。
    val character = uiState.character ?: run {
        // 情况①：正在加载中，等下一帧重组
        if (uiState.isTyping || uiState.messages.isNotEmpty()) {
            // 已经在聊天中（消息列表有内容），突然 character 变 null 是异常，
            // 但大概率是临时状态，先 return 等重组
            return
        }
        // 情况②：初始化就一直没加载到角色，展示错误状态
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = ZaijianTheme.colors.accent)
                Spacer(Modifier.height(12.dp))
                Text(
                    "正在加载角色信息…",
                    color = ZaijianTheme.colors.textSecondary,
                    style = type.body, // 14sp 恰好等于 body，改用排印系统接入
                )
            }
        }
        return
    }

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
    val attachFilesTogether = uiState.attachFilesTogether
    // P3-39 修复：注释乱码，恢复正确文字。
    // UI M3 修复：心情直接读 uiState.currentMood，
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
    // P1-1+22 修复：情绪卡初始值与跨角色状态泄漏
    // 根因：Navigation Compose 使用 launchSingleTop=true，切换角色时 ChatScreen composable
    // 可能被复用而非重建，remember {} 无 key 的状态不会自动重置。
    // 修复：(1) remember(characterId) 保证切换角色时重置所有本地UI状态
    //       (2) LaunchedEffect 保证 presence 异步到达后情绪卡能响应式显示
    var emotionCardVisible by remember(characterId) { mutableStateOf(false) }
    // Phase 16：聊天设置底部面板
    var showChatSettings by remember(characterId) { mutableStateOf(false) }

    // 当 presence.activityHint 从 null 变为非空时，自动显示情绪卡
    LaunchedEffect(characterId, presence?.activityHint) {
        if (presence?.activityHint != null) {
            emotionCardVisible = true
        }
    }

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
            // Fix-FileImportCard：探测真实 MIME 类型（优先问 ContentResolver，
            // 拿不到再按扩展名猜），连同实际写盘后的文件大小一起传下去，
            // 供聊天气泡渲染文件卡片（图标/大小/打开预览）用。
            val mimeType = ctx2.contentResolver.getType(uri)
                ?: android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(safeName.substringAfterLast('.', "").lowercase())
                ?: "*/*"
            chatViewModel.notifyFileImported(safeName, dest.absolutePath, mimeType, dest.length())
        } catch (e: Throwable) {
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
        // v1.48：先尝试应用内预览，不支持再跳外部应用
        val ext = ef.fileName.substringAfterLast('.', "").lowercase()
        if (com.zaijian.zhoumuyun.data.agent.FilePreviewParser.isPreviewable(ext)) {
            // 应用内预览
            onNavigateToFilePreview(ef.absolutePath)
        } else {
            // 兜底：FileProvider + ACTION_VIEW（原逻辑）
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
                } else {
                    android.widget.Toast.makeText(ctx2, "文件不存在：${ef.fileName}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                com.zaijian.zhoumuyun.util.ZLog.e("ChatScreen", "打开文件失败：${ef.absolutePath}", e)
                android.widget.Toast.makeText(ctx2, "无法打开文件：${e.message?.take(60)}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
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
        } catch (e: Throwable) {
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
    // P1 崩溃修复：改用 safeAnimateScrollToItem——即便这里的 totalItems
    // 与 LazyColumn 实际已 measure 的项数出现竞态（比如同一轮里连续
    // 插入多条文件卡片消息、LaunchedEffect 被连续取消重启），也不会再
    // 直接把可能越界的下标透传给 animateScrollToItem 导致未捕获崩溃。
    LaunchedEffect(messages.size, isTyping, pendingEvaluationSessionId) {
        val totalItems = messages.size +
            (if (isTyping) 1 else 0) +
            (if (pendingEvaluationSessionId != null) 1 else 0)
        if (totalItems > 0) {
            listState.safeAnimateScrollToItem(totalItems - 1, tag = "ChatScreen")
        }
    }
    // H1 修复：流式滚动改用 snapshotFlow，在协程里监听 streamingContent 长度变化并滚动，
    // 完全不在 Compose 重组作用域内读取 streamingContent，ChatScreen 顶层不再随 token 重组。
    // UI M14 修复保留：scrollToItem（无动画）避免动画积压。
    // P1-11-1 修复：原 snapshotFlow { chatViewModel.uiState.value.streamingContent } 读
    // StateFlow.value，StateFlow 不是 Compose State，snapshotFlow 只在首次快照时发射一次，
    // 后续 streamingContent 更新不触发重发。修复：改为读已通过 collectAsState() 绑定的
    // Compose State 变量 uiState，snapshotFlow 能正确感知每次重组产生的新快照值。
    // P1-3 修复：snapshotFlow 改为收集独立的 streamingContent（不再读 uiState.streamingContent）。
    // 先通过 collectAsStateWithLifecycle 转为 Compose State，snapshotFlow 才能正确感知每次变化。
    //
    // Task-2 说明：合并输出模式下 _streamingContent 在流式期间保持 null（不再逐 token 更新），
    // 此 snapshotFlow 不会在流式期间触发（len 恒为 0）。自动滚动改由上方
    // LaunchedEffect(messages.size, isTyping) 覆盖——isTyping 变 true 时滚动到占位气泡，
    // messages.size 增加时滚动到新消息。此代码保留不动（无害），若未来恢复打字机模式可复用。
    val streamingContentForScroll by chatViewModel.streamingContent.collectAsStateWithLifecycle()
    LaunchedEffect(listState) {
        snapshotFlow { streamingContentForScroll?.length ?: 0 }
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

    // P2-9 修复：动态测量输入栏高度，替代硬编码 88.dp
    val density = LocalDensity.current
    var inputBarHeightPx by remember { mutableIntStateOf(0) }
    val inputBarHeightDp = with(density) { inputBarHeightPx.toDp() }
    // Fix-ChatHeaderOverlap（批次修复）：顶栏真实高度此前用固定公式估算
    // （topBarHeight + 关系胶囊固定22dp），胶囊数量一多、FlowRow 换行到
    // 第二行时，估算值就比顶栏真实渲染高度矮一截，下方消息列表的顶部
    // padding 不够，顶栏会盖住第一条消息（"上边框上下拉长/文字被遮"）。
    // 改用与底部输入栏相同的实测方案：onSizeChanged 拿到 ChatHeader
    // 的真实像素高度，换算成 dp 直接用于 contentPadding，胶囊无论换行
    // 多少行都不会再和消息列表重叠。首帧未测量到时用旧公式兜底估算。
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerHeightDp = with(density) { headerHeightPx.toDp() }

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
                            Palette.ScreenScrimDark
                        else
                            Palette.ScreenScrimLight
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
                // Fix-ChatHeaderOverlap（批次修复）：顶部 padding 直接用 ChatHeader 的
                // 实测高度（headerHeightPx，含状态栏，无论关系信息展示与否都准确），
                // 不再用"topBarHeight + 固定22dp"这种假设胶囊单独成行的公式。
                // 首帧 headerHeightPx 尚未回调（值为0）时，用旧公式估算兜底避免闪烁。
                //
                // 顶栏压缩v1 连带修复：这个 22dp 是为旧版"关系胶囊 FlowRow 单独占一整行"
                // 设计的补偿值——旧顶栏有关系信息时会多出约22dp高度。新顶栏（单行合一
                // 方案）已经把关系信息合并进角色名同一行展示，不再有这条额外的胶囊行，
                // 顶栏内容区是固定的 42dp（heightIn(min=42.dp)）。继续按旧公式估算会在
                // 有关系信息时把首帧 padding 多估约 24dp（导致消息列表顶部短暂多出一截
                // 空白，下一帧 onSizeChanged 回调后才收回）——只影响首帧，不是持续性
                // 布局错位，但既然顶栏高度已经不再随关系信息变化，公式也应该同步去掉
                // 这个不再成立的条件分支。
                top    = (if (headerHeightPx > 0) headerHeightDp
                          else statusBarHeightDp + 42.dp) +
                         (if (emotionCardVisible && presence?.activityHint != null) 40.dp else 0.dp) +
                         Spacing.md,
                // P2 修复：底部 padding 改为动态测量的输入栏实际高度（含安全区），
                // 避免字体缩放/输入栏内容变化时硬编码值与实际高度不一致导致最后一条消息被遮挡。
                // 首帧 inputBarHeightPx 为 0（onSizeChanged 尚未回调），用 80.dp 兜底避免闪烁。
                bottom = (if (inputBarHeightPx > 0) inputBarHeightDp else 80.dp) + Spacing.md,
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
                        onCopyMessage = { text ->
                            clipboardManager.setText(AnnotatedString(text))
                            scope.launch {
                                snackbarHostState.showSnackbar("已复制", duration = SnackbarDuration.Short)
                            }
                        },
                        // v1.48：气泡点击全屏查看文本
                        onOpenFullText = { text, isMarkdown ->
                            val tempKey = com.zaijian.zhoumuyun.ui.screen.filepreview.PreviewMemoryCache.put(
                                com.zaijian.zhoumuyun.ui.screen.filepreview.PreviewMemoryCache.MemoryItem.MemoryText(text, isMarkdown),
                            )
                            onNavigateToFilePreviewMemory(tempKey)
                        },
                        // v1.48：表格点击全屏查看
                        onOpenTable = { columns, rows ->
                            val tempKey = com.zaijian.zhoumuyun.ui.screen.filepreview.PreviewMemoryCache.put(
                                com.zaijian.zhoumuyun.ui.screen.filepreview.PreviewMemoryCache.MemoryItem.MemoryTable(columns, rows),
                            )
                            onNavigateToFilePreviewMemory(tempKey)
                        },
                        avatarCropOffsetX = character.avatarCropCircleOffsetX,
                        avatarCropOffsetY = character.avatarCropCircleOffsetY,
                        avatarCropScale   = character.avatarCropCircleScale,
                        attachFilesTogether = attachFilesTogether,
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
                        avatarCropOffsetX = character.avatarCropCircleOffsetX,
                        avatarCropOffsetY = character.avatarCropCircleOffsetY,
                        avatarCropScale   = character.avatarCropCircleScale,
                    )
                }

                // Phase 13：工具执行提示行
                // streamingHint 非 null 时在打字机气泡下方显示一行小提示。
                // Task-2：ToolDone 不再置 null，而是恢复为 "正在生成回复…" 通用提示，
                // 避免工具完成到流式结束之间的空窗期用户看到无提示的 "…" 以为卡住了。
                // streamingHint 仅在流式结束（onComplete/finally）时置 null。
                if (streamingHint != null) {
                    item(key = "tool_hint") {
                        ToolHintRow(
                            hint = streamingHint,
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

            // W5-001 修复：消息列表为空时显示空态提示，与加载态做视觉区分
            if (messages.isEmpty() && !isTyping) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "开始你们的第一次对话吧",
                            style = ZaijianTheme.typography.body,
                            color = ZaijianTheme.colors.textDisabled,
                        )
                    }
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
            // Fix-ChatHeaderOverlap（批次修复）：同上，改用实测的 headerHeightPx，
            // 不再用 statusBarHeightDp + Spacing.topBarHeight 这个假设顶栏只有
            // 一行内容的固定值——否则关系胶囊换行变多行时，情绪卡占位层高度不够，
            // 情绪卡会被顶栏盖住一截。首帧未测量到时同样用旧公式兜底。
            Box(
                modifier = Modifier.height(
                    if (headerHeightPx > 0) headerHeightDp
                    else statusBarHeightDp + Spacing.topBarHeight
                )
            )

            // 情绪卡
            AnimatedVisibility(
                visible = emotionCardVisible && presence?.activityHint != null,
                enter   = fadeIn(tween(AnimDuration.fast)) +
                          slideInVertically(tween(AnimDuration.fast)) { -it },
                // P3-19 修复：为情绪卡添加 exit 动画，消失时 fadeOut + slideOut
                exit    = fadeOut(tween(AnimDuration.fast)) +
                          slideOutVertically(tween(AnimDuration.fast)) { -it },
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

            // ── AI 状态胶囊（帧21 AiStatePill）：流式生成/工具执行期间在顶栏下沿
            // 显示当前 AI 状态，由 ChatUiState.streamingHint 驱动（"正在生成回复…"
            // / 工具特定提示如"正在生成PDF…"）。streamingHint 生命周期与 isTyping
            // 一致（流式开始设值、结束清 null），二者同时成立才显示。──
            AnimatedVisibility(
                visible = isTyping && streamingHint != null,
                enter = fadeIn(tween(AnimDuration.fast)) +
                    slideInVertically(tween(AnimDuration.fast)) { -it },
                exit = fadeOut(tween(AnimDuration.fast)) +
                    slideOutVertically(tween(AnimDuration.fast)) { -it },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    AiStatePill(text = streamingHint.orEmpty())
                }
            }
        }

        // ── [2] 顶部栏（毛玻璃，56dp）────────────────────────
        // v152：ChatHeader 去掉了头像展示，avatarUrl/breathColor/statusType/
        // avatarCrop* 不再需要传入；原头像的"点进详情页"入口现在挂在角色名上。
        ChatHeader(
            name         = character.name,
            accentColor  = character.accentColor,
            headerBg     = headerBg,
            onBack       = onBack,
            onProfileClick = { onNavigateToProfile(characterId) },
            onMoreClick  = { showChatSettings = true },
            // 待办10：关系状态
            relStageLabel      = headerStageLabel,
            relMood            = headerMood,
            relSuppressionHint = headerSuppressionLabel,
            chatMode      = uiState.chatMode,
            onChatModeChange = { chatViewModel.setChatMode(it) },
            modifier     = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .onSizeChanged { headerHeightPx = it.height },
        )

        // v147：计算当前角色可见范围内的文件总数，供 ChatSettingsSheet「文件」条目
        // 副标题显示。produceState 在 characterId 变化时重算；IO 切后台线程。
        val vaultCtx = androidx.compose.ui.platform.LocalContext.current
        val vaultFileCount by produceState(initialValue = 0, characterId) {
            value = withContext(Dispatchers.IO) {
                val personal = personalVaultDir(vaultCtx, characterId)
                val project = projectVaultDir(vaultCtx)
                countFilesUnder(personal) + countFilesUnder(project)
            }
        }

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
                // 2.4：导出本次对话，走 ChatViewModel.exportConversation()——
                // 成功后文件卡片会随 loadMessages() 刷新自动出现在消息流里，
                // 不需要在这里额外弹 Snackbar；失败走 uiState.error 已有通道。
                onExportConversation = { chatViewModel.exportConversation() },
                // 批次4：透传日程入口回调，与 onNavigateToDetail 同款范式——
                // ChatSettingsSheet 内部已负责 onDismiss()，这里只传导航动作。
                onNavigateToSchedule = { onNavigateToSchedule(characterId) },
                // v147：透传文件库入口回调，与 onNavigateToSchedule 同款范式。
                onNavigateToVault    = { onNavigateToVault(characterId) },
                vaultFileCount       = vaultFileCount,
                // 角色间私聊入口：透传给 ChatSettingsSheet，与 onNavigateToVault
                // 同款范式，只是不带 characterId 参数。
                onNavigateToPrivateChat = onNavigateToPrivateChat,
                // 文档发送方式：默认一起发（true），底部面板内切换即时生效
                // （ChatViewModel.setAttachFilesTogether 已做乐观更新 + 后台持久化）。
                attachFilesTogether  = attachFilesTogether,
                onAttachFilesTogetherChange = { chatViewModel.setAttachFilesTogether(it) },
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
                    // 专项审查报告问题12：sendMessage 返回是否真正进入发送流程。
                    // 被门控拦截（无 provider / 工具执行中）时返回 false，此时
                    // 不清空输入框，避免用户长文本在 error 后丢失且不可恢复。
                    if (chatViewModel.sendMessage(text)) {
                        inputText = ""
                    }
                    // P1 崩溃修复：删除了这里原本的
                    // `scope.launch { listState.animateScrollToItem(messages.size) }`。
                    // sendMessage() 是异步的，此刻的 messages 仍是发送前的旧列表，
                    // messages.size 相当于"新消息应该在的下标"，比 LazyColumn
                    // 当前实际项数多 1——直接拿去 animateScrollToItem 必然越界，
                    // 命中越界的那一刻若正好落在 subcompose 测量阶段，就会抛出
                    // 未捕获异常导致整个 App 闪退（agent_log.txt CrashHandler
                    // 记录的崩溃调用链与此完全吻合）。
                    // 上方第 464 行的 LaunchedEffect(messages.size, isTyping, ...)
                    // 已经在响应式地做"新消息自动滚到底部"，一旦 sendMessage()
                    // 真正把新消息写入并触发重组，会自然重新触发该 LaunchedEffect
                    // 并安全滚动，这里的手动调用纯属多余且有竞态风险，直接去掉。
                }
            },
            onImport    = { fileImportLauncher.launch(arrayOf("*/*")) },
            // 修复：navigationBarsPadding() 挪到 ChatInputBar 内部（背景/边框之后），
            // 这里不再重复加一层，onSizeChanged 也移到最终位置，才能测到包含导航栏
            // 安全区在内的完整高度。
            modifier    = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .imePadding()
                .onSizeChanged { inputBarHeightPx = it.height },
        )

        // ── [5] 错误 Snackbar ─────────────────────────────────
        SnackbarHost(
            hostState = snackbarHostState,
            modifier  = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                // P2-9 修复：底部偏移由硬编码 88.dp 改为动态测量输入栏高度
                .padding(bottom = inputBarHeightDp),
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

// v147：递归统计目录下文件数（含子目录），供 ChatSettingsSheet「文件」条目副标题角标用。
private fun countFilesUnder(dir: java.io.File): Int {
    if (!dir.exists()) return 0
    val files = dir.listFiles { f -> f.isFile }?.size ?: 0
    val subDirs: List<java.io.File> = dir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
    return files + subDirs.sumOf { countFilesUnder(it) }
}

