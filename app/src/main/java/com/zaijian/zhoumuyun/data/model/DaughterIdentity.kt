package com.zaijian.zhoumuyun.data.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.zaijian.zhoumuyun.data.db.entity.DaughterCharacterEntity
import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────
//  DaughterIdentity.kt — D4 女儿人格强类型层（步骤①）
//
//  背景：DaughterCharacterEntity 三列（identityJson / stateLayerJson /
//  customEnumsJson）目前是裸 JSON 字符串，没有任何强类型访问路径。
//  D4 生成器（DaughterCharacterGenerator）写入时直接拼 JSONObject，
//  消费侧（未来的 PromptOrchestrator 女儿层注入）如果也直接裸解析，
//  字段名、默认值、null 处理会在两处各写一份，容易漂移。
//
//  本文件提供：
//   1. DaughterIdentity      —— 对标 CharacterIdentity（18字段，公开+内核+行为规则）
//   2. DaughterStateLayer    —— 对标 CharacterStateLayer（扁平24字段，
//                                女儿版不嵌套五个子结构，因为 Generator
//                                输出的 stateLayer 本身就是扁平 JSON，
//                                没有必要为了"形似"母亲而引入嵌套）
//   3. DaughterEnumValue / DaughterCustomEnums —— 对标母亲专属枚举
//                                （TifaMask等），但以数据而非编译期枚举存在
//   4. DaughterCharacterData —— 三者 + 名字的聚合，PromptOrchestrator
//                                女儿层接入时的唯一消费入口
//
//  解析失败策略：
//   - identity 的关键字段（persona/speechStyle/coreWound）为空 → 抛出
//     DaughterDataException，不返回残缺对象。这与 Generator.parseAndValidate()
//     的"拒绝写库"是同一防御性原则的另一端——写入时校验过，读取时不能假设
//     数据库里的内容永远完好（人工改过库 / 旧版本写入的脏数据都要拦住）。
//   - stateLayer 的数值字段缺失 → fallback 到与母亲 CharacterStateLayer
//     完全相同的默认值（50/70/30...），而不是 0，避免女儿初始状态因为
//     字段缺失而表现得不自然。
// ─────────────────────────────────────────────────────────────

/**
 * 女儿版 CharacterIdentity（静态文本层）。
 * 字段名与 [CharacterIdentity] 逐一对齐，便于 PromptOrchestrator
 * 复用同一套 buildIdentityBlock() 注入逻辑（步骤②要做的事）。
 * 唯一差异：没有 customSystemPrompt——女儿不支持完全覆盖 Identity Layer。
 */
data class DaughterIdentity(
    val persona: String = "",
    val speechStyle: String = "",
    val attitudeToUser: String = "",
    val boundaries: List<String> = emptyList(),
    val coreBeliefs: List<String> = emptyList(),

    val coreWound: String = "",
    val coreDesire: String = "",
    val maskTrigger: String = "",
    val privatePersona: String = "",
    val privateStyle: String = "",
    val privateExamples: String = "",
    val situationRules: String = "",
    val deviationSignals: String = "",

    val likes: String = "",
    val dislikes: String = "",
    val relationships: String = "",

    val relationAssumption: String = "",
    val conflictStrategy: String = "",

    // P1-47 修复：新增性别字段，解决 FamilyScreen 代数标签硬编码 "女儿"/"孙女" 的问题。
    // S8-窗口08 新问题01+02修复：此前字段名为 gender，但存的从来不是"男/女"这种性别
    // 符号，而是代际称呼词（"女儿"/"孙女"）——与 DaughterCharacterEntity.kinshipTerm
    // 是同一个语义概念，重命名对齐，消除两处命名不一致。
    // 此前该字段始终为 null：D4 生成器 LLM 输出的 identityJson 从不包含这个字段，
    // toDaughterCharacterData() 也没有从 entity.kinshipTerm 回填。现在
    // toDaughterCharacterData() 已补上回填（见该函数），fromJson() 解析出的值仅在
    // entity 侧未回填的边缘情况（如单独调用 fromJson 而不经过 toDaughterCharacterData）
    // 下使用。JSON key 仍用 "gender"（已持久化的 identityJson 里若存在这个 key，
    // 不因本次重命名破坏兼容；新数据不会再写入这个 key，因为回填改在
    // toDaughterCharacterData() 里做，不经过 toJson()/fromJson() 往返）。
    val kinshipTerm: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("persona", persona)
        put("speechStyle", speechStyle)
        put("attitudeToUser", attitudeToUser)
        put("boundaries", JSONArray(boundaries))
        put("coreBeliefs", JSONArray(coreBeliefs))
        put("coreWound", coreWound)
        put("coreDesire", coreDesire)
        put("maskTrigger", maskTrigger)
        put("privatePersona", privatePersona)
        put("privateStyle", privateStyle)
        put("privateExamples", privateExamples)
        put("situationRules", situationRules)
        put("deviationSignals", deviationSignals)
        put("likes", likes)
        put("dislikes", dislikes)
        put("relationships", relationships)
        put("relationAssumption", relationAssumption)
        put("conflictStrategy", conflictStrategy)
        if (kinshipTerm != null) put("gender", kinshipTerm)
    }

    companion object {
        /**
         * 从 identityJson 列解析。
         * @throws DaughterDataException persona/speechStyle/coreWound 任一为空
         *         （与 Generator.parseAndValidate() 的写库前校验是同一道防线，
         *         这里是读库时的第二道——防止脏数据绕过 Generator 直接进库）。
         */
        fun fromJson(json: JSONObject): DaughterIdentity {
            val persona = json.optString("persona")
            val speechStyle = json.optString("speechStyle")
            val coreWound = json.optString("coreWound")

            if (persona.isBlank()) throw DaughterDataException("DaughterIdentity.persona 为空")
            if (speechStyle.isBlank()) throw DaughterDataException("DaughterIdentity.speechStyle 为空")
            if (coreWound.isBlank()) throw DaughterDataException("DaughterIdentity.coreWound 为空")

            return DaughterIdentity(
                persona = persona,
                speechStyle = speechStyle,
                attitudeToUser = json.optString("attitudeToUser"),
                boundaries = json.optJSONArray("boundaries").toStringList(),
                coreBeliefs = json.optJSONArray("coreBeliefs").toStringList(),
                coreWound = coreWound,
                coreDesire = json.optString("coreDesire"),
                maskTrigger = json.optString("maskTrigger"),
                privatePersona = json.optString("privatePersona"),
                privateStyle = json.optString("privateStyle"),
                privateExamples = json.optString("privateExamples"),
                situationRules = json.optString("situationRules"),
                deviationSignals = json.optString("deviationSignals"),
                likes = json.optString("likes"),
                dislikes = json.optString("dislikes"),
                relationships = json.optString("relationships"),
                relationAssumption = json.optString("relationAssumption"),
                conflictStrategy = json.optString("conflictStrategy"),
                // JSON key 仍是 "gender"（历史命名，见字段声明处注释），只是
                // Kotlin 侧字段名重命名为 kinshipTerm 以对齐 DaughterCharacterEntity。
                kinshipTerm = json.optStringOrNull("gender"),
            )
        }

        /** 便捷重载：直接从字符串解析，JSON 格式错误时同样抛 DaughterDataException。 */
        fun fromJson(raw: String): DaughterIdentity = fromJson(raw.toJSONObjectOrThrow("DaughterIdentity"))
    }
}

/**
 * 女儿版专属枚举的单个取值。
 * 对标母亲代码层 enum class 的单个值 + 其注释——
 * key 对应枚举名，label 是 2-4 字短标签，description 是可直接注入 Prompt 的行为描述。
 */
data class DaughterEnumValue(
    val key: String,
    val label: String,
    val description: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("label", label)
        put("description", description)
    }

    companion object {
        fun fromJson(json: JSONObject): DaughterEnumValue = DaughterEnumValue(
            key = json.optString("key"),
            label = json.optString("label"),
            description = json.optString("description"),
        )
    }
}

/**
 * 女儿专属枚举词库。对标母亲的 TifaMask/TifaEmotion/TifaNeed/TifaFear 四套，
 * 但以运行时数据存在，由 D4 生成器生成，而非编译期 enum class。
 */
data class DaughterCustomEnums(
    val maskStates: List<DaughterEnumValue> = emptyList(),
    val emotionStates: List<DaughterEnumValue> = emptyList(),
    val needStates: List<DaughterEnumValue> = emptyList(),
    val fearStates: List<DaughterEnumValue> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("maskStates", JSONArray(maskStates.map { it.toJson() }))
        put("emotionStates", JSONArray(emotionStates.map { it.toJson() }))
        put("needStates", JSONArray(needStates.map { it.toJson() }))
        put("fearStates", JSONArray(fearStates.map { it.toJson() }))
    }

    /** 按 key 查找四套枚举中的任一值，找不到返回 null（由调用方决定 fallback 文案）。 */
    fun findMask(key: String): DaughterEnumValue? = maskStates.find { it.key == key }
    fun findEmotion(key: String): DaughterEnumValue? = emotionStates.find { it.key == key }
    fun findNeed(key: String): DaughterEnumValue? = needStates.find { it.key == key }
    fun findFear(key: String): DaughterEnumValue? = fearStates.find { it.key == key }

    /**
     * P3-35 修复：三处校验口径统一方法。
     * 校验 stateLayer 的四个索引 key 在 customEnums 的对应数组中是否存在匹配项。
     * 原先 [DaughterCharacterGenerator.parseAndValidate]（写库端）、
     * [DaughterCharacterEntity.toDaughterCharacterData]（读库端）、
     * [DaughterCharacterRepository.updateStateLayer]（状态更新端）各有一份
     * 独立实现，现在统一为该方法。
     *
     * @throws DaughterDataException 任一 key 的对应数组为空或无匹配项
     */
    fun validateStateLayerKeys(stateLayer: DaughterStateLayer) {
        if (findMask(stateLayer.maskKey) == null)
            throw DaughterDataException(
                "stateLayer.maskKey='${stateLayer.maskKey}' 不在 customEnums.maskStates 中"
            )
        if (findEmotion(stateLayer.primaryEmotionKey) == null)
            throw DaughterDataException(
                "stateLayer.primaryEmotionKey='${stateLayer.primaryEmotionKey}' 不在 customEnums.emotionStates 中"
            )
        if (findNeed(stateLayer.currentNeedKey) == null)
            throw DaughterDataException(
                "stateLayer.currentNeedKey='${stateLayer.currentNeedKey}' 不在 customEnums.needStates 中"
            )
        if (findFear(stateLayer.currentFearKey) == null)
            throw DaughterDataException(
                "stateLayer.currentFearKey='${stateLayer.currentFearKey}' 不在 customEnums.fearStates 中"
            )
    }

    companion object {
        /**
         * @throws DaughterDataException 四套枚举中任一套为空数组
         *         （与 Generator.parseAndValidate() 对 maskStates 的校验对齐，
         *         这里补齐对 emotion/need/fear 三套的同等校验）。
         */
        fun fromJson(json: JSONObject): DaughterCustomEnums {
            val maskStates = json.optJSONArray("maskStates").toEnumValueList()
            val emotionStates = json.optJSONArray("emotionStates").toEnumValueList()
            val needStates = json.optJSONArray("needStates").toEnumValueList()
            val fearStates = json.optJSONArray("fearStates").toEnumValueList()

            if (maskStates.isEmpty()) throw DaughterDataException("customEnums.maskStates 为空")
            if (emotionStates.isEmpty()) throw DaughterDataException("customEnums.emotionStates 为空")
            if (needStates.isEmpty()) throw DaughterDataException("customEnums.needStates 为空")
            if (fearStates.isEmpty()) throw DaughterDataException("customEnums.fearStates 为空")

            return DaughterCustomEnums(maskStates, emotionStates, needStates, fearStates)
        }

        fun fromJson(raw: String): DaughterCustomEnums = fromJson(raw.toJSONObjectOrThrow("DaughterCustomEnums"))
    }
}

/**
 * 女儿版 CharacterStateLayer（动态数值层）。
 * 与母亲版的关键差异：扁平24字段，不嵌套 PublicState/EmotionalState/
 * MotivationalState/HiddenState/AttentionState 五个子结构——
 * Generator 输出的 JSON 本身是扁平的（见 DaughterCharacterGenerator
 * 的输出格式示例），没有必要为了和母亲"形似"而引入嵌套再拆装一次。
 * PromptOrchestrator 女儿层注入时按需读取对应字段即可。
 *
 * 数值字段默认值与母亲 CharacterStateLayer 的五个子结构默认值一一对应
 * （talkativeness=50/openness=50/patience=70/vigilance=30/intensity=30/
 * emotionalFatigue=0/emotionalStability=70/desireStrength=30/urgency=20/
 * resistance=40/exposureRisk=10/selfControl=80/emotionalSuppression=50/
 * focusStrength=60/observationLevel=50/concernLevel=20），
 * 字段缺失时 fallback 到这些值，而非 0，避免解析出一个不自然的初始状态。
 */
data class DaughterStateLayer(
    // ── PublicState 对应字段 ──
    val maskKey: String,
    val talkativeness: Int = 50,
    val openness: Int = 50,
    val patience: Int = 70,
    val vigilance: Int = 30,

    // ── EmotionalState 对应字段 ──
    val primaryEmotionKey: String,
    val secondaryEmotionKey: String? = null,
    val intensity: Int = 30,
    val emotionalFatigue: Int = 0,
    val emotionalStability: Int = 70,

    // ── MotivationalState 对应字段 ──
    val currentNeedKey: String,
    val currentGoal: String = "",
    val desireStrength: Int = 30,
    val urgency: Int = 20,
    val resistance: Int = 40,

    // ── HiddenState 对应字段 ──
    val currentFearKey: String,
    val secretDesire: String = "",
    val exposureRisk: Int = 10,
    val selfControl: Int = 80,
    val emotionalSuppression: Int = 50,

    // ── AttentionState 对应字段 ──
    val focusTarget: String = "用户",
    val focusStrength: Int = 60,
    val observationLevel: Int = 50,
    val concernLevel: Int = 20,
) {
    /** 便捷属性：对标 CharacterStateLayer.isMaskNearBreaking，判断是否注入深层状态块。 */
    val isMaskNearBreaking: Boolean
        get() = selfControl < 40 || exposureRisk > 65

    fun toJson(): JSONObject = JSONObject().apply {
        put("maskKey", maskKey)
        put("talkativeness", talkativeness)
        put("openness", openness)
        put("patience", patience)
        put("vigilance", vigilance)
        put("primaryEmotionKey", primaryEmotionKey)
        put("secondaryEmotionKey", secondaryEmotionKey)
        put("intensity", intensity)
        put("emotionalFatigue", emotionalFatigue)
        put("emotionalStability", emotionalStability)
        put("currentNeedKey", currentNeedKey)
        put("currentGoal", currentGoal)
        put("desireStrength", desireStrength)
        put("urgency", urgency)
        put("resistance", resistance)
        put("currentFearKey", currentFearKey)
        put("secretDesire", secretDesire)
        put("exposureRisk", exposureRisk)
        put("selfControl", selfControl)
        put("emotionalSuppression", emotionalSuppression)
        put("focusTarget", focusTarget)
        put("focusStrength", focusStrength)
        put("observationLevel", observationLevel)
        put("concernLevel", concernLevel)
    }

    companion object {
        /**
         * @throws DaughterDataException maskKey/primaryEmotionKey/currentNeedKey/currentFearKey
         *         任一为空——这四个 key 是女儿层注入时查 customEnums 的索引，
         *         空值会导致 PromptOrchestrator 查不到对应枚举值，必须在解析时拦截。
         */
        fun fromJson(json: JSONObject): DaughterStateLayer {
            val maskKey = json.optString("maskKey")
            val primaryEmotionKey = json.optString("primaryEmotionKey")
            val currentNeedKey = json.optString("currentNeedKey")
            val currentFearKey = json.optString("currentFearKey")

            if (maskKey.isBlank()) throw DaughterDataException("stateLayer.maskKey 为空")
            if (primaryEmotionKey.isBlank()) throw DaughterDataException("stateLayer.primaryEmotionKey 为空")
            if (currentNeedKey.isBlank()) throw DaughterDataException("stateLayer.currentNeedKey 为空")
            if (currentFearKey.isBlank()) throw DaughterDataException("stateLayer.currentFearKey 为空")

            return DaughterStateLayer(
                maskKey = maskKey,
                talkativeness = json.optInt("talkativeness", 50),
                openness = json.optInt("openness", 50),
                patience = json.optInt("patience", 70),
                vigilance = json.optInt("vigilance", 30),
                primaryEmotionKey = primaryEmotionKey,
                secondaryEmotionKey = json.optStringOrNull("secondaryEmotionKey"),
                intensity = json.optInt("intensity", 30),
                emotionalFatigue = json.optInt("emotionalFatigue", 0),
                emotionalStability = json.optInt("emotionalStability", 70),
                currentNeedKey = currentNeedKey,
                currentGoal = json.optString("currentGoal"),
                desireStrength = json.optInt("desireStrength", 30),
                urgency = json.optInt("urgency", 20),
                resistance = json.optInt("resistance", 40),
                currentFearKey = currentFearKey,
                secretDesire = json.optString("secretDesire"),
                exposureRisk = json.optInt("exposureRisk", 10),
                selfControl = json.optInt("selfControl", 80),
                emotionalSuppression = json.optInt("emotionalSuppression", 50),
                focusTarget = json.optString("focusTarget").ifBlank { "用户" },
                focusStrength = json.optInt("focusStrength", 60),
                observationLevel = json.optInt("observationLevel", 50),
                concernLevel = json.optInt("concernLevel", 20),
            )
        }

        fun fromJson(raw: String): DaughterStateLayer = fromJson(raw.toJSONObjectOrThrow("DaughterStateLayer"))
    }
}

/**
 * 三层聚合 + 名字，DaughterCharacterEntity 的强类型镜像。
 * PromptOrchestrator 女儿层接入（步骤②）以此为唯一输入，
 * 不直接接触 DaughterCharacterEntity 或裸 JSON 字符串。
 */
data class DaughterCharacterData(
    val motherCharacterId: Int,
    val daughterName: String,
    val identity: DaughterIdentity,
    val stateLayer: DaughterStateLayer,
    val customEnums: DaughterCustomEnums,
    val generatedAt: Long,
    val generatorVersion: String,
)

/**
 * DaughterCharacterEntity → DaughterCharacterData 的唯一转换入口。
 *
 * @throws DaughterDataException 三列 JSON 中任一解析失败、关键字段缺失，
 *         或 stateLayer 的四个索引 key（maskKey/primaryEmotionKey/
 *         currentNeedKey/currentFearKey）在 customEnums 对应数组中找不到
 *         匹配项（复核修复：此前只对 maskKey 做过存在性校验，另外三个
 *         key 完全没查——「key 非空」和「key 能查到对应枚举值」是两件事，
 *         后者查不到时不会抛异常，是运行时静默的行为异常：女儿说话时
 *         customEnums.findEmotion(key) 会返回 null，没人发现。
 *         这里补齐，与写库端 DaughterCharacterGenerator.parseAndValidate()
 *         的对应校验保持一致）。
 *         调用方（PromptOrchestrator / 对话入口）必须让这个异常往上抛，
 *         不能 catch 后静默降级成空人格——这是设计文档「防御性要求」
 *         在强类型层的落地：宁可让女儿对话报错，不能让她带着残缺人格说话。
 */
fun DaughterCharacterEntity.toDaughterCharacterData(): DaughterCharacterData {
    val stateLayer = DaughterStateLayer.fromJson(stateLayerJson)
    val customEnums = DaughterCustomEnums.fromJson(customEnumsJson)

    // P3-35 修复：改用统一校验方法 customEnums.validateStateLayerKeys()
    customEnums.validateStateLayerKeys(stateLayer)

    // S8-窗口08 新问题01修复：DaughterIdentity.kinshipTerm（原名 gender）此前
    // 始终为 null——LLM 输出的 identityJson 不带这个字段，这里也没有回填。
    // 现在从 entity.kinshipTerm（数据库列，D4 生成器写入的"女儿"/"孙女"）显式
    // 回填，identityJson 解析出的值（通常为 null）仅在 entity.kinshipTerm 本身
    // 为 null 时（旧数据，字段上线前生成）才会被保留。
    val identity = DaughterIdentity.fromJson(identityJson).let {
        if (it.kinshipTerm == null && kinshipTerm != null) it.copy(kinshipTerm = kinshipTerm) else it
    }

    return DaughterCharacterData(
        motherCharacterId = motherCharacterId,
        daughterName = daughterName,
        identity = identity,
        stateLayer = stateLayer,
        customEnums = customEnums,
        generatedAt = generatedAt,
        generatorVersion = generatorVersion,
    )
}

// ── 异常类 ────────────────────────────────────────────────────

/**
 * 女儿数据解析/校验失败时抛出。
 * 与 DaughterGenerationException（写库前校验）是同一防御性原则的两端：
 * 那个拦在"生成结果进库之前"，这个拦在"数据出库进入 Prompt 之前"。
 */
class DaughterDataException(message: String) : Exception(message)

// ── JSON 解析工具函数（私有，仅本文件内使用）──────────────────

private fun String.toJSONObjectOrThrow(label: String): JSONObject = try {
    JSONObject(this)
} catch (e: Throwable) {
    throw DaughterDataException("$label JSON解析失败：${e.message}")
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { getString(it) }
}

private fun JSONArray?.toEnumValueList(): List<DaughterEnumValue> {
    if (this == null) return emptyList()
    return (0 until length()).map { DaughterEnumValue.fromJson(getJSONObject(it)) }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    val value = optString(key)
    return value.takeIf { it.isNotBlank() }
}

// ─────────────────────────────────────────────────────────────
//  D4 触发点接入 Part 2：女儿资料 → 角色资料表（CharacterIdentityEntity）
//
//  背景：女儿和蒂法/露娜等预设角色平级（产品决定，见 ChatViewModel
//  接入说明），意味着她的人设资料要写进同一张 character_identity 表，
//  而不是只留在 daughter_character 这张"生成暂存表"里。
//
//  字段对应关系：DaughterIdentity 18 字段与 CharacterIdentityEntity
//  逐一同名对齐（除了 boundaries/coreBeliefs 在 Entity 侧是 JSON 字符串列，
//  这里序列化成 JSON 数组字符串）。customSystemPrompt 留空——女儿目前
//  不支持完全覆盖 Identity Layer（与设计文档 2.x 节描述一致）。
// ─────────────────────────────────────────────────────────────

/**
 * 把 D4 生成、已通过强类型校验的 [DaughterCharacterData] 转换成
 * [com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity]，
 * 用于写入女儿专属分配到的 characterId 行。
 *
 * @param daughterCharacterId 由 [com.zaijian.zhoumuyun.data.manager.DaughterIdAllocator]
 *        分配的全新角色编号，不复用 [DaughterCharacterData.motherCharacterId]
 *        （那是母亲的编号，女儿必须有自己独立的编号才能在角色资料表里
 *        和蒂法/露娜平级共存）。
 */
fun DaughterCharacterData.toCharacterIdentityEntity(
    daughterCharacterId: Int,
): com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity {
    val id = identity
    return com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity(
        characterId = daughterCharacterId,
        name = daughterName,
        persona = id.persona,
        speechStyle = id.speechStyle,
        attitudeToUser = id.attitudeToUser,
        boundariesJson = JSONArray(id.boundaries).toString(),
        corebeliefsJson = JSONArray(id.coreBeliefs).toString(),
        coreWound = id.coreWound,
        coreDesire = id.coreDesire,
        maskTrigger = id.maskTrigger,
        privatePersona = id.privatePersona,
        privateStyle = id.privateStyle,
        privateExamples = id.privateExamples,
        situationRules = id.situationRules,
        deviationSignals = id.deviationSignals,
        likes = id.likes,
        dislikes = id.dislikes,
        relationships = id.relationships,
        relationAssumption = id.relationAssumption,
        conflictStrategy = id.conflictStrategy,
        // 女儿不支持完全覆盖 Identity Layer，customSystemPrompt 始终为 null
        customSystemPrompt = null,
        updatedAt = generatedAt,
    )
}

// ─────────────────────────────────────────────────────────────
//  D4 触发点接入 Part 4：女儿资料 → CharacterConfig（对话发送的入场券）
//
//  背景：ChatViewModel.sendMessage() 组装 system prompt 前第一步是
//  `DefaultCharacters.firstOrNull { it.id == currentCharacterId } ?: return@launch`。
//  DefaultCharacters 是预设角色（蒂法/露娜...）的固定列表，女儿的 ID（1000+）
//  永远不在里面，查不到就直接退出协程——这一步发生在 identity/记忆/工具
//  全部读取之前，所以女儿目前完全无法发送/接收消息，不是"人格不完整"，
//  是"连对话入口都进不去"。
//
//  这个函数把 DaughterCharacterData 拼成一个能通过上述检查的 CharacterConfig，
//  ChatViewModel 改为：先查 DefaultCharacters，查不到再查这里。
//
//  字段对应策略：
//  - 决定对话内容的字段（identityConfig 全部 17 个子字段、initialState）：
//    从 D4 生成数据真实拼装，与蒂法/露娜走同一套 buildSystemPrompt() 逻辑，
//    女儿的人设、说话风格、初始情绪状态都是她自己的，不是占位。
//  - 决定 UI 展示位置的字段（floor/shelfRow/shelfCol/accentColor/breathColor/
//    avatarUrl/statusPool）：用安全占位值。这些字段目前只有"公馆书架视图"
//    （BookCard/WindowCard 等组件）会读取，对话发送链路完全不读它们；
//    女儿暂不需要出现在书架上，等产品确定女儿的展示位置后再回填真实值，
//    不阻塞"女儿能不能正常对话"这件事。
//  - goals 留空：D4 生成器目前不产出女儿的目标数据，没有数据源；
//    CharacterGoal 系统对女儿暂不生效，留空不会报错（CharacterConfig.goals
//    默认就是 emptyList()，所有读取方都按"目标列表可以为空"处理）。
// ─────────────────────────────────────────────────────────────

/**
 * 把 [DaughterCharacterData] 拼装成一个可以通过 ChatViewModel 入场检查的
 * [CharacterConfig]。
 *
 * @param daughterCharacterId 女儿自己的 characterId（与写入 character_identity
 *        表时用的是同一个号，由 [com.zaijian.zhoumuyun.data.manager.DaughterIdAllocator]
 *        分配，调用方通常直接传 `currentCharacterId`）。
 */
fun DaughterCharacterData.toCharacterConfig(
    daughterCharacterId: Int,
): CharacterConfig {
    val id = identity
    val state = stateLayer

    return CharacterConfig(
        id = daughterCharacterId,
        name = daughterName,

        // ── UI 展示字段（格位 + 配色 + 头像）──────────────────────
        // 格位：col=0 = 不在公馆格位，女儿通过 FamilyScreen 展示，不占母亲的九个格子。
        floor = FloorEnum.BASEMENT,
        shelfRow = 0,
        shelfCol = 0,
        // accentColor：母亲色系 lerp 白色 25%，女儿用浅版母亲主题色
        accentColor = run {
            val motherAccent = DefaultCharacters
                .firstOrNull { it.id == motherCharacterId }
                ?.accentColor ?: Color(0xFF9E9E9E)
            lerp(motherAccent, Color.White, 0.25f)
        },
        breathColor = run {
            val motherAccent = DefaultCharacters
                .firstOrNull { it.id == motherCharacterId }
                ?.accentColor ?: Color(0xFF9E9E9E)
            lerp(motherAccent, Color.White, 0.25f)
        },
        // avatarUrl：ui-avatars.com 按名字+母亲派生色生成，与母亲头像风格一致
        avatarUrl = run {
            val motherAccent = DefaultCharacters
                .firstOrNull { it.id == motherCharacterId }
                ?.accentColor ?: Color(0xFF9E9E9E)
            val daughterAccent = lerp(motherAccent, Color.White, 0.25f)
            // ARGB long → 去掉 alpha 取低 24 位 RGB → 六位十六进制
            val argb = daughterAccent.value.toLong()
            val rgb = String.format("%06X", argb and 0xFFFFFFL)
            val encodedName = java.net.URLEncoder.encode(daughterName, "UTF-8")
            "https://ui-avatars.com/api/?name=${encodedName}&background=${rgb}&color=fff&size=128"
        },
        statusPool = mapOf(
            com.zaijian.zhoumuyun.data.model.StatusType.ACTIVE  to listOf("在这里"),
            com.zaijian.zhoumuyun.data.model.StatusType.IDLE    to listOf("有点想你"),
            com.zaijian.zhoumuyun.data.model.StatusType.FOCUSED to listOf("在想事情"),
            com.zaijian.zhoumuyun.data.model.StatusType.OFFLINE to listOf("不在线"),
        ),
        isUnlocked = true,

        // ── 真实数据：决定女儿在对话里说话的方式 ───────────────────
        identityConfig = CharacterIdentity(
            persona = id.persona,
            speechStyle = id.speechStyle,
            attitudeToUser = id.attitudeToUser,
            boundaries = id.boundaries,
            coreBeliefs = id.coreBeliefs,
            coreWound = id.coreWound,
            coreDesire = id.coreDesire,
            maskTrigger = id.maskTrigger,
            privatePersona = id.privatePersona,
            privateStyle = id.privateStyle,
            privateExamples = id.privateExamples,
            situationRules = id.situationRules,
            deviationSignals = id.deviationSignals,
            likes = id.likes,
            dislikes = id.dislikes,
            relationships = id.relationships,
            relationAssumption = id.relationAssumption,
            conflictStrategy = id.conflictStrategy,
            customSystemPrompt = null,
        ),

        // 注意：buildSystemPrompt() 实际渲染时 identityEntity（来自
        // character_identity 表）非空字段优先于这里的 identityConfig，
        // 这里填的内容只在 identityEntity 某个字段意外为空时才会被读到
        // ——属于兜底，不是主路径，但仍然按真实数据填，不留空字符串占位。

        goals = emptyList(),

        // ── 复核修复 #7/#13：女儿的初始情绪/动机/隐藏/注意力状态 ──────
        // 女儿的 stateLayer（DaughterStateLayer）用的是 LLM 自由生成的
        // 字符串 key（如 maskKey="DEFAULT"），而母亲走的 CharacterStateLayer
        // 用的是写死的枚举（MaskType/EmotionType/NeedType/FearType）——
        // 两套类型结构不兼容，不能逐字段直接转换，种类维度在这里（编译期
        // CharacterConfig.initialState）只能用中性默认值占位，这一点无法改变。
        // 但这不再是"占位符永久生效"：PromptOrchestrator.buildCharacterStateBlock()
        // 现在单独接收 daughterStateLayer/daughterCustomEnums 参数（由 ChatViewModel
        // 通过 DaughterCharacterRepository.getCharacterData() 单独查询后传入），
        // 渲染面具/情绪/需求/恐惧四个种类维度时优先用女儿专属 customEnums 的
        // description 文本，这里的占位符只在极端 fallback 路径（例如 ChatViewModel
        // 未能查到 DaughterCharacterData）下才会真正被 LLM 看到。
        // 数值维度（强度/疲劳/紧迫感等）复用同一份真实数据，转换逻辑抽取到
        // DaughterStateLayer.toCharacterStateLayer()，避免这里和该函数各写一份。
        initialState = state.toCharacterStateLayer(),
    )
}

/**
 * 复核修复 #7/#13：DaughterStateLayer 数值维度 → CharacterStateLayer 的转换。
 * 从 [toCharacterConfig] 中抽取为独立扩展函数，供 ChatViewModel 在组装 Prompt 前
 * 复用（修正 CharacterStateRepository.getState() 对女儿角色的 fallback 缺口，
 * 详见该类的类注释）。
 *
 * 种类维度（currentMask/primaryEmotion/currentNeed/currentFear）固定使用中性
 * 默认值占位——母亲侧是编译期枚举，无法承载女儿的运行时字符串枚举，这是类型
 * 系统层面的硬约束，不是待办事项。真实种类维度的文本描述通过
 * PromptOrchestrator 的 daughterStateLayer/daughterCustomEnums 参数单独注入，
 * 不经过这个函数。
 */
fun DaughterStateLayer.toCharacterStateLayer(): CharacterStateLayer =
    CharacterStateLayer(
        publicState = PublicState(
            currentMask = MaskType.NORMAL,
            socialMode = SocialMode.ONE_ON_ONE,
            talkativeness = talkativeness,
            openness = openness,
            patience = patience,
            vigilance = vigilance,
        ),
        emotionalState = EmotionalState(
            primaryEmotion = EmotionType.CALM,
            secondaryEmotion = null,
            intensity = intensity,
            emotionalFatigue = emotionalFatigue,
            emotionalStability = emotionalStability,
        ),
        motivationalState = MotivationalState(
            currentNeed = NeedType.ATTENTION,
            currentGoal = currentGoal,
            desireStrength = desireStrength,
            urgency = urgency,
            resistance = resistance,
        ),
        hiddenState = HiddenState(
            currentFear = FearType.ABANDONMENT,
            secretDesire = secretDesire,
            exposureRisk = exposureRisk,
            selfControl = selfControl,
            emotionalSuppression = emotionalSuppression,
        ),
        attentionState = AttentionState(
            focusTarget = focusTarget,
            focusStrength = focusStrength,
            observationLevel = observationLevel,
            concernLevel = concernLevel,
        ),
    )

