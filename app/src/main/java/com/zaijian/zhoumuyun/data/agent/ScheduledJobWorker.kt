package com.zaijian.zhoumuyun.data.agent

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.MainActivity
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.JobResultEntity
import com.zaijian.zhoumuyun.util.ZLog
import org.json.JSONObject
import java.util.UUID

/**
 * ScheduledJobWorker — WorkManager 后台任务执行器
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责：在 App 不在前台、甚至进程被杀掉的情况下，
 *   由系统在预定时间拉起，执行单条 scheduled_jobs 任务。
 *
 * 与现有 runLocalCompensation() 的关系：
 *   runLocalCompensation() — App 启动时补跑所有到期任务（兜底）
 *   ScheduledJobWorker     — 系统级精确调度，到点主动唤醒执行（主路径）
 *   两者互补，不冲突。Worker 执行成功后会更新 nextRunAt，
 *   补跑逻辑发现任务已执行则自然跳过。
 *
 * Input Data（通过 WorkRequest.setInputData 传入）：
 *   KEY_JOB_ID — scheduled_jobs 表中的任务 ID（String）
 *
 * 硬限制说明：
 *   国内厂商（小米/华为/OPPO/vivo）省电策略激进，
 *   用户未开自启动权限/电池白名单时仍可能不被唤醒。
 *   Worker 内会检测执行环境并在通知中给出提示。
 *
 * Gradle 依赖（在 app/build.gradle 的 dependencies 块添加）：
 *   implementation("androidx.work:work-runtime-ktx:2.9.0")
 * ═══════════════════════════════════════════════════════════════
 */
class ScheduledJobWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_JOB_ID        = "job_id"
        const val CHANNEL_ID        = "zaijian_schedule"
        const val CHANNEL_NAME      = "再见公馆 · 定时任务"
        const val MAX_RETRY_COUNT   = 3
        // P1-33：与 ScheduleRepository.LOCK_TTL_MS 保持一致，两条执行路径共用同一把锁
        private const val LOCK_TTL_MS = 3 * 60 * 1000L
        // 批次1 1-5修复：防御纵深校验的时钟漂移容忍量（5秒）
        private const val GRACE_MS = 5_000L
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)
            ?: return Result.failure()

        val db             = AppDatabase.getInstance(context)
        val scheduledJobDao = db.scheduledJobDao()
        val jobResultDao   = db.jobResultDao()

        // 查询任务
        val job = scheduledJobDao.findById(jobId) ?: return Result.failure()
        if (!job.enabled) return Result.success()  // 已禁用，正常退出

        // P1-33 修复：与 ScheduleRepository.runLocalCompensation() 共用同一把
        // 认领锁。App 启动瞬间系统可能恰好也唤醒本 Worker 执行同一条到期任务，
        // 认领失败说明对方已经在处理，本次直接正常结束，不重复执行/重复通知。
        val claimNow = System.currentTimeMillis()
        val lockExpiry = claimNow + LOCK_TTL_MS
        val claimed = scheduledJobDao.claimJob(jobId, claimNow, lockExpiry)
        if (claimed == 0) return Result.success()

        // 批次1 1-5修复（防御纵深）：claimJob 只检查锁竞争，不检查 nextRunAt。
        // 场景：runLocalCompensation 先抢锁执行完任务并更新 nextRunAt 到未来，
        // 但因 context 为 null（1-5主修复前）未重新入队；WorkManager 自动恢复的
        // Worker 后到，claimJob 成功（锁已释放），若不加此校验会二次执行同一任务。
        // 主修复（补 context）已让重新入队生效，此处作为防御纵深：claimJob 成功后
        // 再查一次 job，若 nextRunAt 已被推到未来（说明刚被执行过），直接释放锁退出。
        // GRACE_MS 容忍时钟微小漂移（5秒）。
        val refreshedJob = scheduledJobDao.findById(jobId)
        if (refreshedJob != null && refreshedJob.nextRunAt > claimNow + GRACE_MS) {
            scheduledJobDao.releaseLock(jobId)
            return Result.success()
        }

        val startedAt = System.currentTimeMillis()

        // 解析工具参数
        val baseParams: Map<String, String> = try {
            val json = JSONObject(job.toolParamsJson)
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (_: Exception) {
            emptyMap()
        }
        // P-8 修复：注入 __character_id，工具执行时优先从 params 读取角色 ID，
        // 避免全局单例闭包读到前台会话角色（ChatViewModel.currentCharacterId）导致串数据。
        val params: Map<String, String> = baseParams + mapOf("__character_id" to job.characterId.toString())

        // 执行工具（复用 AgentToolRegistry，与 runLocalCompensation 一致）
        // 日程系统批次3新增：按 job.toolName 分叉。
        //   - 工单型（mode B，toolName == AgentTaskJobExecutor.SENTINEL）：不调任何已注册
        //     工具，改走 AgentTaskJobExecutor 跑一次 headless 对话推理，把 description
        //     当系统触发消息注入角色对话管线，结果作为一条角色消息落库。详见方案第四节、第五节。
        //   - 工具型（mode A，现状）：保持原 AgentToolRegistry.get(toolName).execute(params)。
        // 两条路径都返回 ToolResult，后续 success 判定、写 JobResultEntity、
        // updateRunTime/disable、重新入队、通知等逻辑完全不变。
        val toolResult = try {
            if (job.toolName == AgentTaskJobExecutor.SENTINEL) {
                AgentTaskJobExecutor.execute(context, db, job)
            } else {
                val tool = AgentToolRegistry.get(job.toolName)
                tool?.execute(params)
            }
        } catch (e: Exception) {
            // P1-33：执行异常时主动释放锁，不必等 TTL 到期才能被下次重试/补跑认领
            scheduledJobDao.releaseLock(jobId)
            throw e
        }
        val now        = System.currentTimeMillis()

        val success = toolResult?.success == true

        // H2 修复：只有不会再重试时（成功 or 已达上限）才写结果 + 更新调度时间 + 发通知。
        // 还会重试时先释放锁，让下一次重试能重新认领，不写中间失败结果避免污染历史。
        val willRetry = !success && runAttemptCount < MAX_RETRY_COUNT
        if (willRetry) {
            scheduledJobDao.releaseLock(jobId)
            return Result.retry()
        }

        // 写入执行结果
        // 批次4-3-2 修复：用 try-finally 包裹写入+调度更新，确保无论
        // updateRunTime/disable 是否抛异常（磁盘满、WAL 损坏等），锁都会被释放。
        // 否则对 repeatIntervalMs 短于 LOCK_TTL_MS(3分钟) 的高频任务，
        // 下次到期时锁仍未过期，claimJob 失败，任务永久卡死。
        //
        // W1-001 修复：jobResultDao.insert() 与 scheduledJobDao.updateRunTime()/
        // disable() 此前是两次独立调用，进程若在两者之间被杀，会出现
        // job_results 已有"已执行"记录、但 scheduled_jobs 的下次运行时间/禁用
        // 状态未更新的不一致——重复任务永久卡死，一次性任务也不会被禁用。
        // 用 db.withTransaction 包裹这两步 DAO 写操作，保证要么全部成功要么
        // 全部回滚。WorkManagerScheduler.enqueue() 是系统调用非 DB 写入，
        // 故意放在事务外执行，避免不必要的长事务。
        try {
            db.withTransaction {
                jobResultDao.insert(
                    JobResultEntity(
                        id           = UUID.randomUUID().toString(),
                        jobId        = job.id,
                        characterId  = job.characterId,
                        toolName     = job.toolName,
                        status       = if (success) "success" else "failed",
                        output       = toolResult?.content,
                        errorMessage = toolResult?.error,
                        executedBy   = "workmanager",
                        startedAt    = startedAt,
                        completedAt  = now,
                        isRead       = false,
                        createdAt    = now,
                    )
                )

                // 更新调度时间
                // W1-006 修复：改用 updateRunTimeUsingCurrentInterval，nextRunAt 由 SQL
                // 用数据库当前行的 repeatIntervalMs 现算，不再用 Worker 读取任务时内存里
                // 的旧 job.repeatIntervalMs 计算——避免用户在 Worker 执行期间通过 UI 修改
                // 了间隔，却被 Worker 用旧值静默覆盖。若该行 repeatIntervalMs 已被用户
                // 改为 null（转为一次性任务），UPDATE 的 WHERE 条件不匹配，返回 0，
                // 此时改走 disable() 与"一次性任务"路径保持一致。
                if (job.repeatIntervalMs != null) {
                    val updated = scheduledJobDao.updateRunTimeUsingCurrentInterval(
                        id        = job.id,
                        lastRunAt = now,
                    )
                    if (updated == 0) {
                        // 数据库当前 repeatIntervalMs 已变为 null，任务已被改为一次性
                        scheduledJobDao.disable(job.id)
                    }
                } else {
                    scheduledJobDao.disable(job.id)
                }
            }
            // 重复任务：事务成功提交后再重新排期下一次 WorkRequest。
            // W1-006 修复：重新从数据库读取最新的 repeatIntervalMs（而非沿用
            // Worker 开头读到的 job.repeatIntervalMs 内存旧值）用于 enqueue 的
            // delay 参数——否则即使上面 nextRunAt 已经用新间隔算对，
            // WorkManager 的下一次实际唤醒时间仍会按旧间隔排期，等同于
            // 用户的修改在系统调度层被静默丢弃，同一根因换了个地方重现。
            val latestJob = scheduledJobDao.findById(job.id)
            if (latestJob != null && latestJob.enabled && latestJob.repeatIntervalMs != null) {
                WorkManagerScheduler.enqueue(context, job.id, latestJob.repeatIntervalMs)
            }
        } finally {
            // 无论写入/调度更新是否成功，都释放锁，防止锁泄漏导致任务永久卡死
            scheduledJobDao.releaseLock(job.id)
        }

        // 发送系统通知告知用户任务已完成
        // 批次4 4-4修复：App 前台时跳过系统通知，依赖 ZaijianApp.observeAndNotifyResults()
        // 触发的 PresenceEngine TaskCompletionToast 浮层（应用内通知）。原代码无条件发系统通知，
        // App 前台且用户在 CharacterScreen/FamilyScreen 时会产生两条通知（系统通知+Toast）。
        // 与 ZaijianMessagingService 的前台抑制范式对齐：FCM 路径前台时压制系统通知只转发 Toast。
        if (!com.zaijian.zhoumuyun.domain.PresenceEngine.isAppInForeground) {
            val notifTitle = if (success) "✅ ${job.title}" else "❌ ${job.title} 执行失败"
            val notifText  = toolResult?.content?.take(80) ?: toolResult?.error?.take(80) ?: "任务已执行"
            sendNotification(notifTitle, notifText, job.id, job.characterId)
        } else {
            ZLog.d("ScheduledJobWorker", "App in foreground, skip system notification for job=${job.id}, rely on in-app Toast")
        }

        return Result.success()
    }

    // ─────────────────────────────────────────────────────────────
    //  通知
    // ─────────────────────────────────────────────────────────────

    private fun sendNotification(title: String, text: String, jobId: String, characterId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // D-2 fix: 渠道已由 ZaijianApp.setupNotificationChannels() 在 onCreate() 统一创建，此处无需重复注册

        // 问题9修复：点击通知跳转到任务中心（携带 jobId，方便未来高亮定位）
        // UI M5 修复：原自定义 action + extra 路由改为标准 ACTION_VIEW + zaijian:// 深链接，
        // jobId 通过 query 参数传递（携带方式不变，仍为未来高亮定位预留）。
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("zaijian://${MainActivity.HOST_TASKS}")
                .buildUpon()
                .appendQueryParameter("jobId", jobId)
                .build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        // requestCode 用 jobId 的 hash，避免不同任务的通知互相覆盖对方的 PendingIntent
        val pendingIntent = PendingIntent.getActivity(
            context,
            jobId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // U2 修复：新增「查看日程」操作按钮，直达该角色的个人日程管理页
        // （personal_schedule/{characterId}）。与上面「点击通知正文 → 任务中心
        // 看执行结果」是两件不同的事——结果 vs 日程配置——分开两个入口，
        // 不改动问题9已经修好的主点击行为。
        val scheduleIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("zaijian://${MainActivity.HOST_SCHEDULE}/$characterId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        // requestCode 与上面的主 PendingIntent 区分（"schedule_" 前缀），避免被
        // FLAG_UPDATE_CURRENT 误判为同一个 PendingIntent 而互相覆盖。
        val schedulePendingIntent = PendingIntent.getActivity(
            context,
            ("schedule_$jobId").hashCode(),
            scheduleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_dialog_info, "查看日程", schedulePendingIntent)
            .build()

        nm.notify(jobId.hashCode(), notif)
    }
}
