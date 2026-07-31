package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.agent.writeVaultText
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.util.ChineseTokenizer
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** v1.1：创建时间戳，供 coreMemories 按 createdAt 倒序分区展示 */
    val createdAt: Long = 0L,
    /**
     * C8#44 UI 闭环：对应 MemoryEntity.isNarrativeOnly——假扮身份识别期间
     * （speakerContext == NON_OWNER）产生的记忆。管理页有意展示这类记忆
     * （见 MemoryDao.observeAll 注释），但用户需要能分辨"这条是叙事记忆，
     * 不是角色与 owner 的正常互动记忆"，否则容易误以为角色记忆错乱/串号。
     */
    val isNarrativeOnly: Boolean = false,
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
    // ── 呈现层补充（v1.1）：叙事三字段 + 核心锚点 ──
    /** 关系叙事（narrativeMemory）—— 阶段日志格式，空表示尚未建立 */
    val narrativeMemory: String = "",
    /** 角色对用户的印象（userImpression）—— 当前状态快照 */
    val userImpression: String = "",
    /** 角色自我认知（soulNote）—— 当前状态快照 */
    val soulNote: String = "",
    /** 重大事件锚点（isCore=true 的 memories），按 createdAt 倒序，单独分区展示 */
    val coreMemories: List<MemoryUiItem> = emptyList(),
    /** 导出存档结果提示（成功路径/失败原因），null = 无操作 */
    val exportResult: String? = null,
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

    // v1.1 呈现层补充：复用 IdentityViewModel 同款取法（IdentityViewModel:95），
    // 拿 soulNote/narrativeMemory/userImpression 三个 blob 字段。不新建 Repository。
    private val identityRepo = AppContainer.instance.identityRepo

    // A9-5 修复：导出记忆存档时追加关系数值板块。
    // 复用 AppContainer 共享实例（同 repo/identityRepo 写法）。
    private val relationshipEngine = AppContainer.instance.relationshipEngine

    private val _characterId = MutableStateFlow(-1)
    private val _filter      = MutableStateFlow(MemoryFilter.ALL)
    private val _snackbar    = MutableStateFlow<String?>(null)
    private val _exportResult = MutableStateFlow<String?>(null)

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

    // v1.1：观察角色 identity，拿 soulNote/narrativeMemory/userImpression。
    // cid < 0 时发空 entity，避免 combine 卡在首帧 loading。
    @OptIn(ExperimentalCoroutinesApi::class)
    private val _identity: StateFlow<CharacterIdentityEntity?> = _characterId
        .flatMapLatest { cid ->
            if (cid < 0) flowOf(null)
            else identityRepo.observeById(cid)
        }
        .catch { emit(null) }
        .stateIn(
            scope            = viewModelScope,
            started          = SharingStarted.WhileSubscribed(5_000),
            initialValue     = null,
        )

    // P1-13-24 修复：原实现 _entities.map { _filter.value } 仅在 _entities 变化时触发，
    // _filter / _snackbar 变更不会重算 uiState，导致切换过滤器后 UI 不刷新。
    // 改为 combine 多源，任意一源变化均触发重算。
    // v1.1：再 combine _identity，使叙事三字段进入 uiState。
    val uiState: StateFlow<MemoryUiState> = combine(
        _entities,
        _identity,
        _filter,
        _snackbar,
        _exportResult,
    ) { entities, identity, filter, snackbar, exportResult ->
        val allItems = entities.map { it.toUiItem() }
        // v1.1：coreMemories 单独分区（isCore=true），从全量 items 里拆出，
        // 按 createdAt 倒序——锚点是"事件"性质，按发生时间倒序更符合直觉
        // （observeAll 返回序是 importance DESC, updatedAt DESC，不适合这里）。
        val coreItems = allItems
            .filter { it.isCore }
            .sortedByDescending { it.createdAt }
        // v1.1 修正：其他记忆分区排除 isCore 条目，避免锚点在两个分区重复出现
        // （coreItems 已单独展示在"重大事件锚点"区，下方明细列表只放非锚点记忆）
        val otherItems = allItems.filter { !it.isCore }
        MemoryUiState(
            items          = applyFilter(otherItems, filter),
            isLoading      = false,
            filter         = filter,
            snackbar       = snackbar,
            narrativeMemory = identity?.narrativeMemory ?: "",
            userImpression  = identity?.userImpression ?: "",
            soulNote        = identity?.soulNote ?: "",
            coreMemories    = coreItems,
            exportResult   = exportResult,
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
    //  v1.1：叙事字段编辑（记忆 Tab 内联编辑入口）
    //  复用 identityRepo.upsert* 既有方法，与人设 Tab 同一份数据源
    // ─────────────────────────────────────────────────────────

    fun updateNarrativeMemory(value: String) {
        val cid = _characterId.value
        if (cid < 0) return
        viewModelScope.launch {
            runCatching { identityRepo.upsertNarrativeMemory(cid, value) }
                .onSuccess { _snackbar.value = "关系叙事已保存" }
                .onFailure { _snackbar.value = "保存失败：${it.message?.take(60)}" }
        }
    }

    fun updateSoulNote(value: String) {
        val cid = _characterId.value
        if (cid < 0) return
        viewModelScope.launch {
            runCatching { identityRepo.upsertSoulNote(cid, value) }
                .onSuccess { _snackbar.value = "自我认知已保存" }
                .onFailure { _snackbar.value = "保存失败：${it.message?.take(60)}" }
        }
    }

    fun updateUserImpression(value: String) {
        val cid = _characterId.value
        if (cid < 0) return
        viewModelScope.launch {
            runCatching { identityRepo.upsertUserImpression(cid, value) }
                .onSuccess { _snackbar.value = "印象已保存" }
                .onFailure { _snackbar.value = "保存失败：${it.message?.take(60)}" }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  v1.1：导出记忆存档（UI 直接触发，不经过 LLM）
    //  数据组装是确定性字符串拼接，复用 VaultIo.writeVaultText 落盘
    // ─────────────────────────────────────────────────────────

    /**
     * 把叙事三字段 + 核心锚点 + 其他记忆拼成一份 Markdown，落盘到 VaultScope.PERSONAL。
     * 成功后 exportResult 给出文件名，失败给原因。不经过 LLM。
     */
    fun exportArchive(characterName: String) {
        val cid = _characterId.value
        if (cid < 0) {
            _exportResult.value = "角色未初始化，无法导出"
            return
        }
        viewModelScope.launch {
            runCatching {
                val state = uiState.value
                val now = System.currentTimeMillis()
                val ts = TimeFormatUtils.formatExportStamp(now)
                val displayTs = TimeFormatUtils.formatDateTimeMinute(now)

                // C8#44 UI 闭环：导出文件是用户会保存/分享出去的文本，标记口径
                // 需要和屏幕展示（MemoryRow/CoreAnchorsSection 的"叙事记忆"标签）
                // 保持一致，否则会出现"App 里能看出来、导出后看不出来"的不一致。
                val coreLines = if (state.coreMemories.isEmpty()) {
                    "（暂无）"
                } else {
                    state.coreMemories.joinToString("\n") {
                        "- ${it.dateLabel} ${it.content}" + if (it.isNarrativeOnly) "（叙事记忆）" else ""
                    }
                }

                // 其他记忆上限 50 条，避免文档过长（补充文档 §4.2）
                val otherLimit = 50
                val otherLines = if (state.items.isEmpty()) {
                    "（暂无）"
                } else {
                    state.items.take(otherLimit).joinToString("\n") {
                        "- [${it.domainLabel.ifEmpty { "其他" }}] ${it.dateLabel} ${it.content}" +
                            if (it.isNarrativeOnly) "（叙事记忆）" else ""
                    } + if (state.items.size > otherLimit) "\n…（共 ${state.items.size} 条，已截断至前 $otherLimit 条）" else ""
                }

                // A9-5 修复：追加关系数值板块。
                // owner 侧约定固定传字符串 "user"（见 ChatMessageOrchestrator.kt:921），
                // getOrCreate 若无记录会创建默认值（信任50/尊重50/好感50…），不会 NPE。
                val rel = relationshipEngine.getOrCreate("user", cid.toString())
                val stageLabel = when (runCatching {
                    com.zaijian.zhoumuyun.data.db.entity.RelationshipStage.valueOf(rel.stage)
                }.getOrDefault(com.zaijian.zhoumuyun.data.db.entity.RelationshipStage.STRANGER)) {
                    com.zaijian.zhoumuyun.data.db.entity.RelationshipStage.STRANGER  -> "陌生"
                    com.zaijian.zhoumuyun.data.db.entity.RelationshipStage.FAMILIAR  -> "熟悉"
                    com.zaijian.zhoumuyun.data.db.entity.RelationshipStage.TRUSTED   -> "信任"
                    com.zaijian.zhoumuyun.data.db.entity.RelationshipStage.IMPORTANT -> "重要"
                    com.zaijian.zhoumuyun.data.db.entity.RelationshipStage.CORE      -> "核心"
                }
                val relationshipLines = buildString {
                    appendLine("- 关系阶段：$stageLabel")
                    appendLine("- 信任：${rel.trust}/100")
                    appendLine("- 尊重：${rel.respect}/100")
                    appendLine("- 好感：${rel.affection}/100")
                    appendLine("- 好奇：${rel.curiosity}/100")
                    appendLine("- 依赖：${rel.dependence}/100")
                    appendLine("- 冲突：${rel.conflict}/100")
                    appendLine("- 压抑：${rel.suppression}/100")
                }

                val safeName = characterName.ifBlank { "角色" }
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val markdown = buildString {
                    appendLine("# $safeName 记忆存档")
                    appendLine("生成时间：$displayTs")
                    appendLine()
                    appendLine("## 关系叙事")
                    appendLine(state.narrativeMemory.ifBlank { "（尚未建立）" })
                    appendLine()
                    appendLine("## 她对你的印象")
                    appendLine(state.userImpression.ifBlank { "（尚未建立）" })
                    appendLine()
                    appendLine("## 她的自我认知")
                    appendLine(state.soulNote.ifBlank { "（尚未建立）" })
                    appendLine()
                    appendLine("## 关系数值")
                    appendLine(relationshipLines)
                    appendLine()
                    appendLine("## 重大事件锚点（${state.coreMemories.size} 条）")
                    appendLine(coreLines)
                    appendLine()
                    appendLine("## 其他记忆（最近 ${minOf(state.items.size, otherLimit)} 条，仅供参考）")
                    appendLine(otherLines)
                }

                val rawFileName = "${safeName}_记忆存档_${ts}.md"
                writeVaultText(
                    context     = getApplication(),
                    rawFileName = rawFileName,
                    content     = markdown,
                    mimeType    = "text/markdown",
                )
                rawFileName
            }.onSuccess { fileName ->
                _exportResult.value = "已导出：$fileName"
            }.onFailure { e ->
                _exportResult.value = "导出失败：${e.message?.take(80)}"
            }
        }
    }

    fun clearExportResult() {
        _exportResult.value = null
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
            val trimmedContent = content.trim()
            val entity = MemoryEntity(
                id             = java.util.UUID.randomUUID().toString(),
                characterId    = cid,
                domain         = domain.name,
                content        = trimmedContent,
                importance     = 3,
                // P1-05 修复：原实现硬编码为空字符串，从未提取关键词，导致手动
                // 新增的记忆在 syncL2Tags() 里因 tags 为空被直接判定"删标签"，
                // 永远不会写入 L2 标签索引，按关键词检索不到。改为复用
                // MemoryEngine/AgentCoreTools 同款的 ChineseTokenizer.tokenizeJoined()，
                // 保证三条写入路径（Agent 工具 / MemoryEngine / 手动新增）提取逻辑一致。
                keywords       = ChineseTokenizer.tokenizeJoined(trimmedContent),
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
     * domain / importance / isCore 保持不变，更新 content、updatedAt，
     * 并重新提取 keywords（P1-06 修复，避免关键词与新内容脱节）。
     */
    fun updateContent(memoryId: String, newContent: String) {
        if (newContent.isBlank()) return
        viewModelScope.launch {
            val entity = _entities.value.find { it.id == memoryId } ?: return@launch
            val trimmedContent = newContent.trim()
            val updated = entity.copy(
                content   = trimmedContent,
                // P1-06 修复：原实现只更新 content，keywords 沿用编辑前的旧值，
                // 导致关键词与新内容脱节（repo.update() 内部 syncL2Tags() 会按
                // 这份 keywords 同步 L2 标签，旧关键词继续生效但已不对应新内容，
                // 新内容里的实际关键词反而检索不到）。与 addMemory() 同款处理，
                // 编辑时也重新提取。
                keywords  = ChineseTokenizer.tokenizeJoined(trimmedContent),
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

    // C8#44 UI 闭环补做：observeAll() 有意不过滤 isNarrativeOnly（见
    // MemoryDao.observeAll 注释），用户能看到假扮场景产生的叙事记忆本身是对的；
    // 现在 toUiItem() 把 isNarrativeOnly 映射到 MemoryUiItem 展示字段，
    // 由 CharacterDetailMemory.kt 的 MemoryRow 渲染一个中性标签，
    // 使用户能分辨"这条是假扮场景产生的叙事记忆"，避免误判角色记忆串号。
    private fun MemoryEntity.toUiItem(): MemoryUiItem {
        val isImportant = importance >= 4 || isCore
        val aboutSelf   = domain == MemoryDomain.PERSONAL.name

        // 日期格式化
        val dateLabel = TimeFormatUtils.formatIsoDate(createdAt)

        // Phase 17：衰减状态标签
        // deleteStaleUnused 只清理 importance < 3 的记忆，importance >= 3 不会被删除，
        // 因此不应展示"到期/即将到期"等误导标签，统一标记为"长期"。
        val decayLabel: String? = when {
            isCore          -> null
            importance >= 3 -> "长期"
            else            -> "即将清理"
        }

        // Phase 30 方案三：维度标签 + 色条颜色
        // B4审查报告【序号3】修复：INFERENCE（角色隐性推测记忆）已在 PromptOrchestrator
        // 中被实际读取并注入 prompt，但此处两个 when 此前遗漏该分支，落入 else 导致
        // 空标签+灰色条，与其他域不一致。补齐独立标签与色值。
        val domainLabel = when (domain) {
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.WORK.name      -> "工作"
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.PERSONAL.name  -> "情感"
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.WORLD.name     -> "世界"
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.RULE.name      -> "规则"
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.INFERENCE.name -> "推测"
            else -> ""
        }
        val domainColorArgb = when (domain) {
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.WORK.name      -> 0xFF5B9BD5L  // 蓝（由 accentColor 在 UI 层覆盖）
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.PERSONAL.name  -> 0xFFC89AA3L  // 暖粉
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.WORLD.name     -> 0xFF9CC2AEL  // 绿
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.RULE.name      -> 0xFFB0A0C8L  // 淡紫
            com.zaijian.zhoumuyun.data.db.entity.MemoryDomain.INFERENCE.name -> 0xFFD4B896L  // 沙金，呼应"猜测"的不确定基调
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
            createdAt       = createdAt,
            isNarrativeOnly = isNarrativeOnly,
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
