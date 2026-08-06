package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.agent.AgentTaskJobExecutor
import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────
//  PersonalScheduleViewModel — Stage C + D
//
//  职责：
//  - 观察单个角色的全部日程（ScheduleRepository.observeJobs）
//  - 提供手动新增 / 编辑 / 删除日程的草稿状态与保存逻辑
//  - 与 schedule_create / schedule_update / schedule_delete 三个
//    Agent 工具共用同一个 ScheduleRepository，保证手动编辑和 Agent
//    写入的任务在数据层完全一致（本地 Room + 云端 Supabase 同步）。
// ─────────────────────────────────────────────────────────────

/**
 * 新增/编辑日程时的草稿状态。
 *
 * id == null        → 新建
 * id != null        → 编辑现有任务
 *
 * repeatPreset 与 toolName/toolParamsText 共同决定保存时写入的字段；
 * UI 层只需绑定这些原始字段，不需要关心 Repository 内部的 JSON 序列化细节。
 */
data class ScheduleDraft(
    val id: String? = null,
    val title: String = "",
    /** 执行时要调用的 Agent 工具名，默认 datetime（内置工具，无网络依赖，App 启动即注册，永远可用） */
    val toolName: String = DEFAULT_TOOL_NAME,
    /** 工具参数，UI 用单个文本域编辑，格式 key="val",key2="val2"，与 Agent 标签格式保持一致 */
    val toolParamsText: String = "",
    /** 重复规则预设 */
    val repeatPreset: RepeatPreset = RepeatPreset.ONCE,
    /** 距现在的延迟小时数（新建时填，编辑时表示"从现在起重新调度"的延迟） */
    val delayHours: Double = 0.0,
    /**
     * 延迟小时数输入框的原始文本。审查报告问题9修复：原先直接用
     * text.toDoubleOrNull() ?: 0.0 静默把非法输入回退为0，用户输入错误
     * 格式（如"abc"）时看不到任何提示，也看不到自己刚输入的内容被清空。
     * 现在原始文本单独保留用于回显，delayHours 只在能解析时才更新，
     * 解析失败时通过 delayHoursError 提示，不再静默吞掉用户输入。
     */
    val delayHoursText: String = "",
    val delayHoursError: String? = null,
    /**
     * 工具参数文本的格式校验错误提示（审查报告问题9修复）。
     * saveDraft() 解析 toolParamsText 时如发现格式不合法会写入此字段，
     * UI 层据此展示错误提示而非静默丢弃无法匹配的参数。
     */
    val paramsError: String? = null,
    /** 编辑时回显原始下次执行时间，仅用于展示，不直接参与保存逻辑 */
    val originalNextRunAt: Long? = null,
    /**
     * 同文件-15 修复：编辑时保存任务的原始 repeatIntervalMs（未经预设映射）。
     *
     * 问题：Agent 创建的 36h 任务，openEditDraft 时 toRepeatPreset() 会就近吸附
     * 成 DAILY(24h) 用于 Chip 高亮。若用户没碰重复间隔 Chip 就点保存，
     * saveDraft 用 repeatPreset.toIntervalMs() 回算得到 24h，36h 被静默改成 24h。
     *
     * 修复：草稿单独存一份 originalIntervalMs（原始值，不经过预设映射）。
     * 只要用户没手动点过 Chip（presetManuallyChanged=false），保存时就用原始值；
     * 用户主动选了别的预设后，才用预设值覆盖。
     */
    val originalIntervalMs: Long? = null,
    /** 同文件-15 修复：标记用户是否手动点过重复间隔 Chip（区分"自动吸附回显"与"主动选择"） */
    val presetManuallyChanged: Boolean = false,
    /**
     * 日程系统批次4新增：日程模式。
     * - TOOL       工具型（mode A，现状）：到点调指定已注册工具
     * - AGENT_TASK 工单型（mode B）：到点把 description 当触发消息让角色自己推理回应
     *
     * UI 层只认这个枚举，不感知数据层哨兵值字符串 "agent_task"——
     * 哨兵值统一收口于 AgentTaskJobExecutor.SENTINEL，本 ViewModel 在
     * openEditDraft（读 job.toolName → TaskKind）和 saveDraft
     * （TaskKind → toolName 落值）两个边界点做转换，UI 层完全无感知。
     */
    val mode: TaskKind = TaskKind.TOOL,
    /**
     * 工单型描述（mode B 专用）。UI 多行文本框编辑，保存时落库为
     * ScheduledJobEntity.description。工具型此字段保持空字符串。
     */
    val description: String = "",
    /**
     * 工单描述的校验错误提示：mode=AGENT_TASK 时若 description 为空会写入此字段，
     * UI 据此展示错误并禁用保存按钮，与 paramsError/delayHoursError 同款范式。
     */
    val descriptionError: String? = null,
    /**
     * 关联项目 ID（日程系统第七节新增，可选增强）。
     *
     * 指向 `ProjectEntity` 的主键，null = 独立日程。与 mode/description 完全正交：
     * 工具型与工单型任务都可关联项目。UI 多行文本框/工具选择器与此字段互不影响。
     *
     * UI 层只持有原始 String?，项目标题的展示由 Screen 侧通过 ProjectRepository
     * 自行查询（ViewModel 不缓存项目标题——项目列表会变，缓存会脏）。
     */
    val projectId: String? = null,
) {
    companion object {
        const val DEFAULT_TOOL_NAME = "datetime"
        const val TITLE_MAX_LENGTH = 100
        const val PARAMS_TEXT_MAX_LENGTH = 300
        /** 工单描述最大长度，介于标题(100)和工具参数(300)之间，工单描述本质是一段较长文本 */
        const val DESCRIPTION_MAX_LENGTH = 500
    }
}

/**
 * 日程系统批次4新增：UI 层日程模式枚举。
 *
 * 与数据层哨兵值 AgentTaskJobExecutor.SENTINEL 的映射只在 ViewModel 边界发生
 * （openEditDraft 读时映射、saveDraft 写时映射），UI Composable 只认此枚举，
 * 不直接引用 AgentTaskJobExecutor.SENTINEL，解耦 UI 与数据层常量。
 *
 * 批次7修复：原名 ScheduleMode 与 RoundtableViewModel.kt 中的圆桌调度模式枚举
 * （AUTO/HEURISTIC/AI_ONLY）同包同名冲突，导致 Redeclaration。此枚举描述的是
 * 「工单类型」而非调度策略，改名为 TaskKind 以消除歧义。
 */
enum class TaskKind(val label: String) {
    TOOL("工具型"),
    AGENT_TASK("工单型"),
}

/**
 * 重复规则预设，UI 用 Chip 选择，保存时换算为 repeatIntervalMs（小时）。
 */
enum class RepeatPreset(val label: String, val hours: Double?) {
    ONCE("仅一次", null),
    HOURLY("每小时", 1.0),
    HALF_DAY("每12小时", 12.0),
    DAILY("每天", 24.0),
    WEEKLY("每周", 24.0 * 7),
}

fun RepeatPreset.toIntervalMs(): Long? = hours?.let { (it * 60 * 60 * 1000L).toLong() }

/** 由已有任务的 repeatIntervalMs 反推最接近的预设（用于打开编辑草稿时回显选中状态） */
fun Long?.toRepeatPreset(): RepeatPreset {
    if (this == null) return RepeatPreset.ONCE
    val hours = this / (60 * 60 * 1000.0)
    return RepeatPreset.values()
        .filter { it.hours != null }
        .minByOrNull { kotlin.math.abs((it.hours ?: 0.0) - hours) }
        ?: RepeatPreset.DAILY
}

data class PersonalScheduleUiState(
    val jobs: List<ScheduledJobEntity> = emptyList(),
    val isLoading: Boolean = true,
    // 审查报告问题10配套：toggleEnabled 补上异常处理后，需要一个字段承接错误信息。
    val error: String? = null,
)

class PersonalScheduleViewModel(application: Application) : AndroidViewModel(application) {

    // 阶段2 S-1 收尾：原先本地独立构造一份完整的 ScheduleRepository（5参，含
    // CalendarSyncHelper(application)/context），与 AppContainer.scheduleRepo
    // 构造参数完全一致（同一套 dao + CalendarSyncHelper(appContext) + appContext），
    // 改为引用容器共享实例。行为不变：手动编辑与 schedule_create/update/delete
    // 三个 Agent 工具、GlobalScheduleViewModel 现在共用同一个 Repository 实例。
    // L-P0-4 遗漏补丁：toggleEnabled 已迁移至 scheduleRepository.toggleJobWithFullSync()，
    // db 引用已移除（不再需要裸 dao 访问）。
    private val scheduleRepository = AppContainer.instance.scheduleRepo

    // 日程系统第七节：项目仓库，用于 (a) ScheduleDraftSheet 的项目选择器列出可选项目，
    // (b) 卡片展示侧查项目标题（按 id 单条查，UI 自行调用）。
    // 引用容器共享实例（与 TaskViewModel.projectRepo 同源），不在 ViewModel 里 new。
    private val projectRepository = AppContainer.instance.projectRepo

    private var currentCharacterId: Int = -1

    // 第8窗口问题4修复：保存 Flow 订阅协程引用，切换角色时 cancel 旧协程，
    // 避免旧角色的 observeJobs Flow 持续运行并覆盖新角色的 UI 状态。
    private var observeJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow(PersonalScheduleUiState())
    val uiState: StateFlow<PersonalScheduleUiState> = _uiState.asStateFlow()

    /** 新增/编辑草稿；null = 编辑面板未打开 */
    private val _draft = MutableStateFlow<ScheduleDraft?>(null)
    // P1-21 修复：保存进行中守卫。createJobWithFullSync 涉及本地 DB + 日历 + WorkManager
    // + Supabase 网络，耗时秒级；此期间 _draft 仍非空，快速连点保存按钮会重复创建同一条日程。
    // 用该标志在保存期间忽略重复点击（失败时保留草稿供重试，成功后才清空草稿）。
    @Volatile
    private var isSaving = false
    val draft: StateFlow<ScheduleDraft?> = _draft.asStateFlow()

    /** 已注册的 Agent 工具名列表，供「执行工具」选择器使用 */
    val availableToolNames: List<String>
        get() = AgentToolRegistry.allNames().sorted()

    /**
     * 日程系统第七节新增：可选关联项目列表（供 ScheduleDraftSheet 选择器渲染）。
     *
     * 用 observeActive() 而非 getActiveProjectsForCharacter——日程关联项目不区分
     * 角色（一个日程只关联一个项目，与角色无强绑定），列出所有 ACTIVE+PAUSED 项目
     * 让用户选即可。Flow 形式保证项目状态变化时选择器自动刷新。
     *
     * UI 侧 collect 此 Flow 渲染选择器；卡片展示侧若需查标题，直接调
     * projectRepository.getById（单条同步查询，不缓存，避免脏数据）。
     */
    val availableProjects: kotlinx.coroutines.flow.Flow<List<com.zaijian.zhoumuyun.data.db.entity.ProjectEntity>> =
        projectRepository.observeActive()

    /**
     * 日程系统第七节新增：按 id 查项目标题（卡片展示侧用）。
     * 挂起函数，UI 侧在 LaunchedEffect/collectAsState 里调用。
     * 查不到返回 null（UI fallback 显示 projectId 前 8 位）。
     */
    suspend fun getProjectTitle(id: String): String? = projectRepository.getById(id)?.title

    fun init(characterId: Int) {
        if (currentCharacterId == characterId) return
        currentCharacterId = characterId
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            scheduleRepository.observeJobs(characterId).collect { jobs ->
                _uiState.update { it.copy(jobs = jobs, isLoading = false) }
            }
        }
    }

    // ── 草稿操作（编辑面板双向绑定）─────────────────────────

    fun openNewDraft() {
        _draft.value = ScheduleDraft()
    }

    fun openEditDraft(job: ScheduledJobEntity) {
        val paramsText = try {
            val json = JSONObject(job.toolParamsJson)
            json.keys().asSequence()
                .joinToString(",") { k -> "$k=\"${json.getString(k)}\"" }
        } catch (_: Throwable) {
            ""
        }
        // 批次4：按 job.toolName 是否 == 哨兵值映射 TaskKind。
        // 工单型回显 description，工具型 description 留空（UI 不显示该输入框）。
        val mode = if (job.toolName == AgentTaskJobExecutor.SENTINEL) {
            TaskKind.AGENT_TASK
        } else {
            TaskKind.TOOL
        }
        _draft.value = ScheduleDraft(
            id                = job.id,
            title             = job.title,
            toolName          = job.toolName,
            toolParamsText    = paramsText,
            repeatPreset      = job.repeatIntervalMs.toRepeatPreset(),
            delayHours        = 0.0,
            delayHoursText    = "",
            originalNextRunAt = job.nextRunAt,
            // 同文件-15 修复：保存原始 repeatIntervalMs，用户没动 Chip 时保存用原值
            originalIntervalMs = job.repeatIntervalMs,
            presetManuallyChanged = false,
            mode              = mode,
            description       = job.description ?: "",
            // 日程系统第七节：回显关联项目 ID（null = 独立日程，UI 选择器显示"未关联"）
            projectId          = job.projectId,
        )
    }

    fun onDraftTitleChange(v: String) =
        _draft.update { it?.copy(title = v.take(ScheduleDraft.TITLE_MAX_LENGTH)) }

    fun onDraftToolNameChange(v: String) = _draft.update { it?.copy(toolName = v) }

    fun onDraftParamsTextChange(v: String) = _draft.update {
        it?.copy(
            toolParamsText = v.take(ScheduleDraft.PARAMS_TEXT_MAX_LENGTH),
            // 用户重新编辑时先清掉上一次的格式错误提示，等下次 saveDraft() 重新校验
            paramsError = null,
        )
    }

    /**
     * 批次4新增：切换日程模式。
     *
     * 切换时不清除另一模式的字段（toolName/toolParamsText 与 description 各自独立保留），
     * 用户切回来还能看到之前的输入——避免"误切一下就丢了刚填的内容"。
     * 切到工单型时若 description 已有内容则清掉 descriptionError，否则保留
     * 让 saveDraft 重新校验。
     */
    fun onDraftModeChange(mode: TaskKind) = _draft.update {
        it?.copy(
            mode             = mode,
            // 切换模式后两类校验错误都要重置：工具型的 paramsError 和工单型的 descriptionError
            // 都属于"当前模式下才生效的校验"，切到另一模式后旧错误无意义，重新校验在 saveDraft 触发。
            paramsError      = null,
            descriptionError = null,
        )
    }

    /**
     * 批次4新增：编辑工单描述。
     * 用户重新编辑时先清掉上一次的空描述错误提示，等 saveDraft() 重新校验。
     */
    fun onDraftDescriptionChange(v: String) = _draft.update {
        it?.copy(
            description      = v.take(ScheduleDraft.DESCRIPTION_MAX_LENGTH),
            descriptionError = null,
        )
    }

    /**
     * 日程系统第七节新增：设置关联项目。
     *
     * @param v 选中的项目 ID；传 null 表示解除关联（用户点"无关联项目"选项）。
     *          不做存在性校验——校验在 saveDraft() 调 Repository 时由
     *          ScheduleCreateTool/UpdateTool 或 Repository 层兜底（UI 列表只列
     *          实际存在的项目，正常流程不会传不存在的 ID）。
     */
    fun onDraftProjectIdChange(v: String?) = _draft.update {
        it?.copy(projectId = v)
    }

    // 同文件-15 修复：标记用户手动选了预设，saveDraft 时用预设值覆盖 originalIntervalMs
    fun onDraftRepeatChange(v: RepeatPreset) = _draft.update {
        it?.copy(repeatPreset = v, presetManuallyChanged = true)
    }

    /**
     * 审查报告问题9修复：原实现签名为 (Double)，UI 层用
     * text.toDoubleOrNull() ?: 0.0 把非法输入静默转换成0再传进来，用户完全
     * 感知不到自己输入了非法内容。现在改为接收原始文本，校验失败时保留原始
     * 文本回显并写入 delayHoursError，不修改 delayHours 数值本身，
     * 避免用户输入错字符时保存的日程被悄悄改成"立即执行"。
     */
    fun onDraftDelayHoursChange(text: String) = _draft.update { current ->
        current ?: return@update null
        if (text.isBlank()) {
            return@update current.copy(delayHoursText = text, delayHours = 0.0, delayHoursError = null)
        }
        val parsed = text.toDoubleOrNull()
        when {
            parsed == null -> current.copy(
                delayHoursText  = text,
                delayHoursError = "请输入数字",
            )
            parsed < 0.0 -> current.copy(
                delayHoursText  = text,
                delayHoursError = "延迟不能为负数",
            )
            else -> current.copy(
                delayHoursText  = text,
                delayHours      = parsed,
                delayHoursError = null,
            )
        }
    }

    fun dismissDraft() {
        _draft.value = null
    }

    // ── 保存草稿 ─────────────────────────────────────────────

    /**
     * 解析草稿中的 toolParamsText（格式：key="val",key2="val2"，与 ScheduleCreateTool
     * 的 PARAM_REGEX 保持一致）为 Map，便于复用 Repository 的 create/update 签名。
     *
     * 审查报告问题9修复：原实现对无法匹配 key="val" 格式的输入直接返回空 Map，
     * 用户输错格式（如漏了引号）时参数被静默丢弃，保存后才发现"工具参数怎么没了"。
     * 现在区分三种情况：输入为空（合法，视为无参数）、非空但一个键值对都没匹配到
     * （格式错误，返回 null 让调用方拒绝保存并提示）、成功解析出至少一对。
     */
    private fun parseParamsText(text: String): Map<String, String>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyMap()
        // 验收修复：原写法 """...*)"""" 结尾连续4个引号，与 ScheduleCreateTool.kt/
        // ScheduleUpdateTool.kt 里逐字符相同的既有编译错误（三重引号字符串被提前
        // 截断，见 ScheduleCreateTool.kt 同位置注释）。同样改为把收尾引号拆到
        // + "\"" 里拼接，最终正则字符串不变。
        val regex = Regex("""(\w+)="((?:[^"\\]|\\.)*)""" + "\"")
        val matches = regex.findAll(trimmed).toList()
        if (matches.isEmpty()) return null
        return matches.associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
    }

    fun saveDraft() {
        val d = _draft.value ?: return
        // P1-21 修复：保存进行中，忽略重复点击（避免重复创建同一条日程）。
        if (isSaving) return
        val title = d.title.trim()
        if (title.isEmpty()) return
        if (d.delayHoursError != null) {
            _uiState.update { it.copy(error = "延迟小时数格式不正确，请检查后再保存") }
            return
        }
        val cid = currentCharacterId.takeIf { it >= 0 } ?: return

        // 批次4：按 mode 分叉计算 toolName / description / toolParams。
        // - 工单型（AGENT_TASK）：toolName 落哨兵值，description 必须非空，
        //   toolParams 强制空 Map（工单型无 toolParams 概念，与 ScheduleUpdateTool 对齐）。
        // - 工具型（TOOL）：保持原逻辑，description 置 null。
        // 这套映射与 ScheduleCreateTool / ScheduleUpdateTool 的 mode 分叉逻辑严格一致，
        // UI 与 Agent 工具两条写入路径对同一字段的语义保持统一。
        // P2-48 修复：原 when 为语句形式，toolParams 声明为可空类型 Map<String, String>?，
        // 传给非空参数时依赖编译器智能转换（脆弱）。改为 when 表达式形式，
        // 编译器能推断出非空联合类型，消除可空声明和不安全 cast。
        val (toolName, description, toolParams) = when (d.mode) {
            TaskKind.AGENT_TASK -> {
                val desc = d.description.trim()
                if (desc.isEmpty()) {
                    _draft.update { it?.copy(descriptionError = "工单型任务必须填写描述") }
                    return
                }
                Triple(AgentTaskJobExecutor.SENTINEL, desc, emptyMap<String, String>())
            }
            TaskKind.TOOL -> {
                val tn = d.toolName.trim().ifEmpty { ScheduleDraft.DEFAULT_TOOL_NAME }
                val parsed = parseParamsText(d.toolParamsText)
                if (parsed == null) {
                    // 格式错误：例如漏了引号或分隔符不对。写回 paramsError 让 UI 就地提示，
                    // 不再静默按"无参数"处理，避免用户以为保存成功但参数其实丢了。
                    _draft.update { it?.copy(paramsError = "参数格式不正确，请使用 key=\"value\" 的格式") }
                    return
                }
                Triple(tn, null as String?, parsed)
            }
        }

        // 同文件-15 修复：编辑模式下若用户没手动点过重复间隔 Chip，保存原始
        // repeatIntervalMs（如 Agent 建的 36h 任务不会被就近吸附成 24h）。
        // 只有用户主动选了预设 Chip 后，才用预设值覆盖。新建模式 always 用预设值。
        val repeatIntervalMs = if (d.id != null && !d.presetManuallyChanged && d.originalIntervalMs != null) {
            d.originalIntervalMs
        } else {
            d.repeatPreset.toIntervalMs()
        }
        // 同文件-12 修复：编辑模式下（d.id != null）若用户没有改动延迟小时数
        // 输入框（delayHoursText 为空，openEditDraft 回显时就是空串），说明用户
        // 无意重新调度，应保留原始 nextRunAt；此前无条件用
        // now + delayHours(默认0.0) 计算，导致编辑时任务原定的执行时间被
        // 悄悄丢弃、变成立即触发。新建时（d.id == null）delayHoursText 也可能
        // 为空（用户没填延迟，默认立即执行），这是预期行为，不受影响。
        val nextRunAt = if (d.id != null && d.delayHoursText.isBlank() && d.originalNextRunAt != null) {
            d.originalNextRunAt
        } else {
            System.currentTimeMillis() + (d.delayHours * 60 * 60 * 1000L).toLong()
        }

        // P1-21 修复：校验全部通过后置位保存标志，才开始异步保存。
        isSaving = true
        viewModelScope.launch {
            try {
                if (d.id == null) {
                    // L-P0-4 修复：使用 createJobWithFullSync 替代 createJob，
                    // 补齐日历同步和 WorkManager 调度
                    scheduleRepository.createJobWithFullSync(
                        characterId      = cid,
                        title            = title,
                        toolName         = toolName,
                        toolParams       = toolParams,
                        repeatIntervalMs = repeatIntervalMs,
                        nextRunAt        = nextRunAt,
                        description      = description,
                        // 日程系统第七节：透传关联项目 ID（null = 独立日程）
                        projectId        = d.projectId,
                    )
                } else {
                    // L-P0-4 遗漏补丁：编辑分支此前调用残缺的 updateJob，改用
                    // updateJobWithFullSync 补齐日历事件更新和 WorkManager 重新调度，
                    // 与上面的新建分支（createJobWithFullSync）保持一致。
                    //
                    // 批次4变更：此前因 ScheduleDraft 无 description 字段，这里临时用
                    // `existing?.description` 兜底（批次1的临时占位，注释明说"UI 编辑框
                    // 留给后续批次接入"）。本批次 ScheduleDraft 已支持 description 编辑，
                    // 改为直接透传 d 计算出的 description，去掉 getJob 兜底查询——
                    // 验收标准第7条"用户能通过 UI 单独修改 description 文本"由此跑通。
                    scheduleRepository.updateJobWithFullSync(
                        id               = d.id,
                        title            = title,
                        toolName         = toolName,
                        toolParamsJson   = JSONObject(toolParams).toString(),
                        repeatIntervalMs = repeatIntervalMs,
                        nextRunAt        = nextRunAt,
                        description      = description,
                        // 日程系统第七节：透传关联项目 ID（null = 独立日程）
                        projectId        = d.projectId,
                    )
                }
                _draft.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // P2-29 修复：保存失败时保留草稿，让用户可以重试或修改后再次保存，
                // 避免辛苦填写的日程参数因为一次网络错误就永久丢失。
                ZLog.w("PersonalScheduleVM", "saveDraft 失败", e)
                _uiState.update { it.copy(error = "保存失败：${e.message}") }
            } finally {
                // P1-21 修复：无论成功/失败/取消都复位保存标志，允许下一次保存。
                isSaving = false
            }
        }
    }

    // ── 删除 / 启停 ───────────────────────────────────────────

    fun deleteJob(jobId: String) {
        // L-P0-4 修复：使用 deleteJobWithFullSync 替代 deleteJob，
        // 补齐日历事件删除和 WorkManager 取消调度
        // P2-18 修复：传入当前角色 ID 做归属校验，防止删除其他角色的日程
        viewModelScope.launch {
            try {
                scheduleRepository.deleteJobWithFullSync(
                    jobId,
                    userId = null,
                    characterId = currentCharacterId.takeIf { it >= 0 },
                )
            } catch (e: SecurityException) {
                _uiState.update { it.copy(error = "无权删除此日程") }
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(error = "日程不存在或已被删除") }
            }
        }
    }

    // L-P0-4 遗漏补丁：toggleEnabled 原先直接裸调 dao.disable()/dao.update()，
    // 无 WorkManager 调度变更、无日历事件同步。现改为调用 ScheduleRepository
    // 统一入口 toggleJobWithFullSync()，与 createJobWithFullSync /
    // deleteJobWithFullSync / updateJobWithFullSync 形成统一的写入路径。
    fun toggleEnabled(job: ScheduledJobEntity) {
        viewModelScope.launch {
            try {
                scheduleRepository.toggleJobWithFullSync(job)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("PersonalScheduleViewModel", "切换日程状态失败 jobId=${job.id}", e)
                _uiState.update { it.copy(error = "切换失败：${e.message}") }
            }
        }
    }

    /** 参照 PregnancyViewModel.clearErrorMessage：UI 展示完 error 后调用，清空避免重复弹出。 */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
