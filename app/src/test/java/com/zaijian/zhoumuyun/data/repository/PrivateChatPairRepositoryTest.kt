package com.zaijian.zhoumuyun.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 验收修复回归测试：PrivateChatPairRepository.isStaleDay() 的每日重置边界判断。
 *
 * 背景：原实现 `(now / 86_400_000L) * 86_400_000L` 是把时间戳按 UTC 对齐到
 * 零点，不是设备本地时区的零点。对 UTC+8 用户来说，"今天"的边界会比本地
 * 实际零点早 8 小时触发——本地时间早上 0 点到 8 点之间，按 UTC 算其实还是
 * "昨天"，会导致这段时间内的每日计数重置判断错误。
 *
 * isStaleDay() 内部用 `Calendar.getInstance()`（运行环境的默认时区）取零点，
 * 这里测试也统一用默认时区构造输入时间戳，保证测试在任意时区的机器上跑
 * 结果都一致，不绑死在某一个具体时区上。
 */
class PrivateChatPairRepositoryTest {

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `本地时间零点前一分钟不应判定为新的一天`() {
        // usedTodayResetAt = 昨天上午 10 点；now = 今天 00:00 之前 1 分钟（本地时间）
        // 用 UTC 对齐的旧写法会错误地把 now 归到"昨天"的 UTC 天里，从而不重置；
        // 用本地时区写法这里同样应该判定"还没到本地新的一天"（结果一致，
        // 这条用来确认修复没有引入误判）。
        val yesterday10am = localMillis(2026, 7, 28, 10, 0)
        val todayJustBeforeMidnight = localMillis(2026, 7, 28, 23, 59)
        assertFalse(
            "本地时间当天 23:59，距离昨天上午重置未满一天，不应判定为过期",
            PrivateChatPairRepository.isStaleDay(yesterday10am, todayJustBeforeMidnight),
        )
    }

    @Test
    fun `本地时间零点之后应判定为新的一天`() {
        // usedTodayResetAt = 昨天上午 10 点（本地时间）；now = 今天本地零点过 1 分钟。
        // 这正是旧的 UTC 对齐写法会算错的区间：UTC+8 下，本地零点对应 UTC 前一天
        // 16:00，旧写法要等到本地上午 8 点才会把 now 归到新的 UTC 天，导致这里
        // 本该判定为"新的一天需要重置"却被判定为"还没过期"。
        val yesterday10am = localMillis(2026, 7, 28, 10, 0)
        val todayJustAfterMidnight = localMillis(2026, 7, 29, 0, 1)
        assertTrue(
            "本地时间已经跨过零点进入新的一天，距离昨天上午的重置已经过期",
            PrivateChatPairRepository.isStaleDay(yesterday10am, todayJustAfterMidnight),
        )
    }

    @Test
    fun `同一天内多次调用不应重复判定为过期`() {
        val todayMorning = localMillis(2026, 7, 29, 8, 0)
        val todayEvening = localMillis(2026, 7, 29, 22, 0)
        assertFalse(
            "重置时间和当前时间在本地同一天内，不应判定为过期",
            PrivateChatPairRepository.isStaleDay(todayMorning, todayEvening),
        )
    }
}
