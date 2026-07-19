package com.zaijian.zhoumuyun.domain.scheduler

import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.domain.RelationshipEngine
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────
//  TurnScheduler — 圆桌序贯感知调度器
//
//  Phase 14 后半升级：
//  ① topicBonus Phase B：从 CharacterConfig.identityConfig.coreBeliefs 动态提取关键词
//  ② scheduleHeuristic 容错：activeBots 为空时直接返回 emptyList()
//  ③ scheduleWithAI 系统提示优化：注入 coreBeliefs 摘要让 LLM 感知角色专长
//
//  (Phase 14 前半已完成)
//  ① scheduleWithAI() 实际实现：LLM 判断发言顺序和意图
//  ② scheduleHeuristic() 接入真实 RelationshipEntity.jealousy / tension
//  ③ scheduleAuto() 自动选择模式（短消息=启发式，长消息=AI调度）
// ─────────────────────────────────────────────────────────────

/**
 * 单个 Bot 本轮的发言计划。
 */
data class SpeakPlan(
    val characterId: Int,
    val speakOrder: Int,
    val initialIntent: SpeakIntent = SpeakIntent.RESPOND_USER,
    val speakScore: Float = 50f,
)

/**
 * Bot 的发言倾向。
 */
enum class SpeakIntent {
    RESPOND_USER,       // 直接回应用户
    RESPOND_OTHER_BOT,  // 准备回应前一个 Bot 的观点
    INFLUENCED_BY_BOT,  // 受前序 Bot 影响后用自己方式回应用户
}

/**
 * 调度上下文。
 *
 * [mentionedIds]   本条用户消息中被 @提及 的角色 ID 集合（空集 = 无@）。
 * [isFullMention]  是否命中"全体@"判定（由 RoundtableViewModel.parseAtMentions 计算后传入）。
 *                  全体@ 触发条件：mentionedIds == activeMembers 全部 id，
 *                  或消息含"@全部"/"@所有人"/"@大家"等关键词，
 *                  或轻量意图判定为"布置任务+要求讨论"。
 */
data class ScheduleContext(
    val activeBots: List<CharacterConfig>,
    val userMessage: String,
    val lastRoundSpeakers: Set<Int> = emptySet(),
    /** key = "fromId_toId"，fromId/toId 均为角色 ID 字符串 */
    val relationships: Map<String, RelationshipEntity> = emptyMap(),
    /** 各 Bot 的当前情绪值 (-1.0 ~ 1.0)，key=characterId */
    val moodMap: Map<Int, Float> = emptyMap(),
    // ── 待办6 Step 1 新增 ────────────────────────────────────
    /** 用户消息中被 @ 的角色 ID 集合。空集 = 无@ 分支。*/
    val mentionedIds: Set<Int> = emptySet(),
    /**
     * 是否命中全体@ 判定。
     * true  → 全员发言分支（scheduleFullMention）。
     * false + mentionedIds 非空 → 部分@ 分支（schedulePartialMention）。
     * false + mentionedIds 空   → 无@ 分支（scheduleNoMention）。
     */
    val isFullMention: Boolean = false,
)

object TurnScheduler {

    private const val BASE_SCORE = 50f
    private const val AI_SCHEDULE_THRESHOLD = 30    // 消息字数超过此值 → 优先 AI 调度

    /**
     * 根据在场人数动态计算本轮发言上限，并加入随机浮动使每轮回应数自然变化。
     *
     * 档位：
     *   1~2人   → 1~2人
     *   3~4人   → 2~3人
     *   5~7人   → 3~4人
     *   8~12人  → 4~7人
     *   13~17人 → 6~10人
     *   18~21人 → 7~12人
     */
    private fun dynamicMaxSpeakers(total: Int): Int {
        val (lo, hi) = when {
            total <= 2  -> 1 to 2
            total <= 4  -> 2 to 3
            total <= 7  -> 3 to 4
            total <= 12 -> 4 to 7
            total <= 17 -> 6 to 10
            else        -> 7 to 12
        }
        return (lo..hi).random()
    }

    /**
     * 部分@分支中，未被@角色的插话概率上限（避免人人都插嘴）。
     */
    private const val PARTIAL_MENTION_INTERJECT_CAP = 0.5f

    // ──────────────────────────────────────────────────────────
    //  三分支总入口：scheduleAuto
    // ──────────────────────────────────────────────────────────

    /**
     * 自动调度（三分支）：
     *
     * 1. **全体@**（isFullMention = true）→ 在场全员按分排序发言，不截断。
     * 2. **部分@**（mentionedIds 非空 && !isFullMention）→ 被@的100%发言 + 未被@的按概率插话。
     * 3. **无@**（mentionedIds 空 && !isFullMention）→ 沿用现有启发式/AI调度逻辑。
     *
     * @param apiCall 调用 LLM 的挂起函数（由 RoundtableViewModel 注入，无@分支可能用到）
     */
    suspend fun scheduleAuto(
        ctx: ScheduleContext,
        apiCall: (suspend (prompt: String) -> String)? = null,
        forceAI: Boolean = false, // S2问题4修复：AI_ONLY 模式跳过字数阈值
    ): List<SpeakPlan> = when {
        ctx.isFullMention                        -> scheduleFullMention(ctx)
        ctx.mentionedIds.isNotEmpty()            -> schedulePartialMention(ctx)
        else                                     -> scheduleNoMention(ctx, apiCall, forceAI)
    }

    // ──────────────────────────────────────────────────────────
    //  分支一：全体@ —— 全员发言，不截断
    // ──────────────────────────────────────────────────────────

    /**
     * 全体@ 分支：在场所有 activeBots 均发言，不做人数截断。
     *
     * 顺序：复用启发式打分逻辑降序排列（分数越高越先发言），
     * intent 链路与 scheduleHeuristic 一致（首位 RESPOND_USER，
     * 后续根据 tension/jealousy 决定 RESPOND_OTHER_BOT / INFLUENCED_BY_BOT）。
     *
     * 注：此分支返回的列表会由 RoundtableViewModel 触发"自动连续讨论"状态机
     * （Step 3 实现），TurnScheduler 本身只负责本轮排序，不感知多轮循环。
     */
    fun scheduleFullMention(ctx: ScheduleContext): List<SpeakPlan> {
        if (ctx.activeBots.isEmpty()) return emptyList()

        val scores = computeScores(ctx)
        val sorted = scores.entries.sortedByDescending { it.value }
        // 全体@ 不截断：全员按分排序
        return sorted.mapIndexed { index, entry ->
            val intent = resolveIntent(index, entry.key, sorted, ctx)
            SpeakPlan(
                characterId   = entry.key,
                speakOrder    = index,
                initialIntent = intent,
                speakScore    = entry.value,
            )
        }
    }

    // ──────────────────────────────────────────────────────────
    //  分支二：部分@ —— 被@的100%发言 + 未被@的按概率插话
    // ──────────────────────────────────────────────────────────

    /**
     * 部分@ 分支。
     *
     * 被@角色：
     *   - 100% 发言，intent = RESPOND_USER（排在最前，按打分降序）。
     *
     * 未被@角色：
     *   - 按"插话意愿"概率参与：probability = clamp(score / 150f, 0f, 0.5f)
     *   - 命中则 intent = INFLUENCED_BY_BOT，排在被@角色之后（按分数降序）。
     *
     * 最终顺序：被@角色（按分降序）→ 命中插话角色（按分降序）。
     */
    fun schedulePartialMention(ctx: ScheduleContext): List<SpeakPlan> {
        if (ctx.activeBots.isEmpty()) return emptyList()

        val scores = computeScores(ctx)

        // 被@的角色（必须在 activeBots 中）
        val mentioned = ctx.activeBots
            .filter { it.id in ctx.mentionedIds }
            .sortedByDescending { scores[it.id] ?: 0f }

        // 插话配额 = 本轮总上限 - 被@人数（至少0）
        val totalCap = dynamicMaxSpeakers(ctx.activeBots.size)
        val interjectCap = (totalCap - mentioned.size).coerceAtLeast(0)

        // 未被@的角色 —— 按概率插话，命中后按分数降序取前 interjectCap 个
        val interjecters = ctx.activeBots
            .filter { it.id !in ctx.mentionedIds }
            .mapNotNull { bot ->
                val score = scores[bot.id] ?: 0f
                val prob = (score / 150f).coerceIn(0f, PARTIAL_MENTION_INTERJECT_CAP)
                if (kotlin.random.Random.nextDouble() < prob) bot else null
            }
            .sortedByDescending { scores[it.id] ?: 0f }
            .take(interjectCap)

        val allSpeakers = mentioned + interjecters
        var orderIndex = 0

        return allSpeakers.map { bot ->
            val intent = when {
                bot.id in ctx.mentionedIds -> SpeakIntent.RESPOND_USER
                else                       -> SpeakIntent.INFLUENCED_BY_BOT
            }
            SpeakPlan(
                characterId   = bot.id,
                speakOrder    = orderIndex++,
                initialIntent = intent,
                speakScore    = scores[bot.id] ?: BASE_SCORE,
            )
        }
    }

    // ──────────────────────────────────────────────────────────
    //  分支三：无@ —— 沿用现有启发式/AI调度逻辑
    // ──────────────────────────────────────────────────────────

    /**
     * 无@ 分支：根据消息长度选择调度策略。
     *
     * - 消息 ≤ [AI_SCHEDULE_THRESHOLD] 字 → 启发式（无 API 消耗）
     * - 消息 > [AI_SCHEDULE_THRESHOLD] 字 → AI 调度（结果更自然），失败时 fallback 启发式
     */
    private suspend fun scheduleNoMention(
        ctx: ScheduleContext,
        apiCall: (suspend (prompt: String) -> String)?,
        forceAI: Boolean = false, // S2问题4修复：AI_ONLY 模式跳过字数阈值
    ): List<SpeakPlan> {
        return if (forceAI && apiCall != null) {
            scheduleWithAI(ctx, apiCall)
        } else if (ctx.userMessage.length > AI_SCHEDULE_THRESHOLD && apiCall != null) {
            scheduleWithAI(ctx, apiCall)
        } else {
            scheduleHeuristic(ctx)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  启发式调度（Phase 14 升级：接入真实 jealousy/tension）
    // ──────────────────────────────────────────────────────────

    /**
     * 启发式调度：基于规则打分，用于无@分支。
     *
     * 评分因素：
     * - 情绪加成：积极+20，愤怒+25，冷淡-15
     * - 性格修正：内向-20，外向+15，话极少-25
     * - 嫉妒机制（Phase 14 真实数据）：jealousy 越高 → 发言意愿越强
     * - 紧张机制（Phase 14 真实数据）：tension 越高 → 反驳倾向越强
     * - 刚说过惩罚：-10
     * - 话题触发：+15（Phase B：动态从 identityConfig.coreBeliefs 读取）
     */
    fun scheduleHeuristic(ctx: ScheduleContext): List<SpeakPlan> {
        if (ctx.activeBots.isEmpty()) return emptyList()

        val scores = computeScores(ctx)
        val sorted = scores.entries.sortedByDescending { it.value }
        val maxSpeakers = dynamicMaxSpeakers(ctx.activeBots.size)
        val speakers = sorted.take(maxSpeakers).filter { it.value > 0f }

        return speakers.mapIndexed { index, entry ->
            val intent = resolveIntent(index, entry.key, speakers, ctx)
            SpeakPlan(
                characterId   = entry.key,
                speakOrder    = index,
                initialIntent = intent,
                speakScore    = entry.value,
            )
        }
    }

    // ──────────────────────────────────────────────────────────
    //  共享打分逻辑（被三个分支复用）
    // ──────────────────────────────────────────────────────────

    /**
     * 为 activeBots 中每个角色计算发言分数，返回 Map<characterId, score>。
     *
     * 三个分支均调用此函数，保证打分标准统一：
     * - 全体@：用来排序（不截断）
     * - 部分@：被@的用来排序；未被@的用来计算插话概率
     * - 无@：用来排序并按 dynamicMaxSpeakers 截断
     */
    private fun computeScores(ctx: ScheduleContext): Map<Int, Float> {
        return ctx.activeBots.associate { bot ->
            var score = BASE_SCORE

            // 1. 情绪加成
            // FIX-1: 愤怒分支（mood < -0.8f）必须排在冷淡分支（mood < -0.5f）之前，
            //        否则所有 mood < -0.5 的情况都被冷淡分支提前截断，愤怒+25 永远不可达。
            val mood = ctx.moodMap[bot.id] ?: 0f
            score += when {
                mood > 0.5f  ->  20f   // 积极
                mood < -0.8f ->  25f   // 愤怒（负值但激活）—— 必须先于 < -0.5 判断
                mood < -0.5f -> -15f   // 冷淡
                else         ->   0f
            }

            // 2. 性格修正（基于 identityConfig.speechStyle）
            score += personalityAdjust(bot)

            // 3. 嫉妒/紧张机制（Phase 14：接入真实 RelationshipEntity.jealousy）
            // 大群组时关系对数量 = N-1，原始累加会让分数随人数线性膨胀。
            // 改为对所有关系对求均值再加权，保证量级与其他因子（±25）对齐。
            val relPeers = ctx.activeBots.filter { it.id != bot.id }
            if (relPeers.isNotEmpty()) {
                var relSum = 0f
                var relCount = 0
                relPeers.forEach { other ->
                    // P1-13-7 修复：原来直接拼 "${other.id}_${bot.id}"，未做归一化。
                    // 数据库里 inter-character 关系按字典序 min→max 存成一条记录
                    // （RelationshipEngine.getOrCreateInterCharacter），getInterCharacterMatrix
                    // 也已用同一规则归一化 key（relKey）。这里若不归一化，当
                    // other.id 字典序大于 bot.id 时拼出的 key 与库内顺序相反，
                    // Map 里查不到记录，rel 恒为 null——相当于一半的角色对的
                    // 嫉妒/紧张加分悄悄失效，且不会报错，很难发现。
                    val rel = ctx.relationships[RelationshipEngine.relKey(other.id, bot.id)]
                    if (rel != null) {
                        relSum += rel.jealousy * 0.10f + rel.tension * 0.08f
                        relCount++
                    }
                }
                if (relCount > 0) score += (relSum / relCount).coerceIn(-20f, 25f)
            }

            // 4. 刚说过惩罚（按群组规模动态加重：人越多越需要把机会让给沉默者）
            if (bot.id in ctx.lastRoundSpeakers) {
                val penalty = when {
                    ctx.activeBots.size <= 4  -> -10f
                    ctx.activeBots.size <= 8  -> -18f
                    ctx.activeBots.size <= 14 -> -25f
                    else                      -> -35f
                }
                score += penalty
            }

            // 5. 话题触发（Phase B：动态提取）
            score += topicBonus(bot, ctx.userMessage)

            bot.id to score
        }
    }

    /**
     * 根据发言顺序和关系数据解析 SpeakIntent。
     *
     * [index]     当前角色在有序列表中的位置（0 = 首位）
     * [botId]     当前角色 ID
     * [sortedList] 完整有序列表（用于取前一发言者 ID）
     */
    private fun resolveIntent(
        index: Int,
        botId: Int,
        sortedList: List<Map.Entry<Int, Float>>,
        ctx: ScheduleContext,
    ): SpeakIntent {
        if (index == 0) return SpeakIntent.RESPOND_USER
        val prevSpeakerId = sortedList[index - 1].key
        // Fix-13-7：使用 RelationshipEngine.relKey 归一化键，与存储侧一致（按字典序排小值在前）。
        // 原直接拼 "${prevSpeakerId}_${botId}"，当 prevSpeakerId > botId 时与存储键方向相反，查不到关系。
        val rel = ctx.relationships[RelationshipEngine.relKey(prevSpeakerId, botId)]
        return when {
            rel != null && rel.tension > 60  -> SpeakIntent.RESPOND_OTHER_BOT
            rel != null && rel.jealousy > 50 -> SpeakIntent.RESPOND_OTHER_BOT
            kotlin.random.Random.nextDouble() < 0.35 -> SpeakIntent.INFLUENCED_BY_BOT
            else                             -> SpeakIntent.RESPOND_USER
        }
    }

    // ──────────────────────────────────────────────────────────
    //  AI 调度（Phase 14 实际实现）
    // ──────────────────────────────────────────────────────────

    /**
     * AI 调度：将调度决策交给 LLM。
     *
     * Prompt 格式（Phase 14 后半升级：注入 coreBeliefs 让 LLM 感知角色专长）：
     * - 各角色名、性格摘要（persona 前 30 字）、coreBeliefs 前 2 条
     * - 上轮发言者（避免重复）
     * - 要求返回纯 JSON：{ "speakers": [ {"id": N, "intent": "..."}, ... ] }
     *
     * 解析失败自动 fallback 到启发式。
     */
    private suspend fun scheduleWithAI(
        ctx: ScheduleContext,
        apiCall: suspend (prompt: String) -> String,
    ): List<SpeakPlan> = try {
        val maxSpeakers = dynamicMaxSpeakers(ctx.activeBots.size)
        val prompt = buildAISchedulePrompt(ctx, maxSpeakers)
        val raw = apiCall(prompt)
        parseAIScheduleResponse(raw, ctx, maxSpeakers)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e  // S2审查修复：必须 rethrow，保证协程取消信号正常向上传播，不被吞掉
    } catch (_: Exception) {
        scheduleHeuristic(ctx)
    }

    private fun buildAISchedulePrompt(ctx: ScheduleContext, maxSpeakers: Int): String {
        val botsDesc = ctx.activeBots.joinToString("\n") { bot ->
            val persona = bot.identityConfig.persona.take(40).ifBlank { "无描述" }
            val beliefs = bot.identityConfig.coreBeliefs.take(2)
                .joinToString("，").ifBlank { "" }
            val beliefsLine = if (beliefs.isNotBlank()) "，核心：$beliefs" else ""
            val wasLastSpeaker = if (bot.id in ctx.lastRoundSpeakers) "（上轮已发言）" else ""
            "- id=${bot.id} 名字=${bot.name} 性格=${persona}${beliefsLine}${wasLastSpeaker}"
        }
        return """
你是圆桌对话调度器。根据以下信息决定本轮哪些角色发言（最多${maxSpeakers}个）及顺序。

用户消息：「${ctx.userMessage}」

参与角色：
$botsDesc

规则：
1. 最多选${maxSpeakers}人，按发言顺序排列
2. 上轮已发言者除非必要否则不选
3. 选与话题最相关或情绪最适合的角色
4. intent 只能是 RESPOND_USER、RESPOND_OTHER_BOT、INFLUENCED_BY_BOT 之一

只返回 JSON，不要其他文字：
{"speakers":[{"id":角色id,"intent":"..."},{"id":角色id,"intent":"..."}]}
""".trimIndent()
    }

    // FIX-2: parseAIScheduleResponse 内部的 JSONObject / getJSONArray 可能抛
    //        JSONException，而调用方 scheduleWithAI 的 try-catch 会捕获并 fallback。
    //        但为了让 fallback 路径更显式、避免未来提取此函数时丢失保护，
    //        在函数内部也加一层 try-catch，解析失败直接返回 emptyList()，
    //        由 scheduleWithAI 的 catch 或 ifEmpty 兜底到启发式。
    private fun parseAIScheduleResponse(
        raw: String,
        ctx: ScheduleContext,
        maxSpeakers: Int,
    ): List<SpeakPlan> {
        val validBotIds = ctx.activeBots.map { it.id }.toSet()
        val cleaned = raw
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        val arr: JSONArray = try {
            JSONObject(cleaned).getJSONArray("speakers")
        } catch (_: Exception) {
            // JSON 结构不合法 → 直接 fallback 启发式
            return scheduleHeuristic(ctx)
        }

        val plans = mutableListOf<SpeakPlan>()
        for (i in 0 until arr.length()) {
            if (plans.size >= maxSpeakers) break
            val item = arr.getJSONObject(i)
            val id = when {
                item.has("id") -> {
                    val raw = item.get("id")
                    when (raw) {
                        is Int    -> raw
                        is String -> raw.toIntOrNull()
                        else      -> null
                    }
                }
                else -> null
            } ?: continue
            if (id !in validBotIds) continue

            val intentStr = item.optString("intent", "RESPOND_USER")
            val intent = try {
                SpeakIntent.valueOf(intentStr)
            } catch (_: IllegalArgumentException) {
                SpeakIntent.RESPOND_USER
            }

            plans.add(SpeakPlan(
                characterId   = id,
                speakOrder    = plans.size,
                initialIntent = intent,
                speakScore    = 75f,  // AI 调度固定高分（表示 LLM 推荐）
            ))
        }

        return plans.ifEmpty { scheduleHeuristic(ctx) }
    }

    // ──────────────────────────────────────────────────────────
    //  辅助：话题触发加成（Phase B：动态从 identityConfig.coreBeliefs 提取）
    // ──────────────────────────────────────────────────────────

    /**
     * Phase B 实现：
     * 1. 优先从 identityConfig.coreBeliefs 提取关键词（每条信念取前6字）
     * 2. persona 也参与匹配（取前100字分词）
     * 3. 若 identityConfig 无内容，fallback 到静态关键词表
     */
    private fun topicBonus(bot: CharacterConfig, message: String): Float {
        // 1. 从 identityConfig.coreBeliefs 动态提取关键词
        val dynamicKeywords = bot.identityConfig.coreBeliefs
            .flatMap { belief ->
                // 取每条信念的关键字段：按常见分隔符分割，取有意义的词
                belief.split("、", "，", ",", "；", ";", " ")
                    .map { it.trim() }
                    .filter { it.length in 2..8 }
            }

        // 2. 从 persona 中提取词（粗略：每2-4个汉字为一个候选词）
        val personaKeywords = extractPersonaKeywords(bot.identityConfig.persona)

        val allDynamic = (dynamicKeywords + personaKeywords).distinct()

        if (allDynamic.isNotEmpty()) {
            return if (allDynamic.any { it in message }) 15f else 0f
        }

        // Fallback：静态关键词表（identityConfig 尚未填充时使用）
        val staticKeywords = mapOf(
            "蒂法"  to listOf("情感", "陪伴", "感受", "心情", "关心", "倾诉"),
            "露娜"  to listOf("分析", "逻辑", "推理", "方案", "策略", "计划"),
            "伊芙"  to listOf("创意", "故事", "写作", "表达", "艺术", "设计"),
            "宥熙"  to listOf("架构", "系统", "构建", "优化", "工程", "代码"),
            "索菲娅" to listOf("哲学", "思考", "意义", "存在", "探索"),
            "顾澜"  to listOf("代码", "审查", "产品", "方案", "逻辑", "功能"),
            "明媚"  to listOf("问题", "错误", "批评", "漏洞", "排查"),
            "莫婉凝" to listOf("温暖", "鼓励", "支持", "理解", "陪伴"),
            "江凡"  to listOf("秘密", "过去", "记忆", "时间", "沉默"),
        )
        val keywords = staticKeywords[bot.name]
        if (keywords != null) {
            return if (keywords.any { it in message }) 15f else 0f
        }

        // 审查报告问题34修复：静态关键词表仅覆盖 9 个预设角色名，女儿的名字
        // （用户自定义生成）不可能预先写入这张表。正常情况下女儿的 persona/
        // coreBeliefs 由 D4 生成器产出真实内容，上面的 allDynamic 分支已能
        // 命中；只有当两个动态来源双双异常为空（数据不完整的边缘情况）才会
        // 落到这里——此时女儿没有任何静态表兜底可用，与 1-9 号角色"查不到
        // 关键词表示没有这个角色对应词条"的语义不同：1-9 号角色即使这句话
        // 不命中静态词表，好歹在表里"有名有姓"；女儿在表里完全不存在，
        // 会被结构性地排到圆桌发言序列末尾。给一个中性保底分（非0、低于
        // 关键词命中的15f），避免这种数据边缘情况下的断崖式垫底，同时不
        // 假装"命中了关键词"。
        if (bot.id >= 1000) return TOPIC_BONUS_DAUGHTER_FALLBACK

        return 0f
    }

    /** 问题34修复：女儿角色双重动态关键词来源均为空时的中性保底分。
     *  TurnScheduler 本身是 object（单例），object 内部不能再嵌套
     *  companion object（仅 class/interface 可用），故直接声明为
     *  object 作用域内的 private const val。 */
    private const val TOPIC_BONUS_DAUGHTER_FALLBACK = 5f

    /**
     * 从 persona 文本粗略提取关键词。
     * 策略：取中文词频较高的 2-4 字词（简单滑窗，不做完整分词）
     */
    private fun extractPersonaKeywords(persona: String): List<String> {
        if (persona.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val text = persona.take(120)
        // 滑窗：取 2~4 字中文片段
        for (len in 2..4) {
            var i = 0
            while (i + len <= text.length) {
                val token = text.substring(i, i + len)
                if (token.all { it in '\u4e00'..'\u9fff' }) {
                    result.add(token)
                }
                i += len
            }
        }
        return result.distinct().take(20)
    }

    // ──────────────────────────────────────────────────────────
    //  辅助：性格修正（粗粒度，后续可改成 identityConfig.speechStyle）
    // ──────────────────────────────────────────────────────────

    private fun personalityAdjust(bot: CharacterConfig): Float {
        // 从 speechStyle 中检测性格关键词
        val style = bot.identityConfig.speechStyle.lowercase()
        return when {
            "内向" in style || "安静" in style || "寡言" in style -> -20f
            "外向" in style || "活泼" in style || "热情" in style ->  15f
            "冷淡" in style || "疏离" in style                   -> -15f
            "直接" in style || "果断" in style                   ->  10f
            else -> 0f  // 无明确性格描述，不修正
        }
    }
}
