package com.zaijian.zhoumuyun.data.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.zaijian.zhoumuyun.util.ZLog
import java.util.Calendar

/**
 * DailyPracticeScheduler — P6 专长进化系统「每日修炼」的调度入口
 *
 * D-7 fix: 改用 AlarmManager.setExactAndAllowWhileIdle() 替代 WorkManager setInitialDelay，
 * 确保 Doze 模式下也能在用户设定时刻（默认 21:00）准时触发，不受维护窗口漂移影响。
 *
 * 调用链：
 *   AlarmManager 到点 → PracticeAlarmReceiver.onReceive()
 *   → WorkManager.enqueueUniqueWork(OneTimeWorkRequest<DailyPracticeWorker>)
 *   → DailyPracticeWorker.doWork() → finally: DailyPracticeScheduler.scheduleNext()
 *
 * Android 12+（targetSdk ≥ 31）需要 SCHEDULE_EXACT_ALARM 权限，Manifest 已声明。
 * Android 14+（targetSdk ≥ 34）改用 USE_EXACT_ALARM（自动获取，无需运行时弹窗）；
 * 两个权限 canScheduleExactAlarms() 都会返回 true，此处统一检查后降级兜底。
 */
object DailyPracticeScheduler {

    const val WORK_NAME     = "daily_specialty_practice"
    const val DEFAULT_HOUR  = 21
    const val DEFAULT_MINUTE = 0

    private const val REQUEST_CODE = 0x5DA1  // 固定 requestCode，用于 cancel/replace

    /**
     * 调度下一次修炼，定到「今天/明天的 hour:minute」（取最近的未来时刻）。
     *
     * 优先用 setExactAndAllowWhileIdle（Doze 穿透），若设备拒绝精确闹钟权限则
     * 降级为 setAndAllowWhileIdle（允许系统延迟几分钟，但不会漂移数小时）。
     */
    fun scheduleNext(context: Context, hour: Int = DEFAULT_HOUR, minute: Int = DEFAULT_MINUTE) {
        val now    = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }

        val pi = buildPendingIntent(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            ZLog.e("DailyPracticeScheduler", "AlarmManager 不可用，每日修炼调度中断")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // 用户未授予 SCHEDULE_EXACT_ALARM：降级为 setAndAllowWhileIdle，
            // 系统可延迟最多数分钟，但不受 Doze 长时间阻塞
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target.timeInMillis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target.timeInMillis, pi)
        }
    }

    /** 取消已排队的闹钟（用户停用全部专长时调用） */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            ZLog.e("DailyPracticeScheduler", "AlarmManager 不可用，无法取消闹钟")
            return
        }
        am.cancel(buildPendingIntent(context))
        // 同时取消可能还在队列里的 WorkManager 任务
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** 供 PracticeAlarmReceiver 调用：将 DailyPracticeWorker 投递给 WorkManager */
    fun dispatchWorker(context: Context) {
        // 性能 M1 修复：DailyPracticeWorker 内部依赖 ProviderManager.activeProvider 调用 LLM
        // 生成每日修炼产出，无网时原逻辑会直接进入失败分支白白错过当天这次机会。
        // 加约束后系统会等有网再唤醒执行（仍受 AlarmManager 精确触发时间影响，但避免空跑）。
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DailyPracticeWorker>()
            .setConstraints(constraints)
            .build()
        // P1-2 修复：原先用 REPLACE——若前一天触发时设备离线，
        // Worker 会因 NetworkType.CONNECTED 约束挂起等待网络；次日闹钟
        // 再次调用本方法时，REPLACE 会直接取消并删除这个仍在等待中的
        // 旧 Worker，前一天的修炼永久丢失、无任何记录，也没有补跑路径。
        // 改为 KEEP：官方语义是"若同名 work 存在且尚未跑完（unfinished），
        // 保留旧的、忽略新请求"——旧 Worker 仍挂着等网时，今天这次
        // 调用不会打断它，网络恢复后它会正常继续执行；而如果旧 Worker
        // 已经跑到终态（成功或失败），KEEP 视为无冲突，新请求正常入队，
        // 当天修炼照常进行。两种情况都不需要额外的"当天是否已完成"
        // 状态记录，行为随 WorkManager 对 unique work 生命周期的
        // 判断自然正确。
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PracticeAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
