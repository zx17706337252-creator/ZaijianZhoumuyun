package com.zaijian.zhoumuyun.data.privatechat

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.zaijian.zhoumuyun.MainActivity
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.provider.LLMHttpException
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.util.ZLog
import java.util.concurrent.TimeUnit

/**
 * 私聊 Worker（方案_角色间私聊_v2-5 4.1 节）
 *
 * v2.3 补充：失败策略不能照抄 ProactiveMessageWorker "catch 住一切、返回 success"——
 * 那个策略的前提是"下一个周期还会自动再跑一次"，PrivateChatWorker 是用户点了一次按钮
 * 触发的一次性任务，没有"下个周期"。区分两类失败：
 * - 可重试（429/5xx 等瞬时错误）：Result.retry()，交给 WorkManager 退避策略
 * - 不可重试（4xx/逻辑错误）：Result.failure() + 通知用户
 */
class PrivateChatWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_PAIR_ID = "pair_id"
        const val KEY_INITIATOR_ID = "initiator_id"
        private const val TAG = "PrivateChatWorker"
        private const val CHANNEL_ID = "character_message"
    }

    override suspend fun doWork(): Result {
        val pairId = inputData.getString(KEY_PAIR_ID) ?: return Result.failure()
        val initiatorId = inputData.getInt(KEY_INITIATOR_ID, -1)
        if (initiatorId < 0) return Result.failure()

        val engine = AppContainer.instance.privateChatEngine

        return try {
            when (engine.runSession(pairId, initiatorId)) {
                is PrivateChatSessionResult.Completed -> Result.success()
                is PrivateChatSessionResult.Skipped -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 不吞 CancellationException
        } catch (e: LLMHttpException) {
            // chatSyncWithRetry 内部已经重试过 maxAttempts 次，这里 catch 到的是重试耗尽后仍然失败的情况
            if (e.isRetryable) {
                ZLog.w(TAG, "retryable failure, will retry via WorkManager", e)
                Result.retry()
            } else {
                notifyFailure(pairId, e)
                Result.failure()
            }
        } catch (e: Throwable) {
            ZLog.w(TAG, "non-retryable failure", e)
            notifyFailure(pairId, e)
            Result.failure()
        }
    }

    /**
     * 复用 ProactiveMessageNotifier 同款的系统通知渠道（CHANNEL_ID = "character_message"），
     * 不新建一套通知逻辑。通知内容为用户可理解的文案，不暴露技术细节。
     */
    private suspend fun notifyFailure(pairId: String, e: Throwable) {
        try {
            val container = AppContainer.instance
            val pair = container.privateChatPairRepo.get(pairId) ?: return
            val daughterRepo = container.daughterCharacterRepo
            val nameA = resolveCharacterName(pair.characterIdA, daughterRepo)
            val nameB = resolveCharacterName(pair.characterIdB, daughterRepo)
            val text = "$nameA 和 $nameB 的私聊没能完成，可以重新试试"

            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("zaijian://private_chat/$pairId")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                pairId.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("私聊未完成")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(pairId.hashCode(), notif)
        } catch (ne: Throwable) {
            ZLog.w(TAG, "notifyFailure itself failed", ne)
        }
    }

    /**
     * 两层硬编码查找角色名，与 ProactiveMessageNotifier.resolveCharacterName() 同款。
     */
    private suspend fun resolveCharacterName(
        characterId: Int,
        daughterRepo: DaughterCharacterRepository,
    ): String {
        DefaultCharacters.firstOrNull { it.id == characterId }?.let { return it.name }
        return try {
            daughterRepo.getCharacterConfig(characterId)?.name ?: "角色$characterId"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            "角色$characterId"
        }
    }
}

/**
 * 触发私聊会话（方案_角色间私聊_v2-5 4.1 节）
 *
 * 触发源只有用户手动发起一种（2.1 节已确认）。不设 setInitialDelay——
 * 与原方案 enqueueBackgroundRoundtableTurn() 的关键差异：那边每轮都等一个随机
 * 延时，这边立刻执行，"轮次间隔"全部发生在 runSession() 内部的同步循环里。
 *
 * ExistingWorkPolicy.KEEP：同一对角色的会话如果已经在跑，新的触发不应该打断正在执行的会话。
 */
fun enqueuePrivateChatSession(context: Context, pairId: String, initiatorId: Int) {
    val request = OneTimeWorkRequestBuilder<PrivateChatWorker>()
        .setInputData(
            Data.Builder()
                .putString(PrivateChatWorker.KEY_PAIR_ID, pairId)
                .putInt(PrivateChatWorker.KEY_INITIATOR_ID, initiatorId)
                .build()
        )
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        "private_chat_$pairId",
        ExistingWorkPolicy.KEEP,
        request,
    )
}
