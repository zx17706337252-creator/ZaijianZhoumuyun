package com.zaijian.zhoumuyun.domain

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/**
 * CompetitionEngine — 裁判与竞争机制核心引擎（执行方案第3节）
 *
 * 与 SpecialtyEvolutionEngine 平级，物理隔离，互不调用。
 * 本文件承载三项核心职责：
 *
 *   ① [judgeRound] 实名批量评审：一次 LLM 调用同时看全部参赛作品，
 *      裁判在 prompt 里看到真实角色名 + 每人自己的 styleNotes，
 *      输出每人的 score + issues + improvementDirection，外加整体排名。
 *
 *   ② [selfEvaluateEntry] 角色自评（盲评）：看不到他人产出和裁判分，
 *      只评自己这次的产出，Prompt 要求"诚实评估，不是自我营销"。
 *
 *   ③ [trainJudgeStandard] 裁判标准候选匹配：直接复用
 *      SpecialtyEvolutionEngine.matchCandidateObservation（纯字符串签名，
 *      无需重写，按执行方案第0.2点确认后直接调用）。
 *      裁判标准的阶段摘要并入 standardNotes，直接复用
 *      SpecialtyEvolutionEngine.mergeStageDigestsIntoProfile。
 *
 * JSON 解析规范与 SpecialtyEvolutionEngine 完全一致：
 *   - extractJson 防 Markdown 包裹
 *   - JSONObject.optXxx 兜底，解析失败返回安全默认值，不抛异常
 *
 * 实名评审设计说明（执行方案第3节、第11节）：
 *   裁判要公开点评每个人的问题和提升方向、给出排名，必须知道是谁的作品。
 *   匿名评审会让"指名道姓的具体改进建议"无法落实，不要做匿名化处理。
 *   selfEvaluateEntry 的盲评与实名无关——盲评是为了不被裁判意见或同伴
 *   产出带偏，不涉及身份遮蔽，两者是独立的设计决定。
 */
class CompetitionEngine(
    private val provider: LLMProvider,
    private val evolutionEngine: SpecialtyEvolutionEngine,
) {

    // ─────────────────────────────────────────────────────────
    //  内部工具：JSON 提取（与 SpecialtyEvolutionEngine 同款）
    // ─────────────────────────────────────────────────────────

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    // ─────────────────────────────────────────────────────────
    //  数据模型
    // ─────────────────────────────────────────────────────────

    /**
     * 一位参赛者的完整信息，由调用方（CompetitionRoundManager）组装。
     * styleNotes 让裁判的改进建议贴合该角色自己的养成方向，不是通用套话。
     */
    data class ContestantEntry(
        val characterId: Int,
        val characterName: String,
        /** 该角色已沉淀的风格说明书，给裁判参考，建议贴合此方向 */
        val styleNotes: String,
        /** 本轮参赛作品正文 */
        val content: String,
    )

    /** 裁判对单个参赛者的评判结果 */
    data class EntryVerdict(
        val characterId: Int,
        val characterName: String,
        /** 0-100 分 */
        val score: Int,
        /** 具体问题，指名道姓（如"顾澜这段的节奏断裂在第二段转折处..."） */
        val issues: String,
        /** 贴合该角色 styleNotes 方向的具体提升建议 */
        val improvementDirection: String,
        /** 本轮排名（第1名=1，最后=N） */
        val rank: Int,
    )

    /** judgeRound 的完整输出 */
    data class JudgeRoundResult(
        /** 每位参赛者的评判，顺序与输入 entries 一致 */
        val verdicts: List<EntryVerdict>,
        /** 裁判对本轮整体的一句综评（可选展示） */
        val overallComment: String,
        /** 是否解析成功（false 时 verdicts 为空，调用方应重试或记录错误） */
        val success: Boolean,
    )

    /** selfEvaluateEntry 的输出 */
    data class SelfEvalResult(
        val selfScore: Int,
        val selfReasoning: String,
        val success: Boolean,
    )

    // ─────────────────────────────────────────────────────────
    //  ① 实名批量评审
    // ─────────────────────────────────────────────────────────

    /**
     * 裁判一次性看全部参赛作品，实名点评，给出每人的评分+问题+改进方向和整体排名。
     *
     * @param domain          项目方向（如"短篇小说"）
     * @param judgeStandardNotes 裁判已沉淀的评判标准说明书（可为空，空=按自身审美评）
     * @param judgeName       裁判的角色名（在 prompt 里以第一人称/本名出现）
     * @param entries         全部参赛者及其作品，不做任何匿名化
     */
    suspend fun judgeRound(
        domain: String,
        judgeStandardNotes: String,
        judgeName: String,
        entries: List<ContestantEntry>,
    ): JudgeRoundResult = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) {
            return@withContext JudgeRoundResult(emptyList(), "", false)
        }

        val n = entries.size

        // ① 第一次批量评审：全部参赛者一起送审。
        val firstPass = judgeSubset(domain, judgeStandardNotes, judgeName, entries, n)
        val verdicts = firstPass.verdicts.toMutableList()
        val overallComment = firstPass.overallComment

        // 若模型漏掉了某些参赛者，先尝试对"漏掉的这部分"单独再调一次 LLM 补评，
        // 而不是直接拿默认值兜底填充——漏评往往是输出截断或个别名字未命中，
        // 缩小范围重问一次，命中率通常很高。
        var returnedIds = verdicts.map { it.characterId }.toSet()
        val missingAfterFirstPass = entries.filter { it.characterId !in returnedIds }

        if (missingAfterFirstPass.isNotEmpty()) {
            val secondPass = judgeSubset(
                domain, judgeStandardNotes, judgeName, missingAfterFirstPass, n,
            )
            verdicts.addAll(secondPass.verdicts)
        }

        // 只有在补评之后依然拿不到结果的参赛者，才退回默认值兜底
        // （避免后续空指针）。W4-6：success 判定基于补评后的真实命中数
        // validVerdictCount，不含这里的默认兜底条目。
        val validVerdictCount = verdicts.size
        returnedIds = verdicts.map { it.characterId }.toSet()
        entries.filter { it.characterId !in returnedIds }.forEach { e ->
            verdicts.add(
                EntryVerdict(
                    characterId = e.characterId,
                    characterName = e.characterName,
                    score = 50,
                    issues = "（裁判未给出点评）",
                    improvementDirection = "（裁判未给出建议）",
                    rank = verdicts.size + 1,
                )
            )
        }

        // W4-6 修复：漏评参赛者已经过二次补评兜底，质量有保障，
        // 不再要求"命中过半"这种过于保守的判定——只要有真实评审结果
        // （哪怕只覆盖了一部分，其余走了默认值），就算本轮评审成功，
        // 交由调用方正常推进；完全没有任何真实结果才算失败走重试。
        val success = validVerdictCount > 0

        JudgeRoundResult(
            verdicts = verdicts,
            overallComment = overallComment,
            success = success,
        )
    }

    /**
     * judgeRound 的内部子步骤：对一批 entries（可以是全部参赛者，也可以是
     * 补评时的"漏掉的那部分"）发起一次 LLM 调用并解析出 verdicts。
     * 不做默认值兜底、不做 success 判定——这两件事由调用方（judgeRound）
     * 在合并首次结果与补评结果之后统一处理。
     *
     * @param totalCount 用于 rank 取值范围和 maxTokens 估算的"整场比赛人数"，
     *                   补评时传入原始的 n（而不是本次 subset 的大小），
     *                   保证排名语义与首次评审一致。
     */
    private suspend fun judgeSubset(
        domain: String,
        judgeStandardNotes: String,
        judgeName: String,
        entries: List<ContestantEntry>,
        totalCount: Int,
    ): JudgeRoundResult {
        if (entries.isEmpty()) {
            return JudgeRoundResult(emptyList(), "", false)
        }

        val n = entries.size
        val participantNames = entries.joinToString("、") { it.characterName }

        val systemPrompt = buildString {
            append("你是裁判${judgeName}，正在评审一场「${domain}」方向的命题竞赛。\n\n")

            if (judgeStandardNotes.isNotBlank()) {
                append("你已沉淀的评判标准：\n${judgeStandardNotes}\n\n")
                append("请严格按照上述评判标准来打分和点评。\n\n")
            } else {
                append("你目前没有明文评判标准，按你对「${domain}」的审美和理解来评审。\n\n")
            }

            append("参赛者：${participantNames}（共${n}人）\n\n")
            append("评审要求：\n")
            append("1. 逐一点评每位参赛者，指名道姓，不做匿名化处理。\n")
            append("2. issues 字段：具体说明这位参赛者本次产出的问题，例如节奏、逻辑、语言等维度，要有针对性，不是套话。\n")
            append("3. improvementDirection 字段：结合该参赛者自己的风格说明书，给出贴合她的养成方向的具体提升建议——这是她下一步该往哪个方向练习的指引，不是泛泛而谈。\n")
            append("4. 给出本轮排名（1=最佳），平局时排名可以相同，但总体上应有区分度。\n")
            append("5. overallComment 字段：一句话整体总结本轮竞赛水准，可以体现你作为裁判的个人风格。\n\n")
            append("仅输出 JSON，不加任何其他文字：\n")
            append("{\"verdicts\":[{\"characterName\":\"参赛者名\",\"score\":0-100的整数,\"issues\":\"具体问题\",\"improvementDirection\":\"提升方向\",\"rank\":排名整数},...],\"overallComment\":\"整体总结\"}")
        }

        val userPrompt = buildString {
            entries.forEach { e ->
                append("【${e.characterName}的参赛作品】\n")
                if (e.styleNotes.isNotBlank()) {
                    append("${e.characterName}的风格说明书（供裁判参考，使改进建议贴合她的方向）：\n${e.styleNotes}\n\n")
                } else {
                    append("${e.characterName}的风格说明书：（暂无，仍在摸索阶段）\n\n")
                }
                append("作品正文：\n${e.content.take(1500)}\n\n")
                append("─────────────────────\n\n")
            }
        }

        // 修复 P1-7：固定 1500 token 不随参赛人数缩放。每人输出
        // score/issues(≤300字)/improvementDirection(≤300字)/rank 约 750 字≈500 token，
        // N≥3 时已逼近上限，N≥4 极易被截断，截断后 JSON 解析失败 → success=false →
        // 触发 runJudging 的失败回退（P0-2）。按人数动态放大，封顶避免单次请求过大。
        val judgeMaxTokens = (600 + 500 * n).coerceIn(1500, 6000)

        var rawResponseForLog: String? = null
        return try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config = LLMConfig(
                    model = "",
                    maxTokens = judgeMaxTokens,
                    temperature = SpecialtyEvolutionConfig.JUDGMENT_TEMPERATURE,
                    stream = false,
                ),
            )
            rawResponseForLog = response

            val obj = JSONObject(extractJson(response))
            val verdictsArray: JSONArray = obj.optJSONArray("verdicts") ?: JSONArray()
            val overallComment = obj.optString("overallComment", "").take(200)

            // M5 修复：构建 characterName → index 的查找表（而非 name → id），
            // 防止同名参赛者互相错配 characterId。
            // 优先按 JSON 数组顺序与 entries 顺序对齐（LLM 按输入顺序输出概率最高），
            // 退化时再用 name 反查（entries.firstOrNull）。
            val nameToEntry = entries.associateBy { it.characterName }

            val verdicts = mutableListOf<EntryVerdict>()
            for (i in 0 until verdictsArray.length()) {
                val v = verdictsArray.optJSONObject(i) ?: continue
                val name = v.optString("characterName", "")
                // 优先按下标对齐；同名时按下标的条目可能更准确
                val matchedEntry = entries.getOrNull(i)
                    ?.takeIf { it.characterName == name }
                    ?: nameToEntry[name]
                    ?: entries.getOrNull(i)
                val cid = matchedEntry?.characterId ?: continue
                verdicts.add(
                    EntryVerdict(
                        characterId = cid,
                        characterName = name,
                        score = v.optInt("score", 50).coerceIn(0, 100),
                        issues = v.optString("issues", "").take(300),
                        improvementDirection = v.optString("improvementDirection", "").take(300),
                        rank = v.optInt("rank", i + 1).coerceIn(1, totalCount),
                    )
                )
            }

            JudgeRoundResult(
                verdicts = verdicts,
                overallComment = overallComment,
                success = verdicts.isNotEmpty(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // B3审查序号4修复：原为 catch (_: Throwable) 无日志，LLM 输出格式系统性
            // 偏差时所有评分静默失败且无法排查。补日志记录原始响应（截断避免日志过长）
            // 和异常堆栈。rawResponseForLog 为 null 说明连 LLM 请求本身都失败了
            // （chatSyncWithRetry 抛出），非 null 则说明是 JSON 解析/字段提取阶段出错。
            ZLog.e("CompetitionEngine", "judgeSubset解析失败，judgeName=$judgeName, domain=$domain, rawResponse=${rawResponseForLog?.take(500)}", e)
            JudgeRoundResult(emptyList(), "", false)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ② 角色自评（盲评）
    // ─────────────────────────────────────────────────────────

    /**
     * 角色对自己本轮参赛作品的盲评：看不到其他参赛者的产出和裁判分。
     * Prompt 明确要求"诚实评估，不是自我营销"——这是防止模型给自己打高分的护栏。
     *
     * @param characterName  评估者的角色名
     * @param domain         项目方向
     * @param ownContent     该角色本轮的参赛作品
     * @param ownStyleNotes  该角色已沉淀的风格说明书（作为自我对照标准）
     */
    suspend fun selfEvaluateEntry(
        characterName: String,
        domain: String,
        ownContent: String,
        ownStyleNotes: String,
    ): SelfEvalResult = withContext(Dispatchers.IO) {
        val systemPrompt = buildString {
            append("你是${characterName}，正在对自己在「${domain}」方向的这次参赛作品进行盲评。\n\n")
            append("盲评规则：你看不到其他参赛者的作品，也看不到裁判的评分——这是你对自己这次产出的独立判断。\n\n")
            if (ownStyleNotes.isNotBlank()) {
                append("你已沉淀的风格说明书（用作自我对照的基准）：\n${ownStyleNotes}\n\n")
            }
            append("评估要求：\n")
            append("1. 诚实评估，不是自我营销。如果这次产出有明显不足，如实说出来。\n")
            append("2. 对照你自己的风格说明书（如有），判断这次是否达到了你应有的水准。\n")
            append("3. score 给出 0-100 的整数，代表你对这次产出的真实自我评价。\n")
            append("4. reasoning 字段：说明打这个分的具体理由，指出做得好的地方和不足之处。\n\n")
            append("仅输出 JSON，不加任何其他文字：\n")
            append("{\"score\":0-100的整数,\"reasoning\":\"评估理由\"}")
        }

        val userPrompt = "我这次的参赛作品：\n${ownContent.take(1500)}"

        var rawResponseForLog: String? = null
        try {
            val response = provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config = LLMConfig(
                    model = "",
                    maxTokens = 400,
                    temperature = SpecialtyEvolutionConfig.JUDGMENT_TEMPERATURE,
                    stream = false,
                ),
            )
            rawResponseForLog = response
            val obj = JSONObject(extractJson(response))
            SelfEvalResult(
                selfScore = obj.optInt("score", 50).coerceIn(0, 100),
                selfReasoning = obj.optString("reasoning", "").take(400),
                success = true,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // B3审查序号5修复：原为 catch (_: Throwable) 无日志，默认50分的
            // SelfEvalResult 若被不检查 success 字段的调用方使用会静默污染数据。
            // 补日志记录原始响应，便于确认是格式偏差还是请求本身失败。
            ZLog.e("CompetitionEngine", "selfEvaluateEntry解析失败，characterName=$characterName, domain=$domain, rawResponse=${rawResponseForLog?.take(500)}", e)
            SelfEvalResult(50, "（自评解析失败）", false)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ③ 裁判标准训练：候选匹配 + 标准并入
    //     直接复用 SpecialtyEvolutionEngine 的纯字符串方法，不重写
    // ─────────────────────────────────────────────────────────

    /**
     * 判断新的裁判标准观察是否与已有候选池中的某条在语义上等同。
     *
     * 直接委托给 SpecialtyEvolutionEngine.matchCandidateObservation。
     * 执行方案第0.2点确认该方法签名为纯字符串输入输出，无需重写。
     *
     * @param newCorrection     本次从裁判反馈中提炼的新标准观察
     * @param existingCorrections 候选修正池中已有的条目列表
     * @return 匹配到的已有候选原文（语义相同时合并计数），或 null（全新条目）
     */
    suspend fun matchJudgeCorrectionCandidate(
        newCorrection: String,
        existingCorrections: List<String>,
    ): String? = evolutionEngine.matchCandidateObservation(
        newTrait = newCorrection,
        existingTraits = existingCorrections,
    )

    /**
     * 将若干裁判标准的阶段摘要并入现有 standardNotes，整段重写。
     *
     * 直接委托给 SpecialtyEvolutionEngine.mergeStageDigestsIntoProfile。
     * standardNotes 与 styleNotes 的语义不同（一个是评判标准，一个是创作风格），
     * 但"将新积累的摘要整合进已有说明书"这个操作结构完全相同，直接复用即可。
     *
     * @param currentStandardNotes 裁判当前的评判标准说明书
     * @param newObservationDigests 待并入的新标准摘要列表
     * @return 更新后的 standardNotes（及冲突信息）
     */
    suspend fun mergeJudgeStandardObservations(
        currentStandardNotes: String,
        newObservationDigests: List<String>,
    ): SpecialtyEvolutionEngine.MergeResult = evolutionEngine.mergeStageDigestsIntoProfile(
        currentStyleNotes = currentStandardNotes,
        stageDigests = newObservationDigests,
    )
}
