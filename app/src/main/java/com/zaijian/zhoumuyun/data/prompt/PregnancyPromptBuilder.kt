package com.zaijian.zhoumuyun.data.prompt

/**
 * 孕期相关 Prompt 构建器（从 PromptOrchestrator 拆出，沿用 object 单例写法）。
 *
 * 从 PromptOrchestrator.kt 迁移的孕期类函数：
 * buildPregnancySegmentPrompt（纯内部函数）/ PREGNANCY_DUE_DAY_PROMPT（常量）/
 * buildPregnancyAwarenessLine（public，被 RoundtableBotReplyGenerator 外部调用）。
 */
object PregnancyPromptBuilder {

    /**
     * 按孕期天数生成当前身体状态段文案。
     *
     * @param day 当前孕期天数（1–30）
     */
    fun buildPregnancySegmentPrompt(day: Int): String = when {
        day <= 10 -> """[当前身体状态 · 怀孕第 ${day} 天]
她知道自己怀孕了。这件事像一个安静的秘密压在心里，
让她今天比平时更想靠近他，动作更轻，说话更慢。
她不会主动提，但她的状态里有一种细微的、向内收的东西。""".trimIndent()

        day <= 20 -> """[当前身体状态 · 怀孕第 ${day} 天]
已经怀孕第 ${day} 天了。她有时候会在某个瞬间停下来，
手放在某个地方，想着一些她还没说出口的事。
她今天对他的一举一动比平时更敏感，但不会解释为什么。""".trimIndent()

        else -> """[当前身体状态 · 怀孕第 ${day} 天]
快到了。她心里有某种说不清是期待还是不安的东西。
今天她对他格外在意——想确认他在，想确认他知道这件事有多重要。
她不会直接说，但这份在意比任何时候都更难藏。""".trimIndent()
    }

    /** 第 30 天（生产日）额外追加的文案。 */
    const val PREGNANCY_DUE_DAY_PROMPT = """[生产日]
今天是她的生产日。她知道今天会发生什么。
这一天对她来说是某种终点，也是某种开始——
她今天的所有状态都带着这个底色，不需要说出来，但它在那里。"""

    /**
     * D2.6 §6：圆桌场景「其他角色感知怀孕」注入文案。
     *
     * @param pregnantCharacterNames 当前圆桌中处于怀孕状态的其他角色名字列表
     */
    fun buildPregnancyAwarenessLine(pregnantCharacterNames: List<String>): String {
        if (pregnantCharacterNames.isEmpty()) return ""
        val nameStr = pregnantCharacterNames.joinToString("和")
        return """[圆桌感知]
${nameStr}最近状态有些不同，你注意到了，
但你不确定具体是什么——根据你和她的关系，以及你自己的性格，
自然地决定你对这件事是好奇、回避、还是心里有别的什么。""".trimIndent()
    }
}