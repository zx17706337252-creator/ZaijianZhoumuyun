package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.agent.ToolCallInterceptor
import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.repository.AgentActivityRepository
import com.zaijian.zhoumuyun.data.repository.EventRepository
import com.zaijian.zhoumuyun.data.repository.RoundtableMessageRepository
import com.zaijian.zhoumuyun.domain.MoodType
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.domain.RelationshipEngine
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
    // A8-3 修复：memoryEngine 参数为历史遗留死代码，全文件零调用。
    // 圆桌群记忆走 AgentCoreTools 的 Agent 主动工具调用路径（roundtableId 显式传参），
    // 与此构造参数无关。已删除。
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
        maxTokens: Int = 50000,
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
        // P2 修复（用户遗留项）：此处原硬编码 maxTokens=200，绕过了 buildAiCall
        // 已统一到 50000 的默认值。调度 JSON 通常很短，200 本来看似够用，但
        // 参与调度的角色数量多、@提及/发言意愿等字段膨胀时仍可能被截断，
        // 且与私聊/圆桌回复/工单任务统一 maxTokens 标准的要求相悖。改为不传
        // maxTokens，直接沿用 buildAiCall 的默认值（50000）。
        val aiApiCall = buildAiCall("你是一个调度助手，只返回 JSON，不要任何其他文字。", temperature = 0.3f)
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
        } catch (e: Throwable) {
            ZLog.w("RoundtableMessageOrchestrator", "judgeIsGroupTask 裁判调用失败，降级为 false", e)
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
        } catch (e: Throwable) {
            ZLog.w("RoundtableMessageOrchestrator", "judgeDiscussionConcluded 裁判调用失败，降级为 true（保守结束）", e)
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
            } catch (e: Throwable) {
                // catch Throwable 而非 Exception：与私聊 ChatMessageOrchestrator:584 对齐，
                // 防止工具层 Error 击穿。补 ZLog.e 消除静默失败（原仅 UI error 无日志）。
                ZLog.e("RoundtableViewModel", "${bot.name} 回复异常", e)
                val prev = _uiState.value.error
                _uiState.update { it.copy(error = if (prev.isNullOrEmpty()) "${bot.name} 回复出了点问题" else "$prev；${bot.name} 回复出了点问题") }
            } finally {
                // P0-02 修复：原先 generationStatus=DONE 写在 try-catch 之后，
                // CancellationException rethrow 会跳过这行，导致 Bot 状态指示器
                // 永远卡在 GENERATING（幽灵消息问题的外层表现）。改用 finally 确保
                // 无论正常完成、异常、还是被取消，状态都会被推进到终态。
                //
                // P1-3 修复：常规回复路径此前无条件置 DONE，`INTERRUPTED` 终态只在
                // 自发路径产生（死代码）。这里在 finally 里按 isInterruptedRef() 判定：
                // 用户打断（interrupt() 置 isInterrupted=true 并取消 roundJob）时，被
                // 截断的 Bot 置为 INTERRUPTED 而非 DONE，UI 才能区分"完整回复"与
                // "被截断回复"（RoundtableMemberStrip/Overlays 渲染 ✗/灰点）。
                _uiState.update { s ->
                    val status = if (isInterruptedRef()) BotGenerationStatus.INTERRUPTED else BotGenerationStatus.DONE
                    s.copy(generationStatus = (s.generationStatus + (bot.id to status)).toImmutableMap())
                }
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
        // P1-4 修复：原先只取消 roundJob，自发互动（自发发言运行在 idleWatchJob 内）
        // 无法被 interrupt() 打断——用户正在打字时角色仍会强行插话到流式结束。
        // 这里一并取消 idleWatchJob，让自发发言的流式收集在下一个安全点终止。
        getIdleWatchJob()?.cancel()
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

        // Fix-孤儿文件 ③（与 ChatMessageOrchestrator.sendMessage 同款防线，
        // 详见 ToolCallInterceptor.isToolInFlight 顶部说明）：圆桌里 excel_gen/
        // pptx_gen 这类工具是某个具体成员（bot.id）在执行，正常情况下
        // waitingForUser/isAutoDiscussing 等状态已经会避免用户在这时候插话，
        // 这里是同一道"万一状态没挡住"的兜底——发现活跃成员里有任何一位正在
        // 执行工具，就不取消 roundJob，只提示稍候，避免文件写到一半被打断。
        val memberWithToolInFlight = members.firstOrNull {
            ToolCallInterceptor.isToolInFlight(AgentActivityRepository.SceneType.ROUNDTABLE_BOT, it.id)
        }
        if (memberWithToolInFlight != null) {
            _uiState.update { it.copy(error = "${memberWithToolInFlight.name}还在处理上一个操作，请稍候再发送") }
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
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
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

                        // A9-6 修复：续轮开始前重新查询关系矩阵。onRoundtableRoundEnd
                        // 在每轮结束后写入了新的 jealousy/tension，续轮复用 baseCtx
                        // 中的旧快照会导致调度读到上一轮写入前的旧值。同时覆盖
                        // continueCtx.relationships 和 executeRound 的 relationshipMatrix
                        // 参数，两处必须同步刷新，否则只改一处不会生效。
                        val freshMatrix = withContext(Dispatchers.IO) {
                            relationshipEngine.getInterCharacterMatrix(memberIds)
                        }

                        val continueCtx = baseCtx.copy(
                            userMessage       = continuePrompt,
                            lastRoundSpeakers = _uiState.value.lastRoundSpeakers,
                            relationships     = freshMatrix,
                        )
                        // 续轮调度应遵循用户选择的 scheduleMode，与初始轮（schedulePlans）
                        // 一致，而非固定走全体@逻辑。
                        val continuePlans = schedulePlans(continueCtx)
                        if (continuePlans.isEmpty() || isInterruptedRef()) break

                        executeRound(
                            plans              = continuePlans,
                            members            = members,
                            userMessage        = continuePrompt,
                            turnIdx            = currentTurnIdx,
                            memberIds          = memberIds,
                            relationshipMatrix = freshMatrix,
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
            } catch (e: Throwable) {
                // catch Throwable 而非 Exception：与私聊 ChatMessageOrchestrator:584 对齐，
                // 防止工具层 Error 击穿。补 ZLog.e 消除静默失败（原仅 UI error 无日志）。
                ZLog.e("RoundtableViewModel", "圆桌讨论异常", e)
                _uiState.update { it.copy(error = "发生错误，请重试") }
            } finally {
                // P1-4 修复：isInterrupted 此前只在 sendMessage 开头复位。若用户打字触发
                // interrupt() 后又只删字不发消息，isInterrupted 会一直为 true，下一次
                // 自发互动会因 isInterruptedRef()==true 产出空气泡。这里在本轮收尾统一
                // 复位（本轮无论正常结束 / 被打断 / 异常都会走到这个 finally），
                // 让下轮自发互动从干净状态开始。
                isInterruptedSetter(false)
                _uiState.update {
                    it.copy(
                        isScheduling    = false,
                        waitingForUser  = true,
                        isAutoDiscussing = false,
                        discussionRound = 0,
                    )
                }
                // P1-16 修复：RoundtableIdleManager 的自发互动是"单次计时器触发后
                // 自我重新武装"的循环——只有 generateSpontaneousReply 成功生成一条
                // 自发发言后才会调用 startIdleWatch() 重新武装下一轮倒计时（见该
                // 文件 startIdleWatch 内部 delay 结束的判断分支）。如果计时器到期
                // 时本轮讨论还没跑完（waitingForUser=false，比如续轮讨论较长、
                // 单个 Bot 回复较慢），会静默 return 且不再重新武装；此后无论讨论
                // 正常收敛、被 interrupt() 手动打断、还是抛异常结束，都没有别处
                // 会主动重启计时器，自发互动会一直停摆到用户下一次发消息为止——
                // 如果下一轮讨论同样耗时较长，又会再次失效。
                // 这里是 sendMessage 协程唯一必经的收尾出口（不管从哪条路径退出
                // 都会走到这个 finally），在这里统一重新武装能覆盖所有退出路径，
                // 且是"讨论真正转为空闲"这个时间点，比消息刚发出去那一刻更准确。
                // startIdleWatch() 内部已经会检查 isSpontaneousEnabled，这里不用
                // 重复判断。
                startIdleWatch()
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
        // v66（1.7 P3）：此路径当前不采集工具产出（用户消息落库，无 ToolDone
        // 事件），透传只是保持三处 toEntity 结构对称，实际恒为 null。
        exportedFileJson = exportedFileJson,
        exportedFilesJson = exportedFilesJson,
        // v67（表格直传 W4）：同上，透传保持结构对称，用户消息恒为 null。
        tableDataJson = tableDataJson,
        // 内心独白/心理描写透传（与 MessageEntity 同语义，保持三处 toEntity 结构对称）。
        thinkingText = thinkingText,
        psychText    = psychText,
    )
}