package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
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
)

// ─────────────────────────────────────────────────────────────
//  ViewModel
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val db  = AppDatabase.getInstance(application)
    private val dao = db.scheduledJobDao()

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
                        dao.observeInRange(fromMs, toMs)
                    } else {
                        dao.observeInRangeForCharacters(listOf(selectedId), fromMs, toMs)
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

    suspend fun deleteJob(jobId: String) {
        dao.deleteById(jobId)
    }

    suspend fun toggleEnabled(job: ScheduledJobEntity) {
        if (job.enabled) {
            dao.disable(job.id)
        } else {
            // 重新启用：更新 enable 字段（Dao 只有 disable，这里用 update）
            val updated = job.copy(enabled = true)
            dao.update(updated)
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
