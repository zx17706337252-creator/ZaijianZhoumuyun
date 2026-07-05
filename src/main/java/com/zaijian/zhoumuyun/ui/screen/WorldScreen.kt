package com.zaijian.zhoumuyun.ui.screen

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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.R
import com.zaijian.zhoumuyun.data.model.FloorEnum
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.component.CalibrationProbeOverlay
import com.zaijian.zhoumuyun.ui.component.CharacterPreviewSheet
import com.zaijian.zhoumuyun.ui.component.FamilyPickerSheet
import com.zaijian.zhoumuyun.ui.component.MansionHeader
import com.zaijian.zhoumuyun.ui.component.OnboardingTooltip
import com.zaijian.zhoumuyun.ui.component.WindowCard
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.PresenceViewModel

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
//      头像明显偏大，应该跟宥熙/顾澜一样——已去掉这个特殊分支，索菲娅
//      现在统一走 archHeight(FIRST, shelfCol)，不再单独放大。
//    - colX 左右列的相对间距（0.500 中心 ±0.260）实测后仍然成立，
//      三层同一 colX 套用到全部 9 格逐一核对均吻合，无需改列间距。
//  房间坐标（cx/cy 为内容区宽/高比例，FillBounds 坐标系）：
//
//      二楼  [左 0.244] [中 0.500] [右 0.756]   cy=0.248  h=0.155
//      一楼  [左 0.244] [中 0.500] [右 0.756]   cy=0.494  h=0.180（楼梯间 h=0.205）
//      地下  [左 0.244] [中 0.500] [右 0.756]   cy=0.715  h=0.150
//      cardW（三层共用拱门宽度）= 0.140
//      圆桌大门入口：cy=0.870（台阶正前方，远离地下室角色，未变）
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
    bgStyleIndex: Int = 0,
    viewModel: PresenceViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors  = ZaijianTheme.colors
    val isDark  = colors.isDark
    var familyPickerTarget by remember { mutableStateOf<Int?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 校准工具：长按背景空白处切换，画出每个拱门当前的实际坐标框，
    // 方便对着背景图精修 cx/cy/archWidth/archHeight，不用再靠肉眼反复猜。
    var showDebugGrid by remember { mutableStateOf(false) }
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

    // ── 关键修复：整个内容区 = 屏幕去掉底部导航栏后的区域 ──────
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
    Box(modifier = Modifier
        .fillMaxSize()
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDark) Palette.MansionNightOverlay else Palette.MansionDayOverlay)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            showDebugGrid = !showDebugGrid
                        },
                    )
                },
        )

        // ── [2] 角色卡片 — 绝对定位到对应房间 ────────────────
        //
        // v19 修正（对用户实机截图做像素级网格测量，见 2026-07-02 对话中
        // 上传的调试网格截图；测量方法：把候选框叠加到截图上反复对比，
        // 直到框边缘贴合真实拱门石框，而不是像 v8～v11 那样凭肉眼直接
        // 估算比例数字——这正是过去"改了很多次都对不上"的方法本身有
        // 问题）：
        //   - 二楼（SECOND，蒂法/露娜/伊芙所在层）实测修正：
        //     cardW 0.140→0.128，archHeight 0.155→0.175，cy 0.248→0.258。
        //     修正前的框顶部卡在屋顶装饰线以下、底部停在拱门中部，
        //     远没到房间地板，这正是"上传照片只显示一小条"的根因——
        //     不是头像组件本身的裁剪逻辑有问题（AsyncImage+Crop 早就是对
        //     的），是这个框本身对着二楼拱门来说太窄太矮，Crop 只能在
        //     这个偏小的框里居中裁出一小块。
        //   - 一楼（FIRST）、地下室（BASEMENT）、楼梯间（archHeightStair）
        //     三组数值这次没有同步验证，暂时保留 v11 旧值——用同一套
        //     "叠加候选框反复比对截图"方法逐层量一遍工作量较大，且这几层
        //     大多数格位还没上传真实头像（图上是纯色占位），从截图上更难
        //     判断真实拱门边界在哪。建议用下面新增的拖动式坐标探针
        //     （长按空白处开启调试网格）在真机上直接点这几层拱门的边缘，
        //     读数比隔着截图量准得多，量完直接把 cx/cy/archHeight 数值
        //     反馈回来即可替换。
        //   - colX 三列横向中心位置本次未变动（0.244/0.500/0.756 对
        //     二楼来说仍然吻合良好）。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sw = maxWidth
            val sh = maxHeight

            // v23 修正：三层各角色分别用真机探针五点法（矩形底边两角 +
            // 矩形顶边两角 + 哥特尖拱顶点）逐个实测校准（2026-07-04 对话）。
            // 每层三个角色各自独立测量，不再是"猜一个数字三层共用"：
            //   SECOND：蒂法/露娜/伊芙分别测量，w 0.145~0.155，cy 统一取
            //     三者平均 0.3319，h 统一取 0.1465（同层同款窗框高度一致）。
            //   FIRST：宥熙五点 + 索菲娅/顾澜左右边界点，cy 统一 0.5223，
            //     h 统一 0.1610。
            //   BASEMENT：明媚五点 + 江凡三点（参照明媚模板），莫婉凝五点
            //     单独测量——莫婉凝窗框明显更窄（w=0.101 vs 明媚/江凡
            //     w≈0.148），不能套用同层其余两格的宽度，必须单独处理。
            // colX 三列中心改为按各层实测值分别使用（不再三层共用一套）。
            val colXSecond   = listOf(0.2795f, 0.5035f, 0.7225f)
            val colXFirst    = listOf(0.2762f, 0.4995f, 0.7290f)
            val colXBasement = listOf(0.2775f, 0.5045f, 0.7275f)

            fun colXFor(floor: FloorEnum, col: Int): Float = when (floor) {
                FloorEnum.SECOND   -> colXSecond[col]
                FloorEnum.FIRST    -> colXFirst[col]
                FloorEnum.BASEMENT -> colXBasement[col]
            }

            // 莫婉凝（BASEMENT, shelfCol=2）单独一套尺寸，窗框比同层其余
            // 两格窄得多，绝不能套用 cardW(BASEMENT)/archHeight(BASEMENT)。
            fun cardW(floor: FloorEnum, shelfCol: Int): Dp = when {
                floor == FloorEnum.BASEMENT && shelfCol == 2 -> sw * 0.1010f
                floor == FloorEnum.SECOND   -> sw * 0.1517f
                floor == FloorEnum.FIRST    -> sw * 0.1562f
                floor == FloorEnum.BASEMENT -> sw * 0.1480f
                else -> sw * 0.150f
            }

            fun archHeight(floor: FloorEnum, shelfCol: Int): Dp = when {
                floor == FloorEnum.BASEMENT && shelfCol == 2 -> sh * 0.1490f
                floor == FloorEnum.SECOND   -> sh * 0.1465f
                floor == FloorEnum.FIRST    -> sh * 0.1610f
                floor == FloorEnum.BASEMENT -> sh * 0.1598f
                else -> sh * 0.150f
            }

            fun cyFor(floor: FloorEnum, shelfCol: Int): Float = when {
                floor == FloorEnum.BASEMENT && shelfCol == 2 -> 0.7205f
                floor == FloorEnum.SECOND   -> 0.3319f
                floor == FloorEnum.FIRST    -> 0.5223f
                floor == FloorEnum.BASEMENT -> 0.7169f
                else -> 0.500f
            }

            // v52 修复：索菲娅（FIRST col=2）之前被当成"楼梯间哥特双开门"
            // 单独放大处理（0.205 vs 同层其余两位 0.161，大了近 27%），
            // 用户实测反馈索菲娅头像明显偏大，应该跟宥熙/顾澜一样大——
            // 去掉这个特殊分支，统一走 archHeight(FIRST, shelfCol)。

            uiState.characters.forEach { char ->
                // P1-13-13 修复（加固，非修 bug）：getOrNull 是防御性写法，
                // 不是因为发现了脏数据。已核实过数据来源：
                //   - uiState.characters 始终来自 DefaultCharacters（9 个固定母亲角色），
                //     它们的 shelfCol 在 CharacterConfig.kt 里硬编码为 1/2/3，恒定合法。
                //   - shelfCol = 0 只出现在 DaughterIdentity.kt 的
                //     DaughterCharacterData.toCharacterConfig()，是"女儿角色"
                //     （动态生成，ID 1000+）的故意占位值，含义是"不进公馆九宫格，
                //     走 FamilyScreen 单独展示"——这是产品设计，不是缺陷。
                //   - 女儿数据从未被合并进 uiState.characters，全项目搜索确认
                //     两条路径不会交汇，当前不存在触发越界的真实调用路径。
                // 之所以仍保留 getOrNull：纯粹兜底，防止未来需求变化（比如女儿
                // 也要上墙展示）时不会因为这里没处理边界直接崩掉整个房间界面。
                // 如果以后又有审查发现"shelfCol 可能越界"，不必再深挖根因排查
                // 脏数据来源——根因已经查清，是设计如此，按这条注释跳过即可。
                if (char.shelfCol - 1 !in 0..2) return@forEach
                val cx = colXFor(char.floor, char.shelfCol - 1)
                val cy = cyFor(char.floor, char.shelfCol)
                val thisW = cardW(char.floor, char.shelfCol)
                val thisH = archHeight(char.floor, char.shelfCol)
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
                    // v19：调试网格开启时卡片不响应点击/长按（双保险——即使
                    // 探针拦截层因为某种原因没吃到手势，卡片这边也不会误触
                    // 跳转/弹预览，避免校准时手滑进对话页）。
                    onClick        = {
                        if (!showDebugGrid) {
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
                if (showDebugGrid) {
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
            onTaskCenterClick = onNavigateToTasks,
            modifier          = Modifier.align(Alignment.TopCenter),
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

        // ── [6] 圆桌隐藏入口（大门台阶区域，全透明无视觉元素）──
        // v23 修正：探针实测大门顶点(0.498,0.869)+左肩(0.391,0.905)+
        // 右肩(0.604,0.901)三点，左右底部无法直接测量，按"肩部到顶点
        // 垂直距离"估算底部位置（2026-07-04 对话）：
        //   cx=0.4975 cy=0.9030 w=0.2130 h=0.0680
        // 比旧值(cx=0.500 cy=0.870 w=0.30 h=0.07)整体下移、且收窄，
        // 与实测大门轮廓贴合更好。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sw = maxWidth
            val sh = maxHeight
            val btnW = sw * 0.213f
            val btnH = sh * 0.068f
            val unlockedIds = uiState.characters
                .filter { it.isUnlocked }
                .take(9)
                .map { it.id }

            Box(
                modifier = Modifier
                    .offset(
                        x = sw * 0.4975f - btnW / 2,
                        y = sh * 0.9030f - btnH / 2,
                    )
                    .size(btnW, btnH)
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
