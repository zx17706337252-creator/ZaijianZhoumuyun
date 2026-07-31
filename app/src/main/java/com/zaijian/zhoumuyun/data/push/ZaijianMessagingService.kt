package com.zaijian.zhoumuyun.data.push

import com.zaijian.zhoumuyun.util.ZLog
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.zaijian.zhoumuyun.ZaijianApp
import com.zaijian.zhoumuyun.data.AppContainer
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
            showFallbackNotification(characterId, characterName, jobTitle, summary)
            return
        }
        val msgHandler = CoroutineExceptionHandler { _, e ->
            ZLog.w(TAG, "Foreground message handling failed", e)
            // 批次4-8-6 修复：msgHandler 原先只记录日志，不调用 fallback 通知。
            // 同方法另外两处（appScope==null、sharedPresenceEngine==null）都已正确
            // 调用 showFallbackNotification，handler 漏掉了——消息处理异常时用户
            // 完全不知道任务已完成，只有日志里一条 warn。
            showFallbackNotification(characterId, characterName, jobTitle, summary)
        }
        msgScope.launch(msgHandler) {
            // 阶段2 S-2 遗留补项：此前直接访问 ZaijianApp.sharedPresenceEngine（可空，
            // 显式 null 检查处理冷启动竞态）。改为 AppContainer.instance.presenceEngine——
            // 二者在 ZaijianApp.onCreate() 内被赋值为同一实例，运行时行为不变。
            // B3审查序号13修复：原先用 runCatching 包裹 AppContainer.instance（!! 断言）
            // 来保留冷启动兜底，但这会连带吞掉 presenceEngine getter 内部任何其他异常，
            // 掩盖真实 bug。改用 instanceOrNull()——语义精确到"只在真的未初始化时才
            // 降级"，其他异常不再被静默吞掉。真正的竞态窗口发生在极早期冷启动、
            // onCreate() 尚未跑完时收到 FCM 消息，此时应降级到系统通知而不是让消息
            // 处理协程崩溃。
            val engine = AppContainer.instanceOrNull()?.presenceEngine
            if (engine == null) {
                ZLog.w(TAG, "AppContainer not yet initialized (cold-start race), falling back to system notification")
                showFallbackNotification(characterId, characterName, jobTitle, summary)
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
            // window13新问题2修复：fakeResult 此前只是内存对象，未写入数据库——
            // 用户点击 Toast「立即查看」进入任务中心时查不到对应记录，Toast 展示期间
            // 进程被杀也会导致该任务完成记录永久丢失。这里补上持久化，与 DB 轮询路径
            // （observeAndNotifyResults，通知的即是已入库数据）行为对齐。
            // jobResultDao.insert() 是 OnConflictStrategy.REPLACE，若云端权威数据
            // 稍后通过 DB 同步写入同一 id，会自然覆盖这里的临时记录，不会冲突。
            try {
                AppContainer.instance.jobResultDao.insert(fakeResult)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w(TAG, "Persist fakeResult failed for jobResultId=$jobResultId", e)
                // 落库失败不影响本次浮层展示，仅记录日志——用户仍能看到 Toast，
                // 只是"立即查看"跳转到任务中心时可能查不到这条（与此前行为一致，未劣化）。
            }
            engine.notifyTaskCompletion(
                result        = fakeResult,
                characterName = characterName,
                jobTitle      = jobTitle,
            )
        }
    }

    private fun showFallbackNotification(characterId: Int, characterName: String, jobTitle: String, summary: String) {
        // S3问题4修复：冷启动时 setupNotificationChannels() 可能尚未执行，
        // 在此兜底创建 task_result 渠道（createNotificationChannel 对已存在的渠道是幂等的）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (nm?.getNotificationChannel("task_result") == null) {
                // window13新问题1修复：与 ZaijianApp.setupNotificationChannels() 中
                // task_result 渠道的 importance 保持一致（IMPORTANCE_DEFAULT），
                // 避免同一渠道在正常注册路径与冷启动兜底路径之间出现不一致定义。
                nm.createNotificationChannel(
                    android.app.NotificationChannel("task_result", "任务结果", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
        // window13结论5修复：补上点击跳转Intent，复用与 ProactiveMessageNotifier.sendNotification()
        // 同款的 zaijian://chat/{characterId} 深链接 + ACTION_VIEW，与 FCM 前台Toast路径、
        // 主动消息通知路径跳转到同一个角色聊天页，不新增机制。
        val openIntent = android.content.Intent(this, com.zaijian.zhoumuyun.MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_VIEW
            data = android.net.Uri.parse("zaijian://${com.zaijian.zhoumuyun.MainActivity.HOST_CHAT}/$characterId")
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            characterId,  // requestCode 用 characterId，与 ProactiveMessageNotifier 同规则，同角色新通知覆盖旧 PendingIntent
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, "task_result")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$characterName 完成了任务")
            .setContentText("$jobTitle：$summary")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        // C类审查 #47 修复：统一改用 NotificationPermissionUtils.safeNotify，
        // 与全仓其余通知发送点保持一致（原本这里已有权限检查，现改为调用统一入口，
        // 便于以后只维护一处权限判定逻辑）
        com.zaijian.zhoumuyun.util.NotificationPermissionUtils.safeNotify(
            this, jobTitle.hashCode(), notification, TAG,
        )
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
            // 批次4-4 修复：apply() 是异步写入，如果进程在 apply() 提交到磁盘前
            // 被杀死，deviceId 会丢失，下次启动时重新生成——导致每次重启都生成新 ID，
            // 云端 device_tokens 表累积大量废弃 token 行。
            // 改为 commit() 同步写入，保证 deviceId 在返回前已持久化到磁盘。
            prefs.edit().putString(key, newId).commit()
            newId
        }
    }
}
