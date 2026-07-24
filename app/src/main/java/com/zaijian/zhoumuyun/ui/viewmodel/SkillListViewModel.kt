package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.SkillEntity
import com.zaijian.zhoumuyun.data.db.entity.SkillStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
//  SkillListViewModel（Window C 缺口 2 · 技能管理面板）
//
//  职责：
//  - 从 Room 观察当前角色的技能列表（observeSkills）
//  - 提供技能的编辑 / 废弃 / 恢复 / 删除操作（全部 actor="USER"）
//  - 提供变更日志订阅（observeEditLog）供详情页时间线展示
//
//  范式对齐 GoalViewModel.kt / MemoryViewModel.kt：
//  AndroidViewModel + AppContainer.instance.xxxRepo + StateFlow
//
//  设计方案 v1.2 §6：用户侧管理面板，与 Agent 侧走同一 SkillRepository
//  写入路径，Room Flow 自动推送变更到所有订阅者。
// ─────────────────────────────────────────────────────────────

/** 技能列表过滤选项 */
enum class SkillFilter { ALL, ACTIVE, DEPRECATED }

/** 技能列表 UI 状态 */
data class SkillUiState(
    val skills: List<SkillEntity> = emptyList(),
    val filter: SkillFilter = SkillFilter.ALL,
    val isLoading: Boolean = true,
)

class SkillListViewModel(application: Application) : AndroidViewModel(application) {

    private val skillRepo = AppContainer.instance.skillRepo

    private val _filter = MutableStateFlow(SkillFilter.ALL)

    // P2-15 修复：将 characterId 从函数参数改为内部 MutableStateFlow，
    // 配合 flatMapLatest 实现"characterId 变化时自动切换数据源"，
    // 避免每次重组都创建新 StateFlow 导致 UI 短暂闪烁。
    private val _characterId = MutableStateFlow(-1)

    /**
     * 角色技能列表 + 过滤器的组合 Flow。
     * ALL 覆盖 DRAFT（目前无写入路径产出 DRAFT，但过滤逻辑留着避免后续遗漏）。
     *
     * P2-15 修复：原实现为 `fun uiState(characterId: Int)`，每次调用都创建新
     * StateFlow 实例，Composable 重组时旧 Flow 被取消、新 Flow 从初始值
     * (isLoading=true) 开始收集，导致用户切换过滤器时 UI 短暂重置为加载中。
     * 改为属性模式后 StateFlow 单例复用，characterId 变化通过
     * _characterId.value 触发 flatMapLatest 平滑切换数据源。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SkillUiState> = _characterId
        .filter { it >= 0 }
        .flatMapLatest { characterId ->
            skillRepo.observeSkills(characterId)
                .combine(_filter) { skills, filter ->
                    val filtered = when (filter) {
                        SkillFilter.ALL        -> skills
                        SkillFilter.ACTIVE     -> skills.filter { it.status == SkillStatus.ACTIVE.name }
                        SkillFilter.DEPRECATED -> skills.filter { it.status == SkillStatus.DEPRECATED.name }
                    }
                    SkillUiState(skills = filtered, filter = filter, isLoading = false)
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SkillUiState())

    /** 由 Composable 在 characterId 变化时调用，驱动 uiState 切换数据源。 */
    fun setCharacterId(characterId: Int) {
        _characterId.value = characterId
    }

    /** §6 详情页变更历史时间线订阅 */
    fun observeEditLog(skillId: String) = skillRepo.observeEditLog(skillId)

    fun setFilter(filter: SkillFilter) {
        _filter.value = filter
    }

    // ── 写操作（§6 用户侧，全部 actor="USER"）──────────────────────

    /**
     * 全字段编辑（name/shortDescriptor/category/fullContent）。
     * 走 [SkillRepository.replace]（非 Agent 用的 [SkillRepository.edit]），
     * 保留 createdAt 不被覆盖，version+1，写变更日志。
     */
    fun saveEdit(
        skill: SkillEntity,
        name: String,
        shortDesc: String,
        fullContent: String,
        category: String?,
    ) {
        viewModelScope.launch {
            skillRepo.replace(
                skill = skill.copy(
                    name = name,
                    shortDescriptor = shortDesc,
                    fullContent = fullContent,
                    category = category,
                ),
                changeSummary = "用户编辑",
                reason = null,
                actor = "USER",
            )
        }
    }

    /** §2 废弃：状态转 DEPRECATED，保留记录不删除 */
    fun deprecate(skillId: String, reason: String) {
        viewModelScope.launch {
            skillRepo.deprecate(skillId, reason, actor = "USER")
        }
    }

    /** §2 从废弃恢复为 ACTIVE */
    fun restore(skillId: String) {
        viewModelScope.launch {
            skillRepo.restore(skillId, actor = "USER")
        }
    }

    /** §2 彻底删除（主表 + 变更日志） */
    fun delete(skillId: String) {
        viewModelScope.launch {
            skillRepo.delete(skillId)
        }
    }
}
