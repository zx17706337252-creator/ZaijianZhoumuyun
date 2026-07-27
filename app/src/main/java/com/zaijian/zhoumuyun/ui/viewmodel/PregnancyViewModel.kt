package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.model.BirthRecord
import com.zaijian.zhoumuyun.data.model.PregnancyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
//  PregnancyViewModel — P4.0（V5 执行方案）
//
//  职责：
//  - 观察当前角色的怀孕状态（供书架/详情页状态展示）
//  - 观察当前角色的生育记录列表（供角色档案「生育记录」区块）
//  - 怀孕本身由 P5 判定链路（叙事解锁+伴侣同意+周期判定，见
//    PregnancyTriggerManager）自动触发，本 ViewModel 不再提供手动
//    开始怀孕入口，只保留终止妊娠（D2.6 主动流产）的用户操作。
// ─────────────────────────────────────────────────────────────

data class PregnancyUiState(
    val pregnancy: PregnancyState        = PregnancyState(characterId = -1),
    val birthRecords: List<BirthRecord>  = emptyList(),
    val isLoading: Boolean               = true,
    // D2.6 批次三：终止妊娠二次确认弹窗的显隐状态
    val showTerminateConfirm: Boolean    = false,
    // W6-01 修复：confirmTerminate 失败时的提示信息，UI 层通过 LaunchedEffect
    // 订阅后弹 Snackbar，展示后调用 clearErrorMessage() 清空，避免重复弹出。
    // 命名用 errorMessage（而非 error）以明确这是"待展示的提示文案"，
    // 不是异常对象本身。
    val errorMessage: String?            = null,
)

class PregnancyViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_CHARACTER_ID = "pregnancy_current_character_id"
    }

    // repo 原独立 new（构造参数与容器完全一致），改引用 AppContainer 共享实例。
    private val repo = AppContainer.instance.pregnancyRepo

    // D2.6 批次三：终止妊娠需要走 TriggerManager（落库 + 情绪副作用 + 即时 Prompt），
    // 不能直接调 repo.triggerMiscarriage()，否则跳过情绪副作用写入。
    // S8-窗口01 收口：改用 AppContainer.instance.createPregnancyTriggerManagerMinimal()——
    // 不再需要本文件裸持 db、也不再需要单独取出 cycleRepo/characterStateRepo 局部
    // 变量才能拼出 PregnancyTriggerManager（工厂方法内部默认参数已指向容器共享的
    // menstrualCycleRepo/characterStateRepo 实例；不传 relationshipEngine/aiJudge/
    // consentJudge 的功能差异——本 ViewModel 只走终止妊娠这一条路径，用不到受孕
    // 弹窗/AI 同意判定——同样由工厂方法内部封装）。行为与迁移前完全一致。
    private val triggerManager = AppContainer.instance.createPregnancyTriggerManagerMinimal()

    private val _uiState = MutableStateFlow(PregnancyUiState())
    val uiState: StateFlow<PregnancyUiState> = _uiState.asStateFlow()

    // #35 修复：构造时从 savedStateHandle 恢复（进程死亡重建后不再是 -1）
    private var currentCharacterId: Int = savedStateHandle.get<Int>(KEY_CHARACTER_ID) ?: -1

    /** #35 修复：同 GoalViewModel，防止重建后误判"角色没变"而跳过下方的两个订阅 */
    private var hasRunInit = false
    // P2-5 修复：保存 Flow 订阅协程引用，init() 切换角色时 cancel 旧协程
    private var observeJob: kotlinx.coroutines.Job? = null
    private var birthJob: kotlinx.coroutines.Job? = null

    fun init(characterId: Int) {
        if (hasRunInit && currentCharacterId == characterId) return
        hasRunInit = true
        // P2-5 修复：切换角色时取消上一次的 Flow 订阅协程，
        // 避免 observePregnancy/observeBirthRecords 旧 Flow 继续写入新角色 UI。
        observeJob?.cancel()
        birthJob?.cancel()
        currentCharacterId = characterId
        savedStateHandle[KEY_CHARACTER_ID] = characterId
        // 第九窗口问题5清收：CharacterDetail 路由自跳转时 launchSingleTop 复用
        // 同一 ViewModel 实例，取消旧协程到新 Flow 首次 emit 之间存在空档——
        // 不清空的话 UI 会短暂显示上一个角色的 pregnancy/birthRecords，
        // 顺带清掉终止妊娠二次确认弹窗和残留提示文案，避免挂着上一个角色
        // 的确认框跟到新角色页面上（与已修问题1同根因）。
        _uiState.value = PregnancyUiState(isLoading = true)

        observeJob = viewModelScope.launch {
            repo.observePregnancy(characterId).collect { state ->
                _uiState.update { it.copy(pregnancy = state, isLoading = false) }
            }
        }
        birthJob = viewModelScope.launch {
            repo.observeBirthRecords(characterId).collect { records ->
                _uiState.update { it.copy(birthRecords = records) }
            }
        }
    }

    // ── D2.6 批次三：终止妊娠（叙事流产，用户主动触发） ──────

    /** 打开二次确认弹窗 */
    fun requestTerminate() {
        _uiState.update { it.copy(showTerminateConfirm = true) }
    }

    /** 关闭二次确认弹窗（取消） */
    fun dismissTerminateConfirm() {
        _uiState.update { it.copy(showTerminateConfirm = false) }
    }

    /** 二次确认后实际执行：落库 + 情绪副作用 + 即时 Prompt 文案均由 TriggerManager 内部处理 */
    fun confirmTerminate() {
        val cid = currentCharacterId.takeIf { it >= 0 } ?: return
        _uiState.update { it.copy(showTerminateConfirm = false) }
        // P2-20 修复：confirmTerminate 增加了 try-catch 外壳，
        // 防止 triggerMiscarriage 内部抛出异常时未清理 loading 状态导致 UI 卡死。
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    triggerManager.triggerMiscarriage(cid)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(errorMessage = e.message ?: "终止妊娠失败，请重试") }
            }
        }
    }

    /** W6-01 修复：UI 展示完 errorMessage 后调用，清空提示避免重复弹出 */
    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
