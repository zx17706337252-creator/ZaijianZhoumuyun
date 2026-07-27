package com.zaijian.zhoumuyun.data.agent

import androidx.work.CoroutineWorker
import com.zaijian.zhoumuyun.util.ZLog
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.domain.ProactiveMessageNotifier
import com.zaijian.zhoumuyun.domain.WorldSimulation
import com.zaijian.zhoumuyun.data.repository.CharacterStateRepository
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository

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

            val daughterCharacterRepo = DaughterCharacterRepository(db = db, dao = db.daughterCharacterDao())
            val characterStateRepo = CharacterStateRepository(db.characterStateDao())
            // 第8条修复：ProactiveMessageNotifier/WorldSimulation都需要messageDao，
            // 原先各自裸取db.messageDao()两次，现在包一次Repository、两处复用。
            val messageRepository = MessageRepository(db.messageDao())
            val notifier = ProactiveMessageNotifier(
                context               = applicationContext,
                messageDao             = messageRepository,
                daughterCharacterRepo  = daughterCharacterRepo,
            )

            // PresenceEngine 注入 onProactiveMessage 回调：每次广播主动消息时
            // 顺带写入消息表 + 弹系统通知。
            // 批次1 1-6修复：原注释称"Worker场景下App必然不在前台"不准确——
            // WorkManager 约束只有 NetworkType.CONNECTED，App 在前台时 Worker 一样
            // 会被调度。若 App 正在该角色聊天页，Snackbar 已通过 proactiveMessageFlow
            // 展示，此处再弹系统通知会造成双重打扰。补齐与 AppContainer 一致的抑制
            // 判断（角色匹配 且 App 在前台才抑制），避免 Worker 路径漏判。
            val presenceEngine = PresenceEngine(
                goalDao  = db.characterGoalDao(),
                eventDao = db.worldEventDao(),
                onProactiveMessage = { msg ->
                    val suppress = msg.characterId == PresenceEngine.foregroundChatCharacterId &&
                        PresenceEngine.isAppInForeground
                    notifier.persistAndNotify(msg, suppressNotification = suppress)
                },
                messageDao            = messageRepository,
                daughterCharacterRepo  = daughterCharacterRepo,
            )

            val worldSimulation = WorldSimulation(
                relationshipDao    = db.relationshipDao(),
                goalDao            = db.characterGoalDao(),
                presenceEngine     = presenceEngine,
                messageDao         = messageRepository,
                context            = applicationContext, // S2问题10修复：传入 context，Trust 累加器余数可落盘
                daughterCharacterDao = db.daughterCharacterDao(), // daughters 覆盖修复：与 ZaijianApp 前台实例保持一致，
                                                                    // 否则后台主动消息检查会静默遗漏所有女儿角色
                // 批次1 1-7修复：补齐4个依赖参数，与 ZaijianApp.kt 前台实例保持完全一致。
                // 原代码漏传这4个参数（有=null默认值所以能编译），但 Tier2 的项目驱动行为、
                // Tier3 的记忆衰减在 Worker 路径静默降级，无日志无报错。
                memoryDao          = db.memoryDao(),
                candidateDao       = db.memoryCandidateDao(),
                memoryTagDao       = db.memoryTagDao(),     // Bugfix：与 ZaijianApp 前台实例保持一致，
                                                              // 否则后台路径 memoryRepo 懒加载条件不满足，静默为 null
                projectDao         = db.projectDao(),      // Tier2 项目驱动行为
                eventDao           = db.worldEventDao(),   // Tier3 写入 PROJECT_UPDATED 事件
            )

            worldSimulation.runProactiveCheckForCharacters()

            // L-P1-6 修复：后台心跳中独立清除 fertileWindowConsentAsked 标记，
            // 不依赖用户主动与该角色聊天。确保离开排卵期窗口后标记被重置，
            // 下次排卵期窗口可正常弹出通知。
            com.zaijian.zhoumuyun.data.manager.PregnancyTriggerManager.resyncFertileWindowFlags(db)

            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("ProactiveMsgWorker", "doWork failed", e)
            // 不重试：下一个周期的 PeriodicWorkRequest 自然会再跑一次，
            // 不需要走 WorkManager 的退避重试机制（这不是一次性必达任务）。
            Result.success()
        }
    }
}
