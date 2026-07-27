package com.zaijian.zhoumuyun.ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.zaijian.zhoumuyun.data.agent.TablePayload
import com.zaijian.zhoumuyun.domain.ContentBlockParser
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.MatBadge
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.design.WorldBubble
import com.zaijian.zhoumuyun.ui.design.contentOnFill
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import com.zaijian.zhoumuyun.ui.theme.appSpring
import com.zaijian.zhoumuyun.ui.theme.snapSpring


import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.component.BubbleActionMenu
import com.zaijian.zhoumuyun.ui.component.ContentBlockRenderer
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.BubbleDimen
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.ChatViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.parseExportedFilesWithFallback



// ─────────────────────────────────────────────────────────────
//  消息气泡簇：MessageBubble / FileExportCard / StreamingMessageItem / ToolHintRow
//  拆分自 ChatScreen.kt（v87 Phase 2）。
//  StreamingMessageItem 内部复用 MessageBubble；FileExportCard 被
//  MessageBubble 在展示 exportedFiles（v66 起为多文件列表）时循环调用——
//  四者是同一簇，物理上放同一文件。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  MessageBubble — 单条消息
//  规范 §13：
//    角色气泡 圆角 20/20/20/4dp，左侧 32dp 头像
//    用户气泡 圆角 20/4/20/20dp，右对齐，accentColor 填充
//    最大宽度 屏幕宽 × 0.72
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    message: com.zaijian.zhoumuyun.ui.viewmodel.ChatMessage,
    accentColor: Color,
    avatarUrl: String,
    characterName: String,
    onOpenFile: (com.zaijian.zhoumuyun.ui.viewmodel.ExportedFile) -> Unit = {},
    // v1.48：气泡点击全屏查看文本
    onOpenFullText: (String, Boolean) -> Unit = { _, _ -> },
    // v1.48：表格点击全屏查看
    onOpenTable: (List<String>, List<List<String>>) -> Unit = { _, _ -> },
    // 2.1 对话内容复制：长按气泡触发，回调把消息文字交给调用方（ChatScreen）
    // 写入剪贴板并弹 Snackbar。只挂在有文字内容的气泡上（见下方 content.isNotBlank 判断），
    // 纯文件卡消息不触发。
    onCopyMessage: (String) -> Unit = {},
    // [聊天圆形头像取景修复] 详情页圆形裁剪参数（CharacterConfig.avatarCropCircle*）。
    // 默认 0f/0f/1f 与此前行为一致（居中、ContentScale.Crop 覆盖）。
    avatarCropOffsetX: Float = 0f,
    avatarCropOffsetY: Float = 0f,
    avatarCropScale: Float = 1f,
    // 文档发送方式（ChatSettingsSheet 可切换，默认 true）：
    // true  —— 文件卡片/表格卡片嵌进文字气泡内部，跟文字合并成一个气泡
    // false —— 保留旧版效果，各自独立成一张气泡/卡片（本参数默认值与此保持
    //          一致是为了兼容 StreamingMessageItem 等未显式传参的旧调用点——
    //          流式打字机气泡本来就不带文件，传什么值都不影响观感）。
    attachFilesTogether: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val colors         = ZaijianTheme.colors
    val type           = ZaijianTheme.typography
    val screenWidth    = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * BubbleDimen.maxWidthFraction

    if (message.role == "user" || message.isUserFileImportNotice) {
        // ── 用户气泡（右对齐）──────────────────────────────
        // 修复：原用 accentColor（对方角色专属色）填充，导致用户气泡显示角色色、
        // 角色气泡反而显示默认色。改为用主题默认 accent 色（非任何角色专属色），
        // 角色专属色只用于角色气泡的边框（见下方 else 分支 borderColor）。
        val userBubbleColor = ZaijianTheme.colors.accent
        Row(
            modifier          = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (message.isUserFileImportNotice) {
                // Fix-FileImportCard：这条是"用户导入了一个文件"通知（role="system"，
                // 存储格式不变，见 ChatMessage.isUserFileImportNotice 注释），不当纯文本
                // 显示——内部路径不该直接糊给用户看，改成 FileExportCard 同款文件卡，
                // 右对齐挂在用户这一侧，点击复用 onOpenFile（应用内预览优先，
                // 不支持的类型走系统应用打开，逻辑与角色发的文件完全一致）。
                Column(
                    horizontalAlignment   = Alignment.End,
                    verticalArrangement   = Arrangement.spacedBy(Spacing.xs),
                ) {
                    message.userImportedFiles.forEach { file ->
                        FileExportCard(
                            file        = file,
                            accentColor = userBubbleColor,
                            maxWidth    = maxBubbleWidth,
                            onOpen      = { onOpenFile(file) },
                        )
                    }
                }
            } else {
                // 2.1：长按复制。interactionSource 驱动按压缩放（抄 BookCard.kt 的
                // combinedClickable + collectIsPressedAsState 手感），indication 关掉
                // 默认 ripple——缩放本身已经是按压反馈，两层叠加会显得脏。
                val userInteraction = remember { MutableInteractionSource() }
                val userPressed by userInteraction.collectIsPressedAsState()
                val userScale by animateFloatAsState(
                    targetValue   = if (userPressed) 0.97f else 1f,
                    animationSpec = if (userPressed) snapSpring else appSpring,
                    label         = "userBubblePressScale",
                )
                Box(
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .graphicsLayer { scaleX = userScale; scaleY = userScale }
                        .clip(
                            RoundedCornerShape(
                                topStart    = Radius.md,
                                topEnd      = Radius.md,
                                bottomStart = Radius.md,
                                bottomEnd   = Radius.xs,
                            )
                        )
                        // 细化方案第五节：用户气泡材质修复
                        // 保留纯色识别（不引入中性渐变），给纯色本身加材质层：
                        // 深浅渐变（顶部提亮 12%）+ 顶部极细高光线，不加投影
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    lerp(userBubbleColor, Color.White, 0.12f),
                                    userBubbleColor,
                                )
                            )
                        )
                        .drawBehind {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0f),
                                        Color.White.copy(alpha = if (colors.isDark) 0.15f else 0.25f),
                                        Color.White.copy(alpha = 0f),
                                    ),
                                ),
                                topLeft = Offset.Zero,
                                size = Size(size.width, 1.dp.toPx()),
                            )
                        }
                        .combinedClickable(
                            interactionSource = userInteraction,
                            indication        = null,
                            onClick           = {},
                            onLongClick       = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCopyMessage(message.content)
                            },
                            onLongClickLabel  = "复制这条消息",
                        )
                        // 用户反馈：聊天气泡文字上下贴边过紧，纵向 padding 从
                        // Spacing.sm(8dp) 加大到 12dp，横向维持 Spacing.md(16dp) 不变。
                        .padding(horizontal = Spacing.md, vertical = 12.dp),
                ) {
                    Text(
                        text     = message.content,
                        style    = type.body,
                        color    = Color.White,
                        // P2-6 修复（重做）：maxLines 是 overflow 生效的前提，
                        // 无行数上限时 Ellipsis 不会触发。设高限值兼容长文本，
                        // 超长无空格字符串（如 URL）在足够行数内自然换行。
                        softWrap = true,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    } else {
        // ── 角色气泡（左对齐，带头像）───────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            // 头像（32dp）
            // [聊天圆形头像取景修复] 此前直接用 AsyncImage + ContentScale.Crop 渲染，
            // 完全绕开了详情页调好的圆形裁剪参数（avatarCropCircle*）——图片总是居中
            // 铺满裁剪，用户在详情页调整过的取景范围在聊天气泡里不生效。改用
            // BreathingAvatar（enableBreath=false 关闭呼吸动画/光晕，
            // showStatusIndicator=false 关闭状态环/状态点/离线灰遮罩，视觉效果与
            // 此前的纯头像+描边一致），并传入 cropOffsetX/Y/Scale，让消息气泡头像
            // 与详情页/顶栏保持同一套取景。statusType 传 IDLE 只是占位（不会触发
            // OFFLINE 灰遮罩），showStatusIndicator=false 已经确保它完全不参与绘制。
            BreathingAvatar(
                imageUrl     = avatarUrl,
                breathColor  = accentColor,
                statusType   = com.zaijian.zhoumuyun.data.model.StatusType.IDLE,
                modifier     = Modifier
                    .size(AvatarSize.chat)
                    .border(1.dp, accentColor.copy(alpha = 0.3f), CircleShape),
                size         = AvatarSize.chat,
                enableBreath = false,
                cropOffsetX  = avatarCropOffsetX,
                cropOffsetY  = avatarCropOffsetY,
                cropScale    = avatarCropScale,
                showStatusIndicator = false,
            )

            Spacer(Modifier.width(Spacing.sm))

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                // Fix-PsychMergeIntoBubble（3分部→2分部，方案A·克制斜体）：
                // 心理感受（psychText）不再独立成 PsychCard 卡片，改为拼进台词气泡
                // 正文最前段，跟台词连续排版——只保留两层：① 内心独白折叠卡（戏外，
                // 默认收起）② 台词气泡（戏内心理+动作+台词合并为一段连续正文）。
                // 心理描写用克制斜体（暖金色调 + Italic）与台词区分，不再用独立的
                // 框/条把它和台词切开。PsychCard 组件本身保留在文件里未删除
                // （RoundtableBubble.kt 若后续需要可复用），只是这里不再调用。

                // 1. 内心独白（原想法卡，Fix-ThinkingLeak，位置从台词下方提到最上方）
                message.thinkingText?.takeIf { it.isNotBlank() }?.let { thought ->
                    ThoughtCard(
                        thinkingText  = thought,
                        accentColor   = accentColor,
                        characterName = characterName,
                        maxWidth      = maxBubbleWidth,
                    )
                }

                // 2. 台词气泡（心理感受合并进气泡正文最前段，见下方 psychText 渲染）
                // W12问题1修复：容器改用 WorldOSComponents.kt 的 WorldBubble，接入
                // L0 纸面底 + L1 光斑 + L2 黄铜描边三层视觉规则，取代此前手写的
                // clip+background+border 组合。四角圆角（尖角在左下）、描边色
                // 沿用原值不变（borderColor 显式传 Gold 系，非 WorldBubble 默认
                // accent，保持与此前视觉一致）。
                // 文档发送方式：只有"有文字 + 有附件"才谈得上"合并/分开"，
                // 纯文件消息（content 为空）本来就没有文字气泡可合并，走原逻辑。
                val hasAttachment = message.exportedFiles.isNotEmpty() || message.tablePayload != null
                val mergeIntoBubble = attachFilesTogether && message.content.isNotBlank() && hasAttachment
                // Fix-PsychOnlyMessageDrop：psychText 现在渲染在气泡内部（合并展示
                // 方案A），若仍只按 message.content.isNotBlank() 判断要不要画气泡，
                // 会漏掉"整轮回复只有心理描写、没有台词"的消息——stripPsychText()
                // 剥离圆括号后 content 可以合法变成空串（例如整轮回复原文就是
                // "（沉默地看着窗外）"），此时旧版 PsychCard 是独立于台词气泡渲染的、
                // 仍然会显示；现在 psychText 挪进气泡内部后必须放宽这个条件，
                // 否则这类消息会静默消失，观感上比改动前更差。
                val hasPsych = message.psychText?.isNotBlank() == true
                val showBubble = message.content.isNotBlank() || hasPsych

                if (showBubble) {
                    // 2.1：同用户气泡，长按复制 + 按压缩放反馈。
                    val charInteraction = remember { MutableInteractionSource() }
                    val charPressed by charInteraction.collectIsPressedAsState()
                    val charScale by animateFloatAsState(
                        targetValue   = if (charPressed) 0.97f else 1f,
                        animationSpec = if (charPressed) snapSpring else appSpring,
                        label         = "charBubblePressScale",
                    )
                    // Fix-BubbleTextSelect：长按不再是"秒复制整条"，改为先弹一个
                    // 小菜单，用户自己选"复制"还是"选择文字"。
                    //   · menuVisible / menuOffset —— 菜单是否显示、显示在哪（贴着
                    //     长按点，menuOffset 记录的是长按点相对本 Box 左上角的
                    //     本地坐标，Popup 的 offset 参数按这个来定位）。
                    //   · isSelecting —— 是否处于"选择文字"模式。为 true 时：
                    //     1) 气泡的 combinedClickable 让路（不响应点击全屏/长按
                    //        弹菜单），完全交给 TextView 的原生拖选手势；
                    //     2) ContentBlockRenderer 下所有 MarkdownText 切到
                    //        setTextIsSelectable(true)（见 MarkdownText.kt）。
                    var menuVisible by remember { mutableStateOf(false) }
                    var menuOffset by remember { mutableStateOf(IntOffset.Zero) }
                    var isSelecting by remember { mutableStateOf(false) }

                    Box {
                        WorldBubble(
                            modifier    = Modifier
                                .widthIn(max = maxBubbleWidth)
                                .graphicsLayer { scaleX = charScale; scaleY = charScale }
                                .then(
                                    // 选字模式下彻底不挂手势：把触摸事件完全让给
                                    // TextView 自己的原生选择手势（拖手柄、系统气泡
                                    // 菜单），不与外层任何点击/长按逻辑竞争。
                                    //
                                    // 非选字模式下用单一 pointerInput + detectTapGestures
                                    // 同时处理"点按=全屏查看"和"长按=弹操作菜单"——
                                    // 不再叠加 combinedClickable，两套独立手势探测器
                                    // 同时监听同一批触摸事件会互相干扰（谁先消费、
                                    // 按压态由谁驱动都不确定），选一套就够。按压缩放
                                    // 反馈（charPressed）借用 charInteraction 手动
                                    // 在 onPress 里驱动，效果与之前一致。
                                    if (isSelecting) {
                                        Modifier
                                    } else {
                                        Modifier.pointerInput(message.id) {
                                            detectTapGestures(
                                                onPress = { offset ->
                                                    val press = androidx.compose.foundation.interaction.PressInteraction.Press(offset)
                                                    charInteraction.emit(press)
                                                    val released = tryAwaitRelease()
                                                    charInteraction.emit(
                                                        if (released) {
                                                            androidx.compose.foundation.interaction.PressInteraction.Release(press)
                                                        } else {
                                                            androidx.compose.foundation.interaction.PressInteraction.Cancel(press)
                                                        }
                                                    )
                                                },
                                                onTap = {
                                                    // v1.48：点击气泡全屏查看文本（角色气泡是 Markdown 渲染的）
                                                    if (message.content.isNotBlank()) {
                                                        onOpenFullText(message.content, true)
                                                    }
                                                },
                                                onLongPress = { pos ->
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    menuOffset = IntOffset(pos.x.toInt(), pos.y.toInt())
                                                    menuVisible = true
                                                },
                                            )
                                        }
                                    }
                                ),
                            topStart    = Radius.md,
                            topEnd      = Radius.md,
                            bottomStart = Radius.xs,
                            bottomEnd   = Radius.md,
                            // 角色气泡改版：不再是"accentColor 描边包一层纸面底"，
                            // 气泡本身纯色填充为该角色的 accentColor。
                            fillColor   = accentColor,
                        ) {
                        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = 12.dp)) {
                            // 心理感受合并展示（方案A·克制斜体）：圆括号心理描写作为
                            // 正文第一段，斜体+略降透明度，与下方台词同一 Column 连续
                            // 排布，视觉上是"一条消息的开头"而不是切开的独立元素。
                            // 颜色取舍：HTML 方案稿用固定暖金色（#C4A46A），但那是在
                            // 米白纸面底上；这里气泡本体是纯色填充的 accentColor（每个
                            // 角色不同、饱和度不定），固定金色在部分角色色上对比度会不
                            // 稳（比如角色色本身偏金/偏暖时几乎融为一色）。改为在
                            // contentOnFill() 派生色基础上降透明度——与下方 ACTION 语义
                            // 分段（ContentBlockRenderer 里 textColor.copy(alpha=0.55f)）
                            // 同一逻辑，保证任意角色色下都有足够对比度，同时仍与台词
                            // 满透明度正文区分出层级。
                            // 取舍：中文字体没有真正斜体字形，是系统强制倾斜渲染，笔画
                            // 可能显得别扭——如果真机效果不理想，可以把 fontStyle=Italic
                            // 去掉、只保留透明度区分（即方案B）。
                            message.psychText?.takeIf { it.isNotBlank() }?.let { psych ->
                                Text(
                                    text  = psych,
                                    style = type.body.copy(
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    ),
                                    color    = accentColor.contentOnFill().copy(alpha = 0.72f),
                                    modifier = Modifier.padding(bottom = Spacing.xs),
                                )
                            }

                            // 窗口3：角色气泡使用 ContentBlockRenderer 渲染（块级结构化 + 行内语义标记）
                            // 用户气泡（上方）保持原生 Text，FileExportCard 不受影响
                            val contentBlocks = remember(message.content) {
                                ContentBlockParser.parse(message.content)
                            }
                            // 纯色填充后文字色不能再用 colors.textPrimary（中性墨色只是
                            // 为纸面底设计的，配饱和 accentColor 底对比度不稳）——
                            // 改用 contentOnFill() 按每个角色色的亮度自动选深/浅字。
                            ContentBlockRenderer(
                                blocks     = contentBlocks,
                                textColor  = accentColor.contentOnFill(),
                                style      = type.body,
                                // Fix-BubbleTextSelect：由 BubbleActionMenu"选择
                                // 文字"驱动，一路传给内部 MarkdownText。
                                selectable = isSelecting,
                            )

                            // 文档发送方式="一起发"（默认）：文件/表格卡片嵌进同一个
                            // WorldBubble 内部，跟文字共用一个外框，视觉上是一条消息。
                            // 卡片自身仍保留 WorldCard 描边（与 Telegram/微信"文字+
                            // 附件同气泡"的呈现方式一致），只是不再各自套一层独立气泡。
                            if (mergeIntoBubble) {
                                Column(
                                    modifier = Modifier.padding(top = Spacing.sm),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                                ) {
                                    message.exportedFiles.forEach { ef ->
                                        FileExportCard(
                                            file        = ef,
                                            accentColor = accentColor,
                                            maxWidth    = maxBubbleWidth - Spacing.md * 2,
                                            onOpen      = { onOpenFile(ef) },
                                        )
                                    }
                                    message.tablePayload?.let { payload ->
                                        val excelFile = payload.exportedFileMetaJson?.let { metaJson ->
                                            parseExportedFilesWithFallback(null, metaJson).firstOrNull()
                                        }
                                        TableCard(
                                            payload     = payload,
                                            accentColor = accentColor,
                                            maxWidth    = maxBubbleWidth - Spacing.md * 2,
                                            onOpenExcel = excelFile?.let { ef -> { onOpenFile(ef) } },
                                            onOpenFullTable = {
                                                onOpenTable(payload.columns, payload.rows)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        }

                        // Fix-BubbleTextSelect：长按操作菜单，贴着长按落点弹出。
                        BubbleActionMenu(
                            visible      = menuVisible,
                            anchorOffset = menuOffset,
                            onCopy       = {
                                // Fix-PsychOnlyMessageDrop 连带修复：content 为空、
                                // 只有心理描写时，复制应复制 psychText，而不是复制
                                // 空字符串。
                                onCopyMessage(message.content.ifBlank { message.psychText.orEmpty() })
                            },
                            onSelectText = { isSelecting = true },
                            onDismiss    = { menuVisible = false },
                        )

                        // 选字模式下的退出入口：气泡右上角挂一个小提示条，点一下
                        // 结束选择、恢复正常点击/长按手势。不用系统返回键或点击
                        // 气泡外部退出——那样容易和"正在拖手柄选字"这个手势本身
                        // 冲突（拖到气泡边界外松手可能被误判为"点了外部要退出"）。
                        if (isSelecting) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = (-28).dp, end = 4.dp)
                                    .clip(RoundedCornerShape(Radius.xs))
                                    .background(Palette.Night)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication        = null,
                                        onClick           = { isSelecting = false },
                                    )
                                    .padding(horizontal = Spacing.sm, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = AppIcons.Check,
                                    contentDescription = "完成选择",
                                    tint     = Palette.GoldSoft,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text  = "完成",
                                    style = type.label,
                                    color = Palette.NightText,
                                )
                            }
                        }
                    }
                }

                // 4. 文件导出卡片 / 表格卡片——"分开发"模式（含纯文件消息，无文字可合并）：
                // 原有逻辑不变（Phase 18），各自独立成一张气泡，外层 Column 已经用
                // Arrangement.spacedBy(Spacing.xs) 统一管理垂直间距。
                if (!mergeIntoBubble) {
                    message.exportedFiles.forEach { ef ->
                        FileExportCard(
                            file        = ef,
                            accentColor = accentColor,
                            maxWidth    = maxBubbleWidth,
                            onOpen      = { onOpenFile(ef) },
                        )
                    }

                    // v67（表格直传 W4）：table_export 产出的表格卡片。
                    // 逻辑对齐上方 exportedFiles 渲染段——payload 非空时渲染 TableCard。
                    // >500 行场景 payload.exportedFileMetaJson 非 null，从里面解析出 xlsx
                    // 文件元信息走 onOpenFile（与 FileExportCard 同款打开路径）。
                    message.tablePayload?.let { payload ->
                        val excelFile = payload.exportedFileMetaJson?.let { metaJson ->
                            parseExportedFilesWithFallback(null, metaJson).firstOrNull()
                        }
                        TableCard(
                            payload     = payload,
                            accentColor = accentColor,
                            maxWidth    = maxBubbleWidth,
                            onOpenExcel = excelFile?.let { ef -> { onOpenFile(ef) } },
                            // v1.48：表格全屏查看/编辑
                            onOpenFullTable = {
                                onOpenTable(payload.columns, payload.rows)
                            },
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  FileExportCard — 文件下载卡片（Phase 18）
//
//  展示在角色气泡下方，样式参考 Telegram 文件卡片：
//    ┌──────────────────────────────────┐
//    │  📄  MD   周报草稿.md            │
//    │       1.2 KB                    │
//    │                        [打开] ▶ │
//    └──────────────────────────────────┘
// ─────────────────────────────────────────────────────────────

@Composable
internal fun FileExportCard(
    file: com.zaijian.zhoumuyun.ui.viewmodel.ExportedFile,
    accentColor: Color,
    maxWidth: androidx.compose.ui.unit.Dp,
    onOpen: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // WorldCard 接入（精修方案 v1.3）：附件卡归属当前聊天角色，L3 身份脊用该角色 accentColor。
    WorldCard(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clickable(onClick = onOpen),
        ownerAccent = accentColor,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // 文件类型徽标：MatBadge 微立体图标槽（细化方案第四节 + 第九节）
            // 图标按扩展名映射（fileIconForType），底色用角色 accentColor——
            // 这里的文件归属当前聊天角色（ownerAccent 已体现），图标槽继续用角色色是对的
            MatBadge(
                icon              = AppIcons.fileIconForType(file.extLabel),
                contentDescription = file.extLabel,
                color             = accentColor,
                badgeSize         = 40.dp,
                iconSize          = 20.dp,
                cornerRadius      = Radius.sm,
            )

            // 文件名 + 大小
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = file.fileName,
                    style    = type.body,
                    color    = colors.textPrimary,
                    // P2 修复：maxLines 1→2，避免用户导入的长文件名被过度截断。
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text  = file.sizeLabel,
                    style = type.caption,
                    color = colors.textSecondary,
                )
                // 1.3：委托生成的伪二进制文件（docx_gen/pdf_export）第三行提示，
                // 复用 caption + textSecondary，不额外造新样式，openHint 为 null
                // 时（真文本/真二进制文件）不渲染，卡片高度不变。
                file.openHint?.let { hint ->
                    Text(
                        text  = hint,
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                }
            }

            // 打开按钮
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accentColor.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text  = "打开",
                    style = type.label,
                    color = accentColor,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ThoughtCard — 想法卡（Fix-ThinkingLeak）
//
//  展示在角色气泡下方，纯折叠交互——默认只露标题行（图标 + "谁的想法" + chevron），
//  点开才展开正文，不预览摘要（想法长度不定，摘要要额外截断逻辑，不划算）。
//  外壳复用 WorldCard（与 FileExportCard 同构），展开动效抄
//  LearningGoalScreen.kt 的 RulePanelToggleRow（chevron 180° 旋转 +
//  expandVertically/shrinkVertically，同一组时间曲线，保证和"规则面板"那张卡
//  是一个手感，不会显得是另一套系统）。
// ─────────────────────────────────────────────────────────────

// v1.38 圆桌场景补齐：从 private 改为 internal，供 RoundtableBubble.kt 复用同一套
// 内心独白折叠卡实现——两处视觉/交互要求完全一致（标题文案、折叠动效、样式），
// 没有理由维护两份会漂移的拷贝。
//
// Fix-ThoughtCardPolish（方案A·现状打磨版）：在原壳体（WorldCard + 折叠交互）
// 基础上打磨三处细节，不改函数签名/调用点：
//   1. 图标从裸 Icon 改成圆形徽标底（icon-badge），视觉重量更接近"标签"而非"装饰"；
//   2. 折叠态标题行右侧加字数徽标，展开前就知道内容量，不用点开才发现"好长/好短"；
//   3. 展开正文顶部加"原始输出 · 未改写"角标 + 左侧竖线缩进，强化"这是模型原文，
//      不是二次摘要"的读者预期——对应产品要求"折叠后展开是原始推理文本，不改写"。
@Composable
internal fun ThoughtCard(
    thinkingText: String,
    accentColor: Color,
    characterName: String,
    maxWidth: androidx.compose.ui.unit.Dp,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var expanded by remember { mutableStateOf(false) }

    val chevronAngle by animateFloatAsState(
        targetValue   = if (expanded) 180f else 0f,
        animationSpec = tween(250),
        label         = "thoughtChevron",
    )

    WorldCard(
        modifier    = Modifier.widthIn(max = maxWidth),
        ownerAccent = accentColor,
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.md, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                // 图标徽标：圆形浅底 + 居中图标，替代此前裸 Icon，
                // 视觉上更像一枚"标签"而不是纯装饰性符号。
                Box(
                    modifier          = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment  = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = AppIcons.Lightbulb,
                        contentDescription = null,
                        tint               = accentColor,
                        modifier           = Modifier.size(11.dp),
                    )
                }
                Text(
                    // v1.36 问题2：标题从"${characterName}的想法"改为固定文案"内心独白"，
                    // 不用角色名前缀（气泡本身已经是该角色的消息，前缀是信息冗余），
                    // 也不用"AI推理过程"这类出戏表述。characterName 参数保留在函数签名
                    // 里未删除，是为了不破坏 ThoughtCard 现有调用方签名。
                    text     = "思考过程",
                    style    = type.label,
                    color    = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                // 字数徽标：折叠态就能预判内容量，不用点开才发现"好长"。
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.textDisabled.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text  = "${thinkingText.length}字",
                        style = type.caption,
                        color = colors.textDisabled,
                    )
                }
                Icon(
                    imageVector        = AppIcons.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint               = colors.textDisabled,
                    modifier           = Modifier
                        .size(16.dp)
                        .rotate(chevronAngle),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically(tween(250)) + fadeIn(tween(200)),
                exit    = shrinkVertically(tween(200)) + fadeOut(tween(150)),
            ) {
                // 左侧竖线 + 缩进模拟"批注在正文旁"的观感；顶部角标点明
                // "这是模型原始推理文本，未经改写/摘要"，与折叠卡默认收起的
                // 定位（戏外决策过程，非必须阅读）互相呼应。
                Row(
                    modifier = Modifier
                        .padding(top = Spacing.xs)
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .background(colors.border),
                    )
                    Column(
                        modifier = Modifier
                            .padding(start = Spacing.sm)
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            text     = "原始输出 · 未改写",
                            style    = type.caption,
                            color    = colors.textDisabled,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Text(
                            text  = thinkingText,
                            style = type.caption,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  PsychCard — 心理感受小卡（v1.36 问题2·三层分离新增）
//
//  展示在台词气泡上方，不折叠（区别于 ThoughtCard 的默认收起）——这是"戏内"内容，
//  用户会想直接看到，不像 ThoughtCard 装的"决策思考"那样默认不需要看。
//  样式参照用户已确认的排版方案：金色低透明度底 + 左侧 2dp 竖线 + 斜体小字，
//  与 ThoughtCard 的 WorldCard 卡片壳区分开，视觉上更轻、更像批注而不是一张"卡片"。
//  左侧竖线用 Row + Modifier.height(IntrinsicSize.Min) 让两个子项按最高的一个
//  （即 Text 的实际高度）对齐——Compose 没有 CSS 那种单边 border 简写，
//  这是子项等高对齐最简单直接的写法，不需要 drawBehind 手动画线。
// ─────────────────────────────────────────────────────────────

// v1.38 圆桌场景补齐：同上，从 private 改为 internal 供 RoundtableBubble.kt 复用。
@Composable
internal fun PsychCard(
    psychText: String,
    accentColor: Color,
    maxWidth: androidx.compose.ui.unit.Dp,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(4.dp))
            .background(Palette.Gold.copy(alpha = if (colors.isDark) 0.10f else 0.14f)),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Palette.Gold.copy(alpha = if (colors.isDark) 0.22f else 0.28f)),
        )
        Text(
            text     = psychText,
            style    = type.caption.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
            color    = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  TableCard / TableDetailSheet — 表格直传方案 W3 UI 组件
//
//  展示 `table_export` 工具产出的 [TablePayload]（W2 已实现，走
//  `ToolResult.tablePayloadJson` 返回值；W4 管线打通后从 `message.tableDataJson`
//  反序列化拿到）。本批次只把组件本身做完、能独立预览，**不接入**
//  `MessageBubble` 的实际渲染分发——分发逻辑（`message.tablePayload` 非空时渲染
//  本卡，逻辑对齐现有 `message.exportedFiles` 那段）留给 W4。
//
//  结构参照 [FileExportCard]（同文件）：WorldCard 外壳 + 图标徽标 + 标题/摘要列 +
//  操作按钮。`>500` 行场景的"下载 Excel"按钮复用 [FileExportCard] 的 `onOpen`
//  回调模式（W4 把 `TablePayload.exportedFileMetaJson` 里的 `absolutePath` 走同一套
//  ContentProvider URI 分享）。
//
//  展开视图 [TableDetailSheet] 用 `ModalBottomSheet` + `LazyColumn` 逐行渲染
//  （指令要求 LazyColumn，避免大表全量组合导致性能问题），表头 sticky 置顶，
//  横向 `horizontalScroll` 支持宽表。
// ─────────────────────────────────────────────────────────────

/**
 * 表格卡片：展示 [TablePayload] 的预览入口。
 *
 * - 卡片本体只显示标题/行列数摘要 + "查看全部"按钮（点开弹 [TableDetailSheet]）
 * - `onOpenExcel != null`（>500 行场景，payload 带 xlsx 附件）时多一个"下载 Excel"按钮
 * - 不直接渲染表格内容（表格可能上千行，卡片本体保持轻量，详情走 BottomSheet）
 *
 * W4 接入点：`MessageBubble` 里 `message.tablePayload != null` 时调用本组件，
 * 传 `payload = message.tablePayload!!`、`accentColor`、`maxWidth`、
 * `onOpenExcel` 从 `payload.exportedFileMetaJson` 解析 `absolutePath` 构造。
 *
 * @param payload     表格数据（W2 产出，W4 从 `tableDataJson` 反序列化）
 * @param accentColor 角色主题色（与 FileExportCard 同款，传当前消息归属角色的 accent）
 * @param maxWidth    卡片最大宽度（与 FileExportCard 同款，传气泡宽度约束）
 * @param onOpenExcel >500 行场景的 xlsx 下载回调；null = 无 xlsx 附件（≤500 行场景）
 */
@Composable
internal fun TableCard(
    payload: TablePayload,
    accentColor: Color,
    maxWidth: androidx.compose.ui.unit.Dp,
    onOpenExcel: (() -> Unit)? = null,
    // v1.48：表格全屏查看/编辑
    onOpenFullTable: (() -> Unit)? = null,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var showDetail by remember { mutableStateOf(false) }

    WorldCard(
        modifier = Modifier.widthIn(max = maxWidth),
        ownerAccent = accentColor,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // 表格图标徽标（与 FileExportCard 的文件类型徽标同构）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.ToolTable,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp),
                )
            }

            // 标题 + 行列数摘要
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = payload.title.ifBlank { "表格" },
                    style    = type.body,
                    color    = colors.textPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text  = "共 ${payload.rowCountTotal} 行 × ${payload.columns.size} 列",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                // >500 行预览场景的额外提示
                if (payload.rows.size < payload.rowCountTotal) {
                    Text(
                        text  = "卡片预览前 ${payload.rows.size} 行，点“查看全部”展开",
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                }
            }

            // 查看全部按钮 + 全屏按钮（v1.48）
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(accentColor.copy(alpha = 0.1f))
                        .clickable { showDetail = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text  = "查看全部",
                        style = type.label,
                        color = accentColor,
                    )
                }
                if (onOpenFullTable != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(accentColor.copy(alpha = 0.1f))
                            .clickable { onOpenFullTable() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text  = "全屏",
                            style = type.label,
                            color = accentColor,
                        )
                    }
                }
            }

            // >500 行场景：下载 Excel 按钮（复用 FileExportCard 的 onOpen 模式）
            if (onOpenExcel != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(accentColor.copy(alpha = 0.1f))
                        .clickable(onClick = onOpenExcel)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text  = "下载 Excel",
                        style = type.label,
                        color = accentColor,
                    )
                }
            }
        }
    }

    // 详情 BottomSheet
    if (showDetail) {
        TableDetailSheet(
            payload     = payload,
            accentColor = accentColor,
            onDismiss   = { showDetail = false },
        )
    }
}

/**
 * 表格详情视图：`ModalBottomSheet` + `LazyColumn` 逐行渲染。
 *
 * - 表头 sticky 置顶（`stickyHeader`），滚动时始终可见
 * - 横向 `horizontalScroll` 支持宽表（列数多时不出屏）
 * - 大表（>500 行预览场景）只渲染 payload.rows 里的预览行（前 10 行），
 *   全量数据已通过 xlsx 下载附件提供，BottomSheet 不重复全量渲染
 *
 * 参照 [FamilyPickerSheet] 的 ModalBottomSheet 用法（`onDismissRequest`/`sheetState`/
 * `containerColor`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TableDetailSheet(
    payload: TablePayload,
    accentColor: Color,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val horizontalScrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.bgCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.xl),
        ) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text  = payload.title.ifBlank { "表格" },
                    style = type.cardTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text  = "共 ${payload.rowCountTotal} 行",
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }

            // 表格主体：横向滚动支持宽表
            // 注意：LazyColumn 的 stickyHeader + 横向滚动同时用，需要把横向滚动放在
            // LazyColumn 外层——LazyColumn 纵向滚动，外层 Row/Column 横向滚动，
            // 两个方向独立。但 stickyHeader 在横向滚动下会跟着滚走（sticky 只对纵向
            // 生效）。这里折中：表头单独放一个不滚动的 Row（在 LazyColumn 之外），
            // 数据行放 LazyColumn 里纵向滚动。这样表头永远置顶，数据行横向滚动时
            // 表头也横向滚动（两个同步）——用同一个 horizontalScrollState 同步。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScrollState),
            ) {
                Column {
                    // 表头（与数据行同步横向滚动）
                    TableRow(
                        cells = payload.columns,
                        isHeader = true,
                        accentColor = accentColor,
                    )
                    // 数据行（LazyColumn 纵向滚动）
                    LazyColumn(
                        modifier = Modifier.height(400.dp),  // 限定高度避免占满屏
                    ) {
                        items(payload.rows) { row ->
                            TableRow(
                                cells = row,
                                isHeader = false,
                                accentColor = accentColor,
                            )
                        }
                    }
                }
            }

            // >500 行预览场景的底部提示
            if (payload.rows.size < payload.rowCountTotal) {
                Text(
                    text  = "仅显示前 ${payload.rows.size} 行（共 ${payload.rowCountTotal} 行），完整数据请下载 Excel",
                    style = type.caption,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
        }
    }
}

/**
 * 单行表格（表头或数据行），横向排列单元格。
 *
 * 列宽用 `Modifier.width(IntrinsicSize.Min)` 的等宽策略不够灵活，这里改用
 * 固定最小列宽 + 内容自适应——每列 `widthIn(min = 80.dp)`，内容长则撑宽。
 * 表头行用 accent 色背景 + 加粗，数据行用默认背景。
 */
@Composable
private fun TableRow(
    cells: List<String>,
    isHeader: Boolean,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isHeader) accentColor.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = Spacing.sm, vertical = 6.dp),
    ) {
        cells.forEach { cell ->
            Text(
                text     = cell,
                style    = if (isHeader) type.label.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                ) else type.body,
                color    = if (isHeader) accentColor else colors.textPrimary,
                modifier = Modifier
                    .widthIn(min = 80.dp)
                    .padding(end = Spacing.sm),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  StreamingMessageItem — 流式打字机气泡
//  H1 修复：独立子组件，自己收集 streamingContent。
//  每个 token 只触发此组件重组，ChatScreen 顶层保持稳定。
// ─────────────────────────────────────────────────────────────

@Composable
internal fun StreamingMessageItem(
    chatViewModel: ChatViewModel,
    accentColor: Color,
    avatarUrl: String,
    characterName: String,
    avatarCropOffsetX: Float = 0f,
    avatarCropOffsetY: Float = 0f,
    avatarCropScale: Float = 1f,
) {
    // L-8 修复：原先收集完整 uiState，每个 token 都会让顶层 ChatScreen（同样收集
    // 完整 uiState）一并重组，H1 设计的隔离效果实际未生效。
    // 改为只收集 ChatViewModel 新暴露的独立 streamingContent: StateFlow<String?>，
    // 该流只在内容真正变化时更新，且不携带 uiState 其余字段，
    // 真正把高频重组限制在 StreamingMessageItem 内部。
    val streamingContent by chatViewModel.streamingContent.collectAsStateWithLifecycle()
    // Fix-StreamingPsychLeak：与 streamingContent 同一模式收集实时心理描写，
    // 让 PsychCard 能在打字机效果进行中就显示，不必等到 isStreaming 结束、
    // 消息真正落库后才出现（此前流式阶段 psychText 恒为 null，PsychCard
    // 在角色打字期间完全不会渲染，是"折叠的思考过程不显示"反馈的成因之一）。
    val streamingPsych by chatViewModel.streamingPsych.collectAsStateWithLifecycle()
    // 编译修复：isNullOrEmpty() 是扩展函数，不会对 streamingContent 触发智能转换收窄，
    // then 分支类型仍是 String?，导致整个表达式推断为 String?，与 content: String 不匹配。
    // 用局部 val 显式判空后取值，保证类型确定为 String。
    val currentStreaming = streamingContent
    val displayContent = if (!currentStreaming.isNullOrEmpty()) currentStreaming else "…"
    MessageBubble(
        message = com.zaijian.zhoumuyun.ui.viewmodel.ChatMessage(
            id        = "streaming",
            role      = "assistant",
            content   = displayContent,
            createdAt = System.currentTimeMillis(),
            psychText = streamingPsych,
        ),
        accentColor   = accentColor,
        avatarUrl     = avatarUrl,
        characterName = characterName,
        avatarCropOffsetX = avatarCropOffsetX,
        avatarCropOffsetY = avatarCropOffsetY,
        avatarCropScale   = avatarCropScale,
    )
}

// ─────────────────────────────────────────────────────────────
//  ToolHintRow — 工具执行提示行（Phase 13）
//  显示在打字机气泡下方，工具执行期间可见，ToolDone 后自动消失。
//
//  视觉设计：
//    - 左对齐，与角色气泡对齐（预留 32dp 头像位 + 8dp 间距）
//    - accentColor.copy(alpha=0.55f) 文字，低调不抢眼
//    - 小号字（label），无背景，无气泡
//    - 前置 ⚙ 图标，直径 14dp
// ─────────────────────────────────────────────────────────────

@Composable
internal fun ToolHintRow(
    hint: String,
    accentColor: Color,
) {
    // P3-42 修复：统一主题引用方式，使用 ZaijianTheme 而非直接访问 LocalAppColors/LocalAppTypography
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AvatarSize.bubbleAvatar + Spacing.sm + Spacing.sm,
                end    = Spacing.md,
                top    = 2.dp,
                bottom = Spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text  = "⚙ $hint",
            style = type.label,
            color = accentColor.copy(alpha = 0.55f),
        )
    }
}
