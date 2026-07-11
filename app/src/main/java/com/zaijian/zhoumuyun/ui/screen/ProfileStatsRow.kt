package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// GitHub 配置已移至专属管理页
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.provider.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.component.OptionPickerDialog
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import kotlinx.coroutines.launch


// ─────────────────────────────────────────────────────────────
//  StatsRow
// ─────────────────────────────────────────────────────────────

@Composable
internal fun StatsRow() {
    // Fix-08: 从 DB 读取真实统计数据
    // 3.5 修复：新增 isLoading，加载完成前不展示 0，避免"0 次对话"被误读为
    // "确实没有任何记录"——两者在 UI 上此前完全无法区分。
    var totalMessages    by remember { mutableIntStateOf(0) }
    var completedTasks   by remember { mutableIntStateOf(0) }
    var totalMemories    by remember { mutableIntStateOf(0) }
    var isLoading         by remember { mutableStateOf(true) }

    // 收尾交接清单 任务组A2：改走 AppContainer 共享的 identityRepo/messageRepo/
    // taskRepo/memoryRepo，不再在 Composable 内现拿 db 构造 Repository或
    // 裸调用 db.taskDao()/db.memoryDao()。
    val identityRepo = AppContainer.instance.identityRepo
    val messageRepo  = AppContainer.instance.messageRepo
    val taskRepo     = AppContainer.instance.taskRepo
    val memoryRepo   = AppContainer.instance.memoryRepo

    LaunchedEffect(Unit) {
        isLoading = true
        withContext(Dispatchers.IO) {
            // 从数据库获取所有角色ID（含女儿Agent角色）
            val allIds = identityRepo.getAllIds()
            // 跨所有角色累计消息数
            val msgs  = allIds.sumOf { messageRepo.countByCharacter(it) }
            // 已完成任务数
            val tasks = taskRepo.countByStatus("completed")
            // 跨所有角色累计记忆条数
            val mems  = allIds.sumOf { memoryRepo.count(it) }
            Triple(msgs, tasks, mems)
        }.let { (msgs, tasks, mems) ->
            totalMessages  = msgs
            completedTasks = tasks
            totalMemories  = mems
            isLoading      = false
        }
    }

    WorldCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatCell(value = totalMessages.toString(),  label = "次对话",   isLoading = isLoading)
            StatDivider()
            StatCell(value = completedTasks.toString(), label = "任务完成", isLoading = isLoading)
            StatDivider()
            StatCell(value = totalMemories.toString(),  label = "条记忆",   isLoading = isLoading)
        }
    }
}


@Composable
private fun StatCell(value: String, label: String, isLoading: Boolean = false) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 3.5 修复：加载中用小号 spinner 占位而非直接显示 "0"——旧写法初始值为 0，
        // 加载完成前的一瞬间会展示"0 次对话"，与"确实是 0 次对话"完全无法区分。
        // 用固定高度的 Box 包裹两种状态，避免 spinner 换成数字文本时那一行高度变化、
        // 造成下方 label 与其他 StatCell 抖动错位。
        Box(
            modifier = Modifier.height(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(16.dp),
                    color       = colors.accent,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(text = value, style = type.titleBold, color = colors.accent)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(text = label, style = type.label, color = colors.textSecondary)
    }
}


@Composable
private fun StatDivider() {
    val colors = ZaijianTheme.colors
    Box(
        modifier = Modifier
            .width(0.5.dp)
            .height(36.dp)
            .background(colors.border),
    )
}
