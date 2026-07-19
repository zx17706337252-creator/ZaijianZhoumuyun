package com.zaijian.zhoumuyun.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.domain.MoodType
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.theme.appSpring

// ─────────────────────────────────────────────────────────────
//  WorldOSComponents.kt
//
//  精修方案 v1.3 第6节定义的六个核心组件：
//    WorldCard / AdaptiveAvatarRow / WrapChipGroup / GridTabBar /
//    BondRibbon（新增）/ MoodCandle（新增）
//
//  【接入状态 —— W12 复查更新，取代下方已过时的"仍只在 Showcase 里验证"表述】
//  - WorldCard：已大面积接入（ui/screen 下 20+ 个文件调用，含 chat/、
//    characterdetail/、briefing/、Profile*、CompetitionScreen、
//    TaskCenterScreen 等），是接入最广的组件。
//  - GridTabBar：已接入 TaskCenterScreen.kt。
//  - WrapChipGroup：已接入 CharacterDetailHeader.kt（"关联项目"标签组）。
//  - AdaptiveAvatarRow：已接入 GlobalScheduleScreen.kt。
//  - BondRibbon / MoodCandle：已接入 CharacterDetailHeader.kt；
//    BondRibbon 另接入 briefing/BriefingCharacterCard.kt。
//  - WorldBubble：W12问题1修复已接入 ChatMessageBubble.kt 和 RoundtableBubble.kt
//    的角色气泡（详见下方 WorldBubble 定义处的说明）。用户气泡不接入，保留原有
//    accentColor 纯色填充的视觉区分设计。
//
//  【第三步第一步 —— 已完成】
//  MoodCandle / BondRibbon 已接入 CharacterDetailScreen.kt 的私有函数
//  CharacterHeroCard()：moodType/energy 读取 PresenceEngine 内存缓存
//  （getCachedPresence(characterId)?.mood/.energy），relationshipStage
//  读取 Room relationship_states 表的 stage 字段（用户→该角色一行）。
//  具体接线见 CharacterDetailScreen.kt 顶部 cachedPresence / heroBondStage
//  两处 remember 块，以及 CharacterHeroCard 调用点。
// ─────────────────────────────────────────────────────────────


// ═════════════════════════════════════════════════════════════
//  WorldCard — 五层叠加模型（精修方案 v1.3 第2节）
// ═════════════════════════════════════════════════════════════

/**
 * WorldCard：所有卡片的共同底座，五层可独立开关的叠加效果，外加阴影。
 *
 *   L0 纸面底   —— 常态存在，bgElevated→bgCard 渐变
 *   L1 光斑     —— 常态存在，左上角径向光晕/天光，light/dark 强度不同（第7节数值表）
 *   accentWash  —— 离线简报 UI 改版新增，仅 accentWash = true 且 ownerAccent != null 时
 *                  显示，右上角 ownerAccent 双层径向晕染，模拟纸面渗染质感，默认关闭
 *   L2 黄铜细线 —— 常态存在，1px 描边
 *   L3 身份脊   —— 仅 ownerAccent != null 时显示，左侧 2px 竖线（上深下浅渐变 + 投影）
 *   L4 蜡封角标 —— 仅 isMilestone = true 时显示，右上角 6px 绛红圆点（同心圆投影）
 *   顶部高光线 —— 仅 ownerAccent != null 时显示，卡片顶部居中的极细 accent 渐隐横线
 *   阴影        —— 常态存在，光照在 L2 之外另加一层投影，强调悬浮层次感
 *                  （与 L2 是互补关系，不是互斥替代，详见下方实现处注释）
 *
 * 噪点纹理说明：Compose 没有原生高性能噪点 Modifier，真正的逐像素噪点需要
 * Shader/RenderEffect 或预生成噪点贴图，超出本组件单文件实现的合理范围。
 * 当前用渐变层的不透明度变化做近似，颗粒感不够的话后续可以再补噪点贴图，
 * 这个判断留到第三步接真实页面看效果时再评估，不阻塞当前结构搭建。
 */
@Composable
fun WorldCard(
    modifier: Modifier = Modifier,
    ownerAccent: Color? = null,
    isMilestone: Boolean = false,
    cornerRadius: Dp = Radius.md,
    // 离线简报 UI 改版新增：右上角 accent 双层晕染，默认关闭，不影响已接入
    // WorldCard 的其余 ~14 处调用点（均为具名参数调用）。仅在 accentWash = true
    // 且 ownerAccent != null 时生效，模拟纸面渗染质感（双层不同扩散半径叠加），
    // 而不是单层平铺色块。目前只有 BriefingCharacterCard 打开这个开关。
    accentWash: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val isDark = colors.isDark

    // L1 光斑参数：light/dark 分别给值，不是同一套数值换皮肤（精修方案 v1.3 第1节结论）
    val l1Alpha = if (isDark) 0.18f else 0.06f
    val l1Color = if (isDark) colors.accent else Color.White

    // L2 黄铜描边透明度：浅色模式需要更高不透明度而非更深颜色（线条对比度低的补偿）
    val l2Alpha = if (isDark) 0.22f else 0.35f

    // 阴影：精修方案 v1.3 第69行字面写"L2取代box-shadow"，但第259行又给了具体数值，
    // 两处矛盾。已用 v1.2 视觉预览 HTML（Zaijian_视觉精修预览_v1_2_双主题.html）核实：
    // 真实组装后的卡片（.assembled-card / .card-glow 等）里 border 和 box-shadow 同时
    // 存在，只有单独展示 L2 这一层本身的局部预览块（.layer-box.l2）才没有阴影。说明
    // "取代"指的是"L2 这一层单独承担了过去阴影的部分视觉功能"，不是"完全不要阴影"，
    // 实际效果是两者并存、互补（黄铜线管近处描边精致感，阴影管整体悬浮层次感）。
    // 数值采用 HTML 预览里 --card-shadow 变量的取值（比文档表格数值更精确一致）：
    //   暗色：0 14px 30px -10px rgba(0,0,0,.55)
    //   浅色：0 6px 16px  -8px rgba(44,33,24,.16)
    // Compose 没有原生 box-shadow，且项目未确认 Compose BOM 版本是否支持新版
    // Modifier.dropShadow，这里用「叠一层做了模糊近似的半透明 Box，向下偏移」手动模拟，
    // 兼容所有 Compose 版本，不引入新 API 依赖。
    val shadowColor   = if (isDark) Color.Black.copy(alpha = 0.55f) else Palette.Ink900.copy(alpha = 0.16f) // 批次7 7-1修复：裸色值 Color(0xFF2C2118) 改为引用 Palette.Ink900
    val shadowOffsetY = if (isDark) 14.dp else 6.dp
    val shadowBlur    = if (isDark) 30.dp else 16.dp

    Box(
        modifier = modifier
            // 阴影：用 drawBehind 在卡片本体尺寸确定后于其下方绘制，不是独立子节点，
            // 因此不会影响该 Box 的尺寸协商——调用方传 fillMaxWidth() 等只约束宽度、
            // 高度仍由 content() 撑开的写法（项目里多数调用方都是这种用法）能继续正常工作。
            // 模糊用 graphicsLayer + renderEffect 较新 API 风险大，这里改用更朴素的方式：
            // 画多层逐渐淡出的圆角矩形叠加，模拟模糊扩散的视觉效果，兼容所有 Compose 版本。
            .drawBehind {
                val layers = 6
                for (i in layers downTo 1) {
                    val t = i.toFloat() / layers
                    val expand = shadowBlur.toPx() * t
                    val alpha = shadowColor.alpha * (1f - t) * 0.5f
                    drawRoundRect(
                        color = shadowColor.copy(alpha = alpha),
                        topLeft = Offset(-expand / 2f, shadowOffsetY.toPx() - expand / 2f),
                        size = androidx.compose.ui.geometry.Size(
                            size.width + expand,
                            size.height + expand,
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            cornerRadius.toPx() + expand / 2f,
                        ),
                    )
                }
            }
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(colors.bgElevated, colors.bgCard),
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        l1Color.copy(alpha = l1Alpha),
                        l1Color.copy(alpha = 0f),
                    ),
                    center = Offset.Zero,
                    radius = 480f,
                )
            )
            // accentWash：右上角 ownerAccent 双层径向晕染，扩散半径不同的两层
            // 叠加模拟纸面渗染质感（比单层平铺色块更有层次），而不是替代 L1——
            // L1 是左上角、中性色（accent/白）的常态光斑，这里是右上角、
            // ownerAccent 专属色的额外一层，两者共存不冲突。
            .then(
                if (accentWash && ownerAccent != null) {
                    Modifier.drawBehind {
                        val corner = Offset(size.width, 0f)
                        val washAlphaOuter = if (isDark) 0.16f else 0.10f
                        val washAlphaInner = if (isDark) 0.10f else 0.06f
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    ownerAccent.copy(alpha = washAlphaOuter),
                                    ownerAccent.copy(alpha = 0f),
                                ),
                                center = corner,
                                radius = size.maxDimension * 0.75f,
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx()),
                        )
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    ownerAccent.copy(alpha = washAlphaInner),
                                    ownerAccent.copy(alpha = 0f),
                                ),
                                center = corner,
                                radius = size.maxDimension * 0.4f,
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx()),
                        )
                    }
                } else {
                    Modifier
                }
            )
            .border(
                width = 1.dp,
                color = colors.accent.copy(alpha = l2Alpha),
                shape = RoundedCornerShape(cornerRadius),
            )
    ) {
        content()

        // 卡片顶部极细 accent 高光线：模拟卡片"立起来"的边缘反光。
        // 仅在 ownerAccent 非空时画（无主色时没有可用的高光颜色来源，
        // 沿用 L3 身份脊同样的"仅角色专属卡片显示"范围，不影响无 ownerAccent
        // 的调用点，如 BriefingAttentionSection/BriefingRankingSection）。
        if (ownerAccent != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                ownerAccent.copy(alpha = 0f),
                                ownerAccent.copy(alpha = if (isDark) 0.5f else 0.35f),
                                ownerAccent.copy(alpha = 0f),
                            ),
                        )
                    )
            )
        }

        // L3 身份脊：从纯色改为上深下浅渐变 + 一点投影，不再是死板的纯色 2px 线。
        if (ownerAccent != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ownerAccent,
                                ownerAccent.copy(alpha = 0.55f),
                            )
                        )
                    )
                    .drawBehind {
                        // 竖脊投影：向右侧扩散的一层淡淡阴影，加一点厚度感，
                        // 不用独立阴影层（会影响卡片整体尺寸协商），画在脊本身范围内。
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    ownerAccent.copy(alpha = 0.25f),
                                    ownerAccent.copy(alpha = 0f),
                                ),
                                startX = 0f,
                                endX = size.width * 6f,
                            ),
                            size = androidx.compose.ui.geometry.Size(size.width * 6f, size.height),
                        )
                    }
            )
        }

        if (isMilestone) {
            val velvet = if (isDark) Palette.Velvet else Palette.VelvetSoft
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-8).dp, y = 8.dp)
                    .size(6.dp)
                    // 蜡封角标同心圆投影：两层逐渐扩散淡出的圆形描边，围绕蜡封本体，
                    // 模拟"蜡封"凸起的立体质感，而不是单纯一个纯色小圆点。
                    .drawBehind {
                        val centerOffset = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(
                            color = velvet.copy(alpha = 0.18f),
                            radius = size.minDimension * 1.6f,
                            center = centerOffset,
                        )
                        drawCircle(
                            color = velvet.copy(alpha = 0.30f),
                            radius = size.minDimension * 1.1f,
                            center = centerOffset,
                        )
                    }
                    .clip(CircleShape)
                    .background(velvet)
                    .border(0.5.dp, velvet.copy(alpha = 0.4f), CircleShape)
            )
        }
    }
}


// ═════════════════════════════════════════════════════════════
//  WorldBubble — WorldCard 的气泡变体（非对称圆角场景专用）
// ═════════════════════════════════════════════════════════════

/**
 * WorldBubble：聊天/圆桌消息气泡专用容器，复用 WorldCard 的 L0 纸面底 + L1 光斑 +
 * L2 黄铜描边三层视觉规则，但圆角形状改为四角独立可控（[RoundedCornerShape] 的
 * 四参数版本），以支持"说话方向尖角"（一角直角、其余圆角）这种 WorldCard 本身
 * 的统一 cornerRadius 参数无法表达的形状。
 *
 * 【接入状态】W12问题1修复：已接入 ChatMessageBubble.kt 的 MessageBubble()（角色气泡，
 * 左下尖角）和 RoundtableBubble.kt 的 BotBubble()（角色气泡，左上尖角+左侧主题色条）。
 * 两处用户气泡（右对齐、accentColor 纯色填充）不接入——用户气泡的强调色填充是有意
 * 的视觉区分设计，WorldBubble 的纸面质感背景会削弱这个区分，不应替换。
 *
 * 不复用 L3 身份脊 / L4 蜡封角标：气泡场景已有自己的角色识别方式（如左侧主题色
 * 竖条、头像旁色点），milestone 概念也不适用于单条聊天消息，引入这两层反而会
 * 和气泡既有的视觉语言重复或冲突，因此本组件不暴露 ownerAccent / isMilestone
 * 参数（与 WorldCard 故意不同，不是遗漏）。
 *
 * 新增组件而非扩展 WorldCard 签名：避免四角独立圆角参数影响已接入 WorldCard 的
 * 14 个真实页面，零回归面。两者共享同一套 L0/L1/L2 数值（精修方案 v1.3 第7节），
 * 修改配色或光斑强度时需要同步改这两处。
 */
@Composable
fun WorldBubble(
    modifier: Modifier = Modifier,
    topStart: Dp = Radius.md,
    topEnd: Dp = Radius.md,
    bottomStart: Dp = Radius.md,
    bottomEnd: Dp = Radius.md,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    content: @Composable () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val isDark = colors.isDark

    // L1/L2 数值与 WorldCard 保持一致（精修方案 v1.3 第7节，同一套 light/dark 数值表）
    val l1Alpha = if (isDark) 0.18f else 0.06f
    val l1Color = if (isDark) colors.accent else Color.White
    val l2Alpha = if (isDark) 0.22f else 0.35f
    // borderColor 不传时，默认沿用 WorldCard 同款 accent 黄铜线；调用方可覆盖成
    // 固定色（如圆桌气泡场景需要的金色描边，与角色 accent 解耦）
    val resolvedBorderColor = borderColor ?: colors.accent.copy(alpha = l2Alpha)

    val shape = RoundedCornerShape(
        topStart    = topStart,
        topEnd      = topEnd,
        bottomStart = bottomStart,
        bottomEnd   = bottomEnd,
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(colors.bgElevated, colors.bgCard),
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        l1Color.copy(alpha = l1Alpha),
                        l1Color.copy(alpha = 0f),
                    ),
                    center = Offset.Zero,
                    radius = 480f,
                )
            )
            .border(
                width = borderWidth,
                color = resolvedBorderColor,
                shape = shape,
            )
    ) {
        content()
    }
}


// ═════════════════════════════════════════════════════════════
//  AdaptiveAvatarRow — 角色选择/筛选专用，9 个等分一行（精修方案 v1.3 第5.5节）
// ═════════════════════════════════════════════════════════════

/**
 * 单个头像项的最小信息集合。不直接依赖 data.model.CharacterConfig，
 * 保持组件层与数据层解耦——调用方在真实页面接入时自己映射成这个结构。
 */
data class AvatarRowItem(
    val id: Int,
    val avatarUrl: String,
    val accentColor: Color,
    val floor: AvatarRowFloor,
)

/** 楼层分组标记，独立于 data.model.FloorEnum，仅控制本组件的分组间距，不挂业务逻辑 */
enum class AvatarRowFloor { SECOND, FIRST, BASEMENT }

/**
 * 九个头像等分一行，按楼层分 3-3-3 组，组间距约为组内距的 1.5 倍，
 * 不加文字标签、不加分割线，仅靠间距疏密体现"三组人"（精修方案 v1.3 第5.5节）。
 * 选中态描边色取该角色 accentColor，而非统一金色。
 */
@Composable
fun AdaptiveAvatarRow(
    items: List<AvatarRowItem>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    avatarSize: Dp = AvatarSize.shelf,
) {
    val grouped = items.groupBy { it.floor }
    val itemSpacing = Spacing.sm
    // 组间总间距 = 组内间距 × 1.5（精修方案 v1.3 第5.5节）。
    // itemSpacing 已经是 Arrangement.spacedBy 的常驻间距，这里只需再补一段 Spacer
    // 把组间总间距从 8dp 拉到 12dp，所以额外补的是 (itemSpacing * 1.5 - itemSpacing) = 4dp = Spacing.xs，
    // 不是组内间距本身，避免和下面 Arrangement.spacedBy(itemSpacing) 已经画的那段重复计算。
    val groupGap = Spacing.xs // 组间额外间距（组内 8dp + 额外 4dp = 组间总计 12dp，即组内的 1.5 倍）
    val floorOrder = listOf(AvatarRowFloor.SECOND, AvatarRowFloor.FIRST, AvatarRowFloor.BASEMENT)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        floorOrder.forEachIndexed { groupIndex, floor ->
            val groupItems = grouped[floor].orEmpty()
            if (groupItems.isNotEmpty()) {
                if (groupIndex > 0) {
                    Spacer(Modifier.width(groupGap))
                }
                groupItems.forEach { item ->
                    val isSelected = item.id == selectedId
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .then(
                                if (isSelected) Modifier.border(2.dp, item.accentColor, CircleShape)
                                else Modifier
                            )
                            .clickable { onSelect(item.id) }
                    ) {
                        BreathingAvatar(
                            imageUrl = item.avatarUrl,
                            breathColor = item.accentColor,
                            statusType = StatusType.ACTIVE,
                            size = avatarSize,
                            enableBreath = false, // 选择/筛选场景，关掉呼吸动画避免多头像同时呼吸的视觉噪音
                        )
                    }
                }
            }
        }
    }
}


// ═════════════════════════════════════════════════════════════
//  WrapChipGroup（精修方案 v1.3 第6节：自动换行 + 选中态实心 + 角色色圆点）
// ═════════════════════════════════════════════════════════════

data class ChipItem(
    val label: String,
    val selected: Boolean,
    /** 若该 Chip 关联单一角色，传入角色 accentColor，左侧追加 4px 圆点；无关联传 null */
    val ownerAccent: Color? = null,
)

/**
 * 自动换行的筛选/标签组，选中态实心填充。
 * 新增：芯片若关联单一角色，左侧追加 4px 角色色圆点（精修方案 v1.3 第6节）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrapChipGroup(
    chips: List<ChipItem>,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        chips.forEachIndexed { index, chip ->
            val bg = if (chip.selected) colors.accent else colors.accentSoft
            val textColor = if (chip.selected) Color.White else colors.textPrimary
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(bg)
                    .clickable { onClick(index) }
                    .padding(horizontal = Spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (chip.ownerAccent != null) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(chip.ownerAccent)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(text = chip.label, style = type.caption, color = textColor)
            }
        }
    }
}


// ═════════════════════════════════════════════════════════════
//  GridTabBar（精修方案 v1.3 第6节：≤4 单行等分/5+ 自动换行，
//  选中态下划线动画滑动，计数数字用等宽字体）
// ═════════════════════════════════════════════════════════════

data class GridTabItem(
    val label: String,
    /** 计数数字，null 表示不显示计数；显示时使用等宽字体（精修方案 v1.3 第4节字体分工） */
    val count: Int? = null,
)

/**
 * ≤4 个 Tab 单行等分排列，5 个及以上自动换行。
 * 选中态用下划线动画滑动（替代纯色块切换），计数数字套等宽字体 labelMono。
 *
 * 实现说明：下划线动画用 animateFloatAsState 跟踪选中 index 的目标偏移比例。
 * 5+ Tab 自动换行场景下，"水平滑动"这个语义在多行布局里会失真（同一行内滑动
 * 才有意义），所以换行场景里下划线退化为纯色块高亮，不强行做跨行动画——
 * 这是 GridTabBar 在两种排列模式下视觉表现不完全对称的地方，记在这里说明。
 * 第三步真正接入具体页面时，如果 Tab 数固定 ≤4（多数场景），不会触发换行分支。
 */
@Composable
fun GridTabBar(
    items: List<GridTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.size <= 4) {
        SingleRowGridTabBar(items, selectedIndex, onSelect, modifier)
    } else {
        FlowGridTabBar(items, selectedIndex, onSelect, modifier)
    }
}

@Composable
private fun SingleRowGridTabBar(
    items: List<GridTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    // 批次4-8-1 修复：selectedIndex 无边界保护，越界时 targetFraction 超出
    // [0,1] 范围导致 weight(负数) 崩溃。虽然当前唯一调用方 TaskCenterScreen
    // 传入的 selectedTab 始终在 0-3 范围内，但组件本身应具备防御能力。
    val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)
    val itemCount = items.size.coerceAtLeast(1)
    val targetFraction by animateFloatAsState(
        targetValue = safeIndex.toFloat() / itemCount,
        animationSpec = appSpring,
        label = "gridTabUnderline",
    )

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                GridTabCell(
                    item = item,
                    isSelected = index == safeIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 下划线轨道：整条铺满宽度的淡色底（可视化"轨道"存在感），
        // 选中段用 weight 按 1/itemCount 比例切出对应宽度，再用 Spacer 在它左边
        // 占位 targetFraction 比例的空间来实现"滑动到第 N 格"的效果。
        // 用 weight 而不是绝对 dp 偏移，是因为本组件不知道实际渲染宽度（由父容器决定），
        // 绝对 dp 偏移在不同屏宽下会偏移到错误位置，weight 占位法不依赖具体像素值。
        Row(modifier = Modifier.fillMaxWidth()) {
            if (targetFraction > 0f) {
                Spacer(modifier = Modifier.weight(targetFraction))
            }
            Box(
                modifier = Modifier
                    .weight(1f / itemCount)
                    .height(2.dp)
                    .background(colors.accent)
            )
            val remaining = 1f - targetFraction - (1f / itemCount)
            if (remaining > 0f) {
                Spacer(modifier = Modifier.weight(remaining))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowGridTabBar(
    items: List<GridTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 批次4-审查修复：与 SingleRowGridTabBar 对齐，添加 safeIndex 边界保护。
    // 虽然 FlowGridTabBar 不使用 weight 动画（不会因越界崩溃），但越界时
    // 所有 Tab 都不显示选中态，属于视觉 bug。统一防护。
    val safeIndex = selectedIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items.forEachIndexed { index, item ->
            GridTabCell(
                item = item,
                isSelected = index == safeIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun GridTabCell(
    item: GridTabItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.label,
            style = type.cardTitle,
            color = if (isSelected) colors.accent else colors.textSecondary,
        )
        if (item.count != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = item.count.toString(),
                style = type.labelMono,
                color = if (isSelected) colors.accent else colors.textDisabled,
            )
        }
    }
}


// ═════════════════════════════════════════════════════════════
//  BondRibbon — 关系纽带刻度（新增，精修方案 v1.3 第5.4节）
// ═════════════════════════════════════════════════════════════

/** 与 data.db.entity.RelationshipStage 同名同序，避免本组件直接依赖数据层枚举 */
enum class BondStage { STRANGER, FAMILIAR, TRUSTED, IMPORTANT, CORE }

private val BondStage.label: String
    get() = when (this) {
        BondStage.STRANGER -> "陌生"
        BondStage.FAMILIAR -> "熟悉"
        BondStage.TRUSTED -> "信任"
        BondStage.IMPORTANT -> "重要"
        BondStage.CORE -> "核心"
    }

/**
 * 心防值（suppression, 0-100）对应的简短 UI 文案。
 *
 * 注意：这套文案是为 UI 短标签重新写的，不是照搬 RelationshipEngine.kt 里
 * 给 AI system prompt 用的那句"（内心防线较高，不轻易袒露）"——那句是写给模型看的
 * 叙述性提示文本，直接渲染在界面上太长也不像 UI 文案。这里阈值（≤30 / ≥75）
 * 与 RelationshipEngine.kt 保持一致，只是改写成短词，这是本次实现中需要使用者
 * 知情的一个设计判断，不是简单的字段复制。
 */
private fun suppressionShortLabel(suppression: Int): String? = when {
    suppression <= 30 -> "心防较高"
    suppression >= 75 -> "心防已松"
    else -> null
}

/**
 * 关系纽带刻度：5 格书签穗造型，已达到的阶段用角色 accentColor 填实，未达到留空（边框淡色）。
 *
 * @param showLabels 完整版（CharacterDetail 关系 Tab）传 true，带阶段文字标签；
 *                    迷你版（CharacterHeroCard）传 false，仅刻度，不挤占卡片横向布局。
 * @param suppression 心防值 0-100，null 表示不显示心防文案。
 */
@Composable
fun BondRibbon(
    stage: BondStage,
    accentColor: Color,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    suppression: Int? = null,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val stages = BondStage.entries
    val reachedCount = stages.indexOf(stage) + 1

    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showLabels) {
                // 完整版：5 格全画，已达到的实心，未达到的留空边框（精修方案 v1.3 第5.4节）
                stages.forEach { s ->
                    val reached = stages.indexOf(s) < reachedCount
                    Box(
                        modifier = Modifier
                            .size(width = 14.dp, height = 18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (reached) accentColor else Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = if (reached) accentColor else colors.border,
                                shape = RoundedCornerShape(2.dp),
                            )
                    )
                }
            } else {
                // 迷你版（CharacterHeroCard）：仅显示当前刻度位置这一格，不画全部五格，
                // 避免 Hero 卡片信息过载（精修方案 v1.3 第5.4节原文要求）。
                // 当前格固定用实心 accentColor 填充，不区分"已达到/未达到"——
                // 迷你版本身就只代表"现在在哪一格"，不需要展示整条进度轨迹。
                Box(
                    modifier = Modifier
                        .size(width = 14.dp, height = 18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor)
                        .border(
                            width = 1.dp,
                            color = accentColor,
                            shape = RoundedCornerShape(2.dp),
                        )
                )
            }
        }
        if (showLabels) {
            Spacer(Modifier.height(4.dp))
            Text(text = stage.label, style = type.caption, color = colors.textSecondary)
            val hint = suppression?.let { suppressionShortLabel(it) }
            if (hint != null) {
                Text(text = hint, style = type.label, color = colors.textDisabled)
            }
        }
    }
}


// ═════════════════════════════════════════════════════════════
//  MoodCandle — 心情烛光（新增，精修方案 v1.3 第5.3节），仅用于 CharacterHeroCard
// ═════════════════════════════════════════════════════════════

/**
 * MoodType 对应的中文+emoji 文案。
 *
 * 这份映射内容与 ChatScreen.kt 里 ChatScreen 顶栏"关系状态胶囊"使用的映射完全一致
 * （文档要求"沿用 Chat 顶栏已有映射，不新造一套词"），但物理上是复制的一份独立定义，
 * 没有把 ChatScreen.kt 里的私有局部变量提取成共享函数——这是为了守住"第二步不碰任何
 * 真实页面文件"的范围边界做的取舍。代价：以后这份文案要改动，需要 ChatScreen.kt 和
 * 这里两处一起改。等第三步真正接页面时，建议顺手把它提成 ui/theme 或 ui/design 下的
 * 共享函数，这是本次新增的一点技术债，记在这里方便以后处理。
 */
private fun MoodType.labelWithEmoji(): String = when (this) {
    MoodType.EXCITED -> "✨ 兴奋"
    MoodType.SATISFIED -> "😊 愉快"
    MoodType.CURIOUS -> "🤔 好奇"
    MoodType.FOCUSED -> "🎯 专注"
    MoodType.CALM -> "🌿 平静"
    MoodType.REFLECTIVE -> "💭 沉思"
    MoodType.TIRED -> "😴 疲惫"
    MoodType.CONCERNED -> "😟 担心"
}

/** 心情对应的语义色（不是角色 accentColor，避免和 L3 身份脊颜色语义冲突，精修方案 v1.3 第5.3节）
 *  批次7 7-2修复：4个与已有 token 重复的色值改为引用 Palette，消除数值级重复。
 *  EXCITED/CURIOUS/REFLECTIVE/TIRED 4个无对应 token 的保留裸色值（Palette 中不存在专属 token）。 */
private fun MoodType.candleColor(): Color = when (this) {
    MoodType.EXCITED -> Color(0xFFFF8A65)    // 暖橙，兴奋
    MoodType.SATISFIED -> Palette.SemanticReminder  // 暖黄，愉快（与 SemanticReminder 0xFFFFD54F 重复，改为引用）
    MoodType.CURIOUS -> Color(0xFFBA68C8)    // 紫，好奇
    MoodType.FOCUSED -> Palette.TaskActive   // 蓝，专注（与 TaskActive 0xFF5B9CF6 重复，改为引用。原注释"呼应系统 Focused 状态色"指错对象，实际数值对应 TaskActive）
    MoodType.CALM -> Palette.SemanticSafe    // 冷绿，平静（与 SemanticSafe 0xFF81C784 重复，改为引用）
    MoodType.REFLECTIVE -> Color(0xFF9FA8DA) // 冷紫蓝，沉思
    MoodType.TIRED -> Color(0xFF90A4AE)      // 灰，疲惫
    MoodType.CONCERNED -> Palette.SemanticDanger  // 暖红，担心（与 SemanticDanger 0xFFE57373 重复，改为引用）
}

/**
 * 心情烛光指示，仅用于 CharacterHeroCard，不做成独立大组件到处复用（精修方案 v1.3 第6节）。
 *
 * @param energy 精力值 0-100；按 data.model.CharacterConfig.PresenceState 的约定，
 *               -1 表示"未知"。这是文档没有写清楚、需要本次实现自行决定的边界情况：
 *               energy == -1（或任何 <0 的值）时，烛光画一个固定的低位静态高度
 *               （20% 高度，不参与动画/不随心情变色，呈现"灭"或"未知"的视觉状态），
 *               而不是直接代入百分比公式算出负数高度。energy 在 0-100 内时正常按
 *               精力越高烛光越亮越高映射。
 */
@Composable
fun MoodCandle(
    mood: MoodType,
    energy: Int,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    val knownEnergy = energy in 0..100
    val heightFraction = if (knownEnergy) (energy / 100f).coerceIn(0.2f, 1f) else 0.2f
    val candleColor = if (knownEnergy) mood.candleColor() else colors.textDisabled
    val maxFlameHeight = 24.dp

    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        // 烛芯：用渐变模拟烛光（精修方案 v1.3 第8节：先按渐变模拟，不阻塞组件上线）
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(maxFlameHeight * heightFraction)
                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 1.dp, bottomEnd = 1.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            candleColor.copy(alpha = if (knownEnergy) 0.95f else 0.5f),
                            candleColor.copy(alpha = if (knownEnergy) 0.55f else 0.25f),
                        )
                    )
                )
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = if (knownEnergy) mood.labelWithEmoji() else "状态未知",
            style = type.caption,
            color = colors.textSecondary,
        )
    }
}

// ═════════════════════════════════════════════════════════════
//  InfoChip —— 单色文字胶囊，展示单个静态状态标签
//  （"怀孕中"/"排卵期"这类场景，不需要点击/选中态，与
//  WrapChipGroup 的"可点击筛选标签组"用途不同，故不复用后者）
//  整合方案 v2.1 4.10.3 节（离线简报角色卡片）首次引入。
// ═════════════════════════════════════════════════════════════
@Composable
fun InfoChip(text: String, color: Color, modifier: Modifier = Modifier) {
    val type = ZaijianTheme.typography
    Text(
        text = text,
        style = type.caption,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(color)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
    )
}
