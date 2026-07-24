package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────
//  ProfileViewModel — 「我」Tab
//
//  S8-窗口01 修复：ProfileScreen.kt（角色头像覆盖表 produceState）与
//  ProfileStatsRow.kt（对话/任务/记忆统计 LaunchedEffect）原先各自在
//  Composable 内直接访问 AppContainer.instance.xxxRepo，是 UI 层绕过
//  ViewModel 直接持有数据访问逻辑的分层违规。两处数据源彼此独立、无
//  耦合，收敛进同一个 ProfileViewModel 的两个字段，Composable 侧
//  只负责订阅 uiState，不再持有任何 Repository 引用。
//
//  结构参照 PresenceViewModel（AndroidViewModel + MutableStateFlow<UiState> +
//  viewModelScope.launch { flow.collectLatest {} }）与 BriefingViewModel
//  （一次性聚合查询 + isLoading 标志位）。
// ─────────────────────────────────────────────────────────────

data class ProfileUiState(
    /**
     * 角色 ID → 头像 URL 覆盖表。原 ProfileScreen.characterAvatarOverrides：
     * DefaultCharacters 里的 avatarUrl 是硬编码默认值，用户在角色详情页上传的
     * 头像存在 character_identity.avatarUrl，这里持续订阅 identityRepo.observeAll()
     * 实时组成覆盖表，供 CharacterManagementSection 使用。
     */
    val characterAvatarOverrides: Map<Int, String> = emptyMap(),
    /** 原 ProfileStatsRow 的统计数据：跨所有角色累计对话数、已完成任务数、累计记忆条数。 */
    val totalMessages: Int = 0,
    val completedTasks: Int = 0,
    val totalMemories: Int = 0,
    /** 统计数据加载中标志。加载完成前不展示 0，避免"0 次对话"被误读为"确实没有记录"。 */
    val isStatsLoading: Boolean = true,
)

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        observeCharacterAvatarOverrides()
        loadStats()
    }

    private fun observeCharacterAvatarOverrides() {
        viewModelScope.launch {
            AppContainer.instance.identityRepo
                .observeAll()
                .collectLatest { entities ->
                    _uiState.value = _uiState.value.copy(
                        characterAvatarOverrides = entities.associate { it.characterId to it.avatarUrl }
                    )
                }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isStatsLoading = true)
            try {
                val identityRepo = AppContainer.instance.identityRepo
                val messageRepo  = AppContainer.instance.messageRepo
                val taskRepo     = AppContainer.instance.taskRepo
                val memoryRepo   = AppContainer.instance.memoryRepo

                val (msgs, tasks, mems) = withContext(Dispatchers.IO) {
                    // 从数据库获取所有角色ID（含女儿Agent角色）
                    val allIds = identityRepo.getAllIds()
                    // 跨所有角色累计消息数
                    val msgs  = allIds.sumOf { messageRepo.countByCharacter(it) }
                    // 已完成任务数
                    val tasks = taskRepo.countByStatus("completed")
                    // 跨所有角色累计记忆条数
                    val mems  = allIds.sumOf { memoryRepo.count(it) }
                    Triple(msgs, tasks, mems)
                }
                _uiState.value = _uiState.value.copy(
                    totalMessages   = msgs,
                    completedTasks  = tasks,
                    totalMemories   = mems,
                    isStatsLoading  = false,
                )
            } catch (e: Exception) {
                ZLog.e("ProfileViewModel", "统计数据加载失败", e)
                // 失败时保留已有数值（多为初始 0），仅关闭 loading 状态，
                // 避免 StatsRow 永久卡在 loading 指示器上。
                _uiState.value = _uiState.value.copy(isStatsLoading = false)
            }
        }
    }

    /**
     * 读取用户昵称（E0 分层收口）。
     *
     * 原 ProfileScreen 直接持有 AppContainer.instance.userProfileRepo 调
     * getUserName()，现收敛到 ViewModel；Composable 侧只调本方法，不再
     * 直接触碰 Repository（E0 coupling_scan 违规点 #1 的修复落地）。
     */
    fun getUserName(): String =
        AppContainer.instance.userProfileRepo.getUserName()

    /**
     * 写入用户昵称（与 getUserName 对称，E0 分层收口）。
     *
     * ProfileScreen 编辑称呼 Dialog 的 onConfirm 回调通过本方法写入，
     * 不直接持有 AppContainer.instance.userProfileRepo（避免重新引入
     * 本类头部注释点名要修的"Composable 裸持有 Repository"违规）。
     */
    fun setUserName(name: String) {
        AppContainer.instance.userProfileRepo.setUserName(name)
    }
}
