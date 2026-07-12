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
import com.zaijian.zhoumuyun.data.db.AppDatabase
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
//  SettingGroupSection / SettingRow（通用设置项）
//
//  WorldCard 接入（精修方案 v1.3 第2/6节）：通用全局设置，不属于任何角色，
//  所以接 WorldCard 但不传 ownerAccent，L3 身份脊不显示，只取 L0-L2 常态层。
// ─────────────────────────────────────────────────────────────

@Composable
internal fun SettingGroupSection(group: SettingGroup) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        Text(
            text     = group.title,
            style    = type.label.copy(fontWeight = FontWeight.Medium),
            color    = colors.textSecondary,
            modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
        )
        WorldCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                group.items.forEachIndexed { index, item ->
                    SettingRow(item)
                    if (index < group.items.lastIndex) {
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


@Composable
private fun SettingRow(item: SettingItem) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { item.onClick() }
            .padding(horizontal = Spacing.md, vertical = 14.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.label, style = type.body, color = colors.textPrimary)
            if (item.description != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = item.description, style = type.label, color = colors.textSecondary)
            }
        }
        if (item.trailingLabel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = item.trailingLabel,
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.width(4.dp))
                Text(text = "›", style = type.cardTitle, color = colors.textDisabled)
            }
        } else {
            Text(text = "›", style = type.cardTitle, color = colors.textDisabled)
        }
    }
}
