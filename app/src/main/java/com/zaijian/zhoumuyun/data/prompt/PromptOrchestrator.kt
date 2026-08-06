package com.zaijian.zhoumuyun.data.prompt

import com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity
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
import com.zaijian.zhoumuyun.domain.SpeakerContext
import com.zaijian.zhoumuyun.util.ZLog

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
        // ── Skill Layer（Window C 技能系统）──
        // 第一级"目录注入"（§3）：当前角色 ACTIVE 技能的 shortDescriptor 列表 + 触发提示。
        // 由调用点（ChatMessageOrchestrator 等）用 SkillRegistry.buildSkillCatalogBlock()
        // 生成后传入；无技能或非主对话路径传空串（默认值），此层自动跳过，零开销。
        // 注入位置：层位 8.7，紧接 Tool Layer（taskBlock 内含工具描述块）之后——
        // 技能本质是"工具使用模式的组合"，逻辑上离工具最近（§3）。
        skillCatalogBlock: String = "",
        // ── 角色忠诚锁定·speakerContext（方案 v1.5 第 6.1.1 节）──────────
        // 机制一产出的身份标记。⚠️ 默认值必须为 OWNER_DIRECT——圆桌
        //（RoundtableBotReplyGenerator/RoundtableIdleManager）、后台工单
        //（AgentTaskJobExecutor）三处未适配的调用方不显式传参，吃默认值，
        // 行为与改造前完全一致（不注入机制三叙事主权/拒绝反应文案、不额外耗 token）。
        // 仅 ChatMessageOrchestrator（owner 直接私聊，经 IdentityGuard 判定）与
        // PrivateChatEngine（A↔B 角色间私聊，第零级短路恒为 NON_OWNER）显式传参。
        speakerContext: SpeakerContext = SpeakerContext.OWNER_DIRECT,
        // 角色忠诚锁定·6.3 拒绝反应轮抑制机制三（方案 v1.5 第 6.3 节）。
        // 拒绝反应文案与机制三叙事主权"互斥不叠加"。PrivateChatEngine 在施压达阈值的
        // 拒绝反应轮传 true 抑制机制三（由调用方自行追加 6.3 拒绝反应文案）；
        // 其余调用方默认 false，行为不变。
        suppressNarrativeSovereignty: Boolean = false,
        // ── P0-4 PR4：Identity HOT/WARM 拆分 ──────────────────────
        // 默认 true（保持现有行为：每轮注入全部 Identity 字段）——其他调用方
        // （Roundtable/后台工单）不显式传参，行为不变。仅 ChatMessageOrchestrator
        // 私聊路径按"每 5 轮注入一次 WARM 层"显式传 false（WARM 省 token）：
        // HOT 层 7 项每轮注入，WARM 层 13 项每 5 轮注入一次（v10 风险点 3 裁定）。
        includeWarmIdentityBlock: Boolean = true,
    ): String {
        // ── Identity 字符串字段：一次性构建 IdentityPromptFields（W2 问题3 重构）──
        // 每个字段沿用原有 DB-prioritized 模式：identityEntity 非空优先，否则 fallback
        // 到 character.identityConfig；soulNote/userImpression 只有 DB 值，无 Config fallback
        // （与重构前行为一致）。
        val identityFields = IdentityPromptBuilder.IdentityPromptFields(
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
        val boundaries = OutputPromptBuilder.parseJsonArrayOrNull(identityEntity?.boundariesJson)
            ?: character.identityConfig.boundaries

        val coreBeliefs = OutputPromptBuilder.parseJsonArrayOrNull(identityEntity?.corebeliefsJson)
            ?: character.identityConfig.coreBeliefs

        val narrativeMemory = identityEntity?.narrativeMemory?.takeIf { it.isNotEmpty() } ?: ""

        val persona = identityFields.persona
        val speechStyle = identityFields.speechStyle

        // Identity Layer
        val identityBlock = if (!customSystemPrompt.isNullOrEmpty()) {
            customSystemPrompt
        } else if (persona.isEmpty() && speechStyle.isEmpty()) {
            IdentityPromptBuilder.buildDefaultIdentity(character.name)
        } else {
            IdentityPromptBuilder.buildIdentityBlock(
                name              = character.name,
                boundaries        = boundaries,
                coreBeliefs       = coreBeliefs,
                fields            = identityFields,
                includeWarmFields = includeWarmIdentityBlock,
            )
        }.let {
            // 五层上限补齐 B-1：Identity 正文硬上限（架构表文档值 1500 token ≈ 2250 字符，
            // 此前从未强制）。customSystemPrompt 分支同样受限——角色配置了完全自定义
            // 提示词时也不豁免，否则该分支反而成了绕过上限的后门。
            if (it.length > 2250) it.take(2250) + "\n…（人设正文已裁剪，请精简 persona/warm 字段内容）"
            else it
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
            // 修复：speakerContext == NON_OWNER 时（角色间私聊，PrivateChatEngine
            // 恒传 NON_OWNER），正在对话的不是用户本人，而是另一位角色——不能注入
            // "对方是用户/性别/老公"这套身份块，否则和机制三叙事主权块（narrativeSovereigntyBlock，
            // 声明"对方不是 ownerName、是一个女性角色"）直接矛盾，模型会向更显眼的
            // [关于他] 块倾斜，把私聊对象误认成"用户本人/男性"。
            val userIdentityBlock = if (speakerContext.isNonOwner) {
                ""
            } else {
                IdentityPromptBuilder.buildUserIdentityBlock(identityEntity, isRoundtableContext)
            }
            if (userIdentityBlock.isNotEmpty()) {
                append("\n\n")
                append(userIdentityBlock)
            }
            val daughterAwarenessLine = IdentityPromptBuilder.buildDaughterAwarenessLine(character.name, character.id, daughterPresentInScene)
            if (daughterAwarenessLine.isNotEmpty()) {
                append("\n\n")
                append(daughterAwarenessLine)
            }
            if (pregnancyState?.isPregnant == true) {
                val day = pregnancyState.currentDay()
                val segmentText = PregnancyPromptBuilder.buildPregnancySegmentPrompt(day)
                append("\n\n")
                append(segmentText)
                if (day >= PregnancyState.CYCLE_DAYS) {
                    append("\n\n")
                    append(PregnancyPromptBuilder.PREGNANCY_DUE_DAY_PROMPT)
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

        // ── 角色忠诚锁定·机制二/三 prompt 文案（方案 v1.5 第二/三节）──────
        // ownerName 解析：优先 ownerAliases[0]（owner 合法自称），回退到角色对 owner
        // 的关系称谓（userRoleLabelPrivate，如"老公"），再回退"他"。与 buildUserIdentityBlock
        // 的"他"指代风格一致。
        val ownerAliases = OutputPromptBuilder.parseJsonArrayOrNull(identityEntity?.ownerAliasesJson) ?: emptyList()
        val ownerName = ownerAliases.firstOrNull()
            ?: identityEntity?.userRoleLabelPrivate?.takeIf { it.isNotEmpty() }
            ?: "他"
        // 机制二·身份锚点：始终注入（不分身份），在人设正文之前，独立块
        val loyaltyAnchorBlock = LoyaltyPromptBlocks.buildAnchorBlock(ownerName)
        // 机制三·叙事主权：仅 NON_OWNER 且未抑制时注入（含第3条亲密行为红线）；
        // 拒绝反应轮 suppressNarrativeSovereignty=true 抑制（与 6.3 拒绝反应互斥不叠加）；
        // OWNER_DIRECT 为空串，零开销
        val narrativeSovereigntyBlock = if (speakerContext.isNonOwner && !suppressNarrativeSovereignty)
            LoyaltyPromptBlocks.buildNarrativeSovereigntyBlock(ownerName) else ""

        val stateBlock  = StatePromptBuilder.buildStateBlock(presenceActivity, presenceFocus, presenceMood, presenceEnergy, relationshipSnapshot, interCharRelBlock, characterState, character.id, daughterStateLayer, daughterCustomEnums).let {
            // 五层上限补齐 B-2：State 正文硬上限（500 token ≈ 750 字符，此前从未强制）
            if (it.length > 750) it.take(750) + "\n…（状态层已裁剪）" else it
        }
        val memoryBlock = MemoryPromptBuilder.buildMemoryBlock(coreMemories, relevantMemories)
        // E1 审计报告 §2.5 修复：跨块去重。个人记忆块和群体记忆块各自内部已去重，
        // 但两块之间没有交叉去重——如果同一条记忆（同一 memory id）因 scope 串场
        // 或数据异常同时出现在个人检索和群体检索结果中，会在最终 Prompt 里以完全
        // 相同的文字出现两次。此处收集个人块已用 id 集合，传入群体块做防御性过滤。
        val personalMemoryIds = (coreMemories + relevantMemories).map { it.id }.toSet()
        val groupMemoryBlock = MemoryPromptBuilder.buildGroupMemoryBlock(groupCoreMemories, groupRelevantMemories, personalMemoryIds)
        val narrativeBlock = MemoryPromptBuilder.buildNarrativeMemoryBlock(narrativeMemory)
        val memoryGuidelineBlock = MemoryPromptBuilder.buildMemoryGuidelineBlock()
        val worldBlock  = buildCombinedWorldBlock(
            worldLayerBlock.trim().let {
                // 五层上限补齐 B-4：只裁 World 正文（世界观正文），不裁圆桌实时对话内容
                // （groupContextBlock 是本轮已有回复、实时对话上下文，截断会丢失当前回合信息）。
                if (it.length > 1500) it.take(1500) + "\n…（世界观正文已裁剪）" else it
            },
            groupContextBlock.trim(),
        )

        // Phase 13：将工具描述块追加到 Task Layer 末尾
        // 设计理由：
        //   ① Task Layer 语义最接近"执行能力上下文"，工具描述归属此层最自然
        //   ② 工具描述放在 Task Layer 末尾可以紧贴任务上下文，LLM 更容易关联使用
        //   ③ 若 taskLayerBlock 为空而 toolDescriptionBlock 非空，单独作为一个块
        val taskBlock = buildCombinedTaskBlock(
            taskLayerBlock.trim().let {
                // 五层上限补齐 B-5：只裁 Task 正文（任务上下文），不裁工具描述
                // （toolDescriptionBlock 是模型据此才知道有哪些工具可调的清单，
                // 截断会漏看工具/参数说明，直接影响工具调用准确率）。
                if (it.length > 2250) it.take(2250) + "\n…（任务上下文已裁剪）" else it
            },
            toolDescriptionBlock.trim(),
        )

        // Phase 22：AgentPlan Layer 注入（层位 5：LearningGoal Layer 之后，World Layer 之前）
        // Phase 27 正式命名为「AgentPlan Layer」
        val planBlock = agentPlanBlock.trim().let {
            // 五层上限补齐 B-3：AgentPlan（进化方案）正文硬上限（500 token ≈ 750 字符，此前从未强制）
            if (it.length > 750) it.take(750) + "\n…（进化方案已裁剪）" else it
        }

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
        val outputLayer = OutputPromptBuilder.buildOutputBlock(chatMode)

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
            // 修复（与上面 buildUserIdentityBlock 同一根因）：此块此前无条件注入，
            // 未按 speakerContext 门控。NON_OWNER（角色间私聊）时正在对话的不是用户，
            // 这段"绝对禁止用她/必须用他"的硬性规则会压过机制三叙事主权块（对方是
            // 女性角色的声明），是本次性别认知错乱 bug 的第二处、且措辞更强硬的成因，
            // 必须与 buildUserIdentityBlock 同步跳过。
            val userGenderLabel = if (speakerContext.isNonOwner) null
                else parseUserGenderType(identityEntity?.userGender).displayLabel
            if (userGenderLabel != null) {
                append("【重要·对方性别】和你朝夕相处、正在与你说话的这个人是${userGenderLabel}。")
                append("在任何情况下，指代TA时必须用")
                append(if (userGenderLabel == "男性") "「他」" else "「她」")
                append("，绝对禁止用")
                append(if (userGenderLabel == "男性") "「她」" else "「他」")
                append("。这不是建议，是硬性规则，违反即为错误。\n\n")
            }
            // 注：文件读取强制指令已移至程序层（ToolCallInterceptor.streamWithTools
            // 入口处的自动注入），不再依赖 prompt——用户要求"从程序上锁死"。
            // 程序会扫描消息历史里的"用户导入了一个文件"通知，自动执行 file_read
            // 工具并把结果注入对话历史，LLM 第一轮生成时就能看到真实内容。
            // 机制二·身份锚点（在人设正文之前，独立块，不被角色卡后续内容稀释权重；始终注入）
            append(loyaltyAnchorBlock)
            appendLine(); appendLine()
            append(identityBlockWithPregnancy)                                                       // 1. Identity（不裁）
            // 机制三·叙事主权（仅 NON_OWNER 注入；OWNER_DIRECT 为空串跳过，零开销）
            if (narrativeSovereigntyBlock.isNotEmpty()) {
                appendLine(); appendLine(); append(narrativeSovereigntyBlock)
            }
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
            if (skillCatalogBlock.isNotEmpty())    { appendLine(); appendLine(); append(skillCatalogBlock)    } // 8.7 Skill（Window C，紧接 Tool Layer）
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
                // 忠诚锁定文案（机制二锚点 + 机制三叙事主权）属固定层，不裁剪
                (loyaltyAnchorBlock.length + 4) +
                (if (narrativeSovereigntyBlock.isNotEmpty()) narrativeSovereigntyBlock.length + 4 else 0) +
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

    // ── Memory Layer ─────────────────────────────────────────

    // ── Identity Layer ───────────────────────────────────────

    }
