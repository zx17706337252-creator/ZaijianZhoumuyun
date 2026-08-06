package com.zaijian.zhoumuyun.data.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-4 PR1：PromptInjectionStrategy 构件（纯新增）的纯 JVM 单测。 */
class PromptInjectionStrategyTest {

    @Test
    fun `versionToken timestamp 相等比较`() {
        assertEquals(VersionToken.Timestamp(123L), VersionToken.Timestamp(123L))
        assertFalse(VersionToken.Timestamp(123L) == VersionToken.Timestamp(124L))
    }

    @Test
    fun `versionToken hash 相等比较`() {
        assertEquals(VersionToken.Hash(42), VersionToken.Hash(42))
        assertFalse(VersionToken.Hash(42) == VersionToken.Hash(43))
    }

    @Test
    fun `versionedPromptLayer isChangedSince 令牌变化时返回 true`() {
        val layer = VersionedPromptLayer(name = "P", content = "x", versionToken = VersionToken.Hash(1))
        assertTrue(layer.isChangedSince(VersionToken.Hash(2)))
        assertFalse(layer.isChangedSince(VersionToken.Hash(1)))
    }

    @Test
    fun `injectionDecider 空层不注入`() {
        val layer = VersionedPromptLayer(name = "P", content = "", versionToken = VersionToken.Hash(1))
        assertFalse(InjectionDecider.shouldInject(layer, previous = null))
    }

    @Test
    fun `injectionDecider 首次轮次注入`() {
        val layer = VersionedPromptLayer(name = "P", content = "x", versionToken = VersionToken.Hash(1))
        assertTrue(InjectionDecider.shouldInject(layer, previous = null))
    }

    @Test
    fun `injectionDecider 令牌未变化不注入`() {
        val layer = VersionedPromptLayer(name = "P", content = "x", versionToken = VersionToken.Hash(1))
        assertFalse(InjectionDecider.shouldInject(layer, previous = VersionToken.Hash(1)))
    }

    @Test
    fun `injectionDecider 令牌变化重新注入`() {
        val layer = VersionedPromptLayer(name = "P", content = "x", versionToken = VersionToken.Hash(2))
        assertTrue(InjectionDecider.shouldInject(layer, previous = VersionToken.Hash(1)))
    }

    @Test
    fun `intentRouter 空输入返回 GENERAL`() {
        val router = IntentRouter()
        assertEquals(IntentCategory.GENERAL, router.route(""))
    }

    @Test
    fun `intentRouter 命中 RELATION 关键词返回 RELATION`() {
        val router = IntentRouter()
        assertEquals(IntentCategory.RELATION, router.route("我好像有点喜欢你了"))
        assertEquals(IntentCategory.RELATION, router.route("你有没有在想念我"))
    }

    @Test
    fun `intentRouter 命中 PREGNANCY 关键词返回 PREGNANCY`() {
        val router = IntentRouter()
        assertEquals(IntentCategory.PREGNANCY, router.route("我好像怀孕了"))
        assertEquals(IntentCategory.PREGNANCY, router.route("宝宝今天动了吗"))
    }

    @Test
    fun `intentRouter 命中 IDENTITY 关键词返回 IDENTITY`() {
        val router = IntentRouter()
        assertEquals(IntentCategory.IDENTITY, router.route("你是谁"))
        assertEquals(IntentCategory.IDENTITY, router.route("你是不是AI"))
    }

    @Test
    fun `intentRouter 含他她但无 RELATION 关键词的日常对话返回 GENERAL`() {
        // v10 风险点 4：RELATION 关键词表不含"他"/"她"，含他/她的日常对话不应误触发 RELATION。
        val router = IntentRouter()
        assertEquals(IntentCategory.GENERAL, router.route("他昨天去超市买了点东西"))
        assertEquals(IntentCategory.GENERAL, router.route("她今天心情不错"))
        assertEquals(IntentCategory.GENERAL, router.route("先吃饭吧"))
    }
}