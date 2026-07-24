package com.zaijian.zhoumuyun.util

/**
 * 中文否定前缀检测工具（S2问题3/4、S3问题4 统一修复）。
 *
 * 背景：MemoryEngine、HeuristicRelTracker、PregnancyTriggerManager 均使用
 * `text.contains(keyword)` 做子串匹配，对"我不喜欢""不想要孩子"这类否定
 * 表达会误判为命中正向关键词（因为"不喜欢"包含"喜欢"子串）。
 *
 * 本工具类提供统一的否定前缀检查：在关键词命中位置向前查找 1-3 个字符，
 * 判断是否存在否定词（"不"、"没"、"没有"、"别"、"无"、"非"、"莫"、"勿"）。
 * 三个调用方（MemoryEngine/HeuristicRelTracker/PregnancyTriggerManager）
 * 共用同一套否定词表和判断逻辑，避免后续各自演化导致行为不一致。
 */
object NegationUtils {

    /** 否定词表，按长度降序排列，优先匹配更长的否定词（如"没有"优先于"没"）。 */
    private val NEGATION_WORDS = listOf("没有", "不是", "不", "没", "别", "无", "非", "莫", "勿")

    /** 向前查找否定词时的最大回溯字符数（覆盖"没有"这类2字否定词）。 */
    private const val LOOKBACK_WINDOW = 5  // P2-4 修复：窗口从3扩大到5，提升否定词覆盖率

    /** 句子边界字符：lookback 扫描时遇到这些字符应截断，不再继续向前查找否定词。
     * 阶段0矛盾1修复：HeuristicRelTracker 将 userText + " " + assistantText 拼接后传入，
     * 若不在标点/空格处截断 prefix，会导致否定词跨越两句话边界产生误判。 */
    private val SENTENCE_BOUNDARY_CHARS = setOf('，', '。', '！', '？', '、', '；', ' ', '\n', '\t')

    /**
     * 从 [text] 的 [upTo]（不含）位置向前截取，最多回溯 [LOOKBACK_WINDOW] 个字符，
     * 但一旦遇到句子边界字符（标点或空格）就停止，不跨边界继续查找。
     */
    private fun boundedLookbackPrefix(text: String, upTo: Int): String {
        var start = upTo
        var steps = 0
        while (start > 0 && steps < LOOKBACK_WINDOW) {
            val prevChar = text[start - 1]
            if (prevChar in SENTENCE_BOUNDARY_CHARS) break
            start--
            steps++
        }
        return text.substring(start, upTo)
    }

    /**
     * 判断 [text] 中关键词 [keyword] 的命中，是否被其前方的否定词修饰。
     *
     * 例："我不喜欢喝咖啡" 中 keyword="喜欢"，命中位置前 1 字符是"不"，返回 true（被否定）。
     * 例："我很喜欢你" 中 keyword="喜欢"，命中位置前无否定词，返回 false（未被否定）。
     *
     * 若 [keyword] 在 [text] 中出现多次，只要任意一次命中未被否定，即视为"存在未被否定的命中"，
     * 返回 false（调用方应据此判定为有效命中）；只有全部出现都被否定时才返回 true。
     */
    fun isNegated(text: String, keyword: String): Boolean {
        if (keyword.isEmpty()) return false
        var searchFrom = 0
        var foundAny = false
        while (true) {
            val idx = text.indexOf(keyword, searchFrom)
            if (idx < 0) break
            foundAny = true
            val prefix = boundedLookbackPrefix(text, idx)
            val negatedHere = NEGATION_WORDS.any { prefix.contains(it) }  // P2-4 修复：endsWith→contains
            if (!negatedHere) return false // 存在至少一次未被否定的命中
            searchFrom = idx + keyword.length
        }
        // 走到这里：要么完全没找到（foundAny=false，不算否定），要么所有出现都被否定
        return foundAny
    }

    /**
     * 便捷方法：[text] 是否包含 [keyword] 且该命中未被否定词修饰。
     * 等价于 `text.contains(keyword) && !isNegated(text, keyword)`，但只扫描一次。
     */
    fun containsUnnegated(text: String, keyword: String): Boolean {
        if (!text.contains(keyword)) return false
        return !isNegated(text, keyword)
    }

    /**
     * P2-4 修复：公开方法，判断 [keyword] 在 [text] 中第 [occurrenceIndex]（0-based）
     * 次出现是否被否定词修饰。供 HeuristicRelTracker 等调用方复用统一否定检测逻辑。
     */
    fun isOccurrenceNegated(text: String, keyword: String, occurrenceIndex: Int): Boolean {
        if (keyword.isEmpty()) return false
        var idx = 0
        var seen = -1
        while (true) {
            val found = text.indexOf(keyword, idx)
            if (found < 0) return false
            seen++
            if (seen == occurrenceIndex) {
                val prefix = boundedLookbackPrefix(text, found)
                return NEGATION_WORDS.any { prefix.contains(it) }
            }
            idx = found + keyword.length
        }
    }
}
