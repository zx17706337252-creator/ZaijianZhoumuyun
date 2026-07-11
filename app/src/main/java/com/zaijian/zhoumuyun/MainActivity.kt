package com.zaijian.zhoumuyun

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.zaijian.zhoumuyun.data.datastore.AppearanceDataStore
import com.zaijian.zhoumuyun.ui.screen.AppNavigation
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.util.ZLog

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * UI M5 修复：原 ACTION_OPEN_ROUTE 自定义 action + EXTRA_ROUTE 字符串 extra
         * 只是本 App 内部私有契约，不是 Android 原生深链接（无法被外部以标准 Uri 唤起，
         * 也绕开了 Navigation 的 deepLinks 校验机制）。现改用标准 ACTION_VIEW + zaijian://
         * scheme，配合 AndroidManifest 中新增的 BROWSABLE intent-filter。
         *
         * 路由约定：
         *   zaijian://chat/{characterId}      → 内部路由 "chat/{characterId}"
         *   zaijian://tasks?jobId={jobId}     → 内部路由 ROUTE_TASKS（jobId 存入 [pendingJobId]，
         *                                        预留给任务中心未来做高亮定位，当前尚未消费）
         *   zaijian://schedule/{characterId}  → 内部路由 "personal_schedule/{characterId}"
         *                                        （U2 修复：角色个人日程页深链接，目前由
         *                                        ScheduledJobWorker 通知的「查看日程」操作
         *                                        按钮触发，预留给未来其他通知/推送来源复用）
         */
        const val DEEP_LINK_SCHEME = "zaijian"
        const val HOST_CHAT        = "chat"
        const val HOST_TASKS       = "tasks"
        const val HOST_SCHEDULE    = "schedule"
        const val ROUTE_TASKS      = "tasks"
    }

    private var pendingRoute by mutableStateOf<String?>(null)

    /**
     * zaijian://tasks?jobId=xxx 携带的 jobId，传入 AppNavigation → TaskCenterScreen
     * 供任务中心高亮定位对应任务条目（Fix：之前设置后从未被任何组件读取）。
     */
    private var pendingJobId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        pendingRoute = extractRouteFromIntent(intent)

        // P1-2-23 修复：正常路径下 ZaijianApp.onCreate() 必定先于本方法执行
        // （Application.onCreate 早于任何 Activity.onCreate 是 Android 生命周期保证），
        // 所以这里的 null 分支理论上是死代码。保留可空回退而非改 lateinit，
        // 是为了在极端情况（初始化顺序被改坏、测试环境绕过 Application.onCreate 等）
        // 下优雅降级而不是直接崩溃；但原写法会静默创建一个脱离单例管理的新实例，
        // 掩盖了本不应发生的异常状态。改为记录警告日志，暴露异常但不崩溃。
        val appearanceStore = ZaijianApp.sharedAppearanceDataStore ?: run {
            ZLog.w("MainActivity", "sharedAppearanceDataStore 为 null，ZaijianApp.onCreate() 未按预期先行初始化，回退创建新实例")
            AppearanceDataStore(applicationContext)
        }

        setContent {
            val themeIndex    by appearanceStore.themeIndexFlow.collectAsStateWithLifecycle(initialValue = 0)
            val fontSizeIndex by appearanceStore.fontSizeIndexFlow.collectAsStateWithLifecycle(initialValue = 1)

            // ── 通知权限运行时请求（Android 13+ / API 33+）────────
            // Manifest 里声明 POST_NOTIFICATIONS 只是"申报"，系统不会自动弹窗，
            // 必须用 ActivityResultContracts.RequestPermission 主动触发系统授权弹窗。
            val context = LocalContext.current
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { /* 用户同意或拒绝都不阻塞主流程；拒绝时静默退化为无通知 */ }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // ── 精确闹钟权限（Android 12+ / API 31+）──────────
                // SCHEDULE_EXACT_ALARM 在 API 31+ 不再默认授予，
                // 需引导用户到系统设置页授权，否则角色提醒/每日修炼闹钟无法准时触发。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = context.getSystemService(AlarmManager::class.java)
                    if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:" + context.packageName)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            // 某些设备可能不支持此 Intent，忽略
                        }
                    }
                }
            }

            val appTheme = when (themeIndex) {
                1    -> AppTheme.DARK
                2    -> AppTheme.LIGHT
                else -> AppTheme.SYSTEM
            }
            val fontSizeScale = when (fontSizeIndex) {
                0    -> 0.88f
                2    -> 1.15f
                else -> 1.0f
            }

            ZaijianTheme(appTheme = appTheme, fontSizeScale = fontSizeScale) {
                AppNavigation(
                    pendingRoute            = pendingRoute,
                    onPendingRouteConsumed  = { pendingRoute = null },
                    pendingJobId            = pendingJobId,
                    onPendingJobIdConsumed  = { pendingJobId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractRouteFromIntent(intent)?.let { pendingRoute = it }
    }

    private fun extractRouteFromIntent(intent: Intent?): String? {
        if (intent == null) return null

        // UI M5 修复：标准 ACTION_VIEW + zaijian:// scheme 深链接解析
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return null
            if (uri.scheme != DEEP_LINK_SCHEME) return null
            return when (uri.host) {
                HOST_CHAT  -> uri.pathSegments.firstOrNull()?.let { "chat/$it" }
                HOST_TASKS -> {
                    pendingJobId = uri.getQueryParameter("jobId")
                    ROUTE_TASKS
                }
                // U2 修复：zaijian://schedule/{characterId} → personal_schedule/{characterId}
                // characterId 必须是合法 Int，解析失败（路径缺失/格式错误）时返回 null，
                // 不拼出一个无效路由喂给 NavController（避免 navigate 时找不到匹配目标而崩溃）。
                HOST_SCHEDULE -> uri.pathSegments.firstOrNull()
                    ?.toIntOrNull()
                    ?.let { "personal_schedule/$it" }
                else -> null
            }
        }

        // FCM 数据消息点击：extras 结构由服务端推送负载契约决定，不在本次深链接改造范围内。
        if (intent.getStringExtra("type") == "task_result") {
            val characterId = intent.getStringExtra("characterId")?.toIntOrNull()
            if (characterId != null) {
                return "chat/$characterId"
            }
            return ROUTE_TASKS
        }

        return null
    }
}
