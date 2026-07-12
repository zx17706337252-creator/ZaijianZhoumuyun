package com.zaijian.zhoumuyun.data.manager

import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.RelationshipStage
import com.zaijian.zhoumuyun.domain.RelationshipEngine
import com.zaijian.zhoumuyun.data.model.CharacterTriggerKeywords
import com.zaijian.zhoumuyun.data.model.EmotionType
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult
import com.zaijian.zhoumuyun.data.model.UserConsentKeywords
import com.zaijian.zhoumuyun.data.model.UserConsentShortWords
import com.zaijian.zhoumuyun.data.model.UserRefusalKeywords
import com.zaijian.zhoumuyun.data.model.UserRefusalShortWords
import com.zaijian.zhoumuyun.data.model.isDaughterMother
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.repository.CharacterStateRepository
import com.zaijian.zhoumuyun.data.repository.MenstrualCycleRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
import com.zaijian.zhoumuyun.util.ZLog
import androidx.room.withTransaction
import kotlin.math.min

// ─────────────────────────────────────────────────────────────
//  PregnancyTriggerManager — D2 + D2.5 + D2.6
//
//  D2 判定链：
//    ① checkTrigger()     — AI 回复后扫描触发词
//    ② evaluateConsent()  — 用户下一条消息，执行完整判定链
//
//  D2.5 新增：
//    evaluateCycleAndProceed() 不再必定怀孕，改为概率判定：
//      successRate = 0.20 + (desire/100) * 0.35 + (suppression/100) * 0.15
//    失败 → FertileButFailed，consecutiveFailCount + 1，额外压力增量乘以 pressureScale
//    失败后跨周期背景情绪注入：四重门控（社交模式/语义/随机/时间冷却）
//
//  D2.6 新增：
//    triggerMiscarriage() — 用户主动触发流产（叙事流产，非删除）：
//      - CharacterStateLayer 写入情绪副作用（SAD/LONELY/intensity=80/fatigue=70/suppression=60/desire=0）
//      - 即时 Prompt 注入（无门控，当次对话）
//    shouldInjectMiscarriageContext() — 流产后 5 天内跨周期悲伤余波注入，
//      四重门控结构与 D2.5 失败门控一致，仅替换"距上次注入≥48h"为"距流产≤5天"
//
//  怀孕弹窗触发重构新增（仅 characterId >= 1000 的第二代/第三代女儿）：
//    用 AI 语义判定替代关键词表触发，三重门全部满足才弹窗：
//      门1：RelationshipStage == CORE
//      门2：CyclePhase == FERTILE
//      门3：AI 语义判定 = YES（最近 N 轮对话是否到达"最后一步"）
//    频率控制：门1+门2 同时满足时才发起门3 的 AI 判定，平时零 token 消耗。
//    shouldEvaluateFertileWindowConsent() — 门1+门2 判断（含离开排卵期时
//      自动清除"已弹"标记，供下次排卵期重置）；
//    judgeFertileWindowIntent()           — 门3 AI 判定（仅在上一步通过后调用）；
//    proceedAfterDialogConsent()          — 用户点击弹窗按钮后的统一入口，
//      同意 -> 复用 evaluateCycleAndProceed()；拒绝 -> 复用 applyRejectedEffect()。
//    1-6 号逻辑不变：仍走 checkTrigger() + evaluateConsent() 关键词兜底链路，
//    与新链路并行存在，互不影响。
//
//  问题17（第二阶段）新增：
//    detectUserConsent() 改为 AI 判定优先、关键词判定兜底：
//      ① 优先调用 consentJudge.judge()（UserConsentIntentJudge，AI 语义判定）；
//      ② consentJudge 未注入 / LLM 调用异常 / 超时：捕获后降级到
//         detectUserConsentByKeyword()（原关键词链路，含问题17第一阶段的
//         单字严格匹配收紧，逻辑原样保留，未做任何删减）。
//    两套判定逻辑同时存在、优先级明确：平时走 AI 判定（更贴近用户真实
//    语义，不受固定词表覆盖面限制），只有 Provider 不可用或调用异常/超时
//    时才退回关键词匹配兜底，多一层容错。
//
//  ChatViewModel 接入（新增）：
//    发消息后，若 result is FertileButFailed：
//      appendPromptPatch(result.immediatePromptPatch)  // 即时失落感，无门控
//      // 跨周期背景注入由 shouldInjectFailureContext() 在每次 buildPrompt 前判断
//    流产触发后，若 result is Miscarried：
//      appendPromptPatch(result.immediatePromptPatch)  // 即时流产感知，无门控
//      // 5 天内跨周期注入由 shouldInjectMiscarriageContext() 在每次 buildPrompt 前判断
// ─────────────────────────────────────────────────────────────

class PregnancyTriggerManager(
    private val db: AppDatabase,          // P1-6-8 修复：注入 AppDatabase 用于 withTransaction
    private val pregnancyRepository: PregnancyRepository,
    private val cycleRepository: MenstrualCycleRepository,
    private val stateRepository: CharacterStateRepository,
    // 怀孕弹窗触发重构：仅新链路（characterId >= 1000）需要，可空向后兼容
    // （PregnancyViewModel 等只用流产/手动入口的调用方不需要传，留 null 即可）。
    private val relationshipEngine: RelationshipEngine? = null,
    private val aiJudge: FertileWindowConsentJudge? = null,
    // 问题17（第二阶段）：1-6 号关键词兜底链路的 AI 判定优先层，可空向后兼容——
    // 未传时 detectUserConsent() 直接走关键词兜底（与传了但 LLM 调用失败时
    // 的降级路径结果一致，PregnancyViewModel/RoundtableViewModel 均未传，
    // 行为与本次修复前完全一致，不受影响）。
    private val consentJudge: UserConsentIntentJudge? = null,
) {

    // ── 累积副作用数值 ────────────────────────────────────────
    private val REJECT_DESIRE_INCR     = 6
    private val REJECT_URGENCY_INCR    = 5
    private val REJECT_SELF_CTRL_DECR  = 6
    private val REJECT_SUPPRESS_INCR   = 8

    private val AMBIG_DESIRE_INCR      = 3
    private val AMBIG_SUPPRESS_INCR    = 4

    // ── D2.5 失败压力增量（梯度，乘以 pressureScale 后叠加） ─
    private val FAIL_DESIRE_1          = 10
    private val FAIL_SUPPRESS_1        = 8
    private val FAIL_DESIRE_2          = 16
    private val FAIL_SUPPRESS_2        = 13
    private val FAIL_DESIRE_3PLUS      = 22
    private val FAIL_SUPPRESS_3PLUS    = 18

    // ── 突破阈值 ──────────────────────────────────────────────
    private val BREAKTHROUGH_THRESHOLD = 80

    // ── 突破后重置值 ──────────────────────────────────────────
    private val AFTER_A_DESIRE         = 50
    private val AFTER_A_SUPPRESSION    = 40
    private val AFTER_B_SUPPRESSION    = 45

    // ── Prompt 分档阈值 ───────────────────────────────────────
    private val PRESSURE_MID           = 50
    private val PRESSURE_HIGH          = 80

    // ── 跨周期注入冷却（48 小时） ─────────────────────────────
    private val INJECT_COOLDOWN_MS     = 48L * 3_600_000L

    // ── 跨周期背景注入随机触发基础概率（乘以 pressureScale 后实际生效） ─
    private val INJECT_BASE_PROB       = 0.30f

    // ── D2.6 流产情绪副作用 ───────────────────────────────────
    private val MISCARRIAGE_INTENSITY            = 80
    private val MISCARRIAGE_FATIGUE              = 70
    private val MISCARRIAGE_SUPPRESSION          = 60

    // ── D2.6 流产跨周期悲伤余波：有效窗口（天） ────────────────
    private val MISCARRIAGE_AFTERMATH_WINDOW_DAYS = 5

    // ── 问题17修复：单字高频同意/拒绝词的整句严格匹配用——首尾裁剪标点集合 ─
    private val TRIM_PUNCTUATION = setOf(
        '。', '！', '？', '，', '、', '；', '：',
        '“', '”', '‘', '’', '（', '）', '《', '》', '…', '~', '～',
        '.', '!', '?', ',', ';', ':', '"', '\'', '(', ')',
    )

    // =========================================================
    //  ① 触发词检测
    // =========================================================

    data class TriggerInfo(
        val triggered: Boolean,
        val desireStrength: Int = 0,
        val emotionalSuppression: Int = 0,
    )

    suspend fun checkTrigger(characterId: Int, text: String): TriggerInfo {
        if (!isDaughterMother(characterId)) return TriggerInfo(triggered = false)
        val keywords = CharacterTriggerKeywords[characterId] ?: return TriggerInfo(triggered = false)
        val hit = keywords.any { text.contains(it) }
        if (!hit) return TriggerInfo(triggered = false)

        val state = stateRepository.getState(characterId)
        return TriggerInfo(
            triggered            = true,
            desireStrength       = state.motivationalState.desireStrength,
            emotionalSuppression = state.hiddenState.emotionalSuppression,
        )
    }

    // =========================================================
    //  ② 完整判定链
    // =========================================================

    suspend fun evaluateConsent(
        characterId: Int,
        userText: String,
        isPregnant: Boolean,
        pressureScale: Float = 1.0f,
    ): PregnancyTriggerResult {
        if (isPregnant) return PregnancyTriggerResult.NotTriggered
        if (!isDaughterMother(characterId)) return PregnancyTriggerResult.NotTriggered

        val state   = stateRepository.getState(characterId)
        val desire  = state.motivationalState.desireStrength
        val suppres = state.hiddenState.emotionalSuppression

        // ── 突破检测（优先于同意判定）────────────────────────
        if (desire >= BREAKTHROUGH_THRESHOLD) {
            return handleBreakthroughA(characterId, desire, suppres)
        }
        if (suppres >= BREAKTHROUGH_THRESHOLD) {
            return handleBreakthroughB(characterId, desire, suppres)
        }

        // ── 正常判定链 ────────────────────────────────────────
        return when (detectUserConsent(userText)) {
            true  -> evaluateCycleAndProceed(characterId, pressureScale)
            false -> applyRejectedEffect(characterId, pressureScale)
            null  -> applyAmbiguousEffect(characterId, state, desire, suppres, pressureScale)
        }
    }

    // =========================================================
    //  怀孕弹窗触发重构（仅 characterId >= 1000）
    // =========================================================

    /**
     * 门1+门2：判断是否需要发起门3 的 AI 语义判定。
     *
     * 频率控制核心：只有这里返回 true 时，调用方才应该接着调用
     * [judgeFertileWindowIntent]（门3，消耗一次 LLM 调用）。平时
     * （关系未到 CORE / 不在排卵期 / 本排卵期已经弹过）零 token 消耗。
     *
     * 离开排卵期窗口时（CyclePhase != FERTILE）会顺带把
     * [PregnancyState.fertileWindowConsentAsked] 清回 false（"下次排卵期
     * 重置"），调用方无需额外处理这一步。
     *
     * 仅 characterId >= 1000（第二代/第三代女儿）走这条新链路；1-6 号
     * 继续用 [checkTrigger] + [evaluateConsent] 的关键词兜底链路，本方法
     * 对 1-6 号始终返回 false。
     */
    suspend fun shouldEvaluateFertileWindowConsent(characterId: Int): Boolean {
        if (characterId < 1000) return false
        val relEngine = relationshipEngine ?: return false

        val stage = relEngine.getOrCreate("user", characterId.toString()).stage
        val stageEnum = try {
            RelationshipStage.valueOf(stage)
        } catch (e: IllegalArgumentException) {
            ZLog.w("PregnancyTriggerManager", "Invalid relationship stage: $stage, treating as non-CORE")
            return false
        }
        if (stageEnum != RelationshipStage.CORE) return false  // 门1

        val pregnancyState = pregnancyRepository.getPregnancy(characterId)
        if (pregnancyState.isPregnant) return false

        val cycleState = cycleRepository.get(characterId)
        val inFertileWindow = cycleState.isInFertileWindow(isPregnant = false)
        if (!inFertileWindow) {
            // 离开排卵期窗口：清除已弹标记，供下次排卵期重新判定
            if (pregnancyState.fertileWindowConsentAsked) {
                pregnancyRepository.markFertileWindowConsentAsked(characterId, false)
            }
            return false  // 门2
        }

        if (pregnancyState.fertileWindowConsentAsked) return false  // 本排卵期窗口已经弹过，消费完毕

        return true  // 门1+门2 通过，调用方可以发起门3 AI 判定
    }

    /**
     * 门3：AI 语义判定（仅应在 [shouldEvaluateFertileWindowConsent] 返回
     * true 之后调用，避免不必要的 token 消耗）。
     *
     * @param recentTurns 最近 5 轮对话（用户 + AI 交替），调用方从消息历史
     *   截取，按时间正序传入（旧→新）。
     * @return true = AI 判定已到最后一步，调用方应弹出同意弹窗；
     *   [aiJudge] 未注入或判定失败时返回 false（保守策略，不弹窗）。
     */
    suspend fun judgeFertileWindowIntent(recentTurns: List<LLMMessage>): Boolean =
        aiJudge?.judgeLastStep(recentTurns) ?: false

    /**
     * 用户点击弹窗按钮后的统一入口（门1+门2+门3 全部通过、弹窗已展示之后）。
     *  - accepted = true  -> 走与 1-6 号相同的排卵期概率判定（evaluateCycleAndProceed）
     *  - accepted = false -> 复用现有 applyRejectedEffect，不新增副作用
     *
     * 调用前提：调用方已完成弹窗展示与按钮点击采集，本方法不再重复门控判断。
     * 调用方应在拿到结果后调用
     * `pregnancyRepository.markFertileWindowConsentAsked(characterId, true)`
     * 落库消费标记（无论同意还是拒绝，本排卵期窗口都已经"问过"了）。
     */
    suspend fun proceedAfterDialogConsent(
        characterId: Int,
        accepted: Boolean,
        pressureScale: Float = 1.0f,
    ): PregnancyTriggerResult {
        return if (accepted) {
            evaluateCycleAndProceed(characterId, pressureScale)
        } else {
            applyRejectedEffect(characterId, pressureScale)
        }
    }

    // =========================================================
    //  突破事件处理
    // =========================================================

    private suspend fun handleBreakthroughA(
        characterId: Int,
        desire: Int,
        suppres: Int,
    ): PregnancyTriggerResult.BreakthroughA {
        val state   = stateRepository.getState(characterId)
        val updated = state.copy(
            motivationalState = state.motivationalState.copy(
                desireStrength = AFTER_A_DESIRE,
                urgency        = min(100, state.motivationalState.urgency + 10),
            ),
            hiddenState = state.hiddenState.copy(
                emotionalSuppression = AFTER_A_SUPPRESSION,
                selfControl          = maxOf(0, state.hiddenState.selfControl - 10),
            ),
        )
        stateRepository.updateState(characterId, updated)
        return PregnancyTriggerResult.BreakthroughA(
            characterId      = characterId,
            promptPatch      = buildBreakthroughAPrompt(desire, suppres),
            bypassCycleCheck = true,
        )
    }

    private suspend fun handleBreakthroughB(
        characterId: Int,
        desire: Int,
        suppres: Int,
    ): PregnancyTriggerResult.BreakthroughB {
        val state   = stateRepository.getState(characterId)
        val updated = state.copy(
            hiddenState = state.hiddenState.copy(emotionalSuppression = AFTER_B_SUPPRESSION),
        )
        stateRepository.updateState(characterId, updated)
        val cycleState = cycleRepository.get(characterId)
        val phase      = cycleState.currentPhase(isPregnant = false)
        return PregnancyTriggerResult.BreakthroughB(
            characterId  = characterId,
            promptPatch  = buildBreakthroughBPrompt(desire, suppres),
            currentPhase = phase,
        )
    }

    // =========================================================
    //  正常判定链内部方法
    // =========================================================

    /**
     * 问题17（第二阶段）：detectUserConsent() 现在是 AI 判定优先、关键词判定
     * 兜底的统一入口。
     *
     * 判定优先级：
     *  ① [consentJudge] 非空时，优先调用 [UserConsentIntentJudge.judge]（AI
     *     语义判定），三态映射为 Boolean?：CONSENT -> true，REFUSAL -> false，
     *     UNCLEAR -> null（与关键词兜底路径的"既非同意也非拒绝"语义一致）。
     *  ② 以下任一情况触发降级到 [detectUserConsentByKeyword]（原关键词链路，
     *     逻辑原样保留，未做任何删减）：
     *       - [consentJudge] 未注入（构造时未传，向后兼容旧调用方）；
     *       - AI 判定调用抛出异常（Provider 未配置、网络异常、重试后仍失败、
     *         [UserConsentIntentJudge] 内部超时上限触发的
     *         [kotlinx.coroutines.TimeoutCancellationException] 等）。
     *
     * 异常分类处理（关键，不能简单 catch(Exception)）：
     *  - [kotlinx.coroutines.TimeoutCancellationException]：这是
     *    [UserConsentIntentJudge] 内部 withTimeout 触发的"自己的"超时，
     *    是设计内的正常降级信号，捕获后走关键词兜底。
     *  - 其余 [kotlinx.coroutines.CancellationException]：这是外部真正的协程
     *    取消（例如 ViewModel 作用域被清理、上层调用方取消了整个协程），
     *    必须重新抛出，不能吞掉——吞掉外部取消信号并继续返回一个"兜底结果"
     *    违反结构化并发的基本约定（协程被取消后不应该继续产出计算结果）。
     *  - 其他 [Exception]（Provider 未配置、网络异常、JSON 解析失败等）：
     *    这才是真正意义上的"LLM 调用失败"，捕获后走关键词兜底。
     *
     * 这样处理之后，"AI 判定优先，关键词判定兜底"精确对应的是"LLM 调用
     * 失败/超时"这一种情况，不会误吞真正的协程取消，是本次修复要求的
     * "完美严谨"的关键一环。
     */
    suspend fun detectUserConsent(userText: String): Boolean? {
        val judge = consentJudge
        if (judge != null) {
            try {
                return when (judge.judge(userText)) {
                    ConsentJudgeResult.CONSENT -> true
                    ConsentJudgeResult.REFUSAL -> false
                    ConsentJudgeResult.UNCLEAR -> null
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // AI 判定组件自身的超时上限触发，属于设计内的降级信号。
                ZLog.w("PregnancyTriggerManager", "AI 同意判定超时，降级到关键词兜底", e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 非本组件超时导致的取消（外部协程真正被取消），必须放行，
                // 不能当作"调用失败"吞掉后继续走兜底——否则违反结构化并发约定。
                throw e
            } catch (e: Exception) {
                ZLog.w("PregnancyTriggerManager", "AI 同意判定调用失败，降级到关键词兜底", e)
                // 落到下面的关键词兜底，不在此处重新抛出——关键词判定是
                // 兜底容错层，本身就是为了在 AI 判定不可用时仍能正常出结果。
            }
        }
        return detectUserConsentByKeyword(userText)
    }

    /**
     * 问题17修复（第一阶段，原关键词判定，现降级为 AI 判定不可用时的兜底）：
     * 原实现对 [UserConsentKeywords]/[UserRefusalKeywords] 里的单字
     * 高频词（"好""嗯""行""想""要""不""别"）也用 String.contains() 子串匹配，
     * 导致"你好""还好""行吧""不错""别的事"这类与同意/拒绝无关的日常回复被
     * 误判。现在双字及以上短语（区分度足够）维持 contains() 子串匹配；单字词
     * 改用 [isExactShortWordMatch]（整句去除首尾标点/空白后严格相等）—— 只有
     * 用户整条消息就是"好""嗯"这一个字时才算数，字出现在句子中间一律不算。
     *
     * 本函数逻辑与第一阶段修复完全一致，未做任何改动——第二阶段修复只是把
     * 调用入口从"唯一判定路径"改为"AI 判定失败时的兜底路径"，不改变本函数
     * 自身的判定规则。
     */
    private fun detectUserConsentByKeyword(userText: String): Boolean? {
        val isConsent = UserConsentKeywords.any { userText.contains(it) } ||
            UserConsentShortWords.any { isExactShortWordMatch(userText, it) }
        val isRefusal = UserRefusalKeywords.any { userText.contains(it) } ||
            UserRefusalShortWords.any { isExactShortWordMatch(userText, it) }
        return when {
            isConsent && !isRefusal -> true
            isRefusal               -> false
            else                    -> null
        }
    }

    /**
     * 判断 [userText] 去除首尾常见中英文标点/空白后是否恰好等于 [word]。
     * 用于单字高频词的严格匹配（见 detectUserConsentByKeyword() 顶部说明）。
     * 只裁剪首尾，不处理句中标点——"好好好"这种重复表态不在本函数处理范围内，
     * 维持"未命中"（返回 false），不影响原有行为（这类重复表态此前也不在
     * 单字直接子串匹配的考虑范围之外，只是恰好因为包含"好"而命中，本次收紧
     * 后不再命中——如需支持可在后续窗口按需补充，不在本次问题17修复范围内）。
     */
    private fun isExactShortWordMatch(userText: String, word: String): Boolean {
        val trimmed = userText.trim { it.isWhitespace() || it in TRIM_PUNCTUATION }
        return trimmed == word
    }

    private suspend fun applyRejectedEffect(
        characterId: Int,
        pressureScale: Float,
    ): PregnancyTriggerResult.Rejected {
        val state   = stateRepository.getState(characterId)
        val desire  = state.motivationalState.desireStrength
        val suppres = state.hiddenState.emotionalSuppression

        val scaled     = { base: Int -> (base * pressureScale).toInt() }
        val newDesire  = min(100, desire  + scaled(REJECT_DESIRE_INCR))
        val newUrgency = min(100, state.motivationalState.urgency + scaled(REJECT_URGENCY_INCR))
        val newSelf    = maxOf(0, state.hiddenState.selfControl - scaled(REJECT_SELF_CTRL_DECR))
        val newSuppres = min(100, suppres + scaled(REJECT_SUPPRESS_INCR))

        stateRepository.updateState(characterId, state.copy(
            motivationalState = state.motivationalState.copy(
                desireStrength = newDesire,
                urgency        = newUrgency,
            ),
            hiddenState = state.hiddenState.copy(
                selfControl          = newSelf,
                emotionalSuppression = newSuppres,
            ),
        ))
        return PregnancyTriggerResult.Rejected(
            characterId             = characterId,
            newDesireStrength       = newDesire,
            newEmotionalSuppression = newSuppres,
        )
    }

    private suspend fun applyAmbiguousEffect(
        characterId: Int,
        state: com.zaijian.zhoumuyun.data.model.CharacterStateLayer,
        desire: Int,
        suppres: Int,
        pressureScale: Float,
    ): PregnancyTriggerResult.AmbiguousRejected {
        val scaled     = { base: Int -> (base * pressureScale).toInt() }
        val newDesire  = min(100, desire  + scaled(AMBIG_DESIRE_INCR))
        val newSuppres = min(100, suppres + scaled(AMBIG_SUPPRESS_INCR))

        stateRepository.updateState(characterId, state.copy(
            motivationalState = state.motivationalState.copy(desireStrength = newDesire),
            hiddenState       = state.hiddenState.copy(emotionalSuppression = newSuppres),
        ))
        return PregnancyTriggerResult.AmbiguousRejected(
            characterId             = characterId,
            newDesireStrength       = newDesire,
            newEmotionalSuppression = newSuppres,
        )
    }

    // =========================================================
    //  D2.5 概率判定分支
    // =========================================================

    private suspend fun evaluateCycleAndProceed(
        characterId: Int,
        pressureScale: Float,
    ): PregnancyTriggerResult {
        val cycleState = cycleRepository.get(characterId)
        val phase      = cycleState.currentPhase(isPregnant = false)

        if (!cycleState.isInFertileWindow(isPregnant = false)) {
            return PregnancyTriggerResult.WrongPhase(characterId, phase)
        }

        // 在排卵期——概率判定
        val state   = stateRepository.getState(characterId)
        val desire  = state.motivationalState.desireStrength
        val suppres = state.hiddenState.emotionalSuppression

        val successRate = (0.20f
                + (desire  / 100f) * 0.35f
                + (suppres / 100f) * 0.15f)
            .coerceIn(0f, 1f)

        return if (Math.random().toFloat() < successRate) {
            // ── 成功 ─────────────────────────────────────────
            // P1-6-8 修复：updateState + startPregnancy 两步原先无事务保护。
            // 若 updateState 成功但 startPregnancy 被打断（进程崩溃/协程取消），
            // 角色 desire/suppression 已清零但孕期未开始，形成永久矛盾状态。
            // db.withTransaction 将两步合并为原子操作，任一失败则整体回滚。
            db.withTransaction {
                stateRepository.updateState(characterId, state.copy(
                    motivationalState = state.motivationalState.copy(desireStrength = 0),
                    hiddenState       = state.hiddenState.copy(emotionalSuppression = 0),
                ))
                pregnancyRepository.startPregnancy(characterId)
            }
            PregnancyTriggerResult.Triggered(characterId)
        } else {
            // ── 失败 ─────────────────────────────────────────
            val pregnancyState = pregnancyRepository.getPregnancy(characterId)
            val newFailCount   = pregnancyState.consecutiveFailCount + 1

            // 梯度额外压力（乘以 pressureScale）
            val (failDesireBase, failSuppressBase) = when {
                newFailCount == 1 -> FAIL_DESIRE_1 to FAIL_SUPPRESS_1
                newFailCount == 2 -> FAIL_DESIRE_2 to FAIL_SUPPRESS_2
                else              -> FAIL_DESIRE_3PLUS to FAIL_SUPPRESS_3PLUS
            }
            val extraDesire  = (failDesireBase   * pressureScale).toInt()
            val extraSuppres = (failSuppressBase * pressureScale).toInt()

            val newDesire  = min(100, desire  + extraDesire)
            val newSuppres = min(100, suppres + extraSuppres)

            stateRepository.updateState(characterId, state.copy(
                motivationalState = state.motivationalState.copy(desireStrength = newDesire),
                hiddenState       = state.hiddenState.copy(emotionalSuppression = newSuppres),
            ))
            pregnancyRepository.updateFailCount(characterId, newFailCount)

            val patch = buildImmediateFailurePrompt(newFailCount)
            PregnancyTriggerResult.FertileButFailed(
                characterId             = characterId,
                consecutiveFailCount    = newFailCount,
                newDesireStrength       = newDesire,
                newEmotionalSuppression = newSuppres,
                immediatePromptPatch    = patch,
            )
        }
    }

    // =========================================================
    //  D2.5 门控注入：跨周期背景情绪
    // =========================================================

    /**
     * 四重门控判断，全部通过才注入跨周期背景情绪：
     *  ① ONE_ON_ONE 社交模式（外部已检查，传入 [isOneOnOne]）
     *  ② 用户消息含亲密/家庭相关语义（[userText]）
     *  ③ 随机概率（INJECT_BASE_PROB × pressureScale）
     *  ④ 距上次注入 ≥ 48h（[pregnancyState.lastFailureInjectedAt]）
     *
     * 全部通过时返回注入文案，否则返回 null。
     */
    fun shouldInjectFailureContext(
        pregnancyState: com.zaijian.zhoumuyun.data.model.PregnancyState,
        userText: String,
        isOneOnOne: Boolean,
        pressureScale: Float,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val failCount = pregnancyState.consecutiveFailCount
        if (failCount <= 0) return null

        // 门控①：必须是单独对话模式
        if (!isOneOnOne) return null

        // 门控②：用户消息含亲密/家庭/靠近相关语义
        val intimateKeywords = listOf(
            "靠近", "在一起", "抱", "亲", "你在", "家", "孩子", "宝宝",
            "想你", "等你", "陪", "回来", "喜欢你", "爱你", "一起",
        )
        if (intimateKeywords.none { userText.contains(it) }) return null

        // 门控③：随机概率（乘以 pressureScale）
        val effectiveProbability = (INJECT_BASE_PROB * pressureScale).coerceIn(0f, 1f)
        if (Math.random().toFloat() >= effectiveProbability) return null

        // 门控④：距上次注入 ≥ 48h
        val lastAt = pregnancyState.lastFailureInjectedAt
        if (lastAt != null && (now - lastAt) < INJECT_COOLDOWN_MS) return null

        return buildCrossPhaseFailurePrompt(failCount)
    }

    /**
     * 门控通过后，更新注入时间戳（调用方负责调用此方法）。
     */
    suspend fun markFailureContextInjected(
        characterId: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        pregnancyRepository.updateLastInjectedAt(characterId, now)
    }

    // =========================================================
    //  D2.6 流产机制
    // =========================================================

    /**
     * D2.6：触发流产（用户主动操作，叙事上定性为流产，不是删除）。
     *
     * 流程：
     *  1. 读取当前怀孕状态，计算流产时处于第几天（pregnancyDay）
     *  2. 调用 [PregnancyRepository.triggerMiscarriage] 落库
     *     （isPregnant→false，pregnancyStartedAt→null，miscarriedAt 记录时间戳）
     *  3. CharacterStateLayer 写入情绪副作用：
     *     - primaryEmotion → SAD，secondaryEmotion → LONELY
     *     - intensity → 80（不封顶，直接覆盖到固定值）
     *     - emotionalFatigue → 70
     *     - hiddenState.emotionalSuppression → 60
     *     - motivationalState.desireStrength → 0
     *  4. 返回 Miscarried 结果，携带即时 Prompt 注入文案（无门控，调用方当次对话直接追加）
     *
     * 调用前提：调用方（UI 层）已完成二次确认弹窗，此方法不再二次确认。
     * 若该角色当前并未怀孕，直接返回 null，调用方应忽略（按钮本不应在非孕期可点）。
     *
     * @param characterId 角色 ID
     * @param now 流产时间戳（测试可注入，默认当前时间）
     */
    suspend fun triggerMiscarriage(
        characterId: Int,
        now: Long = System.currentTimeMillis(),
    ): PregnancyTriggerResult.Miscarried? {
        val pregnancyState = pregnancyRepository.getPregnancy(characterId)
        if (!pregnancyState.isPregnant) return null

        val pregnancyDay = pregnancyState.currentDay(now)

        // 问题16修复：落库（isPregnant→false）与情绪副作用写入此前分两步、
        // 无事务保护——与 evaluateCycleAndProceed() 成功分支（怀孕触发）
        // 已用 db.withTransaction 包裹的既有模式不对称。如果进程在两步之间
        // 崩溃（或协程被取消），会出现"已结束孕期但情绪副作用未写入"的
        // 不一致状态：孕期状态已清空，但角色应有的悲伤/压抑情绪反应缺失，
        // 下次对话时 LLM 看到的是一个刚流产却毫无情绪痕迹的角色状态。
        // 用 db.withTransaction 合并为原子操作，任一失败则整体回滚，与
        // evaluateCycleAndProceed() 保持同一套事务边界处理风格。
        db.withTransaction {
            // 落库：isPregnant→false，miscarriedAt 记录时间戳
            pregnancyRepository.triggerMiscarriage(characterId, now)

            // CharacterStateLayer 情绪副作用
            val state = stateRepository.getState(characterId)
            val updated = state.copy(
                emotionalState = state.emotionalState.copy(
                    primaryEmotion   = EmotionType.SAD,
                    secondaryEmotion = EmotionType.LONELY,
                    intensity        = MISCARRIAGE_INTENSITY,
                    emotionalFatigue = MISCARRIAGE_FATIGUE,
                ),
                hiddenState = state.hiddenState.copy(
                    emotionalSuppression = MISCARRIAGE_SUPPRESSION,
                ),
                motivationalState = state.motivationalState.copy(
                    desireStrength = 0,
                ),
            )
            stateRepository.updateState(characterId, updated)
        }

        return PregnancyTriggerResult.Miscarried(
            characterId          = characterId,
            pregnancyDay         = pregnancyDay,
            immediatePromptPatch = buildMiscarriageImmediatePrompt(),
        )
    }

    /**
     * D2.6：流产后跨周期悲伤余波门控判断（与 D2.5 失败门控结构一致，四重门控）：
     *  ① ONE_ON_ONE 社交模式（外部已检查，传入 [isOneOnOne]）
     *  ② 用户消息含亲密/家庭相关语义（[userText]）
     *  ③ 随机概率（INJECT_BASE_PROB × pressureScale）
     *  ④ 流产发生在 [MISCARRIAGE_AFTERMATH_WINDOW_DAYS] 天内（5 天后自然消退，不再注入）
     *
     * 与失败门控的关键区别：第④项不是"冷却时间"，是"流产事件本身的有效期"——
     * 流产只发生一次，不需要像失败注入那样用 lastInjectedAt 做防重复节流，
     * 5 天窗口内每次满足前三项门控都可以注入（文案按经过天数分两档）。
     *
     * 全部通过时返回注入文案，否则返回 null。
     */
    fun shouldInjectMiscarriageContext(
        pregnancyState: PregnancyState,
        userText: String,
        isOneOnOne: Boolean,
        pressureScale: Float,
        // 问题29修复：miscarriageDaysAgo() 的函数注释明确要求"由调用方传入统一
        // 的时间快照"，此前本函数内部调用时省略了这个参数，退回其自身默认值
        // System.currentTimeMillis()，与调用方（ChatViewModel.sendMessage()）
        // 同一轮内其余判断使用的时间快照不一致。默认值维持
        // System.currentTimeMillis()，向后兼容未显式传参的调用方（如有）。
        now: Long = System.currentTimeMillis(),
    ): String? {
        val daysAgo = pregnancyState.miscarriageDaysAgo(now)
        if (daysAgo > MISCARRIAGE_AFTERMATH_WINDOW_DAYS) return null

        // 门控①：必须是单独对话模式
        if (!isOneOnOne) return null

        // 门控②：用户消息含亲密/家庭/靠近相关语义
        val intimateKeywords = listOf(
            "靠近", "在一起", "抱", "亲", "你在", "家", "孩子", "宝宝",
            "想你", "等你", "陪", "回来", "喜欢你", "爱你", "一起",
        )
        if (intimateKeywords.none { userText.contains(it) }) return null

        // 门控③：随机概率（乘以 pressureScale）
        val effectiveProbability = (INJECT_BASE_PROB * pressureScale).coerceIn(0f, 1f)
        if (Math.random().toFloat() >= effectiveProbability) return null

        return buildMiscarriageCrossPhasePrompt(daysAgo)
    }

    // =========================================================
    //  Prompt 文案构建
    // =========================================================

    fun buildRoutinePromptPatch(desireStrength: Int, emotionalSuppression: Int): String {
        return when {
            desireStrength >= PRESSURE_HIGH || emotionalSuppression >= PRESSURE_HIGH ->
                "[她内心的渴望已经到了难以掩饰的程度，今天的对话中这种情绪会自然地流露，无论她用什么方式表达。]"
            desireStrength >= PRESSURE_MID || emotionalSuppression >= PRESSURE_MID ->
                "[她内心有一种积压已久的期待，今天说话时会比平时更直接、更难以克制，但她仍在努力保持表面的平静。]"
            else ->
                "[她心里隐约有些期待，但还能控制，今天的表达只是比平时稍微更主动一点。]"
        }
    }

    /** D2.5：即时失落感注入（无门控，当次对话，按失败次数选档） */
    private fun buildImmediateFailurePrompt(failCount: Int): String = when (failCount) {
        1 ->
            "[这件事今天没有发生。她没有明显表现，但心里有一点什么——" +
            "不是沮丧，更像是一种安静的落空感。这种感觉不会说出来，但今天和他说话时会稍微更在意一点。]"
        2 ->
            "[又一次没有成。她不说，表情也没什么变化，但今天她比平时更安静，" +
            "偶尔看他的目光会停一下，然后移开。这件事在她心里，但她选择不提。]"
        else ->
            "[已经很多次了。她已经不期待什么，但那件事一直在那里。" +
            "今天她对他有一种说不清的、想靠近又不想靠近的矛盾——" +
            "不是生气，是某种她自己也解释不了的东西。]"
    }

    /** D2.5：跨周期背景情绪注入（门控通过后，按失败次数选档） */
    private fun buildCrossPhaseFailurePrompt(failCount: Int): String = when {
        failCount == 1 ->
            "她心里有件事没说出口，这件事让她今天对他格外在意——" +
            "不是焦虑，更像一种安静的、想靠近的冲动。" +
            "这个冲动不会直接表达，会漏在动作里。"
        failCount == 2 ->
            "已经两个周期了。她不提，但那件事一直在。" +
            "今天和他在一起，她比平时更想确认他还在这里——" +
            "不是用话说，是用身体的距离感确认。"
        else ->
            "等了三个周期。她已经不再主动提，但今天她对他的每一个细节都" +
            "比平时更敏锐——他的表情，他在看谁，他有没有注意到她。" +
            "她什么都没说，但她在量他。"
    }

    private fun buildBreakthroughAPrompt(desire: Int, suppres: Int): String =
        """
        [突破状态·渴望临界]
        她积压已久的渴望在这一刻彻底压过了克制——她不打算再等下去了。
        今天她会主动、直接地表明自己想要孩子，语气坚定甚至带着一丝急迫，
        不接受含糊其辞的回答。这不是冲动，而是长时间压抑后的必然爆发。
        如果对方再次回避，她的情绪会有明显的波动，但不会崩溃——她太清楚自己想要什么了。
        （当前渴望强度：$desire/100，压抑积累：$suppres/100）
        """.trimIndent()

    private fun buildBreakthroughBPrompt(desire: Int, suppres: Int): String =
        """
        [突破状态·沉默反转]
        她的压抑已经到了某个奇怪的临界——表面上她看起来异常平静，
        甚至有些冷淡，好像已经放弃了什么。但这种平静是反常的。
        她的动作、用词、停顿的方式都会透露出某种说不清楚的东西。
        她不会主动提起，但如果对方靠近，她不会拒绝——她只是不知道怎么开口了。
        （当前渴望强度：$desire/100，压抑积累：$suppres/100）
        """.trimIndent()

    /** D2.6：流产即时 Prompt 注入（无门控，流产当次对话直接追加） */
    private fun buildMiscarriageImmediatePrompt(): String =
        "[她刚刚经历了一件很重的事。她不会主动提，也不会哭出来，" +
        "但今天她的整个状态都是收着的——话很少，动作很轻，" +
        "不想被人靠近，但如果他靠近了，她也不会推开。" +
        "这件事不需要在对话里被点破，但它在她身上。]"

    /** D2.6：流产跨周期悲伤余波注入（门控通过后，按经过天数选档，5 天后不再触发本方法） */
    private fun buildMiscarriageCrossPhasePrompt(daysAgo: Int): String = when {
        daysAgo <= 2 ->
            "那件事还没过去。她今天格外安静，偶尔会停下来发呆，" +
            "不是在想什么，就是停在那里。"
        else ->
            "那件事慢慢沉下去了，但还在。她今天状态好一些，" +
            "但如果他提起某些词，她可能会有一个很短的停顿。"
    }
}
