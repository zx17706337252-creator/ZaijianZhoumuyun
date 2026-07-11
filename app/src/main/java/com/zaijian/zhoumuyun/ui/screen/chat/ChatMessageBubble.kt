package com.zaijian.zhoumuyun.ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import com.zaijian.zhoumuyun.ui.design.WorldCard
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.zaijian.zhoumuyun.data.model.ChatMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.component.FertileWindowConsentDialog
import com.zaijian.zhoumuyun.ui.component.MarkdownText
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.BubbleDimen
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.RingWidth
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.ChatViewModel

import com.zaijian.zhoumuyun.ui.viewmodel.KnowledgeInjectMode
import com.zaijian.zhoumuyun.ui.viewmodel.PresenceViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.ProjectViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf

import com.zaijian.zhoumuyun.ZaijianApp
import com.zaijian.zhoumuyun.domain.MoodType
import com.zaijian.zhoumuyun.util.TimeFormatUtils

// ─────────────────────────────────────────────────────────────
//  消息气泡簇：MessageBubble / FileExportCard / StreamingMessageItem / ToolHintRow
//  拆分自 ChatScreen.kt（v87 Phase 2）。
//  StreamingMessageItem 内部复用 MessageBubble；FileExportCard 被
//  MessageBubble 在展示 exportedFile 时调用——四者是同一簇，物理上放同一文件。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  MessageBubble — 单条消息
//  规范 §13：
//    角色气泡 圆角 20/20/20/4dp，左侧 32dp 头像
//    用户气泡 圆角 20/4/20/20dp，右对齐，accentColor 填充
//    最大宽度 屏幕宽 × 0.72
// ─────────────────────────────────────────────────────────────

@Composable
internal fun MessageBubble(
    message: com.zaijian.zhoumuyun.ui.viewmodel.ChatMessage,
    accentColor: Color,
    avatarUrl: String,
    characterName: String,
    onOpenFile: (com.zaijian.zhoumuyun.ui.viewmodel.ExportedFile) -> Unit = {},
) {
    val colors         = ZaijianTheme.colors
    val type           = ZaijianTheme.typography
    val screenWidth    = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * BubbleDimen.maxWidthFraction

    if (message.role == "user") {
        // ── 用户气泡（右对齐）──────────────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(
                        RoundedCornerShape(
                            topStart    = Radius.md,
                            topEnd      = Radius.md,
                            bottomStart = Radius.md,
                            bottomEnd   = Radius.xs,
                        )
                    )
                    .background(accentColor)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                Text(
                    text  = message.content,
                    style = type.body,
                    color = Color.White,
                )
            }
        }
    } else {
        // ── 角色气泡（左对齐，带头像）───────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            // 头像占位（32dp）
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(avatarUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = characterName,
                modifier           = Modifier
                    .size(AvatarSize.chat)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.2f)),
                contentScale       = ContentScale.Crop,
                error              = rememberVectorPainter(Icons.Outlined.Person),
            )

            Spacer(Modifier.width(Spacing.sm))

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                // 文字气泡（有内容时显示）
                if (message.content.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = maxBubbleWidth)
                            .clip(
                                RoundedCornerShape(
                                    topStart    = Radius.md,
                                    topEnd      = Radius.md,
                                    bottomStart = Radius.xs,
                                    bottomEnd   = Radius.md,
                                )
                            )
                            .background(if (colors.isDark) colors.bgCard else colors.bgElevated)
                            .border(
                                width  = 0.5.dp,
                                color  = if (colors.isDark) Palette.Gold.copy(alpha = 0.18f) else Palette.Gold.copy(alpha = 0.28f),
                                shape  = RoundedCornerShape(
                                    topStart    = Radius.md,
                                    topEnd      = Radius.md,
                                    bottomStart = Radius.xs,
                                    bottomEnd   = Radius.md,
                                ),
                            )
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    ) {
                        // Phase 21：角色气泡使用 MarkdownText 渲染富文本
                        // 用户气泡（上方）保持原生 Text，FileExportCard 不受影响
                        MarkdownText(
                            markdown  = message.content,
                            textColor = colors.textPrimary,
                            style     = type.body,
                        )
                    }
                }

                // Phase 18：文件导出卡片（有 exportedFile 时显示）
                message.exportedFile?.let { ef ->
                    FileExportCard(
                        file        = ef,
                        accentColor = accentColor,
                        maxWidth    = maxBubbleWidth,
                        onOpen      = { onOpenFile(ef) },
                    )
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
private fun FileExportCard(
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
            // 文件类型徽标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = file.extLabel,
                    style = type.label.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize   = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                    color = accentColor,
                )
            }

            // 文件名 + 大小
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = file.fileName,
                    style    = type.body,
                    color    = colors.textPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text  = file.sizeLabel,
                    style = type.caption,
                    color = colors.textSecondary,
                )
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
) {
    // L-8 修复：原先收集完整 uiState，每个 token 都会让顶层 ChatScreen（同样收集
    // 完整 uiState）一并重组，H1 设计的隔离效果实际未生效。
    // 改为只收集 ChatViewModel 新暴露的独立 streamingContent: StateFlow<String?>，
    // 该流只在内容真正变化时更新，且不携带 uiState 其余字段，
    // 真正把高频重组限制在 StreamingMessageItem 内部。
    val streamingContent by chatViewModel.streamingContent.collectAsStateWithLifecycle()
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
        ),
        accentColor   = accentColor,
        avatarUrl     = avatarUrl,
        characterName = characterName,
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
    val colors = com.zaijian.zhoumuyun.ui.theme.LocalAppColors.current
    val type   = com.zaijian.zhoumuyun.ui.theme.LocalAppTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AvatarSize.bubbleAvatar + Spacing.sm + Spacing.sm,
                end    = Spacing.md,
                top    = 2.dp,
                bottom = 4.dp,
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
