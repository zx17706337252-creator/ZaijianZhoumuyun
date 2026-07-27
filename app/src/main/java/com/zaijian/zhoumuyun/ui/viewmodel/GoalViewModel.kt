package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.CharacterGoalEntity
import com.zaijian.zhoumuyun.data.db.entity.GoalHorizon
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.util.ZLog
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
    val error: String?                   = null,
)

/** 新建/编辑目标时的草稿状态 */
data class GoalDraft(
    val id: String?           = null,   // null = 新建，非null = 编辑
    val title: String         = "",
    val description: String   = "",
    val priority: Int         = 3,      // 1-5
    val timeHorizon: GoalHorizon = GoalHorizon.MID_TERM,
    val relatedProjectId: String? = null, // Step 2：关联进化项目（可选）
    // S8-窗口07 新发现1修复：编辑弹窗此前没有进度字段，用户在编辑模式下
    // 无法主动设定进度，只能等 WorldSimulation Tier2 每30分钟自动+0.01。
    // 新建时默认0f，编辑时取goal.progress，与saveDraft()构造entity时的
    // progress取值语义保持一致。
    val progress: Float       = 0f,     // 0f-1f
)

class GoalViewModel(application: Application) : AndroidViewModel(application) {

    // S-1 缺口2：原先直接裸调 projectDao，现改为通过 AppContainer.projectRepo 统一访问。
    private val projectRepo = AppContainer.instance.projectRepo

    // 阶段2 S-1 最终收尾：goalDao 此前仍是唯一残留的裸持有字段
    // （AppDatabase.getInstance(application).characterGoalDao()），
    // 现改走 AppContainer.characterGoalRepo，与 projectRepo 同一模式。
    private val goalRepo = AppContainer.instance.characterGoalRepo

    /** Step 2: 为目标编辑面板提供活跃项目列表 */
    val activeProjects: StateFlow<List<ProjectEntity>> = projectRepo.observeActive()
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

    // P2-4 修复：init() 切换角色时取消旧协程，避免 observeActive 旧 Flow 继续写入
    // 新角色的 UI 状态（旧协程的 collect lambda 持有旧 characterId 闭包，但 UI 状态
    // 已指向新角色，旧数据写入会导致 UI 闪烁和数据错乱）。
    private var observeJob: kotlinx.coroutines.Job? = null
    private var projectJob: kotlinx.coroutines.Job? = null

    // ── 初始化：观察目标列表 ──────────────────────────────────

    fun init(characterId: Int) {
        if (currentCharacterId == characterId) return
        observeJob?.cancel()
        projectJob?.cancel()
        currentCharacterId = characterId
        // 第九窗口问题5清收：CharacterDetail 路由自跳转（子代角色详情）时
        // launchSingleTop 复用同一 ViewModel 实例，旧协程取消后到新 Flow 第一次
        // emit 之间存在空档——若不在此处先清空，UI 会短暂显示上一个角色的
        // goals/relatedProjects（与已修的问题1 ChatViewModel 竞态同根因）。
        _uiState.value = GoalUiState(isLoading = true)
        _relatedProjects.value = emptyList()
        observeJob = viewModelScope.launch {
            goalRepo.observeActive(characterId).collect { list ->
                _uiState.update { it.copy(goals = list, isLoading = false) }
            }
        }
        projectJob = viewModelScope.launch {
            _relatedProjects.value = projectRepo.getActiveProjectsForCharacter(characterId)
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
            progress    = goal.progress,
        )
    }

    fun onDraftTitleChange(v: String)           = _draft.update { it?.copy(title = v) }
    fun onDraftDescriptionChange(v: String)     = _draft.update { it?.copy(description = v) }
    fun onDraftPriorityChange(v: Int)           = _draft.update { it?.copy(priority = v.coerceIn(1, 5)) }
    fun onDraftHorizonChange(v: GoalHorizon)    = _draft.update { it?.copy(timeHorizon = v) }
    fun onDraftProjectChange(v: String?)           = _draft.update { it?.copy(relatedProjectId = v) }
    // S8-窗口07 新发现1修复：编辑弹窗补上进度滑块的双向绑定入口
    fun onDraftProgressChange(v: Float)         = _draft.update { it?.copy(progress = v.coerceIn(0f, 1f)) }

    fun dismissDraft() {
        _draft.value = null
    }

    // ── 保存草稿 ─────────────────────────────────────────────

    fun saveDraft() {
        val cid = currentCharacterId.takeIf { it >= 0 } ?: return
        // 方案 4-3：同步读取并清空 draft，在协程启动前消除竞态窗口。
        // 两次快速点击时，第二次读取必定得到 null，杜绝重复保存。
        val d = _draft.value ?: return
        _draft.value = null
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val goalId = d.id ?: UUID.randomUUID().toString()
            try {
                goalRepo.upsert(
                    CharacterGoalEntity(
                        id           = goalId,
                        characterId  = cid,
                        title        = d.title.trim(),
                        description  = d.description.trim(),
                        priority     = d.priority,
                        timeHorizon  = d.timeHorizon.name,
                        // S8-窗口07 新发现1修复：此前未传progress，CharacterGoalEntity
                        // 默认值0f会导致每次编辑保存都把已有进度悄悄清零。
                        progress          = d.progress,
                        isActive         = true,
                        relatedProjectId = d.relatedProjectId,
                        updatedAt        = now,
                        createdAt        = now,
                    )
                )
                // S8-窗口07 结论5修复：relatedProjectId（Goal→Project）此前已接入，
                // 但 ProjectEntity.goalId（Project→Goal 反向）从未被写入。这里保存
                // 目标时同步反向链接——若该目标此前挂在另一个项目下，先解绑旧项目，
                // 避免出现一个项目的 goalId 指向一个已经改挂别处的目标（脏反向链接）。
                val oldLinkedProject = projectRepo.getByGoalId(goalId)
                if (oldLinkedProject != null && oldLinkedProject.id != d.relatedProjectId) {
                    projectRepo.setGoalId(oldLinkedProject.id, null)
                }
                if (d.relatedProjectId != null) {
                    projectRepo.setGoalId(d.relatedProjectId, goalId)
                }
                _uiState.update { it.copy(isSaved = true, error = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("GoalViewModel", "保存目标失败", e)
                _uiState.update { it.copy(isSaved = false, error = "保存失败：${e.message}") }
                // 失败时恢复 draft，允许用户重新提交
                _draft.value = d
            }
        }
    }

    // ── 进度更新 ─────────────────────────────────────────────

    fun updateProgress(goalId: String, progress: Float) {
        viewModelScope.launch {
            try {
                goalRepo.updateProgress(goalId, progress.coerceIn(0f, 1f))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("GoalViewModel", "更新目标进度失败 goalId=$goalId", e)
                _uiState.update { it.copy(error = "更新进度失败：${e.message}") }
            }
        }
    }

    // ── 删除目标 ─────────────────────────────────────────────

    fun deactivate(goalId: String) {
        viewModelScope.launch {
            try {
                goalRepo.deactivate(goalId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("GoalViewModel", "停用目标失败 goalId=$goalId", e)
                _uiState.update { it.copy(error = "停用失败：${e.message}") }
            }
        }
    }

    fun delete(goalId: String) {
        viewModelScope.launch {
            try {
                // S8-窗口07 结论5修复：delete()是物理DELETE，若该目标有反向链接的
                // 项目，删除前先解绑，避免 ProjectEntity.goalId 留下指向不存在
                // 目标的悬空引用。deactivate()不做同样处理——它只是isActive=0，
                // 目标记录仍在，反向链接依然有效，不需要解绑。
                projectRepo.getByGoalId(goalId)?.let { linkedProject ->
                    projectRepo.setGoalId(linkedProject.id, null)
                }
                goalRepo.delete(goalId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("GoalViewModel", "删除目标失败 goalId=$goalId", e)
                _uiState.update { it.copy(error = "删除失败：${e.message}") }
            }
        }
    }

    // 批次4 4-1修复：补 clearError() 方法，与 PregnancyViewModel.clearErrorMessage() 范式对齐。
    // CharacterDetailScreen 的 LaunchedEffect(goalState.error) 展示 Snackbar 后调用此方法
    // 清空 error，避免重组时重复弹出。原代码设置了 error 但无任何 UI 消费，也无此方法。
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
