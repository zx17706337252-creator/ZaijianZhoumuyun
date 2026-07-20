package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.ui.design.WorldBubble
import com.zaijian.zhoumuyun.ui.screen.chat.PsychCard
import com.zaijian.zhoumuyun.ui.screen.chat.ThoughtCard
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.BubbleDimen
import com.zaijian.zhoumuyun.ui.theme.DotSize
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.theme.appSpring
import com.zaijian.zhoumuyun.ui.theme.presenceGlow
import com.zaijian.zhoumuyun.ui.theme.snapSpring
import com.zaijian.zhoumuyun.ui.viewmodel.BotGenerationStatus
import com.zaijian.zhoumuyun.ui.viewmodel.RoundtableMessage
import com.zaijian.zhoumuyun.ui.viewmodel.RoundtableViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.ScheduleMode
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow


// ─────────────────────────────────────────────────────────────
//  UserBubble — 用户消息气泡
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserBubble(
    msg: RoundtableMessage,
    // 2.1 补齐：与私聊 MessageBubble 同一套长按复制交互。
    onCopyMessage: (String) -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val config = LocalConfiguration.current
    val maxW   = (config.screenWidthDp * 0.72f).dp
    val haptic = LocalHapticFeedback.current

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue   = if (pressed) 0.97f else 1f,
        animationSpec = if (pressed) snapSpring else appSpring,
        label         = "roundtableUserBubblePressScale",
    )

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text     = msg.content,
            style    = type.body,
            color    = Color.White,
            modifier = Modifier
                .widthIn(max = maxW)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(
                    RoundedCornerShape(
                        topStart    = Radius.md,
                        topEnd      = Radius.xs,
                        bottomStart = Radius.md,
                        bottomEnd   = Radius.md,
                    ),
                )
                .background(if (colors.isDark) Palette.UserBubbleDark else Palette.Ink900)   // W12问题5修复：原硬编码 Color(0xFF3A2E20)
                .combinedClickable(
                    interactionSource = interaction,
                    indication        = null,
                    onClick           = {},
                    onLongClick       = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCopyMessage(msg.content)
                    },
                    onLongClickLabel  = "复制这条消息",
                )
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}


// ─────────────────────────────────────────────────────────────
//  BotBubble — Bot 回复气泡（左侧 4dp 主题色条）
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BotBubble(
    msg: RoundtableMessage,
    bot: CharacterConfig?,
    isLast: Boolean,
    // v1.39 圆桌工具调用接入：文件卡片"打开"回调，默认空实现——
    // 未传参的既有调用点行为不变（文件卡片仍会渲染，只是点击不响应）。
    onOpenFile: (com.zaijian.zhoumuyun.ui.viewmodel.ExportedFile) -> Unit = {},
    // 2.1 补齐：与私聊 MessageBubble 同一套长按复制交互。
    onCopyMessage: (String) -> Unit = {},
) {
    val colors      = ZaijianTheme.colors
    val type        = ZaijianTheme.typography
    val config      = LocalConfiguration.current
    val maxW        = (config.screenWidthDp * 0.82f).dp
    val accentColor = bot?.accentColor ?: colors.accent
    val haptic      = LocalHapticFeedback.current

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment     = Alignment.Top,
    ) {
        // 头像
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(bot?.avatarUrl)
                .crossfade(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build(),
            contentDescription = bot?.name,
            modifier           = Modifier
                .padding(top = 2.dp, end = Spacing.sm)
                .size(AvatarSize.bubble)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.25f))
                // P2 修复：追加可见描边，与 BreathingAvatar 的 statusRing + glow 风格统一。
                .border(1.dp, accentColor.copy(alpha = 0.3f), CircleShape),
            error              = rememberVectorPainter(Icons.Outlined.Person),
        )

        Column(
            modifier = Modifier.widthIn(max = maxW),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 角色名 + 专属色点 + 被点名标签
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text  = msg.speakerName,
                    style = type.label,
                    color = accentColor,
                    fontWeight = FontWeight.Medium,
                )
                Box(
                    modifier = Modifier
                        .size(DotSize.small)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.75f)),
                )
                // 被 @ 点名时显示的标签
                if (msg.isNotified) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(accentColor.copy(alpha = 0.15f))
                            .border(0.5.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text  = "被点名",
                            style = type.caption,
                            color = accentColor,
                        )
                    }
                }
            }

            // v1.38 圆桌场景补齐：气泡簇渲染顺序与私聊 MessageBubble 对齐——
            // 内心独白（折叠，最上方）→ 心理感受（常显，台词上方）→ 台词。
            // 圆桌此前从未解析 thinkingText/psychText，字段恒为 null，这两张卡
            // 不会渲染；补齐解析层（RoundtableBotReplyGenerator/RoundtableIdleManager）
            // 后，这里同步补上展示，否则解析出来的数据无处可去。
            msg.thinkingText?.takeIf { it.isNotBlank() }?.let { thought ->
                ThoughtCard(
                    thinkingText  = thought,
                    accentColor   = accentColor,
                    characterName = msg.speakerName,
                    maxWidth      = maxW,
                )
            }
            msg.psychText?.takeIf { it.isNotBlank() }?.let { psych ->
                PsychCard(
                    psychText   = psych,
                    accentColor = accentColor,
                    maxWidth    = maxW,
                )
            }

            // 气泡（W12问题1修复：容器改用 WorldOSComponents.kt 的 WorldBubble，
            // 接入 L0 纸面底 + L1 光斑 + L2 黄铜描边三层视觉规则，取代此前手写的
            // clip+background+border 组合。四角圆角、描边色沿用原值不变（borderColor
            // 显式传入 Gold 系而非 WorldBubble 默认的 accent，保持与此前视觉一致）。
            // 左侧 4dp 主题色条不是 WorldBubble 的能力，在 content 内部用内层 Box
            // 的 drawBehind 补回。）
            // 2.1 补齐：长按复制。流式打字中（isStreaming）内容会持续变化，
            // 此时长按拿到的是当次重组时的 msg.content 快照，不去特殊拦截——
            // 用户此刻长按大概率也不是为了复制半截还没说完的话，交给
            // onCopyMessage 内部按 isNotBlank 判断即可，不在这里加复杂状态判断。
            val botInteraction = remember { MutableInteractionSource() }
            val botPressed by botInteraction.collectIsPressedAsState()
            val botScale by animateFloatAsState(
                targetValue   = if (botPressed) 0.97f else 1f,
                animationSpec = if (botPressed) snapSpring else appSpring,
                label         = "roundtableBotBubblePressScale",
            )
            WorldBubble(
                modifier    = Modifier
                    .graphicsLayer { scaleX = botScale; scaleY = botScale }
                    .combinedClickable(
                        interactionSource = botInteraction,
                        indication        = null,
                        onClick           = {},
                        onLongClick       = {
                            if (msg.content.isNotBlank()) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCopyMessage(msg.content)
                            }
                        },
                        onLongClickLabel  = "复制这条消息",
                    ),
                topStart    = Radius.xs,
                topEnd      = Radius.md,
                bottomStart = Radius.md,
                bottomEnd   = Radius.md,
                borderColor = if (colors.isDark) Palette.Gold.copy(alpha = 0.18f) else Palette.Gold.copy(alpha = 0.28f),
                borderWidth = 0.5.dp,
            ) {
                Box(
                    modifier = Modifier
                        // 左侧 4dp Bot 主题色条
                        .drawBehind {
                            drawLine(
                                color       = accentColor,
                                start       = Offset(0f, 0f),
                                end         = Offset(0f, size.height),
                                strokeWidth = 4.dp.toPx(),
                            )
                        }
                        .padding(start = 12.dp, end = Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        // 引用气泡（↩ 回应另一个 Bot）
                        if (msg.replyTargetName != null) {
                            ReplyQuoteBlock(
                                targetName  = msg.replyTargetName,
                                targetColor = colors.textSecondary,
                            )
                        }

                        // 正文 + 流式光标
                        val displayContent = when {
                            msg.isStreaming && msg.content.isEmpty() -> "…"
                            msg.isStreaming -> msg.content + "▌"
                            else            -> msg.content
                        }
                        Text(
                            text  = displayContent,
                            style = type.body,
                            color = colors.textPrimary,
                        )
                    }
                }
            }

            // v1.39 圆桌工具调用接入：文件导出卡片，与私聊 ChatMessageBubble
            // 同一份 FileExportCard 组件、同样的"气泡下方展示"布局。
            // v66（Agent附件下发方案 v2.0 · 1.7 P3）：exportedFiles 现在能拿到
            // 本轮全部文件（不再只有最后一个），循环渲染多张卡片——外层 Column
            // 已经用 Arrangement.spacedBy(3.dp) 统一管理垂直间距。
            msg.exportedFiles.forEach { ef ->
                com.zaijian.zhoumuyun.ui.screen.chat.FileExportCard(
                    file        = ef,
                    accentColor = accentColor,
                    maxWidth    = maxW,
                    onOpen      = { onOpenFile(ef) },
                )
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────
//  ReplyQuoteBlock — 引用标记（↩ Bot名）
// ─────────────────────────────────────────────────────────────

@Composable
private fun ReplyQuoteBlock(
    targetName: String,
    targetColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.bgElevated)
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("↩", style = type.label, color = targetColor)
        Text(
            text      = targetName,
            style     = type.label,
            color     = targetColor,
            fontStyle = FontStyle.Italic,
        )
    }
}
