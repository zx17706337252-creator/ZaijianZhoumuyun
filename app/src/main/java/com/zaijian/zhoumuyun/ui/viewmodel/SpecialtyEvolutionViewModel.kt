package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.agent.CandidatePromotionChecker
import com.zaijian.zhoumuyun.data.agent.IdentityPromotionEvaluator
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.EvolutionPlanEntity
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordEntity
import com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity
import com.zaijian.zhoumuyun.data.db.entity.StageDigestEntity
import com.zaijian.zhoumuyun.data.db.entity.SystemSuggestionEntity
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionEngine
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.SpecialtyProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * SpecialtyEvolutionViewModel — P6 专长进化系统 · 专长档案页状态管理
 *
 * 与 LearningGoalViewModel 同样的整体结构（AndroidViewModel + SavedStateHandle
 * 恢复当前角色 ID + flatMapLatest 响应角色切换），职责范围：
 *   - 专长档案列表（一个角色可以同时拥有多个专长方向）
 *   - 选中某个专长后，聚合展示：进化方案历史、风格说明书+候选观察池、
 *     修炼历程（PracticeRecord + StageDigest 按时间穿插）
 *   - 待处理建议（候选转正确认 / 晋升请求 / AI自我提案）的查询与操作入口
 *
 * 不在这一层做的事：LLM 调用的具体 Prompt 逻辑在 SpecialtyEvolutionEngine，
 * 数据读写在 SpecialtyProfileRepository，本类只负责把它们组装成 UI 能直接
 * 渲染的状态、以及把用户操作转发给对应的处理函数。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpecialtyEvolutionViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_CHARACTER_ID = "specialty_evolution_current_character_id"
    }

    private val db = AppDatabase.getInstance(application)
    private val repo = SpecialtyProfileRepository(
        db = db,
        specialtyProfileDao = db.specialtyProfileDao(),
        evolutionPlanDao = db.evolutionPlanDao(),
        practiceRecordDao = db.practiceRecordDao(),
        practiceRecordArchiveDao = db.practiceRecordArchiveDao(),
        stageDigestDao = db.stageDigestDao(),
        systemSuggestionDao = db.systemSuggestionDao(),
    )

    // ── 角色与专长列表 ───────────────────────────────────────────

    private val restoredCharacterId: Int = savedStateHandle.get<Int>(KEY_CHARACTER_ID) ?: -1
    private val _characterIdFlow = MutableStateFlow(restoredCharacterId)
    private var currentCharacterId: Int = restoredCharacterId
    private var hasRunInit = false

    val profiles: StateFlow<List<SpecialtyProfileEntity>> =
        _characterIdFlow
            .flatMapLatest { cid ->
                if (cid < 0) flowOf(emptyList()) else repo.observeProfilesForCharacter(cid)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun init(characterId: Int) {
        if (hasRunInit && currentCharacterId == characterId) return
        hasRunInit = true
        currentCharacterId = characterId
        savedStateHandle[KEY_CHARACTER_ID] = characterId
        _characterIdFlow.value = characterId
    }

    // ── 当前选中的专长档案（专长档案页可能同时管理多个方向，
    //    选中一个后下方展示详情区块） ─────────────────────────

    private val _selectedProfileId = MutableStateFlow<String?>(null)
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    fun selectProfile(profileId: String?) {
        _selectedProfileId.value = profileId
    }

    /** 选中专长的完整详情聚合（方案+历史+修炼记录+阶段摘要+待处理建议，单一 Flow 合并） */
    data class ProfileDetail(
        val profile: SpecialtyProfileEntity? = null,
        val activePlan: EvolutionPlanEntity? = null,
        val planHistory: List<EvolutionPlanEntity> = emptyList(),
        val practiceRecords: List<PracticeRecordEntity> = emptyList(),
        val stageDigests: List<StageDigestEntity> = emptyList(),
        val pendingSuggestions: List<SystemSuggestionEntity> = emptyList(),
        val isLoading: Boolean = true,
    )

    val profileDetail: StateFlow<ProfileDetail> =
        _selectedProfileId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(ProfileDetail(isLoading = false))
                } else {
                    combine(
                        repo.observeProfile(id),
                        repo.observePlanHistory(id),
                        repo.observePracticeRecords(id),
                        repo.observeStageDigests(id),
                        repo.observePendingSuggestions(id),
                    ) { profile, history, records, digests, suggestions ->
                        ProfileDetail(
                            profile = profile,
                            activePlan = history.find { it.isActive },
                            planHistory = history,
                            practiceRecords = records,
                            stageDigests = digests,
                            pendingSuggestions = suggestions,
                            isLoading = false,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfileDetail())

    // ── Snackbar ─────────────────────────────────────────────────

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()
    fun clearSnackbar() { _snackbarMessage.value = null }

    // ── 创建新专长方向 ─────────────────────────────────────────────

    /**
     * 用户布置一个新的专长方向。
     * 这是整套系统的起点（方案第2.2节）：创建 SpecialtyProfile 后，
     * 立刻调用 LLM 生成第一版进化方案（version=1, revisionReason="USER_INITIATED"），
     * 不会出现"创建了档案但没有方案、DailyPracticeWorker 永远跳过"的空档期。
     */
    fun createSpecialty(domain: String, anchorIntent: String) {
        val cid = currentCharacterId.takeIf { it >= 0 } ?: return
        if (domain.isBlank() || anchorIntent.isBlank()) {
            _snackbarMessage.value = "方向和说明都不能为空"
            return
        }

        viewModelScope.launch {
            val profile = repo.createProfile(cid, domain.trim(), anchorIntent.trim())

            val provider = ProviderManager.instance.activeProvider
            if (provider == null) {
                _snackbarMessage.value = "已创建「$domain」方向，但尚未配置 API，需要先在设置里配置才能开始自动修炼"
                return@launch
            }

            val planContent = generateInitialPlan(provider, domain.trim(), anchorIntent.trim())
            repo.createNewPlanVersion(
                characterId = cid,
                specialtyId = profile.id,
                content = planContent,
                revisionReason = "USER_INITIATED",
            )
            _snackbarMessage.value = "已创建「$domain」方向，今天的修炼会在设定的时间自动开始"
        }
    }

    private suspend fun generateInitialPlan(
        provider: com.zaijian.zhoumuyun.data.provider.LLMProvider,
        domain: String,
        anchorIntent: String,
    ): String {
        val systemPrompt = """
            用户希望角色在「$domain」方向上养成专长，原话是："$anchorIntent"。
            请你（角色本人）为自己规划一份分阶段的自我进化方案，叙述体，不要写成
            条目清单。方案应该体现你打算怎么从摸索到逐渐稳定，不需要写得很长，
            重点是有阶段感、有具体打算尝试的方向。

            直接输出方案正文，不需要任何JSON包裹或额外说明。
        """.trimIndent()

        return try {
            provider.chatSyncWithRetry(
                messages = listOf(LLMMessage("user", "请开始规划。")),
                systemPrompt = systemPrompt,
                config = LLMConfig(model = "", maxTokens = 600, temperature = 0.7f, stream = false),
            ).trim().ifBlank { "先从基础的尝试开始，逐步摸索适合自己的方向。" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "先从基础的尝试开始，逐步摸索适合自己的方向。"
        }
    }

    // ── 启停 / 删除 ─────────────────────────────────────────────

    fun setActive(profileId: String, active: Boolean) {
        viewModelScope.launch {
            repo.setActive(profileId, active)
            _snackbarMessage.value = if (active) "已重新启用" else "已暂停（每日修炼会跳过这个方向）"
        }
    }

    fun deleteSpecialty(profileId: String, domain: String) {
        viewModelScope.launch {
            repo.deleteProfile(profileId)
            if (_selectedProfileId.value == profileId) _selectedProfileId.value = null
            _snackbarMessage.value = "已删除「$domain」"
        }
    }

    // ── 里程碑标记（用户在修炼历程列表里手动操作） ───────────────

    fun markMilestone(recordId: String) {
        viewModelScope.launch {
            repo.markMilestone(recordId)
            _snackbarMessage.value = "已标记为里程碑，不会被自动蒸馏降级"
        }
    }

    // ── 待处理建议：候选转正确认 ─────────────────────────────────

    fun confirmCandidate(profileId: String, suggestion: SystemSuggestionEntity) {
        val trait = suggestion.content.removePrefix("CANDIDATE_CONFIRM::")
        viewModelScope.launch {
            CandidatePromotionChecker.confirmCandidate(db, repo, profileId, trait, suggestion.id)
            _snackbarMessage.value = "已写入风格说明书"
        }
    }

    fun declineCandidate(profileId: String, suggestion: SystemSuggestionEntity) {
        val trait = suggestion.content.removePrefix("CANDIDATE_CONFIRM::")
        viewModelScope.launch {
            CandidatePromotionChecker.declineCandidate(db, repo, profileId, trait, suggestion.id)
            _snackbarMessage.value = "已忽略这条观察"
        }
    }

    // ── 待处理建议：晋升 Identity Layer 请求 ─────────────────────

    fun confirmPromotion(profileId: String, suggestion: SystemSuggestionEntity) {
        val trait = suggestion.content.removePrefix("PROMOTION_REQUEST::")
        viewModelScope.launch {
            val provider = ProviderManager.instance.activeProvider
            if (provider == null) {
                _snackbarMessage.value = "需要先配置 API 才能执行晋升整合"
                return@launch
            }
            val engine = SpecialtyEvolutionEngine(provider)
            IdentityPromotionEvaluator.executePromotion(db, engine, profileId, suggestion.id, trait)
            _snackbarMessage.value = "已写入她的人设核心，这个特点现在是她本来的样子了"
        }
    }

    fun declinePromotion(suggestion: SystemSuggestionEntity) {
        viewModelScope.launch {
            IdentityPromotionEvaluator.declinePromotion(db, suggestion.id)
            _snackbarMessage.value = "暂不晋升，下次符合条件时还会再问你"
        }
    }

    // ── 待处理建议：AI 自我提案（仅展示+标记状态，不涉及参数自动应用） ──

    fun adoptSuggestion(suggestionId: String) {
        viewModelScope.launch {
            repo.adoptSuggestion(suggestionId)
            _snackbarMessage.value = "已标记为采纳（具体参数调整需要手动修改配置）"
        }
    }

    fun ignoreSuggestion(suggestionId: String) {
        viewModelScope.launch {
            repo.ignoreSuggestion(suggestionId)
        }
    }

    // ── 查看已蒸馏记录的归档原文（点击历史记录时调用） ───────────

    suspend fun getArchivedContent(recordId: String): String? = repo.getArchivedFullContent(recordId)
}
