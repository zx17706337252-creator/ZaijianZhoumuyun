package com.zaijian.zhoumuyun.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 时间格式化工具 — 迁移到 java.time（API 26+ desugaring 支持）
 *
 * 原实现使用 SimpleDateFormat/Date，线程不安全且在低版本设备上存在
 * 格式化结果不一致的风险。改为 java.time 后：
 * - DateTimeFormatter 是线程安全的，无需 per-call 创建实例
 * - 明确使用 ZoneId.systemDefault() 而非依赖 JVM 默认时区嗅探
 * - 中文星期几使用 DayOfWeek.getDisplayName() 替代 SIMPLE_CHINESE 方言
 */
object TimeFormatUtils {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val timeFormatter     = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dateFormatter     = DateTimeFormatter.ofPattern("MM月dd日", Locale.getDefault())
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val shortDateFormatter = DateTimeFormatter.ofPattern("M/d", Locale.getDefault())

    fun formatTime(ms: Long): String =
        LocalTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(timeFormatter)

    fun formatDate(ms: Long): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(ms), zone).format(dateFormatter)

    fun formatDateTime(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(dateTimeFormatter)

    fun formatShortDate(ms: Long): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(ms), zone).format(shortDateFormatter)

    fun getChineseWeekday(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).dayOfWeek
            .getDisplayName(TextStyle.SHORT, Locale.CHINESE)

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0秒"
        return when {
            ms < 60_000L -> "${ms / 1_000L}秒"
            ms < 3_600_000L -> "${ms / 60_000L}分${(ms % 60_000L) / 1_000L}秒"
            ms < 86_400_000L -> "${ms / 3_600_000L}小时${(ms % 3_600_000L) / 60_000L}分"
            else -> "${ms / 86_400_000L}天${(ms % 86_400_000L) / 3_600_000L}小时"
        }
    }

    fun formatRelativeTime(ms: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - ms

        return when {
            diff < 0 -> "未来"
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L}分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L}小时前"
            diff < 172_800_000L -> "昨天 ${formatTime(ms)}"
            diff < 604_800_000L -> "${diff / 86_400_000L}天前"
            else -> formatDate(ms)
        }
    }
}