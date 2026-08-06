package com.zaijian.zhoumuyun.data.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-4 PR2：PresenceStateLayer 试点 —— 变化检测专项测试。 */
class PresenceStateLayerTest {

    private fun layer(
        activity: String = "",
        focus: String = "",
        mood: String = "",
        energy: Int = -1,
    ) = PresenceStateLayer(activity = activity, focus = focus, mood = mood, energy = energy)

    @Test
    fun `空层 isEmpty 且 prompt 块为空串`() {
        val l = layer()
        assertTrue(l.isEmpty)
        assertEquals("", l.toPromptBlock())
    }

    @Test
    fun `非空层渲染完整状态块`() {
        val l = layer(activity = "写作", focus = "第三章", mood = "平静", energy = 7)
        assertFalse(l.isEmpty)
        assertEquals("【当前状态】活动：写作；焦点：第三章；心情：平静；能量：7", l.toPromptBlock())
    }

    @Test
    fun `部分字段为空时只渲染非空部分`() {
        val l = layer(activity = "散步", energy = 5)
        assertEquals("【当前状态】活动：散步；能量：5", l.toPromptBlock())
    }

    @Test
    fun `状态变化时 hashCode 令牌变化 → 视为已变化`() {
        val before = layer(mood = "平静").toVersionedLayer()
        val after = layer(mood = "开心").toVersionedLayer()
        assertTrue(after.isChangedSince(before.versionToken))
        assertTrue(InjectionDecider.shouldInject(after, before.versionToken))
    }

    @Test
    fun `状态未变化时 hashCode 令牌不变 → 不重复注入`() {
        val before = layer(activity = "做饭", energy = 3).toVersionedLayer()
        val same = layer(activity = "做饭", energy = 3).toVersionedLayer()
        assertFalse(same.isChangedSince(before.versionToken))
        assertFalse(InjectionDecider.shouldInject(same, before.versionToken))
    }

    @Test
    fun `首次轮次（无上一轮）注入`() {
        val l = layer(mood = "困").toVersionedLayer()
        assertTrue(InjectionDecider.shouldInject(l, previous = null))
    }

    @Test
    fun `空层即使有令牌也不注入`() {
        val l = layer().toVersionedLayer()
        assertTrue(l.isEmpty)
        assertFalse(InjectionDecider.shouldInject(l, previous = null))
    }
}