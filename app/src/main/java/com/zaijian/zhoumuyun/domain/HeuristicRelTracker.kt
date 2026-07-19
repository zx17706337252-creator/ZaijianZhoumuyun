package com.zaijian.zhoumuyun.domain

import com.zaijian.zhoumuyun.util.NegationUtils

/**
 * 纯启发式关系增量计算器（无 API 调用，无副作用）。Phase 2（zaijian）
 * 设计原则：单次最大 ±8，避免单轮剧烈波动；
 * suppression 变化速率额外压缩到 0.3x，确保面具碎裂是高光时刻而非常态。
 */
object HeuristicRelTracker {

    fun infer(userText: String, assistantText: String): RelationshipHeuristicDelta {
        val combined = (userText + " " + assistantText).lowercase()

        val positiveWords = listOf("谢谢", "感谢", "喜欢", "开心", "好的", "放心", "支持", "在意", "陪", "想你")
        val negativeWords = listOf("生气", "讨厌", "不行", "走开", "烦", "失望", "不想", "算了", "无聊")
        val trustWords    = listOf("相信", "信任", "秘密", "承诺", "真的", "保证", "答应")
        val releaseWords  = listOf("想说", "说出来", "靠近", "需要你", "放开", "想你", "表达")
        val suppressWords = listOf("不想说", "随便", "算了", "没事", "不重要", "别问", "沉默")

        // S2问题4修复：子串匹配对否定表达（"我不喜欢"）会误判为正向命中。
        // 逐词检查是否被否定修饰——未被否定的计入原类别；被否定的正向词
        // 归入负向计数，被否定的负向词归入正向计数（"不讨厌"≈偏正向）。
        // trust/release/suppress 词表被否定时视为未命中（不做类别翻转，
        // 因为"不相信""不需要你"翻转到相反类别的语义不如 pos/neg 明确）。
        var pos = 0
        var neg = 0
        for (w in positiveWords) {
            val count = countOccurrences(combined, w)
            for (i in 0 until count) {
                if (NegationUtils.isOccurrenceNegated(combined, w, i)) neg++ else pos++
            }
        }
        for (w in negativeWords) {
            val count = countOccurrences(combined, w)
            for (i in 0 until count) {
                if (NegationUtils.isOccurrenceNegated(combined, w, i)) pos++ else neg++
            }
        }
        val trust = trustWords.count    { NegationUtils.containsUnnegated(combined, it) }
        val rel   = releaseWords.count  { NegationUtils.containsUnnegated(combined, it) }
        val supp  = suppressWords.count { NegationUtils.containsUnnegated(combined, it) }

        val rawSuppression = ((rel - supp) * 2).coerceIn(-6, 6)

        return RelationshipHeuristicDelta(
            affectionDelta   = ((pos - neg) * 3).coerceIn(-8, 8),
            trustDelta       = ((trust * 3) - (neg * 2)).coerceIn(-8, 8),
            conflictDelta    = (neg * 3 - pos * 1).coerceIn(-5, 8),
            suppressionDelta = (rawSuppression * 0.3f).toInt().coerceIn(-3, 3),
        )
    }

    /** 统计 [keyword] 在 [text] 中出现的次数（不重叠）。 */
    private fun countOccurrences(text: String, keyword: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            val found = text.indexOf(keyword, idx)
            if (found < 0) break
            count++
            idx = found + keyword.length
        }
        return count
    }
}

data class RelationshipHeuristicDelta(
    val affectionDelta: Int   = 0,
    val trustDelta: Int       = 0,
    val conflictDelta: Int    = 0,
    val suppressionDelta: Int = 0,
)
