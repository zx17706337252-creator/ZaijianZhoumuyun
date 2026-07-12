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
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
//  顶栏簇：ChatHeader / ModeChip / ChatRelCapsule
//  拆分自 ChatScreen.kt（v87 Phase 2）。
//  ModeChip 和 ChatRelCapsule 原先物理位置在文件末尾，但实际只被
//  ChatHeader 调用——三者是同一簇，随 ChatHeader 一起搬迁。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  ChatHeader — 毛玻璃顶栏
//  规范 §13：返回箭头 / 头像+角色名+状态文案 / 更多图标
// ─────────────────────────────────────────────────────────────

@Composable
internal fun ChatHeader(
    name: String,
    avatarUrl: String,
    breathColor: Color,
    accentColor: Color,
    statusText: String,
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
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Box(
        modifier = modifier
            .background(headerBg)
            .border(
                width  = 0.5.dp,
                color  = colors.borderSubtle,
                shape  = RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp),
            )
            .statusBarsPadding(),
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.topBarHeight)  // Fix-ChatHeader: 改 height→heightIn，关系胶囊行存在时可撑开
                .padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回箭头
            IconButton(onClick = onBack) {
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
                )
            }

            Spacer(Modifier.width(Spacing.sm))

            // 角色名 + 状态文案
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = name,
                    style = type.navTitle,
                    color = colors.textPrimary,
                    // P2-7 修复：角色名增加 maxLines + Ellipsis，防止长名字溢出
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (statusText.isNotEmpty()) {
                    Text(
                        text  = statusText,
                        style = type.label.copy(
                            fontStyle     = FontStyle.Italic,
                            letterSpacing = 0.3.sp,
                        ),
                        color    = accentColor.copy(alpha = if (colors.isDark) 0.80f else 0.70f),
                        maxLines = 1,
                        // P3-23 修复：maxLines=1 时补充 overflow = Ellipsis，防止长状态文本溢出
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 待办10：关系状态胶囊行（紧凑，仅有数据时显示）
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
                if (hasRelInfo) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
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

            // 模式切换（工作 / 陪伴）— Phase 30 方案一
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.borderSubtle)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
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
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector        = Icons.Outlined.MoreVert,
                    contentDescription = "更多",
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(24.dp),
                )
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

    val bg      = if (selected) accent else Color.Transparent
    val content = if (selected) Color.White else colors.textSecondary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .minimumInteractiveComponentSize()
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // P3-21 修复（重做）：图标视觉维持 16dp，外层 Row 通过 minimumInteractiveComponentSize
        // 扩大触摸区至 48dp。核心问题是触摸区不足而非图标看不清，直接放大图标
        // 会导致 Chip 整体视觉变粗，与旁边元素比例失调。
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = content,
            modifier           = Modifier.size(16.dp),
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
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text  = text,
            style = ZaijianTheme.typography.caption,
            color = color,
        )
    }
}
