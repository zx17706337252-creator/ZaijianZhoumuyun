package com.zaijian.zhoumuyun.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.CyclePhase
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.model.dotColor
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.RingWidth
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.theme.appSpring
import com.zaijian.zhoumuyun.ui.theme.presenceGlow
import com.zaijian.zhoumuyun.ui.theme.snapSpring

// ─────────────────────────────────────────────────────────────
//  BookCard  — 书架里的单本书（3 × 3 = 9 本）
//  v5 — 适配背景图书本椭圆框
//
//  设计说明：
//  背景图里每本书都有一个椭圆形浮雕框（书封中央）。
//  本版完全去掉半透明覆盖色，改为透明背景，
//  让书架插图的书皮颜色完整透出，仅在画面上叠加：
//    [1] 椭圆形头像（精确对齐背景图椭圆框中心，上移让出名字）
//    [2] 状态点（椭圆右上角）
//    [3] 角色名（底部居中，小号字，带浅色背景条保证可读性）
//
//  椭圆位置标定（FillBounds 坐标系，相对单本书区域）：
//    - 椭圆框中心在书本高度 42% 处（从顶算）
//    - 椭圆宽约书本宽度的 62%，高约书本高度的 38%
//    - 头像直径 = min(椭圆宽, 椭圆高) * 0.88，保留描边余量
//
//  交互：
//    单击 → onClick（弹出角色预览 BottomSheet）
//    长按 → onLongClick（进入家族页）
//    按下 → scale 0.96 弹性回弹
// ─────────────────────────────────────────────────────────────

/** 竖向椭圆 Shape，宽高比由外部 size 决定 */
private class OvalShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path().apply {
            addOval(Rect(0f, 0f, size.width, size.height))
        }
        return Outline.Generic(path)
    }
}

private val ovalShape = OvalShape()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    character: CharacterConfig,
    presence: PresenceState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** 椭圆框宽度（实测值，来自 CharacterScreen 的 ovalWFrac × 屏幕宽） */
    ovalWidth: Dp = 90.dp,
    /** 椭圆框高度（实测值，来自 CharacterScreen 的 ovalHFrac × 屏幕高） */
    ovalHeight: Dp = 85.dp,
    modifier: Modifier = Modifier,
    /** 周期阶段指示点；null = 不显示（7-9 号角色如不启用周期 UI 时传 null） */
    cyclePhase: CyclePhase? = null,
) {
    val isLocked = !character.isUnlocked
    val isDark   = ZaijianTheme.colors.isDark

    // 头像填充比：椭圆框留一点描边余量，不顶满
    val fillRatio = 0.90f
    val avatarW   = ovalWidth * fillRatio
    val avatarH   = ovalHeight * fillRatio

    // UI S5 修复：改用 combinedClickable + MutableInteractionSource 实现按压动画，
    // 替代原来 detectTapGestures 的 onPress 回调 + 透明热区 Box 方案。
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue   = if (isPressed) 0.96f else 1f,
        animationSpec = if (isPressed) snapSpring else appSpring,
        label         = "book_press",
    )

    // 外部 modifier 携带 CharacterScreen 算好的 .offset(...).size(ovalWidth, ovalHeight)，
    // 这个 Box 本身就是椭圆框——不再需要二次测量、二次估算偏移。

    val statusDesc = when (presence.statusType) {
        StatusType.ACTIVE  -> "在线"
        StatusType.IDLE    -> "空闲"
        StatusType.FOCUSED -> "专注"
        StatusType.OFFLINE -> "离线"
    }
    // BUG-6 修复：CharacterScreen 实际行为是单击=预览弹窗、长按=家族页，
    // 与此处原注释「单击→详情，长按→预览」相反。
    // 以 CharacterScreen 的实际绑定为准，同步修正语义描述和 a11y 播报。
    val a11yDesc = if (isLocked)
        "${character.name}，未解锁"
    else
        "${character.name}，$statusDesc，点击预览，长按查看家族"

    Box(
        modifier = modifier
            .combinedClickable(
                interactionSource = interactionSource,
                indication   = ripple(bounded = false, radius = 32.dp),
                enabled      = true,
                onClick      = { if (!isLocked) onClick() },
                onLongClick  = { if (!isLocked) onLongClick() },
                onClickLabel = if (isLocked) null else "预览${character.name}",
                role         = Role.Button,
            )
            .semantics { contentDescription = a11yDesc }
            .scale(scale)
            .presenceGlow(
                color       = character.accentColor,
                isActive    = presence.statusType == StatusType.ACTIVE,
                breathAlpha = if (presence.statusType == StatusType.ACTIVE) 0.28f else 0f,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isLocked) {
            // ── 未解锁：椭圆框内显示问号 ──────────────────────────
            // Box 本身就是椭圆框，默认 Center 对齐即可，不再需要二次偏移。
            Text(
                text       = "?",
                color      = Color.White.copy(alpha = 0.35f),
                fontSize   = 22.sp,
                fontWeight = FontWeight.Light,
            )

            // 角色名（椭圆框下方，不在椭圆内）
            // v46 修复：锁定态这里之前漏了解锁态已经打过的
            // wrapContentWidth(unbounded = true) 补丁（见下方解锁态注释），
            // 窄格角色（莫婉凝）锁定时名字同样会被父级宽度约束截断成
            // "莫…"，补上跟解锁态保持一致。
            Text(
                text       = character.name,
                color      = Color.White.copy(alpha = 0.25f),
                fontSize   = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                textAlign  = TextAlign.Center,
                modifier   = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = ovalHeight + 4.dp)
                    .wrapContentWidth(unbounded = true)
                    .padding(horizontal = 4.dp),
            )

        } else {
            // ── 已解锁：头像嵌入背景图椭圆框 ────────────────────
            // v49_p18 真正修复：之前这里传 `size = maxOf(avatarW, avatarH)`，
            // 把 BreathingAvatar 内部 Box 强制成正方形，被外层
            // `.size(avatarW, avatarH)` 的矩形硬约束居中裁切，跟 AsyncImage
            // 的 Crop 缩放时机没对齐，是头像图片显示成一小块、周围大片
            // 留白的真正根因（上一版注释以为已经修好，实际 maxOf 本身
            // 仍在，没有真正解决）。现在改用 BreathingAvatar 新增的
            // width/height 双参数重载，内部按精确矩形测量，
            // clip(ovalShape) 按真实 avatarW × avatarH 比例裁出竖向椭圆。
            Box(
                modifier = Modifier
                    .size(avatarW, avatarH)
                    .clip(ovalShape),
                contentAlignment = Alignment.Center,
            ) {
                BreathingAvatar(
                    imageUrl     = character.avatarUrl,
                    breathColor  = character.accentColor,
                    statusType   = presence.statusType,
                    width        = avatarW,
                    height       = avatarH,
                    ringWidth    = RingWidth.shelf,
                    shape        = ovalShape,
                    enableBreath = presence.statusType == StatusType.ACTIVE,
                    glowRadius   = when (presence.statusType) {
                        StatusType.ACTIVE  -> 8.dp
                        StatusType.IDLE    -> 4.dp
                        else               -> 0.dp
                    },
                    // v46：书架椭圆和公馆拱形共用同一套竖长矩形裁剪参数
                    // （见 CharacterConfig / BreathingAvatar 头部说明）
                    cropOffsetX  = character.avatarCropTallOffsetX,
                    cropOffsetY  = character.avatarCropTallOffsetY,
                    cropScale    = character.avatarCropTallScale,
                )
            }

            // ── 角色名（椭圆框下方，带半透明背景条保证可读性） ───
            // 名字不再挤占椭圆内空间——TopCenter 对齐椭圆顶部，
            // 再向下偏移整个椭圆高度 + 小间隙，正好落在椭圆框正下方。
            // UI M5 修复：提取为 CharacterNameLabel，与 WindowCard 共享实现。
            // 布局修复：书架卡片本身很窄（约屏宽 1/9），3 字以上中文名
            // （宥熙/索菲娅/顾澜/明媚/莫婉凝/江凡）在原宽度约束下被
            // maxLines=1 + Ellipsis 截断成"莫…"。父 Box 未 clip，
            // 用 wrapContentWidth(unbounded = true) 打破父级宽度约束，
            // 让文字按自身实际宽度测量后居中溢出显示，不再截断。
            CharacterNameLabel(
                name         = character.name,
                isDark       = isDark,
                bgAlphaDark  = 0.32f,
                bgAlphaLight = 0.38f,
                hPad         = 6,
                modifier     = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = ovalHeight + 4.dp)
                    .wrapContentWidth(unbounded = true),
            )

            // ── 状态点（椭圆框右上侧） ────────────────────────────
            // UI M5 修复：提取为 StatusDot，与 WindowCard 共享实现。
            if (presence.statusType != StatusType.OFFLINE) {
                StatusDot(
                    statusType = presence.statusType,
                    modifier   = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 2.dp),
                )
            }

            // ── 周期指示点（椭圆框左上侧，与在线状态点对称） ─────
            if (cyclePhase != null && cyclePhase != CyclePhase.PREGNANT) {
                val cycleColor = when (cyclePhase) {
                    CyclePhase.MENSTRUAL -> Palette.SemanticDanger   // 🔴 经期
                    CyclePhase.FERTILE   -> Palette.SemanticReminder // 🟡 排卵期
                    CyclePhase.SAFE      -> Palette.SemanticSafe     // 🟢 安全期
                    else                 -> Color.Transparent
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 4.dp, start = 2.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(cycleColor),
                )
            }
            if (cyclePhase == CyclePhase.PREGNANT) {
                Text(
                    text = "👶",
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 2.dp, start = 1.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Previews
// ─────────────────────────────────────────────────────────────

@Preview(name = "BookCard · Active · Dark", showBackground = true,
    backgroundColor = 0xFF12131A.toLong(), widthDp = 110, heightDp = 160)
@Composable
private fun PreviewBookCardActiveDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) {
        BookCard(
            character   = DefaultCharacters[0],
            presence    = DefaultPresenceStates[0],
            onClick     = {},
            onLongClick = {},
            ovalWidth   = 90.dp,
            ovalHeight  = 85.dp,
        )
    }
}

@Preview(name = "BookCard · Idle · Light", showBackground = true, widthDp = 110, heightDp = 160)
@Composable
private fun PreviewBookCardIdleLight() {
    ZaijianTheme(appTheme = AppTheme.LIGHT) {
        BookCard(
            character   = DefaultCharacters[1],
            presence    = DefaultPresenceStates[1],
            onClick     = {},
            onLongClick = {},
            ovalWidth   = 90.dp,
            ovalHeight  = 85.dp,
        )
    }
}

@Preview(name = "BookCard · Locked · Dark", showBackground = true,
    backgroundColor = 0xFF12131A.toLong(), widthDp = 110, heightDp = 160)
@Composable
private fun PreviewBookCardLocked() {
    ZaijianTheme(appTheme = AppTheme.DARK) {
        BookCard(
            character   = DefaultCharacters[8],
            presence    = DefaultPresenceStates[8],
            onClick     = {},
            onLongClick = {},
            ovalWidth   = 90.dp,
            ovalHeight  = 85.dp,
        )
    }
}
