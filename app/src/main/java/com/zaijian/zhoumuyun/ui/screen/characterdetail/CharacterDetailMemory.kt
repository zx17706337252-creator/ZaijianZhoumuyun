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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TableChart
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
import androidx.compose.material3.FilterChipDefaults

private data class MemoryEntry(
    val id: String,
    val content: String,
    val dateLabel: String,
    val isImportant: Boolean = false,
    val isCore: Boolean = false,
    /** true = 关于用户（PERSONAL domain），false = 关于角色/世界 */
    val aboutSelf: Boolean = true,
    /** Phase 17：衰减状态标签，null = 无需显示 */
    val decayLabel: String? = null,
    /** Phase 30 方案三：维度标签 */
    val domainLabel: String = "",
    /** Phase 30 方案三：维度色条颜色 (ARGB Long) */
    val domainColorArgb: Long = 0xFF9E9E9EL,
)

private fun MemoryUiItem.toEntry() = MemoryEntry(
    id              = id,
    content         = content,
    dateLabel       = dateLabel,
    isImportant     = isImportant,
    isCore          = isCore,
    aboutSelf       = aboutSelf,
    decayLabel      = decayLabel,
    domainLabel     = domainLabel,
    domainColorArgb = domainColorArgb,
)

@Composable
internal fun MemoryTabContent(
    memoryViewModel:      MemoryViewModel,
    accentColor:          Color,
    memoryDimTab:         Int,
    memorySecondaryChip:  Int,
    onDimTabChange:       (Int) -> Unit,
    onSecondaryChipChange:(Int) -> Unit,
    onShowAddDialog:      () -> Unit,
    onEditMemory:         (String, String) -> Unit,
) {
    // ★ collectAsState 在此处执行，仅当 mainTab == 0 时该 Composable 存在
    val memoryState by memoryViewModel.uiState.collectAsStateWithLifecycle()

    Column {
        // Phase 30 方案三：两层过滤结构
        // 第一层（主维度 Tab）：全部 | 工作 | 情感
        MemoryDimTabRow(
            selectedIndex = memoryDimTab,
            accentColor   = accentColor,
            onSelect      = onDimTabChange,
        )
        // 第二层（次维度 FilterChip）：重要
        MemorySecondaryChips(
            dimIndex    = memoryDimTab,
            chipIndex   = memorySecondaryChip,
            accentColor = accentColor,
            onSelect    = onSecondaryChipChange,
        )
        Spacer(Modifier.height(Spacing.sm))

        if (memoryState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xxl),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color       = accentColor,
                    strokeWidth = 2.dp,
                    modifier    = Modifier.size(24.dp),
                )
            }
        } else {
            val entries = memoryState.items.map { it.toEntry() }

            if (entries.isEmpty()) {
                EmptyState(
                    text     = "还没有记忆，去聊聊吧",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xxl),
                )
            } else {
                entries.forEach { entry ->
                    MemoryRow(
                        entry        = entry,
                        accentColor  = accentColor,
                        onToggleStar = { memoryViewModel.toggleImportant(entry.id) },
                        onDelete     = { memoryViewModel.delete(entry.id) },
                        onEdit       = { onEditMemory(entry.id, entry.content) },
                        modifier     = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.screenHorizontal),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            AddButton(
                label       = "新增记忆",
                accentColor = accentColor,
                onClick     = onShowAddDialog,
                modifier    = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
            )
        }
    }
}

@Composable
internal fun AddMemoryDialog(
    accentColor: Color,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    var text by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = colors.bgCard,
        title = {
            Text(
                text  = "新增记忆",
                style = type.cardTitle,
                color = colors.textPrimary,
            )
        },
        text = {
            Column {
                Text(
                    text  = "手动记录一件重要的事，它会作为长期记忆保留。",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.sm))
                androidx.compose.material3.OutlinedTextField(
                    value         = text,
                    onValueChange = { text = it },
                    placeholder   = {
                        Text(
                            text  = "例：喜欢喝桂花乌龙，不吃辣",
                            style = type.body,
                            color = colors.textDisabled,
                        )
                    },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = accentColor,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor     = colors.textPrimary,
                        unfocusedTextColor   = colors.textPrimary,
                        cursorColor          = accentColor,
                    ),
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(
                    text  = "保存",
                    color = if (text.isNotBlank()) accentColor else colors.textDisabled,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.textSecondary)
            }
        },
    )
}

@Composable
internal fun EditMemoryDialog(
    initialContent: String,
    accentColor: Color,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var text by remember { mutableStateOf(initialContent) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = colors.bgCard,
        title = {
            Text(
                text  = "编辑记忆",
                style = type.cardTitle,
                color = colors.textPrimary,
            )
        },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value         = text,
                onValueChange = { text = it },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = accentColor,
                    unfocusedBorderColor = colors.border,
                    focusedTextColor     = colors.textPrimary,
                    unfocusedTextColor   = colors.textPrimary,
                    cursorColor          = accentColor,
                ),
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(
                    text  = "保存",
                    color = if (text.isNotBlank()) accentColor else colors.textDisabled,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.textSecondary)
            }
        },
    )
}

@Composable
private fun MemoryDimTabRow(
    selectedIndex: Int,
    accentColor: Color,
    onSelect: (Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    // 主维度：全部 / 工作 / 情感
    val tabs = listOf("全部", "工作", "情感")

    ScrollableTabRow(
        selectedTabIndex  = selectedIndex,
        containerColor    = Color.Transparent,
        contentColor      = accentColor,
        edgePadding       = Spacing.screenHorizontal,
        indicator         = { tabPositions ->
            if (selectedIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    color    = when (selectedIndex) {
                        1    -> Palette.Focused   // 工作蓝
                        2    -> Palette.SemanticEmotion   // 情感粉
                        else -> accentColor
                    },
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
            val tabAccent = when (index) {
                1    -> Palette.Focused
                2    -> Palette.SemanticEmotion
                else -> accentColor
            }
            Tab(
                selected = selectedIndex == index,
                onClick  = { onSelect(index) },
                text     = {
                    Text(
                        text  = label,
                        style = type.caption.copy(
                            fontWeight = if (selectedIndex == index) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = if (selectedIndex == index) tabAccent else colors.textSecondary,
                    )
                },
                selectedContentColor   = tabAccent,
                unselectedContentColor = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun MemorySecondaryChips(
    dimIndex:    Int,
    chipIndex:   Int,
    accentColor: Color,
    onSelect:    (Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 根据主维度决定次维度 Chip 组。
    // 待处理报告 9-6 关联修复：MemoryFilter.ABOUT_WORLD（domain != PERSONAL，
    // 涵盖 WORK/WORLD/RULE/INFERENCE）此前定义了但从未接入 UI。只在"全部"
    // 维度（dimIndex==0）下暴露"关于世界"chip——"工作"/"情感"两个维度已经是
    // domain 的精确子集，再叠加"关于世界"会与其语义冲突或恒为空。
    // 不加"关于我"：ABOUT_ME 的查询条件（domain==PERSONAL）与"情感"维度
    // （EMOTION，domain==PERSONAL）完全重复，会做出一个内容和现有"情感"tab
    // 一模一样的多余按钮。
    val chips = if (dimIndex == 0) listOf("重要", "关于世界") else listOf("重要")

    // P3-46 修复：当前只有"重要"一个 Chip，FlowRow（自动换行布局）
    // 在此场景下是多余开销，改为普通 Row +水平间距即可。
    Row(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEachIndexed { index, label ->
            val chipAccent = when (dimIndex) {
                1    -> Palette.Focused
                2    -> Palette.SemanticEmotion
                else -> accentColor
            }
            val selected = chipIndex == index + 1
            FilterChip(
                selected = selected,
                onClick  = { onSelect(if (selected) 0 else index + 1) },
                label    = {
                    Text(
                        text  = label,
                        style = type.label,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor     = chipAccent.copy(alpha = 0.15f),
                    selectedLabelColor         = chipAccent,
                    containerColor             = Color.Transparent,
                    labelColor                 = colors.textSecondary,
                    selectedLeadingIconColor   = chipAccent,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled         = true,
                    selected        = selected,
                    borderColor     = colors.border,
                    selectedBorderColor = chipAccent.copy(alpha = 0.5f),
                    borderWidth     = 0.5.dp,
                    selectedBorderWidth = 1.dp,
                ),
            )
        }
    }
}

@Composable
private fun MemoryRow(
    entry: MemoryEntry,
    accentColor: Color,
    onToggleStar: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // Phase 30 方案三：左侧 2dp 维度色条
    val stripColor = if (entry.domainLabel.isNotEmpty())
        Color(entry.domainColorArgb) else Color.Transparent

    // WorldCard 接入（精修方案 v1.3）：独立列表项，L0-L2 常态层交给
    // WorldCard。不传 ownerAccent——整页本就是单一角色的记忆列表，"归属
    // 谁"已经是页面级的已知信息；这里真正需要逐条区分的是"记忆维度"
    // （Phase 30 既有的左侧 stripColor 色条），保留它不动，避免和 L3
    // 身份脊在左侧出现两条并排竖线、互相抢视觉。
    WorldCard(modifier = modifier, cornerRadius = Radius.sm) {
        Row(verticalAlignment = Alignment.Top) {
            // 左侧维度色条
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        color = stripColor,
                        shape = RoundedCornerShape(topStart = Radius.sm, bottomStart = Radius.sm),
                    ),
            )
            // 右侧内容区（加回 padding）
            Row(
                modifier          = Modifier.weight(1f).padding(Spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
            // 内容（占满剩余宽度）
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = entry.content,
                    style = type.body,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = entry.dateLabel,
                        style = type.label,
                        color = colors.textDisabled,
                    )
                    // Phase 17：衰减状态标签
                    entry.decayLabel?.let { label ->
                        val (bgAlpha, textColor) = when (label) {
                            "7天到期"  -> 0.15f to Palette.SemanticDanger
                            "即将到期"  -> 0.12f to Palette.SemanticWarning
                            "即将清理" -> 0.12f to Palette.SemanticWarning
                            else       -> 0.10f to colors.textSecondary
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(textColor.copy(alpha = bgAlpha))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(label, style = type.label, color = textColor)
                        }
                    }
                }
            }

            Spacer(Modifier.width(Spacing.sm))

            // UI M12 修复：图标触摸热区扩大到 48dp×48dp（Android 最小触控建议），
            // 视觉尺寸（20dp）保持不变，外层 Box 吸收额外点击区域。
            // 编辑
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { onEdit() }
                    .wrapContentSize(Alignment.Center),
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Edit,
                    contentDescription = "编辑记忆",
                    tint               = colors.textDisabled,
                    modifier           = Modifier.size(20.dp),
                )
            }
            // 删除
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { onDelete() }
                    .wrapContentSize(Alignment.Center),
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Delete,
                    contentDescription = "删除记忆",
                    tint               = colors.textDisabled,
                    modifier           = Modifier.size(20.dp),
                )
            }
            // 星标
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { onToggleStar() }
                    .wrapContentSize(Alignment.Center),
            ) {
                Icon(
                    imageVector        = if (entry.isImportant) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (entry.isImportant) "取消重要" else "标记重要",
                    tint               = if (entry.isImportant) accentColor else colors.textDisabled,
                    modifier           = Modifier.size(20.dp),
                )
            }
            } // end inner Row (content+star)
        } // end outer Row (strip+content)
    }
}

