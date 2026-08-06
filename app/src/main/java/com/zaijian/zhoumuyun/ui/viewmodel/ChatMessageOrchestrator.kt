package com.zaijian.zhoumuyun.ui.viewmodel

import android.content.Context
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.MessageEntity
import com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity
import com.zaijian.zhoumuyun.data.db.entity.WorkflowJobEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyQuestionType
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.data.manager.DaughterCharacterGenerator
import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.ChatMode
import com.zaijian.zhoumuyun.data.model.toCharacterStateLayer
import com.zaijian.zhoumuyun.data.model.toMoodType
import com.zaijian.zhoumuyun.data.memory.MemoryEngine
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.prompt.ReplyGuard
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.prompt.PromptOrchestrator
import com.zaijian.zhoumuyun.data.repository.AgentPlanRepository
import com.zaijian.zhoumuyun.data.repository.CharacterStateRepository
import com.zaijian.zhoumuyun.data.repository.CharacterTitleRelationRepository
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.EventRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.LearningGoalRepository
import com.zaijian.zhoumuyun.data.repository.SkillRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
import com.zaijian.zhoumuyun.data.repository.ProjectRepository
import com.zaijian.zhoumuyun.data.repository.TaskRepository
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import com.zaijian.zhoumuyun.data.repository.ChainRunRepository
import com.zaijian.zhoumuyun.data.repository.AgentActivityRepository
import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.agent.SkillRegistry
import com.zaijian.zhoumuyun.data.agent.StreamEvent
import com.zaijian.zhoumuyun.data.agent.ToolCallInterceptor
import com.zaijian.zhoumuyun.data.agent.ToolResult
import com.zaijian.zhoumuyun.data.agent.VaultCallContext
import com.zaijian.zhoumuyun.data.agent.VaultScope
import com.zaijian.zhoumuyun.data.agent.withVaultContext
import com.zaijian.zhoumuyun.domain.AgentRelationEngine
import com.zaijian.zhoumuyun.domain.ChatTagParser
import com.zaijian.zhoumuyun.domain.EvaluationEngine
import com.zaijian.zhoumuyun.domain.ImpersonationDetector
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.domain.RelationshipEngine
import com.zaijian.zhoumuyun.domain.SpeakerContext
import com.zaijian.zhoumuyun.domain.withSpeakerContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.toImmutableList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatMessageOrchestrator(
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val _streamingContent: MutableStateFlow<String?>,
    private val _streamingPsych: MutableStateFlow<String?>,
    // Fix-StreamThinking（输出节奏需求：思考过程先出，正式回复+文件最后一起发）：
    // 流式阶段从 fullReply 里增量解析 [thinking:...] 标签内容推给此流，
    // StreamingMessageItem 收集后以 ThoughtCard 形式实时展示（默认折叠，
    // 与落库后的呈现一致）；正式回复正文与文件卡片仍在收尾时一次性合并提交。
    private val _streamingThinking: MutableStateFlow<String?>,
    private val messageRepo: MessageRepository,
    private val memoryRepo: MemoryRepository,
    private val memoryEngine: MemoryEngine,
    private val identityRepo: IdentityRepository,
    private val relationshipEngine: RelationshipEngine,
    private val presenceEngine: PresenceEngine,
    private val pregnancyRepo: PregnancyRepository,
    private val characterStateRepo: CharacterStateRepository,
    private val daughterRepo: DaughterCharacterRepository,
    private val agentPlanRepo: AgentPlanRepository,
    private val learningGoalRepo: LearningGoalRepository,
    private val skillRepo: SkillRepository,   // Window C 技能系统
    private val taskRepo: TaskRepository,
    private val projectRepo: ProjectRepository,
    private val workflowRepo: WorkflowRepository,
    // 灵活自动化编排（验收缺口修复，§11.10）：链条未播报查询用，与 workflowRepo
    // 同款来源——ChatViewModel 传入 AppContainer.instance.chainRunRepository。
    private val chainRunRepository: ChainRunRepository,
    private val eventRepo: EventRepository,
    private val pregnancyDelegate: PregnancyPromptDelegate,
    private val agentRelationEngine: AgentRelationEngine,
    private val daughterGenerator: DaughterCharacterGenerator,
    private val characterTitleRelationRepo: CharacterTitleRelationRepository,
    private val db: AppDatabase,
    // B2 审查报告问题 #1 修复：与 ChatSessionDelegate 已有的同名参数保持一致的
    // 命名和风格，供 ImpersonationStateStore 写入侧（sendMessage 内假扮状态
    // 变化时）获取 Context，无需额外走 AppContainer（其 appContext 是 private）。
    private val getApplication: () -> Context,
    // Mutable state accessors
    private val getCurrentCharacterId: () -> Int,
    private val getReplyJob: () -> Job?,
    private val setReplyJob: (Job?) -> Unit,
    private val getEvaluationEngine: () -> EvaluationEngine?,
    private val pendingKeywordTriggerMap: ConcurrentHashMap<Int, Boolean>,
    private val lastFertileJudgeAtMap: ConcurrentHashMap<Int, Long>,
    private val viewModelScope: CoroutineScope,
    private val loadMessages: suspend (Int) -> Unit,
    private val MAX_HISTORY_CHARS: Int = 450_000,
) {

    // ── P0-4 PR4：Identity WARM 层注入轮次计数器（私聊路径）──
    // 每 5 轮注入一次 WARM 层（13 项），HOT 层（7 项）每轮注入（v10 风险点 3 裁定）。
    // 每次 sendMessage 成功一轮 +1；counter % 5 == 0 时注入 WARM（省 token）。
    //
    // P1-5 修复：本 orchestrator 是 App 单例 ChatViewModel 的成员，跨所有角色共享。
    // 原来用全局累加计数，导致角色 B 的 WARM 注入轮次取决于全局计数（可能与 B 实际
    // 对话轮数脱节，B 可能连续多轮缺核心身份注入）。改为按 characterId 分片计数。
    private val identityWarmRoundByCharacter = mutableMapOf<Int, Int>()

    // ── P0-4 PR5：presence 层 COLD 变化检测（v10 风险点 2：presenceSnap 用 hashCode）──
    // presence 存在状态非每轮必注入，仅当状态变化（hashCode 变化）时注入，省 token。
    //
    // P1-5 修复：哈希同样按 characterId 分片，避免角色切换后首轮 presence 因哈希与
    // 上一角色碰巧相同而被跳过。
    private val lastPresenceHashByCharacter = mutableMapOf<Int, Int>()

    fun sendMessage(text: String): Boolean {
        val provider = guardAgainstConcurrentSend(text) ?: return false
        // P1-5 修复：按角色计数。当前角色不存在时从 0 开始（首轮 %5==0 注入 WARM）。
        val warmCharId = getCurrentCharacterId()
        identityWarmRoundByCharacter[warmCharId] = (identityWarmRoundByCharacter[warmCharId] ?: 0) + 1

        getReplyJob()?.cancel()
        setReplyJob(viewModelScope.launch(Dispatchers.IO) {
            // B-1 修复：try-finally 保证无论 catch 块外的 DAO / engine 调用抛出何种异常，
            // isTyping 都能被置回 false，避免发送按钮永久禁用。
            // （CancellationException 会越过 catch 直接到 finally，再向上 rethrow，
            //   结构化并发不受影响。）
            try {
                val ctx = persistUserMessageAndLoadCharacter(text) ?: return@launch
                val userMsgId = ctx.userMsgId
                val character = ctx.character
                val messages = ctx.messages
                val identityEntity = ctx.identityEntity
                val toolDesc = ctx.toolDesc
                val relationshipSnapshot = ctx.relationshipSnapshot
                val chatMode = ctx.chatMode
                val layers = buildPromptLayers(ctx, text)
                val systemPrompt = layers.systemPrompt
                val speakerContext = layers.speakerContext
                val characterState = layers.characterState
                val pregnancyState = layers.pregnancyState
                val d3QuestionPatch = layers.d3QuestionPatch
                val d3PendingAsk = layers.d3PendingAsk
                val unreportedJob = layers.unreportedJob
                val unreportedChainRun = layers.unreportedChainRun

                val config = buildStreamConfig()

                val reply = streamAndInterceptReply(
                    provider = provider,
                    messages = messages,
                    systemPrompt = systemPrompt,
                    config = config,
                    speakerContext = speakerContext,
                    character = character,
                )
                // P1-6 修复：streamAndInterceptReply 内部已经在 catch(Throwable) 里
                // 置过 _uiState.error 提示。这里 failed=true 时直接短路整个后续流程——
                // 不清洗、不落库、不 markRecapReported、不 finalizeRound。
                // 避免把"异常中断那一刻攒到的半截 fullReply"当作正常回复写入 DB，
                // 既污染聊天记录展示，也会作为上下文喂给下一轮 LLM，让角色说出
                // 自己从未真正说完的话。isTyping 等状态由外层 finally 统一收尾。
                if (reply.failed) {
                    return@launch
                }
                val fullReply = reply.fullReply
                val pendingExportedFiles = reply.pendingExportedFiles
                var pendingTablePayloadJson = reply.pendingTablePayloadJson
                val replyMsgId = reply.replyMsgId
                val toolTrace = reply.toolTrace

                // Fix-MoodLeak（zaijian）：①②④ 一并处理——
                // 在 cleanReply 产生的唯一入口剥离 [mood:xxx] 标签，
                // 这样后面所有消费者（DB 落库、HeuristicRelTracker、D3 意图识别、D5 关系引擎）
                // 拿到的都是已经干净的文本，不需要逐个消费点单独打补丁。
                //
                // Fix-ThinkingLeak（zaijian）：在同一入口先剥离 [thinking:...]，
                // 复用 stripMoodTag 已验证过的"结构化标记 + 客户端剥离"路径——
                // 剥离顺序是先 thinking 后 mood，因为 Output Layer 里 mood 标签固定是
                // 全文最后一行，thinking 标签可能夹在台词正文中间，先处理内层夹杂的标签，
                // 再处理末尾的 mood 标签，两者互不干扰（mood 正则只锚定字符串末尾）。
                //
                // v1.36 问题2（三层分离）：在 thinking 与 mood 之间插入 stripPsychText，
                // 剥离正文中圆括号包裹的心理感受描写——顺序上先处理 [thinking:] 这种
                // 结构化标签，再处理圆括号这种裸文本标记，最后处理锚定末尾的 mood 标签，
                // 三者互不重叠。
                val (cleanReply, parsedThinking, parsedPsych) = cleanAndExtractMood(fullReply, characterState)
                val latestMessages = persistAssistantMessage(
                    replyMsgId = replyMsgId,
                    cleanReply = cleanReply,
                    parsedThinking = parsedThinking,
                    parsedPsych = parsedPsych,
                    speakerContext = speakerContext,
                    text = text,
                    pendingExportedFiles = pendingExportedFiles,
                    pendingTablePayloadJson = pendingTablePayloadJson,
                    toolTrace = toolTrace,
                    chatMode = chatMode,
                )
                markRecapReported(unreportedJob, unreportedChainRun)

                // P1-10-1 修复：把后置 LLM 分析（评分卡、受孕窗口判定、D5 升阶、D3 didAsk）
                // 收敛到 finalizeRound()，其内部移入独立 viewModelScope.launch，使 replyJob 的
                // finally 块能立即清零 isTyping，避免用户在后置分析期间看到输入框持续禁用。
                finalizeRound(
                    cleanReply = cleanReply,
                    text = text,
                    d3PendingAsk = d3PendingAsk,
                    d3QuestionPatch = d3QuestionPatch,
                    unreportedJob = unreportedJob,
                    pregnancyState = pregnancyState,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // P1-9 修复：原 try 只有 finally 无 catch，后置阶段的裸 Room 调用
                // （buildPromptLayers 的查询、cleanAndExtractMood 的 updateState、
                // persistAssistantMessage 的 insert 等）任一抛 RuntimeException/Error 会经
                // finally（仅重置 isTyping）后直接传播到 viewModelScope.launch 顶层，导致
                // App 崩溃且用户消息已落库但 AI 回复丢失、无提示。这里补 catch：CancellationException
                // 必须 rethrow 保持结构化并发；其余异常记日志 + 置 UI error，不让它击穿。
                throw e
            } catch (e: Throwable) {
                ZLog.e("ChatMessageOrchestrator", "sendMessage 异常", e)
                _uiState.update { it.copy(error = "发送失败，请重试") }
            } finally {
                // B-1 修复：finally 保证任何路径（正常完成、网络异常、CancellationException）
                // 都能重置 isTyping，避免发送按钮永久禁用。
                // P1-10-1 修复：后置 LLM 分析已移至独立 launch，finally 在流式结束后立即执行。
                // P1-3 修复：streamingContent 不再写入 _uiState
                //
                // Fix-isTyping竞态（C）：旧 job 被取消后，它的 finally 块仍然会执行
                // （Kotlin 协程取消后 finally 正常运行），但如果这段时间里已经有一个
                // 更新的 sendMessage() 调用 setReplyJob() 换上了新 job（新 job 早已
                // 把 isTyping 设回 true 并正在真实生成回复），旧 job 的 finally 如果
                // 无条件把 isTyping 冲回 false，就会在新 job 仍在跑的时候把发送按钮
                // 短暂重新点亮——用户看不出区别，很可能趁这个窗口再发一条消息，
                // 而这一发又会把"新 job"当成"旧 job"取消掉，形成连环打断
                // （这正是 excel_gen 取消竞态里"能连发第三条"的可能成因之一）。
                // 改为只有自己仍然是 getReplyJob() 记录的那个 job 时才重置——
                // 说明确实没有更新的 job 顶替过自己，重置是安全的；否则跳过，
                // 交给顶替自己的那个新 job 的 finally 负责收尾。
                if (getReplyJob() === currentCoroutineContext()[Job]) {
                    _uiState.update { it.copy(isTyping = false, streamingHint = null) }
                    _streamingContent.value = null
                    _streamingPsych.value = null
                    _streamingThinking.value = null
                }
            }
        })
        // 专项审查报告问题12：返回"是否真正进入发送流程"，供 UI 决定是否清空输入框。
        // 被门控拦截（无 provider / 工具执行中）时返回 false，输入框文字得以保留。
        return true
    }

    /**
     * 发送门控：空白文本 / 未选角色 / 无 API Provider / 正有工具在执行 任一命中即返回 null，
     * 调用方直接 return，不进入发送流程。
     *
     * - 无 provider：置 isApiKeyMissing，提示 UI 层引导用户配置。
     * - Fix-孤儿文件 ③（配合 ToolCallInterceptor.isToolInFlight 一起看）：
     *   正常情况下这里几乎不会命中——isTyping 已经在门控发送按钮，走到这行
     *   说明要么是 isTyping 门控失效的边界情况，要么是未来新增的某条不经过
     *   按钮的调用路径。以前这里会无条件 getReplyJob()?.cancel()：如果恰好
     *   有 excel_gen/pptx_gen 这类工具正在写文件（POI 写入阻塞、取消不了），
     *   文件会正常落盘但这次回复被腰斩、用户体验上像是"话说到一半没了"
     *   （①②已经保证这种情况下文件本身不会真的丢，见 executeWithTimeout 的
     *   Fix-孤儿文件 说明，但被打断这件事本身仍然是不好的体验）。现在改成：
     *   发现正有工具在执行时，不取消、不发送，只提示用户稍候。
     */
    private fun guardAgainstConcurrentSend(text: String): LLMProvider? {
        if (text.isBlank() || getCurrentCharacterId() < 0) return null
        val provider = ProviderManager.instance.activeProvider ?: run {
            _uiState.update { it.copy(isApiKeyMissing = true) }
            return null
        }
        if (ToolCallInterceptor.isToolInFlight(AgentActivityRepository.SceneType.CHAT, getCurrentCharacterId())) {
            _uiState.update { it.copy(error = "上一个操作还在进行中，请稍候再发送") }
            return null
        }
        return provider
    }

    /**
     * 分段 2：用户消息落库 + 角色加载校验 + 历史消息截断 + 基础 prompt 上下文。
     * 返回 [SendContext]（含后续所有 prompt 组装所需的只读上下文）；character 为 null
     * （女儿数据损坏降级）时已写入用户可见错误并返回 null，调用方应 return@launch。
     */
    private suspend fun persistUserMessageAndLoadCharacter(text: String): SendContext? {
        val userMsgId = UUID.randomUUID().toString()
        messageRepo.insert(
            MessageEntity(
                id = userMsgId,
                characterId = getCurrentCharacterId(),
                role = "user",
                content = text,
                createdAt = System.currentTimeMillis(),
            )
        )
        loadMessages(getCurrentCharacterId())

        // 问题17（第二阶段）附带修复：detectUserConsent() 引入 AI 语义判定后，
        // evaluateConsent()（下方 pregnancyTriggerPromptPatch 计算过程中调用）
        // 最坏情况下会有数秒延迟（UserConsentIntentJudge 的 8s 超时上限），
        // 而原来的 isTyping=true 要等到 prompt 组装完、即将开始流式回复时
        // 才会置位——这中间这段"用户已发送但看不到任何反馈"的空窗期，在关键词
        // 匹配年代几乎不可感知（同步操作，微秒级），现在可能被 AI 判定的网络延迟
        // 明显放大，用户会看到发送后界面短暂"卡住"。这里提前到用户消息落库、
        // UI 刷新之后立即置位，让"正在输入"指示与发送按钮禁用尽早生效——顺带
        // 修复了一个已存在但此前不易察觉的小问题：之前这段窗口期 canSend 仍为
        // true（ChatInputBar.kt 用 !isTyping 门控发送按钮），理论上用户可以在
        // prompt 组装完成前重复点击发送。
        // P1-3 修复：streamingContent 不再写入 _uiState（双写导致整屏重组），
        // 只保留独立 _streamingContent StateFlow 供 StreamingMessageItem 单独收集
        _uiState.update { it.copy(isTyping = true) }

        // W2-2 修复：character 为 null（女儿数据损坏导致 loadCharacterJob 中
        // DaughterDataException 被捕获后降级为 null）时，之前直接 return@launch，
        // 用户消息已落库但 AI 永远不回复、且没有任何提示，界面表现为"正在输入"
        // 一闪而过后卡住。这里在跳过发送前写入用户可见的错误提示。
        val character = _uiState.value.character ?: run {
            _uiState.update { it.copy(error = "角色数据异常，请尝试重新生成或联系开发者") }
            return null
        }
        // Bug2-fix: 过滤非法 role，只保留 user/assistant 两种合法值
        // - role = "system" 且带 [AGENT_MSG:xx]/[ROUNDTABLE_TRIGGER] 前缀的，是历史遗留的
        //   内部控制信号（对应 agent_message/roundtable_trigger 工具已删除），仅作数据卫生
        //   防御——老 DB 里可能残留这类行，不透传给角色，跳过。
        // - role = characterId.toString()（如 "1","2"）的主动消息映射为 "assistant"
        //
        // Fix-FileImportBlindSpot：此前这里把所有 role="system" 消息一律跳过，
        // 连 notifyFileImported() 写的"用户导入了一个文件：xxx"也被当成内部控制信号
        // 一起丢掉了——但这条消息是"文件被导入过"这件事唯一的记录。丢掉之后，
        // 角色对用户导入的文件完全没有感知：UI 上用户能看到这条系统提示，
        // 但角色的对话历史里从来没出现过，用户问"这个看到了吗"时，角色手上
        // 根本没有任何"这个"可以指代，只能瞎猜。现在把它当成用户那边发生的
        // 一个事实，以 user 身份带进历史（角色如果需要看文件具体内容，
        // 可以自己调用读文件工具，这里不直接塞入全文，避免不必要的 token 开销）。
        val messages = messageRepo.getByCharacterForContext(getCurrentCharacterId()).mapNotNull { msg ->
            when (msg.role) {
                "user", "assistant" -> LLMMessage(role = msg.role, content = msg.content)
                "system" -> {
                    if (msg.content.startsWith("[AGENT_MSG:") || msg.content.startsWith("[ROUNDTABLE_TRIGGER]")) {
                        null  // 历史遗留内部控制信号（工具已删除），不进入对话上下文
                    } else if (msg.content.startsWith(FILE_READ_MARK_PREFIX)) {
                        // Fix（消息角色污染）：FILE_READ_MARK_PREFIX 只是给 ToolCallInterceptor.
                        // alreadyRead 检测用的内部凭证标记（见 FileReadConfirmed 处说明），不是
                        // 真实对话内容。之前这个分支只识别 [AGENT_MSG:/[ROUNDTABLE_TRIGGER] 前缀，
                        // 落到 else 分支被当成普通 user 消息透传给 LLM，导致 "[FILE_READ_MARK]"
                        // 这串内部标记字面量混进了模型看到的对话历史。这里去掉前缀，只把
                        // alreadyRead 检测真正依赖的 "[工具执行结果] 文件已读取：xxx" 部分交给
                        // LLM，role 仍保持 "user"（与 ToolCallInterceptor.alreadyRead 的匹配条件
                        // m.role == "user" 保持一致，避免改动波及已修复的 file_read 锁死检测）。
                        LLMMessage(role = "user", content = msg.content.removePrefix(FILE_READ_MARK_PREFIX))
                    } else if (msg.content.startsWith(TOOL_TRACE_MARK_PREFIX)) {
                        // 工具结果跨消息保留：与 FILE_READ_MARK_PREFIX 同款处理——剥离前缀，
                        // 只把摘要正文（"[工具执行结果]\n· xxx"）交给 LLM，role 仍是 "user"，
                        // 让下一轮追问"刚才那个文件第二行写的什么"时角色有据可查。
                        LLMMessage(role = "user", content = msg.content.removePrefix(TOOL_TRACE_MARK_PREFIX))
                    } else {
                        LLMMessage(role = "user", content = msg.content)
                    }
                }
                else -> LLMMessage(role = "assistant", content = msg.content)  // ProactiveMessageNotifier 写入的主动消息
            }
        }.let { all ->
            // 按字符预算从最新消息往前累积，超出上限时停止，
            // 保证最近对话优先保留，兼容 DeepSeek V4 Flash 1M 上下文
            var charCount = 0
            all.asReversed().takeWhile { msg ->
                charCount += msg.content.length
                charCount <= MAX_HISTORY_CHARS
            }.reversed()
        }
        val identityEntity = identityRepo.getById(getCurrentCharacterId())
        val toolDesc = AgentToolRegistry.buildToolDescriptionBlock()
        val relationshipSnapshot = relationshipEngine.buildPromptSnapshot(getCurrentCharacterId())
        // 待办4：COMPANION 模式下可传入 excludeDomain=MemoryDomain.WORK
        val chatMode = _uiState.value.chatMode
        return SendContext(
            userMsgId = userMsgId,
            character = character,
            messages = messages,
            identityEntity = identityEntity,
            toolDesc = toolDesc,
            relationshipSnapshot = relationshipSnapshot,
            chatMode = chatMode,
        )
    }

    /** 分段 2 的返回载体：本轮用户消息落库后、prompt 组装前所需的只读上下文。 */
    private data class SendContext(
        val userMsgId: String,
        val character: CharacterConfig,
        val messages: List<LLMMessage>,
        val identityEntity: CharacterIdentityEntity?,
        val toolDesc: String,
        val relationshipSnapshot: String,
        val chatMode: ChatMode,
    )

    /**
     * 私聊状态播报（buildPromptLayers 子步骤）：查询当前角色所有"未告知"的私聊会话，
     * 取完整逐字记录拼进播报文本，让角色本轮就能主动、准确提起"你刚才跟谁聊了什么"；
     * 播报后调用 markNotified 标记，避免同一次会话被反复播报。单轮最多补播 2 个会话。
     * 任何失败降级为空字符串（不阻断主流程）。
     */
    private suspend fun gatherPrivateChatRecap(): String {
        // private_chat_send（ChatScreen 工具调用入口）已同步化：A 调用它时
        // 会等私聊真正聊完才返回，返回内容里直接带着逐字记录，那一轮 A 自然
        // 知道聊了什么。但这里覆盖的是跨轮场景：那次调用早已是几轮对话之前的
        // 事，当前 prompt 上下文里已经看不到那次的返回内容了；以及
        // PrivateChatViewModel.triggerSession（PrivateChatScreen 管理面板的
        // 手动入口）仍是异步路径，A 完全不在触发那次私聊的对话轮次里，
        // 不会自然知道发生过——B（被动一方）更是完全没有任何主动触发点。
        //
        // 私聊实时同步修复：此前按"近2小时"时间窗口查询，超窗口就永久错过
        // 播报机会，且只给摘要（几轮/什么状态），不含逐字内容——用户追问细节
        // 时角色还得自己再调一次 private_chat_history 才能如实回答，追问不到
        // 就只能拒答或编。现在改为按"未告知"查询（不依赖时间窗口，见
        // PrivateChatSessionDao.getUnnotifiedByCharacter），且直接把完整
        // 逐字记录拼进播报文本——角色这一轮就能完整、准确地知道双方说了什么，
        // 不需要用户追问、也不需要角色自己再调用工具查。播报后立即调用
        // markNotified 标记，避免同一次会话被反复播报。
        //
        // limit=2：一次主对话轮次最多补播 2 次私聊，逐字记录本身可能不短，
        // 避免单轮 prompt 因为攒了太多次未读私聊而被撑得过长；未播报完的
        // 会话留到下一轮继续补，不会丢失（getUnnotifiedByCharacter 不依赖
        // 时间窗口，下次还能查到）。
        return try {
            val container = AppContainer.instance
            val currentCharacterId = getCurrentCharacterId()
            val unnotifiedSessions = container.privateChatSessionRepo
                .getUnnotifiedByCharacter(currentCharacterId, limit = 2)
            if (unnotifiedSessions.isEmpty()) "" else {
                val recapText = buildString {
                    appendLine("[私聊动态]")
                    for (session in unnotifiedSessions) {
                        val pair = container.privateChatPairRepo.get(session.pairId)
                        if (pair == null) continue
                        val otherId = if (pair.characterIdA == currentCharacterId)
                            pair.characterIdB else pair.characterIdA
                        val otherName = try {
                            com.zaijian.zhoumuyun.data.model.DefaultCharacters
                                .firstOrNull { it.id == otherId }?.name
                                ?: container.daughterCharacterRepo.getCharacterConfig(otherId)?.name
                                ?: "另一个角色"
                        } catch (e: Throwable) { "另一个角色" }
                        val statusText = when (session.status) {
                            "completed" -> "聊完了"
                            "interrupted" -> "聊到一半被中断了"
                            "disconnected" -> "对方中途结束了对话"
                            else -> session.status
                        }
                        appendLine("你和${otherName}的私聊$statusText" +
                            if (session.turnCount > 0) "（共${session.turnCount}轮），完整对话如下：" else "：")
                        val transcript = try {
                            container.privateChatMessageRepo
                                .getRecentBySession(session.sessionId, limit = session.turnCount.coerceAtLeast(1) + 1)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            emptyList()
                        }
                        if (transcript.isEmpty()) {
                            appendLine("（未能取到逐字记录，用户追问时可调用 private_chat_history 再查一次）")
                        } else {
                            for (msg in transcript) {
                                val speakerName = if (msg.senderCharacterId == currentCharacterId) "你" else otherName
                                appendLine("$speakerName：${msg.content}")
                            }
                        }
                        appendLine()
                    }
                    append("以上是真实发生的私聊内容，你已经完整知道说了什么——" +
                        "用户问起时直接按这份记录回答，不要说自己不知道、也不要再去调用" +
                        "private_chat_history（内容已经在这里了），更不要编造这份记录之外的内容。")
                }
                // 播报即视为已告知，标记后同一会话不会在后续轮次重复播报。
                // 单条标记失败不影响本轮播报文本已经生成的事实，只记日志，
                // 下一轮如果标记仍未成功会再次查到并重试播报（幂等，不会出错，
                // 顶多是多播一次，好过因为标记失败导致这次私聊被永久漏播）。
                for (session in unnotifiedSessions) {
                    try {
                        container.privateChatSessionRepo.markNotified(session.sessionId, currentCharacterId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ZLog.w("ChatMessageOrchestrator", "私聊播报标记已告知失败: sessionId=${session.sessionId}", e)
                    }
                }
                recapText
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ""
        }
    }

    /** workflowRecap / chainRecap 播报的查询结果载体。 */
    private data class RecapContext(
        val workflowRecapText: String,
        val chainRecapText: String,
        val unreportedJob: WorkflowJobEntity?,
        val unreportedChainRun: ChainRunEntity?,
    )

    /**
     * 播报上下文：查 isReported=0 的已完成后台任务 / 链条，生成简短 recap 文案。
     * 标记已读（markReported）在回复落库后由调用方执行（sendMessage 末尾），
     * 确保即使回复中途异常也不会丢失本次 recap 机会。
     */
    /**
     * 分段 3：buildPromptLayers 的返回值，承载 sendMessage 后续阶段所需的所有 Prompt 组装结果。
     */
    private data class PromptLayers(
        val systemPrompt: String,
        val speakerContext: SpeakerContext,
        val characterState: CharacterStateLayer,
        val pregnancyState: PregnancyState,
        val d3QuestionPatch: String,
        val d3PendingAsk: Pair<PregnancyQuestionType, Int>?,
        val unreportedJob: WorkflowJobEntity?,
        val unreportedChainRun: ChainRunEntity?,
    )

    private suspend fun buildRecapContext(): RecapContext {
        val unreportedJob = workflowRepo.findUnreported(getCurrentCharacterId()).firstOrNull()
        val workflowRecapText = if (unreportedJob != null) {
            val statusLabel = when (unreportedJob.status) {
                "COMPLETED" -> "✅ 完成"
                "FAILED"    -> "❌ 失败"
                else        -> unreportedJob.status
            }
            val detail = unreportedJob.resultSummary
                ?: unreportedJob.failReason
                ?: ""
            buildString {
                appendLine("[后台任务播报]")
                appendLine("上次后台任务「${unreportedJob.goal}」已 $statusLabel。")
                if (detail.isNotBlank()) appendLine("结果：${detail.take(120)}")
                append("请在本次回复中，用你自己的语气自然地提及这件事（一句话即可），不要暴露技术细节。")
            }
        } else ""

        val unreportedChainRun = chainRunRepository.findUnreported(getCurrentCharacterId()).firstOrNull()
        val chainRecapText = if (unreportedChainRun != null) {
            val statusLabel = when (unreportedChainRun.status) {
                "COMPLETED" -> "✅ 完成"
                "FAILED"    -> "❌ 失败"
                "CANCELLED" -> "⚪ 已取消"
                else        -> unreportedChainRun.status
            }
            // ChainRunEntity 没有独立的 goal/failReason 字段（对照 WorkflowJobEntity）：
            // 链条名称需从其定义查（可能已被禁用/删除，findDefinition 返回 null 时降级为
            // 通用描述）；失败原因走 context._failReason（ChainRunRepositoryImpl.markFailed
            // 写入的约定 key，见 §5.5）。
            val chainName = chainRunRepository.findDefinition(unreportedChainRun.chainDefId)?.name
                ?: "自动化规则"
            val detail = try {
                org.json.JSONObject(unreportedChainRun.context).optString("_failReason", "")
            } catch (e: Exception) {
                ""
            }
            buildString {
                appendLine("[链条自动化播报]")
                appendLine("上次自动化规则「$chainName」已 $statusLabel。")
                if (detail.isNotBlank()) appendLine("原因：${detail.take(120)}")
                append("请在本次回复中，用你自己的语气自然地提及这件事（一句话即可），不要暴露技术细节。")
            }
        } else ""

        return RecapContext(
            workflowRecapText = workflowRecapText,
            chainRecapText = chainRecapText,
            unreportedJob = unreportedJob,
            unreportedChainRun = unreportedChainRun,
        )
    }

    /** 假扮身份识别的产出：发言者上下文 + 头衔注入块。 */
    private data class ImpersonationResult(
        val speakerContext: SpeakerContext,
        val interCharRelBlock: String,
    )

    /**
     * 角色间关系头衔系统·接入点2：假扮身份识别（替代原 IdentityGuard 自称异常/称呼
     * 异常判定）。精确匹配"我不是主人，我是XX"，XX 命中预设名单才算数；命中后查头衔
     * 生成 interCharRelBlock 注入。speakerContext 由假扮识别结果直接推导。
     *
     * 持久化：按 characterId 分片存取（ChatUiState.impersonationByCharacter），命中后
     * 持续到用户说"我是主人"才清除，不因后续几句话"表现正常"自动解除。
     */
    private suspend fun resolveImpersonationContext(text: String, userMsgId: String): ImpersonationResult {
        val activeCharacterId = getCurrentCharacterId()
        val prevImpersonation = _uiState.value.impersonationByCharacter[activeCharacterId]
        var impersonatedName = prevImpersonation
        if (prevImpersonation != null && ImpersonationDetector.claimsToBeOwner(text)) {
            impersonatedName = null
            _uiState.update {
                it.copy(impersonationByCharacter = it.impersonationByCharacter + (activeCharacterId to null))
            }
            // B2 审查报告问题 #1 修复：解除假扮时同步清除本地持久化记录，
            // 否则进程死亡重建后 ChatSessionDelegate.init() 会从 SharedPreferences
            // 里读到一条已经过期的假扮记录，把已解除的假扮状态又恢复回来。
            ImpersonationStateStore.save(getApplication(), activeCharacterId, null)
        } else if (prevImpersonation == null) {
            val claimed = ImpersonationDetector.extractClaimedName(text)
            if (claimed != null && characterTitleRelationRepo.isPresetName(claimed)) {
                impersonatedName = claimed
                _uiState.update {
                    it.copy(impersonationByCharacter = it.impersonationByCharacter + (activeCharacterId to claimed))
                }
                // B2 审查报告问题 #1 修复：命中假扮时同步持久化具体名字，
                // 供进程死亡重建后 ChatSessionDelegate.init() 恢复，避免记忆
                // 隔离/关系值跳过/ReplyGuard 三项保护在恢复后短暂失效。
                ImpersonationStateStore.save(getApplication(), activeCharacterId, claimed)
            }
        }
        val speakerContext = if (impersonatedName != null)
            SpeakerContext.NON_OWNER
        else
            SpeakerContext.OWNER_DIRECT
        // C8 #43 写入侧收尾：userMsgId 那条消息在假扮判定算出来之前就已经落库
        // （默认 OWNER_DIRECT），这里判定结果出来后回写。OWNER_DIRECT 是默认值，
        // 只在 NON_OWNER 时才需要真的发一次 UPDATE。
        if (speakerContext.isNonOwner) {
            messageRepo.updateSpeakerContext(userMsgId, speakerContext.name)
        }
        val interCharRelBlock = if (impersonatedName != null) {
            val nonNullName = impersonatedName
            // XX 若同时是真实角色（能在初代9人/女儿中查到 id）→ 按 toCharacterId 查头衔，
            // 否则按 toPresetName（字符串）查——两者查询入口不同，但对 prompt 的呈现一致。
            val matchedCharacterId = resolveCharacterIdByName(nonNullName)
            val title = if (matchedCharacterId != null) {
                characterTitleRelationRepo.getTitle(activeCharacterId, matchedCharacterId)
            } else {
                characterTitleRelationRepo.getTitleForPresetName(activeCharacterId, nonNullName)
            }
            if (!title.isNullOrBlank()) {
                "【眼前这个人是谁】眼前这个人不是主人，是你认识的「${nonNullName}」，" +
                    "你认TA做「${title}」，请按这层关系真心对待，不要把TA当成主人。"
            } else {
                "【眼前这个人是谁】眼前这个人不是主人，是「${nonNullName}」，" +
                    "你认识TA但还没有明确的关系认定，以你对TA的实际了解对待，不要预设亲密关系。"
            }
        } else {
            ""
        }
        return ImpersonationResult(speakerContext, interCharRelBlock)
    }

    /**
     * 分段 8：后置 LLM 分析收尾——评分卡触发链路 + 孕期 post-reply 分析
     * （受孕窗口 / D5 升阶 / D3 didAsk）。
     *
     * P1-10-1 修复：所有后置 LLM 分析移入独立的 viewModelScope.launch，使 replyJob 的
     * finally 块能立即清零 isTyping，避免用户在后置分析期间（可能数秒）看到输入框持续
     * 禁用。后置分析捕获所有需要的不可变局部变量，不依赖任何 replyJob 的可变状态。
     */
    /**
     * 分段 3：组装本轮全部 Prompt Layer（Memory/State/AgentPlan/LearningGoal/Skill/
     * 孕期/女儿/Knowledge/recap/Task/身份头衔/presence COLD）并调用 buildSystemPrompt，
     * 返回 PromptLayers 供 sendMessage 后续流式生成 / 关系值 / 落库使用。
     * 各 Layer 的副作用（私聊 markNotified、workflow markReported 交由调用方在合适时机执行）。
     */
    private suspend fun buildPromptLayers(ctx: SendContext, text: String): PromptLayers {
            val userMsgId = ctx.userMsgId
            val character = ctx.character
            val identityEntity = ctx.identityEntity
            val toolDesc = ctx.toolDesc
            val relationshipSnapshot = ctx.relationshipSnapshot
            val chatMode = ctx.chatMode
    
    
    // ── 补全 Memory Layer（核心 Bug：之前从未查询，一直是空列表）──
    // coreMemories：每次对话必注入的高重要度记忆（A-4：按500字符预算累加，非固定条数）
    // relevantMemories：Window A-1 L2优先检索路由（L2 tag精确匹配→L1 FTS4补充）
    val coreMemories     = memoryRepo.getCoreMemories(getCurrentCharacterId())
    val relevantMemories = memoryRepo.searchRelevantWithRouting(
        characterId = getCurrentCharacterId(),
        query       = text,
        limit       = 8,
    )
    
    // ── 补全 State Layer（presence 在场状态早就在算，只是没接进 prompt）──
    var presenceSnap = presenceEngine.getCachedPresence(getCurrentCharacterId())
    
    // ── 补全 AgentPlan Layer（角色自己写的进化方案）──
    val activePlan = agentPlanRepo.getActive(getCurrentCharacterId())
    val agentPlanBlock = activePlan?.let {
        com.zaijian.zhoumuyun.data.prompt.TaskRulePromptBuilder.buildAgentPlanBlock(it.title, it.content)
    } ?: ""
    
    // ── 补全 LearningGoal Layer（isLocked=true 的能力规则，按目标分组）──
    val activeGoals = learningGoalRepo.getActive(getCurrentCharacterId())
    val rulesByGoal = activeGoals.associate { goal ->
        goal.title to memoryRepo
            .getLockedRules(getCurrentCharacterId(), goal.id)
            .map { it.content }
    }
    val ruleLayerBlock = com.zaijian.zhoumuyun.data.prompt.TaskRulePromptBuilder.buildRuleLayerBlock(rulesByGoal)
    
    // ── Window C：补全 Skill Layer（§3 第一级"目录注入"）──
    // 仅注入当前角色 ACTIVE 技能的 shortDescriptor 列表 + 触发提示，控制 token；
    // Agent 判断某条适用时用 skill_expand 按需展开 fullContent。无技能时返回空串，
    // PromptOrchestrator 自动跳过此层。此处在协程内，suspend 调用安全。
    val skillCatalogBlock = SkillRegistry.buildSkillCatalogBlock(
        characterId = getCurrentCharacterId(),
        repo = skillRepo,
    )
    
    // ── 补全 characterState（深层状态：desireStrength/emotionalSuppression等，
    //    W6-1 修复：提前读取，供 PregnancyPromptDelegate 使用）──
    var characterState = characterStateRepo.getState(getCurrentCharacterId())
    
    // ── W6-1 修复：孕期 Prompt 组装逻辑提取到 PregnancyPromptDelegate ──
    // 原来近 310 行的 evaluateConsent / miscarriage / failure / routine pressure /
    // D3 槎位问答 / D4 触发逻辑全部收敛到 buildPregnancyPrompts() 一个调用。
    val pregnancyPromptResult = pregnancyDelegate.buildPregnancyPrompts(
        characterId              = getCurrentCharacterId(),
        userText                 = text,
        currentPregnancyState    = pregnancyRepo.getPregnancy(getCurrentCharacterId()),
        characterState           = characterState,
        pendingKeywordTriggerMap = pendingKeywordTriggerMap,
        onTriggerD4Generation    = { lockedAnswers ->
            val motherChar = _uiState.value.character
            if (motherChar != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        daughterGenerator.generateForMother(
                            motherConfig  = motherChar,
                            lockedAnswers = lockedAnswers,
                        )
                    } catch (e: CancellationException) {
                        throw e  // 协程取消必须重新抛出，不能当成业务失败吞掉
                    } catch (e: Throwable) {
                        // 与主回复流程同批修复：catch Throwable 而非 Exception，
                        // 这个 launch 独立于外层 try（脱离主流程保护范围），
                        // 原先若这里触发 Error 会直接击穿到 viewModelScope 顶层。
                        ZLog.e("ChatViewModel", "D4 generateForMother 失败", e)
                        _uiState.update { it.copy(pendingDaughterGenerationError = "女儿生成失败，请稍后重试。") }
                    }
                }
            }
        }
    )
    var pregnancyState = pregnancyPromptResult.pregnancyState
    val pregnancyTriggerPromptPatch = pregnancyPromptResult.pregnancyTriggerPromptPatch
    val miscarriageAftermathPatch   = pregnancyPromptResult.miscarriageAftermathPatch
    val failureContextPatch         = pregnancyPromptResult.failureContextPatch
    val routinePressurePatch        = pregnancyPromptResult.routinePressurePatch
    val d3QuestionPatch             = pregnancyPromptResult.d3QuestionPatch
    val d3PendingAsk                = pregnancyPromptResult.d3PendingAsk
    
    // ── 复核修复 #7/#13/#20：女儿角色单独查询专属状态数据 ──────────
    // CharacterStateRepository.getState() 的持久化 fallback 只查
    // DefaultCharacters（ID 1-9），对女儿角色（ID>=1000）永远查不到，
    // 会退化为全空白 CharacterStateLayer()。这里单独查一次女儿的
    // DaughterCharacterData：
    //   1. 若 character_state 表尚无该女儿的持久化记录（characterState
    //      仍是空白默认值），用 DaughterStateLayer 的真实数值维度覆盖，
    //      而不是让 LLM 看到全 0/默认值的假状态；
    //   2. 无论持久化记录是否存在，daughterStateLayer/daughterCustomEnums
    //      都会传给 PromptOrchestrator，用于渲染面具/情绪/需求/恐惧
    //      四个种类维度的专属描述文本（customEnums.description），
    //      不再使用 CharacterStateLayer 编译期枚举的中性占位值。
    // 查询失败或数据损坏（DaughterDataException）时静默跳过，不影响
    // 本轮对话——女儿人格数据的完整性由 loadCharacter() 处的校验把关，
    // 这里只是 Prompt 渲染的锦上添花，不应该因为这一步失败而中断对话。
    var daughterStateLayer: com.zaijian.zhoumuyun.data.model.DaughterStateLayer? = null
    var daughterCustomEnums: com.zaijian.zhoumuyun.data.model.DaughterCustomEnums? = null
    if (getCurrentCharacterId() >= 1000) {
        try {
            val daughterData = daughterRepo.getCharacterData(getCurrentCharacterId())
            if (daughterData != null) {
                daughterStateLayer = daughterData.stateLayer
                daughterCustomEnums = daughterData.customEnums
                if (characterState == CharacterStateLayer()) {
                    characterState = daughterData.stateLayer.toCharacterStateLayer()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("ChatViewModel", "女儿状态数据查询失败，State Layer 渲染将回退到通用描述", e)
        }
    }
    
    // ── presence fallback：缓存为空时主动计算一次，结果写入缓存供后续轮次复用 ──
    if (presenceSnap == null) {
        presenceSnap = presenceEngine.refreshPresence(getCurrentCharacterId(), characterState)
    }
    
    // ── Knowledge Layer（Phase 31）：按注入模式决定是否真正生效 ──
    val knowledgeBlock = _uiState.value.activeProjectId?.let { projectId ->
        when (_uiState.value.knowledgeInjectMode) {
            KnowledgeInjectMode.MANUAL -> {
                // 仅当本轮被显式触发时注入，触发后立即消费掉标志位
                if (_uiState.value.manualKnowledgeTriggerPending) {
                    _uiState.update { it.copy(manualKnowledgeTriggerPending = false) }
                    projectRepo.buildKnowledgeBlock(projectId)
                } else ""
            }
            KnowledgeInjectMode.AUTO -> {
                // AUTO 模式：按知识条目标题做关键词匹配，命中才注入
                val titles = projectRepo.getTopKnowledge(projectId).map { it.title }
                val hit = titles.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
                if (hit) projectRepo.buildKnowledgeBlock(projectId) else ""
            }
        }
    } ?: ""
    
    // ── workflowRecapPatch：上次后台任务结果播报 ──
    // 查 isReported=0 的已完成任务；取第一条生成简短 recap 后立即标记已读，
    // 避免同一任务结果在多条消息里重复播报。
    val recapCtx = buildRecapContext()
    val unreportedJob = recapCtx.unreportedJob
    val unreportedChainRun = recapCtx.unreportedChainRun
    val workflowRecapText = recapCtx.workflowRecapText
    val chainRecapText = recapCtx.chainRecapText
    // ── 私聊状态播报：让角色知道发生过的私聊（含完整逐字记录）──
    val privateChatRecapText = gatherPrivateChatRecap()
    
    // 所有播报都存在时用空行分隔，拼进同一个 prompt 槽位（PromptOrchestrator
    // 侧 workflowRecapPatch 参数本身就是"非空则整块 append"的简单字符串处理，
    // 不改函数签名，改动面最小）。
    val workflowRecapPatch = listOf(workflowRecapText, chainRecapText, privateChatRecapText)
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
    
    // ── 检查5b：D5 关系阶段快照（State Layer 之後注入）──────────────
    // 仅对女儿角色（characterId >= 1000）查询；普通母亲角色直接用空字符串，零开销。
    val agentRelationSnapshot = if (getCurrentCharacterId() >= 1000) {
        agentRelationEngine.buildPromptSnapshot(getCurrentCharacterId())
    } else ""
    
    // ── Task Layer（Phase 12）：工作台任务跟踪 ──────────────────────
    // 取该角色当前 RUNNING / PENDING 任务（最多 5 条），组装为 taskLayerBlock。
    // 无活跃任务时返回空字符串，buildSystemPrompt 内部跳过注入，零开销。
    val activeTasks = taskRepo.getByCharacter(getCurrentCharacterId(), limit = 5)
        .filter { it.status == "RUNNING" || it.status == "PENDING" }
    val taskLayerBlock = if (activeTasks.isEmpty()) "" else {
        val first = activeTasks.first()
        com.zaijian.zhoumuyun.data.prompt.TaskRulePromptBuilder.buildTaskLayerBlock(
            taskType      = first.title,
            currentStep   = first.description.takeIf { it.isNotBlank() },
            toolResults   = activeTasks.drop(1)
                .mapNotNull { it.resultSummary?.take(50) },
            pendingTools  = activeTasks.drop(1)
                .filter { it.toolName != null }
                .map { it.toolName!! },
            taskCompleted = false,
        )
    }
    
    // ── 角色间关系头衔系统·接入点2：假扮身份识别（方案_角色间关系头衔系统_
    // 实施方案 五节 → 六/七节清理后）── 替代原 IdentityGuard 自称异常/称呼
    // 异常判定（已删除，见 domain/IdentityGuard.kt 头部清理说明）。
    //
    // 精确匹配"我不是主人，我是XX"，XX 命中预设名单才算数，不做模糊匹配/
    // 语气推断。命中后查头衔（XX 是真实角色查 toCharacterId，否则查
    // toPresetName），生成 prompt patch 复用 interCharRelBlock 槽位注入
    // （PrivateChatEngine 已用同一槽位承载头衔文本，这里是槽位的第二个用途：
    // 私聊场景传"与私聊对象的关系"，这里传"与假扮者的关系"，两者互斥不会
    // 同时触发，复用同一个参数名不冲突）。
    //
    // 持久化：按 characterId 分片存取（ChatUiState.impersonationByCharacter），
    // 命中后持续到用户说"我是主人"才清除，不因后续几句话"表现正常"自动解除
    // （沿用原 defenseMode 的"不被中途洗白"设计）。speakerContext 现在直接由
    // 假扮识别结果推导，不再有独立的 defenseModeByCharacter 判定源——
    // 两者语义等价（"眼前不是主人" ⇔ "命中假扮识别"），合并成一份状态位，
    // 避免旧代码里两套机制各自判定、互不通气的问题。
    val impersonationCtx = resolveImpersonationContext(text, userMsgId)
    val speakerContext = impersonationCtx.speakerContext
    val interCharRelBlock = impersonationCtx.interCharRelBlock
    
    // ── P0-4 PR5：presence 层 COLD 变化检测（v10 风险点 2：presenceSnap 用 hashCode）──
    // 存在状态未变化时跳过注入（省 token），变化时注入并更新哈希。空 presence 也注入
    // （首轮无缓存时需给出状态，避免首轮缺失）。
    val presenceLayer = com.zaijian.zhoumuyun.data.prompt.PresenceStateLayer(
        activity = presenceSnap?.activity ?: "",
        focus    = presenceSnap?.goalTitle ?: "",
        mood     = presenceSnap?.mood?.name ?: "",
        energy   = presenceSnap?.energy ?: -1,
    )
    val presenceHash = presenceLayer.hashCode()
    val pCharId = getCurrentCharacterId()
    val prevPresenceHash = lastPresenceHashByCharacter[pCharId]
    val presenceChanged = presenceLayer.isEmpty || prevPresenceHash == null || prevPresenceHash != presenceHash
    if (presenceChanged) lastPresenceHashByCharacter[pCharId] = presenceHash
    val presenceActivity = if (presenceChanged) presenceLayer.activity else ""
    val presenceFocus    = if (presenceChanged) presenceLayer.focus else ""
    val presenceMood     = if (presenceChanged) presenceLayer.mood else ""
    val presenceEnergy   = if (presenceChanged) presenceLayer.energy else -1
    
    val systemPrompt = PromptOrchestrator.buildSystemPrompt(
        character             = character,
        identityEntity        = identityEntity,
        // P0-4 PR4：Identity WARM 层每 5 轮注入一次（counter % 5 == 0 时注入）。
        includeWarmIdentityBlock = ((identityWarmRoundByCharacter[getCurrentCharacterId()] ?: 0) % 5 == 0),
        coreMemories          = coreMemories,
        relevantMemories      = relevantMemories,
        presenceActivity      = presenceActivity,
        presenceFocus         = presenceFocus,
        presenceMood          = presenceMood,
        presenceEnergy        = presenceEnergy,
        relationshipSnapshot  = relationshipSnapshot,
        agentPlanBlock        = agentPlanBlock,
        ruleLayerBlock        = ruleLayerBlock,
        pregnancyState        = pregnancyState,
        characterState        = characterState,
        daughterStateLayer    = daughterStateLayer,
        daughterCustomEnums   = daughterCustomEnums,
        miscarriageAftermathPatch = miscarriageAftermathPatch,
        pregnancyTriggerPromptPatch = pregnancyTriggerPromptPatch,
        failureContextPatch   = failureContextPatch,
        routinePressurePatch  = routinePressurePatch,
        d3QuestionPatch       = d3QuestionPatch,
        toolDescriptionBlock  = toolDesc,
        chatMode              = chatMode,
        knowledgeBlock        = knowledgeBlock,
        workflowRecapPatch    = workflowRecapPatch,
        agentRelationSnapshot = agentRelationSnapshot,
        taskLayerBlock        = taskLayerBlock,
        skillCatalogBlock     = skillCatalogBlock,
        speakerContext        = speakerContext,
        interCharRelBlock     = interCharRelBlock,
    )
    
        return PromptLayers(
            systemPrompt = systemPrompt,
            speakerContext = speakerContext,
            characterState = characterState,
            pregnancyState = pregnancyState,
            d3QuestionPatch = d3QuestionPatch,
            d3PendingAsk = d3PendingAsk,
            unreportedJob = unreportedJob,
            unreportedChainRun = unreportedChainRun,
        )
    }

    /** 分段 4/5：streamAndInterceptReply 的返回值，承载流式生成的回复与工具产物。 */
    private data class ReplyResult(
        val fullReply: StringBuilder,
        val pendingExportedFiles: MutableList<String>,
        val pendingTablePayloadJson: String?,
        val replyMsgId: String,
        // P1-6 修复：流式中途抛异常（断网/超时/未预期崩溃）时置 true。
        // fullReply 此时只是"抛异常那一刻已经攒到的半截文本"，不代表模型
        // 完整表达的意思——不能当正常回复落库，也不能喂进下一轮 LLM 上下文，
        // 否则残缺语义会被当作角色说过的话持续污染后续对话。
        // sendMessage 侧看到 failed=true 时只保留已经置位的 _uiState.error
        // 提示，跳过 persistAssistantMessage 落库与 finalizeRound 后置分析。
        val failed: Boolean = false,
        // 工具结果跨消息保留：本轮全部 ToolDone 事件的紧凑摘要（每行一次调用），
        // 为空字符串表示本轮没有工具调用。是否落库由 persistAssistantMessage
        // 按 chatMode 决定（仅 WORK 模式落库），此处只负责收集，不关心用途。
        val toolTrace: String = "",
    )

    /**
     * 分段 4：流式生成（withSpeakerContext + withVaultContext + streamWithTools collect），
     * 收集 TextDelta 累积 fullReply，处理 ToolStarted/ToolDone（心迹事件 + 文件/表格产物识别）
     * 与 FileReadConfirmed（文件已读标记落库），随后调用 runReplyGuard 做越界检测。
     * 异常处理：CancellationException rethrow，其余 Throwable 记录日志并置 error（不中断）。
     * 返回 ReplyResult（fullReply + 本轮工具产物 + replyMsgId），供 sendMessage 后续落库/关系值使用。
     */
    private suspend fun streamAndInterceptReply(
        provider: LLMProvider,
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
        speakerContext: SpeakerContext,
        character: CharacterConfig,
    ): ReplyResult {
    // P2-21 修复：删除此处的重复 isTyping=true 赋值。
    // 推测是早期 P1-3 修复（streamingContent 不再双写 _uiState）时的
    // P3-18 修复：统一 _streamingContent 重置值为 null
    _streamingContent.value = null
    _streamingPsych.value = null
    _streamingThinking.value = null
    // Task-2：设置通用加载提示，覆盖"AI 正在准备/生成"的整个等待期。
    // ToolStarted 会覆盖为工具特定提示（如"正在生成PDF…"），
    // ToolDone 恢复为此通用提示，finally 块统一清空。
    _uiState.update { it.copy(streamingHint = "正在生成回复…") }
    val fullReply = StringBuilder()
    // P0-1（Agent附件下发方案 v2.0）：暂存本轮工具产出的文件元数据 JSON。
    // v66（1.7 P3）：改用 list 收集本轮全部文件，不再是"后一次覆盖前一次"——
    // 落库时 exportedFileJson（旧，单文件）取 lastOrNull，
    // exportedFilesJson（新，数组）取全部，两个字段都写。
    val pendingExportedFiles = mutableListOf<String>()
    // v67（表格直传 W4）：table_export 产出的 payload（单值，一条消息一个表格，
    // 与 exportedFileJson 单值语义同款）。后调用的覆盖先调用的——
    // 一轮回复里多次 table_export 时，以最后一个为准（与 pendingExportedFiles
    // 的"全部收集"不同，因为 tableDataJson 是单值字段不是数组）。
    var pendingTablePayloadJson: String? = null

    // 工具结果跨消息保留：累积本轮每次 ToolDone 的紧凑摘要，一行一次调用。
    // 只在 ToolDone（有终态）时追加，ToolStarted 不记录——避免未完成/被取消的
    // 调用留下无终态的孤行。是否落库、落给谁看，交给调用方（persistAssistantMessage）
    // 按 chatMode 决定，这里只负责如实收集。
    val pendingToolTrace = StringBuilder()

    // 心迹（Window B 2.2.3）：提前生成本轮助手消息 id，用作「心迹」事件
    // 的 sessionRef（私聊= messageId，方案 2.2.2），让"过程痕迹"能关联回
    // 具体一条回复。与圆桌两条路径（msgId 在流式前预生成）对齐。原先此处
    // 在流式结束后才 UUID.randomUUID() 生成 assistantMsg.id，现在提前到流式
    // 前、流式结束落库时复用同一个 id——行为等价（仍是随机 UUID），仅生成
    // 时机前移，不改变消息内容/落库语义。
    val replyMsgId = UUID.randomUUID().toString()

    // P1-6 修复：流式期间任何时刻抛异常都会被下方 catch(Throwable) 捕获，
    // 需要一个在 try 块外可见的标志位记录"本轮是否失败"，供最终 return 使用。
    var streamFailed = false

    try {
        // v147 验收返工：身份绑定到协程（VaultCallContextElement），
        // 避免进程级 AtomicReference 被并发的 streamWithTools 覆盖。
        //
        // 场景一记忆隔离修复：同一作用域内再包一层 withSpeakerContext，
        // 把 446 行已算出的 speakerContext 也绑到协程上，让
        // MemoryWriteTool/SoulUpdateTool/NarrativeMemoryUpdateTool/
        // UserImpressionUpdateTool 的 execute() 内能通过
        // currentSpeakerContext() 读到"owner 本人 vs owner 正在
        // 冒充第三方"，避免冒充产生的记忆被当成正常互动写入/覆盖。
        // 与 withVaultContext 是两个独立的 CoroutineContext.Element，
        // 嵌套顺序不影响各自读取。
        withSpeakerContext(speakerContext) {
        withVaultContext(VaultCallContext(getCurrentCharacterId(), VaultScope.PERSONAL)) {
        ToolCallInterceptor.streamWithTools(
            provider        = provider,
            messages        = messages,
            systemPrompt    = systemPrompt,
            config          = config,
            activityContext = ToolCallInterceptor.ActivityContext(
                characterId = getCurrentCharacterId(),
                sessionRef  = replyMsgId,
                sceneType   = AgentActivityRepository.SceneType.CHAT,
            ),
        ).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> {
                    // Task-2（一次性合并输出）：不再逐 delta 更新 _streamingContent /
                    // _streamingPsych。改为内部累积 fullReply，等整轮生成（文字 +
                    // 工具调用）全部完成后，在循环结束处一次性组装最终消息落库渲染。
                    //
                    // 原实现每个 token 都调 stripTagsForDisplayWithPsych 并更新两个
                    // StateFlow → StreamingMessageItem 重组 → 打字机效果。这导致
                    // "文字先流式出现 → 工具执行完成话术 → 文件卡片最后跳出来"
                    // 的三段式闪烁（用户描述为"一闪一闪的""跟闪屏一样"）。
                    //
                    // tag 剥离逻辑仍在循环结束后的 cleanReply 流程中执行（见下方
                    // stripThinkingTag / stripPsychText / stripMoodTag），不依赖此处
                    // 的流式剥离结果，删除此处不影响最终消息内容。
                    //
                    // 期间用 streamingHint = "正在生成回复…" 提示用户（在 collect
                    // 开始前设置，见下方），StreamingMessageItem 显示 "…" 占位符
                    // + ToolHintRow 提示行，消除空窗感。
                    //
                    // P2-5-1 说明：正文 _streamingContent 在流式期间保持 null（有意不赋
                    // 值），StreamingMessageItem 的 hasContent/currentStreaming 正文分支
                    // 因此是"设计上未启用"而非失修的 bug——若逐 token 更新正文会重新引入
                    // Task-2 已修复的"正文→工具话术→文件凸现"三段式闪烁。正文在收尾
                    // 一次性合并落库；此处只实时流 _streamingThinking 与 _streamingPsych
                    // （两个独立折叠/过程卡，增量更新不触发正文三段式重组）。
                    fullReply.append(event.text)
                    // Fix-StreamThinking（输出节奏：思考先出，正文+文件收尾一起发）：
                    // 思考内容不在"一次性合并"范围内——增量解析 [thinking:...]
                    // （含正在输出、尚未闭合的半截），实时推给流式气泡的
                    // ThoughtCard 展示；正文与文件卡片仍等收尾一次性提交。
                    _streamingThinking.value = extractStreamingThinking(fullReply)
                    // P2-5-2 修复：心理描写（…）同样做增量提取（含未闭合的半截），
                    // 让 PsychCard 在打字过程中就显示——此前 _streamingPsych 从不更新、
                    // 流式阶段恒为 null，与 ChatMessageBubble.kt 注释声称的"打字中即显示"
                    // 不符。注意正文 _streamingContent 仍保持一次性合并（见上注释），
                    // 不在此处更新，psych 是独立折叠卡片，增量更新不会引发 Task-2 要
                    // 规避的"正文→工具话术→文件凸现"三段式闪烁。
                    _streamingPsych.value = extractStreamingPsych(fullReply)
                }
                is StreamEvent.ToolStarted -> {
                    // 心迹（Window B 2.2.3）：记录工具调用"已发起"事件，sceneType=chat。
                    // outcome=null 表示尚无终态（与下方 ToolDone 的终态行配对呈现"开始→完成"）。
                    // attemptIndex=0：正常单次调用；降级链路的多次尝试由 ToolCallInterceptor
                    // 状态机（2.1，模块④）另行写 DEGRADE_* 事件，不在此处累加。
                    try {
                        AppContainer.instance.agentActivityRepo.recordEvent(
                            characterId    = getCurrentCharacterId(),
                            sessionRef     = replyMsgId,
                            sceneType      = AgentActivityRepository.SceneType.CHAT,
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
                        ZLog.w("ChatViewModel", "心迹事件落库失败（不影响主流程）", e)
                    }
                    if (event.hint != null) {
                        _uiState.update { it.copy(streamingHint = event.hint) }
                    }
                }
                is StreamEvent.ToolDone -> {
                    // Task-2：工具完成后恢复通用提示而非清空——避免 ToolDone
                    // 到流式结束之间的空窗期用户看到无提示的 "…" 以为卡住了
                    _uiState.update { it.copy(streamingHint = "正在生成回复…") }
                    // 心迹（Window B 2.2.3）：记录工具调用终态事件，sceneType=chat。
                    // outcome 取 success/fail；outputRaw 落 content 摘要（Repository 内截断≤300字）。
                    try {
                        AppContainer.instance.agentActivityRepo.recordEvent(
                            characterId  = getCurrentCharacterId(),
                            sessionRef   = replyMsgId,
                            sceneType    = AgentActivityRepository.SceneType.CHAT,
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
                        ZLog.w("ChatViewModel", "心迹事件落库失败（不影响主流程）", e)
                    }
                    // P0-1（Agent附件下发方案 v2.0）：识别文件类工具产物。
                    // 不按 toolName 逐个分支判断——通用识别 content 里是否带
                    // fileName+absolutePath 的 JSON，未来新增导出工具（如 zip_export）
                    // 不需要再回来改这里。
                    // v66（1.7 P3）：add 而不是覆盖赋值，本轮连续多个文件类工具
                    // 调用不再互相顶替。
                    extractExportedFileJson(event.result)?.let { pendingExportedFiles.add(it) }
                    // v67（表格直传 W4）：table_export 产出的 payload 走
                    // ToolResult.tablePayloadJson 返回值（W2 验收修复：不存工具
                    // 实例字段，避免并发越权——见 ToolResult.tablePayloadJson KDoc）。
                    // 单值覆盖：一轮多次 table_export 以最后一个为准。
                    event.result.tablePayloadJson?.let { pendingTablePayloadJson = it }
                    // 工具结果跨消息保留：记录这次调用的紧凑摘要（工具名 + 成败 + 内容/错误
                    // 摘要）。content 可能是几千字的文件读取原文，这里截断防止一条标记消息
                    // 把整轮上下文预算吃掉——完整摘要本就够回答"第二行写了什么"这类追问，
                    // 不需要逐字保留。
                    if (pendingToolTrace.isNotEmpty()) pendingToolTrace.append('\n')
                    val traceBody = if (event.result.success) {
                        event.result.content
                    } else {
                        "错误：${event.result.error.orEmpty()}"
                    }.take(500)
                    pendingToolTrace.append(
                        "· ${event.result.toolName}(${if (event.result.success) "成功" else "失败"})：$traceBody"
                    )
                }
                is StreamEvent.RoundDone -> Unit
                is StreamEvent.FileReadConfirmed -> {
                    // v1.49 修复：见 FILE_READ_MARK_PREFIX 处的详细说明——
                    // 这里落库一条标记消息，让下一条新消息组装 LLM 上下文时，
                    // ToolCallInterceptor 的 alreadyRead 检测能查到"已读过"的
                    // 证据，不再无限期反复触发强制读取流程。
                    try {
                        messageRepo.insert(
                            MessageEntity(
                                id = UUID.randomUUID().toString(),
                                characterId = getCurrentCharacterId(),
                                role = "system",
                                content = "$FILE_READ_MARK_PREFIX[工具执行结果] 文件已读取：${event.fileName}",
                                createdAt = System.currentTimeMillis(),
                            )
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ZLog.w("ChatViewModel", "文件已读标记落库失败（不影响主流程，但下条消息可能重新触发强制读取）", e)
                    }
                }
            }
        }
        } // withVaultContext
        } // withSpeakerContext

        runReplyGuard(fullReply, speakerContext, character, provider)
    } catch (e: CancellationException) {
        // B-1 修复：CancellationException 必须 rethrow，保证结构化并发正确传播。
        // replyJob?.cancel() 触发取消时协程库通过此异常信号通知协程停止，
        // 若被吞掉协程会误认为正常结束，viewModelScope 的取消机制失效。
        throw e
    } catch (e: Throwable) {
        // 修复（多工具并发时静默卡死、气泡消失无提示）：原先是
        // catch (e: Exception)，抓不住 Error 子类。单个工具内部
        // （典型如 excel_gen 底层 Apache POI 在 Android 上触发的
        // NoClassDefFoundError）一旦抛出 Error，即使
        // ExcelGenTool.execute() 和 ToolCallInterceptor.executeWithTimeout()
        // 各自也做了兜底，只要链路上任何一环还留有旧的
        // catch (e: Exception)，就会被击穿——这里是整条回复生成流程
        // 的最后一道防线。改为 catch Throwable 后，任何未预料到的
        // 崩溃都会落到下面的 error 提示分支，而不是让 finally 静默
        // 清空 streamingContent、用户只看到"…"消失、什么都没发生。
        ZLog.e("ChatViewModel", "回复生成失败", e)
        _uiState.update { it.copy(error = "回复时遇到问题，请稍后重试。") }
        // P1-6 修复：标记本轮失败，让调用方（sendMessage）能区分"整轮成功"
        // 与"异常中断"，从而跳过把这段残缺 fullReply 当正常回复落库。
        streamFailed = true
    }
        return ReplyResult(
            fullReply = fullReply,
            pendingExportedFiles = pendingExportedFiles,
            pendingTablePayloadJson = pendingTablePayloadJson,
            replyMsgId = replyMsgId,
            failed = streamFailed,
            toolTrace = pendingToolTrace.toString(),
        )
    }

    /**
     * 分段 5：ReplyGuard 越界检测（主聊天路径）。owner 冒充第三方（speakerContext.isNonOwner）
     * 时，对本轮候选回复做生成后兜底检测：命中越界 → 用固定兜底模板替换 fullReply 全部内容。
     * fail-closed：LLM 分类调用失败（null）视同越界，用兜底模板替换。
     */
    private suspend fun runReplyGuard(
        fullReply: StringBuilder,
        speakerContext: SpeakerContext,
        character: CharacterConfig,
        provider: LLMProvider,
    ) {
    // ── Window0 仲裁 #3：ReplyGuard 越界检测扩展到主聊天路径 ──
    // 复用 PrivateChatEngine 同款判定标准（"角色与 NON_OWNER 对象
    // 发生越界"），语义对应主聊天场景：owner 冒充第三方时
    // （speakerContext.isNonOwner），对本轮候选回复做生成后兜底检测。
    // 与私聊不同：这里是流式接口，只能在 collect 结束、fullReply
    // 已完整、落库/清洗之前做一次性检测，不支持流式中途中断重生成。
    // 命中越界 → 用固定兜底模板替换 fullReply 全部内容，不重新调用
    // provider（重新走一次流式生成成本过高，且候选文本已经"说出口"，
    // 主聊天路径选择直接替换而非私聊那种"重生成一次再兜底"两级策略）。
    if (speakerContext.isNonOwner && fullReply.isNotBlank()) {
        val candidateReply = fullReply.toString()
        // C10#52 修复：判定 prompt 改为引用 ReplyGuard.BOUNDARY_BREACH_CLASSIFIER_PROMPT，
        // 不再本地硬编码——与 PrivateChatEngine 私聊路径共享同一份判定标准。
        // A10-2/A11-8 修复：checkBoundaryBreach 返回 Boolean?，null 表示 LLM 调用失败。
        // fail-closed：null 视同越界，用兜底模板替换，因为边界检测的 false negative
        // 会导致不当内容直接展示给用户，不可逆。
        val breach = ReplyGuard.checkBoundaryBreach(candidateReply) { reply ->
            runCatching {
                val resp = provider.chatSyncWithRetry(
                    listOf(LLMMessage("user", reply)), ReplyGuard.BOUNDARY_BREACH_CLASSIFIER_PROMPT,
                    LLMConfig(model = "", maxTokens = 10, temperature = 0.0f, stream = false),
                )
                resp.trim().startsWith("true", ignoreCase = true)
            }.getOrElse { e ->
                // A10-2/A11-8 修复：失败时不再静默返回 false（fail-open），改为 log 告警 + 返回 null。
                // 调用方将 null 视同越界（fail-closed），确保无法判断时不放过潜在越界内容。
                ZLog.w("ChatMessageOrchestrator", "checkBoundaryBreach: LLM 分类调用失败，返回 null（调用方将按 fail-closed 处理）", e)
                null
            }
        }
        if (breach != false) {
            fullReply.clear()
            fullReply.append(ReplyGuard.fallbackTemplate(character.name))
        }
    }
    }

    /**
     * 分段 6：关系数值更新。AI 回复落库后，用 HeuristicRelTracker 语义 delta +
     * 基础 delta 合并后一次性 applyDelta（避免多条 RELATIONSHIP_CHANGED 事件），
     * 并写入 MESSAGE 事件（Timeline 等消费方使用）。
     * NON_OWNER（owner 冒充第三方）时整段跳过关系值计算，仅记日志。
     * applyDelta 失败仅记日志，不阻断已完成的落库与 UI 展示。
     */
    /**
     * 分段 7：剥离 Structured 标签（thinking/psych/mood）得到 cleanReply，
     * 并按 mood 回写情绪（C4#13 方案B：EmotionType 直接落 CharacterStateRepository）。
     * 返回 (cleanReply, parsedThinking, parsedPsych)。
     */
    private suspend fun cleanAndExtractMood(
        fullReply: StringBuilder,
        characterState: CharacterStateLayer,
    ): Triple<String, String?, String?> {
val (afterThinking, parsedThinking) = ChatTagParser.stripThinkingTag(fullReply.toString().trimEnd())
val (afterPsych, parsedPsych) = ChatTagParser.stripPsychText(afterThinking)
val (cleanReply, parsedMood) = ChatTagParser.stripMoodTag(afterPsych)
if (parsedMood != null) {
    // C4#13 落地（方案B）：聊天驱动情绪值回写，让"因"（LLM 这轮真实表现出的
    // EmotionType+强度）直接落 CharacterStateRepository，MoodType 只是
    // toMoodType() 换算出的"果"——不再是方案A那种反过来拿 MoodType 粗猜
    // EmotionType 的有损映射，符合 CharacterStateRepository.updateState()
    // 和 CharacterStateLayer.kt 顶部两处架构注释原本设想的方向。
    //
    // 限定 characterId<1000（普通角色）：女儿角色（ID>=1000）的
    // CharacterStateLayer 走的是上方 #7/#13/#20 修复的特殊路径——
    // characterState 只在"尚无持久化记录"（== 空默认值）时才会被
    // DaughterStateLayer 派生数据整体覆盖。如果这里无差别对所有角色调用
    // updateState() 落库，女儿角色第一次聊天后就会产生一条持久化记录，
    // 导致"空默认值"判定此后永远不成立，DaughterStateLayer 派生的真实
    // 数值反而被这里聊天推导出的粗粒度状态顶替——这是本次改动会新引入的
    // 冲突面，不属于 C4#13 原本要修的范围，女儿角色的情绪回写留给未来
    // 单独一个批次评估怎么和 DaughterStateLayer 路径协调，这里先不碰。
    val moodType = if (getCurrentCharacterId() < 1000) {
        val updatedState = characterState.copy(
            emotionalState = characterState.emotionalState.copy(
                primaryEmotion = parsedMood.emotionType,
                intensity      = parsedMood.intensity,
            )
        )
        characterStateRepo.updateState(getCurrentCharacterId(), updatedState)
        parsedMood.emotionType.toMoodType(
            intensity        = parsedMood.intensity,
            emotionalFatigue = updatedState.emotionalState.emotionalFatigue,
        )
    } else {
        parsedMood.moodType
    }
    presenceEngine.updateMoodFromReply(getCurrentCharacterId(), moodType)
    _uiState.update { it.copy(currentMood = moodType) }
}
        return Triple(cleanReply, parsedThinking, parsedPsych)
    }

    /**
     * 分段 8：落库 + 乐观更新。有正文/文件/表格任一即落库 assistant 消息，
     * 同步追加到 _uiState.messages，并原子清空 isTyping/streaming 占位气泡。
     * 落库后调用 updateRelationshipMetrics 更新关系值。返回最新消息列表。
     */
    private suspend fun persistAssistantMessage(
        replyMsgId: String,
        cleanReply: String,
        parsedThinking: String?,
        parsedPsych: String?,
        speakerContext: SpeakerContext,
        text: String,
        pendingExportedFiles: MutableList<String>,
        pendingTablePayloadJson: String?,
        toolTrace: String = "",
        chatMode: ChatMode = ChatMode.WORK,
    ): List<*> {
    var pendingTablePayloadJsonLocal = pendingTablePayloadJson
// Fix-BlankReplyFilesLost（文件生成成功但文件卡片丢失 根因修复）：
// 原条件只认 cleanReply.isNotBlank()——模型有时整轮只调工具、不写一字正文
// （或被标签剥离后恰好为空），此时生成的文件随整条消息被丢弃，
// 用户看到"文件已生成"的日志但对话里没有任何文件卡片。
// 放宽为"有正文 或 有文件 或 有表格"任一即落库；content 为空串时
// UI 只渲染文件/表格卡片，不画文字气泡（MessageBubble 的 showBubble 判断
// 本身就这么处理，无渲染风险）。
if (cleanReply.isNotBlank() || pendingExportedFiles.isNotEmpty() || pendingTablePayloadJsonLocal != null) {
    val assistantMsg = MessageEntity(
        id = replyMsgId,
        characterId = getCurrentCharacterId(),
        role = "assistant",
        content = cleanReply,
        createdAt = System.currentTimeMillis(),
        thinkingText = parsedThinking,
        psychText = parsedPsych,
        // C8 #43 写入侧收尾：本轮已算出的 speakerContext，构造时直接传入，
        // 不像用户消息那样需要事后回写（这条消息在判定结果算出之后才落库）。
        speakerContext = speakerContext.name,
        // P0-1（Agent附件下发方案 v2.0）：把本轮工具产出的文件元数据接回消息实体，
        // FileExportCard 依赖 ChatMessage.exportedFiles（由
        // exportedFilesJson/exportedFileJson 解析而来，v66 起支持多文件）
        // 才能在气泡下方渲染下载卡片，此前该字段从未被赋值，卡片链路始终未接通。
        // v66（1.7 P3）：exportedFileJson 保留写最后一个文件（兼容旧读取路径），
        // exportedFilesJson 新增写全部文件——UI 应优先读后者。
        exportedFileJson = pendingExportedFiles.lastOrNull(),
        exportedFilesJson = packExportedFilesJson(pendingExportedFiles),
        // v67（表格直传 W4）：table_export 产出的 payload（单值，null=无表格）。
        // ≤50 行 Markdown 路径 tool 不填 tablePayloadJson，这里就是 null。
        tableDataJson = pendingTablePayloadJsonLocal,
    )
    messageRepo.insert(assistantMsg)
    // 工具结果跨消息保留：仅工作模式（WORK）落库这条标记消息——陪伴模式
    // （COMPANION）维持现状，不记录工具原始产出细节，只在 assistant 转述里
    // 留痕（见 TOOL_TRACE_MARK_PREFIX 处说明）。role="system" + 前缀标记，
    // 复用 FILE_READ_MARK_PREFIX 同款"下一轮上下文可读、UI 不可见"模式。
    if (chatMode == ChatMode.WORK && toolTrace.isNotBlank()) {
        try {
            messageRepo.insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    characterId = getCurrentCharacterId(),
                    role = "system",
                    content = "$TOOL_TRACE_MARK_PREFIX[工具执行结果]\n$toolTrace",
                    createdAt = System.currentTimeMillis(),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("ChatViewModel", "工具轮次记录落库失败（不影响主流程，仅下轮追问工具细节时答不上来）", e)
        }
    }
    // 用完立即清空，避免串到下一轮回复（下一轮没有新的文件类工具调用时，
    // 若不清空会把这一轮的文件卡片错误地挂到下一条不相关的消息上）。
    pendingExportedFiles.clear()
    // v67（表格直传 W4）：同上，清空避免串消息。
    pendingTablePayloadJsonLocal = null
    // H2 修复（race消除）：insert是挂起函数，到这里落库已完成。
    // 做乐观更新——把刚落库的消息同步追加到内存list，
    // 后续读 _uiState.value.messages 保证能看到这条新消息。
    //
    // 第7窗口问题3修复：删除此处原有的 loadMessages(currentCharacterId)
    // 兜底调用（全量重查数据库，属冗余操作）。核实依据：
    // 1) observeJobs 中没有任何消息表（messageDao/MessageEntity）相关的
    //    Flow 订阅，此处不存在"响应式更新会遗漏"的问题；
    // 2) 写消息表的 role="system" 控制信号本就不进入 ChatUiState.messages
    //    展示列表，无需同步；
    // 3) ProactiveMessageNotifier 的主动消息写入已有独立、已存在的
    //    刷新路径——clearProactiveMessage() 内的 loadMessages 覆盖此场景，
    //    不依赖此处兜底。
    // 上述三点排除了所有"其他路径写库后内存未同步"的实际场景，
    // 乐观更新已足够，删除此处全量重查是安全的。
    //
    // P1-7 修复：上面这段注释的"observeJobs 中没有任何消息表 Flow 订阅"前提不成立——
    // ChatSessionDelegate.init 的 observeJobs 确实订阅了 messageRepo.observeByCharacter
    // （见 ChatSessionDelegate，line 268）。insert 触发 Room 失效后，该流会全量重发
    // 已含本条新消息的列表。若流在乐观更新读取 _uiState.value.messages 之前先 emit，
    // 乐观更新会基于已含该消息的列表再 append 一次，产生重复气泡。把乐观更新改为
    // 幂等（按 id 去重）：流已带上该消息则不再追加，避免重复。
    val optimisticMsg = ChatTagParser.toChatMessage(assistantMsg)
    val currentMessages = _uiState.value.messages
    val latestMessages = if (currentMessages.any { it.id == optimisticMsg.id }) {
        currentMessages
    } else {
        (currentMessages + optimisticMsg).toImmutableList()
    }
    // Fix-闪烁：messages 写入真实消息（含 exportedFiles/tablePayload）与
    // isTyping 置 false + streamingContent 清空必须在同一批状态更新里原子完成。
    // 此前 isTyping=false 挪到本函数最末尾的 finally 块，中间夹着
    // relationshipEngine.applyDelta / eventRepo 写入等同步 IO 操作
    // （耗时随设备 I/O 状况波动，不是恒定 0ms）——这段时间窗口内
    // messages 已经含有落库后的正式气泡（文字+文件卡），但 isTyping
    // 仍是 true，ChatScreen 的 "streaming" 占位气泡（冻结在最后一次
    // 收到的 streamingContent 内容）还挂在列表末尾没被摘掉，等价于同一条
    // 回复被渲染了两次；一旦 finally 里 isTyping 才翻 false，占位气泡消失，
    // 视觉上就是"文字先出、文件卡再补上时闪一下"。改成落库消息和
    // 打字机占位气泡在同一次 _uiState.update 里"一步到位"地互相替换，
    // 不再有两者同时可见的中间态。finally 块保留 isTyping=false 兜底
    // （cleanReply 为空等未进入本分支的路径仍需它收尾，重复赋值是幂等的）。
    _uiState.update { it.copy(messages = latestMessages, isTyping = false, streamingHint = null) }
    _streamingContent.value = null
    _streamingPsych.value = null
    _streamingThinking.value = null
    // P1-10-3 修复：原先两次 applyDelta（onConversationEnd 基础 delta +
    // HeuristicRelTracker 语义 delta）会产生两条 RELATIONSHIP_CHANGED 事件，
    // 导致同一轮对话的摩擦系数被重复写入。改为将两组 delta 合并后一次性提交。
    // ── A-7：单聊场景关系数值随对话积累增长（原 onConversationEnd 逻辑内联）──
    //
    // 场景一记忆隔离修复·关系值层补漏：446 行已判定的 speakerContext
    // 此前只用于记忆写入侧（机制一~四），关系值这条独立链路
    // （HeuristicRelTracker.infer 纯文本分析，不读 speakerContext）
    // 完全没被覆盖——owner 冒充角色B跟角色A暧昧对话时，"user"对该角色的
    // 关系值仍会正常涨跌，是与记忆污染同一根因、但发生在不同层的漏洞。
    // NON_OWNER 时整段跳过（不计算 delta、不调用 applyDelta），
    // 与 memory_write 的"写入但打标记"不同——这里没有等价的"打标记
    // 但不参与数值"中间态，关系值只有"变"与"不变"两种状态，只能跳过。
updateRelationshipMetrics(latestMessages, speakerContext, text, cleanReply)
        return latestMessages
    }
    // P0-2 修复：本轮模型输出被 cleanAndExtractMood 剥离标签/心理描写后，
    // 正文、导出文件、表格三者皆为空——不落库固然正确（避免空气泡），
    // 但此前直接 return，_uiState.error 从未被置位，UI 侧 TypingDots 消失后
    // 用户看到的是"发了消息、什么反应都没有"，无法区分"网络正常但模型空转"
    // 与"App 卡死"。这里补一条可见的错误提示，并主动清理打字机相关状态——
    // 不依赖 sendMessage 外层 finally 的兜底清理，让这条路径自身完整闭环。
    _uiState.update {
        it.copy(
            isTyping = false,
            streamingHint = null,
            error = "模型没有返回有效回复，请重试",
        )
    }
    _streamingContent.value = null
    _streamingPsych.value = null
    _streamingThinking.value = null
    return emptyList<Any>()
    }

    private suspend fun updateRelationshipMetrics(
        latestMessages: List<*>,
        speakerContext: SpeakerContext,
        text: String,
        cleanReply: String,
    ) {
    if (speakerContext.isNonOwner) {
        ZLog.w("ChatViewModel", "疑似非 owner 本人对话，本轮跳过关系值计算（不影响记忆/消息落库）")
    } else {
    val msgCountForRelEngine = latestMessages.size
    val baseDelta = com.zaijian.zhoumuyun.domain.RelationshipDelta(
        affection = if (msgCountForRelEngine >= 4) 1 else 0,
        curiosity = 1,
    )
    // ── B-5：HeuristicRelTracker 语义 delta ──
    val heuristicDelta = com.zaijian.zhoumuyun.domain.HeuristicRelTracker.infer(text, cleanReply)
    val mergedDelta = com.zaijian.zhoumuyun.domain.RelationshipDelta(
        affection        = baseDelta.affection + heuristicDelta.affectionDelta,
        trust            = heuristicDelta.trustDelta,
        conflict         = heuristicDelta.conflictDelta,
        curiosity        = baseDelta.curiosity,
        suppressionDelta = heuristicDelta.suppressionDelta,
    )
    // L-P0-3 修复：applyDelta 被外层 try（只有 finally 无 catch）覆盖，
    // 若内部 Room 写入抛 RuntimeException 会直接崩溃在用户已看到 AI 回复之后。
    // 包裹 try-catch，失败仅记日志，不阻断已完成的落库和 UI 展示。
    try {
        relationshipEngine.applyDelta(
            fromId        = "user",
            toId          = getCurrentCharacterId().toString(),
            delta         = mergedDelta,
            sourceEventId = java.util.UUID.randomUUID().toString(),
        )
    } catch (e: CancellationException) {
        throw e  // L-P0-3 修复：CancellationException 必须 rethrow
    } catch (e: Throwable) {
        // C7#19 修复：applyDelta 失败意味着这一轮关系值增量被永久丢弃
        // （用户已看到 AI 回复，关系数值却原地不动），不是无关紧要的边缘
        // 噪音，改用 ZLog.e（明确 error 级别，agent_log.txt 保留诊断记录），
        // 文案说清楚"本轮关系值已丢失"，方便事后排查。
        ZLog.e("ChatViewModel", "applyDelta 失败，本轮关系值增量已丢失（消息已正常展示）", e)
    }
    } // speakerContext.isNonOwner 跳过分支

    // 记忆写入收窄为 Agent 主动工具调用（memory_write /
    // narrative_memory_update 等），不再由此自动提取候选。
    // 这里只保留 MESSAGE 事件写入：Timeline 等事件流消费方仍需要
    // 这条事件（RelationshipEngine 用独立 sourceEventId，不依赖它）。
    // 失败时仅记录日志，不打断已完成的落库和 UI 展示。
    viewModelScope.launch(Dispatchers.IO) {
        try {
            eventRepo.appendMessageEvent(
                actorId     = "user",
                targetId    = getCurrentCharacterId().toString(),
                payloadJson = """{"preview":"${text.take(50)}"}""",
            )
        } catch (e: CancellationException) {
            throw e  // 协程取消必须重新抛出，不能当成业务失败吞掉
        } catch (e: Throwable) {
            // 与主回复流程同批修复：catch Throwable 而非 Exception。
            ZLog.e("ChatViewModel", "私聊 MESSAGE 事件写入失败 characterId=${getCurrentCharacterId()}", e)
        }
    }
    }

    /** 构建流式 LLMConfig（5 万字中文封顶：maxTokens 提至 100000）。 */
    private fun buildStreamConfig(): LLMConfig {
val config = LLMConfig(
    model = "",
    // 提升至 100000：中文字符约 1.5-2 token/字，50000 token 只能产出约
    // 25000-33000 字，无法满足用户"5万字中文封顶"的需求。
    // 100000 token 可产出约 50000-66000 字，覆盖需求上限。
    // 实际输出仍受 LLM 提供商的 max output tokens 限制，provider 不支持
    // 这么大时会自行截断（finish_reason=length），不会报错。
    maxTokens = 100000,
    temperature = 0.8f,
    stream = true,
)
    return config
    }

    /**
     * 分段 8b：AI 回复写库完成后，把已播报的 workflow/chain 任务标记为已读（markReported）。
     * 延迟到回复落库后执行，确保即使回复中途异常也不会丢失本次 recap 机会。
     */
    private suspend fun markRecapReported(
        unreportedJob: WorkflowJobEntity?,
        unreportedChainRun: ChainRunEntity?,
    ) {
// workflowRecapPatch 已在 buildSystemPrompt 前计算；
// 此处在 AI 回复写库完成后，才把任务标记为已播报，
// 确保即使回复中途异常也不会丢失本次 recap 机会。
if (unreportedJob != null) {
    workflowRepo.markReported(unreportedJob.id)
}
// 灵活自动化编排（验收缺口修复，§11.10）：链条播报同款延迟标记已读，
// 与上方 workflowRecapPatch 同一时机、同一理由。
if (unreportedChainRun != null) {
    chainRunRepository.markReported(unreportedChainRun.id)
}
    }

    private fun finalizeRound(
        cleanReply: String,
        text: String,
        d3PendingAsk: Pair<PregnancyQuestionType, Int>?,
        d3QuestionPatch: String,
        unreportedJob: WorkflowJobEntity?,
        pregnancyState: PregnancyState,
    ) {
        val capturedCharId   = getCurrentCharacterId()
        val capturedD3Pending = d3PendingAsk
        val capturedD3Patch   = d3QuestionPatch
        val capturedUnreported = unreportedJob
        val capturedReply    = cleanReply
        val capturedText     = text
        // 问题1修复：checkTrigger() 门控用——本轮（含本轮 evaluateConsent()
        // 可能引起的刷新）结束时的怀孕状态快照，而非函数顶部读取的旧值。
        val capturedPregnancyState = pregnancyState
        if (capturedReply.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                // ── Phase 24/26 修复：评分卡触发链路（之前 pendingEvaluationSessionId 从未被赋值）──
                // W13 问题1修复：evaluationEngine / distillationEngine 现由
                // rebuildEvaluationAndDistillationEngines() 在 init 及 Provider
                // 配置变更时维护，provider 未配置时为 null，下方安全跳过。
                runCatching {
                    val engine = getEvaluationEngine() ?: return@runCatching  // provider 未配置，跳过
                    // 附带修复（"有仓库不用"）：本文件199行已有 messageDao 字段
                    // （MessageRepository 包装），此处不再裸取 db.messageDao()。
                    val assistantMsgId = messageRepo
                        .getByCharacter(capturedCharId)
                        .lastOrNull { it.role == "assistant" }
                        ?.id ?: return@runCatching
                    // D-2 修复：maybeCreateSessions 为每个激活目标创建 Session，
                    // 返回所有 Session ID 列表。需遍历所有 Session 逐一评审。
                    val sessionIds = engine.maybeCreateSessions(
                        characterId  = capturedCharId,
                        replyContent = capturedReply,
                        messageId    = assistantMsgId,
                    )
                    if (sessionIds.isEmpty()) return@runCatching  // 门控未命中
                    // Agent B 评审（同一协程串行，内部已有 withContext(IO)）
                    val activeGoals = learningGoalRepo.getActive(capturedCharId)
                    if (activeGoals.isEmpty()) return@runCatching
                    val goalMap = activeGoals.associateBy { it.id }
                    // 遍历所有 Session，每个目标都评审
                    var firstReportSessionId: String? = null
                    for (sid in sessionIds) {
                        val session = db.evaluationSessionDao().getById(sid)
                            ?: continue
                        val goal = goalMap[session.goalId] ?: continue
                        engine.runAgentReview(
                            sessionId    = sid,
                            goalTitle    = goal.title,
                            replyContent = capturedReply,
                            userMessage  = capturedText,
                        )
                        // 评审完成后重新读取，推送到 UI（仅首个有报告的结果）
                        if (firstReportSessionId == null) {
                            val reviewed = db.evaluationSessionDao().getById(sid)
                            if (reviewed?.reportText != null) {
                                firstReportSessionId = sid
                                _uiState.update {
                                    it.copy(
                                        pendingEvaluationSessionId = sid,
                                        pendingEvaluationReport    = reviewed.reportText,
                                        pendingAgentScore          = reviewed.agentScore,
                                    )
                                }
                            }
                        }
                    }
                }.onFailure { e ->
                    ZLog.w("ChatViewModel", "评分链路异常（不影响主流程）", e)
                }

                // ══════════════════════════════════════════════════════════════
                // W6-1 修复：后置孕期分析逻辑提取到 PregnancyPromptDelegate ──
                // 原来近 158 行的 checkTrigger / 受孕窗口 / D5 / D3 didAsk
                // 全部收敛到 runPostReplyAnalysis() 一个调用。
                pregnancyDelegate.runPostReplyAnalysis(
                    characterId              = capturedCharId,
                    aiReply                  = capturedReply,
                    userText                 = capturedText,
                    pregnancyState           = capturedPregnancyState,
                    d3Pending                = capturedD3Pending,
                    d3Patch                  = capturedD3Patch,
                    pendingKeywordTriggerMap = pendingKeywordTriggerMap,
                    lastFertileJudgeAtMap    = lastFertileJudgeAtMap,
                    recentMessages           = _uiState.value.messages
                        .takeLast(10)
                        .mapNotNull { msg ->
                            when (msg.role) {
                                "user", "assistant" -> LLMMessage(role = msg.role, content = msg.content)
                                else -> null
                            }
                        },
                    character                = _uiState.value.character,
                    onTriggerD4Generation    = { lockedAnswers ->
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                daughterGenerator.generateForMother(
                                    motherConfig  = daughterRepo.getCharacterConfig(capturedCharId)
                                        ?: return@launch,
                                    lockedAnswers = lockedAnswers,
                                )
                            } catch (e: CancellationException) {
                                throw e  // 协程取消必须重新抛出，不能当成业务失败吞掉
                            } catch (e: Throwable) {
                                // 与主回复流程同批修复：catch Throwable 而非 Exception。
                                ZLog.e("ChatViewModel", "D5→D4 第三代 generateForMother 失败", e)
                                _uiState.update { it.copy(pendingDaughterGenerationError = "女儿生成失败，请稍后重试。") }
                            }
                        }
                    },
                    onFertileWindowConsentDialog = { dialogText, characterName, charId ->
                        _uiState.update {
                            it.copy(
                                fertileWindowConsentDialogText = dialogText,
                                fertileWindowCharacterName     = characterName,
                                fertileWindowCharacterId       = charId,
                            )
                        }
                    },
                )
            } // end viewModelScope.launch (后置 LLM 分析)
        } // end if (capturedReply.isNotBlank())
    }

    /**
     * Fix-StreamThinking：从流式累积文本中增量提取思考内容。
     *
     * 覆盖两种形态：
     *   1) 已闭合的 [thinking:...] 标签（可能多段，按出现顺序拼接）；
     *   2) 末尾正在输出、尚未闭合的半截 thinking 内容（流式中途标签必然开在末尾，
     *      模型闭合前不会产出标签之后的新内容，见 ChatTagParser 同款"锚定末尾"策略）。
     *
     * 性能：不含 "[thinking" 前缀时直接短路返回，避免每个 token 都跑正则扫描
     * 整条累积文本；只有确实出现思考标签的回复才付出正则成本。
     */
    private fun extractStreamingThinking(reply: CharSequence): String? {
        val text = reply.toString()
        if (!text.contains("[thinking")) return null
        val closedRegex = Regex("""\[thinking[:：]\s*([^\[\]]*?)\s*]""", RegexOption.DOT_MATCHES_ALL)
        val matches = closedRegex.findAll(text).toList()
        val parts = matches.map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toMutableList()
        // 半截：最后一个闭合标签之后（无闭合标签则为全文）存在未闭合的 "[thinking:" 开头
        val tailStart = (matches.lastOrNull()?.range?.last ?: -1) + 1
        if (tailStart < text.length) {
            val tail = text.substring(tailStart)
            val openIdx = tail.lastIndexOf("[thinking")
            if (openIdx >= 0) {
                val afterOpen = tail.substring(openIdx + "[thinking".length)
                if (afterOpen.startsWith(":") || afterOpen.startsWith("：")) {
                    val partial = afterOpen.drop(1)
                    // 半截里若已出现闭合符，说明该段其实已闭合（应已被上面正则覆盖），忽略
                    if (!partial.contains(']') && partial.isNotBlank()) {
                        parts.add(partial.trim())
                    }
                }
            }
        }
        return parts.joinToString("\n\n").ifBlank { null }
    }

    // P2-5-2 修复：增量提取心理描写（全文括号（…）段落），供 _streamingPsych 在
    // 流式期间实时更新。与 extractStreamingThinking 同构：收集所有已闭合的（…）段，
    // 结尾若存在未闭合的（…（无对应 ））则作为"正在输出"的半截一并展示。
    private fun extractStreamingPsych(reply: CharSequence): String? {
        val text = reply.toString()
        if (!text.contains("（")) return null
        val closedRegex = Regex("""（([^（）]*?)）""", RegexOption.DOT_MATCHES_ALL)
        val matches = closedRegex.findAll(text).toList()
        val parts = matches.map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toMutableList()
        val tailStart = (matches.lastOrNull()?.range?.last ?: -1) + 1
        if (tailStart < text.length) {
            val tail = text.substring(tailStart)
            val openIdx = tail.lastIndexOf("（")
            if (openIdx >= 0) {
                val partial = tail.substring(openIdx + 1)
                if (!partial.contains('）') && partial.isNotBlank()) {
                    parts.add(partial.trim())
                }
            }
        }
        return parts.joinToString("\n\n").ifBlank { null }
    }

    /**
     * 角色间关系头衔系统·接入点2辅助：按名字反查 characterId（真实角色）。
     * 先查 DefaultCharacters（初代9人，静态数据，零成本）；查不到再查全部
     * 已注册女儿/孙女（daughterRepo.observeAllCharacterConfigs() 取一次快照）。
     * 两处都查不到返回 null，调用方按"预设身份无对应角色"分支处理（查
     * toPresetName 而非 toCharacterId）。
     */
    private suspend fun resolveCharacterIdByName(name: String): Int? {
        DefaultCharacters.firstOrNull { it.name == name }?.let { return it.id }
        return try {
            daughterRepo.observeAllCharacterConfigs().first().firstOrNull { it.name == name }?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e("ChatMessageOrchestrator", "resolveCharacterIdByName(\"$name\") 查询女儿角色失败", e)
            null
        }
    }
}

/**
 * 从工具执行结果里识别"文件已落盘"的元数据 JSON，以及把多个文件元数据 JSON
 * 打包成一个 JSON 数组字符串——这两个函数的唯一实现已下沉到
 * [com.zaijian.zhoumuyun.data.agent.extractExportedFileJson] /
 * [com.zaijian.zhoumuyun.data.agent.packExportedFilesJson]（P3-2：元数据解析
 * 三份副本统一）。这里保留同名薄封装，是因为本文件内（:549 私聊 ToolDone）以及
 * 同包的 RoundtableBotReplyGenerator / RoundtableIdleManager / ChatExportDelegate
 * 一直以"同包顶层函数"的方式直接调用它们，保留封装可以让这 4 个调用点不用改。
 */
internal fun extractExportedFileJson(result: ToolResult): String? =
    com.zaijian.zhoumuyun.data.agent.extractExportedFileJson(result)

internal fun packExportedFilesJson(fileJsonList: List<String>): String? =
    com.zaijian.zhoumuyun.data.agent.packExportedFilesJson(fileJsonList)
