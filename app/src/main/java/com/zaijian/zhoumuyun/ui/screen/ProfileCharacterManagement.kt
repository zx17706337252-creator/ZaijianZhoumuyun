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
//  CharacterManagementSection — 角色管理入口（★ Phase 15 新增）
//
//  展示九位角色列表，点击跳转到 CharacterDetailScreen（人设/目标编辑）。
//  设计方案 §16：ProfileScreen → 角色管理 → 选择角色 → 编辑人设 / 编辑目标
//
//  WorldCard 接入（精修方案 v1.3 第2/6节）：每个格子明确归属一个具体角色，
//  传 char.breathColor 作为 ownerAccent，触发 L3 身份脊（左侧 2px 竖线），
//  一眼区分"这格是谁的"。这是九宫格首次补入清单，之前排查只数了独立卡片样式块，
//  漏掉了循环渲染的同款卡片，不算新范围。
// ─────────────────────────────────────────────────────────────

@Composable
internal fun CharacterManagementSection(
    onNavigateToCharacter: (Int) -> Unit,
    avatarOverrides: Map<Int, String> = emptyMap(),
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(text = "角色管理", style = type.cardTitle, color = colors.textPrimary)
            Text(text = "点击编辑人设与目标", style = type.caption, color = colors.textDisabled)
        }

        Text(
            text  = "在角色详情页可编辑人设（性格/风格/禁忌/信念）和目标，编辑后下次对话立即生效。",
            style = type.caption,
            color = colors.textSecondary,
        )

        DefaultCharacters.chunked(3).forEach { rowChars ->
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                rowChars.forEach { char ->
                    WorldCard(
                        modifier    = Modifier
                            .weight(1f)
                            .clickable { onNavigateToCharacter(char.id) },
                        ownerAccent = char.breathColor,
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            BreathingAvatar(
                                imageUrl     = avatarOverrides[char.id]?.takeIf { it.isNotBlank() } ?: char.avatarUrl,
                                breathColor  = char.breathColor,
                                statusType   = com.zaijian.zhoumuyun.data.model.StatusType.IDLE,
                                size         = AvatarSize.sm,
                                enableBreath = false,
                            )
                            Text(text = char.name, style = type.caption, color = colors.textPrimary)
                            val floorLabel = when (char.floor) {
                                com.zaijian.zhoumuyun.data.model.FloorEnum.SECOND   -> "二楼"
                                com.zaijian.zhoumuyun.data.model.FloorEnum.FIRST    -> "一楼"
                                com.zaijian.zhoumuyun.data.model.FloorEnum.BASEMENT -> "地下室"
                            }
                            Text(
                                text  = floorLabel,
                                style = type.caption.copy(fontSize = 10.sp),
                                color = colors.textDisabled,
                            )
                        }
                    }
                }
                repeat(3 - rowChars.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
