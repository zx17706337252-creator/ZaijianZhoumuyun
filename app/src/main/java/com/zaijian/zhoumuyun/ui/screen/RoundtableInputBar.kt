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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.zaijian.zhoumuyun.ui.component.SendButton
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
import com.zaijian.zhoumuyun.ui.theme.presenceGlow
import com.zaijian.zhoumuyun.ui.viewmodel.BotGenerationStatus
import com.zaijian.zhoumuyun.ui.viewmodel.RoundtableMessage
import com.zaijian.zhoumuyun.ui.viewmodel.RoundtableViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.ScheduleMode
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow


// ─────────────────────────────────────────────────────────────
//  RoundtableInputBar — 底部输入栏
// ─────────────────────────────────────────────────────────────

@Composable
internal fun RoundtableInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    canSend: Boolean,
    isWaiting: Boolean,
    bgColor: Color,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography

    // 修复（与 RoundtableHeader 顶边框同一类问题）：navigationBarsPadding() 此前
    // 在调用方（RoundtableScreen）的 modifier 里，排在 background()/border() 之前
    // （外层），导致输入栏的背景只覆盖到 navigationBarsPadding 让出的那段以上，
    // 底部导航栏/手势条安全区完全没有背景覆盖——效果和顶边框一样：消息一旦滚动
    // 到那个位置就会直接透出来，边框也没有顶到屏幕真正的底边。
    // 现在把 navigationBarsPadding() 挪到这里，放在 background()/border() 之后
    // （内层），背景/边框先按 modifier 传入的完整尺寸铺满到屏幕最底端，
    // navigationBarsPadding 只把里面的文本框/发送按钮内容再往上推，不影响背景范围。
    Row(
        modifier = modifier
            .background(bgColor)
            .border(
                width = 0.5.dp,
                color = colors.borderSubtle,
                shape = RoundedCornerShape(0.dp),
            )
            .navigationBarsPadding()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value         = value,
            onValueChange = onValueChange,
            textStyle     = type.body.copy(color = colors.textPrimary),
            cursorBrush   = SolidColor(colors.accent),
            modifier      = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    if (colors.isDark) colors.bgElevated else colors.bgCard
                )
                .padding(horizontal = Spacing.md, vertical = 10.dp),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text     = if (isWaiting) "对大家说点什么…" else "Bot 回复中，输入可打断…",
                        style    = type.body,
                        color    = colors.textDisabled,
                        // P2 修复：防御性添加 maxLines/overflow，避免多语言适配时占位文本溢出。
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            },
        )
        Spacer(Modifier.width(Spacing.sm))
        // 窗口16审计【问题E1/补充发现2】修复：改用共享 SendButton 组件，
        // 补齐此前缺失的 P2-5 触摸区修复（此前触摸区仅 32dp，未同步
        // ChatInputBar 的 48dp 触摸区外壳）。背景保留原有 colors.accent 纯色配色。
        SendButton(
            enabled = canSend,
            background = Brush.linearGradient(
                colors = listOf(
                    if (canSend) colors.accent else colors.textDisabled.copy(alpha = 0.3f),
                    if (canSend) colors.accent else colors.textDisabled.copy(alpha = 0.3f),
                ),
            ),
            onSend = onSend,
        )
    }
}
