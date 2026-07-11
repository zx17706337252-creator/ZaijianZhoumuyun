package com.zaijian.zhoumuyun.ui.screen.characterdetail


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.CharacterGoalEntity
import com.zaijian.zhoumuyun.data.db.entity.GoalHorizon
import com.zaijian.zhoumuyun.ui.viewmodel.GoalDraft
import com.zaijian.zhoumuyun.ui.viewmodel.GoalViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.IdentityViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryFilter
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryUiItem
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.PregnancyViewModel
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.data.model.isDaughterMother
import com.zaijian.zhoumuyun.ui.theme.GoldDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.FloorEnum
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.model.accentLight
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.screen.PersonalScheduleTabContent
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.AppColors
import com.zaijian.zhoumuyun.ui.theme.AppTypography
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.Elevation
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.RingWidth
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.util.ZLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.compose.material3.FilterChip

@Composable
fun CharacterDetailScreen(
    characterId: Int,
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
    identityViewModel: IdentityViewModel = viewModel(),
    memoryViewModel: MemoryViewModel = viewModel(),
    goalViewModel: GoalViewModel = viewModel(),
    pregnancyViewModel: PregnancyViewModel = viewModel(),
    // 2.2 修复：mood/energy 改走 PresenceViewModel 响应式订阅，与
    // WorldScreen/CharacterScreen 同一套 uiState.presenceMap 数据源，
    // 不再是一次性快照——角色状态在别处变化后本页会自动刷新。
    presenceViewModel: com.zaijian.zhoumuyun.ui.viewmodel.PresenceViewModel = viewModel(),
) {
    val colors    = ZaijianTheme.colors
    val type      = ZaijianTheme.typography
    val context   = LocalContext.current

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
            // 报告第6条修复：原先在此处直接 AppDatabase.getInstance(context) 再
            // 手动 new 一个 DaughterCharacterRepository，是 Composable 直接
            // 触达持久化层。现改用 AppContainer 共享的 daughterCharacterRepo
            // （与 ChatViewModel/RoundtableViewModel 各自持有的同构造参数实例
            // 语义等价，只是不再各处重复构造）。
            daughterCharacter = com.zaijian.zhoumuyun.data.AppContainer.instance
                .daughterCharacterRepo.getCharacterConfig(characterId)
        }
        daughterLookupDone = true
    }

    val character = presetCharacter ?: daughterCharacter
    if (character == null) {
        if (!daughterLookupDone) {
            // 异步查询尚未完成（通常只持续一帧），暂不渲染，避免闪烁空白页
            return
        }
        // 角色不存在时显示空白页 + 返回按钮
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
        }
        return
    }
    val presence  = remember(characterId) { DefaultPresenceStates.find { it.characterId == characterId } }

    // 初始化 Identity ViewModel
    LaunchedEffect(characterId, "identity") { identityViewModel.init(characterId) }
    val identityState by identityViewModel.uiState.collectAsStateWithLifecycle()

    // ── 头像图片选择器 ────────────────────────────────────────
    // 待裁剪的 Uri：非 null 时显示 AvatarCropDialog
    // v46 头像重新设计：上传新图需要依次裁圆形（详情页）+ 竖长矩形
    // （公馆/书架共用），两次裁剪产出两套独立参数，不再是一次裁剪
    // 通吃所有场景。cropStep 记录当前处于哪一步；pendingCropUri 是
    // 两步共用的原图 uri（矩形步骤复用圆形步骤选的同一张图，不重新弹
    // 系统选图器）。
    var pendingCropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var cropStep by remember {
        mutableStateOf<com.zaijian.zhoumuyun.ui.component.CropShape?>(null)
    }

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
    // relationshipStage：复用 RelationshipPanel 同款 Room Flow 读取模式（UI M4 写法：
    // Composable 内直接访问 DB 实例，属于"局部视图专属数据"），仅服务 Hero 卡片迷你版
    // BondRibbon；完整版 BondRibbon 仍由 RelationshipPanel 自己的 relState 独立订阅，
    // 两处各自查询，不为此额外抽 ViewModel。
    // M-7 修复：AppDatabase.getInstance() 内部已是单例且固定使用 applicationContext，
    // 不会因 context 失效产生多实例或泄漏；这里改用 LocalContext.current.applicationContext
    // 进一步避免 remember 缓存到配置变更前的 Activity context 引用。
    val heroDb = remember { com.zaijian.zhoumuyun.data.db.AppDatabase.getInstance(context.applicationContext) }
    val heroRelFlow = remember(characterId) {
        heroDb.relationshipDao()
            .observeFrom("user")
            .map { list -> list.firstOrNull { it.toId == characterId.toString() } }
            .flowOn(Dispatchers.IO)
    }
    val heroRelState by heroRelFlow.collectAsStateWithLifecycle(initialValue = null)
    val heroBondStage = heroRelState?.stage?.let { stageName ->
        runCatching { com.zaijian.zhoumuyun.ui.design.BondStage.valueOf(stageName) }.getOrNull()
    }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingCropUri = uri
            cropStep = com.zaijian.zhoumuyun.ui.component.CropShape.CIRCLE  // 先裁圆形
        }
    }

    // 裁剪弹窗：先圆形（详情页）后竖长矩形（公馆/书架共用），两步各自
    // 产出一套参数。圆形确认后不立即保存，先把 uri 和圆形参数暂存，
    // 接着弹矩形步骤；矩形确认时才一次性把原图+两套参数都写入数据库
    // （避免中途取消矩形步骤时，圆形已经落库但矩形还是旧值的不一致
    // 状态——不过取消矩形步骤目前直接放弃整次上传，见下方 onDismiss）。
    var pendingCircleParams by remember {
        mutableStateOf<com.zaijian.zhoumuyun.ui.component.CropParams?>(null)
    }
    cropStep?.let { step ->
        pendingCropUri?.let { uri ->
            com.zaijian.zhoumuyun.ui.component.AvatarCropDialog(
                uri       = uri,
                shape     = step,
                onConfirm = { params ->
                    when (step) {
                        com.zaijian.zhoumuyun.ui.component.CropShape.CIRCLE -> {
                            // 圆形裁完，暂存参数，接着弹矩形步骤（同一张图）
                            pendingCircleParams = params
                            cropStep = com.zaijian.zhoumuyun.ui.component.CropShape.TALL_RECT
                        }
                        com.zaijian.zhoumuyun.ui.component.CropShape.TALL_RECT -> {
                            // 矩形也裁完了，一次性保存：原图 + 圆形参数 + 矩形参数。
                            // [v25 修复] 圆形与矩形参数必须通过同一次调用、
                            // 同一个协程写入，不能分两次调用（onAvatarCropped +
                            // onAvatarCropTallUpdated）——两次调用各自 launch
                            // 独立协程，完成顺序不确定，会导致后完成的协程用
                            // 默认值覆盖先完成的协程刚写好的正确矩形参数，
                            // 表现为"上传头像后拱形头像仍是占位大小"。
                            val circle = pendingCircleParams
                            if (circle != null) {
                                identityViewModel.onAvatarCropped(
                                    uri               = uri,
                                    context           = context,
                                    normalizedOffsetX = circle.normalizedOffsetX,
                                    normalizedOffsetY = circle.normalizedOffsetY,
                                    scale             = circle.scale,
                                    tallOffsetX       = params.normalizedOffsetX,
                                    tallOffsetY       = params.normalizedOffsetY,
                                    tallScale         = params.scale,
                                )
                            }
                            pendingCropUri = null
                            pendingCircleParams = null
                            cropStep = null
                        }
                        else -> {}
                    }
                },
                onDismiss = {
                    // 任一步取消都放弃整次上传，避免半套参数落库
                    pendingCropUri = null
                    pendingCircleParams = null
                    cropStep = null
                },
            )
        }
    }

    // 「仅重新调整公馆/书架取景」入口：图已经上传过，只想单独重调
    // 竖长矩形裁剪范围，不需要重新选图。由 CharacterHeroCard 之类的
    // 调用点在需要时把 tallRecropUri 设成当前 avatarUrl 触发。
    var tallRecropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    tallRecropUri?.let { uri ->
        com.zaijian.zhoumuyun.ui.component.AvatarCropDialog(
            uri       = uri,
            shape     = com.zaijian.zhoumuyun.ui.component.CropShape.TALL_RECT,
            onConfirm = { params ->
                identityViewModel.onAvatarCropTallUpdated(
                    normalizedOffsetX = params.normalizedOffsetX,
                    normalizedOffsetY = params.normalizedOffsetY,
                    scale             = params.scale,
                )
                tallRecropUri = null
            },
            onDismiss = { tallRecropUri = null },
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
    LaunchedEffect(characterId, "memory") { memoryViewModel.init(characterId) }

    // 【Phase 15】初始化 GoalViewModel
    // goalDraft 必须在根收集：GoalDraftSheet 渲染在 LazyColumn 外的顶层 Box 中
    LaunchedEffect(characterId, "goal") { goalViewModel.init(characterId) }
    val goalDraft by goalViewModel.draft.collectAsStateWithLifecycle()

    // 【1.2 修复】初始化 PregnancyViewModel（collectAsState 已下移到孕育 Tab 内，避免无关 Tab 重组）
    LaunchedEffect(characterId, "pregnancy") { pregnancyViewModel.init(characterId) }

    // 主 Tab：0 = 记忆  1 = 能力  2 = 人设  3 = 目标（★ Phase 15 新增）
    // UI S4 修复：Tab 选中位置在进程死亡后应能恢复，改用 rememberSaveable
    var mainTab by rememberSaveable { mutableIntStateOf(0) }

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

    // 能力子 Tab：0=能力 1=工具 2=任务
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
            // DetailHeader 的实际高度 = statusBar高度 + topBarHeight(44dp)，
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
                    statusText  = presence?.statusText ?: "",
                    statusType  = presence?.statusType ?: StatusType.OFFLINE,
                    activityHint = presence?.activityHint,
                    onStartChat = { onStartChat(characterId) },
                    onAvatarClick = { avatarPickerLauncher.launch("image/*") },
                    onAvatarLongClick = {
                        // 长按 = 不重新选图，仅重新调整公馆/书架取景范围，
                        // 复用当前已保存的原图（avatarUrl）
                        val currentUrl = identityState.avatarUrl
                        if (currentUrl.isNotEmpty()) {
                            tallRecropUri = android.net.Uri.parse(currentUrl)
                        }
                    },
                    avatarCropOffsetX = identityState.avatarCropCircleOffsetX,
                    avatarCropOffsetY = identityState.avatarCropCircleOffsetY,
                    avatarCropScale   = identityState.avatarCropCircleScale,
                    moodType          = cachedMoodType,
                    energy            = cachedEnergy,
                    relationshipStage = heroBondStage,
                    relatedProjects     = goalViewModel.relatedProjects.collectAsStateWithLifecycle().value,
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
                            memoryViewModel.setFilter(if (chipIdx == 1) MemoryFilter.IMPORTANT else baseFilter)
                        },
                        onShowAddDialog  = { showAddMemoryDialog = true },
                        onEditMemory     = { id, content -> editingMemory = id to content },
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
                        AbilityPanel(
                            tags        = skillTags,
                            accentColor = accentColor,
                            accentLight = accentLight,
                        )
                    }
                    1 -> item {
                        ToolsPanel(
                            tools       = toolItems,
                            accentLight = accentLight,
                            accentColor = accentColor,
                        )
                    }
                    2 -> item {
                        EmptyState(
                            text     = "有点卡住，先歇一歇",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xxl),
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
                    lastEditedNoteField        = identityViewModel.uiState.value.lastEditedNoteField,
                    )
                }
            }

            // ── 目标模块（★ Phase 15 新增）────────────────────
            // A-4：goalState 的 collectAsState 已下移到此 item 内，仅在 Tab 可见时订阅
            if (mainTab == 3) {
                item {
                    val goalState by goalViewModel.uiState.collectAsStateWithLifecycle()
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
                    )
                }
            }

            // ── 孕育模块（1.2 修复：PregnancyPanel 重新接入入口）────────────────────
            // A-4：pregnancyState 的 collectAsState 已下移到此 item 内，仅在 Tab 可见时订阅
            if (mainTab == 5 && isDaughterMother(characterId)) {
                item {
                    val pregnancyState by pregnancyViewModel.uiState.collectAsStateWithLifecycle()
                    PregnancyPanel(
                        state       = pregnancyState,
                        accentColor = accentColor,
                        onRequestTerminate  = pregnancyViewModel::requestTerminate,
                        onDismissTerminate  = pregnancyViewModel::dismissTerminateConfirm,
                        onConfirmTerminate  = pregnancyViewModel::confirmTerminate,
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
        DetailHeader(
            name     = character.name,
            headerBg = headerBg,
            onBack   = onBack,
            floor    = character.floor,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
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
                activeProjects      = goalViewModel.activeProjects.collectAsStateWithLifecycle().value,
                onTitleChange       = goalViewModel::onDraftTitleChange,
                onDescriptionChange = goalViewModel::onDraftDescriptionChange,
                onPriorityChange    = goalViewModel::onDraftPriorityChange,
                onHorizonChange     = goalViewModel::onDraftHorizonChange,
                onProjectChange     = goalViewModel::onDraftProjectChange,
                onSave              = goalViewModel::saveDraft,
                onDismiss           = goalViewModel::dismissDraft,
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

