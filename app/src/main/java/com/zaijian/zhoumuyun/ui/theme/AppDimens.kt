package com.zaijian.zhoumuyun.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────
//  Spacing  (4dp grid)
// ─────────────────────────────────────────────────────────────
object Spacing {
    val xs   : Dp = 4.dp
    val sm   : Dp = 8.dp
    val md   : Dp = 16.dp
    val lg   : Dp = 24.dp
    val xl   : Dp = 32.dp
    val xxl  : Dp = 48.dp

    /** 页面左右边距 */
    val screenHorizontal: Dp = 20.dp
    /** 卡片内边距 */
    val cardPadding: Dp = 16.dp
    /** 列表项高（单行） */
    val listItemSingle: Dp = 56.dp
    /** 列表项高（双行） */
    val listItemDouble: Dp = 72.dp
    // v19 修复：Material3 NavigationBar/NavigationBarItem 内部自带最小
    // 触控高度（图标+文字+选中指示器的默认内边距叠加后）远超这里声明的
    // 64dp，导致实际渲染出来的底栏比这个数值高出一大截、把公馆正门
    // 都挡住了——64dp 本身其实是合理数值，问题出在"用 Material3 现成
    // 组件却指望它服从一个更小的 height()"这个组合不成立。已改用自绘
    // Row（AppNavigation.kt 的 BottomNavBar），不再依赖 NavigationBar/
    // NavigationBarItem，实际渲染高度现在严格等于这里写的数值。
    // 顺带把默认值从 64dp 降到 44dp（缩小约31%，接近"至少缩小1/3"的
    // 要求；改成自绘后不再有 Material3 强加的额外内边距，44dp 能放下
    // 20dp 图标 + 11sp 文字并留出基本点击边距——但比 Android 官方建议的
    // 48dp 最小触控热区略矮，属于"视觉更紧凑"和"点击容错"之间的取舍，
    // 如果实测点击容易碰不到再调大）。
    /** 底部导航栏高 */
    val bottomNavHeight: Dp = 44.dp
    /** 顶部 Header 高 */
    val topBarHeight: Dp = 44.dp
}

// ─────────────────────────────────────────────────────────────
//  Corner radius  (5-stop scale)
// ─────────────────────────────────────────────────────────────
object Radius {
    /** 小标签 / 徽章 */
    val xs  : Dp = 6.dp
    /** 输入框 / 小卡片 */
    val sm  : Dp = 12.dp
    /** 书本卡片 / 标准卡片 */
    val md  : Dp = 20.dp
    /** 底部弹窗 / 大卡片 */
    val lg  : Dp = 28.dp
    /** 头像 / 全圆 — use CircleShape in code */
    val circle: Dp = 999.dp
}

// ─────────────────────────────────────────────────────────────
//  Avatar sizes
// ─────────────────────────────────────────────────────────────
object AvatarSize {
    /** 公馆窗口 */
    val mansion  : Dp = 52.dp
    /** 书架书脊 */
    val shelf    : Dp = 44.dp
    /** 聊天气泡旁 */
    val chat     : Dp = 32.dp
    /** 角色详情页 */
    val detail   : Dp = 80.dp
    /** 任务 / 小工具 */
    val small    : Dp = 24.dp
    /** 圆桌 Bot 气泡左侧头像 */
    val bubble       : Dp = 28.dp
    /** 一对一聊天气泡左侧头像（左边距计算用） */
    val bubbleAvatar : Dp = 32.dp
    /** 兼容别名  */
    val sm       : Dp get() = small
}

// ─────────────────────────────────────────────────────────────
//  Status ring widths
// ─────────────────────────────────────────────────────────────
object RingWidth {
    val mansion : Dp = 2.5.dp
    val shelf   : Dp = 2.dp
    val chat    : Dp = 1.5.dp
    val detail  : Dp = 3.dp
}

// ─────────────────────────────────────────────────────────────
//  Status dot size
// ─────────────────────────────────────────────────────────────
object DotSize {
    val normal : Dp = 10.dp
    val small  : Dp = 8.dp
}

// ─────────────────────────────────────────────────────────────
//  Glassmorphism opacities (as float 0–1)
// ─────────────────────────────────────────────────────────────
object GlassOpacity {
    // Fix-顶底栏穿透：topBarLight/topBarDark/bottomNav 原为 0.82~0.88 的"玻璃拟态"
    // 半透明值。副作用：(1) 视觉上能隐约看到下方内容透出；(2) 更严重的是，固定
    // 顶栏/底栏这几个 Box 本身不消费触摸事件，只要视觉上没有完全不透明地"挡住"，
    // 用户就会误以为那一条区域是可以看穿、点穿的——实际上点击穿透和透明度高低
    // 并无因果关系（下面已单独加 clickable 拦截），但用户明确要求顶栏/底栏必须
    // 是完全不透明的实色遮挡，不再做玻璃拟态处理。改为 1f。
    const val topBarLight   = 1f
    const val topBarDark    = 1f
    const val bottomNav     = 1f
    const val windowMask    = 0.85f   // 公馆亮灯遮罩
    const val previewCard   = 0.80f   // 快速预览卡
    const val fullscreenDim = 0.60f   // 全屏弹窗遮罩
    const val mid           = 0.50f   // 中层玻璃透明度
    const val low           = 0.25f   // 低层玻璃透明度
    const val normal        = 0.40f   // 常规玻璃透明度
}

// ─────────────────────────────────────────────────────────────
//  Animation durations (milliseconds)
// ─────────────────────────────────────────────────────────────
object AnimDuration {
    const val instant    = 80    // 按钮按下态
    const val fast       = 150   // Tab 切换、状态变化
    const val bottomSheet = 220  // BottomSheet 出现
    const val pageSwitch = 250   // 页面切换
    const val bookOpen   = 300   // 书本翻开
    const val breath     = 2000  // 呼吸半周期 (full = 4000ms) — UI v2.0 校准：HTML halo/breathe 全周期 4s/3.2s

    // ── UI 升级 v2.0（融合方案 §4.3）：AI 过程件动效令牌 ──
    const val shimmer    = 1600  // 思考进行态微光扫过（1.6s linear）
    const val spin       = 1000  // spinner / 工具运行金圈旋转（1s linear）
    const val typingPulse = 1200 // 打字点阶梯起伏（1.2s）
    const val cursorBlink = 1000 // 流式光标闪烁（1s steps）
    const val fadeUp     = 350   // 过程件入场淡入上移（.35s）
    const val florPulse  = 1400  // ✦ 圆桌发言者脉冲（1.4s）
}

// ─────────────────────────────────────────────────────────────
//  Chat bubble
// ─────────────────────────────────────────────────────────────
object BubbleDimen {
    /** 气泡最大宽度比例 */
    const val maxWidthFraction = 0.72f
}

// ─────────────────────────────────────────────────────────────
//  Elevation / shadow layers (dp values for BoxShadow / Modifier.shadow)
// ─────────────────────────────────────────────────────────────
object Elevation {
    /** 书本卡片、普通卡片 */
    val card     : Dp = 2.dp
    /** 悬浮卡片、快速预览 */
    val elevated : Dp = 4.dp
    /** 全屏弹窗 */
    val dialog   : Dp = 8.dp
}

