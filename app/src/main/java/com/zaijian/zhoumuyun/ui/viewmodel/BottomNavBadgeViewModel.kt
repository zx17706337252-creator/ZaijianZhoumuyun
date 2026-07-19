package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * BottomNavBadgeViewModel — 底部导航栏角标数据（M4 修复）
 *
 * 职责：
 * 将原本在 AppNavigation Composable 内部直接访问 DAO 的逻辑
 * 上移到 ViewModel，彻底消除 UI 层直连数据层的问题。
 *
 * 当前只暴露「成长」Tab 的未完成目标计数（[incompleteGoalCount]）。
 * 如后续其他 Tab 也需要角标，在此统一扩展。
 */
class BottomNavBadgeViewModel(application: Application) : AndroidViewModel(application) {

    // S8-窗口01 收口：原裸持 AppDatabase.getInstance(application).learningGoalDao()，
    // 改引用 AppContainer 共享的 learningGoalRepo。
    private val learningGoalRepo = AppContainer.instance.learningGoalRepo

    /**
     * 全角色未完成学习目标数（isActive=1 且 status≠COMPLETED）。
     * 响应式 Flow，任意目标状态变化后自动推送新值。
     * AppNavigation 通过 collectAsState(initial = 0) 订阅，驱动「成长」Tab 角标。
     */
    val incompleteGoalCount: StateFlow<Int> =
        learningGoalRepo
            .observeIncompleteCount()
            .stateIn(
                scope            = viewModelScope,
                started          = SharingStarted.WhileSubscribed(5_000),
                initialValue     = 0,
            )
}
