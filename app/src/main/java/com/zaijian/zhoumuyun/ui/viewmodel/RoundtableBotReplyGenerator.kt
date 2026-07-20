package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.agent.StreamEvent
import com.zaijian.zhoumuyun.data.agent.ToolCallInterceptor
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import com.zaijian.zhoumuyun.data.manager.PregnancyTriggerManager
import com.zaijian.zhoumuyun.data.memory.MemoryEngine
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.DaughterCustomEnums
import com.zaijian.zhoumuyun.data.model.DaughterStateLayer
import com.zaijian.zhoumuyun.data.model.toCharacterStateLayer
import com.zaijian.zhoumuyun.data.prompt.PromptOrchestrator
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.repository.AgentPlanRepository
import com.zaijian.zhoumuyun.data.repository.CharacterStateRepository
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.EventRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.LearningGoalRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
import com.zaijian.zhoumuyun.data.repository.RoundtableMessageRepository
import com.zaijian.zhoumuyun.domain.ChatTagParser
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.domain.RelationshipEngine
import com.zaijian.zhoumuyun.domain.scheduler.SpeakIntent
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 圆桌场景排除的工具名单（v1.39 圆桌工具调用接入）。
 *
 * `agent_message`（异步给其他角色发消息）、`roundtable_trigger`（发起圆桌讨论）、
 * `task_delegate`（委托任务）三个工具是为"跨角色协作"设计的，语义假设是私聊
 * 场景（一个角色单独面对用户，需要"叫" 其他角色介入）。圆桌场景里角色之间本来
 * 就能直接对话，这三个工具要么语义重复（agent_message 的"异步、下次对话才看到"
 * 在圆桌里不自然），要么可能引发混乱（roundtable_trigger 在已经身处圆桌讨论中
 * 再次触发，语义不清）。
 *
 * 双层防御：此常量同时用于①prompt 层过滤（不让模型看到这些工具描述，见
 * [RoundtableBotReplyGenerator] 和 [RoundtableIdleManager] 里 buildSystemPrompt
 * 调用附近的 buildToolDescriptionBlock）；②执行层拦截（[ToolCallInterceptor.streamWithTools]
 * 的 disabledToolNames 参数）——即使模型意外生成了这些工具的标签，也不会真正执行。
 *
 * 放在类外顶层（文件级，包内可见）是为了让 RoundtableBotReplyGenerator.kt 与
 * RoundtableIdleManager.kt 两条圆桌生成路径共用同一份定义，避免两处硬编码
 * 各写一份、后续增删工具名时忘记同步。
 */
internal val ROUNDTABLE_DISABLED_TOOL_NAMES = setOf(
    "agent_message",
    "roundtable_trigger",
    "task_delegate",
)

class RoundtableBotReplyGenerator(
    private val _uiState: MutableStateFlow<RoundtableUiState>,
    private val memoryRepo: MemoryRepository,
    private val memoryEngine: MemoryEngine,
    private val relationshipEngine: RelationshipEngine,
    private val pregnancyRepo: PregnancyRepository,
    private val characterStateRepo: CharacterStateRepository,
    private val daughterCharacterRepo: DaughterCharacterRepository,
    private val pregnancyTriggerManager: PregnancyTriggerManager,
    private val presenceEngine: PresenceEngine,
    private val identityDao: IdentityRepository,
    private val agentPlanDao: AgentPlanRepository,
    private val learningGoalDao: LearningGoalRepository,
    private val roundtableMessageDao: RoundtableMessageRepository,
    private val eventRepo: EventRepository,
    private val getCurrentRoundtableId: () -> String?,
    private val isInterruptedRef: () -> Boolean,
    private val viewModelScope: CoroutineScope,
    private val REPLY_TIMEOUT_MS: Long = 60_000L,
) {

    private val SENTENCE_BREAK_CHARS = setOf('。', '？', '！', '…', '；', '.', '?', '!', ';')

    private fun RoundtableMessage.toEntity(roundtableId: String) = RoundtableMessageEntity(
        id              = id,
        roundtableId    = roundtableId,
        speakerId       = speakerId,
        speakerName     = speakerName,
        content         = content,
        createdAt       = createdAt,
        replyTargetId   = replyTargetId,
        replyTargetName = replyTargetName,
        turnIndex       = turnIndex,
        thinkingText    = thinkingText,
        psychText       = psychText,
        exportedFileJson = exportedFileJson,
        exportedFilesJson = exportedFilesJson,   // v66（1.7 P3）
    )

    suspend fun generateBotReply(
        bot: CharacterConfig,
        userMessage: String,
        alreadyReplied: Map<Int, String>,
        turnIdx: Int,
        intent: SpeakIntent,
        provider: LLMProvider,
        isNotified: Boolean = false,
    ): String? {

        val coreMemories     = memoryRepo.getCoreMemories(bot.id)
        val relevantMemories = memoryRepo.searchRelevant(bot.id, userMessage, limit = 8)

        // ── 群记忆查询（圆桌专用，scope=GROUP）──
        val rtId = getCurrentRoundtableId()
        val groupCoreMemories = if (rtId != null) memoryRepo.getGroupCoreMemories(rtId) else emptyList()
        val groupRelevantMemories = if (rtId != null) memoryRepo.searchGroupRelevant(rtId, userMessage, limit = 6) else emptyList()

        val relationshipSnapshot = relationshipEngine.buildPromptSnapshot(bot.id)

        val memberNameMap = _uiState.value.activeMembers.associate { it.id to it.name }
        // 待办6 Step4：把 isAutoDiscussing/discussionRound 透传给 buildGroupContextBlock，
        // 用于在续轮场景追加收敛引导文案。两者都是 Step3 已有的 uiState 字段，
        // 这里只是读取后透传，不引入新的数据结构。
        val groupContextBlock = PromptOrchestrator.buildGroupContextBlock(
            alreadyReplied     = alreadyReplied,
            memberNameMap      = memberNameMap,
            respondingOtherBot = intent == SpeakIntent.RESPOND_OTHER_BOT,
            isAutoDiscussing   = _uiState.value.isAutoDiscussing,
            discussionRound    = _uiState.value.discussionRound,
            notifiedByName     = if (isNotified) "用户" else null,
        )

        val roundtableMemberIds = _uiState.value.activeMembers.map { it.id }.filter { it != bot.id }
        val interCharRel = withContext(Dispatchers.IO) {
            relationshipEngine.buildInterCharacterSnapshot(
                forCharacterId = bot.id,
                memberIds      = roundtableMemberIds,
                nameMap        = memberNameMap,
            )
        }

        val identityEntity  = identityDao.getById(bot.id)
        val pregnancyState  = pregnancyRepo.getPregnancy(bot.id)
        // ── 补全 characterState（深层状态，与 ChatViewModel 这次的修法对齐）──
        var characterState  = characterStateRepo.getState(bot.id)
        // ── 复核修复 #7/#13/#20（圆桌路径补齐）：女儿角色单独查询专属状态数据。
        // 与 ChatViewModel 私聊路径同一段逻辑——圆桌和私聊是两条独立的 Prompt
        // 组装路径，私聊那边修好不代表圆桌这边也好，女儿角色同样会出现在圆桌
        // 发言里（activeMembers 不区分角色类型），必须在这里也查一次，否则
        // 圆桌场景下女儿角色的面具/情绪/需求/恐惧仍然渲染成空白/通用占位值。
        // 查询失败静默跳过，不影响本轮圆桌发言。
        var daughterStateLayer: DaughterStateLayer? = null
        var daughterCustomEnums: DaughterCustomEnums? = null
        if (bot.id >= 1000) {
            try {
                val daughterData = daughterCharacterRepo.getCharacterData(bot.id)
                if (daughterData != null) {
                    daughterStateLayer = daughterData.stateLayer
                    daughterCustomEnums = daughterData.customEnums
                    if (characterState == CharacterStateLayer()) {
                        characterState = daughterData.stateLayer.toCharacterStateLayer()
                    }
                }
            } catch (e: Exception) {
                ZLog.w("RoundtableViewModel", "女儿状态数据查询失败，State Layer 渲染将回退到通用描述", e)
            }
        }
        // ── 补全 State Layer（presence 在场状态，和 ChatViewModel 这次的修法对齐）──
        // presence fallback：缓存为空时主动计算一次，结果写入缓存供后续轮次复用
        var presenceSnap = presenceEngine?.getCachedPresence(bot.id)
        if (presenceSnap == null) {
            presenceSnap = presenceEngine?.refreshPresence(bot.id, characterState)
        }
        // ── 补全 miscarriageAftermathPatch（圆桌场景：isOneOnOne 取决于当前
        //    除该角色外是否还有其他角色在场，与 CharacterStateRepository.applySocialMode
        //    判定 SocialMode 的口径一致——roundtableMemberIds 为空才算一对一）──
        val miscarriageAftermathPatch = pregnancyTriggerManager.shouldInjectMiscarriageContext(
            pregnancyState = pregnancyState,
            userText       = userMessage,
            isOneOnOne     = roundtableMemberIds.isEmpty(),
            pressureScale  = 1.0f,
        ) ?: ""
        val otherPregnantNames = withContext(Dispatchers.IO) {
            _uiState.value.activeMembers
                .filter { it.id != bot.id }
                .mapNotNull { m -> pregnancyRepo.getPregnancy(m.id).takeIf { it.isPregnant }?.let { m.name } }
        }
        val pregnancyAwarenessBlock = PromptOrchestrator.buildPregnancyAwarenessLine(otherPregnantNames)

        // D4 女儿在场感知修复：判断当前圆桌在场成员（除 bot 自己）里是否有
        // 女儿角色（id >= 1000），只有确实在场时母亲角色才会被告知"你是妈妈"。
        val daughterPresentInScene = _uiState.value.activeMembers.any { it.id != bot.id && it.id >= 1000 }

        // ── 补全 AgentPlan Layer（角色自己写的进化方案）──
        val activePlan = agentPlanDao.getActive(bot.id)
        val agentPlanBlock = activePlan?.let {
            PromptOrchestrator.buildAgentPlanBlock(it.title, it.content)
        } ?: ""

        // ── 补全 LearningGoal Layer（isLocked=true 的能力规则，按目标分组）──
        val activeGoals = learningGoalDao.getActive(bot.id)
        val rulesByGoal = activeGoals.associate { goal ->
            goal.title to memoryRepo
                .getLockedRules(bot.id, goal.id)
                .map { it.content }
        }
        val ruleLayerBlock = PromptOrchestrator.buildRuleLayerBlock(rulesByGoal)

        // v1.39 圆桌工具调用接入：此前圆桌角色的 systemPrompt 从未传入
        // toolDescriptionBlock（用默认值 ""），角色不知道自己能调用任何工具。
        // 排除 ROUNDTABLE_DISABLED_TOOL_NAMES 里的跨角色协作工具（语义上只
        // 适合私聊场景），保留 file_export/excel_gen/weather 等文件生成/查询类工具。
        val toolDesc = AgentToolRegistry.buildToolDescriptionBlock(excludeNames = ROUNDTABLE_DISABLED_TOOL_NAMES)

        val systemPrompt = PromptOrchestrator.buildSystemPrompt(
            character               = bot,
            identityEntity          = identityEntity,
            coreMemories            = coreMemories,
            relevantMemories        = relevantMemories,
            groupCoreMemories       = groupCoreMemories,
            groupRelevantMemories   = groupRelevantMemories,
            presenceActivity        = presenceSnap?.activity ?: "",
            presenceFocus           = presenceSnap?.goalTitle ?: "",
            presenceMood            = presenceSnap?.mood?.name ?: "",
            presenceEnergy          = presenceSnap?.energy ?: -1,
            relationshipSnapshot    = relationshipSnapshot,
            groupContextBlock       = groupContextBlock,
            interCharRelBlock       = interCharRel,
            agentPlanBlock          = agentPlanBlock,
            ruleLayerBlock          = ruleLayerBlock,
            pregnancyState          = pregnancyState,
            characterState          = characterState,
            daughterStateLayer      = daughterStateLayer,
            daughterCustomEnums     = daughterCustomEnums,
            miscarriageAftermathPatch = miscarriageAftermathPatch,
            pregnancyAwarenessBlock = pregnancyAwarenessBlock,
            // v1.36 问题3：显式声明圆桌场景，用户身份注入据此选用"公开称谓"。
            isRoundtableContext     = true,
            daughterPresentInScene  = daughterPresentInScene,
            toolDescriptionBlock    = toolDesc,
        )

        // 历史：按轮次取最近 20 轮
        // Fix-RoundtableHistoryOrder：此前这里会把"本轮用户消息"从候选历史里过滤掉，
        // 打算在最后统一补一条 LLMMessage("user", userMessage)。这个补丁只对本轮
        // 第一个发言的角色是对的——轮到本轮第二、第三个角色生成回复时，前面角色
        // 本轮已经产生的回复（非 user 消息，不受下面这条过滤影响）已经进了候选历史，
        // 但触发这些回复的那句用户消息却因为上面的过滤被抠掉了，最后又在末尾重新
        // 补一遍。于是 AI 看到的顺序变成"角色A回复了…角色B也回复了…（然后）用户
        // 又问了一遍同样的话"——顺序被拼乱，AI 会以为这句话被问了第二遍，甚至会
        // 现场编一个"新来的人"的说法去圆这个逻辑。
        //
        // 现在不再排除本轮用户消息，让它留在真实发生的顺序里（问题在前、角色回答
        // 在后）。只有当候选历史最后一条已经是"别的角色在说话"（即本轮轮到第二个
        // 及以后的角色）时，才补一条衔接语，且不再复读用户原话——避免同一句话在
        // AI 眼里被问两遍，同时仍然满足接口要求"最后一条必须是 user 角色"这个前提。
        val candidateHistory = _uiState.value.messages
        val recentTurnIndexes = candidateHistory
            .map { it.turnIndex }.distinct().sortedDescending().take(20).toSet()
        val mappedHistory = candidateHistory
            .filter { it.turnIndex in recentTurnIndexes }
            .map { msg ->
                LLMMessage(
                    role    = if (msg.speakerId == "user") "user" else "assistant",
                    content = if (msg.speakerId == "user") msg.content
                              else "[${msg.speakerName}] ${msg.content}",
                )
            }
        val history = if (mappedHistory.lastOrNull()?.role == "user") {
            // 本轮第一个发言的角色：真实的用户消息已经是最后一条，不用再补。
            mappedHistory
        } else {
            // 本轮第二个及以后的角色：最后一条是别的角色的回复，补一条衔接语
            // 而不是复读用户原话，避免造成"同一句话又问了一遍"的错觉。
            mappedHistory + LLMMessage("user", "（其他人正在这个话题上发言，轮到你了，请你也回应一下）")
        }

        val lastSpeakerEntry = if (intent == SpeakIntent.RESPOND_OTHER_BOT) alreadyReplied.keys.lastOrNull() else null
        val replyTargetName  = lastSpeakerEntry?.let { memberNameMap[it] }

        val msgId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                messages = (it.messages + RoundtableMessage(
                    id              = msgId,
                    speakerId       = bot.id.toString(),
                    speakerName     = bot.name,
                    content         = "",
                    isStreaming     = true,
                    turnIndex       = turnIdx,
                    replyTargetId   = lastSpeakerEntry?.toString(),
                    replyTargetName = replyTargetName,
                    isNotified      = isNotified,
                )).toImmutableList()
            )
        }

        var fullReply = ""
        val config = LLMConfig(model = "", maxTokens = 800, temperature = 0.85f, stream = true)
        var interrupted = false
        // v1.39 圆桌工具调用接入：暂存本轮工具产出的文件元数据 JSON，与私聊
        // ChatMessageOrchestrator 同语义。
        // v66（1.7 P3）：改用 list 收集本轮全部文件，与私聊路径同步升级——
        // 不再是"后一次覆盖前一次"。
        val pendingExportedFiles = mutableListOf<String>()

        try {
            withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                ToolCallInterceptor.streamWithTools(
                    provider          = provider,
                    messages          = history,
                    systemPrompt      = systemPrompt,
                    config            = config,
                    disabledToolNames = ROUNDTABLE_DISABLED_TOOL_NAMES,
                ).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            if (isInterruptedRef() && fullReply.isNotEmpty()) {
                                if (fullReply.lastOrNull() in SENTENCE_BREAK_CHARS) interrupted = true
                            }
                            if (!interrupted) {
                                // event.text 是 streamWithTools 已经过 ToolParser 清洗、
                                // 剥掉 <tool:xxx> 标签之后的纯净文本（与私聊侧原始 delta
                                // 的语义不同——原始 delta 是裸文本，可能含未处理的工具标签）。
                                fullReply += event.text
                                // v1.38 圆桌场景补齐：display-only 剥离，与私聊 ChatMessageOrchestrator
                                // 的 stripTagsForDisplay 用法一致——fullReply 变量本身保持原样供
                                // 流式结束后的最终解析使用，这里只是让流式打字机过程中不要把
                                // 尚未闭合/已闭合的 [thinking:...]、[mood:...] 标签原文露给用户看。
                                // 圆括号心理描写不在 stripTagsForDisplay 的处理范围内（与私聊行为
                                // 对齐——圆括号在流式过程中正常显示，直到整条回复生成完毕才会被
                                // stripPsychText 摘出到心理感受小卡，见下方 psychText 解析）。
                                // Fix-StreamingPsychLeak：流式阶段同步剥离圆括号心理描写，不再等到
                                // 整条回复生成完毕才处理——避免用户在角色打字过程中持续看到裸露的
                                // 圆括号原文，说完瞬间又"跳变"成卡片的违和体验。psychText 跟着
                                // 每个 token 增量更新，PsychCard 在流式阶段就能显示（内容会随新
                                // token 到达继续增长，直至本轮回复结束）。
                                val (displayText, streamingPsych) = ChatTagParser.stripTagsForDisplayWithPsych(fullReply)
                                _uiState.update { s ->
                                    s.copy(messages = s.messages.map { msg ->
                                        if (msg.id == msgId) msg.copy(content = displayText, psychText = streamingPsych) else msg
                                    }.toImmutableList())
                                }
                            }
                        }
                        is StreamEvent.ToolStarted -> Unit
                        is StreamEvent.ToolDone -> {
                            // 识别文件类工具产物，暂存 JSON，落库时接回 RoundtableMessage。
                            // 复用私聊 ChatMessageOrchestrator.extractExportedFileJson，两边同一套识别逻辑。
                            // v66（1.7 P3）：add 而不是覆盖赋值。
                            extractExportedFileJson(event.result)?.let { pendingExportedFiles.add(it) }
                        }
                        is StreamEvent.RoundDone -> Unit
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e  // P1-11-4 修复：CancellationException 必须 rethrow，结构化并发需要它传播
        } catch (e: Exception) {
            // 超时或其他异常：保留已生成的回复
            ZLog.w("RoundtableViewModel", "流式生成中断（msgId=$msgId），已生成长度=${fullReply.length}", e)
        }

        // v1.38 圆桌场景补齐：与私聊 ChatMessageOrchestrator 同一套"结构化标记 +
        // 客户端剥离"路径——先剥 [thinking:...]（决策思考，戏外），再剥圆括号
        // （心理感受，戏内），最后剥锚定末尾的 [mood:xxx]（系统内部使用，不展示）。
        // 圆桌此前从未接入这三层解析，三种标签原样落库、原样喂回下一轮 LLM 历史、
        // 原样展示给用户；此处统一在唯一出口剥离，下游（DB / UI / 下一轮 prompt
        // 历史 / alreadyReplied 群上下文）拿到的都是干净文本。
        val (afterThinking, parsedThinking) = ChatTagParser.stripThinkingTag(fullReply.trimEnd())
        val (afterPsych, parsedPsych) = ChatTagParser.stripPsychText(afterThinking)
        val (cleanReply, parsedMood) = ChatTagParser.stripMoodTag(afterPsych)
        if (parsedMood != null) {
            // presenceCache 是全局共享缓存（按 characterId），私聊路径已经在写；
            // 圆桌角色此前从未回写过，导致 RoundtableIdleManager.pickSpontaneousInitiator
            // 读到的情绪权重实际上是私聊那边写的陈旧值——补上这条写入顺带修正了
            // 自发发言的情绪权重计算。
            presenceEngine.updateMoodFromReply(bot.id, parsedMood)
        }

        _uiState.update { s ->
            s.copy(messages = s.messages.map { msg ->
                if (msg.id == msgId) msg.copy(
                    content      = cleanReply,
                    thinkingText = parsedThinking,
                    psychText    = parsedPsych,
                    isStreaming  = false,
                    // v1.39 圆桌工具调用接入：把本轮工具产出的文件元数据接回 UI 消息，
                    // RoundtableBubble 依赖 RoundtableMessage.exportedFiles（由
                    // exportedFilesJson 解析而来）才能渲染 FileExportCard 下载卡片。
                    // v66（1.7 P3）：两个字段都写，exportedFileJson 保留兼容旧路径。
                    exportedFileJson = pendingExportedFiles.lastOrNull(),
                    exportedFilesJson = packExportedFilesJson(pendingExportedFiles),
                ) else msg
            }.toImmutableList())
        }

        // 落库
        // 第7窗口问题4修复：原先落库无 try-catch，UI 已在上方展示了完整回复，
        // 一旦 insert 失败用户毫无感知（该轮对话看似正常，实则未持久化）。
        // 现补上异常处理：失败时记录日志，并写入 uiState.error 接入
        // RoundtableScreen 已有的 LaunchedEffect(uiState.error) → clearError()
        // 展示通路，让用户能实际看到提示。
        getCurrentRoundtableId()?.let { rtIdForDb ->
            if (cleanReply.isNotBlank()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        roundtableMessageDao.insert(
                            RoundtableMessage(
                                id              = msgId,
                                speakerId       = bot.id.toString(),
                                speakerName     = bot.name,
                                content         = cleanReply,
                                turnIndex       = turnIdx,
                                replyTargetId   = lastSpeakerEntry?.toString(),
                                replyTargetName = replyTargetName,
                                thinkingText    = parsedThinking,
                                psychText       = parsedPsych,
                                exportedFileJson = pendingExportedFiles.lastOrNull(),
                                exportedFilesJson = packExportedFilesJson(pendingExportedFiles),
                            ).toEntity(rtIdForDb)
                        )
                    } catch (e: Exception) {
                        ZLog.e("RoundtableViewModel", "圆桌回复落库失败（msgId=$msgId）", e)
                        _uiState.update { it.copy(error = "消息保存失败，可能会在下次打开时丢失") }
                    }
                }
            }
        }

        // 后台记忆提取
        val userEventId = eventRepo.appendMessageEvent(
            actorId     = "user",
            targetId    = bot.id.toString(),
            payloadJson = """{"preview":"${userMessage.take(50)}"}""",
        )
        // W2-4 修复：memoryEngine.onConversationTurn() 之前无 try-catch，
        // 失败时本轮对话的记忆不写入且无任何提示，角色会表现出"失忆"，
        // 用户完全无法感知。补上异常处理，仅记录日志（这是后台补写，
        // 不影响已展示的对话内容，故不额外弹 uiState.error 打扰用户）。
        viewModelScope.launch(Dispatchers.IO) {
            try {
                memoryEngine.onConversationTurn(
                    characterId    = bot.id,
                    userMessage    = userMessage,
                    assistantReply = cleanReply,
                    userEventId    = userEventId,
                )
            } catch (e: Exception) {
                ZLog.e("RoundtableViewModel", "单角色记忆提取失败 characterId=${bot.id}", e)
            }
        }
        // ── 群记忆写入（圆桌专用，scope=GROUP）──
        // W2-4 修复：同上，onRoundtableTurn() 补 try-catch，避免圆桌集体
        // 记忆静默丢失。
        if (rtId != null && cleanReply.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    memoryEngine.onRoundtableTurn(
                        roundtableId   = rtId,
                        speakerId      = bot.id,
                        userMessage    = userMessage,
                        assistantReply = cleanReply,
                    )
                } catch (e: Exception) {
                    ZLog.e("RoundtableViewModel", "群记忆提取失败 roundtableId=$rtId", e)
                }
            }
        }

        return cleanReply.ifBlank { null }
    }
}