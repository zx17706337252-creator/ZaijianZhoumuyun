package com.zaijian.zhoumuyun.data.agent

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CalendarSyncHelper — 系统日历同步辅助类
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责：将 schedule_* 工具创建/修改/删除的任务同步到手机系统日历。
 *
 * 设计原则：
 *   - 调用方感知极小：三个 ScheduleTool 各调用一行，其余逻辑全在此类
 *   - 不改数据库 schema（Room migration 风险为 0）
 *   - calendarEventId 用 SharedPreferences 做 jobId → calendarEventId 的轻量映射
 *   - 权限未授予时静默跳过（日历同步是锦上添花，不能因此让工具执行失败）
 *   - 全部 IO 操作在 Dispatchers.IO 执行
 *
 * 权限要求（在 AndroidManifest.xml 中声明）：
 *   <uses-permission android:name="android.permission.READ_CALENDAR"/>
 *   <uses-permission android:name="android.permission.WRITE_CALENDAR"/>
 *
 * 注意：READ_CALENDAR / WRITE_CALENDAR 是运行时权限（dangerous），
 *   首次写入时若用户未授权会静默跳过。引导授权的 UI 入口
 *   建议放在 SettingsScreen（本次不做，留给后续 UI 迭代）。
 * ═══════════════════════════════════════════════════════════════
 */
class CalendarSyncHelper(private val context: Context) {

    companion object {
        private const val PREFS_NAME    = "zaijian_calendar_map"
        private const val CALENDAR_NAME = "再见公馆"
        private const val ACCOUNT_NAME  = "zaijian_local"
        private const val ACCOUNT_TYPE  = CalendarContract.ACCOUNT_TYPE_LOCAL
    }

    // SharedPreferences：jobId(String) → calendarEventId(Long)
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ─────────────────────────────────────────────────────────────
    //  公开接口
    // ─────────────────────────────────────────────────────────────

    /**
     * 创建系统日历事件，并记录 calendarEventId 到 prefs。
     * 权限不足时静默跳过，不抛异常。
     *
     * @param jobId      调度任务 ID（用作 key）
     * @param title      事件标题
     * @param nextRunAt  事件开始时间（毫秒时间戳）
     * @param repeatIntervalMs  重复间隔（null = 一次性，日历侧不设 RRULE）
     */
    suspend fun insertEvent(
        jobId: String,
        title: String,
        nextRunAt: Long,
        repeatIntervalMs: Long?,
    ) = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext
        try {
            val calendarId = getOrCreateCalendar() ?: return@withContext

            val endMs = nextRunAt + 60 * 60 * 1000L  // 默认事件时长 1 小时

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID,     calendarId)
                put(CalendarContract.Events.TITLE,           "【再见公馆】$title")
                put(CalendarContract.Events.DTSTART,         nextRunAt)
                put(CalendarContract.Events.DTEND,           endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE,  java.util.TimeZone.getDefault().id)
                put(CalendarContract.Events.DESCRIPTION,     "由再见公馆定时任务自动创建")
                // 有重复间隔时，生成 RRULE（FREQ=HOURLY 或 DAILY）
                repeatIntervalMs?.let { ms ->
                    val rrule = buildRRule(ms)
                    if (rrule.isNotEmpty()) put(CalendarContract.Events.RRULE, rrule)
                }
            }

            val uri = context.contentResolver.insert(
                CalendarContract.Events.CONTENT_URI, values
            )
            val eventId = uri?.lastPathSegment?.toLongOrNull()
            if (eventId != null) {
                prefs.edit().putLong(jobId, eventId).apply()
            }
        } catch (_: Exception) {
            // 静默失败，不影响任务主流程
        }
    }

    /**
     * 更新系统日历中对应的事件。
     * 若找不到已记录的 calendarEventId，尝试重新插入。
     */
    suspend fun updateEvent(
        jobId: String,
        title: String,
        nextRunAt: Long,
        repeatIntervalMs: Long?,
    ) = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext
        try {
            val eventId = prefs.getLong(jobId, -1L)
            if (eventId == -1L) {
                // 历史任务没有对应日历事件，当作新建处理
                insertEvent(jobId, title, nextRunAt, repeatIntervalMs)
                return@withContext
            }

            val endMs = nextRunAt + 60 * 60 * 1000L

            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE,  "【再见公馆】$title")
                put(CalendarContract.Events.DTSTART, nextRunAt)
                put(CalendarContract.Events.DTEND,   endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                repeatIntervalMs?.let { ms ->
                    val rrule = buildRRule(ms)
                    if (rrule.isNotEmpty()) put(CalendarContract.Events.RRULE, rrule)
                } ?: put(CalendarContract.Events.RRULE, "")  // 改为一次性：清除 RRULE
            }

            val uri = CalendarContract.Events.CONTENT_URI
                .buildUpon().appendPath(eventId.toString()).build()
            context.contentResolver.update(uri, values, null, null)
        } catch (_: Exception) {
            // 静默失败
        }
    }

    /**
     * 删除系统日历中对应的事件，并清除 prefs 记录。
     */
    suspend fun deleteEvent(jobId: String) = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext
        try {
            val eventId = prefs.getLong(jobId, -1L)
            if (eventId != -1L) {
                val uri = CalendarContract.Events.CONTENT_URI
                    .buildUpon().appendPath(eventId.toString()).build()
                context.contentResolver.delete(uri, null, null)
                prefs.edit().remove(jobId).apply()
            }
        } catch (_: Exception) {
            // 静默失败
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  内部实现
    // ─────────────────────────────────────────────────────────────

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * 获取"再见公馆"专属日历的 ID，不存在时自动创建。
     * 返回 null 表示操作失败（权限/系统异常）。
     */
    private fun getOrCreateCalendar(): Long? {
        // 先查询是否已存在
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection  = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
        val selArgs    = arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE)

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, selection, selArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }

        // 不存在则创建
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME,              ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE,              ACCOUNT_TYPE)
            put(CalendarContract.Calendars.NAME,                      CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,     CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR,            0xFF9B6A8A.toInt()) // 公馆紫
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,     CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT,             ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE,                   1)
            put(CalendarContract.Calendars.SYNC_EVENTS,               1)
        }

        val uri = CalendarContract.Calendars.CONTENT_URI
            .buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build()

        return context.contentResolver.insert(uri, values)
            ?.lastPathSegment?.toLongOrNull()
    }

    /**
     * 将毫秒间隔转换为 iCal RRULE 字符串。
     * 支持：整月(FREQ=MONTHLY)、整周(FREQ=WEEKLY)、整天(FREQ=DAILY)、
     * 整小时(FREQ=HOURLY)、分钟级(FREQ=HOURLY+BYMINUTE 或 FREQ=MINUTELY)。
     * 低于 1 分钟的间隔返回空字符串（不设 RRULE）。
     */
    private fun buildRRule(intervalMs: Long): String {
        val totalMinutes = intervalMs / (60 * 1000L)
        if (totalMinutes <= 0L) return ""

        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours % 720 == 0L && minutes == 0L -> "FREQ=MONTHLY;INTERVAL=${hours / 720}"
            hours % 168 == 0L && minutes == 0L -> "FREQ=WEEKLY;INTERVAL=${hours / 168}"
            hours % 24  == 0L && minutes == 0L -> "FREQ=DAILY;INTERVAL=${hours / 24}"
            hours > 0 && minutes == 0L          -> "FREQ=HOURLY;INTERVAL=${hours}"
            hours > 0                           -> "FREQ=HOURLY;INTERVAL=${hours};BYMINUTE=${minutes}"
            else                                -> "FREQ=MINUTELY;INTERVAL=${totalMinutes}"
        }
    }
}
