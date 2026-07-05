package com.zaijian.zhoumuyun.data.push

import com.zaijian.zhoumuyun.util.ZLog
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.zaijian.zhoumuyun.ZaijianApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch

private const val TAG = "ZaijianMsgService"

/**
 * Phase 30 方案六：FCM 消息服务
 *
 * 职责：
 * 1. [onNewToken]       — FCM token 刷新时上传到 Supabase device_tokens 表
 * 2. [onMessageReceived]— App 在**前台**时收到推送：压制系统通知，转发给 PresenceEngine
 *                         触发方案二的 TaskCompletionToast 浮层，避免重复打扰
 *
 * App 在**后台/已关闭**时，FCM 系统托管自动弹出系统通知，不经过本方法。
 */
class ZaijianMessagingService : FirebaseMessagingService() {

    /**
     * FCM token 刷新时自动回调（首次安装 / token 过期 / App 重装）。
     * 将新 token 上传到 Supabase device_tokens 表，供 Edge Function 查询推送。
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        ZLog.d(TAG, "FCM token refreshed")
        // P-15 修复：token 上传改走 WorkManager OneTimeWorkRequest + 指数退避。
        // 原方案：appScope.launch { SupabaseClient.upsertDeviceToken(...) }
        //   → 失败后无重试，依赖下次 token 刷新（可能数周后）。
        // 新方案：FcmTokenUploadWorker.enqueue()
        //   → 系统保证进程被杀/重启后仍执行；EXPONENTIAL 退避自动重试直到成功。
        FcmTokenUploadWorker.enqueue(
            context = applicationContext,
            token   = token,
            userId  = getOrCreateDeviceId(),
        )
    }

    /**
     * App 在**前台**时收到 FCM 消息。
     *
     * 逻辑：
     * - 仅处理 type = "task_result" 的数据消息
     * - 通过 PresenceEngine.notifyTaskCompletion() 触发方案二的应用内浮层
     * - 不弹出系统通知（避免前台场景下的双重打扰）
     *
     * 后台/离屏时系统自动展示通知，不走此回调。
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // Fix-06: 尊重用户「消息通知」开关
        val prefs = getSharedPreferences("user_profile", MODE_PRIVATE)
        if (!prefs.getBoolean("notify_messages", true)) return

        val data = message.data
        if (data["type"] != "task_result") return

        val jobResultId  = data["jobResultId"]  ?: return
        val characterId  = data["characterId"]?.toIntOrNull() ?: return
        val characterName = data["characterName"] ?: return
        val jobTitle     = data["jobTitle"]      ?: "任务"
        val status       = data["status"]        ?: "success"
        val summary      = data["summary"]       ?: if (status == "success") "任务已完成" else "任务遇到问题"

        ZLog.d(TAG, "Foreground task_result: char=$characterId job=$jobTitle")

        // 转发给 PresenceEngine → CharacterScreen 的 TaskCompletionToast
        // P1-13-21 修复：复用 appScope
        // P-15 修复：删除 ?: CoroutineScope(Dispatchers.IO) 兜底，appScope 为 null 时直接 return + log。
        // 加 CoroutineExceptionHandler 防止前台消息处理异常导致协程崩溃。
        val msgScope = ZaijianApp.appScope ?: run {
            // L-1 修复：原先 appScope 为 null 时直接 return，消息被静默丢弃，不会触发
            // 任何 fallback 通知。这里改为先发系统通知兜底，再 return，避免冷启动瞬间
            // 收到 FCM 推送时用户完全无感知。
            ZLog.w(TAG, "appScope is null (cold-start race), falling back to system notification")
            showFallbackNotification(characterName, jobTitle, summary)
            return
        }
        val msgHandler = CoroutineExceptionHandler { _, e ->
            ZLog.w(TAG, "Foreground message handling failed", e)
        }
        msgScope.launch(msgHandler) {
            val engine = ZaijianApp.sharedPresenceEngine
            if (engine == null) {
                ZLog.w(TAG, "sharedPresenceEngine is null (cold-start race), falling back to system notification")
                showFallbackNotification(characterName, jobTitle, summary)
                return@launch
            }

            // 构造一个轻量的 JobResultEntity 替身，只携带浮层所需字段
            val fakeResult = com.zaijian.zhoumuyun.data.db.entity.JobResultEntity(
                id           = jobResultId,
                jobId        = data["jobId"] ?: jobResultId,
                characterId  = characterId,
                toolName     = data["toolName"] ?: "",
                status       = status,
                output       = if (status == "success") summary else null,
                errorMessage = if (status == "failed")  summary else null,
                executedBy   = "cloud",
                startedAt    = System.currentTimeMillis(),
                completedAt  = System.currentTimeMillis(),
                isRead       = false,
                createdAt    = System.currentTimeMillis(),
            )
            engine.notifyTaskCompletion(
                result        = fakeResult,
                characterName = characterName,
                jobTitle      = jobTitle,
            )
        }
    }

    private fun showFallbackNotification(characterName: String, jobTitle: String, summary: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            if (granted != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ZLog.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping fallback notification")
                return
            }
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, "task_result")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$characterName 完成了任务")
            .setContentText("$jobTitle：$summary")
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager?.notify(jobTitle.hashCode(), notification)
    }

    // ── 内部工具 ──────────────────────────────────────────────

    /**
     * 获取或创建稳定的设备标识符（存于 SharedPreferences）。
     * 不依赖 ANDROID_ID（重装即变），首次生成 UUID 并持久化。
     */
    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("zaijian_device", MODE_PRIVATE)
        val key   = "device_id"
        return prefs.getString(key, null) ?: run {
            val newId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(key, newId).apply()
            newId
        }
    }
}
