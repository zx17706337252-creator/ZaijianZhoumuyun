package com.zaijian.zhoumuyun.ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.zaijian.zhoumuyun.data.model.ChatMode
import com.zaijian.zhoumuyun.domain.MoodType
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme


// ─────────────────────────────────────────────────────────────
//  顶栏簇：ChatHeader / ModeChip
//  拆分自 ChatScreen.kt（v87 Phase 2）。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  ChatHeader — 单行紧凑顶栏（顶栏压缩v1 · 方案A：单行合一）
//  规范 §13：返回箭头 / 角色名+关系信息合并行 / 模式切换 / 更多图标
//  v1.36：移除时间段活动文案（statusText），保留心情+关系状态。
//  v152：去掉头像，空出的横向空间并入角色名，原头像点击入口改到角色名上。
//
//  顶栏压缩v1（本次）：v152 之后顶栏内容区（不含系统状态栏）实测仍有约
//  84dp——主 Row 因两个 IconButton 各自维持系统默认 48dp 触控热区被撑到
//  56dp，关系胶囊（FlowRow）又单独占一整行约 28dp。三个方案对比后选定
//  "单行合一"：
//    1) 关系信息（阶段/心情/压抑提示）不再各占一个独立胶囊、单独成行，
//       合并成一段用"·"分隔、按语义分色的文字，跟在角色名后面同一行，
//       maxLines=1 + ellipsis 兜底，超宽自动截断。
//    2) 返回/更多按钮触控区从系统默认 48dp 显式收到 32dp——写法参照
//       FileVaultScreen.kt / ProjectDetailScreen.kt 已验证过的
//       Modifier.size(32.dp).minimumInteractiveComponentSize() 组合。
//    3) 工作/陪伴切换器只保留图标、去掉文字标签，整体再缩小一档。
//  压缩后内容区约 42dp，较此前减少约 50%。
//  取舍：关系信息挤在同一行里，稳定展示上限约1~2条短文案；如果后续发现
//  压抑提示这类较长文案经常被截断，再考虑把关系信息整体挪到消息区悬浮条
//  （对比方案里的方案C），本次先按单行合一上线看实际效果。
// ─────────────────────────────────────────────────────────────

@Composable
internal fun ChatHeader(
    name: String,
    accentColor: Color,
    headerBg: Color,
    chatMode: ChatMode = ChatMode.WORK,
    onBack: () -> Unit,
    onProfileClick: () -> Unit,
    onMoreClick: () -> Unit = {},
    onChatModeChange: (ChatMode) -> Unit = {},
    // 待办10：关系状态（均可为 null，null = 不展示）
    relStageLabel: String? = null,
    relMood: MoodType? = null,
    relSuppressionHint: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val moodLabel = when (relMood) {
        MoodType.EXCITED    -> "✨兴奋"
        MoodType.SATISFIED  -> "😊愉快"
        MoodType.CURIOUS    -> "🤔好奇"
        MoodType.FOCUSED    -> "🎯专注"
        MoodType.CALM       -> "🌿平静"
        MoodType.REFLECTIVE -> "💭沉思"
        MoodType.TIRED      -> "😴疲惫"
        MoodType.CONCERNED  -> "😟担心"
        null                -> null
    }

    // 顶栏压缩v1：三段关系信息不再各自是独立胶囊，合并成一段按语义分色的
    // AnnotatedString，跟在角色名后面同一行展示。分隔符固定用 textDisabled
    // 弱化，三段本身各自保留原来的语义色（阶段=accentColor / 心情=
    // textSecondary / 压抑提示=textDisabled），信息层级基本不丢。
    val segments = listOfNotNull(
        relStageLabel?.let { it to accentColor },
        moodLabel?.let { it to colors.textSecondary },
        relSuppressionHint?.let { it to colors.textDisabled },
    )
    val relInfoText = if (segments.isEmpty()) null else buildAnnotatedString {
        segments.forEachIndexed { index, (text, color) ->
            if (index > 0) {
                withStyle(SpanStyle(color = colors.textDisabled)) { append(" · ") }
            }
            withStyle(SpanStyle(color = color)) { append(text) }
        }
    }

    // 紧凑触控尺寸：系统 IconButton 默认强制 48dp 最小触控热区，单行顶栏
    // 放不下两个 48dp 按钮还要留位置给名字/关系信息/模式切换器。
    val compactIconSize = 32.dp

    Box(
        modifier = modifier
            .background(headerBg)
            .border(
                width  = 0.5.dp,
                color  = colors.borderSubtle,
                shape  = RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp),
            )
            // Fix-顶栏点击穿透（复查修复）：clickable 拦截必须放在 statusBarsPadding()
            // 之前。Compose 里链式修饰符的可点击命中区域，按它在链上所处位置时的尺寸
            // 计算——原写法是 .statusBarsPadding().clickable(){}，statusBarsPadding()
            // 先把状态栏高度那一截让给内部 Row，clickable 排在后面，命中区域只覆盖了
            // 状态栏以下的部分；但 background/border 是更早声明的，会画满整个外层尺寸
            // （含状态栏那一条，这本身是对的——顶栏底色需要延伸到状态栏后面）。两者一
            // 结合，状态栏那一条视觉上是顶栏的一部分，触摸却会穿透到下方消息列表。
            // 调整为 .clickable(){}.statusBarsPadding()：clickable 命中区域改为覆盖
            // 链上此刻的完整尺寸（含状态栏），statusBarsPadding() 挪到后面依然正常
            // 生效——它只影响内部 Row 的位置/可用空间，不影响 clickable 已经拿到的
            // 命中区域大小。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
            ) {}
            .statusBarsPadding(),
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .padding(horizontal = Spacing.sm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回箭头
            IconButton(
                onClick  = onBack,
                modifier = Modifier
                    .size(compactIconSize)
                    .minimumInteractiveComponentSize(),
            ) {
                Icon(
                    imageVector        = AppIcons.ArrowBack,
                    contentDescription = "返回",
                    tint               = colors.textPrimary,
                    modifier           = Modifier.size(18.dp),
                )
            }

            Spacer(Modifier.width(Spacing.xs))

            // 角色名 + 关系信息合并行（点击进入详情页，承接原头像的点击入口）
            Row(
                modifier              = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null,
                    ) { onProfileClick() },
                verticalAlignment     = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text     = name,
                    style    = type.navTitle.copy(fontSize = 15.sp, lineHeight = 18.sp),
                    color    = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (relInfoText != null) {
                    Text(
                        text     = relInfoText,
                        style    = type.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }

            Spacer(Modifier.width(Spacing.xs))

            // 模式切换（工作 / 陪伴）— 顶栏压缩v1：只留图标，去掉文字标签
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.borderSubtle)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeChip(
                    icon               = AppIcons.Work,
                    contentDescription = "工作模式",
                    selected           = chatMode == ChatMode.WORK,
                    accent             = accentColor,
                    onClick            = { onChatModeChange(ChatMode.WORK) },
                )
                ModeChip(
                    icon               = AppIcons.Favorite,
                    contentDescription = "陪伴模式",
                    selected           = chatMode == ChatMode.COMPANION,
                    accent             = accentColor,
                    onClick            = { onChatModeChange(ChatMode.COMPANION) },
                )
            }

            // 更多图标
            IconButton(
                onClick  = onMoreClick,
                modifier = Modifier
                    .size(compactIconSize)
                    .minimumInteractiveComponentSize(),
            ) {
                Icon(
                    imageVector        = AppIcons.MoreVert,
                    contentDescription = "更多",
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ModeChip — 工作 / 陪伴 模式切换按钮
//  顶栏压缩v1：改为纯图标（原来带文字标签），靠 contentDescription
//  保留语义、selected 态的填充色沿用原方案区分选中/未选中。
// ─────────────────────────────────────────────────────────────

@Composable
private fun ModeChip(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors

    val bg      = if (selected) accent else Color.Transparent // Transparent 与主题无关，保留不变
    val content = if (selected) Palette.White else colors.textSecondary // accent 在明暗主题下均为 Palette.Gold，白色图标对比度均足够

    Box(
        modifier          = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(6.dp),
        contentAlignment  = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDescription,
            tint               = content,
            modifier           = Modifier.size(14.dp),
        )
    }
}
