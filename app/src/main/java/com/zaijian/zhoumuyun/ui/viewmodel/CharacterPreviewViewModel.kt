package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────
//  CharacterPreviewViewModel（Phase 4 · 4.2 修复）
//
//  职责：为 CharacterPreviewSheet 提供"最近记忆"数据，替代原先
//  Composable 内部直接 LaunchedEffect { AppDatabase.getInstance(...) }
//  的架构违规写法（与 TaskViewModel 修复 BUG-7 是同一类问题、同一套模式）。
//
//  为什么新建一个轻量 ViewModel，而不是挂载到某个已有 ViewModel：
//  CharacterPreviewSheet 被 CharacterScreen 和 WorldScreen 两处复用，
//  这两个 Screen 各自持有语境不同的 ViewModel（分别是角色列表场景和
//  世界主界面场景），挂到任何一方都会造成另一方的跨 Screen 耦合。
//  这个组件本身只需要"按 characterId 查最近记忆"这一件轻量的事，
//  不需要 TaskViewModel 那种复杂的多路 Flow 合并 UiState，
//  所以没有照搬那个模式，而是保持最小职责单一的 StateFlow<List<String>>。
// ─────────────────────────────────────────────────────────────

class CharacterPreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val db   = AppDatabase.getInstance(application)
    private val repo = MemoryRepository(db.memoryDao(), db.memoryCandidateDao())

    private val _recentMemories = MutableStateFlow<List<String>>(emptyList())
    val recentMemories: StateFlow<List<String>> = _recentMemories.asStateFlow()

    /** 当前已加载记忆所属的 characterId，避免同一角色重复触发查询（见 loadForCharacter 内说明）。 */
    private var loadedForCharacterId: Int? = null

    /**
     * 按角色 ID 加载最近 2 条 PERSONAL 记忆。
     * Composable 侧用 LaunchedEffect(character.id) { viewModel.loadForCharacter(character.id) } 触发。
     */
    fun loadForCharacter(characterId: Int) {
        // 同一角色重复调用（例如 Sheet 因重组再次触发 LaunchedEffect）时跳过重复查询。
        if (loadedForCharacterId == characterId) return
        loadedForCharacterId = characterId
        viewModelScope.launch {
            val memories = withContext(Dispatchers.IO) {
                repo.getByDomain(characterId, MemoryDomain.PERSONAL, limit = 2)
                    .map { it.content }
            }
            _recentMemories.value = memories
        }
    }
}
