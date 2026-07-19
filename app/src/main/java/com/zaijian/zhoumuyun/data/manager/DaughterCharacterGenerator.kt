package com.zaijian.zhoumuyun.data.manager

import com.zaijian.zhoumuyun.data.db.entity.DaughterCharacterEntity
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DaughterCharacterData
import com.zaijian.zhoumuyun.data.model.DaughterCustomEnums
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.data.model.DaughterDifferenceType
import com.zaijian.zhoumuyun.data.model.DaughterGenerationInput
import com.zaijian.zhoumuyun.data.model.DaughterStateLayer
import com.zaijian.zhoumuyun.data.db.entity.PregnancyQuestionType
import com.zaijian.zhoumuyun.data.model.slotKey
import com.zaijian.zhoumuyun.data.model.toDaughterCharacterData
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import kotlinx.coroutines.CancellationException
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
     * 改为必填（原为可空、默认 null，Part 1 阶段遗留）：全项目搜索确认唯一
     * 实例化调用方 ChatViewModel 一直传入非空实现，且没有任何测试代码依赖
     * 可空默认值。保留可空默认值只会带来风险——未来任何新调用方如果忘记传
     * 这个参数，"女儿和母亲平级"这一步会被静默跳过且没有任何警告或报错，
     * 只有 daughter_character 暂存表数据，女儿不会出现在角色列表里，
     * 很难定位。改为必填后，遗漏会在编译期直接报错，而不是运行期静默失败。
     */
    private val onIdentityRegister: suspend (DaughterCharacterData) -> Unit,
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
        //
        // 方案 8-7：onIdentityRegister 失败时回滚已保存的 daughter_character 暂存行，
        // 避免 getByMother() 查到半成品行阻止重试，产生永久孤儿数据。
        try {
            onIdentityRegister(entity.toDaughterCharacterData())
        } catch (e: CancellationException) {
            // 保留回滚但不包装异常，传播取消信号以维护结构化并发
            repository.deleteByMother(input.motherConfig.id)
            throw e
        } catch (e: Exception) {
            repository.deleteByMother(input.motherConfig.id)
            throw DaughterGenerationException(
                "女儿角色注册失败，已回滚 daughter_character 暂存数据: ${e.message}"
            )
        }
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
        // 审查报告问题10修复：第三代封顶防御性校验。
        //
        // 此前"不生成第四代"这条规则只在调用方（ChatViewModel）的各个触发点
        // 用 daughterRepo.isThirdGeneration() 分别判断后才决定要不要调 
        // generateForMother()——即只有"外部守门"，生成器自身对"我是否正在
        // 被要求生成第四代"一无所知。ChatViewModel.retryDaughterGeneration()
        // （用户手动重试入口）此前就完全没有做这个判断，直接从
        // _uiState.value.character 取当前角色后调用本方法，如果当前角色恰好
        // 是第三代女儿且此前生成失败过，重试会尝试为第三代生成第四代。
        //
        // 这里补上生成器内部的最后一道防线：仅当传入的 motherConfig 本身也是
        // 一位女儿（id >= 1000）时才需要判断——原生角色（1-9）不可能触发这个
        // 分支，isThirdGeneration() 对非女儿 id 也没有意义。判断结果为真时
        // 直接跳过生成（不抛异常，与上面"已生成过""正在生成中"两个既有跳过
        // 分支保持一致的静默跳过 + 日志记录风格，避免把内部防御性校验的失败
        // 误当成真正的生成失败展示给用户）。
        if (motherConfig.id >= 1000 && repository.isThirdGeneration(motherConfig.id)) {
            ZLog.w(
                "DaughterGen",
                "母亲 ${motherConfig.id} 本身已是第三代女儿，家族传承固定三代封顶，跳过第四代生成",
            )
            return@withLock
        }
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
        // W6-02 修复：gender 字段填充。
        // 已与产品侧确认：本系统不存在"性别可选"设计，女儿系统默认全部是女儿，
        // 不需要从 NAME_PREF（实际是自由文本的"孩子叫什么名字"偏好，不是
        // 结构化的性别信号）里做任何提取或推断。
        // 这里填入的是代际称呼词本身（与 FamilyScreen.kt 的
        // `member.kinshipTerm ?: "孩子"` 直接当展示文案使用的方式对齐），
        // 判断依据是 motherCharacterId 是否 >= 1000——与
        // DaughterCharacterGenerator.generateForMother() 的第三代封顶校验、
        // DaughterCharacterRepository.isThirdGeneration() 用的是同一套
        // 既有代际判断惯例：母亲本身是女儿（id>=1000）→ 这次生成的是孙女（第三代），
        // 母亲是原生角色（1-9号）→ 这次生成的是女儿（第二代）。
        val genderLabel = if (motherCharacterId >= 1000) "孙女" else "女儿"

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

        // 复核修复：此前只校验了 maskKey，遗漏了 primaryEmotionKey/currentNeedKey/
        // currentFearKey 三个同类字段。这三个字段与 maskKey 地位完全一致——都是
        // 女儿层注入时查 customEnums 的索引，读库端 DaughterStateLayer.fromJson()
        // 早就对这四个字段一视同仁地做非空校验，写库端却只挑了其中一个，
        // 导致 LLM 生成的坏数据能绕过写库校验、直到读库时才第一次被拦截。
        // 现补齐，让写库端和读库端的校验范围完全一致。
        val primaryEmotionKey = stateLayer.optString("primaryEmotionKey")
        if (primaryEmotionKey.isBlank())
            throw DaughterGenerationException("stateLayer.primaryEmotionKey 为空")

        val currentNeedKey = stateLayer.optString("currentNeedKey")
        if (currentNeedKey.isBlank())
            throw DaughterGenerationException("stateLayer.currentNeedKey 为空")

        val currentFearKey = stateLayer.optString("currentFearKey")
        if (currentFearKey.isBlank())
            throw DaughterGenerationException("stateLayer.currentFearKey 为空")

        // P3-35 修复：复用统一校验方法 customEnums.validateStateLayerKeys()，
        // 替代原先三套独立的手动校验逻辑（maskStates/emotionStates/needStates/fearStates）。
        val customEnumsObj = DaughterCustomEnums.fromJson(customEnums)
        val stateLayerObj = DaughterStateLayer(
            maskKey = maskKey,
            primaryEmotionKey = primaryEmotionKey,
            currentNeedKey = currentNeedKey,
            currentFearKey = currentFearKey,
        )
        try {
            customEnumsObj.validateStateLayerKeys(stateLayerObj)
        } catch (e: DaughterDataException) {
            throw DaughterGenerationException(e.message ?: "校验失败")
        }

        return DaughterCharacterEntity(
            motherCharacterId = motherCharacterId,
            daughterName = daughterName.trim().ifBlank { "她" },
            identityJson = identity.toString(),
            stateLayerJson = stateLayer.toString(),
            customEnumsJson = customEnums.toString(),
            generatedAt = System.currentTimeMillis(),
            generatorVersion = GENERATOR_VERSION,
            kinshipTerm = genderLabel,
        )
    }
}

// ── 异常类 ────────────────────────────────────────────────────

class DaughterGenerationException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ── 扩展函数 ──────────────────────────────────────────────────

private fun DaughterDifferenceType.chineseName(): String = when (this) {
    DaughterDifferenceType.REVERSAL -> "REVERSAL（反转）"
    DaughterDifferenceType.SHIFT    -> "SHIFT（位移）"
    DaughterDifferenceType.AMPLIFY  -> "AMPLIFY（放大）"
}
