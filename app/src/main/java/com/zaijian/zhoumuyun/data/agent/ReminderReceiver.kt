package com.zaijian.zhoumuyun.data.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.zaijian.zhoumuyun.MainActivity

/**
 * ReminderReceiver — Phase 20 §D
 *
 * AlarmManager 触发时接收广播，发送系统通知。
 *
 * U2 延伸修复：通知新增「查看日程」操作按钮，深链接到触发该提醒的角色的
 * 个人日程页（zaijian://schedule/{characterId} → personal_schedule/{characterId}）。
 * 与 ScheduledJobWorker 通知按钮是同一套模式：不改变通知本身已有的行为
 * （目前没有 contentIntent，点击正文仍只是消失），只新增一个额外入口。
 * characterId 由 ReminderTool.scheduleAlarm() 写入 Intent extra，取不到
 * （旧版本提醒、或 characterIdProvider 还没被 ChatViewModel 动态覆盖前
 * 设置的提醒）时静默跳过按钮，不强行拼一个查不到角色的深链接。
 *
 * 需要在 AndroidManifest.xml 中声明：
 * ```xml
 * <receiver android:name=".data.agent.ReminderReceiver"
 *           android:exported="false"/>
 * ```
 *
 * 同时声明权限（AndroidManifest <manifest> 层级）：
 * ```xml
 * <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
 * ```
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text        = intent.getStringExtra("reminder_text") ?: "你设置的提醒到了"
        val reqId       = intent.getIntExtra("reminder_id", 0)
        val characterId = intent.getIntExtra("character_id", -1)

        // P2-2-1 修复：提醒已触发即为"消费掉"，删除对应持久化文件，避免 filesDir/reminders/
        // 无限增长。scheduleAlarm() 现在把真实 Long id（= 文件名）放进 reminder_id_long；
        // 旧版本提醒（无此 extra）取默认 -1，跳过删除，后续由 BootReceiver 清扫兜底。
        val reminderId = intent.getLongExtra("reminder_id_long", -1L)
        if (reminderId > 0) {
            java.io.File(context.filesDir, "reminders/${reminderId}.json").delete()
        }

        // D-2 fix: 渠道已由 ZaijianApp.setupNotificationChannels() 在 onCreate() 统一创建，此处无需重复注册

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("再见周慕云 · 提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        // U2 延伸修复：characterId 有效时才加「查看日程」按钮
        if (characterId > 0) {
            val scheduleIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("zaijian://${MainActivity.HOST_SCHEDULE}/$characterId")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            // requestCode 用 reminder_id 派生，避免不同提醒的 PendingIntent 互相覆盖
            val schedulePendingIntent = PendingIntent.getActivity(
                context,
                ("reminder_schedule_$reqId").hashCode(),
                scheduleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_dialog_info, "查看日程", schedulePendingIntent)
        }

        // C类审查 #47 修复：改用统一的权限检查入口
        com.zaijian.zhoumuyun.util.NotificationPermissionUtils.safeNotify(
            context, reqId, builder.build(), "ReminderReceiver",
        )
    }

    companion object {
        const val CHANNEL_ID = "zaijian_reminders"
    }
}
