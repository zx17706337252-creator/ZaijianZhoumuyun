package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.agent.TablePayload
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────
//  圆桌 UI 状态
// ─────────────────────────────────────────────────────────────

/**
 * 单条圆桌消息（包含用户消息和各 Bot 回复）。
 */
data class RoundtableMessage(
    val id: String,
    /** "user" 或 characterId.toString() */
    val speakerId: String,
    val speakerName: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 此消息是否在流式生成中 */
    val isStreaming: Boolean = false,
    /** 回应目标：null=回应用户，非null=回应某Bot（显示引用标记 ↩） */
    val replyTargetId: String? = null,
    val replyTargetName: String? = null,
    /** 此消息所属的用户消息轮次 */
    val turnIndex: Int = 0,
    /** 此 Bot 是因被用户 @ 点名而强制回应（显示 NotifyBadge） */
    val isNotified: Boolean = false,
    /**
     * 内心独白：[thinking:...] 标签解析结果，与 ChatMessage.thinkingText 同语义。
     * 补齐圆桌场景与私聊场景的标签解析能力对等。null = 无内心独白内容。
     */
    val thinkingText: String? = null,
    /**
     * 心理感受：圆括号（　）包裹内容的解析结果，与 ChatMessage.psychText 同语义。
     * null = 无心理描写内容。
     */
    val psychText: String? = null,
    /**
     * 工具调用产出的文件元数据（JSON 序列化），与 ChatMessage.exportedFileJson 同语义。
     * 补齐圆桌场景与私聊场景的工具产出展示能力对等。null = 本条消息未产出文件。
     * 单文件字段，本轮多个文件类工具调用时只保留最后一个——保留是为了兼容
     * 尚未切换到 exportedFilesJson 的旧读取路径，新代码应优先读 exportedFiles。
     */
    val exportedFileJson: String? = null,
    /**
     * v66（Agent附件下发方案 v2.0 · 1.7 P3）：多文件版本，与
     * ChatMessage.exportedFilesJson 同语义（JSON 数组字符串）。null = 本条
     * 消息没有文件附件；历史消息永远为 null，即使 exportedFileJson 有值。
     */
    val exportedFilesJson: String? = null,
    /**
     * v67（表格直传方案 W4）：与 [ChatMessage.tableDataJson] 同语义/同格式
     * （JSON 序列化 [TablePayload]）。null = 该消息没有表格；历史消息永远为 null。
     * 由 [RoundtableBotReplyGenerator] / [RoundtableIdleManager] 在 `StreamEvent.ToolDone`
     * 里收集 `event.result.tablePayloadJson`，构造 [RoundtableMessage] 时传入，
     * `.toEntity()` 落库时透传到 `RoundtableMessageEntity.tableDataJson`。
     */
    val tableDataJson: String? = null,
) {
    @Deprecated("单文件读取路径，历史兼容用；新代码请用 exportedFiles", ReplaceWith("exportedFiles.firstOrNull()"))
    val exportedFile: ExportedFile? get() = exportedFiles.firstOrNull()

    /**
     * v66（1.7 P3）：优先解析 exportedFilesJson（多文件数组）；为空时退化为把
     * exportedFileJson 包成单元素 list——历史消息（只有旧字段有值）不会因为
     * 这次改造丢失已有的文件卡片。两个字段都为 null 时返回空 list。
     */
    val exportedFiles: List<ExportedFile> get() {
        return parseExportedFilesWithFallback(exportedFilesJson, exportedFileJson)
    }

    /**
     * v67（表格直传方案 W4）：从 [tableDataJson] 反序列化得到的 [TablePayload]。
     * null = 该消息没有表格（或 JSON 格式异常的历史脏数据兜底）。UI 层
     * `RoundtableBubble` 在 `message.tablePayload != null` 时渲染 `TableCard`。
     */
    val tablePayload: TablePayload? get() = tableDataJson?.let { TablePayload.fromJson(it) }
}



/**
 * 各 Bot 的生成状态，用于序贯进度指示器。
 */
enum class BotGenerationStatus {
    WAITING,      // 等待前序 Bot 完成
    GENERATING,   // 正在生成
    DONE,         // 本轮已完成
    IDLE,         // 本轮不发言
}

/**
 * 调度模式（Phase 14 后半新增，圆桌设置面板用）
 */
enum class ScheduleMode {
    AUTO,        // 自动：短消息=启发式，长消息=AI
    HEURISTIC,   // 强制启发式（省 Token）
    AI_ONLY,     // 强制 AI 调度（最自然，消耗 API）
}

// ─────────────────────────────────────────────────────────────
//  Step 2：@mention 解析结果
// ─────────────────────────────────────────────────────────────

data class MentionResult(
    val mentionedIds: Set<Int>,
    val isFullMention: Boolean,
)

@androidx.compose.runtime.Immutable
data class RoundtableUiState(
    val messages: ImmutableList<RoundtableMessage> = persistentListOf(),
    val generationStatus: ImmutableMap<Int, BotGenerationStatus> = persistentMapOf(),
    val waitingForUser: Boolean = true,
    val error: String? = null,
    val isApiKeyMissing: Boolean = false,
    // Step 2：屏蔽制成员管理
    val allMotherMembers: ImmutableList<CharacterConfig> = persistentListOf(),
    // W11问题3修复：原名 blockedMotherIds 易误导——该集合同时存放母角色和
    // 女儿角色（id ≥ 1000）的屏蔽状态，ID 全局唯一，只是复用同一套屏蔽机制。
    // 重命名为 blockedMemberIds 以准确反映其实际用途（纯改名，行为不变）。
    val blockedMemberIds: ImmutableSet<Int> = persistentSetOf(),
    val extraDaughterMembers: ImmutableList<CharacterConfig> = persistentListOf(),
    /**
     * Step 5：设置面板"拉入女儿"列表用的候选池——所有已完成注册的女儿
     * （id ≥ 1000），不论是否已经在 [extraDaughterMembers] 里。
     * 由 [RoundtableViewModel.refreshAvailableDaughters] 异步加载，
     * 加载完成前为空列表，设置面板对应区域不展示而不是报错。
     */
    val availableDaughterMembers: ImmutableList<CharacterConfig> = persistentListOf(),
    val lastRoundSpeakers: ImmutableSet<Int> = persistentSetOf(),
    val turnIndex: Int = 0,
    val isScheduling: Boolean = false,
    val scheduleMode: ScheduleMode = ScheduleMode.AUTO,
    val showSettingsSheet: Boolean = false,
    // ── Step 3：自动连续讨论状态 ─────────────────────────────
    /**
     * 是否正处于"全体@触发后的自动连续讨论"循环中。
     * UI 侧用来显示"讨论中，第 N 轮"状态条。
     */
    val isAutoDiscussing: Boolean = false,
    /**
     * 当前自动续轮的第几轮（从 1 开始）。
     * 0 = 不在自动讨论状态。
     */
    val discussionRound: Int = 0,
    // ── 自发互动 ─────────────────────────────────────────────
    /** 圆桌"自发互动"功能开关（30 秒无输入时随机触发一个角色自发开口） */
    val isSpontaneousEnabled: Boolean = false,
    // ── 圆桌背景图（复用 ChatBackgroundDataStore，哨兵 characterId 见
    //    RoundtableViewModel.ROUNDTABLE_BG_SENTINEL_ID）─────────────
    /** 圆桌背景图 URI（null = 使用默认纯色背景） */
    val backgroundImageUri: String? = null,
    /** 背景图取景偏移/缩放，语义与 ChatUiState 对应字段一致 */
    val backgroundOffsetX: Float = 0f,
    val backgroundOffsetY: Float = 0f,
    val backgroundScale: Float = 1f,
    /** 待裁剪的背景图 URI：非空时 UI 显示 AvatarCropDialog(FULL_SCREEN) */
    val pendingBackgroundCropUri: String? = null,
) {
    val activeMembers: ImmutableList<CharacterConfig>
        get() = (allMotherMembers.filter { it.id !in blockedMemberIds } +
                 extraDaughterMembers.filter { it.id !in blockedMemberIds }).toImmutableList()
}

// ─────────────────────────────────────────────────────────────
//  RoundtableViewModel（S-4 拆分后 —— 瘦协调器）
//
//  核心逻辑已拆分到三个委托类：
//  - RoundtableMessageOrchestrator   消息发送编排
//  - RoundtableBotReplyGenerator     单 Bot 回复生成
//  - RoundtableIdleManager           自发互动模块
//
//  ViewModel 只负责：
//  - 生命周期（init / onCleared）
//  - 状态持有（_uiState + 可变字段）
//  - 成员管理（setMembers / blockMember / addDaughter 等）
//  - 背景图管理
//  - 委托调度
// ─────────────────────────────────────────────────────────────

class RoundtableViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** 圆桌背景图在 ChatBackgroundDataStore 中使用的哨兵 characterId */
        private const val ROUNDTABLE_BG_SENTINEL_ID = -100
    }

    private val prefs = app.getSharedPreferences("roundtable_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        RoundtableUiState(
            scheduleMode = when (
                prefs.getString("schedule_mode", ScheduleMode.AUTO.name)
            ) {
                ScheduleMode.HEURISTIC.name -> ScheduleMode.HEURISTIC
                ScheduleMode.AI_ONLY.name   -> ScheduleMode.AI_ONLY
                else                         -> ScheduleMode.AUTO
            },
            isSpontaneousEnabled = prefs.getBoolean("spontaneous_enabled", false),
        )
    )
    val uiState: StateFlow<RoundtableUiState> = _uiState.asStateFlow()

    // ── AppContainer 共享实例 ──────────────────────────────────
    private val container = AppContainer.instance
    private val memoryRepo         get() = container.memoryRepo
    private val eventRepo          get() = container.eventRepo
    private val memoryEngine       get() = container.memoryEngine
    private val relationshipEngine get() = container.relationshipEngine
    private val pregnancyRepo      get() = container.pregnancyRepo
    private val characterStateRepo get() = container.characterStateRepo
    // S8-窗口01 收口：改用 container.createPregnancyTriggerManagerForRoundtable()——
    // 不再需要本文件裸持 db 才能拼出 PregnancyTriggerManager（不传
    // relationshipEngine/aiJudge/consentJudge 的功能差异由工厂方法内部封装，
    // 圆桌场景不触发受孕弹窗链路的行为与迁移前完全一致）。
    private val pregnancyTriggerManager = container.createPregnancyTriggerManagerForRoundtable()
    private val presenceEngine: PresenceEngine get() = container.presenceEngine
    // S8-窗口01 收口：agentPlanDao/learningGoalDao/roundtableMessageDao 原先各自
    // 独立 new（构造参数与容器完全一致），改引用 AppContainer 共享实例。
    // 变量名维持不变，避免影响下方大量调用点。
    private val agentPlanDao get() = container.agentPlanRepo
    private val learningGoalDao get() = container.learningGoalRepo
    private val daughterCharacterRepo get() = container.daughterCharacterRepo
    private val roundtableMessageDao get() = container.roundtableMessageRepo
    private val identityDao get() = container.identityRepo
    // Window C 技能系统补做：圆桌两条发言路径（常规回复 + 自发插话）都需要
    // 按当前发言角色读取其私有技能目录，同容器共享实例，不单独 new。
    private val skillRepo get() = container.skillRepo
    // E0 分层收口：圆桌心迹面板的多角色合并时间线，原由 RoundtableScreen
    // 直接持有 AppContainer.instance.agentActivityRepo，现收敛到 ViewModel。
    private val agentActivityRepo get() = container.agentActivityRepo
    private var currentRoundtableId: String? = null

    // ── 圆桌背景图 ────────────────────────────────────────────
    private val chatBgStore = com.zaijian.zhoumuyun.data.datastore.ChatBackgroundDataStore(app)

    // ── 可变状态（委托给 Orchestrator 通过 lambda 访问）──────
    private var roundJob: Job? = null
    @Volatile
    private var isInterrupted = false

    // ── 常量 ──────────────────────────────────────────────────
    private val SENTENCE_BREAK_CHARS = setOf('。', '？', '！', '…', '；', '.', '?', '!', ';')
    private val FULL_MENTION_KEYWORDS = listOf("@全部", "@所有人", "@大家", "@全员", "@everyone")
    private val AUTO_DISCUSSION_MAX_EXTRA_ROUNDS = 6
    private val JUDGE_TIMEOUT_MS = 8_000L
    private val REPLY_TIMEOUT_MS = 60_000L
    private val JUDGE_CONTEXT_MESSAGES = 12
    private val SPONTANEOUS_IDLE_MS = 30_000L

    // ── 自发互动 ──────────────────────────────────────────────
    private var idleWatchJob: Job? = null

    // ── S-4 委托类 ────────────────────────────────────────────
    private val botReplyGenerator = RoundtableBotReplyGenerator(
        _uiState                = _uiState,
        memoryRepo              = memoryRepo,
        relationshipEngine      = relationshipEngine,
        pregnancyRepo           = pregnancyRepo,
        characterStateRepo      = characterStateRepo,
        daughterCharacterRepo   = daughterCharacterRepo,
        pregnancyTriggerManager = pregnancyTriggerManager,
        presenceEngine          = presenceEngine,
        identityDao             = identityDao,
        agentPlanDao            = agentPlanDao,
        learningGoalDao         = learningGoalDao,
        roundtableMessageDao    = roundtableMessageDao,
        eventRepo               = eventRepo,
        skillRepo               = skillRepo,
        getCurrentRoundtableId  = { currentRoundtableId },
        isInterruptedRef        = { isInterrupted },
        viewModelScope          = viewModelScope,
        REPLY_TIMEOUT_MS        = REPLY_TIMEOUT_MS,
    )

    private val messageOrchestrator = RoundtableMessageOrchestrator(
        _uiState                       = _uiState,
        roundtableMessageDao           = roundtableMessageDao,
        relationshipEngine             = relationshipEngine,
        presenceEngine                 = presenceEngine,
        eventRepo                      = eventRepo,
        memoryEngine                   = memoryEngine,
        isInterruptedRef               = { isInterrupted },
        isInterruptedSetter            = { isInterrupted = it },
        getCurrentRoundtableId         = { currentRoundtableId },
        getRoundJob                    = { roundJob },
        setRoundJob                    = { roundJob = it },
        getIdleWatchJob                = { idleWatchJob },
        viewModelScope                 = viewModelScope,
        SENTENCE_BREAK_CHARS           = SENTENCE_BREAK_CHARS,
        FULL_MENTION_KEYWORDS          = FULL_MENTION_KEYWORDS,
        AUTO_DISCUSSION_MAX_EXTRA_ROUNDS = AUTO_DISCUSSION_MAX_EXTRA_ROUNDS,
        JUDGE_TIMEOUT_MS               = JUDGE_TIMEOUT_MS,
        REPLY_TIMEOUT_MS               = REPLY_TIMEOUT_MS,
        JUDGE_CONTEXT_MESSAGES         = JUDGE_CONTEXT_MESSAGES,
    )

    private val idleManager = RoundtableIdleManager(
        _uiState              = _uiState,
        memoryRepo            = memoryRepo,
        relationshipEngine    = relationshipEngine,
        pregnancyRepo         = pregnancyRepo,
        characterStateRepo    = characterStateRepo,
        daughterCharacterRepo = daughterCharacterRepo,
        presenceEngine        = presenceEngine,
        identityDao           = identityDao,
        roundtableMessageDao  = roundtableMessageDao,
        skillRepo             = skillRepo,
        prefs                 = prefs,
        viewModelScope        = viewModelScope,
        getCurrentRoundtableId = { currentRoundtableId },
        getIdleWatchJob       = { idleWatchJob },
        setIdleWatchJob       = { idleWatchJob = it },
        SPONTANEOUS_IDLE_MS   = SPONTANEOUS_IDLE_MS,
        REPLY_TIMEOUT_MS      = REPLY_TIMEOUT_MS,
    )

    init {
        observeAvatarOverrides()
        observeRoundtableBackground()
    }

    // ── 圆桌背景图 ────────────────────────────────────────────

    private fun observeRoundtableBackground() {
        viewModelScope.launch {
            chatBgStore.configFlow(ROUNDTABLE_BG_SENTINEL_ID)
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
        }
    }

    // ── 头像同步 ──────────────────────────────────────────────

    private fun observeAvatarOverrides() {
        viewModelScope.launch {
            identityDao.observeAll().collectLatest { entities ->
                val avatarMap = entities
                    .filter { !it.avatarUrl.isNullOrEmpty() }
                    .associate { it.characterId to it.avatarUrl }
                if (avatarMap.isNotEmpty()) {
                    _uiState.update { state ->
                        val updatedMothers = state.allMotherMembers.map { char ->
                            val url = avatarMap[char.id]
                            if (url != null && url != char.avatarUrl) char.copy(avatarUrl = url) else char
                        }.toImmutableList()
                        val updatedDaughters = state.extraDaughterMembers.map { char ->
                            val url = avatarMap[char.id]
                            if (url != null && url != char.avatarUrl) char.copy(avatarUrl = url) else char
                        }.toImmutableList()
                        state.copy(
                            allMotherMembers     = updatedMothers,
                            extraDaughterMembers = updatedDaughters,
                        )
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  成员初始化
    // ──────────────────────────────────────────────────────────

    /**
     * 观察多个角色的合并心迹时间线（E0 分层收口）。
     *
     * 原 RoundtableScreen 直接持有 AppContainer.instance.agentActivityRepo
     * 调 observeTimelineForCharacters()，现收敛到 ViewModel；UI 侧只订阅本方法
     * 返回的 Flow（E0 coupling_scan 违规点 #2 的修复落地）。
     */
    fun observeTimelineForCharacters(
        characterIds: List<Int>,
    ): kotlinx.coroutines.flow.Flow<List<com.zaijian.zhoumuyun.data.repository.AgentActivityTimelineItem>> =
        agentActivityRepo.observeTimelineForCharacters(characterIds)

    fun setMembers(characterIds: List<Int>) {
        viewModelScope.launch {
            val mothers   = mutableListOf<CharacterConfig>()
            val daughters = mutableListOf<CharacterConfig>()
            for (id in characterIds) {
                val cfg = resolveCharacterConfig(id) ?: continue
                if (id >= 1000) daughters.add(cfg) else mothers.add(cfg)
            }
            _uiState.update {
                it.copy(
                    allMotherMembers     = mothers.toImmutableList(),
                    extraDaughterMembers = daughters.toImmutableList(),
                    blockedMemberIds     = persistentSetOf(),
                )
            }
            val newRoundtableId = characterIds.sorted().joinToString("_")
            if (newRoundtableId != currentRoundtableId) {
                currentRoundtableId = newRoundtableId
                loadPersistedMessages(newRoundtableId)
            }
            refreshAvailableDaughters()
            applyAvatarOverridesOnce()
        }
    }

    private suspend fun applyAvatarOverridesOnce() {
        val entities = identityDao.getAll()
        val avatarMap = entities
            .filter { !it.avatarUrl.isNullOrEmpty() }
            .associate { it.characterId to it.avatarUrl }
        if (avatarMap.isNotEmpty()) {
            _uiState.update { state ->
                val updatedMothers = state.allMotherMembers.map { char ->
                    val url = avatarMap[char.id]
                    if (url != null && url != char.avatarUrl) char.copy(avatarUrl = url) else char
                }.toImmutableList()
                val updatedDaughters = state.extraDaughterMembers.map { char ->
                    val url = avatarMap[char.id]
                    if (url != null && url != char.avatarUrl) char.copy(avatarUrl = url) else char
                }.toImmutableList()
                state.copy(
                    allMotherMembers     = updatedMothers,
                    extraDaughterMembers = updatedDaughters,
                )
            }
        }
    }

    fun refreshAvailableDaughters() {
        viewModelScope.launch {
            val ids = daughterCharacterRepo.getAllDaughterCharacterIds()
            val configs = ids.mapNotNull { id ->
                try {
                    daughterCharacterRepo.getCharacterConfig(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ZLog.w("RoundtableViewModel", "拉入候选女儿 $id 解析失败，跳过", e)
                    null
                }
            }
            _uiState.update { it.copy(availableDaughterMembers = configs.toImmutableList()) }
        }
    }

    private suspend fun loadPersistedMessages(roundtableId: String) {
        val persisted = withContext(Dispatchers.IO) {
            roundtableMessageDao.getByRoundtable(roundtableId)
        }
        if (persisted.isEmpty()) return
        val messages = persisted.map { e ->
            RoundtableMessage(
                id              = e.id,
                speakerId       = e.speakerId,
                speakerName     = e.speakerName,
                content         = e.content,
                createdAt       = e.createdAt,
                isStreaming     = false,
                replyTargetId   = e.replyTargetId,
                replyTargetName = e.replyTargetName,
                turnIndex       = e.turnIndex,
                thinkingText    = e.thinkingText,
                psychText       = e.psychText,
                // v1.39 圆桌工具调用接入：DB→内存的反向映射，此前遗漏会导致
                // 新生成的消息能看到文件卡片，但重新打开圆桌页面重新加载历史
                // 消息后卡片消失（只有 toEntity 单向映射，没有这一侧 fromEntity）。
                exportedFileJson = e.exportedFileJson,
                // v66（1.7 P3）：同理，多文件字段也要在这一侧补上，否则同样的
                // "新消息能看到、重新加载后消失"问题会在多文件卡片上重演一遍。
                exportedFilesJson = e.exportedFilesJson,
                // P1-15 修复：v67 表格直传 W4 接入时只顾上了 toEntity（内存→DB）
                // 单向映射，忘了在这一侧（DB→内存）补上，reversed 出的正是
                // exportedFileJson 当年踩过的同一个坑——生成时 TableCard 正常
                // 显示，重新打开圆桌页面重新加载历史消息后，本条 RoundtableMessage
                // 的 tableDataJson 恒为构造函数默认值 null，表格卡片"消失"。
                tableDataJson = e.tableDataJson,
            )
        }
        val maxTurn = messages.maxOf { it.turnIndex }
        val lastTurnSpeakers = messages
            .filter { it.turnIndex == maxTurn && it.speakerId != "user" }
            .mapNotNull { it.speakerId.toIntOrNull() }
            .toSet()
        _uiState.update {
            it.copy(
                messages          = messages.toImmutableList(),
                turnIndex         = maxTurn,
                lastRoundSpeakers = lastTurnSpeakers.toImmutableSet(),
            )
        }
    }

    private suspend fun resolveCharacterConfig(characterId: Int): CharacterConfig? =
        DefaultCharacters.firstOrNull { it.id == characterId }
            ?: try {
                daughterCharacterRepo.getCharacterConfig(characterId)
            } catch (e: Exception) {
                ZLog.e("RoundtableViewModel", "characterId=$characterId 女儿数据加载失败", e)
                null
            }

    // ──────────────────────────────────────────────────────────
    //  屏蔽制成员管理
    // ──────────────────────────────────────────────────────────

    fun blockMember(characterId: Int) {
        _uiState.update { it.copy(blockedMemberIds = (it.blockedMemberIds + characterId).toImmutableSet()) }
    }

    fun unblockMember(characterId: Int) {
        _uiState.update { it.copy(blockedMemberIds = (it.blockedMemberIds - characterId).toImmutableSet()) }
    }

    fun addDaughter(characterId: Int) {
        if (characterId < 1000) return
        if (_uiState.value.extraDaughterMembers.any { it.id == characterId }) return
        viewModelScope.launch {
            val cfg = resolveCharacterConfig(characterId) ?: return@launch
            _uiState.update { it.copy(extraDaughterMembers = (it.extraDaughterMembers + cfg).toImmutableList()) }
        }
    }

    fun removeDaughter(characterId: Int) {
        _uiState.update {
            it.copy(extraDaughterMembers = it.extraDaughterMembers.filter { m -> m.id != characterId }.toImmutableList())
        }
    }

    fun setScheduleMode(mode: ScheduleMode) {
        _uiState.update { it.copy(scheduleMode = mode) }
        prefs.edit().putString("schedule_mode", mode.name).apply()
    }

    fun toggleSettingsSheet(show: Boolean) {
        _uiState.update { it.copy(showSettingsSheet = show) }
    }

    // ──────────────────────────────────────────────────────────
    //  S-4 委托：消息发送编排
    // ──────────────────────────────────────────────────────────

    /** S-4 拆分：委托给 [RoundtableMessageOrchestrator] */
    fun sendMessage(text: String) {
        messageOrchestrator.sendMessage(
            text               = text,
            generateBotReply   = { bot, userMsg, alreadyReplied, turnIdx, intent, provider, isNotified ->
                botReplyGenerator.generateBotReply(
                    bot, userMsg, alreadyReplied, turnIdx, intent, provider, isNotified,
                )
            },
            startIdleWatch     = { idleManager.startIdleWatch() },
        )
    }

    fun parseAtMentions(text: String, activeMembers: List<CharacterConfig>): MentionResult {
        return messageOrchestrator.parseAtMentions(text, activeMembers)
    }

    fun interrupt() {
        messageOrchestrator.interrupt()
    }

    // ──────────────────────────────────────────────────────────
    //  S-4 委托：自发互动模块
    // ──────────────────────────────────────────────────────────

    fun setSpontaneousEnabled(enabled: Boolean) {
        idleManager.setSpontaneousEnabled(enabled)
    }

    fun startIdleWatch() {
        idleManager.startIdleWatch()
    }

    fun stopIdleWatch() {
        idleManager.stopIdleWatch()
    }

    override fun onCleared() {
        super.onCleared()
        stopIdleWatch()
    }

    // ──────────────────────────────────────────────────────────
    //  圆桌背景图操作
    // ──────────────────────────────────────────────────────────

    fun requestRoundtableBackgroundCrop(uri: String) {
        _uiState.update { it.copy(pendingBackgroundCropUri = uri) }
    }

    fun cancelRoundtableBackgroundCrop() {
        _uiState.update { it.copy(pendingBackgroundCropUri = null) }
    }

    fun confirmRoundtableBackgroundCrop(uri: String, offsetX: Float, offsetY: Float, scale: Float) {
        _uiState.update { it.copy(pendingBackgroundCropUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatBgStore.setBackgroundConfig(
                    ROUNDTABLE_BG_SENTINEL_ID,
                    com.zaijian.zhoumuyun.data.datastore.ChatBackgroundConfig(
                        uri     = uri,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        scale   = scale,
                    )
                )
            } catch (e: Exception) {
                ZLog.e("RoundtableViewModel", "圆桌背景图设置失败", e)
            }
        }
    }

    fun clearRoundtableBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatBgStore.clearBackground(ROUNDTABLE_BG_SENTINEL_ID)
            } catch (e: Exception) {
                ZLog.e("RoundtableViewModel", "圆桌背景图清除失败", e)
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
    fun clearApiKeyMissingFlag() = _uiState.update { it.copy(isApiKeyMissing = false) }
}