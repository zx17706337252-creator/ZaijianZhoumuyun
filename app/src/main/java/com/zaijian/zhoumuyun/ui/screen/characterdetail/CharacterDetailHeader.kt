package com.zaijian.zhoumuyun.ui.screen.characterdetail


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.model.FloorEnum
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.theme.AppColors
import com.zaijian.zhoumuyun.ui.theme.AppTypography
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.Elevation
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.RingWidth
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import kotlinx.coroutines.flow.map
import com.zaijian.zhoumuyun.ui.design.AppIcons

internal fun floorGradientColors(floor: FloorEnum, isDark: Boolean): Pair<Color, Color> {
    // W12问题5修复：原本在此处硬编码 12 个 Color(0x...) 字面量，现改用 Palette 中的
    // 具名 token（FloorXxxDarkStart/End、FloorXxxLightStart/End），纯换值不改视觉。
    return when (floor) {
        FloorEnum.SECOND -> if (isDark)
            Palette.FloorSecondDarkStart to Palette.FloorSecondDarkEnd
        else
            Palette.FloorSecondLightStart to Palette.FloorSecondLightEnd
        FloorEnum.FIRST -> if (isDark)
            Palette.FloorFirstDarkStart to Palette.FloorFirstDarkEnd
        else
            Palette.FloorFirstLightStart to Palette.FloorFirstLightEnd
        FloorEnum.BASEMENT -> if (isDark)
            Palette.FloorBasementDarkStart to Palette.FloorBasementDarkEnd
        else
            Palette.FloorBasementLightStart to Palette.FloorBasementLightEnd
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
    // v56→v57 公馆/书架头像独立化：原 onAvatarLongClick（长按=仅重调取景，
    // 无任何界面提示的隐藏功能）已删除，不保留、不迁移。公馆/书架头像
    // 现在各自有独立的、完全可见的文字入口，见下方 onAvatarTallClick /
    // onAvatarShelfClick——点击即选图+裁剪一步到位，跟圆形头像操作习惯
    // 一致，不需要用户学习新交互，也不再有隐藏手势。
    /** 「公馆头像」入口点击：选图 → 裁剪 → 保存，只影响公馆 */
    onAvatarTallClick: () -> Unit = {},
    /** 「书架头像」入口点击：选图 → 裁剪 → 保存，只影响书架 */
    onAvatarShelfClick: () -> Unit = {},
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
        // P2 修复：新增方形背框衬托圆形头像。背框作为独立兄弟层放在头像下方
        // （而非包裹头像的父容器），避免 clip() 裁切 BreathingAvatar 呼吸光晕
        // （光晕通过 scale() 视觉溢出头像自身 80dp 边界，若背框裁切它会被切边）。
        // 背框比头像小 8dp（在头像边缘内侧留出一圈可见描边），outer Box 尺寸
        // 仍以头像 80dp 为准，相机角标定位不受影响，无需额外偏移。
        Box(modifier = Modifier.wrapContentSize()) {
            // 方形背框：略小于头像直径，圆角矩形贴在头像正下方，
            // 从头像圆形边缘外露出一圈方角，形成"方框衬圆像"的视觉效果。
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(AvatarSize.detail + 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(breathColor.copy(alpha = 0.08f))
                    .border(1.dp, breathColor.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
            )
            // P3-Fix-AvatarAlign: 外层 Box 用 wrapContentSize()，尺寸由最大的
            // 子项（毛玻璃方框，detail+16dp）撑开；此 Box 若不显式 align，
            // 默认贴 TopStart，导致头像相对方框偏左上（视觉上表现为头像与
            // 毛玻璃框不同心）。显式居中，与方框的 Alignment.Center 对齐。
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable(onClick = onAvatarClick),
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
                        imageVector        = AppIcons.CameraAlt,
                        contentDescription = "更换头像",
                        tint               = Color.White,
                        modifier           = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        // v56→v57 公馆/书架头像独立化：三个头像入口横排，操作模式完全一致
        // （点击 = 选图 → 裁剪，一步到位），跟原有圆形头像的操作习惯保持
        // 一致。三个都是完全可见、有文字提示的按钮，不再有任何隐藏手势——
        // 这也是删除旧长按功能的原因（旧功能没有任何界面提示，用户确认
        // 视为从未真正上线）。
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(
                text = "详情页头像",
                style = type.caption,
                color = accentColor,
                modifier = Modifier.clickable(onClick = onAvatarClick),
            )
            Text(
                text = "公馆头像",
                style = type.caption,
                color = accentColor,
                modifier = Modifier.clickable(onClick = onAvatarTallClick),
            )
            Text(
                text = "书架头像",
                style = type.caption,
                color = accentColor,
                modifier = Modifier.clickable(onClick = onAvatarShelfClick),
            )
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
        // UI 升级 v2.0（融合方案 §4.1 角色主按钮）：纯色 accentColor 平涂升级为
        // RolePrimaryButton——角色色渐变底 + 白字 + inset 顶高光 + 按压 0.97 下沉，
        // 本页唯一主行动（Primary Action），材质权重与书架预览弹窗的按钮分级一致。
        com.zaijian.zhoumuyun.ui.design.RolePrimaryButton(
            text = "发起对话",
            roleColor = accentColor,
            onClick = onStartChat,
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
        )
    }
}

private const val MAIN_TAB_COLUMNS = 4

/**
 * 「关系」Tab 在 [MainTabRow] 固定 tabs 列表（"记忆","能力","人设","目标","关系",...）
 * 中的索引。
 *
 * 技术债清理（见 CHANGES_S9_window01_notification_center.md 技术债第 3 条）：
 * 原先这个数字在 [MainTabRow] 的 tabs 列表顺序里隐式存在，同时又在
 * `AppNavigation.kt` 的 `AppRoute.CharacterDetail.createRoute(tab = 4)`
 * 调用处硬编码了一份字面量 `4`，两处必须手动保持一致——如果以后
 * tabs 列表顺序调整（比如"关系"挪到别的位置），只改 `MainTabRow` 会
 * 悄悄弄错通知中心的跳转目标，且编译不会报错。
 *
 * 现在两处都引用这一个常量：`MainTabRow` 的 tabs 列表顺序是唯一真相，
 * 这个常量必须跟着那份列表里"关系"的位置同步维护；`AppNavigation.kt`
 * 里不再写字面量，直接引用 [RELATION_TAB_INDEX]。
 */
internal const val RELATION_TAB_INDEX = 4

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
        // 索引 0-4："记忆","能力","人设","目标","关系"——RELATION_TAB_INDEX（=4）
        // 对应"关系"，与上面常量注释保持一致，调整这里的顺序时必须同步检查该常量。
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

