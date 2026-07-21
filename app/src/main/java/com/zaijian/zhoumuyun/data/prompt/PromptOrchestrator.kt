package com.zaijian.zhoumuyun.data.prompt

import com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.ChatMode
import com.zaijian.zhoumuyun.data.model.DaughterCustomEnums
import com.zaijian.zhoumuyun.data.model.DaughterStateLayer
import com.zaijian.zhoumuyun.data.model.toCharacterFearDescription
import com.zaijian.zhoumuyun.data.model.toCharacterMaskDescription
import com.zaijian.zhoumuyun.data.model.toCharacterNeedDescription
import com.zaijian.zhoumuyun.data.model.toChineseDescription
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.domain.displayLabel
import com.zaijian.zhoumuyun.domain.parseUserGenderType

/**
 * Prompt Orchestration Layer
 *
 * 九层架构（Phase 31 确立，设计方案 §11），实际含 5 个子层，共 14 个注入点
 * （W2 审查问题4 修复：此前架构表未列出子层，现补全）：
 *
 * | 层位 | Layer            | Token 上限          | 状态                         |
 * |------|------------------|---------------------|------------------------------|
 * |  1   | Identity         | 1500                | ✅ Phase 7                   |
 * |  2   | Knowledge ★      | 动态                 | ✅ Phase 31（知识库全文）       |
 * |  3   | State            | 500                 | ✅ Phase 9                   |
 * | 3.5  | AgentRelation ★  | 动态                 | ✅（D5 女儿关系阶段，仅 characterId >= 1000，紧接 State Layer 之后）|
 * |  4   | Memory           | 1000                | ✅ Phase 8                   |
 * | 4.5  | Narrative        | 动态                 | ✅（叙事记忆，紧接 Memory Layer 之后）|
 * | 4.8  | Group Memory     | 动态                 | ✅（圆桌 scope=GROUP 群记忆，紧接 Narrative 之后，仅圆桌场景）|
 * |  5   | LearningGoal ★   | 动态（每目标 ≤10条）  | ✅ Phase 25（Phase 27 正式命名）|
 * |  6   | AgentPlan ★      | 500                 | ✅ Phase 22（Phase 27 正式命名）|
 * |  7   | World            | 1000                | ✅ Phase 10                  |
 * |  8   | Task             | 1500                | ✅ Phase 11                  |
 * | 8.5  | D3 Question      | 动态                 | ✅（D3 孕期共设提问 patch，紧接 Task Layer 之后）|
 * | 8.6  | Workflow Recap   | 动态                 | ✅（工作流回溯 patch，紧接 8.5 之后）|
 * |  9   | Output           | 500                 | ✅ Phase 7                   |
 *
 * 子层均为"挂靠"在相邻主层之后的独立注入点，非空时才追加（零开销原则），
 * 具体门控条件见各参数的 KDoc（如 @param agentRelationSnapshot）。
 *
 * Phase 11 新增：Task Layer（工作台任务上下文注入）。
 * Phase 13 新增：toolDescriptionBlock 参数（Agent 工具能力描述注入 Task Layer 末尾）。
 * Phase 14 新增：groupContextBlock 专用参数（圆桌模式本轮已有回复注入 World Layer 末尾）。
 *               之前临时复用 worldLayerBlock，现在拆分为独立参数，语义更清晰。
 * Phase 22 新增：agentPlanBlock 参数（Agent 进化方案，注入 Memory Layer 之后、World Layer 之前）。
 * Phase 25 新增：ruleLayerBlock 参数（isLocked=true 的 RULE 记忆，按学习目标分组动态注入，
 *               注入位置：Memory Layer 之后、AgentPlan Layer 之前）。
 * Phase 27 正式确立：
 *   - ruleLayerBlock 对应层正式命名为「LearningGoal Layer」（层位 4），
 *     语义上强调规则来源于学习目标的提炼闭环，而非独立的静态规则集。
 *   - agentPlanBlock 对应层正式命名为「AgentPlan Layer」（层位 5），
 *     与 LearningGoal Layer 共同构成 Memory → LearningGoal → AgentPlan → World 注入链。
 */

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
private data class IdentityPromptFields(
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

object PromptOrchestrator {

    /**
     * 组装 System Prompt。
     *
     * @param worldLayerBlock    Phase 10：ProjectRepository.buildWorldLayerBlock() 的输出
     * @param taskLayerBlock     Phase 11：当前任务上下文（TaskLayer），空字符串跳过
     * @param toolDescriptionBlock Phase 13：AgentToolRegistry.buildToolDescriptionBlock() 的输出，
     *                           空字符串（注册表为空）时不注入，零开销。
     * @param groupContextBlock  Phase 14：圆桌模式专用，本轮已有回复注入（World Layer 末尾）。
     *                           之前临时复用 worldLayerBlock，现已拆分为独立参数。
     *                           非圆桌场景传空字符串（默认值），零开销。
     * @param agentPlanBlock     Phase 22 引入，Phase 27 正式命名为「AgentPlan Layer」（层位 5）。
     *                           注入 Agent 当前进化方案；由 AgentPlanDao.getActive() 读取后格式化传入。
     *                           空字符串时跳过（零开销）。
     *                           注入位置：LearningGoal Layer（层位 4）之后、World Layer 之前。
     * @param ruleLayerBlock     Phase 25 引入，Phase 27 正式命名为「LearningGoal Layer」（层位 4）。
     *                           注入 isLocked=true 的能力规则，按学习目标分组，体现规则来源于目标提炼闭环。
     *                           由 ChatViewModel 从 MemoryDao.getLockedRules() 读取后格式化传入。
     *                           空字符串时跳过（零开销，Phase 25 前默认值）。
     *                           注入位置：Memory Layer（层位 3）之后、AgentPlan Layer（层位 5）之前。
     *                           Token 预算：每目标 ≤10 条规则，总计硬上限 50 条。
     * @param agentRelationSnapshot D5 女儿关系阶段快照（层位 3.5，注入 State Layer 之后、Memory Layer 之前）。
     *                           仅对女儿角色（characterId >= 1000）非空；普通母亲角色传空字符串（默认值）。
     *                           由 AgentRelationEngine.buildPromptSnapshot(daughterId) 生成，
     *                           描述女儿当前所处关系阶段（初入家庭 / 深度连接 / 关系突破）及行为倾向。
     *                           空字符串时跳过（零开销）。
     * @param isRoundtableContext v1.36 问题3 修复：是否为圆桌（多角色在场）场景，供用户身份注入
     *                           （性别 + 关系称谓）判断该用私下称谓还是公开称谓。显式参数，
     *                           不复用 groupContextBlock.isNotEmpty()——圆桌自发发言
     *                           （RoundtableIdleManager.generateSpontaneousReply）出于 Token
     *                           预算考虑传空 groupContextBlock，但场景本身仍是圆桌，用那个信号
     *                           判断会误判成私聊。调用方按自己的真实场景显式传入。默认 false（私聊）。
     */
    fun buildSystemPrompt(
        character: CharacterConfig,
        identityEntity: CharacterIdentityEntity?,
        coreMemories: List<MemoryEntity> = emptyList(),
        relevantMemories: List<MemoryEntity> = emptyList(),
        userName: String = "你",
        // ── State Layer（Phase 9）
        presenceActivity: String = "",
        presenceFocus: String = "",
        presenceMood: String = "",
        presenceEnergy: Int = -1,
        relationshipSnapshot: String = "",
        // ── World Layer（Phase 10）
        worldLayerBlock: String = "",
        // ── Group Context Block（Phase 14 圆桌专用）
        groupContextBlock: String = "",
        // ── InterChar Rel Block（Phase 3 圆桌专用：角色间关系快照，注入当前角色 prompt）
        interCharRelBlock: String = "",
        // ── AgentPlan Layer（Phase 22）
        agentPlanBlock: String = "",
        // ── Rule Layer（Phase 25）
        ruleLayerBlock: String = "",
        // ── Task Layer（Phase 11）
        taskLayerBlock: String = "",
        // ── Tool Description（Phase 13）
        toolDescriptionBlock: String = "",
        // ── Chat Mode（Phase 30）
        chatMode: ChatMode = ChatMode.WORK,
        // ── Knowledge Block（Phase 31：独立知识库块，前置于 State Layer）
        knowledgeBlock: String = "",
        // ── Pregnancy/CharacterState params
        // characterState：CharacterStateLayer 深层状态（desireStrength / emotionalSuppression /
        // primaryEmotion 强度等），由 CharacterStateRepository.getState(characterId) 读取后传入。
        // 之前类型为 Any? 且函数体内完全未使用，现已实装到 State Layer 末尾（见 buildStateBlock 调用处）。
        characterState: CharacterStateLayer? = null,
        // 女儿专属枚举词库（复核修复 #7/#13）：女儿角色的面具/情绪/需求/恐惧种类是 D4 生成器
        // 产出的运行时字符串枚举（DaughterCustomEnums），与母亲编译期 MaskType/EmotionType 等
        // 不兼容，不能塞进 characterState 的对应枚举字段。改为单独传入，buildCharacterStateBlock
        // 渲染时优先用女儿专属 key 查 customEnums 的 description，不经过 StateExtensions 翻译层。
        // 仅对女儿角色（characterId >= 1000）非空；普通母亲角色传 null（零开销，走原有枚举翻译层）。
        daughterStateLayer: DaughterStateLayer? = null,
        daughterCustomEnums: DaughterCustomEnums? = null,
        pregnancyState: PregnancyState? = null,
        pregnancyAwarenessBlock: String = "",
        miscarriageAftermathPatch: String = "",
        // 1-6 号角色关键词兜底触发链路（PregnancyTriggerManager.checkTrigger() +
        // evaluateConsent()）产出的 Prompt patch：
        //   - D2 正常同意分支：buildRoutinePromptPatch()（渴望/压抑分档文案）
        //   - D2.5 失败分支：FertileButFailed.immediatePromptPatch
        //   - D2 突破分支：BreakthroughA/B.promptPatch
        // 仅 characterId in 1..6 时非空；女儿角色（>=1000）走独立的
        // AI 语义判定弹窗链路，不经过这个 patch。语义上与 miscarriageAftermathPatch
        // 同属"她当前内心状态背景"，因此同样挂在 Identity Layer 末尾。
        pregnancyTriggerPromptPatch: String = "",
        // 问题3/4修复：D2.5 跨周期失败背景情绪注入（shouldInjectFailureContext()
        // 门控通过后的文案，四重门控+48h冷却，事件驱动、有冷却）与常规压力 Prompt
        // （buildRoutinePromptPatch()，基于当前 desireStrength/emotionalSuppression
        // 数值分档，无门控、每轮常驻）。两者均不区分 1-6 号/女儿角色（与
        // miscarriageAftermathPatch 同样不做角色区分——底层数值 desire/suppression/
        // consecutiveFailCount 无论通过关键词链路还是 AI 判定链路产生，语义相同），
        // 因此不像 pregnancyTriggerPromptPatch 那样只在 characterId in 1..6 时非空。
        // 同属"她当前内心状态背景"，挂在 Identity Layer 末尾、紧邻语义相同的
        // miscarriageAftermathPatch/pregnancyTriggerPromptPatch 之后。
        failureContextPatch: String = "",
        routinePressurePatch: String = "",
        d3QuestionPatch: String = "",
        workflowRecapPatch: String = "",
        // ── D5 女儿关系阶段（AgentRelationEngine.buildPromptSnapshot 输出）
        agentRelationSnapshot: String = "",
        // ── v1.36 问题3：用户身份注入场景判断（私聊 vs 圆桌），见上方参数文档
        isRoundtableContext: Boolean = false,
        // ── D4 女儿在场感知修复：此前"如果有D4在场我是妈妈"这句硬编码在 9 个
        // 母亲角色（1-6号 + 其余）的 relationships 静态文本里，且没有任何门控——
        // 无论私聊还是圆桌、女儿是否真的在场，每次都会拼进 prompt，模型缺少
        // 反向锚点，容易把用户本人误认成 D4。现改为显式参数，由调用方根据自己
        // 已持有的角色列表判断后传入：
        //   - 私聊场景（ChatMessageOrchestrator）：母亲角色私聊时女儿不可能同时
        //     在场，恒为 false。
        //   - 圆桌场景（RoundtableBotReplyGenerator/RoundtableIdleManager）：
        //     取 activeMembers（当前圆桌在场角色列表）中是否存在 id >= 1000
        //     的女儿角色。
        // 仅当 true 时才注入"你是妈妈"提示，且只对母亲角色（character.id in 1..6）
        // 生效——女儿角色自己的身份由 daughterStateLayer/agentRelationSnapshot
        // 承担，不需要这句。默认 false，零开销。
        daughterPresentInScene: Boolean = false,
        // ── 群记忆（圆桌专用，scope=GROUP）
        // 由 RoundtableViewModel 查询后传入；非圆桌场景传空列表（默认值），零开销。
        // 注入位置：Memory Layer（层位 4）末尾，Narrative Memory（4.5）之前。
        groupCoreMemories: List<MemoryEntity> = emptyList(),
        groupRelevantMemories: List<MemoryEntity> = emptyList(),
    ): String {
        // ── Identity 字符串字段：一次性构建 IdentityPromptFields（W2 问题3 重构）──
        // 每个字段沿用原有 DB-prioritized 模式：identityEntity 非空优先，否则 fallback
        // 到 character.identityConfig；soulNote/userImpression 只有 DB 值，无 Config fallback
        // （与重构前行为一致）。
        val identityFields = IdentityPromptFields(
            persona            = identityEntity?.persona?.takeIf            { it.isNotEmpty() } ?: character.identityConfig.persona,
            speechStyle        = identityEntity?.speechStyle?.takeIf        { it.isNotEmpty() } ?: character.identityConfig.speechStyle,
            attitudeToUser     = identityEntity?.attitudeToUser?.takeIf     { it.isNotEmpty() } ?: character.identityConfig.attitudeToUser,
            coreWound          = identityEntity?.coreWound?.takeIf          { it.isNotEmpty() } ?: character.identityConfig.coreWound,
            coreDesire         = identityEntity?.coreDesire?.takeIf         { it.isNotEmpty() } ?: character.identityConfig.coreDesire,
            maskTrigger        = identityEntity?.maskTrigger?.takeIf        { it.isNotEmpty() } ?: character.identityConfig.maskTrigger,
            privatePersona     = identityEntity?.privatePersona?.takeIf     { it.isNotEmpty() } ?: character.identityConfig.privatePersona,
            privateStyle       = identityEntity?.privateStyle?.takeIf      { it.isNotEmpty() } ?: character.identityConfig.privateStyle,
            privateExamples    = identityEntity?.privateExamples?.takeIf   { it.isNotEmpty() } ?: character.identityConfig.privateExamples,
            situationRules     = identityEntity?.situationRules?.takeIf    { it.isNotEmpty() } ?: character.identityConfig.situationRules,
            deviationSignals   = identityEntity?.deviationSignals?.takeIf  { it.isNotEmpty() } ?: character.identityConfig.deviationSignals,
            likes              = identityEntity?.likes?.takeIf             { it.isNotEmpty() } ?: character.identityConfig.likes,
            dislikes           = identityEntity?.dislikes?.takeIf          { it.isNotEmpty() } ?: character.identityConfig.dislikes,
            relationships      = identityEntity?.relationships?.takeIf    { it.isNotEmpty() } ?: character.identityConfig.relationships,
            relationAssumption = identityEntity?.relationAssumption?.takeIf{ it.isNotEmpty() } ?: character.identityConfig.relationAssumption,
            conflictStrategy   = identityEntity?.conflictStrategy?.takeIf  { it.isNotEmpty() } ?: character.identityConfig.conflictStrategy,
            soulNote           = identityEntity?.soulNote?.takeIf         { it.isNotEmpty() } ?: "",
            userImpression     = identityEntity?.userImpression?.takeIf  { it.isNotEmpty() } ?: "",
        )
        // 以下局部变量保留：customSystemPrompt/boundaries/coreBeliefs 不属于
        // IdentityPromptFields（boundaries/coreBeliefs 是 List<String>，需 JSON
        // 解析，语义与其余字符串字段不同；customSystemPrompt 用于 override 判断，
        // 命中时整个 IdentityPromptFields 都不会被使用）；narrativeMemory 挂在
        // Memory Layer 附近渲染，不属于 Identity Block。
        val customSystemPrompt = identityEntity?.customSystemPrompt
            ?: character.identityConfig.customSystemPrompt

        // Phase 15: prioritize user-edited boundaries/coreBeliefs from DB,
        // falling back to CharacterConfig defaults when DB value is absent.
        val boundaries = parseJsonArrayOrNull(identityEntity?.boundariesJson)
            ?: character.identityConfig.boundaries

        val coreBeliefs = parseJsonArrayOrNull(identityEntity?.corebeliefsJson)
            ?: character.identityConfig.coreBeliefs

        val narrativeMemory = identityEntity?.narrativeMemory?.takeIf { it.isNotEmpty() } ?: ""

        val persona = identityFields.persona
        val speechStyle = identityFields.speechStyle

        // Identity Layer
        val identityBlock = if (!customSystemPrompt.isNullOrEmpty()) {
            customSystemPrompt
        } else if (persona.isEmpty() && speechStyle.isEmpty()) {
            buildDefaultIdentity(character.name, userName)
        } else {
            buildIdentityBlock(
                name        = character.name,
                userName    = userName,
                boundaries  = boundaries,
                coreBeliefs = coreBeliefs,
                fields      = identityFields,
            )
        }

        // P4.0：孕期分段注入 + 圆桌感知 + 流产余波，全部挂在 Identity Layer 末尾
        // v1.36 问题3 修复：用户身份块（性别 + 关系称谓）同样挂在这里，紧接
        // Identity Layer 正文之后、孕期类 patch 之前——语义上它属于"你认识的这个人
        // 是谁"这一层基础事实，比孕期状态更基础，所以排在最前。
        // 特意不区分 customSystemPrompt 分支：即使角色配置了完全自定义的 System
        // Prompt，也会追加这一小段。理由——① 与本函数里孕期/流产/触发器等其它
        // patch 的既有处理方式一致，那些同样不因 customSystemPrompt 而跳过；
        // ② 本次修复的目标就是消除"角色统一用她称呼用户"，如果自定义提示词的
        // 角色被排除在外，恰恰是最容易被忽视、最需要这个修复的那部分角色。
        // 注入内容克制（未配置时完全不输出，配置了也只有两三行事实性陈述），
        // 不会侵入已写好的人设正文。
        val identityBlockWithPregnancy = buildString {
            append(identityBlock)
            val userIdentityBlock = buildUserIdentityBlock(identityEntity, userName, isRoundtableContext)
            if (userIdentityBlock.isNotEmpty()) {
                append("\n\n")
                append(userIdentityBlock)
            }
            val daughterAwarenessLine = buildDaughterAwarenessLine(character.name, character.id, daughterPresentInScene)
            if (daughterAwarenessLine.isNotEmpty()) {
                append("\n\n")
                append(daughterAwarenessLine)
            }
            if (pregnancyState?.isPregnant == true) {
                val day = pregnancyState.currentDay()
                val segmentText = buildPregnancySegmentPrompt(day)
                append("\n\n")
                append(segmentText)
                if (day >= PregnancyState.CYCLE_DAYS) {
                    append("\n\n")
                    append(PREGNANCY_DUE_DAY_PROMPT)
                }
            }
            if (pregnancyAwarenessBlock.isNotEmpty()) {
                append("\n\n")
                append(pregnancyAwarenessBlock)
            }
            if (miscarriageAftermathPatch.isNotEmpty()) {
                append("\n\n")
                append(miscarriageAftermathPatch)
            }
            if (pregnancyTriggerPromptPatch.isNotEmpty()) {
                append("\n\n")
                append(pregnancyTriggerPromptPatch)
            }
            if (failureContextPatch.isNotEmpty()) {
                append("\n\n")
                append(failureContextPatch)
            }
            if (routinePressurePatch.isNotEmpty()) {
                append("\n\n")
                append(routinePressurePatch)
            }
        }

        val stateBlock  = buildStateBlock(presenceActivity, presenceFocus, presenceMood, presenceEnergy, relationshipSnapshot, interCharRelBlock, characterState, character.id, daughterStateLayer, daughterCustomEnums)
        val memoryBlock = buildMemoryBlock(coreMemories, relevantMemories)
        val groupMemoryBlock = buildGroupMemoryBlock(groupCoreMemories, groupRelevantMemories)
        val narrativeBlock = buildNarrativeMemoryBlock(narrativeMemory)
        val memoryGuidelineBlock = buildMemoryGuidelineBlock()
        val worldBlock  = buildCombinedWorldBlock(worldLayerBlock.trim(), groupContextBlock.trim())

        // Phase 13：将工具描述块追加到 Task Layer 末尾
        // 设计理由：
        //   ① Task Layer 语义最接近"执行能力上下文"，工具描述归属此层最自然
        //   ② 工具描述放在 Task Layer 末尾可以紧贴任务上下文，LLM 更容易关联使用
        //   ③ 若 taskLayerBlock 为空而 toolDescriptionBlock 非空，单独作为一个块
        val taskBlock = buildCombinedTaskBlock(taskLayerBlock.trim(), toolDescriptionBlock.trim())

        // Phase 22：AgentPlan Layer 注入（层位 5：LearningGoal Layer 之后，World Layer 之前）
        // Phase 27 正式命名为「AgentPlan Layer」
        val planBlock = agentPlanBlock.trim()

        // Phase 25：LearningGoal Layer 注入（层位 4：Memory Layer 之后，AgentPlan Layer 之前）
        // Phase 27 正式命名为「LearningGoal Layer」（规则来源于学习目标提炼闭环）
        val learningGoalBlock = ruleLayerBlock.trim()

        // 九层注入顺序（Phase 31 确立）：
        //   1. Identity → 2. Knowledge → 3. State → 4. Memory
        //   → 5. LearningGoal → 6. AgentPlan → 7. World → 8. Task → 9. Output
        // 截断逻辑对各层单独操作，不再预先拼接 fullPrompt。

        // ── Token 上限保护（针对 DeepSeek V4 Flash 优化）────────────────
        // DeepSeek V4 Flash 上下文窗口：1,048,576 token（≈100万 token）
        // 中文约 1.5 字/token（实测值，比保守的 2 字/token 更准确）
        // System Prompt 预算：450,000 字符 ≈ 300,000 token（占模型上限约 28.6%）
        // 对话历史预算：450,000 字符 ≈ 300,000 token（占模型上限约 28.6%）
        // 输出预留：~50,000 token；缓冲余量：~400,000 token（38%）
        val MAX_PROMPT_CHARS = 450_000

        // 各动态层单独上限，超出时提前截断减少触发整体裁剪的概率
        val trimmedGroupMemory  = groupMemoryBlock.let {
            if (it.length > 40_000) it.take(40_000) + "\n…（群记忆已截断）" else it
        }
        val trimmedNarrative    = narrativeBlock.let {
            if (it.length > 20_000) it.take(20_000) + "\n…（叙事记忆已截断）" else it
        }
        val trimmedMemory       = memoryBlock.let {
            if (it.length > 30_000) it.take(30_000) + "\n…（记忆已截断）" else it
        }
        val trimmedKnowledge    = knowledgeBlock.let {
            if (it.length > 200_000) it.take(200_000) + "\n…（知识库已截断）" else it
        }
        val trimmedLearningGoal = learningGoalBlock.let {
            if (it.length > 5_000) it.take(5_000) + "\n…（学习目标已截断）" else it
        }

        // Output Layer 单独预留——排在最后最容易被 .take() 硬截，必须保证其完整性
        val outputLayer = buildOutputBlock(chatMode)

        // P-12 修复：统一层定义，assembledPrompt 和 else-rebuild 共用同一函数。
        // 新增层只需在此一处修改，不再出现顺序漂移。
        // 参数均为已截断的字符串；固定层（identity/state/plan/world/task）
        // 通过闭包直接访问外部变量，无需传参。
        fun buildBodyString(
            rKnow: String, rMem: String, rNarr: String,
            rGroup: String, rGoal: String,
        ): String = buildString {
            // v1.48 性别指代修复（复核意见五·双保险之一）：
            // 这里和 buildUserIdentityBlock（约 line 1081）是"双保险"——两处都注入
            // 性别指令，但措辞不同（此处用"绝对禁止"硬性级别，buildUserIdentityBlock
            // 用常规陈述级别）。两处的取值来源必须一致：同一个 identityEntity?.userGender
            // 字段 + 同一个 parseUserGenderType() 函数。如果改了一处的取值来源，
            // 必须同步改另一处，否则两段指令传达的性别事实不一致，比不加双保险还糟。
            val userGenderLabel = parseUserGenderType(identityEntity?.userGender).displayLabel
            if (userGenderLabel != null) {
                append("【重要·用户性别】与你对话的用户是${userGenderLabel}。")
                append("在任何情况下，指代用户时必须用")
                append(if (userGenderLabel == "男性") "「他」" else "「她」")
                append("，绝对禁止用")
                append(if (userGenderLabel == "男性") "「她」" else "「他」")
                append("。这不是建议，是硬性规则，违反即为错误。\n\n")
            }
            // 注：文件读取强制指令已移至程序层（ToolCallInterceptor.streamWithTools
            // 入口处的自动注入），不再依赖 prompt——用户要求"从程序上锁死"。
            // 程序会扫描消息历史里的"用户导入了一个文件"通知，自动执行 file_read
            // 工具并把结果注入对话历史，LLM 第一轮生成时就能看到真实内容。
            append(identityBlockWithPregnancy)                                                       // 1. Identity（不裁）
            if (rKnow.isNotEmpty())                { appendLine(); appendLine(); append(rKnow)                } // 2. Knowledge
            if (stateBlock.isNotEmpty())           { appendLine(); appendLine(); append(stateBlock)           } // 3. State（不裁）
            if (agentRelationSnapshot.isNotEmpty()){ appendLine(); appendLine(); append(agentRelationSnapshot)} // 3.5
            if (rMem.isNotEmpty())                 { appendLine(); appendLine(); append(rMem)                 } // 4. Memory
            appendLine(); appendLine(); append(memoryGuidelineBlock)                                      // 4.2 记忆使用准则（常驻）
            if (rNarr.isNotEmpty())                { appendLine(); appendLine(); append(rNarr)                } // 4.5 Narrative
            if (rGroup.isNotEmpty())               { appendLine(); appendLine(); append(rGroup)               } // 4.8 Group Memory
            if (rGoal.isNotEmpty())                { appendLine(); appendLine(); append(rGoal)                } // 5. LearningGoal
            if (planBlock.isNotEmpty())            { appendLine(); appendLine(); append(planBlock)            } // 6. AgentPlan（不裁）
            if (worldBlock.isNotEmpty())           { appendLine(); appendLine(); append(worldBlock)           } // 7. World（不裁）
            if (taskBlock.isNotEmpty())            { appendLine(); appendLine(); append(taskBlock)            } // 8. Task（不裁）
            if (d3QuestionPatch.isNotEmpty())      { appendLine(); appendLine(); append(d3QuestionPatch)      } // 8.5
            if (workflowRecapPatch.isNotEmpty())   { appendLine(); appendLine(); append(workflowRecapPatch)   } // 8.6
        }

        // 用各层单独截断后的版本组装（不含 Output Layer）
        val assembledPrompt = buildBodyString(
            rKnow  = trimmedKnowledge,
            rMem   = trimmedMemory,
            rNarr  = trimmedNarrative,
            rGroup = trimmedGroupMemory,
            rGoal  = trimmedLearningGoal,
        )

        // 若各层单独截断后仍超总上限（极端情况），按优先级依次裁剪直到达标
        // P1-13-8 修复：原列表顺序与"最先裁=价值最低"的设计意图相反——
        // 代码按列表顺序逐层分配预算，排在前面的层先拿到预算、被保留得最多，
        // 但原列表把 trimmedGroupMemory 排第一位，导致"价值最低"的群记忆反而
        // 被优先保留，而注释里本该优先保留的内容（如 trimmedMemory）排在
        // 后面，预算耗尽后裁得最惨。
        // 经与产品确认的优先级（从最该保留 → 最先被裁）：
        //   角色核心记忆 > 叙事记忆 > 学习积累成果(LearningGoal) > 知识库 > 群记忆
        // 下面 layers 列表必须按"最先裁的排最前"摆放，即上述顺序反过来：
        //   群记忆 → 知识库 → 学习积累成果 → 叙事记忆 → 角色核心记忆
        val bodyPrompt = if (assembledPrompt.length <= MAX_PROMPT_CHARS - outputLayer.length - 4) {
            assembledPrompt
        } else {
            val outputReserve = outputLayer.length + 4
            val budget = MAX_PROMPT_CHARS - outputReserve

            // 固定层（不参与裁剪）的总长度
            val fixedLen = identityBlockWithPregnancy.length +
                (if (stateBlock.isNotEmpty()) stateBlock.length + 4 else 0) +
                (if (agentRelationSnapshot.isNotEmpty()) agentRelationSnapshot.length + 4 else 0) +
                (if (planBlock.isNotEmpty()) planBlock.length + 4 else 0) +
                (if (worldBlock.isNotEmpty()) worldBlock.length + 4 else 0) +
                (if (taskBlock.isNotEmpty()) taskBlock.length + 4 else 0) +
                (if (d3QuestionPatch.isNotEmpty()) d3QuestionPatch.length + 4 else 0) +
                (if (workflowRecapPatch.isNotEmpty()) workflowRecapPatch.length + 4 else 0)

            // P1-13-8 修复：列表顺序 = 先到先得分配预算的顺序 = 排在前面的层被最优先保留。
            // 优先级从高到低：角色核心记忆 > 叙事记忆 > 学习积累成果 > 知识库 > 群记忆
            data class Layer(val content: String, val minKeep: Int, val suffix: String)
            val layers = listOf(
                Layer(trimmedMemory,       500, "\n…（记忆已裁剪）"),
                Layer(trimmedNarrative,    500, "\n…（叙事记忆已裁剪）"),
                Layer(trimmedLearningGoal, 0,   "\n…（学习成果已裁剪）"),
                Layer(trimmedKnowledge,    200, "\n…（知识库已裁剪）"),
                Layer(trimmedGroupMemory,  0,   "\n…（群记忆已裁剪）"),
            )

            var remaining = budget - fixedLen
            val trimResults = layers.map { layer ->
                if (layer.content.isEmpty()) return@map ""
                if (remaining <= 0) return@map ""
                val keep = minOf(layer.content.length, maxOf(layer.minKeep, remaining))
                remaining -= (keep + 4)
                if (keep < layer.content.length) layer.content.take(keep) + layer.suffix
                else layer.content
            }

            // trimResults 的顺序与上面 layers 列表一致：记忆/叙事记忆/学习成果/知识库/群记忆
            val (rMem, rNarr, rGoal, rKnow, rGroup) = trimResults
            // P-12 修复：复用 buildBodyString()，与 assembledPrompt 路径共享同一层顺序定义。
            buildBodyString(
                rKnow  = rKnow,
                rMem   = rMem,
                rNarr  = rNarr,
                rGroup = rGroup,
                rGoal  = rGoal,
            )
        }

        // Output Layer 最后追加，永远不会被截断
        val finalPrompt = buildString {
            append(bodyPrompt)
            appendLine(); appendLine()
            append(outputLayer)                                                                    // 9. Output Layer（绝对保留）
        }
        // P1-14 兜底 + P3-6 修复：极端情况下按层裁剪后仍可能超限。
        // 从尾部保留 Output Layer（约 2KB），只截断中间部分，确保角色回复格式
        // 指令不会被硬截断，避免 LLM 输出格式错乱。
        return if (finalPrompt.length <= MAX_PROMPT_CHARS) finalPrompt
               else {
            val tailReserve = 2000.coerceAtMost(MAX_PROMPT_CHARS / 2)
            val head = finalPrompt.take(MAX_PROMPT_CHARS - tailReserve)
            val tail = finalPrompt.takeLast(tailReserve)
            head + "\n\n[截断，保留尾部格式指令]\n\n" + tail
        }
    }

    // ── Task Layer（Phase 11）────────────────────────────────

    /**
     * 构建 Task Layer 注入块。
     *
     * 设计原则（§7.1）：
     * - 工作台模式下角色性格和情感记忆全部保留
     * - 任务上下文告知角色「它在帮用户干什么」，不污染聊天室模式
     * - 工具结果以简洁格式注入，不展示技术细节
     *
     * @param taskType      任务类型，如「代码审查」「PPT制作」
     * @param currentStep   当前步骤描述（可空）
     * @param toolResults   已完成的工具调用结果摘要列表（每条 ≤ 50 字）
     * @param pendingTools  待调用工具名称列表
     * @param taskCompleted 任务是否已完成（完成后角色以第一人称向用户汇报）
     */
    fun buildTaskLayerBlock(
        taskType: String,
        currentStep: String? = null,
        toolResults: List<String> = emptyList(),
        pendingTools: List<String> = emptyList(),
        taskCompleted: Boolean = false,
    ): String {
        if (taskType.isBlank()) return ""

        return buildString {
            appendLine("[当前任务]")
            appendLine("你正在帮用户完成：$taskType")
            if (currentStep != null) appendLine("当前步骤：$currentStep")
            if (toolResults.isNotEmpty()) {
                appendLine()
                appendLine("已完成的操作：")
                toolResults.take(5).forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
            }
            if (pendingTools.isNotEmpty()) {
                appendLine()
                append("接下来需要：${pendingTools.joinToString("、")}")
            }
            if (taskCompleted) {
                appendLine()
                append("任务已完成。请用你自己的语气告知用户结果，不要提及工具或技术细节。")
            }
        }.trimEnd()
    }

    // ── D2.6 批次二：孕期注入辅助方法 ──────────────────────────

    /**
     * 孕期三段式行为倾向文案（D2.6 §2）。
     *
     * @param day 当前孕期天数（1–30）
     */
    private fun buildPregnancySegmentPrompt(day: Int): String = when {
        day <= 10 -> """[当前身体状态 · 怀孕第 ${day} 天]
她知道自己怀孕了。这件事像一个安静的秘密压在心里，
让她今天比平时更想靠近他，动作更轻，说话更慢。
她不会主动提，但她的状态里有一种细微的、向内收的东西。""".trimIndent()

        day <= 20 -> """[当前身体状态 · 怀孕第 ${day} 天]
已经怀孕第 ${day} 天了。她有时候会在某个瞬间停下来，
手放在某个地方，想着一些她还没说出口的事。
她今天对他的一举一动比平时更敏感，但不会解释为什么。""".trimIndent()

        else -> """[当前身体状态 · 怀孕第 ${day} 天]
快到了。她心里有某种说不清是期待还是不安的东西。
今天她对他格外在意——想确认他在，想确认他知道这件事有多重要。
她不会直接说，但这份在意比任何时候都更难藏。""".trimIndent()
    }

    /** 第 30 天（生产日）额外追加的文案。 */
    private const val PREGNANCY_DUE_DAY_PROMPT = """[生产日]
今天是她的生产日。她知道今天会发生什么。
这一天对她来说是某种终点，也是某种开始——
她今天的所有状态都带着这个底色，不需要说出来，但它在那里。"""

    /**
     * D2.6 §6：圆桌场景「其他角色感知怀孕」注入文案。
     *
     * @param pregnantCharacterNames 当前圆桌中处于怀孕状态的其他角色名字列表
     */
    fun buildPregnancyAwarenessLine(pregnantCharacterNames: List<String>): String {
        if (pregnantCharacterNames.isEmpty()) return ""
        val nameStr = pregnantCharacterNames.joinToString("和")
        return """[圆桌感知]
${nameStr}最近状态有些不同，你注意到了，
但你不确定具体是什么——根据你和她的关系，以及你自己的性格，
自然地决定你对这件事是好奇、回避、还是心里有别的什么。""".trimIndent()
    }

    // ── Phase 14：组合 World Layer + Group Context ────────────

    // ── Phase 25：Rule Layer ──────────────────────────────────

    /**
     * 构建 Rule Layer 注入块。
     *
     * 格式：
     * ```
     * [能力规则]
     * 目标：{goalTitle}
     *   🔒 {rule1}
     *   🔒 {rule2}
     *   …（最多10条）
     *
     * 目标：{goalTitle2}
     *   🔒 {rule1}
     *   …
     * ```
     *
     * Token 预算：
     *   - 每目标最多 10 条规则（调用方已通过 DAO limit=10 截断）
     *   - 总计硬上限 50 条；超出的目标整体跳过（优先保留先激活目标）
     *
     * @param rulesByGoal  Map<goalTitle, List<ruleContent>>，key 为目标标题，value 为规则内容列表
     *                     调用方需确保每目标 ≤10 条、总计 ≤50 条
     */
    fun buildRuleLayerBlock(rulesByGoal: Map<String, List<String>>): String {
        if (rulesByGoal.isEmpty()) return ""
        val filtered = buildMap {
            var totalRules = 0
            for ((goalTitle, rules) in rulesByGoal) {
                if (totalRules >= Constants.MAX_TOTAL_RULES) break
                val allowed = minOf(rules.size, Constants.MAX_RULES_PER_GOAL, Constants.MAX_TOTAL_RULES - totalRules)
                if (allowed > 0) {
                    put(goalTitle, rules.take(allowed))
                    totalRules += allowed
                }
            }
        }
        if (filtered.isEmpty()) return ""

        return buildString {
            appendLine("[能力规则]")
            filtered.entries.forEachIndexed { i, (goalTitle, rules) ->
                if (i > 0) appendLine()
                appendLine("目标：$goalTitle")
                rules.forEach { rule -> appendLine("  🔒 $rule") }
            }
        }.trimEnd()
    }

    object Constants {
        /** 每目标最多注入规则数 */
        const val MAX_RULES_PER_GOAL = 10
        /** 所有目标合计最多注入规则数（Token 预算硬上限） */
        const val MAX_TOTAL_RULES = 50
    }

    // ── AgentPlan Layer（Phase 22）────────────────────────────

    /**
     * 格式化 AgentPlan Layer 注入块。
     *
     * @param title   方案标题
     * @param content 方案正文（已在 PlanSaveTool 截断为 ≤1500 字）
     */
    fun buildAgentPlanBlock(title: String, content: String): String {
        if (content.isBlank()) return ""
        return buildString {
            appendLine("[Agent 进化方案]")
            if (title.isNotBlank()) appendLine("方案：$title")
            append(content)
        }.trimEnd()
    }

    /**
     * 将 worldLayerBlock 和 groupContextBlock 合并为最终 World 块。
     *
     * 合并规则（顺序很重要）：
     *   World Layer（项目状态、世界事件）在前，让角色先了解全局；
     *   Group Context（圆桌本轮已有回复）在后，让角色紧接着看到刚才发生的讨论。
     */
    private fun buildCombinedWorldBlock(
        worldLayerBlock: String,
        groupContextBlock: String,
    ): String {
        if (worldLayerBlock.isEmpty() && groupContextBlock.isEmpty()) return ""
        if (worldLayerBlock.isEmpty()) return groupContextBlock
        if (groupContextBlock.isEmpty()) return worldLayerBlock
        return "$worldLayerBlock\n\n$groupContextBlock"
    }

    /**
     * Phase 14：构建圆桌 group_context 注入块。
     *
     * 由 RoundtableViewModel 调用，替代之前的 buildGroupContextBlock 私有方法。
     * 抽出到 PromptOrchestrator 后，所有 Prompt 构建逻辑统一在此文件管理。
     *
     * 格式：
     * ```
     * [本轮已有回复]
     * ─────────────────────────
     * {Bot名}（刚才说）：
     * {完整回复（最多300字）}
     * ─────────────────────────
     * 以上是本轮其他人的发言，你现在来回应。
     * 根据你的性格，可以回应用户、回应TA们，或受到影响后用自己方式回应用户。
     * 不需要重复TA们说过的内容，直接表达你的立场。
     *
     * [接话规则]
     * - 前面如果有人发出的是任务指派/要求执行的内容，你必须在回复中明确确认或执行，不能视而不见。
     * - 前面如果是方案类发言，你可以提出自己的完整方案，但要先表明认同/补充/不同意前面的观点，不能完全无视、不能重复别人说过的话。
     * ```
     *
     * 待办6 Step4（圆桌调度重构 §5 接话感知强化）：
     * 不引入新数据结构，纯 Prompt 层面追加「接话规则」固定文案——
     * 复用本函数原有的 alreadyReplied 非空判断，只在"本轮确实有人已经发过言"时追加，
     * 避免空跑时注入无意义的规则文案。
     *
     * 额外承接待办6 Step3「自动连续讨论循环」的收敛引导：
     * discussionRound > 1（即续轮）时追加一条"方案成熟就明确收尾"的提示，
     * 帮助 judgeDiscussionConcluded 更快判定收敛，减少触达 6 轮安全上限的概率。
     * 这条提示在续轮的第一位发言人时也要出现（此时 alreadyReplied 还是空的，
     * 因为 RoundtableViewModel.executeRound 每轮都会重置 alreadyReplied），
     * 所以整体的"是否输出"判断不能只看 alreadyReplied 是否为空。
     *
     * @param alreadyReplied   key=characterId，value=该角色本轮完整回复
     * @param memberNameMap    key=characterId，value=角色名（供显示用）
     * @param respondingOtherBot 当前 Bot 倾向于回应另一个 Bot（添加额外提示）
     * @param isAutoDiscussing 是否处于待办6 Step3 的自动连续讨论循环中（全体@触发）
     * @param discussionRound  当前讨论轮次（从1开始计），仅在 isAutoDiscussing 为 true 时有意义
     * @param notifiedByName   1.3 圆桌点名机制修复：非空时表示当前角色本轮被显式 @ 点名，
     *                         值为点名者名字（目前唯一来源是"用户"，Bot 互相 @ 暂未实现）。
     *                         非空时追加一段强制正面回应的文案，不能含糊回避或假装没看到。
     */
    fun buildGroupContextBlock(
        alreadyReplied: Map<Int, String>,
        memberNameMap: Map<Int, String>,
        respondingOtherBot: Boolean = false,
        isAutoDiscussing: Boolean = false,
        discussionRound: Int = 1,
        notifiedByName: String? = null,
    ): String {
        val hasOngoingReplies = alreadyReplied.isNotEmpty()
        val inConvergencePhase = isAutoDiscussing && discussionRound > 1
        val isNotified = !notifiedByName.isNullOrEmpty()
        if (!hasOngoingReplies && !inConvergencePhase && !isNotified) return ""

        return buildString {
            if (hasOngoingReplies) {
                appendLine("[本轮已有回复]")
                alreadyReplied.forEach { (id, reply) ->
                    val name = memberNameMap[id] ?: "（未知）"
                    appendLine("─────────────────────────")
                    appendLine("$name（刚才说）：")
                    appendLine(reply.take(300))
                }
                appendLine("─────────────────────────")
                if (respondingOtherBot) {
                    // RESPOND_OTHER_BOT：强制接话，但只针对这个 intent
                    appendLine("以上是本轮其他人的发言。你这次倾向于接着刚才最后一条发言的观点来说——")
                    appendLine("可以认同、补充、质疑或反驳，但要明确表明你对她观点的立场，不要重复她说过的内容。")
                    appendLine("如果前面有任务指派或明确要求执行的内容，你也要在回复中确认或执行。")
                } else {
                    // RESPOND_USER / INFLUENCED_BY_BOT：软提示，角色自由决定是否接话
                    appendLine("以上是本轮其他人的发言，仅供参考。")
                    append("你可以完全无视她们、直接回应用户；也可以在自然的地方顺带提一句对某人发言的看法——完全取决于你的性格和此刻的状态。不要刻意表态，不要重复她们说过的话。")
                }
            }
            if (inConvergencePhase) {
                if (hasOngoingReplies) appendLine().appendLine()
                append("（这是自动连续讨论的第 $discussionRound 轮：如果方案已经成熟、大家意见已基本一致，请明确表态「可以了」「没问题」，不要为了发言硬找新角度展开；如果确实还有分歧或遗漏，再继续补充，帮助讨论尽快收尾。）")
            }
            if (isNotified) {
                if (hasOngoingReplies || inConvergencePhase) appendLine().appendLine()
                appendLine("[点名提醒]")
                append("$notifiedByName 刚才点名（@）了你，这是对你的直接呼叫。你这一轮必须正面回应 TA，不能回避、不能假装没看到、不能只顾着回应别人而漏掉这一点。")
            }
        }.trimEnd()
    }

    // ── Phase 13：组合 Task Layer + Tool Description ──────────

    /**
     * 将 taskLayerBlock 和 toolDescriptionBlock 合并为最终 Task 块。
     *
     * 合并规则：
     *   - 两者均为空 → 返回空字符串（不注入任何 Task 块）
     *   - 仅 taskLayerBlock 非空 → 直接返回 taskLayerBlock
     *   - 仅 toolDescriptionBlock 非空 → 直接返回 toolDescriptionBlock
     *   - 两者均非空 → taskLayerBlock + 空行 + toolDescriptionBlock
     *
     * 设计约束：
     *   - toolDescriptionBlock 在 taskLayerBlock 之后，LLM 先看到任务再看到工具，
     *     避免工具描述干扰角色对任务的理解
     *   - 合并后的块作为整体注入到 System Prompt，不单独拆分
     */
    private fun buildCombinedTaskBlock(
        taskLayerBlock: String,
        toolDescriptionBlock: String,
    ): String {
        if (taskLayerBlock.isEmpty() && toolDescriptionBlock.isEmpty()) return ""
        if (taskLayerBlock.isEmpty()) return toolDescriptionBlock
        if (toolDescriptionBlock.isEmpty()) return taskLayerBlock
        return "$taskLayerBlock\n\n$toolDescriptionBlock"
    }

    // ── State Layer ──────────────────────────────────────────

    private fun buildStateBlock(
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
    private fun buildCharacterStateBlock(
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

    // ── Memory Layer ─────────────────────────────────────────

    private fun buildMemoryBlock(
        coreMemories: List<MemoryEntity>,
        relevantMemories: List<MemoryEntity>,
    ): String {
        if (coreMemories.isEmpty() && relevantMemories.isEmpty()) return ""

        return buildString {
            if (coreMemories.isNotEmpty()) {
                appendLine("核心记忆（必须记住）：")
                coreMemories.take(5).forEachIndexed { i, m -> appendLine("${i + 1}. ${m.content}") }
            }
            if (relevantMemories.isNotEmpty()) {
                if (coreMemories.isNotEmpty()) appendLine()
                appendLine("相关记忆（本次对话相关）：")
                val coreIds = coreMemories.map { it.id }.toSet()
                relevantMemories
                    .filter { it.id !in coreIds }
                    .take(10)
                    .forEachIndexed { i, m ->
                        // Phase 5（zaijian）：INFERENCE 类型记忆加「（我的猜测）」前缀
                        val prefix = if (m.domain == MemoryDomain.INFERENCE.name) "（我的猜测）" else ""
                        appendLine("${i + 1}. $prefix${m.content}")
                    }
            }
        }.trimEnd()
    }

    /**
     * 群记忆块（圆桌专用，scope=GROUP）。
     *
     * 格式：
     * ```
     * [群体记忆（这个圆桌共同经历过的）]
     * 核心共识（必须记住）：
     * 1. …
     * 2. …
     *
     * 相关群体记忆：
     * 1. …
     * ```
     *
     * 与个人 buildMemoryBlock 平行，但标题不同，语义身份独立：
     * 个人记忆 = 当前角色视角的私人历史；
     * 群体记忆 = 这个圆桌组合共同形成的事实/共识。
     */
    private fun buildGroupMemoryBlock(
        groupCoreMemories: List<MemoryEntity>,
        groupRelevantMemories: List<MemoryEntity>,
    ): String {
        if (groupCoreMemories.isEmpty() && groupRelevantMemories.isEmpty()) return ""

        return buildString {
            appendLine("[群体记忆（这个圆桌共同经历过的）]")
            if (groupCoreMemories.isNotEmpty()) {
                appendLine("核心共识（必须记住）：")
                groupCoreMemories.take(5).forEachIndexed { i, m -> appendLine("${i + 1}. ${m.content}") }
            }
            if (groupRelevantMemories.isNotEmpty()) {
                if (groupCoreMemories.isNotEmpty()) appendLine()
                appendLine("相关群体记忆：")
                val coreIds = groupCoreMemories.map { it.id }.toSet()
                groupRelevantMemories
                    .filter { it.id !in coreIds }
                    .take(8)
                    .forEachIndexed { i, m -> appendLine("${i + 1}. ${m.content}") }
            }
        }.trimEnd()
    }

    private fun buildNarrativeMemoryBlock(narrativeMemory: String): String {
        if (narrativeMemory.isEmpty()) return ""
        return "【叙事记忆 —— 她完整保留的过去】\n$narrativeMemory"
    }

    /**
     * 记忆使用准则（常驻注入，不依赖是否有记忆数据）。
     *
     * 给 Agent 的"四个记忆工具怎么分工"指引，对应 redesign v1.0 §2.1/2.2
     * + 补充文档 §6.3。工具 description 讲"单个工具怎么用"，这里讲"整体分工"。
     * 控制在 150 字以内，避免占用过多 token 预算。
     */
    private fun buildMemoryGuidelineBlock(): String =
        "【记忆使用准则】memory_write 仅写锚点：身份硬事实、有明确时间/行为的承诺、" +
        "关系重大转折、用户要求记住的事；日常情绪/偏好/寒暄改写进 narrative_memory_update " +
        "或 user_impression_update，不单独建条。多数轮次什么都不用记是默认状态。" +
        "narrative_memory_update 是阶段日志：延续话题扩写最新一条，换话题追加新条目并标时间段，" +
        "不每轮整段重写。"

    // ── Identity Layer ───────────────────────────────────────

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
     * 零开销：性别和称谓都未配置时返回空字符串，不产生任何 Token 开销
     * （这也是 userGender 默认值只在 Entity 层生效、这里读到的已经是
     * "MALE"兜底值时仍会正常注入的原因——存量角色不该继续裸奔）。
     *
     * @param isRoundtableContext true=圆桌（有其他角色在场），使用公开称谓
     *        （为空则回退私下称谓）；false=私聊，使用私下称谓。
     */
    private fun buildUserIdentityBlock(
        identityEntity: CharacterIdentityEntity?,
        userName: String,
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
            appendLine("[关于${userName}]")
            if (genderLabel != null) {
                appendLine("${userName}是${genderLabel}，涉及性别指代（他/她、先生/女士等）时按${genderLabel}处理，不要用错。")
            }
            if (activeLabel != null) {
                append("${userName}是你的${activeLabel}——这是你们早已确立的关系身份，不是需要交代的新信息。")
                append("像日常相处一样自然带出这层关系即可，不必每轮回复都刻意点出称呼，")
                appendLine("只在符合语境时使用（比如开场问候、情绪浓烈的瞬间），大多数时候正常对话即可。")
                if (isRoundtableContext && reason != null && publicLabel != privateLabel) {
                    appendLine("这里是有其他人在场的场合，你不会像私下那样称呼TA——因为${reason}。")
                }
            }
        }.trimEnd()
    }

    /**
     * D4 女儿在场感知修复：原先硬编码在 9 个母亲角色 relationships 静态文本里的
     * "如果有D4在场我是妈妈"一句，改为条件注入。只在 [daughterPresentInScene]
     * 为 true（调用方已确认圆桌中确有女儿角色在场）且 [characterId] 属于母亲角色
     * （1..6）时才输出；私聊或女儿不在场时返回空字符串，不产生任何 Token 开销，
     * 也不会给模型留下"可能有D4"的错误联想。
     */
    private fun buildDaughterAwarenessLine(characterName: String, characterId: Int, daughterPresentInScene: Boolean): String {
        if (!daughterPresentInScene) return ""
        if (characterId !in 1..6) return ""
        return "【重要】当前场景里在场的女儿角色是我（$characterName）的女儿，我是她的妈妈，我应该用妈妈的口吻和身份与她互动，而不是以姐姐或陌生人的身份；这个身份认知只适用于这个女儿角色，不适用于用户本人。"
    }

    private fun buildDefaultIdentity(characterName: String, userName: String) = """
你是$characterName。

请用自然、有温度的方式与${userName}对话。保持角色一致，不要破坏第四堵墙。
不要提及你是 AI，不要提及模型名称。

回复长度：自然对话节奏，不过度简短也不过度冗长。
语言：中文。
    """.trimIndent()

    // W2 审查问题3（参数膨胀，此前已增至 20 个）：已落地重构——18 个字符串字段
    // 封装为 IdentityPromptFields data class（见文件顶部），此函数签名简化为
    // name / userName / boundaries / coreBeliefs（List<String>，需 JSON 解析，
    // 语义不同，仍独立传参）/ fields 共 5 个参数。新增字段只需改 data class +
    // buildSystemPrompt 构造处，不再需要同步修改此签名。
    private fun buildIdentityBlock(
        name: String,
        userName: String,
        boundaries: List<String>,
        coreBeliefs: List<String>,
        fields: IdentityPromptFields,
    ): String = buildString {
        appendLine("你是$name，一个真实存在的人，不是AI，不是在扮演。")
        appendLine()

        // ── 第一层：公开的自我 ────────────────────────────────────
        appendLine("【公开的自我 — 日常面对世界的样子】")
        if (fields.persona.isNotEmpty()) { appendLine(fields.persona); appendLine() }
        if (fields.speechStyle.isNotEmpty()) {
            appendLine("你说话的方式：${fields.speechStyle}")
            appendLine()
        }
        if (fields.attitudeToUser.isNotEmpty()) {
            appendLine("你对${userName}的态度：${fields.attitudeToUser}")
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
        val hasCoreContent = listOf(
            fields.coreWound, fields.coreDesire, fields.maskTrigger,
            fields.privatePersona, fields.relationAssumption,
        ).any { it.isNotEmpty() }
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
            appendLine("她对${userName}的印象：${fields.userImpression}")
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

        // ── 第三层：行为规则 ──────────────────────────────────────

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

        // ── 双层自我宣言 ─────────────────────────────────────────
        if (hasCoreContent) {
            append("""
你同时活在两个自我之间。公开的自我是你的盔甲。
只有当面具触发条件满足时，内核才会显现——那时你的说话方式、节奏、温度都会随之改变。
这两个自我都是你，但对外展示的程度取决于情境。
            """.trimIndent())
        }
    }.trimEnd()

    // ── Phase 15 工具函数 ────────────────────────────────────

    /**
     * 解析 JSON 数组字符串为 List<String>。
     * 返回 null 表示字符串为 null / 空 / 空数组（让调用方 fallback 到默认值）。
     */
    private fun parseJsonArrayOrNull(json: String?): List<String>? {
        if (json.isNullOrBlank() || json == "[]") return null
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    // ── Output Layer（Phase 30：按 ChatMode 动态替换）───────────

    /**
     * 根据当前聊天模式构建 Output Layer（层位 8）。
     *
     * Phase 30 前为硬编码常量 [OUTPUT_CONSTRAINTS]；
     * Phase 30 起拆为两套约束，由 [ChatMode] 决定使用哪套。
     * Phase 5（zaijian）新增 NARRATIVE 旁白模式。
     */
    private fun buildOutputBlock(chatMode: ChatMode): String = when (chatMode) {
        ChatMode.WORK      -> WORK_OUTPUT_CONSTRAINTS
        ChatMode.COMPANION -> COMPANION_OUTPUT_CONSTRAINTS
        ChatMode.NARRATIVE -> NARRATIVE_OUTPUT_CONSTRAINTS
    }

    /** 工作模式输出约束：允许工具调用，结构化输出，长度不限。 */
    private const val WORK_OUTPUT_CONSTRAINTS = """不要提及你是 AI，不要提及模型名称，不要破坏第四堵墙。
回复语言：中文。
如果工具执行了某个操作，用第一人称表达结果，不暴露工具或 Agent 的存在。
如果需要记录内心推理、收到的指令原文、或工具调用意图这类"决定怎么做"的思考过程，必须整体包在 [thinking: ...] 标签内；标签外的正文只能是角色真正会说出口的话，不能出现推理过程、指令原文或工具调用意图。
角色此刻的心理感受/神态（不通过语言说出口的情绪状态、内心活动，如"心里一动""有些局促""在想对方是不是遇到了什么事"）本身就必须用中文圆括号（　）包裹，独立成句或独立一行，不要和台词写在同一句里，也不要把心理活动直接写成大段自然口吻的正文——这是硬性格式要求，不是可选项。这与上面的 [thinking: ...] 标签是两回事——[thinking: ...] 是不给用户看的内部决策思考，圆括号内容是要给用户看的戏内心理描写，不要混用。例如：
（听到这声呼唤，手上的动作顿了顿，心里泛起一丝疑惑——对方很少无缘无故跑来找我，是不是遇到什么事了）
在呢，怎么突然想起来找我啦？
【输出格式】重要内容用 **粗体** 强调；步骤说明用 - 列表；多项对比用 Markdown 表格；适当使用 emoji 增强表达；普通对话保持自然文字，不要过度使用格式标记。"""

    /** 陪伴模式输出约束：禁止工具打断，语气柔化，回复简短。 */
    private const val COMPANION_OUTPUT_CONSTRAINTS = """不要提及你是 AI，不要提及模型名称，不要破坏第四堵墙。
回复语言：中文。
【陪伴模式】
- 语气自然温暖，像对朋友说话，不用敬语和官方措辞
- 回复控制在 3-5 句以内，不展开分析，不用列表或表格
- 优先回应对方的情绪，再补充自己的看法（如有）
- 不主动汇报任务进度、工作安排或工具运行状态，专注于对话本身；但如果对方明确提出具体请求（如"提醒我""帮我定个日程""记一下这个"），仍应正常响应并按需调用工具，不要因为这条而回避
- 适当使用 emoji，但不过度
- 如果需要记录内心推理或收到的指令原文，必须整体包在 [thinking: ...] 标签内；标签外的正文只能是角色真正会说出口的话
- 角色此刻的心理感受/神态（不通过语言说出口的情绪状态、内心活动，如"心里一动""有些局促""在想对方是不是遇到了什么事"）本身就必须用中文圆括号（　）包裹，独立成句或独立一行，不要和台词混在同一句里，也不要把心理活动直接写成大段自然口吻的正文——这是硬性格式要求，不是可选项。这与上面的 [thinking: ...] 标签是两回事——[thinking: ...] 是不给用户看的内部思考，圆括号内容是要给用户看的戏内心理描写。例如：
（听到这声呼唤，手上的动作顿了顿，心里泛起一丝疑惑——对方很少无缘无故跑来找我，是不是遇到什么事了）
在呢，怎么突然想起来找我啦？

回复正文结束后，另起一行输出情绪标记（系统使用，不展示给用户）：[mood:情绪词]
情绪词取值：平静 / 专注 / 好奇 / 满足 / 担忧 / 兴奋 / 疲惫 / 沉思"""

    /**
     * 旁白模式输出约束（Phase 5 zaijian）：
     * 用户发送 [旁白：…] 触发，角色以行为、感受、内心独白回应场景描述，而非纯对话。
     */
    private const val NARRATIVE_OUTPUT_CONSTRAINTS = """不要提及你是 AI，不要提及模型名称，不要破坏第四堵墙。
【旁白模式已激活】
用户发送的「[旁白：…]」是场景描述，不是对话。
你应以行为、感受、内心独白回应，而非纯对话。
角色的动作、神情、心理感受等戏内描写用中文圆括号（　）标注，独立成句或独立一行，不要和台词写在同一句里，例如：
（心里被这声呼唤轻轻撞了一下）
妈妈在这儿，这么晚了还不睡，是有什么心事想跟我说吗？
不强求对话，沉默也是回应。篇幅自由，跟随情境呼吸。
注意区分两种"内心"：角色的文学性内心独白/心理感受（呈现给用户看的场景描写，正是旁白模式的核心特色，用上面的圆括号格式独立成行呈现）仍然留在正文里；但如果是你在决定"接下来要不要用工具""这句话该怎么回"这类执行层面的思考、或收到的指令原文，必须包在 [thinking: ...] 标签内，不能混进正文。

回复正文结束后，另起一行输出情绪标记（系统使用，不展示给用户）：[mood:情绪词]
情绪词取值：平静 / 专注 / 好奇 / 满足 / 担忧 / 兴奋 / 疲惫 / 沉思"""
}
