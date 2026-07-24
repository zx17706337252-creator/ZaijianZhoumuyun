package com.zaijian.zhoumuyun.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.provider.ProviderType
import com.zaijian.zhoumuyun.ui.component.OptionPickerDialog
import com.zaijian.zhoumuyun.ui.component.RootTabTopBar
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
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
    profileViewModel: com.zaijian.zhoumuyun.ui.viewmodel.ProfileViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val headerBg = if (colors.isDark)
        colors.bgBase.copy(alpha = GlassOpacity.topBarDark)
    else
        colors.bgBase.copy(alpha = GlassOpacity.topBarLight)

    // Phase 16：用户昵称（用 SharedPreferences 持久化）
    // 窗口1方案B：签名字段已删除，不保留、不迁移。
    // 「称呼」功能性缺陷修复：不再由本 Composable 裸持有 SharedPreferences 实例，
    // 改走 ProfileViewModel.getUserName()——与 buildSystemPrompt 四条读取路径
    // 共用同一份 key 名/默认值定义（UserProfileRepository），避免两处硬编码字面量漂移。
    val context = LocalContext.current
    // 通知设置持久化（Phase 16 起沿用的简单 SharedPreferences 存储）。
    // P1-30 修复：原使用 "profile_prefs" 文件，但所有读取方
    // （ZaijianApp、ZaijianMessagingService、ProactiveMessageNotifier、
    // PresenceEngine）均从 "user_profile" 文件读取，导致写入的通知开关
    // 设置永远不会被读到，三个开关全部失效。
    // 现改为写入 "user_profile"，与所有读取方对齐；
    // ZaijianApp 中注册的 OnSharedPreferenceChangeListener 也将正确触发。
    val userPrefs = remember { context.getSharedPreferences("user_profile", android.content.Context.MODE_PRIVATE) }
    // E0 分层收口：原直接 AppContainer.instance.userProfileRepo.getUserName()，
    // 改走 ProfileViewModel.getUserName()，Composable 不再持有 Repository。
    var userName  by remember { mutableStateOf(profileViewModel.getUserName()) }
    var showEditNicknameDialog by remember { mutableStateOf(false) }

    // ── 外观设置（Fix-11: DataStore 持久化，响应式 Flow 驱动）──────
    val themeOptions     = remember { listOf("跟随系统", "深色", "浅色") }
    val fontSizeOptions  = remember { listOf("小", "标准", "大") }
    val bgStyleOptions   = remember { listOf("暗夜版", "极简版") }
    val scope            = rememberCoroutineScope()
    // S8-窗口01 修复：复用 ZaijianApp.onCreate() 中提前初始化的
    // sharedAppearanceDataStore 单例，与 AppNavigation 共享同一份实例，
    // 消除两处各自实例化导致的配置变更短暂不一致（DataStore 底层虽为单文件，
    // 但独立实例各自维护内存态 Flow，切换设置时可能出现短暂读到旧值的窗口）。
    val appearanceStore  = com.zaijian.zhoumuyun.ZaijianApp.sharedAppearanceDataStore
        ?: remember { com.zaijian.zhoumuyun.data.datastore.AppearanceDataStore(context) }
    val themeIndex    by appearanceStore.themeIndexFlow.collectAsStateWithLifecycle(initialValue = 0)
    val fontSizeIndex by appearanceStore.fontSizeIndexFlow.collectAsStateWithLifecycle(initialValue = 1)
    val bgStyleIndex  by appearanceStore.bgStyleIndexFlow.collectAsStateWithLifecycle(initialValue = 0)
    var showThemeDialog    by remember { mutableStateOf(false) }
    var showFontDialog     by remember { mutableStateOf(false) }
    var showBgStyleDialog  by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }

    // ── 门扉页（启动页）背景图设置 ──────────────────────────────
    // 与 sharedAppearanceDataStore 同一持有模式：优先用 AppContainer.instance
    // 里的单例（SplashScreen 也读同一份，避免两处各自持有导致短暂不一致）。
    val splashBgStore = com.zaijian.zhoumuyun.data.AppContainer.instance.splashBackgroundDataStore
    val splashBgConfig by splashBgStore.configFlow.collectAsStateWithLifecycle(initialValue = null)
    var showSplashBgActionDialog by remember { mutableStateOf(false) }
    var pendingSplashBgCropUri   by remember { mutableStateOf<String?>(null) }

    // 选图后先持久化读取权限（重启 App 后仍能访问），再进入裁剪步骤——
    // 不直接写入 DataStore，取景参数要等用户在裁剪弹窗确认后才一并保存。
    val splashBgImageLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            pendingSplashBgCropUri = uri.toString()
        } catch (e: Exception) {
            com.zaijian.zhoumuyun.util.ZLog.w("ProfileScreen", "启动页背景图设置失败: uri=$uri", e)
        }
    }

    // ── 通知设置（SharedPreferences 持久化）─────────────────────
    var notifyMessages    by remember { mutableStateOf(userPrefs.getBoolean("notify_messages",   true)) }
    var notifyTaskDone    by remember { mutableStateOf(userPrefs.getBoolean("notify_task_done",  true)) }
    var proactiveEnabled  by remember { mutableStateOf(userPrefs.getBoolean("proactive_enabled", true)) }

    // ── 角色管理头像（P0-2 修复，升级为响应式 Flow）─────────────
    // DefaultCharacters 里的 avatarUrl 是硬编码默认值；用户在角色详情页
    // 上传的头像存在 character_identity.avatarUrl。
    // S8-窗口01 修复：原先在此处 produceState + AppContainer.instance.identityRepo
    // 直接订阅 Flow，是 UI 层绕过 ViewModel 直接持有数据访问逻辑的分层违规。
    // 现改为订阅 ProfileViewModel.uiState.characterAvatarOverrides——ViewModel
    // 内部持续订阅 identityRepo.observeAll()，头像上传后仍会实时反映在本页，
    // 行为与之前完全一致，只是数据访问逻辑挪到了 ViewModel 里。
    val profileUiState by profileViewModel.uiState.collectAsStateWithLifecycle()
    val characterAvatarOverrides = profileUiState.characterAvatarOverrides

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
            // ── 统计概览行 ────────────────────────────────────
            item {
                StatsRow(
                    totalMessages  = profileUiState.totalMessages,
                    completedTasks = profileUiState.completedTasks,
                    totalMemories  = profileUiState.totalMemories,
                    isLoading      = profileUiState.isStatsLoading,
                )
                Spacer(Modifier.height(Spacing.lg))
            }

            // ── AI 配置（接真实 ProviderManager）────────────
            item {
                AiConfigSection(
                    userName       = userName,
                    onEditNickname = { showEditNicknameDialog = true },
                )
                Spacer(Modifier.height(Spacing.lg))
            }

            // ── 集成配置（GitHub / 邮箱，问题12 补齐缺失的配置入口）──
            item {
                IntegrationsSection()
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
                    // P2-36 修复：DataStore 中可能存储了超出范围的索引值，
                    // 直接索引访问会抛 IndexOutOfBoundsException，加 coerceIn 保护。
                    themeLabel    = themeOptions[themeIndex.coerceIn(0, themeOptions.lastIndex)],
                    fontSizeLabel = fontSizeOptions[fontSizeIndex.coerceIn(0, fontSizeOptions.lastIndex)],
                    bgStyleLabel  = bgStyleOptions[bgStyleIndex.coerceIn(0, bgStyleOptions.lastIndex)],
                    splashBgLabel = if (splashBgConfig != null) "已自定义" else "默认",
                    onThemeClick    = { showThemeDialog   = true },
                    onFontClick     = { showFontDialog    = true },
                    onBgStyleClick  = { showBgStyleDialog = true },
                    onSplashBgClick = {
                        // 还没设置过图时直接打开选图器；已经设置过图时弹出
                        // "更换图片 / 恢复默认"的小 AlertDialog，避免用户设置后
                        // 无法撤销。
                        if (splashBgConfig != null) {
                            showSplashBgActionDialog = true
                        } else {
                            splashBgImageLauncher.launch(arrayOf("image/*"))
                        }
                    },
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

        // ── 固定顶部 Header（窗口4：统一为 RootTabTopBar）────────
        RootTabTopBar(
            title    = "我",
            headerBg = headerBg,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // ── 编辑称呼 Dialog（窗口1方案B：不再是"编辑资料"，只编辑称呼）──
        if (showEditNicknameDialog) {
            EditNicknameDialog(
                initialName = userName,
                onConfirm   = { newName ->
                    profileViewModel.setUserName(newName)
                    userName = profileViewModel.getUserName()
                    showEditNicknameDialog = false
                },
                onDismiss = { showEditNicknameDialog = false },
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

        // ── 启动页背景图：更换/恢复默认 操作弹窗 ──────────────
        // 语义是"动作型菜单"而非"当前选中哪一项"，不适合复用
        // OptionPickerDialog（它是为高亮当前选项设计的），故用一个跟本文件
        // 已有的"更新日志"弹窗同风格的 AlertDialog。
        if (showSplashBgActionDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSplashBgActionDialog = false },
                title = { Text("启动页背景图") },
                text  = { Text("更换一张新图片，或恢复默认的品馆呼吸 Logo 视觉。") },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showSplashBgActionDialog = false
                            splashBgImageLauncher.launch(arrayOf("image/*"))
                        }
                    ) { Text("更换图片") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showSplashBgActionDialog = false
                            scope.launch { splashBgStore.clearBackground() }
                        }
                    ) { Text("恢复默认") }
                },
            )
        }

        // ── 启动页背景图：裁剪弹窗 ─────────────────────────────
        // pendingSplashBgCropUri 非空时显示，复用聊天背景同一套
        // AvatarCropDialog(shape = FULL_SCREEN) 竖版全屏取景交互，确认后
        // 一次性把 URI + 偏移/缩放写入 SplashBackgroundDataStore。
        pendingSplashBgCropUri?.let { pendingUriString ->
            com.zaijian.zhoumuyun.ui.component.AvatarCropDialog(
                uri       = android.net.Uri.parse(pendingUriString),
                shape     = com.zaijian.zhoumuyun.ui.component.CropShape.FULL_SCREEN,
                onConfirm = { params ->
                    pendingSplashBgCropUri = null
                    scope.launch {
                        splashBgStore.setBackgroundConfig(
                            com.zaijian.zhoumuyun.data.datastore.SplashBackgroundConfig(
                                uri     = pendingUriString,
                                offsetX = params.normalizedOffsetX,
                                offsetY = params.normalizedOffsetY,
                                scale   = params.scale,
                            )
                        )
                    }
                },
                onDismiss = { pendingSplashBgCropUri = null },
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
