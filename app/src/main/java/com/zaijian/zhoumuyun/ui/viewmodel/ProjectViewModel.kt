package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectKnowledgeEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMilestoneEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMemberEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskStatus
import com.zaijian.zhoumuyun.data.repository.ProjectRepository
import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import com.zaijian.zhoumuyun.data.repository.TaskRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────
//  成长记录：单天摘要模型（P2-B）
// ─────────────────────────────────────────────────────────────

/**
 * 某一天内按角色聚合的规划数量。
 * 用于 ProjectDetailScreen「成长记录」区块展示近7天历史。
 *
 * @param dateLabel       格式化日期，如「6月27日」
 * @param countByCharacter characterId → 当天规划任务数
 */
data class DayGrowthSummary(
    val dateLabel: String,
    val countByCharacter: Map<Int, Int>,
) {
    val totalCount: Int get() = countByCharacter.values.sum()
}

// ─────────────────────────────────────────────────────────────
//  UI State
// ─────────────────────────────────────────────────────────────

data class ProjectListUiState(
    val projects: List<ProjectEntity> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class ProjectDetailUiState(
    val project: ProjectEntity? = null,
    val milestones: List<ProjectMilestoneEntity> = emptyList(),
    val members: List<ProjectMemberEntity> = emptyList(),
    val knowledge: List<ProjectKnowledgeEntity> = emptyList(),
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,   // Phase 31: 文件导入中
    val importError: String? = null,    // Phase 31: 导入错误
    val error: String? = null,
    // ── P2-A：今日规划，按角色分组 ──────────────────────────
    /** characterId → 今日 source="project_growth" 任务列表 */
    val todayGrowthByCharacter: Map<Int, List<TaskEntity>> = emptyMap(),
    // ── P2-B：成长记录，近7天每日摘要 ───────────────────────
    val recentGrowthSummary: List<DayGrowthSummary> = emptyList(),
)

// ─────────────────────────────────────────────────────────────
//  ViewModel
// ─────────────────────────────────────────────────────────────

/**
 * Project Engine ViewModel（Phase 31 + V46 P2-A/B）
 *
 * 职责：
 * 1. 观察项目列表（observeActive / observeAll）
 * 2. 提供项目 CRUD 操作
 * 3. 里程碑 / 成员 / 知识管理
 * 4. Phase 31：importFile() — 从 Android Uri 读取文件并导入知识库
 * 5. P2-A：detailState.todayGrowthByCharacter — 今日规划按角色分组（实时 Flow）
 * 6. P2-B：detailState.recentGrowthSummary    — 近7天成长记录摘要
 * 7. P2-A：toggleGrowthTask()                 — 勾选/取消勾选今日任务
 */
class ProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val db           = AppDatabase.getInstance(application)
    private val repo         = ProjectRepository(db.projectDao(), db.projectKnowledgeDao())
    // 7.7 修复：原直连裸 taskDao，绕过 TaskRepository（288行，已存在但未被使用）。
    // ProjectViewModel 是唯一用到任务数据的地方，不需要纳入 AppContainer 共享范围，
    // 直接在此构造一个专属实例即可。
    private val taskRepo     = TaskRepository(db, db.taskDao(), db.worldEventDao())
    private val scheduleRepo = ScheduleRepository(db.scheduledJobDao(), db.jobResultDao())

    // ── 项目列表 ─────────────────────────────────────────────
    // G2.5 修复：区分"正在加载"与"确实没有项目"。observeActive() 首次收集前
    // UI 无法得知数据是否已经到达，用 onStart 在 collect 开始时先发一次
    // isLoading=true，数据到达后再发 isLoading=false，避免加载期间被误判为空列表。

    val listState: StateFlow<ProjectListUiState> = repo.observeActive()
        .map { projects -> ProjectListUiState(projects = projects, isLoading = false) }
        .onStart { emit(ProjectListUiState(isLoading = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectListUiState(isLoading = true))

    /** 兼容旧调用方：仅取列表本身。新代码请优先使用 [listState] 以获得 loading 状态。 */
    val activeProjects: StateFlow<List<ProjectEntity>> = repo.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 详情状态 ─────────────────────────────────────────────

    private val _detailState = MutableStateFlow(ProjectDetailUiState())
    val detailState: StateFlow<ProjectDetailUiState> = _detailState.asStateFlow()

    private var detailProjectId: String? = null
    // P1-11-5 修复：保存所有详情页 collector Job，openProject 重入时先 cancel 旧 Job，
    // 避免旧项目的 Flow collector 残留，与新项目 collector 并发写同一 _detailState。
    private var detailCollectorJobs: List<Job> = emptyList()

    fun openProject(projectId: String) {
        if (detailProjectId == projectId) return
        detailProjectId = projectId
        // 取消上一个项目的所有 collector，防止旧数据流覆盖新项目状态
        detailCollectorJobs.forEach { it.cancel() }

        // G2.6 修复：重置为初始 loading 态，同时清空上一个项目残留的 project/error，
        // 避免切换项目时短暂显示上一个项目的详情或错误信息。
        _detailState.value = ProjectDetailUiState(isLoading = true)

        val milestoneJob = viewModelScope.launch {
            repo.observeMilestones(projectId).collect { milestones ->
                _detailState.update { it.copy(milestones = milestones) }
            }
        }
        val knowledgeJob = viewModelScope.launch {
            repo.observeKnowledge(projectId).collect { knowledge ->
                _detailState.update { it.copy(knowledge = knowledge) }
            }
        }
        // G2.6 修复：isLoading/error 由 infoJob（真正加载 project 本体的协程）
        // 负责收尾，而不是由 milestoneJob 提前关闭——旧代码里 milestoneJob 第一次
        // emit（哪怕是空列表）就会把 isLoading 置 false，此时 project 可能还没查到，
        // UI 会在"正在加载"和"项目不存在"之间出现一帧无法区分的空白态。
        val infoJob = viewModelScope.launch {
            val project = repo.getById(projectId)
            val members = if (project != null) repo.getMembers(projectId) else emptyList()
            _detailState.update {
                it.copy(
                    project = project,
                    members = members,
                    isLoading = false,
                    error = if (project == null) "项目不存在或已被删除" else null,
                )
            }
        }

        // ── P2-A：今日规划实时订阅 ──────────────────────────
        val growthJob = viewModelScope.launch {
            taskRepo.observeByProjectAndSourceAfter(
                projectId = projectId,
                source    = "project_growth",
                after     = startOfToday(),
            ).collect { tasks ->
                val byChar = tasks.groupBy { it.characterId }
                _detailState.update { it.copy(todayGrowthByCharacter = byChar) }
            }
        }

        detailCollectorJobs = listOf(milestoneJob, knowledgeJob, infoJob, growthJob)

        // ── P2-B：近7天成长记录（一次性计算，成员变动后可通过 combine 触发刷新）
        loadRecentGrowthSummary(projectId)
    }

    // ── P2-A：勾选/取消勾选 project_growth 任务 ─────────────

    /**
     * 轻量 toggle：在 PENDING ↔ COMPLETED 之间翻转。
     * 不产生 WorldEvent，避免污染记忆链路（成长任务勾选是低重要度操作）。
     */
    fun toggleGrowthTask(taskId: String) {
        viewModelScope.launch {
            taskRepo.toggleGrowthTaskDone(id = taskId, now = System.currentTimeMillis())
            // 勾选后重新计算历史摘要（今日完成数变了）
            detailProjectId?.let { loadRecentGrowthSummary(it) }
        }
    }

    // ── P2-B：近7天成长记录计算 ──────────────────────────────

    private fun loadRecentGrowthSummary(projectId: String) {
        viewModelScope.launch {
            val sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
            val tasks = taskRepo.getByProjectAndSourceAfter(
                projectId = projectId,
                source    = "project_growth",
                after     = sevenDaysAgo,
            )
            val summaries = buildGrowthSummaries(tasks)
            _detailState.update { it.copy(recentGrowthSummary = summaries) }
        }
    }

    /**
     * 将近7天任务列表聚合为每日摘要。
     * 按天分组（用"M月d日"格式），每组内再按 characterId 计数。
     * 结果按日期倒序（最近的在前）。
     */
    private fun buildGrowthSummaries(tasks: List<TaskEntity>): List<DayGrowthSummary> {
        val fmt = SimpleDateFormat("M月d日", Locale.CHINESE)
        // 先按"天"分组，Key = 当天 00:00:00 的时间戳（便于排序）
        val byDay = tasks.groupBy { task ->
            val cal = Calendar.getInstance().apply { timeInMillis = task.createdAt }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        return byDay.entries
            .sortedByDescending { it.key }   // 最近的天排在前
            .map { (dayTs, dayTasks) ->
                DayGrowthSummary(
                    dateLabel       = fmt.format(dayTs),
                    countByCharacter = dayTasks.groupBy { it.characterId }
                        .mapValues { (_, list) -> list.size },
                )
            }
    }

    // ── 项目 CRUD ────────────────────────────────────────────

    fun createProject(
        title: String,
        description: String = "",
        onCreated: (projectId: String) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                val id = repo.createProject(title, description)
                onCreated(id)
            }.onFailure { e ->
                _detailState.update { it.copy(error = e.message) }
            }
        }
    }

    fun updateProject(project: ProjectEntity) {
        viewModelScope.launch { runCatching { repo.updateProject(project) } }
    }

    fun archiveProject(id: String) {
        viewModelScope.launch { repo.archiveProject(id) }
    }

    fun completeProject(id: String) {
        viewModelScope.launch { repo.completeProject(id) }
    }

    // ── 里程碑 ───────────────────────────────────────────────

    fun addMilestone(projectId: String, title: String, description: String = "") {
        viewModelScope.launch {
            runCatching { repo.addMilestone(projectId, title, description) }
                .onFailure { e -> _detailState.update { it.copy(error = e.message) } }
        }
    }

    fun completeMilestone(milestoneId: String) {
        viewModelScope.launch { repo.completeMilestone(milestoneId) }
    }

    // ── 成员 ─────────────────────────────────────────────────

    fun addMember(projectId: String, characterId: String, role: String = "CONTRIBUTOR") {
        viewModelScope.launch {
            runCatching { repo.addMember(projectId, characterId, role) }
                .onFailure { e -> _detailState.update { it.copy(error = e.message) } }
            val members = repo.getMembers(projectId)
            _detailState.update { it.copy(members = members) }
            // 角色加入项目后自动注册成长规划日程
            val charId = characterId.toIntOrNull() ?: return@launch
            val project = repo.getById(projectId) ?: return@launch
            scheduleDailyPlannerJob(project.title, projectId, charId)
        }
    }

    fun removeMember(projectId: String, characterId: String) {
        viewModelScope.launch {
            repo.removeMember(projectId, characterId)
            val members = repo.getMembers(projectId)
            _detailState.update { it.copy(members = members) }
        }
    }

    // ── 知识库 ───────────────────────────────────────────────

    fun addKnowledge(
        projectId: String,
        content: String,
        title: String = "",
        importance: Int = 3,
        source: String = "MANUAL",
    ) {
        viewModelScope.launch {
            runCatching { repo.addKnowledge(projectId, content, title, null, source, importance) }
                .onFailure { e -> _detailState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * 从 Android Uri 导入文件到知识库。
     *
     * 支持 .txt / .md / .docx / .pdf。
     * 导入过程中 detailState.isImporting = true，完成后归 false。
     * 失败时写入 importError，不影响其他字段。
     *
     * @param context   Activity 或 Application Context
     * @param uri       文件选择器返回的 Uri（content:// 或 file://）
     * @param projectId 目标项目 ID
     * @param importance 重要度，默认 3
     */
    fun importFile(
        context: Context,
        uri: Uri,
        projectId: String,
        importance: Int = 3,
    ) {
        viewModelScope.launch {
            _detailState.update { it.copy(isImporting = true, importError = null) }
            runCatching {
                val fileName = resolveFileName(context, uri)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    repo.importFile(
                        context     = context,
                        inputStream = stream,
                        fileName    = fileName,
                        projectId   = projectId,
                        importance  = importance,
                    )
                } ?: error("无法打开文件流")
            }.onFailure { e ->
                _detailState.update { it.copy(importError = e.message ?: "导入失败") }
            }
            _detailState.update { it.copy(isImporting = false) }
        }
    }

    /** 从 Uri 解析原始文件名，优先取 OpenableColumns.DISPLAY_NAME */
    private fun resolveFileName(context: Context, uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment ?: "imported_file"
    }

    fun deleteKnowledge(id: String) {
        viewModelScope.launch { repo.deleteKnowledge(id) }
    }

    // 成长规划日程注册\uff08角色加入项目时自动触发\uff09
    private suspend fun scheduleDailyPlannerJob(
        projectTitle: String,
        projectId: String,
        characterId: Int,
    ) {
        // 计算今晒21:00的时间戳
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 21)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            // 如果当前时间已过 21:00\uff0c排到明日
            if (timeInMillis <= System.currentTimeMillis()) {
                add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
        }
        runCatching {
            scheduleRepo.createJob(
                characterId      = characterId,
                title            = "\u300c$projectTitle\u300d成长规划",
                toolName         = "project_daily_planner",
                toolParams       = mapOf("projectId" to projectId, "characterId" to characterId.toString()),
                repeatIntervalMs = java.util.concurrent.TimeUnit.HOURS.toMillis(24),
                nextRunAt        = cal.timeInMillis,
            )
        } // 失败不阻塞主流程
    }

    fun clearError() {
        _detailState.update { it.copy(error = null) }
    }

    fun clearImportError() {
        _detailState.update { it.copy(importError = null) }
    }

    // ── 内部工具 ─────────────────────────────────────────────

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
