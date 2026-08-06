package com.zaijian.zhoumuyun.data.privatechat

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
        const val KEY_DIRECTIVE = "directive"
        private const val TAG = "PrivateChatWorker"
        private const val CHANNEL_ID = "character_message"
    }

    override suspend fun doWork(): Result {
        val pairId = inputData.getString(KEY_PAIR_ID) ?: return Result.failure()
        val initiatorId = inputData.getInt(KEY_INITIATOR_ID, -1)
        if (initiatorId < 0) return Result.failure()
        val directive = inputData.getString(KEY_DIRECTIVE)

        val engine = AppContainer.instance.privateChatEngine

        return try {
            when (val result = engine.runSession(pairId, initiatorId, directive = directive)) {
                is PrivateChatSessionResult.Completed -> {
                    notifySuccess(pairId, result.turnCount)
                    Result.success()
                }
                is PrivateChatSessionResult.Skipped -> {
                    // 修复：Skipped 不再静默吞没。此前 Skipped 直接返回 success()，
                    // 既不记日志也不通知用户——而 PrivateChatSendTool 已向 LLM 返回
                    // "已经去找B聊天了"，用户被告知"已出发"但实际什么都没发生。
                    // 现在记录日志并通知用户跳过原因，让用户知道真实情况。
                    ZLog.w(TAG, "私聊会话被跳过: ${result.reason}, pairId=$pairId")
                    notifySkipped(pairId, result.reason)
                    Result.success()
                }
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

            sendNotification(pairId, "私聊未完成", text)
        } catch (ne: Throwable) {
            ZLog.w(TAG, "notifyFailure itself failed", ne)
        }
    }

    /**
     * 修复：私聊成功完成时发送通知，告知用户可以去查看聊天记录。
     * 此前 Completed 直接返回 success() 无任何通知——用户在 ChatScreen 对 A 说
     * "去找B聊聊"后，完全不知道私聊何时完成、去哪里查看 A-B 的聊天记录。
     */
    private suspend fun notifySuccess(pairId: String, turnCount: Int) {
        try {
            val container = AppContainer.instance
            val pair = container.privateChatPairRepo.get(pairId) ?: return
            val daughterRepo = container.daughterCharacterRepo
            val nameA = resolveCharacterName(pair.characterIdA, daughterRepo)
            val nameB = resolveCharacterName(pair.characterIdB, daughterRepo)
            val text = "$nameA 和 $nameB 的私聊已结束（共 $turnCount 轮），点击查看聊天记录"

            sendNotification(pairId, "私聊已完成", text)
        } catch (ne: Throwable) {
            ZLog.w(TAG, "notifySuccess itself failed", ne)
        }
    }

    /**
     * 修复：私聊被跳过时通知用户真实原因，避免用户以为私聊已完成。
     */
    private suspend fun notifySkipped(pairId: String, reason: String) {
        try {
            val container = AppContainer.instance
            val pair = container.privateChatPairRepo.get(pairId) ?: return
            val daughterRepo = container.daughterCharacterRepo
            val nameA = resolveCharacterName(pair.characterIdA, daughterRepo)
            val nameB = resolveCharacterName(pair.characterIdB, daughterRepo)
            val userFacingReason = when {
                reason.contains("全局开关") || reason.contains("kill") -> "私聊功能当前已关闭"
                reason.contains("冷却") -> "距离上次私聊太近，需要等一会儿"
                reason.contains("上限") -> "今天的私聊次数已用完"
                reason.contains("下线") -> "对方暂时不想聊天"
                reason.contains("未开启") -> "这对角色的私聊尚未开启"
                // 修复 #4：与 PrivateChatSendTool 的 friendlyReason 映射同步补上这一条，
                // 避免两处对同一个 reason 字符串出现不同的用户提示（项目既有原则）。
                reason.contains("会话进行中") -> "这对角色已经在聊了，等这次聊完再试"
                else -> reason
            }
            val text = "$nameA 和 $nameB 的私聊未能开始：$userFacingReason"

            sendNotification(pairId, "私聊未开始", text)
        } catch (ne: Throwable) {
            ZLog.w(TAG, "notifySkipped itself failed", ne)
        }
    }

    /**
     * 统一通知发送入口：构建通知并安全发送。
     * deep link 指向 private_chat_detail/{pairId}，让用户可直接查看聊天记录。
     */
    private suspend fun sendNotification(pairId: String, title: String, text: String) {
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("zaijian://private_chat_detail/$pairId")
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
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        com.zaijian.zhoumuyun.util.NotificationPermissionUtils.safeNotify(
            applicationContext, pairId.hashCode(), notif, TAG,
        )
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
 * 触发源：原方案 2.1 节确认时只有用户在 PrivateChatScreen 手动发起一种；
 * 主聊天工具接入（PrivateChatAgentTools.kt）后新增第二种——角色 A 在与用户的
 * 日常对话里通过 <tool:private_chat_send/> 主动触发。两条入口共用本函数，
 * 不设 setInitialDelay——
 * 与原方案 enqueueBackgroundRoundtableTurn() 的关键差异：那边每轮都等一个随机
 * 延时，这边立刻执行，"轮次间隔"全部发生在 runSession() 内部的同步循环里。
 *
 * ExistingWorkPolicy.KEEP：同一对角色的会话如果已经在跑，新的触发不应该打断正在执行的会话。
 */
fun enqueuePrivateChatSession(context: Context, pairId: String, initiatorId: Int, directive: String? = null) {
    val request = OneTimeWorkRequestBuilder<PrivateChatWorker>()
        .setInputData(
            Data.Builder()
                .putString(PrivateChatWorker.KEY_PAIR_ID, pairId)
                .putInt(PrivateChatWorker.KEY_INITIATOR_ID, initiatorId)
                .apply { if (!directive.isNullOrBlank()) putString(PrivateChatWorker.KEY_DIRECTIVE, directive) }
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
