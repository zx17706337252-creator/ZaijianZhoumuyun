package com.zaijian.zhoumuyun.data.agent

import androidx.work.CoroutineWorker
import com.zaijian.zhoumuyun.util.ZLog
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.engine.PresenceEngine
import com.zaijian.zhoumuyun.data.engine.ProactiveMessageNotifier
import com.zaijian.zhoumuyun.data.engine.WorldSimulation
import com.zaijian.zhoumuyun.data.repository.CharacterStateRepository
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository

/**
 * ProactiveMessageWorker — 角色"主动发消息"后台定时任务
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责：App 不在前台、甚至进程被系统杀掉时，由 WorkManager 按周期唤醒，
 *   临时拼一套最小依赖跑一次"判断该不该主动发消息"的检查，
 *   不需要、也不会启动 WorldSimulation 的常驻三档循环
 *   （那是 ZaijianApp 里 Activity 生命周期管理的事，与此 Worker 无关）。
 *
 * 与 ZaijianApp 内 WorldSimulation 实例的关系：
 *   两者是两个独立的 WorldSimulation 对象，各自只用自己需要的那部分能力：
 *   - ZaijianApp 那份：前台运行，三档循环全开
 *   - 本 Worker 这份：每次唤醒临时 new 一个，只调用
 *     runProactiveCheckForCharacters()，跑完即弃，不持有长生命周期状态
 *     （lastProactiveAt 等节流状态在 PresenceEngine 的 companion object 里，
 *      是进程级单例，App 在前台/后台之间切换也不会丢节流记录）。
 *
 * 触发频率：由 WorkManagerScheduler.scheduleProactiveMessageCheck() 控制
 *   （当前取 90 分钟一次，过密会被系统判定异常耗电；过疏则角色"主动"的感觉会变弱）。
 *
 * Gradle 依赖：与 ScheduledJobWorker 共用同一个
 *   implementation("androidx.work:work-runtime-ktx:2.9.0")
 * ═══════════════════════════════════════════════════════════════
 */
class ProactiveMessageWorker(
    context: android.content.Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 用户在设置里关掉了"主动消息"总开关，直接跳过，不做任何检查
        if (!PresenceEngine.isProactiveEnabled()) return Result.success()

        return try {
            val db = AppDatabase.getInstance(applicationContext)

            val daughterCharacterRepo = DaughterCharacterRepository(dao = db.daughterCharacterDao())
            val characterStateRepo = CharacterStateRepository(db.characterStateDao())
            val notifier = ProactiveMessageNotifier(
                context               = applicationContext,
                messageDao             = db.messageDao(),
                daughterCharacterRepo  = daughterCharacterRepo,
            )

            // PresenceEngine 注入 onProactiveMessage 回调：每次广播主动消息时
            // 顺带写入消息表 + 弹系统通知。Worker 场景下 App 必然不在前台
            // （否则 WorkManager 不会被系统唤醒来跑这个任务），所以不传
            // suppressNotification，让通知正常弹出。
            val presenceEngine = PresenceEngine(
                goalDao  = db.characterGoalDao(),
                eventDao = db.worldEventDao(),
                onProactiveMessage = { msg -> notifier.persistAndNotify(msg) },
            )

            val worldSimulation = WorldSimulation(
                relationshipDao    = db.relationshipDao(),
                goalDao            = db.characterGoalDao(),
                presenceEngine     = presenceEngine,
                messageDao         = db.messageDao(),
            )

            worldSimulation.runProactiveCheckForCharacters()

            Result.success()
        } catch (e: Exception) {
            ZLog.w("ProactiveMsgWorker", "doWork failed", e)
            // 不重试：下一个周期的 PeriodicWorkRequest 自然会再跑一次，
            // 不需要走 WorkManager 的退避重试机制（这不是一次性必达任务）。
            Result.success()
        }
    }
}
