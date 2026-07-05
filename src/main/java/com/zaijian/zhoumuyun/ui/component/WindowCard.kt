package com.zaijian.zhoumuyun.ui.component

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ripple
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.FloorEnum
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.model.dotColor
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.RingWidth
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.theme.presenceGlow

// ─────────────────────────────────────────────────────────────
//  WindowCard — 公馆单个角色窗口
//  v11 — 头像与名字标签解耦定位，修复"改多少次 cy 都对不齐"的根因
//
//  设计说明（详见《挖孔头像对齐_设计方案》第 4/7 节）：
//  公馆背景图同样是 FillBounds + fillMaxSize 精确拉伸铺满屏幕，
//  头像统一嵌在每个房间的"拱形窗框开口"里（不是各房间不同的
//  家具/装饰元素）。WorldScreen 测出 9 个拱形开口的实测尺寸后
//  直接传入 archWidth/archHeight，本组件不再用固定 dp
//  （AvatarSize.mansion）或二次估算偏移：
//    [1] 头像：archWidth/archHeight × 0.85 居中嵌入，裁剪成
//        参数化"罗马拱形"（两侧直边 + 顶部半圆/椭圆弧）
//    [2] 点击/长按热区：scale() 整体放大，不影响视觉尺寸
//    [3] 角色名 + 状态文字：拱形正下方，用固定 offset 贴边
//    [4] 状态点 / 家族链角标：右上 / 左上角
//
//  v11 修复的结构性 bug（此前版本头像位置"改很多次都没用"的根因）：
//  旧版把头像 Box 和名字 CharacterNameLabel（+ 状态文字）塞进
//  同一个 Column，靠 Box 的 contentAlignment = Center 把整个
//  Column 一起居中。但 Column 总高度 = 头像高 + 间距 + 名字高
//  (+ 状态文字高)，明显超出 archHeight（头像本身就已经占了
//  archHeight 的 85%），于是"整体居中"实际把头像向上顶出了
//  archWidth/archHeight 框定的拱门开口——文字越高，头像被顶得
//  越多，跟 WorldScreen 传入的 cx/cy 数值本身正确与否无关，
//  单纯调 cy 只能碰运气式地抵消，换名字长度或字号就又偏。
//  现在改为：头像 Box 自己独立 `.align(Alignment.Center)`，
//  只受 archWidth/archHeight 支配，不再受名字/状态文字影响；
//  名字/状态文字用 `.align(Alignment.TopCenter).offset(y = archHeight + 4.dp)`
//  固定贴在拱门开口正下方——这正是书架 BookCard 一直以来的正确
//  写法（BookCard 本身没问题，问题出在 CharacterScreen 调用处
//  多加了 28dp 高度又让头像跟着居中偏移，已一并在 CharacterScreen
//  修复）。两个组件现在用的是同一套、经过验证的定位方式。
//
//  关于「楼梯间」格位（col=2, floor=FIRST）：
//  背景图中间格位是哥特式玻璃大门，尺寸和比例与其他8个窗格不一致。
//  WorldScreen 为该格位单独传入 isStairSlot=true，WindowCard 据此
//  把头像（仅头像，不再连带名字）向上偏移，使视觉重心落在玻璃窗
//  上方的采光区域，与其他格位统一观感——这部分逻辑维持不变。
//
//  顶层（3F/书房·门厅·卧室）房间高度明显比中下两层矮，因此
//  archHeight 三层不可统一传同一个值，调用方（WorldScreen）按楼层
//  传入不同尺寸（v10 已按实测数据修正三层数值）。
// ─────────────────────────────────────────────────────────────

/**
 * v51 修复（P0）：原 ArchShape 用 Path.arcTo() 手写"两侧直边 + 顶部
 * 半圆弧"的拱形轮廓，数学计算反复验证过没有问题（Python 独立模拟画出
 * 的形状完全正确），但实机测试证实——只要用这个 arcTo 拱形做 clip()，
 * AsyncImage 里的图片内容就只能显示拱顶那一小截，矩形部分完全空白；
 * 把同样的 clip 换成一个普通矩形（RoundedCornerShape(0.dp)）后，同一张
 * 图片立刻完整铺满。两边惟一的差异就是"裁剪形状用了 arcTo 画的路径"，
 * 说明问题出在 arcTo 生成的路径在 Android/Skia 实际渲染管线里的某个
 * 尚未查清的细节上（不是路径的几何定义本身有错，而是这条路径被
 * clip() 使用时的渲染表现和数学预期不符——arcTo 是在同一条连续路径里
 * "续接"一段弧线，怀疑是这种续接方式在具体渲染时触发了问题）。
 *
 * 这版彻底不用 arcTo：改成两个独立的、互不续接的子路径——一个矩形
 * （下半部分）+ 一个完整椭圆（用来画出上半部分的拱顶），两个子路径
 * 各自封闭、互不依赖对方的路径状态，走的是 Path 标准的 fill 规则合并，
 * 不涉及 arcTo 那种"接着上一段路径画弧线"的机制，能避开可疑的那部分。
 *
 * 代价：拱顶不再是数学意义上的正圆弧（arcTo 画的是"半个椭圆"，这里
 * addOval 画的是"完整椭圆"，靠矩形部分盖住下半部分，视觉结果等价，
 * 但两者生成路径的方式不同）。
 *
 * @param archHeightRatio 拱顶饱满度，1f = 顶部圆角半径等于宽度一半
 *   （视觉上接近半圆），值越小拱顶越扁。
 */
private fun archShapeFor(archHeightRatio: Float = 0.85f): Shape =
    GenericShape { size, _ ->
        val w = size.width
        val h = size.height
        val archRadiusX = w / 2f
        val archRadiusY = (archRadiusX * archHeightRatio).coerceAtMost(h)
        val straightTopY = (h - archRadiusY).coerceAtLeast(0f)

        // 两个独立子路径，互不续接：矩形负责下半部分，完整椭圆负责
        // 拱顶（椭圆下半部分会被矩形盖住看不见，只有上半部分露出来
        // 形成拱顶效果）。跟旧实现的关键差异：这里没有 arcTo，没有
        // "接着上一条线段继续画弧"的连续路径逻辑。
        addRect(Rect(left = 0f, top = straightTopY, right = w, bottom = h))
        addOval(
            Rect(
                left   = 0f,
                top    = straightTopY - archRadiusY,
                right  = w,
                bottom = straightTopY + archRadiusY,
            )
        )
    }

private val archShape = archShapeFor()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WindowCard(
    character: CharacterConfig,
    presence: PresenceState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** 拱形窗框宽度（实测/估算值，来自 WorldScreen 的列宽 × 屏幕宽） */
    archWidth: Dp,
    /** 拱形窗框高度（实测/估算值，来自 WorldScreen 对应楼层行高 × 屏幕高；
     *  顶层（3F）房间矮，调用方应传入明显更小的值，不能三层统一） */
    archHeight: Dp,
    modifier: Modifier = Modifier,
    // 标记「楼梯间」特殊格位（中间大门格），需要额外偏移
    isStairSlot: Boolean = false,
    // 线二·女儿对话入口：该母亲是否有已注册完成的后代（≥1 代）。
    // true 时左上角显示一个小圆点角标，提示点击会先弹家族列表而非直接进对话。
    // 故意不放数字（几代）——角标只回答"点了会不会弹菜单"这一个问题，
    // 具体几代由弹出的列表自己说明，格子层不需要剧透。
    hasDescendants: Boolean = false,
) {
    val colors = ZaijianTheme.colors
    val isDark  = colors.isDark

    // UI S5 修复：改用 combinedClickable 后，通过 MutableInteractionSource 监听
    // Press 交互来驱动按压缩放动画，替代原来 detectTapGestures 的 onPress 回调。
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue   = if (isPressed) 0.94f else 1f,
        animationSpec = tween(80),
        label         = "window_press",
    )

    // UI S5 修复：为 TalkBack 等无障碍服务提供完整语义描述。
    val statusDesc = when (presence.statusType) {
        StatusType.ACTIVE  -> "在线"
        StatusType.IDLE    -> "空闲"
        StatusType.FOCUSED -> "专注"
        StatusType.OFFLINE -> "离线"
    }
    val a11yDesc = "${character.name}，$statusDesc，点击进入对话，长按预览"

    // P-14 修复：在线/离线拆分路径，OFFLINE 时完全不创建 InfiniteTransition。
    // WindowCard 与 BreathingAvatar 共享同一条 InfiniteTransition，减少动画器数量。
    val isOnline = presence.statusType != StatusType.OFFLINE
    if (isOnline) {
        WindowCardOnline(
            character         = character,
            presence          = presence,
            archWidth         = archWidth,
            archHeight        = archHeight,
            isStairSlot       = isStairSlot,
            hasDescendants    = hasDescendants,
            interactionSource = interactionSource,
            scale             = scale,
            onClick           = onClick,
            onLongClick       = onLongClick,
            a11yDesc          = a11yDesc,
            isDark            = isDark,
            modifier          = modifier,
        )
        return
    }

    // ── OFFLINE 路径：无动画，presenceGlow isActive=false ──────────
    val fillRatio = 0.85f
    val avatarW   = archWidth * fillRatio
    val avatarH   = archHeight * fillRatio
    // v11：只作用于头像本身，不再连带名字/状态文字一起偏移
    val avatarOffsetY = if (isStairSlot) (-14).dp else 0.dp

    Box(
        modifier = modifier
            .combinedClickable(
                interactionSource = interactionSource,
                indication   = ripple(bounded = false, radius = 40.dp),
                onClick      = { onClick() },
                onLongClick  = { onLongClick() },
                onClickLabel = "进入${character.name}的对话",
                role         = Role.Button,
            )
            .semantics { contentDescription = a11yDesc }
            .scale(scale)
            // OFFLINE：isActive=false，presenceGlow 不绘制光晕，breathAlpha 值无意义
            .presenceGlow(
                color       = character.accentColor,
                isActive    = false,
                breathAlpha = 0f,
            ),
    ) {
        // ── [1] 呼吸头像 ─────────────────────────────────────
        // v11：头像独立居中在 archWidth × archHeight 框正中，不再和
        // 名字/状态文字共用一个 Column 做整体居中（那样文字越高，
        // 头像被挤得越往上偏，是过去反复调 cy 都对不齐的根因）。
        // v49_p18 真正修复：之前这里传 `size = maxOf(avatarW, avatarH)`
        // 给 BreathingAvatar，把内部 Box 强制成正方形，被外层
        // `.size(avatarW, avatarH)` 的矩形硬约束居中裁切，跟 AsyncImage
        // 的 Crop 缩放时机没对齐——这才是头像图片显示成一小块、周围
        // 大片留白的真正根因（上一版注释以为已经修好，实际当时只是
        // 换了个包法，maxOf 本身仍在，没有真正解决）。现在改用
        // BreathingAvatar 新增的 width/height 双参数重载，内部按
        // 精确矩形测量，不再强制正方形。
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = avatarOffsetY)
                .size(avatarW, avatarH)
                .clip(archShape),
            contentAlignment = Alignment.Center,
        ) {
            BreathingAvatar(
                imageUrl     = character.avatarUrl,
                breathColor  = character.accentColor,
                statusType   = presence.statusType,
                width        = avatarW,
                height       = avatarH,
                ringWidth    = RingWidth.mansion,
                shape        = archShape,
                glowRadius   = when (presence.statusType) {
                    StatusType.ACTIVE  -> 12.dp
                    StatusType.IDLE    -> 6.dp
                    StatusType.FOCUSED -> 8.dp
                    StatusType.OFFLINE -> 0.dp
                },
                enableBreath = presence.statusType != StatusType.OFFLINE,
                // v46：原图 + 竖长矩形裁剪参数，见 BreathingAvatar 头部说明
                cropOffsetX  = character.avatarCropTallOffsetX,
                cropOffsetY  = character.avatarCropTallOffsetY,
                cropScale    = character.avatarCropTallScale,
            )
        }

        // ── [2]+[3] 角色名 + 状态文字：固定贴在拱门开口正下方 ──
        // v11：用 TopCenter + offset(archHeight + 4.dp) 直接以拱门底边为
        // 基准定位，不参与、也不影响头像的居中计算——这正是 BookCard
        // 一直以来的正确写法，现在两个组件保持一致。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = archHeight + 4.dp)
                .padding(horizontal = 4.dp),
        ) {
            // UI M6 修复：提取为 CharacterNameLabel，与 BookCard 共享实现。
            CharacterNameLabel(
                name         = character.name,
                isDark       = isDark,
                bgAlphaDark  = 0.40f,
                bgAlphaLight = 0.52f,
                hPad         = 8,
            )

            // 状态文字（仅 ACTIVE/IDLE 且已解锁时显示）
            if (presence.statusType != StatusType.OFFLINE && character.isUnlocked) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text      = presence.statusText,
                    fontSize  = 10.sp,
                    color     = if (isDark) Color.White.copy(0.50f)
                                else Palette.WindowCardStatusTextLight.copy(alpha = 0.75f),
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // ── [4] 状态点（右上角）──────────────────────────────
        // UI M6 修复：提取为 StatusDot，与 BookCard 共享实现。
        if (presence.statusType != StatusType.OFFLINE) {
            StatusDot(
                statusType = presence.statusType,
                modifier   = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
            )
        }

        // ── [5] 家族链角标（左上角）──────────────────────────
        // 仅提示"有后代"，不显示具体代数，避免格子层信息过载。
        if (hasDescendants) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp, start = 8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(character.accentColor),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  WindowCardOnline  — 在线状态（ACTIVE / IDLE / FOCUSED）专用渲染
//
//  P-14 修复：
//  此函数在 enableBreath=true 路径下才被调用，在此创建唯一一条
//  InfiniteTransition，同时驱动：
//    1. 外层 presenceGlow 光晕的 breathAlpha（WindowCard 外框光晕）
//    2. BreathingAvatar 内部头像呼吸动画（通过 sharedTransition 参数共享）
//  合并后首页 9 张在线卡片共用 9 条（而非 18 条）InfiniteTransition。
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowCardOnline(
    character: CharacterConfig,
    presence: PresenceState,
    archWidth: Dp,
    archHeight: Dp,
    isStairSlot: Boolean,
    hasDescendants: Boolean,
    interactionSource: MutableInteractionSource,
    scale: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    a11yDesc: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    // 共享 InfiniteTransition：同时驱动外框光晕 + 头像内部呼吸动画
    val infiniteTransition = rememberInfiniteTransition(label = "window_breath_online")
    val breathAlpha by infiniteTransition.animateFloat(
        initialValue  = if (presence.statusType == StatusType.ACTIVE) 0.20f else 0.10f,
        targetValue   = if (presence.statusType == StatusType.ACTIVE) 0.40f else 0.20f,
        animationSpec = infiniteRepeatable(
            animation  = tween(AnimDuration.breath, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "window_glow",
    )

    val fillRatio    = 0.85f
    val avatarW      = archWidth * fillRatio
    val avatarH      = archHeight * fillRatio
    // v11：只作用于头像本身，不再连带名字/状态文字一起偏移（原因同 WindowCard 离线路径）
    val avatarOffsetY = if (isStairSlot) (-14).dp else 0.dp

    Box(
        modifier = modifier
            .combinedClickable(
                interactionSource = interactionSource,
                indication   = ripple(bounded = false, radius = 40.dp),
                onClick      = { onClick() },
                onLongClick  = { onLongClick() },
                onClickLabel = "进入${character.name}的对话",
                role         = Role.Button,
            )
            .semantics { contentDescription = a11yDesc }
            .scale(scale)
            .presenceGlow(
                color       = character.accentColor,
                isActive    = true,
                breathAlpha = breathAlpha,
            ),
    ) {
        // v11：头像独立居中在 archWidth × archHeight 框正中（详见文件头 v11 说明）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = avatarOffsetY)
                .size(avatarW, avatarH)
                .clip(archShape),
            contentAlignment = Alignment.Center,
        ) {
            // sharedTransition 传入，BreathingAvatar 内部直接复用，不新建动画器
            // v49_p18 修复：同离线路径，改传 width/height 精确矩形测量，
            // 不再用 maxOf(avatarW, avatarH) 强制正方形（真正根因见离线路径注释）。
            BreathingAvatar(
                imageUrl          = character.avatarUrl,
                breathColor       = character.accentColor,
                statusType        = presence.statusType,
                width             = avatarW,
                height            = avatarH,
                ringWidth         = RingWidth.mansion,
                shape             = archShape,
                glowRadius        = when (presence.statusType) {
                    StatusType.ACTIVE  -> 12.dp
                    StatusType.IDLE    -> 6.dp
                    StatusType.FOCUSED -> 8.dp
                    StatusType.OFFLINE -> 0.dp
                },
                enableBreath      = true,
                sharedTransition  = infiniteTransition,
                // v46：原图 + 竖长矩形裁剪参数，见 BreathingAvatar 头部说明
                cropOffsetX       = character.avatarCropTallOffsetX,
                cropOffsetY       = character.avatarCropTallOffsetY,
                cropScale         = character.avatarCropTallScale,
            )
        }

        // 角色名 + 状态文字：固定贴在拱门开口正下方，不影响头像居中
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = archHeight + 4.dp)
                .padding(horizontal = 4.dp),
        ) {
            CharacterNameLabel(
                name         = character.name,
                isDark       = isDark,
                bgAlphaDark  = 0.40f,
                bgAlphaLight = 0.52f,
                hPad         = 8,
            )

            if (character.isUnlocked) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text      = presence.statusText,
                    fontSize  = 10.sp,
                    color     = if (isDark) Color.White.copy(0.50f)
                                else Palette.WindowCardStatusTextLight.copy(alpha = 0.75f),
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        StatusDot(
            statusType = presence.statusType,
            modifier   = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        )

        if (hasDescendants) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp, start = 8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(character.accentColor),
            )
        }
    }
}
