package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import com.zaijian.zhoumuyun.util.ZLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.repository.AgentPlanRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.LearningGoalRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.agent.CiCdStartTool
import com.zaijian.zhoumuyun.data.agent.GoalUpdateTool
import com.zaijian.zhoumuyun.data.agent.MemoryQueryTool
import com.zaijian.zhoumuyun.data.agent.MemoryWriteTool
import com.zaijian.zhoumuyun.data.agent.PlanSaveTool
import com.zaijian.zhoumuyun.data.agent.RuleDistillTool
import com.zaijian.zhoumuyun.data.agent.SelfReflectTool
import com.zaijian.zhoumuyun.data.agent.RuleReviewTool
import com.zaijian.zhoumuyun.data.agent.StreamEvent
import com.zaijian.zhoumuyun.data.agent.TaskCancelTool
import com.zaijian.zhoumuyun.data.agent.TaskCompleteTool
import com.zaijian.zhoumuyun.data.agent.TaskStartTool
import com.zaijian.zhoumuyun.data.agent.TaskUpdateTool
import com.zaijian.zhoumuyun.data.agent.ToolCallInterceptor
import com.zaijian.zhoumuyun.data.agent.registerSoulMemoryUserTools
import com.zaijian.zhoumuyun.data.agent.WorkflowStartTool
import com.zaijian.zhoumuyun.data.agent.ScheduleCreateTool
import com.zaijian.zhoumuyun.data.agent.ScheduleListTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatSetTool
import com.zaijian.zhoumuyun.data.agent.ReminderTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatUpdateTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatDeleteTool
import com.zaijian.zhoumuyun.data.agent.CalendarSyncHelper
import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import com.zaijian.zhoumuyun.data.datastore.D3AskAttemptDataStore
import com.zaijian.zhoumuyun.data.datastore.ChatBackgroundDataStore
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.MessageEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyQuestionType
import com.zaijian.zhoumuyun.data.db.entity.AgentRelationEntity
import com.zaijian.zhoumuyun.domain.AgentRelationEngine
import com.zaijian.zhoumuyun.domain.DistillationEngine
import com.zaijian.zhoumuyun.domain.EvaluationEngine
import com.zaijian.zhoumuyun.domain.StageTransitionResult
import com.zaijian.zhoumuyun.domain.MoodType
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.data.manager.DaughterCharacterGenerator
import com.zaijian.zhoumuyun.data.manager.DaughterIdAllocator
import com.zaijian.zhoumuyun.data.model.AgentRelationStage
import com.zaijian.zhoumuyun.data.model.ChatMode
import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.isDaughterMother
import com.zaijian.zhoumuyun.data.model.toDaughterCharacterData
import com.zaijian.zhoumuyun.data.model.toCharacterStateLayer
import com.zaijian.zhoumuyun.data.model.toCharacterIdentityEntity
import com.zaijian.zhoumuyun.data.prompt.D3TriggerContent
import com.zaijian.zhoumuyun.data.prompt.PromptOrchestrator
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.domain.pregnancy.IntentResult
import com.zaijian.zhoumuyun.domain.pregnancy.PregnancyAnswerConsistencyChecker
import com.zaijian.zhoumuyun.domain.pregnancy.PregnancyAnswerIntentDetector
import com.zaijian.zhoumuyun.data.repository.PregnancyAnswerRepository
import com.zaijian.zhoumuyun.data.repository.SlotRecordResult

import com.zaijian.zhoumuyun.data.repository.MenstrualCycleRepository
import com.zaijian.zhoumuyun.data.manager.FertileWindowConsentJudge
import com.zaijian.zhoumuyun.data.manager.PregnancyTriggerManager
import com.zaijian.zhoumuyun.data.manager.UserConsentIntentJudge
import com.zaijian.zhoumuyun.data.model.pickCharacterDialogText
import com.zaijian.zhoumuyun.data.model.slotKey
import com.zaijian.zhoumuyun.data.repository.ProjectRepository
import com.zaijian.zhoumuyun.data.repository.TaskRepository
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.json.JSONObject
import java.util.UUID

data class ExportedFile(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val absolutePath: String,
) {
    val extLabel: String get() = fileName.substringAfterLast(".", "?").take(4).uppercase()
    val sizeLabel: String get() = when {
        sizeBytes < 1024 -> "${sizeBytes} B"
        sizeBytes < 1024 * 1024 -> "${"%.1f".format(sizeBytes / 1024.0)} KB"
        else -> "${"%.1f".format(sizeBytes / 1024.0 / 1024.0)} MB"
    }
}

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val exportedFileJson: String? = null,
    // Fix-ThinkingLeak：从回复正文剥离出的内心推理/工具调用意图原文，null = 无想法内容。
    val thinkingText: String? = null,
) {
    val exportedFile: ExportedFile? get() {
        if (exportedFileJson == null) return null
        return try {
            val obj = org.json.JSONObject(exportedFileJson)
            ExportedFile(
                fileName = obj.optString("fileName", ""),
                mimeType = obj.optString("mimeType", "text/plain"),
                sizeBytes = obj.optLong("sizeBytes", 0),
                absolutePath = obj.optString("absolutePath", ""),
            )
        } catch (_: Exception) { null }
    }
}

@androidx.compose.runtime.Immutable
data class ChatUiState(
    val messages: ImmutableList<ChatMessage> = persistentListOf(),
    val character: com.zaijian.zhoumuyun.data.model.CharacterConfig? = null,
    val isTyping: Boolean = false,
    // 代码清洁：streamingContent 已从 uiState 中移除，改用独立 StateFlow 暴露
    val streamingHint: String? = null,
    val error: String? = null,
    val isApiKeyMissing: Boolean = false,
    val chatMode: ChatMode = ChatMode.COMPANION,
    val pendingEvaluationSessionId: String? = null,
    val pendingEvaluationReport: String? = null,
    val pendingAgentScore: Float? = null,
    val pendingDistillResult: DistillResult? = null,
    val knowledgeInjectMode: KnowledgeInjectMode = KnowledgeInjectMode.AUTO,
    val activeProjectId: String? = null,
    val activeProjects: ImmutableList<com.zaijian.zhoumuyun.data.db.entity.ProjectEntity> = persistentListOf(),
    val manualKnowledgeTriggerPending: Boolean = false,  // MANUAL 模式下：用户触发一次性注入
    // 1.1 受孕窗口同意对话框
    val fertileWindowConsentDialogText: String? = null,   // 非空时显示对话框
    val fertileWindowCharacterName: String = "",
    // 问题14修复：弹窗展示时捕获的角色ID快照（capturedCharId），而非实时的
    // currentCharacterId——弹窗展示期间用户若切换角色，onFertileWindowDialogResult()
    // 必须仍然作用在弹窗真正对应的角色上，不能被切换后的 currentCharacterId 顶替。
    val fertileWindowCharacterId: Int = -1,
    // D4 女儿生成失败提示（非空时 UI 弹 Snackbar）
    val pendingDaughterGenerationError: String? = null,
    // 主动消息前台实时呈现（非空时 UI 弹 Snackbar，含角色名 + 消息内容）
    val pendingProactiveMessage: String? = null,
    // UI M3 修复：角色当前心情，由 ViewModel 通过 StateFlow 推送，
    // ChatScreen 读 uiState.currentMood，不再直接访问全局单例 ZaijianApp.sharedPresenceEngine。
    val currentMood: MoodType? = null,
    // 聊天背景图：用户为当前角色设置的背景图 URI（null = 使用默认渐变背景）
    val backgroundImageUri: String? = null,
    // v55 修复：背景图取景偏移/缩放（来自 AvatarCropDialog 拖拽/缩放结果）。
    // scale=1f/offset=0f 时等价于旧版"直接 Crop 居中铺满"的行为。
    val backgroundOffsetX: Float = 0f,
    val backgroundOffsetY: Float = 0f,
    val backgroundScale: Float = 1f,
    // 待裁剪的背景图 URI：用户刚从相册选完图、裁剪弹窗还未确认时的中间态，
    // 非空时 UI 显示 AvatarCropDialog(shape = FULL_SCREEN)
    val pendingBackgroundCropUri: String? = null,
)

enum class KnowledgeInjectMode { AUTO, MANUAL }

data class DistillResult(
    val triggered: Boolean,
    val newlyLockedCount: Int,
    val goalTitle: String,
    val progressDelta: Float,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // L-8 修复：streamingContent 原先只是 uiState.streamingContent 的一个字段，
    // StreamingMessageItem 子组件为了隔离重组范围而单独收集 uiState，但收集的仍是
    // 完整对象——每个 token 到达时 uiState 整体变化，顶层 ChatScreen（同样收集
    // 完整 uiState）也会跟着重组，原本的隔离设计实际未生效。
    // 改为暴露独立的 StateFlow<String?>，只在 streamingContent 真正变化时更新，
    // StreamingMessageItem 改为只收集这一个流，真正把高频重组限制在子组件内。
    private val _streamingContent = MutableStateFlow<String?>(null)
    val streamingContent: StateFlow<String?> = _streamingContent.asStateFlow()

    private val db = AppDatabase.getInstance(application)
    // Phase 3 修复手册第3条：messageDao/identityDao/agentPlanDao 原先是裸持有的
    // DAO 字段（29种DAO里真正"字段裸持有、无包装、被直接调方法"的3个之一）。
    // 现在改包一层 Repository，但字段名保持不变——本文件内 messageDao.insert(...)/
    // identityDao.upsert(...)/agentPlanDao.getActive(...) 等调用点方法名与
    // 新 Repository 完全一致，不用跟着改名，改动面更小。
    private val messageDao = MessageRepository(db.messageDao())
    private val identityDao = IdentityRepository(db.characterIdentityDao())
    private val agentPlanDao = AgentPlanRepository(db.agentPlanDao())
    // 收尾交接清单 任务组B：本文件原无 LearningGoalRepository 包装字段，
    // 617/997 行裸调用 db.learningGoalDao()。RoundtableViewModel 已有同构造
    // 的 learningGoalDao 字段（同一模式：字段名沿用 DAO 原名，仅改底层实现），
    // 此处照抄。
    private val learningGoalDao = LearningGoalRepository(db.learningGoalDao())
    // Phase 3 修复手册：以下 6 项改从 AppContainer 取现成实例，不再各自 new
    // （原先与 RoundtableViewModel 逐行重复的装配逻辑，见审计报告 Phase 3）
    private val container = AppContainer.instance
    private val eventRepo get() = container.eventRepo
    private val memoryRepo get() = container.memoryRepo
    // 问题24修复：SelfReflectTool/RuleReviewTool 构造函数需要 MemoryDao 做
    // 只读查询（getByDomain/getAllRules/getLockedRules），与 memoryRepo 是
    // 同一张表的两种访问方式（Repository 写入路径 vs DAO 直接只读查询），
    // 沿用 DataVisTools.registerDataVisTools() 里同一对参数的取值方式，
    // 直接从 db 取，不经 Repository 包一层（这两个工具内部本就只做只读查询，
    // 不涉及 FTS 同步写入，不需要 memoryRepo.save() 那层保证）。
    private val memoryDao get() = db.memoryDao()
    private val memoryEngine get() = container.memoryEngine
    private val relationshipEngine get() = container.relationshipEngine
    private val pregnancyRepo get() = container.pregnancyRepo
    private val characterStateRepo get() = container.characterStateRepo
    // 问题4修复：PregnancyPressureDataStore 现已在 AppContainer 中实例化，
    // 供下方 sendMessage() 读取动态 pressureScale（此前所有调用点硬编码 1.0f）。
    private val pregnancyPressureDataStore get() = container.pregnancyPressureDataStore
    private val taskRepo = TaskRepository(db, db.taskDao(), db.worldEventDao())
    private val projectRepo = ProjectRepository(db.projectDao(), db.projectKnowledgeDao())
    // 报告第6条收口：daughterRepo 原先独立 new，与 AppContainer.daughterCharacterRepo
    // 构造参数完全一致（均只是 db.daughterCharacterDao()），改为引用容器共享实例，
    // 字段名保持不变，本文件内调用点（daughterRepo.xxx）不用跟着改。
    private val daughterRepo get() = container.daughterCharacterRepo
    // 报告第5条：PresenceEngine 收敛，改从 AppContainer 取（与 eventRepo 等
    // 6 项共享实例同一套模式），不再直接访问 ZaijianApp.sharedPresenceEngine
    // 全局单例。AppContainer 保证 onCreate() 时已完成构造，故此处为非空类型
    // （原先是 PresenceEngine? 是因为全局单例存在"onCreate 尚未跑完"的理论
    // 空窗期，容器化后不再有这个问题——ViewModel 能被构造，说明 onCreate 早已跑完）。
    private val presenceEngine: PresenceEngine get() = container.presenceEngine
    // PregnancyTriggerManager：shouldInjectMiscarriageContext（流产余波）+
    // shouldEvaluateFertileWindowConsent / judgeFertileWindowIntent（受孕窗口弹窗链路）。
    // 这一项不进 AppContainer——ChatViewModel 传 aiJudge、RoundtableViewModel 不传，
    // 是维持现状的真实功能差异（见审计报告 Phase 3 决策 2），依赖仍取容器共享实例。
    //
    // relationshipEngine：修复"二代/三代女儿受孕窗口弹窗链路未接通"的缺口——
    // shouldEvaluateFertileWindowConsent() 内部 `relationshipEngine ?: return false`，
    // 此前两个 ViewModel 构造时都没传这个参数，门1对所有 characterId>=1000 恒为
    // false，990-1030行那整套三重门控UI链路（冷却保护/弹窗文案/state更新）形同
    // 摆设。这里补传 container.relationshipEngine 接通该链路；圆桌场景维持不
    // 触发受孕弹窗的现状，不传。
    private val pregnancyTriggerManager = PregnancyTriggerManager(
        db                   = db,
        pregnancyRepository = pregnancyRepo,
        cycleRepository      = MenstrualCycleRepository(db.menstrualCycleDao()),
        stateRepository       = characterStateRepo,
        relationshipEngine   = relationshipEngine,
        aiJudge              = FertileWindowConsentJudge(providerFn = { ProviderManager.instance.activeProvider }),
        // 问题17（第二阶段）：1-6 号关键词兜底链路的 AI 判定优先层。
        // 与 aiJudge 同样用懒加载 providerFn，确保用户切换 provider/Key 后
        // 始终拿到最新实例；LLM 调用失败/超时时 PregnancyTriggerManager 内部
        // 自动降级到关键词兜底，这里不需要也不应该做任何额外的 try/catch。
        consentJudge         = UserConsentIntentJudge(providerFn = { ProviderManager.instance.activeProvider }),
    )
    private val workflowRepo = WorkflowRepository(db, db.workflowJobDao(), db.workflowStepResultDao())

    // ── Phase 24/26：打分引擎 + 规则提炼引擎 ────────────────────
    // Fix：改为返回 nullable，provider 为 null（首次启动未配置 Key）时不抛 ISE，
    // 调用处已通过 runCatching / return@runCatching 安全降级。
    private val evaluationEngine: EvaluationEngine? by lazy {
        val p = ProviderManager.instance.activeProvider ?: return@lazy null
        EvaluationEngine(
            evaluationSessionDao = db.evaluationSessionDao(),
            learningGoalDao      = db.learningGoalDao(),
            provider             = p,
        )
    }
    private val distillationEngine: DistillationEngine? by lazy {
        val p = ProviderManager.instance.activeProvider ?: return@lazy null
        DistillationEngine(
            db                   = db,
            evaluationSessionDao = db.evaluationSessionDao(),
            learningGoalDao      = db.learningGoalDao(),
            memoryDao            = db.memoryDao(),
            provider             = p,
            memoryRepo           = memoryRepo,
        )
    }

    private val scheduleRepo = ScheduleRepository(
        scheduledJobDao = db.scheduledJobDao(),
        jobResultDao    = db.jobResultDao(),
    )
    // 审查报告问题8修复：ZaijianApp.onCreate() 静态注册 ScheduleCreateTool 时已正确
    // 传入 CalendarSyncHelper 和 context，但 ChatViewModel 动态覆盖注册（仅为了绑定
    // 当前会话 characterId，见下方 Fix-#1 注释）时漏传了这两个参数，覆盖后
    // calendarSync/context 又变回构造函数默认值 null，导致日历同步和 WorkManager
    // 精确调度两条链路对聊天里创建的定时任务静默失效。此处与 ZaijianApp 同款方式
    // 各自持有一个 CalendarSyncHelper 实例（该类只依赖 context，无跨实例共享状态，
    // 两份实例不会产生数据不一致——SharedPreferences 映射表是进程级共享存储，
    // 不依赖 CalendarSyncHelper 对象本身的内存状态）。
    private val calendarSync = CalendarSyncHelper(getApplication())
    private val githubConfigStore = GithubConfigDataStore(getApplication())
    private val chatBgStore       = ChatBackgroundDataStore(getApplication())
    // ── D3 孕期共设 · 槎位问答状态机依赖 ──────────────────────────
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

    // ── D5 女儿关系阶段引擎 ─────────────────────────────────────
    // 问题19修复：新增传入 daughterRepo（即 container.daughterCharacterRepo），
    // 供 buildPromptSnapshot() 内部查询女儿真实 persona/speechStyle/coreWound，
    // 注入到阶段 Prompt 块里，不再是"谁来都一样"的纯模板文本。
    private val agentRelationEngine = AgentRelationEngine(db.agentRelationDao(), daughterRepo)
    private val daughterIdAllocator  = DaughterIdAllocator(db.daughterIdAllocatorDao())

    // ── D4 女儿人格生成器（槎位全锁后触发）──────────────────────
    private val daughterGenerator = DaughterCharacterGenerator(
        repository = daughterRepo,
        llmCall = { sys, user ->
            val provider = ProviderManager.instance.activeProvider
                ?: error("D4 生成器：无可用 LLM Provider")
            val cfg = com.zaijian.zhoumuyun.data.provider.LLMConfig(
                model = "", maxTokens = 4000, temperature = 0.9f, stream = false,
            )
            val resp = StringBuilder()
            provider.chat(
                listOf(com.zaijian.zhoumuyun.data.provider.LLMMessage(role = "user", content = user)),
                sys,
                cfg,
            ).collect { resp.append(it) }
            resp.toString()
        },
        onIdentityRegister = { daughterData ->
            // ── A-6 修复：女儿注册时同步插入 agent_relation 初始行 ──
            // 分配 daughterId → 写 character_identity → 插 agent_relation → 回填 daughter_character
            val allocatedId = daughterIdAllocator.allocate()
            val identityEntity = daughterData.toCharacterIdentityEntity(allocatedId)
            identityDao.upsert(identityEntity)
            db.agentRelationDao().insert(
                AgentRelationEntity(
                    daughterId        = allocatedId,
                    motherCharacterId = daughterData.motherCharacterId,
                )
            )
            daughterRepo.updateDaughterCharacterId(
                motherCharacterId  = daughterData.motherCharacterId,
                daughterCharacterId = allocatedId,
            )
            ZLog.i("ChatViewModel", "A-6: agent_relation 初始行已插入 daughterId=$allocatedId")
        },
    )

    private var currentCharacterId = -1

    // A-1 修复：将 relForHeader 从 ChatScreen 迁移至 ViewModel
    private val _relForHeader = MutableStateFlow<RelationshipEntity?>(null)
    val relForHeader: StateFlow<RelationshipEntity?> = _relForHeader.asStateFlow()
    private var replyJob: Job? = null
    // P2 修复：保存 init() 中启动的 flow collector Job，每次 init 先取消上一次的，避免叠加
    private var observeJobs: List<Job> = emptyList()

    /**
     * L5 修复：受孕机制 AI 门3判定（judgeFertileWindowIntent）冷却。
     * shouldEvaluateFertileWindowConsent 通过后，门3 AI 调用每条消息都会触发，
     * 排卵期内剧情尚未发展到最后一步时会频繁消耗 LLM token。
     * 此 map 记录上次 AI 判定的时间戳，同一角色在 [FERTILE_JUDGE_COOLDOWN_MS] 内只判定一次。
     * key = characterId，value = 上次判定时间戳（ms）
     */
    private val lastFertileJudgeAtMap = mutableMapOf<Int, Long>()
    private val FERTILE_JUDGE_COOLDOWN_MS = 5 * 60 * 1000L  // 5 分钟冷却

    /**
     * 问题1修复：1-6 号角色关键词兜底触发链路（PregnancyTriggerManager.checkTrigger() +
     * evaluateConsent()）的跨轮"待定触发"标记。
     *
     * D2 判定链结构：checkTrigger() 扫描 AI 刚说完的回复文本是否命中触发词
     * （AI 回复写库后调用）；evaluateConsent() 在用户下一条消息发送时执行完整
     * 判定链（同意/拒绝/模糊）。两者跨越两轮消息，需要一个轻量的跨轮标记
     * 把"上一轮 AI 回复命中了触发词"这个事实带到下一轮用户发消息时。
     *
     * 用内存 Map 而非落库：这条链路本身是"关键词兜底"（轻权重、可丢失重来的
     * 辅助判定），与 lastFertileJudgeAtMap 同一定位——ViewModel 销毁重建（如
     * 切后台被回收）时丢失该标记，最坏情况只是错过一次触发窗口，不影响
     * pregnancyState/characterState 等核心数据正确性，不需要为此新增 Room 字段。
     *
     * key = characterId（恒为 1..6，checkTrigger() 内部已过滤女儿角色），
     * value = true 表示上一轮 AI 回复命中触发词，本轮用户消息应送入
     * evaluateConsent() 判定；判定完成后（无论结果如何）立即清除，
     * 避免同一次触发被连续多轮重复判定。
     */
    private val pendingKeywordTriggerMap = mutableMapOf<Int, Boolean>()

    fun init(characterId: Int) {
        // P1-10-4 修复：切换角色时必须同时取消上一次的 replyJob，
        // 否则旧 replyJob 完成后会把旧角色的回复写入新角色的 UI 状态，
        // 且 isTyping 被两个协程同时操控导致状态混乱。
        replyJob?.cancel()
        // P2 修复：取消上一次 init 残留的 collector，避免叠加导致重复处理
        observeJobs.forEach { it.cancel() }
        currentCharacterId = characterId
        loadMessages(characterId)
        registerCharacterTools()

        // 批次C·问题5 修复：分娩到期结算——"进入聊天时"触发路径。
        // 与 ZaijianApp.onCreate() 的 12h 周期兜底轮询互补：用户可能在两次轮询
        // 之间打开聊天页，此时若恰好某角色已满 30 天，应立即结算而不是让用户
        // 干等到下一个轮询点。独立协程、独立 try-catch，与角色加载逻辑解耦，
        // 结算失败不影响本次进入聊天页的其余流程。
        viewModelScope.launch(Dispatchers.IO) {
            com.zaijian.zhoumuyun.data.agent.PregnancySettlementScheduler.runImmediateCheck(
                pregnancyRepo = pregnancyRepo,
                memoryRepo    = memoryRepo,
                daughterRepo  = daughterRepo,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            // 防御性保护：daughterRepo.getCharacterConfig() 在女儿数据损坏时会抛
            // DaughterDataException（见 DaughterCharacterEntity.toDaughterCharacterData()
            // 的校验规则）。写库端（DaughterCharacterGenerator.parseAndValidate()）和
            // updateStateLayer() 两条写入路径均已补齐同款 key 存在性校验，正常流程下
            // 不应再产生这类坏数据；这里的 try-catch 是最后一道防线，避免万一真的
            // 出现损坏数据时，打开聊天页面直接让协程崩溃——降级为 character=null，
            // 走后面 uiState 的既有"角色不存在"处理路径，而不是让用户看到崩溃。
            val char = DefaultCharacters.find { it.id == characterId }
                ?: try {
                    daughterRepo.getCharacterConfig(characterId)
                } catch (e: DaughterDataException) {
                    ZLog.e("ChatViewModel", "characterId=$characterId 女儿数据损坏，无法加载", e)
                    null
                }
            _uiState.update { it.copy(
                character   = char,
                // UI M3 修复：初始化时从 PresenceEngine 缓存读取当前心情，后续由 stripMoodTag 路径实时更新。
                currentMood = presenceEngine.getCachedPresence(characterId)?.mood,
                // 切换角色时先清空背景图，避免短暂显示上一个角色的背景；
                // backgroundUriFlow 会立即重新订阅并推送当前角色的值。
                backgroundImageUri = null,
            ) }

            // B-6 修复：死状态2补偿——
            // 进程被杀时机恰好在 saveDaughter() 之后、onIdentityRegister 回调之前，
            // daughter_character 行有完整 JSON 但 daughterCharacterId 为 null。
            // 此时 getCharacterConfig() 反查不到这行，character 仍为 null。
            // 每次打开母亲角色聊天界面时检查一次，发现死状态则重新执行注册步骤。
            // onIdentityRegister 内部全部是幂等操作（upsert / INSERT IGNORE），
            // 重复执行无副作用，只会在 DaughterIdAllocator 里多消耗一个号码
            // （旧号在 character_identity 留孤儿行，不影响任何查询，可忽略）。
            if (characterId in 1..6 || characterId >= 1000) {
                try {
                    val raw = daughterRepo.getByMother(characterId)
                    if (raw != null
                        && raw.daughterCharacterId == null
                        && raw.identityJson.isNotBlank()
                        && raw.stateLayerJson.isNotBlank()
                        && raw.customEnumsJson.isNotBlank()
                    ) {
                        ZLog.w(
                            "ChatViewModel",
                            "B-6: 检测到死状态2（母亲=$characterId），重新执行 onIdentityRegister"
                        )
                        val daughterData = raw.toDaughterCharacterData()
                        val allocatedId = daughterIdAllocator.allocate()
                        val identityEntity = daughterData.toCharacterIdentityEntity(allocatedId)
                        identityDao.upsert(identityEntity)
                        db.agentRelationDao().insert(
                            com.zaijian.zhoumuyun.data.db.entity.AgentRelationEntity(
                                daughterId        = allocatedId,
                                motherCharacterId = daughterData.motherCharacterId,
                            )
                        )
                        daughterRepo.updateDaughterCharacterId(
                            motherCharacterId   = characterId,
                            daughterCharacterId = allocatedId,
                        )
                        ZLog.i(
                            "ChatViewModel",
                            "B-6: 补偿注册完成，daughterId=$allocatedId"
                        )
                    }
                } catch (e: Exception) {
                    ZLog.e("ChatViewModel", "B-6: 补偿注册失败", e)
                }
            }
        }
        // P2 修复：三个 collector 用 listOf(...) 保存到 observeJobs，下次 init 会先 cancel
        observeJobs = listOf(
            // 订阅 PresenceEngine 主动消息流，用于前台实时呈现
            viewModelScope.launch {
                presenceEngine.proactiveMessageFlow.collect { msg ->
                    if (msg.characterId == currentCharacterId) {
                        val charName = _uiState.value.character?.name ?: "她"
                        _uiState.update { it.copy(pendingProactiveMessage = "「${charName}」：${msg.text}") }
                    }
                }
            },
            // A-1 修复：关系状态订阅移入 ViewModel，加 flowOn(IO)
            viewModelScope.launch {
                db.relationshipDao()
                    .observeFrom("user")
                    .map { list -> list.firstOrNull { it.toId == characterId.toString() } }
                    .flowOn(Dispatchers.IO)
                    .collect { _relForHeader.value = it }
            },
            // Avatar 同步修复：监听 identity.avatarUrl，用户上传头像后实时更新聊天界面头像
            viewModelScope.launch {
                identityDao.observeById(characterId)
                    .flowOn(Dispatchers.IO)
                    .collectLatest { entity ->
                        val url = entity?.avatarUrl?.takeIf { it.isNotBlank() } ?: return@collectLatest
                        _uiState.update { state ->
                            state.copy(character = state.character?.copy(avatarUrl = url))
                        }
                    }
            },
            // 聊天背景图：订阅当前角色的背景配置（URI + 取景偏移/缩放），
            // 用户换图或调整取景后实时更新
            viewModelScope.launch {
                chatBgStore.configFlow(characterId)
                    .flowOn(Dispatchers.IO)
                    .collect { config ->
                        _uiState.update {
                            it.copy(
                                backgroundImageUri = config?.uri,
                                backgroundOffsetX   = config?.offsetX ?: 0f,
                                backgroundOffsetY   = config?.offsetY ?: 0f,
                                backgroundScale     = config?.scale ?: 1f,
                            )
                        }
                    }
            },
        )
    }

    private fun loadMessages(characterId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val msgs = messageDao.getByCharacter(characterId)
            _uiState.update { it.copy(messages = msgs.map { it.toChatMessage() }.toImmutableList()) }
        }
    }

    fun notifyFileImported(fileName: String, absolutePath: String) {
        if (currentCharacterId < 0) return
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    characterId = currentCharacterId,
                    role = "system",
                    content = "用户导入了一个文件：$fileName（路径：$absolutePath）",
                    createdAt = System.currentTimeMillis(),
                )
            )
            loadMessages(currentCharacterId)
        }
    }

    fun setChatMode(mode: ChatMode) {
        _uiState.update { it.copy(chatMode = mode) }
    }

    /**
     * 用户刚从相册选完背景图（尚未裁剪）：先记录到 pendingBackgroundCropUri，
     * 触发 UI 弹出 AvatarCropDialog(shape = FULL_SCREEN)，不直接写入
     * 持久化存储——真正的取景参数要等用户在裁剪弹窗里拖拽/缩放并确认后
     * 才通过 confirmChatBackgroundCrop 一并写入。
     */
    fun requestChatBackgroundCrop(uri: String) {
        _uiState.update { it.copy(pendingBackgroundCropUri = uri) }
    }

    /** 用户在裁剪弹窗中点击「取消」，放弃本次换背景 */
    fun cancelChatBackgroundCrop() {
        _uiState.update { it.copy(pendingBackgroundCropUri = null) }
    }

    /**
     * 用户在 AvatarCropDialog 中确认裁剪：写入 URI + 归一化偏移/缩放，
     * 三者作为一个整体存储，保证聊天页读到的取景参数始终跟对应的图片
     * 是同一次操作产出的（不会出现"图还是老的、偏移却是新的"错位）。
     */
    fun confirmChatBackgroundCrop(uri: String, offsetX: Float, offsetY: Float, scale: Float) {
        val charId = currentCharacterId
        if (charId < 0) return
        _uiState.update { it.copy(pendingBackgroundCropUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            chatBgStore.setBackgroundConfig(
                charId,
                com.zaijian.zhoumuyun.data.datastore.ChatBackgroundConfig(
                    uri     = uri,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    scale   = scale,
                )
            )
        }
    }

    /** 设置当前角色的聊天背景图（URI 字符串，来自系统图片选择器）。
     *  保留供旧调用点兼容；新代码请走 requestChatBackgroundCrop → 裁剪弹窗
     *  → confirmChatBackgroundCrop 的完整流程，才能让用户拖动缩放取景。 */
    fun setChatBackground(uri: String) {
        val charId = currentCharacterId
        if (charId < 0) return
        viewModelScope.launch(Dispatchers.IO) {
            chatBgStore.setBackgroundUri(charId, uri)
        }
    }

    /** 清除当前角色的聊天背景图，恢复默认渐变背景 */
    fun clearChatBackground() {
        val charId = currentCharacterId
        if (charId < 0) return
        viewModelScope.launch(Dispatchers.IO) {
            chatBgStore.clearBackground(charId)
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || currentCharacterId < 0) return
        val provider = ProviderManager.instance.activeProvider ?: run {
            _uiState.update { it.copy(isApiKeyMissing = true) }
            return
        }

        replyJob?.cancel()
        replyJob = viewModelScope.launch(Dispatchers.IO) {
            // B-1 修复：try-finally 保证无论 catch 块外的 DAO / engine 调用抛出何种异常，
            // isTyping 都能被置回 false，避免发送按钮永久禁用。
            // （CancellationException 会越过 catch 直接到 finally，再向上 rethrow，
            //   结构化并发不受影响。）
            try {
                val userMsgId = UUID.randomUUID().toString()
                messageDao.insert(
                    MessageEntity(
                        id = userMsgId,
                        characterId = currentCharacterId,
                        role = "user",
                        content = text,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                loadMessages(currentCharacterId)

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

                val character = _uiState.value.character ?: return@launch
                // Bug2-fix: 过滤非法 role，只保留 user/assistant 两种合法值
                // - role = "system" 的内部控制消息直接跳过（notifyFileImported / Phase28 AGENT_MSG / ROUNDTABLE_TRIGGER）
                // - role = characterId.toString()（如 "1","2"）的主动消息映射为 "assistant"
                val messages = messageDao.getByCharacter(currentCharacterId).mapNotNull { msg ->
                    when (msg.role) {
                        "user", "assistant" -> LLMMessage(role = msg.role, content = msg.content)
                        "system" -> null  // 内部控制消息，不进入对话上下文
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
                val identityEntity = identityDao.getById(currentCharacterId)
                val toolDesc = AgentToolRegistry.buildToolDescriptionBlock()
                val relationshipSnapshot = relationshipEngine.buildPromptSnapshot(currentCharacterId)
                // 待办4：COMPANION 模式下可传入 excludeDomain=MemoryDomain.WORK
                val chatMode = _uiState.value.chatMode

                // ── 补全 Memory Layer（核心 Bug：之前从未查询，一直是空列表）──
                // coreMemories：每次对话必注入的高重要度记忆（≤5条）
                // relevantMemories：按本条用户消息做 FTS 检索的相关记忆（≤8条）
                val coreMemories     = memoryRepo.getCoreMemories(currentCharacterId)
                val relevantMemories = memoryRepo.searchRelevant(
                    characterId = currentCharacterId,
                    query       = text,
                    limit       = 8,
                )

                // ── 补全 State Layer（presence 在场状态早就在算，只是没接进 prompt）──
                var presenceSnap = presenceEngine?.getCachedPresence(currentCharacterId)

                // ── 补全 AgentPlan Layer（角色自己写的进化方案）──
                val activePlan = agentPlanDao.getActive(currentCharacterId)
                val agentPlanBlock = activePlan?.let {
                    PromptOrchestrator.buildAgentPlanBlock(it.title, it.content)
                } ?: ""

                // ── 补全 LearningGoal Layer（isLocked=true 的能力规则，按目标分组）──
                val activeGoals = learningGoalDao.getActive(currentCharacterId)
                val rulesByGoal = activeGoals.associate { goal ->
                    goal.title to memoryRepo
                        .getLockedRules(currentCharacterId, goal.id)
                        .map { it.content }
                }
                val ruleLayerBlock = PromptOrchestrator.buildRuleLayerBlock(rulesByGoal)

                // ── 补全怀孕状态（注意：PregnancyDao 没有 getLatest，
                //    正确入口是 PregnancyRepository.getPregnancy，与 RoundtableViewModel
                //    现有写法保持一致；未怀孕时返回默认 PregnancyState，非 null）──
                var pregnancyState = pregnancyRepo.getPregnancy(currentCharacterId)

                // ══════════════════════════════════════════════════════════════
                // 问题1修复：1-6 号角色关键词兜底触发链路 —— ② evaluateConsent()
                //
                // 消费上一轮 checkTrigger()（后置分析协程块内）留下的 pending 标记：
                // 若上一轮 AI 回复命中了触发词，本轮用户消息（text）就是"回应"，
                // 送入 evaluateConsent() 走完整判定链（突破检测 → 同意/拒绝/模糊）。
                //
                // 范围限定：显式用 currentCharacterId in 1..6 判断是否需要走这条链路，
                // 不依赖 evaluateConsent() 内部的 isDaughterMother() 检查来兜底——
                // isDaughterMother(characterId) = characterId in setOf(1..6) ||
                // characterId >= 1000，对 1-6 和女儿（>=1000）一视同仁地放行，
                // 只排除 7-9 号等真正无关角色。也就是说 evaluateConsent() 内部
                // 那道检查本身并不会把女儿角色挡在外面；本调用点的 pending 标记
                // 只可能在 checkTrigger()（同样已用 currentCharacterId in 1..6 限定，
                // 见下方后置分析协程块）里被设置为 true，双重限定叠加才保证了
                // 女儿（id>=1000）的角色永远不会走到这条 1-6 号专属链路，而不是内部
                // isDaughterMother() 检查单独起作用。
                //
                // 判定完成后立即清除 pending 标记（无论结果如何），避免同一次
                // 触发被后续多轮重复判定——evaluateConsent() 是"一次性问答"语义，
                // 不是持续轮询状态。
                var pregnancyTriggerPromptPatch = ""
                if (currentCharacterId in 1..6 && pendingKeywordTriggerMap[currentCharacterId] == true) {
                    pendingKeywordTriggerMap.remove(currentCharacterId)
                    try {
                        val triggerResult = pregnancyTriggerManager.evaluateConsent(
                            characterId  = currentCharacterId,
                            userText     = text,
                            isPregnant   = pregnancyState.isPregnant,
                        )
                        when (triggerResult) {
                            is com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult.Triggered -> {
                                // 怀孕已在 PregnancyTriggerManager 内部落库，这里重新读一次
                                // 保证本轮 buildSystemPrompt 用的是怀孕后的最新状态，
                                // 不会因为用的是本函数顶部读的旧快照而漏掉"刚怀孕"这一状态变化。
                                pregnancyState = pregnancyRepo.getPregnancy(currentCharacterId)
                            }
                            is com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult.FertileButFailed -> {
                                pregnancyTriggerPromptPatch = triggerResult.immediatePromptPatch
                            }
                            is com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult.BreakthroughA -> {
                                pregnancyTriggerPromptPatch = triggerResult.promptPatch
                            }
                            is com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult.BreakthroughB -> {
                                pregnancyTriggerPromptPatch = triggerResult.promptPatch
                            }
                            is com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult.Rejected,
                            is com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult.AmbiguousRejected,
                            is com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult.WrongPhase,
                            is com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult.NotTriggered,
                            is com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult.Miscarried -> {
                                // Rejected/AmbiguousRejected/WrongPhase：副作用已在 manager 内部落库
                                // （desireStrength/emotionalSuppression 数值更新），无需即时 Prompt 注入，
                                // 下一轮 State Layer 渲染时数值会自然体现在角色状态描述里。
                                // NotTriggered：isPregnant 为 true 时的兜底分支，理论上不应发生
                                // （pending 标记只在未怀孕时由 checkTrigger 设置），安全忽略。
                                // Miscarried：evaluateConsent() 内部判定链不会产出此分支
                                // （只有 triggerMiscarriage() 会），穷尽 when 分支需要，安全忽略。
                            }
                        }
                    } catch (e: Exception) {
                        ZLog.w("ChatViewModel", "evaluateConsent 判定链异常（不影响主流程）", e)
                    }
                }

                // ── 补全 characterState（深层状态：desireStrength/emotionalSuppression等，
                //    之前 PromptOrchestrator 参数存在但函数体内完全未使用，现已实装）──
                var characterState = characterStateRepo.getState(currentCharacterId)

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
                if (currentCharacterId >= 1000) {
                    try {
                        val daughterData = daughterRepo.getCharacterData(currentCharacterId)
                        if (daughterData != null) {
                            daughterStateLayer = daughterData.stateLayer
                            daughterCustomEnums = daughterData.customEnums
                            if (characterState == CharacterStateLayer()) {
                                characterState = daughterData.stateLayer.toCharacterStateLayer()
                            }
                        }
                    } catch (e: Exception) {
                        ZLog.w("ChatViewModel", "女儿状态数据查询失败，State Layer 渲染将回退到通用描述", e)
                    }
                }

                // ── presence fallback：缓存为空时主动计算一次，结果写入缓存供后续轮次复用 ──
                if (presenceSnap == null) {
                    presenceSnap = presenceEngine?.refreshPresence(currentCharacterId, characterState)
                }

                // ── 补全 miscarriageAftermathPatch（D2.6 流产后5天内跨周期悲伤余波）──
                // ChatViewModel 是一对一私聊场景，isOneOnOne 恒为 true。
                //
                // 问题4修复：pressureScale 此前硬编码 1.0f，现读取
                // PregnancyPressureDataStore.pregnancyPressureScaleFlow 的当前值
                // （用户可调节的孕期压力系数，默认 1.0f，与硬编码时代行为完全一致，
                // 用户未主动调整过设置时零行为变化）。用 .first() 读一次而非持续
                // collect——这是"发消息"这一次性事件里的单次快照读取，不是需要
                // 响应式更新的 UI 状态，与 BriefingViewModel 里 lastOpenAtFlow.first()
                // 的用法同一模式。safeData() 已在 DataStore 层兜底 IOException，
                // 这里不需要额外 try-catch。
                //
                // 问题29修复：miscarriageDaysAgo() 内部由 shouldInjectMiscarriageContext()
                // 调用，此前用其默认参数 System.currentTimeMillis()，与"整轮统一时间快照"
                // 的既有约定（本函数其余各处落库/判断均使用同一个 now）不一致。这里
                // 统一取一次 now，显式透传给 shouldInjectMiscarriageContext()/
                // shouldInjectFailureContext()，避免同一轮内因为函数调用先后跨越了
                // 毫秒边界而产生难以复现的细微不一致（例如流产"第5天窗口"边界判断）。
                val pressureScale = pregnancyPressureDataStore.pregnancyPressureScaleFlow.first()
                val nowSnapshot   = System.currentTimeMillis()

                val miscarriageAftermathPatch = pregnancyTriggerManager.shouldInjectMiscarriageContext(
                    pregnancyState = pregnancyState,
                    userText       = text,
                    isOneOnOne     = true,
                    pressureScale  = pressureScale,
                    now            = nowSnapshot,
                ) ?: ""

                // ── 补全 failureContextPatch（D2.5 跨周期失败背景情绪，问题3修复）──
                // 与上面的流产余波同构：四重门控（含随机概率+48h冷却）全部通过才
                // 返回非空文案，否则静默返回 ""，零行为可见变化。门控通过后必须
                // 调用 markFailureContextInjected() 落库更新 lastFailureInjectedAt，
                // 否则下一轮 48h 冷却检查会一直读到旧时间戳，实质上失去冷却效果——
                // 这一步不能漏，是本条修复"真正生效"而非"看起来接上了"的关键。
                val failureContextPatch = pregnancyTriggerManager.shouldInjectFailureContext(
                    pregnancyState = pregnancyState,
                    userText       = text,
                    isOneOnOne     = true,
                    pressureScale  = pressureScale,
                    now            = nowSnapshot,
                ) ?: ""
                if (failureContextPatch.isNotEmpty()) {
                    try {
                        pregnancyTriggerManager.markFailureContextInjected(currentCharacterId, nowSnapshot)
                    } catch (e: Exception) {
                        // 落库失败不应该丢弃这一轮已经生成好的 Prompt 文案（用户体验
                        // 优先于"下一轮冷却计时是否精确"），但要记录日志——如果这个
                        // 异常反复出现，说明 lastFailureInjectedAt 的持久化链路本身
                        // 有问题，需要单独排查，不属于本次修复范围。
                        ZLog.w("ChatViewModel", "markFailureContextInjected 失败（不影响本轮文案）", e)
                    }
                }

                // ── 补全 routinePressurePatch（常规压力 Prompt，问题3/4修复）──
                // 无门控、每轮都渲染：基于当前 characterState 的 desireStrength/
                // emotionalSuppression 数值分档给出背景文案（PromptOrchestrator.kt
                // 注释原本设想的"D2 正常同意分支"专属场景实际过窄——只要角色当前
                // 有渴望/压抑数值积累，无论是通过 1-6 号关键词链路还是女儿 AI 判定
                // 链路产生的，都应该体现在日常 Prompt 里，不应该只在恰好命中判定
                // 分支的那一轮才出现，否则绝大多数轮次这个数值状态对 LLM 完全不可见。
                // 与 failureContextPatch 的区别：那是"事件驱动、有冷却"的一次性情绪
                // 涟漪，这是"持续存在、无冷却"的背景压力描述，两者不互斥、可以同轮共存。
                val routinePressurePatch = if (
                    characterState.motivationalState.desireStrength > 0 ||
                    characterState.hiddenState.emotionalSuppression > 0
                ) {
                    pregnancyTriggerManager.buildRoutinePromptPatch(
                        desireStrength        = characterState.motivationalState.desireStrength,
                        emotionalSuppression  = characterState.hiddenState.emotionalSuppression,
                    )
                } else {
                    ""
                }

                // ══════════════════════════════════════════════════════════════
                // 补全 d3QuestionPatch（D3 孕期共设 · 槎位问答状态机）
                // 三重门控（与 D3AskAttemptDataStore 文档枚举的三个 gate 完全一致）：
                //   ① 孕期状态不符（非母亲角色 / 未怀孕 / 第三代女儿——没有第四代可问）→ 不触发
                //   ② 本轮开始时已有挂起问题等待回答 → 本轮不追加新题，先处理回答
                //      （这一轮如果刚答完，也不在同一轮立刻追问下一题，留一轮呼吸空间，
                //       下一轮 pending 已清空后才会问下一题）
                //   ③ 全部 6 个槎位已锁定 → D3 阶段结束（D4 生成器消费锁定答案，超出本次范围）
                //
                // 注意（问题1修复引入）：pregnancyState 是 var，若本轮用户消息刚好命中
                // 上方 evaluateConsent() 判定链且结果为 Triggered（1-6 号关键词兜底触发
                // 怀孕成功），这里读到的已经是刷新后 isPregnant=true 的最新值——即"这条
                // 消息让她怀孕"和"同一轮就开始问 D3 第一题"是同一轮发生的，属于预期内的
                // 时序改进，不是脏读；1-6 号角色此前从未有过 D3 问答（因为从未真正触发
                // 过怀孕，见问题1原始描述），这里是该链路接入后自然获得的新行为。
                // ══════════════════════════════════════════════════════════════
                val isD3Eligible = isDaughterMother(currentCharacterId) &&
                    pregnancyState.isPregnant &&
                    (currentCharacterId < 1000 || !daughterRepo.isThirdGeneration(currentCharacterId))

                val pendingQuestionAtTurnStart = if (isD3Eligible) {
                    pregnancyAnswerRepo.getPendingQuestion(currentCharacterId)
                } else null

                // 步骤①：若上一轮有挂起问题，本轮用户消息可能是在回答——交给 AI 判定意图
                if (pendingQuestionAtTurnStart != null) {
                    val intent = pregnancyAnswerIntentDetector.isAnswering(
                        pendingQuestionText = pendingQuestionAtTurnStart.questionText,
                        userReply           = text,
                    )
                    if (intent == IntentResult.YES) {
                        val answeredType = runCatching {
                            PregnancyQuestionType.valueOf(pendingQuestionAtTurnStart.questionType)
                        }.getOrNull()
                        if (answeredType != null) {
                        val answeredSlot = pendingQuestionAtTurnStart.slotIndex
                        val recordResult = pregnancyAnswerRepo.recordAnswer(
                            motherCharacterId  = currentCharacterId,
                            pregnancyStartedAt = pregnancyState.pregnancyStartedAt ?: 0L,
                            questionType       = answeredType,
                            slotIndex          = answeredSlot,
                            questionText       = pendingQuestionAtTurnStart.questionText,
                            answerText         = text,
                        )
                        pregnancyAnswerRepo.clearPendingQuestion(currentCharacterId)
                        if (recordResult is SlotRecordResult.Locked || recordResult is SlotRecordResult.ForceLocked) {
                            // 槎位锁定后清掉该槎位的提问次数计数（D3AskAttemptDataStore 文档约定）
                            d3AskAttemptStore.clear(currentCharacterId, answeredType, answeredSlot)
                            // ── 检查3：D4 触发门控 ──────────────────────────────────
                            // 本次锁定后立即检查全部 6 个槎位是否均已锁定；
                            // 是 → 读取全部锁定答案，异步启动 D4 人格生成器。
                            // 查询和生成都是 IO 密集型，放进独立协程，不阻塞当前对话轮。
                            val allLocked = pregnancyAnswerRepo.isAllSlotsLocked(currentCharacterId)
                            if (allLocked) {
                                val motherChar = _uiState.value.character
                                if (motherChar != null) {
                                    val lockedAnswers = com.zaijian.zhoumuyun.data.repository.PregnancyAnswerRepository
                                        .ALL_SLOTS
                                        .mapNotNull { slot ->
                                            val ans = pregnancyAnswerRepo.getLockedAnswer(
                                                motherCharacterId = currentCharacterId,
                                                questionType      = slot.questionType,
                                                slotIndex         = slot.slotIndex,
                                            )
                                            if (ans != null) {
                                                slotKey(slot.questionType, slot.slotIndex) to ans
                                            } else null
                                        }.toMap()
                                    viewModelScope.launch(Dispatchers.IO) {
                                        try {
                                            daughterGenerator.generateForMother(
                                                motherConfig  = motherChar,
                                                lockedAnswers = lockedAnswers,
                                            )
                                        } catch (e: Exception) {
                                            ZLog.e("ChatViewModel", "D4 generateForMother 失败", e)
                                            // 问题31修复：原 take(60) 过短，LLM 返回的 JSON 解析失败
                                            // 诊断信息（如"Expected STRING but was BEGIN_OBJECT at
                                            // path $.xxx"这类）经常超过60字，关键部分会被截断。
                                            // 放宽到 200 字——Snackbar 能容纳的展示长度足够，且不是
                                            // 无限制拼接（避免异常消息里偶发的超长堆栈片段撑爆提示条）。
                                            // 完整异常已在上一行 ZLog.e() 里带 e 参数记录，这里只是
                                            // 放宽 UI 摘要的信息量，不依赖这行日志做诊断依据。
                                            // 同一模式出现 3 处（另两处见 D5→D4 第三代生成、手动重试
                                            // 入口），已一并同步修改，保持三处行为一致。
                                            _uiState.update { it.copy(pendingDaughterGenerationError = "女儿生成失败：${e.message?.take(200) ?: "未知错误"}") }
                                        }
                                    }
                                }
                            }
                        }
                        // StillOpen / FirstAnswer：保留计数，槎位仍开放，等下一次门控窗口再问
                        } else {
                            // 枚举值非法，清除脏数据避免后续反复触发
                            pregnancyAnswerRepo.clearPendingQuestion(currentCharacterId)
                        }
                    }
                    // intent == NO：用户没在回答这个问题，挂起问题原样保留，不消耗，不清空
                }

                // 步骤②：仅当本轮开始时没有挂起问题，才考虑问下一个槎位的新题
                var d3QuestionPatch = ""
                // (questionType, slotIndex) —— 本轮若确实问出口，AI 回复生成后用于落库
                var d3PendingAsk: Pair<PregnancyQuestionType, Int>? = null
                if (isD3Eligible && pendingQuestionAtTurnStart == null) {
                    val nextSlot = pregnancyAnswerRepo.nextUnlockedSlot(currentCharacterId)
                    if (nextSlot != null) {
                        val attemptNumber = d3AskAttemptStore.nextAttemptNumber(
                            characterId  = currentCharacterId,
                            questionType = nextSlot.questionType,
                            slotIndex    = nextSlot.slotIndex,
                        )
                        val patchText = D3TriggerContent.blockFor(
                            characterId   = currentCharacterId,
                            questionType  = nextSlot.questionType,
                            slotIndex     = nextSlot.slotIndex,
                            attemptNumber = attemptNumber,
                        )
                        if (patchText != null) {
                            d3QuestionPatch = "[D3 孕期共设 · 本轮提问指令]\n$patchText"
                            d3PendingAsk = nextSlot.questionType to nextSlot.slotIndex
                        }
                        // patchText == null：文案库缺该组合（角色/槎位/次数），理论上不应发生
                        // （TRIGGER_DATA 应已全覆盖），保守地不注入，不记 pending
                    }
                    // nextSlot == null：6 个槎位全部锁定，D3 阶段结束，不注入
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
                val unreportedJob = workflowRepo.findUnreported(currentCharacterId).firstOrNull()
                val workflowRecapPatch = if (unreportedJob != null) {
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

                // ── 检查5b：D5 关系阶段快照（State Layer 之後注入）──────────────
                // 仅对女儿角色（characterId >= 1000）查询；普通母亲角色直接用空字符串，零开销。
                val agentRelationSnapshot = if (currentCharacterId >= 1000) {
                    agentRelationEngine.buildPromptSnapshot(currentCharacterId)
                } else ""

                // ── Task Layer（Phase 12）：工作台任务跟踪 ──────────────────────
                // 取该角色当前 RUNNING / PENDING 任务（最多 5 条），组装为 taskLayerBlock。
                // 无活跃任务时返回空字符串，buildSystemPrompt 内部跳过注入，零开销。
                val activeTasks = taskRepo.getByCharacter(currentCharacterId, limit = 5)
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
                )

                val config = LLMConfig(
                    model = "",
                    maxTokens = 4000,
                    temperature = 0.8f,
                    stream = true,
                )

                // P1-3 修复：streamingContent 不再写入 _uiState
                _uiState.update { it.copy(isTyping = true) }
                _streamingContent.value = ""
                val fullReply = StringBuilder()

                try {
                    ToolCallInterceptor.streamWithTools(
                        provider     = provider,
                        messages     = messages,
                        systemPrompt = systemPrompt,
                        config       = config,
                    ).collect { event ->
                        when (event) {
                            is StreamEvent.TextDelta -> {
                                fullReply.append(event.text)
                                // Fix-MoodLeak（zaijian）：display-only 剥离，fullReply 保持原样供后续解析。
                                // [mood:xxx] 固定出现在末尾；流式过程中标签可能还没打完，
                                // stripPartialMoodTagForDisplay 同时兜住"完整标签"和"半截标签"两种情况。
                                //
                                // H1 修复：原实现每个 token 都调 stripPartialMoodTagForDisplay，
                                // 内部跑两次正则（MOOD_TAG_REGEX + PARTIAL_MOOD_TAG_REGEX），
                                // 且每次都先 fullReply.toString() 创建新 String，整个流式过程累计 O(n²) 复杂度。
                                //
                                // 优化策略：绝大多数 token 正文中不含 '[' 字符，直接输出。
                                // 只有末尾出现 '[' 时（可能是 mood 标签前缀），才触发完整的剥离逻辑，
                                // 将正则调用频率从"每个 token"降低到"接近末尾的少数 token"。
                                //
                                // Fix-ThinkingLeak（zaijian）：新增 [thinking:...] 标签剥离，接入同一条
                                // display-only 管道。与 mood 不同，thinking 标签可能出现在正文任意位置
                                // （说完一段台词又插入一段思考，再继续说台词），不是只在末尾出现一次，
                                // 所以 stripTagsForDisplay 内部会先对全文做一次"剥离所有已闭合 thinking 标签"
                                // 的 replace，这一步在 thinking 标签出现后的每个 token 上都会重新扫描全文，
                                // 相当于放弃了 H1 修复追求的"绝大多数 token 零正则"最优路径——但仅限于
                                // 单条消息内确实包含 thinking 标签的情况，消息长度通常在几千字符量级，
                                // 实测不构成可感知卡顿，暂不做更复杂的增量解析。
                                val fullText = fullReply.toString()
                                val displayText = if ('[' in fullText) {
                                    stripTagsForDisplay(fullText)
                                } else {
                                    fullText
                                }
                                // P1-3 修复：streamingContent 不再写入 _uiState（此处的双写是高频路径，
                                // 每个 token 触发一次 _uiState 更新 → ChatScreen 整屏重组）
                                _streamingContent.value = displayText
                            }
                            is StreamEvent.ToolStarted -> {
                                if (event.hint != null) {
                                    _uiState.update { it.copy(streamingHint = event.hint) }
                                }
                            }
                            is StreamEvent.ToolDone -> {
                                _uiState.update { it.copy(streamingHint = null) }
                            }
                            is StreamEvent.RoundDone -> Unit
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // B-1 修复：CancellationException 必须 rethrow，保证结构化并发正确传播。
                    // replyJob?.cancel() 触发取消时协程库通过此异常信号通知协程停止，
                    // 若被吞掉协程会误认为正常结束，viewModelScope 的取消机制失效。
                    throw e
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = "回复时遇到问题：${e.message?.take(60)}") }
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
                val (afterThinking, parsedThinking) = stripThinkingTag(fullReply.toString().trimEnd())
                val (cleanReply, parsedMood) = stripMoodTag(afterThinking)
                if (parsedMood != null) {
                    presenceEngine?.updateMoodFromReply(currentCharacterId, parsedMood)
                    _uiState.update { it.copy(currentMood = parsedMood) }
                }
                if (cleanReply.isNotBlank()) {
                    val assistantMsg = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = currentCharacterId,
                        role = "assistant",
                        content = cleanReply,
                        createdAt = System.currentTimeMillis(),
                        thinkingText = parsedThinking,
                    )
                    messageDao.insert(assistantMsg)
                    // H2 修复（race消除）：insert是挂起函数，到这里落库已完成。
                    // 先做乐观更新——把刚落库的消息同步追加到内存list，
                    // 后续读 _uiState.value.messages 保证能看到这条新消息。
                    // loadMessages 保留作异步兜底（防止其他路径写库后内存未同步）。
                    val latestMessages = (_uiState.value.messages + assistantMsg.toChatMessage())
                        .toImmutableList()
                    _uiState.update { it.copy(messages = latestMessages) }
                    loadMessages(currentCharacterId)
                    // P1-10-3 修复：原先两次 applyDelta（onConversationEnd 基础 delta +
                    // HeuristicRelTracker 语义 delta）会产生两条 RELATIONSHIP_CHANGED 事件，
                    // 导致同一轮对话的摩擦系数被重复写入。改为将两组 delta 合并后一次性提交。
                    // ── A-7：单聊场景关系数值随对话积累增长（原 onConversationEnd 逻辑内联）──
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
                    relationshipEngine.applyDelta(
                        fromId        = "user",
                        toId          = currentCharacterId.toString(),
                        delta         = mergedDelta,
                        sourceEventId = java.util.UUID.randomUUID().toString(),
                    )
                }
                // workflowRecapPatch 已在 buildSystemPrompt 前计算；
                // 此处在 AI 回复写库完成后，才把任务标记为已播报，
                // 确保即使回复中途异常也不会丢失本次 recap 机会。
                if (unreportedJob != null) {
                    workflowRepo.markReported(unreportedJob.id)
                }

                // P1-10-1 修复：把所有后置 LLM 分析（评分卡、受孕窗口判定、D5 升阶、D3 didAsk）
                // 移入独立的 viewModelScope.launch，使 replyJob 的 finally 块能立即清零
                // isTyping，避免用户在后置 LLM 分析期间（可能数秒）看到输入框持续禁用。
                // 后置分析捕获所有需要的不可变局部变量（cleanReply、text、currentCharacterId 等），
                // 不依赖任何 replyJob 的可变状态。
                val capturedCharId   = currentCharacterId
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
                        // evaluationEngine / distillationEngine 为 lazy，此处是首次可能访问点；
                        // provider 为 null 时 lazy 内部抛 ISE，用 runCatching 兜住，不阻断主流程。
                        runCatching {
                            val engine = evaluationEngine ?: return@runCatching  // provider 未配置，跳过
                            // 附带修复（"有仓库不用"）：本文件199行已有 messageDao 字段
                            // （MessageRepository 包装），此处不再裸取 db.messageDao()。
                            val assistantMsgId = messageDao
                                .getByCharacter(capturedCharId)
                                .lastOrNull { it.role == "assistant" }
                                ?.id ?: return@runCatching
                            val sessionId = engine.maybeCreateSession(
                                characterId  = capturedCharId,
                                replyContent = capturedReply,
                                messageId    = assistantMsgId,
                            ) ?: return@runCatching   // 门控未命中（冷却中 / 回复太短 / 无目标）
                            // Agent B 评审（同一协程串行，内部已有 withContext(IO)）
                            val goal = learningGoalDao.getActive(capturedCharId).firstOrNull()
                                ?: return@runCatching
                            engine.runAgentReview(
                                sessionId    = sessionId,
                                goalTitle    = goal.title,
                                replyContent = capturedReply,
                                userMessage  = capturedText,
                            )
                            // 评审完成后从 DB 读取 reportText / agentScore，推送到 UI
                            val session = db.evaluationSessionDao().getById(sessionId)
                                ?: return@runCatching
                            if (session.reportText != null) {
                                _uiState.update {
                                    it.copy(
                                        pendingEvaluationSessionId = sessionId,
                                        pendingEvaluationReport    = session.reportText,
                                        pendingAgentScore          = session.agentScore,
                                    )
                                }
                            }
                        }.onFailure { e ->
                            ZLog.w("ChatViewModel", "评分链路异常（不影响主流程）", e)
                        }

                        // ══════════════════════════════════════════════════════════════
                        // 问题1修复：1-6 号角色关键词兜底触发链路 —— ① checkTrigger()
                        //
                        // AI 回复（capturedReply）写库完成后，扫描本轮回复文本是否命中
                        // CharacterTriggerKeywords 关键词表。命中则把 pending 标记写入
                        // pendingKeywordTriggerMap，供下一轮用户发消息时的 evaluateConsent()
                        // 调用点（sendMessage 顶部，pregnancyState 读取之后）消费。
                        //
                        // 范围限定：显式用 capturedCharId in 1..6 判断，不依赖
                        // checkTrigger() 内部的 isDaughterMother() 检查来挡住女儿角色——
                        // isDaughterMother() 对 1-6 和女儿（>=1000）都返回 true，真正让
                        // 女儿角色查不到关键词的是 CharacterTriggerKeywords[characterId]
                        // 这个 map 本身只有 1-6 号的 key（女儿角色查表落空，?: 兜底返回
                        // triggered=false）。这是"关键词表恰好未收录女儿"造成的结果，
                        // 不是 isDaughterMother() 主动排除女儿的结果——如果以后有人往
                        // CharacterTriggerKeywords 里补充了 1000+ 的 key，checkTrigger()
                        // 内部不会拦住它。本调用点的 capturedCharId in 1..6 限定，才是
                        // 这条链路唯一可靠生效的边界，必须保留，不能因为"内部好像也判断了"
                        // 就省略。
                        //
                        // 仅在角色未怀孕时才有意义（已怀孕不需要再判定是否触发怀孕）。
                        // capturedPregnancyState 捕获的是本轮 pregnancyState（var）在
                        // evaluateConsent 调用点之后的值——若本轮用户消息恰好通过
                        // evaluateConsent() 触发了怀孕（Triggered 分支），这里能看到
                        // 刷新后的 isPregnant=true，正确跳过本次 checkTrigger 标记；
                        // 不是"函数顶部读取的原始快照"。
                        if (capturedCharId in 1..6 && !capturedPregnancyState.isPregnant) {
                            try {
                                val trigger = pregnancyTriggerManager.checkTrigger(capturedCharId, capturedReply)
                                if (trigger.triggered) {
                                    pendingKeywordTriggerMap[capturedCharId] = true
                                }
                            } catch (e: Exception) {
                                ZLog.w("ChatViewModel", "checkTrigger 扫描异常（不影响主流程）", e)
                            }
                        }

                        // ── 1.1 受孕窗口同意对话框触发链路 ────────────────────────────
                        // 三重门控顺序：门1+门2（shouldEvaluateFertileWindowConsent）→ 门3（AI语义判定）
                        val shouldEval = pregnancyTriggerManager.shouldEvaluateFertileWindowConsent(capturedCharId)
                        if (shouldEval) {
                            // L5 修复：加冷却保护，避免同一角色每条消息都触发一次 AI 判定 LLM 调用。
                            val now = System.currentTimeMillis()
                            val lastJudgeAt = lastFertileJudgeAtMap[capturedCharId] ?: 0L
                            val cooldownPassed = (now - lastJudgeAt) >= FERTILE_JUDGE_COOLDOWN_MS
                            if (cooldownPassed) {
                                val recentTurns = _uiState.value.messages
                                    .takeLast(10)
                                    .mapNotNull { msg ->
                                        when (msg.role) {
                                            "user", "assistant" -> LLMMessage(role = msg.role, content = msg.content)
                                            else -> null
                                        }
                                    }
                                val intentPassed = pregnancyTriggerManager.judgeFertileWindowIntent(recentTurns)
                                lastFertileJudgeAtMap[capturedCharId] = System.currentTimeMillis()
                                if (intentPassed) {
                                    val character = _uiState.value.character
                                    if (character != null) {
                                        val dialogText = pickCharacterDialogText(capturedCharId, character.name)
                                        _uiState.update {
                                            it.copy(
                                                fertileWindowConsentDialogText = dialogText,
                                                fertileWindowCharacterName     = character.name,
                                                // 问题14修复：与 dialogText/characterName 同批写入，
                                                // 三者共享同一个 capturedCharId 快照，保证一致性。
                                                fertileWindowCharacterId       = capturedCharId,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // ── 检查5a：D5 关系阶段引擎 ─────────────────────────────────
                        // 整段包 try-catch：内部涉及多次女儿数据读取
                        // （isThirdGeneration/getCharacterConfig/isAllSlotsLocked 等），
                        // 理论上走到这里的 daughterId 数据应该完好（她能正常对话，
                        // 说明数据本身可用），但作为最后一道防线，任一环节异常
                        // 都不应该连累后面完全独立的"D3 didAsk 判定"逻辑。
                        try {
                            if (capturedCharId >= 1000) {
                                val transitionResult = agentRelationEngine.onInteractionComplete(
                                    daughterId    = capturedCharId,
                                    userText      = capturedText,
                                    assistantText = capturedReply,
                                )
                                if (transitionResult is StageTransitionResult.Upgraded) {
                                    ZLog.i(
                                        "ChatViewModel",
                                        "D5 升阶：daughterId=${transitionResult.daughterId} → ${transitionResult.newStage}",
                                    )
                                    if (transitionResult.newStage == AgentRelationStage.STAGE_3_SEEKING) {
                                        val daughterId = transitionResult.daughterId
                                        val isAlreadyGen3 = daughterRepo.isThirdGeneration(daughterId)
                                        if (!isAlreadyGen3) {
                                            val allLocked = pregnancyAnswerRepo.isAllSlotsLocked(daughterId)
                                            if (allLocked) {
                                                val motherConfig = daughterRepo.getCharacterConfig(daughterId)
                                                if (motherConfig != null) {
                                                    val lockedAnswers = PregnancyAnswerRepository.ALL_SLOTS
                                                        .mapNotNull { slot ->
                                                            val ans = pregnancyAnswerRepo.getLockedAnswer(
                                                                motherCharacterId = daughterId,
                                                                questionType      = slot.questionType,
                                                                slotIndex         = slot.slotIndex,
                                                            )
                                                            if (ans != null) {
                                                                slotKey(slot.questionType, slot.slotIndex) to ans
                                                            } else null
                                                        }.toMap()
                                                    viewModelScope.launch(Dispatchers.IO) {
                                                        try {
                                                            daughterGenerator.generateForMother(
                                                                motherConfig  = motherConfig,
                                                                lockedAnswers = lockedAnswers,
                                                            )
                                                        } catch (e: Exception) {
                                                            ZLog.e("ChatViewModel", "D5→D4 第三代 generateForMother 失败", e)
                                                            _uiState.update { it.copy(pendingDaughterGenerationError = "女儿生成失败：${e.message?.take(200) ?: "未知错误"}") }
                                                        }
                                                    }
                                                } else {
                                                    ZLog.w("ChatViewModel", "D5 STAGE_3：daughterId=$daughterId 无法取得 CharacterConfig，跳过第三代生成")
                                                }
                                            } else {
                                                ZLog.i("ChatViewModel", "D5 STAGE_3：daughterId=$daughterId 槽位尚未全锁，等待 D3 收敛")
                                            }
                                        } else {
                                            ZLog.i("ChatViewModel", "D5 STAGE_3：daughterId=$daughterId 已是第三代，不再生成下一代")
                                        }
                                    }
                                }
                            }
                        } catch (e: DaughterDataException) {
                            ZLog.e("ChatViewModel", "D5 升阶检查中女儿数据异常，daughterId=$capturedCharId", e)
                        }

                        // ── D3 didAsk 判定：本轮注入了提问指令，确认 AI 是否真的把问题问出口 ──
                        // Fix（token 优化）：d3QuestionPatch 含完整指令块，截取前 200 字。
                        if (capturedD3Pending != null) {
                            val (askedType, askedSlot) = capturedD3Pending
                            val didAsk = pregnancyAnswerIntentDetector.didAsk(
                                expectedQuestionTopic = capturedD3Patch.take(200),
                                aiReply               = capturedReply,
                            )
                            if (didAsk == IntentResult.YES) {
                                pregnancyAnswerRepo.recordPendingQuestion(
                                    motherCharacterId = capturedCharId,
                                    questionType      = askedType,
                                    slotIndex         = askedSlot,
                                    questionText      = capturedReply,
                                )
                                d3AskAttemptStore.recordAsked(capturedCharId, askedType, askedSlot)
                            }
                            // didAsk == NO：AI 没把问题问出口，不记 pending、不增加计数
                        }
                    } // end viewModelScope.launch (后置 LLM 分析)
                } // end if (capturedReply.isNotBlank())
            } finally {
                // B-1 修复：finally 保证任何路径（正常完成、网络异常、CancellationException）
                // 都能重置 isTyping，避免发送按钮永久禁用。
                // P1-10-1 修复：后置 LLM 分析已移至独立 launch，finally 在流式结束后立即执行。
                // P1-3 修复：streamingContent 不再写入 _uiState
                _uiState.update { it.copy(isTyping = false) }
                _streamingContent.value = null
            }
        }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }

    /** 用户在受孕窗口同意对话框点击"同意"或"拒绝"后调用。
     *  先关闭对话框 UI，再异步执行底层逻辑（写库、触发怀孕或拒绝效果）。
     *
     *  问题14修复：使用弹窗展示时捕获的 [ChatUiState.fertileWindowCharacterId]
     *  快照，而不是本函数被调用这一刻的 [currentCharacterId]——如果用户在
     *  弹窗展示期间切换了角色再点击按钮，currentCharacterId 已经指向新角色，
     *  但弹窗内容（dialogText/characterName）仍是旧角色的，必须保证三者
     *  作用在同一个角色上，不能用切换后的新角色 ID 错误地调用
     *  proceedAfterDialogConsent()。
     *
     *  问题2修复：拿到结果后调用 markFertileWindowConsentAsked(true) 落库
     *  消费"已问过"标记（PregnancyTriggerManager.proceedAfterDialogConsent()
     *  文档明确要求调用方做这一步）——无论用户同意还是拒绝，本排卵期窗口
     *  都已经"问过"了，避免同一排卵期重复弹窗。此前只依赖 ViewModel 内存级
     *  lastFertileJudgeAtMap 冷却，进程重启后失效；现在落库后
     *  shouldEvaluateFertileWindowConsent() 里的 fertileWindowConsentAsked
     *  检查才会真正生效。 */
    fun onFertileWindowDialogResult(accepted: Boolean) {
        val targetCharId = _uiState.value.fertileWindowCharacterId
        _uiState.update {
            it.copy(
                fertileWindowConsentDialogText = null,
                fertileWindowCharacterName     = "",
                fertileWindowCharacterId       = -1,
            )
        }
        if (targetCharId < 0) {
            // 理论上不应发生（弹窗展示时必然同批写入了合法 ID），
            // 防御性兜底：没有有效目标角色时不做任何底层调用。
            ZLog.w("ChatViewModel", "onFertileWindowDialogResult: fertileWindowCharacterId 无效，跳过")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pregnancyTriggerManager.proceedAfterDialogConsent(
                    characterId  = targetCharId,
                    accepted     = accepted,
                )
            } catch (e: Exception) {
                ZLog.e("ChatViewModel", "proceedAfterDialogConsent 失败", e)
            } finally {
                // 落库消费标记与 proceedAfterDialogConsent() 的成败无关：
                // 无论底层判定成功、失败还是抛异常，弹窗都已经展示给用户看过、
                // 用户也已经点击过按钮了，本排卵期窗口客观上已经"问过"，
                // 都不应该在同一排卵期再次弹窗。放在 finally 里保证这一点
                // 不会因为上面 try 块异常而被跳过。
                try {
                    pregnancyRepo.markFertileWindowConsentAsked(targetCharId, true)
                } catch (e: Exception) {
                    ZLog.e("ChatViewModel", "markFertileWindowConsentAsked 失败", e)
                }
            }
        }
    }
    fun clearApiKeyMissingFlag() { _uiState.update { it.copy(isApiKeyMissing = false) } }
    fun clearDaughterGenerationError() { _uiState.update { it.copy(pendingDaughterGenerationError = null) } }

    /**
     * 重试女儿生成（D4/D5）。
     * 当 generateForMother 失败后，槎位已全部锁定但女儿未生成，
     * 用户可通过此方法手动重试，避免永久死锁。
     */
    fun retryDaughterGeneration() {
        val motherChar = _uiState.value.character ?: return
        _uiState.update { it.copy(pendingDaughterGenerationError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 从 DB 读取用户已锁定的答案（与首次 D4 生成逻辑保持一致），
                // 避免传 emptyMap() 导致 LLM 收不到用户偏好（名字/世界观等）
                val lockedAnswers = com.zaijian.zhoumuyun.data.repository.PregnancyAnswerRepository
                    .ALL_SLOTS
                    .mapNotNull { slot ->
                        val ans = pregnancyAnswerRepo.getLockedAnswer(
                            motherCharacterId = currentCharacterId,
                            questionType      = slot.questionType,
                            slotIndex         = slot.slotIndex,
                        )
                        if (ans != null) slotKey(slot.questionType, slot.slotIndex) to ans
                        else null
                    }.toMap()
                daughterGenerator.generateForMother(
                    motherConfig  = motherChar,
                    lockedAnswers = lockedAnswers,
                )
            } catch (e: Exception) {
                ZLog.e("ChatViewModel", "重试 D4 generateForMother 失败", e)
                _uiState.update { it.copy(pendingDaughterGenerationError = "女儿生成失败：${e.message?.take(200) ?: "未知错误"}") }
            }
        }
    }
    fun clearProactiveMessage() {
        _uiState.update { it.copy(pendingProactiveMessage = null) }
        // 主动消息已写入 DB（persistAndNotify 保证），刷新消息列表让气泡即时出现
        loadMessages(currentCharacterId)
    }
    fun dismissDistillResult() { _uiState.update { it.copy(pendingDistillResult = null) } }
    fun submitEvaluationScore(score: Int) {
        val sessionId = _uiState.value.pendingEvaluationSessionId ?: return
        // 先清除 UI 弹窗，避免用户等待期间重复触发
        _uiState.update { it.copy(
            pendingEvaluationSessionId = null,
            pendingEvaluationReport    = null,
            pendingAgentScore          = null,
        ) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val engine = evaluationEngine ?: run {
                    ZLog.w("ChatViewModel", "submitEvaluationScore: provider 未初始化，跳过打分")
                    return@launch
                }
                // ① 提交用户分数，完成 Session（SCORED）
                val compositeScore = engine.submitUserScore(
                    sessionId = sessionId,
                    userScore = score,
                )
                if (compositeScore == null) {
                    ZLog.w("ChatViewModel", "submitEvaluationScore: Session $sessionId 不存在或状态不符")
                    return@launch
                }

                // ② 查询 Session 关联的 goalId，触发提炼判断
                val session = db.evaluationSessionDao().getById(sessionId) ?: return@launch
                val goalId = session.goalId ?: return@launch

                val distillResult = try {
                    distillationEngine?.maybeDistill(
                        characterId = currentCharacterId,
                        goalId      = goalId,
                    )
                } catch (e: Exception) {
                    ZLog.w("ChatViewModel", "maybeDistill 异常（不影响打分结果）", e)
                    null
                }

                // ③ 若触发了提炼，通知 UI 展示结果
                if (distillResult?.triggered == true) {
                    _uiState.update { it.copy(
                        pendingDistillResult = DistillResult(
                            triggered        = true,
                            newlyLockedCount = distillResult.newlyLockedCount,
                            goalTitle        = distillResult.goalTitle,
                            progressDelta    = distillResult.progressDelta,
                        )
                    ) }
                }
            } catch (e: Exception) {
                ZLog.w("ChatViewModel", "submitEvaluationScore 异常", e)
            }
        }
    }
    fun skipEvaluation() {
        _uiState.update { it.copy(
            pendingEvaluationSessionId = null,
            pendingEvaluationReport = null,
            pendingAgentScore = null,
        ) }
    }
    fun setKnowledgeInjectMode(mode: KnowledgeInjectMode) {
        _uiState.update { it.copy(knowledgeInjectMode = mode) }
    }
    /** MANUAL 模式下：设置一次性注入标志位，下一条消息发送时消费 */
    fun triggerManualKnowledgeInject() {
        _uiState.update { it.copy(manualKnowledgeTriggerPending = true) }
    }
    fun clearMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.deleteByCharacter(currentCharacterId)
            loadMessages(currentCharacterId)
        }
    }
    fun setActiveProject(projectId: String?) {
        viewModelScope.launch {
            val project = if (projectId != null) projectRepo.getById(projectId) else null
            _uiState.update { it.copy(activeProjectId = projectId, activeProjects = if (project != null) persistentListOf(project) else persistentListOf()) }
        }
    }

    private fun registerCharacterTools() {
        val providerFn = { ProviderManager.instance.activeProvider }
        AgentToolRegistry.registerAll(
            PlanSaveTool(agentPlanDao = agentPlanDao, characterId = { currentCharacterId }),
            MemoryWriteTool(memoryRepository = memoryRepo, characterId = { currentCharacterId }),
            MemoryQueryTool(memoryRepo = memoryRepo, characterId = { currentCharacterId }),
            GoalUpdateTool(goalDao = learningGoalDao, characterId = { currentCharacterId }),
            WorkflowStartTool(
                context = getApplication(),
                workflowRepository = workflowRepo,
                characterId = { currentCharacterId },
            ),
            // 问题12修复：CI/CD（提交代码→编译→下载APK）是项目级操作，与"当前
            // 正在跟哪个角色聊天"无关；原先绑定 currentCharacterId 会导致任务完成
            // 通知（见下方 workflowRecapPatch 的 findUnreported(currentCharacterId)
            // 查询）只能在开启任务时那个角色的聊天窗口里被看到——如果用户开完任务
            // 后切换到别的角色聊天，通知永远不会出现。改为固定绑定 -1（项目里已有
            // 的"非绑定特定角色"约定，ZaijianApp.kt 静态注册大量工具用同一约定），
            // 这样无论用户在跟哪个角色聊天，都能看到 CI/CD 任务的完成播报。
            CiCdStartTool(
                context = getApplication(),
                githubConfigStore = githubConfigStore,
                db = db,
                workflowJobDao = db.workflowJobDao(),
                workflowStepResultDao = db.workflowStepResultDao(),
                characterId = { -1 },
            ),
            // ── 2.3 工作台任务跟踪修复：补上"开始/更新/完成/取消"任务的入口 ──
            TaskStartTool(taskRepo = taskRepo, characterId = { currentCharacterId }),
            TaskUpdateTool(taskRepo = taskRepo, characterId = { currentCharacterId }),
            TaskCompleteTool(taskRepo = taskRepo, characterId = { currentCharacterId }),
            TaskCancelTool(taskRepo = taskRepo, characterId = { currentCharacterId }),
            // 问题39修复：Soul/Memory/User 三模块 6 个工具的实例化代码此前在本文件
            // 和 ZaijianApp.kt 各写一份（仅 characterId 闭包不同），改用
            // AgentToolRegistry.registerSoulMemoryUserTools() 统一封装，本处传
            // currentCharacterId 覆盖 ZaijianApp 里的 -1 静态占位——覆盖时机、
            // 覆盖原因（updateSoulNote 等否则永远打到 characterId=-1 的行）均不变，
            // 只是不再各自手写 6 行几乎相同的构造代码。
            //
            // 注意：AgentToolRegistry.registerAll(...) 这个 vararg 调用只接受
            // AgentTool 实例，registerSoulMemoryUserTools() 是扩展函数不是
            // AgentTool，因此在 registerAll(...) 调用结束后单独调用（见下方）。
            // ── Fix-#1: 覆盖 ZaijianApp 里 characterIdProvider={-1} 的静态注册 ──
            // schedule_create / schedule_list / heartbeat_set / heartbeat_update /
            // heartbeat_delete 这5个工具在 ZaijianApp 里以 -1 注册，导致任务写入错误
            // 角色行，observeAndNotifyResults() 找不到 characterId=-1 的角色，
            // 推送永久跳过。此处用当前会话的 currentCharacterId 动态覆盖。
            // 问题8修复：补上 calendarSync/context，否则覆盖注册后这两个参数
            // 回落到构造函数默认值 null，日历同步与 WorkManager 精确调度失效。
            ScheduleCreateTool(
                scheduleRepository  = scheduleRepo,
                characterIdProvider = { currentCharacterId },
                calendarSync = calendarSync,
                context = getApplication(),
            ),
            ScheduleListTool(
                scheduleRepository  = scheduleRepo,
                characterIdProvider = { currentCharacterId },
            ),
            HeartbeatSetTool(
                context             = getApplication(),
                characterIdProvider = { currentCharacterId },
            ),
            HeartbeatUpdateTool(
                context             = getApplication(),
                characterIdProvider = { currentCharacterId },
            ),
            HeartbeatDeleteTool(
                context             = getApplication(),
                characterIdProvider = { currentCharacterId },
            ),
            // U2 延伸修复：覆盖 ZaijianApp 里 characterIdProvider={-1} 的静态注册——
            // 否则提醒触发时通知上的「查看日程」按钮永远指向 personal_schedule/-1，
            // 查不到角色，按钮形同失效。
            ReminderTool(
                context             = getApplication(),
                characterIdProvider = { currentCharacterId },
            ),
            // 问题24修复：SelfReflectTool（self_reflect）/RuleReviewTool（rule_review）
            // 在 DataVisTools.registerDataVisTools() 里以 characterIdProvider={-1} 静态
            // 注册，此前和 schedule_create 等一样从未在 ChatViewModel 里被覆盖注册。
            // execute() 内部虽然优先读 params["__character_id"]（LLM 工作流标签注入时
            // 能拿到正确角色），但私聊场景下 LLM 输出的 <tool:self_reflect .../> 标签
            // 通常不带 __character_id 属性（不是所有触发路径都走工作流注入），此时
            // fallback 到 characterIdProvider() 就会拿到 -1——反思记忆写入 characterId=-1
            // 这一不存在的行，查询该角色 WORK 域记忆时永远查不到；rule_review 同理会审视
            // 到 charId=-1 下的规则（大概率为空），而不是当前正在聊天的角色的规则。
            // 与 schedule_create/heartbeat_* 等既有 Fix-#1 覆盖注册同一模式，用
            // currentCharacterId 动态覆盖。
            SelfReflectTool(
                providerFn          = providerFn,
                memoryDao           = memoryDao,
                memoryRepo          = memoryRepo,
                characterIdProvider = { currentCharacterId },
            ),
            RuleReviewTool(
                providerFn          = providerFn,
                memoryDao           = memoryDao,
                characterIdProvider = { currentCharacterId },
            ),
        )
        // 问题39修复：见上方 registerAll(...) 内注释——统一封装的 Soul/Memory/User
        // 6 个工具注册，在此处传 currentCharacterId 覆盖 ZaijianApp 里的 -1 占位。
        AgentToolRegistry.registerSoulMemoryUserTools(
            identityDao = identityDao,
            characterId = { currentCharacterId },
        )
        providerFn()?.let { p ->
            AgentToolRegistry.register(
                RuleDistillTool(provider = p, memoryRepo = memoryRepo, goalDao = learningGoalDao, characterId = { currentCharacterId })
            )
        }
    }

    /**
     * display 专用：在 stripMoodTag 之上再兜一层——流式过程中标签可能还没打完
     * （比如刚输出到 "...正文\n[mo"，闭合的 `]` 还没到），完整正则匹配不上，
     * 这层负责把这种"半截标签"也从展示文本里砍掉，避免裸字符闪现。
     * 只在文本末尾尝试匹配 "[mood" 的任意前缀（包括换行/空格开头的情况），
     * 不影响已经完整闭合的标签（那部分由 stripMoodTag 处理）。
     */
    private fun stripPartialMoodTagForDisplay(text: String): String {
        val (afterFullStrip, _) = stripMoodTag(text)
        // afterFullStrip 与 text 不同说明已经命中完整标签，直接返回即可。
        if (afterFullStrip != text) return afterFullStrip
        // 否则检查末尾是否是 "[mood" 的某个前缀（如 "[", "[m", "[mo", "[moo", "[mood", "[mood:" 等），
        // 前面允许有换行/空格。
        val tailMatch = PARTIAL_MOOD_TAG_REGEX.find(text) ?: return text
        return text.substring(0, tailMatch.range.first).trimEnd()
    }

    /**
     * display 专用总入口（Fix-ThinkingLeak）：thinking 标签剥离 + mood 标签剥离（含半截）一起跑。
     *
     * 与 mood 的关键差异——mood 固定出现在全文最后一行，只需锚定字符串末尾；
     * thinking 标签可能出现在正文任意位置（角色说一段台词、插一段思考、再说一段台词），
     * 所以：
     *   1) 先对全文做一次 THINKING_TAG_REGEX.replace，剥掉所有"已经完整闭合"的 thinking 标签；
     *   2) 再跑原有的 stripPartialMoodTagForDisplay，处理末尾的 mood 标签（完整或半截）；
     *   3) 最后检查处理完前两步后的文本末尾，是否残留一个"尚未闭合"的半截 thinking 前缀
     *      （如 "...台词\n[think"）——由于模型在标签闭合前不会产出标签之后的新内容，
     *      未闭合的 thinking 标签在任意时刻的流式文本里必然只会出现在末尾，
     *      用与 PARTIAL_MOOD_TAG_REGEX 相同的"锚定末尾"策略即可覆盖，不需要更复杂的状态机。
     */
    private fun stripTagsForDisplay(fullText: String): String {
        val afterThinking = THINKING_TAG_REGEX.replace(fullText, "")
        val afterMood = stripPartialMoodTagForDisplay(afterThinking)
        val tailMatch = PARTIAL_THINKING_TAG_REGEX.find(afterMood) ?: return afterMood
        return afterMood.substring(0, tailMatch.range.first).trimEnd()
    }

    private fun MessageEntity.toChatMessage() = ChatMessage(
        id = id,
        role = role,
        content = content,
        createdAt = createdAt,
        exportedFileJson = exportedFileJson,
        thinkingText = thinkingText,
    )

    /**
     * 剥离回复末尾的 `[mood:情绪词]` 系统标记，返回（净文本, 解析出的 MoodType?）。
     *
     * 背景（Fix-MoodLeak）：COMPANION / NARRATIVE 模式的 Output Layer
     * （见 PromptOrchestrator.COMPANION_OUTPUT_CONSTRAINTS /
     * NARRATIVE_OUTPUT_CONSTRAINTS）要求 LLM 在正文末尾另起一行输出
     * `[mood:情绪词]`，注释明确写"系统使用，不展示给用户"，但此前全项目
     * 没有任何代码解析或剥离它——用户在这两种模式下每条回复末尾都会看到
     * 裸露的 `[mood:平静]` 这类内部标记，且 PresenceEngine.updateMoodFromReply()
     * 已经写好却从未被调用。
     *
     * 设计为 ChatViewModel 的成员函数（而非顶层/companion 纯函数）只是因为
     * 调用点都在本类内部；逻辑本身不依赖任何实例状态，纯文本处理。
     *
     * @return Pair(去除标签后的文本, 解析出的 MoodType；未命中或无标签则为 null)
     */
    private fun stripMoodTag(reply: String): Pair<String, MoodType?> {
        val match = MOOD_TAG_REGEX.find(reply) ?: return reply to null
        val cleaned = reply.substring(0, match.range.first).trimEnd()
        val moodWord = match.groupValues[1].trim()
        return cleaned to parseMoodType(moodWord)
    }

    /**
     * 剥离正文中所有 `[thinking:...]` 内心推理标签，返回（净文本, 合并后的思考内容或null）。
     *
     * 背景（Fix-ThinkingLeak）：Output Layer（PromptOrchestrator.WORK_OUTPUT_CONSTRAINTS /
     * COMPANION_OUTPUT_CONSTRAINTS / NARRATIVE_OUTPUT_CONSTRAINTS）新增规则，要求 LLM 把
     * 内心推理、收到的指令原文、工具调用意图包进 `[thinking:...]` 标签，不能直接写进标签外的
     * 正文——这套"结构化标记 + 客户端剥离"完全复用 stripMoodTag 已验证过的技术路径。
     *
     * 与 mood 标签的两点差异：
     *   1) mood 固定只出现一次、且在全文最后一行；thinking 可能出现在正文任意位置，
     *      也可能出现不止一次（模型分几段记录思考），所以用 findAll + replace 而非单次 find。
     *   2) mood 命中即返回单个 MoodType；thinking 命中多段时按出现顺序拼接，中间用空行分隔，
     *      交给想法卡片作为一段完整内容展示。
     *
     * @return Pair(去除所有 thinking 标签后的正文, 按出现顺序拼接的思考内容；未命中则为 null)
     */
    private fun stripThinkingTag(reply: String): Pair<String, String?> {
        val matches = THINKING_TAG_REGEX.findAll(reply).toList()
        if (matches.isEmpty()) return reply to null
        val thoughts = matches.joinToString(separator = "\n\n") { it.groupValues[1].trim() }.trim()
        // 标签原地整段抠掉后，原来标签独占一行的位置会留下多余空行，
        // 压缩连续 3 行及以上空行为 1 个空行，避免正文出现大片空白。
        val cleaned = THINKING_TAG_REGEX.replace(reply, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return cleaned to thoughts.ifBlank { null }
    }

    companion object {
        /**
         * 单次请求按字符预算保留的历史消息（字符总量上限）。
         * DeepSeek V4 Flash 1M 上下文：450,000 字符 ≈ 300,000 token（占上限约 28.6%）。
         * 从最新消息往前累积，超出预算时停止，保证最近对话优先保留。
         */
        private const val MAX_HISTORY_CHARS = 450_000

        // Fix-MoodLeak：匹配末尾的 [mood:词] 或 [mood：词]（中英文冒号都兼容），
        // 允许标签前有空行/空格，允许标签后有少量尾随空白。
        private val MOOD_TAG_REGEX = Regex("""\[mood[:：]\s*([^\[\]]+?)\s*]\s*$""")

        // display 专用：末尾出现 "[mood" 任意未闭合前缀时也要隐藏，前面允许换行/空格。
        // 例如 "[", "[m", "[mo", "[moo", "[mood", "[mood:", "[mood:平" 等streaming中间态。
        private val PARTIAL_MOOD_TAG_REGEX = Regex("""\s*\[m(o(o(d(\s*[:：]\s*[^\[\]]*)?)?)?)?$""")

        // Fix-ThinkingLeak：匹配 [thinking:...] 或 [thinking：...]（中英文冒号都兼容），
        // DOT_MATCHES_ALL 允许标签内部跨行（内心推理可能是多行文本）。
        // 与 MOOD_TAG_REGEX 一样限定内部不含方括号，避免贪婪匹配跨越多个标签、误吞中间的
        // 正文——已知局限：如果模型的思考内容本身包含方括号（较少见），会在此处截断，
        // 可接受，不为这个边缘情况引入更复杂的括号计数解析。
        private val THINKING_TAG_REGEX = Regex(
            """\[thinking[:：]\s*([^\[\]]*?)\s*]""",
            RegexOption.DOT_MATCHES_ALL,
        )

        // display 专用：末尾出现 "[thinking" 任意未闭合前缀时也要隐藏，前面允许换行/空格，
        // 用法与 PARTIAL_MOOD_TAG_REGEX 同一思路——见 stripTagsForDisplay 顶部注释。
        private val PARTIAL_THINKING_TAG_REGEX = Regex(
            """\s*\[t(h(i(n(k(i(n(g(\s*[:：]\s*[^\[\]]*)?)?)?)?)?)?)?)?$"""
        )

        /**
         * Fix⑥：COMPANION_OUTPUT_CONSTRAINTS / NARRATIVE_OUTPUT_CONSTRAINTS 里
         * 给 LLM 的情绪词枚举（中文）与 MoodType（英文枚举）做对应——
         * 两边在设计时本就是按顺序一一对应的（平静/专注/好奇/满足/担忧/兴奋/疲惫/沉思
         * ↔ CALM/FOCUSED/CURIOUS/SATISFIED/CONCERNED/EXCITED/TIRED/REFLECTIVE），
         * 只是从未写出这层转换代码。未命中时返回 null（不更新 mood，静默忽略，
         * 不让一次格式异常的 LLM 输出打断主流程）。
         */
        private fun parseMoodType(word: String): MoodType? = when (word) {
            "平静" -> MoodType.CALM
            "专注" -> MoodType.FOCUSED
            "好奇" -> MoodType.CURIOUS
            "满足" -> MoodType.SATISFIED
            "担忧" -> MoodType.CONCERNED
            "兴奋" -> MoodType.EXCITED
            "疲惫" -> MoodType.TIRED
            "沉思" -> MoodType.REFLECTIVE
            else   -> null
        }
    }
}
