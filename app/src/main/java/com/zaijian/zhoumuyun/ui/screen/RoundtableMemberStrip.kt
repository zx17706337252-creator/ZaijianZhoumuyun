package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import com.zaijian.zhoumuyun.ui.theme.AnimDuration
import com.zaijian.zhoumuyun.ui.theme.AppBrushes
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.BubbleDimen
import com.zaijian.zhoumuyun.ui.theme.DotSize
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.theme.presenceGlow
import com.zaijian.zhoumuyun.ui.viewmodel.BotGenerationStatus
import com.zaijian.zhoumuyun.ui.viewmodel.RoundtableMessage
import com.zaijian.zhoumuyun.ui.viewmodel.RoundtableViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.ScheduleMode
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import com.zaijian.zhoumuyun.ui.design.AppIcons


// ─────────────────────────────────────────────────────────────
//  MemberStrip — 成员切换栏（水平滚动，粘性）
// ─────────────────────────────────────────────────────────────

@Composable
internal fun MemberStrip(
    members: List<CharacterConfig>,
    generationStatus: Map<Int, BotGenerationStatus>,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val scroll = rememberScrollState()

    Row(
        modifier = modifier
            .height(64.dp)   // 64dp：56dp内容 + 8dp为头像弹出留空
            .background(
                if (colors.isDark) colors.bgCard.copy(alpha = 0.85f)
                else colors.bgBase.copy(alpha = 0.90f)
            )
            .border(
                width = 0.5.dp,
                color = colors.border,
                shape = RoundedCornerShape(0.dp),
            )
            .horizontalScroll(scroll)
            .padding(horizontal = Spacing.screenHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        members.forEach { bot ->
            val status = generationStatus[bot.id] ?: BotGenerationStatus.IDLE
            MemberChip(bot = bot, status = status)
        }
    }
}


@Composable
private fun MemberChip(
    bot: CharacterConfig,
    status: BotGenerationStatus,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // GENERATING 状态 = 当前激活（头像弹出）
    val isActive = status == BotGenerationStatus.GENERATING

    // 头像弹出动画：GENERATING 时上移 4dp，弹性回弹
    val yOffset by animateDpAsState(
        targetValue   = if (isActive) (-4).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label         = "chip_pop_${bot.id}",
    )

    // 脉冲动画（状态点用）
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_${bot.id}")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue   = 1f,
        targetValue    = 0.3f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    val dotColor = when (status) {
        BotGenerationStatus.GENERATING -> colors.statusIdle.copy(alpha = pulseAlpha)
        BotGenerationStatus.DONE       -> colors.statusActive
        BotGenerationStatus.WAITING    -> colors.accent.copy(alpha = 0.45f)
        BotGenerationStatus.IDLE       -> colors.textDisabled.copy(alpha = 0.4f)
    }

    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // 头像容器：上移弹出 + presenceGlow（仅 GENERATING 时亮起）
        Box(
            modifier = Modifier
                .offset(y = yOffset)
                .presenceGlow(
                    color       = bot.accentColor,
                    isActive    = isActive,
                    breathAlpha = 0.32f,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(bot.avatarUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = bot.name,
                modifier           = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(bot.accentColor.copy(alpha = 0.3f))
                    // P2 修复：追加可见描边，与 BreathingAvatar 的 statusRing + glow 风格保持一致。
                    // 保留现有 presenceGlow 而非替换为完整 BreathingAvatar：
                    // presenceGlow 是本组件的既定轻量方案（28dp 小头像 + 生成状态点），
                    // 替换为 BreathingAvatar 是更大改动，风险不成比例，故仅做最小视觉对齐。
                    // UI 升级 v2.0（融合方案帧17 成员条报幕）：发言中成员的头像描边
                    // 升级为 2dp 黄铜渐变金环（上移弹出 + 金环 + 金点脉冲三件套之一），
                    // 非发言态保持 1dp 角色色淡边——"谁在开腔"靠上光，不靠放大。
                    .then(
                        if (isActive) {
                            Modifier.border(2.dp, AppBrushes.goldGradient(), CircleShape)
                        } else {
                            Modifier.border(1.dp, bot.accentColor.copy(alpha = 0.3f), CircleShape)
                        }
                    ),
                error              = rememberVectorPainter(AppIcons.Person),
            )

            // 金色指示点：紧贴头像底部中心，仅 GENERATING 时显示
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .offset(y = 3.dp)
                        .clip(CircleShape)
                        .background(Palette.Gold),
                )
            }
        }

        Text(
            text  = bot.name,
            style = type.label,
            // UI 升级 v2.0：发言中名字用深金 accentDeep（帧17：衬线署名的视觉落点
            // 在发言者身上），其余状态沿用墨色层级不变。
            color = when (status) {
                BotGenerationStatus.GENERATING -> colors.accentDeep
                BotGenerationStatus.WAITING    -> colors.textSecondary
                BotGenerationStatus.DONE       -> colors.textSecondary
                else                           -> colors.textDisabled
            },
        )

        // 生成状态点
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
    }
}
