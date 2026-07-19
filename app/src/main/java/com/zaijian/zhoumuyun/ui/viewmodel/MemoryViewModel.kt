package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
//  UI State
// ─────────────────────────────────────────────────────────────

/**
 * 记忆 Tab 的展示模型（UI 层，从 MemoryEntity 映射而来）。
 *
 * 与旧的 sampleMemories 硬编码结构对齐，保持 UI 改动最小化：
 * - id: Long → 用 hashCode() 映射
 * - content: 记忆内容
 * - dateLabel: 格式化日期
 * - isImportant: importance >= 4
 * - aboutSelf: domain == PERSONAL（关于用户）
 * - isCore: isCore 字段透传
 */
data class MemoryUiItem(
    val id: String,
    val content: String,
    val dateLabel: String,
    val isImportant: Boolean,
    val isCore: Boolean,
    /** true = 关于用户（PERSONAL domain），false = 关于角色/世界 */
    val aboutSelf: Boolean,
    val domain: String,
    val importance: Int,
    /** Phase 17：衰减状态标签（null = 永久/核心记忆） */
    val decayLabel: String? = null,
    /** Phase 30 方案三：维度标签（"工作" / "情感" / "世界" / "规则"） */
    val domainLabel: String = "",
    /** Phase 30 方案三：维度色条颜色（ARGB Long，由 UI 层转为 Color） */
    val domainColorArgb: Long = 0xFF9E9E9EL,
)

enum class MemoryFilter {
    ALL,
    IMPORTANT,
    ABOUT_ME,       // 关于我（domain=PERSONAL，原「关于我」）
    ABOUT_WORLD,    // 关于他/世界（原「关于他」）
    WORK,           // Phase 30 方案三：工作记忆（domain=WORK）
    EMOTION,        // Phase 30 方案三：情感记忆（domain=PERSONAL）
}

data class MemoryUiState(
    val items: List<MemoryUiItem> = emptyList(),
    val isLoading: Boolean = true,
    val filter: MemoryFilter = MemoryFilter.ALL,
    /** 操作结果提示（删除/标记等） */
    val snackbar: String? = null,
)

// ─────────────────────────────────────────────────────────────
//  ViewModel
// ─────────────────────────────────────────────────────────────

/**
 * MemoryViewModel（Phase 8）
 *
 * 为 CharacterDetailScreen 的「记忆」Tab 提供真实数据。
 * 替换掉 sampleMemories 硬编码。
 *
 * 职责：
 * 1. 按角色 ID 从 memories 表实时观察数据
 * 2. 支持四种过滤（全部 / 重要 / 关于我 / 关于他）
 * 3. 切换 isImportant（对应 importance 4/3 互切）
 * 4. 删除单条记忆
 * 5. 标记 Core（importance=5）
 */
class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    // 阶段2 S-1 批次1收口：repo 原先独立 new（构造参数与容器完全一致），
    // 改引用 AppContainer 共享实例。db 字段本身无其他用途，一并移除。
    private val repo = AppContainer.instance.memoryRepo

    private val _characterId = MutableStateFlow(-1)
    private val _filter      = MutableStateFlow(MemoryFilter.ALL)
    private val _snackbar    = MutableStateFlow<String?>(null)

    // ─────────────────────────────────────────────────────────
    //  实时数据流：根据 filter 切换观察的 Flow
    // ─────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _entities: StateFlow<List<MemoryEntity>> = _characterId
        .flatMapLatest { cid ->
            if (cid < 0) flowOf(emptyList())
            else repo.observeAll(cid)   // 总是观察全量，在 ViewModel 里过滤
        }
        .catch { emit(emptyList()) }
        .stateIn(
            scope            = viewModelScope,
            started          = SharingStarted.WhileSubscribed(5_000),
            initialValue     = emptyList(),
        )

    // P1-13-24 修复：原实现 _entities.map { _filter.value } 仅在 _entities 变化时触发，
    // _filter / _snackbar 变更不会重算 uiState，导致切换过滤器后 UI 不刷新。
    // 改为 combine 三源，任意一源变化均触发重算。
    val uiState: StateFlow<MemoryUiState> = combine(
        _entities,
        _filter,
        _snackbar,
    ) { entities, filter, snackbar ->
        val allItems = entities.map { it.toUiItem() }
        MemoryUiState(
            items     = applyFilter(allItems, filter),
            isLoading = false,
            filter    = filter,
            snackbar  = snackbar,
        )
    }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = MemoryUiState(isLoading = true),
        )

    // ─────────────────────────────────────────────────────────
    //  初始化
    // ─────────────────────────────────────────────────────────

    fun init(characterId: Int) {
        if (_characterId.value == characterId) return
        _characterId.value = characterId
    }

    // ─────────────────────────────────────────────────────────
    //  过滤切换
    // ─────────────────────────────────────────────────────────

    fun setFilter(filter: MemoryFilter) {
        _filter.value = filter
    }

    // ─────────────────────────────────────────────────────────
    //  操作：切换 isImportant（星标）
    //  逻辑：importance >= 4 → 降到 3；否则 → 升到 4
    // ─────────────────────────────────────────────────────────

    fun toggleImportant(memoryId: String) {
        viewModelScope.launch {
            val entity = _entities.value.find { it.id == memoryId } ?: return@launch
            val newImportance = if (entity.importance >= 4) 3 else 4
            val updated = entity.copy(
                importance = newImportance,
                updatedAt  = System.currentTimeMillis(),
            )
            repo.update(updated)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  操作：删除
    // ─────────────────────────────────────────────────────────

    fun delete(memoryId: String, ftsRowId: Int = 0) {
        viewModelScope.launch {
            repo.deleteById(memoryId, ftsRowId)
            _snackbar.value = "已删除"
        }
    }

    // ─────────────────────────────────────────────────────────
    //  操作：标记/取消 Core Memory
    // ─────────────────────────────────────────────────────────

    fun toggleCore(memoryId: String) {
        viewModelScope.launch {
            val entity = _entities.value.find { it.id == memoryId } ?: return@launch
            val updated = entity.copy(
                isCore     = !entity.isCore,
                importance = if (!entity.isCore) 5 else 4,
                updatedAt  = System.currentTimeMillis(),
            )
            repo.update(updated)
            val msg = if (updated.isCore) "已设为核心记忆" else "已取消核心记忆"
            _snackbar.value = msg
        }
    }

    fun clearSnackbar() {
        _snackbar.value = null
    }

    // ─────────────────────────────────────────────────────────
    //  Phase 16：手动新增记忆
    // ─────────────────────────────────────────────────────────

    /**
     * 手动添加一条记忆（来自用户在「记忆 Tab」点击「+ 新增」）。
     * domain 默认 PERSONAL，importance 默认 3（进入长期记忆）。
     */
    fun addMemory(content: String, domain: MemoryDomain = MemoryDomain.PERSONAL) {
        val cid = _characterId.value
        if (cid < 0 || content.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entity = MemoryEntity(
                id             = java.util.UUID.randomUUID().toString(),
                characterId    = cid,
                domain         = domain.name,
                content        = content.trim(),
                importance     = 3,
                keywords       = "",
                sourceEventId  = null,
                createdAt      = now,
                updatedAt      = now,
                lastAccessedAt = now,
                accessCount    = 0,
                isCore         = false,
                projectId      = null,
            )
            repo.save(entity)
            _snackbar.value = "记忆已保存"
        }
    }

    // ─────────────────────────────────────────────────────────
    //  操作：编辑记忆内容
    // ─────────────────────────────────────────────────────────

    /**
     * 编辑已有记忆的文本内容。
     * domain / importance / isCore 保持不变，仅更新 content 和 updatedAt。
     */
    fun updateContent(memoryId: String, newContent: String) {
        if (newContent.isBlank()) return
        viewModelScope.launch {
            val entity = _entities.value.find { it.id == memoryId } ?: return@launch
            val updated = entity.copy(
                content   = newContent.trim(),
                updatedAt = System.currentTimeMillis(),
            )
            repo.update(updated)
            _snackbar.value = "记忆已更新"
        }
    }

    // ─────────────────────────────────────────────────────────
    //  内部工具
    // ─────────────────────────────────────────────────────────

    private fun applyFilter(items: List<MemoryUiItem>, filter: MemoryFilter): List<MemoryUiItem> =
        when (filter) {
            MemoryFilter.ALL         -> items
            MemoryFilter.IMPORTANT   -> items.filter { it.isImportant }
            MemoryFilter.ABOUT_ME    -> items.filter { it.aboutSelf }
            MemoryFilter.ABOUT_WORLD -> items.filter { !it.aboutSelf }
            // Phase 30 方案三
            MemoryFilter.WORK        -> items.filter { it.domain == com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.WORK.name }
            MemoryFilter.EMOTION     -> items.filter { it.domain == com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.PERSONAL.name }
        }

    private fun MemoryEntity.toUiItem(): MemoryUiItem {
        val isImportant = importance >= 4 || isCore
        val aboutSelf   = domain == MemoryDomain.PERSONAL.name

        // 日期格式化
        val dateLabel = run {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            sdf.format(java.util.Date(createdAt))
        }

        // Phase 17：衰减状态标签
        val now = System.currentTimeMillis()
        val daysSinceCreated = (now - createdAt) / (1000L * 60 * 60 * 24)
        val decayLabel: String? = when {
            isCore          -> null
            importance >= 4 -> "长期"
            importance == 3 -> when {
                daysSinceCreated > 7 -> "7天到期"
                daysSinceCreated > 4 -> "即将到期"
                else                 -> null
            }
            else            -> "即将清理"
        }

        // Phase 30 方案三：维度标签 + 色条颜色
        val domainLabel = when (domain) {
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.WORK.name     -> "工作"
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.PERSONAL.name -> "情感"
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.WORLD.name    -> "世界"
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.RULE.name     -> "规则"
            else -> ""
        }
        val domainColorArgb = when (domain) {
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.WORK.name     -> 0xFF5B9BD5L  // 蓝（由 accentColor 在 UI 层覆盖）
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.PERSONAL.name -> 0xFFC89AA3L  // 暖粉
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.WORLD.name    -> 0xFF9CC2AEL  // 绿
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.RULE.name     -> 0xFFB0A0C8L  // 淡紫
            else -> 0xFF9E9E9EL
        }

        return MemoryUiItem(
            id              = id,
            content         = content,
            dateLabel       = dateLabel,
            isImportant     = isImportant,
            isCore          = isCore,
            aboutSelf       = aboutSelf,
            domain          = domain,
            importance      = importance,
            decayLabel      = decayLabel,
            domainLabel     = domainLabel,
            domainColorArgb = domainColorArgb,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  MemoryRepository 需要 update 方法，在此处扩展
// ─────────────────────────────────────────────────────────────

// update() 已在 MemoryRepository 中定义，此处无需重复。
// ─────────────────────────────────────────────────────────────
//  Phase 16 扩展（附加到 MemoryViewModel 伴随函数内不可行，
//  改为在此文件末尾补充顶层扩展方法，由 CharacterDetailScreen 使用）
// ─────────────────────────────────────────────────────────────
