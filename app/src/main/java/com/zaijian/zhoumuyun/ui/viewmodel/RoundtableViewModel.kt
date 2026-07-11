package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import android.app.Application
import com.zaijian.zhoumuyun.util.ZLog
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.repository.AgentPlanRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.LearningGoalRepository
import com.zaijian.zhoumuyun.data.repository.RoundtableMessageRepository
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.domain.RelationshipEngine
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.prompt.PromptOrchestrator
import com.zaijian.zhoumuyun.data.repository.CharacterStateRepository
import com.zaijian.zhoumuyun.data.repository.MenstrualCycleRepository
import com.zaijian.zhoumuyun.data.manager.PregnancyTriggerManager
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.domain.scheduler.ScheduleContext
import com.zaijian.zhoumuyun.domain.scheduler.SpeakIntent
import com.zaijian.zhoumuyun.domain.scheduler.TurnScheduler
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import java.util.UUID

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
)

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
    val blockedMotherIds: ImmutableSet<Int> = persistentSetOf(),
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
        get() = (allMotherMembers.filter { it.id !in blockedMotherIds } + extraDaughterMembers).toImmutableList()
}

// ─────────────────────────────────────────────────────────────
//  RoundtableViewModel（Phase 14 后半升级）
// ─────────────────────────────────────────────────────────────

class RoundtableViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** 圆桌背景图在 ChatBackgroundDataStore 中使用的哨兵 characterId */
        private const val ROUNDTABLE_BG_SENTINEL_ID = -100
    }

    private val prefs = app.getSharedPreferences("roundtable_settings", Context.MODE_PRIVATE)
    private val db             = AppDatabase.getInstance(app)

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

    // Phase 3 修复手册：以下 5 项改从 AppContainer 取现成实例，不再各自 new
    // （原先与 ChatViewModel 逐行重复的装配逻辑，见审计报告 Phase 3）
    private val container = AppContainer.instance
    private val memoryRepo         get() = container.memoryRepo
    private val eventRepo          get() = container.eventRepo
    private val memoryEngine       get() = container.memoryEngine
    private val relationshipEngine get() = container.relationshipEngine
    private val pregnancyRepo      get() = container.pregnancyRepo
    private val characterStateRepo get() = container.characterStateRepo
    // PregnancyTriggerManager：仅用于 shouldInjectMiscarriageContext()（与 ChatViewModel 一致）。
    // 这一项不进 AppContainer——ChatViewModel 传 aiJudge、这里不传，是维持现状的
    // 真实功能差异（圆桌场景不开启"受孕窗口同意弹窗"AI判定，见审计报告 Phase 3 决策 2），
    // 依赖仍取容器共享实例。
    private val pregnancyTriggerManager = PregnancyTriggerManager(
        db                  = db,
        pregnancyRepository = pregnancyRepo,
        cycleRepository      = MenstrualCycleRepository(db.menstrualCycleDao()),
        stateRepository       = characterStateRepo,
    )
    // 报告第5条：PresenceEngine 收敛，改从 AppContainer 取（与上面 6 项共享
    // 实例同一套模式），不再直接访问 ZaijianApp.sharedPresenceEngine 全局单例。
    // 原为可空类型是因为全局单例存在"onCreate 尚未跑完"的理论空窗期，容器化
    // 后不再有这个问题（ViewModel 能被构造，说明 onCreate 早已跑完）。
    private val presenceEngine: PresenceEngine get() = container.presenceEngine
    // Phase 3 修复手册第3条：agentPlanDao/roundtableMessageDao/identityDao 原先是
    // 裸持有的 DAO 字段，现在改包一层 Repository，字段名保持不变——本文件内
    // agentPlanDao.getActive(...)/roundtableMessageDao.insert(...)/identityDao.getAll()
    // 等调用点方法名与新 Repository 完全一致，不用跟着改名。
    private val agentPlanDao = AgentPlanRepository(db.agentPlanDao())
    // 审计报告 Phase 3 附带修复：原 1017 行 db.learningGoalDao().getActive(...) 裸调用，
    // 新建 LearningGoalRepository 包装（与 agentPlanDao/roundtableMessageDao/identityDao
    // 同一模式），字段名沿用 learningGoalDao，仅此一处调用点，未牵动其他文件。
    private val learningGoalDao = LearningGoalRepository(db.learningGoalDao())
    // 报告第6条收口：daughterCharacterRepo 原先独立 new（dao = db.daughterCharacterDao()），
    // 与 AppContainer.daughterCharacterRepo 构造参数完全一致，改为引用容器共享实例，
    // 字段名保持不变，本文件内调用点不用跟着改。
    private val daughterCharacterRepo get() = container.daughterCharacterRepo
    private val roundtableMessageDao = RoundtableMessageRepository(db.roundtableMessageDao())
    private val identityDao = IdentityRepository(db.characterIdentityDao())
    private var currentRoundtableId: String? = null

    // ── 圆桌背景图：复用 ChatBackgroundDataStore ──────────────────
    // 圆桌是多角色场景，没有单一 characterId，给它一个不会跟真实角色 id
    // 冲突的固定哨兵值（DefaultCharacters 母角色 id 为 1..6，女儿角色 id
    // ≥ 1000，均为正数，-100 不可能撞上），语义上是"圆桌房间背景"，
    // 跟单聊背景是存储层里平行的两套配置，不需要改动 ChatBackgroundDataStore
    // 本身一行代码。
    private val chatBgStore = com.zaijian.zhoumuyun.data.datastore.ChatBackgroundDataStore(app)

    private var roundJob: Job? = null
    @Volatile
    private var isInterrupted = false

    private val SENTENCE_BREAK_CHARS = setOf('。', '？', '！', '…', '；', '.', '?', '!', ';')
    private val FULL_MENTION_KEYWORDS = listOf("@全部", "@所有人", "@大家", "@全员", "@everyone")

    // 自动连续讨论的安全上限（不含初始轮）
    private val AUTO_DISCUSSION_MAX_EXTRA_ROUNDS = 6
    // 裁判 prompt 超时（毫秒）
    private val JUDGE_TIMEOUT_MS = 8_000L
    private val REPLY_TIMEOUT_MS = 60_000L
    // ── 自发互动：空闲计时 Job ──────────────────────────────
    private var idleWatchJob: Job? = null
    private val SPONTANEOUS_IDLE_MS = 30_000L   // 30 秒无输入触发
    // 裁判 prompt 参考的最近消息条数
    private val JUDGE_CONTEXT_MESSAGES = 12

    init {
        observeAvatarOverrides()
        observeRoundtableBackground()
    }

    // 圆桌背景图：订阅哨兵 key 对应的背景配置（URI + 取景偏移/缩放），
    // 与 ChatViewModel 里按角色订阅的写法一致，只是 characterId 固定为
    // ROUNDTABLE_BG_SENTINEL_ID。圆桌只有一个全局房间背景，不随成员
    // 增减变化，所以放在 init 里订阅一次即可，不需要像 setMembers 那样
    // 每次成员变化都重新订阅。
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

    // ── 头像同步：监听 character_identity 表的 avatarUrl 变更 ──
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
                    blockedMotherIds     = persistentSetOf(),
                )
            }
            val newRoundtableId = characterIds.sorted().joinToString("_")
            if (newRoundtableId != currentRoundtableId) {
                currentRoundtableId = newRoundtableId
                loadPersistedMessages(newRoundtableId)
            }
            refreshAvailableDaughters()
            // 成员列表填充后，主动查一次 DB 头像覆盖
            // （observeAvatarOverrides 的 Flow 首次 emission 时成员列表为空，需此处补偿）
            applyAvatarOverridesOnce()
        }
    }

    // 主动查询 DB 并覆盖头像（弥补 Flow 首次 emission 时机问题）
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

    /**
     * Step 5：刷新设置面板"拉入女儿"区域的候选池。
     * 拉取所有已完成注册的女儿 id，逐个解析成 CharacterConfig；
     * 单条解析失败（数据损坏）跳过该条，不影响其余候选正常显示。
     */
    fun refreshAvailableDaughters() {
        viewModelScope.launch {
            val ids = daughterCharacterRepo.getAllDaughterCharacterIds()
            val configs = ids.mapNotNull { id ->
                try {
                    daughterCharacterRepo.getCharacterConfig(id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e  // P1-11-4 修复：CancellationException 必须 rethrow，不能被吞掉
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

    private suspend fun resolveCharacterConfig(characterId: Int): CharacterConfig? =
        DefaultCharacters.firstOrNull { it.id == characterId }
            ?: daughterCharacterRepo.getCharacterConfig(characterId)

    // ──────────────────────────────────────────────────────────
    //  屏蔽制成员管理（Step 2）
    // ──────────────────────────────────────────────────────────

    fun blockMother(characterId: Int) {
        if (characterId >= 1000) return
        _uiState.update { it.copy(blockedMotherIds = (it.blockedMotherIds + characterId).toImmutableSet()) }
    }

    fun unblockMother(characterId: Int) {
        _uiState.update { it.copy(blockedMotherIds = (it.blockedMotherIds - characterId).toImmutableSet()) }
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
    //  @mention 解析（Step 2）
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
    //  Step 3：全体@ 轻量意图判定
    //  （设计方案第三触发条件：无@但明显是"布置任务+要求讨论"）
    // ──────────────────────────────────────────────────────────

    /**
     * 轻量 AI 判断：用户消息是否属于"布置任务/要求全体讨论"但没有明确 @任何人。
     *
     * 返回 true → 视为全体@，触发自动连续讨论。
     * 失败/超时 → 返回 false（fallback 当无@ 处理，不阻塞主流程）。
     *
     * 仅在 mentionedIds 为空时调用，避免冗余判定。
     */
    private suspend fun judgeIsGroupTask(
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // P1-11-4 修复：CancellationException 必须 rethrow
        } catch (e: Exception) {
            ZLog.d("RoundtableViewModel", "judgeIsGroupTask 裁判调用失败，降级为 false", e)
            false
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Step 3：讨论收敛裁判
    // ──────────────────────────────────────────────────────────

    /**
     * 轻量 AI 裁判：判断最近几轮讨论是否已收敛（有共识/方案可执行了）。
     *
     * 返回 true  → 结束自动续轮，把控制权还给用户。
     * 返回 false → 继续下一轮。
     * 失败/超时  → 返回 true（保守策略：宁可提前结束，不让循环失控）。
     *
     * [recentMessages] 最近 JUDGE_CONTEXT_MESSAGES 条圆桌消息的文本摘要。
     * [originalUserMessage] 触发本次全体@讨论的原始用户消息，用于帮助 AI 裁判
     *                       对齐"是否解决了用户的问题"这个判断锚点。
     */
    private suspend fun judgeDiscussionConcluded(
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // P1-11-4 修复：CancellationException 必须 rethrow
        } catch (e: Exception) {
            ZLog.d("RoundtableViewModel", "judgeDiscussionConcluded 裁判调用失败，降级为 true（保守结束）", e)
            true          // 异常 → 保守结束
        }
    }

    // ──────────────────────────────────────────────────────────
    //  发送消息（触发一轮序贯生成 + 可能的自动续轮）
    // ──────────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val members = _uiState.value.activeMembers
        if (members.isEmpty()) return

        val provider = ProviderManager.instance.activeProvider
        if (provider == null) {
            _uiState.update { it.copy(isApiKeyMissing = true) }
            return
        }

        isInterrupted = false
        roundJob?.cancel()
        roundJob = viewModelScope.launch {
            // B-5 修复：try-finally 保证无论 judgeIsGroupTask / executeRound /
            // 讨论循环中任何位置抛出异常（含网络错误），都能重置
            // isScheduling 和 waitingForUser，避免输入框永久禁用。
            // CancellationException 越过 catch 直达 finally，结构化并发正常传播。
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
                currentRoundtableId?.let { rtId ->
                    launch(Dispatchers.IO) { roundtableMessageDao.insert(userMsg.toEntity(rtId)) }
                }

                // 2. 加载关系矩阵 & 情绪
                val memberIds = members.map { it.id }
                val relationshipMatrix = withContext(Dispatchers.IO) {
                    relationshipEngine.getInterCharacterMatrix(memberIds)
                }
                val moodMap: Map<Int, Float> = members.associate { bot ->
                    val moodType = presenceEngine.getCachedPresence(bot.id)?.mood
                    val moodFloat = when (moodType) {
                        com.zaijian.zhoumuyun.domain.MoodType.EXCITED    ->  0.9f
                        com.zaijian.zhoumuyun.domain.MoodType.SATISFIED  ->  0.6f
                        com.zaijian.zhoumuyun.domain.MoodType.CURIOUS    ->  0.6f
                        com.zaijian.zhoumuyun.domain.MoodType.FOCUSED    ->  0.3f
                        com.zaijian.zhoumuyun.domain.MoodType.CALM       ->  0.0f
                        com.zaijian.zhoumuyun.domain.MoodType.REFLECTIVE ->  0.0f
                        com.zaijian.zhoumuyun.domain.MoodType.TIRED      -> -0.6f
                        com.zaijian.zhoumuyun.domain.MoodType.CONCERNED  -> -0.9f
                        null                                                   ->  0.0f
                    }
                    bot.id to moodFloat
                }

                // AI apiCall 包装（调度 + 裁判共用）
                val aiApiCall: suspend (String) -> String = { prompt ->
                    provider.chatSyncWithRetry(
                        messages     = listOf(LLMMessage("user", prompt)),
                        systemPrompt = "你是一个助手，只返回要求格式的内容，不要其他文字。",
                        config       = LLMConfig(model = "", maxTokens = 100, temperature = 0.2f, stream = false),
                    )
                }

                // 3. 解析 @mention，+ Step 3 意图判定（第三触发条件）
                var mentionResult = parseAtMentions(text, members)
                // 第三触发条件：无@但消息明显是群体任务 → 视为全体@
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
                    plans             = initialPlans,
                    members           = members,
                    userMessage       = text,
                    turnIdx           = turnIdx,
                    memberIds         = memberIds,
                    relationshipMatrix = relationshipMatrix,
                    provider          = provider,
                    mentionedIds      = mentionResult.mentionedIds,
                )

                if (isInterrupted) {
                    finishDiscussion()
                    return@launch
                }

                // 6. 全体@ → 自动连续讨论循环
                if (isFullMention) {
                    var extraRound = 0
                    var currentTurnIdx = turnIdx
                    val originalUserMessage = text

                    while (extraRound < AUTO_DISCUSSION_MAX_EXTRA_ROUNDS && !isInterrupted) {
                        // 裁判判断是否收敛
                        val recentMsgs = _uiState.value.messages
                        val concluded = judgeDiscussionConcluded(recentMsgs, originalUserMessage, aiApiCall)
                        if (concluded) break

                        extraRound++
                        currentTurnIdx++
                        val roundLabel = extraRound + 1  // UI 显示：第2轮、第3轮…

                        _uiState.update {
                            it.copy(
                                discussionRound = roundLabel,
                                turnIndex       = currentTurnIdx,
                            )
                        }

                        // 续轮用固定追问 prompt（不写入消息列表，避免用户看到机械追问）
                        val continuePrompt = "请结合前面各位的发言，继续完善方案，争取形成共识。"

                        // 续轮重新调度（沿用全员不截断，但用 continuePrompt 更新 userMessage）
                        val continueCtx = baseCtx.copy(
                            userMessage       = continuePrompt,
                            lastRoundSpeakers = _uiState.value.lastRoundSpeakers,
                        )
                        val continuePlans = TurnScheduler.scheduleFullMention(continueCtx)
                        if (continuePlans.isEmpty() || isInterrupted) break

                        executeRound(
                            plans              = continuePlans,
                            members            = members,
                            // 续轮的 userMessage 用追问 prompt，让 generateBotReply 的
                            // history 末尾插入它，而不是原始用户消息
                            userMessage        = continuePrompt,
                            turnIdx            = currentTurnIdx,
                            memberIds          = memberIds,
                            relationshipMatrix = relationshipMatrix,
                            provider           = provider,
                        )
                    }

                    // 安全上限到了还没收敛 → 友好提示
                    if (extraRound >= AUTO_DISCUSSION_MAX_EXTRA_ROUNDS && !isInterrupted) {
                        val totalRounds = extraRound + 1
                        _uiState.update {
                            it.copy(error = "讨论了 $totalRounds 轮还没有定论，要不要你来定个方向？")
                        }
                    }
                }

                finishDiscussion()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "发生错误，请重试") }
            } finally {
                // B-5 修复：保证 isScheduling / waitingForUser 在任何路径下都被重置。
                // 正常路径 finishDiscussion() 已先执行，finally 里的 update 是幂等的。
                _uiState.update {
                    it.copy(
                        isScheduling    = false,
                        waitingForUser  = true,
                        isAutoDiscussing = false,
                        discussionRound = 0,
                    )
                }
            }
        }
    }

    /**
     * 一轮的调度计划计算（根据 scheduleMode 选策略）。
     *
     * 修复（核对发现）：三个模式必须统一走 [TurnScheduler.scheduleAuto]，
     * 否则 HEURISTIC 模式会绕过三分支判断（全体@/部分@/无@），
     * 直接退化成无@分支的截断打分——用户切到"启发式"后，即使消息里
     * @全部 或明确要求全员讨论，也不会触发全体@分支和自动连续讨论循环。
     *
     * HEURISTIC 模式通过不传 apiCall（传 null）保持"零 API 消耗"的特性：
     * - 全体@/部分@分支本身不调用 apiCall，不受影响
     * - 无@分支在 apiCall 为 null 时，scheduleAuto 内部会直接走 scheduleHeuristic，
     *   不会意外触发 AI 调度
     */
    private suspend fun schedulePlans(ctx: ScheduleContext): List<com.zaijian.zhoumuyun.domain.scheduler.SpeakPlan> {
        val provider = ProviderManager.instance.activeProvider
            ?: return TurnScheduler.scheduleAuto(ctx, apiCall = null)
        val aiApiCall: suspend (String) -> String = { prompt ->
            provider.chatSyncWithRetry(
                messages     = listOf(LLMMessage("user", prompt)),
                systemPrompt = "你是一个调度助手，只返回 JSON，不要任何其他文字。",
                config       = LLMConfig(model = "", maxTokens = 200, temperature = 0.3f, stream = false),
            )
        }
        return when (_uiState.value.scheduleMode) {
            ScheduleMode.AUTO      -> TurnScheduler.scheduleAuto(ctx, aiApiCall)
            ScheduleMode.HEURISTIC -> TurnScheduler.scheduleAuto(ctx, apiCall = null)
            ScheduleMode.AI_ONLY   -> TurnScheduler.scheduleAuto(ctx, aiApiCall)
        }
    }

    /**
     * 执行一轮序贯生成（初始轮 & 续轮均调用此函数）。
     *
     * 完成后自动更新 lastRoundSpeakers 和角色关系。
     * 不修改 waitingForUser / isAutoDiscussing，由调用方负责。
     */
    private suspend fun executeRound(
        plans: List<com.zaijian.zhoumuyun.domain.scheduler.SpeakPlan>,
        members: List<CharacterConfig>,
        userMessage: String,
        turnIdx: Int,
        memberIds: List<Int>,
        relationshipMatrix: Map<String, com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity>,
        provider: com.zaijian.zhoumuyun.data.provider.LLMProvider,
        mentionedIds: Set<Int> = emptySet(),
    ) {
        // 初始化 Bot 状态指示器
        val initStatus = members.associate { bot ->
            val plan = plans.firstOrNull { it.characterId == bot.id }
            bot.id to (if (plan != null) BotGenerationStatus.WAITING else BotGenerationStatus.IDLE)
        }
        _uiState.update { it.copy(generationStatus = initStatus.toImmutableMap()) }

        val alreadyReplied = mutableMapOf<Int, String>()

        for (plan in plans) {
            if (isInterrupted) break
            val bot = members.firstOrNull { it.id == plan.characterId } ?: continue

            _uiState.update { s ->
                s.copy(generationStatus = (s.generationStatus + (bot.id to BotGenerationStatus.GENERATING)).toImmutableMap())
            }

            try {
                generateBotReply(
                    bot            = bot,
                    userMessage    = userMessage,
                    alreadyReplied = alreadyReplied,
                    turnIdx        = turnIdx,
                    intent         = plan.initialIntent,
                    provider       = provider,
                    isNotified     = bot.id in mentionedIds,
                )?.let { fullReply -> alreadyReplied[bot.id] = fullReply }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "${bot.name} 回复出了点问题") }
            }

            _uiState.update { s ->
                s.copy(generationStatus = (s.generationStatus + (bot.id to BotGenerationStatus.DONE)).toImmutableMap())
            }
        }

        // 更新角色间关系
        val speakerIds = plans.map { it.characterId }
        if (speakerIds.isNotEmpty()) {
            val conflictPairs = mutableListOf<Pair<Int, Int>>()
            for (i in speakerIds.indices) {
                for (j in i + 1 until speakerIds.size) {
                    val idA = speakerIds[i]; val idB = speakerIds[j]
                    // 此处原先双向兜底查询（正反 key 都试）本身是安全的，不受
                    // P1-13-7 影响。改用 relKey 统一写法，与 TurnScheduler /
                    // RelationshipEngine 保持一致，减少日后误改出分裂实现的风险。
                    val conflict = relationshipMatrix[RelationshipEngine.relKey(idA, idB)]?.conflict ?: 0
                    if (conflict >= 50) conflictPairs.add(idA to idB)
                }
            }
            withContext(Dispatchers.IO) {
                relationshipEngine.onRoundtableRoundEnd(speakerIds, memberIds, conflictPairs)
            }
        }

        _uiState.update { it.copy(lastRoundSpeakers = speakerIds.toImmutableSet()) }
    }

    /**
     * 结束当前讨论（无论是正常结束、收敛、安全上限还是被中断）。
     * 统一把 waitingForUser 置 true、清除讨论状态。
     */
    private fun finishDiscussion() {
        _uiState.update {
            it.copy(
                waitingForUser  = true,
                isAutoDiscussing = false,
                discussionRound = 0,
            )
        }
    }

    // ──────────────────────────────────────────────────────────
    //  中断
    // ──────────────────────────────────────────────────────────

    /**
     * 用户在 Bot 生成中发新消息时调用。
     * 将 isInterrupted 设为 true，流式收集循环和续轮循环都会在下一个安全点停止。
     */
    fun interrupt() {
        isInterrupted = true
    }

    // ──────────────────────────────────────────────────────────
    //  单个 Bot 生成
    // ──────────────────────────────────────────────────────────

    private suspend fun generateBotReply(
        bot: CharacterConfig,
        userMessage: String,
        alreadyReplied: Map<Int, String>,
        turnIdx: Int,
        intent: SpeakIntent,
        provider: com.zaijian.zhoumuyun.data.provider.LLMProvider,
        isNotified: Boolean = false,
    ): String? {

        val coreMemories     = memoryRepo.getCoreMemories(bot.id)
        val relevantMemories = memoryRepo.searchRelevant(bot.id, userMessage, limit = 8)

        // ── 群记忆查询（圆桌专用，scope=GROUP）──
        val rtId = currentRoundtableId
        val groupCoreMemories = if (rtId != null) memoryRepo.getGroupCoreMemories(rtId) else emptyList()
        val groupRelevantMemories = if (rtId != null) memoryRepo.searchGroupRelevant(rtId, userMessage, limit = 6) else emptyList()

        val relationshipSnapshot = relationshipEngine.buildPromptSnapshot(bot.id)

        val memberNameMap = _uiState.value.activeMembers.associate { it.id to it.name }
        // 待办6 Step4：把 isAutoDiscussing/discussionRound 透传给 buildGroupContextBlock，
        // 用于在续轮场景追加收敛引导文案。两者都是 Step3 已有的 uiState 字段，
        // 这里只是读取后透传，不引入新的数据结构。
        val groupContextBlock = PromptOrchestrator.buildGroupContextBlock(
            alreadyReplied     = alreadyReplied,
            memberNameMap      = memberNameMap,
            respondingOtherBot = intent == SpeakIntent.RESPOND_OTHER_BOT,
            isAutoDiscussing   = _uiState.value.isAutoDiscussing,
            discussionRound    = _uiState.value.discussionRound,
            notifiedByName     = if (isNotified) "用户" else null,
        )

        val roundtableMemberIds = _uiState.value.activeMembers.map { it.id }.filter { it != bot.id }
        val interCharRel = withContext(Dispatchers.IO) {
            relationshipEngine.buildInterCharacterSnapshot(
                forCharacterId = bot.id,
                memberIds      = roundtableMemberIds,
                nameMap        = memberNameMap,
            )
        }

        val identityEntity  = identityDao.getById(bot.id)
        val pregnancyState  = pregnancyRepo.getPregnancy(bot.id)
        // ── 补全 characterState（深层状态，与 ChatViewModel 这次的修法对齐）──
        val characterState  = characterStateRepo.getState(bot.id)
        // ── 补全 State Layer（presence 在场状态，和 ChatViewModel 这次的修法对齐）──
        // presence fallback：缓存为空时主动计算一次，结果写入缓存供后续轮次复用
        var presenceSnap = presenceEngine?.getCachedPresence(bot.id)
        if (presenceSnap == null) {
            presenceSnap = presenceEngine?.refreshPresence(bot.id, characterState)
        }
        // ── 补全 miscarriageAftermathPatch（圆桌场景：isOneOnOne 取决于当前
        //    除该角色外是否还有其他角色在场，与 CharacterStateRepository.applySocialMode
        //    判定 SocialMode 的口径一致——roundtableMemberIds 为空才算一对一）──
        val miscarriageAftermathPatch = pregnancyTriggerManager.shouldInjectMiscarriageContext(
            pregnancyState = pregnancyState,
            userText       = userMessage,
            isOneOnOne     = roundtableMemberIds.isEmpty(),
            pressureScale  = 1.0f,
        ) ?: ""
        val otherPregnantNames = withContext(Dispatchers.IO) {
            _uiState.value.activeMembers
                .filter { it.id != bot.id }
                .mapNotNull { m -> pregnancyRepo.getPregnancy(m.id).takeIf { it.isPregnant }?.let { m.name } }
        }
        val pregnancyAwarenessBlock = PromptOrchestrator.buildPregnancyAwarenessLine(otherPregnantNames)

        // ── 补全 AgentPlan Layer（角色自己写的进化方案）──
        val activePlan = agentPlanDao.getActive(bot.id)
        val agentPlanBlock = activePlan?.let {
            PromptOrchestrator.buildAgentPlanBlock(it.title, it.content)
        } ?: ""

        // ── 补全 LearningGoal Layer（isLocked=true 的能力规则，按目标分组）──
        val activeGoals = learningGoalDao.getActive(bot.id)
        val rulesByGoal = activeGoals.associate { goal ->
            goal.title to memoryRepo
                .getLockedRules(bot.id, goal.id)
                .map { it.content }
        }
        val ruleLayerBlock = PromptOrchestrator.buildRuleLayerBlock(rulesByGoal)

        val systemPrompt = PromptOrchestrator.buildSystemPrompt(
            character               = bot,
            identityEntity          = identityEntity,
            coreMemories            = coreMemories,
            relevantMemories        = relevantMemories,
            groupCoreMemories       = groupCoreMemories,
            groupRelevantMemories   = groupRelevantMemories,
            presenceActivity        = presenceSnap?.activity ?: "",
            presenceFocus           = presenceSnap?.goalTitle ?: "",
            presenceMood            = presenceSnap?.mood?.name ?: "",
            presenceEnergy          = presenceSnap?.energy ?: -1,
            relationshipSnapshot    = relationshipSnapshot,
            groupContextBlock       = groupContextBlock,
            interCharRelBlock       = interCharRel,
            agentPlanBlock          = agentPlanBlock,
            ruleLayerBlock          = ruleLayerBlock,
            pregnancyState          = pregnancyState,
            characterState          = characterState,
            miscarriageAftermathPatch = miscarriageAftermathPatch,
            pregnancyAwarenessBlock = pregnancyAwarenessBlock,
        )

        // 历史：按轮次取最近 20 轮
        val candidateHistory = _uiState.value.messages
            .filter { it.speakerId != "user" || it.turnIndex < turnIdx }
        val recentTurnIndexes = candidateHistory
            .map { it.turnIndex }.distinct().sortedDescending().take(20).toSet()
        val history = candidateHistory
            .filter { it.turnIndex in recentTurnIndexes }
            .map { msg ->
                LLMMessage(
                    role    = if (msg.speakerId == "user") "user" else "assistant",
                    content = if (msg.speakerId == "user") msg.content
                              else "[${msg.speakerName}] ${msg.content}",
                )
            } + LLMMessage("user", userMessage)

        val lastSpeakerEntry = if (intent == SpeakIntent.RESPOND_OTHER_BOT) alreadyReplied.keys.lastOrNull() else null
        val replyTargetName  = lastSpeakerEntry?.let { memberNameMap[it] }

        val msgId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                messages = (it.messages + RoundtableMessage(
                    id              = msgId,
                    speakerId       = bot.id.toString(),
                    speakerName     = bot.name,
                    content         = "",
                    isStreaming     = true,
                    turnIndex       = turnIdx,
                    replyTargetId   = lastSpeakerEntry?.toString(),
                    replyTargetName = replyTargetName,
                    isNotified      = isNotified,
                )).toImmutableList()
            )
        }

        var fullReply = ""
        val config = LLMConfig(model = "", maxTokens = 800, temperature = 0.85f, stream = true)
        var interrupted = false

        try {
            withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                provider.chat(history, systemPrompt, config).collect { delta ->
                    if (isInterrupted && fullReply.isNotEmpty()) {
                        if (fullReply.lastOrNull() in SENTENCE_BREAK_CHARS) interrupted = true
                    }
                    if (!interrupted) {
                        fullReply += delta
                        _uiState.update { s ->
                            s.copy(messages = s.messages.map { msg ->
                                if (msg.id == msgId) msg.copy(content = fullReply) else msg
                            }.toImmutableList())
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // P1-11-4 修复：CancellationException 必须 rethrow，结构化并发需要它传播
        } catch (e: Exception) {
            // 超时或其他异常：保留已生成的回复
            ZLog.w("RoundtableViewModel", "流式生成中断（msgId=$msgId），已生成长度=${fullReply.length}", e)
        }

        _uiState.update { s ->
            s.copy(messages = s.messages.map { msg ->
                if (msg.id == msgId) msg.copy(content = fullReply, isStreaming = false) else msg
            }.toImmutableList())
        }

        // 落库
        currentRoundtableId?.let { rtId ->
            if (fullReply.isNotBlank()) {
                viewModelScope.launch(Dispatchers.IO) {
                    roundtableMessageDao.insert(
                        RoundtableMessage(
                            id              = msgId,
                            speakerId       = bot.id.toString(),
                            speakerName     = bot.name,
                            content         = fullReply,
                            turnIndex       = turnIdx,
                            replyTargetId   = lastSpeakerEntry?.toString(),
                            replyTargetName = replyTargetName,
                        ).toEntity(rtId)
                    )
                }
            }
        }

        // 后台记忆提取
        val userEventId = eventRepo.appendMessageEvent(
            actorId     = "user",
            targetId    = bot.id.toString(),
            payloadJson = """{"preview":"${userMessage.take(50)}"}""",
        )
        viewModelScope.launch(Dispatchers.IO) {
            memoryEngine.onConversationTurn(
                characterId    = bot.id,
                userMessage    = userMessage,
                assistantReply = fullReply,
                userEventId    = userEventId,
            )
        }
        // ── 群记忆写入（圆桌专用，scope=GROUP）──
        if (rtId != null && fullReply.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                memoryEngine.onRoundtableTurn(
                    roundtableId   = rtId,
                    speakerId      = bot.id,
                    userMessage    = userMessage,
                    assistantReply = fullReply,
                )
            }
        }

        return fullReply.ifBlank { null }
    }

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
        idleWatchJob?.cancel()
        idleWatchJob = viewModelScope.launch {
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
        }
    }

    /** 停止空闲计时器（Screen 离开前景 / 功能关闭时调用） */
    fun stopIdleWatch() {
        idleWatchJob?.cancel()
        idleWatchJob = null
    }

    // U-8 修复：Screen 使用 LaunchedEffect 启动 idleWatch，导航离开（屏幕仍在 backstack）
    // 时 LaunchedEffect 取消仅影响自身协程，不会停止 viewModelScope 内启动的 idleWatchJob，
    // 原代码注释声称由 onCleared 兜底但实际未重写 onCleared，导致协程泄漏。
    // 这里重写 onCleared 保证 ViewModel 销毁时停止 idleWatch。
    override fun onCleared() {
        super.onCleared()
        stopIdleWatch()
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
    private fun pickSpontaneousInitiator(members: List<com.zaijian.zhoumuyun.data.model.CharacterConfig>): com.zaijian.zhoumuyun.data.model.CharacterConfig? {
        if (members.isEmpty()) return null

        // 情绪权重映射
        val moodWeight: (com.zaijian.zhoumuyun.domain.MoodType?) -> Float = { mood ->
            when (mood) {
                com.zaijian.zhoumuyun.domain.MoodType.EXCITED    -> 5f
                com.zaijian.zhoumuyun.domain.MoodType.CURIOUS    -> 4f
                com.zaijian.zhoumuyun.domain.MoodType.SATISFIED  -> 3f
                com.zaijian.zhoumuyun.domain.MoodType.FOCUSED    -> 2f
                com.zaijian.zhoumuyun.domain.MoodType.CALM       -> 1.5f
                com.zaijian.zhoumuyun.domain.MoodType.REFLECTIVE -> 1f
                com.zaijian.zhoumuyun.domain.MoodType.TIRED      -> 0.3f
                com.zaijian.zhoumuyun.domain.MoodType.CONCERNED  -> 0.5f
                null                                                   -> 1f
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

        var rand = (Math.random() * totalWeight).toFloat()
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
        initiator: com.zaijian.zhoumuyun.data.model.CharacterConfig,
        provider: com.zaijian.zhoumuyun.data.provider.LLMProvider,
    ) {
        val coreMemories     = memoryRepo.getCoreMemories(initiator.id)
        val relationshipSnap = relationshipEngine.buildPromptSnapshot(initiator.id)
        // presence fallback：缓存为空时（角色未打开过单人对话）主动计算一次，
        // 与 generateBotReply 的处理逻辑对齐。
        val characterStateForPresence = characterStateRepo.getState(initiator.id)
        val presenceSnap = presenceEngine.getCachedPresence(initiator.id)
            ?: presenceEngine.refreshPresence(initiator.id, characterStateForPresence)

        // 最近 6 条消息作为上下文摘要
        val recentContext = _uiState.value.messages.takeLast(6).joinToString("\n") { msg ->
            val speaker = if (msg.speakerId == "user") "用户" else msg.speakerName
            "[$speaker]: ${msg.content.take(80)}"
        }

        val spontaneousSystemPrompt = buildString {
            append(com.zaijian.zhoumuyun.data.prompt.PromptOrchestrator.buildSystemPrompt(
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
                characterState          = characterStateRepo.getState(initiator.id),
                miscarriageAftermathPatch = "",
                pregnancyAwarenessBlock = "",
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

        val msgId  = java.util.UUID.randomUUID().toString()
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
        val config = LLMConfig(model = "", maxTokens = 200, temperature = 0.92f, stream = true)

        try {
            withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                provider.chat(
                    messages     = spontaneousHistory,
                    systemPrompt = spontaneousSystemPrompt,
                    config       = config,
                ).collect { delta ->
                    fullReply += delta
                    _uiState.update { s ->
                        s.copy(messages = s.messages.map { msg ->
                            if (msg.id == msgId) msg.copy(content = fullReply) else msg
                        }.toImmutableList())
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // P1-11-4 修复：CancellationException 必须 rethrow
        } catch (e: Exception) {
            ZLog.w("RoundtableViewModel", "自发发言流式生成中断（msgId=$msgId），已生成长度=${fullReply.length}", e)
        }

        _uiState.update { s ->
            s.copy(
                messages = s.messages.map { msg ->
                    if (msg.id == msgId) msg.copy(content = fullReply, isStreaming = false) else msg
                }.toImmutableList(),
                generationStatus = (s.generationStatus + (initiator.id to BotGenerationStatus.DONE)).toImmutableMap(),
            )
        }

        // 落库
        currentRoundtableId?.let { rtId ->
            if (fullReply.isNotBlank()) {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    roundtableMessageDao.insert(
                        RoundtableMessage(
                            id          = msgId,
                            speakerId   = initiator.id.toString(),
                            speakerName = initiator.name,
                            content     = fullReply,
                            turnIndex   = turnIdx,
                        ).toEntity(rtId)
                    )
                }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
    fun clearApiKeyMissingFlag() = _uiState.update { it.copy(isApiKeyMissing = false) }

    // ── 圆桌背景图操作（与 ChatViewModel 对应方法逻辑一致，
    //    唯一区别是固定用 ROUNDTABLE_BG_SENTINEL_ID 代替 currentCharacterId）──

    /** 用户刚从相册选完圆桌背景图（尚未裁剪），触发 UI 弹出裁剪弹窗 */
    fun requestRoundtableBackgroundCrop(uri: String) {
        _uiState.update { it.copy(pendingBackgroundCropUri = uri) }
    }

    /** 用户在裁剪弹窗中点击「取消」，放弃本次换背景 */
    fun cancelRoundtableBackgroundCrop() {
        _uiState.update { it.copy(pendingBackgroundCropUri = null) }
    }

    /** 用户在 AvatarCropDialog 中确认裁剪：写入 URI + 归一化偏移/缩放 */
    fun confirmRoundtableBackgroundCrop(uri: String, offsetX: Float, offsetY: Float, scale: Float) {
        _uiState.update { it.copy(pendingBackgroundCropUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            chatBgStore.setBackgroundConfig(
                ROUNDTABLE_BG_SENTINEL_ID,
                com.zaijian.zhoumuyun.data.datastore.ChatBackgroundConfig(
                    uri     = uri,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    scale   = scale,
                )
            )
        }
    }

    /** 清除圆桌背景图，恢复默认纯色背景 */
    fun clearRoundtableBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            chatBgStore.clearBackground(ROUNDTABLE_BG_SENTINEL_ID)
        }
    }
}
