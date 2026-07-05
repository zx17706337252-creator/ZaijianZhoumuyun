package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Fix-16：角色基础配置查询 ViewModel。
 *
 * 问题：CharacterDetailScreen 原先用裸 produceState 直接在 Composable 里查 Room，
 * 导致每次从其他 Tab 导航回来（NavBackStackEntry 被销毁重建），produceState
 * 重新执行一次 DB 查询，即使 characterId 完全没变。
 *
 * 解决方案：把查询提升至 ViewModel。ViewModel 的生命周期绑定到 NavBackStackEntry，
 * 只要 backstack entry 不被彻底销毁（例如同一目标内的 Tab 切换、横竖屏旋转），
 * characterConfig 就不会重查；导航离开再回来（backstack entry 重建）时才重查，
 * 而此时重查是合理且必要的行为。
 *
 * 附带好处：
 * - 预设角色（id 1–9）只走内存查找，无 IO；
 * - 女儿角色（id 1000+）单次 Room 查询后缓存在 StateFlow，同一 backstack entry
 *   内的任何重组都命中缓存，不再多次访问数据库。
 */
@Suppress("Unused")
class CharacterConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val daughterRepo by lazy {
        DaughterCharacterRepository(
            dao = AppDatabase.getInstance(application).daughterCharacterDao(),
        )
    }

    /** 当前加载中的 characterId，用于避免并发重复查询 */
    private var loadedId: Int = -1

    private val _characterConfig = MutableStateFlow<CharacterConfig?>(null)
    val characterConfig: StateFlow<CharacterConfig?> = _characterConfig

    /**
     * 加载指定角色的配置。
     *
     * - 若 [characterId] 与上次一致且结果已存在，直接返回（无 IO）。
     * - 预设角色（id 1–9）：走内存查找，同步返回，不走协程。
     * - 女儿角色（id 1000+）：启动协程做一次 Room 查询，结果缓存在 StateFlow。
     */
    fun load(characterId: Int) {
        if (characterId == loadedId && _characterConfig.value != null) return
        loadedId = characterId

        // 先尝试预设角色（内存，无 IO）
        val preset = DefaultCharacters.firstOrNull { it.id == characterId }
        if (preset != null) {
            _characterConfig.value = preset
            return
        }

        // 女儿或未知角色：Room 查询
        _characterConfig.value = null   // 重置，让 UI 显示加载态（如有必要）
        viewModelScope.launch {
            _characterConfig.value = daughterRepo.getCharacterConfig(characterId)
        }
    }
}
