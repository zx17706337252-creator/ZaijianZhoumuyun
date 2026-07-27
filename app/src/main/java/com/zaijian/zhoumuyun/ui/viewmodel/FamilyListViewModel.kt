package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import com.zaijian.zhoumuyun.util.ZLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
//  FamilyListViewModel（Step 6 UI 层，v29→v30）
//
//  职责：
//  给定第一代母亲的 characterId，查询完整家族链，
//  组装「母亲 + 全部后代」有序列表供 FamilyPickerSheet 展示。
//
//  调用链：
//    FamilyPickerSheet 传入 firstGenCharacterId
//    → loadFamily() 查 DaughterCharacterRepository.getFamilyChain()
//    → 拼 listOf(mother) + descendants
//    → 更新 uiState
//
//  注意：此 ViewModel 不负责「有没有后代」的判断——那个判断在
//  PresenceViewModel.resolveCharacterEntry() 里做，有后代才会展开
//  FamilyPickerSheet，所以此 Sheet 至少总有 ≥2 项（母亲 + 至少一个后代）。
//  但为防御性起见，空列表情况也兜底处理（显示 loading 或 error 状态）。
// ─────────────────────────────────────────────────────────────

sealed class FamilyListUiState {
    object Loading : FamilyListUiState()
    data class Ready(val members: List<FamilyMember>) : FamilyListUiState()
    data class Error(val message: String) : FamilyListUiState()
}

/**
 * 家族列表里的一个成员。
 *
 * @param config      完整角色配置，FamilyPickerSheet 从中取名字/颜色等展示信息
 * @param generation  代数（1 = 第一代母亲，2 = 女儿，3 = 孙女），用于代数标签展示
 */
data class FamilyMember(
    val config: CharacterConfig,
    val generation: Int,
    // P1-47 修复：新增性别字段，从 DaughterCharacterEntity.kinshipTerm 读取。
    // W6-02 复核修正：实际取值是代际称呼词（"女儿"/"孙女"），不是"男"/"女"——
    // 母亲角色（generation=1）为 null，后代角色为 "女儿"/"孙女"/null（旧数据）。
    val gender: String? = null,
)

class FamilyListViewModel(application: Application) : AndroidViewModel(application) {

    // 阶段2 S-1 批次1收口：daughterRepo/identityDao 原先各自独立 new（构造参数
    // 与容器完全一致），改引用 AppContainer 共享实例。db 字段本身无其他用途，
    // 一并移除。
    private val daughterRepo = AppContainer.instance.daughterCharacterRepo
    private val identityDao = AppContainer.instance.identityRepo

    private val _uiState = MutableStateFlow<FamilyListUiState>(FamilyListUiState.Loading)
    val uiState: StateFlow<FamilyListUiState> = _uiState.asStateFlow()

    // 第8窗口问题5修复：保存 loadFamily 协程引用，参照 TimelineViewModel 的模式，
    // 快速切换查看不同母亲的家族链时取消上一次未完成的加载，避免后启动的协程
    // 先完成、先启动的协程后完成时覆盖掉正确结果。
    private var loadJob: kotlinx.coroutines.Job? = null

    /**
     * 加载家族链。
     * 由 FamilyPickerSheet 在展开时调用，传入第一代母亲的 characterId（1-9）。
     */
    fun loadFamily(firstGenCharacterId: Int) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val mother = DefaultCharacters.firstOrNull { it.id == firstGenCharacterId }
                if (mother == null) {
                    _uiState.value = FamilyListUiState.Error("找不到母亲角色（id=$firstGenCharacterId）")
                    return@launch
                }
                // 检查 DB 中是否有自定义头像，覆盖 DefaultCharacters 的硬编码 URL
                val motherWithAvatar = identityDao.getById(firstGenCharacterId)?.avatarUrl
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { mother.copy(avatarUrl = it) }
                    ?: mother

                val descendants = daughterRepo.getFamilyChain(firstGenCharacterId)

                val members = mutableListOf<FamilyMember>()
                members.add(FamilyMember(config = motherWithAvatar, generation = 1))
                descendants.forEachIndexed { index, entry ->
                    members.add(FamilyMember(
                        config     = entry.config,
                        generation = index + 2,
                        gender     = entry.gender,
                    ))
                }

                _uiState.value = FamilyListUiState.Ready(members)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("FamilyListViewModel", "loadFamily failed", e)
                _uiState.value = FamilyListUiState.Error("加载失败：${e.message}")
            }
        }
    }
}
