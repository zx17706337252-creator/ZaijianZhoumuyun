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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
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


// ─────────────────────────────────────────────────────────────
//  RoundtableScreen — 圆桌聊天页
//
//  Phase 14 后半升级：
//  ① 圆桌设置面板（ModalBottomSheet）：动态成员管理 + 调度模式切换
//  ② RoundtableHeader 接入设置按钮
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
    viewModel: RoundtableViewModel = viewModel(),
) {
    val colors   = ZaijianTheme.colors
    val type     = ZaijianTheme.typography
    val scope    = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // 初始化圆桌成员
    LaunchedEffect(characterIds) {
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
        } catch (e: Exception) {
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
                            Color.Black.copy(alpha = 0.45f)
                        else
                            Color.White.copy(alpha = 0.30f)
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
                bottom = 80.dp + Spacing.xl,
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
                    UserBubble(msg)
                } else {
                    val bot = memberMap[msg.speakerId.toIntOrNull()]
                    BotBubble(
                        msg    = msg,
                        bot    = bot,
                        isLast = index == uiState.messages.lastIndex,
                    )
                }
            }
        }

        // ── [2] 顶部栏 ────────────────────────────────────────
        RoundtableHeader(
            memberCount    = members.size,
            headerBg       = headerBg,
            onBack         = onBack,
            onOpenSettings = { viewModel.toggleSettingsSheet(true) },
            modifier       = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
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
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding(),
        )

        // ── Snackbar ──────────────────────────────────────────
        SnackbarHost(
            hostState = snackbar,
            modifier  = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 88.dp),
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
                    blockedMotherIds      = uiState.blockedMotherIds,
                    extraDaughters        = uiState.extraDaughterMembers,
                    availableDaughters    = uiState.availableDaughterMembers,
                    scheduleMode          = uiState.scheduleMode,
                    isSpontaneousEnabled  = uiState.isSpontaneousEnabled,
                    hasCustomBackground   = backgroundImageUri != null,
                    onToggleMother        = { id, blocked ->
                        if (blocked) viewModel.blockMother(id) else viewModel.unblockMother(id)
                    },
                    onAddDaughter         = { viewModel.addDaughter(it) },
                    onRemoveDaughter      = { viewModel.removeDaughter(it) },
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
}


// ─────────────────────────────────────────────────────────────
//  工具函数
// ─────────────────────────────────────────────────────────────

private fun formatRoundtableTimestamp(ms: Long): String = TimeFormatUtils.formatClockTime(ms)


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
