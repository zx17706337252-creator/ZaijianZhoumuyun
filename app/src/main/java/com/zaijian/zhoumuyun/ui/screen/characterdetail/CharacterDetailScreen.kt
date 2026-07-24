package com.zaijian.zhoumuyun.ui.screen.characterdetail


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.ui.viewmodel.GoalViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.IdentityViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryFilter
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.PregnancyViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.SkillListViewModel
import com.zaijian.zhoumuyun.data.model.isDaughterMother
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.model.accentLight
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
import com.zaijian.zhoumuyun.ui.screen.PersonalScheduleTabContent
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.zaijian.zhoumuyun.ui.design.AppIcons

@Composable
fun CharacterDetailScreen(
    characterId: Int,
    // 通知中心设计方案：Tension/RelationWorsened 条目"去看看"跳转到
    // 关系 Tab 时携带此参数；null 表示走原有默认行为（记忆 Tab，index 0）。
    // 传入的值只在首次进入本 Composable 时生效（见下方 rememberSaveable
    // 初始值用法），页面内部切换 Tab 后由用户操作接管，不会被这个参数
    // 反复拉回。
    initialTab: Int? = null,
    onBack: () -> Unit = {},
    onStartChat: (Int) -> Unit = {},
    onNavigateToGoals: (Int) -> Unit = {},
    onNavigateToTimeline: (Int) -> Unit = {},
    onNavigateToFileVault: (Int) -> Unit = {},
    // P6 专长进化系统：从「目标」Tab 直接导航到专长档案页
    onNavigateToSpecialty: (Int) -> Unit = {},
    // U1 修复：从角色详情「目标」Tab → 专长页 → 竞赛页，补全导航链路
    onNavigateToCompetition: (domain: String) -> Unit = {},
    // 精修方案 v1.3 第5.1节：「关联项目」WrapChipGroup 点击跳转项目详情页
    onNavigateToProjectDetail: (String) -> Unit = {},
    // P3-44 修复：从孕育记录点击跳转到子代角色详情页
    onNavigateToCharacterDetail: (Int) -> Unit = {},
    identityViewModel: IdentityViewModel = viewModel(),
    memoryViewModel: MemoryViewModel = viewModel(),
    goalViewModel: GoalViewModel = viewModel(),
    pregnancyViewModel: PregnancyViewModel = viewModel(),
    // Window C 缺口2：技能管理面板 ViewModel
    skillListViewModel: SkillListViewModel = viewModel(),
    // Window D-4：能力面板 ViewModel
    capabilityPanelViewModel: com.zaijian.zhoumuyun.ui.viewmodel.CapabilityPanelViewModel = viewModel(),
    // 窗口7贯通：心迹面板 ViewModel（observeTimeline → ContentBlockAdapter → ContentBlockRenderer）
    agentActivityViewModel: com.zaijian.zhoumuyun.ui.viewmodel.AgentActivityViewModel = viewModel(),
    // 2.2 修复：mood/energy 改走 PresenceViewModel 响应式订阅，与
    // WorldScreen/CharacterScreen 同一套 uiState.presenceMap 数据源，
    // 不再是一次性快照——角色状态在别处变化后本页会自动刷新。
    presenceViewModel: com.zaijian.zhoumuyun.ui.viewmodel.PresenceViewModel = viewModel(),
    // E0 分层收口：关系/女儿角色数据改走 ViewModel，Composable 不再直接持有 Repository。
    relationshipViewModel: com.zaijian.zhoumuyun.ui.viewmodel.RelationshipViewModel = viewModel(),
    daughterCharacterViewModel: com.zaijian.zhoumuyun.ui.viewmodel.DaughterCharacterViewModel = viewModel(),
) {
    val colors    = ZaijianTheme.colors
    val type      = ZaijianTheme.typography
    val context   = LocalContext.current
    // W6-01 UI 展示补全：本页无 Scaffold，手动持有 SnackbarHostState 供孕育模块
    // 终止妊娠失败时展示提示（见下方 mainTab == 5 分支）。
    // 第九窗口问题5清收：与已修的 ChatScreen/RoundtableScreen listState/snackbar
    // 泄漏同一根因——CharacterDetail 路由自跳转（子代角色详情）时 launchSingleTop
    // 复用同一 NavBackStackEntry，characterId 变了但 remember(Unit) 不会重新创建，
    // 导致上一个角色的 Snackbar 状态（含正在展示的提示文本）残留到新角色页面。
    // 改为以 characterId 为 key。
    val snackbarHostState = remember(characterId) { SnackbarHostState() }
    // 批次3 3-6修复：生育记录点击需要异步查子代ID再导航，需协程作用域
    val pregnancyScope = rememberCoroutineScope()

    // M-6 修复：原先只查 DefaultCharacters（预设角色），女儿角色（characterId >= 1000）
    // 永远查不到，导致整个详情页只剩一个空白返回按钮。
    // 参照 ChatViewModel.init() 同款查找顺序：先查预设角色，查不到再异步查
    // DaughterCharacterRepository。预设角色是同步常量查找，不产生加载态；
    // 女儿角色需要一次 DB 查询，用 LaunchedEffect 异步填充。
    val presetCharacter = remember(characterId) { DefaultCharacters.find { it.id == characterId } }
    var daughterCharacter by remember(characterId) { mutableStateOf<CharacterConfig?>(null) }
    var daughterLookupDone by remember(characterId) { mutableStateOf(false) }

    LaunchedEffect(characterId) {
        if (presetCharacter == null) {
            // E0 分层收口：原直接 AppContainer.instance.daughterCharacterRepo
            // .getCharacterConfig()，改走 DaughterCharacterViewModel.getCharacterConfig()。
            // 女儿数据损坏（DaughterDataException）已在 VM 内兜底为 null 并记录日志，
            // daughterCharacter 保持 null → 走下面已有的"角色不存在"兜底页面。
            daughterCharacter = daughterCharacterViewModel.getCharacterConfig(characterId)
        }
        daughterLookupDone = true
    }

    val character = presetCharacter ?: daughterCharacter
    if (character == null) {
        if (!daughterLookupDone) {
            // 异步查询尚未完成（通常只持续一帧），暂不渲染，避免闪烁空白页
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            return
        }
        // 角色不存在时显示空白页 + 返回按钮 + 文字提示
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onBack) {
                    Icon(AppIcons.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "该角色不存在或数据异常",
                    style = ZaijianTheme.typography.body,
                    color = ZaijianTheme.colors.textDisabled,
                )
            }
        }
        return
    }

    // 初始化 Identity ViewModel
    LaunchedEffect(characterId, "identity") {
        try { identityViewModel.init(characterId) }
        catch (e: Exception) { android.util.Log.e("CharDetail", "Identity init failed", e) }
    }
    val identityState by identityViewModel.uiState.collectAsStateWithLifecycle()

    // W5-004 修复：提升到顶层订阅，避免在 LazyColumn 的 item{} 块内
    // 直接调用 collectAsStateWithLifecycle() 并立即取 .value——那样每次
    // 重组都会产生新的 State 实例，item 在回收重建时订阅行为不可预测，
    // 导致项目标签/项目列表不能随数据更新正确刷新。
    val relatedProjects by goalViewModel.relatedProjects.collectAsStateWithLifecycle()
    val activeProjects by goalViewModel.activeProjects.collectAsStateWithLifecycle()

    // ── 头像图片选择器（v56→v57 公馆/书架头像独立化）───────────
    // 三处头像（圆形/公馆/书架）完全独立，各自单独选图、单独裁剪，
    // 互不影响。此前这里是"先裁圆形→暂存→再裁矩形→一起提交"的三步
    // 串联状态机（cropStep/pendingCircleParams/pendingCropUri），因为
    // 过去公馆和书架共用同一次上传流程；现在改为三组结构完全对称、
    // 互相独立的 <选图 uri, 裁剪弹窗> 状态，每组走"点击入口 → 选图 →
    // 弹裁剪框 → 确认后直接调用对应的 onAvatarCropXxxPicked"，一步到位，
    // 不再有"先存起来等下一步"的逻辑。
    var pendingCircleCropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingTallCropUri   by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingShelfCropUri  by remember { mutableStateOf<android.net.Uri?>(null) }

    // ── 第三步：CharacterHeroCard 的 moodType/energy/relationshipStage 接入真实数据源 ──
    // 2.2 修复：mood/energy 改为订阅 PresenceViewModel.uiState.presenceMap，
    // 与 WorldScreen/CharacterScreen 走同一条响应式链路（Event 驱动 + 30 分钟
    // 闲置衰减兜底），角色状态在别处（如聊天页）变化后本页会自动刷新，
    // 不再是进入页面那一刻的一次性快照。
    // 注意：PresenceState.moodLabel 是中文展示字符串（"平静"/"专注"…），不是
    // MoodType 枚举——用公共反向映射 moodTypeFromLabel() 还原，未命中（角色
    // 还没有任何 mood 记录）时为 null，MoodCandle 按原逻辑不显示。
    val presenceUiState by presenceViewModel.uiState.collectAsStateWithLifecycle()
    val cachedPresenceState = presenceUiState.presenceMap[characterId]
    val cachedMoodType = cachedPresenceState?.moodLabel
        ?.let { com.zaijian.zhoumuyun.domain.moodTypeFromLabel(it) }
    val cachedEnergy = cachedPresenceState?.energy ?: -1

    // W14 P0 问题1修复：HeroCard 的 statusText/statusType/activityHint 原先只读
    // 静态 DefaultPresenceStates（见上方已删除的旧 presence 赋值），即使
    // cachedPresenceState（上面两行）已经是实时数据、也已用于 moodType/energy，
    // Hero 卡片的状态文案却从未接入，导致聊天后回到详情页状态不会更新。
    // 改为与 FamilyScreen（审查报告问题26修复）同款三级回退链：实时
    // cachedPresenceState → 静态 DefaultPresenceStates → 合成默认值（区分
    // 女儿 id>=1000 / 母亲角色的默认文案），直接复用上面已取好的
    // cachedPresenceState，不重复查一次 presenceMap。
    val presence = cachedPresenceState
        ?: DefaultPresenceStates.find { it.characterId == characterId }
        ?: if (characterId >= 1000) {
            PresenceState(
                characterId = characterId,
                statusText  = "刚刚到来",
                statusType  = StatusType.IDLE,
                lastUpdated = 0L,
            )
        } else {
            PresenceState(
                characterId = characterId,
                statusText  = "—",
                statusType  = StatusType.OFFLINE,
                lastUpdated = 0L,
            )
        }
    // relationshipStage：Hero 卡片订阅关系流，同时通过 sharedRelState 传递给
    // RelationshipPanel 复用，避免重复订阅同一 Room 查询（P2-31 修复）。
    // E0 分层收口：改走 RelationshipViewModel.observeRelationTo()——内部已含
    // `.catch{}` 兜底为 null，Room 查询异常不会再经 collectAsStateWithLifecycle
    // 传播导致本页重组崩溃。
    val heroRelFlow = remember(characterId) {
        relationshipViewModel.observeRelationTo("user", characterId.toString())
    }
    val heroRelState by heroRelFlow.collectAsStateWithLifecycle(initialValue = null)
    val heroBondStage = heroRelState?.stage?.let { stageName ->
        runCatching { com.zaijian.zhoumuyun.ui.design.BondStage.valueOf(stageName) }.getOrNull()
    }

    val avatarCirclePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) pendingCircleCropUri = uri }

    val avatarTallPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) pendingTallCropUri = uri }

    val avatarShelfPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) pendingShelfCropUri = uri }

    // 圆形头像：选图 → 裁剪（CIRCLE）→ 直接保存，只影响圆形
    pendingCircleCropUri?.let { uri ->
        com.zaijian.zhoumuyun.ui.component.AvatarCropDialog(
            uri       = uri,
            shape     = com.zaijian.zhoumuyun.ui.component.CropShape.CIRCLE,
            onConfirm = { params ->
                identityViewModel.onAvatarCropCirclePicked(
                    uri               = uri,
                    context           = context,
                    normalizedOffsetX = params.normalizedOffsetX,
                    normalizedOffsetY = params.normalizedOffsetY,
                    scale             = params.scale,
                )
                pendingCircleCropUri = null
            },
            onDismiss = { pendingCircleCropUri = null },
        )
    }

    // 公馆头像：选图 → 裁剪（TALL_RECT，照抄公馆现有取景框比例）→ 直接保存，只影响公馆
    pendingTallCropUri?.let { uri ->
        com.zaijian.zhoumuyun.ui.component.AvatarCropDialog(
            uri       = uri,
            shape     = com.zaijian.zhoumuyun.ui.component.CropShape.TALL_RECT,
            onConfirm = { params ->
                identityViewModel.onAvatarCropTallPicked(
                    uri               = uri,
                    context           = context,
                    normalizedOffsetX = params.normalizedOffsetX,
                    normalizedOffsetY = params.normalizedOffsetY,
                    scale             = params.scale,
                )
                pendingTallCropUri = null
            },
            onDismiss = { pendingTallCropUri = null },
        )
    }

    // 书架头像：选图 → 裁剪（TALL_RECT，取景框形状/比例沿用公馆同一套）→ 直接保存，只影响书架
    pendingShelfCropUri?.let { uri ->
        com.zaijian.zhoumuyun.ui.component.AvatarCropDialog(
            uri       = uri,
            shape     = com.zaijian.zhoumuyun.ui.component.CropShape.TALL_RECT,
            onConfirm = { params ->
                identityViewModel.onAvatarCropShelfPicked(
                    uri               = uri,
                    context           = context,
                    normalizedOffsetX = params.normalizedOffsetX,
                    normalizedOffsetY = params.normalizedOffsetY,
                    scale             = params.scale,
                )
                pendingShelfCropUri = null
            },
            onDismiss = { pendingShelfCropUri = null },
        )
    }

    // 头像错误提示
    val avatarError = identityState.avatarError
    if (avatarError != null) {
        LaunchedEffect(avatarError) {
            android.widget.Toast.makeText(context, avatarError, android.widget.Toast.LENGTH_SHORT).show()
            identityViewModel.clearAvatarError()
        }
    }

    // 【Phase 8】初始化 MemoryViewModel（collectAsState 已下移到记忆 Tab 内，避免无关 Tab 重组）
    LaunchedEffect(characterId, "memory") {
        try { memoryViewModel.init(characterId) }
        catch (e: Exception) { android.util.Log.e("CharDetail", "Memory init failed", e) }
    }

    // 【Phase 15】初始化 GoalViewModel
    // goalDraft 必须在根收集：GoalDraftSheet 渲染在 LazyColumn 外的顶层 Box 中
    LaunchedEffect(characterId, "goal") {
        try { goalViewModel.init(characterId) }
        catch (e: Exception) { android.util.Log.e("CharDetail", "Goal init failed", e) }
    }
    val goalDraft by goalViewModel.draft.collectAsStateWithLifecycle()

    // 【1.2 修复】初始化 PregnancyViewModel（collectAsState 已下移到孕育 Tab 内，避免无关 Tab 重组）
    LaunchedEffect(characterId, "pregnancy") {
        try { pregnancyViewModel.init(characterId) }
        catch (e: Exception) { android.util.Log.e("CharDetail", "Pregnancy init failed", e) }
    }

    // 主 Tab：0 = 记忆  1 = 能力  2 = 人设  3 = 目标（★ Phase 15 新增）
    // UI S4 修复：Tab 选中位置在进程死亡后应能恢复，改用 rememberSaveable
    var mainTab by rememberSaveable { mutableIntStateOf(initialTab ?: 0) }

    // P1-31 修复：navigateSingle 使用 launchSingleTop=true，当 CharacterDetail
    // 已在栈顶时 NavBackStackEntry 被复用而非重建，rememberSaveable 保留旧值，
    // 传入的新 initialTab 被忽略。通过 LaunchedEffect 监听 initialTab 变化，
    // 非 null 时主动覆盖 mainTab，使通知中心"去看看"→关系 Tab 等跳转生效。
    // initialTab 为 null（普通导航，无指定 Tab）时不干预，保留用户上次的手动选择。
    LaunchedEffect(initialTab) {
        initialTab?.let { mainTab = it }
    }

    // 是否显示「孕育」Tab（仅对母亲角色）
    val showPregnancyTab = isDaughterMother(characterId)
    // Stage C：日程 Tab 的索引随「孕育」Tab 是否存在而浮动（与 MainTabRow 的 buildList 顺序保持一致：
    // 记忆0 能力1 人设2 目标3 关系4 [孕育5] 日程5或6 文件6或7）
    val scheduleTabIndex = if (showPregnancyTab) 6 else 5
    // 记忆子 Tab：0=全部 1=重要 2=关于我 3=关于他
    var memoryTab by rememberSaveable { mutableIntStateOf(0) }
    // Phase 30 方案三：记忆主维度（0=全部 1=工作 2=情感）
    var memoryDimTab by rememberSaveable { mutableIntStateOf(0) }
    // 次维度 Chip：0=无 1=重要 （「关于我」去掉，改为主维度已有维度本身）
    var memorySecondaryChip by rememberSaveable { mutableIntStateOf(0) }

    // Phase 16：新增记忆 Dialog
    var showAddMemoryDialog by remember { mutableStateOf(false) }
    // 编辑记忆：非 null 时存储「正在编辑的 (id, 原始内容)」
    var editingMemory by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 能力子 Tab：0=能力 1=任务 2=技能 3=心迹
    var abilityTab by rememberSaveable { mutableIntStateOf(0) }

    val accentColor = character.accentColor
    val accentLight = character.accentLight()

    // Header 毛玻璃背景
    val headerBg = if (colors.isDark)
        colors.bgBase.copy(alpha = GlassOpacity.topBarDark)
    else
        colors.bgBase.copy(alpha = GlassOpacity.topBarLight)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        LazyColumn(
            modifier       = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
        ) {
            // ── 顶部 Header 占位 ──────────────────────────────
            // DetailTopBar 的实际高度 = statusBar高度 + topBarHeight(44dp)，
            // 这里用 WindowInsets.statusBars 动态读取状态栏高度，与顶栏保持一致，
            // 避免刘海/高状态栏设备上头像被顶栏底边遮挡。
            item {
                Spacer(
                    Modifier.height(
                        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                            + Spacing.topBarHeight
                    )
                )
            }

            // ── 角色卡（头像 + 名 + 状态 + 「发起对话」）──────
            item {
                CharacterHeroCard(
                    name        = character.name,
                    avatarUrl   = identityState.avatarUrl.ifEmpty { character.avatarUrl },
                    breathColor = character.breathColor,
                    accentColor = accentColor,
                    statusText  = presence.statusText,
                    statusType  = presence.statusType,
                    activityHint = presence.activityHint,
                    onStartChat = { onStartChat(characterId) },
                    onAvatarClick = { avatarCirclePickerLauncher.launch("image/*") },
                    onAvatarTallClick = { avatarTallPickerLauncher.launch("image/*") },
                    onAvatarShelfClick = { avatarShelfPickerLauncher.launch("image/*") },
                    avatarCropOffsetX = identityState.avatarCropCircleOffsetX,
                    avatarCropOffsetY = identityState.avatarCropCircleOffsetY,
                    avatarCropScale   = identityState.avatarCropCircleScale,
                    moodType          = cachedMoodType,
                    energy            = cachedEnergy,
                    relationshipStage = heroBondStage,
                    relatedProjects     = relatedProjects,
                    onProjectChipClick  = onNavigateToProjectDetail,
                )
            }

            // ── 主 Tab（记忆 / 能力）─────────────────────────
            item {
                MainTabRow(
                    selectedIndex    = mainTab,
                    accentColor      = accentColor,
                    showPregnancyTab = showPregnancyTab,
                    onSelect         = { index, label ->
                        if (label == "文件") {
                            // P2-30 修复：不将 mainTab 设为"文件"Tab 索引，仅导航。
                            // 返回后 mainTab 保持上一次有效 Tab，避免内容区空白。
                            onNavigateToFileVault(characterId)
                        } else {
                            mainTab = index
                        }
                    },
                )
            }

            // ── 记忆模块（Phase 8：接入 MemoryViewModel 真实数据）──
            // A-4：memoryState 的 collectAsState 已下移到 MemoryTabContent 内部
            if (mainTab == 0) {
                item {
                    MemoryTabContent(
                        memoryViewModel     = memoryViewModel,
                        accentColor         = accentColor,
                        memoryDimTab        = memoryDimTab,
                        memorySecondaryChip = memorySecondaryChip,
                        onDimTabChange      = { idx ->
                            memoryDimTab = idx
                            memorySecondaryChip = 0
                            val filter = when (idx) {
                                1 -> MemoryFilter.WORK
                                2 -> MemoryFilter.EMOTION
                                else -> MemoryFilter.ALL
                            }
                            memoryViewModel.setFilter(filter)
                        },
                        onSecondaryChipChange = { chipIdx ->
                            memorySecondaryChip = chipIdx
                            val baseFilter = when (memoryDimTab) {
                                1 -> MemoryFilter.WORK
                                2 -> MemoryFilter.EMOTION
                                else -> MemoryFilter.ALL
                            }
                            // 待处理报告 9-6 关联修复：chipIdx==2（"关于世界"）仅在
                            // memoryDimTab==0（全部）下由 MemorySecondaryChips 暴露，
                            // 映射到此前零调用的 MemoryFilter.ABOUT_WORLD。
                            memoryViewModel.setFilter(
                                when (chipIdx) {
                                    1    -> MemoryFilter.IMPORTANT
                                    2    -> MemoryFilter.ABOUT_WORLD
                                    else -> baseFilter
                                }
                            )
                        },
                        onShowAddDialog  = { showAddMemoryDialog = true },
                        onEditMemory     = { id, content -> editingMemory = id to content },
                        characterName    = character.name,
                    )
                }
            }

            // ── 能力模块 ─────────────────────────────────────
            if (mainTab == 1) {
                item {
                    AbilitySubTabRow(
                        selectedIndex = abilityTab,
                        accentColor   = accentColor,
                        onSelect      = { abilityTab = it },
                    )
                    Spacer(Modifier.height(Spacing.md))
                }

                when (abilityTab) {
                    0 -> item {
                        // 擅长领域标签墙接通真实数据修复：原先 getSkillTags(characterId)
                        // 是硬编码占位符，改为 identityViewModel.skillTags——
                        // 响应式读取 promoted_skill_tags 表，晋升发生后标签墙无需
                        // 手动刷新即可自动更新（与 identityState 用同一套
                        // collectAsStateWithLifecycle 模式）。
                        val skillTags by identityViewModel.skillTags.collectAsStateWithLifecycle()
                        AbilityPanel(
                            tags        = skillTags,
                            accentColor = accentColor,
                            accentLight = accentLight,
                        )
                    }
                    // Window C 缺口2：技能管理面板
                    2 -> item {
                        SkillTabContent(
                            characterId       = characterId,
                            accentColor       = accentColor,
                            skillListViewModel = skillListViewModel,
                        )
                    }
                    // Window D-4：能力面板（原"任务"子Tab占位符，现为Agent能力面板）
                    1 -> item {
                        CapabilityPanelContent(
                            characterId              = characterId,
                            accentColor              = accentColor,
                            capabilityPanelViewModel = capabilityPanelViewModel,
                        )
                    }
                    // 窗口7贯通：心迹面板（observeTimeline → ContentBlockAdapter → ContentBlockRenderer）
                    3 -> item {
                        AgentActivityTimelinePanel(
                            characterId            = characterId,
                            accentColor            = accentColor,
                            agentActivityViewModel = agentActivityViewModel,
                        )
                    }
                }
            }

            // ── 人设模块 ─────────────────────────────────────
            if (mainTab == 2) {
                item {
                    IdentityPanel(
                        state       = identityState,
                        accentColor = accentColor,
                        onPersonaChange           = identityViewModel::onPersonaChange,
                        onSpeechStyleChange       = identityViewModel::onSpeechStyleChange,
                        onAttitudeToUserChange    = identityViewModel::onAttitudeToUserChange,
                        onCustomSystemPromptChange = identityViewModel::onCustomSystemPromptChange,
                        onAddBoundary             = identityViewModel::addBoundary,
                        onRemoveBoundary          = identityViewModel::removeBoundary,
                        onUpdateBoundary          = identityViewModel::updateBoundary,
                        onAddCoreBelief           = identityViewModel::addCoreBelief,
                        onRemoveCoreBelief        = identityViewModel::removeCoreBelief,
                        onUpdateCoreBelief        = identityViewModel::updateCoreBelief,
                        onSave      = identityViewModel::save,
                        onCoreWoundChange         = identityViewModel::onCoreWoundChange,
                        onCoreDesireChange        = identityViewModel::onCoreDesireChange,
                        onMaskTriggerChange       = identityViewModel::onMaskTriggerChange,
                        onPrivatePersonaChange    = identityViewModel::onPrivatePersonaChange,
                        onPrivateStyleChange      = identityViewModel::onPrivateStyleChange,
                        onPrivateExamplesChange   = identityViewModel::onPrivateExamplesChange,
                        onSituationRulesChange    = identityViewModel::onSituationRulesChange,
                        onDeviationSignalsChange  = identityViewModel::onDeviationSignalsChange,
                    onLikesChange              = identityViewModel::onLikesChange,
                    onDislikesChange           = identityViewModel::onDislikesChange,
                    onRelationshipsChange      = identityViewModel::onRelationshipsChange,
                    onSoulNoteChange           = identityViewModel::onSoulNoteChange,
                    onNarrativeMemoryChange    = identityViewModel::onNarrativeMemoryChange,
                    onUserImpressionChange     = identityViewModel::onUserImpressionChange,
                    onUndoLastNoteEdit         = identityViewModel::undoLastNoteEdit,
                    lastEditedNoteField        = identityState.lastEditedNoteField,
                    // v1.36 问题3：用户身份设定（性别 + 关系称谓）
                    onUserGenderChange           = identityViewModel::onUserGenderChange,
                    onUserRoleLabelPrivateChange = identityViewModel::onUserRoleLabelPrivateChange,
                    onUserRoleLabelPublicChange  = identityViewModel::onUserRoleLabelPublicChange,
                    onPublicPrivacyReasonChange  = identityViewModel::onPublicPrivacyReasonChange,
                    )
                }
            }

            // ── 目标模块（★ Phase 15 新增）────────────────────
            // A-4：goalState 的 collectAsState 已下移到此 item 内，仅在 Tab 可见时订阅
            if (mainTab == 3) {
                item {
                    val goalState by goalViewModel.uiState.collectAsStateWithLifecycle()
                    // 批次4 4-1修复：消费 goalState.error，与 pregnancyState.errorMessage 范式对齐。
                    // 原代码 saveDraft/updateProgress/deactivate/delete 失败时设置 error 但无 UI 消费，
                    // 保存按钮永远显示"保存目标"，用户不知道为什么编辑面板重新弹开。
                    LaunchedEffect(goalState.error) {
                        val msg = goalState.error
                        if (msg != null) {
                            snackbarHostState.showSnackbar(msg)
                            goalViewModel.clearError()
                        }
                    }
                    GoalPanel(
                        state       = goalState,
                        accentColor = accentColor,
                        onOpenNew   = goalViewModel::openNewDraft,
                        onOpenEdit  = goalViewModel::openEditDraft,
                        onDelete     = goalViewModel::delete,
                        onDeactivate = goalViewModel::deactivate,
                        onProgressChange = goalViewModel::updateProgress,
                        // Phase 27：跳转到完整学习闭环管理页
                        onNavigateToGoals = { onNavigateToGoals(characterId) },
                        // P6 专长进化系统：跳转到专长档案页
                        onNavigateToSpecialty = { onNavigateToSpecialty(characterId) },
                        // U1 修复：从专长页入竞赛页
                        onNavigateToCompetition = onNavigateToCompetition,
                    )
                }
            }

            // ── 关系模块（Phase 9：六维雷达图 + 阶段展示；Phase 17：接入真实数据）────
            if (mainTab == 4) {
                item {
                    RelationshipPanel(
                        character     = character,
                        accentColor   = accentColor,
                        characterIdStr = character.id.toString(),
                        onNavigateToTimeline = onNavigateToTimeline,
                        relationshipViewModel = relationshipViewModel,
                        sharedRelState = heroRelState,
                    )
                }
            }

            // ── 孕育模块（1.2 修复：PregnancyPanel 重新接入入口）────────────────────
            // A-4：pregnancyState 的 collectAsState 已下移到此 item 内，仅在 Tab 可见时订阅
            if (mainTab == 5 && isDaughterMother(characterId)) {
                item {
                    val pregnancyState by pregnancyViewModel.uiState.collectAsStateWithLifecycle()
                    // W6-01 UI 展示补全：confirmTerminate() 失败时 errorMessage 非 null，
                    // 弹一次 Snackbar 告知用户，展示完调用 clearErrorMessage() 清空避免重复弹出。
                    LaunchedEffect(pregnancyState.errorMessage) {
                        val msg = pregnancyState.errorMessage
                        if (msg != null) {
                            snackbarHostState.showSnackbar(msg)
                            pregnancyViewModel.clearErrorMessage()
                        }
                    }
                    PregnancyPanel(
                        state       = pregnancyState,
                        accentColor = accentColor,
                        onRequestTerminate  = pregnancyViewModel::requestTerminate,
                        onDismissTerminate  = pregnancyViewModel::dismissTerminateConfirm,
                        onConfirmTerminate  = pregnancyViewModel::confirmTerminate,
                        // 批次3 3-6修复：原直接传 onNavigateToCharacterDetail(record.characterId)
                        // 传的是母亲ID，导航到当前页面自身（无操作）。改为根据 record.isDaughter
                        // 查子代ID再导航（男孩无子代角色不响应点击）。
                        onBirthRecordClick = { record ->
                            if (record.isDaughter) {
                                pregnancyScope.launch {
                                    val daughterId = runCatching {
                                        // E0 分层收口：原 AppContainer.instance
                                        // .daughterCharacterRepo.getByMother()，
                                        // 改走 DaughterCharacterViewModel.getDaughterIdByMother()。
                                        daughterCharacterViewModel
                                            .getDaughterIdByMother(record.characterId)
                                    }.getOrNull()
                                    if (daughterId != null) {
                                        onNavigateToCharacterDetail(daughterId)
                                    } else {
                                        ZLog.w(
                                            "CharacterDetailScreen",
                                            "生育记录点击：未找到母亲 characterId=${record.characterId} 的子代角色，不响应点击",
                                        )
                                    }
                                }
                            }
                            // 男孩场景：无子代角色，不响应点击
                        },
                    )
                }
            }

            // ── 日程模块（Stage C：v47_stage8 个人日程视图）──────────────────
            if (mainTab == scheduleTabIndex) {
                item {
                    PersonalScheduleTabContent(
                        characterId = characterId,
                        accentColor = accentColor,
                    )
                }
            }

            // ── 文件库模块：Tab 点击直接导航，此处无需占位 ─────────────────

            item { Spacer(Modifier.navigationBarsPadding()) }
        }

        // ── 固定顶栏（毛玻璃）────────────────────────────────
        // 窗口4：统一为 DetailTopBar，楼层渐变通过 backgroundBrush 参数传入
        val floorGradientBrush = character.floor?.let { f ->
            val (start, end) = floorGradientColors(f, colors.isDark)
            Brush.verticalGradient(colors = listOf(start, end))
        }
        DetailTopBar(
            title           = character.name,
            onBack          = onBack,
            headerBg        = headerBg,
            modifier        = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            backgroundBrush  = floorGradientBrush,
        )

        // ── Phase 16：新增记忆 Dialog ─────────────────────────
        if (showAddMemoryDialog) {
            AddMemoryDialog(
                accentColor = accentColor,
                onConfirm   = { content ->
                    memoryViewModel.addMemory(content)
                    showAddMemoryDialog = false
                },
                onDismiss   = { showAddMemoryDialog = false },
            )
        }

        // ── 2.4 编辑记忆 Dialog ───────────────────────────────
        editingMemory?.let { (memId, originalContent) ->
            EditMemoryDialog(
                initialContent = originalContent,
                accentColor    = accentColor,
                onConfirm      = { newContent ->
                    memoryViewModel.updateContent(memId, newContent)
                    editingMemory = null
                },
                onDismiss      = { editingMemory = null },
            )
        }

        // ── Phase 15：目标草稿 BottomSheet（提升至顶层 Box，确保全屏遮罩正确叠加）──
        goalDraft?.let { draft ->
            GoalDraftSheet(
                draft               = draft,
                accentColor         = accentColor,
                activeProjects      = activeProjects,
                onTitleChange       = goalViewModel::onDraftTitleChange,
                onDescriptionChange = goalViewModel::onDraftDescriptionChange,
                onPriorityChange    = goalViewModel::onDraftPriorityChange,
                onHorizonChange     = goalViewModel::onDraftHorizonChange,
                onProjectChange     = goalViewModel::onDraftProjectChange,
                onProgressChange    = goalViewModel::onDraftProgressChange,
                onSave              = goalViewModel::saveDraft,
                onDismiss           = goalViewModel::dismissDraft,
            )
        }

        // W6-01 UI 展示补全：本页无 Scaffold，手动叠加 SnackbarHost 于根 Box 底部。
        // 视觉样式与 ChatScreen.kt 的自定义 Snackbar 保持一致（同一套设计系统配色）。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier  = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = Spacing.md),
        ) { data ->
            Snackbar(
                snackbarData   = data,
                containerColor = colors.bgCard,
                contentColor   = colors.textPrimary,
                shape          = RoundedCornerShape(12.dp),
            )
        }
    }
}

@Preview(
    name           = "CharacterDetail · Dark",
    showBackground = true,
    widthDp        = 390,
    heightDp       = 844,
)
@Composable
private fun PreviewDetailDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) {
        CharacterDetailScreen(characterId = 1)
    }
}

@Preview(
    name           = "CharacterDetail · Light",
    showBackground = true,
    widthDp        = 390,
    heightDp       = 844,
)
@Composable
private fun PreviewDetailLight() {
    ZaijianTheme(appTheme = AppTheme.LIGHT) {
        CharacterDetailScreen(characterId = 2)
    }
}

