package com.zaijian.zhoumuyun.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 统一时间格式化工具（架构瘦身 Phase 1 - 第3项）
 *
 * 收敛原先分散在 4 处的重复实现：
 *   - ChatScreen.kt#formatTimestamp
 *   - RoundtableScreen.kt#formatRoundtableTimestamp
 *   - CompetitionScreen.kt#formatTimestamp
 *   - TaskCenterScreen.kt#formatTimestamp（含内联 SimpleDateFormat("HH:mm") 用法）
 *
 * ⚠️ 时区 bug 修复说明：
 * 原 ChatScreen/RoundtableScreen 版本用 `ms / 3600000 % 24` 手算小时数，
 * 这是对 epoch 毫秒数做纯数值除法，等价于把时间戳当作 UTC 时间处理，
 * 完全没有走设备本地时区。国内设备（UTC+8）会导致聊天气泡显示的时间
 * 与系统时钟相差 8 小时。这里改用 Calendar（本地时区）重新计算，
 * 是本次搬迁里唯一"顺手修的真实 bug"，其余函数均为逻辑等价搬迁。
 */
object TimeFormatUtils {

    private val clockFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    private val hmFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val monthDayFormat = SimpleDateFormat("M月d日", Locale.CHINA)

    /**
     * 上午/下午 12 小时制，如 "下午 3:05"。
     * 对应原 ChatScreen#formatTimestamp / RoundtableScreen#formatRoundtableTimestamp。
     * 使用本地时区（原实现的时区 bug 在此修复）。
     */
    fun formatClockTime(ms: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val ampm = if (h < 12) "上午" else "下午"
        val h12 = when {
            h == 0 -> 12
            h <= 12 -> h
            else -> h - 12
        }
        return "$ampm $h12:${m.toString().padStart(2, '0')}"
    }

    /**
     * "MM/dd HH:mm"，0 时间戳返回空字符串。
     * 对应原 CompetitionScreen#formatTimestamp。
     */
    fun formatAbsoluteDate(ts: Long): String =
        if (ts == 0L) "" else clockFormat.format(Date(ts))

    /**
     * 相对时间："刚刚" / "X 分钟前" / "X 小时前" / "X 天前"，超过 7 天回退为 "M月d日"。
     * 对应原 TaskCenterScreen#formatTimestamp。
     */
    fun formatRelativeTime(epochMs: Long): String {
        val now = System.currentTimeMillis()
        val diffMs = now - epochMs
        return when {
            diffMs < 60_000L -> "刚刚"
            diffMs < 3_600_000L -> "${diffMs / 60_000L} 分钟前"
            diffMs < 86_400_000L -> "${diffMs / 3_600_000L} 小时前"
            diffMs < 86_400_000L * 7 -> "${diffMs / 86_400_000L} 天前"
            else -> monthDayFormat.format(Date(epochMs))
        }
    }

    /**
     * 纯 "HH:mm"（本地时区，Locale.CHINA）。
     * 对应原 TaskCenterScreen.kt 第760行内联的 SimpleDateFormat("HH:mm", Locale.CHINA) 用法。
     */
    fun formatHourMinute(epochMs: Long): String =
        hmFormat.format(Date(epochMs))
}
