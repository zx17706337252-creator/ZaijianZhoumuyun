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
import androidx.compose.foundation.layout.statusBarsPadding
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
//      头像明显偏大，应该跟宥熙/顾澜一样——已去掉这个特殊分支。v53 起
//      九人坐标改为按角色 id 各自独立查表（见下方 archSpots），索菲娅
//      现在和宥熙/顾澜共用同一套统一逻辑，不再有任何特殊放大分支。
//    - colX 左右列的相对间距（0.500 中心 ±0.260）当时验证仍然成立；
//      v53 用拖动式探针对全部 9 格逐一三点法实测后，发现同层左右两格
//      并非绝对对称（存在千分位级偏移），已按"左右对中间格对称"重新
//      校准，具体数值见下方 archSpots 表，不再引用这里的估算值。
//  九人精确坐标见下方 BoxWithConstraints 内的 archSpots 表（v53），
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
        // 坐标校准方法：拖动式坐标探针（长按背景任意位置切换调试网格，
        // 见下方"校准工具"说明）。九人最终坐标见下方 archSpots，
        // 校准过程与数据处理原则见 archSpots 定义处的 v53 注释。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sw = maxWidth
            val sh = maxHeight

            // v53 修正：v23 的"每层三格共用一套 cardW/archHeight/cy"改为
            // 九人各自独立取值。原因：用户用拖动式坐标探针对全部 9 个
            // 拱门逐个做三点法实测（左下角+右下角+拱顶顶点，2026-07-06
            // 对话），发现同层三格的宽度/高度并不完全相同——尤其中间格
            // 普遍比两侧格更宽（露娜/索菲娅/明媚+江凡 vs 蒂法+伊芙/宥熙+
            // 顾澜/莫婉凝），继续用同层共用一套值会导致两侧格偏差。
            //
            // 数据处理原则（手测存在抖动，不能直接采信原始点位）：
            //   1. 拱门本身左右对称 -> 顶点 x 强制取左右下角连线中点，
            //      不采信手测顶点的 x 偏移。
            //   2. 同层左右两格相对中间格的列间距应左右对称 -> 取左右
            //      偏移量的平均值，强制对称后再分别算左右格 cx。
            //   3. 同层三格底边高度应一致（同一列窗框排布）-> 取三格
            //      平均值统一，抹掉千分位级测量噪声。
            //   4. 左右两格的宽度/高度互相对称 -> 取两者平均值；中间格
            //      结构本来就可能与两侧不同（这次实测确认中间格更宽），
            //      保留中间格自己的实测值不与两侧强行拉平。
            //   5. 莫婉凝（BASEMENT 中格）窗框显著更窄，不参与"中间格
            //      更宽"的通用规律，直接用实测值。
            //
            // v57 修正：追加实测索菲娅左下/右下、江凡左下/右下、明媚
            // 左下/右下（2026-07-06 对话）。江凡此前一直是"肉眼看和
            // 明媚完全对称，未单独测量，按镜像推算"，现在有了真实点位，
            // 不再需要镜像假设——实测显示江凡 cx 比镜像推算值大 0.011，
            // 底边也比明媚低了一截，两者并不是严格镜像对称。顶点未重测，
            // 沿用各自原有 archHeight（h），仅更新 cx / w / cy（cy 按
            // "新底边 y - h/2"重新推算）。
            //
            // v58 修正：用户指出——除莫婉凝（BASEMENT 中格，窗框显著更
            // 窄，是唯一的例外）外，同层左右两格宽度必须完全一致，这条
            // 约束此前几轮零散更新单个角色时曾被破坏（只更新提到的角色，
            // 没有回头检查同层另一侧是否还保持一致）。这次追加实测蒂法、
            // 伊芙左下/右下点，两者原始宽度并不相等（0.1550 vs 0.1620，
            // 差 0.007），按约束取平均强制拉齐为 0.1585（比 v53 版本
            // SECOND 层左右格的 0.1360 明显更宽，判断 v53 那次蒂法/伊芙
            // 原始点位测得偏窄）。同时追加实测莫婉凝左下/右下，与 v53
            // 版本几乎一致（cx 0.4990→0.5000，w 0.1000→0.1060），判断
            // 为测量噪声范围内的正常波动。已逐层核对，当前 SECOND/
            // FIRST/BASEMENT 三层左右格宽度均一致。
            //
            // v41 修正：追加实测露娜（SECOND 中格）、宥熙+顾澜（FIRST
            // 左右格）、明媚+江凡（BASEMENT 左右格，明媚仅右下角新测，
            // 左下沿用现值）。同时按"除莫婉凝外同层左右宽度必须一致"
            // 规则重新拉齐：FIRST 层宥熙实测 w=0.1580、顾澜实测
            // w=0.1700，平均拉齐为 0.1640；BASEMENT 层明媚实测
            // w=0.1620、江凡实测 w=0.1680，平均拉齐为 0.1650。顶点
            // （h）未重测，沿用各自原有 archHeight，cy 按"新底边 y -
            // h/2"重新推算：露娜 0.4080-0.1483/2=0.3338，宥熙
            // 0.6050-0.1610/2=0.5245，顾澜 0.6045-0.1610/2=0.5240，
            // 明媚 0.7928-0.1515/2=0.7170，江凡 0.7955-0.1515/2=0.7198。
            // 这轮未变：蒂法、伊芙、莫婉凝、索菲娅——保持 v58 的值不动。
            //
            // v42 修正：本轮改用顶点+右下角两点法逐个重测（顶点x即为
            // cx，w=(右下角x-顶点x)×2，h=右下角y-顶点y，cy=顶点y+h/2），
            // 覆盖 8 个角色（除莫婉凝外全部，2026-07-06 对话，历时多轮
            // 确认）：
            //   - SECOND：蒂法顶(0.277,0.256)+右下(0.358,0.407)，露娜
            //     顶(0.500,0.257)+右下(0.584,0.408)，伊芙顶(0.735,0.258)
            //     +右下(0.809,0.407)（右下角x用户中途从0.804修正为
            //     0.809）。蒂法/伊芙是SECOND层左右格，按"除莫婉凝外
            //     左右宽度必须一致"规则，算出的w(0.162/0.148)取平均
            //     拉齐为0.1550；露娜是中格不参与拉齐。
            //   - FIRST：宥熙只给了新顶点(0.280,0.441)，左右(cx/w)
            //     user确认不调，用现有底边(0.6050，来自v41)反推新
            //     h=0.164、cy=0.523。索菲娅只给了右下角(0.588,0.603)，
            //     顶点从现有值反推（cx=0.4975,顶点y=cy-h/2=0.44245），
            //     两点法算出w=0.1810、h=0.1606、cy=0.5227——索菲娅是
            //     中格不参与左右拉齐。顾澜只给了右下角(0.810,0.603)，
            //     user反馈"左边缘被遮挡量不到，且当前显示偏左"，
            //     因此不用两点法重算w（避免用不可靠的左边缘假设），
            //     改为"只平移、宽度不变"：w沿用现有0.1640，新cx=
            //     右下角x-w/2=0.810-0.0820=0.7280，h/cy仍用右下角y=
            //     0.603配合现有顶点反推（h=0.1595,cy=0.5233）。宥熙/
            //     顾澜w都维持0.1640，本轮左右天然一致，无需再拉齐。
            //   - BASEMENT：明媚新顶点(0.285,0.633)，江凡新顶点
            //     (0.735,0.634)，user确认"拉长、底部不变"——底边沿用
            //     v41已定值（明媚0.79275、江凡0.79655），w维持不变
            //     (0.1650)，cx用新顶点x，h/cy按新顶点+沿用底边重算：
            //     明媚h=0.1598,cy=0.7129；江凡h=0.1626,cy=0.7153。
            //
            // 按角色 id（1~9，固定对应蒂法/露娜/伊芙/宥熙/索菲娅/顾澜/
            // 明媚/莫婉凝/江凡，参见 StateExtensions.kt）直接查表，
            // 不再依赖 floor+shelfCol 的组合判断——每人数值已各自独立。
            data class ArchSpot(val cx: Float, val cy: Float, val w: Float, val h: Float)

            val archSpots: Map<Int, ArchSpot> = mapOf(
                // ── SECOND：蒂法(1) / 露娜(2) / 伊芙(3) ── 左右w已强制一致(0.1550，v42拉齐)
                1 to ArchSpot(cx = 0.2770f, cy = 0.3315f, w = 0.1550f, h = 0.1510f),
                2 to ArchSpot(cx = 0.5000f, cy = 0.3325f, w = 0.1680f, h = 0.1510f),
                3 to ArchSpot(cx = 0.7240f, cy = 0.3325f, w = 0.1580f, h = 0.1490f),
                // ── FIRST：宥熙(4) / 索菲娅(5) / 顾澜(6) ── 左右w已一致(0.1640)
                4 to ArchSpot(cx = 0.2790f, cy = 0.5230f, w = 0.1640f, h = 0.1640f),
                5 to ArchSpot(cx = 0.4995f, cy = 0.5227f, w = 0.1730f, h = 0.1606f),
                6 to ArchSpot(cx = 0.7250f, cy = 0.5233f, w = 0.1640f, h = 0.1595f),
                // ── BASEMENT：明媚(7) / 莫婉凝(8) / 江凡(9) ── 左右w已一致(0.1650)，
                // 莫婉凝(中格)窄格例外，不参与左右一致规则
                7 to ArchSpot(cx = 0.2755f, cy = 0.7141f, w = 0.1650f, h = 0.1598f),
                8 to ArchSpot(cx = 0.5045f, cy = 0.7190f, w = 0.1370f, h = 0.1600f),
                9 to ArchSpot(cx = 0.7255f, cy = 0.7147f, w = 0.1650f, h = 0.1626f),
            )

            uiState.characters.forEach { char ->
                // P1-13-13 修复（加固，非修 bug）：archSpots 只登记 9 位固定
                // 母亲角色（id 1~9），不在表里的 id（例如女儿角色，id 1000+，
                // shelfCol 故意占位为 0，不进公馆九宫格，走 FamilyScreen 单独
                // 展示——这是产品设计，不是缺陷）直接查表拿不到值，用
                // ?: return@forEach 兜底跳过，防止未来数据来源变化时
                // 直接崩掉整个房间界面。已核实当前 uiState.characters 始终
                // 来自 DefaultCharacters（9 个固定角色），女儿数据从未合并
                // 进这个列表，当前不存在触发越界的真实调用路径。
                val spot = archSpots[char.id] ?: return@forEach
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
