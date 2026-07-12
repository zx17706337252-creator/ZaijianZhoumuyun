package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.Icon
import com.zaijian.zhoumuyun.R
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.data.model.StatusType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.zaijian.zhoumuyun.ui.component.BookCard
import com.zaijian.zhoumuyun.ui.component.CalibrationProbeOverlay
import com.zaijian.zhoumuyun.ui.component.CharacterPreviewSheet
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.PresenceViewModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.shadow
import com.zaijian.zhoumuyun.domain.TaskCompletionMessage

// ─────────────────────────────────────────────────────────────
//  CharacterScreen — 书架页
//
//  层级（从后到前）：
//    [0] 书架插图背景（日/夜随主题切换，FillBounds 坐标系）
//    [1] BoxWithConstraints — BookCard 绝对定位到书本位置
//    [2] ShelfHeader（顶部毛玻璃）
//    [3] CharacterPreviewSheet（长按弹出）
//
//  ── 背景图坐标系说明 ──────────────────────────────────────────
//  背景图用 ContentScale.FillBounds 铺满「导航栏以上」的内容区域
//  （即 fillMaxSize + navigationBarsPadding），而非铺满整屏。
//  BoxWithConstraints 的 maxHeight = 内容区高度 = 背景图渲染高度，
//  cx/cy 比例坐标与背景图像素坐标严格对应，不因底栏高度错位。
//
//  v8 修正（对 shelf_day.webp 1024×1536 原图做像素级网格测量）：
//    - ovalWFrac/ovalHFrac 此前是 0.1076/0.1037，实测椭圆框实际
//      约 0.150/0.132，偏小 30% 左右，头像明显小于书本上的金色
//      椭圆浮雕框。colX/rowY 本身此前已经比较接近实测值，只做微调。
//    - 同时去掉 size(cardW, cardH + 28.dp) 的"加高凑名字空间"写法：
//      BookCard 内部头像 Box 靠父 Box 的 contentAlignment=Center 定位，
//      多加的 28dp 会被 Center 平分，头像被整体拖下去 14dp、偏离椭圆
//      真实中心。BookCard 名字标签本来就是用
//      `.offset(y = ovalHeight + 4.dp)` 固定贴在椭圆正下方，不依赖
//      外层 Box 多留的高度，所以 size 直接传真实 (cardW, cardH) 即可，
//      头像和名字都会自动各自归位。
//
//  v9 修正（p17，对实机截图 1264×2780 做逐格逐像素网格测量，见
//  《v49_p17_avatar_alignment_fix》审查素材）：
//    - v8 的 colX/rowY/ovalWFrac/ovalHFrac 仍整体偏右上，且椭圆偏
//      "宽而矮"，与书本封面金色椭圆浮雕框实际"窄而高"的比例不符，
//      九宫格逐格截图核对后，中心点 cx 左移约 0.7 个百分点、cy 下
//      移约 0.4 个百分点，宽高各收窄约一成，实测后三列/三行的等距
//      关系仍然成立（同一套 colX 间距、rowY 间距套到所有 9 格全部
//      吻合），故沿用"首格坐标 + 固定间距"的参数结构，只更新首格
//      坐标和椭圆尺寸这四个数：
//        cx: 0.255 → 0.248   cy: 0.256 → 0.252
//        ovalWFrac: 0.150 → 0.138   ovalHFrac: 0.132 → 0.122
//      列间距（0.256/0.512）、行间距（0.233/0.472）保持不变。
//
//  书本格位坐标（cx/cy 为内容区宽/高比例，FillBounds 坐标系）：
//
//      Row1  [0.248] [0.504] [0.760]   cy=0.252  ← 上层书架椭圆框中心
//      Row2  [0.248] [0.504] [0.760]   cy=0.485  ← 中层书架椭圆框中心
//      Row3  [0.248] [0.504] [0.760]   cy=0.724  ← 下层书架椭圆框中心
//
//  ── 校准工具 ─────────────────────────────────────────────────
//  长按背景空白处（非书本区域）2 秒可切换"调试网格"，用高亮色框
//  精确画出当前代码认为每本书的椭圆在哪、多宽多高，方便截图比对
//  背景图精修。再长按一次关闭。
// ─────────────────────────────────────────────────────────────

@Composable
fun CharacterScreen(
    onNavigateToDetail: (characterId: Int) -> Unit = {},
    onNavigateToFamily: (characterId: Int) -> Unit = {},
    onNavigateToChat: (characterId: Int) -> Unit = {},
    onNavigateToTaskCenter: () -> Unit = {},
    bgStyleIndex: Int = 0,
    viewModel: PresenceViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val taskCompletionToast = uiState.taskCompletionToast
    val isDark  = ZaijianTheme.colors.isDark
    var previewCharacterId by remember { mutableStateOf<Int?>(null) }
    // 校准工具：长按背景空白处切换，画出每本书当前的实际椭圆坐标框。
    var showDebugGrid by remember { mutableStateOf(false) }
    // v20 状态清理：探针的记点/拖动状态已经搬进共享组件
    // CalibrationProbeOverlay 内部自己 remember（见下方 [1b]），这里
    // 不再需要单独持有 probePoints。
    //
    // 【尚未解决，之前的 v49_p18 遗留问题】：当时有用户反馈书架竖轴
    // （cy）显著偏低、头像上方留了一大块空白背景，隔着压缩截图肉眼
    // 估出三行金色椭圆浮雕框大致中心可能在 cy≈0.20/0.48/0.72（当前代码
    // 仍是 0.252/0.485/0.724），但这只是截图肉眼估算，从未用探针在
    // 真机上核实过，不确定准不准——如果头像位置看起来还是偏低，建议
    // 直接用现在修好的探针工具在真机上点一下三排椭圆的上下边，量出来
    // 的 cy 数值发给我，比继续猜数字可靠。

    // ── 关键修复：整个内容区 = 屏幕去掉状态栏和底部导航栏后的区域 ──
    // [v26 修复] 原来只用了 navigationBarsPadding()，这只消费"系统"
    // 导航栏（手势条/虚拟按键）的 inset，不知道 App 自己在
    // AppNavigation.kt 里手绘的那条 Spacing.bottomNavHeight（44dp）
    // 高的底部 Tab 栏——两者不是一回事，系统对自绘 Tab 栏一无所知。
    // 内容区需要在系统 inset 之外再额外让出 Tab 栏的高度，下边界才
    // 真正贴在 Tab 栏上边缘，书架最后一排卡片才不会被 Tab 栏盖住。
    //
    // v54 修复：原来这里没有消费顶部状态栏 inset，背景图从屏幕最
    // 顶端（Y=0）就开始渲染并被 FillBounds 拉伸铺满剩余空间，跟
    // 顶部工具栏自己单独处理的 statusBarsPadding() 不一致，导致
    // 背景图实际拉伸范围比视觉上应该露出的区域多出一截状态栏高度，
    // 是深色模式背景图看起来被拉长、书本头像整体贴合偏差的根因
    // 之一（另一根因是深浅色两张背景图构图比例本身不一致，见下方
    // colX/rowY 相关注释）。加上 statusBarsPadding() 后，内容区
    // （含背景图）从状态栏下方开始，与顶栏可见区域基准一致。
    Box(modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(bottom = Spacing.bottomNavHeight)
    ) {

        // ── [0] 书架插图背景 ─────────────────────────────────
        if (bgStyleIndex == 1) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDark) Palette.Night else Palette.Cream),
            )
        } else {
            Image(
                painter            = painterResource(
                    if (isDark) R.drawable.shelf_night else R.drawable.shelf_day
                ),
                contentDescription = null,
                modifier           = Modifier.fillMaxSize(),
                contentScale       = ContentScale.FillBounds,
            )
        }

        // ── [0a] 长按空白处切换调试网格 ─────────────────────────
        // v20：探针本身的记点/拖动逻辑挪到 [1b]（书本卡片渲染*之后*的
        // 全屏拦截层，见下方），不再放在这里。这一层现在只负责"长按
        // 空白处切换调试网格"，书本卡片自己的 combinedClickable 依旧
        // 会先消费掉落在书本范围内的手势——但这没关系，因为调试网格
        // 开启后真正拦截全部手势的是 [1b]，不再依赖这一层能不能收到
        // 落在书本范围内的点击。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            showDebugGrid = !showDebugGrid
                        },
                    )
                },
        )

        // ── [0b] 亮色主题：金色暖调校正层 ────────────────────
        if (!isDark && bgStyleIndex != 1) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x08C4A46A),
                                Color(0x10C4A46A),
                            )
                        )
                    )
            )
        }

        // ── [1] 书本角色卡片 — 绝对定位到书架格位 ────────────
        //
        // v5 适配：BookCard 完全透明，只叠加头像+名字到背景图书本椭圆框。
        // v8 修复：去掉了 v7 的「cardH 额外加 28.dp」写法。BookCard 内部
        // 头像 Box 靠父 Box 的 contentAlignment=Center 定位，size() 传入
        // 的高度每多 1dp，Center 就会把头像往下多挤 0.5dp——28dp 的额外
        // 高度会让头像整体偏离椭圆真实中心 14dp。名字标签本来就是用
        // `.offset(y = ovalHeight + 4.dp)` 固定贴在椭圆正下方（不依赖
        // 外层 Box 多留的高度），所以这里直接传真实 (cardW, cardH)，
        // 头像和名字都会各自精确归位，不需要再靠加高度去"凑空间"。
        // ovalWFrac/ovalHFrac 同步按背景图实测网格改为 0.150/0.132
        // （此前 0.1076/0.1037 偏小约 30%，头像明显小于书本椭圆浮雕框）。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sw    = maxWidth
            val sh    = maxHeight

            // v22 修正：用户拖动探针实测 9 个椭圆顶部中点坐标（三行
            // 三列各一个点，2026-07-03），结果显示：
            //   - cy（高度）基本准确，没有再调整。
            //   - cx（水平中心）比原来的 colX 都略微偏右一点：
            //     左列 0.248→0.254，中列 0.504→0.510，右列 0.760→0.763
            //     （三行测量取平均，行间浮动在 ±0.002 以内，一致性很好）。
            //   - 宽度：用户反馈"左右宽度需要轻微收窄"，但这9个点只测了
            //     每个椭圆的顶部中点，没有测左右边缘，没法直接算出应该
            //     收窄多少。用户要求先按经验收窄 3% 试效果（0.138→0.134），
            //     不是从实测边缘反推的精确值，如果窄多了/窄少了需要用
            //     探针实测左右边缘点再调。
            // v22 二次修正：用户反馈头像右侧对齐正常，左侧略微突出于
            // 书本轮廓，需收窄椭圆自身宽度的 2~3%（非相对屏幕宽度）。
            // 做法：只收窄左边界、右边界不动 —— 整体宽度减少
            // ovalWFrac×2.5%，中心 cx 同步右移收窄量的一半，使右边缘
            // 位置保持不变，左边缘相应内收。
            // v23 修正：用户拖动探针实测 row2/row3 共 6 个椭圆"顶部中点"
            // 坐标（2026-07-04 对话），九本书完全对称，据此推导：
            //   - colX：取 row2/row3 实测顶部中点 x 的均值，[0.2740,
            //     0.5195, 0.7630]（比旧值整体右移约 0.5~1.8 个百分点）。
            //   - cy：实测顶部 y 比"旧cy - ovalHFrac/2"预测值高出约
            //     0.0083~0.0100（row2/row3 分别），说明椭圆整体位置比
            //     代码认为的更靠上，整体上移均值 0.0092（保持 ovalHFrac
            //     不变，只平移 cy），与用户此前反馈"头像上方留白明显"
            //     的现象吻合。row1 未提供实测点，同步应用相同上移量
            //     （同一书架三行结构一致，理论上偏移规律相同）。
            //   这套数值已用真机深色模式截图核实基本贴合，保留作为
            //   深色版（shelf_night）专用坐标。
            //
            // v55 修复：用户反馈真机截图显示——深色模式下头像贴合较准，
            // 浅色模式下头像整体偏右下（书本浮雕框在头像左上方露出一截）。
            // 排查后确认 shelf_day.webp 和 shelf_night.webp 两张背景图
            // 本身构图比例不一致（图片素材层面的差异，非同一底图仅换
            // 色调），继续用同一套 colX/rowY/ovalWFrac/ovalHFrac 没法
            // 同时适配两张图。用户在浅色模式下用拖动探针实测蒂法/宥熙/
            // 明媚（左列三排）的顶部+中心点、莫婉凝/江凡（中右列）顶部
            // 点（2026-07-06 对话），核实 9 个椭圆宽高完全一致，只有
            // 位置不同，据此推导浅色版专属 colX/rowY/ovalHFrac：
            //   - 半高（半径）= (宥熙中心y - 宥熙顶部y + 明媚中心y - 明媚
            //     顶部y) / 2 = 0.0535 -> ovalHFrac = 0.1070（比深色版
            //     0.122 更矮，浅色图书本椭圆本身画得更扁）。
            //   - rowY：蒂法/宥熙/明媚顶部y + 半高，得 [0.2115, 0.4480,
            //     0.6830]，比深色版三排统一低了约 0.03（浅色图书架
            //     整体比深色图偏上/偏小，与真机截图"浅色头像位置更高"
            //     吻合）。
            //   - colX：左列取蒂法/宥熙/明媚顶部 x 均值 0.2543，中列取
            //     莫婉凝顶部 x 0.5110，右列取江凡顶部 x 0.7630（右列与
            //     深色版基本一致，左列、中列比深色版偏左）。
            //   - ovalWFrac 未重测，暂沿用深色版同一数值。
            //
            // v56 修正：用户反馈 v55 版本位置已经对了，但椭圆上下高度
            // 偏小，头像上下会露出书本浮雕框边缘。追加实测索菲娅、明媚
            // 顶部+底部两点（2026-07-06 对话），直接量出真实椭圆总高
            // 分别为 0.1090 / 0.1100，比 v55 用"顶部+中心点*2"间接推算
            // 出的 0.1070 更准确（直接量总高比量半高再乘二误差更小），
            // 取两者平均 0.1095。cx/cy 用这次顶+底两点重新核对，跟 v55
            // 版本差异都在千分位以内（≤0.003），判断为测量噪声，位置
            // 保持不变，只修正 ovalHFrac。
            //
            // v41 修正：追加实测三排各自的顶部+底部两点（2026-07-06
            // 对话，左右 x 数据本轮不采用，只用 y）：row1(蒂法/露娜/
            // 伊芙) 顶0.152/底0.272，row2(宥熙/索菲娅/顾澜) 顶0.388/
            // 底0.508，row3(明媚/莫婉凝/江凡) 顶0.626/底0.743。三排
            // 量出的总高分别为 0.120/0.120/0.117，取平均 0.119 作为
            // 新 ovalHFrac（比 v56 的 0.1095 更高，头像上下留白进一步
            // 收窄）。三排中点（cy）分别为 0.212/0.448/0.6845，与现有
            // rowY [0.2115, 0.4480, 0.6830] 差异均在千分位以内，判断
            // 为测量噪声，rowY 保持不变，只更新 ovalHFrac。
            //
            // v41 二次修正：用户反馈书架第三排底部偏短，追加实测
            // row3 底边三点（左0.260/0.744、中0.516/0.743、右
            // 0.767/0.744，2026-07-06 对话）——底边y平均 0.7437，
            // 比上面刚定的 row3 底边（rowY[2]+ovalHFrac/2=0.6830+
            // 0.0595=0.7425）多 0.0012。用户确认顶部不变、只把底边
            // 下移这 0.0012，且要求三排统一按同一偏移量下拉（不是
            // 只调第三排）。做法：ovalHFrac 整体 +0.0012（顶部不动，
            // 底边下移全部偏移量），rowY 整体 +0.0006（下移偏移量的
            // 一半，使中心点跟着底边下移同步下移，顶部则保持原位）。
            // colX 这轮不变。
            //
            // v45 修正：用户反馈深色版最上一排（蒂法/露娜/伊芙）整体偏了，
            // 追加实测三人新顶点（2026-07-07 对话）：蒂法(0.289,0.172)、
            // 露娜(0.523,0.178)、伊芙(0.762,0.170)。三人新顶部y平均=
            // (0.172+0.178+0.170)/3=0.1733，跟现有顶部y(0.2428-0.122/2=
            // 0.1818)相差-0.0085（整体上移0.0085）。用户确认"移动过去"
            // ——整排平移、高度(ovalHFrac)不变，只挪位置。新rowY[0]=
            // 0.2428-0.0085=0.2343；新colX(row1)直接取三个新顶点的x值
            // [0.289, 0.523, 0.762]。
            //
            // 注意：colX 原来是三排共用同一组值（1个 List<Float>），但这次
            // 只调 row1，row2/row3 要保持不动——如果继续用共用的 colX，会
            // 把 row2(宥熙/索菲娅/顾澜)、row3(明媚/莫婉凝/江凡) 的列位置也
            // 一起带偏。因此把 colX 从"1组三排共用"拆成"每排各自一组"的
            // 3×3 结构（colXByRow[row-1][col-1]），row2/row3 沿用原来共用
            // 的那组值，row1 用新值，互不影响。
            val colXByRow: List<List<Float>> = if (isDark) {
                listOf(
                    listOf(0.2890f, 0.5230f, 0.7620f), // row1：蒂法/露娜/伊芙（v45 新值）
                    listOf(0.2740f, 0.5195f, 0.7630f), // row2：宥熙/索菲娅/顾澜（不变）
                    listOf(0.2740f, 0.5195f, 0.7630f), // row3：明媚/莫婉凝/江凡（不变）
                )
            } else {
                listOf(
                    listOf(0.2543f, 0.5110f, 0.7630f),
                    listOf(0.2543f, 0.5110f, 0.7630f),
                    listOf(0.2543f, 0.5110f, 0.7630f),
                )
            }
            val rowY      = if (isDark) listOf(0.2343f, 0.4758f, 0.7148f)
                             else        listOf(0.2121f, 0.4486f, 0.6836f)
            val ovalWFrac = 0.1307f
            val ovalHFrac = if (isDark) 0.122f else 0.1202f

            (1..3).forEach { row ->
                (1..3).forEach { col ->
                    val char     = uiState.characters.find {
                        it.shelfRow == row && it.shelfCol == col
                    }
                    val cx = colXByRow[row - 1][col - 1]
                    val cy = rowY[row - 1]
                    val cardW = sw * ovalWFrac
                    val cardH = sh * ovalHFrac

                    if (char != null) {
                        val presence = uiState.presenceMap[char.id] ?: PresenceState(
                            characterId = char.id,
                            statusText  = "—",
                            statusType  = StatusType.OFFLINE,
                            lastUpdated = 0L,
                        )
                        BookCard(
                            character   = char,
                            presence    = presence,
                            ovalWidth   = cardW,
                            ovalHeight  = cardH,
                            // v20：调试网格开启时不响应点击/长按（同
                            // WorldScreen.kt 的 v19 修复），避免校准时
                            // 手滑弹出预览/跳转家族页。
                            onClick     = {
                                if (!showDebugGrid) {
                                    previewCharacterId = char.id
                                }
                            },
                            onLongClick = {
                                if (!showDebugGrid) {
                                    onNavigateToFamily(char.id)
                                }
                            },
                            modifier    = Modifier
                                .offset(
                                    x = sw * cx - cardW / 2,
                                    y = sh * cy - cardH / 2,
                                )
                                .size(cardW, cardH),
                        )
                    }

                    // ── 调试网格：高亮框精确画出当前 cx/cy/ovalWidth/ovalHeight ──
                    if (showDebugGrid) {
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = sw * cx - cardW / 2,
                                    y = sh * cy - cardH / 2,
                                )
                                .size(cardW, cardH)
                                .border(2.dp, Color(0xFFFF00FF)),
                        )
                        Text(
                            text     = "cx${"%.3f".format(cx)} cy${"%.3f".format(cy)}",
                            color    = Color(0xFF00FFFF),
                            fontSize = 9.sp,
                            modifier = Modifier.offset(
                                x = sw * cx - cardW / 2,
                                y = sh * cy - cardH / 2 - 14.dp,
                            ),
                        )
                    }
                }
            }
        }

        // ── [1b] 坐标探针拦截层（v20：改用共享组件 CalibrationProbeOverlay）──
        // 和 WorldScreen.kt 同样的道理：这个 Box 在书本卡片渲染*之后*
        // 声明，才能稳稳盖住全部 9 本书，不被 BookCard 自己的
        // combinedClickable 抢走手势。之前这里独立维护一份 v18 版探针
        // （单击、最多4点、没有拖动/撤销/清空），用户在这个页面测试
        // 拖动时才发现"拖了但读数没跟着变"——因为这里的代码压根没有
        // 拖动手势。现在改成调用共享组件，和 WorldScreen.kt 共享同一份
        // 拖动+撤销+清空+可读性列表面板+复制导出的实现。
        if (showDebugGrid) {
            CalibrationProbeOverlay(modifier = Modifier.fillMaxSize())
        }

        // ── [2] 顶部书架 Header 已删除 ────────────────────────

        // ── [5] 任务完成浮层 ──────────────────────────────────
        AnimatedVisibility(
            visible = taskCompletionToast != null,
            enter   = slideInVertically(
                initialOffsetY = { it },
                animationSpec  = tween(320),
            ),
            exit    = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(240),
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
        ) {
            val toast = taskCompletionToast
            if (toast != null) {
                TaskCompletionToast(
                    message     = toast,
                    accentColor = ZaijianTheme.colors.accent,
                    onDismiss   = { viewModel.dismissTaskCompletionToast() },
                    onViewResult = { jobResultId ->
                        viewModel.markResultReadAndDismiss(jobResultId)
                        onNavigateToTaskCenter()
                    },
                )
            }
        }

        // ── [4] 单击预览底部弹窗 ─────────────────────────────
        val previewId = previewCharacterId
        if (previewId != null) {
            val character = uiState.characters.find { it.id == previewId }
            val presence  = uiState.presenceMap[previewId]
            if (character != null && presence != null) {
                CharacterPreviewSheet(
                    character     = character,
                    presence      = presence,
                    onDismiss     = { previewCharacterId = null },
                    onStartChat   = { id ->
                        previewCharacterId = null
                        onNavigateToChat(id)
                    },
                    onViewProfile = { id ->
                        previewCharacterId = null
                        onNavigateToDetail(id)
                    },
                    onViewFamily  = { id ->
                        previewCharacterId = null
                        onNavigateToFamily(id)
                    },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Previews
// ─────────────────────────────────────────────────────────────

@Preview(name = "CharacterScreen · Dark", showBackground = true,
    backgroundColor = 0xFF1A1610.toLong(), widthDp = 390, heightDp = 844)
@Composable
private fun PreviewCharacterScreenDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) { CharacterScreen() }
}

@Preview(name = "CharacterScreen · Light", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewCharacterScreenLight() {
    ZaijianTheme(appTheme = AppTheme.LIGHT) { CharacterScreen() }
}

// ─────────────────────────────────────────────────────────────
//  TaskCompletionToast — 任务完成角色主动汇报浮层（Phase 30 方案二）
// ─────────────────────────────────────────────────────────────

@Composable
private fun TaskCompletionToast(
    message: TaskCompletionMessage,
    accentColor: Color,
    onDismiss: () -> Unit,
    onViewResult: (String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    LaunchedEffect(message.jobResultId) {
        kotlinx.coroutines.delay(8_000L)
        onDismiss()
    }

    // P3-48 修复：.random() 导致重组闪烁，改为 remember 确保同一次展示不变化
    val reportLine = remember(message.characterName, message.jobTitle, message.status) {
        buildReportLine(message.characterName, message.jobTitle, message.status)
    }
    val statusIcon = if (message.status == "success") "✅" else "❌"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(16.dp))
            .background(
                color = colors.bgCard,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Row(
                modifier          = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = message.characterName,
                    style = type.bodyBold,
                    color = accentColor,
                )
                Text(
                    text     = "✕",
                    style    = type.body,
                    color    = colors.textSecondary,
                    modifier = Modifier.clickable { onDismiss() },
                )
            }

            Text(
                text  = reportLine,
                style = type.body,
                color = colors.textPrimary,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .height(1.dp)
                        .width(12.dp)
                        .background(colors.border),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text  = "${message.jobTitle}  $statusIcon",
                    style = type.label,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .height(1.dp)
                        .weight(1f)
                        .background(colors.border),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text  = "稍后查看",
                        style = type.label,
                        color = colors.textSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onViewResult(message.jobResultId) },
                    colors  = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape   = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        text  = "立即查看 →",
                        style = type.label,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

private fun buildReportLine(name: String, jobTitle: String, status: String): String {
    return if (status == "success") {
        listOf(
            "「$jobTitle 整理好了，你看看？」",
            "「$jobTitle 这边搞定了」",
            "「刚把 $jobTitle 做完了」",
            "「$jobTitle 已经处理好了，随时可以看」",
        ).random()
    } else {
        listOf(
            "「$jobTitle 这边遇到了一点问题，你过来看看？」",
            "「$jobTitle 没顺利完成，帮我看一下？」",
        ).random()
    }
}
