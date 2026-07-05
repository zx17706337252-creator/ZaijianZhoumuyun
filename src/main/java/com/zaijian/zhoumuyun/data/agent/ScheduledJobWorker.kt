package com.zaijian.zhoumuyun.data.agent

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.MainActivity
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.JobResultEntity
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
        val toolResult = try {
            val tool = AgentToolRegistry.get(job.toolName)
            tool?.execute(params)
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
        if (job.repeatIntervalMs != null) {
            scheduledJobDao.updateRunTime(
                id        = job.id,
                lastRunAt = now,
                nextRunAt = now + job.repeatIntervalMs,
            )
            // 重复任务：重新排期下一次 WorkRequest
            WorkManagerScheduler.enqueue(context, job.id, job.repeatIntervalMs)
        } else {
            scheduledJobDao.disable(job.id)
        }

        // P1-4-1 修复：成功路径此前未释放锁，仅依赖 3 分钟 TTL 到期。
        // 与 ScheduleRepository.runLocalCompensation()（4-2，已修复）行为对齐：
        // 执行完毕（无论成功/失败但不再重试）立即释放锁，不等 TTL。
        // 否则 repeatIntervalMs 短于 LOCK_TTL_MS（3 分钟）的高频任务，
        // 下一次到期时锁仍未过期，claimJob 失败，任务被静默跳过且永久卡死
        // （后续每次到期都因锁未释放而继续跳过）。
        // 放在通知发送之前：通知是尽力而为的操作，不应让锁释放依赖它成功。
        scheduledJobDao.releaseLock(job.id)

        // 发送系统通知告知用户任务已完成
        val notifTitle = if (success) "✅ ${job.title}" else "❌ ${job.title} 执行失败"
        val notifText  = toolResult?.content?.take(80) ?: toolResult?.error?.take(80) ?: "任务已执行"
        sendNotification(notifTitle, notifText, job.id, job.characterId)

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

        nm.notify(System.currentTimeMillis().toInt(), notif)
    }
}
