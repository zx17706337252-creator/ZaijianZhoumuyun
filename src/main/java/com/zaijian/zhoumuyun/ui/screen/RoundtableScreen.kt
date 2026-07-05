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
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow

// ─────────────────────────────────────────────────────────────
//  RoundtableScreen — 圆桌聊天页
//
//  Phase 14 后半升级：
//  ① 圆桌设置面板（ModalBottomSheet）：动态成员管理 + 调度模式切换
//  ② RoundtableHeader 接入设置按钮（移除 TODO）
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
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
                    onToggleMother        = { id, blocked ->
                        if (blocked) viewModel.blockMother(id) else viewModel.unblockMother(id)
                    },
                    onAddDaughter         = { viewModel.addDaughter(it) },
                    onRemoveDaughter      = { viewModel.removeDaughter(it) },
                    onModeChange          = { viewModel.setScheduleMode(it) },
                    onSpontaneousToggle   = { viewModel.setSpontaneousEnabled(it) },
                    onClose               = { viewModel.toggleSettingsSheet(false) },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  DiscussionRoundBanner — "讨论中，第 N 轮"状态条
//  （Step 5：自动连续讨论循环 §4 UI 配套）
// ─────────────────────────────────────────────────────────────

@Composable
private fun DiscussionRoundBanner(
    round: Int,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 轻量呼吸动效：让状态条带一点"正在进行"的生命感，而不是静止的文字条
    val infiniteTransition = rememberInfiniteTransition(label = "discussionPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "discussionPulseAlpha",
    )

    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = pulseAlpha)),
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text  = "讨论中 · 第 $round 轮（发消息可随时打断）",
            style = type.label,
            color = colors.accent,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  AtMentionPopup — 输入框 @ 候选弹窗
//  （Step 5：§3 @mention 解析 配套 UI）
// ─────────────────────────────────────────────────────────────

@Composable
private fun AtMentionPopup(
    candidates: List<CharacterConfig>,
    onSelect: (CharacterConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = modifier
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.bgElevated)
            .border(
                width = 0.5.dp,
                color = colors.borderSubtle,
                shape = RoundedCornerShape(Radius.md),
            ),
    ) {
        candidates.forEach { bot ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(bot) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(bot.avatarUrl)
                        .crossfade(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = bot.name,
                    modifier           = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(bot.accentColor.copy(alpha = 0.3f)),
                    error              = rememberVectorPainter(Icons.Outlined.Person),
                )
                Text(
                    text  = bot.name,
                    style = type.body,
                    color = colors.textPrimary,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  RoundtableHeader — 顶栏（Phase 14 后半：设置按钮实装）
// ─────────────────────────────────────────────────────────────

@Composable
private fun RoundtableHeader(
    memberCount: Int,
    headerBg: Color,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Box(
        modifier = modifier
            .statusBarsPadding()
            .height(Spacing.topBarHeight)
            .background(headerBg)
            .border(
                width = 0.5.dp,
                color = colors.borderSubtle,
                shape = RoundedCornerShape(0.dp),
            ),
    ) {
        Row(
            modifier          = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint               = colors.textPrimary,
                    modifier           = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "圆桌讨论",
                    style = type.navTitle,
                    color = colors.textPrimary,
                )
                if (memberCount > 0) {
                    Text(
                        text  = "$memberCount 位成员",
                        style = type.label,
                        color = colors.textSecondary,
                    )
                }
            }
            // Phase 14 后半：设置按钮已实装
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector        = Icons.Outlined.Settings,
                    contentDescription = "圆桌设置",
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(22.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  RoundtableSettingsSheet — 圆桌设置面板（Step 5：屏蔽制改造）
//
//  设计方案 §1 + §6：
//    A. 母角色区：1-9 已解锁角色，勾选框形式，取消勾选 = 屏蔽
//       （移出本轮，随时可重新勾回），不再是"4人上限选人"。
//    B. 女儿/第三代区：列表 + "拉入"/"移出"按钮，类似
//       FamilyPickerSheet 的家族链选择交互，但放在圆桌设置面板内。
//    C. 调度模式：AUTO / HEURISTIC / AI_ONLY（不变）。
// ─────────────────────────────────────────────────────────────

@Composable
private fun RoundtableSettingsSheet(
    allMotherMembers: List<CharacterConfig>,
    blockedMotherIds: Set<Int>,
    extraDaughters: List<CharacterConfig>,
    availableDaughters: List<CharacterConfig>,
    scheduleMode: ScheduleMode,
    isSpontaneousEnabled: Boolean,
    onToggleMother: (characterId: Int, blocked: Boolean) -> Unit,
    onAddDaughter: (Int) -> Unit,
    onRemoveDaughter: (Int) -> Unit,
    onModeChange: (ScheduleMode) -> Unit,
    onSpontaneousToggle: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val activeMotherCount = allMotherMembers.count { it.id !in blockedMotherIds }
    val extraDaughterIds  = extraDaughters.map { it.id }.toSet()
    // 候选区只展示"尚未拉入"的女儿，已拉入的在上面的"已拉入"区显示
    val pullableDaughters = availableDaughters.filter { it.id !in extraDaughterIds }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.screenHorizontal)
            .verticalScroll(rememberScrollState()),
    ) {
        // ── 标题栏 ──────────────────────────────────────────
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = "圆桌设置",
                style    = type.cardTitle,
                color    = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector        = Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(22.dp),
                )
            }
        }

        // ── A. 母角色区（屏蔽制） ────────────────────────────
        Text(
            text     = "在场母角色（$activeMotherCount/${allMotherMembers.size}）",
            style    = type.label,
            color    = colors.textSecondary,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )

        if (allMotherMembers.isEmpty()) {
            Text(
                text     = "暂无已解锁的母角色",
                style    = type.body,
                color    = colors.textDisabled,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
        } else {
            allMotherMembers.forEach { bot ->
                val blocked = bot.id in blockedMotherIds
                MemberSettingsRow(
                    bot        = bot,
                    action     = if (blocked) MemberAction.ADD else MemberAction.REMOVE,
                    actionTint = if (blocked) colors.accent else Palette.SemanticDanger,
                    onAction   = { onToggleMother(bot.id, !blocked) },
                    dimmed     = blocked,
                )
            }
        }

        // ── B. 女儿/第三代区（拉入/移出） ────────────────────
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text     = "女儿（${extraDaughters.size} 位已拉入）",
            style    = type.label,
            color    = colors.textSecondary,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )

        if (extraDaughters.isEmpty() && pullableDaughters.isEmpty()) {
            Text(
                text     = "暂无可拉入的女儿角色",
                style    = type.body,
                color    = colors.textDisabled,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
        } else {
            extraDaughters.forEach { bot ->
                MemberSettingsRow(
                    bot        = bot,
                    action     = MemberAction.REMOVE,
                    actionTint = Palette.SemanticDanger,
                    onAction   = { onRemoveDaughter(bot.id) },
                )
            }
            if (pullableDaughters.isNotEmpty()) {
                if (extraDaughters.isNotEmpty()) Spacer(Modifier.height(Spacing.xs))
                pullableDaughters.forEach { bot ->
                    MemberSettingsRow(
                        bot        = bot,
                        action     = MemberAction.ADD,
                        actionTint = colors.accent,
                        onAction   = { onAddDaughter(bot.id) },
                    )
                }
            }
        }

        // ── D. 自发互动 ─────────────────────────────────────
        Spacer(Modifier.height(Spacing.lg))
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "自发互动",
                    style = type.body,
                    color = colors.textPrimary,
                )
                Text(
                    text  = "30 秒无输入时，角色会主动开口",
                    style = type.label,
                    color = colors.textSecondary,
                )
            }
            Switch(
                checked         = isSpontaneousEnabled,
                onCheckedChange = onSpontaneousToggle,
            )
        }

        // ── C. 调度模式 ────────────────────────────────────
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text     = "调度模式",
            style    = type.label,
            color    = colors.textSecondary,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )

        ScheduleModeOption(
            icon        = Icons.Outlined.AutoMode,
            title       = "自动",
            subtitle    = "短消息启发式 · 长消息 AI 调度",
            selected    = scheduleMode == ScheduleMode.AUTO,
            onClick     = { onModeChange(ScheduleMode.AUTO) },
        )
        ScheduleModeOption(
            icon        = Icons.Outlined.Speed,
            title       = "启发式",
            subtitle    = "基于规则调度，零 API 消耗",
            selected    = scheduleMode == ScheduleMode.HEURISTIC,
            onClick     = { onModeChange(ScheduleMode.HEURISTIC) },
        )
        ScheduleModeOption(
            icon        = Icons.Outlined.SmartToy,
            title       = "AI 调度",
            subtitle    = "每轮额外一次 API 调用，最自然",
            selected    = scheduleMode == ScheduleMode.AI_ONLY,
            onClick     = { onModeChange(ScheduleMode.AI_ONLY) },
        )

        Spacer(Modifier.height(Spacing.xl))
    }
}

private enum class MemberAction { ADD, REMOVE, LOCKED }

@Composable
private fun MemberSettingsRow(
    bot: CharacterConfig,
    action: MemberAction,
    actionTint: Color,
    onAction: () -> Unit,
    dimmed: Boolean = false,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val contentAlpha = if (dimmed) 0.5f else 1f

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 头像
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(bot.avatarUrl)
                .crossfade(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build(),
            contentDescription = bot.name,
            modifier           = Modifier
                .size(36.dp)
                .alpha(contentAlpha)
                .clip(CircleShape)
                .background(bot.accentColor.copy(alpha = 0.3f)),
            error              = rememberVectorPainter(Icons.Outlined.Person),
        )
        // 名字
        Text(
            text     = bot.name,
            style    = type.body,
            color    = colors.textPrimary.copy(alpha = contentAlpha),
            modifier = Modifier.weight(1f),
        )
        // 操作按钮
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    when (action) {
                        MemberAction.ADD    -> actionTint.copy(alpha = 0.12f)
                        MemberAction.REMOVE -> actionTint.copy(alpha = 0.10f)
                        MemberAction.LOCKED -> Color.Transparent
                    }
                )
                .clickable(enabled = action != MemberAction.LOCKED) { onAction() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (action) {
                    MemberAction.ADD    -> Icons.Outlined.Add
                    MemberAction.REMOVE -> Icons.Outlined.Close
                    MemberAction.LOCKED -> Icons.Outlined.Check
                },
                contentDescription = action.name,
                tint               = actionTint,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ScheduleModeOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (selected) colors.accent.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .border(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) colors.accent.copy(alpha = 0.5f) else colors.border,
                shape = RoundedCornerShape(Radius.sm),
            )
            .clickable { onClick() }
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (selected) colors.accent else colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = title,
                style = type.body,
                color = if (selected) colors.accent else colors.textPrimary,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text  = subtitle,
                style = type.label,
                color = colors.textSecondary,
            )
        }
        if (selected) {
            Icon(
                imageVector        = Icons.Outlined.Check,
                contentDescription = "已选择",
                tint               = colors.accent,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
    Spacer(Modifier.height(Spacing.xs))
}

// ─────────────────────────────────────────────────────────────
//  MemberStrip — 成员切换栏（水平滚动，粘性）
// ─────────────────────────────────────────────────────────────

@Composable
private fun MemberStrip(
    members: List<CharacterConfig>,
    generationStatus: Map<Int, BotGenerationStatus>,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val scroll = rememberScrollState()

    Row(
        modifier = modifier
            .height(64.dp)   // 64dp：56dp内容 + 8dp为头像弹出留空
            .background(
                if (colors.isDark) colors.bgCard.copy(alpha = 0.85f)
                else colors.bgBase.copy(alpha = 0.90f)
            )
            .border(
                width = 0.5.dp,
                color = colors.border,
                shape = RoundedCornerShape(0.dp),
            )
            .horizontalScroll(scroll)
            .padding(horizontal = Spacing.screenHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        members.forEach { bot ->
            val status = generationStatus[bot.id] ?: BotGenerationStatus.IDLE
            MemberChip(bot = bot, status = status)
        }
    }
}

@Composable
private fun MemberChip(
    bot: CharacterConfig,
    status: BotGenerationStatus,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // GENERATING 状态 = 当前激活（头像弹出）
    val isActive = status == BotGenerationStatus.GENERATING

    // 头像弹出动画：GENERATING 时上移 4dp，弹性回弹
    val yOffset by animateDpAsState(
        targetValue   = if (isActive) (-4).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label         = "chip_pop_${bot.id}",
    )

    // 脉冲动画（状态点用）
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_${bot.id}")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue   = 1f,
        targetValue    = 0.3f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    val dotColor = when (status) {
        BotGenerationStatus.GENERATING -> colors.statusIdle.copy(alpha = pulseAlpha)
        BotGenerationStatus.DONE       -> colors.statusActive
        BotGenerationStatus.WAITING    -> colors.accent.copy(alpha = 0.45f)
        BotGenerationStatus.IDLE       -> colors.textDisabled.copy(alpha = 0.4f)
    }

    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // 头像容器：上移弹出 + presenceGlow（仅 GENERATING 时亮起）
        Box(
            modifier = Modifier
                .offset(y = yOffset)
                .presenceGlow(
                    color       = bot.accentColor,
                    isActive    = isActive,
                    breathAlpha = 0.32f,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(bot.avatarUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = bot.name,
                modifier           = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(bot.accentColor.copy(alpha = 0.3f)),
                error              = rememberVectorPainter(Icons.Outlined.Person),
            )

            // 金色指示点：紧贴头像底部中心，仅 GENERATING 时显示
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .offset(y = 3.dp)
                        .clip(CircleShape)
                        .background(Palette.Gold),
                )
            }
        }

        Text(
            text  = bot.name,
            style = type.label,
            color = when (status) {
                BotGenerationStatus.GENERATING -> colors.textPrimary
                BotGenerationStatus.WAITING    -> colors.textSecondary
                BotGenerationStatus.DONE       -> colors.textSecondary
                else                           -> colors.textDisabled
            },
        )

        // 生成状态点
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  SequentialProgressBar — 序贯生成进度指示器
// ─────────────────────────────────────────────────────────────

@Composable
private fun SequentialProgressBar(
    members: List<CharacterConfig>,
    generationStatus: Map<Int, BotGenerationStatus>,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        members.forEach { bot ->
            val status = generationStatus[bot.id] ?: return@forEach

            val label = when (status) {
                BotGenerationStatus.GENERATING -> "${bot.name}⠿"
                BotGenerationStatus.DONE       -> "${bot.name}✓"
                BotGenerationStatus.WAITING    -> "${bot.name}○"
                BotGenerationStatus.IDLE       -> null
            }
            if (label != null) {
                Text(
                    text  = label,
                    style = type.label.copy(fontSize = 11.sp),
                    color = when (status) {
                        BotGenerationStatus.GENERATING -> colors.accent
                        BotGenerationStatus.DONE       -> colors.statusActive
                        else                           -> colors.textDisabled
                    },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  UserBubble — 用户消息气泡
// ─────────────────────────────────────────────────────────────

@Composable
private fun UserBubble(msg: RoundtableMessage) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val config = LocalConfiguration.current
    val maxW   = (config.screenWidthDp * 0.72f).dp

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text     = msg.content,
            style    = type.body,
            color    = Color.White,
            modifier = Modifier
                .widthIn(max = maxW)
                .clip(
                    RoundedCornerShape(
                        topStart    = Radius.md,
                        topEnd      = Radius.xs,
                        bottomStart = Radius.md,
                        bottomEnd   = Radius.md,
                    ),
                )
                .background(if (colors.isDark) Color(0xFF3A2E20) else Palette.Ink900)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  BotBubble — Bot 回复气泡（左侧 4dp 主题色条）
// ─────────────────────────────────────────────────────────────

@Composable
private fun BotBubble(
    msg: RoundtableMessage,
    bot: CharacterConfig?,
    isLast: Boolean,
) {
    val colors      = ZaijianTheme.colors
    val type        = ZaijianTheme.typography
    val config      = LocalConfiguration.current
    val maxW        = (config.screenWidthDp * 0.82f).dp
    val accentColor = bot?.accentColor ?: colors.accent

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment     = Alignment.Top,
    ) {
        // 头像
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(bot?.avatarUrl)
                .crossfade(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build(),
            contentDescription = bot?.name,
            modifier           = Modifier
                .padding(top = 2.dp, end = Spacing.sm)
                .size(AvatarSize.bubble)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.25f)),
            error              = rememberVectorPainter(Icons.Outlined.Person),
        )

        Column(
            modifier = Modifier.widthIn(max = maxW),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 角色名 + 专属色点 + 被点名标签
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text  = msg.speakerName,
                    style = type.label,
                    color = accentColor,
                    fontWeight = FontWeight.Medium,
                )
                Box(
                    modifier = Modifier
                        .size(DotSize.small)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.75f)),
                )
                // 被 @ 点名时显示的标签
                if (msg.isNotified) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(accentColor.copy(alpha = 0.15f))
                            .border(0.5.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text  = "被点名",
                            style = type.caption,
                            color = accentColor,
                        )
                    }
                }
            }

            // 气泡
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart    = Radius.xs,
                            topEnd      = Radius.md,
                            bottomStart = Radius.md,
                            bottomEnd   = Radius.md,
                        ),
                    )
                    .background(
                        if (colors.isDark) colors.bgCard else colors.bgElevated
                    )
                    .border(
                        width = 0.5.dp,
                        color = if (colors.isDark) Palette.Gold.copy(alpha = 0.18f) else Palette.Gold.copy(alpha = 0.28f),
                        shape = RoundedCornerShape(
                            topStart    = Radius.xs,
                            topEnd      = Radius.md,
                            bottomStart = Radius.md,
                            bottomEnd   = Radius.md,
                        ),
                    )
                    // 左侧 4dp Bot 主题色条
                    .drawBehind {
                        drawLine(
                            color       = accentColor,
                            start       = Offset(0f, 0f),
                            end         = Offset(0f, size.height),
                            strokeWidth = 4.dp.toPx(),
                        )
                    }
                    .padding(start = 12.dp, end = Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    // 引用气泡（↩ 回应另一个 Bot）
                    if (msg.replyTargetName != null) {
                        ReplyQuoteBlock(
                            targetName  = msg.replyTargetName,
                            targetColor = colors.textSecondary,
                        )
                    }

                    // 正文 + 流式光标
                    val displayContent = when {
                        msg.isStreaming && msg.content.isEmpty() -> "…"
                        msg.isStreaming -> msg.content + "▌"
                        else            -> msg.content
                    }
                    Text(
                        text  = displayContent,
                        style = type.body,
                        color = colors.textPrimary,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ReplyQuoteBlock — 引用标记（↩ Bot名）
// ─────────────────────────────────────────────────────────────

@Composable
private fun ReplyQuoteBlock(
    targetName: String,
    targetColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.bgElevated)
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("↩", style = type.label, color = targetColor)
        Text(
            text      = targetName,
            style     = type.label,
            color     = targetColor,
            fontStyle = FontStyle.Italic,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  RoundtableInputBar — 底部输入栏
// ─────────────────────────────────────────────────────────────

@Composable
private fun RoundtableInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    canSend: Boolean,
    isWaiting: Boolean,
    bgColor: Color,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography

    Row(
        modifier = modifier
            .background(bgColor)
            .border(
                width = 0.5.dp,
                color = colors.borderSubtle,
                shape = RoundedCornerShape(0.dp),
            )
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value         = value,
            onValueChange = onValueChange,
            textStyle     = type.body.copy(color = colors.textPrimary),
            cursorBrush   = SolidColor(colors.accent),
            modifier      = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    if (colors.isDark) colors.bgElevated else colors.bgCard
                )
                .padding(horizontal = Spacing.md, vertical = 10.dp),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text  = if (isWaiting) "对大家说点什么…" else "Bot 回复中，输入可打断…",
                        style = type.body,
                        color = colors.textDisabled,
                    )
                }
                innerTextField()
            },
        )
        Spacer(Modifier.width(Spacing.sm))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) colors.accent
                    else colors.textDisabled.copy(alpha = 0.3f)
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

// ─────────────────────────────────────────────────────────────
//  工具函数
// ─────────────────────────────────────────────────────────────

private fun formatRoundtableTimestamp(ms: Long): String {
    val h    = (ms / 3600000 % 24).toInt()
    val m    = (ms / 60000 % 60).toInt()
    val ampm = if (h < 12) "上午" else "下午"
    val h12  = when { h == 0 -> 12; h <= 12 -> h; else -> h - 12 }
    return "$ampm $h12:${m.toString().padStart(2, '0')}"
}

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
