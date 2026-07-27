package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.component.ContentBlockAdapter
import com.zaijian.zhoumuyun.ui.component.ContentBlockRenderer
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.BubbleDimen
import com.zaijian.zhoumuyun.ui.theme.DotSize
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.theme.presenceGlow
import com.zaijian.zhoumuyun.ui.viewmodel.BotGenerationStatus
import com.zaijian.zhoumuyun.ui.viewmodel.RoundtableMessage
import com.zaijian.zhoumuyun.ui.viewmodel.RoundtableViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.ScheduleMode
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import com.zaijian.zhoumuyun.ui.design.AppIcons


// ─────────────────────────────────────────────────────────────
//  RoundtableScreen — 圆桌聊天页
//
//  Phase 14 后半升级：
//  ① 圆桌设置面板（ModalBottomSheet）：动态成员管理 + 调度模式切换
//  ② DetailTopBar 接入设置按钮（原 RoundtableHeader，窗口4统一）
//
//  设计方案 §6 + §9.3（圆桌模式）：
//
//  层级结构（从后到前）：
//    [0] bgBase 背景
//    [1] 消息列表（LazyColumn）
//    [2] Bot 切换栏（粘性水平滚动，顶部 Header 下方）
//    [3] 顶部栏（毛玻璃 56dp）
//    [4] 序贯进度条（可选，Bot 生成时显示）
//    [5] 底部输入栏（毛玻璃，imePadding）
//    [6] ModalBottomSheet — 圆桌设置面板（Phase 14 后半）
//
//  序贯感知特性：
//    · Bot 气泡左侧 4dp 色条为 Bot 主题色（区分多人）
//    · 引用气泡（↩）显示被引用的 Bot 名和内容片段
//    · 序贯进度指示器：顾澜✓ 苏柯⠿ 季杭○
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundtableScreen(
    characterIds: List<Int> = DefaultCharacters.take(9).map { it.id },
    onBack: () -> Unit = {},
    // v1.48 应用内预览编辑接入圆桌（与 ChatScreen 同构）：
    // - onNavigateToFilePreview：文件卡片点开支持类型（xlsx/docx/csv/...）时走应用内预览
    // - onNavigateToFilePreviewMemory：表格气泡（table_export payload，未落盘）点开全屏查看
    onNavigateToFilePreview: (String) -> Unit = {},
    onNavigateToFilePreviewMemory: (String) -> Unit = {},
    viewModel: RoundtableViewModel = viewModel(),
) {
    val colors   = ZaijianTheme.colors
    val type     = ZaijianTheme.typography
    val scope    = rememberCoroutineScope()
    // 2.1 对话内容复制（圆桌场景补齐，与私聊 ChatScreen 同一套交互）：
    // 长按气泡 → 写入系统剪贴板 → 复用现有 snackbar 反馈"已复制"。
    val clipboardManager = LocalClipboardManager.current
    // W9问题2+4修复：launchSingleTop=true 导致同一 NavBackStackEntry 复用时，
    // characterIds 变化但 Composable 实例不重建。用排序后拼接的稳定字符串作为
    // key（而非直接用 characterIds 这个 List 实例——每次传入的新 List 即使内容
    // 相同也会被判定为"变化"，反而失去了 remember 的意义），驱动 snackbar/
    // listState 重新创建、LaunchedEffect 重新触发。
    val memberKey = characterIds.sorted().joinToString("_")
    val snackbar = remember(memberKey) { SnackbarHostState() }
    val listState = remember(memberKey) { LazyListState() }

    // P2 修复：动态测量输入栏实际高度，替代硬编码 80.dp，
    // 与 ChatScreen 的 inputBarHeightPx 方案保持一致。
    val screenDensity = LocalDensity.current
    var inputBarHeightPx by remember { mutableIntStateOf(0) }
    val inputBarHeightDp = with(screenDensity) { inputBarHeightPx.toDp() }

    // W9问题2修复：原先用 Unit 作为 key，launchSingleTop=true 复用同一
    // NavBackStackEntry 时 characterIds 变化不会重新触发本 Effect，导致
    // 切换圆桌成员后 setMembers() 永远不会被第二次调用。改用 memberKey，
    // 成员列表变化时正确重新触发；setMembers() 内部已有
    // newRoundtableId != currentRoundtableId 检查，防止重复加载消息。
    LaunchedEffect(memberKey) {
        viewModel.setMembers(characterIds)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 自发互动：isSpontaneousEnabled 变化时响应式启动/停止空闲计时。
    // 修复 UI M2：原 DisposableEffect(Unit) 在 setup 块内读取 uiState，
    // 由于 DisposableEffect 的 setup 块只在首次进入时执行，
    // 若 isSpontaneousEnabled 初始值为 false（数据还在加载）会错误跳过 startIdleWatch。
    // 改为 LaunchedEffect(isSpontaneousEnabled)：每次开关变化都重新执行，
    // 离开屏幕时由 ViewModel 的 onCleared 保证 stopIdleWatch。
    val isSpontaneousEnabled = uiState.isSpontaneousEnabled
    LaunchedEffect(isSpontaneousEnabled) {
        if (isSpontaneousEnabled) viewModel.startIdleWatch()
        else viewModel.stopIdleWatch()
    }

    val members = uiState.activeMembers
    val memberMap = remember(members) { members.associateBy { it.id } }

    // UI S4 修复：用户正在输入的文字在进程死亡后应能恢复，改用 rememberSaveable
    var inputText by rememberSaveable { mutableStateOf("") }
    // Step 5：@ 候选弹窗——记录触发时的 '@' 在文本中的位置，null = 未触发
    var atTriggerIndex by remember { mutableStateOf<Int?>(null) }
    // D-2 圆桌 ContentBlock 入口：心迹面板开关
    var showActivityPanel by remember { mutableStateOf(false) }

    // 输入框内容变化时，判断当前是否处于"刚输入 @ 后，还没打完名字/没打空格"的片段中。
    // 简化策略（纯文本协议，不依赖真实光标位置）：只看输入框末尾——
    // 若末尾连续的非空白片段以 '@' 开头，视为正在输入候选名，弹窗显示并按片段过滤候选；
    // 一旦出现空白或片段不再以 '@' 开头，弹窗收起。
    val atQuery: String? = remember(inputText) {
        val lastAt = inputText.lastIndexOf('@')
        if (lastAt == -1) return@remember null
        val tail = inputText.substring(lastAt + 1)
        if (tail.any { it.isWhitespace() }) null else tail
    }
    LaunchedEffect(atQuery) {
        atTriggerIndex = if (atQuery != null) inputText.lastIndexOf('@') else null
    }

    // 新消息/轮次变化时滚到底部（带动画，触发频率低）
    LaunchedEffect(uiState.messages.size, uiState.waitingForUser) {
        val size = uiState.messages.size
        if (size > 0) {
            listState.animateScrollToItem(size - 1)
        }
    }
    // UI M14 修复：流式输出期间 messages.size 不变但末条 content 持续增长，
    // 原实现用 lastMsgLen 作 LaunchedEffect key，每个 token 触发一次 animateScrollToItem，
    // 动画队列积压导致长回复卡顿。
    // 改为 snapshotFlow 在协程内监听内容长度，流式期间用无动画的 scrollToItem，
    // 与 ChatScreen 保持一致。
    // P1-11-1 修复：原 snapshotFlow { viewModel.uiState.value.messages... } 读 StateFlow.value，
    // 快照系统感知不到 StateFlow 变化，只发射一次。改为读 collectAsState() 产生的
    // Compose State 变量 uiState，每次重组时快照值变化，snapshotFlow 正确持续发射。
    LaunchedEffect(listState) {
        snapshotFlow {
            uiState.messages.lastOrNull()?.content?.length ?: 0
        }.collect { len ->
            if (len > 0) {
                val size = listState.layoutInfo.totalItemsCount
                if (size > 0) listState.scrollToItem(size - 1)
            }
        }
    }

    // 错误提示
    LaunchedEffect(uiState.error) {
        val err = uiState.error ?: return@LaunchedEffect
        snackbar.showSnackbar(err)
        viewModel.clearError()
    }

    LaunchedEffect(uiState.isApiKeyMissing) {
        if (uiState.isApiKeyMissing) {
            snackbar.showSnackbar("请先在「我」→「AI 配置」中填写 API Key")
            viewModel.clearApiKeyMissingFlag()
        }
    }

    val headerBg = if (colors.isDark)
        colors.bgBase.copy(alpha = GlassOpacity.topBarDark)
    else
        colors.bgBase.copy(alpha = GlassOpacity.topBarLight)

    val inputBg = if (colors.isDark)
        colors.bgCard.copy(alpha = 0.92f)
    else
        colors.bgBase.copy(alpha = 0.95f)

    val anyGenerating = uiState.generationStatus.values.any {
        it == BotGenerationStatus.GENERATING || it == BotGenerationStatus.WAITING
    }
    // 修复（核对发现）：续轮裁判判断期间（最长 JUDGE_TIMEOUT_MS≈8s）没有任何
    // bot 处于生成状态，anyGenerating 会是 false，导致这段时间打字不会触发打断，
    // 要等到下一轮真正开始生成才会停。这里额外把"处于自动连续讨论中"纳入
    // 打断判断，不影响 anyGenerating 本身用于序贯进度条可见性的语义。
    val canInterrupt = anyGenerating || uiState.isAutoDiscussing

    // ── 圆桌背景图：从 uiState 读取（复用 ChatBackgroundDataStore，
    //    哨兵 characterId 见 RoundtableViewModel.ROUNDTABLE_BG_SENTINEL_ID）──
    val backgroundImageUri = uiState.backgroundImageUri
    val ctxBg = LocalContext.current

    // v1.39 圆桌工具调用接入：文件卡片"打开"回调。
    // v1.48 圆桌 openFile 缺应用内预览分支修复（诊断确认属实）：此前这里无条件
    // FileProvider + ACTION_VIEW 跳外部应用——同一个 xlsx/docx 文件在私聊
    // （ChatScreen.openFile）点开会走应用内预览，圆桌点开却直接甩给系统选择器，
    // 行为不一致。现改为与 ChatScreen.openFile 完全同构：先查
    // FilePreviewParser.isPreviewable，命中则走应用内预览（在 FilePreviewParser
    // 完成 Excel 闪退修复后，这条路径现在是安全的），不支持的类型才兜底外部打开。
    val openFile: (com.zaijian.zhoumuyun.ui.viewmodel.ExportedFile) -> Unit = { ef ->
        val ext = ef.fileName.substringAfterLast('.', "").lowercase()
        if (com.zaijian.zhoumuyun.data.agent.FilePreviewParser.isPreviewable(ext)) {
            onNavigateToFilePreview(ef.absolutePath)
        } else {
            try {
                val file = java.io.File(ef.absolutePath)
                if (file.exists()) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctxBg,
                        "${ctxBg.packageName}.fileprovider",
                        file,
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, ef.mimeType)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctxBg.startActivity(android.content.Intent.createChooser(intent, "打开 ${ef.fileName}"))
                } else {
                    android.widget.Toast.makeText(ctxBg, "文件不存在：${ef.fileName}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                // v147 vault 改造修复：与 ChatScreen.openFile 同步修复（见该文件注释）。
                com.zaijian.zhoumuyun.util.ZLog.e("RoundtableScreen", "打开文件失败：${ef.absolutePath}", e)
                android.widget.Toast.makeText(ctxBg, "无法打开文件：${e.message?.take(60)}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // v1.48 圆桌表格气泡全屏查看：table_export payload 本身在内存里（没有落盘的
    // xlsx 附件时，excelFile 为 null，onOpenFullTable 无法走上面的 openFile），
    // 与 ChatScreen 的 onOpenTable 同构——暂存进 PreviewMemoryCache 后跳转
    // memory 模式的预览页，用完即焚。
    val openTable: (List<String>, List<List<String>>) -> Unit = { columns, rows ->
        val tempKey = com.zaijian.zhoumuyun.ui.screen.filepreview.PreviewMemoryCache.put(
            com.zaijian.zhoumuyun.ui.screen.filepreview.PreviewMemoryCache.MemoryItem.MemoryTable(columns, rows),
        )
        onNavigateToFilePreviewMemory(tempKey)
    }

    // 圆桌背景图选择器：持久化 URI 权限 + 触发裁剪弹窗，与
    // ChatScreen.bgImageLauncher 完全同构的流程，只是落到
    // RoundtableViewModel.requestRoundtableBackgroundCrop。
    val roundtableBgImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctxBg.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.requestRoundtableBackgroundCrop(uri.toString())
        } catch (e: Throwable) {
            com.zaijian.zhoumuyun.util.ZLog.w("RoundtableScreen", "背景图设置失败: uri=$uri", e)
            scope.launch { snackbar.showSnackbar("背景图设置失败，请重试") }
        }
    }

    // 圆桌背景图裁剪弹窗：pendingBackgroundCropUri 非空时显示，与
    // ChatScreen 中的裁剪弹窗调用方式一致。
    uiState.pendingBackgroundCropUri?.let { pendingUriString ->
        com.zaijian.zhoumuyun.ui.component.AvatarCropDialog(
            uri       = android.net.Uri.parse(pendingUriString),
            shape     = com.zaijian.zhoumuyun.ui.component.CropShape.FULL_SCREEN,
            onConfirm = { params ->
                viewModel.confirmRoundtableBackgroundCrop(
                    uri     = pendingUriString,
                    offsetX = params.normalizedOffsetX,
                    offsetY = params.normalizedOffsetY,
                    scale   = params.scale,
                )
            },
            onDismiss = { viewModel.cancelRoundtableBackgroundCrop() },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ── [0] 自定义背景图（用户设置时覆盖默认纯色背景）──────
        // 与 ChatScreen 完全同构的坐标系实现：用较大边覆盖容器的基准
        // 尺寸公式 + rememberAsyncImagePainter 拿真实 intrinsicSize，
        // 保证保存的 offset/scale 跟最终渲染效果一致（同 v57 修复）。
        // 这里外层是 BoxWithConstraints(Modifier.fillMaxSize())，全屏
        // 约束足够大，暂不会触发 BreathingAvatar 那次修复过的
        // "小容器+极端长宽比"压缩问题。
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
        // ── [1] 消息列表 ──────────────────────────────────────
        // imePadding()：键盘弹出时，LazyColumn 的可视区域底部跟随键盘上移；
        // 否则 contentPadding.bottom 是固定值（80.dp+xl），键盘弹起后输入栏
        // 用 imePadding() 上移了，但消息列表没动，最新消息会被抬起的输入框压住。
        LazyColumn(
            state          = listState,
            modifier       = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(
                // 顶部 = 状态栏高度 + 顶部栏高度(44dp) + 选人栏高度(64dp) + 间距
                // 与 MemberStrip/进度条/讨论条的 statusBarsPadding()+padding(top) 保持一致，
                // 确保消息列表首条消息不被这些固定元素遮挡。
                top    = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                         + Spacing.topBarHeight + 64.dp + Spacing.md,
                // P2 修复：底部改为动态测量的输入栏实际高度，避免字体缩放/内容变化时
                // 硬编码值与实际高度不一致导致最后一条消息被遮挡。
                // 首帧 inputBarHeightPx 为 0（onSizeChanged 尚未回调），用 80.dp 兜底避免闪烁。
                bottom = (if (inputBarHeightPx > 0) inputBarHeightDp else 80.dp) + Spacing.md,
                start  = Spacing.screenHorizontal,
                end    = Spacing.screenHorizontal,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            itemsIndexed(
                items = uiState.messages,
                key   = { _, msg -> msg.id },
            ) { index, msg ->
                val prev = if (index > 0) uiState.messages[index - 1] else null
                if (prev == null || (msg.createdAt - prev.createdAt) >= 30 * 60 * 1000L) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.sm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = formatRoundtableTimestamp(msg.createdAt),
                            style = type.label,
                            color = colors.textDisabled,
                        )
                    }
                }

                if (msg.speakerId == "user") {
                    UserBubble(
                        msg = msg,
                        onCopyMessage = { text ->
                            clipboardManager.setText(AnnotatedString(text))
                            scope.launch {
                                snackbar.showSnackbar("已复制", duration = SnackbarDuration.Short)
                            }
                        },
                    )
                } else {
                    val bot = memberMap[msg.speakerId.toIntOrNull()]
                    BotBubble(
                        msg    = msg,
                        bot    = bot,
                        isLast = index == uiState.messages.lastIndex,
                        onOpenFile = openFile,
                        onOpenTable = openTable,
                        onCopyMessage = { text ->
                            clipboardManager.setText(AnnotatedString(text))
                            scope.launch {
                                snackbar.showSnackbar("已复制", duration = SnackbarDuration.Short)
                            }
                        },
                    )
                }
            }
        }

        // ── [2] 顶部栏 ────────────────────────────────────────
        DetailTopBar(
            title     = "圆桌讨论",
            subtitle  = if (members.size > 0) "${members.size} 位成员" else null,
            onBack    = onBack,
            headerBg  = headerBg,
            modifier  = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            actions   = {
                // D-2 圆桌 ContentBlock 入口：心迹面板
                IconButton(onClick = { showActivityPanel = true }) {
                    Icon(
                        imageVector        = AppIcons.History,
                        contentDescription = "圆桌心迹",
                        tint               = colors.textSecondary,
                        modifier           = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = { viewModel.toggleSettingsSheet(true) }) {
                    Icon(
                        imageVector        = AppIcons.Settings,
                        contentDescription = "圆桌设置",
                        tint               = colors.textSecondary,
                        modifier           = Modifier.size(22.dp),
                    )
                }
            },
        )

        // ── [3] Bot 成员切换栏（粘性，紧贴 Header 下方）────────
        MemberStrip(
            members          = members,
            generationStatus = uiState.generationStatus,
            modifier         = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = Spacing.topBarHeight),
        )

        // ── [4] 序贯进度指示器
        AnimatedVisibility(
            visible  = anyGenerating && members.isNotEmpty(),
            enter    = fadeIn(tween(AnimDuration.fast)) + slideInVertically { -it },
            exit     = fadeOut(tween(AnimDuration.fast)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = Spacing.topBarHeight + 64.dp),
        ) {
            SequentialProgressBar(
                members          = members,
                generationStatus = uiState.generationStatus,
                modifier         = Modifier
                    .fillMaxWidth()
                    .background(colors.bgBase.copy(alpha = 0.92f))
                    .padding(horizontal = Spacing.screenHorizontal, vertical = 6.dp),
            )
        }

        // ── [4b] 讨论中状态条（Step 5：自动连续讨论循环 §4 UI）────
        // "全体@"触发自动续轮时显示，告知用户圆桌在自动跑，不是卡住了。
        AnimatedVisibility(
            visible  = uiState.isAutoDiscussing,
            enter    = fadeIn(tween(AnimDuration.fast)) + slideInVertically { -it },
            exit     = fadeOut(tween(AnimDuration.fast)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = Spacing.topBarHeight + 64.dp),
        ) {
            DiscussionRoundBanner(
                round    = uiState.discussionRound,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accent.copy(alpha = 0.12f))
                    .padding(horizontal = Spacing.screenHorizontal, vertical = 6.dp),
            )
        }

        // ── [4c] @ 候选弹窗（Step 5：§3 输入框 @ 触发候选） ──────
        val atQueryNow = atQuery
        if (atQueryNow != null) {
            val candidates = remember(members, atQueryNow) {
                members.filter { cfg ->
                    val nick = (cfg.nickname ?: "").trim()
                    cfg.name.contains(atQueryNow, ignoreCase = true) ||
                        (nick.isNotBlank() && nick.contains(atQueryNow, ignoreCase = true))
                }
            }
            if (candidates.isNotEmpty()) {
                AtMentionPopup(
                    candidates = candidates,
                    onSelect   = { bot ->
                        val triggerIndex = atTriggerIndex
                        if (triggerIndex != null) {
                            // 插入"@角色名 "替换掉触发位置之后的查询片段，纯文本协议
                            inputText = inputText.substring(0, triggerIndex) + "@" + bot.name + " "
                        }
                        atTriggerIndex = null
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = 64.dp, start = Spacing.screenHorizontal, end = Spacing.screenHorizontal),
                )
            }
        }

        // ── [5] 底部输入栏 ────────────────────────────────────
        RoundtableInputBar(
            value         = inputText,
            onValueChange = {
                inputText = it
                if (canInterrupt) viewModel.interrupt()
            },
            canSend       = inputText.trim().isNotEmpty(),
            isWaiting     = uiState.waitingForUser,
            bgColor       = inputBg,
            onSend        = {
                val text = inputText.trim()
                if (text.isNotEmpty()) {
                    viewModel.sendMessage(text)
                    inputText = ""
                    scope.launch { listState.animateScrollToItem(uiState.messages.size) }
                }
            },
            // 修复：navigationBarsPadding() 挪到 RoundtableInputBar 内部（背景/边框
            // 之后），这里不再重复加一层——否则背景覆盖范围会被这里的 padding 先
            // 裁掉一部分，onSizeChanged 也需要在这之后触发，才能测到包含导航栏安全区
            // 在内的完整高度，避免 LazyColumn 底部预留空间不够、最后一条消息被挡住。
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .imePadding()
                .onSizeChanged { inputBarHeightPx = it.height },
        )

        // ── Snackbar ──────────────────────────────────────────
        SnackbarHost(
            hostState = snackbar,
            modifier  = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                // P2 修复：Snackbar 偏移同样改用动态测量的输入栏高度，与 ChatScreen 一致。
                .padding(bottom = if (inputBarHeightPx > 0) inputBarHeightDp else 88.dp),
            snackbar = { data ->
                Snackbar(
                    snackbarData   = data,
                    containerColor = colors.bgCard,
                    contentColor   = colors.textPrimary,
                    shape          = RoundedCornerShape(12.dp),
                )
            },
        )
    }

    // ── [6] 圆桌设置面板（Step 5：屏蔽制改造）────────────────
    // 改为全屏 Dialog 而非 ModalBottomSheet：
    // M3 的 ModalBottomSheet 即便 skipPartiallyExpanded=true，依然保留了
    // "下拉可关闭"的内置手势（这是 SheetState 的 AnchoredDraggable 行为，
    // 没有公开 API 能单独禁用），用户在成员列表里上下滚动/拖拽勾选时很容易
    // 误触发整个面板被甩下去关闭。改用 Dialog 后交互变成"普通全屏页面 +
    // 右上角 ✕ 按钮关闭 / 系统返回键关闭"，不再有意外手势关闭的问题。
    if (uiState.showSettingsSheet) {
        Dialog(
            onDismissRequest = { viewModel.toggleSettingsSheet(false) },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows  = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.bgCard)
                    .statusBarsPadding(),
            ) {
                RoundtableSettingsSheet(
                    allMotherMembers      = uiState.allMotherMembers,
                    blockedMemberIds      = uiState.blockedMemberIds,
                    extraDaughters        = uiState.extraDaughterMembers,
                    availableDaughters    = uiState.availableDaughterMembers,
                    scheduleMode          = uiState.scheduleMode,
                    isSpontaneousEnabled  = uiState.isSpontaneousEnabled,
                    hasCustomBackground   = backgroundImageUri != null,
                    onToggleMember        = { id, blocked ->
                        if (blocked) viewModel.blockMember(id) else viewModel.unblockMember(id)
                    },
                    onAddDaughter         = { viewModel.addDaughter(it) },
                    onModeChange          = { viewModel.setScheduleMode(it) },
                    onSpontaneousToggle   = { viewModel.setSpontaneousEnabled(it) },
                    onSetBackground       = {
                        roundtableBgImageLauncher.launch(arrayOf("image/*"))
                        viewModel.toggleSettingsSheet(false)
                    },
                    onClearBackground     = {
                        viewModel.clearRoundtableBackground()
                        viewModel.toggleSettingsSheet(false)
                    },
                    onClose               = { viewModel.toggleSettingsSheet(false) },
                )
            }
        }
    }

    // ── [7] 心迹面板（D-2 圆桌 ContentBlock 入口）────────────────
    // 多角色合并时间线：observeTimelineForCharacters → ContentBlockAdapter
    // → ContentBlockRenderer，与 AgentActivityTimelinePanel 同一套渲染管线，
    // 区别是数据源为多角色合并 Flow（observeTimelineForCharacters）。
    if (showActivityPanel) {
        // E0 分层收口：原 AppContainer.instance.agentActivityRepo
        // .observeTimelineForCharacters()，改走 RoundtableViewModel。
        // P2-13 修复：原 remember(characterIds) 用 List 实例做 key，
        // 同文件上方已踩过坑——直接拿 List 当 key 不可靠（成员相同但顺序
        // 不同时会被误判为"变化"）。改用 memberKey（排序后拼接的字符串），
        // 与同文件 snackbar/listState/LaunchedEffect 的 key 策略一致。
        val timelineItems by remember(memberKey) {
            viewModel.observeTimelineForCharacters(characterIds)
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        val activityBlocks = remember(timelineItems) {
            ContentBlockAdapter.fromTimelineItems(timelineItems)
        }

        Dialog(
            onDismissRequest = { showActivityPanel = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows  = false,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.bgCard)
                    .statusBarsPadding(),
            ) {
                DetailTopBar(
                    title    = "圆桌心迹",
                    subtitle = if (members.size > 0) "${members.size} 位成员" else null,
                    onBack   = { showActivityPanel = false },
                    headerBg = colors.bgCard,
                )

                if (activityBlocks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = "暂无心迹记录",
                            style = type.body,
                            color = colors.textSecondary,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = Spacing.screenHorizontal,
                            vertical   = Spacing.md,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        item {
                            ContentBlockRenderer(
                                blocks    = activityBlocks,
                                textColor = colors.textPrimary,
                                style     = type.body,
                            )
                        }
                    }
                }
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────
//  工具函数
// ─────────────────────────────────────────────────────────────

private fun formatRoundtableTimestamp(ms: Long): String = TimeFormatUtils.formatTime(ms)


// ─────────────────────────────────────────────────────────────
//  Previews
// ─────────────────────────────────────────────────────────────

@Preview(
    name            = "RoundtableScreen · Dark",
    showBackground  = true,
    backgroundColor = 0xFF12100A,
    widthDp         = 390,
    heightDp        = 844,
)
@Composable
private fun PreviewRoundtableDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) {
        RoundtableScreen(characterIds = DefaultCharacters.take(4).map { it.id })
    }
}


@Preview(
    name           = "RoundtableScreen · Light",
    showBackground = true,
    widthDp        = 390,
    heightDp       = 844,
)
@Composable
private fun PreviewRoundtableLight() {
    ZaijianTheme(appTheme = AppTheme.LIGHT) {
        RoundtableScreen(characterIds = DefaultCharacters.take(4).map { it.id })
    }
}
