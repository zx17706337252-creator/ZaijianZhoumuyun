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
//  ProfileMiscRow — 折叠跳转入口行（UI 升级 v2.0 帧20 我的页）
//  「外观 · 昼夜跟随系统 ›」「通知 · 简报与牵挂 ›」这类单行导航，
//  替代原先完整展开的设置面板，点击后由调用方弹层承载具体设置。
// ─────────────────────────────────────────────────────────────

@Composable
internal fun ProfileMiscRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.screenHorizontal, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(Spacing.sm))
        Text(text = title, style = type.body, color = colors.textPrimary)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(text = "· $subtitle", style = type.caption, color = colors.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        Text(text = trailing, style = type.cardTitle, color = colors.textDisabled)
    }
}


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
    systemNotificationsEnabled: Boolean,
    onOpenSystemNotificationSettings: () -> Unit,
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
            // C类审查 #46/#48 修复：系统级 POST_NOTIFICATIONS 权限被拒绝时，
            // 下面的开关即使显示"开"也不会真的收到任何通知（Android 13+ notify()
            // 静默 no-op）。这里的提示条让用户能看到"为什么设置开着却收不到通知"，
            // 而不是像原来那样开关状态和实际效果完全脱节、无从排查。
            if (!systemNotificationsEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                        .clickable(onClick = onOpenSystemNotificationSettings),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text  = "系统通知权限未开启",
                            style = type.body.copy(fontWeight = FontWeight.Medium),
                            color = Palette.SemanticWarning,
                        )
                        Text(
                            text  = "下面的开关不会生效，需要先在系统设置里允许通知",
                            style = type.caption,
                            color = colors.textSecondary,
                        )
                    }
                    Text(
                        text  = "去设置",
                        style = type.body,
                        color = colors.accent,
                    )
                }
                HorizontalDivider(
                    modifier  = Modifier.padding(start = Spacing.md),
                    thickness = 0.5.dp,
                    color     = colors.border,
                )
            }
            // 消息通知
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 14.dp),
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
                    .padding(horizontal = Spacing.md, vertical = 14.dp),
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
                    .padding(horizontal = Spacing.md, vertical = 14.dp),
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
//  PregnancySettingsSection — 怀孕功能总开关（C3#9 修复）
//
//  p5_trigger_enabled 此前读写两端全仓为零，DataStore 字段完全是摆设。
//  P5 = 怀孕自动触发判定链路代号（叙事解锁 + 伴侣同意 + 周期判定，见
//  PregnancyTriggerManager），本开关现已接入该链路的两个上游入口
//  （checkTrigger/shouldEvaluateFertileWindowConsent）作为总门禁：
//  关闭后角色不会再自动触发新的怀孕，已怀孕角色的孕期进程不受影响。
// ─────────────────────────────────────────────────────────────

@Composable
internal fun PregnancySettingsSection(
    p5TriggerEnabled: Boolean,
    onP5TriggerEnabledChange: (Boolean) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        Text(
            text     = "孕育系统",
            style    = type.label.copy(fontWeight = FontWeight.Medium),
            color    = colors.textSecondary,
            modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
        )
        WorldCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 14.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "怀孕功能", style = type.body, color = colors.textPrimary)
                    Text(
                        text  = "关闭后角色不会自动触发新的怀孕，已怀孕角色不受影响",
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                }
                Switch(
                    checked         = p5TriggerEnabled,
                    onCheckedChange = onP5TriggerEnabledChange,
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


// ─────────────────────────────────────────────────────────────
//  OptionPickerDialog — 单选弹窗（外观设置通用）
// ─────────────────────────────────────────────────────────────

// OptionPickerDialog 已收敛至 ui/component/CommonDialogs.kt（架构瘦身 Phase 1 第4项）
