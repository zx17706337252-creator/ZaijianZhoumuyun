package com.zaijian.zhoumuyun.data.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * PracticeAlarmReceiver — D-7 fix
 *
 * AlarmManager.setExactAndAllowWhileIdle() 到点时系统广播到此处。
 * 接收后立即将 DailyPracticeWorker 投递给 WorkManager，由 WorkManager 管理
 * 实际执行（约束、重试、生命周期），BroadcastReceiver 本身不做耗时操作。
 *
 * C9#49 修复：次日闹钟在此处立即挂上，不再等待 doWork() 的 finally。
 * 原逻辑下一次调度完全依赖 DailyPracticeWorker.doWork() 执行完 finally 块；
 * 但 dispatchWorker() 入队时带 NetworkType.CONNECTED 约束，若设备此刻离线，
 * WorkManager 会一直不启动 doWork，finally 永远不执行 → 次日闹钟未设置 →
 * 每日修炼链路整条断裂（需用户手动重新打开 App 触发某个间接路径才能恢复）。
 * 现在 onReceive 一收到广播就调用 scheduleNext（不依赖 Worker 是否跑起来），
 * 从而保证"次日同一时刻还有闹钟"这件事与网络状态、Worker 执行与否完全解耦。
 * DailyPracticeWorker.doWork() 的 finally 调用保留：BroadcastReceiver 分支
 * 和 Worker 分支各自独立触发 scheduleNext，WorkManager 内部按 requestCode
 * 去重（PendingIntent.FLAG_UPDATE_CURRENT），重复调度是安全的幂等操作。
 *
 * 需在 AndroidManifest.xml 中声明：
 * <receiver android:name=".data.agent.PracticeAlarmReceiver"
 *           android:exported="false" />
 */
class PracticeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // P1-19 修复：此处是"每日修炼"次日闹钟重排的主路径，此前直接 scheduleNext(context)
        // 落到默认 21:00，忽略了用户配置的修炼时间（BootReceiver 开机恢复时却正确读取配置），
        // 三处重排路径不一致。当用户配置了非 21:00 的修炼时间，且触发时刻设备离线导致
        // DailyPracticeWorker 的 finally 修正被 WorkManager 挂起时，次日修炼会被静默挪到 21:00。
        // 这里与 BootReceiver 保持一致，读取配置的 hour/minute 再重排。
        val prefs = context.getSharedPreferences("specialty_evolution_prefs", Context.MODE_PRIVATE)
        val hour   = prefs.getInt("daily_practice_hour",   DailyPracticeScheduler.DEFAULT_HOUR)
        val minute = prefs.getInt("daily_practice_minute", DailyPracticeScheduler.DEFAULT_MINUTE)
        DailyPracticeScheduler.scheduleNext(context, hour, minute)
        DailyPracticeScheduler.dispatchWorker(context)
    }
}
