package com.zaijian.zhoumuyun.data.prompt

import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.DaughterCustomEnums
import com.zaijian.zhoumuyun.data.model.DaughterStateLayer
import com.zaijian.zhoumuyun.data.model.toCharacterFearDescription
import com.zaijian.zhoumuyun.data.model.toCharacterMaskDescription
import com.zaijian.zhoumuyun.data.model.toCharacterNeedDescription
import com.zaijian.zhoumuyun.data.model.toChineseDescription

/**
 * State Layer 构建器（从 PromptOrchestrator 拆出，沿用 object 单例写法）。
 *
 * 从 PromptOrchestrator.kt 迁移的 State 类零外部依赖函数：
 * buildStateBlock / buildCharacterStateBlock。
 */
object StatePromptBuilder {

    fun buildStateBlock(
        activity: String,
        focus: String,
        mood: String,
        energy: Int,
        relationshipSnapshot: String,
        interCharRelBlock: String = "",  // Phase 3：圆桌专用，角色间关系快照
        characterState: CharacterStateLayer? = null,  // 深层状态（desireStrength/emotionalSuppression等）
        characterId: Int = 0,            // 用于角色专属枚举描述（StateExtensions）
        daughterStateLayer: DaughterStateLayer? = null,
        daughterCustomEnums: DaughterCustomEnums? = null,
    ): String {
        val hasPresence = activity.isNotEmpty() || focus.isNotEmpty() || mood.isNotEmpty() || energy >= 0
        val hasRelationship = relationshipSnapshot.isNotEmpty()
        val hasInterChar    = interCharRelBlock.isNotEmpty()
        val hiddenStateText = buildCharacterStateBlock(characterState, characterId, daughterStateLayer, daughterCustomEnums)
        val hasHiddenState  = hiddenStateText.isNotEmpty()
        if (!hasPresence && !hasRelationship && !hasInterChar && !hasHiddenState) return ""

        return buildString {
            if (hasPresence) {
                if (activity.isNotEmpty()) appendLine("当前状态：$activity")
                if (focus.isNotEmpty())    appendLine("关注：$focus")
                val moodEnergy = buildString {
                    if (mood.isNotEmpty())  append("情绪：$mood")
                    if (energy >= 0) { if (mood.isNotEmpty()) append("，"); append("精力：$energy/100") }
                }
                if (moodEnergy.isNotEmpty()) appendLine(moodEnergy)
            }
            if (hasHiddenState) {
                if (hasPresence) appendLine()
                append(hiddenStateText)
            }
            if (hasRelationship) {
                if (hasPresence || hasHiddenState) appendLine()
                append(relationshipSnapshot)
            }
            if (hasInterChar) {
                appendLine()
                appendLine()
                append(interCharRelBlock)
            }
        }.trimEnd()
    }

    /**
     * 将 CharacterStateLayer 的全部有指导意义的字段格式化为 Prompt 文字。
     *
     * 恢复旧版完整渲染深度，注入五个维度：
     *   1. 面具模式（currentMask）+ 社交场景（socialMode）— 影响"怎么说话/和谁说话时什么态度"
     *   2. 话量（talkativeness）/ 警觉度（vigilance）/ 耐心（patience）— 影响回应长度与防御性
     *   3. 真实情绪（primaryEmotion + secondaryEmotion + intensity + 疲劳度）— 内心主色调
     *   4. 当下渴望（currentNeed / currentGoal + desireStrength + urgency + resistance）
     *   5. 深层隐藏（currentFear + secretDesire + emotionalSuppression + exposureRisk）
     *      + isMaskNearBreaking 衍生结论
     *
     * 枚举值通过 StateExtensions 的 toCharacterXxxDescription(characterId) 翻译为
     * 角色专属具体句，前四位女主（1-4）有完整专属描述，其余 fallback 到通用中文。
     *
     * characterState 为 null 时返回空字符串，零开销。
     * characterId 为 0（默认值）时退化到通用描述，不崩溃。
     */
    fun buildCharacterStateBlock(
        characterState: CharacterStateLayer?,
        characterId: Int = 0,
        daughterStateLayer: DaughterStateLayer? = null,
        daughterCustomEnums: DaughterCustomEnums? = null,
    ): String {
        if (characterState == null) return ""
        val pub = characterState.publicState
        val emo = characterState.emotionalState
        val mot = characterState.motivationalState
        val hid = characterState.hiddenState
        val att = characterState.attentionState

        // 女儿专属枚举查找结果（复核修复 #7/#13）：非女儿角色或 daughterStateLayer/
        // daughterCustomEnums 任一为 null 时，四个查找结果均为 null，下面的 ?:
        // 兜底表达式会退回 StateExtensions 的通用/角色专属枚举翻译，行为与修复前一致，
        // 不影响母亲角色（1-9号）任何现有输出。
        val daughterMaskDesc = daughterStateLayer?.let { sl -> daughterCustomEnums?.findMask(sl.maskKey)?.description }
        val daughterEmotionDesc = daughterStateLayer?.let { sl -> daughterCustomEnums?.findEmotion(sl.primaryEmotionKey)?.description }
        val daughterSecondaryEmotionDesc = daughterStateLayer?.secondaryEmotionKey?.let { key -> daughterCustomEnums?.findEmotion(key)?.description }
        val daughterNeedDesc = daughterStateLayer?.let { sl -> daughterCustomEnums?.findNeed(sl.currentNeedKey)?.description }
        val daughterFearDesc = daughterStateLayer?.let { sl -> daughterCustomEnums?.findFear(sl.currentFearKey)?.description }

        return buildString {
            appendLine("[角色当前状态 — 仅供你参考，绝不可直接说出口]")

            // ── 1. 面具 & 社交场景 ─────────────────────────────
            appendLine("面具：${daughterMaskDesc ?: pub.currentMask.toCharacterMaskDescription(characterId)}")
            appendLine("场景：${pub.socialMode.toChineseDescription(characterId)}")

            // ── 2. 行为倾向数值 ────────────────────────────────
            val talkDesc = when {
                pub.talkativeness >= 75 -> "话多，主动"
                pub.talkativeness >= 50 -> "正常"
                pub.talkativeness >= 25 -> "话少，被动"
                else                    -> "几乎沉默"
            }
            val patienceDesc = when {
                pub.patience >= 75 -> "极度耐心"
                pub.patience >= 50 -> "耐心尚可"
                pub.patience >= 25 -> "耐心将尽"
                else               -> "已经不耐烦"
            }
            val vigilanceDesc = when {
                pub.vigilance >= 75 -> "高度设防，每句话都在量距离"
                pub.vigilance >= 50 -> "有防备"
                pub.vigilance >= 25 -> "较为放松"
                else                -> "完全没有防备"
            }
            appendLine("话量 ${pub.talkativeness}/100（$talkDesc）｜耐心 ${pub.patience}/100（$patienceDesc）｜警觉 ${pub.vigilance}/100（$vigilanceDesc）")

            // ── 3. 真实情绪 ────────────────────────────────────
            appendLine()
            val primaryDesc = daughterEmotionDesc ?: emo.primaryEmotion.toChineseDescription()
            val intensityTag = when {
                emo.intensity >= 80 -> "极强"
                emo.intensity >= 60 -> "较强"
                emo.intensity >= 40 -> "中等"
                emo.intensity >= 20 -> "轻微"
                else                -> "几乎感知不到"
            }
            val secondaryPart = emo.secondaryEmotion
                ?.let { daughterSecondaryEmotionDesc ?: it.toChineseDescription() }
                ?.let { "，次情绪：$it" }
                ?: ""
            append("真实情绪：$primaryDesc（${emo.intensity}/100，$intensityTag）$secondaryPart")
            if (emo.emotionalFatigue > 0) {
                append("｜情绪疲劳 ${emo.emotionalFatigue}/100")
                if (emo.emotionalFatigue > 60) append("（已很难被新刺激触动）")
            }
            appendLine()

            // ── 4. 当下渴望 ────────────────────────────────────
            val needDesc = daughterNeedDesc ?: mot.currentNeed.toCharacterNeedDescription(characterId)
            val goalPart = mot.currentGoal.ifBlank { needDesc }
            val urgencyPart = if (mot.urgency > 50) "，急切" else ""
            val resistancePart = if (mot.resistance > 60) "，但她在压制自己" else ""
            appendLine("渴望：$goalPart（强度 ${mot.desireStrength}/100$urgencyPart$resistancePart）")

            // ── 5. 深层隐藏 ────────────────────────────────────
            appendLine()
            append("压抑度：${hid.emotionalSuppression}/100（越高，表面越平静、内部越满）")
            appendLine()
            if (hid.secretDesire.isNotBlank()) {
                appendLine("隐藏渴望：${hid.secretDesire}")
            }

            // ── 6. 面具松动 / 恐惧激活（条件触发）──────────────
            if (characterState.isMaskNearBreaking) {
                appendLine()
                appendLine("注意：面具已接近松动边缘（自控力 ${hid.selfControl}/100，暴露风险 ${hid.exposureRisk}/100）。")
                append("底层恐惧正在驱动反应：${daughterFearDesc ?: hid.currentFear.toCharacterFearDescription(characterId)}")
            }

            // ── 7. 注意力焦点（非默认时才注入）─────────────────
            if (att.focusTarget != "用户" || att.concernLevel > 30) {
                appendLine()
                val concernPart = if (att.concernLevel > 30) "（带着担忧，${att.concernLevel}/100）" else ""
                append("当前关注：${att.focusTarget}$concernPart，专注度 ${att.focusStrength}/100")
            }
        }.trimEnd()
    }
}