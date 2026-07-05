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
 * 需在 AndroidManifest.xml 中声明：
 * <receiver android:name=".data.agent.PracticeAlarmReceiver"
 *           android:exported="false" />
 */
class PracticeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DailyPracticeScheduler.dispatchWorker(context)
    }
}
