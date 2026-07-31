package com.zaijian.zhoumuyun.util

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 通知发送统一入口（C 类审查 #47 修复）。
 *
 * 原问题：全仓 8 处通知发送点里，只有 ZaijianMessagingService.kt 一处在
 * nm.notify() 之前调用了 checkSelfPermission()；其余 7 处直接裸调
 * nm.notify()。Android 13+（API 33+）POST_NOTIFICATIONS 权限被拒绝时，
 * notify() 是静默 no-op（不抛异常），外层 try-catch 兜不住，用户和开发者
 * 都看不到任何失败痕迹。
 *
 * 修复方式：把"权限检查 + notify"收敛成唯一入口 [safeNotify]，8 处调用点
 * 全部改用这个方法，不再各自裸调 nm.notify()。
 *
 * 用法（替换原来的 `nm.notify(id, notif)`）：
 * ```
 * NotificationPermissionUtils.safeNotify(context, notificationId, notification, "TAG名")
 * ```
 */
object NotificationPermissionUtils {

    /**
     * 检查当前是否有权限发送通知。
     *
     * API 33 以下没有运行时通知权限概念，只要用户没在系统设置里关闭通知
     * 渠道/App 通知总开关就算有权限，用 NotificationManagerCompat
     * .areNotificationsEnabled() 覆盖这一层（同时兼容渠道被单独关闭的情况）。
     * API 33+ 额外需要 POST_NOTIFICATIONS 运行时权限。
     */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        // NotificationManagerCompat.areNotificationsEnabled() 覆盖"总开关被关闭"
        // 这一层，API 24+ 全版本可用，与运行时权限检查互补、不冲突。
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 权限检查通过才真正调用 nm.notify()；未通过则跳过并记录一条可排查的日志
     * （经 ZLog 自动转发到 agent_log.txt，用户可导出查看，不是只在 logcat 里）。
     *
     * @param tag 调用方标识，仅用于日志，方便排查是哪个 Worker/组件被跳过
     */
    fun safeNotify(
        context: Context,
        notificationId: Int,
        notification: Notification,
        tag: String,
    ) {
        if (!canPostNotifications(context)) {
            ZLog.w(tag, "POST_NOTIFICATIONS 未授权或通知总开关关闭，跳过本次通知（id=$notificationId）")
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm == null) {
            ZLog.w(tag, "NotificationManager 获取失败，跳过本次通知（id=$notificationId）")
            return
        }
        // A5-5 修复：nm.notify() 在极端场景（通知渠道被系统禁用、RemoteViews 过大、
        // 跨进程 Binder 异常等）可能抛 SecurityException/RuntimeException。原 safeNotify()
        // 只做了权限前置检查，notify() 本身仍裸调——若异常向上传播到 Worker.doWork()，
        // 会导致非幂等操作（如 git commit）在重试时产生重复提交。
        // 此处一次性兜底全仓 9 个调用点，比逐个 Worker 单独包 try-catch 更彻底。
        try {
            nm.notify(notificationId, notification)
        } catch (e: Exception) {
            ZLog.w(tag, "nm.notify() 抛异常，已捕获防止向上传播（id=$notificationId）: ${e.message}")
        }
    }
}
