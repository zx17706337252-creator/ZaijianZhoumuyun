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

/**
 * Prompt Orchestration Layer
 *
 * 九层架构（Phase 31 确立，设计方案 §11）：
 *
 * | 层位 | Layer            | Token 上限          | 状态                         |
 * |------|------------------|---------------------|------------------------------|
 * |  1   | Identity         | 1500                | ✅ Phase 7                   |
 * |  2   | Knowledge ★      | 动态                 | ✅ Phase 31（知识库全文）       |
 * |  3   | State            | 500                 | ✅ Phase 9                   |
 * |  4   | Memory           | 1000                | ✅ Phase 8                   |
 * |  5   | LearningGoal ★   | 动态（每目标 ≤10条）  | ✅ Phase 25（Phase 27 正式命名）|
 * |  6   | AgentPlan ★      | 500                 | ✅ Phase 22（Phase 27 正式命名）|
 * |  7   | World            | 1000                | ✅ Phase 10                  |
 * |  8   | Task             | 1500                | ✅ Phase 11                  |
 * |  9   | Output           | 500                 | ✅ Phase 7                   |
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
        // ── 群记忆（圆桌专用，scope=GROUP）
        // 由 RoundtableViewModel 查询后传入；非圆桌场景传空列表（默认值），零开销。
        // 注入位置：Memory Layer（层位 4）末尾，Narrative Memory（4.5）之前。
        groupCoreMemories: List<MemoryEntity> = emptyList(),
        groupRelevantMemories: List<MemoryEntity> = emptyList(),
    ): String {
        val persona = identityEntity?.persona
            ?.takeIf { it.isNotEmpty() }
            ?: character.identityConfig.persona

        val speechStyle = identityEntity?.speechStyle
            ?.takeIf { it.isNotEmpty() }
            ?: character.identityConfig.speechStyle

        val attitudeToUser = identityEntity?.attitudeToUser
            ?.takeIf { it.isNotEmpty() }
            ?: character.identityConfig.attitudeToUser

        val customSystemPrompt = identityEntity?.customSystemPrompt
            ?: character.identityConfig.customSystemPrompt

        // Phase 15: prioritize user-edited boundaries/coreBeliefs from DB,
        // falling back to CharacterConfig defaults when DB value is absent.
        val boundaries = parseJsonArrayOrNull(identityEntity?.boundariesJson)
            ?: character.identityConfig.boundaries

        val coreBeliefs = parseJsonArrayOrNull(identityEntity?.corebeliefsJson)
            ?: character.identityConfig.coreBeliefs

        // ── 新增：读取内核字段（DB优先，fallback到CharacterConfig）──
        val coreWound        = identityEntity?.coreWound?.takeIf       { it.isNotEmpty() } ?: character.identityConfig.coreWound
        val coreDesire       = identityEntity?.coreDesire?.takeIf      { it.isNotEmpty() } ?: character.identityConfig.coreDesire
        val maskTrigger      = identityEntity?.maskTrigger?.takeIf     { it.isNotEmpty() } ?: character.identityConfig.maskTrigger
        val privatePersona   = identityEntity?.privatePersona?.takeIf  { it.isNotEmpty() } ?: character.identityConfig.privatePersona
        val privateStyle     = identityEntity?.privateStyle?.takeIf    { it.isNotEmpty() } ?: character.identityConfig.privateStyle
        val privateExamples  = identityEntity?.privateExamples?.takeIf { it.isNotEmpty() } ?: character.identityConfig.privateExamples
        val situationRules   = identityEntity?.situationRules?.takeIf  { it.isNotEmpty() } ?: character.identityConfig.situationRules
        val deviationSignals = identityEntity?.deviationSignals?.takeIf{ it.isNotEmpty() } ?: character.identityConfig.deviationSignals

        // ── 附加（NyxChat V18 A.1/A.2）：likes / dislikes / relationships ──
        val likes         = identityEntity?.likes?.takeIf         { it.isNotEmpty() } ?: character.identityConfig.likes
        val dislikes      = identityEntity?.dislikes?.takeIf      { it.isNotEmpty() } ?: character.identityConfig.dislikes
        val relationships = identityEntity?.relationships?.takeIf { it.isNotEmpty() } ?: character.identityConfig.relationships

        // ── Soul/Memory/User 三模块 ──────────────────────────────
        val soulNote        = identityEntity?.soulNote?.takeIf        { it.isNotEmpty() } ?: ""
        val narrativeMemory = identityEntity?.narrativeMemory?.takeIf { it.isNotEmpty() } ?: ""
        val userImpression  = identityEntity?.userImpression?.takeIf  { it.isNotEmpty() } ?: ""

        // Identity Layer
        val identityBlock = if (!customSystemPrompt.isNullOrEmpty()) {
            customSystemPrompt
        } else if (persona.isEmpty() && speechStyle.isEmpty()) {
            buildDefaultIdentity(character.name, userName)
        } else {
            buildIdentityBlock(
                name             = character.name,
                persona          = persona,
                speechStyle      = speechStyle,
                attitudeToUser   = attitudeToUser,
                boundaries       = boundaries,
                coreBeliefs      = coreBeliefs,
                userName         = userName,
                coreWound        = coreWound,
                coreDesire       = coreDesire,
                maskTrigger      = maskTrigger,
                privatePersona   = privatePersona,
                privateStyle     = privateStyle,
                privateExamples  = privateExamples,
                situationRules   = situationRules,
                deviationSignals = deviationSignals,
                likes            = likes,
                dislikes         = dislikes,
                relationships    = relationships,
                soulNote         = soulNote,
                userImpression   = userImpression,
            )
        }

        // P4.0：孕期分段注入 + 圆桌感知 + 流产余波，全部挂在 Identity Layer 末尾
        val identityBlockWithPregnancy = buildString {
            append(identityBlock)
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
            append(identityBlockWithPregnancy)                                                       // 1. Identity（不裁）
            if (rKnow.isNotEmpty())                { appendLine(); appendLine(); append(rKnow)                } // 2. Knowledge
            if (stateBlock.isNotEmpty())           { appendLine(); appendLine(); append(stateBlock)           } // 3. State（不裁）
            if (agentRelationSnapshot.isNotEmpty()){ appendLine(); appendLine(); append(agentRelationSnapshot)} // 3.5
            if (rMem.isNotEmpty())                 { appendLine(); appendLine(); append(rMem)                 } // 4. Memory
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
        // P1-14 兜底：极端情况下（outputLayer 本身超大）按层裁剪后仍可能超限，
        // 硬截保证绝不超出 MAX_PROMPT_CHARS，避免 API 400。
        return if (finalPrompt.length <= MAX_PROMPT_CHARS) finalPrompt
               else finalPrompt.take(MAX_PROMPT_CHARS)
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
                ?.let { "，次情绪：${it.toChineseDescription()}" }
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

    // ── Identity Layer ───────────────────────────────────────

    private fun buildDefaultIdentity(characterName: String, userName: String) = """
你是$characterName。

请用自然、有温度的方式与${userName}对话。保持角色一致，不要破坏第四堵墙。
不要提及你是 AI，不要提及模型名称。

回复长度：自然对话节奏，不过度简短也不过度冗长。
语言：中文。
    """.trimIndent()

    private fun buildIdentityBlock(
        name: String,
        persona: String,
        speechStyle: String,
        attitudeToUser: String,
        boundaries: List<String>,
        coreBeliefs: List<String>,
        userName: String,
        // ── 新增参数（默认空字符串，旧调用兼容）──
        coreWound: String = "",
        coreDesire: String = "",
        maskTrigger: String = "",
        privatePersona: String = "",
        privateStyle: String = "",
        privateExamples: String = "",
        situationRules: String = "",
        deviationSignals: String = "",
        // ── 附加（NyxChat V18 A.1/A.2）──
        /** 喜好列表（A.1）：注入在 coreBeliefs 之后，内核之前 */
        likes: String = "",
        /** 厌恶列表（A.1）：紧接 likes 之后 */
        dislikes: String = "",
        /** 人际关系行为逻辑（A.2）：独立块，注入在 situationRules 之前 */
        relationships: String = "",
        // ── Soul/Memory/User 三模块 ─────────────────────────────────
        soulNote: String = "",
        userImpression: String = "",
    ): String = buildString {
        appendLine("你是$name，一个真实存在的人，不是AI，不是在扮演。")
        appendLine()

        // ── 第一层：公开的自我 ────────────────────────────────────
        appendLine("【公开的自我 — 日常面对世界的样子】")
        if (persona.isNotEmpty()) { appendLine(persona); appendLine() }
        if (speechStyle.isNotEmpty()) {
            appendLine("你说话的方式：$speechStyle")
            appendLine()
        }
        if (attitudeToUser.isNotEmpty()) {
            appendLine("你对${userName}的态度：$attitudeToUser")
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
        if (likes.isNotBlank()) {
            appendLine("你喜欢：$likes")
            appendLine()
        }
        if (dislikes.isNotBlank()) {
            appendLine("你厌恶：$dislikes")
            appendLine()
        }

        // ── 第二层：内核（只在字段有内容时才追加）────────────────
        val hasCoreContent = listOf(coreWound, coreDesire, maskTrigger, privatePersona).any { it.isNotEmpty() }
        if (hasCoreContent) {
            appendLine("【内核 — 公开面具下藏着的真实，不轻易暴露】")
            if (coreWound.isNotEmpty())      appendLine("• 未愈的伤：$coreWound")
            if (coreDesire.isNotEmpty())     appendLine("• 真正渴望：$coreDesire")
            if (maskTrigger.isNotEmpty())    appendLine("• 面具何时碎裂：$maskTrigger")
            if (privatePersona.isNotEmpty()) appendLine("• 碎裂后的真实面目：$privatePersona")
            appendLine()
        }

        // ── Soul/Memory/User：人设备忘录 ──────────────────────────
        if (soulNote.isNotEmpty()) {
            appendLine("【人设备忘录 —— 她自己想被记住的样子】")
            appendLine(soulNote)
            appendLine()
        }
        if (userImpression.isNotEmpty()) {
            appendLine("她对${userName}的印象：$userImpression")
            appendLine()
        }

        if (privateStyle.isNotEmpty()) {
            appendLine("【私下说话方式 — 面具碎裂后才会出现】")
            appendLine(privateStyle)
            appendLine()
        }

        if (privateExamples.isNotEmpty()) {
            appendLine("【私下对话示例 — 面具松动时参考语气和节奏】")
            appendLine(privateExamples)
            appendLine()
        }

        // ── 第三层：行为规则 ──────────────────────────────────────

        // A.2 修复：relationships 作为独立行为指令块，位于 situationRules 之前
        if (relationships.isNotBlank()) {
            appendLine("【人际关系——行为逻辑】")
            appendLine(relationships)
            appendLine()
        }

        if (situationRules.isNotEmpty()) {
            appendLine("【情境反应规则 — 不同触发情境下的行为模式，严格遵守】")
            appendLine(situationRules)
            appendLine()
        }

        if (deviationSignals.isNotEmpty()) {
            appendLine("【有心事时的外显信号 — 区别于正常状态的细节，自然融入，不必全部出现】")
            appendLine(deviationSignals)
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
【输出格式】重要内容用 **粗体** 强调；步骤说明用 - 列表；多项对比用 Markdown 表格；适当使用 emoji 增强表达；普通对话保持自然文字，不要过度使用格式标记。"""

    /** 陪伴模式输出约束：禁止工具打断，语气柔化，回复简短。 */
    private const val COMPANION_OUTPUT_CONSTRAINTS = """不要提及你是 AI，不要提及模型名称，不要破坏第四堵墙。
回复语言：中文。
【陪伴模式】
- 语气自然温暖，像对朋友说话，不用敬语和官方措辞
- 回复控制在 3-5 句以内，不展开分析，不用列表或表格
- 优先回应对方的情绪，再补充自己的看法（如有）
- 不主动提及任务、工具或工作安排，专注于对话本身
- 适当使用 emoji，但不过度
- 如果需要记录内心推理或收到的指令原文，必须整体包在 [thinking: ...] 标签内；标签外的正文只能是角色真正会说出口的话

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
可以用括号标注动作或神情（例：「（她没有回头，只是轻声）……」）。
不强求对话，沉默也是回应。篇幅自由，跟随情境呼吸。
注意区分两种"内心"：角色的文学性内心独白（呈现给用户看的场景描写，正是旁白模式的核心特色）仍然留在正文里；但如果是你在决定"接下来要不要用工具""这句话该怎么回"这类执行层面的思考、或收到的指令原文，必须包在 [thinking: ...] 标签内，不能混进正文。

回复正文结束后，另起一行输出情绪标记（系统使用，不展示给用户）：[mood:情绪词]
情绪词取值：平静 / 专注 / 好奇 / 满足 / 担忧 / 兴奋 / 疲惫 / 沉思"""
}
