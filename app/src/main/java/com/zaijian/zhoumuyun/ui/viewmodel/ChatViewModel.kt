package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.repository.AgentPlanRepository
import com.zaijian.zhoumuyun.data.repository.LearningGoalRepository
import com.zaijian.zhoumuyun.data.agent.CalendarSyncHelper
import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import com.zaijian.zhoumuyun.data.datastore.D3AskAttemptDataStore
import com.zaijian.zhoumuyun.data.datastore.ChatBackgroundDataStore
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.MessageEntity
import com.zaijian.zhoumuyun.data.db.entity.AgentRelationEntity
import com.zaijian.zhoumuyun.domain.AgentRelationEngine
import com.zaijian.zhoumuyun.domain.ChatTagParser
import com.zaijian.zhoumuyun.domain.DistillationEngine
import com.zaijian.zhoumuyun.domain.EvaluationEngine
import com.zaijian.zhoumuyun.domain.MoodType
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.data.manager.DaughterCharacterGenerator
import com.zaijian.zhoumuyun.data.manager.DaughterIdAllocator
import com.zaijian.zhoumuyun.data.model.ChatMode
import com.zaijian.zhoumuyun.data.model.CharacterStateLayer
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.toDaughterCharacterData
import com.zaijian.zhoumuyun.data.model.toCharacterStateLayer
import com.zaijian.zhoumuyun.data.model.toCharacterIdentityEntity
import com.zaijian.zhoumuyun.data.prompt.PromptOrchestrator
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.domain.pregnancy.PregnancyAnswerConsistencyChecker
import com.zaijian.zhoumuyun.domain.pregnancy.PregnancyAnswerIntentDetector
import com.zaijian.zhoumuyun.data.repository.PregnancyAnswerRepository

import com.zaijian.zhoumuyun.data.manager.PregnancyTriggerManager
// S-1 缺口2：ProjectRepository 已迁移至 AppContainer.projectRepo
// S-1 缺口2：TaskRepository 已迁移至 AppContainer.taskRepo
// S-1 缺口2：WorkflowRepository 已迁移至 AppContainer.workflowRepo
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ExportedFile(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val absolutePath: String,
    // 1.3 附件卡片类型区分：非 null 时卡片上多渲染一行提示（如"需用浏览器打开另存"），
    // 用于 docx_gen/pdf_export 这类"委托生成的伪二进制"文件，向后兼容（默认 null 不影响现有文件）。
    val openHint: String? = null,
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
    /**
     * 单文件字段，本轮多个文件类工具调用时只保留最后一个——保留是为了兼容
     * 尚未切换到 exportedFilesJson 的旧读取路径，新代码应优先读 exportedFiles。
     */
    val exportedFileJson: String? = null,
    /**
     * v66（Agent附件下发方案 v2.0 · 1.7 P3）：多文件版本，JSON 数组字符串。
     * null = 该消息没有文件附件；历史消息永远为 null，即使 exportedFileJson 有值。
     */
    val exportedFilesJson: String? = null,
    // Fix-ThinkingLeak：从回复正文剥离出的内心推理/工具调用意图原文，null = 无想法内容。
    val thinkingText: String? = null,
    // v1.36 问题2：从回复正文中圆括号包裹的内容抽取出的心理感受/神态描写，null = 无心理描写。
    val psychText: String? = null,
) {
    @Deprecated("单文件读取路径，历史兼容用；新代码请用 exportedFiles", ReplaceWith("exportedFiles.firstOrNull()"))
    val exportedFile: ExportedFile? get() = exportedFiles.firstOrNull()

    /**
     * v66（1.7 P3）：优先解析 exportedFilesJson（多文件数组）；为空时退化为把
     * exportedFileJson 包成单元素 list——历史消息（只有旧字段有值）不会因为
     * 这次改造丢失已有的文件卡片。两个字段都为 null 时返回空 list。
     */
    val exportedFiles: List<ExportedFile> get() = parseExportedFilesWithFallback(exportedFilesJson, exportedFileJson)
}

/**
 * v66（1.7 P3）：解析文件元数据的共享逻辑，供 ChatMessage.exportedFiles /
 * RoundtableMessage.exportedFiles 共用，避免私聊+圆桌两处各写一份、日后漏改其中一处。
 *
 * 优先用 filesJson（数组，v66 新字段）；为空/解析失败时退化用 legacyJson
 * （单对象，v65 及更早字段）包成单元素 list。
 */
internal fun parseExportedFilesWithFallback(filesJson: String?, legacyJson: String?): List<ExportedFile> {
    filesJson?.let { json ->
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> parseExportedFile(arr.optJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }
    val legacy = legacyJson ?: return emptyList()
    return try {
        listOfNotNull(parseExportedFile(org.json.JSONObject(legacy)))
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseExportedFile(obj: org.json.JSONObject?): ExportedFile? {
    obj ?: return null
    return ExportedFile(
        fileName = obj.optString("fileName", ""),
        mimeType = obj.optString("mimeType", "text/plain"),
        sizeBytes = obj.optLong("sizeBytes", 0),
        absolutePath = obj.optString("absolutePath", ""),
        openHint = obj.optString("openHint", "").ifBlank { null },
    )
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

    // Fix-StreamingPsychLeak：与 _streamingContent 同一思路——流式阶段实时剥离出的
    // 圆括号心理描写，独立 StateFlow 暴露给 StreamingMessageItem，让 PsychCard 能在
    // 打字机效果进行中就显示，不必等到整条回复生成完毕。
    private val _streamingPsych = MutableStateFlow<String?>(null)
    val streamingPsych: StateFlow<String?> = _streamingPsych.asStateFlow()

    private val db = AppDatabase.getInstance(application)
    // Phase 3 修复手册第3条：messageDao/identityDao/agentPlanDao 原先是裸持有的
    // DAO 字段（29种DAO里真正"字段裸持有、无包装、被直接调方法"的3个之一）。
    // P3-14 修复：字段重命名 messageDao → messageRepo 等，使变量名与实际类型
    // （Repository 包装实例）一致。原先的 xxxDao 命名是阶段 2 架构重构的遗留命名。
    private val agentPlanRepo = AgentPlanRepository(db.agentPlanDao())
    private val learningGoalRepo = LearningGoalRepository(db.learningGoalDao())
    // Phase 3 修复手册：以下 6 项改从 AppContainer 取现成实例，不再各自 new
    // （原先与 RoundtableViewModel 逐行重复的装配逻辑，见审计报告 Phase 3）
    private val container = AppContainer.instance
    // 阶段2 S-1 收尾：messageRepo 原先独立 new（db.messageDao()），与
    // AppContainer.messageRepo 构造参数完全一致，改引用容器共享实例。
    private val messageRepo get() = container.messageRepo
    private val eventRepo get() = container.eventRepo
    private val memoryRepo get() = container.memoryRepo
    // 阶段2 S-1 批次1收口：identityRepo 原先独立 new（db.characterIdentityDao()，
    // 与容器构造参数完全一致），改引用容器共享实例。
    private val identityRepo get() = container.identityRepo
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

    // W6-1 修复：孕期 Prompt 逻辑抽取到 PregnancyPromptDelegate，
    // 减少 sendMessage() 约 470 行，所有孕期依赖通过构造函数注入。
    // Fix-顺序：构造依赖 pregnancyTriggerManager/pregnancyAnswerRepo/
    // pregnancyAnswerIntentDetector/d3AskAttemptStore/agentRelationEngine，
    // 这些字段在类体中声明于本处之后，故实际构造挪到它们全部声明完毕之后
    // （见下方 agentRelationEngine 声明之后），避免"属性尚未初始化"编译错误。
    private val taskRepo    get() = container.taskRepo
    private val projectRepo get() = container.projectRepo
    private val workflowRepo get() = container.workflowRepo
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
    // 报告6/P0-1 收口：cycleRepository 原先在此内联 new 出第二个
    // MenstrualCycleRepository(db.menstrualCycleDao()) 实例，与 AppContainer
    // 已持有的共享单例 container.menstrualCycleRepo 重复且不一致（违反本文件
    // 上方"跨 ViewModel 共享实例一律取自 AppContainer"的既定架构约定）。
    // 改为复用容器单例，同时供下方 onIdentityRegister 女儿周期初始化使用，
    // 确保读写走同一个 Repository 实例。
    private val cycleRepository get() = container.menstrualCycleRepo

    // S8-窗口09 新发现1修复（P1，DI不一致）：此前本处各自 new 了一份
    // FertileWindowConsentJudge/UserConsentIntentJudge，与 AppContainer 已有的
    // 共享单例（fertileWindowConsentJudge/userConsentIntentJudge）是两个独立对象，
    // 虽然功能等价（均无内部状态），但违背 DI 收敛原则。容器已提供
    // createPregnancyTriggerManagerFull() 工厂方法，构造参数与本处原写法逐字段
    // 一致（含 cycleRepository/stateRepository 默认值），直接复用即可。
    private val pregnancyTriggerManager = container.createPregnancyTriggerManagerFull(
        cycleRepository = cycleRepository,
        stateRepository = characterStateRepo,
    )
    

    // ── Phase 24/26：打分引擎 + 规则提炼引擎 ────────────────────
    // 审查报告（W13 问题1）修复：原 by lazy 只在首次访问时捕获 Provider，
    // 此后用户在 ProfileAiConfigSection 切换 Key/Provider 不会让这两个引擎
    // 重新取到新凭证，评分卡/规则提炼会静默用旧 Key 请求并 401 失败。
    // EvaluationEngine.lastSessionAt / DistillationEngine.lastDistillAt 是
    // 有状态的冷却去重缓存（ConcurrentHashMap，见 M-4 / P1-6-5 修复），若改为
    // 每次访问都 new 一个实例（方案A），冷却状态会被清空，去重失效——
    // 因此采用方案B：Provider 变更时重建实例，与 ZaijianApp.kt 中
    // CompetitionEngine 的 addOnProviderConfigChangedListener 重建模式一致。
    @Volatile private var evaluationEngine: EvaluationEngine? = null
    @Volatile private var distillationEngine: DistillationEngine? = null

    /** 依据当前 activeProvider 重建 evaluationEngine / distillationEngine；provider 未配置时置空。 */
    private fun rebuildEvaluationAndDistillationEngines() {
        val p = ProviderManager.instance.activeProvider
        evaluationEngine = p?.let {
            EvaluationEngine(
                evaluationSessionDao = db.evaluationSessionDao(),
                learningGoalDao      = db.learningGoalDao(),
                provider             = it,
            )
        }
        distillationEngine = p?.let {
            DistillationEngine(
                db                   = db,
                evaluationSessionDao = db.evaluationSessionDao(),
                learningGoalDao      = db.learningGoalDao(),
                memoryDao            = db.memoryDao(),
                provider             = it,
                memoryRepo           = memoryRepo,
            )
        }
    }

    /** W6-6 修复：ProviderManager 监听器 lambda 引用的 ChatViewModel 实例在
     *  ViewModel 被意外销毁重建时（导航架构变更 / 进程回收后恢复），旧的
     *  listener 若不反注册，ProviderManager 会一直持有对旧 ViewModel 实例的
     *  引用，导致内存泄漏且回调更新到错误的 ViewModel 实例上。
     *  Fix-顺序：挪到 init 块之前声明（原先声明在 init 块之后，被 init 块
     *  提前引用，导致"属性尚未初始化"编译错误）。 */
    private val providerConfigListener: () -> Unit = {
        rebuildEvaluationAndDistillationEngines()
    }

    // Fix-顺序：githubConfigStore 原声明在下方 scheduleRepo 附近，
    // 被 ChatToolRegistrar 构造提前引用，挪到此处。
    private val githubConfigStore = GithubConfigDataStore(getApplication())

    // 首次装配（Key 已配置场景），并订阅 Provider 配置变更——切 Key/切 Provider 后
    // 自动重建，不再需要用户重启 App 或重新进入聊天页才能让评分卡/规则提炼
    // 拿到新凭证。ChatViewModel 是应用内单例 ViewModel（不随切换角色重建，见
    // currentCharacterId 为可变字段），监听器只需注册一次，与 ProviderManager
    // 单例同生命周期，无需在 onCleared() 中反注册。回调在 IO 线程触发（见
    // ProviderManager 内注释），此处重建逻辑是轻量同步赋值，不需要额外切线程。
    // init 块已挪到 toolRegistrar 声明之后（314 行引用了 toolRegistrar，
    // 必须等它声明完才能执行 init；见下方 toolRegistrar 声明后的完整说明）。

    override fun onCleared() {
        super.onCleared()
        ProviderManager.instance.removeOnProviderConfigChangedListener(providerConfigListener)
        // window13结论7修复：离开聊天页时清除前台角色标记。仅当当前全局值仍是
        // 本 ViewModel 设置的那个 characterId 时才清——避免"新 ChatViewModel 已经
        // 为角色B设置了标记，旧的角色A ViewModel 才姗姗来迟执行onCleared()"这种
        // 时序下，误把角色B的标记清掉。
        if (com.zaijian.zhoumuyun.domain.PresenceEngine.foregroundChatCharacterId == currentCharacterId) {
            com.zaijian.zhoumuyun.domain.PresenceEngine.foregroundChatCharacterId = null
        }
    }

    private val scheduleRepo = ScheduleRepository(
        scheduledJobDao = db.scheduledJobDao(),
        jobResultDao    = db.jobResultDao(),
        db              = db,
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

    // Fix-顺序：toolRegistrar 依赖 scheduleRepo/calendarSync，必须放在这两者声明之后；
    // 同时它被下方 init 块（314 行 toolRegistrar.registerStaticTools()）提前引用，
    // 所以又必须放在 init 块之前——三处顺序合起来只有这一个位置合法。
    private val toolRegistrar = ChatToolRegistrar(
        db               = db,
        getApplication   = { getApplication() },
        agentPlanRepo    = agentPlanRepo,
        memoryRepo       = memoryRepo,
        memoryDao        = memoryDao,
        learningGoalRepo = learningGoalRepo,
        workflowRepo     = workflowRepo,
        taskRepo         = taskRepo,
        memoryEngine     = memoryEngine,
        scheduleRepo     = scheduleRepo,
        calendarSync     = calendarSync,
        identityRepo     = identityRepo,
        githubConfigStore = githubConfigStore,
    )

    // 首次装配（Key 已配置场景），并订阅 Provider 配置变更——切 Key/切 Provider 后
    // 自动重建，不再需要用户重启 App 或重新进入聊天页才能让评分卡/规则提炼
    // 拿到新凭证。ChatViewModel 是应用内单例 ViewModel（不随切换角色重建，见
    // currentCharacterId 为可变字段），监听器只需注册一次，与 ProviderManager
    // 单例同生命周期，无需在 onCleared() 中反注册。回调在 IO 线程触发（见
    // ProviderManager 内注释），此处重建逻辑是轻量同步赋值，不需要额外切线程。
    //
    // Fix-顺序：此 init 块原先声明在文件靠前位置（scheduleRepo/calendarSync/
    // toolRegistrar 声明之前），但块内 toolRegistrar.registerStaticTools() 提前
    // 引用了 toolRegistrar，而 toolRegistrar 又依赖 scheduleRepo/calendarSync——
    // 三者只有当 init 块排在这三个属性声明之后时顺序才自洽，故整体挪到此处。
    init {
        rebuildEvaluationAndDistillationEngines()
        ProviderManager.instance.addOnProviderConfigChangedListener(providerConfigListener)
        toolRegistrar.registerStaticTools()
    }


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

    // W6-1 修复：孕期 Prompt 逻辑抽取到 PregnancyPromptDelegate，
    // 减少 sendMessage() 约 470 行，所有孕期依赖通过构造函数注入。
    // Fix-顺序：挪到此处（所有依赖字段声明完毕之后），原先在类体靠前位置
    // 构造时引用了尚未初始化的 pregnancyTriggerManager/pregnancyAnswerRepo/
    // pregnancyAnswerIntentDetector/d3AskAttemptStore/agentRelationEngine。
    private val pregnancyDelegate = PregnancyPromptDelegate(
        pregnancyRepo                = pregnancyRepo,
        pregnancyTriggerManager      = pregnancyTriggerManager,
        pregnancyPressureDataStore   = pregnancyPressureDataStore,
        pregnancyAnswerRepo          = pregnancyAnswerRepo,
        pregnancyAnswerIntentDetector = pregnancyAnswerIntentDetector,
        d3AskAttemptStore            = d3AskAttemptStore,
        daughterRepo                 = daughterRepo,
        agentRelationEngine          = agentRelationEngine,
    )

    // S-3 拆分：工具注册逻辑提取到 ChatToolRegistrar
    // toolRegistrar 声明已挪到 init 块之前（见上方 scheduleRepo/calendarSync 后面），
    // 原因同 providerConfigListener/githubConfigStore：init 块内 314 行提前引用了它，
    // 按 Kotlin 属性初始化顺序（自上而下）必须让声明先于 init 块出现，否则报
    // "Variable 'toolRegistrar' must be initialized"。

    // S-3 拆分：背景图管理提取到 ChatBackgroundManager
    private val backgroundManager = ChatBackgroundManager(
        _uiState             = _uiState,
        chatBgStore          = chatBgStore,
        viewModelScope       = viewModelScope,
        getCurrentCharacterId = { currentCharacterId },
    )
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
            //
            // 批次3 3-1修复：原代码是4步裸写入，中途失败时 DaughterCharacterGenerator
            // 的 catch 块只调用 repository.deleteByMother() 删 daughter_character 表，
            // character_identity/agent_relation/menstrual_cycle 残留孤儿行，且
            // CharacterIdentityDao 此前没有 delete 方法无法清理。重试会分配新 ID
            // 产生新孤儿，旧孤儿永久累积。
            // 修复：每步写入后若后续步骤失败，在 catch 块里按反序清理已写入的表。
            // 清理顺序：menstrual_cycle → daughter_character(回填) → agent_relation → character_identity。
            // daughter_character 的删除由 DaughterCharacterGenerator 的 catch 块负责（它知道 motherId），
            // 这里只负责清理另外3张表（按 allocatedId 清理）。
            val allocatedId = daughterIdAllocator.allocate()
            val identityEntity = daughterData.toCharacterIdentityEntity(allocatedId)
            try {
                identityRepo.upsert(identityEntity)
                try {
                    db.agentRelationDao().insert(
                        AgentRelationEntity(
                            daughterId        = allocatedId,
                            motherCharacterId = daughterData.motherCharacterId,
                        )
                    )
                    try {
                        daughterRepo.updateDaughterCharacterId(
                            motherCharacterId  = daughterData.motherCharacterId,
                            daughterCharacterId = allocatedId,
                        )
                        // P0-1 修复：女儿角色（含二代→三代场景）注册时必须同步初始化周期锚点，
                        // 否则 MenstrualCycleRepository.get()/observe() 查不到该 characterId 的
                        // 记录，fallback 出 cycleAnchorAt=null 的默认状态 → currentPhase() 恒为
                        // SAFE → isInFertileWindow() 恒为 false → shouldEvaluateFertileWindowConsent()
                        // 的门2（PregnancyTriggerManager.kt:236 `if (!inFertileWindow) return false`）
                        // 永远不通过，该女儿角色的受孕弹窗永久不会触发，生育链路被静默阻断。
                        // 用 resetAnchorToToday 而非 initIfAbsent：后者只遍历写死的九位母亲
                        // DefaultCycleOffsetDays 映射表，不认识动态分配的女儿 characterId。
                        try {
                            cycleRepository.resetAnchorToToday(allocatedId)
                        } catch (e: Exception) {
                            // 第④步失败：清理已写入的 agent_relation + character_identity
                            // daughter_character 的回填回滚由 DaughterCharacterGenerator 的 deleteByMother 负责
                            db.agentRelationDao().deleteByDaughterId(allocatedId)
                            db.characterIdentityDao().deleteForRollback(allocatedId)
                            throw e
                        }
                    } catch (e: Exception) {
                        // 第③步失败：清理已写入的 agent_relation + character_identity
                        db.agentRelationDao().deleteByDaughterId(allocatedId)
                        db.characterIdentityDao().deleteForRollback(allocatedId)
                        throw e
                    }
                } catch (e: Exception) {
                    // 第②步失败：清理已写入的 character_identity
                    db.characterIdentityDao().deleteForRollback(allocatedId)
                    throw e
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // CancellationException 传播前也尝试清理（避免协程取消留下孤儿）
                runCatching { db.agentRelationDao().deleteByDaughterId(allocatedId) }
                runCatching { db.characterIdentityDao().deleteForRollback(allocatedId) }
                throw e
            }
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
    // W9问题1修复：init() 中原有三个 viewModelScope.launch 协程未追踪 Job 引用，
    // launchSingleTop=true 导致 ViewModel 实例复用时，旧协程闭包捕获的旧 characterId
    // 不会随新 init() 调用失效，完成时会用旧角色数据覆盖新角色的 _uiState，
    // 与 observeJobs 已有的取消逻辑形成遗漏对比，现补齐追踪与取消。
    private var loadMessagesJob: Job? = null
    private var settlementCheckJob: Job? = null
    private var loadCharacterJob: Job? = null

    /**
     * L5 修复：受孕机制 AI 门3判定（judgeFertileWindowIntent）冷却。
     * shouldEvaluateFertileWindowConsent 通过后，门3 AI 调用每条消息都会触发，
     * 排卵期内剧情尚未发展到最后一步时会频繁消耗 LLM token。
     * 此 map 记录上次 AI 判定的时间戳，同一角色在 [FERTILE_JUDGE_COOLDOWN_MS] 内只判定一次。
     * key = characterId，value = 上次判定时间戳（ms）
     */
    private val lastFertileJudgeAtMap = ConcurrentHashMap<Int, Long>()
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
    private val pendingKeywordTriggerMap = ConcurrentHashMap<Int, Boolean>()

    // S-3 拆分：sendMessage() 编排逻辑提取到 ChatMessageOrchestrator。
    // 注意：必须声明在 daughterGenerator / pendingKeywordTriggerMap / lastFertileJudgeAtMap
    // 之后，否则直接传值（非闭包）会触发 Kotlin "属性未初始化"编译错误。
    private val messageOrchestrator = ChatMessageOrchestrator(
        _uiState                      = _uiState,
        _streamingContent             = _streamingContent,
        _streamingPsych               = _streamingPsych,
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
        taskRepo                      = taskRepo,
        projectRepo                   = projectRepo,
        workflowRepo                  = workflowRepo,
        eventRepo                     = eventRepo,
        pregnancyDelegate             = pregnancyDelegate,
        agentRelationEngine           = agentRelationEngine,
        daughterGenerator             = daughterGenerator,
        db                            = db,
        getCurrentCharacterId         = { currentCharacterId },
        getReplyJob                   = { replyJob },
        setReplyJob                   = { replyJob = it },
        getEvaluationEngine           = { evaluationEngine },
        pendingKeywordTriggerMap      = pendingKeywordTriggerMap,
        lastFertileJudgeAtMap         = lastFertileJudgeAtMap,
        viewModelScope                = viewModelScope,
        loadMessages                  = { loadMessages(it) },
        MAX_HISTORY_CHARS             = MAX_HISTORY_CHARS,
    )

    fun init(characterId: Int) {
        // P1-10-4 修复：切换角色时必须同时取消上一次的 replyJob，
        // 否则旧 replyJob 完成后会把旧角色的回复写入新角色的 UI 状态，
        // 且 isTyping 被两个协程同时操控导致状态混乱。
        replyJob?.cancel()
        // 第7窗口问题2修复：切换角色时重置 _streamingContent，避免上一个角色
        // 尚未清空的流式回复残留内容，在新角色页面初次渲染时被短暂看到。
        _streamingContent.value = null
        _streamingPsych.value = null
        // P2 修复：取消上一次 init 残留的 collector，避免叠加导致重复处理
        observeJobs.forEach { it.cancel() }
        // W9问题1修复：取消上一次 init() 遗留的三个未追踪协程，避免旧角色的
        // loadMessages / 分娩结算检查 / 角色加载在切换角色后仍继续运行，
        // 完成时用旧角色数据覆盖新角色的 _uiState。
        loadMessagesJob?.cancel()
        settlementCheckJob?.cancel()
        loadCharacterJob?.cancel()
        currentCharacterId = characterId
        // window13结论7修复：告知 PresenceEngine 当前前台聊天页角色，
        // 使 AppContainer 的 onProactiveMessage 回调能对该角色抑制重复系统通知
        // （Snackbar 已经在下方 proactiveMessageFlow 订阅里展示了）。
        com.zaijian.zhoumuyun.domain.PresenceEngine.foregroundChatCharacterId = characterId
        // 批次1 1-6修复：消息列表改用 Flow 订阅（原为一次性 loadMessages）。
        // Worker 持久化的主动消息会实时出现在当前聊天页，无需用户发新消息或重进页面。
        // observeJobs 在下次 init 时会先 cancel，避免重复订阅。
        loadMessagesJob = null  // 不再用一次性加载

        // W6-2 修复：registerCharacterTools() 是同步调用，若内部异常（如
        // AgentToolRegistry 内部状态异常）不应阻断后续 settlementCheckJob
        // 和 loadCharacterJob 的启动——这三个任务是独立的，任一失败不应影响其他。
        try {
            registerCharacterTools()
        } catch (e: Exception) {
            ZLog.e("ChatViewModel", "registerCharacterTools 失败，工具注册可能不完整", e)
        }

        // 批次C·问题5 修复：分娩到期结算——"进入聊天时"触发路径。
        // 与 ZaijianApp.onCreate() 的 12h 周期兜底轮询互补：用户可能在两次轮询
        // 之间打开聊天页，此时若恰好某角色已满 30 天，应立即结算而不是让用户
        // 干等到下一个轮询点。独立协程、独立 try-catch，与角色加载逻辑解耦，
        // 结算失败不影响本次进入聊天页的其余流程。
        settlementCheckJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                com.zaijian.zhoumuyun.data.agent.PregnancySettlementScheduler.runImmediateCheck(
                    context       = getApplication(),
                    pregnancyRepo = pregnancyRepo,
                    memoryRepo    = memoryRepo,
                    daughterRepo  = daughterRepo,
                )
            } catch (e: Exception) {
                ZLog.e("ChatViewModel", "分娩结算检查失败", e)
            }
        }

        loadCharacterJob = viewModelScope.launch(Dispatchers.IO) {
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
                        identityRepo.upsert(identityEntity)
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
            // 批次1 1-6修复：消息列表改用 Flow 订阅，Worker 持久化的主动消息实时呈现。
            // 原为一次性 loadMessages()，用户需发新消息或重进页面才能看到 Worker 写入的消息。
            viewModelScope.launch {
                messageRepo.observeByCharacter(characterId)
                    .flowOn(Dispatchers.IO)
                    .collect { msgs ->
                        // 与原 loadMessages() 的错误处理对齐：DB 查询失败时通过 error 字段
                        // 驱动 UI 层已有的 Snackbar 展示逻辑，而不是让协程静默取消。
                        try {
                            _uiState.update {
                                it.copy(messages = msgs.map { ChatTagParser.toChatMessage(it) }.toImmutableList())
                            }
                        } catch (e: Exception) {
                            ZLog.e("ChatViewModel", "characterId=$characterId 消息Flow订阅处理失败", e)
                            _uiState.update { it.copy(error = "加载消息失败，请重试") }
                        }
                    }
            },
            // 订阅 PresenceEngine 主动消息流，用于前台实时呈现
            viewModelScope.launch {
                // 批次1 1-6修复：proactiveMessageFlow 已提升为 companion object 成员，
                // Kotlin 不支持通过实例引用访问 companion 成员，需用类名访问。
                PresenceEngine.proactiveMessageFlow.collect { msg ->
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
                identityRepo.observeById(characterId)
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
            backgroundManager.startObserving(),
        )
    }

    private suspend fun loadMessages(characterId: Int) {
        // W5-002 修复：DB 查询失败时（数据库损坏、磁盘满等）之前会让异常
        // 直接传播到 viewModelScope，导致协程静默取消，_uiState.error 永远
        // 不会被赋值，用户看到空消息列表却没有任何提示。这里补上 try-catch，
        // 失败时通过 error 字段驱动 UI 层已有的 Snackbar 展示逻辑。
        try {
            val msgs = withContext(Dispatchers.IO) {
                messageRepo.getByCharacter(characterId)
            }
            _uiState.update { it.copy(messages = msgs.map { ChatTagParser.toChatMessage(it) }.toImmutableList()) }
        } catch (e: Exception) {
            ZLog.e("ChatViewModel", "characterId=$characterId 加载消息失败", e)
            _uiState.update { it.copy(error = "加载消息失败，请重试") }
        }
    }

    fun notifyFileImported(fileName: String, absolutePath: String) {
        if (currentCharacterId < 0) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                messageRepo.insert(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = currentCharacterId,
                        role = "system",
                        content = "用户导入了一个文件：$fileName（路径：$absolutePath）",
                        createdAt = System.currentTimeMillis(),
                    )
                )
                loadMessages(currentCharacterId)
            } catch (e: Exception) {
                ZLog.e("ChatViewModel", "文件导入失败 fileName=$fileName", e)
                _uiState.update { it.copy(error = "文件导入失败，请重试") }
            }
        }
    }

    /**
     * 2.4：导出本次对话（Agent附件下发方案 v2.0 P2）。
     *
     * 把当前角色的消息列表按时间顺序拼成一份文本（角色/用户各自加前缀区分），
     * 走 AgentToolRegistry 里已注册的 file_export 工具落地——复用 1.1 打通的
     * extractExportedFileJson 识别链路，产出的 metaJson 包进一条 role="system"
     * 消息插入数据库，FileExportCard 会像其他工具产出的文件一样自动出现在
     * 消息流里，不需要额外的成功 Snackbar（卡片本身就是最直观的反馈）；
     * 失败走 error 字段驱动 UI 层已有的 Snackbar 展示逻辑，与本文件其余方法
     * 的错误处理范式一致。
     *
     * 只导出台词正文（ChatMessage.content），不含内心独白/心理描写/文件卡——
     * 这些是气泡簇的展示层信息，不是"对话内容"本身；用户要的是"聊了什么"。
     */
    fun exportConversation() {
        if (currentCharacterId < 0) return
        val characterId = currentCharacterId
        val characterName = _uiState.value.character?.name ?: "对方"
        val messages = _uiState.value.messages
        if (messages.isEmpty()) {
            _uiState.update { it.copy(error = "当前没有可导出的对话内容") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val transcript = buildString {
                    messages.forEach { msg ->
                        if (msg.content.isBlank()) return@forEach
                        val speaker = if (msg.role == "user") "我" else characterName
                        appendLine("[$speaker] ${msg.content}")
                        appendLine()
                    }
                }.trimEnd()

                val exportTool = AgentToolRegistry.get("file_export")
                if (exportTool == null) {
                    ZLog.e("ChatViewModel", "导出对话失败：file_export 工具未注册")
                    _uiState.update { it.copy(error = "导出失败，请重试") }
                    return@launch
                }

                val fileName = "与${characterName}的对话记录"
                val result = exportTool.execute(
                    mapOf(
                        "name"    to fileName,
                        "content" to transcript,
                        "format"  to "md",
                    )
                )

                val exportedFileJson = extractExportedFileJson(result)
                if (!result.success || exportedFileJson == null) {
                    ZLog.e("ChatViewModel", "导出对话失败 characterId=$characterId error=${result.error}")
                    _uiState.update { it.copy(error = "导出失败，请重试") }
                    return@launch
                }

                messageRepo.insert(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = characterId,
                        role = "system",
                        content = "已导出本次对话",
                        createdAt = System.currentTimeMillis(),
                        exportedFileJson = exportedFileJson,
                    )
                )
                loadMessages(characterId)
            } catch (e: Exception) {
                ZLog.e("ChatViewModel", "导出对话失败 characterId=$characterId", e)
                _uiState.update { it.copy(error = "导出失败，请重试") }
            }
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
    fun requestChatBackgroundCrop(uri: String) = backgroundManager.requestChatBackgroundCrop(uri)
    fun cancelChatBackgroundCrop() = backgroundManager.cancelChatBackgroundCrop()
    fun confirmChatBackgroundCrop(uri: String, offsetX: Float, offsetY: Float, scale: Float) = backgroundManager.confirmChatBackgroundCrop(uri, offsetX, offsetY, scale)
    fun setChatBackground(uri: String) = backgroundManager.setChatBackground(uri)
    fun clearChatBackground() = backgroundManager.clearChatBackground()

    // S-3 拆分：sendMessage() 编排逻辑已提取至 ChatMessageOrchestrator
    fun sendMessage(text: String) = messageOrchestrator.sendMessage(text)

    fun clearProactiveMessage() {
        _uiState.update { it.copy(pendingProactiveMessage = null) }
        // 主动消息已写入 DB（persistAndNotify 保证），刷新消息列表让气泡即时出现
        viewModelScope.launch { loadMessages(currentCharacterId) }
    }
    fun dismissDistillResult() { _uiState.update { it.copy(pendingDistillResult = null) } }

    /** 顶部通用错误 Snackbar 消费后清除，避免重组时重复弹出。 */
    fun clearError() { _uiState.update { it.copy(error = null) } }

    /** D4 女儿生成失败提示消费后清除。 */
    fun clearDaughterGenerationError() { _uiState.update { it.copy(pendingDaughterGenerationError = null) } }

    /** API Key 未配置提示消费后清除（ChatScreen 内已在同一个 LaunchedEffect 里跳转到 Profile）。 */
    fun clearApiKeyMissingFlag() { _uiState.update { it.copy(isApiKeyMissing = false) } }

    /**
     * 受孕窗口同意对话框的用户选择回调。
     * accepted = true/false 分别对应用户点击「同意」/「拒绝」。
     *
     * S8-窗口09 修复（结论1/结论6，P0）：此前本方法只清空弹窗文案，从未调用
     * pregnancyTriggerManager.proceedAfterDialogConsent()——受孕流程判定从未
     * 执行，且 fertileWindowConsentAsked 标记从未被置为 true，弹窗保护机制
     * 完全失效（同一排卵期内会反复弹窗）。
     *
     * 现在改为：先关闭对话框（避免同一个 dialogText 触发二次弹窗，与原实现
     * 顺序一致），再异步调用 proceedAfterDialogConsent()——该方法内部 finally
     * 块保证 markFertileWindowConsentAsked 无论如何都会执行，本处无需额外兜底。
     *
     * 使用 fertileWindowCharacterId（弹窗展示时捕获的快照）而非实时的
     * currentCharacterId——弹窗展示期间用户若切换角色，判定结果必须仍然作用
     * 在弹窗真正对应的角色上（问题14既有设计，见字段声明处注释）。
     *
     * pressureScale 读取方式与 PregnancyPromptDelegate.buildPregnancyPrompts()
     * 保持一致，取当前动态压力系数，不再硬编码 1.0f。
     */
    fun onFertileWindowDialogResult(accepted: Boolean) {
        val characterId = _uiState.value.fertileWindowCharacterId
        _uiState.update { it.copy(fertileWindowConsentDialogText = null) }
        if (characterId < 0) {
            ZLog.w("ChatViewModel", "onFertileWindowDialogResult: fertileWindowCharacterId 无效（$characterId），跳过受孕判定")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pressureScale = pregnancyPressureDataStore.pregnancyPressureScaleFlow.first()
                val result = pregnancyTriggerManager.proceedAfterDialogConsent(
                    characterId   = characterId,
                    accepted      = accepted,
                    pressureScale = pressureScale,
                )
                when (result) {
                    is PregnancyTriggerResult.Triggered -> {
                        ZLog.i("ChatViewModel", "受孕弹窗同意后判定：怀孕触发（characterId=$characterId）")
                    }
                    is PregnancyTriggerResult.FertileButFailed -> {
                        ZLog.i("ChatViewModel", "受孕弹窗同意后判定：本次未命中（characterId=$characterId，" +
                            "连续失败${result.consecutiveFailCount}次）")
                    }
                    is PregnancyTriggerResult.WrongPhase -> {
                        ZLog.i("ChatViewModel", "受孕弹窗同意后判定：非排卵期（characterId=$characterId）")
                    }
                    is PregnancyTriggerResult.Rejected -> {
                        ZLog.i("ChatViewModel", "受孕弹窗拒绝：累积副作用已写入（characterId=$characterId）")
                    }
                    is PregnancyTriggerResult.AmbiguousRejected,
                    is PregnancyTriggerResult.NotTriggered,
                    is PregnancyTriggerResult.BreakthroughA,
                    is PregnancyTriggerResult.BreakthroughB,
                    is PregnancyTriggerResult.Miscarried -> {
                        // proceedAfterDialogConsent 内部只会走 evaluateCycleAndProceed（同意分支）
                        // 或 applyRejectedEffect（拒绝分支），不会产出以上分支；
                        // 穷尽 when 分支需要，安全忽略。
                    }
                }
            } catch (e: Exception) {
                ZLog.w("ChatViewModel", "onFertileWindowDialogResult: proceedAfterDialogConsent 异常", e)
            }
        }
    }

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
            try {
                messageRepo.deleteByCharacter(currentCharacterId)
                loadMessages(currentCharacterId)
            } catch (e: Exception) {
                ZLog.e("ChatViewModel", "清空消息失败", e)
                _uiState.update { it.copy(error = "清空消息失败，请重试") }
            }
        }
    }
    fun setActiveProject(projectId: String?) {
        viewModelScope.launch {
            try {
                val project = if (projectId != null) projectRepo.getById(projectId) else null
                _uiState.update { it.copy(activeProjectId = projectId, activeProjects = if (project != null) persistentListOf(project) else persistentListOf()) }
            } catch (e: Exception) {
                ZLog.e("ChatViewModel", "设置活跃项目失败 projectId=$projectId", e)
                _uiState.update { it.copy(error = "设置项目失败，请重试") }
            }
        }
    }

    private fun registerCharacterTools() {
        toolRegistrar.registerCharacterTools(currentCharacterId)
    }

    // S-3 拆分：标签解析已提取至 ChatTagParser

    companion object {
        /**
         * 单次请求按字符预算保留的历史消息（字符总量上限）。
         * DeepSeek V4 Flash 1M 上下文：450,000 字符 ≈ 300,000 token（占上限约 28.6%）。
         * 从最新消息往前累积，超出预算时停止，保证最近对话优先保留。
         */
        private const val MAX_HISTORY_CHARS = 450_000

        
    }
}
