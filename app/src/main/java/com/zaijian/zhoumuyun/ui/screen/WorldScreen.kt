package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.BuildConfig
import com.zaijian.zhoumuyun.R
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.component.CalibrationProbeOverlay
import com.zaijian.zhoumuyun.ui.component.CharacterPreviewSheet
import com.zaijian.zhoumuyun.ui.component.FamilyPickerSheet
import com.zaijian.zhoumuyun.ui.component.MansionHeader
import com.zaijian.zhoumuyun.ui.component.OnboardingTooltip
import com.zaijian.zhoumuyun.ui.component.WindowCard
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.AppBrushes
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.WcAlpha
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.PresenceViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.NotificationBadgeViewModel

// ─────────────────────────────────────────────────────────────
//  WorldScreen  — 公馆首页
//
//  Phase 14 后半：新增圆桌 FAB（右下角），默认选全部解锁角色（最多4个）
//
//  层级（从后到前）：
//    [0] 公馆插图背景（日/夜随主题切换，FillBounds 保证坐标映射可靠）
//    [1] 微暗氛围叠层（长按空白处切换调试网格）
//    [2] BoxWithConstraints — 角色卡片绝对定位到各房间
//    [2b] 坐标探针拦截层（仅调试网格开启时挂载，盖住 [2] 拦截全部手势）
//    [3] MansionHeader（毛玻璃顶栏，固定）
//    [4] 已移除（任务中心入口已放入 MansionHeader 左侧图标）
//    [5] OnboardingTooltip
//    [6] 圆桌 FAB（右下角，Phase 14 后半新增）
//    [7] CharacterPreviewSheet（长按弹出）
//
//  ── 背景图坐标系说明 ──────────────────────────────────────────
//  背景图用 ContentScale.FillBounds 铺满「导航栏以上」的内容区域
//  （即 fillMaxSize + navigationBarsPadding），而非铺满整屏。
//  这样 BoxWithConstraints 的 maxHeight = 内容区高度 = 背景图渲染高度，
//  cx/cy 比例坐标与背景图像素坐标严格对应，不因底栏高度错位。
//
//  v10 修正（对 mansion_day.webp 1024×1536 原图做像素级网格测量，
//  取代此前从未核实过的估算值）：
//    - cardW 此前写死 0.260，实测拱门开口宽度只有 ~0.185，
//      超宽 40%，是头像溢出拱门边框的主因。
//    - cy 此前二楼层写的是 0.305，实测应为 ~0.267（偏差 0.038，
//      三层里二楼偏差最大，一楼/地下室此前已经比较接近实测值）。
//    - archHeight 三层同步修正为实测值。
//
//  v11 修正（p17，对实机截图 1264×2780 做逐格逐像素网格测量，见
//  《v49_p17_avatar_alignment_fix》审查素材）：
//    - v10 的拱门框仍然偏宽偏右上：cardW=0.185 比拱门开口实测宽度
//      (0.140) 宽出 32%，导致头像连同两侧石柱边框一起被裁进圆角框；
//      三层 cy 也都要下移 1.5～2 个百分点，archHeight 需要放大，
//      因为拱门开口本身比石柱围出的"格位"更高更窄（哥特尖拱造型，
//      不是矮扁的半圆罗马拱）。
//    - 中间"楼梯间"（col=2, floor=FIRST，哥特双开门）曾经单独实测：
//      开口比其余 8 个窗格更高（双开门造型），一度拆出 archHeightStair
//      单独传入、比同层其余两格大了近 27%。2026-07 用户实机反馈索菲娅
//      头像明显偏大，应该跟宥熙/顾澜一样——已去掉这个特殊分支。v53 起
//      九人坐标改为按角色 id 各自独立查表（见 WorldLayoutConfig.kt 的
//      archSpots，W14 问题6修复迁出前原在本文件下方），索菲娅
//      现在和宥熙/顾澜共用同一套统一逻辑，不再有任何特殊放大分支。
//    - colX 左右列的相对间距（0.500 中心 ±0.260）当时验证仍然成立；
//      v53 用拖动式探针对全部 9 格逐一三点法实测后，发现同层左右两格
//      并非绝对对称（存在千分位级偏移），已按"左右对中间格对称"重新
//      校准，具体数值见 WorldLayoutConfig.kt 的 archSpots 表，不再引用这里的估算值。
//  九人精确坐标见 WorldLayoutConfig.kt（同包）的 archSpots 表（v53），
//  这里不再列出可能过时的估算数值表格。
//
//  ── 头像位置"改了很多次都没用"的真正原因 ─────────────────────
//  过去反复调的都是上面这些 cx/cy 数值，但头像实际显示位置还受
//  WindowCard 内部布局结构影响：之前头像和名字标签共用一个 Column
//  做整体居中，名字/状态文字越高，头像就被挤得越往上偏——这是
//  结构性问题，光调 cy 数值只能碰运气式地部分抵消，换一部手机
//  屏幕比例或者角色名字变长就又偏了。已在 WindowCard.kt 里把头像
//  和名字拆开独立定位（头像单独居中在拱门框正中，名字用固定偏移
//  贴在拱门框正下方），从根上解决，不再是"数值游戏"。
//
//  ── 校准工具（v19 拖动式坐标探针） ────────────────────────────
//  长按背景任意位置（不分是否落在头像上）可切换"调试网格"：
//    - 开启后画出高亮色框，精确显示当前代码认为每个拱门在哪、
//      多宽多高，方便对着真机画面比对精修。
//    - 同时挂载一个盖住全部角色卡片的全屏拦截层 [2b]：角色卡片
//      的点击/长按在调试网格开启期间完全不响应，所有手势都先给
//      探针层处理，不会再出现"点在头像上被卡片直接吃掉进对话/
//      弹预览"的问题。
//    - 按住拖动：黄色十字实时跟着手指显示 cx/cy 三位小数读数，
//      可以按住来回微调，不满意就移开手指改位置，只要还没松手
//      就不会记点。
//    - 松手：把当前位置提交为一个青色确认点，编号 + 坐标常驻显示，
//      点数不设上限，量一个拱门的四个角、量全部9个拱门都可以。
//    - 顶部工具条有"撤销"（删最后一个点）和"清空"（全部清空），
//      点错了不用再长按重新开始。
//    - 再长按一次关闭调试网格（同时清空当前所有探针点）。
// ─────────────────────────────────────────────────────────────

@Composable
fun WorldScreen(
    onNavigateToChat: (characterId: Int) -> Unit = {},
    onNavigateToProfile: (characterId: Int) -> Unit = {},
    onNavigateToTasks: () -> Unit = {},
    onNavigateToRoundtable: (List<Int>) -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    bgStyleIndex: Int = 0,
    viewModel: PresenceViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 角标数据源：复用 BottomNavBadgeViewModel 同一套"ViewModel 暴露
    // StateFlow<Int>，Composable collectAsStateWithLifecycle" 模式。
    val notificationBadgeViewModel: NotificationBadgeViewModel = viewModel()
    val unreadCount by notificationBadgeViewModel.unreadCount.collectAsStateWithLifecycle(initialValue = 0)

    val colors  = ZaijianTheme.colors
    val isDark  = colors.isDark
    var familyPickerTarget by remember { mutableStateOf<Int?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 校准工具：长按背景空白处切换，画出每个拱门当前的实际坐标框，
    // 方便对着背景图精修 cx/cy/archWidth/archHeight，不用再靠肉眼反复猜。
    var showDebugGrid by remember { mutableStateOf(false) }
    // UI 升级 v2.0 帧03：大门引导气泡——首次进入公馆时显示，4s 后或点击消失。
    var showDoorGuide by remember { mutableStateOf(true) }
    // v49_p19 重写——坐标探针（拖动式，不再是单击式）：
    //   问题1（点位被头像卡片挡住）：v18 版探针手势挂在氛围层 [1]，
    //     角色卡片 [2] 的 combinedClickable 画在它上面，落在卡片范围内
    //     的点击会被卡片先吃掉。v19 改为在角色卡片渲染*之后*另起一个
    //     全屏拦截层（见下方 [2b]），调试网格开启时这层盖住一切，
    //     角色卡片暂时不响应点击/长按，所有手势先到探针层。
    //   问题2（不能拖动确认）：单击是瞬间事件，手指按下的位置和真正
    //     想要的位置容易有几像素误差。改成"按住拖动实时看 cx/cy 读数，
    //     松手才真正记一个点"，可以按住来回微调再松手确认。
    //   问题3（点错不能撤销、上限4个）：加撤销（删最后一个）和清空
    //     按钮，去掉4点上限，量多少个点都行。
    //   v20：探针的记点/拖动状态已经搬进共享组件 CalibrationProbeOverlay
    //     内部自己 remember，这里不用再单独持有 probePoints/dragPreview——
    //     调试网格关闭时 [2b] 那个 if(showDebugGrid) 直接不再组合探针
    //     组件，它内部的 remember 状态跟着丢弃；下次开启时组件重新
    //     mount，天然就是空列表，不需要在这里手动清空。

    // 家族链选择弹窗：有后代时选人，无后代时直接进对话
    familyPickerTarget?.let { motherId ->
        FamilyPickerSheet(
            firstGenCharacterId = motherId,
            onDismiss           = { familyPickerTarget = null },
            onSelectCharacter   = { characterId ->
                familyPickerTarget = null
                onNavigateToChat(characterId)
            },
        )
    }

    // ── 关键修复：整个内容区 = 屏幕去掉状态栏和底部导航栏后的区域 ──
    // [v26 修复] 原来只用了 navigationBarsPadding()，这只消费"系统"
    // 导航栏（手势条/虚拟按键）的 inset，完全不知道 App 自己在
    // AppNavigation.kt 里用 Row 手绘的那条 Spacing.bottomNavHeight
    // （44dp）高的底部 Tab 栏——两者是不同的东西：系统 inset 是 OS
    // 层面的安全区，自绘 Tab 栏是这个 App 内部的普通 View，系统对它
    // 一无所知。结果内容区只避开了手势条，没有再避开 Tab 栏本身，
    // 背景图和角色卡片的可用高度比实际应该露出的区域多算了 44dp，
    // 底部一整条内容会被 Tab 栏盖住。
    // 正确做法：在系统 inset 的基础上再叠加 App 自己的 Tab 栏高度，
    // 内容区的下边界才真正贴在 Tab 栏的上边缘。
    //
    // v54 修复：原来这里没有消费顶部状态栏 inset，背景图从屏幕最
    // 顶端（Y=0）就开始渲染并被 FillBounds 拉伸铺满剩余空间——
    // MansionHeader 自己虽然加了 statusBarsPadding()，但那只影响
    // Header 内容本身的位置，不影响背景图和 BoxWithConstraints 的
    // 测量高度。两者不一致导致背景图实际拉伸范围比视觉上应该露出的
    // 区域多出一截状态栏高度，是背景图看起来被拉长变形的根因之一。
    // 加上 statusBarsPadding() 后，内容区（含背景图）从状态栏下方
    // 开始，与 Header 的可见区域基准一致。
    Box(modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(bottom = Spacing.bottomNavHeight)
    ) {

        // ── [0] 公馆插图背景 ─────────────────────────────────
        // bgStyleIndex == 1（极简版）：仅用纯色背景，不渲染插画
        // bgStyleIndex == 0（暗夜版/默认）：按深浅色模式选插画
        val bgPainter = when {
            bgStyleIndex == 1 -> null
            isDark            -> painterResource(R.drawable.mansion_night)
            else              -> painterResource(R.drawable.mansion_day)
        }
        if (bgPainter != null) {
            Image(
                painter            = bgPainter,
                contentDescription = null,
                modifier           = Modifier.fillMaxSize(),
                contentScale       = ContentScale.FillBounds,
            )
        } else {
            // 极简版：纯色底，走主题色统一深浅
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.bgBase),
            )
        }

        // ── [1] 氛围叠层（夜晚略加深，白天略提亮） ──────────
        // v19：只保留"长按背景空白处切换调试网格"这一个手势。探针本身
        // 的拖动/记点手势移到 [2b]（角色卡片渲染之后的全屏拦截层），
        // 不再放在这里——氛围层在 WindowCard 下方，只有空白处的长按
        // 才能落到这层，拱门范围内的长按会被卡片自己的 onLongClick
        // （弹预览）先吃掉，这一点在调试网格关闭时是期望行为。
        //
        // UI 升级 v2.0 帧04：暗色帧令牌——夜间在画面四边叠一层
        // NightBorder 渐隐暗角（vignette），强化"暗色帧"的画框感，
        // 与窗内烛光呼应；白天仅保留原 MansionDayOverlay 提亮。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (isDark) {
                        // UI v2.0 帧04 校准：冷蓝夜纱 + 上下压暗线性渐变
                        // HTML：顶 42% 冷蓝深 → 中部夜纱 → 底 40% 冷蓝深
                        drawRect(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Palette.MansionNightOverlay,
                                    0.30f to Palette.MansionNightOverlay.copy(alpha = 0.12f),
                                    0.70f to Palette.MansionNightOverlay.copy(alpha = 0.12f),
                                    1.0f to Palette.MansionNightOverlay,
                                )
                            )
                        )
                    } else {
                        // UI v2.0 帧03 校准：白天顶 16%/底 18% 暖墨压暗 + 顶部 5% 金径向
                        drawRect(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Palette.MansionDayOverlay,
                                    0.18f to Palette.MansionDayOverlay.copy(alpha = 0.04f),
                                    0.82f to Palette.MansionDayOverlay.copy(alpha = 0.04f),
                                    1.0f to Palette.MansionDayOverlay.copy(alpha = 0.18f),
                                )
                            )
                        )
                        // 顶部金径向提亮（光源右上）
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(
                                    Palette.Gold.copy(alpha = 0.05f),
                                    Color.Transparent,
                                ),
                                center = Offset(size.width * 0.9f, 0f),
                                radius = size.maxDimension * 0.6f,
                            )
                        )
                    }
                    // v2.0 金色视觉：右上角金色水彩晕染
                    drawRect(
                        brush   = AppBrushes.watercolorWash(
                            color   = Palette.Gold,
                            alpha   = WcAlpha.page,
                            center  = Offset(size.width * 0.9f, 0f),
                            radius  = size.maxDimension * 0.7f,
                        ),
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            // P2-37 修复：调试网格仅 debug build 可用，防止调试色值泄漏到 release。
                            if (BuildConfig.DEBUG) showDebugGrid = !showDebugGrid
                        },
                    )
                },
        )

        // ── [1b] 光尘（帧03 氛围装饰）──────────────────────────
        // 缓慢上浮的金色微尘粒子，世界层慢动效，亮色淡金/暗色暖金。
        LightDustOverlay(isDark = isDark)

        // ── [2] 角色卡片 — 绝对定位到对应房间 ────────────────
        //
        // 坐标校准方法：拖动式坐标探针（长按背景任意位置切换调试网格，
        // 见下方"校准工具"说明）。九人最终坐标见 WorldLayoutConfig.kt
        // 的 archSpots（W14 问题6修复：坐标表已迁出此文件，同包无需
        // import；校准过程与数据处理原则见该文件内的完整版本历史注释）。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sw = maxWidth
            val sh = maxHeight

            // W5-012 修复：角色列表为空时显示空态提示
            if (uiState.characters.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无角色数据",
                        style = ZaijianTheme.typography.body,
                        color = ZaijianTheme.colors.textDisabled,
                    )
                }
                return@BoxWithConstraints
            }

            uiState.characters.forEachIndexed { index, char ->
                // P1-13-13 修复（加固，非修 bug）：archSpots 只登记 9 位固定
                // 母亲角色（id 1~9），不在表里的 id（例如女儿角色，id 1000+，
                // shelfCol 故意占位为 0，不进公馆九宫格，走 FamilyScreen 单独
                // 展示——这是产品设计，不是缺陷）直接查表拿不到值，用
                // ?: return@forEach 兜底跳过，防止未来数据来源变化时
                // 直接崩掉整个房间界面。已核实当前 uiState.characters 始终
                // 来自 DefaultCharacters（9 个固定角色），女儿数据从未合并
                // 进这个列表，当前不存在触发越界的真实调用路径。
                val spot = archSpots[char.id] ?: return@forEachIndexed
                val cx = spot.cx
                val cy = spot.cy
                val thisW = sw * spot.w
                val thisH = sh * spot.h
                val presence = uiState.presenceMap[char.id] ?: PresenceState(
                    characterId = char.id,
                    statusText  = "—",
                    statusType  = StatusType.OFFLINE,
                    lastUpdated = 0L,
                )

                // BUG-3 修复：提前计算 hasDescendants，同时传给 WindowCard 参数（左上角角标）
                // 和 onClick 分支（决定弹家族选择还是直接进对话）。
                val hasDescendants = uiState.familyChainMap[char.id]?.isNotEmpty() == true
                WindowCard(
                    character      = char,
                    presence       = presence,
                    archWidth      = thisW,
                    archHeight     = thisH,
                    hasDescendants = hasDescendants,
                    staggerIndex   = index,
                    // v19：调试网格开启时卡片不响应点击/长按（双保险——即使
                    // 探针拦截层因为某种原因没吃到手势，卡片这边也不会误触
                    // 跳转/弹预览，避免校准时手滑进对话页）。
                    onClick        = {
                        if (!showDebugGrid) {
                            // W14 修复：familyChainMap 异步加载完成前忽略点击，
                            // 避免竞态误判为"无后代"直接跳转聊天。
                            if (!uiState.isFamilyChainLoaded) return@WindowCard
                            if (hasDescendants) {
                                familyPickerTarget = char.id
                            } else {
                                onNavigateToChat(char.id)
                            }
                        }
                    },
                    onLongClick = {
                        if (!showDebugGrid) {
                            viewModel.showPreview(char.id)
                        }
                    },
                    modifier    = Modifier
                        .offset(
                            x = sw * cx - thisW / 2,
                            y = sh * cy - thisH / 2,
                        )
                        .size(thisW, thisH),
                )

                // ── 调试网格：高亮框精确画出当前 cx/cy/archWidth/archHeight ──
                // 长按背景空白处打开后，对着真实拱门截图比对，直接读数校准。
                // P2-37 修复：双重守卫——showDebugGrid 只在 debug build 可被置 true，
                // 此处再检查 BuildConfig.DEBUG 做防御性编程。
                if (showDebugGrid && BuildConfig.DEBUG) {
                    Box(
                        modifier = Modifier
                            .offset(
                                x = sw * cx - thisW / 2,
                                y = sh * cy - thisH / 2,
                            )
                            .size(thisW, thisH)
                            .border(2.dp, Color(0xFFFF00FF)),
                    )
                    Text(
                        text     = "cx${"%.3f".format(cx)} cy${"%.3f".format(cy)}",
                        color    = Color(0xFF00FFFF),
                        fontSize = 9.sp,
                        modifier = Modifier.offset(
                            x = sw * cx - thisW / 2,
                            y = sh * cy - thisH / 2 - 14.dp,
                        ),
                    )
                }
            }
        }

        // ── [2b] 坐标探针拦截层（v20：改用共享组件 CalibrationProbeOverlay）──
        // 关键：这个 Box 在角色卡片 [2] *之后* 声明，Compose 里同一个父
        // Box 内后声明的子节点画在更上层、也更早拿到命中测试——所以它能
        // 稳稳盖住全部 9 张 WindowCard，不会再被卡片自己的 combinedClickable
        // 抢走手势。仅在 showDebugGrid 时才挂载，关闭调试网格时这层完全
        // 不存在，不影响正常使用时的点击穿透。
        //
        // v19→v20：之前这里和 CharacterScreen.kt（书架页）各自维护一份
        // 拖动/记点/撤销/清空逻辑，结果书架页那份忘了同步，还停在更早
        // 的单击版本——两边独立维护同一段交互逻辑，迟早会像这次一样
        // 走岔。现在统一调用 CalibrationProbeOverlay，两个页面共享同一份
        // 实现，以后只需要改一处。
        // 同时这次也顺带修了可读性问题：原来每个点的编号+读数都直接
        // 浮在图上，点一多（用户截图里到了 45 个点）文字互相压得完全看
        // 不清；新版本画面上只留小圆点+编号，完整读数挪到顶部固定的
        // 可滚动列表面板里，并加了"复制全部"一键导出到剪贴板，不用再
        // 靠截图肉眼抄数字。
        if (showDebugGrid) {
            CalibrationProbeOverlay(modifier = Modifier.fillMaxSize())
        }

        // ── [3] 顶部 MansionHeader（毛玻璃顶栏；任务中心图标在左侧）──
        // U3 修复：MansionHeader 已定义但从未挂载，导致 onNavigateToTasks
        // 以及 TaskCenterEntryCard 设计链路断开。此处将其叠加到背景图顶部。
        MansionHeader(
            onTaskCenterClick   = onNavigateToTasks,
            hasNewNotification  = unreadCount > 0,
            onNotificationClick = onNavigateToNotifications,
            modifier            = Modifier.align(Alignment.TopCenter),
        )

        // ── [5] 新手引导 Tooltip ──────────────────────────────
        OnboardingTooltip(
            visible   = uiState.showOnboardingTooltip,
            onDismiss = { viewModel.dismissOnboarding() },
            modifier  = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = Spacing.xl),
        )

        // ── [6] 圆桌隐藏入口（大门台阶区域）+ 门环呼吸 + 引导气泡 ──
        // v23 修正：探针实测大门顶点(0.498,0.869)+左肩(0.391,0.905)+
        // 右肩(0.604,0.901)三点，左右底部无法直接测量，按"肩部到顶点
        // 垂直距离"估算底部位置（2026-07-04 对话）：
        //   cx=0.4975 cy=0.9030 w=0.2130 h=0.0680
        // 比旧值(cx=0.500 cy=0.870 w=0.30 h=0.07)整体下移、且收窄，
        // 与实测大门轮廓贴合更好。
        //
        // UI 升级 v2.0 帧03：门环呼吸 3.2s —— 大门处一抹金色呼吸光晕，
        // 提示此处可点击召开圆桌；引导气泡首次出现指向大门。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sw = maxWidth
            val sh = maxHeight
            val btnW = sw * 0.213f
            val btnH = sh * 0.068f
            val unlockedIds = uiState.characters
                .filter { it.isUnlocked }
                .take(9)
                .map { it.id }

            // 门环呼吸光晕（3.2s 全周期 = 1600ms 半周期）
            val doorTransition = rememberInfiniteTransition(label = "door_breath")
            val doorGlow by doorTransition.animateFloat(
                initialValue = if (isDark) 0.15f else 0.10f,
                targetValue  = if (isDark) 0.40f else 0.28f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(1600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "door_glow_alpha",
            )

            // UI v2.0 帧03 校准：门环为 30dp 金边圆环（HTML border:2px solid #DCC08A;border-radius:50%）
            val ringSize = 30.dp
            Box(
                modifier = Modifier
                    .offset(
                        x = sw * 0.4975f - btnW / 2,
                        y = sh * 0.9030f - btnH / 2,
                    )
                    .size(btnW, btnH)
                    .drawBehind {
                        // 门环呼吸光晕：径向金色渐变，随 doorGlow 透明度脉动
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(
                                    Palette.Gold.copy(alpha = doorGlow),
                                    Color.Transparent,
                                ),
                                center = Offset(size.width * 0.5f, size.height * 0.5f),
                                radius = size.maxDimension,
                            )
                        )
                        // 金边圆环（2dp GoldBright 描边）
                        val cx = size.width * 0.5f
                        val cy = size.height * 0.5f
                        val r = ringSize.toPx() * 0.5f
                        drawCircle(
                            color = Palette.GoldBright,
                            radius = r,
                            center = Offset(cx, cy),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                        )
                    }
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication        = null,
                    ) {
                        if (unlockedIds.size >= 2) {
                            onNavigateToRoundtable(unlockedIds)
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar("需要解锁至少 2 位角色才能召开圆桌")
                            }
                        }
                    },
            )

            // 引导气泡：首次进入公馆时在大门上方显示，点击或 4s 后消失
            if (showDoorGuide) {
                DoorGuideBubble(
                    modifier = Modifier
                        .offset(
                            x = sw * 0.4975f - 60.dp,
                            y = sh * 0.9030f - btnH / 2 - 44.dp,
                        ),
                    onDismiss = { showDoorGuide = false },
                )
            }
        }

        // ── [8] Snackbar 提示（圆桌锁定反馈）────────────────
        SnackbarHost(
            hostState = snackbarHostState,
            modifier  = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Spacing.xl),
            snackbar = { data ->
                Snackbar(snackbarData = data)
            },
        )
    }

    // ── [7] 长按预览底部弹窗 ─────────────────────────────────
    val previewId = uiState.previewCharacterId
    if (previewId != null) {
        val character = uiState.characters.find { it.id == previewId }
        val presence  = uiState.presenceMap[previewId]
        if (character != null && presence != null) {
            CharacterPreviewSheet(
                character     = character,
                presence      = presence,
                onDismiss     = {
                    viewModel.dismissPreview()
                    viewModel.markFirstInteraction()
                },
                onStartChat   = { id: Int ->
                    viewModel.dismissPreview()
                    onNavigateToChat(id)
                },
                onViewProfile = { id: Int ->
                    viewModel.dismissPreview()
                    onNavigateToProfile(id)
                },
                // BUG-4 修复：补传 onViewFamily，使预览弹窗里的「家族」按钮生效。
                onViewFamily  = { id: Int ->
                    viewModel.dismissPreview()
                    familyPickerTarget = id
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  LightDustOverlay — 光尘（帧03 公馆氛围装饰）
//  缓慢上浮的金色微尘粒子，世界层慢动效（≥1.8s）。
//  12 颗粒子各自独立相位，亮色淡金、暗色暖金，alpha 极低不抢主体。
// ─────────────────────────────────────────────────────────────

@Composable
private fun LightDustOverlay(isDark: Boolean) {
    val dustColor = if (isDark) Palette.GoldBright else Palette.Gold
    val transition = rememberInfiniteTransition(label = "light_dust")
    // 单条 8s 线性进度驱动全部粒子，每颗粒子按各自 speed/phase 从中派生位置，
    // 避免在 Canvas draw lambda 内调用 @Composable animateFloat（非法）。
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dust_progress",
    )
    // 12 颗粒子，固定种子参数（位置/速度/相位/大小），无随机性保证稳定。
    val particles = remember {
        (0 until 12).map { i ->
            val seed = i * 37
            DustParticle(
                xFrac = (seed * 13 % 100) / 100f,
                speed = 0.6f + (seed * 11 % 100) / 100f * 0.8f,
                phase = (seed * 17 % 100) / 100f,
                sizeDp = 2 + (seed % 3),
                driftX = ((seed * 7 % 20) - 10).toFloat(),
            )
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEachIndexed { i, p ->
            val localProgress = ((progress * p.speed + p.phase) % 1f)
            val x = p.xFrac * size.width + p.driftX * localProgress
            val y = size.height * (1f - localProgress)
            val alpha = (0.5f - kotlin.math.abs(localProgress - 0.5f)) * 0.12f
            val radius = p.sizeDp.dp.toPx() / 2f
            drawCircle(
                color = dustColor.copy(alpha = alpha.coerceAtLeast(0f)),
                radius = radius,
                center = Offset(x, y),
            )
        }
    }
}

private data class DustParticle(
    val xFrac: Float,
    val speed: Float,
    val phase: Float,
    val sizeDp: Int,
    val driftX: Float,
)

// ─────────────────────────────────────────────────────────────
//  DoorGuideBubble — 大门引导气泡（帧03 引导气泡）
//  指向公馆大门（圆桌入口）的小气泡，提示用户"点这里召开圆桌"。
//  4s 后自动消失，或点击消失。
// ─────────────────────────────────────────────────────────────

@Composable
private fun DoorGuideBubble(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    LaunchedEffect(Unit) {
        delay(4000L)
        onDismiss()
    }

    val transition = rememberInfiniteTransition(label = "door_guide_pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "guide_pulse",
    )

    Box(
        modifier = modifier
            .alpha(pulseAlpha)
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.bgElevated)
            .clickable { onDismiss() }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        Text(
            text = "轻触大门，召开圆桌",
            style = type.caption.copy(fontWeight = FontWeight.Medium),
            color = Palette.GoldDeep,
            fontSize = 11.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Previews
// ─────────────────────────────────────────────────────────────

@Preview(name = "WorldScreen · Dark", showBackground = true,
    backgroundColor = 0xFF12131A.toLong(), widthDp = 390, heightDp = 844)
@Composable
private fun PreviewWorldDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) { WorldScreen() }
}

@Preview(name = "WorldScreen · Light", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewWorldLight() {
    ZaijianTheme(appTheme = AppTheme.LIGHT) { WorldScreen() }
}
