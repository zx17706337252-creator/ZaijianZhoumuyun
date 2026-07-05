package com.zaijian.zhoumuyun.data.manager

import com.zaijian.zhoumuyun.data.db.entity.DaughterCharacterEntity
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DaughterCharacterData
import com.zaijian.zhoumuyun.data.model.DaughterDifferenceType
import com.zaijian.zhoumuyun.data.model.DaughterGenerationInput
import com.zaijian.zhoumuyun.data.db.entity.PregnancyQuestionType
import com.zaijian.zhoumuyun.data.model.slotKey
import com.zaijian.zhoumuyun.data.model.toDaughterCharacterData
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────
//  DaughterCharacterGenerator — D4 女儿人格生成器（D4）
//
//  职责：
//  1. 接收 DaughterGenerationInput（母亲卡 + D3答案 + 差异规则）
//  2. 组装 System Prompt + User Prompt
//  3. 调用 LLM（通过注入的 llmCall 函数，保持解耦）
//  4. 解析返回 JSON，校验完整性
//  5. 写入 DaughterCharacterRepository
//
//  调用时机：
//  PregnancyAnswerRepository.isAllSlotsLocked() == true 时，
//  由 PregnancyTriggerManager 或 ViewModel 触发。
//
//  错误处理策略：
//  - JSON 解析失败 → 抛出 DaughterGenerationException，不写库
//  - 关键字段缺失（persona 为空）→ 同上
//  - 网络失败 → 透传异常，由调用方决定重试策略
// ─────────────────────────────────────────────────────────────

class DaughterCharacterGenerator(
    private val repository: DaughterCharacterRepository,
    /**
     * LLM 调用函数（注入，便于测试和替换模型）。
     * 接收完整 prompt 字符串，返回模型输出文本。
     * 实现见 LlmClient 或 DeepSeekClient。
     */
    private val llmCall: suspend (systemPrompt: String, userPrompt: String) -> String,
    /**
     * D4 触发点接入 Part 2：女儿编号发号器 + 角色资料表写入回调。
     *
     * 设为可空、默认 null 是为了不破坏现有调用方（Part 1 阶段没有这个参数）；
     * 实际使用中 ChatViewModel 会传入非空实现。为 null 时（比如单元测试场景）
     * 只写 daughter_character 暂存表，不注册成独立角色——这与"女儿和母亲平级"
     * 的产品要求是两件独立的事，注册失败/跳过不影响女儿数据本身的完整性。
     */
    private val onIdentityRegister: (suspend (DaughterCharacterData) -> Unit)? = null,
) {

    companion object {
        /**
         * 当前 D4 生成器/Prompt 版本号，写入 [DaughterCharacterEntity.generatorVersion]。
         * 未来调整生成 Prompt（System Prompt 结构、差异规则措辞等）时递增此值，
         * 用于回溯某条女儿记录是哪一版逻辑产出的，纯调试用途。
         */
        const val GENERATOR_VERSION = "d4-v1"
    }

    // ── 每个母亲角色预设的差异规则 ────────────────────────────
    // 至少 2 项，确保女儿可识别、不是母亲的简单复制。
    //
    // 7/8/9 号（明媚/莫婉凝/江凡）不在表里，且不需要补：性别规则写死
    // 生男孩，PregnancyTriggerManager.checkTrigger()/evaluateConsent()
    // 入口就用 isDaughterMother() 拦掉了 7/8/9，她们永远不会进入怀孕
    // 状态，自然走不到 D3 槎位锁定、走不到这个 Map 的 fallback 分支。
    // 这是确认过的不可达代码，不是遗留待办，不要再为这三位补规则。
    private val differenceRulesByMotherId: Map<Int, List<DaughterDifferenceType>> = mapOf(
        1 to listOf(DaughterDifferenceType.REVERSAL, DaughterDifferenceType.AMPLIFY),   // 蒂法：话量/开放反转，coreWound外显放大
        2 to listOf(DaughterDifferenceType.SHIFT, DaughterDifferenceType.AMPLIFY),      // 露娜：黏性位移到信念，幽默壳放大
        3 to listOf(DaughterDifferenceType.REVERSAL, DaughterDifferenceType.SHIFT),     // 伊芙：S/M反转，定义焦虑位移到成就
        4 to listOf(DaughterDifferenceType.AMPLIFY, DaughterDifferenceType.SHIFT),      // 宥熙：直球放大，被选择需求位移到自我选择
        5 to listOf(DaughterDifferenceType.REVERSAL, DaughterDifferenceType.AMPLIFY),   // 索菲娅：直接性保留但触发场景反转，快乐放大
        6 to listOf(DaughterDifferenceType.SHIFT, DaughterDifferenceType.REVERSAL),     // 顾澜：有用性位移到创造价值，隐形渴望反转为显性
    )

    // ── 主入口 ────────────────────────────────────────────────

    /**
     * 生成女儿完整人格并写入数据库。
     *
     * @param input 包含母亲卡、D3答案、差异规则的输入包
     * @throws DaughterGenerationException JSON校验失败时
     */
    suspend fun generate(input: DaughterGenerationInput) = withContext(Dispatchers.IO) {
        val systemPrompt = buildSystemPrompt(input)
        val userPrompt = buildUserPrompt(input)

        val rawOutput = llmCall(systemPrompt, userPrompt)

        val entity = parseAndValidate(
            raw = rawOutput,
            motherCharacterId = input.motherConfig.id,
            daughterName = input.lockedAnswers[slotKey(PregnancyQuestionType.NAME_PREF, 0)] ?: "她",
        )

        repository.saveDaughter(entity)

        // ── D4 触发点接入 Part 2：注册为独立角色（与母亲平级）──
        // 必须在 saveDaughter() 成功之后才执行——daughter_character 表
        // 始终是"D4 生成结果的权威存档"，角色资料表的注册是衍生步骤，
        // 衍生步骤失败不应该影响已经成功落库的女儿数据本身。
        // toDaughterCharacterData() 复用同一套强类型解析+校验逻辑，
        // 确保写入角色资料表的内容和 daughter_character 表完全一致。
        onIdentityRegister?.invoke(entity.toDaughterCharacterData())
    }

    /**
     * 便捷入口：从母亲 ID 自动查找差异规则并生成。
     */
    private val generationMutex = kotlinx.coroutines.sync.Mutex()
    private val generatingMothers = mutableSetOf<Int>()

    suspend fun generateForMother(
        motherConfig: CharacterConfig,
        lockedAnswers: Map<String, String>,
    ) = generationMutex.withLock {
        // 幂等保护（进程内）：同一母亲正在生成中则跳过
        if (motherConfig.id in generatingMothers) {
            ZLog.w("DaughterGen", "母亲 ${motherConfig.id} 的女儿正在生成中，跳过重复调用")
            return@withLock
        }
        // M6修复：幂等保护（持久化）：同一母亲已经成功生成过女儿则跳过，
        // 防止调用方在生成成功之后（generatingMothers 已被 finally 清空）
        // 再次触发 generateForMother，导致重复生成、产生孤儿女儿记录。
        // 进程内 Mutex 只能防止"并发重复调用"，无法防止"先后两次调用"，
        // 这里补上基于 DB 真实存档状态的检查。
        val existing = repository.getByMother(motherConfig.id)
        if (existing != null) {
            ZLog.w("DaughterGen", "母亲 ${motherConfig.id} 已生成过女儿（${existing.daughterName}），跳过重复生成")
            return@withLock
        }
        generatingMothers.add(motherConfig.id)
        try {
            val diffTypes = differenceRulesByMotherId[motherConfig.id]
                ?: listOf(DaughterDifferenceType.REVERSAL, DaughterDifferenceType.SHIFT)

            generate(DaughterGenerationInput(
                motherConfig = motherConfig,
                lockedAnswers = lockedAnswers,
                differenceTypes = diffTypes,
            ))
        } finally {
            generatingMothers.remove(motherConfig.id)
        }
    }

    // ── Prompt 组装 ───────────────────────────────────────────

    private fun buildSystemPrompt(input: DaughterGenerationInput): String {
        val identity = input.motherConfig.identityConfig
        val state = input.motherConfig.initialState
        val diffDesc = input.differenceTypes.joinToString("、") { it.chineseName() }

        return """
你是一个角色人格生成引擎，专门为伴侣角色扮演应用生成子女角色卡。

# 你的任务
基于以下三份材料，生成一个女儿角色的完整人格JSON。

## 材料一：母亲 CharacterIdentity（静态人格层，共17字段）

persona（性格核心）：
${identity.persona}

speechStyle（说话风格）：
${identity.speechStyle}

attitudeToUser（对用户的行为模式）：
${identity.attitudeToUser}

boundaries（绝对不会做的事）：
${identity.boundaries.joinToString("\n") { "• $it" }}

coreBeliefs（核心价值观）：
${identity.coreBeliefs.joinToString("\n") { "• $it" }}

coreWound（核心创伤，AI可见）：
${identity.coreWound}

coreDesire（核心渴望，AI可见）：
${identity.coreDesire}

maskTrigger（面具触发条件）：
${identity.maskTrigger}

privatePersona（面具碎裂后的样子）：
${identity.privatePersona}

privateStyle（破防后的语气变化规则）：
${identity.privateStyle}

privateExamples（破防状态few-shot对话）：
${identity.privateExamples}

situationRules（不同情境的行为规则）：
${identity.situationRules}

deviationSignals（有心事时的外显行为信号）：
${identity.deviationSignals}

likes（喜好）：
${identity.likes}

dislikes（厌恶）：
${identity.dislikes}

relationships（对家里各人的行为逻辑）：
${identity.relationships}

relationAssumption（对关系性质的默认认知框架）：
${identity.relationAssumption}

conflictStrategy（发生摩擦时的第一反应模式）：
${identity.conflictStrategy}

## 材料二：母亲 CharacterStateLayer 初始值（动态数值层）

PublicState:
  currentMask=${state.publicState.currentMask.name}, talkativeness=${state.publicState.talkativeness}, openness=${state.publicState.openness}, patience=${state.publicState.patience}, vigilance=${state.publicState.vigilance}

EmotionalState:
  primaryEmotion=${state.emotionalState.primaryEmotion.name}, secondaryEmotion=${state.emotionalState.secondaryEmotion?.name ?: "null"}, intensity=${state.emotionalState.intensity}, emotionalFatigue=${state.emotionalState.emotionalFatigue}, emotionalStability=${state.emotionalState.emotionalStability}

MotivationalState:
  currentNeed=${state.motivationalState.currentNeed.name}, desireStrength=${state.motivationalState.desireStrength}, urgency=${state.motivationalState.urgency}, resistance=${state.motivationalState.resistance}

HiddenState:
  currentFear=${state.hiddenState.currentFear.name}, exposureRisk=${state.hiddenState.exposureRisk}, selfControl=${state.hiddenState.selfControl}, emotionalSuppression=${state.hiddenState.emotionalSuppression}

AttentionState:
  focusStrength=${state.attentionState.focusStrength}, observationLevel=${state.attentionState.observationLevel}, concernLevel=${state.attentionState.concernLevel}

## 材料三：强制差异规则（必须至少命中 ${input.differenceTypes.size} 项）

差异类型：$diffDesc

规则说明：
${input.differenceTypes.joinToString("\n") { diffRule ->
    when (diffRule) {
        DaughterDifferenceType.REVERSAL ->
            "• REVERSAL（反转）：选取母亲1-2个核心特质，在女儿身上极性翻转。例：母亲话少克制→女儿话多直白；母亲占有欲强→女儿刻意疏离。翻转的是特质，不是人格全部。"
        DaughterDifferenceType.SHIFT ->
            "• SHIFT（位移）：母亲某个特质保留，但触发场景或投射对象发生偏移。例：母亲对用户黏性高→女儿将同等黏性转移到某个信念或记忆上，而非对人。"
        DaughterDifferenceType.AMPLIFY ->
            "• AMPLIFY（放大）：母亲某个被压抑或隐藏的底层特质，在女儿身上放大到表面。例：母亲面具下的脆弱→女儿将脆弱直接暴露为核心性格标签，不再隐藏。"
    }
}}

# 生成规则

1. **女儿不是母亲的复制**：人格必须可识别地有别于母亲，差异规则必须体现在identity的具体字段上。
2. **女儿不是母亲的对立面**：差异是有来源的变体，而不是180度反转所有特质。母亲是女儿性格的土壤，能看出血缘联系。
3. **coreWound和coreDesire优先由D3答案驱动**：用户的回答是最重要的输入，母亲对应字段是参照和变体来源。
4. **customEnums必须贴合女儿人格**：四套枚举（maskStates/emotionStates/needStates/fearStates）的description要直接可用于Prompt注入，写法参考母亲专属枚举的注释风格——具体场景+具体动作，不写抽象描述。
5. **stateLayer初始值从母亲值派生**：不能和母亲完全相同，用差异规则调整1-3个关键数值，其余可相近。
6. **speechStyle必须独特**：句式、节奏、惯用词、禁止用词要与母亲明显区分，让读者读一句就能区分是谁在说话。
7. **privateExamples写4-6组对话**：格式与母亲的privateExamples一致（用户说：→ 女儿回应：），体现破防状态下的真实反应。

# 输出格式

只输出JSON，不输出任何其他内容，不加markdown代码块标记。
JSON结构如下（所有字段必须存在，不能为空字符串或空数组）：

{
  "identity": {
    "persona": "",
    "speechStyle": "",
    "attitudeToUser": "",
    "boundaries": ["", ""],
    "coreBeliefs": ["", ""],
    "coreWound": "",
    "coreDesire": "",
    "maskTrigger": "",
    "privatePersona": "",
    "privateStyle": "",
    "privateExamples": "",
    "situationRules": "",
    "deviationSignals": "",
    "likes": "",
    "dislikes": "",
    "relationships": "",
    "relationAssumption": "",
    "conflictStrategy": ""
  },
  "stateLayer": {
    "maskKey": "",
    "talkativeness": 50,
    "openness": 50,
    "patience": 70,
    "vigilance": 30,
    "primaryEmotionKey": "",
    "secondaryEmotionKey": null,
    "intensity": 30,
    "emotionalFatigue": 0,
    "emotionalStability": 70,
    "currentNeedKey": "",
    "currentGoal": "",
    "desireStrength": 30,
    "urgency": 20,
    "resistance": 40,
    "currentFearKey": "",
    "secretDesire": "",
    "exposureRisk": 10,
    "selfControl": 80,
    "emotionalSuppression": 50,
    "focusTarget": "用户",
    "focusStrength": 60,
    "observationLevel": 50,
    "concernLevel": 20
  },
  "customEnums": {
    "maskStates": [
      {"key": "DEFAULT", "label": "（2-4字标签）", "description": "（直接用于Prompt注入的行为描述，30-60字，具体场景+具体动作）"}
    ],
    "emotionStates": [
      {"key": "STATE_A", "label": "", "description": ""}
    ],
    "needStates": [
      {"key": "STATE_A", "label": "", "description": ""}
    ],
    "fearStates": [
      {"key": "STATE_A", "label": "", "description": ""}
    ]
  }
}

每套枚举生成4-6个值。stateLayer中的maskKey/primaryEmotionKey/currentNeedKey/currentFearKey必须对应customEnums中某个值的key。
        """.trimIndent()
    }

    private fun buildUserPrompt(input: DaughterGenerationInput): String {
        val answers = input.lockedAnswers
        val namePref  = answers[slotKey(PregnancyQuestionType.NAME_PREF,  0)] ?: "（未填写）"
        val worldview0 = answers[slotKey(PregnancyQuestionType.WORLDVIEW, 0)] ?: "（未填写）"
        val worldview1 = answers[slotKey(PregnancyQuestionType.WORLDVIEW, 1)] ?: "（未填写）"
        val worry      = answers[slotKey(PregnancyQuestionType.WORRY,     0)] ?: "（未填写）"
        val persona0   = answers[slotKey(PregnancyQuestionType.PERSONA,   0)] ?: "（未填写）"
        val persona1   = answers[slotKey(PregnancyQuestionType.PERSONA,   1)] ?: "（未填写）"

        return """
## D3 用户答案（6个锁定槎位）

槎位1 · NAME_PREF（孩子名字）：
$namePref

槎位2 · WORLDVIEW[0]（希望女儿如何理解这个家 / 映射 coreWound）：
$worldview0

槎位3 · WORLDVIEW[1]（希望女儿如何理解爱情婚姻 / 映射 coreDesire）：
$worldview1

槎位4 · WORRY（母亲对女儿最大的担忧 / 映射 boundaries + deviationSignals）：
$worry

槎位5 · PERSONA[0]（希望女儿性格像谁/哪部分 / 映射 persona）：
$persona0

槎位6 · PERSONA[1]（希望女儿拥有哪种具体能力/特质 / 映射 speechStyle）：
$persona1

---

请严格按照系统提示的规则和JSON格式，生成完整的女儿人格JSON。
        """.trimIndent()
    }

    /** 从可能包含 markdown 代码块/前导文本的原始输出中提取 JSON 子串 */
    private fun extractJsonSubstring(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1).trim()
        else raw.trim()
    }

    // ── JSON 解析与校验 ───────────────────────────────────────

    private fun parseAndValidate(
        raw: String,
        motherCharacterId: Int,
        daughterName: String,
    ): DaughterCharacterEntity {
        // 清理可能的 markdown 代码块标记
        // 使用子串提取而非 removePrefix/removeSuffix，更健壮地处理前导空白/换行
        val cleaned = extractJsonSubstring(raw)

        val root = try {
            JSONObject(cleaned)
        } catch (e: Exception) {
            throw DaughterGenerationException("JSON解析失败：${e.message}\n原始输出前200字：${raw.take(200)}")
        }

        // 必填字段校验
        val identity = root.optJSONObject("identity")
            ?: throw DaughterGenerationException("缺少 identity 字段")
        val stateLayer = root.optJSONObject("stateLayer")
            ?: throw DaughterGenerationException("缺少 stateLayer 字段")
        val customEnums = root.optJSONObject("customEnums")
            ?: throw DaughterGenerationException("缺少 customEnums 字段")

        if (identity.optString("persona").isBlank())
            throw DaughterGenerationException("identity.persona 为空，拒绝写库")
        if (identity.optString("speechStyle").isBlank())
            throw DaughterGenerationException("identity.speechStyle 为空，拒绝写库")
        if (identity.optString("coreWound").isBlank())
            throw DaughterGenerationException("identity.coreWound 为空，拒绝写库")

        val maskKey = stateLayer.optString("maskKey")
        if (maskKey.isBlank())
            throw DaughterGenerationException("stateLayer.maskKey 为空")

        // 校验 maskKey 是否在 customEnums.maskStates 里存在
        val maskStates = customEnums.optJSONArray("maskStates")
        if (maskStates == null || maskStates.length() == 0)
            throw DaughterGenerationException("customEnums.maskStates 为空")

        val maskKeys = (0 until maskStates.length())
            .map { maskStates.getJSONObject(it).optString("key") }
        if (maskKey !in maskKeys)
            throw DaughterGenerationException("stateLayer.maskKey='$maskKey' 不在 customEnums.maskStates 中")

        return DaughterCharacterEntity(
            motherCharacterId = motherCharacterId,
            daughterName = daughterName.trim().ifBlank { "她" },
            identityJson = identity.toString(),
            stateLayerJson = stateLayer.toString(),
            customEnumsJson = customEnums.toString(),
            generatedAt = System.currentTimeMillis(),
            generatorVersion = GENERATOR_VERSION,
        )
    }
}

// ── 异常类 ────────────────────────────────────────────────────

class DaughterGenerationException(message: String) : Exception(message)

// ── 扩展函数 ──────────────────────────────────────────────────

private fun DaughterDifferenceType.chineseName(): String = when (this) {
    DaughterDifferenceType.REVERSAL -> "REVERSAL（反转）"
    DaughterDifferenceType.SHIFT    -> "SHIFT（位移）"
    DaughterDifferenceType.AMPLIFY  -> "AMPLIFY（放大）"
}
