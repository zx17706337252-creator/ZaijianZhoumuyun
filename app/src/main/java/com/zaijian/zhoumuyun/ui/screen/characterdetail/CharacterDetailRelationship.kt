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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.util.ZLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.FilterChip

@Composable
internal fun RelationshipPanel(
    character: CharacterConfig,
    accentColor: Color,
    characterIdStr: String = character.id.toString(),
    onNavigateToTimeline: (Int) -> Unit = {},
    // E0 分层收口：关系数据改走 ViewModel，Composable 不再直接持有 Repository。
    relationshipViewModel: com.zaijian.zhoumuyun.ui.viewmodel.RelationshipViewModel = viewModel(),
    // P2-31 修复：复用 Hero 卡片已收集的关系状态，避免重复订阅同一 Flow。
    sharedRelState: com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity? = null,
    modifier: Modifier = Modifier,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography

    // Phase 17：从 Room 加载真实关系数据（用户 → 该角色）
    // P2-31 修复：若父级已传入 sharedRelState 则直接复用，避免与 Hero 卡片重复订阅。
    val relState = sharedRelState

    // Phase 17：加载最近关系变化事件（用于历史 Timeline）
    val recentRelEvents = remember { mutableStateOf<List<com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity>>(emptyList()) }
    // 关系转折点（Milestone）
    val milestones = remember { mutableStateOf<List<com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity>>(emptyList()) }
    LaunchedEffect(characterIdStr) {
        recentRelEvents.value = relationshipViewModel.getRecentRelationshipEvents(
            actorId = "user", targetId = characterIdStr, queryLimit = 8,
        )
        milestones.value = relationshipViewModel.getRecentMilestones(
            fromId = "user", toId = characterIdStr, limit = 10,
        )
    }

    val dims = relState?.let { rel ->
        listOf(
            "信任" to rel.trust.toFloat(),
            "尊重" to rel.respect.toFloat(),
            "亲密" to rel.affection.toFloat(),
            "好奇" to rel.curiosity.toFloat(),
            "依赖" to rel.dependence.toFloat(),
            "冲突" to rel.conflict.toFloat(),
        )
    } ?: run {
        // W5-013 修复：数据未加载时显示 loading 而非默认值
        if (recentRelEvents.value.isEmpty()) return@run emptyList()
        listOf(
            "信任" to 50f, "尊重" to 50f, "亲密" to 50f,
            "好奇" to 50f, "依赖" to 30f, "冲突" to 10f,
        )
    }

    val stageLabel = relState?.let { rel ->
        when (rel.stage) {
            "STRANGER"  -> "陌生人"
            "FAMILIAR"  -> "熟悉"
            "TRUSTED"   -> "信任"
            "IMPORTANT" -> "重要"
            "CORE"      -> "核心"
            else        -> rel.stage
        }
    } ?: "熟悉"

    val relHistory = recentRelEvents.value

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Spacer(Modifier.height(Spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("与${character.name}的关系", style = type.titleBold, color = colors.textPrimary)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accentColor.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(stageLabel, style = type.label, color = accentColor)
            }
        }

        if (dims.isEmpty()) {
            CircularProgressIndicator(
                color = accentColor,
                modifier = Modifier.fillMaxWidth().height(260.dp).wrapContentSize(Alignment.Center),
            )
        } else {
            RelationshipRadarChart(
                dimensions  = dims,
                accentColor = accentColor,
                modifier    = Modifier.fillMaxWidth().height(260.dp),
            )
        }

        if (dims.isNotEmpty()) {
            dims.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = type.body, color = colors.textSecondary, modifier = Modifier.width(40.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier.width(140.dp).height(4.dp)
                                .clip(RoundedCornerShape(2.dp)).background(colors.border),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxHeight()
                                    .fillMaxWidth(value / 100f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(accentColor),
                            )
                        }
                        Text(value.toInt().toString(), style = type.label, color = colors.textSecondary, modifier = Modifier.width(28.dp))
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                .border(0.5.dp, colors.border, RoundedCornerShape(Radius.md))
                .padding(Spacing.md),
        ) {
            Text(
                "随着互动加深，关系会自然演化。\n信任和亲密达到 75 以上后阶段会提升。",
                style = type.secondary, color = colors.textSecondary,
            )
        }

        // ── 关系转折点 Milestone Timeline ────────────────────
        val milestoneList = milestones.value
        if (milestoneList.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.lg))
            Text(
                "重大转折点",
                style = type.titleBold,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(Spacing.sm))
            milestoneList.forEach { milestone ->
                MilestoneRow(milestone = milestone, accentColor = accentColor)
                Spacer(Modifier.height(Spacing.xs))
            }
        }

        // ── Phase 17：关系历史 Timeline ─────────────────────
        if (relHistory.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.lg))
            Text(
                "关系变化记录",
                style = type.titleBold,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(Spacing.sm))
            relHistory.forEach { event ->
                RelationshipHistoryRow(event = event, accentColor = accentColor)
                Spacer(Modifier.height(Spacing.xs))
            }
        }

        // ── B-1 Fix：故事时间线入口按钮 ─────────────────────
        Spacer(Modifier.height(Spacing.md))
        androidx.compose.material3.TextButton(
            onClick = { onNavigateToTimeline(character.id) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.material3.Text(
                text = "查看完整故事时间线 →",
                color = accentColor,
                style = type.body,
            )
        }

        Spacer(Modifier.height(Spacing.lg))
    }
}

@Composable
private fun RelationshipHistoryRow(
    event: com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 解析 payload JSON，读取关系变化摘要
    val summary = remember(event.id) {
        try {
            val j = org.json.JSONObject(event.payload)
            val trust     = j.optInt("trust",     -1)
            val affection = j.optInt("affection", -1)
            val conflict  = j.optInt("conflict",  -1)
            val stage     = j.optString("stage", "")
            buildString {
                if (trust     >= 0) append("信任 $trust  ")
                if (affection >= 0) append("亲密 $affection  ")
                if (conflict  >= 0) append("冲突 $conflict")
                if (stage.isNotBlank()) append("  → ${
                    when (stage) {
                        "STRANGER"  -> "陌生人"
                        "FAMILIAR"  -> "熟悉"
                        "TRUSTED"   -> "信任"
                        "IMPORTANT" -> "重要"
                        "CORE"      -> "核心"
                        else        -> stage
                    }
                }")
            }.trim().ifEmpty { "关系更新" }
        } catch (e: Throwable) {
            ZLog.w("CharacterDetail", "RelationshipEventCard: failed to parse payload JSON", e)
            "关系更新"
        }
    }

    val dateLabel = remember(event.createdAt) {
        TimeFormatUtils.formatMonthDaySlashTime(event.createdAt)
    }

    // WorldCard 接入（精修方案 v1.3）：关系变化记录，归属当前查看角色，
    // accentColor 即该角色 accent，直接作为 ownerAccent。
    WorldCard(
        modifier = Modifier.fillMaxWidth(),
        ownerAccent = accentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accentColor.copy(alpha = 0.7f))
                )
                Text(summary, style = type.secondary, color = colors.textPrimary)
            }
            Text(dateLabel, style = type.label, color = colors.textSecondary)
        }
    }
}

@Composable
private fun MilestoneRow(
    milestone: com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val isWorsened = milestone.direction ==
        com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneDirection.WORSENED.name

    val dotColor = if (isWorsened)
        Palette.SemanticDanger   // 红：恶化
    else
        Palette.SemanticSafe   // 绿：缓和/和好（W12问题5修复：原硬编码 0xFF81C784，与该 token 同值）

    val directionLabel = if (isWorsened) "↘ 转折" else "↗ 缓和"

    val dateLabel = remember(milestone.createdAt) {
        TimeFormatUtils.formatMonthDaySlashTime(milestone.createdAt)
    }

    // WorldCard 接入（精修方案 v1.3）：关系转折点，归属当前查看角色，
    // ownerAccent 用角色色；"好事/坏事"语义已经由圆点 + 方向文字标签
    // （dotColor）独立承担，不依赖边框颜色，故边框交还 WorldCard 标准黄铜线，
    // 不会丢失信息。
    WorldCard(
        modifier = Modifier.fillMaxWidth(),
        ownerAccent = accentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(dotColor),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = milestone.description,
                        style = type.secondary,
                        color = colors.textPrimary,
                    )
                    Text(
                        text  = directionLabel,
                        style = type.label,
                        color = dotColor,
                    )
                }
            }
            Text(
                text     = dateLabel,
                style    = type.label,
                color    = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun RelationshipRadarChart(
    dimensions: List<Pair<String, Float>>,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val n = dimensions.size
    // P3-28 修复：使用 TextMeasurer 将标签绘制到 Canvas 内，
    // 此前标签在 Canvas 外部用 Row 渲染，与雷达图视觉分离。
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(
        color = colors.textSecondary,
        fontSize = 11.sp,
        fontFamily = ZaijianTheme.typography.label.fontFamily,
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = minOf(cx, cy) * 0.72f
        val angleStep = (2 * kotlin.math.PI / n).toFloat()

        for (layer in 1..5) {
            val r = maxR * layer / 5f
            val path = androidx.compose.ui.graphics.Path()
            for (i in 0 until n) {
                val angle = (-kotlin.math.PI / 2 + i * angleStep).toFloat()
                val x = cx + r * kotlin.math.cos(angle.toDouble()).toFloat()
                val y = cy + r * kotlin.math.sin(angle.toDouble()).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color = colors.border.copy(alpha = 0.45f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.8.dp.toPx()))
        }

        for (i in 0 until n) {
            val angle = (-kotlin.math.PI / 2 + i * angleStep).toFloat()
            drawLine(
                color       = colors.border.copy(alpha = 0.35f),
                start       = Offset(cx, cy),
                end         = Offset(cx + maxR * kotlin.math.cos(angle.toDouble()).toFloat(), cy + maxR * kotlin.math.sin(angle.toDouble()).toFloat()),
                strokeWidth = 0.8.dp.toPx(),
            )
        }

        val dataPath = androidx.compose.ui.graphics.Path()
        dimensions.forEachIndexed { i, (_, value) ->
            val r = maxR * (value / 100f)
            val angle = (-kotlin.math.PI / 2 + i * angleStep).toFloat()
            val x = cx + r * kotlin.math.cos(angle.toDouble()).toFloat()
            val y = cy + r * kotlin.math.sin(angle.toDouble()).toFloat()
            if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()
        drawPath(dataPath, color = accentColor.copy(alpha = 0.25f))
        drawPath(dataPath, color = accentColor.copy(alpha = 0.80f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))

        dimensions.forEachIndexed { i, (_, value) ->
            val r = maxR * (value / 100f)
            val angle = (-kotlin.math.PI / 2 + i * angleStep).toFloat()
            drawCircle(
                color  = accentColor,
                radius = 3.dp.toPx(),
                center = Offset(cx + r * kotlin.math.cos(angle.toDouble()).toFloat(), cy + r * kotlin.math.sin(angle.toDouble()).toFloat()),
            )
        }

        // P3-28 修复：将维度标签绘制到 Canvas 内，标签位于轴线末端外侧
        for (i in 0 until n) {
            val (label, _) = dimensions[i]
            val angle = (-kotlin.math.PI / 2 + i * angleStep).toFloat()
            val labelR = maxR * 1.18f // 标签位置略超出最大半径
            val labelX = cx + labelR * kotlin.math.cos(angle.toDouble()).toFloat()
            val labelY = cy + labelR * kotlin.math.sin(angle.toDouble()).toFloat()
            val measured = textMeasurer.measure(
                text = label,
                style = labelStyle,
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    labelX - measured.size.width / 2f,
                    labelY - measured.size.height / 2f,
                ),
            )
        }
    }
}

// S-8：ListEditSection 已提取至 CharacterDetailShared.kt

