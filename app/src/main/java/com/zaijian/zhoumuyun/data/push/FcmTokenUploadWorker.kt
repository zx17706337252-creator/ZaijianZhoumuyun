package com.zaijian.zhoumuyun.data.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.data.remote.SupabaseClient
import com.zaijian.zhoumuyun.util.ZLog
import java.util.concurrent.TimeUnit

/**
 * P-15 修复：FCM token 上传改走 WorkManager OneTimeWorkRequest + 指数退避。
 *
 * 原方案在 onNewToken 里直接 appScope.launch()，若网络不可用或 Supabase 暂时
 * 不可达，上传失败后无任何重试机制，只能等下次 token 刷新（可能数周后）。
 * 改为 WorkManager 后：
 *   - 系统保证在进程被杀、设备重启后仍可执行
 *   - EXPONENTIAL 退避（30s → 60s → 120s …）自动重试直到成功
 *   - enqueueUniqueWork(REPLACE) 保证同一 token 只有一个上传任务在排队
 */
class FcmTokenUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val token  = inputData.getString(KEY_TOKEN)  ?: return Result.failure()
        val userId = inputData.getString(KEY_USER_ID) ?: return Result.failure()

        // 修复手册 Phase 2.1：upsertDeviceToken 原先返回 Unit、内部吞掉所有异常，
        // 这里的 try-catch 因此永远不会进入 catch 分支，无论成功失败都判定为
        // Result.success()，失败的 token 不会重试、永久丢失。
        // upsertDeviceToken 已改为返回 Boolean（内部异常同样被转换为 false，
        // 不会再向外抛出），此处改为基于返回值判断，原 try-catch 骨架不再需要。
        val ok = SupabaseClient.upsertDeviceToken(userId = userId, fcmToken = token)
        return if (ok) {
            ZLog.d(TAG, "FCM token 上传成功")
            Result.success()
        } else {
            ZLog.w(TAG, "FCM token 上传失败（将退避重试）")
            // runAttemptCount 由 WorkManager 管理；超过系统最大重试次数后自动放弃
            Result.retry()
        }
    }

    companion object {
        const val KEY_TOKEN   = "fcm_token"
        const val KEY_USER_ID = "user_id"
        private const val TAG = "FcmTokenUploadWorker"

        /**
         * 将 token 上传任务入队。
         * 使用 REPLACE 策略：若队列中已有同名任务（上次刷新后还未成功），用新 token 替换。
         */
        fun enqueue(context: Context, token: String, userId: String) {
            val inputData = Data.Builder()
                .putString(KEY_TOKEN, token)
                .putString(KEY_USER_ID, userId)
                .build()

            val request = OneTimeWorkRequestBuilder<FcmTokenUploadWorker>()
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                // S3问题6修复：任务名包含 userId，避免多用户切换时互相覆盖
                "fcm_token_upload_$userId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
            ZLog.d(TAG, "FCM token 上传任务已入队")
        }
    }
}
