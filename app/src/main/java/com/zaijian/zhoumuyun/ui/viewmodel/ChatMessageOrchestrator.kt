package com.zaijian.zhoumuyun.ui.viewmodel

import android.content.Context
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.MessageEntity
import com.zaijian.zhoumuyun.data.manager.DaughterCharacterGenerator
import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.ChatMode
import com.zaijian.zhoumuyun.data.model.toCharacterStateLayer
import com.zaijian.zhoumuyun.data.model.toMoodType
import com.zaijian.zhoumuyun.data.memory.MemoryEngine
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
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

    fun sendMessage(text: String) {
        if (text.isBlank() || getCurrentCharacterId() < 0) return
        val provider = ProviderManager.instance.activeProvider ?: run {
            _uiState.update { it.copy(isApiKeyMissing = true) }
            return
        }

        // Fix-孤儿文件 ③（配合 ToolCallInterceptor.isToolInFlight 一起看）：
        // 正常情况下这里几乎不会命中——isTyping 已经在门控发送按钮，走到这行
        // 说明要么是 isTyping 门控失效的边界情况，要么是未来新增的某条不经过
        // 按钮的调用路径。以前这里会无条件 getReplyJob()?.cancel()：如果恰好
        // 有 excel_gen/pptx_gen 这类工具正在写文件（POI 写入阻塞、取消不了），
        // 文件会正常落盘但这次回复被腰斩、用户体验上像是"话说到一半没了"
        // （①②已经保证这种情况下文件本身不会真的丢，见 executeWithTimeout 的
        // Fix-孤儿文件 说明，但被打断这件事本身仍然是不好的体验）。现在改成：
        // 发现正有工具在执行时，不取消、不发送，只提示用户稍候。
        if (ToolCallInterceptor.isToolInFlight(AgentActivityRepository.SceneType.CHAT, getCurrentCharacterId())) {
            _uiState.update { it.copy(error = "上一个操作还在进行中，请稍候再发送") }
            return
        }

        getReplyJob()?.cancel()
        setReplyJob(viewModelScope.launch(Dispatchers.IO) {
            // B-1 修复：try-finally 保证无论 catch 块外的 DAO / engine 调用抛出何种异常，
            // isTyping 都能被置回 false，避免发送按钮永久禁用。
            // （CancellationException 会越过 catch 直接到 finally，再向上 rethrow，
            //   结构化并发不受影响。）
            try {
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
                // （原 1012 行附近）才会置位——这中间这段"用户已发送但看不到任何
                // 反馈"的空窗期，在关键词匹配年代几乎不可感知（同步操作，微秒级），
                // 现在可能被 AI 判定的网络延迟明显放大，用户会看到发送后界面
                // 短暂"卡住"。这里提前到用户消息落库、UI 刷新之后立即置位，
                // 让"正在输入"指示与发送按钮禁用尽早生效——顺带修复了一个
                // 已存在但此前不易察觉的小问题：之前这段窗口期 canSend 仍为
                // true（ChatInputBar.kt 用 !isTyping 门控发送按钮），理论上用户
                // 可以在 prompt 组装完成前重复点击发送。
                // P1-3 修复：streamingContent 不再写入 _uiState（双写导致整屏重组），
                // 只保留独立 _streamingContent StateFlow 供 StreamingMessageItem 单独收集
                _uiState.update { it.copy(isTyping = true) }

                // W2-2 修复：character 为 null（女儿数据损坏导致 loadCharacterJob 中
                // DaughterDataException 被捕获后降级为 null）时，之前直接 return@launch，
                // 用户消息已落库但 AI 永远不回复、且没有任何提示，界面表现为"正在输入"
                // 一闪而过后卡住。这里在跳过发送前写入用户可见的错误提示。
                val character = _uiState.value.character ?: run {
                    _uiState.update { it.copy(error = "角色数据异常，请尝试重新生成或联系开发者") }
                    return@launch
                }
                // Bug2-fix: 过滤非法 role，只保留 user/assistant 两种合法值
                // - role = "system" 且带 [AGENT_MSG:xx]/[ROUNDTABLE_TRIGGER] 前缀的，是内部控制信号，
                //   会被别的地方消费/转换成真正的对话内容，本就不该原样出现在角色看到的历史里，跳过。
                // - role = characterId.toString()（如 "1","2"）的主动消息映射为 "assistant"
                //
                // Fix-FileImportBlindSpot：此前这里把所有 role="system" 消息一律跳过，
                // 连 notifyFileImported() 写的"用户导入了一个文件：xxx"也被当成内部控制信号
                // 一起丢掉了——但这条消息不像 AGENT_MSG/ROUNDTABLE_TRIGGER 那样会在别处被
                // 转换成别的内容再喂给角色，它是"文件被导入过"这件事唯一的记录。丢掉之后，
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
                                null  // 内部控制信号，不进入对话上下文
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
                    PromptOrchestrator.buildAgentPlanBlock(it.title, it.content)
                } ?: ""

                // ── 补全 LearningGoal Layer（isLocked=true 的能力规则，按目标分组）──
                val activeGoals = learningGoalRepo.getActive(getCurrentCharacterId())
                val rulesByGoal = activeGoals.associate { goal ->
                    goal.title to memoryRepo
                        .getLockedRules(getCurrentCharacterId(), goal.id)
                        .map { it.content }
                }
                val ruleLayerBlock = PromptOrchestrator.buildRuleLayerBlock(rulesByGoal)

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
                val unreportedJob = workflowRepo.findUnreported(getCurrentCharacterId()).firstOrNull()
                val workflowRecapText = if (unreportedJob != null) {
                    val statusLabel = when (unreportedJob.status) {
                        "COMPLETED" -> "\u2705 完成"
                        "FAILED"    -> "\u274C 失败"
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

                // ── chainRecapPatch：灵活自动化编排 · 链条播报（验收缺口修复，§11.10）──
                // 与上方 workflowRecapPatch 同一模式、并列查询：ChainRunRepository.findUnreported()
                // 数据层此前已实现（含 characterId=-1 项目级链条），但从未被任何 UI/业务层调用，
                // 链条即使正确跑完 End(COMPLETED)，结果也只是安静躺在 chain_runs 表里，用户
                // 永远不会知道。此处补上查询 + 拼接播报文案，标记已读逻辑见下方 751 行附近。
                // 文案前缀"[链条自动化播报]"与"[后台任务播报]"区分开，避免用户/角色混淆两种机制。
                val unreportedChainRun = chainRunRepository.findUnreported(getCurrentCharacterId()).firstOrNull()
                val chainRecapText = if (unreportedChainRun != null) {
                    val statusLabel = when (unreportedChainRun.status) {
                        "COMPLETED" -> "\u2705 完成"
                        "FAILED"    -> "\u274C 失败"
                        "CANCELLED" -> "\u26AA 已取消"
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

                // 两种播报都存在时用空行分隔，拼进同一个 prompt 槽位（PromptOrchestrator
                // 侧 workflowRecapPatch 参数本身就是"非空则整块 append"的简单字符串处理，
                // 不改函数签名，改动面最小）。
                val workflowRecapPatch = listOf(workflowRecapText, chainRecapText)
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
                    PromptOrchestrator.buildTaskLayerBlock(
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
                    com.zaijian.zhoumuyun.domain.SpeakerContext.NON_OWNER
                else
                    com.zaijian.zhoumuyun.domain.SpeakerContext.OWNER_DIRECT
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

                val systemPrompt = PromptOrchestrator.buildSystemPrompt(
                    character             = character,
                    identityEntity        = identityEntity,
                    coreMemories          = coreMemories,
                    relevantMemories      = relevantMemories,
                    presenceActivity      = presenceSnap?.activity ?: "",
                    presenceFocus         = presenceSnap?.goalTitle ?: "",
                    presenceMood          = presenceSnap?.mood?.name ?: "",
                    presenceEnergy        = presenceSnap?.energy ?: -1,
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

                val config = LLMConfig(
                    model = "",
                    maxTokens = 50000,
                    temperature = 0.8f,
                    stream = true,
                )

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

                // 心迹（Window B 2.2.3）：提前生成本轮助手消息 id，用作「心迹」事件
                // 的 sessionRef（私聊= messageId，方案 2.2.2），让"过程痕迹"能关联回
                // 具体一条回复。与圆桌两条路径（msgId 在流式前预生成）对齐。原先此处
                // 在流式结束后才 UUID.randomUUID() 生成 assistantMsg.id，现在提前到流式
                // 前、流式结束落库时复用同一个 id——行为等价（仍是随机 UUID），仅生成
                // 时机前移，不改变消息内容/落库语义。
                val replyMsgId = UUID.randomUUID().toString()

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
                                fullReply.append(event.text)
                                // Fix-StreamThinking（输出节奏：思考先出，正文+文件收尾一起发）：
                                // 思考内容不在"一次性合并"范围内——增量解析 [thinking:...]
                                // （含正在输出、尚未闭合的半截），实时推给流式气泡的
                                // ThoughtCard 展示；正文与文件卡片仍等收尾一次性提交。
                                _streamingThinking.value = extractStreamingThinking(fullReply)
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
                }

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
                // Fix-BlankReplyFilesLost（文件生成成功但文件卡片丢失 根因修复）：
                // 原条件只认 cleanReply.isNotBlank()——模型有时整轮只调工具、不写一字正文
                // （或被标签剥离后恰好为空），此时生成的文件随整条消息被丢弃，
                // 用户看到"文件已生成"的日志但对话里没有任何文件卡片。
                // 放宽为"有正文 或 有文件 或 有表格"任一即落库；content 为空串时
                // UI 只渲染文件/表格卡片，不画文字气泡（MessageBubble 的 showBubble 判断
                // 本身就这么处理，无渲染风险）。
                if (cleanReply.isNotBlank() || pendingExportedFiles.isNotEmpty() || pendingTablePayloadJson != null) {
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
                        tableDataJson = pendingTablePayloadJson,
                    )
                    messageRepo.insert(assistantMsg)
                    // 用完立即清空，避免串到下一轮回复（下一轮没有新的文件类工具调用时，
                    // 若不清空会把这一轮的文件卡片错误地挂到下一条不相关的消息上）。
                    pendingExportedFiles.clear()
                    // v67（表格直传 W4）：同上，清空避免串消息。
                    pendingTablePayloadJson = null
                    // H2 修复（race消除）：insert是挂起函数，到这里落库已完成。
                    // 做乐观更新——把刚落库的消息同步追加到内存list，
                    // 后续读 _uiState.value.messages 保证能看到这条新消息。
                    //
                    // 第7窗口问题3修复：删除此处原有的 loadMessages(currentCharacterId)
                    // 兜底调用（全量重查数据库，属冗余操作）。核实依据：
                    // 1) observeJobs 中没有任何消息表（messageDao/MessageEntity）相关的
                    //    Flow 订阅，此处不存在"响应式更新会遗漏"的问题；
                    // 2) AgentMetaTools 中跨角色写消息表的两处工具调用写入的是
                    //    role="system" 的 AGENT_MSG/ROUNDTABLE_TRIGGER 控制信号，
                    //    本就不进入 ChatUiState.messages 展示列表，无需同步；
                    // 3) ProactiveMessageNotifier 的主动消息写入已有独立、已存在的
                    //    刷新路径——clearProactiveMessage() 内的 loadMessages 覆盖此场景，
                    //    不依赖此处兜底。
                    // 上述三点排除了所有"其他路径写库后内存未同步"的实际场景，
                    // 乐观更新已足够，删除此处全量重查是安全的。
                    val latestMessages = (_uiState.value.messages + ChatTagParser.toChatMessage(assistantMsg))
                        .toImmutableList()
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

                // P1-10-1 修复：把所有后置 LLM 分析（评分卡、受孕窗口判定、D5 升阶、D3 didAsk）
                // 移入独立的 viewModelScope.launch，使 replyJob 的 finally 块能立即清零
                // isTyping，避免用户在后置 LLM 分析期间（可能数秒）看到输入框持续禁用。
                // 后置分析捕获所有需要的不可变局部变量（cleanReply、text、currentCharacterId 等），
                // 不依赖任何 replyJob 的可变状态。
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