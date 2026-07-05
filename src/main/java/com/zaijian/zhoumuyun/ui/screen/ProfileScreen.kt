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
import androidx.compose.foundation.layout.navigationBars

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
// GitHub 配置已移至专属管理页
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.provider.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
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

private fun ProviderType.toProviderId() = when (this) {
    ProviderType.DEEPSEEK    -> "deepseek"
    ProviderType.VOLCENGINE  -> "volcengine"
    ProviderType.ALIYUN      -> "aliyun"
    ProviderType.OPENCODEGO  -> "opencodego"
    ProviderType.CUSTOM      -> "custom"
}

// ── 设置项数据模型 ────────────────────────────────────────────

private data class SettingItem(
    val label: String,
    val description: String? = null,
    val trailingLabel: String? = null,
    val onClick: () -> Unit = {},
)

private data class SettingGroup(
    val title: String,
    val items: List<SettingItem>,
)

// ─────────────────────────────────────────────────────────────
//  ProfileScreen 主体
// ─────────────────────────────────────────────────────────────

@Composable
fun ProfileScreen(
    onNavigateToCharacter: (Int) -> Unit = {},
    onNavigateToProjects:  () -> Unit    = {},
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
        AppDatabase.getInstance(context).characterIdentityDao()
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
        // [v27 订正] 上一版注释里"NavigationBar 是 Material3 组件会自行消费
        // inset"的前提已不成立——AppNavigation.kt 从 v19 起就不再用 Material3
        // NavigationBar，改成了手绘 Row，其真实高度 = bottomNavHeight + 系统
        // 手势条 insetBottom（Box 外层直接加的，可见 AppNavigation.kt 里
        // `.height(Spacing.bottomNavHeight + insetBottom)`）。这里只留固定的
        // bottomNavHeight + md，没有把手势条 inset 算进去，手势条较高的机型上
        // 列表最后内容仍会被压住，需要显式把 navigationBars inset 加回来。
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBarInsetBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top    = statusBarHeight + Spacing.topBarHeight + Spacing.md,
                bottom = Spacing.bottomNavHeight + Spacing.md + navBarInsetBottom,
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

            // ── Step 1：进化项目快捷入口 ──────────────────────
            item {
                EvolutionProjectsEntry(onClick = onNavigateToProjects)
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
//  AiConfigSection — AI 提供商配置（接 ProviderManager）
// ─────────────────────────────────────────────────────────────

@Composable
private fun AiConfigSection() {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val pm      = remember { ProviderManager.instance }

    // ── 本地 UI State ──────────────────────────────────────

    // 当前选择的 ProviderType
    var selectedType by remember {
        val savedId = pm.getActiveProviderId()
        val initial = ProviderType.entries.find { it.toProviderId() == savedId }
            ?: ProviderType.DEEPSEEK
        mutableStateOf(initial)
    }

    // Key 输入（不显示明文）
    // 使用 TextFieldValue 以便在粘贴后将光标重置到首位，
    // 让用户能看到 key 的开头（如 sk-… / ms-…），而不是末尾。
    var apiKey by remember {
        mutableStateOf(
            TextFieldValue(
                text      = pm.getKey(selectedType.toProviderId()) ?: "",
                selection = TextRange(0),
            )
        )
    }
    var keyVisible by remember { mutableStateOf(false) }

    // 自定义 URL / Model（仅 CUSTOM 展示）
    var customUrl   by remember { mutableStateOf(pm.getCustomBaseUrl()) }
    var customModel by remember { mutableStateOf(pm.getCustomModel()) }

    // 需要用户填写模型名的平台（火山方舟 Endpoint ID / opencode go 模型名）
    var providerModel by remember { mutableStateOf(pm.getProviderModel(selectedType.toProviderId())) }

    // 下拉菜单
    var dropdownExpanded by remember { mutableStateOf(false) }

    // 测试状态：idle / testing / ok / fail
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }

    // 当提供商切换时，重新加载已存储的 Key；光标置于首位以显示前缀
    LaunchedEffect(selectedType) {
        val raw = pm.getKey(selectedType.toProviderId()) ?: ""
        apiKey        = TextFieldValue(text = raw, selection = TextRange(0))
        providerModel = pm.getProviderModel(selectedType.toProviderId())
        testState     = TestState.Idle
    }

    Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        // 分组标题
        Text(
            text     = "AI 配置",
            style    = type.label.copy(fontWeight = FontWeight.Medium),
            color    = colors.textSecondary,
            modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
        )

        WorldCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // ── 提供商选择器 ─────────────────────────────────
                Text(
                    text  = "提供商",
                    style = type.label,
                    color = colors.textSecondary,
                )
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(colors.bgElevated)
                            .clickable { dropdownExpanded = true }
                            .padding(horizontal = Spacing.md, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text  = selectedType.displayName,
                            style = type.body,
                            color = colors.textPrimary,
                        )
                        Icon(
                            imageVector        = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "展开",
                            tint               = colors.textSecondary,
                            modifier           = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded         = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier         = Modifier.background(
                            if (colors.isDark) colors.bgCard else Palette.White
                        ),
                    ) {
                        ProviderType.entries.forEach { pt ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text  = pt.displayName,
                                        style = type.body,
                                        color = if (pt == selectedType) colors.accent
                                                else colors.textPrimary,
                                    )
                                },
                                trailingIcon = if (pt == selectedType) ({
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint     = colors.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }) else null,
                                onClick = {
                                    selectedType     = pt
                                    dropdownExpanded = false
                                    // 保存活跃提供商
                                    pm.saveActiveProviderId(pt.toProviderId())
                                },
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, color = colors.border)

                // ── API Key 输入框 ────────────────────────────────
                Text(
                    text  = "API Key",
                    style = type.label,
                    color = colors.textSecondary,
                )
                OutlinedTextField(
                    value         = apiKey,
                    onValueChange = { new ->
                        // 粘贴检测：文本长度一次跳增超过 1 字符视为粘贴。
                        // 粘贴后将光标重置到首位，让用户看到 key 前缀而非末尾。
                        val isPaste = new.text.length - apiKey.text.length > 1
                        apiKey = if (isPaste) new.copy(selection = TextRange(0)) else new
                    },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    placeholder   = {
                        Text(
                            text  = "sk-…",
                            style = type.body,
                            color = colors.textDisabled,
                        )
                    },
                    visualTransformation = if (keyVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Text(
                            text  = if (keyVisible) "隐藏" else "显示",
                            style = type.caption,
                            color = colors.accent,
                            modifier = Modifier
                                .clickable { keyVisible = !keyVisible }
                                .padding(end = 8.dp),
                        )
                    },
                    textStyle = type.body.copy(color = colors.textPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = colors.accent,
                        unfocusedBorderColor = colors.border,
                        cursorColor          = colors.accent,
                    ),
                    shape = RoundedCornerShape(Radius.sm),
                )

                // ── 火山方舟 / opencode go：模型名输入框 ──────────
                val needsModelInput = selectedType == ProviderType.VOLCENGINE ||
                                      selectedType == ProviderType.OPENCODEGO
                if (needsModelInput) {
                    HorizontalDivider(thickness = 0.5.dp, color = colors.border)

                    val modelHint = if (selectedType == ProviderType.VOLCENGINE)
                        "接入点 ID（ep-xxxxxxxx-xxxxx）"
                    else
                        "模型名称（如 deepseek-v3）"

                    Text(
                        text  = modelHint,
                        style = type.label,
                        color = colors.textSecondary,
                    )
                    OutlinedTextField(
                        value         = providerModel,
                        onValueChange = { providerModel = it },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        placeholder   = {
                            Text(
                                text  = modelHint,
                                style = type.body,
                                color = colors.textDisabled,
                            )
                        },
                        textStyle = type.body.copy(color = colors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = colors.accent,
                            unfocusedBorderColor = colors.border,
                            cursorColor          = colors.accent,
                        ),
                        shape = RoundedCornerShape(Radius.sm),
                    )
                }

                // ── 自定义 Base URL / Model（CUSTOM 专属）────────
                if (selectedType == ProviderType.CUSTOM) {
                    HorizontalDivider(thickness = 0.5.dp, color = colors.border)

                    Text(
                        text  = "Base URL",
                        style = type.label,
                        color = colors.textSecondary,
                    )
                    OutlinedTextField(
                        value         = customUrl,
                        onValueChange = { customUrl = it },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        placeholder   = {
                            Text(
                                text  = "https://your-endpoint.com",
                                style = type.body,
                                color = colors.textDisabled,
                            )
                        },
                        textStyle = type.body.copy(color = colors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = colors.accent,
                            unfocusedBorderColor = colors.border,
                            cursorColor          = colors.accent,
                        ),
                        shape = RoundedCornerShape(Radius.sm),
                    )

                    Text(
                        text  = "模型名称",
                        style = type.label,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                    OutlinedTextField(
                        value         = customModel,
                        onValueChange = { customModel = it },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        placeholder   = {
                            Text(
                                text  = "gpt-4o / deepseek-chat / …",
                                style = type.body,
                                color = colors.textDisabled,
                            )
                        },
                        textStyle = type.body.copy(color = colors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = colors.accent,
                            unfocusedBorderColor = colors.border,
                            cursorColor          = colors.accent,
                        ),
                        shape = RoundedCornerShape(Radius.sm),
                    )
                }

                HorizontalDivider(thickness = 0.5.dp, color = colors.border)

                // ── 操作行：保存 + 测试连接 ──────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    // 保存按钮
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(colors.bgElevated)
                            .clickable {
                                val id = selectedType.toProviderId()
                                pm.saveActiveProviderId(id)
                                pm.saveKey(id, apiKey.text.trim())
                                if (selectedType == ProviderType.CUSTOM) {
                                    pm.saveCustomBaseUrl(customUrl.trim())
                                    pm.saveCustomModel(customModel.trim())
                                }
                                // 火山方舟 / opencode go：保存模型名/接入点 ID
                                if (selectedType == ProviderType.VOLCENGINE ||
                                    selectedType == ProviderType.OPENCODEGO) {
                                    pm.saveProviderModel(id, providerModel.trim())
                                }
                                // Fix-13-18：通过 ProviderManager.onProviderConfigChanged 回调
                                // 已在 ZaijianApp.onCreate 中注册自动装配，无需再手动调用。
                                testState = TestState.Saved
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = "保存",
                            style = type.body,
                            color = colors.textPrimary,
                        )
                    }

                    // 测试连接按钮
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(colors.accent)
                            .clickable(enabled = testState !is TestState.Testing) {
                                // 先保存再测试（与"保存"按钮保持一致）：
                                // 火山方舟/opencode go 的 defaultModel 必须先落盘，
                                // 否则 pm.activeProvider 读到的是上次保存的旧值（或空），
                                // testConnection() 会因 defaultModel 为空而"正确地"返回 false，
                                // 但对用户来说看起来像是"配置出错了"。
                                val id = selectedType.toProviderId()
                                pm.saveActiveProviderId(id)
                                pm.saveKey(id, apiKey.text.trim())
                                if (selectedType == ProviderType.CUSTOM) {
                                    pm.saveCustomBaseUrl(customUrl.trim())
                                    pm.saveCustomModel(customModel.trim())
                                }
                                if (selectedType == ProviderType.VOLCENGINE ||
                                    selectedType == ProviderType.OPENCODEGO) {
                                    pm.saveProviderModel(id, providerModel.trim())
                                }
                                testState = TestState.Testing
                                scope.launch {
                                    val provider = pm.activeProvider
                                    testState = if (provider != null && provider.testConnection()) {
                                        TestState.Ok
                                    } else {
                                        TestState.Fail
                                    }
                                }
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (testState) {
                            is TestState.Testing ->
                                CircularProgressIndicator(
                                    modifier  = Modifier.size(18.dp),
                                    color     = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            else ->
                                Text(
                                    text  = "测试连接",
                                    style = type.body,
                                    color = Color.White,
                                )
                        }
                    }
                }

                // ── 测试结果提示 ──────────────────────────────────
                when (testState) {
                    is TestState.Ok ->
                        StatusHint(text = "✓ 连接成功，可以开始对话了", color = Palette.SemanticSuccess)
                    is TestState.Fail ->
                        StatusHint(text = "✗ 连接失败，请检查 Key 或网络", color = Palette.SemanticError)
                    is TestState.Saved ->
                        StatusHint(text = "已保存", color = colors.textSecondary)
                    else -> Spacer(Modifier.height(0.dp))
                }
            }
        }
    }
}

private sealed class TestState {
    object Idle    : TestState()
    object Testing : TestState()
    object Ok      : TestState()
    object Fail    : TestState()
    object Saved   : TestState()
}

@Composable
private fun StatusHint(text: String, color: Color) {
    val type = ZaijianTheme.typography
    Text(
        text     = text,
        style    = type.caption,
        color    = color,
        modifier = Modifier.padding(top = 2.dp),
    )
}

// ─────────────────────────────────────────────────────────────
//  UserCard
// ─────────────────────────────────────────────────────────────

@Composable
private fun UserCard(
    userName: String  = "旅人",
    signature: String = "",
    onEdit: () -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    WorldCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                colors.accent.copy(alpha = 0.6f),
                                colors.accent.copy(alpha = 0.3f),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = userName.take(1),
                    style = type.cardTitle.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
                    color = Palette.White,
                )
            }

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = userName, style = type.cardTitle, color = colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                if (signature.isNotBlank()) {
                    Text(
                        text  = signature,
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                } else {
                    Text(
                        text  = "还没有签名，去写一句吧",
                        style = type.caption,
                        color = colors.textDisabled,
                    )
                }
            }

            Text(
                text     = "编辑",
                style    = type.caption,
                color    = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { onEdit() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  EditProfileDialog — 编辑用户昵称和签名（Phase 16）
// ─────────────────────────────────────────────────────────────

@Composable
private fun EditProfileDialog(
    initialName: String,
    initialSignature: String,
    onConfirm: (name: String, signature: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var name by remember { mutableStateOf(initialName) }
    var sig  by remember { mutableStateOf(initialSignature) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = colors.bgCard,
        title = {
            Text(text = "编辑资料", style = type.cardTitle, color = colors.textPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(text = "昵称", style = type.label, color = colors.textSecondary)
                OutlinedTextField(
                    value         = name,
                    onValueChange = { if (it.length <= 12) name = it },
                    placeholder   = { Text("旅人", style = type.body, color = colors.textDisabled) },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = colors.accent,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor     = colors.textPrimary,
                        unfocusedTextColor   = colors.textPrimary,
                        cursorColor          = colors.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(text = "签名", style = type.label, color = colors.textSecondary)
                OutlinedTextField(
                    value         = sig,
                    onValueChange = { if (it.length <= 40) sig = it },
                    placeholder   = { Text("说点什么吧…", style = type.body, color = colors.textDisabled) },
                    minLines      = 2,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = colors.accent,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor     = colors.textPrimary,
                        unfocusedTextColor   = colors.textPrimary,
                        cursorColor          = colors.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(name, sig) },
            ) {
                Text(text = "保存", color = colors.accent)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.textSecondary)
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  StatsRow
// ─────────────────────────────────────────────────────────────

@Composable
private fun StatsRow() {
    val context = LocalContext.current

    // Fix-08: 从 DB 读取真实统计数据
    var totalMessages    by remember { mutableIntStateOf(0) }
    var completedTasks   by remember { mutableIntStateOf(0) }
    var totalMemories    by remember { mutableIntStateOf(0) }

    // M4：getInstance() 只在首次组合时获取一次，不在协程体内每次重复调用
    val db = remember(context) { AppDatabase.getInstance(context) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // 从数据库获取所有角色ID（含女儿Agent角色）
            val allIds = db.characterIdentityDao().getAllIds()
            // 跨所有角色累计消息数
            val msgs  = allIds.sumOf { db.messageDao().countByCharacter(it) }
            // 已完成任务数
            val tasks = db.taskDao().countByStatus("completed")
            // 跨所有角色累计记忆条数
            val mems  = allIds.sumOf { db.memoryDao().count(it) }
            Triple(msgs, tasks, mems)
        }.let { (msgs, tasks, mems) ->
            totalMessages  = msgs
            completedTasks = tasks
            totalMemories  = mems
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
            StatCell(value = totalMessages.toString(),  label = "次对话")
            StatDivider()
            StatCell(value = completedTasks.toString(), label = "任务完成")
            StatDivider()
            StatCell(value = totalMemories.toString(),  label = "条记忆")
        }
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = type.titleBold, color = colors.accent)
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

// ─────────────────────────────────────────────────────────────
//  SettingGroupSection / SettingRow（通用设置项）
//
//  WorldCard 接入（精修方案 v1.3 第2/6节）：通用全局设置，不属于任何角色，
//  所以接 WorldCard 但不传 ownerAccent，L3 身份脊不显示，只取 L0-L2 常态层。
// ─────────────────────────────────────────────────────────────

@Composable
private fun SettingGroupSection(group: SettingGroup) {
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
private fun CharacterManagementSection(
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

// ─────────────────────────────────────────────────────────────
//  AppearanceSection — 外观设置（主题/字体/背景风格，可点击）
// ─────────────────────────────────────────────────────────────

@Composable
private fun AppearanceSection(
    themeLabel:    String,
    fontSizeLabel: String,
    bgStyleLabel:  String,
    onThemeClick:   () -> Unit,
    onFontClick:    () -> Unit,
    onBgStyleClick: () -> Unit,
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
            listOf(
                Triple("主题",       themeLabel,    onThemeClick),
                Triple("字体大小",    fontSizeLabel, onFontClick),
                Triple("公馆背景风格", bgStyleLabel,  onBgStyleClick),
            ).forEachIndexed { index, (label, value, onClick) ->
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
                if (index < 2) {
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
private fun NotificationSection(
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

@Composable
private fun OptionPickerDialog(
    title:    String,
    options:  List<String>,
    current:  Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = colors.bgCard,
        title = {
            Text(text = title, style = type.cardTitle, color = colors.textPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(
                                if (index == current) colors.accent.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .clickable { onSelect(index) }
                            .padding(horizontal = Spacing.md, vertical = 12.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text  = label,
                            style = type.body,
                            color = if (index == current) colors.accent else colors.textPrimary,
                        )
                        if (index == current) {
                            Icon(
                                imageVector        = Icons.Outlined.Check,
                                contentDescription = null,
                                tint               = colors.accent,
                                modifier           = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.textSecondary)
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────
//  Step 1：进化项目快捷入口（「我」页）
// ─────────────────────────────────────────────────────────────

/**
 * 在「我」页显示「进化项目」入口卡片，复用已有路由 AppRoute.ProjectList。
 * 纯 UI 添加，无数据依赖。
 */
@Composable
private fun EvolutionProjectsEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors      = ZaijianTheme.colors
    val type        = ZaijianTheme.typography
    val growthGreen = androidx.compose.ui.graphics.Color(0xFF7BAE7F)

    androidx.compose.material3.Surface(
        onClick        = onClick,
        shape          = androidx.compose.foundation.shape.RoundedCornerShape(
            com.zaijian.zhoumuyun.ui.theme.Radius.md
        ),
        color          = colors.bgCard,
        tonalElevation = 0.dp,
        modifier       = modifier
            .fillMaxWidth()
            .padding(horizontal = com.zaijian.zhoumuyun.ui.theme.Spacing.screenHorizontal)
            .border(
                1.dp,
                growthGreen.copy(alpha = 0.25f),
                androidx.compose.foundation.shape.RoundedCornerShape(
                    com.zaijian.zhoumuyun.ui.theme.Radius.md
                )
            ),
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = com.zaijian.zhoumuyun.ui.theme.Spacing.md,
                    vertical   = 14.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector        = Icons.Outlined.Spa,
                contentDescription = null,
                tint               = growthGreen,
                modifier           = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(com.zaijian.zhoumuyun.ui.theme.Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text      = "进化项目",
                    style     = type.label.copy(fontWeight = FontWeight.SemiBold),
                    color     = colors.textPrimary,
                )
                Text(
                    text  = "角色的长期成长方向",
                    style = type.small,
                    color = colors.textSecondary,
                )
            }
            Icon(
                imageVector        = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint               = colors.textDisabled,
                modifier           = Modifier.size(18.dp),
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
