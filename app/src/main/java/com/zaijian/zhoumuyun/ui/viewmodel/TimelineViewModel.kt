package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TimelineUiState(
    val events: List<WorldEventEntity> = emptyList(),
    val isLoading: Boolean = true,
    /** W14 修复：加载失败时的错误信息，null = 无错误 */
    val error: String? = null,
)

class TimelineViewModel(application: Application) : AndroidViewModel(application) {

    // 阶段2 S-1 收尾：原先本地独立 new EventRepository(db.worldEventDao())，
    // 构造参数与 AppContainer.eventRepo 完全一致（同一个 db.worldEventDao()），
    // 改为引用容器共享实例，不再重复持有。
    private val eventRepo = AppContainer.instance.eventRepo

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    // P2-18 修复：load() 多次调用时取消上一次的查询协程，
    // 避免旧协程在 after 回调中写入过期数据覆盖新数据。
    private var loadJob: kotlinx.coroutines.Job? = null

    fun load(actorId: String? = null) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // 第九窗口问题5清收：CharacterDetail「时间线」Tab 路由自跳转时
            // launchSingleTop 复用同一 ViewModel 实例，load() 原先只置
            // isLoading=true 不清空 events，旧角色最多 100 条事件会在新查询
            // 返回前残留展示（与已修问题1 ChatViewModel 竞态同根因）。
            _uiState.update { it.copy(events = emptyList(), isLoading = true, error = null) }
            try {
                val events = withContext(Dispatchers.IO) {
                    if (actorId != null) eventRepo.queryByActor(actorId, 100)
                    else eventRepo.queryLatest(100)
                }
                _uiState.update { it.copy(events = events, isLoading = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(
                    isLoading = false,
                    error = "加载时间线失败：${e.message?.take(80) ?: "未知错误"}"
                ) }
            }
        }
    }
}
