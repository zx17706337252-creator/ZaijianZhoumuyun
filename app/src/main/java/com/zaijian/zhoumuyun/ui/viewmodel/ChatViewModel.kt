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
import com.zaijian.zhoumuyun.data.agent.StreamEvent
import com.zaijian.zhoumuyun.data.agent.TaskCancelTool
import com.zaijian.zhoumuyun.data.agent.TaskCompleteTool
import com.zaijian.zhoumuyun.data.agent.TaskStartTool
import com.zaijian.zhoumuyun.data.agent.TaskUpdateTool
import com.zaijian.zhoumuyun.data.agent.ToolCallInterceptor
import com.zaijian.zhoumuyun.data.agent.NarrativeMemoryClearTool
import com.zaijian.zhoumuyun.data.agent.NarrativeMemoryUpdateTool
import com.zaijian.zhoumuyun.data.agent.SoulClearTool
import com.zaijian.zhoumuyun.data.agent.SoulUpdateTool
import com.zaijian.zhoumuyun.data.agent.UserImpressionClearTool
import com.zaijian.zhoumuyun.data.agent.UserImpressionUpdateTool
import com.zaijian.zhoumuyun.data.agent.WorkflowStartTool
import com.zaijian.zhoumuyun.data.agent.ScheduleCreateTool
import com.zaijian.zhoumuyun.data.agent.ScheduleListTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatSetTool
import com.zaijian.zhoumuyun.data.agent.ReminderTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatUpdateTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatDeleteTool
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
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.isDaughterMother
import com.zaijian.zhoumuyun.data.model.toDaughterCharacterData
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
import com.zaijian.zhoumuyun.data.model.pickCharacterDialogText
import com.zaijian.zhoumuyun.data.repository.ProjectRepository
import com.zaijian.zhoumuyun.data.repository.TaskRepository
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    val streamingContent: String? = null,
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
    private val memoryEngine get() = container.memoryEngine
    private val relationshipEngine get() = container.relationshipEngine
    private val pregnancyRepo get() = container.pregnancyRepo
    private val characterStateRepo get() = container.characterStateRepo
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
    private val agentRelationEngine = AgentRelationEngine(db.agentRelationDao())
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
                val pregnancyState = pregnancyRepo.getPregnancy(currentCharacterId)

                // ── 补全 characterState（深层状态：desireStrength/emotionalSuppression等，
                //    之前 PromptOrchestrator 参数存在但函数体内完全未使用，现已实装）──
                val characterState = characterStateRepo.getState(currentCharacterId)
                // ── presence fallback：缓存为空时主动计算一次，结果写入缓存供后续轮次复用 ──
                if (presenceSnap == null) {
                    presenceSnap = presenceEngine?.refreshPresence(currentCharacterId, characterState)
                }

                // ── 补全 miscarriageAftermathPatch（D2.6 流产后5天内跨周期悲伤余波）──
                // ChatViewModel 是一对一私聊场景，isOneOnOne 恒为 true。
                val miscarriageAftermathPatch = pregnancyTriggerManager.shouldInjectMiscarriageContext(
                    pregnancyState = pregnancyState,
                    userText       = text,
                    isOneOnOne     = true,
                    pressureScale  = 1.0f,
                ) ?: ""

                // ══════════════════════════════════════════════════════════════
                // 补全 d3QuestionPatch（D3 孕期共设 · 槎位问答状态机）
                // 三重门控（与 D3AskAttemptDataStore 文档枚举的三个 gate 完全一致）：
                //   ① 孕期状态不符（非母亲角色 / 未怀孕 / 第三代女儿——没有第四代可问）→ 不触发
                //   ② 本轮开始时已有挂起问题等待回答 → 本轮不追加新题，先处理回答
                //      （这一轮如果刚答完，也不在同一轮立刻追问下一题，留一轮呼吸空间，
                //       下一轮 pending 已清空后才会问下一题）
                //   ③ 全部 6 个槎位已锁定 → D3 阶段结束（D4 生成器消费锁定答案，超出本次范围）
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
                                                "slot_${slot.questionType.name}_${slot.slotIndex}" to ans
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
                                            _uiState.update { it.copy(pendingDaughterGenerationError = "女儿生成失败：${e.message?.take(60) ?: "未知错误"}") }
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
                    miscarriageAftermathPatch = miscarriageAftermathPatch,
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

                _uiState.update { it.copy(isTyping = true, streamingContent = "") }
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
                                val fullText = fullReply.toString()
                                val displayText = if ('[' in fullText) {
                                    stripPartialMoodTagForDisplay(fullText)
                                } else {
                                    fullText
                                }
                                _uiState.update { it.copy(streamingContent = displayText) }
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
                val (cleanReply, parsedMood) = stripMoodTag(fullReply.toString().trimEnd())
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
                                                                "slot_${slot.questionType.name}_${slot.slotIndex}" to ans
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
                                                            _uiState.update { it.copy(pendingDaughterGenerationError = "女儿生成失败：${e.message?.take(60) ?: "未知错误"}") }
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
                _uiState.update { it.copy(isTyping = false, streamingContent = null) }
                _streamingContent.value = null
            }
        }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }

    /** 用户在受孕窗口同意对话框点击"同意"或"拒绝"后调用。
     *  先关闭对话框 UI，再异步执行底层逻辑（写库、触发怀孕或拒绝效果）。 */
    fun onFertileWindowDialogResult(accepted: Boolean) {
        _uiState.update {
            it.copy(fertileWindowConsentDialogText = null, fertileWindowCharacterName = "")
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pregnancyTriggerManager.proceedAfterDialogConsent(
                    characterId  = currentCharacterId,
                    accepted     = accepted,
                )
            } catch (e: Exception) {
                ZLog.e("ChatViewModel", "proceedAfterDialogConsent 失败", e)
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
                        if (ans != null) "slot_${slot.questionType.name}_${slot.slotIndex}" to ans
                        else null
                    }.toMap()
                daughterGenerator.generateForMother(
                    motherConfig  = motherChar,
                    lockedAnswers = lockedAnswers,
                )
            } catch (e: Exception) {
                ZLog.e("ChatViewModel", "重试 D4 generateForMother 失败", e)
                _uiState.update { it.copy(pendingDaughterGenerationError = "女儿生成失败：${e.message?.take(60) ?: "未知错误"}") }
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
            CiCdStartTool(
                context = getApplication(),
                githubConfigStore = githubConfigStore,
                db = db,
                workflowJobDao = db.workflowJobDao(),
                workflowStepResultDao = db.workflowStepResultDao(),
                characterId = { currentCharacterId },
            ),
            // ── 2.3 工作台任务跟踪修复：补上"开始/更新/完成/取消"任务的入口 ──
            TaskStartTool(taskRepo = taskRepo, characterId = { currentCharacterId }),
            TaskUpdateTool(taskRepo = taskRepo, characterId = { currentCharacterId }),
            TaskCompleteTool(taskRepo = taskRepo, characterId = { currentCharacterId }),
            TaskCancelTool(taskRepo = taskRepo, characterId = { currentCharacterId }),
            // ── Fix-ToolWire: 覆盖 ZaijianApp 里 characterId={-1} 的静态注册 ──
            // 这6个工具是人设/叙事记忆/用户印象的读写，必须绑定当前会话角色ID，
            // 否则 updateSoulNote / updateNarrativeMemory / updateUserImpression
            // 全部打到 characterId=-1 的行，永远改不了实际角色的数据。
            SoulUpdateTool(identityDao = identityDao, characterId = { currentCharacterId }),
            SoulClearTool(identityDao = identityDao, characterId = { currentCharacterId }),
            NarrativeMemoryUpdateTool(identityDao = identityDao, characterId = { currentCharacterId }),
            NarrativeMemoryClearTool(identityDao = identityDao, characterId = { currentCharacterId }),
            UserImpressionUpdateTool(identityDao = identityDao, characterId = { currentCharacterId }),
            UserImpressionClearTool(identityDao = identityDao, characterId = { currentCharacterId }),
            // ── Fix-#1: 覆盖 ZaijianApp 里 characterIdProvider={-1} 的静态注册 ──
            // schedule_create / schedule_list / heartbeat_set / heartbeat_update /
            // heartbeat_delete 这5个工具在 ZaijianApp 里以 -1 注册，导致任务写入错误
            // 角色行，observeAndNotifyResults() 找不到 characterId=-1 的角色，
            // 推送永久跳过。此处用当前会话的 currentCharacterId 动态覆盖。
            ScheduleCreateTool(
                scheduleRepository  = scheduleRepo,
                characterIdProvider = { currentCharacterId },
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

    private fun MessageEntity.toChatMessage() = ChatMessage(
        id = id,
        role = role,
        content = content,
        createdAt = createdAt,
        exportedFileJson = exportedFileJson,
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
