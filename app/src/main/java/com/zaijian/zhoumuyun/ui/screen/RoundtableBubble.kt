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
import com.zaijian.zhoumuyun.domain.ContentBlockParser
import com.zaijian.zhoumuyun.ui.component.ContentBlockRenderer
import com.zaijian.zhoumuyun.ui.design.WorldBubble
import com.zaijian.zhoumuyun.ui.design.contentOnFill
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
import com.zaijian.zhoumuyun.ui.design.AppIcons


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
    // v1.48 圆桌 openFile 缺应用内预览分支修复配套：表格卡片"查看完整表格"
    // 回调（与私聊 ChatMessageBubble 的 onOpenTable 同构），默认空实现。
    onOpenTable: (List<String>, List<List<String>>) -> Unit = { _, _ -> },
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
            error              = rememberVectorPainter(AppIcons.Person),
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

            // Fix-PsychMergeIntoBubble：与私聊 ChatMessageBubble 同步，取消
            // PsychCard 独立渲染，psychText 改为合并进下方气泡正文最前段
            // （见下方气泡内 psychText 渲染）——两处视觉/交互要求保持一致，
            // 不维护两份会漂移的拷贝。内心独白折叠卡逻辑不变。
            msg.thinkingText?.takeIf { it.isNotBlank() }?.let { thought ->
                ThoughtCard(
                    thinkingText  = thought,
                    accentColor   = accentColor,
                    characterName = msg.speakerName,
                    maxWidth      = maxW,
                )
            }

            // 气泡改版：纯专属色填充，同步私聊 ChatMessageBubble 的两处变化——
            // ① 容器不再是"accentColor/Gold 描边 + WorldBubble 纸面底"，改传
            //   fillColor = accentColor，气泡本身就是该角色的颜色；
            // ② 正文改用 ContentBlockRenderer（此前圆桌一直是裸 Text，从未解析
            //   过 Markdown/块级结构，和私聊气泡的富文本渲染不是同一套管线——
            //   现在补齐，接同一份 ContentBlockParser → ContentBlockRenderer）。
            // 左侧 4dp 主题色条随之移除：气泡纯色填充后，同色的条在同色的底上
            // 已经不可见，留着没有意义。
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
                fillColor   = accentColor,
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        // 引用气泡（↩ 回应另一个 Bot）——自带纸面底色块，独立于气泡填色，不受影响
                        if (msg.replyTargetName != null) {
                            ReplyQuoteBlock(
                                targetName  = msg.replyTargetName,
                                targetColor = colors.textSecondary,
                            )
                        }

                        // 心理感受合并展示（方案A·克制斜体），与私聊气泡同一逻辑：
                        // 斜体 + 降透明度，颜色基于 contentOnFill() 派生而非固定金色，
                        // 保证任意角色 accentColor 底色下都有稳定对比度。
                        msg.psychText?.takeIf { it.isNotBlank() }?.let { psych ->
                            Text(
                                text  = psych,
                                style = type.body.copy(
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                ),
                                color    = accentColor.contentOnFill().copy(alpha = 0.72f),
                                modifier = Modifier.padding(bottom = Spacing.xs),
                            )
                        }

                        // 正文 + 流式光标：与私聊角色气泡同一套 ContentBlockParser
                        // → ContentBlockRenderer 管线，取代此前的裸 Text。
                        val displayContent = when {
                            msg.isStreaming && msg.content.isEmpty() -> "…"
                            msg.isStreaming -> msg.content + "▌"
                            else            -> msg.content
                        }
                        val contentBlocks = remember(displayContent) {
                            ContentBlockParser.parse(displayContent)
                        }
                        ContentBlockRenderer(
                            blocks    = contentBlocks,
                            textColor = accentColor.contentOnFill(),
                            style     = type.body,
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

            // v67（表格直传 W4）：table_export 产出的表格卡片（与私聊 MessageBubble 同款接入）。
            // >500 行场景从 payload.exportedFileMetaJson 解析 xlsx 文件元信息走 onOpenFile。
            msg.tablePayload?.let { payload ->
                val excelFile = payload.exportedFileMetaJson?.let { metaJson ->
                    com.zaijian.zhoumuyun.ui.viewmodel.parseExportedFilesWithFallback(null, metaJson).firstOrNull()
                }
                com.zaijian.zhoumuyun.ui.screen.chat.TableCard(
                    payload     = payload,
                    accentColor = accentColor,
                    maxWidth    = maxW,
                    onOpenExcel = excelFile?.let { ef -> { onOpenFile(ef) } },
                    // v1.48 圆桌 openFile 缺应用内预览分支修复配套：此前圆桌的
                    // TableCard 没有接 onOpenFullTable，表格气泡点了没反应
                    // （私聊 ChatMessageBubble 早就有这个能力）。现在补齐，
                    // ≤500 行场景（没有 xlsx 附件）也能点开全屏查看完整表格。
                    onOpenFullTable = {
                        onOpenTable(payload.columns, payload.rows)
                    },
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
