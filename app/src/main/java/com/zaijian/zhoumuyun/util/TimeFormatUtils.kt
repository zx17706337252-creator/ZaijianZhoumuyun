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

    // P2-35 修复：显式固定为 Asia/Shanghai 时区，避免同一 createdAt 在不同
    // 时区设备上落到不同日期分组（TimelineScreen 日期分组依赖此格式化结果）。
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private val timeFormatter     = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dateFormatter     = DateTimeFormatter.ofPattern("MM月dd日", Locale.getDefault())
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val shortDateFormatter = DateTimeFormatter.ofPattern("M/d", Locale.getDefault())

    // 批次7 [重构-01] 新增：收敛全项目此前散布的 SimpleDateFormat 实现。
    // 以下 pattern 均只含数字/分隔符（无 EEEE/M 等语义化文本符号），
    // 因此 Locale 选择不影响输出，统一用 Locale.getDefault() 即可。
    private val monthDaySlashTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.getDefault())
    private val monthDayDashTimeFormatter  = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault())
    private val monthDayDashFormatter      = DateTimeFormatter.ofPattern("MM-dd", Locale.getDefault())
    private val isoDateFormatter           = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
    private val dateTimeMinuteFormatter    = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val fileStampFormatter         = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.getDefault())
    private val exportStampFormatter       = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.getDefault())
    private val logTimestampFormatter      = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // 这两个 pattern 里的"年/月/日"是字面中文字符（非格式符号），Locale 同样不影响输出，
    // 显式用 Locale.CHINESE 只是保留原实现的语义标注。
    private val chineseFullDateFormatter  = DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.CHINESE)
    private val chineseShortDateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINESE)

    // datetime 工具（BuiltinTools）专用：EEEE 是真正随 Locale 变化的星期文本，
    // 原实现固定传 Locale.CHINESE（与设备系统语言无关），此处保持一致，
    // 不可改成 Locale.getDefault()，否则非中文设备上 Agent 的"今天星期几"会用错语言回复用户。
    private val chineseFullDateWeekdayFormatter     = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINESE)
    private val timeWithSecondsFormatter            = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINESE)
    private val chineseWeekdayFullFormatter          = DateTimeFormatter.ofPattern("EEEE", Locale.CHINESE)
    private val chineseFullDateTimeWeekdayFormatter  = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm:ss", Locale.CHINESE)

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

    /** "MM/dd HH:mm"，如 "07/24 14:30"。 */
    fun formatMonthDaySlashTime(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(monthDaySlashTimeFormatter)

    /** "MM-dd HH:mm"，如 "07-24 14:30"。 */
    fun formatMonthDayDashTime(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(monthDayDashTimeFormatter)

    /** "MM-dd"，如 "07-24"。 */
    fun formatMonthDayDash(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(monthDayDashFormatter)

    /** "yyyy-MM-dd"，如 "2026-07-24"。 */
    fun formatIsoDate(ms: Long): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(ms), zone).format(isoDateFormatter)

    /** "yyyy-MM-dd HH:mm"，如 "2026-07-24 14:30"。 */
    fun formatDateTimeMinute(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(dateTimeMinuteFormatter)

    /** "yyyyMMdd_HHmmss"，用于文件名时间戳，如 "20260724_143000"。 */
    fun formatFileStamp(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(fileStampFormatter)

    /** "yyyy-MM-dd_HHmm"，用于导出文件名时间戳，如 "2026-07-24_1430"。 */
    fun formatExportStamp(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(exportStampFormatter)

    /** "yyyy-MM-dd HH:mm:ss.SSS"，用于日志时间戳（含毫秒）。 */
    fun formatLogTimestamp(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(logTimestampFormatter)

    /** "yyyy年MM月dd日"，如 "2026年07月24日"。 */
    fun formatChineseFullDate(ms: Long): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(ms), zone).format(chineseFullDateFormatter)

    /** "M月d日"，如 "7月24日"（无前导零）。 */
    fun formatChineseShortDate(ms: Long): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(ms), zone).format(chineseShortDateFormatter)

    // ── 以下 4 个方法专供 BuiltinTools 的 datetime 工具使用：固定输出中文，
    //    不随系统语言变化（Agent 对用户的回复语言契约要求） ──

    /** "yyyy年M月d日 EEEE"，固定中文，如 "2026年7月24日 星期五"。 */
    fun formatChineseFullDateWithWeekday(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(chineseFullDateWeekdayFormatter)

    /** "HH:mm:ss"。 */
    fun formatTimeWithSeconds(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(timeWithSecondsFormatter)

    /** 中文完整星期名，如"星期五"（区别于 getChineseWeekday 的"周五"短格式）。 */
    fun getChineseWeekdayFull(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(chineseWeekdayFullFormatter)

    /** "yyyy年M月d日 EEEE HH:mm:ss"，固定中文。 */
    fun formatChineseFullDateTimeWithWeekday(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(chineseFullDateTimeWeekdayFormatter)

    // ── 解析 ──

    /**
     * 解析 GitHub API 等来源的 ISO-8601 UTC 时间串（形如 "2024-01-01T12:00:00Z"）为 epoch 秒。
     * `Instant.parse` 原生支持该格式，无需手动指定 pattern/时区。解析失败返回 0
     * （视为非常旧，调用方据此判定不通过时间窗口校验）。
     */
    fun parseIso8601UtcToEpochSeconds(iso: String): Long =
        try {
            Instant.parse(iso).epochSecond
        } catch (_: Throwable) {
            0L
        }

    /**
     * 解析 "yyyy-MM-dd" 格式日期字符串，返回当天 00:00:00（系统默认时区）对应的 epoch 毫秒。
     * 解析失败返回 null。
     *
     * 注：相比原 SimpleDateFormat(lenient=true) 实现，LocalDate.parse 对非法日期
     * （如月份13、日期32）会严格拒绝而非"进位"到下个月，属于更安全的行为，
     * 不影响任何合法输入的解析结果。
     */
    fun parseIsoDateToEpochMillis(s: String): Long? =
        try {
            LocalDate.parse(s, isoDateFormatter).atStartOfDay(zone).toInstant().toEpochMilli()
        } catch (_: Throwable) {
            null
        }

    /**
     * [重构-02] 统一"今天/某天 00:00:00"计算逻辑，此前在
     * ProjectViewModel / TaskRepository / ProjectDailyPlannerTool 三处各自用
     * Calendar 重复实现完全相同的逻辑。
     *
     * @param ms 基准时间戳（默认当前时间），返回该时间戳所在自然日 00:00:00（系统默认时区）对应的 epoch 毫秒。
     */
    fun startOfDay(ms: Long = System.currentTimeMillis()): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

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