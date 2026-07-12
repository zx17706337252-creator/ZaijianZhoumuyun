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

@Composable
internal fun RelationshipPanel(
    character: CharacterConfig,
    accentColor: Color,
    characterIdStr: String = character.id.toString(),
    onNavigateToTimeline: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    val context = androidx.compose.ui.platform.LocalContext.current

    // Phase 17：从 Room 加载真实关系数据（用户 → 该角色）
    // UI M4 说明：此处在 Composable 内直接访问 DB 实例，属于\"局部视图专属数据\"模式——
    // RelationshipCard 是 CharacterDetailScreen 的内嵌子 Composable，无专属 ViewModel；
    // 若提取到 ViewModel 则需在 CharacterDetailViewModel 中暴露额外 StateFlow，
    // 这里以 remember {} + flowOn(IO) 作为最小改动保证线程安全。
    val db = remember { com.zaijian.zhoumuyun.data.db.AppDatabase.getInstance(context) }
    val relFlow = remember(characterIdStr) {
        db.relationshipDao()
            .observeFrom("user")
            .map { list -> list.firstOrNull { it.toId == characterIdStr } }
            .flowOn(Dispatchers.IO)
    }
    val relState by relFlow.collectAsStateWithLifecycle(initialValue = null)

    // Phase 17：加载最近关系变化事件（用于历史 Timeline）
    val recentRelEvents = remember { mutableStateOf<List<com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity>>(emptyList()) }
    // 关系转折点（Milestone）
    val milestones = remember { mutableStateOf<List<com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity>>(emptyList()) }
    LaunchedEffect(characterIdStr) {
        withContext(Dispatchers.IO) {
            val events = db.worldEventDao().queryByType(
                com.zaijian.zhoumuyun.data.db.entity.EventType.RELATIONSHIP_CHANGED.name, 8
            ).filter { it.actorId == "user" && it.targetId == characterIdStr }
            recentRelEvents.value = events
            milestones.value = db.relationshipMilestoneDao().getRecent("user", characterIdStr, 10)
        }
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
    } ?: listOf(
        "信任" to 50f, "尊重" to 50f, "亲密" to 50f,
        "好奇" to 50f, "依赖" to 30f, "冲突" to 10f,
    )

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

        RelationshipRadarChart(
            dimensions  = dims,
            accentColor = accentColor,
            modifier    = Modifier.fillMaxWidth().height(260.dp),
        )

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
        } catch (e: Exception) {
            ZLog.w("CharacterDetail", "RelationshipEventCard: failed to parse payload JSON", e)
            "关系更新"
        }
    }

    val dateLabel = remember(event.createdAt) {
        java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(event.createdAt))
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
        androidx.compose.ui.graphics.Color(0xFF81C784)   // 绿：缓和/和好

    val directionLabel = if (isWorsened) "↘ 转折" else "↗ 缓和"

    val dateLabel = remember(milestone.createdAt) {
        java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(milestone.createdAt))
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

@Composable
internal fun ListEditSection(
    title: String,
    hint: String,
    items: List<String>,
    accentColor: Color,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
    onUpdate: (Int, String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var newText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = type.label, color = colors.textSecondary)

        // 已有条目
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                    .border(0.5.dp, colors.border, RoundedCornerShape(Radius.sm))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.6f))
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value         = item,
                    onValueChange = { onUpdate(index, it) },
                    textStyle     = type.body.copy(color = colors.textPrimary),
                    cursorBrush   = androidx.compose.ui.graphics.SolidColor(accentColor),
                    modifier      = Modifier.weight(1f),
                )
                IconButton(
                    onClick  = { onRemove(index) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.Add,
                        contentDescription = "删除",
                        tint               = colors.textDisabled,
                        modifier           = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = 45f },
                    )
                }
            }
        }

        // 新增输入行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                .border(0.5.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(Radius.sm))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value         = newText,
                onValueChange = { newText = it },
                textStyle     = type.body.copy(color = colors.textPrimary),
                cursorBrush   = androidx.compose.ui.graphics.SolidColor(accentColor),
                decorationBox = { inner ->
                    if (newText.isEmpty()) {
                        Text(text = hint, style = type.body, color = colors.textDisabled)
                    }
                    inner()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (newText.isNotBlank()) accentColor else colors.border)
                    .clickable {
                        if (newText.isNotBlank()) {
                            onAdd(newText)
                            newText = ""
                        }
                    }
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Add,
                    contentDescription = "添加",
                    tint               = Color.White,
                    modifier           = Modifier.size(14.dp),
                )
            }
        }
    }
}

