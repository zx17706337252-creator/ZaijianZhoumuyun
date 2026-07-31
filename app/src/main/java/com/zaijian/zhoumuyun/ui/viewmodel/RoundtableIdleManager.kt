package com.zaijian.zhoumuyun.ui.viewmodel

import android.content.SharedPreferences
import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.agent.SkillRegistry
import com.zaijian.zhoumuyun.data.agent.StreamEvent
import com.zaijian.zhoumuyun.data.agent.ToolCallInterceptor
import com.zaijian.zhoumuyun.data.agent.VaultCallContext
import com.zaijian.zhoumuyun.data.agent.VaultScope
import com.zaijian.zhoumuyun.data.agent.withVaultContext
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.DaughterCustomEnums
import com.zaijian.zhoumuyun.data.model.DaughterStateLayer
import com.zaijian.zhoumuyun.data.model.toCharacterStateLayer
import com.zaijian.zhoumuyun.data.prompt.PromptOrchestrator
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.AgentActivityRepository
import com.zaijian.zhoumuyun.data.repository.CharacterStateRepository
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
import com.zaijian.zhoumuyun.data.repository.RoundtableMessageRepository
import com.zaijian.zhoumuyun.data.repository.SkillRepository
import com.zaijian.zhoumuyun.domain.ChatTagParser
import com.zaijian.zhoumuyun.domain.MoodType
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.domain.RelationshipEngine
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlin.random.Random
import java.util.UUID

/**
 * 自发互动模块委托类。
 * 从 [RoundtableViewModel] 中提取，封装 30 秒空闲计时、自发发言触发、权重随机选择、
 * 流式回复生成、持久化落库及记忆写入等全部自发互动逻辑。
 */
class RoundtableIdleManager(
    private val _uiState: MutableStateFlow<RoundtableUiState>,
    private val memoryRepo: MemoryRepository,
    private val relationshipEngine: RelationshipEngine,
    private val pregnancyRepo: PregnancyRepository,
    private val characterStateRepo: CharacterStateRepository,
    private val daughterCharacterRepo: DaughterCharacterRepository,
    private val presenceEngine: PresenceEngine,
    private val identityDao: IdentityRepository,
    private val roundtableMessageDao: RoundtableMessageRepository,
    // Window C 技能系统补做：自发发言路径与常规圆桌回复（RoundtableBotReplyGenerator）
    // 同等对待，同一个角色不该因为"是自己主动搭话还是被叫到发言"就有不同的技能可见性。
    private val skillRepo: SkillRepository,
    private val prefs: SharedPreferences,
    private val viewModelScope: CoroutineScope,
    private val getCurrentRoundtableId: () -> String?,
    private val getIdleWatchJob: () -> Job?,
    private val setIdleWatchJob: (Job?) -> Unit,
    private val SPONTANEOUS_IDLE_MS: Long = 30_000L,
    private val REPLY_TIMEOUT_MS: Long = 60_000L,
) {

    // ─────────────────────────────────────────────────────────────────
    //  toEntity() 扩展
    // ─────────────────────────────────────────────────────────────────

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
        tableDataJson   = tableDataJson,   // v67（表格直传 W4）：透传到 entity。
    )

    // ══════════════════════════════════════════════════════════
    //  自发互动模块
    // ══════════════════════════════════════════════════════════

    /** 设置面板开关回调 */
    fun setSpontaneousEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isSpontaneousEnabled = enabled) }
        prefs.edit().putBoolean("spontaneous_enabled", enabled).apply()
        if (enabled) startIdleWatch() else stopIdleWatch()
    }

    /**
     * 启动 30 秒空闲计时器。
     * 每次用户发消息后调用（重置计时），Screen 进入 onStart 时也调用。
     * 只在 [isSpontaneousEnabled] == true 且当前没有轮次在进行时生效。
     */
    fun startIdleWatch() {
        if (!_uiState.value.isSpontaneousEnabled) return
        getIdleWatchJob()?.cancel()
        setIdleWatchJob(viewModelScope.launch {
            kotlinx.coroutines.delay(SPONTANEOUS_IDLE_MS)
            // 空闲超时：仅在圆桌处于等待用户输入、没有生成任务时才触发
            val state = _uiState.value
            if (!state.waitingForUser) return@launch
            if (state.activeMembers.isEmpty()) return@launch
            val provider = com.zaijian.zhoumuyun.data.provider.ProviderManager.instance.activeProvider
                ?: return@launch
            val initiator = pickSpontaneousInitiator(state.activeMembers) ?: return@launch
            generateSpontaneousReply(initiator, provider)
            // 生成完毕后重新开始计时（循环空闲监测）
            startIdleWatch()
        })
    }

    /** 停止空闲计时器（Screen 离开前景 / 功能关闭时调用） */
    fun stopIdleWatch() {
        getIdleWatchJob()?.cancel()
        setIdleWatchJob(null)
    }

    /**
     * 从当前活跃成员中，基于情绪权重 + 话量倒数权重挑一个最合适的自发开口者。
     *
     * 权重规则：
     *   - 情绪越活跃（EXCITED > CURIOUS > SATISFIED > FOCUSED > CALM …）权重越高
     *   - 上一轮说话多（alreadyReplied 轮次里出现次数多）的角色权重降低，
     *     即让沉默角色更容易被触发，避免同一个角色反复自发开口
     *
     * 全部权重为 0 时返回随机一个（兜底）。
     */
    private fun pickSpontaneousInitiator(members: List<CharacterConfig>): CharacterConfig? {
        if (members.isEmpty()) return null

        // 情绪权重映射
        val moodWeight: (MoodType?) -> Float = { mood ->
            when (mood) {
                MoodType.EXCITED    -> 5f
                MoodType.CURIOUS    -> 4f
                MoodType.SATISFIED  -> 3f
                MoodType.FOCUSED    -> 2f
                MoodType.CALM       -> 1.5f
                MoodType.REFLECTIVE -> 1f
                MoodType.TIRED      -> 0.3f
                MoodType.CONCERNED  -> 0.5f
                null                -> 1f
            }
        }

        // 上一轮发言次数（用 lastRoundSpeakers 判定 — Set，每人最多算 1 次）
        val lastSpeakers = _uiState.value.lastRoundSpeakers

        val weights = members.map { bot ->
            val mood = presenceEngine.getCachedPresence(bot.id)?.mood
            val mw   = moodWeight(mood)
            // 上一轮发过言的角色权重减半（避免连续自发）
            val silenceBonus = if (bot.id in lastSpeakers) 0.5f else 1.0f
            bot to (mw * silenceBonus).coerceAtLeast(0.1f)
        }

        val totalWeight = weights.sumOf { it.second.toDouble() }.toFloat()
        if (totalWeight <= 0f) return members.random()

        var rand = Random.nextFloat() * totalWeight
        for ((bot, w) in weights) {
            rand -= w
            if (rand <= 0f) return bot
        }
        return members.last()
    }

    /**
     * 用自发发言专用 prompt 驱动 [initiator] 生成一条主动发言，
     * 追加到消息列表中（speakerId = initiator.id，turnIndex = 当前 turnIndex，
     * 不新增 turnIndex——自发发言属于"同一轮上下文延续"，不算用户新一轮）。
     */
    private suspend fun generateSpontaneousReply(
        initiator: CharacterConfig,
        provider: LLMProvider,
    ) {
        val coreMemories     = memoryRepo.getCoreMemories(initiator.id)
        val relationshipSnap = relationshipEngine.buildPromptSnapshot(initiator.id)
        // presence fallback：缓存为空时（角色未打开过单人对话）主动计算一次，
        // 与 generateBotReply 的处理逻辑对齐。
        var characterStateForPresence = characterStateRepo.getState(initiator.id)
        // ── 复核修复 #7/#13/#20（圆桌自发发言路径补齐）：与常规圆桌发言、
        // ChatViewModel 私聊路径同一段逻辑——自发发言的 initiator 同样不区分
        // 角色类型，女儿角色一样会自发插话，必须同样查一次专属状态数据。
        var daughterStateLayer: DaughterStateLayer? = null
        var daughterCustomEnums: DaughterCustomEnums? = null
        if (initiator.id >= 1000) {
            try {
                val daughterData = daughterCharacterRepo.getCharacterData(initiator.id)
                if (daughterData != null) {
                    daughterStateLayer = daughterData.stateLayer
                    daughterCustomEnums = daughterData.customEnums
                    if (characterStateForPresence == CharacterStateLayer()) {
                        characterStateForPresence = daughterData.stateLayer.toCharacterStateLayer()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("RoundtableViewModel", "女儿状态数据查询失败（自发发言），State Layer 渲染将回退到通用描述", e)
            }
        }
        val presenceSnap = presenceEngine.getCachedPresence(initiator.id)
            ?: presenceEngine.refreshPresence(initiator.id, characterStateForPresence)

        // 最近 6 条消息作为上下文摘要
        val recentContext = _uiState.value.messages.takeLast(6).joinToString("\n") { msg ->
            val speaker = if (msg.speakerId == "user") "他" else msg.speakerName
            "[$speaker]: ${msg.content.take(80)}"
        }

        // D4 女儿在场感知修复：与 RoundtableBotReplyGenerator 同一判断口径——
        // 圆桌在场成员（除 initiator 自己）里是否有女儿角色（id >= 1000）。
        val daughterPresentInScene = _uiState.value.activeMembers.any { it.id != initiator.id && it.id >= 1000 }

        // v1.39 圆桌工具调用接入：自发发言路径与常规回复路径（RoundtableBotReplyGenerator）
        // 共用同一份 ROUNDTABLE_DISABLED_TOOL_NAMES 排除名单，保持行为一致。
        val toolDesc = AgentToolRegistry.buildToolDescriptionBlock(excludeNames = ROUNDTABLE_DISABLED_TOOL_NAMES)

        // Window C 技能系统补做：自发发言同样按 initiator.id 取该角色自己的技能目录，
        // 和 RoundtableBotReplyGenerator 同一份 SkillRegistry.buildSkillCatalogBlock()。
        val skillCatalogBlock = SkillRegistry.buildSkillCatalogBlock(
            characterId = initiator.id,
            repo = skillRepo,
        )

        val spontaneousSystemPrompt = buildString {
            append(PromptOrchestrator.buildSystemPrompt(
                character               = initiator,
                identityEntity          = identityDao.getById(initiator.id),
                coreMemories            = coreMemories,
                relevantMemories        = emptyList(),
                presenceActivity        = presenceSnap?.activity ?: "",
                presenceFocus           = presenceSnap?.goalTitle ?: "",
                presenceMood            = presenceSnap?.mood?.name ?: "",
                presenceEnergy          = presenceSnap?.energy ?: -1,
                relationshipSnapshot    = relationshipSnap,
                groupContextBlock       = "",
                interCharRelBlock       = "",
                agentPlanBlock          = "",
                ruleLayerBlock          = "",
                pregnancyState          = pregnancyRepo.getPregnancy(initiator.id),
                characterState          = characterStateForPresence,
                daughterStateLayer      = daughterStateLayer,
                daughterCustomEnums     = daughterCustomEnums,
                miscarriageAftermathPatch = "",
                pregnancyAwarenessBlock = "",
                // v1.36 问题3：自发发言场景 groupContextBlock 出于 Token 预算传空
                // （见上），但这里仍然是圆桌（有其他角色在场），必须显式声明，
                // 否则用户身份注入会误判成私聊、用错私下称谓。
                isRoundtableContext     = true,
                daughterPresentInScene  = daughterPresentInScene,
                toolDescriptionBlock    = toolDesc,
                skillCatalogBlock       = skillCatalogBlock,
            ))
            appendLine()
            appendLine("【自发发言模式】")
            appendLine("圆桌已经沉默了一段时间。请你以 ${initiator.name} 的身份，")
            appendLine("根据当前氛围和你的心情，主动说一句话来打破沉默。")
            appendLine("不要解释自己为什么要说话，直接说出你想说的内容。")
            appendLine("字数控制在 30~80 字，语气自然，像真实的人一样开口。")
            if (recentContext.isNotBlank()) {
                appendLine()
                appendLine("最近的对话上下文（供参考）：")
                appendLine(recentContext)
            }
        }

        val msgId  = UUID.randomUUID().toString()
        val turnIdx = _uiState.value.turnIndex

        _uiState.update {
            it.copy(
                messages = (it.messages + RoundtableMessage(
                    id          = msgId,
                    speakerId   = initiator.id.toString(),
                    speakerName = initiator.name,
                    content     = "",
                    isStreaming = true,
                    turnIndex   = turnIdx,
                )).toImmutableList(),
                // P1-13-25 修复：原代码只设 isStreaming，generationStatus 未更新，
                // 导致 UI 层无法感知到自发发言正在生成（进度指示器不亮）。
                generationStatus = (it.generationStatus + (initiator.id to BotGenerationStatus.GENERATING)).toImmutableMap(),
            )
        }

        // 用最近 10 条真实圆桌消息作为对话历史（与 generateBotReply 逻辑对齐），
        // 让角色能感知到沉默前的上下文，避免重复发言或语境断裂。
        // 最后追加一条内部触发消息（role=user），驱动模型输出。
        val spontaneousHistory = _uiState.value.messages
            .takeLast(10)
            .map { msg ->
                LLMMessage(
                    role    = if (msg.speakerId == "user") "user" else "assistant",
                    content = if (msg.speakerId == "user") msg.content
                              else "[${msg.speakerName}] ${msg.content}",
                )
            } + LLMMessage("user", "（沉默了一会儿，请自然地开口说一句话）")

        var fullReply = ""
        val config = LLMConfig(model = "", maxTokens = 50000, temperature = 0.92f, stream = true)
        // v1.39 圆桌工具调用接入：与常规回复路径同语义，暂存本轮工具产出的文件元数据。
        // v66（1.7 P3）：改用 list 收集本轮全部文件，与另外两条路径同步升级。
        val pendingExportedFiles = mutableListOf<String>()
        // v67（表格直传 W4）：table_export 产出的 payload（与另外两条路径同语义）。
        var pendingTablePayloadJson: String? = null

        // v147 验收返工：身份绑定到协程（VaultCallContextElement），避免进程级
        // AtomicReference 被并发的 streamWithTools 覆盖。
        try {
            withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                withVaultContext(VaultCallContext(initiator.id, VaultScope.ROUNDTABLE, getCurrentRoundtableId())) {
                ToolCallInterceptor.streamWithTools(
                    provider          = provider,
                    messages          = spontaneousHistory,
                    systemPrompt      = spontaneousSystemPrompt,
                    config            = config,
                    disabledToolNames = ROUNDTABLE_DISABLED_TOOL_NAMES,
                    activityContext   = ToolCallInterceptor.ActivityContext(
                        characterId = initiator.id,
                        sessionRef  = msgId,
                        sceneType   = AgentActivityRepository.SceneType.ROUNDTABLE_IDLE,
                    ),
                ).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            // event.text 已经过 ToolParser 清洗，不含 <tool:xxx> 标签，
                            // 与常规回复路径（RoundtableBotReplyGenerator）语义一致。
                            fullReply += event.text
                            // Fix-StreamingPsychLeak：与 generateBotReply 同一套流式圆括号剥离，
                            // 见该函数内注释——避免自发发言路径与普通回复路径出现"一条修了
                            // 一条没修"的不一致。
                            val (displayText, streamingPsych) = ChatTagParser.stripTagsForDisplayWithPsych(fullReply)
                            _uiState.update { s ->
                                s.copy(messages = s.messages.map { msg ->
                                    if (msg.id == msgId) msg.copy(content = displayText, psychText = streamingPsych) else msg
                                }.toImmutableList())
                            }
                        }
                        is StreamEvent.ToolStarted -> {
                            // 心迹（Window B 2.2.3）：记录工具调用"已发起"事件，sceneType=roundtable_idle。
                            try {
                                AppContainer.instance.agentActivityRepo.recordEvent(
                                    characterId    = initiator.id,
                                    sessionRef     = msgId,
                                    sceneType      = AgentActivityRepository.SceneType.ROUNDTABLE_IDLE,
                                    eventType      = AgentActivityRepository.EventType.TOOL_CALL,
                                    toolName       = event.toolName,
                                    outcome        = null,
                                    toolParamsJson = org.json.JSONObject(event.params).toString(),
                                    startedAt      = System.currentTimeMillis(),
                                    completedAt    = null,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                ZLog.w("RoundtableViewModel", "心迹事件落库失败（不影响主流程）", e)
                            }
                        }
                        is StreamEvent.ToolDone -> {
                            // 心迹（Window B 2.2.3）：记录工具调用终态事件，sceneType=roundtable_idle。
                            try {
                                AppContainer.instance.agentActivityRepo.recordEvent(
                                    characterId  = initiator.id,
                                    sessionRef   = msgId,
                                    sceneType    = AgentActivityRepository.SceneType.ROUNDTABLE_IDLE,
                                    eventType    = AgentActivityRepository.EventType.TOOL_CALL,
                                    toolName     = event.result.toolName,
                                    outcome      = if (event.result.success)
                                        AgentActivityRepository.Outcome.SUCCESS
                                    else AgentActivityRepository.Outcome.FAIL,
                                    outputRaw    = event.result.content,
                                    errorMessage = event.result.error,
                                    startedAt    = System.currentTimeMillis(),
                                    completedAt  = System.currentTimeMillis(),
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                ZLog.w("RoundtableViewModel", "心迹事件落库失败（不影响主流程）", e)
                            }
                            // v66（1.7 P3）：add 而不是覆盖赋值。
                            extractExportedFileJson(event.result)?.let { pendingExportedFiles.add(it) }
                            // v67（表格直传 W4）：table_export 产出走 ToolResult.tablePayloadJson
                            // 返回值（与另外两条路径同款，不存工具实例字段）。
                            event.result.tablePayloadJson?.let { pendingTablePayloadJson = it }
                        }
                        is StreamEvent.RoundDone -> Unit
                        // v1.49 新增：见 RoundtableBotReplyGenerator.kt 同名分支的说明，
                        // 圆桌空闲消息场景暂不处理文件已读凭证落库。
                        is StreamEvent.FileReadConfirmed -> Unit
                    }
                }
                } // withVaultContext
            }
        } catch (e: CancellationException) {
            throw e  // P1-11-4 修复：CancellationException 必须 rethrow
        } catch (e: Throwable) {
            // catch Throwable 而非 Exception：与私聊 ChatMessageOrchestrator:584 对齐，
            // 防止工具层 Error 击穿到独立 CoroutineScope 静默终止（idle 自发发言路径更隐蔽）。
            ZLog.e("RoundtableViewModel", "自发发言流式生成中断（msgId=$msgId），已生成长度=${fullReply.length}", e)
            _uiState.update { it.copy(error = "回复时遇到问题，请稍后重试。") }
        } finally {
            // P0-02 修复：与 RoundtableBotReplyGenerator.generateBotReply 同一根因——
            // catch(CancellationException) 里的 throw e 会让原本写在 try-catch 之外的
            // 收尾代码（isStreaming=false、generationStatus=DONE、落库）被整体跳过。
            // 用户新发消息或点击 interrupt 打断自发发言时，消息会永远卡在
            // isStreaming=true/GENERATING，UI 残留永久加载指示器的"幽灵消息"。
            // 挪进 finally 确保无论正常结束、异常、还是被取消，都会执行收尾。
            //
            // v1.38 圆桌场景补齐：与 generateBotReply 同一套三层解析（详见该函数内注释），
            // 自发发言路径此前同样从未接入，同批次一并补齐，避免两条圆桌生成路径
            // 出现"一条修了一条没修"的行为不一致。
            val (afterThinking, parsedThinking) = ChatTagParser.stripThinkingTag(fullReply.trimEnd())
            val (afterPsych, parsedPsych) = ChatTagParser.stripPsychText(afterThinking)
            val (cleanReply, parsedMood) = ChatTagParser.stripMoodTag(afterPsych)
            if (parsedMood != null) {
                // C4#13 方案B：见 RoundtableBotReplyGenerator 同一处改动的注释——
                // stripMoodTag 现在返回 ParsedMood（EmotionType+强度），.moodType 是按
                // 默认情绪疲劳值换算出的 MoodType，用法与此前直接拿 MoodType 等价。
                presenceEngine.updateMoodFromReply(initiator.id, parsedMood.moodType)
            }

            _uiState.update { s ->
                s.copy(
                    messages = s.messages.map { msg ->
                        if (msg.id == msgId) msg.copy(
                            content      = cleanReply,
                            thinkingText = parsedThinking,
                            psychText    = parsedPsych,
                            isStreaming  = false,
                            // v66（1.7 P3）：两个字段都写，exportedFileJson 保留兼容旧路径。
                            exportedFileJson = pendingExportedFiles.lastOrNull(),
                            exportedFilesJson = packExportedFilesJson(pendingExportedFiles),
                            // v67（表格直传 W4）：table_export 产出接回 UI 消息（与常规回复路径同款）。
                            tableDataJson = pendingTablePayloadJson,
                        ) else msg
                    }.toImmutableList(),
                    generationStatus = (s.generationStatus + (initiator.id to BotGenerationStatus.DONE)).toImmutableMap(),
                )
            }

            // 落库
            // W2-4 修复：自发发言落库之前无 try-catch，失败时自发发言内容静默丢失。
            getCurrentRoundtableId()?.let { rtId ->
                if (cleanReply.isNotBlank()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            roundtableMessageDao.insert(
                                RoundtableMessage(
                                    id           = msgId,
                                    speakerId    = initiator.id.toString(),
                                    speakerName  = initiator.name,
                                    content      = cleanReply,
                                    turnIndex    = turnIdx,
                                    thinkingText = parsedThinking,
                                    psychText    = parsedPsych,
                                    exportedFileJson = pendingExportedFiles.lastOrNull(),
                                    exportedFilesJson = packExportedFilesJson(pendingExportedFiles),
                                    // v67（表格直传 W4）：透传到 toEntity → RoundtableMessageEntity.tableDataJson。
                                    tableDataJson = pendingTablePayloadJson,
                                ).toEntity(rtId)
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            ZLog.e("RoundtableViewModel", "自发发言落库失败（msgId=$msgId）", e)
                            _uiState.update { it.copy(error = "消息保存失败，可能会在下次打开时丢失") }
                        }
                    }
                }
            }

            // 记忆写入收窄为 Agent 主动工具调用，自发发言路径不再自动提取
            // 个人/群记忆候选（与 generateBotReply 路径一致）。自发发言不对应
            // 任何用户 MESSAGE 事件，故此处也无事件写入需要保留。
        }
    }
}