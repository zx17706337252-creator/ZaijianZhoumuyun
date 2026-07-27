package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
import com.zaijian.zhoumuyun.util.ZLog
import java.util.concurrent.TimeUnit

/**
 * PregnancySettlementScheduler — 问题5 修复：分娩到期结算调度入口
 *
 * 两条独立触发路径，互补而非互斥：
 *   1. [ensurePeriodicWork]：App 启动时挂上 12h 一次的 PeriodicWorkRequest 兜底轮询
 *      （怀孕以"天"为粒度，12h 间隔足够及时；不需要 DailyPracticeScheduler 那种
 *      精确到分钟的 AlarmManager 方案，用 WorkManager 周期任务即可，系统自身处理
 *      Doze/重启后的重新调度，比自己维护 AlarmManager+BootReceiver 更省心）。
 *   2. [runImmediateCheck]：App 启动、进入聊天页时各调用一次立即检查，避免用户
 *      刚好在满 30 天那一刻打开 App，却要等到下一个 12h 轮询点才看到结算结果。
 *
 * PeriodicWorkRequest 使用 ExistingPeriodicWorkPolicy.KEEP：已经挂了就不重复入队，
 * 每次 App 启动调用本函数是幂等的。
 */
object PregnancySettlementScheduler {

    const val WORK_NAME = "pregnancy_delivery_settlement"
    private const val PERIODIC_INTERVAL_HOURS = 12L

    /** App 启动时调用一次，确保周期兜底轮询已挂上（幂等，重复调用无副作用）。 */
    fun ensurePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<PregnancySettlementWorker>(
            PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS,
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * 立即执行一次结算检查（不经过 WorkManager 排队延迟）。
     * 供 ZaijianApp.onCreate() 后台协程 / ChatViewModel.init() 调用。
     * 内部已做 try-catch，调用方无需重复包裹；失败仅记录日志，
     * 不影响调用方所在协程的后续逻辑。
     */
    suspend fun runImmediateCheck(
        context:       Context,
        pregnancyRepo: PregnancyRepository,
        memoryRepo:    MemoryRepository,
        daughterRepo:  DaughterCharacterRepository,
    ) {
        try {
            PregnancySettlementWorker.settleAndRecord(
                context       = context,
                pregnancyRepo = pregnancyRepo,
                memoryRepo    = memoryRepo,
                daughterRepo  = daughterRepo,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("PregnancySettlementScheduler", "立即结算检查失败", e)
        }
    }
}
