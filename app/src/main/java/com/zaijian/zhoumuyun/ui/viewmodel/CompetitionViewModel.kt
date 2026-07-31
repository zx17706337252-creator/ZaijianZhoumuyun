package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.util.ZLog
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity
import com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.agent.CompetitionRoundManager
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTING
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTING_IN_PROGRESS
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COLLECTED
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_JUDGING
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus.STATUS_COMPLETED
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CompetitionViewModel — 裁判与竞争机制 · 竞赛屏幕状态管理（窗口 2B）
 *
 * 负责：
 *   - 按 domain 加载历史轮次列表
 *   - 发起新轮次（startRound → runCollecting → runJudging，三步串行）
 *   - 结算（finalizeRound，内部依次调用 private 的 submitUserScoreForEntry
 *     逐条提交打分，再触发奖惩反哺）
 *
 * CompetitionRoundManager 通过私有属性 competitionRoundManager 取（底层读取
 * AppContainer.instance.competitionRoundManager，见该属性声明处的说明）；
 * 未配置时通过 snackbar 提示，不崩溃。
 *
 * 打分输入状态（ScoreInputState / ScoreInputMode）定义在本文件，
 * 供 CompetitionScreen 直接使用，避免跨文件循环依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompetitionViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_DOMAIN = "competition_domain"
        private const val KEY_SELECTED_ROUND = "competition_selected_round"
    }

    // ── 打分模式枚举（UI 层用） ───────────────────────────────────

    enum class ScoreInputMode { SLIDER, RANK, COMMENT }

    /**
     * 单个参赛条目的打分输入状态。
     * 三种模式互斥，提交时 ViewModel 按 activeMode 选取对应字段换算。
     */
    data class ScoreInputState(
        val activeMode: ScoreInputMode = ScoreInputMode.SLIDER,
        val sliderValue: Float = 50f,           // 0f..100f
        val rankPosition: Int = 1,              // 第 k 名（1-based）
        val comment: String = "",               // 纯文字评语
        val hasUserInput: Boolean = false,       // 方案 4-1：标记用户是否真的操作过
    )

    // ── 数据库 ──────────────────────────────────────────────────

    // S8-窗口01 收口：db 裸引用已移除，全部改走 AppContainer 共享的
    // competitionRoundRepo（封装 competitionRoundDao/competitionEntryDao）。
    private val competitionRoundRepo = AppContainer.instance.competitionRoundRepo
    // 阶段2 S-1 批次3收口：daughterRepo 原先独立 new（构造参数与容器完全一致），
    // 改引用 AppContainer 共享实例。
    private val daughterRepo = AppContainer.instance.daughterCharacterRepo

    // 阶段2 S-2 收口：CompetitionRoundManager 原先是 CompetitionViewModel
    // 唯一还直接访问 ZaijianApp.sharedCompetitionRoundManager 全局单例的
    // ViewModel，与 ChatViewModel/RoundtableViewModel 统一走 AppContainer.instance
    // 的模式不一致。此前未收敛的原因是它的装配时机依赖 Provider 就绪、
    // 是 @Volatile var 而非普通的 onCreate 一次性构造 val，与 AppContainer
    // 其余字段性质不同——现已在 AppContainer 内部设计了"可变但受控"的扩展
    // 模式（competitionEngine/competitionRoundManager 为 @Volatile var，
    // 仅通过 reassembleCompetitionEngine() 一处写入，内部 Mutex 保护），
    // 装配触发条件、幂等逻辑与搬家前完全一致，故收敛为容器统一持有。
    private val competitionRoundManager: CompetitionRoundManager?
        get() = AppContainer.instance.competitionRoundManager

    // 问题35修复：manager 为 null 时，区分"用户还没配置 Key（正常未装配）"
    // 和"配置了 Key 但装配过程本身出错"，避免后一种情况误报"请先配置 API Key"
    // 让已经配好 Key 的用户看到文不对题的提示、不知道该怎么办。
    private fun managerUnavailableMessage(): String =
        if (AppContainer.instance.competitionEngineAssemblyFailed) {
            "竞争系统初始化失败，请稍后重试或重启 App"
        } else {
            "请先配置 API Key"
        }

    // ── domain 初始化 ────────────────────────────────────────────

    private val restoredDomain: String = savedStateHandle.get<String>(KEY_DOMAIN) ?: ""
    private val _domainFlow = MutableStateFlow(restoredDomain)
    private var hasRunInit = false

    // ── 已注册女儿角色（Audit-v1.33 P1-1 修复）───────────────────
    //
    // 竞赛发起 UI（CompetitionScreen.allCharacters）此前仅使用 DefaultCharacters，
    // 女儿角色（ID ≥ 1000）虽然 CompetitionRoundManager 底层已完整支持
    // （daughterRepo + resolveCharacterName 回退逻辑），但从未出现在可选列表里。
    // 此处与 WorldSimulation.allCharacterIds() 使用同一套查询模式：
    // getAllDaughterCharacterIds() 取已完成注册的女儿 ID，再逐个 getCharacterConfig()
    // 拼出 CharacterConfig，供 CompetitionScreen 与 DefaultCharacters 合并展示。
    //
    // 加载时机：一次性挂起加载，不做实时 Flow 订阅——竞赛发起页面本身是
    // 短生命周期的一次性表单场景，女儿在此期间新增注册的概率极低，
    // 与 rounds/roundDetail 等需要实时刷新的状态在设计取舍上不同。
    private val _daughterCharacters = MutableStateFlow<List<CharacterConfig>>(emptyList())
    val daughterCharacters: StateFlow<List<CharacterConfig>> = _daughterCharacters.asStateFlow()

    fun init(domain: String) {
        if (hasRunInit && _domainFlow.value == domain) return
        hasRunInit = true
        savedStateHandle[KEY_DOMAIN] = domain
        _domainFlow.value = domain
        // App 启动/恢复时：把崩溃前卡在过程态的轮次回退到入口态，
        // 让重试按钮出现，避免轮次永久卡死。
        // W4-1 修复：原先只处理 JUDGING，遗漏了 COLLECTING_IN_PROGRESS
        // （runCollecting 中途崩溃）。
        //   - JUDGING → COLLECTING：runJudging 本身已支持从 COLLECTING 重入。
        //   - COLLECTING_IN_PROGRESS → COLLECTING：runCollecting 本身已支持
        //     从 COLLECTING_IN_PROGRESS 重入（内部对每位角色做 existingEntry
        //     幂等检查），回退到 COLLECTING 只是为了让 UI 重试按钮统一识别
        //     入口态、走同一套调度逻辑。
        //   - COLLECTED 不在此处理：它是 runCollecting 已经正常完成的终态
        //     （不是"中途崩溃"），runJudging 本身就接受 COLLECTED 直接进入，
        //     不需要回退；重试按钮会按当前真实状态直接分派到 retryJudging。
        viewModelScope.launch {
            try {
                val stuckRounds = competitionRoundRepo.getAllPendingRounds()
                    .filter { it.status == STATUS_JUDGING || it.status == STATUS_COLLECTING_IN_PROGRESS }
                for (round in stuckRounds) {
                    competitionRoundRepo.updateRoundStatus(round.id, STATUS_COLLECTING)
                    com.zaijian.zhoumuyun.util.ZLog.i("CompetitionViewModel",
                        "启动恢复：${round.status} → COLLECTING roundId=${round.id}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) { /* 恢复失败静默，不阻断正常流程 */ }
        }
        viewModelScope.launch {
            try {
                val ids = daughterRepo.getAllDaughterCharacterIds()
                val configs = ids.mapNotNull { id ->
                    try {
                        daughterRepo.getCharacterConfig(id)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // 单个女儿数据损坏不应影响整批加载——与 DaughterCharacterRepository
                        // 类注释中"宁可这一条消息报错，不能让女儿带着残缺人格说话"原则一致，
                        // 这里选择跳过该条，其余女儿角色正常可选。
                        com.zaijian.zhoumuyun.util.ZLog.w("CompetitionViewModel",
                            "女儿角色配置加载失败，跳过 id=$id", e)
                        null
                    }
                }
                _daughterCharacters.value = configs
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) { /* 女儿列表加载失败时，竞赛页面仍可用 DefaultCharacters 正常运作 */ }
        }
    }

    // ── 历史轮次列表 ─────────────────────────────────────────────

    val rounds: StateFlow<List<CompetitionRoundEntity>> =
        _domainFlow
            .flatMapLatest { d ->
                if (d.isBlank()) flowOf(emptyList())
                else competitionRoundRepo.observeAllForDomain(d)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 选中轮次 ─────────────────────────────────────────────────

    // Fix-13-14：selectedRoundId 从 SavedStateHandle 恢复，进程死亡后不丢失选中轮次。
    private val _selectedRoundId = MutableStateFlow<String?>(
        savedStateHandle.get<String>(KEY_SELECTED_ROUND)
    )
    val selectedRoundId: StateFlow<String?> = _selectedRoundId.asStateFlow()

    fun selectRound(roundId: String?) {
        savedStateHandle[KEY_SELECTED_ROUND] = roundId   // 持久化，进程死亡后可恢复
        _selectedRoundId.value = roundId
        // P2-14 修复：不再清空打分状态。
        // scoreInputMap 现在按 roundId 分组缓存（_scoreCache），
        // 切换轮次时自动切到对应轮次的打分记录，切回来不会丢失。
        _scoreInputMap.value = if (roundId != null) _scoreCache[roundId] ?: emptyMap()
                               else emptyMap()
    }

    // ── 选中轮次详情聚合 ──────────────────────────────────────────

    data class RoundDetail(
        val round: CompetitionRoundEntity? = null,
        val entries: List<CompetitionEntryEntity> = emptyList(),
        val isLoading: Boolean = true,
    )

    val roundDetail: StateFlow<RoundDetail> =
        _selectedRoundId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(RoundDetail(isLoading = false))
                } else {
                    combine(
                        competitionRoundRepo.observeRoundById(id),
                        competitionRoundRepo.observeAllEntriesForRound(id),
                    ) { round, entries ->
                        RoundDetail(
                            round = round,
                            entries = entries,
                            isLoading = false,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoundDetail())

    // ── 打分输入状态（entryId → ScoreInputState） ────────────────

    // P2-14 修复：按 roundId 分组缓存打分状态，切换轮次不丢分。
    // _scoreCache: roundId → (entryId → ScoreInputState)
    private val _scoreCache = mutableMapOf<String, Map<String, ScoreInputState>>()

    private val _scoreInputMap = MutableStateFlow<Map<String, ScoreInputState>>(emptyMap())
    val scoreInputMap: StateFlow<Map<String, ScoreInputState>> = _scoreInputMap.asStateFlow()

    fun updateScoreInput(entryId: String, state: ScoreInputState) {
        _scoreInputMap.update { it + (entryId to state.copy(hasUserInput = true)) }
        // 同步写入缓存，key 用当前选中的 roundId
        val roundId = _selectedRoundId.value ?: return
        _scoreCache[roundId] = _scoreInputMap.value
    }

    // ── Loading 标记 ─────────────────────────────────────────────

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── Snackbar ─────────────────────────────────────────────────

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()
    fun clearSnackbar() { _snackbarMessage.value = null }

    // ── 发起新轮次 ────────────────────────────────────────────────

    /**
     * 发起竞赛：startRound → runCollecting → runJudging（三步串行）。
     * 完成后状态自动变为 AWAITING_USER，UI 订阅 roundDetail 响应。
     * 失败时 snackbar 提示，不影响已有数据。
     */
    fun startRound(
        topic: String,
        judgeCharacterId: Int,
        participantIds: List<Int>,
    ) {
        if (topic.isBlank()) {
            _snackbarMessage.value = "题目不能为空"
            return
        }
        if (participantIds.isEmpty()) {
            _snackbarMessage.value = "至少选一位参赛者"
            return
        }

        val manager = competitionRoundManager
        if (manager == null) {
            _snackbarMessage.value = managerUnavailableMessage()
            return
        }

        val domain = _domainFlow.value
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val roundId = manager.startRound(
                    projectDomain   = domain,
                    topic           = topic,
                    judgeCharacterId = judgeCharacterId,
                    participantIds   = participantIds,
                )
                // 选中新建轮次，UI 立即切入详情层
                _selectedRoundId.value = roundId

                val collected = manager.runCollecting(roundId)
                if (!collected) {
                    _snackbarMessage.value = "作品收集阶段出现问题，请检查网络或 API 配置"
                    _isLoading.value = false
                    return@launch
                }

                val judged = manager.runJudging(roundId)
                if (!judged) {
                    _snackbarMessage.value = "裁判评审阶段出现问题，可稍后重试"
                }
                // 无论成功与否，isLoading 均在这里收尾
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _snackbarMessage.value = "竞赛启动失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── 重试评审（P0-2 修复：judgeRound 失败后 UI 重试入口） ────────

    /**
     * 对当前处于 COLLECTING 状态（评审失败回退）的轮次重新发起 runJudging。
     *
     * 调用时机：用户点击详情层"重试评审"按钮，该按钮仅在
     * status == STATUS_COLLECTING && !isLoading && entries.isNotEmpty() 时显示。
     *
     * runJudging 内部已处理状态机转换（COLLECTING→JUDGING→AWAITING_USER）
     * 及失败时回退到 COLLECTING，此处只负责调度和反馈。
     */
    fun retryJudging(roundId: String) {
        val manager = competitionRoundManager
        if (manager == null) {
            _snackbarMessage.value = managerUnavailableMessage()
            return
        }
        // P2-9 修复：防重入——_isLoading 为 true 时跳过，避免用户快速连点
        // 触发多个并发协程，导致同一轮次被多次评审。
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val judged = manager.runJudging(roundId)
                if (!judged) {
                    _snackbarMessage.value = "裁判评审再次失败，请稍后重试"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _snackbarMessage.value = "重试评审失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * W4-1 修复：对当前处于 COLLECTING_IN_PROGRESS 状态（runCollecting 中途
     * 崩溃/失败）的轮次重新发起 runCollecting，成功后接续 runJudging——
     * 与 startRound 里 "runCollecting 成功后接着 runJudging" 是同一段逻辑，
     * 抽出来复用，避免重复代码。
     *
     * runCollecting 内部对每位角色有 existingEntry 幂等检查，重新调用不会
     * 重复生成已成功的参赛作品，只会补齐尚未生成的部分。
     */
    fun retryCollecting(roundId: String) {
        val manager = competitionRoundManager
        if (manager == null) {
            _snackbarMessage.value = managerUnavailableMessage()
            return
        }
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val collected = manager.runCollecting(roundId)
                if (!collected) {
                    _snackbarMessage.value = "作品收集阶段仍有问题，请检查网络或 API 配置"
                    return@launch
                }
                val judged = manager.runJudging(roundId)
                if (!judged) {
                    _snackbarMessage.value = "裁判评审阶段出现问题，可稍后重试"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _snackbarMessage.value = "重试收集失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * W4-1 修复：统一重试入口，按轮次当前真实状态分派到正确的 Manager 方法——
     * COLLECTING/COLLECTING_IN_PROGRESS 卡住需要重新走 runCollecting，
     * COLLECTED/JUDGING 卡住则 runCollecting 已完成，直接重新走 runJudging。
     * UI 层（CompetitionScreen 重试按钮）只需调用这一个方法，不需要自己判断
     * 应该走哪条路径。
     */
    fun retryRound(roundId: String, status: String) {
        when (status) {
            STATUS_COLLECTING, STATUS_COLLECTING_IN_PROGRESS -> retryCollecting(roundId)
            STATUS_COLLECTED, STATUS_JUDGING -> retryJudging(roundId)
            else -> { /* 其他状态不允许重试，UI 层按钮条件已过滤，这里静默忽略 */ }
        }
    }

    // ── 取消竞赛（W4-5） ──────────────────────────────────────────

    /**
     * 取消当前轮次。UI 层（详情页顶部栏 + 二次确认弹窗）确认后调用。
     *
     * 与 retryJudging/retryCollecting 同款防重入：_isLoading 为 true 时跳过，
     * 避免快速连点导致重复调用。Manager.cancelRound 内部已有状态守卫
     * （COMPLETED/CANCELLED 不可再取消，见 CompetitionRoundManager 注释），
     * 这里只负责调度和用户反馈，不重复判断状态——真实状态判断以 Manager
     * 层为准，避免 UI 与 Manager 两处状态守卫条件不一致导致的行为分歧。
     *
     * 取消成功后不需要额外处理 UI 状态：round.status 变为 CANCELLED 后，
     * roundDetail 是对 competitionRoundRepo.observeRoundById() 的订阅，
     * 会自动感知变化并刷新详情页显示。
     */
    fun cancelRound(roundId: String) {
        val manager = competitionRoundManager
        if (manager == null) {
            _snackbarMessage.value = managerUnavailableMessage()
            return
        }
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val cancelled = manager.cancelRound(roundId)
                _snackbarMessage.value = if (cancelled) {
                    "竞赛已取消"
                } else {
                    "取消失败：该轮竞赛可能已结算或已取消"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _snackbarMessage.value = "取消竞赛失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── 提交用户评分 ──────────────────────────────────────────────

    /**
     * 将 UI 层的 ScoreInputState 换算后提交给 Manager。
     *
     * SLIDER  → directScore = sliderValue.toInt()
     * RANK    → rankAmongN  = Pair(rankPosition, participantCount)
     * COMMENT → sentimentComment = comment（Manager 内 judgeSentimentScore 换算）
     *
     * @param participantCount 本轮参赛总人数，用于 RANK 模式换算
     *
     * 审查报告问题10修复：原为 public suspend fun，但只在本类 finalizeRound()
     * 内部的 for 循环里被调用，没有任何 Screen 直接调用它——它是
     * finalizeRound 结算流程的一个实现细节（单条目打分提交），不是独立对外
     * 的操作。改为 private，减少接口噪音，也避免调用方绕过 finalizeRound 的
     * 防重入/loading 状态管理直接调用单条打分。
     */
    private suspend fun submitUserScoreForEntry(
        entryId: String,
        roundId: String,
        state: ScoreInputState,
        participantCount: Int,
        rawComment: String = "",
    ) {
        val manager = competitionRoundManager ?: return
        when (state.activeMode) {
            ScoreInputMode.SLIDER -> manager.submitUserScore(
                entryId = entryId,
                roundId = roundId,
                directScore = state.sliderValue.toInt(),
                rawComment = rawComment,
            )
            ScoreInputMode.RANK -> manager.submitUserScore(
                entryId = entryId,
                roundId = roundId,
                rankAmongN = Pair(state.rankPosition, participantCount),
                rawComment = rawComment,
            )
            ScoreInputMode.COMMENT -> manager.submitUserScore(
                entryId = entryId,
                roundId = roundId,
                sentimentComment = state.comment,
                rawComment = state.comment,
            )
        }
    }

    // ── 结算 ──────────────────────────────────────────────────────

    /**
     * 依次提交所有打分再结算。
     * 调用方（CompetitionScreen）按顺序传入 entries + scoreInputMap，
     * 本函数统一处理，结束后状态变 COMPLETED。
     */
    fun finalizeRound(
        roundId: String,
        entries: List<CompetitionEntryEntity>,
        scoreInputMap: Map<String, ScoreInputState>,
    ) {
        val manager = competitionRoundManager
        if (manager == null) {
            _snackbarMessage.value = managerUnavailableMessage()
            return
        }
        // P2-9 修复：防重入
        if (_isLoading.value) return

        val participantCount = entries.size

        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 1. 按条目顺序逐一提交打分
                for (entry in entries) {
                    val state = scoreInputMap[entry.id] ?: ScoreInputState()
                    // 方案 4-1：未打分条目跳过，不提交默认 50 分。
                    // hasUserInput 精确区分"从未操作"与"主动打到 50 分"。
                    // Manager 层 userScore 保持 null，finalizeRound 内部自行降级处理。
                    if (!state.hasUserInput) {
                        ZLog.i("CompetitionViewModel", "finalizeRound: 跳过未打分条目 entryId=${entry.id}")
                        continue
                    }
                    submitUserScoreForEntry(
                        entryId          = entry.id,
                        roundId          = roundId,
                        state            = state,
                        participantCount = participantCount,
                        rawComment       = if (state.activeMode == ScoreInputMode.COMMENT)
                            state.comment else "",
                    )
                }
                // 2. 结算
                val ok = manager.finalizeRound(roundId)
                _snackbarMessage.value = when {
                    ok -> "竞赛已结算，奖惩反哺已触发"
                    else -> {
                        // W4-2 修复：_isLoading 的 TOCTOU 竞态——检查在协程外、
                        // 设置在协程内，快速连续点击"提交并结算"时两次调用都能
                        // 通过防重入检查。Manager 层的 getRoundMutex(roundId)
                        // 保证了数据一致性（第二次调用读到状态已变为 COMPLETED
                        // 时返回 false），但这里如果直接把 false 当错误提示给
                        // 用户会造成误报——结算其实已经成功了。这里重新查一次
                        // 库里的真实状态，如果已经是 COMPLETED，说明是重复点击
                        // 导致的误报，提示"已结算"而不是报错。
                        val currentStatus = competitionRoundRepo.getRoundById(roundId)?.status
                        if (currentStatus == STATUS_COMPLETED) {
                            "竞赛已结算"
                        } else {
                            "结算时出现问题，请重试"
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _snackbarMessage.value = "结算失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
