package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import com.zaijian.zhoumuyun.util.ZLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.EventType
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.data.model.StatusType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.zaijian.zhoumuyun.domain.PresenceEngine
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
    /**
     * W14 修复：familyChainMap 异步加载完成标志。
     * loadFamilyChainMap() 在 viewModelScope.launch 中异步执行，初始为 false。
     * WorldScreen 点击角色时若此标志为 false（尚未加载完成），应忽略点击或
     * 展示短暂 loading，避免竞态误判"无后代"直接跳转聊天。
     */
    val isFamilyChainLoaded: Boolean = false,
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

    // S8-窗口01 收口：db/eventDao/jobResultDao 裸引用已移除。
    // eventDao.observeLatest → eventRepo.observeLatest（AppContainer 已有共享实例，
    // 方法签名完全一致）；jobResultDao.markRead → scheduleRepo.markResultRead
    // （ScheduleRepository 已有的方法，逐字段一致）。
    private val eventRepo    = AppContainer.instance.eventRepo
    private val scheduleRepo = AppContainer.instance.scheduleRepo
    // 阶段2 S-1 批次2收口：daughterRepo/identityDao 原先独立 new
    // （构造参数与容器完全一致），改引用 AppContainer 共享实例。
    private val daughterRepo = AppContainer.instance.daughterCharacterRepo
    private val identityDao  = AppContainer.instance.identityRepo

    private val _uiState = MutableStateFlow(WorldUiState())
    val uiState: StateFlow<WorldUiState> = _uiState.asStateFlow()

    // 记录每个角色最后一次 PRESENCE_CHANGED 事件的时间戳（内存缓存）。
    // P2-27 修复：lastEventTime 初始化时默认值从 0 改为 System.currentTimeMillis()，
    // 避免 App 启动后首轮衰减检查时误判全部角色都已超时 IDLE_THRESHOLD_MS（30 分钟），
    // 导致所有角色开局就显示"不知在做什么"。
    private val lastEventTime = mutableMapOf<Int, Long>().withDefault { System.currentTimeMillis() }

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
    //  v56→v57 公馆/书架头像独立化：过滤条件原先只看 avatarUrl（圆形）
    //  是否非空，导致「只上传了公馆或书架头像、从未碰过圆形头像」的
    //  角色永远不会进入 avatarMap，公馆/书架页面同步不到已上传的图——
    //  三处头像本次改造后完全独立，任一处非空都应该纳入同步范围。
    private fun observeAvatarOverrides() {
        viewModelScope.launch {
            identityDao.observeAll().collectLatest { entities ->
                val avatarMap = entities
                    .filter {
                        !it.avatarUrl.isNullOrEmpty() ||
                        !it.avatarUrlTall.isNullOrEmpty() ||
                        !it.avatarUrlShelf.isNullOrEmpty()
                    }
                    .associate { it.characterId to it }
                if (avatarMap.isNotEmpty()) {
                    _uiState.update { current ->
                        val updatedChars = current.characters.map { char ->
                            val entity = avatarMap[char.id] ?: return@map char
                            // v46 修复：原先只在 avatarUrl 变化时才同步，导致
                            // 「长按单独重调矩形取景」（avatarUrl 不变，只有
                            // avatarCropTall* 变化）这个新场景永远同步不到
                            // CharacterConfig，公馆/书架页面看不到用户刚调好
                            // 的取景范围。改为任一相关字段变化都触发同步。
                            // [聊天圆形头像取景修复] 同步条件和写回字段追加
                            // avatarCropCircle* 三项，跟 avatarCropTall* 一样处理——
                            // 否则用户在详情页单独重调圆形取景（avatarUrl/Tall* 都
                            // 不变）时，这次变化不会触发 changed=true，聊天页头像
                            // 就永远拿不到最新的圆形裁剪参数。
                            // v56→v57 公馆/书架头像独立化：追加 avatarUrlTall/
                            // avatarUrlShelf/avatarCropShelf* 共5项，公馆和书架
                            // 现在各自独立一套原图+裁剪参数，都要参与变化判断
                            // 和写回，否则用户单独上传/裁剪书架头像时同步不到。
                            val changed = (
                                entity.avatarUrl != char.avatarUrl ||
                                entity.avatarUrlTall != char.avatarUrlTall ||
                                entity.avatarUrlShelf != char.avatarUrlShelf ||
                                entity.avatarCropTallOffsetX != char.avatarCropTallOffsetX ||
                                entity.avatarCropTallOffsetY != char.avatarCropTallOffsetY ||
                                entity.avatarCropTallScale != char.avatarCropTallScale ||
                                entity.avatarCropCircleOffsetX != char.avatarCropCircleOffsetX ||
                                entity.avatarCropCircleOffsetY != char.avatarCropCircleOffsetY ||
                                entity.avatarCropCircleScale != char.avatarCropCircleScale ||
                                entity.avatarCropShelfOffsetX != char.avatarCropShelfOffsetX ||
                                entity.avatarCropShelfOffsetY != char.avatarCropShelfOffsetY ||
                                entity.avatarCropShelfScale != char.avatarCropShelfScale
                            )
                            if (changed) {
                                char.copy(
                                    avatarUrl               = entity.avatarUrl,
                                    avatarUrlTall           = entity.avatarUrlTall,
                                    avatarUrlShelf          = entity.avatarUrlShelf,
                                    avatarCropTallOffsetX   = entity.avatarCropTallOffsetX,
                                    avatarCropTallOffsetY   = entity.avatarCropTallOffsetY,
                                    avatarCropTallScale     = entity.avatarCropTallScale,
                                    avatarCropCircleOffsetX = entity.avatarCropCircleOffsetX,
                                    avatarCropCircleOffsetY = entity.avatarCropCircleOffsetY,
                                    avatarCropCircleScale   = entity.avatarCropCircleScale,
                                    avatarCropShelfOffsetX  = entity.avatarCropShelfOffsetX,
                                    avatarCropShelfOffsetY  = entity.avatarCropShelfOffsetY,
                                    avatarCropShelfScale    = entity.avatarCropShelfScale,
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
            eventRepo.observeLatest(50).collectLatest { events ->
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
                val lastActive = lastEventTime[char.id] ?: System.currentTimeMillis()
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
     * 监听 AppContainer.instance.presenceEngine.taskCompletionFlow，
     * 将最新到达的 TaskCompletionMessage 写入 uiState 展示浮层。
     *
     * 阶段2 S-2 遗留补项：此前直接访问 ZaijianApp.sharedPresenceEngine（可空），
     * 与 ChatViewModel/RoundtableViewModel/ChatScreen.kt 已完成的迁移不一致
     * ——那三处已改为不直接访问该全局单例，这里当时漏改。sharedPresenceEngine
     * 在 ZaijianApp.onCreate() 内被赋值为 AppContainer.instance.presenceEngine
     * 的同一实例，运行时行为不受影响，现改为直接引用容器实例（非空，
     * 与本 ViewModel 其余共享依赖的取用方式一致）。
     */
    private fun observeTaskCompletions() {
        viewModelScope.launch {
            AppContainer.instance.presenceEngine.taskCompletionFlow.collect { msg ->
                _uiState.update { it.copy(taskCompletionToast = msg) }
            }
        }
    }

    /** 用户点击「立即查看」后调用：标记已读 + 收起浮层 */
    fun markResultReadAndDismiss(jobResultId: String) {
        viewModelScope.launch {
            try {
                scheduleRepo.markResultRead(jobResultId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
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
                    val chainResult = daughterRepo.getFamilyChain(char.id)
                    map[char.id] = chainResult.entries.map { it.config.id }
                    // 问题38修复联动：这里只用于构建"母亲id -> 后代id列表"的映射，
                    // parseFailed 时 entries 仍是尽力收集到的部分结果，
                    // 沿用即可；此处不涉及 UI 直接展示，暂不需要额外提示。
                    if (chainResult.parseFailed) {
                        ZLog.w("PresenceViewModel", "家族链部分解析失败，characterId=${char.id}")
                    }
                }
                _uiState.update { it.copy(familyChainMap = map, isFamilyChainLoaded = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("PresenceViewModel", "loadFamilyChainMap failed: ${e.message}")
                // 加载失败也标记为完成，避免 WorldScreen 永远阻塞等待
                _uiState.update { it.copy(isFamilyChainLoaded = true) }
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
