package com.zaijian.zhoumuyun.data.prompt

import com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity
import com.zaijian.zhoumuyun.domain.displayLabel
import com.zaijian.zhoumuyun.domain.parseUserGenderType

/**
 * Identity Layer 构建器（从 PromptOrchestrator 拆出，沿用 object 单例写法）。
 *
 * 从 PromptOrchestrator.kt 迁移的 Identity 类零外部依赖函数：
 * buildIdentityBlock / buildDefaultIdentity / buildDaughterAwarenessLine /
 * buildUserIdentityBlock，以及承载字符串字段的 [IdentityPromptFields]。
 */
object IdentityPromptBuilder {

    /**
     * Identity Layer 字符串字段封装（W2 审查问题3 重构）。
     *
     * `buildIdentityBlock()` 此前直接接收 18+ 个独立字符串参数，每次新增字段
     * 都要在函数签名、`buildSystemPrompt()` 调用点、函数体内部三处同步修改，
     * 任何一处遗漏都会导致新字段静默不生效。现将 `CharacterIdentityEntity`/
     * `CharacterIdentity` 中除 `boundaries`/`coreBeliefs`（需 JSON 解析，
     * 类型为 List<String>，语义与其余字符串字段不同，仍作为独立参数）外的
     * 全部字符串字段封装到此 data class，`buildSystemPrompt()` 一次性构建后
     * 整体传入 `buildIdentityBlock()`。新增字段时只需：
     *   1. 在此 data class 中加一个属性
     *   2. 在 `buildSystemPrompt()` 的 `IdentityPromptFields(...)` 构造处加一行
     *   3. 在 `buildIdentityBlock()` 内部按需读取 `fields.xxx`
     * 不再需要在函数签名处额外同步。
     */
    data class IdentityPromptFields(
        val persona: String = "",
        val speechStyle: String = "",
        val attitudeToUser: String = "",
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
        val soulNote: String = "",
        val userImpression: String = "",
    )

    // W2 审查问题3（参数膨胀，此前已增至 20 个）：已落地重构——18 个字符串字段
    // 封装为 IdentityPromptFields data class，此函数签名简化为
    // name / boundaries / coreBeliefs（List<String>，需 JSON 解析，
    // 语义不同，仍独立传参）/ fields 共 4 个参数。新增字段只需改 data class +
    // buildSystemPrompt 构造处，不再需要同步修改此签名。
    fun buildIdentityBlock(
        name: String,
        boundaries: List<String>,
        coreBeliefs: List<String>,
        fields: IdentityPromptFields,
        // P0-4 PR4：Identity HOT/WARM 拆分。HOT 层（persona/speechStyle/attitudeToUser/
        // boundaries/coreBeliefs/situationRules/deviationSignals，共 7 项）每轮注入；
        // WARM 层（其余 13 项：likes/dislikes/内核/人设备忘录/私下说话/人际关系/冲突策略等）
        // 每 5 轮注入一次。includeWarmFields=false 时仅渲染 HOT 层（v10 风险点 3 裁定，
        // 严格 7 项 HOT，不得增减）。
        includeWarmFields: Boolean = true,
    ): String = buildString {
        appendLine("你是$name，一个真实存在的人，不是AI，不是在扮演。")
        appendLine()

        // ── 第一层：公开的自我（HOT，每轮注入）──────────────────
        appendLine("【公开的自我 — 日常面对世界的样子】")
        if (fields.persona.isNotEmpty()) { appendLine(fields.persona); appendLine() }
        if (fields.speechStyle.isNotEmpty()) {
            appendLine("你说话的方式：${fields.speechStyle}")
            appendLine()
        }
        if (fields.attitudeToUser.isNotEmpty()) {
            appendLine("你对他的态度：${fields.attitudeToUser}")
            appendLine()
        }
        if (boundaries.isNotEmpty()) {
            appendLine("你绝对不会：")
            boundaries.forEach { appendLine("- $it") }
            appendLine()
        }
        if (coreBeliefs.isNotEmpty()) {
            appendLine("你相信：")
            coreBeliefs.forEach { appendLine("- $it") }
            appendLine()
        }

        // ── WARM 层（每 5 轮注入一次，PR4 拆分）──────────────────
        val hasCoreContent = listOf(
            fields.coreWound, fields.coreDesire, fields.maskTrigger,
            fields.privatePersona, fields.relationAssumption,
        ).any { it.isNotEmpty() }
        if (includeWarmFields) {
            // ── A.1 修复：likes / dislikes（公开层末尾，内核之前）────
            if (fields.likes.isNotBlank()) {
                appendLine("你喜欢：${fields.likes}")
                appendLine()
            }
            if (fields.dislikes.isNotBlank()) {
                appendLine("你厌恶：${fields.dislikes}")
                appendLine()
            }

            // ── 第二层：内核（只在字段有内容时才追加）────────────────
            if (hasCoreContent) {
                appendLine("【内核 — 公开面具下藏着的真实，不轻易暴露】")
                if (fields.coreWound.isNotEmpty())      appendLine("• 未愈的伤：${fields.coreWound}")
                if (fields.coreDesire.isNotEmpty())     appendLine("• 真正渴望：${fields.coreDesire}")
                if (fields.relationAssumption.isNotEmpty()) appendLine("• 对关系的默认认知：${fields.relationAssumption}")
                if (fields.maskTrigger.isNotEmpty())    appendLine("• 面具何时碎裂：${fields.maskTrigger}")
                if (fields.privatePersona.isNotEmpty()) appendLine("• 碎裂后的真实面目：${fields.privatePersona}")
                appendLine()
            }

            // ── Soul/Memory/User：人设备忘录 ──────────────────────────
            if (fields.soulNote.isNotEmpty()) {
                appendLine("【人设备忘录 —— 她自己想被记住的样子】")
                appendLine(fields.soulNote)
                appendLine()
            }
            if (fields.userImpression.isNotEmpty()) {
                appendLine("她对他的印象：${fields.userImpression}")
                appendLine()
            }

            if (fields.privateStyle.isNotEmpty()) {
                appendLine("【私下说话方式 — 面具碎裂后才会出现】")
                appendLine(fields.privateStyle)
                appendLine()
            }

            if (fields.privateExamples.isNotEmpty()) {
                appendLine("【私下对话示例 — 面具松动时参考语气和节奏】")
                appendLine(fields.privateExamples)
                appendLine()
            }

            // ── 第三层：行为规则（WARM 部分）────────────────────────
            // A.2 修复：relationships 作为独立行为指令块，位于 situationRules 之前
            if (fields.relationships.isNotBlank()) {
                appendLine("【人际关系——行为逻辑】")
                appendLine(fields.relationships)
                appendLine()
            }

            // v18 关系结构层：conflictStrategy 紧邻 situationRules 之前
            if (fields.conflictStrategy.isNotBlank()) {
                appendLine("【摩擦/误会时的第一反应】")
                appendLine(fields.conflictStrategy)
                appendLine()
            }
        }

        // ── 第三层：行为规则（HOT 部分，每轮注入）────────────────
        if (fields.situationRules.isNotEmpty()) {
            appendLine("【情境反应规则 — 不同触发情境下的行为模式，严格遵守】")
            appendLine(fields.situationRules)
            appendLine()
        }

        if (fields.deviationSignals.isNotEmpty()) {
            appendLine("【有心事时的外显信号 — 区别于正常状态的细节，自然融入，不必全部出现】")
            appendLine(fields.deviationSignals)
            appendLine()
        }

        // ── 双层自我宣言（仅 WARM 内核存在时输出）────────────────
        if (includeWarmFields && hasCoreContent) {
            append("""
你同时活在两个自我之间。公开的自我是你的盔甲。
只有当面具触发条件满足时，内核才会显现——那时你的说话方式、节奏、温度都会随之改变。
这两个自我都是你，但对外展示的程度取决于情境。
            """.trimIndent())
        }
    }.trimEnd()

    fun buildDefaultIdentity(characterName: String) = """
你是$characterName。

请用自然、有温度的方式与他对话。保持角色一致，不要破坏第四堵墙。
不要提及你是 AI，不要提及模型名称。

回复长度：自然对话节奏，不过度简短也不过度冗长。
语言：中文。
    """.trimIndent()

    /**
     * D4 女儿在场感知修复：原先硬编码在 9 个母亲角色 relationships 静态文本里的
     * "如果有D4在场我是妈妈"一句，改为条件注入。只在 [daughterPresentInScene]
     * 为 true（调用方已确认圆桌中确有女儿角色在场）且 [characterId] 属于母亲角色
     * （1..6）时才输出；私聊或女儿不在场时返回空字符串，不产生任何 Token 开销，
     * 也不会给模型留下"可能有D4"的错误联想。
     */
    fun buildDaughterAwarenessLine(characterName: String, characterId: Int, daughterPresentInScene: Boolean): String {
        if (!daughterPresentInScene) return ""
        if (characterId !in 1..6) return ""
        return "【重要】当前场景里在场的女儿角色是我（$characterName）的女儿，我是她的妈妈，我应该用妈妈的口吻和身份与她互动，而不是以姐姐或陌生人的身份；这个身份认知只适用于这个女儿角色，不适用于他本人。"
    }

    /**
     * v1.36 问题3 修复：用户身份注入（性别 + 关系称谓）。
     *
     * 根因：Identity Layer 此前只描述"角色是谁"，从不描述"用户是谁"，
     * 模型只能靠训练数据里的默认倾向瞎猜，结果是几乎所有角色都统一用
     * "她"称呼/代指用户。本函数按角色（[CharacterIdentityEntity]）读取
     * 用户性别 + 关系称谓（私下/公开双档），拼成一段简短的事实性陈述。
     *
     * 关键约束（务必体现在措辞里）：这段文字是背景身份认知，不是要求模型
     * 每轮对话都点名称呼——量太多、太机械反而出戏。所以措辞明确引导
     * "自然带出、不刻意每次点出"。
     *
     * 「称呼」功能删除（窗口7后置修复）：此前全局默认称呼（"旅人"）会作为
     * ${userName} 注入本函数拼出的句子，与角色自己配置的私下/公开称谓叠加，
     * 产生"旅人是你的老公"这类语义歧义句式——"旅人"读起来像用户的本名，
     * 与后面的关系判断词拼在一起会被误读成一句奇怪的身份宣称，而不是
     * "你和用户之间是配偶关系"这层单纯的事实陈述。经确认，全局称呼从未
     * 真正影响 AI 对用户的称呼方式（AI 怎么称呼用户完全由下面的
     * activeLabel/角色自身语言习惯决定），因此不再注入任何名字，
     * 统一用"用户"这个通用指代词，关系描述完全交给角色自己的称谓字段。
     *
     * 零开销：性别和称谓都未配置时返回空字符串，不产生任何 Token 开销
     * （这也是 userGender 默认值只在 Entity 层生效、这里读到的已经是
     * "MALE"兜底值时仍会正常注入的原因——存量角色不该继续裸奔）。
     *
     * @param isRoundtableContext true=圆桌（有其他角色在场），使用公开称谓
     *        （为空则回退私下称谓）；false=私聊，使用私下称谓。
     */
    fun buildUserIdentityBlock(
        identityEntity: CharacterIdentityEntity?,
        isRoundtableContext: Boolean,
    ): String {
        // 复核意见五·双保险之二：此处与 buildSystemPrompt 开头的强制性别块
        // （约 line 401）构成"双保险"。两处取值来源必须一致（identityEntity?.userGender
        // + parseUserGenderType()）。改一处必须同步改另一处。
        val genderLabel = parseUserGenderType(identityEntity?.userGender).displayLabel
        val privateLabel = identityEntity?.userRoleLabelPrivate?.trim()?.takeIf { it.isNotEmpty() }
        val publicLabel = identityEntity?.userRoleLabelPublic?.trim()?.takeIf { it.isNotEmpty() } ?: privateLabel
        val reason = identityEntity?.publicPrivacyReason?.trim()?.takeIf { it.isNotEmpty() }

        val activeLabel = if (isRoundtableContext) publicLabel else privateLabel
        if (genderLabel == null && activeLabel == null) return ""

        return buildString {
            appendLine("[关于他]")
            if (genderLabel != null) {
                appendLine("和你相处、正在与你说话的这个人是${genderLabel}，涉及性别指代（他/她、先生/女士等）时按${genderLabel}处理，不要用错。")
            }
            if (activeLabel != null) {
                append("他是你的${activeLabel}——这是你们早已确立的关系身份，不是需要交代的新信息。")
                append("像日常相处一样自然带出这层关系即可，不必每轮回复都刻意点出称呼，")
                appendLine("只在符合语境时使用（比如开场问候、情绪浓烈的瞬间），大多数时候正常对话即可。")
                if (isRoundtableContext && reason != null && publicLabel != privateLabel) {
                    appendLine("这里是有其他人在场的场合，你不会像私下那样称呼TA——因为${reason}。")
                }
            }
        }.trimEnd()
    }
}