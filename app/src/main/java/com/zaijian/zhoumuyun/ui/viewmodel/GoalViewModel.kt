package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.CharacterGoalEntity
import com.zaijian.zhoumuyun.data.db.entity.GoalHorizon
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

// ─────────────────────────────────────────────────────────────
//  GoalViewModel（Phase 15 新增）
//
//  职责：
//  - 从 Room 观察当前角色的激活目标列表
//  - 提供 Goal 的创建 / 编辑 / 删除 / 进度更新操作
//  - 给 CharacterDetailScreen 的目标编辑面板提供数据
//
//  设计方案 §9：Goal 是连接「角色是谁」与「角色在做什么」的桥梁。
//  Goal 内容属于私人配置，在系统中预留槽位由用户填写。
// ─────────────────────────────────────────────────────────────

data class GoalUiState(
    val goals: List<CharacterGoalEntity> = emptyList(),
    val isLoading: Boolean               = true,
    val isSaved: Boolean                 = false,
)

/** 新建/编辑目标时的草稿状态 */
data class GoalDraft(
    val id: String?           = null,   // null = 新建，非null = 编辑
    val title: String         = "",
    val description: String   = "",
    val priority: Int         = 3,      // 1-5
    val timeHorizon: GoalHorizon = GoalHorizon.MID_TERM,
    val relatedProjectId: String? = null, // Step 2：关联进化项目（可选）
)

class GoalViewModel(application: Application) : AndroidViewModel(application) {

    private val goalDao    = AppDatabase.getInstance(application).characterGoalDao()
    private val projectDao = AppDatabase.getInstance(application).projectDao()

    /** Step 2: 为目标编辑面板提供活跃项目列表 */
    val activeProjects: StateFlow<List<ProjectEntity>> = projectDao.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前角色参与的活跃项目列表，供 CharacterDetailScreen「关联项目」
     * WrapChipGroup 区域使用（精修方案 v1.3 第5.1/6节）。
     * 与上面 activeProjects 不同：activeProjects 是全量项目（给目标编辑面板的下拉选项用），
     * 这里是按 characterId 过滤后「这个角色参与了哪些项目」，两者用途不同不能合并。
     */
    private val _relatedProjects = MutableStateFlow<List<ProjectEntity>>(emptyList())
    val relatedProjects: StateFlow<List<ProjectEntity>> = _relatedProjects.asStateFlow()

    private val _uiState = MutableStateFlow(GoalUiState())
    val uiState: StateFlow<GoalUiState> = _uiState.asStateFlow()

    /** 当前正在编辑的草稿（null = 编辑面板未打开） */
    private val _draft = MutableStateFlow<GoalDraft?>(null)
    val draft: StateFlow<GoalDraft?> = _draft.asStateFlow()

    private var currentCharacterId: Int = -1

    // ── 初始化：观察目标列表 ──────────────────────────────────

    fun init(characterId: Int) {
        if (currentCharacterId == characterId) return
        currentCharacterId = characterId
        viewModelScope.launch {
            goalDao.observeActive(characterId).collect { list ->
                _uiState.update { it.copy(goals = list, isLoading = false) }
            }
        }
        // getActiveProjectsForCharacter 是一次性 suspend 查询（非 Flow），
        // 角色详情页的关联项目区不需要随项目增删实时刷新，按角色切换时查一次即可，
        // 与上面 goalDao.observeActive 持续订阅的写法不同，是有意为之的取舍。
        viewModelScope.launch {
            _relatedProjects.value = projectDao.getActiveProjectsForCharacter(characterId.toString())
        }
    }

    // ── 草稿操作（编辑面板双向绑定）─────────────────────────

    /** 打开新建草稿 */
    fun openNewDraft() {
        _draft.value = GoalDraft()
    }

    /** 打开编辑现有目标的草稿 */
    fun openEditDraft(goal: CharacterGoalEntity) {
        _draft.value = GoalDraft(
            id          = goal.id,
            title       = goal.title,
            description = goal.description,
            priority    = goal.priority,
            timeHorizon = runCatching { GoalHorizon.valueOf(goal.timeHorizon) }
                .getOrDefault(GoalHorizon.MID_TERM),
            relatedProjectId = goal.relatedProjectId,
        )
    }

    fun onDraftTitleChange(v: String)           = _draft.update { it?.copy(title = v) }
    fun onDraftDescriptionChange(v: String)     = _draft.update { it?.copy(description = v) }
    fun onDraftPriorityChange(v: Int)           = _draft.update { it?.copy(priority = v.coerceIn(1, 5)) }
    fun onDraftHorizonChange(v: GoalHorizon)    = _draft.update { it?.copy(timeHorizon = v) }
    fun onDraftProjectChange(v: String?)           = _draft.update { it?.copy(relatedProjectId = v) }

    fun dismissDraft() {
        _draft.value = null
    }

    // ── 保存草稿 ─────────────────────────────────────────────

    fun saveDraft() {
        val d   = _draft.value ?: return
        val cid = currentCharacterId.takeIf { it >= 0 } ?: return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            goalDao.upsert(
                CharacterGoalEntity(
                    id           = d.id ?: UUID.randomUUID().toString(),
                    characterId  = cid,
                    title        = d.title.trim(),
                    description  = d.description.trim(),
                    priority     = d.priority,
                    timeHorizon  = d.timeHorizon.name,
                    isActive         = true,
                    relatedProjectId = d.relatedProjectId,
                    updatedAt        = now,
                    createdAt        = now,
                )
            )
            _uiState.update { it.copy(isSaved = true) }
            _draft.value = null
        }
    }

    // ── 进度更新 ─────────────────────────────────────────────

    fun updateProgress(goalId: String, progress: Float) {
        viewModelScope.launch {
            goalDao.updateProgress(goalId, progress.coerceIn(0f, 1f))
        }
    }

    // ── 删除目标 ─────────────────────────────────────────────

    fun deactivate(goalId: String) {
        viewModelScope.launch { goalDao.deactivate(goalId) }
    }

    fun delete(goalId: String) {
        viewModelScope.launch { goalDao.delete(goalId) }
    }
}
