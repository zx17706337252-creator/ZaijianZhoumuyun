package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.manager.PregnancyTriggerManager
import com.zaijian.zhoumuyun.data.model.BirthRecord
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.data.repository.CharacterStateRepository
import com.zaijian.zhoumuyun.data.repository.MenstrualCycleRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
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
//  - 提供"开始怀孕"手动/测试入口：本阶段触发方式先不做复杂判定
//    （叙事解锁触发+伴侣同意+周期判定是 P5 的范畴），后续接入 P5 时
//    只需把这个手动入口换成 P5 判定结果调用同一个 startPregnancy 即可，
//    UI/Prompt/记录三处不用改。
// ─────────────────────────────────────────────────────────────

data class PregnancyUiState(
    val pregnancy: PregnancyState        = PregnancyState(characterId = -1),
    val birthRecords: List<BirthRecord>  = emptyList(),
    val isLoading: Boolean               = true,
    // D2.6 批次三：终止妊娠二次确认弹窗的显隐状态
    val showTerminateConfirm: Boolean    = false,
)

@Suppress("Unused")
class PregnancyViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_CHARACTER_ID = "pregnancy_current_character_id"
    }

    private val db = AppDatabase.getInstance(application)
    private val repo = PregnancyRepository(db.pregnancyDao())

    // D2.6 批次三：终止妊娠需要走 TriggerManager（落库 + 情绪副作用 + 即时 Prompt），
    // 不能直接调 repo.triggerMiscarriage()，否则跳过情绪副作用写入。
    private val cycleRepo = MenstrualCycleRepository(db.menstrualCycleDao())
    private val characterStateRepo = CharacterStateRepository(db.characterStateDao())
    private val triggerManager = PregnancyTriggerManager(
        db                  = db,
        pregnancyRepository = repo,
        cycleRepository     = cycleRepo,
        stateRepository     = characterStateRepo,
    )

    private val _uiState = MutableStateFlow(PregnancyUiState())
    val uiState: StateFlow<PregnancyUiState> = _uiState.asStateFlow()

    // #35 修复：构造时从 savedStateHandle 恢复（进程死亡重建后不再是 -1）
    private var currentCharacterId: Int = savedStateHandle.get<Int>(KEY_CHARACTER_ID) ?: -1

    /** #35 修复：同 GoalViewModel，防止重建后误判"角色没变"而跳过下方的两个订阅 */
    private var hasRunInit = false

    fun init(characterId: Int) {
        if (hasRunInit && currentCharacterId == characterId) return
        hasRunInit = true
        currentCharacterId = characterId
        savedStateHandle[KEY_CHARACTER_ID] = characterId

        viewModelScope.launch {
            repo.observePregnancy(characterId).collect { state ->
                _uiState.update { it.copy(pregnancy = state, isLoading = false) }
            }
        }
        viewModelScope.launch {
            repo.observeBirthRecords(characterId).collect { records ->
                _uiState.update { it.copy(birthRecords = records) }
            }
        }
    }

    /** 手动/测试入口：开始怀孕。详见类注释——后续 P5 接入时调用方会换成判定结果。 */
    fun startPregnancy() {
        val cid = currentCharacterId.takeIf { it >= 0 } ?: return
        viewModelScope.launch { repo.startPregnancy(cid) }
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
        // Fix-31: triggerMiscarriage 内部有 3 次 DB 写入（孕期落库 → 情绪副作用 → 状态更新），
        // 若 viewModelScope 在中途被取消（用户快速关闭页面），会留下「已结束孕期但情绪副作用
        // 未写入」的不一致状态。用 NonCancellable 保证这三步要么全部完成，要么在取消信号
        // 到达前继续跑完，不在中间被打断。
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                triggerManager.triggerMiscarriage(cid)
            }
            // pregnancy Flow 会自动推送 isPregnant=false 的最新状态，无需手动 update
        }
    }
}
