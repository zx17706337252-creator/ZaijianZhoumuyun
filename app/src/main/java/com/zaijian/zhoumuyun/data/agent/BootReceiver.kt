package com.zaijian.zhoumuyun.data.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.zaijian.zhoumuyun.data.db.AppDatabase

/**
 * BootReceiver — D-7 fix
 *
 * AlarmManager 的闹钟在设备重启后会全部清空。
 * 监听 BOOT_COMPLETED 广播，重启后恢复每日修炼闹钟，
 * 确保每日链路不会因重启而永久断掉。
 *
 * 只在存在 isActive=true 的专长档案时才恢复，避免用户
 * 从未使用该功能时也注册闹钟。
 *
 * 需在 AndroidManifest.xml 中声明：
 * <receiver android:name=".data.agent.BootReceiver"
 *           android:exported="true">
 *   <intent-filter>
 *     <action android:name="android.intent.action.BOOT_COMPLETED" />
 *   </intent-filter>
 * </receiver>
 *
 * 同时在 <manifest> 层添加：
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // 性能 M5 修复：原注释认为普通 CoroutineScope(Dispatchers.IO).launch{}
        // 能"借"到系统给 BroadcastReceiver 留的约 10 秒存活窗口，这个理解不对——
        // 该窗口只在调用 goAsync() 拿到 PendingResult 并持有它时才会生效，
        // 普通协程不受这层保护，系统在 onReceive() 返回后随时可能回收进程，
        // 导致这段 DB 查询 + 闹钟恢复在低概率下被中途杀死、永远不会执行完。
        // 改为 goAsync() + finally 中 pendingResult.finish()，确保协程真正跑完
        // （或异常退出）后才释放，系统也会按预期延长这段时间的存活。
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val hasActive = db.specialtyProfileDao().getAllActiveProfiles().isNotEmpty()
                if (hasActive) {
                    val prefs = context.getSharedPreferences("specialty_evolution_prefs", Context.MODE_PRIVATE)
                    val hour   = prefs.getInt("daily_practice_hour",   DailyPracticeScheduler.DEFAULT_HOUR)
                    val minute = prefs.getInt("daily_practice_minute", DailyPracticeScheduler.DEFAULT_MINUTE)
                    DailyPracticeScheduler.scheduleNext(context, hour, minute)
                }
            } catch (_: Exception) {
                // 静默失败：开机恢复闹钟是兜底功能，单次失败不影响 App 后续正常使用，
                // 用户进入 App 后 ZaijianApp.onCreate 仍会按需重新调度。
            } finally {
                pendingResult.finish()
            }
        }
    }
}
