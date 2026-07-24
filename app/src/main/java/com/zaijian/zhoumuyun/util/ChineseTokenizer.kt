package com.zaijian.zhoumuyun.util

import android.icu.text.BreakIterator
import java.util.Locale

// ─────────────────────────────────────────────────────────────
//  ChineseTokenizer — 统一中文分词工具（E1 审计报告任务1 修复）
//
//  背景：
//  原代码在 MemoryEngine / AgentCoreTools.MemoryWriteTool /
//  AgentCoreTools.MemoryQueryTool / MemoryRepository（buildFtsQuery +
//  searchRelevantWithRouting）共四处各自实现"关键词提取"，全部基于
//  空白/标点切分（split(Regex("\\s+"))）。中文连续输入没有空格，
//  一句自然消息会被整体当作一个超长 token，既无法和写入侧产出的
//  短 tag 做 L2 精确相等匹配，也无法通过 FTS4 前缀匹配命中，导致
//  记忆检索 95% 漏召回（见 E1 phase_e1_verification_report.md §1.4）。
//
//  本工具统一收口为基于 Android 自带 ICU BreakIterator（API 24+，
//  本项目 minSdk=26，满足要求）的真实中文分词，无需引入 jieba 等
//  第三方分词库。写入侧（extractKeywords）与查询侧（buildFtsQuery /
//  searchRelevantWithRouting）共用同一分词器，保证"写时怎么切、
//  查时就怎么切"，使 L2 tag 精确匹配与 FTS4 前缀匹配都能生效。
//
//  关键设计：
//  1. BreakIterator.getWordInstance(Locale.CHINA) 基于 ICU 内置词典
//     做中文词边界判定，同时保留英文单词与数字。
//  2. 停用词过滤：去掉对记忆检索无区分度的高频虚词/代词/语气词，
//     以及本项目特有噪声词"用户"（记忆内容大量以"用户说…"开头，
//     "用户"作为 tag 几乎对所有记忆都命中，无区分度）。
//  3. 单字 token（长度 < 2）一律丢弃——单字在 L2 精确匹配中区分度过低，
//     且会让 memory_tags 表膨胀。最小长度可通过参数调整。
// ─────────────────────────────────────────────────────────────

/**
 * 统一中文分词工具。
 *
 * 所有需要从自然语言文本提取关键词/检索词的路径都应委托本工具，
 * 不再各自用空白/标点切分。当前调用方：
 * - [com.zaijian.zhoumuyun.data.memory.MemoryEngine.extractKeywords]
 * - [com.zaijian.zhoumuyun.data.agent.AgentCoreTools.MemoryWriteTool.extractKeywords]
 * - [com.zaijian.zhoumuyun.data.repository.MemoryRepository.buildFtsQuery]
 * - [com.zaijian.zhoumuyun.data.repository.MemoryRepository.searchRelevantWithRouting]
 */
object ChineseTokenizer {

    /**
     * 停用词：对记忆检索无区分度的高频词，分词后过滤掉。
     *
     * 维护原则：
     * - 只放"在多数记忆中都出现、无法区分话题"的词；
     * - 不放可能承载话题语义的实词（如"工作""压力""失眠"即使高频也保留，
     *   因为它们正是检索要命中的目标）；
     * - "用户"是本项目特有噪声：记忆内容几乎都以"用户说…"开头，
     *   作为 tag 对几乎所有记忆都命中，必须过滤。
     */
    private val STOP_WORDS: Set<String> = buildSet {
        // ── 项目特有高频噪声 ──
        addAll(listOf("用户", "说了", "提到", "说起"))

        // ── 代词（无话题区分度）──
        addAll(listOf(
            "我", "你", "他", "她", "它", "们", "自己",
            "我们", "你们", "他们", "她们", "它们",
            "这个", "那个", "这些", "那些", "什么", "怎么", "怎样",
        ))

        // ── 助词 / 语气词 / 量词虚化 ──
        addAll(listOf(
            "的", "了", "是", "在", "着", "过", "地", "得",
            "吗", "呢", "吧", "啊", "呀", "哦", "嗯", "嘛", "哈",
            "一个", "一些", "一下", "一样", "的话",
        ))

        // ── 介词 / 连词 / 副词（高频但无话题语义）──
        addAll(listOf(
            "和", "与", "或", "及", "但", "把", "被", "让", "给", "到", "对", "向",
            "也", "都", "就", "还", "又", "已经", "一直", "有点", "有些",
            "因为", "所以", "如果", "虽然", "然后", "或者", "但是", "不过",
            "可以", "没有", "不是", "不会", "不能", "不要", "应该", "可能",
            "知道", "觉得", "感觉", "现在", "最近", "上次", "比较", "特别",
        ))

        // ── 疑问/否定虚词 ──
        addAll(listOf("是不是", "要不要", "有没有", "怎么样", "为什么"))
    }

    /** 仅含字母/数字/汉字的 token 才保留（过滤纯标点、纯空白、纯符号片段）。 */
    private val VALID_TOKEN = Regex("^[\\p{L}\\p{N}]+$")

    /**
     * 对文本进行中文分词，返回有效 token 列表（已去停用词、去过短 token、保序去重）。
     *
     * 使用 [BreakIterator.getWordInstance] 配合中文 Locale，ICU 内部基于词典
     * 做词边界判定；同时保留英文单词与数字。
     *
     * @param text   待分词的原始文本
     * @param minLen 保留的最小 token 长度，默认 2（单字区分度过低且膨胀索引）
     */
    fun tokenize(text: String, minLen: Int = 2): List<String> {
        if (text.isBlank()) return emptyList()

        val result = mutableListOf<String>()
        val seen = HashSet<String>()
        val boundary = BreakIterator.getWordInstance(Locale.CHINA)
        boundary.setText(text)

        var start = boundary.first()
        var end = boundary.next()
        while (end != BreakIterator.DONE) {
            val word = text.substring(start, end).trim()
            if (word.length >= minLen &&
                word !in STOP_WORDS &&
                VALID_TOKEN.matches(word)
            ) {
                // 保序去重：同一文本内重复出现的词只保留首次
                if (seen.add(word)) {
                    result.add(word)
                }
            }
            start = end
            end = boundary.next()
        }
        return result
    }

    /**
     * 分词后用空格拼接，直接写入 [com.zaijian.zhoumuyun.data.db.entity.MemoryEntity.keywords] 字段。
     *
     * keywords 字段会同步写入 FTS4 虚拟表（memories_fts）。空格分隔后，
     * FTS4 unicode61 tokenizer 会把每个词当作独立 token 索引——这是让
     * 前缀匹配（word*）能命中的前提：原实现写入的是整句中文（无空格），
     * unicode61 不切分连续中文，导致整句被索引成单个超长 token，前缀匹配
     * 几乎永远失效。改为空格分隔的真实词后，每个词都是可被前缀匹配命中的独立 token。
     */
    fun tokenizeJoined(text: String): String = tokenize(text).joinToString(" ")

    // ─────────────────────────────────────────────────────────
    //  查询侧专用：bigram 扩展
    // ─────────────────────────────────────────────────────────

    /**
     * 查询侧专用分词：在 [tokenize] 基础上，对长度 ≥ 3 的词额外提取字符 bigram。
     *
     * 解决的问题——专有名词 / OOV 词的分词不一致：
     * 同一专有名词在写入时可能被正确切出（如"顾澜"），但在查询时因上下文
     * 不同被粘连成更长 token（如"提顾澜"），导致：
     * - L2 精确匹配失效：query tag "提顾澜" ≠ memory tag "顾澜"
     * - FTS 前缀匹配失效："提顾澜*" 不匹配 FTS token "顾澜"（后者不以"提顾澜"开头）
     *
     * 对"提顾澜"提取 bigram 后得到"顾澜"，即可：
     * - 与写入侧"顾澜" tag 精确匹配（L2 命中）
     * - 与 FTS 索引中的"顾澜" token 前缀匹配（"顾澜*" 命中）
     *
     * 仅用于查询侧（[MemoryRepository.buildFtsQuery] /
     * [MemoryRepository.searchRelevantWithRouting]）。写入侧不需要 bigram——
     * FTS 前缀匹配已覆盖"查询词是索引 token 前缀"的常规场景，bigram 扩展
     * 只需在查询侧补齐"查询 token 比索引 token 长"的反向场景。
     *
     * @return 词 token（保序在前）+ 各 ≥3 字词的 bigram（去重），整体已去重保序
     */
    fun tokenizeForQuery(text: String): List<String> {
        val words = tokenize(text)
        if (words.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        val seen = HashSet<String>()
        for (word in words) {
            if (seen.add(word)) result.add(word)
            // 对 ≥3 字的词提取字符 bigram，弥补分词器对专有名词的切分不一致
            if (word.length >= 3) {
                for (i in 0 until word.length - 1) {
                    val bigram = word.substring(i, i + 2)
                    if (seen.add(bigram)) result.add(bigram)
                }
            }
        }
        return result
    }
}
