package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatMessageEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatSessionEntity
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.privatechat.PrivateChatEngine
import com.zaijian.zhoumuyun.data.privatechat.enqueuePrivateChatSession
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatPairRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 私聊管理 ViewModel（方案_角色间私聊_v2-5 第七节）
 */
class PrivateChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.instance
    private val pairRepo = container.privateChatPairRepo
    private val messageRepo = container.privateChatMessageRepo
    private val sessionRepo = container.privateChatSessionRepo
    private val exporter = container.privateChatExporter
    private val daughterRepo = container.daughterCharacterRepo

    val allPairs: StateFlow<List<PrivateChatPairEntity>> =
        pairRepo.observeAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedPairMessages = MutableStateFlow<List<PrivateChatMessageEntity>>(emptyList())
    val selectedPairMessages: StateFlow<List<PrivateChatMessageEntity>> = _selectedPairMessages.asStateFlow()

    private val _selectedPairSessions = MutableStateFlow<List<PrivateChatSessionEntity>>(emptyList())
    val selectedPairSessions: StateFlow<List<PrivateChatSessionEntity>> = _selectedPairSessions.asStateFlow()

    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    private val _killSwitchOn = MutableStateFlow(PrivateChatEngine.isKillSwitchOn(application))
    val killSwitchOn: StateFlow<Boolean> = _killSwitchOn.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var detailJob: Job? = null

    fun loadPairDetail(pairId: String) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            launch {
                messageRepo.observeByPair(pairId).collect { messages ->
                    _selectedPairMessages.value = messages
                }
            }
            launch {
                sessionRepo.observeByPair(pairId).collect { sessions ->
                    _selectedPairSessions.value = sessions
                }
            }
        }
    }

    fun createPair(characterIdA: Int, characterIdB: Int) {
        if (characterIdA == characterIdB) {
            _toast.value = "不能选择同一个角色"
            return
        }
        val pairId = PrivateChatPairRepository.generatePairId(characterIdA, characterIdB)
        viewModelScope.launch {
            val existing = pairRepo.get(pairId)
            if (existing != null) {
                _toast.value = "该角色对已存在"
                return@launch
            }
            val now = System.currentTimeMillis()
            pairRepo.insert(PrivateChatPairEntity(
                pairId = pairId,
                characterIdA = minOf(characterIdA, characterIdB),
                characterIdB = maxOf(characterIdA, characterIdB),
                enabled = true,
                usedTodayResetAt = now,
            ))
            _toast.value = "已创建并开启私聊"
        }
    }

    fun toggleEnabled(pairId: String, enabled: Boolean) {
        viewModelScope.launch {
            pairRepo.updateEnabled(pairId, enabled)
        }
    }

    fun updateParams(pairId: String, maxTurns: Int, maxSessions: Int, cooldown: Int) {
        viewModelScope.launch {
            pairRepo.updateParams(pairId, maxTurns, maxSessions, cooldown)
            _toast.value = "参数已更新"
        }
    }

    fun triggerSession(pairId: String, initiatorId: Int) {
        viewModelScope.launch {
            val pair = pairRepo.get(pairId)
            if (pair == null) {
                _toast.value = "配对不存在"
                return@launch
            }
            if (!pair.enabled) {
                _toast.value = "该角色对私聊未开启"
                return@launch
            }
            if (PrivateChatEngine.isKillSwitchOn(getApplication())) {
                _toast.value = "全局私聊开关已关闭"
                return@launch
            }
            enqueuePrivateChatSession(getApplication(), pairId, initiatorId)
            _toast.value = "已发起私聊，请稍候"
        }
    }

    fun exportMarkdown(pairId: String) {
        viewModelScope.launch {
            try {
                val result = exporter.exportPairToMarkdown(pairId)
                _exportResult.value = result
                _toast.value = "导出成功"
            } catch (e: Exception) {
                _toast.value = "导出失败：${e.message}"
            }
        }
    }

    fun exportPlainText(pairId: String) {
        viewModelScope.launch {
            try {
                val result = exporter.exportPairToPlainText(pairId)
                _exportResult.value = result
                _toast.value = "导出成功"
            } catch (e: Exception) {
                _toast.value = "导出失败：${e.message}"
            }
        }
    }

    fun toggleKillSwitch(on: Boolean) {
        PrivateChatEngine.setKillSwitch(getApplication(), on)
        _killSwitchOn.value = on
        _toast.value = if (on) "已暂停所有私聊" else "已恢复私聊"
    }

    fun clearToast() { _toast.value = null }
    fun clearExportResult() { _exportResult.value = null }

    /**
     * 解析角色显示名（两层硬编码查找）
     */
    suspend fun resolveCharacterName(characterId: Int): String {
        DefaultCharacters.firstOrNull { it.id == characterId }?.let { return it.name }
        return try {
            daughterRepo.getCharacterConfig(characterId)?.name ?: "角色$characterId"
        } catch (e: Exception) {
            "角色$characterId"
        }
    }
}
