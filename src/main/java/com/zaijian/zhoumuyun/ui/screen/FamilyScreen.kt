package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.FamilyListUiState
import com.zaijian.zhoumuyun.ui.viewmodel.FamilyListViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.FamilyMember
import com.zaijian.zhoumuyun.ui.viewmodel.PresenceViewModel

// ─────────────────────────────────────────────────────────────
//  FamilyScreen — 书架家族页
//
//  导航链路：
//    CharacterScreen（书架格子点击）
//      └── FamilyScreen(motherId)      ← 本页
//            └── 点击某一行
//                  └── CharacterDetailScreen(characterId)
//
//  布局：
//    顶部：返回按钮 + 标题（家族关系/母亲名字）
//    内容：竖排富卡片列表
//      每项：56dp BreathingAvatar + 在线状态点 + 名字 + 简介/心情标签
//      母亲排第一，后代依次排在下面
//      代际用边框颜色区分（同 FamilyPickerSheet 颜色算法）
//    点击任意一行 → 进入 CharacterDetailScreen
//
//  代际边框颜色算法（复用 FamilyPickerSheet 同款）：
//    一代（母亲）：accentColor 原色，边框稍粗（1.5dp）
//    二代：lerp(motherAccentColor, White, 0.35f)
//    三代：lerp(motherAccentColor, White, 0.60f)
// ─────────────────────────────────────────────────────────────

private const val GEN2_FRACTION = 0.35f
private const val GEN3_FRACTION = 0.60f

private fun borderColor(motherAccent: Color, generation: Int): Color =
    when (generation) {
        1    -> motherAccent
        2    -> lerp(motherAccent, Color.White, GEN2_FRACTION)
        else -> lerp(motherAccent, Color.White, GEN3_FRACTION)
    }

@Composable
fun FamilyScreen(
    motherId: Int,
    onBack: () -> Unit = {},
    onNavigateToDetail: (characterId: Int) -> Unit = {},
    familyViewModel: FamilyListViewModel = viewModel(),
    presenceViewModel: PresenceViewModel = viewModel(),
) {
    val colors   = ZaijianTheme.colors
    val type     = ZaijianTheme.typography
    val uiState  by familyViewModel.uiState.collectAsStateWithLifecycle()
    val pState   by presenceViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(motherId) {
        familyViewModel.loadFamily(motherId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // ── 顶部 Bar ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(Spacing.topBarHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            IconButton(
                onClick  = onBack,
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(
                    imageVector        = Icons.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint               = colors.textPrimary,
                )
            }
            // 标题居中
            val titleText = when (val s = uiState) {
                is FamilyListUiState.Ready ->
                    s.members.firstOrNull { it.generation == 1 }?.config?.name?.let { "$it 的家族" }
                        ?: "家族"
                else -> "家族"
            }
            Text(
                text     = titleText,
                style    = type.titleBold,
                color    = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // ── 内容区 ───────────────────────────────────────────
        when (val state = uiState) {
            is FamilyListUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }

            is FamilyListUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = state.message,
                        style = type.body,
                        color = colors.textSecondary,
                    )
                }
            }

            is FamilyListUiState.Ready -> {
                val motherAccent = state.members
                    .firstOrNull { it.generation == 1 }
                    ?.config?.accentColor
                    ?: colors.accent

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = Spacing.screenHorizontal,
                        vertical   = Spacing.md,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.members, key = { it.config.id }) { member ->
                        val presence = pState.presenceMap[member.config.id]
                            ?: DefaultPresenceStates.find { it.characterId == member.config.id }
                            ?: PresenceState(
                                characterId = member.config.id,
                                statusText  = "—",
                                statusType  = StatusType.OFFLINE,
                                lastUpdated = 0L,
                            )
                        FamilyMemberCard(
                            member       = member,
                            presence     = presence,
                            motherAccent = motherAccent,
                            onClick      = { onNavigateToDetail(member.config.id) },
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  FamilyMemberCard — 每行富卡片
// ─────────────────────────────────────────────────────────────

@Composable
private fun FamilyMemberCard(
    member: FamilyMember,
    presence: PresenceState,
    motherAccent: Color,
    onClick: () -> Unit,
) {
    val colors      = ZaijianTheme.colors
    val type        = ZaijianTheme.typography
    val border      = borderColor(motherAccent, member.generation)
    val borderWidth = if (member.generation == 1) 1.5.dp else 1.dp

    // WorldCard 接入（精修方案 v1.3 第2/6节）：L0-L2 常态层 + L3 身份脊
    // （ownerAccent 取该成员自己的 accentColor，不是 motherAccent——每一行
    // 卡片代表的是这一位具体角色，不是母亲）。现有代际描边（外圈完整边框，
    // 颜色随代数渐淡）承载的是"第几代"这个独立语义，与 L3 身份脊（左侧
    // 2dp 细线，表达"归属哪位角色"）是两个不同维度的信息，保留共存、
    // 不互相替换，因此原 .border(borderWidth, border, ...) 不删除，
    // 叠加在 WorldCard 外层。
    WorldCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(borderWidth, border, RoundedCornerShape(14.dp)),
        ownerAccent = member.config.accentColor,
        cornerRadius = 14.dp,
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 56dp 真实头像 ────────────────────────────────────
        BreathingAvatar(
            imageUrl     = member.config.avatarUrl,
            breathColor  = member.config.accentColor,
            statusType   = presence.statusType,
            size         = 56.dp,
            enableBreath = presence.statusType == StatusType.ACTIVE,
        )

        Spacer(modifier = Modifier.width(14.dp))

        // ── 文字信息区 ───────────────────────────────────────
        Column(
            modifier            = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment      = Alignment.CenterVertically,
                horizontalArrangement  = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text     = member.config.name,
                    style    = type.bodyBold,
                    color    = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 代数角标（仅后代显示）
                if (member.generation > 1) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(border.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text  = if (member.generation == 2) "女儿" else "孙女",
                            style = type.label,
                            color = border,
                        )
                    }
                }
            }

            // 状态文案：拆分为独立行，避免拼接后 maxLines=1 截断
            val showStatus = presence.statusText.isNotEmpty() && presence.statusText != "—"
            val showMood   = presence.moodLabel.isNotEmpty()
            if (showStatus) {
                Text(
                    text     = presence.statusText,
                    style    = type.label,
                    color    = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showMood) {
                Text(
                    text     = presence.moodLabel,
                    style    = type.label,
                    color    = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!showStatus && !showMood) {
                Text(
                    text     = "—",
                    style    = type.label,
                    color    = colors.textSecondary,
                )
            }
        }

        // ── 在线状态点（右侧） ────────────────────────────────
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when (presence.statusType) {
                        StatusType.ACTIVE  -> Palette.Online   // 与 StatusTypeExt.dotColor() 保持一致
                        StatusType.FOCUSED -> Palette.Focused
                        StatusType.IDLE    -> Palette.Idle
                        StatusType.OFFLINE -> Palette.Offline
                    }
                ),
        )
    }
    }
}
