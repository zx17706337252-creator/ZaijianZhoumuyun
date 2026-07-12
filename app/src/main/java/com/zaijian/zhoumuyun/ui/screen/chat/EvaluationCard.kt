package com.zaijian.zhoumuyun.ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ripple.ripple
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
//  EvaluationCard — Agent B 汇报 + 用户打分卡片（Phase 24）
//  拆分自 ChatScreen.kt（v87 Phase 2）。
//  说明：审计报告的四文件方案未单列此组件；逐行核对后发现它既不属于
//  消息气泡簇，也不属于顶栏/输入栏/设置面板，是独立特性，故单独成文件。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  EvaluationCard — Agent B 汇报 + 用户打分卡片（Phase 24）
//
//  布局：
//    ┌─────────────────────────────────────────────┐
//    │  📊 Agent B 评审汇报（Markdown 渲染）        │
//    │                                              │
//    │  你的评分：  ☆ ☆ ☆ ☆ ☆                    │
//    │  [跳过]                        [提交打分]    │
//    └─────────────────────────────────────────────┘
//
//  用户选星后「提交打分」按钮变为 accentColor 激活状态。
//  「跳过」调用 skipEvaluation()，卡片消失，不记录分数。
// ─────────────────────────────────────────────────────────────

@Composable
internal fun EvaluationCard(
    reportText:  String,
    agentScore:  Float?,
    accentColor: Color,
    onSubmit:    (Int) -> Unit,
    onSkip:      () -> Unit,
    modifier:    Modifier = Modifier,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    var selectedStars by remember { mutableIntStateOf(0) }

    AnimatedVisibility(
        visible = true,
        enter   = fadeIn(tween(AnimDuration.pageSwitch)) +
                  slideInVertically(tween(AnimDuration.pageSwitch)) { it / 2 },
        exit    = fadeOut(tween(AnimDuration.fast)),
        modifier = modifier.fillMaxWidth(),
    ) {
        // WorldCard 接入（精修方案 v1.3）：单角色评审汇报卡，整卡内容均归属
        // 当前对话角色，L3 身份脊用该角色 accentColor。
        WorldCard(
            modifier = Modifier.fillMaxWidth(),
            ownerAccent = accentColor,
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // ── 标题行 ──────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text  = "📊",
                    style = type.body,
                )
                Text(
                    text  = "本次对话评审",
                    style = type.cardTitle,
                    color = accentColor,
                )
                if (agentScore != null) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(accentColor.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text  = "AI ${"%.1f".format(agentScore)}",
                            style = type.label,
                            color = accentColor,
                        )
                    }
                }
            }

            // ── Agent B 评审汇报文本 ──────────────────────
            MarkdownText(
                markdown  = reportText,
                textColor = colors.textSecondary,
                style     = type.caption,
            )

            // ── 分隔线 ────────────────────────────────────
            HorizontalDivider(
                color     = accentColor.copy(alpha = 0.15f),
                thickness = 0.5.dp,
            )

            // ── 用户打星区 ────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text  = "你的评分",
                    style = type.label,
                    color = colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { star ->
                        val filled = star <= selectedStars
                        Text(
                            text     = if (filled) "⭐" else "☆",
                            style    = type.body.copy(
                                fontSize = androidx.compose.ui.unit.TextUnit(
                                    22f, androidx.compose.ui.unit.TextUnitType.Sp
                                )
                            ),
                            color    = if (filled) accentColor else colors.textDisabled,
                            // P3-24 修复：为星级评分添加 ripple 点击反馈
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),  // 代码清洁：rememberRipple → ripple()
                            ) { selectedStars = star },
                        )
                    }
                }
            }

            // ── 操作按钮行 ────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // 跳过
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable(onClick = onSkip)
                        .padding(horizontal = Spacing.md, vertical = 6.dp),
                ) {
                    Text(
                        text  = "跳过",
                        style = type.label,
                        color = colors.textDisabled,
                    )
                }

                // 提交打分
                val canSubmit = selectedStars > 0
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(
                            if (canSubmit) accentColor
                            else colors.textDisabled.copy(alpha = 0.3f)
                        )
                        .clickable(enabled = canSubmit) { onSubmit(selectedStars) }
                        .padding(horizontal = Spacing.md, vertical = 6.dp),
                ) {
                    Text(
                        text  = "提交打分",
                        style = type.label,
                        color = Color.White,
                    )
                }
            }
        }
        }
    }
}
