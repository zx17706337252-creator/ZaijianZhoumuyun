package com.zaijian.zhoumuyun.domain

/**
 * 架构位置说明（本次从 `ui.viewmodel` 包下沉至 `domain` 包）：
 *
 * 原实现位于 `ui.viewmodel.ChatTagParser`，`ChatMessageOrchestrator`/`ChatViewModel`
 * 同包直接调用。`domain.ProactiveMessageNotifier` 落库前需要复用同一套剥离逻辑时，
 * 若继续留在 `ui.viewmodel` 包，会形成 `domain` → `ui.viewmodel` 的反向依赖——
 * 全项目此前不存在这种反向依赖（`ui.viewmodel` 单向依赖 `domain`，没有反过来的先例），
 * 贸然引入会破坏既有分层。而这个对象本身是纯文本处理（只依赖同包的 `MoodType`），
 * 不依赖任何 Compose/ViewModel/Android UI 概念，本就不该长在 `ui.viewmodel` 包里，
 * 下沉到 `domain` 是修正一个本就放错位置的历史归属，不是新增耦合。
 *
 * `toChatMessage()` 扩展函数（依赖 `ui.viewmodel.ChatMessage`）保留在原 `ui.viewmodel`
 * 包（现文件 `ChatMessageMapper.kt`），只是改为对本对象的扩展。
 */
object ChatTagParser {

    // Fix-MoodLeak：匹配末尾的 [mood:词] 或 [mood：词]（中英文冒号都兼容），
    // 允许标签前有空行/空格，允许标签后有少量尾随空白。
    private val MOOD_TAG_REGEX = Regex("""\[mood[:：]\s*([^\[\]]+?)\s*]\s*$""")

    // display 专用：末尾出现 "[mood" 任意未闭合前缀时也要隐藏，前面允许换行/空格。
    // 例如 "[", "[m", "[mo", "[moo", "[mood", "[mood:", "[mood:平" 等streaming中间态。
    private val PARTIAL_MOOD_TAG_REGEX = Regex("""\s*\[m(o(o(d(\s*[:：]\s*[^\[\]]*)?)?)?)?$""")

    // Fix-ThinkingLeak：匹配 [thinking:...] 或 [thinking：...]（中英文冒号都兼容），
    // DOT_MATCHES_ALL 允许标签内部跨行（内心推理可能是多行文本）。
    // 与 MOOD_TAG_REGEX 一样限定内部不含方括号，避免贪婪匹配跨越多个标签、误吞中间的
    // 正文——已知局限：如果模型的思考内容本身包含方括号（较少见），会在此处截断，
    // 可接受，不为这个边缘情况引入更复杂的括号计数解析。
    private val THINKING_TAG_REGEX = Regex(
        """\[thinking[:：]\s*([^\[\]]*?)\s*]""",
        RegexOption.DOT_MATCHES_ALL,
    )

    // display 专用：末尾出现 "[thinking" 任意未闭合前缀时也要隐藏，前面允许换行/空格，
    // 用法与 PARTIAL_MOOD_TAG_REGEX 同一思路——见 stripTagsForDisplay 顶部注释。
    private val PARTIAL_THINKING_TAG_REGEX = Regex(
        """\s*\[t(h(i(n(k(i(n(g(\s*[:：]\s*[^\[\]]*)?)?)?)?)?)?)?)?$"""
    )

    // v1.36 问题2（三层分离）：心理感受描写，中文全角圆括号（　）包裹的独立成句内容。
    // 与既有动作/神情标注不强制做语义区分——两者都用圆括号，本次改动
    // 只按"是否被圆括号包裹"统一抽取渲染为心理感受小卡，不细分是心理活动还是动作提示。
    // 用懒惰匹配（不含括号本身）而非 THINKING_TAG_REGEX 那种贪婪跨越写法，避免相邻两段
    // 心理描写被错误合并成一段——这个差异是有意为之，不要照抄 thinking 的正则模式。
    private val PSYCH_TAG_REGEX = Regex(
        """（([^（）]*?)）""",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun stripPartialMoodTagForDisplay(text: String): String {
        val (afterFullStrip, _) = stripMoodTag(text)
        // afterFullStrip 与 text 不同说明已经命中完整标签，直接返回即可。
        if (afterFullStrip != text) return afterFullStrip
        // 否则检查末尾是否是 "[mood" 的某个前缀（如 "[", "[m", "[mo", "[moo", "[mood", "[mood:" 等），
        // 前面允许有换行/空格。
        val tailMatch = PARTIAL_MOOD_TAG_REGEX.find(text) ?: return text
        return text.substring(0, tailMatch.range.first).trimEnd()
    }

    /**
     * display 专用总入口（Fix-ThinkingLeak）：thinking 标签剥离 + mood 标签剥离（含半截）一起跑。
     *
     * 与 mood 的关键差异——mood 固定出现在全文最后一行，只需锚定字符串末尾；
     * thinking 标签可能出现在正文任意位置（角色说一段台词、插一段思考、再说一段台词），
     * 所以：
     *   1) 先对全文做一次 THINKING_TAG_REGEX.replace，剥掉所有"已经完整闭合"的 thinking 标签；
     *   2) 再跑原有的 stripPartialMoodTagForDisplay，处理末尾的 mood 标签（完整或半截）；
     *   3) 最后检查处理完前两步后的文本末尾，是否残留一个"尚未闭合"的半截 thinking 前缀
     *      （如 "...台词\n[think"）——由于模型在标签闭合前不会产出标签之后的新内容，
     *      未闭合的 thinking 标签在任意时刻的流式文本里必然只会出现在末尾，
     *      用与 PARTIAL_MOOD_TAG_REGEX 相同的"锚定末尾"策略即可覆盖，不需要更复杂的状态机。
     */
    fun stripTagsForDisplay(fullText: String): String {
        val afterThinking = THINKING_TAG_REGEX.replace(fullText, "")
        val afterMood = stripPartialMoodTagForDisplay(afterThinking)
        val tailMatch = PARTIAL_THINKING_TAG_REGEX.find(afterMood) ?: return afterMood
        return afterMood.substring(0, tailMatch.range.first).trimEnd()
    }

    /**
     * 流式展示专用（修复"折叠的思考过程不显示"问题的一部分）：与 [stripTagsForDisplay]
     * 相比多剥离一层——圆括号包裹的心理感受描写。
     *
     * 背景：此前圆桌（[RoundtableBotReplyGenerator]/[RoundtableIdleManager]）和私聊
     * （[com.zaijian.zhoumuyun.ui.viewmodel.ChatMessageOrchestrator]）流式阶段都特意
     * 保留圆括号内容裸露在正文里，直到整条回复生成完毕才用 [stripPsychText] 摘出——
     * 原意是"与 mood/thinking 的处理粒度对齐、减少流式阶段的重复正则扫描"，但实际
     * 体验是用户在角色打字的这几秒钟里，看到的是未加工的圆括号原文（"（心里一动）"
     * 这种），说完的瞬间才"跳变"成折叠卡片，容易让人误以为折叠功能根本不存在。
     *
     * 本函数在流式阶段同步剥离圆括号，返回（剥离后正文, 已闭合圆括号拼接出的心理
     * 描写或null）。与 [stripPsychText] 用同一个正则/同一种拼接方式，只是不在这里
     * 处理"未闭合的半截圆括号"——右括号还没生成出来之前，正则天然不会匹配到它，
     * 半截内容会保留在正文里直到下一个 token 补全右括号，这与 thinking/mood 标签
     * "锚定末尾处理半截前缀"的做法不同，是因为圆括号没有固定长度的标签名前缀可供
     * 锚定匹配（[thinking/[mood 这几个字符是已知的，"（"后面接什么完全不确定），
     * 强行做半截兜底价值不大，短暂的一两个 token 延迟可接受。
     */
    fun stripTagsForDisplayWithPsych(fullText: String): Pair<String, String?> {
        val afterThinking = THINKING_TAG_REGEX.replace(fullText, "")
        val afterMood = stripPartialMoodTagForDisplay(afterThinking)
        val withoutTail = PARTIAL_THINKING_TAG_REGEX.find(afterMood)
            ?.let { afterMood.substring(0, it.range.first).trimEnd() }
            ?: afterMood
        return stripPsychText(withoutTail)
    }

    /**
     * 剥离回复末尾的 `[mood:情绪词]` 系统标记，返回（净文本, 解析出的 MoodType?）。
     *
     * 背景（Fix-MoodLeak）：COMPANION 模式的 Output Layer
     * （见 PromptOrchestrator.COMPANION_OUTPUT_CONSTRAINTS）要求 LLM 在正文末尾另起一行输出
     * `[mood:情绪词]`，注释明确写"系统使用，不展示给用户"，但此前全项目
     * 没有任何代码解析或剥离它——用户在这两种模式下每条回复末尾都会看到
     * 裸露的 `[mood:平静]` 这类内部标记，且 PresenceEngine.updateMoodFromReply()
     * 已经写好却从未被调用。
     *
     * @return Pair(去除标签后的文本, 解析出的 MoodType；未命中或无标签则为 null)
     */
    fun stripMoodTag(reply: String): Pair<String, MoodType?> {
        val match = MOOD_TAG_REGEX.find(reply) ?: return reply to null
        val cleaned = reply.substring(0, match.range.first).trimEnd()
        val moodWord = match.groupValues[1].trim()
        return cleaned to parseMoodType(moodWord)
    }

    /**
     * 剥离正文中所有 `[thinking:...]` 内心推理标签，返回（净文本, 合并后的思考内容或null）。
     *
     * 背景（Fix-ThinkingLeak）：Output Layer（PromptOrchestrator.WORK_OUTPUT_CONSTRAINTS /
     * COMPANION_OUTPUT_CONSTRAINTS）新增规则，要求 LLM 把
     * 内心推理、收到的指令原文、工具调用意图包进 `[thinking:...]` 标签，不能直接写进标签外的
     * 正文——这套"结构化标记 + 客户端剥离"完全复用 stripMoodTag 已验证过的技术路径。
     *
     * 与 mood 标签的两点差异：
     *   1) mood 固定只出现一次、且在全文最后一行；thinking 可能出现在正文任意位置，
     *      也可能出现不止一次（模型分几段记录思考），所以用 findAll + replace 而非单次 find。
     *   2) mood 命中即返回单个 MoodType；thinking 命中多段时按出现顺序拼接，中间用空行分隔，
     *      交给想法卡片作为一段完整内容展示。
     *
     * @return Pair(去除所有 thinking 标签后的正文, 按出现顺序拼接的思考内容；未命中则为 null)
     */
    fun stripThinkingTag(reply: String): Pair<String, String?> {
        val matches = THINKING_TAG_REGEX.findAll(reply).toList()
        if (matches.isEmpty()) return reply to null
        val thoughts = matches.joinToString(separator = "\n\n") { it.groupValues[1].trim() }.trim()
        // 标签原地整段抠掉后，原来标签独占一行的位置会留下多余空行，
        // 压缩连续 3 行及以上空行为 1 个空行，避免正文出现大片空白。
        val cleaned = THINKING_TAG_REGEX.replace(reply, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return cleaned to thoughts.ifBlank { null }
    }

    /**
     * 剥离正文中所有圆括号包裹的心理感受/神态描写，返回（净文本, 合并后的心理描写或null）。
     *
     * 背景（v1.36 问题2·三层分离）：内心想法折叠功能"看起来没实现"，根因是模型输出的
     * 心理描写用中文全角圆括号（　），不是 [thinking:] 标签，解析器此前完全没识别到，
     * 圆括号内容原样混在台词正文里展示。本函数专门抽取这部分内容，与 stripThinkingTag
     * 抽出的"决策思考"内容分开存储、分开渲染。
     *
     * 调用顺序（在 ChatMessageOrchestrator / ProactiveMessageNotifier 里）：先
     * stripThinkingTag 剥离 [thinking:] 标签，再对剩余文本调用本函数剥离圆括号——
     * 两种标记互不重叠，谁先谁后不影响正确性，但保持"先处理内层结构化标签、
     * 再处理裸文本标记"的顺序更符合直觉。
     *
     * 已知局限（有意不在本次处理，留给实测后再决定是否需要）：模型有时会把很短的
     * 舞台提示（如"（笑）"）也塞进圆括号，这类内容会被一并抽取到 psychText，
     * 而不是本意上的"心理感受"。当前不做长度阈值过滤——过早引入阈值属于没有实测
     * 数据支撑的过度设计，如果实测发现心理小卡内容过短、过于频繁，再回来加。
     *
     * @return Pair(去除所有圆括号标记后的正文, 按出现顺序拼接的心理描写；未命中则为 null)
     */
    fun stripPsychText(reply: String): Pair<String, String?> {
        val matches = PSYCH_TAG_REGEX.findAll(reply).toList()
        if (matches.isEmpty()) return reply to null
        val psych = matches.joinToString(separator = "\n\n") { it.groupValues[1].trim() }.trim()
        val cleaned = PSYCH_TAG_REGEX.replace(reply, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return cleaned to psych.ifBlank { null }
    }

    /**
     * Fix⑥：COMPANION_OUTPUT_CONSTRAINTS 里
     * 给 LLM 的情绪词枚举（中文）与 MoodType（英文枚举）做对应——
     * 两边在设计时本就是按顺序一一对应的（平静/专注/好奇/满足/担忧/兴奋/疲惫/沉思
     * ↔ CALM/FOCUSED/CURIOUS/SATISFIED/CONCERNED/EXCITED/TIRED/REFLECTIVE），
     * 只是从未写出这层转换代码。未命中时返回 null（不更新 mood，静默忽略，
     * 不让一次格式异常的 LLM 输出打断主流程）。
     */
    private fun parseMoodType(word: String): MoodType? = when (word) {
        "平静" -> MoodType.CALM
        "专注" -> MoodType.FOCUSED
        "好奇" -> MoodType.CURIOUS
        "满足" -> MoodType.SATISFIED
        "担忧" -> MoodType.CONCERNED
        "兴奋" -> MoodType.EXCITED
        "疲惫" -> MoodType.TIRED
        "沉思" -> MoodType.REFLECTIVE
        else   -> null
    }
}
