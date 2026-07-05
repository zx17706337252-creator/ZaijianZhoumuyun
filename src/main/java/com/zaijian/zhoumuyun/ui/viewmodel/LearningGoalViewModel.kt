package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.LearningGoalEntity
import com.zaijian.zhoumuyun.data.db.entity.LearningGoalStatus
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

// ─────────────────────────────────────────────────────────────
//  LearningGoalViewModel（Phase 23 新增，Phase 27 扩展）
//
//  Phase 27 新增职责：
//  - 同时观察 learning_goals 和 memories(RULE) 两张表
//  - 将规则按 goalId 分组，合并为 GoalWithRules 列表
//  - 角色切换时规则数据随目标数据一同刷新
//  - 暴露 goalPanelExpanded 状态（各目标卡片独立展开/收起规则面板）
//
//  设计原则（Phase 27）：
//  - 两个 Flow（goals + rules）通过 combine 合并，保持单一事实来源
//  - flatMapLatest 确保角色切换后立即重新订阅，无残留数据
//  - GoalWithRules.lockedRules / candidateRules 分开展示：
//      lockedRules   = isLocked=true，已固化进入 Rule Layer
//      candidateRules = isLocked=false，候选/待锁定
// ─────────────────────────────────────────────────────────────

// ── 进化项目聚合模型（P1-B 新增） ────────────────────────────

/**
 * 单个进化项目在当前角色视角下的今日状态。
 * 用于 LearningGoalScreen 的「进化项目」区块。
 */
data class ProjectGrowthData(
    val project: ProjectEntity,
    /** 今日 source="project_growth" 且属于该角色+项目的任务 */
    val todayTasks: List<TaskEntity>,
) {
    val todayDoneCount  get() = todayTasks.count { it.status == TaskStatus.COMPLETED.name }
    val todayTotalCount get() = todayTasks.size
}

/**
 * 当前角色今日成长概览，用于 GrowthSummaryCard。
 */
data class GrowthSummaryData(
    val activeProjectCount: Int,
    val todayTaskTotal: Int,
    val todayTaskDone: Int,
    val activeGoalCount: Int,
)

// ── 目标 + 规则聚合模型（Phase 27 新增） ─────────────────────

/**
 * 单个学习目标及其关联规则的聚合视图模型。
 *
 * @param goal           学习目标实体
 * @param lockedRules    已锁定规则列表（isLocked=true，已注入 Rule Layer）
 * @param candidateRules 候选规则列表（isLocked=false，尚未达到锁定条件）
 */
data class GoalWithRules(
    val goal: LearningGoalEntity,
    val lockedRules: List<MemoryEntity> = emptyList(),
    val candidateRules: List<MemoryEntity> = emptyList(),
) {
    val totalRuleCount get() = lockedRules.size + candidateRules.size
    val lockedCount    get() = lockedRules.size
}

// ── UI State ─────────────────────────────────────────────────

data class LearningGoalUiState(
    val goals: List<LearningGoalEntity>          = emptyList(),
    val goalsWithRules: List<GoalWithRules>      = emptyList(),
    val isLoading: Boolean                       = true,
    val snackbarMessage: String?                 = null,
    // Phase 27：各目标卡片规则面板展开状态（goalId → isExpanded）
    val expandedRulePanels: Set<String>          = emptySet(),
    // P1-B：进化项目区块数据
    val projectCards: List<ProjectGrowthData>    = emptyList(),
    val growthSummary: GrowthSummaryData         = GrowthSummaryData(0, 0, 0, 0),
    // B2 修复：头像覆盖表从 ViewModel 加载，消除 Composable 内直连 AppDatabase。
    val avatarOverrides: Map<Int, String>        = emptyMap(),
)

// ── Draft（新建/编辑用） ──────────────────────────────────────

data class LearningGoalDraft(
    /** null = 新建，非 null = 编辑现有目标 */
    val id: String?         = null,
    val title: String       = "",
    val description: String = "",
)

// ── ViewModel ────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class LearningGoalViewModel(application: Application) : AndroidViewModel(application) {

    private val db         = AppDatabase.getInstance(application)
    private val goalDao    = db.learningGoalDao()
    private val memDao     = db.memoryDao()
    private val projectDao = db.projectDao()
    private val taskDao    = db.taskDao()

    private val _uiState = MutableStateFlow(LearningGoalUiState())
    val uiState: StateFlow<LearningGoalUiState> = _uiState.asStateFlow()

    /** 当前正在编辑的草稿（null = 创建弹窗未打开） */
    private val _draft = MutableStateFlow<LearningGoalDraft?>(null)
    val draft: StateFlow<LearningGoalDraft?> = _draft.asStateFlow()

    /** 当前角色 ID 的响应式容器，flatMapLatest 依赖此值切换订阅 */
    private val _characterIdFlow = MutableStateFlow(-1)

    private var currentCharacterId: Int = -1

    init {
        // ── Phase 27：goals + rules 双 Flow 合并 ─────────────
        viewModelScope.launch {
            _characterIdFlow
                .flatMapLatest { cid ->
                    if (cid < 0) {
                        flowOf(LearningGoalUiState(isLoading = true))
                    } else {
                        combine(
                            goalDao.observeAll(cid),
                            memDao.observeAllRules(cid),
                        ) { goals, rules ->
                            val rulesByGoalId = rules.groupBy { it.goalId ?: "" }
                            val goalsWithRules = goals.map { goal ->
                                val goalRules = rulesByGoalId[goal.id] ?: emptyList()
                                GoalWithRules(
                                    goal           = goal,
                                    lockedRules    = goalRules.filter { it.isLocked },
                                    candidateRules = goalRules.filter { !it.isLocked },
                                )
                            }
                            // 保留 projectCards / growthSummary（由另一路协程写入）
                            _uiState.value.copy(
                                goals              = goals,
                                goalsWithRules     = goalsWithRules,
                                isLoading          = false,
                                snackbarMessage    = _uiState.value.snackbarMessage,
                                expandedRulePanels = _uiState.value.expandedRulePanels,
                            )
                        }
                    }
                }
                .collect { newState -> _uiState.value = newState }
        }

        // ── P1-B：进化项目 + 今日成长任务订阅 ────────────────
        viewModelScope.launch {
            _characterIdFlow
                .flatMapLatest<Int, Pair<List<ProjectGrowthData>, GrowthSummaryData>> { cid ->
                    if (cid < 0) flowOf(emptyList<ProjectGrowthData>() to GrowthSummaryData(0, 0, 0, 0))
                    else {
                        taskDao.observeBySourceAfter(
                            source = "project_growth",
                            after  = startOfToday(),
                        ).flatMapLatest<List<TaskEntity>, Pair<List<ProjectGrowthData>, GrowthSummaryData>> { allGrowthTasks ->
                            val myTasks = allGrowthTasks.filter { it.characterId == cid }
                            val projects = projectDao.getActiveProjectsForCharacter(cid.toString())
                            val cards = projects.map { project ->
                                ProjectGrowthData(
                                    project    = project,
                                    todayTasks = myTasks.filter { it.projectId == project.id },
                                )
                            }
                            val activeGoalCount = _uiState.value.goals.count { it.isActive && it.status != LearningGoalStatus.COMPLETED.name }
                            val summary = GrowthSummaryData(
                                activeProjectCount = projects.size,
                                todayTaskTotal     = myTasks.size,
                                todayTaskDone      = myTasks.count { it.status == TaskStatus.COMPLETED.name },
                                activeGoalCount    = activeGoalCount,
                            )
                            flowOf(cards to summary)
                        }
                    }
                }
                .collect { (cards, summary) ->
                    _uiState.update { it.copy(projectCards = cards, growthSummary = summary) }
                }
        }

        // B2 修复：头像覆盖表订阅移入 ViewModel，消除 Composable 内直连 AppDatabase。
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            db.characterIdentityDao().observeAll()
                .map { list -> list.associate { it.characterId to it.avatarUrl } }
                .collect { map ->
                    _uiState.update { it.copy(avatarOverrides = map) }
                }
        }
    }

    // ── 初始化：切换当前角色 ──────────────────────────────────

    fun init(characterId: Int) {
        if (currentCharacterId == characterId) return
        currentCharacterId = characterId
        // 重置面板展开状态和项目数据，避免上一个角色的状态残留
        _uiState.update { it.copy(
            expandedRulePanels = emptySet(),
            projectCards       = emptyList(),
            growthSummary      = GrowthSummaryData(0, 0, 0, 0),
            isLoading          = true,
        ) }
        _characterIdFlow.value = characterId
    }

    // ── 内部工具 ─────────────────────────────────────────────

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    // ── Phase 27：规则面板展开/收起 ──────────────────────────

    /**
     * 切换指定目标的规则面板展开状态。
     * 折叠/展开逻辑：若已展开则折叠，若未展开则展开。
     */
    fun toggleRulePanel(goalId: String) {
        _uiState.update { state ->
            val current = state.expandedRulePanels
            val updated = if (goalId in current) current - goalId else current + goalId
            state.copy(expandedRulePanels = updated)
        }
    }

    /** 展开所有目标的规则面板 */
    fun expandAllRulePanels() {
        _uiState.update { state ->
            val allIds = state.goals.map { it.id }.toSet()
            state.copy(expandedRulePanels = allIds)
        }
    }

    /** 折叠所有目标的规则面板 */
    fun collapseAllRulePanels() {
        _uiState.update { it.copy(expandedRulePanels = emptySet()) }
    }

    // ── 草稿操作（创建弹窗双向绑定） ─────────────────────────

    fun openNewDraft() {
        _draft.value = LearningGoalDraft()
    }

    fun openEditDraft(goal: LearningGoalEntity) {
        _draft.value = LearningGoalDraft(
            id          = goal.id,
            title       = goal.title,
            description = goal.description,
        )
    }

    fun onDraftTitleChange(value: String)       = _draft.update { it?.copy(title = value) }
    fun onDraftDescriptionChange(value: String) = _draft.update { it?.copy(description = value) }
    fun dismissDraft()                          { _draft.value = null }

    // ── 保存草稿（创建 or 更新） ─────────────────────────────

    fun saveDraft() {
        val d   = _draft.value ?: return
        val cid = currentCharacterId.takeIf { it >= 0 } ?: return
        val title = d.title.trim()
        if (title.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "目标名称不能为空") }
            return
        }

        val now = System.currentTimeMillis()
        viewModelScope.launch {
            if (d.id == null) {
                // 新建
                goalDao.insert(
                    LearningGoalEntity(
                        id          = UUID.randomUUID().toString(),
                        characterId = cid,
                        title       = title.take(50),
                        description = d.description.trim(),
                        progress    = 0f,
                        isActive    = true,
                        status      = LearningGoalStatus.IN_PROGRESS.name,
                        createdAt   = now,
                        updatedAt   = now,
                    )
                )
                _uiState.update { it.copy(snackbarMessage = "已添加学习目标「$title」") }
            } else {
                // 更新现有
                val existing = goalDao.getById(d.id) ?: return@launch
                goalDao.update(
                    existing.copy(
                        title       = title.take(50),
                        description = d.description.trim(),
                        updatedAt   = now,
                    )
                )
                _uiState.update { it.copy(snackbarMessage = "已更新目标「$title」") }
            }
            _draft.value = null
        }
    }

    // ── 停用目标（软删除，isActive = false） ─────────────────

    fun deactivate(goalId: String) {
        viewModelScope.launch {
            goalDao.deactivate(goalId)
            _uiState.update { it.copy(snackbarMessage = "目标已停用") }
        }
    }

    // ── 硬删除 ────────────────────────────────────────────────

    fun delete(goalId: String, title: String) {
        viewModelScope.launch {
            goalDao.deleteById(goalId)
            // 删除目标时同步收起其规则面板
            _uiState.update { state ->
                state.copy(
                    snackbarMessage    = "已删除「$title」",
                    expandedRulePanels = state.expandedRulePanels - goalId,
                )
            }
        }
    }

    // ── 切换激活状态 ──────────────────────────────────────────

    fun toggleActive(goal: LearningGoalEntity) {
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            goalDao.update(goal.copy(isActive = !goal.isActive, updatedAt = now))
        }
    }

    // ── 清除 Snackbar ─────────────────────────────────────────

    fun clearSnackbar() = _uiState.update { it.copy(snackbarMessage = null) }
}
