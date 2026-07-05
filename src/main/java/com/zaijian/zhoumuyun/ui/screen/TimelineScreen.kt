package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle // P1-11-2
import com.zaijian.zhoumuyun.data.db.entity.EventType
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.TimelineViewModel
import java.text.SimpleDateFormat
import java.util.*

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
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(Spacing.xs))
            Text("我们的故事", style = type.titleBold, color = colors.textPrimary)
        }

        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", color = colors.textSecondary)
            }
        } else if (uiState.events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有故事记录", color = colors.textSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                val grouped = uiState.events.groupBy { event ->
                    SimpleDateFormat("yyyy年MM月dd日", Locale.CHINESE).format(Date(event.createdAt))
                }
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
    val eventColor = when (event.type) {
        EventType.MESSAGE.name -> colors.accent
        EventType.RELATIONSHIP_CHANGED.name -> Palette.TimelineRelationship
        EventType.TASK_CREATED.name, EventType.TASK_COMPLETED.name -> colors.taskDone
        EventType.PROJECT_CREATED.name, EventType.PROJECT_UPDATED.name -> colors.taskActive
        EventType.MEMORY_CREATED.name -> Palette.TimelineMemory
        else -> colors.textSecondary
    }
    val eventIcon = when (event.type) {
        EventType.MESSAGE.name -> "💬"
        EventType.RELATIONSHIP_CHANGED.name -> "💝"
        EventType.TASK_CREATED.name -> "📋"
        EventType.TASK_COMPLETED.name -> "✅"
        EventType.TASK_CANCELLED.name -> "🚫"
        EventType.PROJECT_CREATED.name -> "📁"
        EventType.PROJECT_UPDATED.name -> "🔄"
        EventType.MEMORY_CREATED.name -> "🧠"
        EventType.PRESENCE_CHANGED.name -> "🔵"
        else -> "📌"
    }
    val timeStr = SimpleDateFormat("HH:mm", Locale.CHINESE).format(Date(event.createdAt))

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
                    Text(eventIcon, fontSize = 14.sp)
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
    else -> type
}
