package com.zaijian.zhoumuyun.data.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-4 PR4：Identity HOT/WARM 分层专项测试。 */
class IdentityPromptBuilderTest {

    private val fullFields = IdentityPromptBuilder.IdentityPromptFields(
        persona            = "冷面但心软的外科医生",
        speechStyle        = "简短、克制、偶尔毒舌",
        attitudeToUser     = "嘴上嫌弃，心里在乎",
        coreWound          = "被最信任的人背叛过",
        coreDesire         = "想要一个不会离开的人",
        maskTrigger        = "被戳到旧伤时会突然冷淡",
        privatePersona     = "脆弱、依赖、会撒娇",
        privateStyle       = "语速放慢、语气变软",
        privateExamples    = "（示例对话）",
        situationRules     = "被质疑时先冷静确认事实",
        deviationSignals   = "回避眼神、答非所问",
        likes              = "黑咖啡",
        dislikes           = "吵闹的环境",
        relationships      = "对亲近的人会口是心非",
        relationAssumption = "没人会无条件留在我身边",
        conflictStrategy   = "先冷战，等对方先开口",
        soulNote           = "想被记住的是温柔的一面",
        userImpression     = "他是一个让她安心的人",
    )

    private val boundaries = listOf("不会承认自己心软", "不会主动示弱")
    private val coreBeliefs = listOf("真心会被辜负", "只能靠自己")

    @Test
    fun `HOT 层严格 7 项每轮注入`() {
        val hot = IdentityPromptBuilder.buildIdentityBlock(
            name = "存档里的她", boundaries = boundaries, coreBeliefs = coreBeliefs,
            fields = fullFields, includeWarmFields = false,
        )
        // HOT：persona / speechStyle / attitudeToUser / boundaries / coreBeliefs / situationRules / deviationSignals
        assertTrue(hot.contains("冷面但心软的外科医生"))
        assertTrue(hot.contains("你说话的方式：简短、克制、偶尔毒舌"))
        assertTrue(hot.contains("你对他的态度：嘴上嫌弃，心里在乎"))
        assertTrue(hot.contains("你绝对不会："))
        assertTrue(hot.contains("- 不会承认自己心软"))
        assertTrue(hot.contains("你相信："))
        assertTrue(hot.contains("被质疑时先冷静确认事实"))
        assertTrue(hot.contains("回避眼神、答非所问"))
    }

    @Test
    fun `关闭 WARM 时 WARM 字段不注入`() {
        val hot = IdentityPromptBuilder.buildIdentityBlock(
            name = "存档里的她", boundaries = boundaries, coreBeliefs = coreBeliefs,
            fields = fullFields, includeWarmFields = false,
        )
        // WARM：likes / 内核 / 私下说话 / 人际关系 / 人设备忘录 等不应出现
        assertFalse(hot.contains("你喜欢：黑咖啡"))
        assertFalse(hot.contains("【内核"))
        assertFalse(hot.contains("私下说话方式"))
        assertFalse(hot.contains("【人际关系"))
        assertFalse(hot.contains("人设备忘录"))
        assertFalse(hot.contains("你同时活在两个自我之间"))
    }

    @Test
    fun `开启 WARM 时全量字段注入（默认行为保持）`() {
        val full = IdentityPromptBuilder.buildIdentityBlock(
            name = "存档里的她", boundaries = boundaries, coreBeliefs = coreBeliefs,
            fields = fullFields, // includeWarmFields 默认 true
        )
        assertTrue(full.contains("你喜欢：黑咖啡"))
        assertTrue(full.contains("【内核"))
        assertTrue(full.contains("私下说话方式"))
        assertTrue(full.contains("【人际关系"))
        assertTrue(full.contains("人设备忘录"))
        assertTrue(full.contains("你同时活在两个自我之间"))
    }
}