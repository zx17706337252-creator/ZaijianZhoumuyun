package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity
import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
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
    /** 编辑时回显原始下次执行时间，仅用于展示，不直接参与保存逻辑 */
    val originalNextRunAt: Long? = null,
) {
    companion object {
        const val DEFAULT_TOOL_NAME = "datetime"
    }
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
)

class PersonalScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)

    // 复用与 schedule_create/update/delete 工具完全相同的 Repository 实现，
    // 保证手动编辑和 Agent 写入在「本地 Room + 云端 Supabase」两侧行为一致。
    private val scheduleRepository = ScheduleRepository(
        scheduledJobDao = db.scheduledJobDao(),
        jobResultDao    = db.jobResultDao(),
    )

    private var currentCharacterId: Int = -1

    private val _uiState = MutableStateFlow(PersonalScheduleUiState())
    val uiState: StateFlow<PersonalScheduleUiState> = _uiState.asStateFlow()

    /** 新增/编辑草稿；null = 编辑面板未打开 */
    private val _draft = MutableStateFlow<ScheduleDraft?>(null)
    val draft: StateFlow<ScheduleDraft?> = _draft.asStateFlow()

    /** 已注册的 Agent 工具名列表，供「执行工具」选择器使用 */
    val availableToolNames: List<String>
        get() = AgentToolRegistry.allNames().sorted()

    fun init(characterId: Int) {
        if (currentCharacterId == characterId) return
        currentCharacterId = characterId
        viewModelScope.launch {
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
        } catch (_: Exception) {
            ""
        }
        _draft.value = ScheduleDraft(
            id                = job.id,
            title             = job.title,
            toolName          = job.toolName,
            toolParamsText    = paramsText,
            repeatPreset      = job.repeatIntervalMs.toRepeatPreset(),
            delayHours        = 0.0,
            originalNextRunAt = job.nextRunAt,
        )
    }

    fun onDraftTitleChange(v: String) = _draft.update { it?.copy(title = v) }
    fun onDraftToolNameChange(v: String) = _draft.update { it?.copy(toolName = v) }
    fun onDraftParamsTextChange(v: String) = _draft.update { it?.copy(toolParamsText = v) }
    fun onDraftRepeatChange(v: RepeatPreset) = _draft.update { it?.copy(repeatPreset = v) }
    fun onDraftDelayHoursChange(v: Double) = _draft.update { it?.copy(delayHours = v.coerceAtLeast(0.0)) }

    fun dismissDraft() {
        _draft.value = null
    }

    // ── 保存草稿 ─────────────────────────────────────────────

    /**
     * 解析草稿中的 toolParamsText（格式：key="val",key2="val2"，与 ScheduleCreateTool
     * 的 PARAM_REGEX 保持一致）为 Map，便于复用 Repository 的 create/update 签名。
     */
    private fun parseParamsText(text: String): Map<String, String> {
        // 验收修复：原写法 """...*)"""" 结尾连续4个引号，与 ScheduleCreateTool.kt/
        // ScheduleUpdateTool.kt 里逐字符相同的既有编译错误（三重引号字符串被提前
        // 截断，见 ScheduleCreateTool.kt 同位置注释）。同样改为把收尾引号拆到
        // + "\"" 里拼接，最终正则字符串不变。
        val regex = Regex("""(\w+)="((?:[^"\\]|\\.)*)""" + "\"")
        return regex.findAll(text)
            .associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
    }

    fun saveDraft() {
        val d = _draft.value ?: return
        val title = d.title.trim()
        if (title.isEmpty()) return
        val cid = currentCharacterId.takeIf { it >= 0 } ?: return
        val toolName = d.toolName.trim().ifEmpty { ScheduleDraft.DEFAULT_TOOL_NAME }
        val toolParams = parseParamsText(d.toolParamsText)
        val repeatIntervalMs = d.repeatPreset.toIntervalMs()
        val nextRunAt = System.currentTimeMillis() +
            (d.delayHours * 60 * 60 * 1000L).toLong()

        viewModelScope.launch {
            if (d.id == null) {
                scheduleRepository.createJob(
                    characterId      = cid,
                    title            = title,
                    toolName         = toolName,
                    toolParams       = toolParams,
                    repeatIntervalMs = repeatIntervalMs,
                    nextRunAt        = nextRunAt,
                )
            } else {
                scheduleRepository.updateJob(
                    id               = d.id,
                    title            = title,
                    toolName         = toolName,
                    toolParamsJson   = JSONObject(toolParams as Map<*, *>).toString(),
                    repeatIntervalMs = repeatIntervalMs,
                    nextRunAt        = nextRunAt,
                )
            }
            _draft.value = null
        }
    }

    // ── 删除 / 启停 ───────────────────────────────────────────

    fun deleteJob(jobId: String) {
        viewModelScope.launch { scheduleRepository.deleteJob(jobId) }
    }

    fun toggleEnabled(job: ScheduledJobEntity) {
        // 与 GlobalScheduleViewModel.toggleEnabled 保持一致：enabled 字段目前
        // 只在本地 Room 生效（SupabaseClient 没有暴露云端 enabled 字段的 PATCH 接口，
        // upsert 时云端 enabled 恒为 true）。这里不假装做云端同步，避免误导。
        viewModelScope.launch {
            val dao = db.scheduledJobDao()
            if (job.enabled) {
                dao.disable(job.id)
            } else {
                dao.update(job.copy(enabled = true))
            }
        }
    }
}
