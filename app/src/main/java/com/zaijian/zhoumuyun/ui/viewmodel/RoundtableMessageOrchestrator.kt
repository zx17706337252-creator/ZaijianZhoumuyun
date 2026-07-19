package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.repository.EventRepository
import com.zaijian.zhoumuyun.data.repository.RoundtableMessageRepository
import com.zaijian.zhoumuyun.domain.MoodType
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.domain.RelationshipEngine
import com.zaijian.zhoumuyun.data.memory.MemoryEngine
import com.zaijian.zhoumuyun.domain.scheduler.ScheduleContext
import com.zaijian.zhoumuyun.domain.scheduler.SpeakIntent
import com.zaijian.zhoumuyun.domain.scheduler.SpeakPlan
import com.zaijian.zhoumuyun.domain.scheduler.TurnScheduler
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 从 [RoundtableViewModel] 中提取的消息发送编排委托类。
 *
 * 封装了：
 * - @mention 解析
 * - 群体任务判定
 * - 调度计划计算
 * - 单轮执行
 * - 自动连续讨论循环
 * - 中断与结束
 *
 * 不持有 [generateBotReply] 的实现——该实现保留在 ViewModel 中，
 * 通过回调参数注入。
 */
class RoundtableMessageOrchestrator(
    private val _uiState: MutableStateFlow<RoundtableUiState>,
    private val roundtableMessageDao: RoundtableMessageRepository,
    private val relationshipEngine: RelationshipEngine,
    private val presenceEngine: PresenceEngine,
    private val eventRepo: EventRepository,
    private val memoryEngine: MemoryEngine,
    private val isInterruptedRef: () -> Boolean,
    private val isInterruptedSetter: (Boolean) -> Unit,
    private val getCurrentRoundtableId: () -> String?,
    private val getRoundJob: () -> Job?,
    private val setRoundJob: (Job?) -> Unit,
    private val getIdleWatchJob: () -> Job?,
    private val viewModelScope: CoroutineScope,
    private val SENTENCE_BREAK_CHARS: Set<Char>,
    private val FULL_MENTION_KEYWORDS: List<String>,
    private val AUTO_DISCUSSION_MAX_EXTRA_ROUNDS: Int,
    private val JUDGE_TIMEOUT_MS: Long,
    private val REPLY_TIMEOUT_MS: Long,
    private val JUDGE_CONTEXT_MESSAGES: Int,
) {

    // ──────────────────────────────────────────────────────────
    //  AI API 调用构造
    // ──────────────────────────────────────────────────────────

    fun buildAiCall(
        systemPrompt: String,
        maxTokens: Int = 100,
        temperature: Float = 0.2f,
    ): suspend (String) -> String {
        val provider = ProviderManager.instance.activeProvider
            ?: return { "" }
        return { prompt ->
            provider.chatSyncWithRetry(
                messages     = listOf(LLMMessage("user", prompt)),
                systemPrompt = systemPrompt,
                config       = LLMConfig(model = "", maxTokens = maxTokens, temperature = temperature, stream = false),
            )
        }
    }

    // ──────────────────────────────────────────────────────────
    //  @mention 解析
    // ──────────────────────────────────────────────────────────

    fun parseAtMentions(text: String, activeMembers: List<CharacterConfig>): MentionResult {
        if (activeMembers.isEmpty()) return MentionResult(emptySet(), false)

        if (FULL_MENTION_KEYWORDS.any { it in text }) {
            return MentionResult(mentionedIds = activeMembers.map { it.id }.toSet(), isFullMention = true)
        }

        val sortedNames: List<Pair<String, Int>> = activeMembers
            .flatMap { cfg ->
                val names = mutableListOf(cfg.name to cfg.id)
                val nick = (cfg.nickname ?: "").trim()
                if (nick.isNotBlank() && nick != cfg.name) names.add(nick to cfg.id)
                names
            }
            .sortedByDescending { it.first.length }

        val mentionedIds = mutableSetOf<Int>()
        var cursor = 0
        while (cursor < text.length) {
            if (text[cursor] == '@') {
                val rest = text.substring(cursor + 1)
                val matched = sortedNames.firstOrNull { (name, _) -> rest.startsWith(name) }
                if (matched != null) {
                    mentionedIds.add(matched.second)
                    cursor += 1 + matched.first.length
                    continue
                }
            }
            cursor++
        }

        val allIds = activeMembers.map { it.id }.toSet()
        val isFullByIds = mentionedIds == allIds && allIds.isNotEmpty()
        return MentionResult(mentionedIds = mentionedIds, isFullMention = isFullByIds)
    }

    // ──────────────────────────────────────────────────────────
    //  调度计划计算
    // ──────────────────────────────────────────────────────────

    suspend fun schedulePlans(ctx: ScheduleContext): List<SpeakPlan> {
        val provider = ProviderManager.instance.activeProvider
            ?: return TurnScheduler.scheduleAuto(ctx, apiCall = null)
        val aiApiCall = buildAiCall("你是一个调度助手，只返回 JSON，不要任何其他文字。", maxTokens = 200, temperature = 0.3f)
        return when (_uiState.value.scheduleMode) {
            ScheduleMode.AUTO      -> TurnScheduler.scheduleAuto(ctx, aiApiCall)
            ScheduleMode.HEURISTIC -> TurnScheduler.scheduleAuto(ctx, apiCall = null)
            ScheduleMode.AI_ONLY   -> TurnScheduler.scheduleAuto(ctx, aiApiCall, forceAI = true)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  轻量 AI 意图判定
    // ──────────────────────────────────────────────────────────

    /**
     * 轻量 AI 判断：用户消息是否属于"布置任务/要求全体讨论"但没有明确 @任何人。
     *
     * 返回 true → 视为全体@，触发自动连续讨论。
     * 失败/超时 → 返回 false（fallback 当无@ 处理，不阻塞主流程）。
     *
     * 仅在 mentionedIds 为空时调用，避免冗余判定。
     */
    suspend fun judgeIsGroupTask(
        text: String,
        apiCall: suspend (String) -> String,
    ): Boolean {
        val prompt = """
判断用户的这条消息是否是"要求所有人一起讨论某个议题或方案"。

用户消息：「$text」

规则：
- 如果消息是在布置任务、要求大家讨论/商量/评审/投票，返回 YES
- 如果只是普通问话、闲聊、或只问某一个人的问题，返回 NO
- 只回复 YES 或 NO，不要其他文字
""".trimIndent()
        return try {
            withTimeoutOrNull(JUDGE_TIMEOUT_MS) {
                apiCall(prompt).trim().uppercase().startsWith("Y")
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ZLog.d("RoundtableMessageOrchestrator", "judgeIsGroupTask 裁判调用失败，降级为 false", e)
            false
        }
    }

    // ──────────────────────────────────────────────────────────
    //  讨论收敛裁判
    // ──────────────────────────────────────────────────────────

    /**
     * 轻量 AI 裁判：判断最近几轮讨论是否已收敛（有共识/方案可执行了）。
     *
     * 返回 true  → 结束自动续轮，把控制权还给用户。
     * 返回 false → 继续下一轮。
     * 失败/超时  → 返回 true（保守策略：宁可提前结束，不让循环失控）。
     */
    suspend fun judgeDiscussionConcluded(
        recentMessages: List<RoundtableMessage>,
        originalUserMessage: String,
        apiCall: suspend (String) -> String,
    ): Boolean {
        val digest = recentMessages.takeLast(JUDGE_CONTEXT_MESSAGES).joinToString("\n") { msg ->
            val speaker = if (msg.speakerId == "user") "用户" else msg.speakerName
            "[$speaker]: ${msg.content.take(120)}"
        }
        val prompt = """
你是一个讨论进程裁判。请判断下面这段多人讨论是否已经达成共识或形成了可执行的结论。

原始议题：「$originalUserMessage」

最近的讨论内容：
$digest

判断标准：
- 如果大家已经形成共识、给出了明确的方案或结论，回复 YES
- 如果讨论还在发散、存在明显分歧、或还没有可执行的结论，回复 NO
- 只回复 YES 或 NO，不要其他文字
""".trimIndent()
        return try {
            withTimeoutOrNull(JUDGE_TIMEOUT_MS) {
                apiCall(prompt).trim().uppercase().startsWith("Y")
            } ?: true   // 超时 → 保守结束
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ZLog.d("RoundtableMessageOrchestrator", "judgeDiscussionConcluded 裁判调用失败，降级为 true（保守结束）", e)
            true          // 异常 → 保守结束
        }
    }

    // ──────────────────────────────────────────────────────────
    //  单轮执行
    // ──────────────────────────────────────────────────────────

    /**
     * 执行一轮序贯生成（初始轮 & 续轮均调用此函数）。
     *
     * 完成后自动更新 lastRoundSpeakers 和角色关系。
     * 不修改 waitingForUser / isAutoDiscussing，由调用方负责。
     */
    suspend fun executeRound(
        plans: List<SpeakPlan>,
        members: List<CharacterConfig>,
        userMessage: String,
        turnIdx: Int,
        memberIds: List<Int>,
        relationshipMatrix: Map<String, RelationshipEntity>,
        provider: LLMProvider,
        mentionedIds: Set<Int> = emptySet(),
        generateBotReply: suspend (CharacterConfig, String, Map<Int, String>, Int, SpeakIntent, LLMProvider, Boolean) -> String?,
    ) {
        // 初始化 Bot 状态指示器
        val initStatus = members.associate { bot ->
            val plan = plans.firstOrNull { it.characterId == bot.id }
            bot.id to (if (plan != null) BotGenerationStatus.WAITING else BotGenerationStatus.IDLE)
        }
        _uiState.update { it.copy(generationStatus = initStatus.toImmutableMap()) }

        val alreadyReplied = mutableMapOf<Int, String>()

        for (plan in plans) {
            if (isInterruptedRef()) break
            val bot = members.firstOrNull { it.id == plan.characterId } ?: continue

            _uiState.update { s ->
                s.copy(generationStatus = (s.generationStatus + (bot.id to BotGenerationStatus.GENERATING)).toImmutableMap())
            }

            try {
                generateBotReply(
                    bot,
                    userMessage,
                    alreadyReplied,
                    turnIdx,
                    plan.initialIntent,
                    provider,
                    bot.id in mentionedIds,
                )?.let { fullReply -> alreadyReplied[bot.id] = fullReply }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val prev = _uiState.value.error
                _uiState.update { it.copy(error = if (prev.isNullOrEmpty()) "${bot.name} 回复出了点问题" else "$prev；${bot.name} 回复出了点问题") }
            }

            _uiState.update { s ->
                s.copy(generationStatus = (s.generationStatus + (bot.id to BotGenerationStatus.DONE)).toImmutableMap())
            }
        }

        // 更新角色间关系
        val actualSpeakers = alreadyReplied.keys.toList()
        if (actualSpeakers.isNotEmpty()) {
            val conflictPairs = mutableListOf<Pair<Int, Int>>()
            for (i in actualSpeakers.indices) {
                for (j in i + 1 until actualSpeakers.size) {
                    val idA = actualSpeakers[i]; val idB = actualSpeakers[j]
                    val conflict = relationshipMatrix[RelationshipEngine.relKey(idA, idB)]?.conflict ?: 0
                    if (conflict >= 50) conflictPairs.add(idA to idB)
                }
            }
            withContext(Dispatchers.IO) {
                relationshipEngine.onRoundtableRoundEnd(actualSpeakers, memberIds, conflictPairs)
            }
        }

        _uiState.update { it.copy(lastRoundSpeakers = actualSpeakers.toImmutableSet()) }
    }

    // ──────────────────────────────────────────────────────────
    //  结束讨论 & 中断
    // ──────────────────────────────────────────────────────────

    /**
     * 结束当前讨论（无论是正常结束、收敛、安全上限还是被中断）。
     * 统一把 waitingForUser 置 true、清除讨论状态。
     */
    fun finishDiscussion() {
        _uiState.update {
            it.copy(
                waitingForUser  = true,
                isAutoDiscussing = false,
                discussionRound = 0,
            )
        }
    }

    /**
     * 用户在 Bot 生成中发新消息时调用。
     * 将 isInterrupted 设为 true，流式收集循环和续轮循环都会在下一个安全点停止。
     */
    fun interrupt() {
        isInterruptedSetter(true)
        getRoundJob()?.cancel()
    }

    // ──────────────────────────────────────────────────────────
    //  sendMessage — 主编排方法
    // ──────────────────────────────────────────────────────────

    fun sendMessage(
        text: String,
        generateBotReply: suspend (CharacterConfig, String, Map<Int, String>, Int, SpeakIntent, LLMProvider, Boolean) -> String?,
        startIdleWatch: () -> Unit,
    ) {
        if (text.isBlank()) return
        val members = _uiState.value.activeMembers
        if (members.isEmpty()) {
            _uiState.update { it.copy(error = "圆桌成员尚未加载，请稍候") }
            return
        }

        val provider = ProviderManager.instance.activeProvider
        if (provider == null) {
            _uiState.update { it.copy(isApiKeyMissing = true) }
            return
        }

        getRoundJob()?.cancel()
        getIdleWatchJob()?.cancel()

        val oldJob = getRoundJob()
        val oldIdleJob = getIdleWatchJob()
        setRoundJob(viewModelScope.launch {
            oldJob?.join()
            oldIdleJob?.join()
            isInterruptedSetter(false)

            try {
                // 用户有输入 → 重置空闲计时
                if (_uiState.value.isSpontaneousEnabled) startIdleWatch()

                val turnIdx = _uiState.value.turnIndex + 1

                // 1. 追加用户消息
                val userMsg = RoundtableMessage(
                    id          = UUID.randomUUID().toString(),
                    speakerId   = "user",
                    speakerName = "你",
                    content     = text,
                    turnIndex   = turnIdx,
                )
                _uiState.update {
                    it.copy(
                        messages       = (it.messages + userMsg).toImmutableList(),
                        waitingForUser = false,
                        turnIndex      = turnIdx,
                        error          = null,
                        isScheduling   = true,
                    )
                }
                getCurrentRoundtableId()?.let { rtId ->
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            roundtableMessageDao.insert(userMsg.toEntity(rtId))
                        } catch (e: Exception) {
                            ZLog.e("RoundtableMessageOrchestrator", "用户消息落库失败 rtId=$rtId", e)
                            _uiState.update { it.copy(error = "消息保存失败，可能无法在下次打开时看到") }
                        }
                    }
                }

                // 2. 加载关系矩阵 & 情绪
                val memberIds = members.map { it.id }
                val relationshipMatrix = withContext(Dispatchers.IO) {
                    relationshipEngine.getInterCharacterMatrix(memberIds)
                }
                val moodMap: Map<Int, Float> = members.associate { bot ->
                    val moodType = presenceEngine.getCachedPresence(bot.id)?.mood
                    val moodFloat = when (moodType) {
                        MoodType.EXCITED    ->  0.9f
                        MoodType.SATISFIED  ->  0.6f
                        MoodType.CURIOUS    ->  0.6f
                        MoodType.FOCUSED    ->  0.3f
                        MoodType.CALM       ->  0.0f
                        MoodType.REFLECTIVE ->  0.0f
                        MoodType.TIRED      -> -0.6f
                        MoodType.CONCERNED  -> -0.9f
                        null                ->  0.0f
                    }
                    bot.id to moodFloat
                }

                // 3. 解析 @mention，+ 意图判定（第三触发条件）
                val aiApiCall = buildAiCall("你是一个助手，只返回要求格式的内容，不要其他文字。")
                var mentionResult = parseAtMentions(text, members)
                if (!mentionResult.isFullMention && mentionResult.mentionedIds.isEmpty()) {
                    val isGroupTask = judgeIsGroupTask(text, aiApiCall)
                    if (isGroupTask) {
                        mentionResult = MentionResult(
                            mentionedIds  = members.map { it.id }.toSet(),
                            isFullMention = true,
                        )
                    }
                }

                // 4. 构建 ScheduleContext
                val baseCtx = ScheduleContext(
                    activeBots        = members,
                    userMessage       = text,
                    lastRoundSpeakers = _uiState.value.lastRoundSpeakers,
                    relationships     = relationshipMatrix,
                    moodMap           = moodMap,
                    mentionedIds      = mentionResult.mentionedIds,
                    isFullMention     = mentionResult.isFullMention,
                )

                _uiState.update { it.copy(isScheduling = false) }

                // 5. 初始轮调度 & 生成
                val initialPlans = schedulePlans(baseCtx)
                if (initialPlans.isEmpty()) {
                    _uiState.update {
                        it.copy(waitingForUser = true, error = "暂时没有角色可以回应，请稍后重试")
                    }
                    return@launch
                }

                // 全体@ → 进入自动讨论状态
                val isFullMention = mentionResult.isFullMention
                if (isFullMention) {
                    _uiState.update { it.copy(isAutoDiscussing = true, discussionRound = 1) }
                }

                // 执行初始轮
                executeRound(
                    plans              = initialPlans,
                    members            = members,
                    userMessage        = text,
                    turnIdx            = turnIdx,
                    memberIds          = memberIds,
                    relationshipMatrix = relationshipMatrix,
                    provider           = provider,
                    mentionedIds       = mentionResult.mentionedIds,
                    generateBotReply   = generateBotReply,
                )

                if (isInterruptedRef()) {
                    finishDiscussion()
                    return@launch
                }

                // 6. 全体@ → 自动连续讨论循环
                if (isFullMention) {
                    var extraRound = 0
                    var currentTurnIdx = turnIdx
                    val originalUserMessage = text

                    while (extraRound < AUTO_DISCUSSION_MAX_EXTRA_ROUNDS && !isInterruptedRef()) {
                        // 裁判判断是否收敛
                        val recentMsgs = _uiState.value.messages
                        val concluded = judgeDiscussionConcluded(recentMsgs, originalUserMessage, aiApiCall)
                        if (concluded) break

                        extraRound++
                        currentTurnIdx++
                        val roundLabel = extraRound + 1

                        _uiState.update {
                            it.copy(
                                discussionRound = roundLabel,
                                turnIndex       = currentTurnIdx,
                            )
                        }

                        // 续轮用固定追问 prompt
                        val continuePrompt = "请结合前面各位的发言，继续完善方案，争取形成共识。"

                        val continueCtx = baseCtx.copy(
                            userMessage       = continuePrompt,
                            lastRoundSpeakers = _uiState.value.lastRoundSpeakers,
                        )
                        val continuePlans = TurnScheduler.scheduleFullMention(continueCtx)
                        if (continuePlans.isEmpty() || isInterruptedRef()) break

                        executeRound(
                            plans              = continuePlans,
                            members            = members,
                            userMessage        = continuePrompt,
                            turnIdx            = currentTurnIdx,
                            memberIds          = memberIds,
                            relationshipMatrix = relationshipMatrix,
                            provider           = provider,
                            generateBotReply   = generateBotReply,
                        )
                    }

                    if (extraRound >= AUTO_DISCUSSION_MAX_EXTRA_ROUNDS && !isInterruptedRef()) {
                        val totalRounds = extraRound + 1
                        _uiState.update {
                            it.copy(error = "讨论了 $totalRounds 轮还没有定论，要不要你来定个方向？")
                        }
                    }
                }

                finishDiscussion()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "发生错误，请重试") }
            } finally {
                _uiState.update {
                    it.copy(
                        isScheduling    = false,
                        waitingForUser  = true,
                        isAutoDiscussing = false,
                        discussionRound = 0,
                    )
                }
            }
        })
    }

    // ──────────────────────────────────────────────────────────
    //  helper
    // ──────────────────────────────────────────────────────────

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
    )
}