package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.datastore.D3AskAttemptDataStore
import com.zaijian.zhoumuyun.data.datastore.PregnancyPressureDataStore
import com.zaijian.zhoumuyun.data.db.entity.PregnancyQuestionType
import com.zaijian.zhoumuyun.domain.AgentRelationEngine
import com.zaijian.zhoumuyun.data.manager.PregnancyTriggerManager
import com.zaijian.zhoumuyun.data.model.AgentRelationStage
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult
import com.zaijian.zhoumuyun.data.model.isDaughterMother
import com.zaijian.zhoumuyun.data.model.pickGenericDialogText
import com.zaijian.zhoumuyun.data.model.slotKey
import com.zaijian.zhoumuyun.data.prompt.D3TriggerContent
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyAnswerRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
import com.zaijian.zhoumuyun.data.repository.SlotRecordResult
import com.zaijian.zhoumuyun.domain.StageTransitionResult
import com.zaijian.zhoumuyun.domain.pregnancy.IntentResult
import com.zaijian.zhoumuyun.domain.pregnancy.PregnancyAnswerIntentDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
//  PregnancyPromptResult — buildPregnancyPrompts() 的产出数据类
//
//  包含两阶段孕期 Prompt 组装（发送前 Prompt 构建）的全部产出，
//  供 ChatViewModel.sendMessage() 消费后继续走 PromptOrchestrator 主链路。
// ─────────────────────────────────────────────────────────────────────────────
data class PregnancyPromptResult(
    /** 孕期状态快照（可能被 evaluateConsent 的 Triggered 分支刷新过） */
    val pregnancyState: PregnancyState,
    /** 孕期触发 Prompt 注入（evaluateConsent 判定链产出的即时 Prompt 片段） */
    val pregnancyTriggerPromptPatch: String,
    /** D2.6 流产余波 Prompt 注入（跨周期 5 天内悲伤背景） */
    val miscarriageAftermathPatch: String,
    /** D2.5 失败背景情绪 Prompt 注入（跨周期 48h 冷却门控） */
    val failureContextPatch: String,
    /** 常规压力 Prompt 注入（无门控，每轮基于 desireStrength/emotionalSuppression 渲染） */
    val routinePressurePatch: String,
    /** D3 孕期共设 · 槎位问答本轮提问指令 Prompt 注入 */
    val d3QuestionPatch: String,
    /** D3 本轮若确实问出口，记录 (questionType, slotIndex) 供后置 didAsk 判定 */
    val d3PendingAsk: Pair<PregnancyQuestionType, Int>?,
)

// ─────────────────────────────────────────────────────────────────────────────
//  PregnancyPromptDelegate
//
//  从 ChatViewModel.sendMessage() 中抽取的孕期相关逻辑（约 470 行），
//  分为两个方法：
//    - buildPregnancyPrompts()：发送前 Prompt 组装（约 310 行）
//    - runPostReplyAnalysis()：后置孕期分析（约 158 行）
//
//  所有依赖通过构造函数注入，不持有 ViewModel 引用。
//  D4 生成器触发（需要 viewModelScope.launch + _uiState 更新）通过回调参数处理。
// ─────────────────────────────────────────────────────────────────────────────
class PregnancyPromptDelegate(
    private val pregnancyRepo: PregnancyRepository,
    private val pregnancyTriggerManager: PregnancyTriggerManager,
    private val pregnancyPressureDataStore: PregnancyPressureDataStore,
    private val pregnancyAnswerRepo: PregnancyAnswerRepository,
    private val pregnancyAnswerIntentDetector: PregnancyAnswerIntentDetector,
    private val d3AskAttemptStore: D3AskAttemptDataStore,
    private val daughterRepo: DaughterCharacterRepository,
    private val agentRelationEngine: AgentRelationEngine,
    private val viewModelScope: CoroutineScope,
) {

    companion object {
        /** L5 修复：受孕机制 AI 门3判定冷却，与 ChatViewModel 中同名常量保持一致 */
        private const val FERTILE_JUDGE_COOLDOWN_MS = 5 * 60 * 1000L  // 5 分钟冷却
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  buildPregnancyPrompts()
    //
    //  从 ChatViewModel.sendMessage() 中抽取的孕期 Prompt 组装逻辑
    //  （对应 ChatViewModel 的 880-1190 行区域）。
    //
    //  处理：
    //    - evaluateConsent 关键词触发链路
    //    - pregnancyState 读取与刷新
    //    - miscarriageAftermathPatch（D2.6 流产余波）
    //    - failureContextPatch（D2.5 失败背景情绪）
    //    - routinePressurePatch（常规压力）
    //    - D3 槎位问答（pendingQuestion 处理 + 新题注入）
    //    - D4 生成器触发（槎位全锁后，通过回调处理）
    // ═════════════════════════════════════════════════════════════════════════
    suspend fun buildPregnancyPrompts(
        characterId: Int,
        userText: String,
        currentPregnancyState: PregnancyState,
        characterState: CharacterStateLayer,
        pendingKeywordTriggerMap: ConcurrentHashMap<Int, Boolean>,
        onTriggerD4Generation: suspend (Map<String, String>) -> Unit,
    ): PregnancyPromptResult {

        // ── 补全怀孕状态（注意：PregnancyDao 没有 getLatest，
        //    正确入口是 PregnancyRepository.getPregnancy，与 RoundtableViewModel
        //    现有写法保持一致；未怀孕时返回默认 PregnancyState，非 null）──
        var pregnancyState = currentPregnancyState

        // ══════════════════════════════════════════════════════════════
        // 问题1修复：1-6 号角色关键词兜底触发链路 —— ② evaluateConsent()
        //
        // 消费上一轮 checkTrigger()（后置分析协程块内）留下的 pending 标记：
        // 若上一轮 AI 回复命中了触发词，本轮用户消息（text）就是"回应"，
        // 送入 evaluateConsent() 走完整判定链（突破检测 → 同意/拒绝/模糊）。
        //
        // 范围限定：显式用 characterId in 1..6 判断是否需要走这条链路，
        // 不依赖 evaluateConsent() 内部的 isDaughterMother() 检查来兜底——
        // isDaughterMother(characterId) = characterId in setOf(1..6) ||
        // characterId >= 1000，对 1-6 和女儿（>=1000）一视同仁地放行，
        // 只排除 7-9 号等真正无关角色。也就是说 evaluateConsent() 内部
        // 那道检查本身并不会把女儿角色挡在外面；本调用点的 pending 标记
        // 只可能在 checkTrigger()（同样已用 characterId in 1..6 限定，
        // 见下方后置分析协程块）里被设置为 true，双重限定叠加才保证了
        // 女儿（id>=1000）的角色永远不会走到这条 1-6 号专属链路，而不是内部
        // isDaughterMother() 检查单独起作用。
        //
        // 判定完成后立即清除 pending 标记（无论结果如何），避免同一次
        // 触发被后续多轮重复判定——evaluateConsent() 是"一次性问答"语义，
        // 不是持续轮询状态。
        var pregnancyTriggerPromptPatch = ""
        if (characterId in 1..6 && pendingKeywordTriggerMap[characterId] == true) {
            pendingKeywordTriggerMap.remove(characterId)
            try {
                val triggerResult = pregnancyTriggerManager.evaluateConsent(
                    characterId  = characterId,
                    userText     = userText,
                    isPregnant   = pregnancyState.isPregnant,
                )
                when (triggerResult) {
                    is PregnancyTriggerResult.Triggered -> {
                        // 怀孕已在 PregnancyTriggerManager 内部落库，这里重新读一次
                        // 保证本轮 buildSystemPrompt 用的是怀孕后的最新状态，
                        // 不会因为用的是本函数顶部读的旧快照而漏掉"刚怀孕"这一状态变化。
                        pregnancyState = pregnancyRepo.getPregnancy(characterId)
                    }
                    is PregnancyTriggerResult.FertileButFailed -> {
                        pregnancyTriggerPromptPatch = triggerResult.immediatePromptPatch
                    }
                    is PregnancyTriggerResult.BreakthroughA -> {
                        pregnancyTriggerPromptPatch = triggerResult.promptPatch
                    }
                    is PregnancyTriggerResult.BreakthroughB -> {
                        pregnancyTriggerPromptPatch = triggerResult.promptPatch
                    }
                    is PregnancyTriggerResult.Rejected,
                    is PregnancyTriggerResult.AmbiguousRejected,
                    is PregnancyTriggerResult.WrongPhase,
                    is PregnancyTriggerResult.NotTriggered,
                    is PregnancyTriggerResult.Miscarried -> {
                        // Rejected/AmbiguousRejected/WrongPhase：副作用已在 manager 内部落库
                        // （desireStrength/emotionalSuppression 数值更新），无需即时 Prompt 注入，
                        // 下一轮 State Layer 渲染时数值会自然体现在角色状态描述里。
                        // NotTriggered：isPregnant 为 true 时的兜底分支，理论上不应发生
                        // （pending 标记只在未怀孕时由 checkTrigger 设置），安全忽略。
                        // Miscarried：evaluateConsent() 内部判定链不会产出此分支
                        // （只有 triggerMiscarriage() 会），穷尽 when 分支需要，安全忽略。
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("PregnancyPromptDelegate", "evaluateConsent 判定链异常（不影响主流程）", e)
            }
        }

        // ── 补全 miscarriageAftermathPatch（D2.6 流产后5天内跨周期悲伤余波）──
        // ChatViewModel 是一对一私聊场景，isOneOnOne 恒为 true。
        //
        // 问题4修复：pressureScale 此前硬编码 1.0f，现读取
        // PregnancyPressureDataStore.pregnancyPressureScaleFlow 的当前值
        // （用户可调节的孕期压力系数，默认 1.0f，与硬编码时代行为完全一致，
        // 用户未主动调整过设置时零行为变化）。用 .first() 读一次而非持续
        // collect——这是"发消息"这一次性事件里的单次快照读取，不是需要
        // 响应式更新的 UI 状态，与 BriefingViewModel 里 lastOpenAtFlow.first()
        // 的用法同一模式。safeData() 已在 DataStore 层兜底 IOException，
        // 这里不需要额外 try-catch。
        //
        // 问题29修复：miscarriageDaysAgo() 内部由 shouldInjectMiscarriageContext()
        // 调用，此前用其默认参数 System.currentTimeMillis()，与"整轮统一时间快照"
        // 的既有约定（本函数其余各处落库/判断均使用同一个 now）不一致。这里
        // 统一取一次 now，显式透传给 shouldInjectMiscarriageContext()/
        // shouldInjectFailureContext()，避免同一轮内因为函数调用先后跨越了
        // 毫秒边界而产生难以复现的细微不一致（例如流产"第5天窗口"边界判断）。
        val pressureScale = pregnancyPressureDataStore.pregnancyPressureScaleFlow.first()
        val nowSnapshot   = System.currentTimeMillis()

        val miscarriageAftermathPatch = pregnancyTriggerManager.shouldInjectMiscarriageContext(
            pregnancyState = pregnancyState,
            userText       = userText,
            isOneOnOne     = true,
            pressureScale  = pressureScale,
            now            = nowSnapshot,
        ) ?: ""

        // ── 补全 failureContextPatch（D2.5 跨周期失败背景情绪，问题3修复）──
        // 与上面的流产余波同构：四重门控（含随机概率+48h冷却）全部通过才
        // 返回非空文案，否则静默返回 ""，零行为可见变化。门控通过后必须
        // 调用 markFailureContextInjected() 落库更新 lastFailureInjectedAt，
        // 否则下一轮 48h 冷却检查会一直读到旧时间戳，实质上失去冷却效果——
        // 这一步不能漏，是本条修复"真正生效"而非"看起来接上了"的关键。
        val failureContextPatch = pregnancyTriggerManager.shouldInjectFailureContext(
            pregnancyState = pregnancyState,
            userText       = userText,
            isOneOnOne     = true,
            pressureScale  = pressureScale,
            now            = nowSnapshot,
        ) ?: ""
        if (failureContextPatch.isNotEmpty()) {
            try {
                pregnancyTriggerManager.markFailureContextInjected(characterId, nowSnapshot)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 落库失败不应该丢弃这一轮已经生成好的 Prompt 文案（用户体验
                // 优先于"下一轮冷却计时是否精确"），但要记录日志——如果这个
                // 异常反复出现，说明 lastFailureInjectedAt 的持久化链路本身
                // 有问题，需要单独排查，不属于本次修复范围。
                ZLog.w("PregnancyPromptDelegate", "markFailureContextInjected 失败（不影响本轮文案）", e)
            }
        }

        // ── 补全 routinePressurePatch（常规压力 Prompt，问题3/4修复）──
        // 无门控、每轮都渲染：基于当前 characterState 的 desireStrength/
        // emotionalSuppression 数值分档给出背景文案（PromptOrchestrator.kt
        // 注释原本设想的"D2 正常同意分支"专属场景实际过窄——只要角色当前
        // 有渴望/压抑数值积累，无论是通过 1-6 号关键词链路还是女儿 AI 判定
        // 链路产生的，都应该体现在日常 Prompt 里，不应该只在恰好命中判定
        // 分支的那一轮才出现，否则绝大多数轮次这个数值状态对 LLM 完全不可见。
        // 与 failureContextPatch 的区别：那是"事件驱动、有冷却"的一次性情绪
        // 涟漪，这是"持续存在、无冷却"的背景压力描述，两者不互斥、可以同轮共存。
        val routinePressurePatch = if (
            characterState.motivationalState.desireStrength > 0 ||
            characterState.hiddenState.emotionalSuppression > 0
        ) {
            pregnancyTriggerManager.buildRoutinePromptPatch(
                desireStrength        = characterState.motivationalState.desireStrength,
                emotionalSuppression  = characterState.hiddenState.emotionalSuppression,
            )
        } else {
            ""
        }

        // ══════════════════════════════════════════════════════════════
        // 补全 d3QuestionPatch（D3 孕期共设 · 槎位问答状态机）
        // 三重门控（与 D3AskAttemptDataStore 文档枚举的三个 gate 完全一致）：
        //   ① 孕期状态不符（非母亲角色 / 未怀孕 / 第三代女儿——没有第四代可问）→ 不触发
        //   ② 本轮开始时已有挂起问题等待回答 → 本轮不追加新题，先处理回答
        //      （这一轮如果刚答完，也不在同一轮立刻追问下一题，留一轮呼吸空间，
        //       下一轮 pending 已清空后才会问下一题）
        //   ③ 全部 6 个槎位已锁定 → D3 阶段结束（D4 生成器消费锁定答案，超出本次范围）
        //
        // 注意（问题1修复引入）：pregnancyState 是 var，若本轮用户消息刚好命中
        // 上方 evaluateConsent() 判定链且结果为 Triggered（1-6 号关键词兜底触发
        // 怀孕成功），这里读到的已经是刷新后 isPregnant=true 的最新值——即"这条
        // 消息让她怀孕"和"同一轮就开始问 D3 第一题"是同一轮发生的，属于预期内的
        // 时序改进，不是脏读；1-6 号角色此前从未有过 D3 问答（因为从未真正触发
        // 过怀孕，见问题1原始描述），这里是该链路接入后自然获得的新行为。
        // ══════════════════════════════════════════════════════════════
        val isD3Eligible = isDaughterMother(characterId) &&
            pregnancyState.isPregnant &&
            (characterId < 1000 || !daughterRepo.isThirdGeneration(characterId))

        val pendingQuestionAtTurnStart = if (isD3Eligible) {
            pregnancyAnswerRepo.getPendingQuestion(characterId)
        } else null

        // 步骤①：若上一轮有挂起问题，本轮用户消息可能是在回答——交给 AI 判定意图
        if (pendingQuestionAtTurnStart != null) {
            val intent = pregnancyAnswerIntentDetector.isAnswering(
                pendingQuestionText = pendingQuestionAtTurnStart.questionText,
                userReply           = userText,
            )
            if (intent == IntentResult.YES) {
                val answeredType = runCatching {
                    PregnancyQuestionType.valueOf(pendingQuestionAtTurnStart.questionType)
                }.getOrNull()
                if (answeredType != null) {
                    val answeredSlot = pendingQuestionAtTurnStart.slotIndex
                    val recordResult = pregnancyAnswerRepo.recordAnswer(
                        motherCharacterId  = characterId,
                        pregnancyStartedAt = pregnancyState.pregnancyStartedAt ?: 0L,
                        questionType       = answeredType,
                        slotIndex          = answeredSlot,
                        questionText       = pendingQuestionAtTurnStart.questionText,
                        answerText         = userText,
                    )
                    pregnancyAnswerRepo.clearPendingQuestion(characterId)
                    if (recordResult is SlotRecordResult.Locked || recordResult is SlotRecordResult.ForceLocked) {
                        // 槎位锁定后清掉该槎位的提问次数计数（D3AskAttemptDataStore 文档约定）
                        d3AskAttemptStore.clear(characterId, answeredType, answeredSlot)
                        // ── 检查3：D4 触发门控 ──────────────────────────────────
                        // 本次锁定后立即检查全部 6 个槎位是否均已锁定；
                        // 是 → 读取全部锁定答案，通过回调触发 D4 人格生成器。
                        // 查询和生成都是 IO 密集型，回调由调用方负责放进独立协程。
                        val allLocked = pregnancyAnswerRepo.isAllSlotsLocked(characterId)
                        if (allLocked) {
                            val lockedAnswers = PregnancyAnswerRepository
                                .ALL_SLOTS
                                .mapNotNull { slot ->
                                    val ans = pregnancyAnswerRepo.getLockedAnswer(
                                        motherCharacterId = characterId,
                                        questionType      = slot.questionType,
                                        slotIndex         = slot.slotIndex,
                                    )
                                    if (ans != null) {
                                        slotKey(slot.questionType, slot.slotIndex) to ans
                                    } else null
                                }.toMap()
                            // 注意：motherConfig 需要由调用方在回调闭包中从 _uiState 获取，
                            // 这里通过回调参数传入 lockedAnswers，调用方负责组装完整调用。
                            onTriggerD4Generation(lockedAnswers)
                        }
                    }
                    // StillOpen / FirstAnswer：保留计数，槎位仍开放，等下一次门控窗口再问
                } else {
                    // 枚举值非法，清除脏数据避免后续反复触发
                    pregnancyAnswerRepo.clearPendingQuestion(characterId)
                }
            }
            // intent == NO：用户没在回答这个问题，挂起问题原样保留，不消耗，不清空
        }

        // 步骤②：仅当本轮开始时没有挂起问题，才考虑问下一个槎位的新题
        var d3QuestionPatch = ""
        // (questionType, slotIndex) —— 本轮若确实问出口，AI 回复生成后用于落库
        var d3PendingAsk: Pair<PregnancyQuestionType, Int>? = null
        if (isD3Eligible && pendingQuestionAtTurnStart == null) {
            val nextSlot = pregnancyAnswerRepo.nextUnlockedSlot(characterId)
            if (nextSlot != null) {
                // P2-9 修复：原子化"读取→+1→返回"，消除 read-modify-write 竞态。
                val attemptNumber = d3AskAttemptStore.nextAttemptNumberAndRecord(
                    characterId  = characterId,
                    questionType = nextSlot.questionType,
                    slotIndex    = nextSlot.slotIndex,
                )
                val patchText = D3TriggerContent.blockFor(
                    characterId   = characterId,
                    questionType  = nextSlot.questionType,
                    slotIndex     = nextSlot.slotIndex,
                    attemptNumber = attemptNumber,
                )
                if (patchText != null) {
                    d3QuestionPatch = "[D3 孕期共设 · 本轮提问指令]\n$patchText"
                    d3PendingAsk = nextSlot.questionType to nextSlot.slotIndex
                }
                // patchText == null：文案库缺该组合（角色/槎位/次数），理论上不应发生
                // （TRIGGER_DATA 应已全覆盖），保守地不注入，不记 pending
            }
            // nextSlot == null：6 个槎位全部锁定，D3 阶段结束，不注入
        }

        return PregnancyPromptResult(
            pregnancyState              = pregnancyState,
            pregnancyTriggerPromptPatch = pregnancyTriggerPromptPatch,
            miscarriageAftermathPatch   = miscarriageAftermathPatch,
            failureContextPatch         = failureContextPatch,
            routinePressurePatch        = routinePressurePatch,
            d3QuestionPatch             = d3QuestionPatch,
            d3PendingAsk                = d3PendingAsk,
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  runPostReplyAnalysis()
    //
    //  从 ChatViewModel.sendMessage() 中抽取的后置孕期分析逻辑
    //  （对应 ChatViewModel 的 1475-1634 行区域）。
    //
    //  处理：
    //    - checkTrigger 关键词扫描（1-6 号角色关键词兜底触发链路 ①）
    //    - 受孕窗口同意对话框触发链路（三重门控）
    //    - D5 关系阶段升阶（女儿角色 >= 1000）
    //    - D3 didAsk 判定（确认 AI 是否真的把问题问出口）
    // ═════════════════════════════════════════════════════════════════════════
    suspend fun runPostReplyAnalysis(
        characterId: Int,
        aiReply: String,
        userText: String,
        pregnancyState: PregnancyState,
        d3Pending: Pair<PregnancyQuestionType, Int>?,
        d3Patch: String,
        pendingKeywordTriggerMap: ConcurrentHashMap<Int, Boolean>,
        lastFertileJudgeAtMap: ConcurrentHashMap<Int, Long>,
        recentMessages: List<LLMMessage>,
        character: CharacterConfig?,
        onTriggerD4Generation: suspend (Map<String, String>) -> Unit,
        onFertileWindowConsentDialog: (dialogText: String, characterName: String, characterId: Int) -> Unit,
    ) {
        // ══════════════════════════════════════════════════════════════
        // 问题1修复：1-6 号角色关键词兜底触发链路 —— ① checkTrigger()
        //
        // AI 回复（capturedReply）写库完成后，扫描本轮回复文本是否命中
        // CharacterTriggerKeywords 关键词表。命中则把 pending 标记写入
        // pendingKeywordTriggerMap，供下一轮用户发消息时的 evaluateConsent()
        // 调用点（sendMessage 顶部，pregnancyState 读取之后）消费。
        //
        // 范围限定：显式用 characterId in 1..6 判断，不依赖
        // checkTrigger() 内部的 isDaughterMother() 检查来挡住女儿角色——
        // isDaughterMother() 对 1-6 和女儿（>=1000）都返回 true，真正让
        // 女儿角色查不到关键词的是 CharacterTriggerKeywords[characterId]
        // 这个 map 本身只有 1-6 号的 key（女儿角色查表落空，?: 兜底返回
        // triggered=false）。这是"关键词表恰好未收录女儿"造成的结果，
        // 不是 isDaughterMother() 主动排除女儿的结果——如果以后有人往
        // CharacterTriggerKeywords 里补充了 1000+ 的 key，checkTrigger()
        // 内部不会拦住它。本调用点的 characterId in 1..6 限定，才是
        // 这条链路唯一可靠生效的边界，必须保留，不能因为"内部好像也判断了"
        // 就省略。
        //
        // 仅在角色未怀孕时才有意义（已怀孕不需要再判定是否触发怀孕）。
        // capturedPregnancyState 捕获的是本轮 pregnancyState（var）在
        // evaluateConsent 调用点之后的值——若本轮用户消息恰好通过
        // evaluateConsent() 触发了怀孕（Triggered 分支），这里能看到
        // 刷新后的 isPregnant=true，正确跳过本次 checkTrigger 标记；
        // 不是"函数顶部读取的原始快照"。
        if (characterId in 1..6 && !pregnancyState.isPregnant) {
            try {
                val trigger = pregnancyTriggerManager.checkTrigger(characterId, aiReply)
                if (trigger.triggered) {
                    pendingKeywordTriggerMap[characterId] = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("PregnancyPromptDelegate", "checkTrigger 扫描异常（不影响主流程）", e)
            }
        }

        // ── 1.1 受孕窗口同意对话框触发链路 ────────────────────────────
        // 三重门控顺序：门1+门2（shouldEvaluateFertileWindowConsent）→ 门3（AI语义判定）
        val shouldEval = pregnancyTriggerManager.shouldEvaluateFertileWindowConsent(characterId)
        if (shouldEval) {
            // L5 修复：加冷却保护，避免同一角色每条消息都触发一次 AI 判定 LLM 调用。
            val now = System.currentTimeMillis()
            val lastJudgeAt = lastFertileJudgeAtMap[characterId] ?: 0L
            val cooldownPassed = (now - lastJudgeAt) >= FERTILE_JUDGE_COOLDOWN_MS
            if (cooldownPassed) {
                // 注意：recentMessages 由调用方从消息历史截取（最近 10 轮），
                // 这里直接使用传入的列表。
                val intentPassed = pregnancyTriggerManager.judgeFertileWindowIntent(recentMessages)
                lastFertileJudgeAtMap[characterId] = System.currentTimeMillis()
                if (intentPassed) {
                    if (character != null) {
                        // S3问题2修复：原 pickCharacterDialogText 中 1-6 号角色定制文案永不可达
                        // （characterId < 1000 门控阻断），现统一使用通用文案
                        val dialogText = pickGenericDialogText(character.name)
                        onFertileWindowConsentDialog(
                            dialogText,
                            character.name,
                            characterId,
                        )
                    }
                }
            }
        }

        // ── 检查5a：D5 关系阶段引擎 ─────────────────────────────────
        // 整段包 try-catch：内部涉及多次女儿数据读取
        // （isThirdGeneration/getCharacterConfig/isAllSlotsLocked 等），
        // 理论上走到这里的 daughterId 数据应该完好（她能正常对话，
        // 说明数据本身可用），但作为最后一道防线，任一环节异常
        // 都不应该连累后面完全独立的"D3 didAsk 判定"逻辑。
        try {
            if (characterId >= 1000) {
                val transitionResult = agentRelationEngine.onInteractionComplete(
                    daughterId    = characterId,
                    userText      = userText,
                    assistantText = aiReply,
                )
                if (transitionResult is StageTransitionResult.Upgraded) {
                    ZLog.i(
                        "PregnancyPromptDelegate",
                        "D5 升阶：daughterId=${transitionResult.daughterId} → ${transitionResult.newStage}",
                    )
                    if (transitionResult.newStage == AgentRelationStage.STAGE_3_SEEKING) {
                        val daughterId = transitionResult.daughterId
                        val isAlreadyGen3 = daughterRepo.isThirdGeneration(daughterId)
                        if (!isAlreadyGen3) {
                            val allLocked = pregnancyAnswerRepo.isAllSlotsLocked(daughterId)
                            if (allLocked) {
                                val motherConfig = daughterRepo.getCharacterConfig(daughterId)
                                if (motherConfig != null) {
                                    val lockedAnswers = PregnancyAnswerRepository.ALL_SLOTS
                                        .mapNotNull { slot ->
                                            val ans = pregnancyAnswerRepo.getLockedAnswer(
                                                motherCharacterId = daughterId,
                                                questionType      = slot.questionType,
                                                slotIndex         = slot.slotIndex,
                                            )
                                            if (ans != null) {
                                                slotKey(slot.questionType, slot.slotIndex) to ans
                                            } else null
                                        }.toMap()
                                    onTriggerD4Generation(lockedAnswers)
                                } else {
                                    ZLog.w("PregnancyPromptDelegate", "D5 STAGE_3：daughterId=$daughterId 无法取得 CharacterConfig，跳过第三代生成")
                                }
                            } else {
                                ZLog.i("PregnancyPromptDelegate", "D5 STAGE_3：daughterId=$daughterId 槽位尚未全锁，等待 D3 收敛")
                            }
                        } else {
                            ZLog.i("PregnancyPromptDelegate", "D5 STAGE_3：daughterId=$daughterId 已是第三代，不再生成下一代")
                        }
                    }
                }
            }
        } catch (e: DaughterDataException) {
            ZLog.e("PregnancyPromptDelegate", "D5 升阶检查中女儿数据异常，daughterId=$characterId", e)
        }

        // ── D3 didAsk 判定：本轮注入了提问指令，确认 AI 是否真的把问题问出口 ──
        // Fix（token 优化）：d3QuestionPatch 含完整指令块，截取前 200 字。
        if (d3Pending != null) {
            val (askedType, askedSlot) = d3Pending
            val didAsk = pregnancyAnswerIntentDetector.didAsk(
                expectedQuestionTopic = d3Patch.take(200),
                aiReply               = aiReply,
            )
            if (didAsk == IntentResult.YES) {
                pregnancyAnswerRepo.recordPendingQuestion(
                    motherCharacterId = characterId,
                    questionType      = askedType,
                    slotIndex         = askedSlot,
                    questionText      = aiReply,
                )
                // P2-9 修复：nextAttemptNumberAndRecord 已原子递增，
                // 不再需要单独的 recordAsked 调用。
            }
            // didAsk == NO：AI 没把问题问出口，不记 pending、不增加计数
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  onFertileWindowDialogResult()
    //
    //  受孕窗口同意对话框的用户选择回调。从 ChatViewModel 中移入。
    //  accepted = true/false 分别对应用户点击「同意」/「拒绝」。
    //  使用 characterId（弹窗展示时捕获的快照）而非实时 currentCharacterId。
    // ═════════════════════════════════════════════════════════════════════════
    fun onFertileWindowDialogResult(
        accepted: Boolean,
        characterId: Int,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pressureScale = pregnancyPressureDataStore.pregnancyPressureScaleFlow.first()
                val result = pregnancyTriggerManager.proceedAfterDialogConsent(
                    characterId   = characterId,
                    accepted      = accepted,
                    pressureScale = pressureScale,
                )
                when (result) {
                    is PregnancyTriggerResult.Triggered -> {
                        ZLog.i("PregnancyPromptDelegate", "受孕弹窗同意后判定：怀孕触发（characterId=$characterId）")
                    }
                    is PregnancyTriggerResult.FertileButFailed -> {
                        ZLog.i("PregnancyPromptDelegate", "受孕弹窗同意后判定：本次未命中（characterId=$characterId，" +
                            "连续失败${result.consecutiveFailCount}次）")
                    }
                    is PregnancyTriggerResult.WrongPhase -> {
                        ZLog.i("PregnancyPromptDelegate", "受孕弹窗同意后判定：非排卵期（characterId=$characterId）")
                    }
                    is PregnancyTriggerResult.Rejected -> {
                        ZLog.i("PregnancyPromptDelegate", "受孕弹窗拒绝：累积副作用已写入（characterId=$characterId）")
                    }
                    is PregnancyTriggerResult.AmbiguousRejected,
                    is PregnancyTriggerResult.NotTriggered,
                    is PregnancyTriggerResult.BreakthroughA,
                    is PregnancyTriggerResult.BreakthroughB,
                    is PregnancyTriggerResult.Miscarried -> {
                        // proceedAfterDialogConsent 内部只会走 evaluateCycleAndProceed（同意分支）
                        // 或 applyRejectedEffect（拒绝分支），不会产出以上分支；
                        // 穷尽 when 分支需要，安全忽略。
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("PregnancyPromptDelegate", "onFertileWindowDialogResult: proceedAfterDialogConsent 异常", e)
            }
        }
    }
}