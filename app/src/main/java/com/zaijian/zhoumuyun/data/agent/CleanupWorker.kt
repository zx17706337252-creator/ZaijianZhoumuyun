package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.util.ZLog

/**
 * CleanupWorker — 批次8 8-1修复：高频表定期清理
 *
 * world_events 和 messages 两张高频写入表原先只增不删，长期使用后体积持续增长，
 * 最终影响 DB 读写性能和存储空间。本 Worker 定期执行双策略裁剪：
 *
 * 1. **时间裁剪**：删除 N 天前的记录（兜底，防止远古数据堆积）
 * 2. **分组裁剪**：每 domain/每角色只保留最近 M 条（防止某分组暴增占满表）
 *
 * 清理策略参数：
 * - world_events：删除 90 天前 + 每 domain 保留最近 500 条
 * - messages：删除 180 天前 + 每角色保留最近 2000 条
 *
 * 与 PregnancySettlementWorker 同一模式：CoroutineWorker + 临时拼装最小依赖，
 * 不走 Hilt。清理操作是幂等的，重复执行无副作用。
 *
 * 严重程度：P2（非即时崩溃，是长期运行后必然显现的性能退化）
 */
class CleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "CleanupWorker"

        // ── 清理策略参数 ──
        /** world_events 保留天数（90天） */
        private const val WORLD_EVENT_RETENTION_DAYS = 90L
        /** world_events 每 domain 保留条数 */
        private const val WORLD_EVENT_KEEP_PER_DOMAIN = 500
        /** messages 保留天数（180天，聊天记录比事件更有保留价值） */
        private const val MESSAGE_RETENTION_DAYS = 180L
        /** messages 每角色保留条数 */
        private const val MESSAGE_KEEP_PER_CHARACTER = 2000
    }

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val now = System.currentTimeMillis()

            // ── world_events 清理 ──
            val eventCutoff = now - WORLD_EVENT_RETENTION_DAYS * 86_400_000L
            val eventsDeletedByTime = db.worldEventDao().deleteBefore(eventCutoff)
            val eventsDeletedByDomain = db.worldEventDao().trimByDomain(WORLD_EVENT_KEEP_PER_DOMAIN)
            ZLog.i(
                TAG,
                "world_events 清理完成：时间裁剪删除 ${eventsDeletedByTime} 条（>${WORLD_EVENT_RETENTION_DAYS}天），" +
                    "domain 裁剪删除 ${eventsDeletedByDomain} 条（每 domain 保留 ${WORLD_EVENT_KEEP_PER_DOMAIN} 条）",
            )

            // ── messages 清理 ──
            val msgCutoff = now - MESSAGE_RETENTION_DAYS * 86_400_000L
            val msgsDeletedByTime = db.messageDao().deleteBefore(msgCutoff)
            val msgsDeletedByChar = db.messageDao().trimByCharacter(MESSAGE_KEEP_PER_CHARACTER)
            ZLog.i(
                TAG,
                "messages 清理完成：时间裁剪删除 ${msgsDeletedByTime} 条（>${MESSAGE_RETENTION_DAYS}天），" +
                    "角色裁剪删除 ${msgsDeletedByChar} 条（每角色保留 ${MESSAGE_KEEP_PER_CHARACTER} 条）",
            )

            Result.success()
        } catch (e: Exception) {
            ZLog.e(TAG, "清理任务执行失败，下次定期调度时会重试", e)
            Result.retry()
        }
    }
}
