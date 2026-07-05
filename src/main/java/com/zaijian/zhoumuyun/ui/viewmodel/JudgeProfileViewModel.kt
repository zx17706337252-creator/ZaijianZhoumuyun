package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity
import com.zaijian.zhoumuyun.data.db.entity.JudgeProfileEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * JudgeProfileViewModel — 裁判档案管理页状态管理（窗口 5A）
 *
 * 负责：
 *   - 按 characterId 加载该角色所有裁判档案列表
 *   - 选中某档案后，聚合展示：standardNotes + candidateCorrections + 相关竞赛轮次
 *   - anchorIntent 整段覆盖写（用户在\"编辑评判偏好\"Dialog 里操作）
 *   - 候选修正池：confirmCorrection 写入 standardNotes / declineCorrection 仅清除候选
 *
 * 档案由 CompetitionRoundManager 懒创建，ViewModel 不负责创建。
 * 候选修正池（candidateCorrectionsJson）存在 JudgeProfileEntity 里，
 * 不依赖 SystemSuggestionEntity（后者属于专长进化系统）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JudgeProfileViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_CHARACTER_ID = "judge_profile_character_id"
        private const val KEY_SELECTED_ID  = "judge_profile_selected_id"
    }

    private val db = AppDatabase.getInstance(application)

    // ── characterId（从路由参数或 SavedState 取）──────────────────────

    val characterId: Int = savedStateHandle.get<Int>(KEY_CHARACTER_ID) ?: -1

    // ── 该角色所有裁判档案 ─────────────────────────────────────────────

    val profiles: StateFlow<List<JudgeProfileEntity>> =
        if (characterId < 0) flowOf<List<JudgeProfileEntity>>(emptyList()).stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
        else db.judgeProfileDao().observeAllForCharacter(characterId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 选中档案 ID ────────────────────────────────────────────────────

    private val _selectedProfileId =
        MutableStateFlow<String?>(savedStateHandle.get<String>(KEY_SELECTED_ID))
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    fun selectProfile(id: String?) {
        savedStateHandle[KEY_SELECTED_ID] = id
        _selectedProfileId.value = id
    }

    // ── 详情聚合：选中档案 + 该角色为裁判的相关轮次 ─────────────────────

    data class JudgeProfileDetail(
        val profile: JudgeProfileEntity? = null,
        val recentRounds: List<CompetitionRoundEntity> = emptyList(),
        val isLoading: Boolean = true,
    )

    val detail: StateFlow<JudgeProfileDetail> =
        _selectedProfileId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(JudgeProfileDetail(isLoading = false))
                } else {
                    combine(
                        db.judgeProfileDao().observeById(id),
                        if (characterId >= 0)
                            db.competitionRoundDao().observeRoundsAsJudge(characterId)
                        else
                            flowOf(emptyList()),
                    ) { profile, allRounds ->
                        // 筛选出与当前档案 domain 一致的轮次（最多展示最近10条）
                        val filtered = allRounds
                            .filter { it.projectDomain == profile?.domain }
                            .take(10)
                        JudgeProfileDetail(
                            profile     = profile,
                            recentRounds = filtered,
                            isLoading   = false,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), JudgeProfileDetail())

    // ── Snackbar ────────────────────────────────────────────────────────

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()
    fun clearSnackbar() { _snackbarMessage.value = null }

    // ── Loading ────────────────────────────────────────────────────────

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── anchorIntent 整段覆盖写 ────────────────────────────────────────

    /**
     * 用户在\"编辑评判偏好\"Dialog 里确认后调用。
     * 写入 standardNotes（这里复用 standardNotes 字段存储用户文字偏好；
     * anchorIntent 字段在建档时由系统写入，后续更新直接覆盖 standardNotes）。
     */
    fun updateAnchorIntent(profileId: String, text: String) {
        if (text.isBlank()) {
            _snackbarMessage.value = "内容不能为空"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                db.judgeProfileDao().updateStandardNotes(profileId, text.trim())
                _snackbarMessage.value = "评判标准已更新"
            } catch (e: Exception) {
                _snackbarMessage.value = "保存失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── 候选修正池操作 ─────────────────────────────────────────────────

    /**
     * 确认一条候选修正：将其内容追加写入 standardNotes，然后从候选池移除。
     *
     * 候选修正池的数据结构复用 candidateCorrectionsJson（JSON 数组字符串），
     * 每条格式为 {"text":"...", "count":N}。
     * 这里采用简单策略：将被确认的 trait 追加到现有 standardNotes 末尾（换行分隔），
     * 再从候选 JSON 中删除对应条目。
     *
     * @param profileId 目标裁判档案 ID
     * @param trait     要写入 standardNotes 的修正内容
     * @param entryText 候选池 JSON 中该条目的 text 字段（用于精确匹配删除）
     */
    fun confirmCorrection(profileId: String, trait: String, entryText: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val profile = db.judgeProfileDao().getById(profileId) ?: return@launch
                // 1. 追加写入 standardNotes
                val newNotes = if (profile.standardNotes.isBlank()) trait.trim()
                else "${profile.standardNotes.trimEnd()}\n${trait.trim()}"
                db.judgeProfileDao().updateStandardNotes(profileId, newNotes)
                // 2. 从候选池移除该条目
                val updatedJson = removeCandidateEntry(profile.candidateCorrectionsJson, entryText)
                db.judgeProfileDao().updateCandidateCorrections(profileId, updatedJson)
                _snackbarMessage.value = "已写进评判标准"
            } catch (e: Exception) {
                _snackbarMessage.value = "操作失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 拒绝一条候选修正：仅从候选池移除，不写入 standardNotes。
     */
    fun declineCorrection(profileId: String, entryText: String) {
        viewModelScope.launch {
            try {
                val profile = db.judgeProfileDao().getById(profileId) ?: return@launch
                val updatedJson = removeCandidateEntry(profile.candidateCorrectionsJson, entryText)
                db.judgeProfileDao().updateCandidateCorrections(profileId, updatedJson)
                _snackbarMessage.value = "已忽略这条观察"
            } catch (_: Exception) { /* 静默失败，候选池会在下次评审时再次写入 */ }
        }
    }

    /**
     * 从 candidateCorrectionsJson 中移除 text 字段匹配的条目。
     * 格式与 candidateObservationsJson 一致：[{"trait":"...","occurrenceCount":N,...}, ...]
     * 采用 org.json.JSONArray 解析，与 SpecialtyProfileRepository 对齐。
     */
    private fun removeCandidateEntry(json: String, entryText: String): String {
        return try {
            val arr = org.json.JSONArray(json)
            val newArr = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val trait = obj.optString("trait", "")
                if (trait != entryText) newArr.put(obj)
            }
            newArr.toString()
        } catch (_: Exception) {
            json
        }
    }

    /** 简易解析：返回 (trait, occurrenceCount) 对列表，供Screen展示 */
    fun parseCandidateCorrectionsForDisplay(json: String): List<Pair<String, Int>> {
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.getString("trait") to obj.getInt("occurrenceCount")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
