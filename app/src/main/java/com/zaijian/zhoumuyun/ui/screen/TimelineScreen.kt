package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle // P1-11-2
import com.zaijian.zhoumuyun.data.db.entity.EventType
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.GoldPrimaryButton
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.TimelineViewModel
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.util.ZLog

@Composable
fun TimelineScreen(
    characterId: Int? = null,
    onBack: () -> Unit = {},
    viewModel: TimelineViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    LaunchedEffect(characterId) {
        viewModel.load(actorId = characterId?.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBase)
    ) {
        // 窗口4：统一为 DetailTopBar（原内联 Row 字号用 titleBold(20sp) 偏大、缺 topBarHeight）
        DetailTopBar(
            title    = "我们的故事",
            onBack   = onBack,
            headerBg = colors.bgBase,
        )

        if (uiState.isLoading) {
            // P3-51 修复：加载态用纯文本改为 CircularProgressIndicator
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
        } else if (uiState.error != null) {
            // W14 修复：增加错误状态分支（原缺失，加载失败时展示误导性"还没有故事记录"）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.screenHorizontal),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = uiState.error!!,
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                GoldPrimaryButton(
                    text = "重试",
                    onClick = { viewModel.load(actorId = characterId?.toString()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (characterId != null) {
                    Spacer(Modifier.height(12.dp))
                    GhostGoldButton(
                        text = "查看全部时间线",
                        onClick = { viewModel.load(actorId = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else if (uiState.events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有故事记录", color = colors.textSecondary)
            }
        } else {
            // P3-50 修复：分组计算未 remember，添加 remember 避免每次重组都重新计算
            // （remember 是 @Composable 函数，须在 LazyColumn 的 content lambda 之外调用，
            //  因为 content lambda 是 LazyListScope 构建器上下文而非 @Composable 上下文）
            val grouped = remember(uiState.events) {
                uiState.events.groupBy { event ->
                    TimeFormatUtils.formatChineseFullDate(event.createdAt)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                grouped.entries.forEach { (date, events) ->
                    item {
                        Text(
                            text = date,
                            style = type.caption,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(vertical = Spacing.xs),
                        )
                    }
                    items(events, key = { it.id }) { event ->
                        TimelineEventCard(event, colors)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineEventCard(event: WorldEventEntity, colors: com.zaijian.zhoumuyun.ui.theme.AppColors) {
    val type = ZaijianTheme.typography
    // P1-33 修复：eventColor / eventIcon 原先只覆盖了 15 个 EventType 中的 9/9 个，
    // TASK_FAILED/TASK_CANCELLED/PROJECT_MILESTONE/MEMORY_UPDATED/PRESENCE_CHANGED/
    // CHARACTER_DISCUSSION/WORLD_SIMULATION/SYSTEM 这 8 个类型落入 else 分支，
    // 使用统一的 textSecondary / "📌" 兜底——与 eventTypeLabel()（已覆盖全部 15 个）
    // 不一致，导致时间线上这些事件有中文标签但颜色/图标缺失区分度。
    // 现逐一补齐，复用已有 Palette / AppColors 语义色，不新增 token：
    //   失败/取消 → taskFailed / textSecondary（与任务卡片同语义）
    //   里程碑    → VelvetSoft（仪式性强调色，稀缺时刻专用）
    //   记忆更新  → TimelineMemory（与创建同色系）
    //   状态变化  → Focused（蓝灰，与 PresenceState 语义一致）
    //   角色讨论  → SemanticEmotion（粉，角色互动情感色）
    //   世界事件  → SemanticInfo（信息蓝，世界级事件）
    //   系统      → SemanticNeutral（中性灰）
    val eventColor = when (event.type) {
        EventType.MESSAGE.name -> colors.accent
        EventType.RELATIONSHIP_CHANGED.name -> Palette.TimelineRelationship
        EventType.TASK_CREATED.name, EventType.TASK_COMPLETED.name -> colors.taskDone
        EventType.TASK_FAILED.name -> colors.taskFailed
        EventType.TASK_CANCELLED.name -> colors.textSecondary
        EventType.PROJECT_CREATED.name, EventType.PROJECT_UPDATED.name -> colors.taskActive
        EventType.PROJECT_MILESTONE.name -> Palette.VelvetSoft
        EventType.MEMORY_CREATED.name, EventType.MEMORY_UPDATED.name -> Palette.TimelineMemory
        EventType.PRESENCE_CHANGED.name -> Palette.Focused
        EventType.CHARACTER_DISCUSSION.name -> Palette.SemanticEmotion
        EventType.WORLD_SIMULATION.name -> Palette.SemanticInfo
        EventType.SYSTEM.name -> Palette.SemanticNeutral
        else -> colors.textSecondary
    }
    val eventIcon = when (event.type) {
        EventType.MESSAGE.name -> "💬"
        EventType.RELATIONSHIP_CHANGED.name -> "💝"
        EventType.TASK_CREATED.name -> "📋"
        EventType.TASK_COMPLETED.name -> "✅"
        EventType.TASK_FAILED.name -> "❌"
        EventType.TASK_CANCELLED.name -> "🚫"
        EventType.PROJECT_CREATED.name -> "📁"
        EventType.PROJECT_UPDATED.name -> "🔄"
        EventType.PROJECT_MILESTONE.name -> "🏆"
        EventType.MEMORY_CREATED.name, EventType.MEMORY_UPDATED.name -> "🧠"
        EventType.PRESENCE_CHANGED.name -> "🔵"
        EventType.CHARACTER_DISCUSSION.name -> "🗣️"
        EventType.WORLD_SIMULATION.name -> "🌍"
        EventType.SYSTEM.name -> "⚙️"
        else -> "📌"
    }
    // P3-52 修复：SimpleDateFormat 在 items 内重复创建，提升到 remember
    val timeStr = remember(event.createdAt) {
        TimeFormatUtils.formatTime(event.createdAt)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(eventColor)
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(40.dp)
                    .background(eventColor.copy(alpha = 0.3f))
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        // WorldCard 接入（精修方案 v1.3 第2/6节）：L0-L2 常态层。时间线事件
        // 不归属单一角色（characterId 可为 null，表示跨角色总览），eventColor
        // 已通过左侧时间轴圆点+竖线表达"事件类型"语义，与 L3 身份脊（归属
        // 哪位角色）是不同维度信息，故不传 ownerAccent。
        WorldCard(
            modifier = Modifier.weight(1f),
        ) {
            Column(modifier = Modifier.padding(Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(eventIcon, style = type.body)  // P3-32 修复：emoji 图标 fontSize 替换为主题排印
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = eventTypeLabel(event.type),
                        style = type.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = eventColor,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(timeStr, style = type.small, color = colors.textSecondary)
                }
                if (event.payload.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = event.payload.take(100),
                        style = type.small,
                        color = colors.textSecondary,
                        maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun eventTypeLabel(type: String): String = when (type) {
    EventType.MESSAGE.name -> "对话"
    EventType.RELATIONSHIP_CHANGED.name -> "关系变化"
    EventType.TASK_CREATED.name -> "任务创建"
    EventType.TASK_COMPLETED.name -> "任务完成"
    EventType.TASK_FAILED.name -> "任务失败"
    EventType.TASK_CANCELLED.name -> "任务取消"
    EventType.PROJECT_CREATED.name -> "项目创建"
    EventType.PROJECT_UPDATED.name -> "项目更新"
    EventType.PROJECT_MILESTONE.name -> "里程碑"
    EventType.MEMORY_CREATED.name -> "记忆形成"
    EventType.MEMORY_UPDATED.name -> "记忆更新"
    EventType.PRESENCE_CHANGED.name -> "状态变化"
    EventType.CHARACTER_DISCUSSION.name -> "角色讨论"
    EventType.WORLD_SIMULATION.name -> "世界事件"
    EventType.SYSTEM.name -> "系统"
    // W14 问题7修复：当前 EventType 全部 15 个值均已在上面逐一覆盖，
    // else 分支理论上不可达；保留只是为了未来新增枚举值时不炸崩，
    // 原先直接回退到裸英文枚举名（如新增值忘记补映射，UI 会显示
    // "PRESENCE_CHANGED"这类原始标识符），改为回退到通用中文标签，
    // 同时打一条 warning 日志提醒开发者尽快补上专属文案。
    else -> {
        ZLog.w("TimelineScreen", "eventTypeLabel 未覆盖的 EventType：$type，请补充映射")
        "事件"
    }
}
