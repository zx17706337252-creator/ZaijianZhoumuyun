package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.JobResultEntity
import com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

// ─────────────────────────────────────────────────────────────
//  UI State
// ─────────────────────────────────────────────────────────────

/**
 * 今日时间线中的一条任务条目（方案四）。
 * 将 ScheduledJobEntity 与对应的 JobResultEntity（若已执行）合并展示。
 */
data class TodayJobUiItem(
    val job: ScheduledJobEntity,
    val result: JobResultEntity?,  // null = 尚未执行
)

data class TaskCenterUiState(
    val activeTasks: List<TaskEntity>      = emptyList(),  // PENDING + RUNNING
    val completedTasks: List<TaskEntity>   = emptyList(),  // COMPLETED
    val failedTasks: List<TaskEntity>      = emptyList(),  // FAILED
    val todayJobs: List<TodayJobUiItem>    = emptyList(),  // Phase 30 方案四：今日时间线
    val todayGrowthTasks: List<TaskEntity> = emptyList(),  // P1-A：成长任务分组
    val isLoading: Boolean                 = true,
    val snackbarMessage: String?           = null,
    /** BUG-7 修复：头像覆盖表（characterId → avatarUrl），由 ViewModel 一次性加载。 */
    val avatarOverrides: Map<Int, String>  = emptyMap(),
    /** Fix-pendingJobId：深链接携带的 jobId，TaskCenterScreen 读取后滚动/高亮该任务，消费后设 null。 */
    val highlightedTaskId: String?         = null,
)

// ─────────────────────────────────────────────────────────────
//  ViewModel
// ─────────────────────────────────────────────────────────────

/**
 * Task Engine ViewModel（Phase 19）
 *
 * 职责：
 * 1. 观察 DB 任务列表，按状态分类（进行中 / 已完成 / 失败）
 * 2. 提供删除任务操作
 * 3. TaskCenterScreen 的唯一数据来源（替换 sampleTasks）
 */
class TaskViewModel(application: Application) : AndroidViewModel(application) {

    private val db   = AppDatabase.getInstance(application)
    private val repo = TaskRepository(db, db.taskDao(), db.worldEventDao())
    // 遗留裸调用修复：头像覆盖表加载原先裸取 db.characterIdentityDao()，改走薄包装。
    private val identityRepo = IdentityRepository(db.characterIdentityDao())

    private val _uiState = MutableStateFlow(TaskCenterUiState())
    val uiState: StateFlow<TaskCenterUiState> = _uiState.asStateFlow()

    init {
        // 三路 Flow 合并，任何一路更新都刷新激活/完成/失败任务列表。
        // Fix-VM-1：原实现 _uiState.update { state } 会用一个全新的 TaskCenterUiState()
        // 覆盖整个状态，导致 highlightedTaskId / todayJobs / todayGrowthTasks / avatarOverrides
        // 在每次 DB 变更时被重置。改为 it.copy(…) 只更新三个任务字段，保留其余字段。
        viewModelScope.launch {
            combine(
                repo.observeActive(),
                repo.observeCompleted(),
                repo.observeFailed(),
            ) { active, completed, failed ->
                Triple(active, completed, failed)
            }.collect { (active, completed, failed) ->
                _uiState.update {
                    it.copy(
                        activeTasks    = active,
                        completedTasks = completed,
                        failedTasks    = failed,
                        isLoading      = false,
                    )
                }
            }
        }

        // Phase 30 方案四：今日时间线数据流
        // Fix-VM-2：todayJobs 过滤条件由 nextRunAt in today 改为
        //   nextRunAt in today OR lastRunAt in today
        // 原因：ScheduledJobWorker 执行后先更新 nextRunAt（推到下一周期），再发通知。
        // 用户点击通知时，该 job 的 nextRunAt 已超出今天范围，旧过滤条件会让它从列表
        // 消失，导致 Phase1 找不到目标 → 立即 clearHighlightedTask()，高亮永远不显示。
        // 新条件：只要今天曾经执行过（lastRunAt in today）或今天计划执行（nextRunAt in today），
        // 就纳入今日列表。
        viewModelScope.launch {
            combine(
                db.scheduledJobDao().observeAll(),
                db.jobResultDao().observeAllUnread(),
            ) { allJobs, unreadResults ->
                val (start, end) = todayRange()
                val todayJobs = allJobs
                    .filter { job ->
                        job.nextRunAt in start..end ||
                            (job.lastRunAt != null && job.lastRunAt in start..end)
                    }
                    // Fix-SORT：对于今天已执行、nextRunAt 已推到明天的 job，
                    // 用 lastRunAt 作为排序键，保持在列表中的视觉位置正确。
                    // 未执行的 job 用 nextRunAt 排序（正常情况）。
                    .sortedBy { job ->
                        if (job.nextRunAt in start..end) job.nextRunAt
                        else job.lastRunAt ?: job.nextRunAt
                    }
                todayJobs.map { job ->
                    TodayJobUiItem(
                        job    = job,
                        result = unreadResults.firstOrNull { it.jobId == job.id }
                            ?: db.jobResultDao().findLatestByJobId(job.id),
                    )
                }
            }.collect { items ->
                _uiState.update { it.copy(todayJobs = items) }
            }
        }

        // P1-A：今日成长任务订阅（source="project_growth"）
        viewModelScope.launch {
            repo.observeGrowthTasksToday().collect { tasks ->
                _uiState.update { it.copy(todayGrowthTasks = tasks) }
            }
        }

        // BUG-7 修复：头像覆盖表一次性加载移入 ViewModel，
        // 消除 TaskCenterScreen 直连 AppDatabase.characterIdentityDao() 的架构违规。
        viewModelScope.launch(Dispatchers.IO) {
            val overrides = identityRepo.getAll()
                .associate { it.characterId to it.avatarUrl }
            _uiState.update { it.copy(avatarOverrides = overrides) }
        }
    }

    // ── 操作 ────────────────────────────────────────────────

    /**
     * P3-B：在任务页今日 Tab 内勾选/取消勾选成长任务。
     * 复用 TaskDao.toggleGrowthTaskDone，不产生 WorldEvent。
     */
    fun toggleGrowthTask(taskId: String) {
        viewModelScope.launch {
            db.taskDao().toggleGrowthTaskDone(id = taskId, now = System.currentTimeMillis())
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            repo.deleteTask(taskId)
            showSnackbar("任务已删除")
        }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch {
            repo.cancelTask(taskId)
            showSnackbar("任务已取消")
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    /** Fix-pendingJobId：深链接命中时高亮指定任务，TaskCenterScreen 消费后调用此方法清除。 */
    fun highlightTask(taskId: String) {
        _uiState.update { it.copy(highlightedTaskId = taskId) }
    }

    fun clearHighlightedTask() {
        _uiState.update { it.copy(highlightedTaskId = null) }
    }

    private fun showSnackbar(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    companion object {
        /** 返回今天 00:00:00 和 23:59:59 的毫秒时间戳 */
        fun todayRange(): Pair<Long, Long> {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            val end   = start + 24 * 3600_000L - 1
            return start to end
        }
    }
}
