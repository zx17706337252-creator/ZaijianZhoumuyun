package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.datastore.BriefingDataStore
import com.zaijian.zhoumuyun.data.model.BriefingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
//  BriefingViewModel — 离线简报开场页
//  整合方案 v2.1 4.7 节。结构参照 PresenceViewModel
//  （AndroidViewModel + MutableStateFlow<XxxUiState> + asStateFlow()）。
//
//  时间起点逻辑：
//    - 首次安装（BriefingDataStore 里没有记录）→ 起点回退为 7 天前。
//    - 非首次 → 起点是「上一次简报生成完成时」记下的真实时间戳，
//      不是固定 7 天，如实反映距上次打开过去了多久。
//    - 生成完本次简报后，立即把当前时间写回 DataStore，作为下一次
//      简报的起点，保证统计区间首尾相接、不漏不重。
// ─────────────────────────────────────────────────────────────

private const val FALLBACK_WINDOW_MS = 7 * 86_400_000L // 首次安装兜底：7 天

data class BriefingUiState(
    val isLoading: Boolean = true,
    val data: BriefingData? = null,
)

class BriefingViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BriefingUiState())
    val uiState: StateFlow<BriefingUiState> = _uiState.asStateFlow()

    private val store = BriefingDataStore(application)

    init {
        viewModelScope.launch {
            val lastOpenAt = store.lastOpenAtFlow.first()
            val since = lastOpenAt ?: (System.currentTimeMillis() - FALLBACK_WINDOW_MS)
            val data = AppContainer.instance.briefingRepo.generateBriefing(since)
            _uiState.value = BriefingUiState(isLoading = false, data = data)
            store.setLastOpenAt(System.currentTimeMillis())
        }
    }
}
