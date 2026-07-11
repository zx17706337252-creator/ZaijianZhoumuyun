package com.zaijian.zhoumuyun.domain

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SpecialtyEvolutionEngine — P6 专长进化系统核心判断引擎
 *
 * 与 EvaluationEngine（打分）/DistillationEngine（Phase26 规则提炼）平级，
 * 物理隔离，互不调用——专长进化系统不复用原有的"高分会话累积→提炼规则"链路，
 * 这里走的是"主动练习→风格比对→分层蒸馏"的独立闭环（见设计方案第0节）。
 *
 * 本文件承载的职责（按调用频率从高到低排列）：
 *   ① [compareAgainstStyleNotes] 风格比对：每次 PracticeRecord 产出后必调
 *   ② [matchCandidateObservation] 候选观察语义匹配：EMERGING 结果出现后调用
 *   ③ [digestRawRecords] 第1→2层蒸馏：原始产出 → 阶段摘要
 *   ④ [mergeStageDigestsIntoProfile] 第2→3层蒸馏：阶段摘要 → 并入 styleNotes
 *   ⑤ [evaluateIdentityPromotion] 晋升判定：styleNotes 某特征是否够格升入 soulNote
 *   ⑥ [integrateIntoSoulNote] 晋升整合：将精华自然融入已有 soulNote，避免生硬拼接
 *   ⑦ [generateSystemSuggestion] AI 自我提案（低频，仅建议）
 *
 * 所有方法统一遵循项目既有的 LLM 调用规范（与 EvaluationEngine 同构）：
 *   - 判断/规划类调用统一用低 temperature（SpecialtyEvolutionConfig.JUDGMENT_TEMPERATURE）
 *   - 严格要求 JSON 输出，解析前先做"防 Markdown 代码块包裹"的容错处理
 *   - 解析失败或字段缺失时返回安全的兜底值，不抛异常，不污染数据
 */
class SpecialtyEvolutionEngine(
    private val provider: LLMProvider,
) {

    /** 从 LLM 原始输出中提取 JSON 子串，防止前后被 Markdown 代码块或解释文字包裹（与 EvaluationEngine 同款写法） */
    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    // ─────────────────────────────────────────────────────────
    //  ① 风格比对（方案第5.1节）
    // ─────────────────────────────────────────────────────────

    data class ComparisonResult(
        val result: String,        // "REINFORCING" | "EMERGING" | "CONFLICTING"
        val note: String,
        val observedTrait: String,
    )

    /**
     * 判断一次新创作与已有风格描述（styleNotes + 候选观察池）的关系。
     *
     * styleNotes 为空（摸索期前几次）时，模型大概率每次都判定 EMERGING——
     * 这是合理的，摸索期本来就是"什么都算新发现"的阶段（见方案第5.1节末尾说明）。
     */
    suspend fun compareAgainstStyleNotes(
        domain: String,
        styleNotes: String,
        candidateObservationsSummary: String,
        newContent: String,
    ): ComparisonResult = withContext(Dispatchers.IO) {
        val systemPrompt = """
            你在判断一段新创作与角色已确立的风格描述之间的关系。专长方向：$domain

            请判断这次创作与已有风格的关系，只能三选一：
            - REINFORCING：延续/印证了已有风格描述中的某个特征，没有引入新信息
            - EMERGING：出现了一个目前styleNotes/候选观察池里都没有的、值得记录的新特征
            - CONFLICTING：与styleNotes中明确写到的某个特征相矛盾

            仅输出 JSON，不加任何其他文字：
            {"result":"REINFORCING|EMERGING|CONFLICTING","note":"一句话说明判断依据，不超过60字","observedTrait":"若result=EMERGING，用一句话描述观察到的新特征；否则为空字符串"}
        """.trimIndent()

        val userPrompt = buildString {
            append("已确立的风格描述（可能为空，为空说明角色仍处于摸索期）：\n")
            append(styleNotes.ifBlank { "（暂无，仍在摸索阶段）" })
            append("\n\n最近的候选观察（尚未确认是否成为正式风格，可能为空）：\n")
            append(candidateObservationsSummary.ifBlank { "（暂无）" })
            append("\n\n这次新创作：\n")
            append(newContent.take(2000))
        }

        try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config = LLMConfig(
                    model = "",
                    maxTokens = 200,
                    temperature = SpecialtyEvolutionConfig.JUDGMENT_TEMPERATURE,
                    stream = false,
                ),
            )
            val obj = JSONObject(extractJson(response))
            val result = obj.optString("result", "REINFORCING").let {
                if (it in setOf("REINFORCING", "EMERGING", "CONFLICTING")) it else "REINFORCING"
            }
            ComparisonResult(
                result = result,
                note = obj.optString("note", "").take(100),
                observedTrait = if (result == "EMERGING") obj.optString("observedTrait", "").take(120) else "",
            )
        } catch (_: Exception) {
            // 解析失败时按 REINFORCING 兜底——这是最安全的默认值：
            // 不会误触发候选池写入，也不会误判冲突，只是"这次先不当新信息处理"
            ComparisonResult("REINFORCING", "（风格比对解析失败，按默认处理）", "")
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ② 候选观察语义匹配（方案第5.3节）
    // ─────────────────────────────────────────────────────────

    /**
     * 判断新观察到的特征，是否是候选池里已有某条的另一种表述（语义匹配，
     * 不是字符串包含——这是对 Phase26 原 DistillationEngine"前10字包含判断"的
     * 明确改进）。
     *
     * @return 匹配到的已有候选 trait 原文；未匹配到任何已有候选则返回 null
     */
    suspend fun matchCandidateObservation(
        newTrait: String,
        existingTraits: List<String>,
    ): String? = withContext(Dispatchers.IO) {
        if (existingTraits.isEmpty()) return@withContext null

        val systemPrompt = """
            你在判断一个新观察到的特征描述，是否在语义上等同于已有列表中的某一条
            （表述方式可能不同，但指向的是同一种倾向/习惯）。

            仅输出 JSON，不加任何其他文字：
            {"matchedIndex":-1或匹配到的列表序号（从0开始）}
        """.trimIndent()

        val userPrompt = buildString {
            append("已有候选特征列表：\n")
            existingTraits.forEachIndexed { i, t -> append("$i. $t\n") }
            append("\n新观察到的特征：\n$newTrait")
        }

        try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config = LLMConfig(
                    model = "",
                    maxTokens = 50,
                    temperature = SpecialtyEvolutionConfig.JUDGMENT_TEMPERATURE,
                    stream = false,
                ),
            )
            val obj = JSONObject(extractJson(response))
            val idx = obj.optInt("matchedIndex", -1)
            if (idx in existingTraits.indices) existingTraits[idx] else null
        } catch (_: Exception) {
            // 匹配失败时按"全新特征"处理——宁可候选池多一条独立观察，
            // 也不要错误合并两个语义不同的特征到同一条计数下
            null
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ③ 第1→2层蒸馏：原始产出 → 阶段摘要（方案第5.2节）
    // ─────────────────────────────────────────────────────────

    /** 供蒸馏 Prompt 使用的单条记录精简描述 */
    data class RecordForDigest(
        val practiceTopic: String,
        val content: String,
        val comparisonResult: String,
        val comparisonNote: String,
    )

    data class DigestResult(
        val digestContent: String,
        val hasConflict: Boolean,
        val conflictSummary: String,
    )

    /**
     * 将一批原始练习记录蒸馏成一段阶段摘要。
     *
     * 蒸馏权重按 comparisonResult 分层（不在 Kotlin 侧做截断式预处理，
     * 而是把完整记录连同标签一起交给 LLM，让模型在生成摘要正文时自己
     * 决定每条记录该占多少篇幅——这样 REINFORCING 记录会被自然地一句话
     * 概括，CONFLICTING 记录会被自然地完整保留，比预先截断内容更可靠）。
     */
    suspend fun digestRawRecords(
        domain: String,
        records: List<RecordForDigest>,
    ): DigestResult = withContext(Dispatchers.IO) {
        val systemPrompt = """
            以下是角色在「$domain」方向最近的 ${records.size} 次创作记录，请整理成一段阶段性摘要。

            整理原则：
            1. 标记为 REINFORCING 的记录，只需在摘要里用一句话概括"这段时间反复验证了XX特征"，
               不需要逐条复述每次的具体内容。
            2. 标记为 EMERGING 的记录，需要具体说明观察到了什么新倾向、出现在什么场景下。
            3. 标记为 CONFLICTING 的记录，必须完整保留这次的具体描述，并明确指出与哪条已有
               风格特征冲突，不能简化处理——这类记录需要用户后续裁决，过度概括会丢失裁决所需信息。

            仅输出 JSON，不加任何其他文字：
            {"digestContent":"整段阶段摘要正文，建议200-400字","hasConflict":true或false,"conflictSummary":"若hasConflict为true，简述冲突点；否则为空字符串"}
        """.trimIndent()

        val userPrompt = buildString {
            records.forEachIndexed { i, r ->
                append("【记录${i + 1}】主题：${r.practiceTopic}\n")
                append("标签：${r.comparisonResult}（${r.comparisonNote}）\n")
                append("内容：${r.content.take(600)}\n\n")
            }
        }

        try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config = LLMConfig(
                    model = "",
                    maxTokens = 800,
                    temperature = SpecialtyEvolutionConfig.JUDGMENT_TEMPERATURE,
                    stream = false,
                ),
            )
            val obj = JSONObject(extractJson(response))
            DigestResult(
                digestContent = obj.optString("digestContent", "").take(600),
                hasConflict = obj.optBoolean("hasConflict", false),
                conflictSummary = obj.optString("conflictSummary", "").take(200),
            )
        } catch (_: Exception) {
            // 蒸馏失败时返回空摘要，调用方（DistillationTrigger）应跳过本次合并，
            // 保留原始记录不降级，下次容量阈值再次达到时重试——
            // 不能把失败也当成"已蒸馏"写进数据库，否则会丢信息
            DigestResult("", false, "")
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ④ 第2→3层蒸馏：阶段摘要 → 并入 styleNotes（方案第5.5节）
    // ─────────────────────────────────────────────────────────

    data class MergeResult(
        val updatedStyleNotes: String,
        val hasUnresolvedConflict: Boolean,
        val conflictDescription: String,
    )

    /**
     * 将若干阶段摘要并入现有 styleNotes，整段重写（不是追加）。
     * 这是用户要求"留下精华"的核心执行点——见方案第5.5节完整 Prompt 设计。
     */
    suspend fun mergeStageDigestsIntoProfile(
        currentStyleNotes: String,
        stageDigests: List<String>,
    ): MergeResult = withContext(Dispatchers.IO) {
        val systemPrompt = """
            请输出更新后的完整风格说明书，要求：
            1. 这不是"追加"，是重写整段说明书。已有内容如果仍然成立，需要保留其要点
               （但不要求保留原文措辞，可以更精炼地重新表达）。
            2. 新的阶段摘要内容，对每一条要明确判断：
               - 「强化」已有特征的具体表现 → 合并进对应描述，不单独占篇幅
               - 「补充」一个全新的、不冲突的特征 → 作为新段落加入
               - 「冲突」与已有某条明确矛盾 → 不要擅自覆盖，单独列出作为
                 "待确认的分歧"，不计入正式风格描述
            3. 整体严格控制在 ${SpecialtyEvolutionConfig.STYLE_NOTES_MAX_CHARS} 字以内。
               如果信息量超出，优先压缩"强化类"的表达（这类信息本质是重复确认，
               可以更简略），不要为了塞入新内容而删减已经稳定确立的核心特征。

            仅输出 JSON，不加任何其他文字：
            {"updatedStyleNotes":"更新后的完整说明书正文","hasUnresolvedConflict":true或false,"conflictDescription":"若有冲突，描述分歧内容；否则为空字符串"}
        """.trimIndent()

        val userPrompt = buildString {
            append("角色当前的风格说明书（可能为空）：\n")
            append(currentStyleNotes.ifBlank { "（暂无）" })
            append("\n\n最近积累的 ${stageDigests.size} 份阶段摘要：\n")
            stageDigests.forEachIndexed { i, d -> append("【摘要${i + 1}】$d\n\n") }
        }

        try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config = LLMConfig(
                    model = "",
                    maxTokens = 1200,
                    temperature = SpecialtyEvolutionConfig.JUDGMENT_TEMPERATURE,
                    stream = false,
                ),
            )
            val obj = JSONObject(extractJson(response))
            val updated = obj.optString("updatedStyleNotes", currentStyleNotes)
                .take(SpecialtyEvolutionConfig.STYLE_NOTES_MAX_CHARS)
            MergeResult(
                updatedStyleNotes = updated,
                hasUnresolvedConflict = obj.optBoolean("hasUnresolvedConflict", false),
                conflictDescription = obj.optString("conflictDescription", "").take(200),
            )
        } catch (_: Exception) {
            // 合并失败时原样保留旧 styleNotes，不丢失已有内容，调用方应跳过本次合并
            MergeResult(currentStyleNotes, false, "")
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ⑤ 晋升判定的 LLM 辅助判断（方案第6.2节条件2）
    // ─────────────────────────────────────────────────────────
    //
    // 注意：完整的复合判定（四个条件）由 IdentityPromotionEvaluator 统筹，
    // 其中条件1/3/4是纯数据判断（成熟度/用户确认标记/冲突标记，不需要LLM），
    // 只有条件2"该特征是否已稳定存在"需要 LLM 辅助——具体做法是比较本轮
    // styleNotes 和上一版本 styleNotes，判断某条特征是否在两版中都有相近表述
    // （见 SpecialtyEvolutionConfig 末尾"已知限制"说明：这是合理近似，
    // 不是严格的版本级追踪）。

    data class StabilityCheckResult(
        val stableTraits: List<String>,
    )

    /**
     * 比较两版 styleNotes，找出"在两版中都有相近表述"的特征。
     * 调用时机：每次 mergeStageDigestsIntoProfile 完成后，IdentityPromotionEvaluator
     * 用本方法判断哪些特征已经连续跨越多轮合并保持稳定。
     */
    suspend fun findStableTraits(
        previousStyleNotes: String,
        currentStyleNotes: String,
    ): StabilityCheckResult = withContext(Dispatchers.IO) {
        if (previousStyleNotes.isBlank() || currentStyleNotes.isBlank()) {
            return@withContext StabilityCheckResult(emptyList())
        }

        val systemPrompt = """
            比较风格说明书的两个版本，找出在两版中都有相近表述的具体特征
            （措辞可以不同，但指向同一种习惯/倾向）。只关心"延续下来的"，
            不需要列出哪个版本独有的内容。

            仅输出 JSON，不加任何其他文字：
            {"stableTraits":["特征描述1","特征描述2"]}
        """.trimIndent()

        val userPrompt = "旧版本：\n$previousStyleNotes\n\n新版本：\n$currentStyleNotes"

        try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config = LLMConfig(
                    model = "",
                    maxTokens = 300,
                    temperature = SpecialtyEvolutionConfig.JUDGMENT_TEMPERATURE,
                    stream = false,
                ),
            )
            val obj = JSONObject(extractJson(response))
            val arr = obj.optJSONArray("stableTraits")
            val list = mutableListOf<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) list.add(arr.optString(i, ""))
            }
            StabilityCheckResult(list.filter { it.isNotBlank() })
        } catch (_: Exception) {
            StabilityCheckResult(emptyList())
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ⑥ 晋升整合：将精华自然融入已有 soulNote（方案第6.4节）
    // ─────────────────────────────────────────────────────────

    /**
     * 将已晋升的专长特征整合进现有人设备忘录（soulNote），不是简单拼接。
     * 措辞要求"她本来就是/她一直都是"，避免任何暗示"最近养成"的语气——
     * 这是"本能感"在文字层面的最后一道保证（结构层面的保证是注入位置本身，
     * 见方案第6.3节）。
     */
    suspend fun integrateIntoSoulNote(
        currentSoulNote: String,
        promotedTraitDescription: String,
    ): String = withContext(Dispatchers.IO) {
        val systemPrompt = """
            请将两者整合成一段连贯的人设备忘录，要求：
            1. 不是简单拼接，新特征要用"她本来就是/她一直都是"这种语气自然融入，
               读起来像是这个人本来的样子，不能有"最近养成了"这种暗示是新近习得的措辞。
            2. 如果新特征与已有描述的某部分有自然的关联（比如都涉及她对待感情的方式），
               尽量放在相邻位置，形成整体感，不要分散在不相关的两段。
            3. 整体不设硬性字数上限，但避免空洞重复，每句话都要有实际信息量。

            仅输出整合后的完整人设备忘录正文（不需要JSON包裹，直接输出整段文本）。
        """.trimIndent()

        val userPrompt = buildString {
            append("角色当前的人设备忘录：\n")
            append(currentSoulNote.ifBlank { "（暂无）" })
            append("\n\n需要并入的、已经稳定确立的专长特征：\n")
            append(promotedTraitDescription)
        }

        try {
            provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config = LLMConfig(
                    model = "",
                    maxTokens = 800,
                    temperature = SpecialtyEvolutionConfig.JUDGMENT_TEMPERATURE,
                    stream = false,
                ),
            ).trim()
        } catch (_: Exception) {
            // 整合失败时退而求其次：简单拼接，至少不丢失信息，
            // 用户后续可在角色编辑页手动润色（P5 Step3 已交付的编辑通路）
            buildString {
                append(currentSoulNote.trim())
                if (isNotEmpty()) append("\n\n")
                append(promotedTraitDescription.trim())
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ⑦ AI 自我提案（方案第8节，低频，仅建议不自动生效）
    // ─────────────────────────────────────────────────────────

    data class SuggestionResult(
        val hasSuggestion: Boolean,
        val suggestion: String,
        val reasoning: String,
    )

    /**
     * 基于蒸馏历史，生成一条可选的系统优化建议。
     * 调用频率远低于其他方法（见 SpecialtyEvolutionConfig.SUGGESTION_TRIGGER_MERGE_CYCLES），
     * 且只产出建议文本，不返回任何"可执行的参数变更指令"——
     * 即使未来想做得更结构化，也不应该让这个方法的返回值能被直接拿去改配置，
     * 这一步刻意保持"纯文本建议"的形式，强制中间必须经过人工阅读和判断。
     */
    suspend fun generateSystemSuggestion(
        domain: String,
        distillationHistorySummary: String,
    ): SuggestionResult = withContext(Dispatchers.IO) {
        val systemPrompt = """
            基于最近的蒸馏历史，如果你观察到某些模式（比如某类候选特征总是需要超过
            预期次数才能转正、或者总是在转正后又被判定为冲突），请提出一条具体的
            参数调整建议。如果没有观察到值得一提的模式，hasSuggestion 填 false。

            仅输出 JSON，不加任何其他文字：
            {"hasSuggestion":true或false,"suggestion":"具体建议内容，如有","reasoning":"为什么这样建议"}
        """.trimIndent()

        val userPrompt = "专长方向：$domain\n\n蒸馏历史：\n$distillationHistorySummary"

        try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config = LLMConfig(
                    model = "",
                    maxTokens = 300,
                    temperature = SpecialtyEvolutionConfig.JUDGMENT_TEMPERATURE,
                    stream = false,
                ),
            )
            val obj = JSONObject(extractJson(response))
            SuggestionResult(
                hasSuggestion = obj.optBoolean("hasSuggestion", false),
                suggestion = obj.optString("suggestion", "").take(300),
                reasoning = obj.optString("reasoning", "").take(200),
            )
        } catch (_: Exception) {
            SuggestionResult(false, "", "")
        }
    }
}
