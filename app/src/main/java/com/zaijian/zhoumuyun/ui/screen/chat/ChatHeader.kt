package com.zaijian.zhoumuyun.ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.model.ChatMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.RingWidth
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme


import com.zaijian.zhoumuyun.domain.MoodType


// ─────────────────────────────────────────────────────────────
//  顶栏簇：ChatHeader / ModeChip / ChatRelCapsule
//  拆分自 ChatScreen.kt（v87 Phase 2）。
//  ModeChip 和 ChatRelCapsule 原先物理位置在文件末尾，但实际只被
//  ChatHeader 调用——三者是同一簇，随 ChatHeader 一起搬迁。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  ChatHeader — 毛玻璃顶栏
//  规范 §13：返回箭头 / 头像+角色名+关系胶囊行 / 更多图标
//  v1.36：移除时间段活动文案（statusText，如"还没睡"），保留心情+关系状态胶囊。
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatHeader(
    name: String,
    avatarUrl: String,
    breathColor: Color,
    accentColor: Color,
    statusType: StatusType,
    headerBg: Color,
    chatMode: ChatMode = ChatMode.WORK,
    onBack: () -> Unit,
    onAvatarClick: () -> Unit,
    onMoreClick: () -> Unit = {},
    onChatModeChange: (ChatMode) -> Unit = {},
    // 待办10：关系状态胶囊（均可为 null，null = 不展示）
    relStageLabel: String? = null,
    relMood: com.zaijian.zhoumuyun.domain.MoodType? = null,
    relSuppressionHint: String? = null,
    // [聊天圆形头像取景修复] 详情页圆形裁剪参数（CharacterConfig.avatarCropCircle*），
    // 默认 0f/0f/1f 与此前行为一致（居中、Crop 覆盖）。
    avatarCropOffsetX: Float = 0f,
    avatarCropOffsetY: Float = 0f,
    avatarCropScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 待办10：关系状态胶囊行数据（心情/关系阶段/压抑提示）。
    // Fix-ChatHeaderTagsRow：把这几个值的计算提到最外层，因为胶囊行现在
    // 从"角色名下方的窄 Column"里搬出来、独立成顶栏最下面单独一整行，
    // 主 Row 和胶囊行两处都要用到 hasRelInfo。
    val moodLabel = when (relMood) {
        com.zaijian.zhoumuyun.domain.MoodType.EXCITED    -> "✨ 兴奋"
        com.zaijian.zhoumuyun.domain.MoodType.SATISFIED  -> "😊 愉快"
        com.zaijian.zhoumuyun.domain.MoodType.CURIOUS    -> "🤔 好奇"
        com.zaijian.zhoumuyun.domain.MoodType.FOCUSED    -> "🎯 专注"
        com.zaijian.zhoumuyun.domain.MoodType.CALM       -> "🌿 平静"
        com.zaijian.zhoumuyun.domain.MoodType.REFLECTIVE -> "💭 沉思"
        com.zaijian.zhoumuyun.domain.MoodType.TIRED      -> "😴 疲惫"
        com.zaijian.zhoumuyun.domain.MoodType.CONCERNED  -> "😟 担心"
        null                -> null
    }
    val hasRelInfo = relStageLabel != null || moodLabel != null || relSuppressionHint != null

    // 头像前面 back 按钮的默认触控宽度。M3 IconButton 即使图标只有 24dp，
    // 组件本身也会强制保留 48dp 最小触控热区，这部分空间胶囊行缩进时要算进去，
    // 不然胶囊行会跟头像/角色名对不齐。
    val backButtonWidth = 48.dp

    Box(
        modifier = modifier
            .background(headerBg)
            .border(
                width  = 0.5.dp,
                color  = colors.borderSubtle,
                shape  = RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp),
            )
            .statusBarsPadding()
            // Fix-顶栏点击穿透：顶栏此前只是纯展示 Box，不消费触摸事件，
            // 隔着顶栏能点到下方消息列表里滚到顶栏视觉区域下面的气泡。
            // 加空 clickable 拦截，indication=null 避免多余水波纹。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
            ) {},
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.topBarHeight)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 返回箭头
                IconButton(
                    onClick  = onBack,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(backButtonWidth),
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint               = colors.textPrimary,
                        modifier           = Modifier.size(24.dp),
                    )
                }

                Spacer(Modifier.width(Spacing.xs))

                // 头像（点击进入详情页）
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(AvatarSize.chat)
                        .clickable { onAvatarClick() },
                ) {
                    BreathingAvatar(
                        imageUrl    = avatarUrl,
                        breathColor = breathColor,
                        statusType  = statusType,
                        modifier    = Modifier.fillMaxSize(),
                        size        = AvatarSize.chat,
                        ringWidth   = RingWidth.chat,
                        glowRadius  = 4.dp,
                        enableBreath = false,   // 顶栏不呼吸，减少干扰
                        cropOffsetX = avatarCropOffsetX,
                        cropOffsetY = avatarCropOffsetY,
                        cropScale   = avatarCropScale,
                    )
                }

                Spacer(Modifier.width(Spacing.sm))

                // 角色名 —— Fix-ChatHeaderTagsRow：这里现在只放名字。关系胶囊行
                // 已经搬到这个主 Row 外面单独成行，不再挤在头像和右侧模式切换器
                // 之间这条窄缝里，避免两个短胶囊因为可用宽度不够而被迫上下堆叠、
                // 把整个顶栏撑得又高又宽。
                Text(
                    text     = name,
                    style    = type.navTitle,
                    color    = colors.textPrimary,
                    // P2 修复：maxLines 1→2，避免女儿角色自定义长昵称被过度截断。
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // 模式切换（工作 / 陪伴）— Phase 30 方案一
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.borderSubtle)
                        .padding(horizontal = 3.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeChip(
                        label    = "工作",
                        icon     = Icons.Outlined.Work,
                        selected = chatMode == ChatMode.WORK,
                        accent   = accentColor,
                        onClick  = { onChatModeChange(ChatMode.WORK) },
                    )
                    ModeChip(
                        label    = "陪伴",
                        icon     = Icons.Outlined.Favorite,
                        selected = chatMode == ChatMode.COMPANION,
                        accent   = accentColor,
                        onClick  = { onChatModeChange(ChatMode.COMPANION) },
                    )
                }

                // 更多图标
                IconButton(
                    onClick  = onMoreClick,
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.MoreVert,
                        contentDescription = "更多",
                        tint               = colors.textSecondary,
                        modifier           = Modifier.size(24.dp),
                    )
                }
            }

            // 待办10：关系状态胶囊行 —— Fix-ChatHeaderTagsRow：独立成完整一行，
            // 左侧缩进对齐到角色名起始位置（返回按钮 + 头像 + 两个 Spacer 的宽度），
            // 右侧只留主 Row 同样的水平内边距。这一整行拥有顶栏的全部横向空间，
            // 不再被右侧工作/陪伴切换器挤占，"信任""平静"这类短胶囊可以稳定
            // 左右并排展示，不会再被迫各占一行、把顶栏撑高。
            if (hasRelInfo) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start  = Spacing.sm + backButtonWidth + Spacing.xs + AvatarSize.chat + Spacing.sm,
                            end    = Spacing.sm,
                            bottom = Spacing.xs,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (relStageLabel != null) {
                        ChatRelCapsule(text = relStageLabel, color = accentColor)
                    }
                    if (moodLabel != null) {
                        ChatRelCapsule(text = moodLabel, color = colors.textSecondary)
                    }
                    if (relSuppressionHint != null) {
                        ChatRelCapsule(text = relSuppressionHint, color = colors.textDisabled)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ModeChip — 工作 / 陪伴 模式切换按钮（Phase 30 方案一）
//
//  选中态：accentColor 填充 + 白色图标/文字
//  未选中：透明背景 + textSecondary 色
// ─────────────────────────────────────────────────────────────

@Composable
private fun ModeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val bg      = if (selected) accent else Color.Transparent // Transparent 与主题无关，保留不变
    val content = if (selected) Palette.White else colors.textSecondary // accent 在明暗主题下均为 Palette.Gold，白色文字对比度均足够

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            // P3-21 的 minimumInteractiveComponentSize() 强制单个 chip 最小 48x48dp，
            // 两个 chip（工作/陪伴）叠加后整条切换器宽度轻松超过 100dp，占掉顶栏近
            // 一半横向空间，把角色名+关系胶囊挤到只剩窄窄一条（这正是"熟悉""沉思"
            // 两个短胶囊都要各占一行的直接原因）。顶栏场景下切换器是次要操作，不需要
            // 主导航级别的 48dp 热区，改用 32dp 紧凑高度（对齐 M3 小型 Chip 规范），
            // 横向空间立刻多出一大截给角色名那一侧。
            .heightIn(min = 32.dp)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = content,
            modifier           = Modifier.size(14.dp),
        )
        Text(
            text  = label,
            style = type.label,
            color = content,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  ChatRelCapsule — 顶栏关系状态胶囊（待办10）
// ─────────────────────────────────────────────────────────────

@Composable
private fun ChatRelCapsule(text: String, color: Color) {
    val colors = ZaijianTheme.colors
    // P3-40 修复：ChatRelCapsule padding 从 5dp/1dp 增加至 8dp/2dp，避免文字贴边过紧
    //
    // 批次10 10-1修复：Text 此前未设 maxLines/softWrap，在"熟悉"+"沉思"等多个
    // 胶囊同行、且右侧被工作/陪伴切换器挤占横向空间时，容器可用宽度不足以容纳
    // 单行文字，Compose 会按字符自动换行（"沉思"拆成"沉"/"思"上下两行），
    // 胶囊 Box 高度被撑高变成细长竖条，与右侧切换器纵向叠在一起、还会遮挡
    // 下方时间戳。胶囊场景下文字必须保持单行，改为 maxLines=1 强制不换行、
    // 超宽时用省略号截断（理论上不会触发，胶囊文案都很短，仅作兜底）。
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text     = text,
            style    = ZaijianTheme.typography.caption,
            color    = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
