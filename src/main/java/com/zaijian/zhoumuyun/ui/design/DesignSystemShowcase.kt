package com.zaijian.zhoumuyun.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.engine.MoodType
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.FloorEnum
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  Design System Showcase
//  Opens as a standalone screen during Phase 1 development.
//  Remove or hide behind a debug flag in production.
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignSystemShowcase(modifier: Modifier = Modifier) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screenHorizontal)
    ) {
        Spacer(Modifier.height(Spacing.lg))

        // ── Section: Colors ───────────────────────────────────
        SectionHeader("Color Tokens")

        val swatches = listOf(
            "bgBase"        to colors.bgBase,
            "bgCard"        to colors.bgCard,
            "bgElevated"    to colors.bgElevated,
            "border"        to colors.border,
            "textPrimary"   to colors.textPrimary,
            "textSecondary" to colors.textSecondary,
            "textDisabled"  to colors.textDisabled,
            "accent"        to colors.accent,
            "accentSoft"    to colors.accentSoft,
            "statusActive"  to colors.statusActive,
            "statusIdle"    to colors.statusIdle,
            "statusFocused" to colors.statusFocused,
            "statusOffline" to colors.statusOffline,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement   = Arrangement.spacedBy(Spacing.sm),
        ) {
            swatches.forEach { (label, color) ->
                ColorSwatch(label, color, colors.textPrimary, colors.border)
            }
        }

        SectionDivider()

        // ── Section: Character Accents ────────────────────────
        SectionHeader("Character Accent Colors")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement   = Arrangement.spacedBy(Spacing.sm),
        ) {
            DefaultCharacters.forEach { char ->
                ColorSwatch(char.name, char.accentColor, Color.White, Color.Transparent)
            }
        }

        SectionDivider()

        // ── Section: Typography ───────────────────────────────
        SectionHeader("Typography")
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("titleBold · 20sp Bold",  style = type.titleBold,  color = colors.textPrimary)
            Text("cardTitle · 16sp Medium", style = type.cardTitle, color = colors.textPrimary)
            Text("navTitle · 17sp Bold",   style = type.navTitle,   color = colors.textPrimary)
            Text("body · 14sp Regular",    style = type.body,       color = colors.textPrimary)
            Text("caption · 13sp Regular", style = type.caption,    color = colors.textSecondary)
            Text("label · 11sp Regular",   style = type.label,      color = colors.textDisabled)
            Text("button · 14sp Medium",   style = type.button,     color = colors.accent)
            Text("presence · 13sp · 正在想你", style = type.presence, color = colors.textSecondary)
        }

        SectionDivider()

        // ── Section: Radius ───────────────────────────────────
        SectionHeader("Corner Radius")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(
                "xs·6" to Radius.xs,
                "sm·12" to Radius.sm,
                "md·20" to Radius.md,
                "lg·28" to Radius.lg,
            ).forEach { (label, r) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(r))
                            .background(colors.accent.copy(alpha = 0.2f))
                            .border(1.dp, colors.accent, RoundedCornerShape(r))
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = type.label, color = colors.textSecondary)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.2f))
                        .border(1.dp, colors.accent, CircleShape)
                )
                Spacer(Modifier.height(4.dp))
                Text("circle", style = type.label, color = colors.textSecondary)
            }
        }

        SectionDivider()

        // ── Section: Avatar sizes ─────────────────────────────
        SectionHeader("Avatar Sizes")
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            listOf(
                "detail·80" to AvatarSize.detail,
                "mansion·52" to AvatarSize.mansion,
                "shelf·44" to AvatarSize.shelf,
                "chat·32" to AvatarSize.chat,
                "small·24" to AvatarSize.small,
            ).forEach { (label, size) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(CircleShape)
                            .background(colors.accent)
                    ) {
                        Text(
                            text  = size.value.toInt().toString(),
                            style = ZaijianTheme.typography.label,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = type.label, color = colors.textSecondary)
                }
            }
        }

        SectionDivider()

        // ── Section: Status dots ──────────────────────────────
        SectionHeader("Status Colors")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            listOf(
                "活跃"  to colors.statusActive,
                "空闲"  to colors.statusIdle,
                "专注"  to colors.statusFocused,
                "离线"  to colors.statusOffline,
            ).forEach { (label, color) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = type.label, color = colors.textSecondary)
                }
            }
        }

        SectionDivider()

        // ── Section: WorldOS Components (精修方案 v1.3 第6节，第二步验证) ──
        SectionHeader("WorldOS · WorldCard")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            WorldCard(modifier = Modifier.size(110.dp, 90.dp)) {
                Box(modifier = Modifier.padding(Spacing.sm)) {
                    Text("L0+L1+L2\n（默认）", style = type.caption, color = colors.textPrimary)
                }
            }
            WorldCard(
                modifier = Modifier.size(110.dp, 90.dp),
                ownerAccent = DefaultCharacters[0].accentColor,
            ) {
                Box(modifier = Modifier.padding(Spacing.sm)) {
                    Text("+L3 身份脊\n（${DefaultCharacters[0].name}）", style = type.caption, color = colors.textPrimary)
                }
            }
            WorldCard(
                modifier = Modifier.size(110.dp, 90.dp),
                isMilestone = true,
            ) {
                Box(modifier = Modifier.padding(Spacing.sm)) {
                    Text("+L4 蜡封角标\n（重要/核心）", style = type.caption, color = colors.textPrimary)
                }
            }
        }

        SectionDivider()

        SectionHeader("WorldOS · AdaptiveAvatarRow")
        run {
            val avatarItems = DefaultCharacters.map { c ->
                AvatarRowItem(
                    id = c.id,
                    avatarUrl = c.avatarUrl,
                    accentColor = c.accentColor,
                    floor = when (c.floor) {
                        FloorEnum.SECOND -> AvatarRowFloor.SECOND
                        FloorEnum.FIRST -> AvatarRowFloor.FIRST
                        FloorEnum.BASEMENT -> AvatarRowFloor.BASEMENT
                    },
                )
            }
            var selectedAvatarId by remember {
                mutableStateOf<Int?>(DefaultCharacters.first().id)
            }
            AdaptiveAvatarRow(
                items = avatarItems,
                selectedId = selectedAvatarId,
                onSelect = { selectedAvatarId = it },
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "九人 3-3-3 分组（二楼/一楼/地下室），点击切换选中态描边色",
                style = type.label,
                color = colors.textDisabled,
            )
        }

        SectionDivider()

        SectionHeader("WorldOS · WrapChipGroup")
        run {
            var chipStates by remember {
                mutableStateOf(
                    listOf(
                        ChipItem("全部", selected = true),
                        ChipItem("二楼", selected = false, ownerAccent = DefaultCharacters[0].accentColor),
                        ChipItem("一楼", selected = false, ownerAccent = DefaultCharacters[3].accentColor),
                        ChipItem("地下室", selected = false, ownerAccent = DefaultCharacters[6].accentColor),
                        ChipItem("最近活跃", selected = false),
                        ChipItem("重要关系", selected = false),
                    )
                )
            }
            WrapChipGroup(
                chips = chipStates,
                onClick = { index ->
                    chipStates = chipStates.mapIndexed { i, c ->
                        if (i == index) c.copy(selected = !c.selected) else c
                    }
                },
            )
        }

        SectionDivider()

        SectionHeader("WorldOS · GridTabBar")
        run {
            var selectedTab4 by remember { mutableStateOf(0) }
            GridTabBar(
                items = listOf(
                    GridTabItem("记忆", count = 12),
                    GridTabItem("能力"),
                    GridTabItem("关系", count = 9),
                    GridTabItem("日程", count = 3),
                ),
                selectedIndex = selectedTab4,
                onSelect = { selectedTab4 = it },
            )
            Spacer(Modifier.height(Spacing.md))
            Text("≤4 Tab · 单行等分 + 下划线滑动", style = type.label, color = colors.textDisabled)

            Spacer(Modifier.height(Spacing.md))
            var selectedTab6 by remember { mutableStateOf(0) }
            GridTabBar(
                items = listOf(
                    GridTabItem("记忆"), GridTabItem("能力"), GridTabItem("人设"),
                    GridTabItem("目标"), GridTabItem("关系"), GridTabItem("日程"),
                ),
                selectedIndex = selectedTab6,
                onSelect = { selectedTab6 = it },
            )
            Spacer(Modifier.height(Spacing.xs))
            Text("5+ Tab · 自动换行（无下划线动画，见组件实现说明）", style = type.label, color = colors.textDisabled)
        }

        SectionDivider()

        SectionHeader("WorldOS · BondRibbon")
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            BondStage.entries.forEach { stage ->
                BondRibbon(
                    stage = stage,
                    accentColor = DefaultCharacters[0].accentColor,
                    suppression = when (stage) {
                        BondStage.STRANGER -> 20
                        BondStage.CORE -> 80
                        else -> 50
                    },
                )
            }
        }

        SectionDivider()

        SectionHeader("WorldOS · MoodCandle")
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MoodType.entries.forEach { mood ->
                MoodCandle(mood = mood, energy = 70)
            }
            Spacer(Modifier.height(Spacing.xs))
            Text("energy = -1（未知态，灰色低位静态）：", style = type.label, color = colors.textDisabled)
            MoodCandle(mood = MoodType.CALM, energy = -1)
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

// ─────────────────────────────────────────────────────────────
//  Sub-components used only in this showcase
// ─────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = ZaijianTheme.typography.cardTitle,
        color    = ZaijianTheme.colors.textPrimary,
        modifier = Modifier.padding(bottom = Spacing.sm),
    )
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(Spacing.lg))
    HorizontalDivider(color = ZaijianTheme.colors.border)
    Spacer(Modifier.height(Spacing.lg))
}

@Composable
private fun ColorSwatch(
    label: String,
    color: Color,
    textColor: Color,
    borderColor: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(Radius.xs))
                .background(color)
                .border(1.dp, borderColor, RoundedCornerShape(Radius.xs))
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text     = label,
            style    = ZaijianTheme.typography.label,
            color    = textColor.copy(alpha = 0.7f),
            modifier = Modifier.width(56.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  Previews
// ─────────────────────────────────────────────────────────────

@Preview(name = "Design System · Light", showBackground = true, widthDp = 390)
@Composable
private fun PreviewLight() {
    ZaijianTheme {
        DesignSystemShowcase()
    }
}

@Preview(name = "Design System · Dark", showBackground = true,
    backgroundColor = 0xFF12131A, widthDp = 390)
@Composable
private fun PreviewDark() {
    ZaijianTheme(appTheme = com.zaijian.zhoumuyun.ui.theme.AppTheme.DARK) {
        DesignSystemShowcase()
    }
}
