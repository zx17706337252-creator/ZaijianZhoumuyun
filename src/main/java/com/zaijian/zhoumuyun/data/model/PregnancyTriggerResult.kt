package com.zaijian.zhoumuyun.data.model

// ─────────────────────────────────────────────────────────────
//  PregnancyTriggerResult — 判定链结果（D2 + D2.5 + D2.6）
//
//  D2 正常分支：
//    NotTriggered      — 无触发，正常对话
//    Rejected          — 明确拒绝，写累积副作用
//    AmbiguousRejected — 模糊回复，写轻度累积副作用
//    WrongPhase        — 同意但非排卵期，本次不怀孕
//    Triggered         — 同意 + 排卵期 + 概率命中，怀孕开始
//
//  D2.5 新增分支：
//    FertileButFailed  — 同意 + 排卵期 + 概率未命中，失败后加压
//
//  D2 突破事件分支：
//    BreakthroughA     — desireStrength ≥ 80：绕过周期强制表态
//    BreakthroughB     — emotionalSuppression ≥ 80 且 desireStrength < 80：沉默反转
//
//  D2.6 新增分支：
//    Miscarried        — 用户主动触发流产（叙事上为流产，不是删除）
//
//  两者同时 ≥ 80 时 A 优先（渴望压过压抑）。
// ─────────────────────────────────────────────────────────────

sealed class PregnancyTriggerResult {

    /** 无触发，正常对话，无任何副作用 */
    object NotTriggered : PregnancyTriggerResult()

    /**
     * 明确拒绝。
     * 副作用：desireStrength ↑6 / urgency ↑5 / selfControl ↓6 / emotionalSuppression ↑8
     */
    data class Rejected(
        val characterId: Int,
        val newDesireStrength: Int,
        val newEmotionalSuppression: Int,
    ) : PregnancyTriggerResult()

    /**
     * 模糊回复（既非明确同意也非明确拒绝）。
     * 副作用：desireStrength ↑3 / emotionalSuppression ↑4（约为拒绝的一半）
     */
    data class AmbiguousRejected(
        val characterId: Int,
        val newDesireStrength: Int,
        val newEmotionalSuppression: Int,
    ) : PregnancyTriggerResult()

    /**
     * 同意，但当前不在排卵期。本次不怀孕，无副作用。
     */
    data class WrongPhase(
        val characterId: Int,
        val currentPhase: CyclePhase,
    ) : PregnancyTriggerResult()

    /**
     * 同意 + 排卵期 + 概率命中，怀孕开始。
     * PregnancyTriggerManager 已调用 startPregnancy()，调用方无需再写入。
     * desireStrength / emotionalSuppression 归零，consecutiveFailCount 归零。
     */
    data class Triggered(
        val characterId: Int,
    ) : PregnancyTriggerResult()

    /**
     * D2.5：同意 + 排卵期 + 概率**未**命中，本次尝试失败。
     *
     * 失败后：
     * - consecutiveFailCount + 1
     * - 额外压力增量（按次数梯度，乘以 pressureScale）叠加到 desireStrength / emotionalSuppression
     * - 即时注入一次失落感 Prompt（无门控，当次对话）
     *
     * [consecutiveFailCount] 失败后的新计数（已 +1）
     * [newDesireStrength] / [newEmotionalSuppression] 写入后的最新值
     * [immediatePromptPatch] 即时 Prompt 注入，ChatViewModel 追加到当次 AI 调用
     */
    data class FertileButFailed(
        val characterId: Int,
        val consecutiveFailCount: Int,
        val newDesireStrength: Int,
        val newEmotionalSuppression: Int,
        val immediatePromptPatch: String,
    ) : PregnancyTriggerResult()

    /**
     * 突破事件 A：desireStrength ≥ 80。
     * 渴望积累到临界，绕过排卵期直接提出，不接受模糊回复。
     * 触发后重置：desireStrength → 50 / emotionalSuppression → 40
     */
    data class BreakthroughA(
        val characterId: Int,
        val promptPatch: String,
        val bypassCycleCheck: Boolean = true,
    ) : PregnancyTriggerResult()

    /**
     * 突破事件 B：emotionalSuppression ≥ 80 且 desireStrength < 80。
     * 压抑到极限，沉默反转。周期判定照常进行。
     * 触发后重置：emotionalSuppression → 45（desireStrength 不变）
     */
    data class BreakthroughB(
        val characterId: Int,
        val promptPatch: String,
        val currentPhase: CyclePhase,
    ) : PregnancyTriggerResult()

    /**
     * D2.6：用户主动触发流产（叙事上是流产，不是删除操作）。
     *
     * 触发后：
     * - isPregnant → false，miscarriedAt 记录流产时间戳
     * - CharacterStateLayer 写入情绪副作用（SAD / LONELY / intensity 80 / fatigue 70 ...）
     * - 即时 Prompt 注入一次流产当次对话文案（无门控）
     * - 跨周期 5 天内悲伤余波注入（四重门控，同失败门控机制）
     *
     * [pregnancyDay] 流产时处于第几天，影响情绪强度（已由调用方计算）
     * [immediatePromptPatch] 当次对话直接注入的流产 Prompt 文案
     */
    data class Miscarried(
        val characterId: Int,
        val pregnancyDay: Int,
        val immediatePromptPatch: String,
    ) : PregnancyTriggerResult()
}
