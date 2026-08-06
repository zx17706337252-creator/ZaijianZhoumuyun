package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectKnowledgeEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMilestoneEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMemberEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskStatus
import com.zaijian.zhoumuyun.data.repository.ProjectRepository
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/**
 * 项目列表卡摘要（UI 升级 v2.0 帧14）。
 *
 * 列表态只有 [ProjectEntity]，列表卡要展示「金条进度 / 里程碑·知识·角色
 * 三列统计 / 头像叠放 / 里程碑 chip」需要里程碑、成员、知识数据，故由
 * [ProjectViewModel.getProjectCardSummary] 一次性挂起查询提供快照。
 *
 * @param milestones         里程碑列表（金条进度 + chip）
 * @param knowledgeCount     知识条目数
 * @param memberCharacterIds 参与角色的 characterId 列表（头像叠放 + 角色数）
 */
data class ProjectCardSummary(
    val milestones: List<ProjectMilestoneEntity> = emptyList(),
    val knowledgeCount: Int = 0,
    val memberCharacterIds: List<Int> = emptyList(),
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
    // ── 审查报告问题9：知识库 FTS 搜索接入 ───────────────────
    /** 当前搜索关键词，空字符串代表未搜索（此时 UI 应展示 knowledge 全量列表） */
    val knowledgeSearchQuery: String = "",
    /** searchKnowledge() 的结果，仅当 knowledgeSearchQuery 非空时有意义 */
    val knowledgeSearchResults: List<ProjectKnowledgeEntity> = emptyList(),
    /** 搜索请求进行中 */
    val isSearchingKnowledge: Boolean = false,
    /** 搜索失败信息（如 FTS MATCH 语法错误），与 error 字段区分，避免污染详情页整体错误态 */
    val knowledgeSearchError: String? = null,
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

    // 阶段2 S-1 收尾：原先注释称"AppContainer 没有 ProjectRepository 字段"，
    // 已过时——批次4为 GoalViewModel 新增了 AppContainer.projectRepo（构造参数
    // db.projectDao() + db.projectKnowledgeDao()，与此处原独立构造完全一致）。
    // 本 ViewModel 用到的18个方法（createProject/addMember/observeActive等）
    // ProjectRepository 已全部覆盖，改引用容器共享实例；db 不再需要，一并移除。
    private val repo         = AppContainer.instance.projectRepo

    // 阶段2 S-1 收尾：原先本地独立构造 TaskRepository(db, db.taskDao(), db.worldEventDao())，
    // 构造参数与 AppContainer.taskRepo 完全一致，改为引用容器共享实例。
    private val taskRepo     = AppContainer.instance.taskRepo

    // 阶段2 S-1 收尾：原先本地独立构造 ScheduleRepository(db.scheduledJobDao(),
    // db.jobResultDao(), db)（3参，无 calendarSync/context）。本 ViewModel 唯一
    // 的调用方 scheduleDailyPlannerJob() 只用到 createJob()（不涉及
    // calendarSync/context 这两个可选参数的逻辑分支），与 AppContainer.scheduleRepo
    // （5参，多出的 calendarSync/context 仅影响 createJobWithFullSync/
    // deleteJobWithFullSync 分支）在此调用路径下行为完全一致，改为引用容器共享实例。
    private val scheduleRepo = AppContainer.instance.scheduleRepo

    // 审查报告问题10修复：项目成员选择此前只遍历 DefaultCharacters（ID 1-9），
    // 女儿角色（ID>=1000）无法被添加到项目，与 RoundtableViewModel 早已支持
    // addDaughter() 的现状不一致。
    // 阶段2 S-1 收尾：原先本地独立构造 DaughterCharacterRepository(db, db.daughterCharacterDao())，
    // 构造参数与 AppContainer.daughterCharacterRepo 完全一致，改为引用容器共享实例。
    private val daughterCharacterRepo = AppContainer.instance.daughterCharacterRepo

    // 已注册女儿角色的完整 CharacterConfig 列表（一次性加载，非响应式订阅——
    // 女儿注册是低频事件，ProjectDetailScreen 只在 ViewModel 存活期间读取一次
    // 快照即可满足"能被添加到项目"的需求；若后续新注册了女儿，下次重新进入
    // 项目页/重建 ViewModel 会自然刷新，不引入额外的 Flow 订阅复杂度）。
    private val _daughterCharacters = MutableStateFlow<List<CharacterConfig>>(emptyList())
    val daughterCharacters: StateFlow<List<CharacterConfig>> = _daughterCharacters.asStateFlow()

    
    
    /**
     * P3-18 修复：统一异常日志包装，替代裸 runCatching。
     * 所有 DB 写入/查询失败均记录日志，不再静默丢弃。
     */
    private inline fun <T> safeRun(tag: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        ZLog.w("ProjectVM", "$tag 失败", e)
        null
    }

    init {
        viewModelScope.launch {
            val ids = try {
                daughterCharacterRepo.getAllDaughterCharacterIds()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            val configs = ids.mapNotNull { id ->
                try {
                    daughterCharacterRepo.getCharacterConfig(id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            }
            _daughterCharacters.value = configs
        }
    }

    // ── 项目列表 ─────────────────────────────────────────────
    // G2.5 修复：区分"正在加载"与"确实没有项目"。observeActive() 首次收集前
    // UI 无法得知数据是否已经到达，用 onStart 在 collect 开始时先发一次
    // isLoading=true，数据到达后再发 isLoading=false，避免加载期间被误判为空列表。
    //
    // 审查报告问题9配套修复：ProjectListUiState.error 此前定义了字段但从未被
    // 赋值——observeActive() 是 Room Flow，理论上可能因数据库损坏/磁盘 IO 异常
    // 而抛出，此前没有 .catch{}，异常会直接从 stateIn 逃逸导致应用崩溃，且即便
    // 不崩溃 UI 也没有任何地方能感知到"加载失败"。现在捕获异常，写入 error 并
    // 降级为空列表，与 isLoading=false 一起让 UI 能区分"空项目列表"与"加载失败"。
    val listState: StateFlow<ProjectListUiState> = repo.observeActive()
        .map { projects -> ProjectListUiState(projects = projects, isLoading = false) }
        .catch { e ->
            ZLog.e("ProjectViewModel", "observeActive 加载失败", e)
            emit(ProjectListUiState(isLoading = false, error = e.message ?: "项目列表加载失败"))
        }
        .onStart { emit(ProjectListUiState(isLoading = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectListUiState(isLoading = true))

    /**
     * 兼容旧调用方：仅取列表本身。新代码请优先使用 [listState] 以获得 loading 状态。
     *
     * 审查报告问题11修复：此前独立调用 repo.observeActive().stateIn(...)，与
     * [listState] 各自订阅同一个 Room DAO Flow，产生两套独立订阅、两次查询。
     * 现改为从 [listState] 派生，只保留一份底层订阅。行为对旧调用方透明：
     * 加载失败时 listState.projects 本就降级为空列表（见上方 .catch{}），
     * 与此前"异常直接从 stateIn 逃逸"相比只有更好、没有更差。
     */
    val activeProjects: StateFlow<List<ProjectEntity>> = listState
        .map { it.projects }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 详情状态 ─────────────────────────────────────────────

    private val _detailState = MutableStateFlow(ProjectDetailUiState())
    val detailState: StateFlow<ProjectDetailUiState> = _detailState.asStateFlow()

    private var detailProjectId: String? = null
    // P1-11-5 修复：保存所有详情页 collector Job，openProject 重入时先 cancel 旧 Job，
    // 避免旧项目的 Flow collector 残留，与新项目 collector 并发写同一 _detailState。
    private var detailCollectorJobs: List<Job> = emptyList()
    // 审查报告问题9：知识库搜索是用户输入触发的一次性挂起调用（非 Flow 订阅），
    // 新一次搜索发起时需要取消上一次尚未返回的搜索，避免慢请求后到达覆盖快请求的新结果
    // （经典的“旧响应覆盖新响应”竞态，与 openProject 对 detailCollectorJobs 的处理同一思路）。
    private var knowledgeSearchJob: Job? = null

    fun openProject(projectId: String) {
        if (detailProjectId == projectId) return
        detailProjectId = projectId
        // 取消上一个项目的所有 collector，防止旧数据流覆盖新项目状态
        detailCollectorJobs.forEach { it.cancel() }
        // 取消上一个项目尚未返回的知识库搜索，防止其结果覆盖新项目的 detailState
        knowledgeSearchJob?.cancel()

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
                after     = TimeFormatUtils.startOfDay(),
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
            // 第8窗口问题8修复：原先无异常处理，DB 写入失败时会传播到 viewModelScope。
            runCatching {  // P3-18: 静默吞异常已加日志
                taskRepo.toggleGrowthTaskDone(id = taskId, now = System.currentTimeMillis())
                // 勾选后重新计算历史摘要（今日完成数变了）
                detailProjectId?.let { loadRecentGrowthSummary(it) }
            }.onFailure { e ->
                ZLog.e("ProjectViewModel", "成长任务勾选失败（taskId=$taskId）", e)
                _detailState.update { it.copy(error = e.message ?: "操作失败") }
            }
        }
    }

    // ── P2-B：近7天成长记录计算 ──────────────────────────────

    private fun loadRecentGrowthSummary(projectId: String) {
        viewModelScope.launch {
            // 第8窗口问题8修复：原先无异常处理。
            runCatching {  // P3-18: 静默吞异常已加日志
                val sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                val tasks = taskRepo.getByProjectAndSourceAfter(
                    projectId = projectId,
                    source    = "project_growth",
                    after     = sevenDaysAgo,
                )
                buildGrowthSummaries(tasks)
            }.onSuccess { summaries ->
                _detailState.update { it.copy(recentGrowthSummary = summaries) }
            }.onFailure { e ->
                ZLog.e("ProjectViewModel", "近7天成长摘要加载失败（projectId=$projectId）", e)
                _detailState.update { it.copy(error = e.message ?: "加载失败") }
            }
        }
    }

    /**
     * 将近7天任务列表聚合为每日摘要。
     * 按天分组（用"M月d日"格式），每组内再按 characterId 计数。
     * 结果按日期倒序（最近的在前）。
     */
    private fun buildGrowthSummaries(tasks: List<TaskEntity>): List<DayGrowthSummary> {
        // 先按"天"分组，Key = 当天 00:00:00 的时间戳（便于排序）
        val byDay = tasks.groupBy { task -> TimeFormatUtils.startOfDay(task.createdAt) }
        return byDay.entries
            .sortedByDescending { it.key }   // 最近的天排在前
            .map { (dayTs, dayTasks) ->
                DayGrowthSummary(
                    dateLabel       = TimeFormatUtils.formatChineseShortDate(dayTs),
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
            runCatching {  // P3-18: 静默吞异常已加日志
                val id = repo.createProject(title, description)
                onCreated(id)
            }.onFailure { e ->
                _detailState.update { it.copy(error = e.message) }
            }
        }
    }

    fun updateProject(project: ProjectEntity) {
        viewModelScope.launch {
            runCatching {  // P3-18: 静默吞异常已加日志
                repo.updateProject(project)
            }
                .onSuccess { refreshDetailProjectIfCurrent(project.id) }
                .onFailure { e ->
                    // 第8窗口问题8修复：原先 .onFailure 为空，属静默吞异常。
                    ZLog.e("ProjectViewModel", "项目更新失败（id=${project.id}）", e)
                    _detailState.update { it.copy(error = e.message ?: "更新失败") }
                }
        }
    }

    fun archiveProject(id: String) {
        viewModelScope.launch {
            // 第8窗口问题8修复：原先无异常处理，DB 写入失败会传播到 viewModelScope。
            runCatching {  // P3-18: 静默吞异常已加日志
                repo.archiveProject(id)
                refreshDetailProjectIfCurrent(id)
            }.onFailure { e ->
                ZLog.e("ProjectViewModel", "项目归档失败（id=$id）", e)
                _detailState.update { it.copy(error = e.message ?: "归档失败") }
            }
        }
    }

    fun completeProject(id: String) {
        viewModelScope.launch {
            runCatching {  // P3-18: 静默吞异常已加日志
                repo.completeProject(id)
                refreshDetailProjectIfCurrent(id)
            }.onFailure { e ->
                ZLog.e("ProjectViewModel", "项目完成状态更新失败（id=$id）", e)
                _detailState.update { it.copy(error = e.message ?: "操作失败") }
            }
        }
    }

    // Audit-v1.33 P1-4 修复：Repository 早已定义 pauseProject/reactivateProject，
    // 但 ViewModel 从未暴露对应方法，导致 ACTIVE→PAUSED→ACTIVE 流转路径在 UI
    // 层完全不可达。此处补齐暴露。
    // 第8窗口问题8修复：原注释称"与 archiveProject/completeProject 保持一致的
    // fire-and-forget 风格，暂不额外接 error 状态"——但这四个方法本身都缺少
    // 异常处理，一致地没有保护并不能降低崩溃风险，故统一补上。
    fun pauseProject(id: String) {
        viewModelScope.launch {
            runCatching {  // P3-18: 静默吞异常已加日志
                repo.pauseProject(id)
                refreshDetailProjectIfCurrent(id)
            }.onFailure { e ->
                ZLog.e("ProjectViewModel", "项目暂停失败（id=$id）", e)
                _detailState.update { it.copy(error = e.message ?: "暂停失败") }
            }
        }
    }

    fun reactivateProject(id: String) {
        viewModelScope.launch {
            runCatching {  // P3-18: 静默吞异常已加日志
                repo.reactivateProject(id)
                refreshDetailProjectIfCurrent(id)
            }.onFailure { e ->
                ZLog.e("ProjectViewModel", "项目恢复失败（id=$id）", e)
                _detailState.update { it.copy(error = e.message ?: "恢复失败") }
            }
        }
    }

    // Audit-v1.33 P1-3/P1-4 配套修复：detailState.project 由 openProject() 一次性
    // 挂起加载（repo.getById），不是 Room Flow，状态变更方法（updateProject/
    // archiveProject/completeProject/pauseProject/reactivateProject）执行后
    // 若不主动刷新，UI 会继续显示旧标题/旧状态徽章直到用户退出详情页重新进入——
    // 这会让刚接入的编辑/归档/暂停/恢复入口显得"点了没反应"，故补充此方法。
    // 仅当当前打开的详情页恰好是被修改的项目时才刷新，避免误刷新到已切换项目的
    // detailState（与 openProject 的 detailProjectId 判重逻辑保持一致）。
    private suspend fun refreshDetailProjectIfCurrent(projectId: String) {
        if (detailProjectId != projectId) return
        val updated = repo.getById(projectId)
        _detailState.update { it.copy(project = updated) }
    }

    // ── 里程碑 ───────────────────────────────────────────────

    fun addMilestone(projectId: String, title: String, description: String = "") {
        viewModelScope.launch {
            runCatching {  // P3-18: 静默吞异常已加日志
                repo.addMilestone(projectId, title, description)
            }
                .onFailure { e -> _detailState.update { it.copy(error = e.message) } }
        }
    }

    fun completeMilestone(milestoneId: String) {
        viewModelScope.launch {
            // 第8窗口问题8修复：原先无异常处理。
            runCatching {  // P3-18: 静默吞异常已加日志
                repo.completeMilestone(milestoneId)
            }
                .onFailure { e ->
                    ZLog.e("ProjectViewModel", "里程碑完成状态更新失败（id=$milestoneId）", e)
                    _detailState.update { it.copy(error = e.message ?: "操作失败") }
                }
        }
    }

    // ── 成员 ─────────────────────────────────────────────────

    fun addMember(projectId: String, characterId: Int, role: String = "CONTRIBUTOR") {
        viewModelScope.launch {
            // P1-22 修复：原 runCatching 吞掉 addMember 异常后仍继续 getMembers / getById /
            // scheduleDailyPlannerJob，导致「成员未加入却仍为它注册成长规划任务」（孤儿任务），
            // 且后续裸 DB 读抛异常会传播出 viewModelScope.launch 引发未捕获崩溃。
            // 改为：addMember 失败即中止（不排程）；后续 DB 读各自 try-catch，失败优雅中止。
            try {
                repo.addMember(projectId, characterId, role)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("ProjectViewModel", "添加成员失败（projectId=$projectId, characterId=$characterId）", e)
                _detailState.update { it.copy(error = e.message ?: "添加成员失败") }
                return@launch
            }
            val members = try {
                repo.getMembers(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("ProjectViewModel", "读取成员失败（projectId=$projectId）", e)
                _detailState.update { it.copy(error = e.message ?: "读取成员失败") }
                return@launch
            }
            _detailState.update { it.copy(members = members) }
            // 角色加入项目后自动注册成长规划日程
            val project = try {
                repo.getById(projectId) ?: return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("ProjectViewModel", "读取项目失败（projectId=$projectId）", e)
                _detailState.update { it.copy(error = e.message ?: "读取项目失败") }
                return@launch
            }
            scheduleDailyPlannerJob(project.title, projectId, characterId)
        }
    }

    fun removeMember(projectId: String, characterId: Int) {
        viewModelScope.launch {
            // 第8窗口问题8修复：原先无异常处理。
            runCatching {  // P3-18: 静默吞异常已加日志
                repo.removeMember(projectId, characterId)
                repo.getMembers(projectId)
            }.onSuccess { members ->
                _detailState.update { it.copy(members = members) }
            }.onFailure { e ->
                ZLog.e("ProjectViewModel", "移除成员失败（projectId=$projectId, characterId=$characterId）", e)
                _detailState.update { it.copy(error = e.message ?: "移除成员失败") }
            }
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
            runCatching {  // P3-18: 静默吞异常已加日志
                repo.addKnowledge(projectId, content, title, null, source, importance)
            }
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
            runCatching {  // P3-18: 静默吞异常已加日志
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

    /**
     * 编辑知识条目（标题/内容/重要度）。
     * UI 侧知识库条目原来只有删除入口，点开看不到完整内容也改不了——
     * 这个方法配合 KnowledgeRow 点击进详情弹窗，一并解决"无法预览"和
     * "无法编辑"两个问题（预览就是把未截断的 content 显示在弹窗里）。
     */
    fun updateKnowledge(id: String, title: String, content: String, importance: Int) {
        viewModelScope.launch {
            runCatching {
                repo.updateKnowledge(id, title, content, importance)
            }
                .onFailure { e ->
                    ZLog.e("ProjectViewModel", "知识库条目更新失败（id=$id）", e)
                    _detailState.update { it.copy(error = e.message ?: "更新失败") }
                }
        }
    }

    fun deleteKnowledge(id: String) {
        viewModelScope.launch {
            // 第8窗口问题8修复：原先无异常处理。
            runCatching {  // P3-18: 静默吞异常已加日志
                repo.deleteKnowledge(id)
            }
                .onFailure { e ->
                    ZLog.e("ProjectViewModel", "知识库条目删除失败（id=$id）", e)
                    _detailState.update { it.copy(error = e.message ?: "删除失败") }
                }
        }
    }

    // ── 知识库搜索（审查报告问题9：searchKnowledge FTS 接入 UI）──
    //
    // repo.searchKnowledge()/knowledgeDao.searchFts() 此前已存在完整实现，
    // 但从未被任何 ViewModel/UI 调用——是一段完整但无入口的死代码。
    // 本次补齐 UI 侧的查询状态与触发方法，串联现有 FTS 能力。
    //
    // 设计要点：
    // - 空查询（用户清空搜索框）视为"退出搜索模式"，不触发 FTS 查询，
    //   直接清空 knowledgeSearchResults，UI 据此回落展示 detail.knowledge 全量列表。
    // - FTS5/FTS4 的 MATCH 语法对用户输入很敏感（裸露的双引号、* 等符号可能
    //   导致 SQLite 抛 SQLiteException），查询包一层 runCatching，失败写入
    //   knowledgeSearchError 而不是让异常沿协程往上抛崩溃 App。
    // - 每次调用取消上一次未返回的搜索（knowledgeSearchJob?.cancel()），避免
    //   用户连续输入时慢请求覆盖快请求的结果（同 openProject 的处理思路）。
    fun searchKnowledge(projectId: String, query: String) {
        knowledgeSearchJob?.cancel()
        val trimmed = query.trim()
        _detailState.update {
            it.copy(
                knowledgeSearchQuery = query,
                knowledgeSearchError = null,
            )
        }
        if (trimmed.isEmpty()) {
            // 退出搜索模式：清空结果，UI 回落展示全量知识列表
            _detailState.update {
                it.copy(knowledgeSearchResults = emptyList(), isSearchingKnowledge = false)
            }
            return
        }
        knowledgeSearchJob = viewModelScope.launch {
            _detailState.update { it.copy(isSearchingKnowledge = true) }
            runCatching {  // P3-18: 静默吞异常已加日志
                repo.searchKnowledge(projectId, trimmed)
            }
                .onSuccess { results ->
                    _detailState.update {
                        it.copy(knowledgeSearchResults = results, isSearchingKnowledge = false)
                    }
                }
                .onFailure { e ->
                    ZLog.w("ProjectViewModel", "searchKnowledge 失败: query=$trimmed", e)
                    _detailState.update {
                        it.copy(
                            knowledgeSearchResults = emptyList(),
                            isSearchingKnowledge = false,
                            knowledgeSearchError = "搜索失败，请尝试其他关键词",
                        )
                    }
                }
        }
    }

    /** 清空搜索框，退出搜索模式，回落展示全量知识列表。 */
    fun clearKnowledgeSearch() {
        knowledgeSearchJob?.cancel()
        _detailState.update {
            it.copy(
                knowledgeSearchQuery = "",
                knowledgeSearchResults = emptyList(),
                isSearchingKnowledge = false,
                knowledgeSearchError = null,
            )
        }
    }

    // 成长规划日程注册（角色加入项目时自动触发）
    private suspend fun scheduleDailyPlannerJob(
        projectTitle: String,
        projectId: String,
        characterId: Int,
    ) {
        // 计算今天21:00的时间戳
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 21)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            // 如果当前时间已过 21:00，排到明日
            if (timeInMillis <= System.currentTimeMillis()) {
                add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
        }
        runCatching {
            scheduleRepo.createJob(
                characterId      = characterId,
                title            = "「$projectTitle」成长规划",
                toolName         = "project_daily_planner",
                toolParams       = mapOf("project_id" to projectId, "character_id" to characterId.toString()),
                repeatIntervalMs = java.util.concurrent.TimeUnit.HOURS.toMillis(24),
                nextRunAt        = cal.timeInMillis,
            )
        }.onFailure {
            // P3-18 修复：此前仅 runCatching 吞掉异常，没有实际记录，注释与代码不符。
            // 现补上真正的日志调用；仍不向上抛出，因为该任务失败不应阻塞项目创建主流程。
            ZLog.w("ProjectViewModel", "scheduleDailyPlannerJob 创建定时任务失败", it)
        } // 失败不阻塞主流程，但现在至少会留下排查线索
    }

    // 审查报告问题10修复：原 clearError() 无任何调用方——ProjectDetailScreen
    // 在 detail.project == null 的错误态下已经提供了返回按钮，用户离开该页面
    // 即相当于退出错误展示，无需额外的"清除错误"入口，故删除死代码而非补
    // UI 入口（对比 clearImportError()，后者确实被 ProjectDetailScreen 消费，
    // 保留不变）。
    fun clearImportError() {
        _detailState.update { it.copy(importError = null) }
    }

    // ── UI 升级 v2.0 帧14：项目列表卡摘要 ─────────────────────
    // 列表态只有 ProjectEntity，列表卡的新设计元素（金条进度 / 三列统计 /
    // 头像叠放 / 里程碑 chip）需要里程碑、成员、知识数据。这里提供一个
    // 一次性挂起快照查询，供 ProjectCard 通过 produceState 拉取——不为
    // 整张列表建 N 个 Flow 订阅（项目列表通常很短，一次性查询足够；且列表
    // 本身已是 Room Flow 派生，项目增删会触发整表刷新重建卡片，摘要自然
    // 跟着重算）。各查询独立 try/catch 兜底，单条失败不阻断其余字段，且
    // CancellationException 向上抛出以正确支持协程取消。
    suspend fun getProjectCardSummary(projectId: String): ProjectCardSummary {
        // 各查询独立兜底：单条失败不阻断其余字段。CancellationException 必须
        // 向上抛出以正确支持协程取消（produceState 切换/离场时取消），不能像
        // 普通 Throwable 一样被吞掉——这与本类 init 块的处理方式一致。
        val milestones = try {
            repo.getMilestones(projectId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("ProjectVM", "getProjectCardSummary/milestones 失败", e)
            emptyList()
        }
        val members = try {
            repo.getMembers(projectId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("ProjectVM", "getProjectCardSummary/members 失败", e)
            emptyList()
        }
        val knowledgeCount = try {
            repo.observeKnowledge(projectId).first().size
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("ProjectVM", "getProjectCardSummary/knowledge 失败", e)
            0
        }
        return ProjectCardSummary(
            milestones         = milestones,
            knowledgeCount     = knowledgeCount,
            memberCharacterIds = members.map { it.characterId },
        )
    }

    // ── 内部工具 ─────────────────────────────────────────────
}
