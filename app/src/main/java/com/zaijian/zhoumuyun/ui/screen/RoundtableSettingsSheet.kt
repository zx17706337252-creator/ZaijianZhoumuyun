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
import androidx.compose.foundation.layout.wrapContentSize
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
//  RoundtableSettingsSheet — 圆桌设置面板（Step 5：屏蔽制改造）
//
//  设计方案 §1 + §6：
//    A. 母角色区：1-9 已解锁角色，勾选框形式，取消勾选 = 屏蔽
//       （移出本轮，随时可重新勾回），不再是"4人上限选人"。
//    B. 女儿/第三代区：列表 + "拉入"/"移出"按钮，类似
//       FamilyPickerSheet 的家族链选择交互，但放在圆桌设置面板内。
//    C. 调度模式：AUTO / HEURISTIC / AI_ONLY（不变）。
// ─────────────────────────────────────────────────────────────

@Composable
internal fun RoundtableSettingsSheet(
    allMotherMembers: List<CharacterConfig>,
    blockedMotherIds: Set<Int>,
    extraDaughters: List<CharacterConfig>,
    availableDaughters: List<CharacterConfig>,
    scheduleMode: ScheduleMode,
    isSpontaneousEnabled: Boolean,
    hasCustomBackground: Boolean,
    onToggleMother: (characterId: Int, blocked: Boolean) -> Unit,
    onAddDaughter: (Int) -> Unit,
    onModeChange: (ScheduleMode) -> Unit,
    onSpontaneousToggle: (Boolean) -> Unit,
    onSetBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val activeMotherCount = allMotherMembers.count { it.id !in blockedMotherIds }
    val extraDaughterIds  = extraDaughters.map { it.id }.toSet()
    // 候选区只展示"尚未拉入"的女儿，已拉入的在上面的"已拉入"区显示
    val pullableDaughters = availableDaughters.filter { it.id !in extraDaughterIds }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.screenHorizontal)
            .verticalScroll(rememberScrollState()),
    ) {
        // ── 标题栏 ──────────────────────────────────────────
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = "圆桌设置",
                style    = type.cardTitle,
                color    = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector        = Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint               = colors.textSecondary,
                    modifier           = Modifier.size(22.dp),
                )
            }
        }

        // ── A. 母角色区（屏蔽制） ────────────────────────────
        Text(
            text     = "在场母角色（$activeMotherCount/${allMotherMembers.size}）",
            style    = type.label,
            color    = colors.textSecondary,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )

        if (allMotherMembers.isEmpty()) {
            Text(
                text     = "暂无已解锁的母角色",
                style    = type.body,
                color    = colors.textDisabled,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
        } else {
            allMotherMembers.forEach { bot ->
                val blocked = bot.id in blockedMotherIds
                MemberSettingsRow(
                    bot        = bot,
                    action     = if (blocked) MemberAction.ADD else MemberAction.REMOVE,
                    actionTint = if (blocked) colors.accent else Palette.SemanticDanger,
                    onAction   = { onToggleMother(bot.id, !blocked) },
                    dimmed     = blocked,
                )
            }
        }

        // ── B. 女儿/第三代区 ──────────────────────────────────
        //   已拉入的女儿：跟 A 区母角色完全一致的勾选框屏蔽/取消屏蔽交互
        //   （复用 onToggleMother，state 保留在 blockedMotherIds 里，
        //   随时可勾回来，不会丢失头像/人设）。
        //   尚未拉入的候选：仍是"拉入"按钮，拉入动作本身不变。
        //   女儿不再提供"彻底移出圆桌"的入口——跟母角色一样，只能屏蔽。
        Spacer(Modifier.height(Spacing.lg))
        val activeDaughterCount = extraDaughters.count { it.id !in blockedMotherIds }
        Text(
            text     = "女儿（$activeDaughterCount/${extraDaughters.size} 在场，已拉入 ${extraDaughters.size} 位）",
            style    = type.label,
            color    = colors.textSecondary,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )

        if (extraDaughters.isEmpty() && pullableDaughters.isEmpty()) {
            Text(
                text     = "暂无可拉入的女儿角色",
                style    = type.body,
                color    = colors.textDisabled,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
        } else {
            extraDaughters.forEach { bot ->
                val blocked = bot.id in blockedMotherIds
                MemberSettingsRow(
                    bot        = bot,
                    action     = if (blocked) MemberAction.ADD else MemberAction.REMOVE,
                    actionTint = if (blocked) colors.accent else Palette.SemanticDanger,
                    onAction   = { onToggleMother(bot.id, !blocked) },
                    dimmed     = blocked,
                )
            }
            if (pullableDaughters.isNotEmpty()) {
                if (extraDaughters.isNotEmpty()) Spacer(Modifier.height(Spacing.xs))
                pullableDaughters.forEach { bot ->
                    MemberSettingsRow(
                        bot        = bot,
                        action     = MemberAction.ADD,
                        actionTint = colors.accent,
                        onAction   = { onAddDaughter(bot.id) },
                    )
                }
            }
        }

        // ── D. 自发互动 ─────────────────────────────────────
        Spacer(Modifier.height(Spacing.lg))
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "自发互动",
                    style = type.body,
                    color = colors.textPrimary,
                )
                Text(
                    text  = "30 秒无输入时，角色会主动开口",
                    style = type.label,
                    color = colors.textSecondary,
                )
            }
            Switch(
                checked         = isSpontaneousEnabled,
                onCheckedChange = onSpontaneousToggle,
            )
        }

        // ── E. 圆桌背景图 ───────────────────────────────────
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text     = "圆桌背景图",
            style    = type.label,
            color    = colors.textSecondary,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(colors.accent.copy(alpha = 0.12f))
                    .clickable(onClick = onSetBackground)
                    .padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text  = if (hasCustomBackground) "更换背景图" else "设置背景图",
                    style = type.body,
                    color = colors.accent,
                )
            }
            if (hasCustomBackground) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(Palette.SemanticDanger.copy(alpha = 0.10f))
                        .clickable(onClick = onClearBackground)
                        .padding(vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = "清除背景图",
                        style = type.body,
                        color = Palette.SemanticDanger,
                    )
                }
            }
        }

        // ── C. 调度模式 ────────────────────────────────────
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text     = "调度模式",
            style    = type.label,
            color    = colors.textSecondary,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )

        ScheduleModeOption(
            icon        = Icons.Outlined.AutoMode,
            title       = "自动",
            subtitle    = "短消息启发式 · 长消息 AI 调度",
            selected    = scheduleMode == ScheduleMode.AUTO,
            onClick     = { onModeChange(ScheduleMode.AUTO) },
        )
        ScheduleModeOption(
            icon        = Icons.Outlined.Speed,
            title       = "启发式",
            subtitle    = "基于规则调度，零 API 消耗",
            selected    = scheduleMode == ScheduleMode.HEURISTIC,
            onClick     = { onModeChange(ScheduleMode.HEURISTIC) },
        )
        ScheduleModeOption(
            icon        = Icons.Outlined.SmartToy,
            title       = "AI 调度",
            subtitle    = "每轮额外一次 API 调用，最自然",
            selected    = scheduleMode == ScheduleMode.AI_ONLY,
            onClick     = { onModeChange(ScheduleMode.AI_ONLY) },
        )

        Spacer(Modifier.height(Spacing.xl))
    }
}


private enum class MemberAction { ADD, REMOVE, LOCKED }


@Composable
private fun MemberSettingsRow(
    bot: CharacterConfig,
    action: MemberAction,
    actionTint: Color,
    onAction: () -> Unit,
    dimmed: Boolean = false,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val contentAlpha = if (dimmed) 0.5f else 1f

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 头像
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(bot.avatarUrl)
                .crossfade(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build(),
            contentDescription = bot.name,
            modifier           = Modifier
                .size(36.dp)
                .alpha(contentAlpha)
                .clip(CircleShape)
                .background(bot.accentColor.copy(alpha = 0.3f)),
            error              = rememberVectorPainter(Icons.Outlined.Person),
        )
        // 名字
        Text(
            text     = bot.name,
            style    = type.body,
            color    = colors.textPrimary.copy(alpha = contentAlpha),
            modifier = Modifier.weight(1f),
        )
        // 操作按钮
        // P3-33 修复（重做）：操作按钮视觉维持 32dp，外层 48dp 透明热区承载 clickable，
        // 参照问题5/问题14的模式——视觉尺寸与触摸目标分离，避免按钮色块比头像还大。
        Box(
            modifier = Modifier
                .size(48.dp)
                .wrapContentSize(Alignment.Center)
                .clickable(enabled = action != MemberAction.LOCKED) { onAction() },
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        when (action) {
                            MemberAction.ADD    -> actionTint.copy(alpha = 0.12f)
                            MemberAction.REMOVE -> actionTint.copy(alpha = 0.10f)
                            MemberAction.LOCKED -> Color.Transparent
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
            Icon(
                imageVector = when (action) {
                    MemberAction.ADD    -> Icons.Outlined.Add
                    MemberAction.REMOVE -> Icons.Outlined.Close
                    MemberAction.LOCKED -> Icons.Outlined.Check
                },
                contentDescription = action.name,
                tint               = actionTint,
                modifier           = Modifier.size(18.dp),
            )
            } // 闭合内层 Box 内容
        } // 闭合外层 Box 内容
    }
}


@Composable
private fun ScheduleModeOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (selected) colors.accent.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .border(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) colors.accent.copy(alpha = 0.5f) else colors.border,
                shape = RoundedCornerShape(Radius.sm),
            )
            .clickable { onClick() }
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (selected) colors.accent else colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = title,
                style = type.body,
                color = if (selected) colors.accent else colors.textPrimary,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text  = subtitle,
                style = type.label,
                color = colors.textSecondary,
            )
        }
        if (selected) {
            Icon(
                imageVector        = Icons.Outlined.Check,
                contentDescription = "已选择",
                tint               = colors.accent,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
    Spacer(Modifier.height(Spacing.xs))
}
