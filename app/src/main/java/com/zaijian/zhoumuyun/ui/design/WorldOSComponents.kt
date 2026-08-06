package com.zaijian.zhoumuyun.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.domain.MoodType
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.theme.AppBrushes
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
    // UI 升级 v2.0（帧20 我的页九宫格）：晕染圆心可配置，用"相对宽高的分数"表示
    // （drawBehind 里已有 size，可直接乘）。默认 null = 右上角 `(1f, 0f)`（熔合规则
    // §9 光源右上）。仅我的页九宫格传 `Offset(0.5f, 0f)` 顶部居中（HTML 帧20 要求
    // `radial-gradient(... at 50% 0%)`）。其他 ~14 处 WorldCard 调用点不传，行为不变。
    washCenterFraction: Offset? = null,
    // UI 升级 v2.0（融合方案 §4.2 L4 仪式层）：火漆刻字角标。
    // 传一个字（如 "珍"/"念"/"期"/"隙"/"缔"）即在右上角压一枚火漆印
    // （WaxSealBadge，径向高光三档 + 内圈刻痕 + 随机感微旋转）。
    // 预算纪律：全 App ≤8 处，仅置顶/需要关注/升阶时刻使用；
    // 与 isMilestone 的 6px 蜡封点互斥使用，不同时出现（金红不同卡同理）。
    waxChar: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val isDark = colors.isDark

    // L1 光斑参数：light/dark 分别给值，不是同一套数值换皮肤（精修方案 v1.3 第1节结论）
    // 视觉浓度增强：浅色光斑 0.06→0.09，让卡片左上角光感更明显
    val l1Alpha = if (isDark) 0.22f else 0.09f
    val l1Color = if (isDark) colors.accent else Color.White

    // L2 黄铜描边透明度：浅色模式需要更高不透明度而非更深颜色（线条对比度低的补偿）
    // 视觉浓度增强：浅色 0.35→0.45，配合 1.5dp 描边让金线更醒目
    val l2Alpha = if (isDark) 0.28f else 0.45f

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
    // 视觉浓度增强：阴影 alpha 上调、偏移和模糊加大，让卡片悬浮感更明显
    val shadowColor   = if (isDark) Color.Black.copy(alpha = 0.62f) else Palette.Ink900.copy(alpha = 0.20f)
    val shadowOffsetY = if (isDark) 18.dp else 8.dp
    val shadowBlur    = if (isDark) 38.dp else 22.dp

    Box(
        modifier = modifier
            // 阴影：用 drawBehind 在卡片本体尺寸确定后于其下方绘制，不是独立子节点，
            // 因此不会影响该 Box 的尺寸协商——调用方传 fillMaxWidth() 等只约束宽度、
            // 高度仍由 content() 撑开的写法（项目里多数调用方都是这种用法）能继续正常工作。
            // 模糊用 graphicsLayer + renderEffect 较新 API 风险大，这里改用更朴素的方式：
            // 画多层逐渐淡出的圆角矩形叠加，模拟模糊扩散的视觉效果，兼容所有 Compose 版本。
            .drawBehind {
                // 视觉浓度增强：阴影层数 6→8，扩散更柔和、层次更丰富
                val layers = 8
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
                        val corner = Offset(
                            size.width * (washCenterFraction?.x ?: 1f),
                            size.height * (washCenterFraction?.y ?: 0f),
                        )
                        // 视觉浓度增强：accentWash alpha 上调，角色色晕染更明显
                        val washAlphaOuter = if (isDark) 0.22f else 0.16f
                        val washAlphaInner = if (isDark) 0.14f else 0.10f
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
            // UI 升级 v2.0（鎏金纸梦融合方案 §4.2 L2 描边层）：
            // 1px 黄铜描边从「单色半透明」升级为「135° 三段渐变描边」——
            // 两端亮、中段收敛，视线自然落在卡的左上。Compose 原生支持
            // Brush border，零新增依赖。暗色模式整体降 alpha（AppBrushes 内处理）。
            .border(
                width = 1.5.dp,
                brush = AppBrushes.cardBorderGradient(isDark),
                shape = RoundedCornerShape(cornerRadius),
            )
    ) {
        content()

        // UI 升级 v2.0（融合方案 §4.2 L2 顶高光线）：卡内顶部 1px 纸面高光
        // 从「仅 ownerAccent 卡显示」扩展为「全卡常态存在」——无 ownerAccent 时
        // 用纸白高光（亮 .9 / 暗 .16），有 ownerAccent 时沿用角色色高光（更丰富，
        // 保留 v1.3 既有观感）。左右各缩进 14dp，与卡内边距对齐。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(1.dp)
                .background(
                    if (ownerAccent != null) {
                        Brush.horizontalGradient(
                            colors = listOf(
                                ownerAccent.copy(alpha = 0f),
                                ownerAccent.copy(alpha = if (isDark) 0.5f else 0.35f),
                                ownerAccent.copy(alpha = 0f),
                            ),
                        )
                    } else {
                        AppBrushes.topHighlight(isDark)
                    }
                )
        )

        // L3 身份脊：从纯色改为上深下浅渐变 + 一点投影，不再是死板的纯色线。
        // UI 升级 v2.0（融合方案 §4.2 L3 身份层）：2dp → 3dp，上下各缩进 14dp，
        // 与卡内边距对齐（此前通高，视觉上把卡片切成两半）。
        if (ownerAccent != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(vertical = 14.dp)
                    .width(3.dp)
                    .clip(RoundedCornerShape(2.dp))
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

        // UI 升级 v2.0：L4 火漆刻字角标（融合方案 §4.2）。
        // 组件本体在 AgentVisibility.kt，半枚压出卡片上缘（-9dp 偏移），
        // 与 isMilestone 蜡封点互斥（waxChar 优先，见参数注释）。
        waxChar?.let { ch ->
            WaxSealBadge(
                char = ch,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-9).dp, y = (-9).dp),
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
 *
 * 角色对话气泡改版（纯专属色填充）：两处角色气泡都改传 fillColor = accentColor，
 * 不再是"专属色描边包一层纸面底"。原先的说法——用户气泡纯色填充是有意的区分
 * 设计、WorldBubble 纸面质感不应替换——现在角色气泡也直接纯色填充了，区分
 * 用户/角色气泡改靠对齐方向（右/左）+ 圆角尖角方向，不再靠"纯色 vs 纸面"。
 * fillColor 走独立分支（见下方实现），不影响仍使用默认纸面底的其他调用点
 * （如 ContentBlockRenderer.kt 里的文件卡片、心迹面板）。
 *
 * 不复用 L3 身份脊 / L4 蜡封角标：气泡场景已有自己的角色识别方式（如左侧主题色
 * 竖条、头像旁色点），milestone 概念也不适用于单条聊天消息，引入这两层反而会
 * 和气泡既有的视觉语言重复或冲突，因此本组件不暴露 ownerAccent / isMilestone
 * 参数（与 WorldCard 故意不同，不是遗漏）。
 *
 * 新增组件而非扩展 WorldCard 签名：避免四角独立圆角参数影响已接入 WorldCard 的
 * 14 个真实页面，零回归面。两者共享同一套 L0/L1/L2 数值（精修方案 v1.3 第7节），
 * 修改配色或光斑强度时需要同步改这两处（fillColor 分支不受影响）。
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
    // 角色对话气泡新方案（纯专属色填充）：传入后气泡背景直接是这个纯色，
    // 不再叠 L0 纸面渐变 + L1 光斑；不是"专属色描边包一层中性纸面"，是
    // 气泡本身就是该角色的颜色。传入时默认不描边（纯色色块本身已经和页面
    // 背景有区分，不需要再包一层边）——如仍要边，显式传 borderColor 即可。
    fillColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val isDark = colors.isDark

    val shape = RoundedCornerShape(
        topStart    = topStart,
        topEnd      = topEnd,
        bottomStart = bottomStart,
        bottomEnd   = bottomEnd,
    )

    var boxModifier = modifier.clip(shape)

    boxModifier = if (fillColor != null) {
        boxModifier.background(fillColor)
    } else {
        // L1/L2 数值与 WorldCard 保持一致（精修方案 v1.3 第7节，同一套 light/dark 数值表）
        val l1Alpha = if (isDark) 0.18f else 0.06f
        val l1Color = if (isDark) colors.accent else Color.White
        boxModifier
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
    }

    // fillColor 模式下默认不描边；未传 fillColor 时保持原逻辑——borderColor
    // 不传则默认沿用 WorldCard 同款 accent 黄铜线（不能省略描边，中性纸面
    // 底不描边会和页面背景糊在一起）。
    if (fillColor == null || borderColor != null) {
        val l2Alpha = if (isDark) 0.22f else 0.35f
        val resolvedBorderColor = borderColor ?: colors.accent.copy(alpha = l2Alpha)
        boxModifier = boxModifier.border(
            width = borderWidth,
            color = resolvedBorderColor,
            shape = shape,
        )
    }

    Box(modifier = boxModifier) {
        content()
    }
}

/**
 * 纯专属色气泡的内容对比色（正文文字、引用符号等）。九个角色的 accentColor
 * 亮度跨度很大——深藏青 #34506E（luminance 0.08）到浅靛蓝 #ACC0E8（luminance
 * 0.52）都存在，固定白字在浅色系角色气泡上会糊掉。按相对亮度阈值 0.25 自动
 * 选深/浅：九个现有角色色实测在阈值两侧都有安全余量（最紧的 #C23A54 配白字
 * 仍有 5.2:1 对比度，最紧的 #EC93AE 配 Ink900 仍有 7:1），均满足 WCAG AA
 * 正文文字 4.5:1 门槛。
 */
fun Color.contentOnFill(): Color =
    if (this.luminance() < 0.25f) Color.White else Palette.Ink900


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
 * 选中态描边色默认取该角色 accentColor；若传入 selectedBorder（Brush）则改用
 * 该渐变笔刷——UI 升级 v2.0 帧13 全局日程页用黄铜金环替代角色色单色边框。
 */
@Composable
fun AdaptiveAvatarRow(
    items: List<AvatarRowItem>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    avatarSize: Dp = AvatarSize.shelf,
    selectedBorder: Brush? = null,
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
                                if (isSelected) {
                                    // UI 升级 v2.0 帧13：selectedBorder 传入时用黄铜渐变金环
                                    if (selectedBorder != null) {
                                        Modifier.border(2.dp, selectedBorder, CircleShape)
                                    } else {
                                        Modifier.border(2.dp, item.accentColor, CircleShape)
                                    }
                                } else Modifier
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
                    Spacer(Modifier.width(Spacing.xs))
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
            // UI 升级 v2.0（融合方案 §4.5 页内 Tab）：满格 2dp 单色下划线改为
            // 22×2.5dp 黄铜渐变圆角下划线（居中于选中格）——指示器收敛为
            // 「一小段烫金刻度」，与渲染稿 ptabs 一致；滑动动画保持不变。
            Box(
                modifier = Modifier
                    .weight(1f / itemCount)
                    .height(2.5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AppBrushes.goldGradient()),
                )
            }
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
        // UI 升级 v2.0（融合方案 §4.5）：选中态文字从金色改为墨色（渲染稿
        // ptabs 选中=ink+金下划线；小字号纯金文字在纸底对比度不足 4.5:1，
        // 选中语义由下划线承担，文字只负责读）。
        Text(
            text = item.label,
            style = type.cardTitle,
            color = if (isSelected) colors.textPrimary else colors.textSecondary,
        )
        if (item.count != null) {
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = item.count.toString(),
                style = type.labelMono,
                color = if (isSelected) colors.accentDeep else colors.textDisabled,
            )
        }
    }
}


// ═════════════════════════════════════════════════════════════
//  GoldPillSegmentedControl — 金药丸分段控件（UI 升级 v2.0 新增）
// ═════════════════════════════════════════════════════════════

/**
 * 金药丸分段控件（融合方案 §4.4）：米灰底胶囊槽（#EDE4D2）+ 黄铜渐变
 * 药丸选中（滑动动画）+ 选中白字 / 未选次级字。
 *
 * 用于 IA 合并后的页内三段切换（事务=任务/日程/项目，成长=目标/专长/竞赛）。
 * 与 GridTabBar（下划线页内 Tab）语义不同：分段控件是「视图切换」，
 * GridTabBar 是「同视图内的筛选标签」——两者并存，不互相替代。
 *
 * 分段状态保留规则（v1.1 决策3）：调用方用 rememberSaveable 持有选中段，
 * 切走再切回时记住上次选中（组件本身不持久化，进程内保留即可）。
 */
@Composable
fun GoldPillSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val safeIndex = selectedIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))
    val itemCount = items.size.coerceAtLeast(1)
    // 药丸滑动：与 GridTabBar 同一套 weight 占位法，不依赖像素宽度。
    val targetFraction by animateFloatAsState(
        targetValue = safeIndex.toFloat() / itemCount,
        animationSpec = appSpring,
        label = "goldPillSlide",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFEDE4D2))
            .border(0.5.dp, colors.border, RoundedCornerShape(999.dp))
            .padding(3.dp),
    ) {
        // 滑动药丸（先声明=底层：weight 占位定位 + 黄铜渐变）
        Row(modifier = Modifier.fillMaxWidth()) {
            if (targetFraction > 0f) {
                Spacer(modifier = Modifier.weight(targetFraction))
            }
            Box(
                modifier = Modifier
                    .weight(1f / itemCount)
                    .height(30.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(AppBrushes.goldGradient()),
            )
            val remaining = 1f - targetFraction - (1f / itemCount)
            if (remaining > 0f) {
                Spacer(modifier = Modifier.weight(remaining))
            }
        }
        // 槽内文字行（后声明=上层：文字始终在药丸之上可读）
        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = type.body,
                        fontWeight = if (index == safeIndex) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index == safeIndex) Color.White else colors.textSecondary,
                    )
                }
            }
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
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
            Spacer(Modifier.height(Spacing.xs))
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
 *  P2-41 修复：剩余4个裸色值（EXCITED/CURIOUS/REFLECTIVE/TIRED）收口为 Palette token。 */
private fun MoodType.candleColor(): Color = when (this) {
    MoodType.EXCITED -> Palette.MoodExcited       // 暖橙，兴奋
    MoodType.SATISFIED -> Palette.SemanticReminder  // 暖黄，愉快（与 SemanticReminder 0xFFFFD54F 重复，改为引用）
    MoodType.CURIOUS -> Palette.MoodCurious        // 紫，好奇
    MoodType.FOCUSED -> Palette.TaskActive   // 蓝，专注（与 TaskActive 0xFF5B9CF6 重复，改为引用。原注释"呼应系统 Focused 状态色"指错对象，实际数值对应 TaskActive）
    MoodType.CALM -> Palette.SemanticSafe    // 冷绿，平静（与 SemanticSafe 0xFF81C784 重复，改为引用）
    MoodType.REFLECTIVE -> Palette.MoodReflective // 冷紫蓝，沉思
    MoodType.TIRED -> Palette.MoodTired           // 灰，疲惫
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


// ═════════════════════════════════════════════════════════════
//  MatBadge — 文件类型图标槽（icon_redesign_renders 方向B · 扁平风格，选定）
//
//  原"微立体"配方（三段渐变斜面高光 + 色晕投影 + 描边）已废弃——两版渲染
//  对比后选定扁平方向：去掉渐变/投影/描边，纯色浅底 + 单色线性图标，
//  类似 Notion/Files 的文件类型标签，同一套色板直接复用（不改调用接口）。
//
//  配方：
//    - 底色：浅色模式 = 本色 14% + Parchment 86%；深色模式 = 本色 22% + Night 78%
//    - 图标：浅色模式直接用本色；深色模式 = 本色 65% + 白 35%（提亮以保证在
//      深底浅色 tint 上可读）
// ═════════════════════════════════════════════════════════════

/**
 * 文件类型图标徽章（扁平风格）。
 *
 * @param icon      图标本体
 * @param contentDescription 无障碍描述
 * @param modifier  布局修饰符
 * @param color     语义色（--c），决定浅底 tint 与图标色相；默认 Gold
 * @param badgeSize 徽章整体尺寸
 * @param iconSize  图标本身尺寸
 * @param cornerRadius 圆角半径
 */
@Composable
fun MatBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    color: Color = Palette.Gold,
    badgeSize: Dp = 38.dp,
    iconSize: Dp = 19.dp,
    cornerRadius: Dp = 11.dp,
) {
    val colors = ZaijianTheme.colors
    val isDark = colors.isDark

    val bgTint   = if (isDark) lerp(color, Palette.Night, 0.78f) else lerp(color, Palette.Parchment, 0.86f)
    val iconTint = if (isDark) lerp(color, Color.White, 0.35f) else color

    Box(
        modifier = modifier
            .size(badgeSize)
            .clip(RoundedCornerShape(cornerRadius))
            .background(bgTint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize),
        )
    }
}


// ═════════════════════════════════════════════════════════════
//  BrassBadge — 黄铜"打开"徽章（细化方案第三节）
//
//  正圆球面高光 + 投影，固定 Gold 色（代表"打开"这个统一动作，
//  不随文件类型色变化——动作符号和内容符号分开）。
//  先在文件卡上落地，排行名次/紧急度点/分类角标留给后续窗口。
// ═════════════════════════════════════════════════════════════

/**
 * 黄铜"打开"徽章。
 *
 * @param modifier 布局修饰符
 * @param size     徽章直径
 * @param onClick  点击回调（打开动作）
 */
@Composable
fun BrassBadge(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    onClick: (() -> Unit)? = null,
) {
    val isDark = ZaijianTheme.colors.isDark

    // 球面径向渐变：左上提亮 → 右下压暗
    val gradientColors = listOf(
        Color(0xFFF5E6C4),  // 高光
        Color(0xFFD9B87C),  // 中段
        Color(0xFFA9803F),  // 暗部
        Color(0xFF8C6A34),  // 边缘
    )

    // 外投影
    val shadowColor = Color(0xFF8C6A52)
    val shadowBlur = 8.dp

    val sizePx = with(LocalDensity.current) { size.toPx() }
    val radius = sizePx / 2f

    Box(
        modifier = modifier
            .size(size)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind {
                // 外投影（多层扩散模拟模糊）
                val layers = 4
                for (i in layers downTo 1) {
                    val t = i.toFloat() / layers
                    val expand = shadowBlur.toPx() * t
                    val alpha = 0.55f * (1f - t) * 0.3f
                    drawCircle(
                        color = shadowColor.copy(alpha = alpha),
                        radius = radius + expand / 2f,
                        center = Offset(radius, radius + 2.dp.toPx()),
                    )
                }
            }
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = gradientColors,
                    center = Offset(sizePx * 0.30f, sizePx * 0.25f),
                    radius = sizePx * 0.85f,
                )
            )
            // 内上缘高光
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isDark) 0.15f else 0.40f),
                            Color.White.copy(alpha = 0f),
                        ),
                        center = Offset(sizePx * 0.32f, sizePx * 0.28f),
                        radius = sizePx * 0.40f,
                    ),
                )
            }
            .border(0.5.dp, Color(0xFF8C6A34).copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.OpenInNew,
            contentDescription = "打开",
            tint = Color(0xFF4A3417),
            modifier = Modifier.size(size * 0.47f),
        )
    }
}


// ═════════════════════════════════════════════════════════════
//  VaultCard — 馆藏收藏卡（融合方案 §3.2 第10项，仅记忆/收藏场景）
//
//  外金内白双层描边 + 拱形卡头 + 115° 静态微光。
//  与 WorldCard 的区别：VaultCard 是"被收藏的文物"专用底座，
//  仪式感更强（双层金描边 + 拱形头 + 静态斜光），仅用于记忆置顶、
//  家族珍藏等少数收藏语义场景，不替代 WorldCard 做日常卡片。
// ═════════════════════════════════════════════════════════════

/**
 * 馆藏收藏卡：外金描边 + 内白描边 + 拱形卡头 + 静态微光。
 *
 * @param headerContent 拱形卡头内容（如角色色渐变 + 标题），高 64dp，拱形圆角
 * @param bodyContent   卡身内容
 * @param ownerAccent   角色色，用于卡头渐变与水彩晕染
 * @param waxChar       火漆刻字（如"珍"），右上角压印
 * @param modifier      布局修饰符
 */
@Composable
fun VaultCard(
    headerContent: @Composable () -> Unit,
    bodyContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    ownerAccent: Color? = null,
    waxChar: String? = null,
) {
    val colors = ZaijianTheme.colors
    val isDark = colors.isDark

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            // 外层金描边
            .border(1.dp, Palette.Gold.copy(alpha = if (isDark) 0.6f else 0.7f), RoundedCornerShape(Radius.sm))
            .background(colors.bgCard)
            // 阴影
            .drawBehind {
                val layers = 5
                val shadowColor = if (isDark) Color.Black.copy(alpha = 0.4f) else Palette.Ink900.copy(alpha = 0.10f)
                for (i in layers downTo 1) {
                    val t = i.toFloat() / layers
                    val expand = 14.dp.toPx() * t
                    drawRoundRect(
                        color = shadowColor.copy(alpha = shadowColor.alpha * (1f - t) * 0.5f),
                        topLeft = Offset(-expand / 2f, 7.dp.toPx() - expand / 2f),
                        size = Size(size.width + expand, size.height + expand),
                        cornerRadius = CornerRadius(Radius.sm.toPx() + expand / 2f),
                    )
                }
            },
    ) {
        Column {
            // 拱形卡头：44px 拱形圆角，角色色渐变底 + 静态 115° 微光
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(topStart = 39.dp, topEnd = 39.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                    .background(
                        if (ownerAccent != null) {
                            Brush.linearGradient(
                                colors = listOf(ownerAccent.brighten(0.15f), ownerAccent.copy(alpha = 0.85f)),
                                start = Offset.Zero,
                                end = Offset.Infinite,
                            )
                        } else {
                            AppBrushes.goldGradient()
                        }
                    )
                    // 静态 115° 微光斜带
                    .drawBehind {
                        val w = size.width
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = if (isDark) 0.10f else 0.22f),
                                    Color.Transparent,
                                ),
                                start = Offset(w * 0.15f, 0f),
                                end = Offset(w * 0.55f, size.height),
                            ),
                        )
                    },
                contentAlignment = Alignment.BottomStart,
            ) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    headerContent()
                }
            }
            // 卡身
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(bottomStart = 7.dp, bottomEnd = 7.dp, topStart = 2.dp, topEnd = 2.dp))
                    .border(0.5.dp, Palette.Gold.copy(alpha = 0.22f), RoundedCornerShape(7.dp))
                    .background(colors.bgCard)
                    .padding(14.dp),
            ) {
                bodyContent()
            }
        }

        // 火漆角标（如有）
        waxChar?.let { ch ->
            WaxSealBadge(
                char = ch,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-9).dp, y = (-9).dp),
            )
        }
    }
}

/** 角色色提亮（渐变高光端）：向白色插值。WorldOSComponents 内复用。 */
private fun Color.brighten(fraction: Float): Color = Color(
    red = red + (1f - red) * fraction,
    green = green + (1f - green) * fraction,
    blue = blue + (1f - blue) * fraction,
    alpha = alpha,
)


// ═════════════════════════════════════════════════════════════
//  ScrollVine — 卷草装饰（融合方案 §3.2 第7项，仅 WorldBar/题献/页框）
//
//  纯 Canvas 绘制的 S 形卷草曲线，1.5px 金色描边（currentColor 语义），
//  不依赖 SVG 资源。三种形态：侧边（orn-side）、角落（orn-corner）、分隔（orn-div）。
//  预算纪律：≤2 处/屏，仅公馆/书架 WorldBar 两侧 + 题献/页框特许。
// ═════════════════════════════════════════════════════════════

/** 卷草装饰形态 */
enum class VineStyle { SIDE, CORNER, DIVIDER }

/**
 * 卷草装饰：Canvas 绘制的 S 形金色曲线。
 *
 * @param style 形态：SIDE=侧边横展、CORNER=角落 L 形、DIVIDER=居中分隔
 * @param modifier 布局修饰符
 * @param tint 金色色调，默认 Palette.Gold
 */
@Composable
fun ScrollVine(
    modifier: Modifier = Modifier,
    style: VineStyle = VineStyle.SIDE,
    tint: Color = Palette.Gold,
) {
    val alpha = if (ZaijianTheme.colors.isDark) 0.55f else 0.70f
    val vineColor = tint.copy(alpha = alpha)

    when (style) {
        VineStyle.SIDE -> {
            Canvas(
                modifier = modifier.height(10.dp).width(52.dp),
            ) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(0f, h * 0.5f)
                    cubicTo(w * 0.2f, 0f, w * 0.35f, 0f, w * 0.5f, h * 0.5f)
                    cubicTo(w * 0.65f, h, w * 0.8f, h, w, h * 0.5f)
                }
                drawPath(path, color = vineColor, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
                // 末端小卷
                drawCircle(vineColor, radius = 2.dp.toPx(), center = Offset(w * 0.5f, h * 0.5f))
            }
        }
        VineStyle.CORNER -> {
            Canvas(
                modifier = modifier.size(48.dp),
            ) {
                val s = size.width
                val path = Path().apply {
                    moveTo(0f, s * 0.15f)
                    cubicTo(s * 0.1f, 0f, s * 0.25f, 0f, s * 0.35f, s * 0.1f)
                    cubicTo(s * 0.5f, s * 0.25f, s * 0.15f, s * 0.5f, 0f, s * 0.35f)
                }
                drawPath(path, color = vineColor, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        VineStyle.DIVIDER -> {
            Canvas(
                modifier = modifier.height(12.dp).width(84.dp),
            ) {
                val w = size.width
                val h = size.height
                val cy = h * 0.5f
                // 左卷草
                val leftPath = Path().apply {
                    moveTo(0f, cy)
                    cubicTo(w * 0.08f, 0f, w * 0.15f, 0f, w * 0.2f, cy)
                }
                drawPath(leftPath, color = vineColor, style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round))
                // 中线
                drawLine(vineColor, Offset(w * 0.2f, cy), Offset(w * 0.8f, cy), strokeWidth = 0.8.dp.toPx(), cap = StrokeCap.Round)
                // 右卷草（镜像）
                val rightPath = Path().apply {
                    moveTo(w, cy)
                    cubicTo(w * 0.92f, 0f, w * 0.85f, 0f, w * 0.8f, cy)
                }
                drawPath(rightPath, color = vineColor, style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round))
                // 中心点
                drawCircle(vineColor, radius = 2.5.dp.toPx(), center = Offset(w * 0.5f, cy))
            }
        }
    }
}


// ═════════════════════════════════════════════════════════════
//  WashiTape — 和纸胶带（融合方案 §3.3，仅时间线 + 书架预览）
//
//  半透明彩色胶带条，两端锯齿裁切，±4° 微旋转。
//  预算：≤1 条/卡。
// ═════════════════════════════════════════════════════════════

/**
 * 和纸胶带装饰。
 *
 * @param color 胶带颜色（角色色或固定色）
 * @param modifier 布局修饰符（调用方控制定位）
 * @param widthDp 胶带宽度
 */
@Composable
fun WashiTape(
    modifier: Modifier = Modifier,
    color: Color = Palette.Gold.copy(alpha = 0.38f),
    widthDp: Dp = 64.dp,
) {
    Box(
        modifier = modifier
            .width(widthDp)
            .height(18.dp)
            .graphicsLayer { rotationZ = -4f }
            .drawBehind {
                val w = size.width
                val h = size.height
                // 胶带主体
                drawRect(color)
                // 左端锯齿
                val teeth = 4
                val teethW = 3.dp.toPx()
                for (i in 0 until teeth) {
                    val y = h * i / teeth
                    drawLine(
                        color = color.copy(alpha = 0f),
                        start = Offset(0f, y),
                        end = Offset(teethW, y + h / teeth / 2f),
                        strokeWidth = 0f,
                    )
                }
            }
            .clip(
                RoundedCornerShape(0.dp)
            ),
    )
}
