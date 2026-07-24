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
import com.zaijian.zhoumuyun.data.model.BirthRecord
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
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.util.ZLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.compose.material3.FilterChip

@Composable
internal fun PregnancyPanel(
    state: com.zaijian.zhoumuyun.ui.viewmodel.PregnancyUiState,
    accentColor: Color,
    onRequestTerminate: () -> Unit,
    onDismissTerminate: () -> Unit,
    onConfirmTerminate: () -> Unit,
    // P3-44 修复：生育记录可点击导航到子代角色详情页
    // 批次3 3-6修复：原签名 (Int) -> Unit 传的是 record.characterId（母亲ID），
    // 导航到当前页面自身（无操作）。改为 (BirthRecord) -> Unit，让调用方
    // 根据record.isDaughter决定是否查子代ID再导航（男孩无子代角色不响应）。
    onBirthRecordClick: (BirthRecord) -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val pregnancy = state.pregnancy

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // ── 标题行 ───────────────────────────────────────────
        GoldDivider(withDiamond = true, fadeEdges = true)
        Spacer(modifier = Modifier.height(Spacing.xs))

        // ── 当前孕期状态卡 ───────────────────────────────────
        // WorldCard 接入（精修方案 v1.3）：结构化状态展示卡，归属当前角色。
        // 内部"终止妊娠"操作按钮维持原样不单独接 WorldCard，避免卡片套卡片。
        WorldCard(
            modifier = Modifier.fillMaxWidth(),
            ownerAccent = accentColor,
        ) {
            Box(modifier = Modifier.padding(Spacing.md)) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).align(Alignment.Center),
                    color    = accentColor,
                    strokeWidth = 2.dp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    val statusText = when {
                        pregnancy.isPregnant -> {
                            val day = pregnancy.currentDay()
                            "孕期第 $day / ${PregnancyState.CYCLE_DAYS} 天"
                        }
                        pregnancy.miscarriedAt != null -> {
                            val daysAgo = pregnancy.miscarriageDaysAgo() ?: 0
                            if (daysAgo <= 30) "流产已 $daysAgo 天" else "无在孕记录"
                        }
                        else -> "当前未怀孕"
                    }
                    Text(
                        text  = statusText,
                        style = type.cardTitle,
                        color = if (pregnancy.isPregnant) accentColor else colors.textPrimary,
                    )
                    if (pregnancy.consecutiveFailCount > 0 && !pregnancy.isPregnant) {
                        Text(
                            text  = "连续失败 ${pregnancy.consecutiveFailCount} 次",
                            style = type.caption,
                            color = colors.textSecondary,
                        )
                    }

                    // ── 操作按钮 ────────────────────────────────
                    // 手动"开始怀孕"入口已移除：怀孕由 P5 判定链路（叙事解锁+
                    // 伴侣同意+周期判定）自动触发，未孕状态下不再展示任何按钮。
                    if (pregnancy.isPregnant) {
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(colors.bgElevated)
                                    .border(0.5.dp, colors.border, RoundedCornerShape(Radius.sm))
                                    .clickable { onRequestTerminate() }
                                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                            ) {
                                Text(text = "终止妊娠", style = type.button, color = colors.textSecondary)
                            }
                        }
                    }
                }
            }
            }
        }

        // ── 生育记录列表 ─────────────────────────────────────
        if (state.birthRecords.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text  = "生育记录",
                style = type.label,
                color = colors.textSecondary,
            )
            state.birthRecords.forEach { record ->
                val dateStr = TimeFormatUtils.formatIsoDate(record.bornAt)
                val genderLabel = if (record.isDaughter) "女儿" else "儿子"
                // WorldCard 接入（精修方案 v1.3）：独立列表项，归属当前角色。
                // P3-44 修复：生育记录可点击跳转到子代角色详情页
                WorldCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 批次3 3-6修复：传整个 record，由调用方根据 isDaughter
                        // 决定是否查子代ID再导航（男孩无子代角色不响应点击）
                        .clickable { onBirthRecordClick(record) },
                    ownerAccent = accentColor,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = genderLabel, style = type.body, color = colors.textPrimary)
                        Text(text = dateStr, style = type.caption, color = colors.textSecondary)
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xs))
        GoldDivider(withDiamond = false, fadeEdges = true)
    }

    // ── 终止妊娠二次确认弹窗 ─────────────────────────────────
    if (state.showTerminateConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissTerminate,
            title = { Text("确认终止妊娠", style = ZaijianTheme.typography.cardTitle) },
            text  = { Text("此操作不可撤销，将终止当前怀孕并记录为流产。是否继续？",
                style = ZaijianTheme.typography.body,
                color = ZaijianTheme.colors.textSecondary) },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(accentColor)
                        .clickable { onConfirmTerminate() }
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                ) {
                    Text("确认终止", style = ZaijianTheme.typography.button, color = Color.White)
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable { onDismissTerminate() }
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                ) {
                    Text("取消", style = ZaijianTheme.typography.button, color = ZaijianTheme.colors.textSecondary)
                }
            },
        )
    }
}

