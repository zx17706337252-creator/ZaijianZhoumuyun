package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * CleanupScheduler — 批次8 8-1修复：高频表定期清理调度入口
 *
 * 与 PregnancySettlementScheduler 同一模式：
 *   - App 启动时调用 [ensurePeriodicWork] 挂上 24h 一次的 PeriodicWorkRequest
 *   - 使用 ExistingPeriodicWorkPolicy.KEEP：已经挂了就不重复入队，幂等
 *   - 24h 间隔足够（清理非紧急任务，系统自身处理 Doze/重启后的重新调度）
 *
 * 清理策略见 [CleanupWorker] 的 companion object 参数。
 */
object CleanupScheduler {

    const val WORK_NAME = "db_cleanup_periodic"
    private const val PERIODIC_INTERVAL_HOURS = 24L

    /** App 启动时调用一次，确保周期清理已挂上（幂等，重复调用无副作用）。 */
    fun ensurePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<CleanupWorker>(
            PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS,
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
