package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.agent.CalendarSyncHelper
import com.zaijian.zhoumuyun.data.datastore.ChatBackgroundDataStore
import com.zaijian.zhoumuyun.data.datastore.D3AskAttemptDataStore
import com.zaijian.zhoumuyun.data.datastore.FileDeliveryDataStore
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.data.manager.DaughterIdAllocator
import com.zaijian.zhoumuyun.data.manager.PregnancyTriggerManager
import com.zaijian.zhoumuyun.data.model.ChatMode
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.PregnancyAnswerRepository
import com.zaijian.zhoumuyun.domain.AgentRelationEngine
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.domain.pregnancy.PregnancyAnswerConsistencyChecker
import com.zaijian.zhoumuyun.domain.pregnancy.PregnancyAnswerIntentDetector
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 聊天页 ViewModel（应用内单例，不随切换角色重建）。
 *
 * 重构后职责：状态持有 + 委托转发 + 生命周期管理。
 * 业务逻辑分布在各委托类中：
 * - [ChatSessionDelegate]：会话初始化、消息加载、Flow 订阅
 * - [ChatMessageOrchestrator]：消息发送编排
 * - [ChatEvaluationDelegate]：评分引擎 + 规则提炼
 * - [ChatExportDelegate]：对话导出
 * - [ChatMessageActionsDelegate]：文件导入、消息清空
 * - [ChatProjectDelegate]：活跃项目切换
 * - [PregnancyPromptDelegate]：孕期 Prompt 构建 + 受孕弹窗回调
 * - [ChatBackgroundManager]：聊天背景图管理
 * - [ChatToolRegistrar]：Agent 工具注册
 * - [DaughterRegistrationHelper]：女儿角色生成器构造
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // ── 状态流 ──────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // L-8 修复：独立 StateFlow 隔离高频重组，StreamingMessageItem 只收集此流。
    private val _streamingContent = MutableStateFlow<String?>(null)
    val streamingContent: StateFlow<String?> = _streamingContent.asStateFlow()

    // Fix-StreamingPsychLeak：流式阶段实时剥离的心理描写，独立 StateFlow。
    private val _streamingPsych = MutableStateFlow<String?>(null)
    val streamingPsych: StateFlow<String?> = _streamingPsych.asStateFlow()

    // Fix-StreamThinking：流式阶段实时剥离的思考过程（[thinking:...] 增量解析），
    // 独立 StateFlow——思考先出（ThoughtCard 实时展示），正文+文件收尾一次性合并提交。
    private val _streamingThinking = MutableStateFlow<String?>(null)
    val streamingThinking: StateFlow<String?> = _streamingThinking.asStateFlow()

    // A-1 修复：关系状态供顶部 Header 使用。
    private val _relForHeader = MutableStateFlow<RelationshipEntity?>(null)
    val relForHeader: StateFlow<RelationshipEntity?> = _relForHeader.asStateFlow()

    // ── 依赖 ────────────────────────────────────────────────────
    // [AUDIT-RETAIN S8-窗口01] db 仍需直接持有——多个委托（evaluation/session/
    // daughter）构造函数需要 db 参数，且 memoryDao 只读查询路径沿用 DataVisTools
    // 的既定模式（不经 Repository 包一层）。改为 container.db 需将 AppContainer
    // 的 db 从 private 改为 internal，留待后续 DI 收口批次处理。
    private val db = AppDatabase.getInstance(application)
    private val container = AppContainer.instance

    // S-1/S8 收口：以下 Repository 均引用容器共享实例，不再各自 new。
    private val agentPlanRepo get() = container.agentPlanRepo
    private val learningGoalRepo get() = container.learningGoalRepo
    private val messageRepo get() = container.messageRepo
    private val eventRepo get() = container.eventRepo
    private val memoryRepo get() = container.memoryRepo
    private val skillRepo get() = container.skillRepo
    private val identityRepo get() = container.identityRepo
    private val memoryDao get() = db.memoryDao()
    private val memoryEngine get() = container.memoryEngine
    private val relationshipEngine get() = container.relationshipEngine
    private val pregnancyRepo get() = container.pregnancyRepo
    private val characterStateRepo get() = container.characterStateRepo
    private val pregnancyPressureDataStore get() = container.pregnancyPressureDataStore
    private val taskRepo get() = container.taskRepo
    private val projectRepo get() = container.projectRepo
    private val agentStoreRepo get() = container.agentStoreRepo
    private val workflowRepo get() = container.workflowRepo
    private val daughterRepo get() = container.daughterCharacterRepo
    private val presenceEngine: PresenceEngine get() = container.presenceEngine
    private val cycleRepository get() = container.menstrualCycleRepo
    // S8 收口：scheduleRepo 改引用容器版本（含 calendarSync + context），
    // 修复审查报告问题8——此前自建版本缺 calendarSync/context，日历同步和
    // WorkManager 精确调度对聊天里创建的定时任务静默失效。
    private val scheduleRepo get() = container.scheduleRepo

    // [AUDIT-RETAIN S8-窗口01] pregnancyTriggerManager 不进容器——ChatViewModel
    // 传 aiJudge/consentJudge，RoundtableViewModel 不传，是真实功能差异。
    private val pregnancyTriggerManager = container.createPregnancyTriggerManagerFull(
        cycleRepository = cycleRepository,
        stateRepository = characterStateRepo,
    )

    // ── 评分引擎委托 ────────────────────────────────────────────
    private val evaluationDelegate = ChatEvaluationDelegate(
        _uiState = _uiState,
        db = db,
        memoryRepo = memoryRepo,
        viewModelScope = viewModelScope,
        getCurrentCharacterId = { currentCharacterId },
    )

    override fun onCleared() {
        super.onCleared()
        ProviderManager.instance.removeOnProviderConfigChangedListener(evaluationDelegate.providerConfigListener)
        // window13结论7：离开聊天页时清除前台角色标记（仅当全局值仍是本 ViewModel 设置的）。
        // B1审查序号2修复：check-then-clear改用原子 compareAndSet，见 PresenceEngine 注释。
        PresenceEngine.clearForegroundChatCharacterIdIfMatches(currentCharacterId)
    }

    // ── 工具注册 ────────────────────────────────────────────────
    private val githubConfigStore = GithubConfigDataStore(getApplication())
    // calendarSync 供 toolRegistrar 内 ScheduleCreateTool 使用（与容器 scheduleRepo
    // 内部的 calendarSync 是各自独立实例，该类无跨实例共享状态）。
    private val calendarSync = CalendarSyncHelper(getApplication())

    private val toolRegistrar = ChatToolRegistrar(
        db               = db,
        getApplication   = { getApplication() },
        agentPlanRepo    = agentPlanRepo,
        memoryRepo       = memoryRepo,
        memoryDao        = memoryDao,
        learningGoalRepo = learningGoalRepo,
        workflowRepo     = workflowRepo,
        chainRunRepository = container.chainRunRepository,
        taskRepo         = taskRepo,
        memoryEngine     = memoryEngine,
        scheduleRepo     = scheduleRepo,
        calendarSync     = calendarSync,
        identityRepo     = identityRepo,
        githubConfigStore = githubConfigStore,
        skillRepo        = skillRepo,
        projectRepo      = projectRepo,
        agentStoreRepo   = agentStoreRepo,
    )

    // ── 孕期 + 背景图 + 女儿注册 ────────────────────────────────
    private val chatBgStore = ChatBackgroundDataStore(getApplication())

    // 文档发送方式（默认文字+文件合并进同一气泡）：全局开关，跟随 App 生命周期
    // 持久化，不随角色切换或 ViewModel 重建丢失——ChatViewModel 本身是 App 内
    // 单例（见类头注释），init 里订阅一次即可覆盖整个 App 生命周期。
    private val fileDeliveryStore = FileDeliveryDataStore(getApplication())

    // [AUDIT-RETAIN S8-窗口01] 以下 4 项为 ChatViewModel 专用、构造参数有功能性
    // 依赖链，不适合收敛到容器。
    private val pregnancyAnswerConsistencyChecker = PregnancyAnswerConsistencyChecker(
        providerFn = { ProviderManager.instance.activeProvider },
    )
    private val pregnancyAnswerRepo = PregnancyAnswerRepository(
        answerDao          = db.pregnancyAnswerDao(),
        pendingDao         = db.pregnancyPendingQuestionDao(),
        consistencyChecker = pregnancyAnswerConsistencyChecker,
    )
    private val pregnancyAnswerIntentDetector = PregnancyAnswerIntentDetector(
        providerFn = { ProviderManager.instance.activeProvider },
    )
    private val d3AskAttemptStore = D3AskAttemptDataStore(getApplication())

    // [AUDIT-RETAIN S8-窗口01] 依赖 daughterRepo，仅 ChatViewModel 使用。
    private val agentRelationEngine = AgentRelationEngine(db.agentRelationDao(), daughterRepo)

    private val pregnancyDelegate = PregnancyPromptDelegate(
        pregnancyRepo                 = pregnancyRepo,
        pregnancyTriggerManager       = pregnancyTriggerManager,
        pregnancyPressureDataStore    = pregnancyPressureDataStore,
        pregnancyAnswerRepo           = pregnancyAnswerRepo,
        pregnancyAnswerIntentDetector = pregnancyAnswerIntentDetector,
        d3AskAttemptStore             = d3AskAttemptStore,
        daughterRepo                  = daughterRepo,
        agentRelationEngine           = agentRelationEngine,
        viewModelScope                = viewModelScope,
    )

    private val backgroundManager = ChatBackgroundManager(
        _uiState             = _uiState,
        chatBgStore          = chatBgStore,
        viewModelScope       = viewModelScope,
        getCurrentCharacterId = { currentCharacterId },
    )

    private val daughterIdAllocator = DaughterIdAllocator(db.daughterIdAllocatorDao())
    private val daughterRegistrationHelper = DaughterRegistrationHelper(
        daughterRepo        = daughterRepo,
        daughterIdAllocator = daughterIdAllocator,
        identityRepo        = identityRepo,
        cycleRepository     = cycleRepository,
        db                  = db,
    )
    private val daughterGenerator = daughterRegistrationHelper.createGenerator()

    // ── 可变状态 ────────────────────────────────────────────────
    private var currentCharacterId = -1
    private var replyJob: Job? = null

    // 受孕机制 AI 门3判定冷却 + 关键词兜底跨轮标记（供 messageOrchestrator 消费）。
    private val lastFertileJudgeAtMap = ConcurrentHashMap<Int, Long>()
    private val pendingKeywordTriggerMap = ConcurrentHashMap<Int, Boolean>()

    // ── 委托类 ──────────────────────────────────────────────────
    private val sessionDelegate = ChatSessionDelegate(
        _uiState             = _uiState,
        _streamingContent    = _streamingContent,
        _streamingPsych      = _streamingPsych,
        _relForHeader        = _relForHeader,
        messageRepo          = messageRepo,
        daughterRepo         = daughterRepo,
        presenceEngine       = presenceEngine,
        identityRepo         = identityRepo,
        pregnancyRepo        = pregnancyRepo,
        memoryRepo           = memoryRepo,
        cycleRepository      = cycleRepository,
        daughterIdAllocator  = daughterIdAllocator,
        db                   = db,
        backgroundManager    = backgroundManager,
        toolRegistrar        = toolRegistrar,
        viewModelScope       = viewModelScope,
        getApplication       = { getApplication() },
        setCurrentCharacterId = { currentCharacterId = it },
        getCurrentCharacterId = { currentCharacterId },
        getReplyJob          = { replyJob },
        setReplyJob          = { replyJob = it },
    )

    private val exportDelegate = ChatExportDelegate(
        _uiState            = _uiState,
        messageRepo         = messageRepo,
        viewModelScope      = viewModelScope,
        getCurrentCharacterId = { currentCharacterId },
        reloadMessages      = { sessionDelegate.reloadMessages(it) },
    )

    private val messageActionsDelegate = ChatMessageActionsDelegate(
        _uiState            = _uiState,
        messageRepo         = messageRepo,
        viewModelScope      = viewModelScope,
        getCurrentCharacterId = { currentCharacterId },
        reloadMessages      = { sessionDelegate.reloadMessages(it) },
    )

    private val projectDelegate = ChatProjectDelegate(
        _uiState      = _uiState,
        projectRepo   = projectRepo,
        viewModelScope = viewModelScope,
    )

    private val messageOrchestrator = ChatMessageOrchestrator(
        _uiState                      = _uiState,
        _streamingContent             = _streamingContent,
        _streamingPsych               = _streamingPsych,
        _streamingThinking            = _streamingThinking,
        messageRepo                   = messageRepo,
        memoryRepo                    = memoryRepo,
        memoryEngine                  = memoryEngine,
        identityRepo                  = identityRepo,
        relationshipEngine            = relationshipEngine,
        presenceEngine                = presenceEngine,
        pregnancyRepo                 = pregnancyRepo,
        characterStateRepo            = characterStateRepo,
        daughterRepo                  = daughterRepo,
        agentPlanRepo                 = agentPlanRepo,
        learningGoalRepo              = learningGoalRepo,
        skillRepo                     = skillRepo,
        taskRepo                      = taskRepo,
        projectRepo                   = projectRepo,
        workflowRepo                  = workflowRepo,
        chainRunRepository            = container.chainRunRepository,
        eventRepo                     = eventRepo,
        pregnancyDelegate             = pregnancyDelegate,
        agentRelationEngine           = agentRelationEngine,
        daughterGenerator             = daughterGenerator,
        characterTitleRelationRepo    = container.characterTitleRelationRepo,
        db                            = db,
        getApplication                = { getApplication() },
        getCurrentCharacterId         = { currentCharacterId },
        getReplyJob                   = { replyJob },
        setReplyJob                   = { replyJob = it },
        getEvaluationEngine           = { evaluationDelegate.getEvaluationEngine() },
        pendingKeywordTriggerMap      = pendingKeywordTriggerMap,
        lastFertileJudgeAtMap         = lastFertileJudgeAtMap,
        viewModelScope                = viewModelScope,
        loadMessages                  = { sessionDelegate.loadMessages(it) },
        MAX_HISTORY_CHARS             = MAX_HISTORY_CHARS,
    )

    // 崩溃修复：init 块原先插在字段声明中间（evaluationDelegate 之后、githubConfigStore
    // 之前），但块内访问的 fileDeliveryStore 声明在文件更靠后的位置——Kotlin 类主体按
    // 声明顺序初始化，init 块在 fileDeliveryStore 赋值之前执行，此时访问
    // fileDeliveryStore.attachTogetherFlow 会拿到 null 引用，NPE 崩溃（ChatViewModel 是
    // App 内单例，首次构造即触发，表现为一点开角色私聊就闪退）。
    // 移到全部字段声明完成之后，保证 init 块内引用的所有属性都已初始化。
    init {
        evaluationDelegate.rebuildEngines()
        ProviderManager.instance.addOnProviderConfigChangedListener(evaluationDelegate.providerConfigListener)
        toolRegistrar.registerStaticTools()
        // 文档发送方式：启动时订阅持久化值，覆盖 ChatUiState 默认值（true=合并）。
        viewModelScope.launch {
            fileDeliveryStore.attachTogetherFlow.collect { together ->
                _uiState.update { it.copy(attachFilesTogether = together) }
            }
        }
    }

    // ── 公共 API（委托转发 + trivial state updates）──────────────

    fun init(characterId: Int) = sessionDelegate.init(characterId)

    fun sendMessage(text: String) = messageOrchestrator.sendMessage(text)

    fun notifyFileImported(fileName: String, absolutePath: String, mimeType: String, sizeBytes: Long) =
        messageActionsDelegate.notifyFileImported(fileName, absolutePath, mimeType, sizeBytes)

    fun exportConversation() = exportDelegate.exportConversation()

    fun clearMessages() = messageActionsDelegate.clearMessages()

    fun clearProactiveMessage() = messageActionsDelegate.clearProactiveMessage()

    fun setActiveProject(projectId: String?) = projectDelegate.setActiveProject(projectId)

    fun submitEvaluationScore(score: Int) = evaluationDelegate.submitEvaluationScore(score)

    fun skipEvaluation() = evaluationDelegate.skipEvaluation()

    /** 受孕窗口同意对话框回调。UI 状态清理留在 ViewModel，业务逻辑委托给 pregnancyDelegate。 */
    fun onFertileWindowDialogResult(accepted: Boolean) {
        val characterId = _uiState.value.fertileWindowCharacterId
        _uiState.update { it.copy(fertileWindowConsentDialogText = null) }
        if (characterId < 0) {
            ZLog.w("ChatViewModel", "onFertileWindowDialogResult: fertileWindowCharacterId 无效（$characterId），跳过受孕判定")
            return
        }
        pregnancyDelegate.onFertileWindowDialogResult(accepted = accepted, characterId = characterId)
    }

    // 背景图管理转发
    fun requestChatBackgroundCrop(uri: String) = backgroundManager.requestChatBackgroundCrop(uri)
    fun cancelChatBackgroundCrop() = backgroundManager.cancelChatBackgroundCrop()
    fun confirmChatBackgroundCrop(uri: String, offsetX: Float, offsetY: Float, scale: Float) =
        backgroundManager.confirmChatBackgroundCrop(uri, offsetX, offsetY, scale)
    fun setChatBackground(uri: String) = backgroundManager.setChatBackground(uri)
    fun clearChatBackground() = backgroundManager.clearChatBackground()

    // Trivial state updates
    fun setChatMode(mode: ChatMode) { _uiState.update { it.copy(chatMode = mode) } }
    fun dismissDistillResult() { _uiState.update { it.copy(pendingDistillResult = null) } }
    fun clearError() { _uiState.update { it.copy(error = null) } }
    fun clearDaughterGenerationError() { _uiState.update { it.copy(pendingDaughterGenerationError = null) } }
    fun clearApiKeyMissingFlag() { _uiState.update { it.copy(isApiKeyMissing = false) } }
    fun setKnowledgeInjectMode(mode: KnowledgeInjectMode) { _uiState.update { it.copy(knowledgeInjectMode = mode) } }
    fun triggerManualKnowledgeInject() { _uiState.update { it.copy(manualKnowledgeTriggerPending = true) } }

    /**
     * 文档发送方式切换（ChatSettingsSheet 入口）。
     * 先乐观更新 UI（切换即生效，不用等磁盘写完），持久化写入放后台协程——
     * 与本文件其余 DataStore 写入点（如 confirmChatBackgroundCrop）同一模式。
     */
    fun setAttachFilesTogether(together: Boolean) {
        _uiState.update { it.copy(attachFilesTogether = together) }
        viewModelScope.launch { fileDeliveryStore.setAttachTogether(together) }
    }

    companion object {
        /** 单次请求按字符预算保留的历史消息（DeepSeek V4 Flash 1M 上下文约 28.6%）。 */
        private const val MAX_HISTORY_CHARS = 450_000
    }
}
