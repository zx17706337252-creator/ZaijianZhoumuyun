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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Palette
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
//  ProfileScreen — 「我」Tab（Phase 7 AI 配置接真实逻辑）
// ─────────────────────────────────────────────────────────────

// ── ProviderType → ProviderManager 内部 id 映射 ──────────────

internal fun ProviderType.toProviderId() = when (this) {
    ProviderType.DEEPSEEK    -> "deepseek"
    ProviderType.VOLCENGINE  -> "volcengine"
    ProviderType.ALIYUN      -> "aliyun"
    ProviderType.OPENCODEGO  -> "opencodego"
    ProviderType.CUSTOM      -> "custom"
}


// ── 设置项数据模型 ────────────────────────────────────────────

internal data class SettingItem(
    val label: String,
    val description: String? = null,
    val trailingLabel: String? = null,
    val onClick: () -> Unit = {},
)


internal data class SettingGroup(
    val title: String,
    val items: List<SettingItem>,
)


// ─────────────────────────────────────────────────────────────
//  ProfileScreen 主体
// ─────────────────────────────────────────────────────────────

@Composable
fun ProfileScreen(
    onNavigateToCharacter: (Int) -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val headerBg = if (colors.isDark)
        colors.bgBase.copy(alpha = GlassOpacity.topBarDark)
    else
        colors.bgBase.copy(alpha = GlassOpacity.topBarLight)

    // Phase 16：用户昵称 + 签名（用 SharedPreferences 持久化）
    val context = LocalContext.current
    val userPrefs = remember { context.getSharedPreferences("user_profile", android.content.Context.MODE_PRIVATE) }
    var userName  by remember { mutableStateOf(userPrefs.getString("user_name", "旅人") ?: "旅人") }
    var signature by remember { mutableStateOf(userPrefs.getString("user_signature", "") ?: "") }
    var showEditProfileDialog by remember { mutableStateOf(false) }

    // ── 外观设置（Fix-11: DataStore 持久化，响应式 Flow 驱动）──────
    val themeOptions     = remember { listOf("跟随系统", "深色", "浅色") }
    val fontSizeOptions  = remember { listOf("小", "标准", "大") }
    val bgStyleOptions   = remember { listOf("暗夜版", "极简版") }
    val scope            = rememberCoroutineScope()
    val appearanceStore  = remember { com.zaijian.zhoumuyun.data.datastore.AppearanceDataStore(context) }
    val themeIndex    by appearanceStore.themeIndexFlow.collectAsStateWithLifecycle(initialValue = 0)
    val fontSizeIndex by appearanceStore.fontSizeIndexFlow.collectAsStateWithLifecycle(initialValue = 1)
    val bgStyleIndex  by appearanceStore.bgStyleIndexFlow.collectAsStateWithLifecycle(initialValue = 0)
    var showThemeDialog    by remember { mutableStateOf(false) }
    var showFontDialog     by remember { mutableStateOf(false) }
    var showBgStyleDialog  by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }

    // ── 通知设置（SharedPreferences 持久化）─────────────────────
    var notifyMessages    by remember { mutableStateOf(userPrefs.getBoolean("notify_messages",   true)) }
    var notifyTaskDone    by remember { mutableStateOf(userPrefs.getBoolean("notify_task_done",  true)) }
    var proactiveEnabled  by remember { mutableStateOf(userPrefs.getBoolean("proactive_enabled", true)) }

    // ── 角色管理头像（P0-2 修复，升级为响应式 Flow）─────────────
    // DefaultCharacters 里的 avatarUrl 是硬编码默认值；用户在角色详情页
    // 上传的头像存在 character_identity.avatarUrl，这里订阅 Flow 实时组成
    // characterId → avatarUrl 的覆盖表，传给 CharacterManagementSection。
    // 原 LaunchedEffect(Unit)+getAll() 只在进入页面时读一次，identity 更新后不刷新；
    // 改为 produceState+observeAll() 后，头像上传会即时反映在「我」页面的角色列表里。
    val characterAvatarOverrides by produceState(initialValue = emptyMap<Int, String>()) {
        // 收尾交接清单 任务组A1：改走 AppContainer 共享的 identityRepo，
        // 不再在 Composable 内现拿 db 构造 Repository。
        AppContainer.instance.identityRepo
            .observeAll()
            .collect { entities ->
                value = entities.associate { it.characterId to it.avatarUrl }
            }
    }

    // 关于（静态）
    val aboutGroup = remember {
        SettingGroup(
            title = "关于",
            items = listOf(
                SettingItem("版本",    trailingLabel = "0.1.0-dev"),
                SettingItem("设计方案", description  = "再见周慕云 · v3.0",
                    onClick = { showChangelogDialog = true }),
                SettingItem("隐私政策",
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://zaijian.app/privacy")
                        )
                        context.startActivity(intent)
                    }),
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        // [v44 修复] 改用 LocalBottomBarHeight（唯一权威来源，见
        // AppNavigation.kt 定义处说明），不再自己重新读取
        // WindowInsets.navigationBars 计算——避免多处口径不一致导致
        // 内容仍能滚动进导航栏物理区域（2026-07-07 用户反馈：显示范围
        // 严禁超出导航栏上边缘）。
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top    = statusBarHeight + Spacing.topBarHeight + Spacing.md,
                bottom = LocalBottomBarHeight.current + Spacing.md,
            ),
        ) {
            // ── 用户信息卡片 ──────────────────────────────────
            item {
                UserCard(
                    userName  = userName,
                    signature = signature,
                    onEdit    = { showEditProfileDialog = true },
                )
                Spacer(Modifier.height(Spacing.md))
            }

            // ── 统计概览行 ────────────────────────────────────
            item {
                StatsRow()
                Spacer(Modifier.height(Spacing.lg))
            }

            // ── AI 配置（接真实 ProviderManager）────────────
            item {
                AiConfigSection()
                Spacer(Modifier.height(Spacing.lg))
            }

            // ── 角色管理（★ Phase 15 新增）────────────────────
            item {
                CharacterManagementSection(
                    onNavigateToCharacter = onNavigateToCharacter,
                    avatarOverrides       = characterAvatarOverrides,
                )
                Spacer(Modifier.height(Spacing.lg))
            }

            // ── 外观设置 ──────────────────────────────────────
            item {
                AppearanceSection(
                    themeLabel    = themeOptions[themeIndex],
                    fontSizeLabel = fontSizeOptions[fontSizeIndex],
                    bgStyleLabel  = bgStyleOptions[bgStyleIndex],
                    onThemeClick    = { showThemeDialog   = true },
                    onFontClick     = { showFontDialog    = true },
                    onBgStyleClick  = { showBgStyleDialog = true },
                )
                Spacer(Modifier.height(Spacing.md))
            }

            // ── 通知设置 ──────────────────────────────────────
            item {
                NotificationSection(
                    notifyMessages   = notifyMessages,
                    notifyTaskDone   = notifyTaskDone,
                    proactiveEnabled = proactiveEnabled,
                    onNotifyMessagesChange = { v ->
                        notifyMessages = v
                        userPrefs.edit().putBoolean("notify_messages", v).apply()
                    },
                    onNotifyTaskDoneChange = { v ->
                        notifyTaskDone = v
                        userPrefs.edit().putBoolean("notify_task_done", v).apply()
                    },
                    onProactiveEnabledChange = { v ->
                        proactiveEnabled = v
                        userPrefs.edit().putBoolean("proactive_enabled", v).apply()
                        // 角色主动发消息：开关实时生效，不用等下次启动 App
                        if (v) {
                            com.zaijian.zhoumuyun.data.agent.WorkManagerScheduler
                                .scheduleProactiveMessageCheck(context)
                        } else {
                            com.zaijian.zhoumuyun.data.agent.WorkManagerScheduler
                                .cancelProactiveMessageCheck(context)
                        }
                    },
                )
                Spacer(Modifier.height(Spacing.md))
            }

            // ── 关于 ──────────────────────────────────────────
            item {
                SettingGroupSection(aboutGroup)
                Spacer(Modifier.height(Spacing.md))
            }
        }

        // ── 固定顶部 Header ────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .border(
                    width = 0.5.dp,
                    color = colors.borderSubtle,
                    shape = RoundedCornerShape(0.dp),
                )
                .statusBarsPadding()
                .align(Alignment.TopCenter),
        ) {
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .height(Spacing.topBarHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
            Text(
                text     = "我",
                style    = type.navTitle,
                color    = colors.textPrimary,
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
            )
            }
        }

        // ── Phase 16：编辑用户资料 Dialog ─────────────────
        if (showEditProfileDialog) {
            EditProfileDialog(
                initialName      = userName,
                initialSignature = signature,
                onConfirm        = { newName, newSig ->
                    userName  = newName.ifBlank { "旅人" }
                    signature = newSig
                    userPrefs.edit()
                        .putString("user_name", userName)
                        .putString("user_signature", signature)
                        .apply()
                    showEditProfileDialog = false
                },
                onDismiss = { showEditProfileDialog = false },
            )
        }

        // ── 外观选择 Dialogs ──────────────────────────────
        if (showThemeDialog) {
            OptionPickerDialog(
                title   = "主题",
                options = themeOptions,
                current = themeIndex,
                onSelect = { idx ->
                    scope.launch { appearanceStore.setThemeIndex(idx) }
                    showThemeDialog = false
                },
                onDismiss = { showThemeDialog = false },
            )
        }
        if (showFontDialog) {
            OptionPickerDialog(
                title   = "字体大小",
                options = fontSizeOptions,
                current = fontSizeIndex,
                onSelect = { idx ->
                    scope.launch { appearanceStore.setFontSizeIndex(idx) }
                    showFontDialog = false
                },
                onDismiss = { showFontDialog = false },
            )
        }
        if (showBgStyleDialog) {
            OptionPickerDialog(
                title   = "公馆背景风格",
                options = bgStyleOptions,
                current = bgStyleIndex,
                onSelect = { idx ->
                    scope.launch { appearanceStore.setBgStyleIndex(idx) }
                    showBgStyleDialog = false
                },
                onDismiss = { showBgStyleDialog = false },
            )
        }

        if (showChangelogDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showChangelogDialog = false },
                title = { Text("更新日志") },
                text = {
                    Text(
                        "v0.1.0-dev\n" +
                        "• 圆桌群记忆系统\n" +
                        "• 女儿 Agent 人格系统\n" +
                        "• 文件库\n" +
                        "• 信任衰减 & 世界模拟"
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showChangelogDialog = false }
                    ) {
                        Text("关闭")
                    }
                },
            )
        }
    }
}


// ─────────────────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────

@Preview(
    name            = "Profile · Dark",
    showBackground  = true,
    backgroundColor = 0xFF12131A,
    widthDp         = 390,
    heightDp        = 844,
)
@Composable
private fun PreviewProfileDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) { ProfileScreen() }
}


@Preview(
    name           = "Profile · Light",
    showBackground = true,
    widthDp        = 390,
    heightDp       = 844,
)
@Composable
private fun PreviewProfileLight() {
    ZaijianTheme(appTheme = AppTheme.LIGHT) { ProfileScreen() }
}
