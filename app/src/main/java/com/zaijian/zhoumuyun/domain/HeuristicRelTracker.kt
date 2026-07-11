package com.zaijian.zhoumuyun.domain

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

        val pos  = positiveWords.count { combined.contains(it) }
        val neg  = negativeWords.count { combined.contains(it) }
        val trust = trustWords.count  { combined.contains(it) }
        val rel  = releaseWords.count { combined.contains(it) }
        val supp = suppressWords.count{ combined.contains(it) }

        val rawSuppression = ((rel - supp) * 2).coerceIn(-6, 6)

        return RelationshipHeuristicDelta(
            affectionDelta   = ((pos - neg) * 3).coerceIn(-8, 8),
            trustDelta       = ((trust * 3) - (neg * 2)).coerceIn(-8, 8),
            conflictDelta    = (neg * 3 - pos * 1).coerceIn(-5, 8),
            suppressionDelta = (rawSuppression * 0.3f).toInt().coerceIn(-3, 3),
        )
    }
}

data class RelationshipHeuristicDelta(
    val affectionDelta: Int   = 0,
    val trustDelta: Int       = 0,
    val conflictDelta: Int    = 0,
    val suppressionDelta: Int = 0,
)
