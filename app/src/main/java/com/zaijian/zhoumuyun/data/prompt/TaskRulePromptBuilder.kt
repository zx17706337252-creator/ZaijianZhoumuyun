package com.zaijian.zhoumuyun.data.prompt

/**
 * Task / Rule / AgentPlan / GroupContext 构建器（从 PromptOrchestrator 拆出，沿用 object 单例写法）。
 *
 * 从 PromptOrchestrator.kt 迁移的公开函数（有外部调用点）：
 * buildTaskLayerBlock / buildRuleLayerBlock / buildAgentPlanBlock / buildGroupContextBlock，
 * 以及 Rule Layer 的 [Constants] 预算常量。
 */
object TaskRulePromptBuilder {

    object Constants {
        /** 每目标最多注入规则数 */
        const val MAX_RULES_PER_GOAL = 10
        /** 所有目标合计最多注入规则数（Token 预算硬上限） */
        const val MAX_TOTAL_RULES = 50
    }

    /**
     * 构建 Task Layer 注入块。
     *
     * @param taskCompleted 任务是否已完成（完成后角色以第一人称向用户汇报）
     */
    fun buildTaskLayerBlock(
        taskType: String,
        currentStep: String? = null,
        toolResults: List<String> = emptyList(),
        pendingTools: List<String> = emptyList(),
        taskCompleted: Boolean = false,
    ): String {
        if (taskType.isBlank()) return ""

        return buildString {
            appendLine("[当前任务]")
            appendLine("你正在帮TA完成：$taskType")
            if (currentStep != null) appendLine("当前步骤：$currentStep")
            if (toolResults.isNotEmpty()) {
                appendLine()
                appendLine("已完成的操作：")
                toolResults.take(5).forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
            }
            if (pendingTools.isNotEmpty()) {
                appendLine()
                append("接下来需要：${pendingTools.joinToString("、")}")
            }
            if (taskCompleted) {
                appendLine()
                append("任务已完成。请用你自己的语气告知TA结果，不要提及工具或技术细节。")
            }
        }.trimEnd()
    }

    /**
     * 构建 Rule Layer 注入块。
     *
     * 格式：
     * ```
     * [能力规则]
     * 目标：{goalTitle}
     *   🔒 {rule1}
     *   🔒 {rule2}
     *   …（最多10条）
     *
     * 目标：{goalTitle2}
     *   🔒 {rule1}
     *   …
     * ```
     *
     * Token 预算：
     *   - 每目标最多 10 条规则（调用方已通过 DAO limit=10 截断）
     *   - 总计硬上限 50 条；超出的目标整体跳过（优先保留先激活目标）
     *
     * @param rulesByGoal  Map<goalTitle, List<ruleContent>>，key 为目标标题，value 为规则内容列表
     *                     调用方需确保每目标 ≤10 条、总计 ≤50 条
     */
    fun buildRuleLayerBlock(rulesByGoal: Map<String, List<String>>): String {
        if (rulesByGoal.isEmpty()) return ""
        val filtered = buildMap {
            var totalRules = 0
            for ((goalTitle, rules) in rulesByGoal) {
                if (totalRules >= Constants.MAX_TOTAL_RULES) break
                val allowed = minOf(rules.size, Constants.MAX_RULES_PER_GOAL, Constants.MAX_TOTAL_RULES - totalRules)
                if (allowed > 0) {
                    put(goalTitle, rules.take(allowed))
                    totalRules += allowed
                }
            }
        }
        if (filtered.isEmpty()) return ""

        return buildString {
            appendLine("[能力规则]")
            filtered.entries.forEachIndexed { i, (goalTitle, rules) ->
                if (i > 0) appendLine()
                appendLine("目标：$goalTitle")
                rules.forEach { rule -> appendLine("  🔒 $rule") }
            }
        }.trimEnd()
    }

    /**
     * 格式化 AgentPlan Layer 注入块。
     *
     * @param title   方案标题
     * @param content 方案正文（已在 PlanSaveTool 截断为 ≤1500 字）
     */
    fun buildAgentPlanBlock(title: String, content: String): String {
        if (content.isBlank()) return ""
        return buildString {
            appendLine("[Agent 进化方案]")
            if (title.isNotBlank()) appendLine("方案：$title")
            append(content)
        }.trimEnd()
    }

    /**
     * Phase 14：构建圆桌 group_context 注入块。
     *
     * 由 RoundtableViewModel 调用，替代之前的 buildGroupContextBlock 私有方法。
     * 抽出到 PromptOrchestrator 后，所有 Prompt 构建逻辑统一在此文件管理。
     *
     * 格式：
     * ```
     * [本轮已有回复]
     * ─────────────────────────
     * {Bot名}（刚才说）：
     * {完整回复（最多300字）}
     * ─────────────────────────
     * 以上是本轮其他人的发言，你现在来回应。
     * 根据你的性格，可以回应用户、回应TA们，或受到影响后用自己方式回应用户。
     * 不需要重复TA们说过的内容，直接表达你的立场。
     *
     * [接话规则]
     * - 前面如果有人发出的是任务指派/要求执行的内容，你必须在回复中明确确认或执行，不能视而不见。
     * - 前面如果是方案类发言，你可以提出自己的完整方案，但要先表明认同/补充/不同意前面的观点，不能完全无视、不能重复别人说过的话。
     * ```
     *
     * 待办6 Step4（圆桌调度重构 §5 接话感知强化）：
     * 不引入新数据结构，纯 Prompt 层面追加「接话规则」固定文案——
     * 复用本函数原有的 alreadyReplied 非空判断，只在"本轮确实有人已经发过言"时追加，
     * 避免空跑时注入无意义的规则文案。
     *
     * 额外承接待办6 Step3「自动连续讨论循环」的收敛引导：
     * discussionRound > 1（即续轮）时追加一条"方案成熟就明确收尾"的提示，
     * 帮助 judgeDiscussionConcluded 更快判定收敛，减少触达 6 轮安全上限的概率。
     * 这条提示在续轮的第一位发言人时也要出现（此时 alreadyReplied 还是空的，
     * 因为 RoundtableViewModel.executeRound 每轮都会重置 alreadyReplied），
     * 所以整体的"是否输出"判断不能只看 alreadyReplied 是否为空。
     *
     * @param alreadyReplied   key=characterId，value=该角色本轮完整回复
     * @param memberNameMap    key=characterId，value=角色名（供显示用）
     * @param respondingOtherBot 当前 Bot 倾向于回应另一个 Bot（添加额外提示）
     * @param isAutoDiscussing 是否处于待办6 Step3 的自动连续讨论循环中（全体@触发）
     * @param discussionRound  当前讨论轮次（从1开始计），仅在 isAutoDiscussing 为 true 时有意义
     * @param notifiedByName   1.3 圆桌点名机制修复：非空时表示当前角色本轮被显式 @ 点名，
     *                         值为点名者名字（目前唯一来源是"用户"，Bot 互相 @ 暂未实现）。
     *                         非空时追加一段强制正面回应的文案，不能含糊回避或假装没看到。
     */
    fun buildGroupContextBlock(
        alreadyReplied: Map<Int, String>,
        memberNameMap: Map<Int, String>,
        respondingOtherBot: Boolean = false,
        isAutoDiscussing: Boolean = false,
        discussionRound: Int = 1,
        notifiedByName: String? = null,
    ): String {
        val hasOngoingReplies = alreadyReplied.isNotEmpty()
        val inConvergencePhase = isAutoDiscussing && discussionRound > 1
        val isNotified = !notifiedByName.isNullOrEmpty()
        if (!hasOngoingReplies && !inConvergencePhase && !isNotified) return ""

        return buildString {
            if (hasOngoingReplies) {
                appendLine("[本轮已有回复]")
                alreadyReplied.forEach { (id, reply) ->
                    val name = memberNameMap[id] ?: "（未知）"
                    appendLine("─────────────────────────")
                    appendLine("$name（刚才说）：")
                    appendLine(reply.take(300))
                }
                appendLine("─────────────────────────")
                if (respondingOtherBot) {
                    // RESPOND_OTHER_BOT：强制接话，但只针对这个 intent
                    appendLine("以上是本轮其他人的发言。你这次倾向于接着刚才最后一条发言的观点来说——")
                    appendLine("可以认同、补充、质疑或反驳，但要明确表明你对她观点的立场，不要重复她说过的内容。")
                    appendLine("如果前面有任务指派或明确要求执行的内容，你也要在回复中确认或执行。")
                } else {
                    // RESPOND_USER / INFLUENCED_BY_BOT：软提示，角色自由决定是否接话
                    appendLine("以上是本轮其他人的发言，仅供参考。")
                    append("你可以完全无视她们、直接回应他；也可以在自然的地方顺带提一句对某人发言的看法——完全取决于你的性格和此刻的状态。不要刻意表态，不要重复她们说过的话。")
                }
            }
            if (inConvergencePhase) {
                if (hasOngoingReplies) appendLine().appendLine()
                append("（这是自动连续讨论的第 $discussionRound 轮：如果方案已经成熟、大家意见已基本一致，请明确表态「可以了」「没问题」，不要为了发言硬找新角度展开；如果确实还有分歧或遗漏，再继续补充，帮助讨论尽快收尾。）")
            }
            if (isNotified) {
                if (hasOngoingReplies || inConvergencePhase) appendLine().appendLine()
                appendLine("[点名提醒]")
                append("$notifiedByName 刚才点名（@）了你，这是对你的直接呼叫。你这一轮必须正面回应 TA，不能回避、不能假装没看到、不能只顾着回应别人而漏掉这一点。")
            }
        }.trimEnd()
    }
}