package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import com.zaijian.zhoumuyun.util.ZLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.EventType
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.ZaijianApp
import com.zaijian.zhoumuyun.domain.TaskCompletionMessage

// ─────────────────────────────────────────────────────────────
//  WorldUiState
// ─────────────────────────────────────────────────────────────

data class WorldUiState(
    val characters: List<CharacterConfig> = DefaultCharacters,
    val presenceMap: Map<Int, PresenceState> = DefaultPresenceStates.associateBy { it.characterId },
    val previewCharacterId: Int? = null,
    val showOnboardingTooltip: Boolean = false,
    /** Phase 30 方案二：当前待展示的任务完成通知浮层，null = 不展示 */
    val taskCompletionToast: TaskCompletionMessage? = null,
    /**
     * 家族链后代映射：motherId → 后代 characterId 列表（不含母亲自身）。
     * 用于 WorldScreen / CharacterScreen 判断"有无后代"，决定点击后弹
     * FamilyPickerSheet 还是直接进对话。
     * 空列表 = 该角色无后代，直接进聊天。
     */
    val familyChainMap: Map<Int, List<Int>> = emptyMap(),
)

// ─────────────────────────────────────────────────────────────
//  PresenceViewModel（Phase 7 升级）
//
//  Presence 更新策略（双轨并行）：
//
//  轨道 A — Event 驱动（真实数据）：
//    observeLatest() 监听 world_events 表，
//    遇到 PRESENCE_CHANGED 事件立即更新对应角色的 presenceMap。
//    状态文案来自「刚聊过天」等语义化文本，而非随机池。
//
//  轨道 B — 时间衰减（兜底逻辑）：
//    若某角色超过 IDLE_THRESHOLD_MS（30 分钟）没有 PRESENCE_CHANGED 事件，
//    自动降级到 IDLE，表现为"不知在做什么"。
//    每 5 分钟轮询一次（Demo 模式 30 秒）。
//
//  这样公馆窗口的状态文案会真正反映"最近聊过天"，而不是随机文字。
// ─────────────────────────────────────────────────────────────

private val ACTIVE_STATUS_TEXTS = listOf(
    "刚聊过天",
    "刚回了消息",
    "还在想刚才说的话",
)
private val IDLE_STATUS_TEXTS = listOf(
    "不知在做什么",
    "最近有点安静",
    "没什么动静",
)
private const val IDLE_THRESHOLD_MS = 30 * 60 * 1000L  // 30 分钟
private const val DECAY_CHECK_INTERVAL_MS = 5 * 60 * 1000L   // 生产：5 分钟

class PresenceViewModel(application: Application) : AndroidViewModel(application) {

    private val db          = AppDatabase.getInstance(application)
    private val eventDao    = db.worldEventDao()
    private val jobResultDao = db.jobResultDao()
    private val daughterRepo = DaughterCharacterRepository(db.daughterCharacterDao())
    private val identityDao  = IdentityRepository(db.characterIdentityDao())

    private val _uiState = MutableStateFlow(WorldUiState())
    val uiState: StateFlow<WorldUiState> = _uiState.asStateFlow()

    // 记录每个角色最后一次 PRESENCE_CHANGED 事件的时间戳（内存缓存）
    private val lastEventTime = mutableMapOf<Int, Long>()

    private var hasSeenOnboarding = false

    init {
        observePresenceEvents()
        startDecayTimer()
        observeTaskCompletions()
        loadFamilyChainMap()
        observeAvatarOverrides()
    }

    // ── 头像同步：监听 character_identity 表的 avatarUrl 变更 ──
    //  当用户在角色详情页上传新头像后，DB 中 avatarUrl 更新，
    //  此 Flow 触发 → 覆盖 DefaultCharacters 中的硬编码 avatarUrl，
    //  使公馆/书架等页面头像自动同步。
    //  v46：一并同步 avatarCropTall* 三个字段——公馆拱形/书架椭圆现在
    //  靠这套参数动态取景，只同步 avatarUrl 不够，图会用默认居中裁剪
    //  （效果等同旧行为，不会崩，但用户调好的取景范围不生效）。
    private fun observeAvatarOverrides() {
        viewModelScope.launch {
            identityDao.observeAll().collectLatest { entities ->
                val avatarMap = entities
                    .filter { !it.avatarUrl.isNullOrEmpty() }
                    .associate { it.characterId to it }
                if (avatarMap.isNotEmpty()) {
                    _uiState.update { current ->
                        val updatedChars = current.characters.map { char ->
                            val entity = avatarMap[char.id]
                            // v46 修复：原先只在 avatarUrl 变化时才同步，导致
                            // 「长按单独重调矩形取景」（avatarUrl 不变，只有
                            // avatarCropTall* 变化）这个新场景永远同步不到
                            // CharacterConfig，公馆/书架页面看不到用户刚调好
                            // 的取景范围。改为任一相关字段变化都触发同步。
                            val changed = entity != null && (
                                entity.avatarUrl != char.avatarUrl ||
                                entity.avatarCropTallOffsetX != char.avatarCropTallOffsetX ||
                                entity.avatarCropTallOffsetY != char.avatarCropTallOffsetY ||
                                entity.avatarCropTallScale != char.avatarCropTallScale
                            )
                            if (changed && entity != null) {
                                char.copy(
                                    avatarUrl               = entity.avatarUrl,
                                    avatarCropTallOffsetX   = entity.avatarCropTallOffsetX,
                                    avatarCropTallOffsetY   = entity.avatarCropTallOffsetY,
                                    avatarCropTallScale     = entity.avatarCropTallScale,
                                )
                            } else char
                        }
                        current.copy(characters = updatedChars)
                    }
                }
            }
        }
    }

    // ── 轨道 A：监听 DB 事件流 ────────────────────────────────

    private fun observePresenceEvents() {
        viewModelScope.launch {
            // 观察最近 50 条事件，有新事件插入时重新 emit
            eventDao.observeLatest(50).collectLatest { events ->
                val presenceEvents = events.filter { it.type == EventType.PRESENCE_CHANGED.name }

                // 对每个有 PRESENCE_CHANGED 事件的角色，取最新一条更新 UI
                val eventUpdates = mutableMapOf<Int, PresenceState>()
                presenceEvents.forEach { event ->
                    val charId = event.actorId?.toIntOrNull() ?: return@forEach
                    // 只处理每个角色的最新一条（list 已按 createdAt DESC 排序）
                    if (charId in eventUpdates) return@forEach

                    val payload = runCatching { JSONObject(event.payload) }.getOrNull()
                    val statusTypeName = payload?.optString("statusType", StatusType.ACTIVE.name)
                        ?: StatusType.ACTIVE.name
                    val statusType = runCatching {
                        StatusType.valueOf(statusTypeName)
                    }.getOrDefault(StatusType.ACTIVE)

                    // Phase 20：读取 mood / energy / activity 字段
                    val moodName = payload?.optString("mood", "") ?: ""
                    val energy   = payload?.optInt("energy", -1) ?: -1
                    val activity = payload?.optString("activity", "") ?: ""

                    val statusText = when {
                        activity.isNotEmpty() -> activity  // 优先展示 PresenceEngine 生成的 activity
                        statusType == StatusType.ACTIVE  -> ACTIVE_STATUS_TEXTS.random()
                        statusType == StatusType.IDLE    -> IDLE_STATUS_TEXTS.random()
                        statusType == StatusType.FOCUSED -> "在专注做事"
                        statusType == StatusType.OFFLINE -> "不在线"
                        else -> IDLE_STATUS_TEXTS.random()
                    }

                    val moodLabel = when (moodName) {
                        "CALM"       -> "平静"
                        "FOCUSED"    -> "专注"
                        "CURIOUS"    -> "好奇"
                        "SATISFIED"  -> "满足"
                        "CONCERNED"  -> "担忧"
                        "EXCITED"    -> "兴奋"
                        "TIRED"      -> "疲惫"
                        "REFLECTIVE" -> "沉思"
                        else         -> ""
                    }

                    eventUpdates[charId] = PresenceState(
                        characterId   = charId,
                        statusText    = statusText,
                        statusType    = statusType,
                        lastUpdated   = event.createdAt,
                        sourceEventId = event.id,
                        moodLabel     = moodLabel,  // Phase 20 新增
                        energy        = energy,     // Phase 20 新增
                    )
                    // 更新内存时间戳缓存
                    lastEventTime[charId] = event.createdAt
                }

                if (eventUpdates.isNotEmpty()) {
                    _uiState.update { current ->
                        current.copy(presenceMap = current.presenceMap + eventUpdates)
                    }
                }
            }
        }
    }

    // ── 轨道 B：时间衰减 ──────────────────────────────────────

    private fun startDecayTimer() {
        viewModelScope.launch {
            while (true) {
                delay(DECAY_CHECK_INTERVAL_MS)
                applyDecay()
            }
        }
    }

    private fun applyDecay() {
        val now = System.currentTimeMillis()

        _uiState.update { current ->
            var changed = false
            val decayed = current.presenceMap.toMutableMap()
            current.characters.filter { it.isUnlocked }.forEach { char ->
                val lastActive = lastEventTime[char.id] ?: 0L
                val currentState = decayed[char.id] ?: return@forEach
                // 只对 ACTIVE 状态做衰减（FOCUSED / OFFLINE 不动）
                if (currentState.statusType == StatusType.ACTIVE &&
                    (now - lastActive) > IDLE_THRESHOLD_MS
                ) {
                    decayed[char.id] = currentState.copy(
                        statusText  = IDLE_STATUS_TEXTS.random(),
                        statusType  = StatusType.IDLE,
                        lastUpdated = now,
                    )
                    changed = true
                }
            }

            if (changed) current.copy(presenceMap = decayed) else current
        }
    }

    // ── Public actions ────────────────────────────────────────

    fun showPreview(characterId: Int) {
        _uiState.update { it.copy(previewCharacterId = characterId) }
    }

    fun dismissPreview() {
        _uiState.update { it.copy(previewCharacterId = null) }
    }

    fun markFirstInteraction() {
        if (hasSeenOnboarding) return
        viewModelScope.launch {
            delay(5_000L)
            if (!hasSeenOnboarding) {
                _uiState.update { it.copy(showOnboardingTooltip = true) }
            }
        }
    }

    fun dismissOnboarding() {
        hasSeenOnboarding = true
        _uiState.update { it.copy(showOnboardingTooltip = false) }
    }

    // ── Phase 30 方案二：任务完成浮层 ──────────────────────

    /**
     * 监听 ZaijianApp.sharedPresenceEngine.taskCompletionFlow，
     * 将最新到达的 TaskCompletionMessage 写入 uiState 展示浮层。
     */
    private fun observeTaskCompletions() {
        viewModelScope.launch {
            // 通过 ZaijianApp 公开的 presenceEngine 订阅
            ZaijianApp.sharedPresenceEngine?.taskCompletionFlow?.collect { msg ->
                _uiState.update { it.copy(taskCompletionToast = msg) }
            }
        }
    }

    /** 用户点击「立即查看」后调用：标记已读 + 收起浮层 */
    fun markResultReadAndDismiss(jobResultId: String) {
        viewModelScope.launch {
            try {
                jobResultDao.markRead(jobResultId)
            } catch (e: Exception) {
                ZLog.w("PresenceViewModel", "markRead failed: ${e.message}")
            }
        }
        _uiState.update { it.copy(taskCompletionToast = null) }
    }

    /** 用户点击「稍后查看」后调用：只收起浮层，不标记已读 */
    fun dismissTaskCompletionToast() {
        _uiState.update { it.copy(taskCompletionToast = null) }
    }

    // ── 家族链映射（书架 / 公馆点击路由判断用）────────────────

    /**
     * 在 init 时一次性加载所有一代母亲（id 1-9）的后代列表，
     * 组装 familyChainMap 写入 uiState。
     * 只需加载一次：后代出生后 App 会重启或角色列表会刷新。
     */
    private fun loadFamilyChainMap() {
        viewModelScope.launch {
            try {
                val map = mutableMapOf<Int, List<Int>>()
                DefaultCharacters.forEach { char ->
                    val descendants = daughterRepo.getFamilyChain(char.id)
                    map[char.id] = descendants.map { it.config.id }
                }
                _uiState.update { it.copy(familyChainMap = map) }
            } catch (e: Exception) {
                ZLog.w("PresenceViewModel", "loadFamilyChainMap failed: ${e.message}")
            }
        }
    }

    fun renameCharacter(id: Int, newName: String) {
        _uiState.update { current ->
            val updated = current.characters.map { c ->
                if (c.id == id) c.copy(name = newName) else c
            }
            current.copy(characters = updated)
        }
    }
}
