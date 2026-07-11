package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.ZaijianApp
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity
import com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity
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
 *   - 代理用户打分（submitUserScoreForEntry）
 *   - 结算（finalizeRound）
 *
 * CompetitionRoundManager 从 ZaijianApp.sharedCompetitionRoundManager 取；
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
    )

    // ── 数据库 ──────────────────────────────────────────────────

    private val db = AppDatabase.getInstance(application)

    // ── domain 初始化 ────────────────────────────────────────────

    private val restoredDomain: String = savedStateHandle.get<String>(KEY_DOMAIN) ?: ""
    private val _domainFlow = MutableStateFlow(restoredDomain)
    private var hasRunInit = false

    fun init(domain: String) {
        if (hasRunInit && _domainFlow.value == domain) return
        hasRunInit = true
        savedStateHandle[KEY_DOMAIN] = domain
        _domainFlow.value = domain
        // App 启动/恢复时：把崩溃前卡在 JUDGING 的轮次回退到 COLLECTING，
        // 让重试按钮出现，避免轮次永久卡死。
        // （runJudging 本身已支持从 JUDGING 重入，此处回退是为了让 UI 显示重试按钮）
        viewModelScope.launch {
            try {
                val stuckRounds = db.competitionRoundDao().getAllPendingRounds()
                    .filter { it.status == "JUDGING" }
                for (round in stuckRounds) {
                    db.competitionRoundDao().updateStatus(round.id, "COLLECTING")
                    com.zaijian.zhoumuyun.util.ZLog.i("CompetitionViewModel",
                        "启动恢复：JUDGING → COLLECTING roundId=${round.id}")
                }
            } catch (_: Exception) { /* 恢复失败静默，不阻断正常流程 */ }
        }
    }

    // ── 历史轮次列表 ─────────────────────────────────────────────

    val rounds: StateFlow<List<CompetitionRoundEntity>> =
        _domainFlow
            .flatMapLatest { d ->
                if (d.isBlank()) flowOf(emptyList())
                else db.competitionRoundDao().observeAllForDomain(d)
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
                        db.competitionRoundDao().observeById(id),
                        db.competitionEntryDao().observeAllForRound(id),
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
        _scoreInputMap.update { it + (entryId to state) }
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

        val manager = ZaijianApp.sharedCompetitionRoundManager
        if (manager == null) {
            _snackbarMessage.value = "请先配置 API Key"
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
            } catch (e: Exception) {
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
     * status == "COLLECTING" && !isLoading && entries.isNotEmpty() 时显示。
     *
     * runJudging 内部已处理状态机转换（COLLECTING→JUDGING→AWAITING_USER）
     * 及失败时回退到 COLLECTING，此处只负责调度和反馈。
     */
    fun retryJudging(roundId: String) {
        val manager = ZaijianApp.sharedCompetitionRoundManager
        if (manager == null) {
            _snackbarMessage.value = "请先配置 API Key"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val judged = manager.runJudging(roundId)
                if (!judged) {
                    _snackbarMessage.value = "裁判评审再次失败，请稍后重试"
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "重试评审失败：${e.message}"
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
     * COMMENT → sentimentComment = comment（Manager 内 sentimentToScore 换算）
     *
     * @param participantCount 本轮参赛总人数，用于 RANK 模式换算
     */
    suspend fun submitUserScoreForEntry(
        entryId: String,
        state: ScoreInputState,
        participantCount: Int,
        rawComment: String = "",
    ) {
        val manager = ZaijianApp.sharedCompetitionRoundManager ?: return
        when (state.activeMode) {
            ScoreInputMode.SLIDER -> manager.submitUserScore(
                entryId = entryId,
                directScore = state.sliderValue.toInt(),
                rawComment = rawComment,
            )
            ScoreInputMode.RANK -> manager.submitUserScore(
                entryId = entryId,
                rankAmongN = Pair(state.rankPosition, participantCount),
                rawComment = rawComment,
            )
            ScoreInputMode.COMMENT -> manager.submitUserScore(
                entryId = entryId,
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
        val manager = ZaijianApp.sharedCompetitionRoundManager
        if (manager == null) {
            _snackbarMessage.value = "请先配置 API Key"
            return
        }

        val participantCount = entries.size

        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 1. 按条目顺序逐一提交打分
                for (entry in entries) {
                    val state = scoreInputMap[entry.id] ?: ScoreInputState()
                    submitUserScoreForEntry(
                        entryId          = entry.id,
                        state            = state,
                        participantCount = participantCount,
                        rawComment       = if (state.activeMode == ScoreInputMode.COMMENT)
                            state.comment else "",
                    )
                }
                // 2. 结算
                val ok = manager.finalizeRound(roundId)
                _snackbarMessage.value = if (ok) "竞赛已结算，奖惩反哺已触发" else "结算时出现问题，请重试"
            } catch (e: Exception) {
                _snackbarMessage.value = "结算失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
