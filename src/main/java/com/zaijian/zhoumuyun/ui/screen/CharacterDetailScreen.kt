package com.zaijian.zhoumuyun.ui.screen

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
import androidx.compose.material3.FilterChipDefaults

// ─────────────────────────────────────────────────────────────
//  MemoryEntry — 保留本地结构供 UI 组件使用（Phase 8 仅内部传参用）
// ─────────────────────────────────────────────────────────────

private data class MemoryEntry(
    val id: String,
    val content: String,
    val dateLabel: String,
    val isImportant: Boolean = false,
    val isCore: Boolean = false,
    /** true = 关于用户（PERSONAL domain），false = 关于角色/世界 */
    val aboutSelf: Boolean = true,
    /** Phase 17：衰减状态标签，null = 无需显示 */
    val decayLabel: String? = null,
    /** Phase 30 方案三：维度标签 */
    val domainLabel: String = "",
    /** Phase 30 方案三：维度色条颜色 (ARGB Long) */
    val domainColorArgb: Long = 0xFF9E9E9EL,
)

private fun MemoryUiItem.toEntry() = MemoryEntry(
    id              = id,
    content         = content,
    dateLabel       = dateLabel,
    isImportant     = isImportant,
    isCore          = isCore,
    aboutSelf       = aboutSelf,
    decayLabel      = decayLabel,
    domainLabel     = domainLabel,
    domainColorArgb = domainColorArgb,
)

private data class ToolItem(val icon: ImageVector, val label: String)
private val toolItems = listOf(
    ToolItem(Icons.Outlined.Search,      "搜索"),
    ToolItem(Icons.Outlined.Description, "文件"),
    ToolItem(Icons.Outlined.Code,        "代码"),
    ToolItem(Icons.Outlined.TableChart,  "表格"),
    ToolItem(Icons.Outlined.Email,       "邮件"),
)

private val skillTags = listOf("写作", "逻辑推理", "情绪陪伴", "信息整理", "头脑风暴")

// ─────────────────────────────────────────────────────────────
//  CharacterDetailScreen  — 角色详情页（Phase 4 Step 2）
//  设计规范 §15
//
//  两个顶级 Tab：
//    [记忆] 全部 / 重要 / 关于我 / 关于他
//    [能力] 能力 / 工具 / 任务（任务 Phase 5 完善）
//
//  进入方式：
//    书架单击书本（300ms bounceSpring，由 AppNavigation 控制）
//    公馆长按预览卡 → 「查看完整档案」
//    聊天页顶栏头像
// ─────────────────────────────────────────────────────────────

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
            val db = com.zaijian.zhoumuyun.data.db.AppDatabase.getInstance(context)
            val daughterRepo = com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository(db.daughterCharacterDao())
            daughterCharacter = daughterRepo.getCharacterConfig(characterId)
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
    // mood/energy：PresenceEngine 内存缓存，与 ChatViewModel.currentMood 同一数据源
    // （都是 getCachedPresence(characterId)?.mood），one-shot 快照读取，与本页 presence
    // 状态文案的新鲜度级别一致——这一页目前没有走 PresenceViewModel 的响应式更新链路。
    val cachedPresence = remember(characterId) {
        com.zaijian.zhoumuyun.ZaijianApp.sharedPresenceEngine?.getCachedPresence(characterId)
    }
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
                    moodType          = cachedPresence?.mood,
                    energy            = cachedPresence?.energy ?: -1,
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
                        onStartPregnancy    = pregnancyViewModel::startPregnancy,
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

// ─────────────────────────────────────────────────────────────
//  MemoryTabContent — A-4：将 memoryState 收集下移至此，
//  切换其他 Tab 时记忆状态变化不再触发根 Composable 重组
// ─────────────────────────────────────────────────────────────

@Composable
private fun MemoryTabContent(
    memoryViewModel:      MemoryViewModel,
    accentColor:          Color,
    memoryDimTab:         Int,
    memorySecondaryChip:  Int,
    onDimTabChange:       (Int) -> Unit,
    onSecondaryChipChange:(Int) -> Unit,
    onShowAddDialog:      () -> Unit,
    onEditMemory:         (String, String) -> Unit,
) {
    // ★ collectAsState 在此处执行，仅当 mainTab == 0 时该 Composable 存在
    val memoryState by memoryViewModel.uiState.collectAsStateWithLifecycle()

    Column {
        // Phase 30 方案三：两层过滤结构
        // 第一层（主维度 Tab）：全部 | 工作 | 情感
        MemoryDimTabRow(
            selectedIndex = memoryDimTab,
            accentColor   = accentColor,
            onSelect      = onDimTabChange,
        )
        // 第二层（次维度 FilterChip）：重要
        MemorySecondaryChips(
            dimIndex    = memoryDimTab,
            chipIndex   = memorySecondaryChip,
            accentColor = accentColor,
            onSelect    = onSecondaryChipChange,
        )
        Spacer(Modifier.height(Spacing.sm))

        if (memoryState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xxl),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color       = accentColor,
                    strokeWidth = 2.dp,
                    modifier    = Modifier.size(24.dp),
                )
            }
        } else {
            val entries = memoryState.items.map { it.toEntry() }

            if (entries.isEmpty()) {
                EmptyState(
                    text     = "还没有记忆，去聊聊吧",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xxl),
                )
            } else {
                entries.forEach { entry ->
                    MemoryRow(
                        entry        = entry,
                        accentColor  = accentColor,
                        onToggleStar = { memoryViewModel.toggleImportant(entry.id) },
                        onDelete     = { memoryViewModel.delete(entry.id) },
                        onEdit       = { onEditMemory(entry.id, entry.content) },
                        modifier     = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.screenHorizontal),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            AddButton(
                label       = "新增记忆",
                accentColor = accentColor,
                onClick     = onShowAddDialog,
                modifier    = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  AddMemoryDialog  — 手动新增记忆弹窗（Phase 16）
// ─────────────────────────────────────────────────────────────

@Composable
private fun AddMemoryDialog(
    accentColor: Color,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    var text by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = colors.bgCard,
        title = {
            Text(
                text  = "新增记忆",
                style = type.cardTitle,
                color = colors.textPrimary,
            )
        },
        text = {
            Column {
                Text(
                    text  = "手动记录一件重要的事，它会作为长期记忆保留。",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.sm))
                androidx.compose.material3.OutlinedTextField(
                    value         = text,
                    onValueChange = { text = it },
                    placeholder   = {
                        Text(
                            text  = "例：喜欢喝桂花乌龙，不吃辣",
                            style = type.body,
                            color = colors.textDisabled,
                        )
                    },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = accentColor,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor     = colors.textPrimary,
                        unfocusedTextColor   = colors.textPrimary,
                        cursorColor          = accentColor,
                    ),
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(
                    text  = "保存",
                    color = if (text.isNotBlank()) accentColor else colors.textDisabled,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.textSecondary)
            }
        },
    )
}

@Composable
private fun EditMemoryDialog(
    initialContent: String,
    accentColor: Color,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var text by remember { mutableStateOf(initialContent) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = colors.bgCard,
        title = {
            Text(
                text  = "编辑记忆",
                style = type.cardTitle,
                color = colors.textPrimary,
            )
        },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value         = text,
                onValueChange = { text = it },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = accentColor,
                    unfocusedBorderColor = colors.border,
                    focusedTextColor     = colors.textPrimary,
                    unfocusedTextColor   = colors.textPrimary,
                    cursorColor          = accentColor,
                ),
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(
                    text  = "保存",
                    color = if (text.isNotBlank()) accentColor else colors.textDisabled,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.textSecondary)
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  DetailHeader  — 返回 + 标题
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  floorGradientColors — 楼层光氛围（精修方案 v1.3 第5.2节）
//
//  FloorEnum.kt 注释原文：「二楼 — 最亮，暖白透光」「一楼 — 中等，暖黄透光」
//  「地下室 — 最暗，冷蓝/紫光」。这里按该设定给三档楼层各一组 light/dark
//  渐变端点色，强度遵循文档要求"亮度依次降低，地下室最静最私密"。
//  返回 (起始色, 结束色)，DetailHeader 用 verticalGradient 叠加在毛玻璃
//  纯色 headerBg 之上（第8节"先按只罩 Header"的范围约定）。
// ─────────────────────────────────────────────────────────────

private fun floorGradientColors(floor: FloorEnum, isDark: Boolean): Pair<Color, Color> {
    return when (floor) {
        FloorEnum.SECOND -> if (isDark)
            Color(0xFF3A3424) to Color(0xFF2A2418)   // 暖白，亮度最高（暗色模式下仍是三档里最亮的一档）
        else
            Color(0xFFFFFBF3) to Color(0xFFFBF7F0)
        FloorEnum.FIRST -> if (isDark)
            Color(0xFF2E2818) to Color(0xFF221E14)   // 暖黄，亮度中等
        else
            Color(0xFFFAF2E2) to Color(0xFFF5F0E8)
        FloorEnum.BASEMENT -> if (isDark)
            Color(0xFF1A1C28) to Color(0xFF14141C)   // 冷蓝紫，亮度最低，更静更私密
        else
            Color(0xFFE8E6F2) to Color(0xFFE0DCEC)
    }
}

@Composable
private fun DetailHeader(
    name: String,
    headerBg: Color,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    floor: FloorEnum? = null,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 楼层光氛围（精修方案 v1.3 第5.2节）：floor != null 时叠加一层楼层基调渐变，
    // 在原有毛玻璃纯色 headerBg 之上叠加，不替换——保留毛玻璃透明度行为，
    // 只是把"纯色"换成"带楼层冷暖倾向的渐变"。floor == null（角色数据异常兜底）
    // 时维持原有纯色 background，不强行画一个无意义的默认渐变。
    val floorGradientBrush = floor?.let { f ->
        val (start, end) = floorGradientColors(f, colors.isDark)
        Brush.verticalGradient(colors = listOf(start, end))
    }

    Box(
        modifier = modifier
            .then(
                if (floorGradientBrush != null)
                    Modifier.background(floorGradientBrush).background(headerBg)
                else
                    Modifier.background(headerBg)
            )
            .border(
                width = 0.5.dp,
                color = colors.borderSubtle,
                shape = RoundedCornerShape(0.dp),
            )
            .statusBarsPadding()
            .height(Spacing.topBarHeight),
    ) {
        Row(
            modifier          = Modifier.fillMaxSize().padding(horizontal = Spacing.sm),
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
            Text(
                text  = name,
                style = type.navTitle,
                color = colors.textPrimary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  CharacterHeroCard  — 头像 + 状态 + 「发起对话」
//  规范 §15：头像 80dp，3dp 状态环，16dp 光晕
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterHeroCard(
    name: String,
    avatarUrl: String,
    breathColor: Color,
    accentColor: Color,
    statusText: String,
    statusType: StatusType,
    activityHint: String?,
    onStartChat: () -> Unit,
    onAvatarClick: () -> Unit = {},
    // v46 新增：长按头像重新调整"公馆拱形/书架椭圆"取景范围（不重新
    // 选图，复用已上传的原图）。默认空实现，兼容未接入此功能的调用点。
    onAvatarLongClick: () -> Unit = {},
    // v46 头像重新设计：详情页圆形头像的裁剪参数，对应
    // CharacterIdentityEntity.avatarCropCircle*。默认 0f/0f/1f 与旧行为
    // 一致（居中、Crop 覆盖）。
    avatarCropOffsetX: Float = 0f,
    avatarCropOffsetY: Float = 0f,
    avatarCropScale: Float = 1f,
    // ── 精修方案 v1.3 第5.3/5.4节：MoodCandle / BondRibbon ──
    // 第二步（Token + 组件单独造）阶段新增三个可选参数，默认值不影响其他调用点。
    // 第三步第一步：本函数的唯一真实调用点（CharacterDetailScreen 角色卡）已接入
    // 真实数据源（PresenceEngine 缓存 mood/energy + Room relationship_states 表的
    // stage），见 CharacterDetailScreen 顶部 cachedPresence / heroBondStage 两处。
    /** 心情类型，null 表示不显示 MoodCandle */
    moodType: com.zaijian.zhoumuyun.data.engine.MoodType? = null,
    /** 精力值 0-100，-1 或超出范围表示未知（与 PresenceState.energy 的 -1 约定一致） */
    energy: Int = -1,
    /** 关系阶段，null 表示不显示 BondRibbon 迷你版 */
    relationshipStage: com.zaijian.zhoumuyun.ui.design.BondStage? = null,
    // ── 精修方案 v1.3 第5.1/6节：「关联项目」WrapChipGroup ──
    /** 当前角色参与的活跃项目列表，空列表表示不显示这一区块 */
    relatedProjects: List<com.zaijian.zhoumuyun.data.db.entity.ProjectEntity> = emptyList(),
    /** 点击某个项目芯片时触发，传入被点击项目的 id（跳转项目详情页） */
    onProjectChipClick: (String) -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 头像区域：可点击，叠加相机图标
        Box(
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(
                modifier = Modifier.combinedClickable(
                    onClick     = onAvatarClick,
                    onLongClick = onAvatarLongClick,
                ),
            ) {
                BreathingAvatar(
                    imageUrl     = avatarUrl,
                    breathColor  = breathColor,
                    statusType   = statusType,
                    size         = AvatarSize.detail,
                    ringWidth    = RingWidth.detail,
                    glowRadius   = 16.dp,
                    enableBreath = statusType != StatusType.OFFLINE,
                    cropOffsetX  = avatarCropOffsetX,
                    cropOffsetY  = avatarCropOffsetY,
                    cropScale    = avatarCropScale,
                )
            }
            // 相机编辑角标
            // UI M12 修复：视觉圆形保持 26dp，外层 Box 扩大热区到 48dp
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(onClick = onAvatarClick)
                    .wrapContentSize(Alignment.Center),
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.CameraAlt,
                        contentDescription = "更换头像",
                        tint               = Color.White,
                        modifier           = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        Text(
            text  = name,
            style = type.titleBold,
            color = colors.textPrimary,
        )

        Spacer(Modifier.height(Spacing.xs))

        if (statusText.isNotEmpty()) {
            Text(
                text  = statusText,
                style = type.caption,
                color = colors.textSecondary,
            )
        }

        if (activityHint != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text  = activityHint,
                style = type.caption,
                color = accentColor,
            )
        }

        // MoodCandle / BondRibbon 迷你版：均为可选展示，仅当调用方传入对应参数时渲染。
        // moodType 为 null（PresenceEngine 缓存里该角色还没有任何 mood 记录，例如从未
        // 聊过天）或 relationshipStage 为 null（关系表里还没有该角色的行）时分别不显示，
        // 这是正常的"数据还没产生"状态，不是 bug。
        if (moodType != null) {
            Spacer(Modifier.height(Spacing.sm))
            com.zaijian.zhoumuyun.ui.design.MoodCandle(
                mood = moodType,
                energy = energy,
            )
        }
        if (relationshipStage != null) {
            Spacer(Modifier.height(Spacing.sm))
            com.zaijian.zhoumuyun.ui.design.BondRibbon(
                stage = relationshipStage,
                accentColor = accentColor,
                showLabels = false, // Hero 卡片用迷你版，仅刻度，不显示阶段文字标签（精修方案 v1.3 第5.4节）
            )
        }

        // 「关联项目」WrapChipGroup：展示当前角色参与的活跃项目（精修方案 v1.3 第5.1/6节）。
        // 色点固定取当前角色自己的 accentColor（产品侧确认的简化决策——不取项目 OWNER 的
        // accentColor，因为一个项目可能没有单一 OWNER，固定用角色自身颜色不依赖这个前提，
        // 也更直观：这一排芯片本来就是"挂在这个角色身上"的标签）。
        // ChipItem.selected 这里固定传 false：本场景是纯展示，不是筛选器，没有"选中"语义；
        // 选中态实心反色的视觉留给真正的筛选场景（如 GridTabBar 旁边的标签筛选）使用。
        if (relatedProjects.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            com.zaijian.zhoumuyun.ui.design.WrapChipGroup(
                chips = relatedProjects.map { project ->
                    com.zaijian.zhoumuyun.ui.design.ChipItem(
                        label = project.title,
                        selected = false,
                        ownerAccent = accentColor,
                    )
                },
                onClick = { index -> onProjectChipClick(relatedProjects[index].id) },
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
            )
        }

        Spacer(Modifier.height(Spacing.lg))

        // 「发起对话」全宽按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(accentColor)
                .clickable { onStartChat() }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = "发起对话",
                style = type.button,
                color = Color.White,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  主 Tab 行（记忆 / 能力）
//
//  v47_stage8 修订：Tab 数量随「日程」加入达到 7~8 个，单行 weight(1f)
//  会被挤得很窄。改为固定 4 列的两行网格：
//    - 两行共用同一份列宽（每列 weight 都按 4 列计算），
//      行数不满 4 个时用「不可见的 weight(1f) 占位」补齐，
//      保证第二行的格子和第一行严格对齐，不会因为列数变少而被拉宽。
//    - 选中态的格子加一点点投影，制造轻微浮起的层次感（Elevation.card）。
// ─────────────────────────────────────────────────────────────

private const val MAIN_TAB_COLUMNS = 4

@Composable
private fun MainTabRow(
    selectedIndex: Int,
    accentColor: Color,
    showPregnancyTab: Boolean,
    onSelect: (Int, String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val tabs = buildList {
        addAll(listOf("记忆", "能力", "人设", "目标", "关系"))
        if (showPregnancyTab) add("孕育")
        // Stage C：全局日程视图（v47_stage8）新增的个人日程 Tab，
        // 紧邻「文件」之前；「文件」本身点击即跳转，不占用 mainTab 索引判断，
        // 因此在它前面插入新 Tab 不会影响既有 Tab 的索引语义。
        add("日程")
        add("文件")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        tabs.withIndex().chunked(MAIN_TAB_COLUMNS).forEach { rowEntries ->
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                rowEntries.forEach { (index, label) ->
                    val selected = selectedIndex == index
                    MainTabCell(
                        label       = label,
                        selected    = selected,
                        accentColor = accentColor,
                        colors      = colors,
                        type        = type,
                        modifier    = Modifier.weight(1f),
                        onClick     = { onSelect(index, label) },
                    )
                }
                // 末行格子数不足整列时，用占位 weight(1f) 补齐剩余列，
                // 让已有格子的宽度与上一行保持一致（不被拉宽铺满）。
                repeat(MAIN_TAB_COLUMNS - rowEntries.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    Spacer(Modifier.height(Spacing.md))
}

/**
 * 单个 Tab 格子。抽成独立 Composable 是为了两行复用同一份样式，
 * 同时给选中态加一点点投影（Elevation.card = 2dp），让选中的格子有
 * 「轻轻浮起」的层次感，弱化"两行方块"本身偏机械的网格感。
 */
@Composable
private fun MainTabCell(
    label: String,
    selected: Boolean,
    accentColor: Color,
    colors: AppColors,
    type: AppTypography,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = if (selected) Elevation.card else 0.dp,
                shape     = RoundedCornerShape(Radius.sm),
            )
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (selected) {
                    // 选中态：用主题色的极淡底色替代普通卡片底色，
                    // 配合投影，比单纯加粗边框更有"被选中"的存在感。
                    accentColor.copy(alpha = if (colors.isDark) 0.16f else 0.10f)
                } else if (colors.isDark) {
                    colors.bgElevated
                } else {
                    colors.bgCard
                }
            )
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) accentColor else colors.border,
                shape = RoundedCornerShape(Radius.sm),
            )
            .clickable { onClick() }
            .padding(vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = type.button,
            // 选中态背景已改为 accentColor 的低透明度叠色（见上方 background 分支），
            // accentColor 文字直接叠在自己的淡色调上，对比度天然达标，无需再对照 bgElevated 验证。
            color = if (selected) accentColor else colors.textSecondary,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  MemoryDimTabRow — 记忆主维度 Tab（全部 / 工作 / 情感）（Phase 30 方案三）
// ─────────────────────────────────────────────────────────────

@Composable
private fun MemoryDimTabRow(
    selectedIndex: Int,
    accentColor: Color,
    onSelect: (Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    // 主维度：全部 / 工作 / 情感
    val tabs = listOf("全部", "工作", "情感")

    ScrollableTabRow(
        selectedTabIndex  = selectedIndex,
        containerColor    = Color.Transparent,
        contentColor      = accentColor,
        edgePadding       = Spacing.screenHorizontal,
        indicator         = { tabPositions ->
            if (selectedIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    color    = when (selectedIndex) {
                        1    -> Palette.Focused   // 工作蓝
                        2    -> Palette.SemanticEmotion   // 情感粉
                        else -> accentColor
                    },
                    height   = 2.dp,
                )
            }
        },
        divider = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.border)
            )
        },
    ) {
        tabs.forEachIndexed { index, label ->
            val tabAccent = when (index) {
                1    -> Palette.Focused
                2    -> Palette.SemanticEmotion
                else -> accentColor
            }
            Tab(
                selected = selectedIndex == index,
                onClick  = { onSelect(index) },
                text     = {
                    Text(
                        text  = label,
                        style = type.caption.copy(
                            fontWeight = if (selectedIndex == index) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = if (selectedIndex == index) tabAccent else colors.textSecondary,
                    )
                },
                selectedContentColor   = tabAccent,
                unselectedContentColor = colors.textSecondary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  MemorySecondaryChips — 次维度 FilterChip（重要）（Phase 30 方案三）
//
//  只在有意义时展示：
//    - 全部(0) → 显示「重要」Chip
//    - 工作(1) → 显示「重要」Chip
//    - 情感(2) → 显示「重要」Chip
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemorySecondaryChips(
    dimIndex:    Int,
    chipIndex:   Int,
    accentColor: Color,
    onSelect:    (Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 根据主维度决定次维度 Chip 组
    val chips = listOf("重要")

    FlowRow(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        chips.forEachIndexed { index, label ->
            val chipAccent = when (dimIndex) {
                1    -> Palette.Focused
                2    -> Palette.SemanticEmotion
                else -> accentColor
            }
            val selected = chipIndex == index + 1
            FilterChip(
                selected = selected,
                onClick  = { onSelect(if (selected) 0 else index + 1) },
                label    = {
                    Text(
                        text  = label,
                        style = type.label,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor     = chipAccent.copy(alpha = 0.15f),
                    selectedLabelColor         = chipAccent,
                    containerColor             = Color.Transparent,
                    labelColor                 = colors.textSecondary,
                    selectedLeadingIconColor   = chipAccent,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled         = true,
                    selected        = selected,
                    borderColor     = colors.border,
                    selectedBorderColor = chipAccent.copy(alpha = 0.5f),
                    borderWidth     = 0.5.dp,
                    selectedBorderWidth = 1.dp,
                ),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  能力子 Tab（能力 / 工具 / 任务）
// ─────────────────────────────────────────────────────────────

@Composable
private fun AbilitySubTabRow(
    selectedIndex: Int,
    accentColor: Color,
    onSelect: (Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val tabs   = listOf("能力", "工具", "任务")

    ScrollableTabRow(
        selectedTabIndex  = selectedIndex,
        containerColor    = Color.Transparent,
        contentColor      = accentColor,
        edgePadding       = Spacing.screenHorizontal,
        indicator         = { tabPositions ->
            if (selectedIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    color    = accentColor,
                    height   = 2.dp,
                )
            }
        },
        divider = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.border)
            )
        },
    ) {
        tabs.forEachIndexed { index, label ->
            Tab(
                selected      = selectedIndex == index,
                onClick       = { onSelect(index) },
                text          = {
                    Text(
                        text  = label,
                        style = type.caption.copy(
                            fontWeight = if (selectedIndex == index) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = if (selectedIndex == index) accentColor else colors.textSecondary,
                    )
                },
                selectedContentColor   = accentColor,
                unselectedContentColor = colors.textSecondary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  MemoryRow  — 单条记忆
//  规范 §15：日期标签（右）/ 内容 / ⭐ 重要性标记
// ─────────────────────────────────────────────────────────────

@Composable
private fun MemoryRow(
    entry: MemoryEntry,
    accentColor: Color,
    onToggleStar: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // Phase 30 方案三：左侧 2dp 维度色条
    val stripColor = if (entry.domainLabel.isNotEmpty())
        Color(entry.domainColorArgb) else Color.Transparent

    // WorldCard 接入（精修方案 v1.3）：独立列表项，L0-L2 常态层交给
    // WorldCard。不传 ownerAccent——整页本就是单一角色的记忆列表，"归属
    // 谁"已经是页面级的已知信息；这里真正需要逐条区分的是"记忆维度"
    // （Phase 30 既有的左侧 stripColor 色条），保留它不动，避免和 L3
    // 身份脊在左侧出现两条并排竖线、互相抢视觉。
    WorldCard(modifier = modifier, cornerRadius = Radius.sm) {
        Row(verticalAlignment = Alignment.Top) {
            // 左侧维度色条
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        color = stripColor,
                        shape = RoundedCornerShape(topStart = Radius.sm, bottomStart = Radius.sm),
                    ),
            )
            // 右侧内容区（加回 padding）
            Row(
                modifier          = Modifier.weight(1f).padding(Spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
            // 内容（占满剩余宽度）
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = entry.content,
                    style = type.body,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = entry.dateLabel,
                        style = type.label,
                        color = colors.textDisabled,
                    )
                    // Phase 17：衰减状态标签
                    entry.decayLabel?.let { label ->
                        val (bgAlpha, textColor) = when (label) {
                            "7天到期"  -> 0.15f to Palette.SemanticDanger
                            "即将到期"  -> 0.12f to Palette.SemanticWarning
                            "即将清理" -> 0.12f to Palette.SemanticWarning
                            else       -> 0.10f to colors.textSecondary
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(textColor.copy(alpha = bgAlpha))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(label, style = type.label, color = textColor)
                        }
                    }
                }
            }

            Spacer(Modifier.width(Spacing.sm))

            // UI M12 修复：图标触摸热区扩大到 48dp×48dp（Android 最小触控建议），
            // 视觉尺寸（20dp）保持不变，外层 Box 吸收额外点击区域。
            // 编辑
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { onEdit() }
                    .wrapContentSize(Alignment.Center),
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Edit,
                    contentDescription = "编辑记忆",
                    tint               = colors.textDisabled,
                    modifier           = Modifier.size(20.dp),
                )
            }
            // 删除
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { onDelete() }
                    .wrapContentSize(Alignment.Center),
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Delete,
                    contentDescription = "删除记忆",
                    tint               = colors.textDisabled,
                    modifier           = Modifier.size(20.dp),
                )
            }
            // 星标
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { onToggleStar() }
                    .wrapContentSize(Alignment.Center),
            ) {
                Icon(
                    imageVector        = if (entry.isImportant) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (entry.isImportant) "取消重要" else "标记重要",
                    tint               = if (entry.isImportant) accentColor else colors.textDisabled,
                    modifier           = Modifier.size(20.dp),
                )
            }
            } // end inner Row (content+star)
        } // end outer Row (strip+content)
    }
}

// ─────────────────────────────────────────────────────────────
//  AbilityPanel  — 擅长领域 Tags（规范 §15）
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AbilityPanel(
    tags: List<String>,
    accentColor: Color,
    accentLight: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        Text(
            text  = "擅长领域",
            style = type.cardTitle,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(Spacing.sm))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement   = Arrangement.spacedBy(Spacing.sm),
        ) {
            tags.forEach { tag ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(accentLight)
                        .border(
                            width = 0.5.dp,
                            color = accentColor.copy(alpha = 0.30f),
                            shape = RoundedCornerShape(Radius.xs),
                        )
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                ) {
                    Text(
                        text  = tag,
                        style = type.caption,
                        color = accentColor,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ToolsPanel  — 可用工具（4 列图标，规范 §15）
// ─────────────────────────────────────────────────────────────

@Composable
private fun ToolsPanel(
    tools: List<ToolItem>,
    accentLight: Color,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        Text(
            text  = "可用工具",
            style = type.cardTitle,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(Spacing.sm))

        // 固定 4 列布局
        val rows = tools.chunked(4)
        rows.forEach { rowTools ->
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                rowTools.forEach { tool ->
                    Column(
                        modifier            = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(accentLight)
                                .clickable { /* 工具能力展示，对话中按需触发 */ },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector        = tool.icon,
                                contentDescription = tool.label,
                                tint               = accentColor,
                                modifier           = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text  = tool.label,
                            style = type.label,
                            color = colors.textSecondary,
                        )
                    }
                }
                // 补空列保持对齐
                repeat(4 - rowTools.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  AddButton  — 「新增记忆」全宽按钮（规范 §15）
// ─────────────────────────────────────────────────────────────

@Composable
private fun AddButton(
    label: String,
    accentColor: Color,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val type = ZaijianTheme.typography

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(accentColor)
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector        = Icons.Outlined.Add,
                contentDescription = label,
                tint               = Color.White,
                modifier           = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text  = label,
                style = type.button,
                color = Color.White,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  EmptyState
// ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(text = text, style = type.caption, color = colors.textDisabled)
    }
}


// ─────────────────────────────────────────────────────────────
//  IdentityPanel — 「人设」Tab（Phase 7）
//
//  让用户可以自定义角色的 persona / speechStyle / attitudeToUser，
//  写入 character_identity 表，PromptOrchestrator 下次对话立刻生效。
//
//  高级选项：customSystemPrompt（展开/折叠，替换整个 Identity Layer）
// ─────────────────────────────────────────────────────────────

@Composable
private fun IdentityPanel(
    state: com.zaijian.zhoumuyun.ui.viewmodel.IdentityUiState,
    accentColor: Color,
    onPersonaChange: (String) -> Unit,
    onSpeechStyleChange: (String) -> Unit,
    onAttitudeToUserChange: (String) -> Unit,
    onCustomSystemPromptChange: (String) -> Unit,
    onAddBoundary: (String) -> Unit,
    onRemoveBoundary: (Int) -> Unit,
    onUpdateBoundary: (Int, String) -> Unit,
    onAddCoreBelief: (String) -> Unit,
    onRemoveCoreBelief: (Int) -> Unit,
    onUpdateCoreBelief: (Int, String) -> Unit,
    onSave: () -> Unit,
    // ── Phase 1（zaijian）内核字段回调 ──────────────────────
    onCoreWoundChange: (String) -> Unit = {},
    onCoreDesireChange: (String) -> Unit = {},
    onMaskTriggerChange: (String) -> Unit = {},
    onPrivatePersonaChange: (String) -> Unit = {},
    onPrivateStyleChange: (String) -> Unit = {},
    onPrivateExamplesChange: (String) -> Unit = {},
    onSituationRulesChange: (String) -> Unit = {},
    onDeviationSignalsChange: (String) -> Unit = {},
    // ── 附加（NyxChat V18 A.1/A.2）──
    onLikesChange: (String) -> Unit = {},
    onDislikesChange: (String) -> Unit = {},
    onRelationshipsChange: (String) -> Unit = {},
    onSoulNoteChange: (String) -> Unit = {},
    onNarrativeMemoryChange: (String) -> Unit = {},
    onUserImpressionChange: (String) -> Unit = {},
    onUndoLastNoteEdit: () -> Unit = {},
    lastEditedNoteField: String? = null,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var advancedExpanded by remember { mutableStateOf(false) }

    if (state.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color       = accentColor,
                strokeWidth = 2.dp,
                modifier    = Modifier.size(24.dp),
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // ── 说明文字 ─────────────────────────────────────────
        Text(
            text  = "编辑后的人设在下次对话时立即生效。留空则使用角色默认设定。",
            style = type.caption,
            color = colors.textSecondary,
        )

        // ── 性格核心 ─────────────────────────────────────────
        IdentityField(
            label       = "性格核心",
            placeholder = "描述这个角色是什么样的人…",
            value       = state.persona,
            onValueChange = onPersonaChange,
            accentColor = accentColor,
            minLines    = 3,
        )

        // ── 说话风格 ─────────────────────────────────────────
        IdentityField(
            label       = "说话风格",
            placeholder = "语气、句式特点，例如「简洁克制，偶尔反问」…",
            value       = state.speechStyle,
            onValueChange = onSpeechStyleChange,
            accentColor = accentColor,
            minLines    = 2,
        )

        // ── 对你的态度 ───────────────────────────────────────
        IdentityField(
            label       = "对你的态度",
            placeholder = "例如「温柔但有距离感，不轻易表露情绪」…",
            value       = state.attitudeToUser,
            onValueChange = onAttitudeToUserChange,
            accentColor = accentColor,
            minLines    = 2,
        )

        // ── 禁忌（Boundaries）★ Phase 15 ────────────────────
        ListEditSection(
            title       = "绝对不会做的事",
            hint        = "每条一项，例如「不评价用户的选择」",
            items       = state.boundaries,
            accentColor = accentColor,
            onAdd       = onAddBoundary,
            onRemove    = onRemoveBoundary,
            onUpdate    = onUpdateBoundary,
        )

        // ── 核心信念（CoreBeliefs）★ Phase 15 ──────────────
        ListEditSection(
            title       = "核心信念",
            hint        = "每条一项，例如「陪伴是无声的力量」",
            items       = state.coreBeliefs,
            accentColor = accentColor,
            onAdd       = onAddCoreBelief,
            onRemove    = onRemoveCoreBelief,
            onUpdate    = onUpdateCoreBelief,
        )

        // ── 角色内核（Phase 1 zaijian）────────────────────────
        Spacer(Modifier.height(8.dp))
        Text(
            text     = "角色内核（AI 可见，影响角色深度表现）",
            style    = type.label,
            color    = accentColor,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        IdentityField(
            label         = "核心创伤",
            placeholder   = "曾经付出过全部，被彻底辜负。此后不再轻易动心。",
            value         = state.coreWound,
            onValueChange = onCoreWoundChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "核心渴望",
            placeholder   = "被一个人完全接住，不需要交换，不需要表演。",
            value         = state.coreDesire,
            onValueChange = onCoreDesireChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "面具何时碎裂（触发条件）",
            placeholder   = "对方第一次让她感到真正的安全；或她突然意识到自己已经在乎了。",
            value         = state.maskTrigger,
            onValueChange = onMaskTriggerChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "私下真实面目（面具碎裂后）",
            placeholder   = "情感极度浓烈，像最纯粹的孩子，没有防御，也没有理智。",
            value         = state.privatePersona,
            onValueChange = onPrivatePersonaChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "私下说话方式",
            placeholder   = "语气突然软下来，开始没有逻辑。可能哑口无言，也可能一下子说很多。",
            value         = state.privateStyle,
            onValueChange = onPrivateStyleChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "私下对话示例（破防时的 Few-shot）",
            placeholder   = "用户：你哭了吗？\n角色：（没有回答，只是把头埋进他肩膀）",
            value         = state.privateExamples,
            onValueChange = onPrivateExamplesChange,
            accentColor   = accentColor,
            minLines      = 3,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "情境反应规则",
            placeholder   = "在被问到家人时：停顿三秒，换话题，如果对方继续问才会说一句模糊的话。",
            value         = state.situationRules,
            onValueChange = onSituationRulesChange,
            accentColor   = accentColor,
            minLines      = 3,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "有心事时的外显信号",
            placeholder   = "比平时沉默多一些；回复速度变慢；说话开始用「随便」、「都行」。",
            value         = state.deviationSignals,
            onValueChange = onDeviationSignalsChange,
            accentColor   = accentColor,
            minLines      = 3,
        )
        Spacer(Modifier.height(8.dp))

        // ── 附加（NyxChat V18 A.1/A.2）：喜恶 + 人际关系行为逻辑 ──
        Text(
            text     = "喜恶与人际（注入行为层，权重等同情境规则）",
            style    = type.label,
            color    = accentColor,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        IdentityField(
            label         = "你喜欢",
            placeholder   = "清晨的咖啡香气、独处时的安静、有人记住她的细节",
            value         = state.likes,
            onValueChange = onLikesChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "你厌恶",
            placeholder   = "被人打断、无意义的客套、被当成工具",
            value         = state.dislikes,
            onValueChange = onDislikesChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "人际关系行为逻辑",
            placeholder   = "在露娜面前：压制自己的情绪反应，偶尔用锐利的话刺她，但事后会后悔。\n在宥熙面前：隐性保护，不承认自己在关心她。",
            value         = state.relationships,
            onValueChange = onRelationshipsChange,
            accentColor   = accentColor,
            minLines      = 4,
        )
        Spacer(Modifier.height(8.dp))

        // ── Soul/Memory/User 三模块 ─────────────────────────────
        if (lastEditedNoteField != null) {
            val undoLabel = when (lastEditedNoteField) {
                "soul"   -> "人设备忘录"
                "memory" -> "关系记忆摘要"
                "user"   -> "她对你的印象"
                else     -> "笔记"
            }
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.TextButton(onClick = onUndoLastNoteEdit) {
                Text("↩ 撤销上次对「$undoLabel」的修改", style = type.caption, color = colors.accent)
            }
        }
        IdentityField(
            label         = "人设备忘录",
            placeholder   = "她希望被记住的样子——自由文本，不套结构",
            value         = state.soulNote,
            onValueChange = onSoulNoteChange,
            accentColor   = accentColor,
            minLines      = 3,
            softLimit     = 600,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "关系记忆摘要",
            placeholder   = "她经历了什么、关系走到哪了——整段覆盖写",
            value         = state.narrativeMemory,
            onValueChange = onNarrativeMemoryChange,
            accentColor   = accentColor,
            minLines      = 3,
            softLimit     = 800,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "她对你的印象",
            placeholder   = "角色对用户的整体印象",
            value         = state.userImpression,
            onValueChange = onUserImpressionChange,
            accentColor   = accentColor,
            minLines      = 2,
            softLimit     = 400,
        )
        Spacer(Modifier.height(8.dp))

        // ── 高级：完全替换 System Prompt ────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = "高级：自定义完整 System Prompt",
                style = type.label,
                color = accentColor,
            )
            Text(
                text  = if (advancedExpanded) "收起" else "展开",
                style = type.caption,
                color = colors.textSecondary,
            )
        }
        if (advancedExpanded) {
            Text(
                text  = "非空时将完全替换上方字段，直接作为 AI 的 System Prompt。",
                style = type.caption,
                color = colors.textDisabled,
            )
            IdentityField(
                label       = "",
                placeholder = "你是…（直接写 System Prompt）",
                value       = state.customSystemPrompt,
                onValueChange = onCustomSystemPromptChange,
                accentColor = accentColor,
                minLines    = 5,
            )
        }

        // ── 保存按钮 ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(accentColor)
                .clickable { onSave() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = if (state.isSaved) "已保存 ✓" else "保存人设",
                style = type.button,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }

        Spacer(Modifier.height(Spacing.sm))
    }
}

// ─────────────────────────────────────────────────────────────
//  IdentityField — 多行文本输入框（人设专用）
// ─────────────────────────────────────────────────────────────

@Composable
private fun IdentityField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    accentColor: Color,
    minLines: Int = 2,
    softLimit: Int = 0,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) {
            Text(text = label, style = type.label, color = colors.textSecondary)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                .border(
                    width = 0.5.dp,
                    color = colors.border,
                    shape = RoundedCornerShape(Radius.sm),
                )
                .padding(horizontal = Spacing.md, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(text = placeholder, style = type.body, color = colors.textDisabled)
            }
            BasicTextField(
                value         = value,
                onValueChange = onValueChange,
                textStyle     = type.body.copy(color = colors.textPrimary),
                minLines      = minLines,
                modifier      = Modifier.fillMaxWidth(),
                cursorBrush   = androidx.compose.ui.graphics.SolidColor(accentColor),
            )
        }
        if (softLimit > 0) {
            val over = value.length > softLimit
            Text(
                text    = "${value.length} / ${softLimit} 字",
                style   = type.small,
                color   = if (over) Palette.SemanticDanger else colors.textDisabled,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  RelationshipPanel — 关系 Tab（六维雷达图 + 阶段展示）
// ─────────────────────────────────────────────────────────────

@Composable
private fun RelationshipPanel(
    character: CharacterConfig,
    accentColor: Color,
    characterIdStr: String = character.id.toString(),
    onNavigateToTimeline: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    val context = androidx.compose.ui.platform.LocalContext.current

    // Phase 17：从 Room 加载真实关系数据（用户 → 该角色）
    // UI M4 说明：此处在 Composable 内直接访问 DB 实例，属于\"局部视图专属数据\"模式——
    // RelationshipCard 是 CharacterDetailScreen 的内嵌子 Composable，无专属 ViewModel；
    // 若提取到 ViewModel 则需在 CharacterDetailViewModel 中暴露额外 StateFlow，
    // 这里以 remember {} + flowOn(IO) 作为最小改动保证线程安全。
    val db = remember { com.zaijian.zhoumuyun.data.db.AppDatabase.getInstance(context) }
    val relFlow = remember(characterIdStr) {
        db.relationshipDao()
            .observeFrom("user")
            .map { list -> list.firstOrNull { it.toId == characterIdStr } }
            .flowOn(Dispatchers.IO)
    }
    val relState by relFlow.collectAsStateWithLifecycle(initialValue = null)

    // Phase 17：加载最近关系变化事件（用于历史 Timeline）
    val recentRelEvents = remember { mutableStateOf<List<com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity>>(emptyList()) }
    // 关系转折点（Milestone）
    val milestones = remember { mutableStateOf<List<com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity>>(emptyList()) }
    LaunchedEffect(characterIdStr) {
        withContext(Dispatchers.IO) {
            val events = db.worldEventDao().queryByType(
                com.zaijian.zhoumuyun.data.db.entity.EventType.RELATIONSHIP_CHANGED.name, 8
            ).filter { it.actorId == "user" && it.targetId == characterIdStr }
            recentRelEvents.value = events
            milestones.value = db.relationshipMilestoneDao().getRecent("user", characterIdStr, 10)
        }
    }

    val dims = relState?.let { rel ->
        listOf(
            "信任" to rel.trust.toFloat(),
            "尊重" to rel.respect.toFloat(),
            "亲密" to rel.affection.toFloat(),
            "好奇" to rel.curiosity.toFloat(),
            "依赖" to rel.dependence.toFloat(),
            "冲突" to rel.conflict.toFloat(),
        )
    } ?: listOf(
        "信任" to 50f, "尊重" to 50f, "亲密" to 50f,
        "好奇" to 50f, "依赖" to 30f, "冲突" to 10f,
    )

    val stageLabel = relState?.let { rel ->
        when (rel.stage) {
            "STRANGER"  -> "陌生人"
            "FAMILIAR"  -> "熟悉"
            "TRUSTED"   -> "信任"
            "IMPORTANT" -> "重要"
            "CORE"      -> "核心"
            else        -> rel.stage
        }
    } ?: "熟悉"

    val relHistory = recentRelEvents.value

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Spacer(Modifier.height(Spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("与${character.name}的关系", style = type.titleBold, color = colors.textPrimary)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accentColor.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(stageLabel, style = type.label, color = accentColor)
            }
        }

        RelationshipRadarChart(
            dimensions  = dims,
            accentColor = accentColor,
            modifier    = Modifier.fillMaxWidth().height(260.dp),
        )

        dims.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = type.body, color = colors.textSecondary, modifier = Modifier.width(40.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier.width(140.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(colors.border),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight()
                                .fillMaxWidth(value / 100f)
                                .clip(RoundedCornerShape(2.dp))
                                .background(accentColor),
                        )
                    }
                    Text(value.toInt().toString(), style = type.label, color = colors.textSecondary, modifier = Modifier.width(28.dp))
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                .border(0.5.dp, colors.border, RoundedCornerShape(Radius.md))
                .padding(Spacing.md),
        ) {
            Text(
                "随着互动加深，关系会自然演化。\n信任和亲密达到 75 以上后阶段会提升。",
                style = type.secondary, color = colors.textSecondary,
            )
        }

        // ── 关系转折点 Milestone Timeline ────────────────────
        val milestoneList = milestones.value
        if (milestoneList.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.lg))
            Text(
                "重大转折点",
                style = type.titleBold,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(Spacing.sm))
            milestoneList.forEach { milestone ->
                MilestoneRow(milestone = milestone, accentColor = accentColor)
                Spacer(Modifier.height(Spacing.xs))
            }
        }

        // ── Phase 17：关系历史 Timeline ─────────────────────
        if (relHistory.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.lg))
            Text(
                "关系变化记录",
                style = type.titleBold,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(Spacing.sm))
            relHistory.forEach { event ->
                RelationshipHistoryRow(event = event, accentColor = accentColor)
                Spacer(Modifier.height(Spacing.xs))
            }
        }

        // ── B-1 Fix：故事时间线入口按钮 ─────────────────────
        Spacer(Modifier.height(Spacing.md))
        androidx.compose.material3.TextButton(
            onClick = { onNavigateToTimeline(character.id) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.material3.Text(
                text = "查看完整故事时间线 →",
                color = accentColor,
                style = type.body,
            )
        }

        Spacer(Modifier.height(Spacing.lg))
    }
}

// ─────────────────────────────────────────────────────────────
//  RelationshipHistoryRow — 单条关系变化事件（Phase 17）
// ─────────────────────────────────────────────────────────────

@Composable
private fun RelationshipHistoryRow(
    event: com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 解析 payload JSON，读取关系变化摘要
    val summary = remember(event.id) {
        try {
            val j = org.json.JSONObject(event.payload)
            val trust     = j.optInt("trust",     -1)
            val affection = j.optInt("affection", -1)
            val conflict  = j.optInt("conflict",  -1)
            val stage     = j.optString("stage", "")
            buildString {
                if (trust     >= 0) append("信任 $trust  ")
                if (affection >= 0) append("亲密 $affection  ")
                if (conflict  >= 0) append("冲突 $conflict")
                if (stage.isNotBlank()) append("  → ${
                    when (stage) {
                        "STRANGER"  -> "陌生人"
                        "FAMILIAR"  -> "熟悉"
                        "TRUSTED"   -> "信任"
                        "IMPORTANT" -> "重要"
                        "CORE"      -> "核心"
                        else        -> stage
                    }
                }")
            }.trim().ifEmpty { "关系更新" }
        } catch (e: Exception) {
            ZLog.w("CharacterDetail", "RelationshipEventCard: failed to parse payload JSON", e)
            "关系更新"
        }
    }

    val dateLabel = remember(event.createdAt) {
        java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(event.createdAt))
    }

    // WorldCard 接入（精修方案 v1.3）：关系变化记录，归属当前查看角色，
    // accentColor 即该角色 accent，直接作为 ownerAccent。
    WorldCard(
        modifier = Modifier.fillMaxWidth(),
        ownerAccent = accentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accentColor.copy(alpha = 0.7f))
                )
                Text(summary, style = type.secondary, color = colors.textPrimary)
            }
            Text(dateLabel, style = type.label, color = colors.textSecondary)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  MilestoneRow — 单条关系转折点
// ─────────────────────────────────────────────────────────────

@Composable
private fun MilestoneRow(
    milestone: com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val isWorsened = milestone.direction ==
        com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneDirection.WORSENED.name

    val dotColor = if (isWorsened)
        Palette.SemanticDanger   // 红：恶化
    else
        androidx.compose.ui.graphics.Color(0xFF81C784)   // 绿：缓和/和好

    val directionLabel = if (isWorsened) "↘ 转折" else "↗ 缓和"

    val dateLabel = remember(milestone.createdAt) {
        java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(milestone.createdAt))
    }

    // WorldCard 接入（精修方案 v1.3）：关系转折点，归属当前查看角色，
    // ownerAccent 用角色色；"好事/坏事"语义已经由圆点 + 方向文字标签
    // （dotColor）独立承担，不依赖边框颜色，故边框交还 WorldCard 标准黄铜线，
    // 不会丢失信息。
    WorldCard(
        modifier = Modifier.fillMaxWidth(),
        ownerAccent = accentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(dotColor),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = milestone.description,
                        style = type.secondary,
                        color = colors.textPrimary,
                    )
                    Text(
                        text  = directionLabel,
                        style = type.label,
                        color = dotColor,
                    )
                }
            }
            Text(
                text     = dateLabel,
                style    = type.label,
                color    = colors.textSecondary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  RelationshipRadarChart — Canvas 六维雷达图
// ─────────────────────────────────────────────────────────────

@Composable
private fun RelationshipRadarChart(
    dimensions: List<Pair<String, Float>>,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val n = dimensions.size

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = minOf(cx, cy) * 0.72f
        val angleStep = (2 * kotlin.math.PI / n).toFloat()

        for (layer in 1..5) {
            val r = maxR * layer / 5f
            val path = androidx.compose.ui.graphics.Path()
            for (i in 0 until n) {
                val angle = (-kotlin.math.PI / 2 + i * angleStep).toFloat()
                val x = cx + r * kotlin.math.cos(angle.toDouble()).toFloat()
                val y = cy + r * kotlin.math.sin(angle.toDouble()).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color = colors.border.copy(alpha = 0.45f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.8.dp.toPx()))
        }

        for (i in 0 until n) {
            val angle = (-kotlin.math.PI / 2 + i * angleStep).toFloat()
            drawLine(
                color       = colors.border.copy(alpha = 0.35f),
                start       = Offset(cx, cy),
                end         = Offset(cx + maxR * kotlin.math.cos(angle.toDouble()).toFloat(), cy + maxR * kotlin.math.sin(angle.toDouble()).toFloat()),
                strokeWidth = 0.8.dp.toPx(),
            )
        }

        val dataPath = androidx.compose.ui.graphics.Path()
        dimensions.forEachIndexed { i, (_, value) ->
            val r = maxR * (value / 100f)
            val angle = (-kotlin.math.PI / 2 + i * angleStep).toFloat()
            val x = cx + r * kotlin.math.cos(angle.toDouble()).toFloat()
            val y = cy + r * kotlin.math.sin(angle.toDouble()).toFloat()
            if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()
        drawPath(dataPath, color = accentColor.copy(alpha = 0.25f))
        drawPath(dataPath, color = accentColor.copy(alpha = 0.80f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))

        dimensions.forEachIndexed { i, (_, value) ->
            val r = maxR * (value / 100f)
            val angle = (-kotlin.math.PI / 2 + i * angleStep).toFloat()
            drawCircle(
                color  = accentColor,
                radius = 3.dp.toPx(),
                center = Offset(cx + r * kotlin.math.cos(angle.toDouble()).toFloat(), cy + r * kotlin.math.sin(angle.toDouble()).toFloat()),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ListEditSection — 可编辑列表（Boundaries / CoreBeliefs 通用）
//  ★ Phase 15 新增
// ─────────────────────────────────────────────────────────────

@Composable
private fun ListEditSection(
    title: String,
    hint: String,
    items: List<String>,
    accentColor: Color,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
    onUpdate: (Int, String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var newText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = type.label, color = colors.textSecondary)

        // 已有条目
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                    .border(0.5.dp, colors.border, RoundedCornerShape(Radius.sm))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.6f))
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value         = item,
                    onValueChange = { onUpdate(index, it) },
                    textStyle     = type.body.copy(color = colors.textPrimary),
                    cursorBrush   = androidx.compose.ui.graphics.SolidColor(accentColor),
                    modifier      = Modifier.weight(1f),
                )
                IconButton(
                    onClick  = { onRemove(index) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.Add,
                        contentDescription = "删除",
                        tint               = colors.textDisabled,
                        modifier           = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = 45f },
                    )
                }
            }
        }

        // 新增输入行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                .border(0.5.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(Radius.sm))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value         = newText,
                onValueChange = { newText = it },
                textStyle     = type.body.copy(color = colors.textPrimary),
                cursorBrush   = androidx.compose.ui.graphics.SolidColor(accentColor),
                decorationBox = { inner ->
                    if (newText.isEmpty()) {
                        Text(text = hint, style = type.body, color = colors.textDisabled)
                    }
                    inner()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (newText.isNotBlank()) accentColor else colors.border)
                    .clickable {
                        if (newText.isNotBlank()) {
                            onAdd(newText)
                            newText = ""
                        }
                    }
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Add,
                    contentDescription = "添加",
                    tint               = Color.White,
                    modifier           = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  GoalPanel — 「目标」Tab（★ Phase 15 新增）
//
//  展示角色的激活目标列表，支持新建/编辑/删除/进度调整。
//  设计方案 §9：Goal 是 Presence 和 World Simulation 的行为来源。
// ─────────────────────────────────────────────────────────────

@Composable
private fun GoalPanel(
    state: com.zaijian.zhoumuyun.ui.viewmodel.GoalUiState,
    accentColor: Color,
    onOpenNew: () -> Unit,
    onOpenEdit: (CharacterGoalEntity) -> Unit,
    onDelete: (String) -> Unit,
    onDeactivate: (String) -> Unit,
    onProgressChange: (String, Float) -> Unit,
    // Phase 27：跳转到 LearningGoalScreen（含规则面板）的完整学习闭环管理页
    onNavigateToGoals: () -> Unit = {},
    // P6 专长进化系统：跳转到专长档案页
    onNavigateToSpecialty: () -> Unit = {},
    // U1 修复：从专长页直通竞赛页（domain 由专长档案决定）
    onNavigateToCompetition: (domain: String) -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // ── 说明文字 ─────────────────────────────────────────
        Text(
            text  = "目标驱动角色的状态与行为。Goal 越具体，Presence 生成越自然。",
            style = type.caption,
            color = colors.textSecondary,
        )

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color       = accentColor,
                    strokeWidth = 2.dp,
                    modifier    = Modifier.size(24.dp),
                )
            }
        } else {
            // ── 目标列表 ───────────────────────────────────────
            if (state.goals.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = "还没有目标，点击下方添加",
                        style = type.caption,
                        color = colors.textDisabled,
                    )
                }
            } else {
                state.goals.forEach { goal ->
                    GoalCard(
                        goal        = goal,
                        accentColor = accentColor,
                        onEdit      = { onOpenEdit(goal) },
                        onDelete    = { onDelete(goal.id) },
                        onProgressChange = { p -> onProgressChange(goal.id, p) },
                    )
                }
            }

            // ── 新增按钮 ───────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(Radius.sm))
                    .clickable { onOpenNew() }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Add,
                    contentDescription = "新增目标",
                    tint               = accentColor,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(text = "新增目标", style = type.label, color = accentColor)
            }

            // Phase 27：进入完整学习闭环管理（含规则面板、六步流程可视化）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accentColor.copy(alpha = 0.08f))
                    .clickable { onNavigateToGoals() }
                    .padding(vertical = 10.dp, horizontal = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "查看学习闭环与规则面板",
                    style = type.label,
                    color = accentColor,
                )
                Icon(
                    imageVector        = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint               = accentColor,
                    modifier           = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.height(6.dp))

            // P6 专长进化系统：专长养成入口（让她针对某个方向主动练习、积累风格）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accentColor.copy(alpha = 0.08f))
                    .clickable { onNavigateToSpecialty() }
                    .padding(vertical = 10.dp, horizontal = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "专长养成（她的自主练习与风格积累）",
                    style = type.label,
                    color = accentColor,
                )
                Icon(
                    imageVector        = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint               = accentColor,
                    modifier           = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))
    }
}

// ─────────────────────────────────────────────────────────────
//  GoalCard — 单条目标展示卡片
// ─────────────────────────────────────────────────────────────

@Composable
private fun GoalCard(
    goal: CharacterGoalEntity,
    accentColor: Color,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onProgressChange: (Float) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val horizon = runCatching { GoalHorizon.valueOf(goal.timeHorizon) }.getOrDefault(GoalHorizon.MID_TERM)
    val horizonLabel = when (horizon) {
        GoalHorizon.SHORT_TERM -> "短期"
        GoalHorizon.MID_TERM   -> "中期"
        GoalHorizon.LONG_TERM  -> "长期"
    }
    val priorityColor = when {
        goal.priority >= 4 -> accentColor
        goal.priority == 3 -> accentColor.copy(alpha = 0.7f)
        else               -> colors.textDisabled
    }

    // WorldCard 接入（精修方案 v1.3 第2/6节）：L0-L2 常态层由 WorldCard 内部承担，
    // L3 身份脊取本页角色 accentColor——GoalCard 展示的目标始终归属"当前查看的这位角色"，
    // 与 TaskCenterScreen 里"按任务归属的角色各自不同"语义一致，只是这里恒定指向同一人。
    com.zaijian.zhoumuyun.ui.design.WorldCard(
        modifier = Modifier.fillMaxWidth(),
        ownerAccent = accentColor,
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 标题行
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                // 优先级指示点
                repeat(goal.priority.coerceIn(1, 5)) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(priorityColor)
                    )
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text  = goal.title.ifEmpty { "（未命名目标）" },
                    style = type.navTitle.copy(fontWeight = FontWeight.Medium),
                    color = colors.textPrimary,
                )
            }
            // 时间范围标签
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(accentColor.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(text = horizonLabel, style = type.caption, color = accentColor)
            }
        }

        // 描述
        if (goal.description.isNotEmpty()) {
            Text(
                text  = goal.description,
                style = type.caption,
                color = colors.textSecondary,
            )
        }

        // 进度条
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "进度", style = type.caption, color = colors.textDisabled)
                Text(
                    text  = "${(goal.progress * 100).toInt()}%",
                    style = type.caption,
                    color = accentColor,
                )
            }
            // 进度条：角色专属色 → Gold 横向渐变（§6.6 设计规范）
            val progressFraction = goal.progress.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .drawBehind {
                        // 轨道底色
                        drawRect(accentColor.copy(alpha = 0.15f))
                        // 渐变填充（角色色 → Gold）
                        if (progressFraction > 0f) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(accentColor, Palette.Gold),
                                    startX = 0f,
                                    endX   = size.width * progressFraction,
                                ),
                                size = Size(size.width * progressFraction, size.height),
                            )
                        }
                    },
            )
        }

        // 操作行
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                text     = "编辑",
                style    = type.caption,
                color    = accentColor,
                modifier = Modifier
                    .clickable { onEdit() }
                    .padding(4.dp),
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text     = "删除",
                style    = type.caption,
                color    = colors.textDisabled,
                modifier = Modifier
                    .clickable { onDelete() }
                    .padding(4.dp),
            )
        }
    }
    }
}

// ─────────────────────────────────────────────────────────────
//  GoalDraftSheet — 新建/编辑目标的内嵌表单（无 BottomSheet API，避免额外依赖）
// ─────────────────────────────────────────────────────────────

@Composable
private fun GoalDraftSheet(
    draft: GoalDraft,
    accentColor: Color,
    activeProjects: List<com.zaijian.zhoumuyun.data.db.entity.ProjectEntity> = emptyList(),
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPriorityChange: (Int) -> Unit,
    onHorizonChange: (GoalHorizon) -> Unit,
    onProjectChange: (String?) -> Unit = {},
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 用半透明遮罩 + 底部弹出卡片模拟 BottomSheet
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(if (colors.isDark) colors.bgCard else colors.bgBase)
                .clickable(enabled = false) {}  // 阻止点击穿透
                .padding(Spacing.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // 标题栏
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text  = if (draft.id == null) "新增目标" else "编辑目标",
                    style = type.navTitle,
                    color = colors.textPrimary,
                )
                Text(
                    text     = "取消",
                    style    = type.caption,
                    color    = colors.textSecondary,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(4.dp),
                )
            }

            // 目标名称
            IdentityField(
                label         = "目标名称",
                placeholder   = "例如「整理永恒之家的架构文档」",
                value         = draft.title,
                onValueChange = onTitleChange,
                accentColor   = accentColor,
                minLines      = 1,
            )

            // 目标描述
            IdentityField(
                label         = "描述（可选）",
                placeholder   = "为什么重要，具体要做什么…",
                value         = draft.description,
                onValueChange = onDescriptionChange,
                accentColor   = accentColor,
                minLines      = 2,
            )

            // 时间维度选择
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "时间维度", style = type.label, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalHorizon.values().forEach { h ->
                        val label = when (h) {
                            GoalHorizon.SHORT_TERM -> "短期"
                            GoalHorizon.MID_TERM   -> "中期"
                            GoalHorizon.LONG_TERM  -> "长期"
                        }
                        val selected = draft.timeHorizon == h
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(if (selected) accentColor else accentColor.copy(alpha = 0.1f))
                                .border(
                                    width = if (selected) 0.dp else 0.5.dp,
                                    color = accentColor.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(Radius.sm),
                                )
                                .clickable { onHorizonChange(h) }
                                .padding(horizontal = Spacing.md, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text  = label,
                                style = type.label,
                                color = if (selected) Color.White else accentColor,
                            )
                        }
                    }
                }
            }

            // 优先级选择（1-5 星）
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "优先级", style = type.label, color = colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    (1..5).forEach { i ->
                        val active = i <= draft.priority
                        // E fix: 用 40dp Box 包裹 28dp 图标，触摸热区扩大到 40dp 防误触
                        Box(
                            modifier         = Modifier
                                .size(40.dp)
                                .clickable { onPriorityChange(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector        = if (active) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                contentDescription = "${i}星优先级",
                                tint               = if (active) accentColor else colors.textDisabled,
                                modifier           = Modifier.size(28.dp),
                            )
                        }
                    }
                }
            }

            // 关联项目下拉\uff08Step 2\uff09
            if (activeProjects.isNotEmpty()) {
                val growthGreen = Color(0xFF7BAE7F)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text  = "关联项目\uff08可选\uff09",
                        style = type.label,
                        color = colors.textSecondary,
                    )
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val options = listOf<com.zaijian.zhoumuyun.data.db.entity.ProjectEntity?>(null) + activeProjects
                        items(options) { proj ->
                            val isSelected = draft.relatedProjectId == proj?.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(
                                        if (isSelected) growthGreen
                                        else growthGreen.copy(alpha = 0.12f)
                                    )
                                    .clickable { onProjectChange(proj?.id) }
                                    .padding(horizontal = Spacing.md, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text  = proj?.title ?: "无",
                                    style = type.label,
                                    color = if (isSelected) Color.White else growthGreen,
                                )
                            }
                        }
                    }
                }
            }

            // 保存按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (draft.title.isNotBlank()) accentColor else colors.border)
                    .clickable(enabled = draft.title.isNotBlank()) { onSave() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = "保存目标",
                    style = type.button,
                    color = Color.White,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  PregnancyPanel — 孕育 Tab（1.2 修复：接回 PregnancyViewModel 入口）
//
//  展示内容：
//  - 当前怀孕状态（未孕 / 孕第N天 / 已流产）
//  - 生育记录列表（birthRecords，每条显示出生时间）
//  - 操作区：开始怀孕（未孕时）/ 终止妊娠（孕期时）
//  - 终止妊娠的二次确认弹窗（showTerminateConfirm）
//
//  isLoading=true 时显示加载占位，避免闪烁。
// ─────────────────────────────────────────────────────────────

@Composable
private fun PregnancyPanel(
    state: com.zaijian.zhoumuyun.ui.viewmodel.PregnancyUiState,
    accentColor: Color,
    onStartPregnancy: () -> Unit,
    onRequestTerminate: () -> Unit,
    onDismissTerminate: () -> Unit,
    onConfirmTerminate: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val pregnancy = state.pregnancy

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // ── 标题行 ───────────────────────────────────────────
        GoldDivider(withDiamond = true, fadeEdges = true)
        Spacer(modifier = Modifier.height(Spacing.xs))

        // ── 当前孕期状态卡 ───────────────────────────────────
        // WorldCard 接入（精修方案 v1.3）：结构化状态展示卡，归属当前角色。
        // 内部"开始怀孕"/"终止妊娠"操作按钮维持原样不单独接 WorldCard，
        // 避免卡片套卡片。
        WorldCard(
            modifier = Modifier.fillMaxWidth(),
            ownerAccent = accentColor,
        ) {
            Box(modifier = Modifier.padding(Spacing.md)) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).align(Alignment.Center),
                    color    = accentColor,
                    strokeWidth = 2.dp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    val statusText = when {
                        pregnancy.isPregnant -> {
                            val day = pregnancy.currentDay()
                            "孕期第 $day / ${PregnancyState.CYCLE_DAYS} 天"
                        }
                        pregnancy.miscarriedAt != null -> {
                            val daysAgo = pregnancy.miscarriageDaysAgo()
                            if (daysAgo <= 30) "流产已 $daysAgo 天" else "无在孕记录"
                        }
                        else -> "当前未怀孕"
                    }
                    Text(
                        text  = statusText,
                        style = type.cardTitle,
                        color = if (pregnancy.isPregnant) accentColor else colors.textPrimary,
                    )
                    if (pregnancy.consecutiveFailCount > 0 && !pregnancy.isPregnant) {
                        Text(
                            text  = "连续失败 ${pregnancy.consecutiveFailCount} 次",
                            style = type.caption,
                            color = colors.textSecondary,
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    // ── 操作按钮 ────────────────────────────────
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (!pregnancy.isPregnant) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(accentColor.copy(alpha = 0.15f))
                                    .border(0.5.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(Radius.sm))
                                    .clickable { onStartPregnancy() }
                                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                            ) {
                                Text(text = "开始怀孕", style = type.button, color = accentColor)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(colors.bgElevated)
                                    .border(0.5.dp, colors.border, RoundedCornerShape(Radius.sm))
                                    .clickable { onRequestTerminate() }
                                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                            ) {
                                Text(text = "终止妊娠", style = type.button, color = colors.textSecondary)
                            }
                        }
                    }
                }
            }
            }
        }

        // ── 生育记录列表 ─────────────────────────────────────
        if (state.birthRecords.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text  = "生育记录",
                style = type.label,
                color = colors.textSecondary,
            )
            state.birthRecords.forEach { record ->
                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date(record.bornAt))
                val genderLabel = if (record.isDaughter) "女儿" else "儿子"
                // WorldCard 接入（精修方案 v1.3）：独立列表项，归属当前角色。
                WorldCard(
                    modifier = Modifier.fillMaxWidth(),
                    ownerAccent = accentColor,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = genderLabel, style = type.body, color = colors.textPrimary)
                        Text(text = dateStr, style = type.caption, color = colors.textSecondary)
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xs))
        GoldDivider(withDiamond = false, fadeEdges = true)
    }

    // ── 终止妊娠二次确认弹窗 ─────────────────────────────────
    if (state.showTerminateConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissTerminate,
            title = { Text("确认终止妊娠", style = ZaijianTheme.typography.cardTitle) },
            text  = { Text("此操作不可撤销，将终止当前怀孕并记录为流产。是否继续？",
                style = ZaijianTheme.typography.body,
                color = ZaijianTheme.colors.textSecondary) },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(accentColor)
                        .clickable { onConfirmTerminate() }
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                ) {
                    Text("确认终止", style = ZaijianTheme.typography.button, color = Color.White)
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable { onDismissTerminate() }
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                ) {
                    Text("取消", style = ZaijianTheme.typography.button, color = ZaijianTheme.colors.textSecondary)
                }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Previews
// ─────────────────────────────────────────────────────────────

@Preview(
    name            = "CharacterDetail · Dark",
    showBackground  = true,
    backgroundColor = 0xFF12131A,
    widthDp         = 390,
    heightDp        = 844,
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
