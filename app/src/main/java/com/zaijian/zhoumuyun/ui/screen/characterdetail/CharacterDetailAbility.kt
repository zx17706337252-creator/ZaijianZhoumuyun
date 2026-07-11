package com.zaijian.zhoumuyun.ui.screen.characterdetail


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.CharacterGoalEntity
import com.zaijian.zhoumuyun.data.db.entity.GoalHorizon
import com.zaijian.zhoumuyun.ui.viewmodel.GoalDraft
import com.zaijian.zhoumuyun.ui.viewmodel.GoalViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.IdentityViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryFilter
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryUiItem
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.PregnancyViewModel
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.data.model.isDaughterMother
import com.zaijian.zhoumuyun.ui.theme.GoldDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.FloorEnum
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.model.accentLight
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.AppColors
import com.zaijian.zhoumuyun.ui.theme.AppTypography
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.Elevation
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.RingWidth
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.util.ZLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.compose.material3.FilterChip

internal data class ToolItem(val icon: ImageVector, val label: String)

internal val toolItems = listOf(
    ToolItem(com.zaijian.zhoumuyun.ui.design.AppIcons.ToolSearch,      "搜索"),
    ToolItem(com.zaijian.zhoumuyun.ui.design.AppIcons.ToolDescription, "文件"),
    ToolItem(com.zaijian.zhoumuyun.ui.design.AppIcons.ToolCode,        "代码"),
    ToolItem(com.zaijian.zhoumuyun.ui.design.AppIcons.ToolTable,       "表格"),
    ToolItem(com.zaijian.zhoumuyun.ui.design.AppIcons.ToolEmail,       "邮件"),
)

internal val skillTags = listOf("写作", "逻辑推理", "情绪陪伴", "信息整理", "头脑风暴")

@Composable
internal fun AbilitySubTabRow(
    selectedIndex: Int,
    accentColor: Color,
    onSelect: (Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val tabs   = listOf("能力", "工具", "任务")

    ScrollableTabRow(
        selectedTabIndex  = selectedIndex,
        containerColor    = Color.Transparent,
        contentColor      = accentColor,
        edgePadding       = Spacing.screenHorizontal,
        indicator         = { tabPositions ->
            if (selectedIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    color    = accentColor,
                    height   = 2.dp,
                )
            }
        },
        divider = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.border)
            )
        },
    ) {
        tabs.forEachIndexed { index, label ->
            Tab(
                selected      = selectedIndex == index,
                onClick       = { onSelect(index) },
                text          = {
                    Text(
                        text  = label,
                        style = type.caption.copy(
                            fontWeight = if (selectedIndex == index) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = if (selectedIndex == index) accentColor else colors.textSecondary,
                    )
                },
                selectedContentColor   = accentColor,
                unselectedContentColor = colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun AbilityPanel(
    tags: List<String>,
    accentColor: Color,
    accentLight: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        Text(
            text  = "擅长领域",
            style = type.cardTitle,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(Spacing.sm))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement   = Arrangement.spacedBy(Spacing.sm),
        ) {
            tags.forEach { tag ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(accentLight)
                        .border(
                            width = 0.5.dp,
                            color = accentColor.copy(alpha = 0.30f),
                            shape = RoundedCornerShape(Radius.xs),
                        )
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                ) {
                    Text(
                        text  = tag,
                        style = type.caption,
                        color = accentColor,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ToolsPanel(
    tools: List<ToolItem>,
    accentLight: Color,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        Text(
            text  = "可用工具",
            style = type.cardTitle,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(Spacing.sm))

        // 固定 4 列布局
        val rows = tools.chunked(4)
        rows.forEach { rowTools ->
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                rowTools.forEach { tool ->
                    Column(
                        modifier            = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        com.zaijian.zhoumuyun.ui.design.IconBadge(
                            icon               = tool.icon,
                            contentDescription = tool.label,
                            tint               = accentColor,
                            background         = accentLight,
                            size               = 22.dp,
                            badgeSize          = 48.dp,
                            modifier           = Modifier.clickable { /* 工具能力展示，对话中按需触发 */ },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text  = tool.label,
                            style = type.label,
                            color = colors.textSecondary,
                        )
                    }
                }
                // 补空列保持对齐
                repeat(4 - rowTools.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}

@Composable
internal fun AddButton(
    label: String,
    accentColor: Color,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val type = ZaijianTheme.typography

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(accentColor)
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector        = Icons.Outlined.Add,
                contentDescription = label,
                tint               = Color.White,
                modifier           = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text  = label,
                style = type.button,
                color = Color.White,
            )
        }
    }
}

@Composable
internal fun EmptyState(text: String, modifier: Modifier = Modifier) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(text = text, style = type.caption, color = colors.textDisabled)
    }
}

