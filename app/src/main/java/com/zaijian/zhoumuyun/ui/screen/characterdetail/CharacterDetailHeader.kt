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

private fun floorGradientColors(floor: FloorEnum, isDark: Boolean): Pair<Color, Color> {
    return when (floor) {
        FloorEnum.SECOND -> if (isDark)
            Color(0xFF3A3424) to Color(0xFF2A2418)   // 暖白，亮度最高（暗色模式下仍是三档里最亮的一档）
        else
            Color(0xFFFFFBF3) to Color(0xFFFBF7F0)
        FloorEnum.FIRST -> if (isDark)
            Color(0xFF2E2818) to Color(0xFF221E14)   // 暖黄，亮度中等
        else
            Color(0xFFFAF2E2) to Color(0xFFF5F0E8)
        FloorEnum.BASEMENT -> if (isDark)
            Color(0xFF1A1C28) to Color(0xFF14141C)   // 冷蓝紫，亮度最低，更静更私密
        else
            Color(0xFFE8E6F2) to Color(0xFFE0DCEC)
    }
}

@Composable
internal fun DetailHeader(
    name: String,
    headerBg: Color,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    floor: FloorEnum? = null,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 楼层光氛围（精修方案 v1.3 第5.2节）：floor != null 时叠加一层楼层基调渐变，
    // 在原有毛玻璃纯色 headerBg 之上叠加，不替换——保留毛玻璃透明度行为，
    // 只是把"纯色"换成"带楼层冷暖倾向的渐变"。floor == null（角色数据异常兜底）
    // 时维持原有纯色 background，不强行画一个无意义的默认渐变。
    val floorGradientBrush = floor?.let { f ->
        val (start, end) = floorGradientColors(f, colors.isDark)
        Brush.verticalGradient(colors = listOf(start, end))
    }

    // P2-13 修复：楼层渐变此前被毛玻璃完全盖住，看不到任何效果。
    // 改为：先画 headerBg 基底，再叠加楼层渐变（低透明度），让渐变氛围可见。
    Box(
        modifier = modifier
            .background(headerBg)
            .then(
                if (floorGradientBrush != null)
                    Modifier.background(floorGradientBrush, alpha = 0.35f)
                else
                    Modifier
            )
            .border(
                width = 0.5.dp,
                color = colors.borderSubtle,
                shape = RoundedCornerShape(0.dp),
            )
            .statusBarsPadding()
            .height(Spacing.topBarHeight),
    ) {
        Row(
            modifier          = Modifier.fillMaxSize().padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint               = colors.textPrimary,
                    modifier           = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text  = name,
                style = type.navTitle,
                color = colors.textPrimary,
            )
        }
    }
}

@Composable
internal fun CharacterHeroCard(
    name: String,
    avatarUrl: String,
    breathColor: Color,
    accentColor: Color,
    statusText: String,
    statusType: StatusType,
    activityHint: String?,
    onStartChat: () -> Unit,
    onAvatarClick: () -> Unit = {},
    // v46 新增：长按头像重新调整"公馆拱形/书架椭圆"取景范围（不重新
    // 选图，复用已上传的原图）。默认空实现，兼容未接入此功能的调用点。
    onAvatarLongClick: () -> Unit = {},
    // v46 头像重新设计：详情页圆形头像的裁剪参数，对应
    // CharacterIdentityEntity.avatarCropCircle*。默认 0f/0f/1f 与旧行为
    // 一致（居中、Crop 覆盖）。
    avatarCropOffsetX: Float = 0f,
    avatarCropOffsetY: Float = 0f,
    avatarCropScale: Float = 1f,
    // ── 精修方案 v1.3 第5.3/5.4节：MoodCandle / BondRibbon ──
    // 第二步（Token + 组件单独造）阶段新增三个可选参数，默认值不影响其他调用点。
    // 第三步第一步：本函数的唯一真实调用点（CharacterDetailScreen 角色卡）已接入
    // 真实数据源（2.2 修复后为 PresenceViewModel.uiState.presenceMap 响应式订阅
    // + Room relationship_states 表的 stage），见 CharacterDetailScreen 顶部
    // cachedPresenceState / cachedMoodType / heroBondStage 三处。
    /** 心情类型，null 表示不显示 MoodCandle */
    moodType: com.zaijian.zhoumuyun.domain.MoodType? = null,
    /** 精力值 0-100，-1 或超出范围表示未知（与 PresenceState.energy 的 -1 约定一致） */
    energy: Int = -1,
    /** 关系阶段，null 表示不显示 BondRibbon 迷你版 */
    relationshipStage: com.zaijian.zhoumuyun.ui.design.BondStage? = null,
    // ── 精修方案 v1.3 第5.1/6节：「关联项目」WrapChipGroup ──
    /** 当前角色参与的活跃项目列表，空列表表示不显示这一区块 */
    relatedProjects: List<com.zaijian.zhoumuyun.data.db.entity.ProjectEntity> = emptyList(),
    /** 点击某个项目芯片时触发，传入被点击项目的 id（跳转项目详情页） */
    onProjectChipClick: (String) -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 头像区域：可点击，叠加相机图标
        // P3-14 修复：此前 contentAlignment = BottomEnd 导致相机图标 48dp
        // 热区与头像 combinedClickable 热区完全重叠，点击右下角区域时两处
        // 热区竞争。改为：头像居中，相机图标通过 align 定位到右下角，
        // 两层级分离，各自独立响应点击。
        Box(modifier = Modifier.wrapContentSize()) {
            Box(
                modifier = Modifier.combinedClickable(
                    onClick     = onAvatarClick,
                    onLongClick = onAvatarLongClick,
                ),
            ) {
                BreathingAvatar(
                    imageUrl     = avatarUrl,
                    breathColor  = breathColor,
                    statusType   = statusType,
                    size         = AvatarSize.detail,
                    ringWidth    = RingWidth.detail,
                    glowRadius   = 16.dp,
                    enableBreath = statusType != StatusType.OFFLINE,
                    cropOffsetX  = avatarCropOffsetX,
                    cropOffsetY  = avatarCropOffsetY,
                    cropScale    = avatarCropScale,
                )
            }
            // 相机编辑角标（右下角，独立热区，不与头像点击区重叠）
            // P3-14 修复（重做）：48dp 外层热区 + 26dp 圆形图标视觉，
            // 同时满足触摸目标 >= 48dp 和独立定位防重叠两个要求。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(48.dp)
                    .wrapContentSize(Alignment.Center)
                    .clickable(onClick = onAvatarClick),
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.CameraAlt,
                        contentDescription = "更换头像",
                        tint               = Color.White,
                        modifier           = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        Text(
            text  = name,
            style = type.titleBold,
            color = colors.textPrimary,
        )

        Spacer(Modifier.height(Spacing.xs))

        if (statusText.isNotEmpty()) {
            Text(
                text  = statusText,
                style = type.caption,
                color = colors.textSecondary,
            )
        }

        if (activityHint != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text  = activityHint,
                style = type.caption,
                color = accentColor,
            )
        }

        // MoodCandle / BondRibbon 迷你版：均为可选展示，仅当调用方传入对应参数时渲染。
        // moodType 为 null（PresenceEngine 缓存里该角色还没有任何 mood 记录，例如从未
        // 聊过天）或 relationshipStage 为 null（关系表里还没有该角色的行）时分别不显示，
        // 这是正常的"数据还没产生"状态，不是 bug。
        if (moodType != null) {
            Spacer(Modifier.height(Spacing.sm))
            com.zaijian.zhoumuyun.ui.design.MoodCandle(
                mood = moodType,
                energy = energy,
            )
        }
        if (relationshipStage != null) {
            Spacer(Modifier.height(Spacing.sm))
            com.zaijian.zhoumuyun.ui.design.BondRibbon(
                stage = relationshipStage,
                accentColor = accentColor,
                showLabels = false, // Hero 卡片用迷你版，仅刻度，不显示阶段文字标签（精修方案 v1.3 第5.4节）
            )
        }

        // 「关联项目」WrapChipGroup：展示当前角色参与的活跃项目（精修方案 v1.3 第5.1/6节）。
        // 色点固定取当前角色自己的 accentColor（产品侧确认的简化决策——不取项目 OWNER 的
        // accentColor，因为一个项目可能没有单一 OWNER，固定用角色自身颜色不依赖这个前提，
        // 也更直观：这一排芯片本来就是"挂在这个角色身上"的标签）。
        // ChipItem.selected 这里固定传 false：本场景是纯展示，不是筛选器，没有"选中"语义；
        // 选中态实心反色的视觉留给真正的筛选场景（如 GridTabBar 旁边的标签筛选）使用。
        if (relatedProjects.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            com.zaijian.zhoumuyun.ui.design.WrapChipGroup(
                chips = relatedProjects.map { project ->
                    com.zaijian.zhoumuyun.ui.design.ChipItem(
                        label = project.title,
                        selected = false,
                        ownerAccent = accentColor,
                    )
                },
                onClick = { index -> onProjectChipClick(relatedProjects[index].id) },
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
            )
        }

        Spacer(Modifier.height(Spacing.lg))

        // 「发起对话」全宽按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(accentColor)
                .clickable { onStartChat() }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = "发起对话",
                style = type.button,
                color = Color.White,
            )
        }
    }
}

private const val MAIN_TAB_COLUMNS = 4

@Composable
internal fun MainTabRow(
    selectedIndex: Int,
    accentColor: Color,
    showPregnancyTab: Boolean,
    onSelect: (Int, String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val tabs = buildList {
        addAll(listOf("记忆", "能力", "人设", "目标", "关系"))
        if (showPregnancyTab) add("孕育")
        // Stage C：全局日程视图（v47_stage8）新增的个人日程 Tab，
        // 紧邻「文件」之前；「文件」本身点击即跳转，不占用 mainTab 索引判断，
        // 因此在它前面插入新 Tab 不会影响既有 Tab 的索引语义。
        add("日程")
        add("文件")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        tabs.withIndex().chunked(MAIN_TAB_COLUMNS).forEach { rowEntries ->
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                rowEntries.forEach { (index, label) ->
                    val selected = selectedIndex == index
                    MainTabCell(
                        label       = label,
                        selected    = selected,
                        accentColor = accentColor,
                        colors      = colors,
                        type        = type,
                        modifier    = Modifier.weight(1f),
                        onClick     = { onSelect(index, label) },
                    )
                }
                // 末行格子数不足整列时，用占位 weight(1f) 补齐剩余列，
                // 让已有格子的宽度与上一行保持一致（不被拉宽铺满）。
                repeat(MAIN_TAB_COLUMNS - rowEntries.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    Spacer(Modifier.height(Spacing.md))
}

@Composable
private fun MainTabCell(
    label: String,
    selected: Boolean,
    accentColor: Color,
    colors: AppColors,
    type: AppTypography,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = if (selected) Elevation.card else 0.dp,
                shape     = RoundedCornerShape(Radius.sm),
            )
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (selected) {
                    // 选中态：用主题色的极淡底色替代普通卡片底色，
                    // 配合投影，比单纯加粗边框更有"被选中"的存在感。
                    accentColor.copy(alpha = if (colors.isDark) 0.16f else 0.10f)
                } else if (colors.isDark) {
                    colors.bgElevated
                } else {
                    colors.bgCard
                }
            )
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) accentColor else colors.border,
                shape = RoundedCornerShape(Radius.sm),
            )
            .clickable { onClick() }
            .padding(vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = type.button,
            // 选中态背景已改为 accentColor 的低透明度叠色（见上方 background 分支），
            // accentColor 文字直接叠在自己的淡色调上，对比度天然达标，无需再对照 bgElevated 验证。
            color = if (selected) accentColor else colors.textSecondary,
        )
    }
}

