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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
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
//  AppearanceSection — 外观设置（主题/字体/背景风格，可点击）
// ─────────────────────────────────────────────────────────────

@Composable
internal fun AppearanceSection(
    themeLabel:      String,
    fontSizeLabel:   String,
    bgStyleLabel:    String,
    splashBgLabel:   String,
    onThemeClick:    () -> Unit,
    onFontClick:     () -> Unit,
    onBgStyleClick:  () -> Unit,
    onSplashBgClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        Text(
            text     = "外观",
            style    = type.label.copy(fontWeight = FontWeight.Medium),
            color    = colors.textSecondary,
            modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
        )
        // WorldCard 接入（精修方案 v1.3）：外观设置分组列表容器，
        // 含多个跳转项不归属单一角色，不传 ownerAccent。
        WorldCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val rows = listOf(
                Triple("主题",         themeLabel,    onThemeClick),
                Triple("字体大小",      fontSizeLabel, onFontClick),
                Triple("公馆背景风格",   bgStyleLabel,  onBgStyleClick),
                Triple("启动页背景图",  splashBgLabel, onSplashBgClick),
            )
            rows.forEachIndexed { index, (label, value, onClick) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClick() }
                        .padding(horizontal = Spacing.md, vertical = 14.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = label, style = type.body, color = colors.textPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = value, style = type.caption, color = colors.textSecondary)
                        Spacer(Modifier.width(4.dp))
                        Text(text = "›", style = type.cardTitle, color = colors.textDisabled)
                    }
                }
                if (index < rows.lastIndex) {
                    HorizontalDivider(
                        modifier  = Modifier.padding(start = Spacing.md),
                        thickness = 0.5.dp,
                        color     = colors.border,
                    )
                }
            }
        }
        }
    }
}


// ─────────────────────────────────────────────────────────────
//  NotificationSection — 通知设置（Switch 真实可切换 + 持久化）
// ─────────────────────────────────────────────────────────────

@Composable
internal fun NotificationSection(
    notifyMessages:          Boolean,
    notifyTaskDone:          Boolean,
    proactiveEnabled:        Boolean,
    onNotifyMessagesChange:  (Boolean) -> Unit,
    onNotifyTaskDoneChange:  (Boolean) -> Unit,
    onProactiveEnabledChange:(Boolean) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        Text(
            text     = "通知",
            style    = type.label.copy(fontWeight = FontWeight.Medium),
            color    = colors.textSecondary,
            modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
        )
        // WorldCard 接入（精修方案 v1.3）：通知设置分组列表容器，
        // 含多项开关不归属单一角色，不传 ownerAccent。
        WorldCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 消息通知
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "消息通知", style = type.body, color = colors.textPrimary)
                Switch(
                    checked         = notifyMessages,
                    onCheckedChange = onNotifyMessagesChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor       = Palette.White,
                        checkedTrackColor       = colors.accent,
                        uncheckedThumbColor     = colors.textDisabled,
                        uncheckedTrackColor     = colors.bgElevated,
                        uncheckedBorderColor    = colors.border,
                    ),
                )
            }
            HorizontalDivider(
                modifier  = Modifier.padding(start = Spacing.md),
                thickness = 0.5.dp,
                color     = colors.border,
            )
            // 任务完成提醒
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "任务完成提醒", style = type.body, color = colors.textPrimary)
                Switch(
                    checked         = notifyTaskDone,
                    onCheckedChange = onNotifyTaskDoneChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor       = Palette.White,
                        checkedTrackColor       = colors.accent,
                        uncheckedThumbColor     = colors.textDisabled,
                        uncheckedTrackColor     = colors.bgElevated,
                        uncheckedBorderColor    = colors.border,
                    ),
                )
            }
            HorizontalDivider(
                modifier  = Modifier.padding(start = Spacing.md),
                thickness = 0.5.dp,
                color     = colors.border,
            )
            // 角色主动发言
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "角色主动发言", style = type.body, color = colors.textPrimary)
                    Text(
                        text  = "关闭后角色不会主动向你发送消息",
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                }
                Switch(
                    checked         = proactiveEnabled,
                    onCheckedChange = onProactiveEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor       = Palette.White,
                        checkedTrackColor       = colors.accent,
                        uncheckedThumbColor     = colors.textDisabled,
                        uncheckedTrackColor     = colors.bgElevated,
                        uncheckedBorderColor    = colors.border,
                    ),
                )
            }
        }
        }
    }
}


// ─────────────────────────────────────────────────────────────
//  OptionPickerDialog — 单选弹窗（外观设置通用）
// ─────────────────────────────────────────────────────────────

// OptionPickerDialog 已收敛至 ui/component/CommonDialogs.kt（架构瘦身 Phase 1 第4项）
