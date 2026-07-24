package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

// ─────────────────────────────────────────────────────────────
//  数据模型
// ─────────────────────────────────────────────────────────────

/**
 * 一个时间点上可能有多个角色的任务，聚合为一条时间轴条目。
 * timeLabel：格式化后的时间字符串，如 "09:00"
 */
data class ScheduleTimeSlot(
    val timeLabel: String,       // "HH:mm"
    val nextRunAt: Long,         // 原始时间戳，用于排序
    val items: List<ScheduleJobItem>,
)

/**
 * 单条日程条目，包含任务本身 + 对应角色信息。
 */
data class ScheduleJobItem(
    val job: ScheduledJobEntity,
    val character: CharacterConfig?,  // null = 找不到对应角色（女儿角色等）
)

/**
 * 重复类型枚举，用于 UI 展示标签。
 */
enum class RepeatLabel(val text: String) {
    ONCE("一次"),
    MINUTELY("每分钟"),
    HOURLY("每小时"),
    HALF_HOUR("每30分钟"),
    DAILY("每天"),
    WEEKLY("每周"),
    MONTHLY("每月"),
    CUSTOM("循环"),
}

fun ScheduledJobEntity.repeatLabel(): RepeatLabel {
    val ms = repeatIntervalMs ?: return RepeatLabel.ONCE
    return when {
        ms < 60_000L           -> RepeatLabel.MINUTELY
        ms == 30 * 60_000L     -> RepeatLabel.HALF_HOUR
        ms < 60 * 60_000L      -> RepeatLabel.CUSTOM
        ms == 60 * 60_000L     -> RepeatLabel.HOURLY
        ms < 24 * 60 * 60_000L -> RepeatLabel.CUSTOM
        ms == 24 * 60 * 60_000L -> RepeatLabel.DAILY
        ms < 7 * 24 * 60 * 60_000L -> RepeatLabel.CUSTOM
        ms == 7 * 24 * 60 * 60_000L -> RepeatLabel.WEEKLY
        else                   -> RepeatLabel.MONTHLY
    }
}

data class GlobalScheduleUiState(
    /** 当前选中的日期偏移（0 = 今天，1 = 明天，-1 = 昨天） */
    val dayOffset: Int = 0,
    /** 角色筛选；null = 全选（不筛选）。原为 Set<Int> 多选，
     * 改为单选是产品决策（接入 AdaptiveAvatarRow 时一并简化，
     * 99% 场景本就只需要看一个角色的日程，多选场景去掉）。 */
    val selectedCharacterId: Int? = null,
    /** 当前日期的时间轴条目 */
    val timeSlots: List<ScheduleTimeSlot> = emptyList(),
    /** 所有可用角色（用于筛选器头像横滚） */
    val allCharacters: List<CharacterConfig> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

// ─────────────────────────────────────────────────────────────
//  ViewModel
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalScheduleViewModel(application: Application) : AndroidViewModel(application) {

    // S8-窗口01 收口：db/dao 裸引用已移除。observeInRange/observeInRangeForCharacters
    // 已补齐到 ScheduleRepository，全部改走容器共享的 scheduleRepo。
    //
    // 阶段2 S-2 遗留补项：此前本地独立构造整套 db → dao → ScheduleRepository
    // （审计报告"窗口16 P5"点名的问题），AppContainer 当时尚无 scheduleRepo
    // 字段可引用。现改为引用容器共享实例，构造参数（scheduledJobDao/jobResultDao/
    // db/calendarSync/context）与容器完全一致。
    // L-P0-4 修复：使用 ScheduleRepository 替代直接 DA 操作，
    // 使 deleteJob 走完整路径（日历同步 + WorkManager 取消 + Supabase 删除 + Room 删除）。
    private val scheduleRepo = AppContainer.instance.scheduleRepo

    // 日程系统第七节：项目仓库，用于卡片展示侧查项目标题（Global 视图跨多角色，
    // 关联项目可能来自任意角色，observeActive 列出全部 ACTIVE+PAUSED 项目即可）。
    private val projectRepository = AppContainer.instance.projectRepo

    /**
     * 日程系统第七节新增：按 id 查项目标题（卡片展示侧用）。
     * 与 PersonalScheduleViewModel.getProjectTitle 同款实现，避免在两个 ViewModel
     * 间共享状态——Global 视图自己查自己的，互不干扰。
     */
    suspend fun getProjectTitle(id: String): String? = projectRepository.getById(id)?.title

    // 当前日期偏移（响应式，切换日期时更新）
    private val _dayOffset = MutableStateFlow(0)

    // 角色筛选；null = 全选（原 Set<Int> 多选改为单选，见 UiState 注释）
    private val _selectedId = MutableStateFlow<Int?>(null)

    private val _uiState = MutableStateFlow(GlobalScheduleUiState())
    val uiState: StateFlow<GlobalScheduleUiState> = _uiState.asStateFlow()

    // 所有母角色（固定9个）
    private val allCharacters: List<CharacterConfig> = DefaultCharacters

    init {
        // 当日期偏移或筛选角色变化时，重新订阅对应时间窗口的 Flow
        viewModelScope.launch {
            combine(_dayOffset, _selectedId) { offset, selectedId ->
                Pair(offset, selectedId)
            }.flatMapLatest { (offset, selectedId) ->
                val (fromMs, toMs) = dayRange(offset)
                val jobsFlow: Flow<List<ScheduledJobEntity>> =
                    if (selectedId == null) {
                        scheduleRepo.observeInRange(fromMs, toMs)
                    } else {
                        scheduleRepo.observeInRangeForCharacters(listOf(selectedId), fromMs, toMs)
                    }
                jobsFlow.map { jobs -> Triple(offset, selectedId, jobs) }
            }.collect { (offset, selectedId, jobs) ->
                val slots = buildTimeSlots(jobs)
                _uiState.update {
                    it.copy(
                        dayOffset            = offset,
                        selectedCharacterId  = selectedId,
                        timeSlots            = slots,
                        allCharacters        = allCharacters,
                        isLoading            = false,
                    )
                }
            }
        }
    }

    // ── 公开操作 ──────────────────────────────────────────────

    fun setDayOffset(offset: Int) {
        _dayOffset.value = offset
    }

    /** 单选切换：选中同一角色再次点击 = 取消选中（回到全部），选中其他角色 = 切换 */
    fun selectCharacter(characterId: Int) {
        _selectedId.update { current -> if (current == characterId) null else characterId }
    }

    fun clearFilter() {
        _selectedId.value = null
    }

    /** P3-13 修复：供 UI 清除 error 提示。 */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    suspend fun deleteJob(jobId: String) {
        try {
            // L-P0-4 修复：使用 ScheduleRepository 完整删除路径，
            // 替代原来的直接 DA 删除（dao.deleteById）
            // P2-18 修复：全局日程视图可跨角色删除，characterId 传 null 跳过归属校验，
            // 但 deleteJobWithFullSync 内部仍会校验 job 存在性（不存在则抛异常）。
            scheduleRepo.deleteJobWithFullSync(
                jobId       = jobId,
                userId      = null,
                characterId = null,
            )
        } catch (e: Exception) {
            ZLog.e("GlobalScheduleViewModel", "删除日程失败 jobId=$jobId", e)
            _uiState.update { it.copy(error = "删除失败：${e.message}") }
        }
    }

    // L-P0-4 遗漏补丁：toggleEnabled 原先直接裸调 dao.disable()/dao.update()，
    // 无 WorkManager 调度变更、无日历事件同步。现改为调用 ScheduleRepository
    // 统一入口 toggleJobWithFullSync()，与 createJobWithFullSync /
    // deleteJobWithFullSync / updateJobWithFullSync 形成统一的写入路径。
    suspend fun toggleEnabled(job: ScheduledJobEntity) {
        try {
            scheduleRepo.toggleJobWithFullSync(job)
        } catch (e: Exception) {
            ZLog.e("GlobalScheduleViewModel", "切换日程状态失败 jobId=${job.id}", e)
            _uiState.update { it.copy(error = "切换失败：${e.message}") }
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────

    /**
     * 将任务列表按"分钟级别"时间分组，合并同一分钟的多个任务为一个 TimeSlot。
     */
    private fun buildTimeSlots(jobs: List<ScheduledJobEntity>): List<ScheduleTimeSlot> {
        // 按分钟分组（nextRunAt / 60000 * 60000 取整到分钟）
        val grouped = jobs.groupBy { job ->
            job.nextRunAt / 60_000L * 60_000L
        }
        return grouped.entries
            .sortedBy { it.key }
            .map { (minuteMs, groupJobs) ->
                val cal = Calendar.getInstance().apply { timeInMillis = minuteMs }
                val h = cal.get(Calendar.HOUR_OF_DAY)
                val m = cal.get(Calendar.MINUTE)
                val label = "%02d:%02d".format(h, m)
                ScheduleTimeSlot(
                    timeLabel  = label,
                    nextRunAt  = minuteMs,
                    items      = groupJobs.map { job ->
                        ScheduleJobItem(
                            job       = job,
                            character = allCharacters.find { it.id == job.characterId },
                        )
                    },
                )
            }
    }

    /**
     * 计算指定偏移天的 [00:00, 24:00) 时间戳范围。
     */
    private fun dayRange(offset: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, offset)
        }
        val from = cal.timeInMillis
        val to   = from + 24 * 60 * 60_000L
        return from to to
    }
}
