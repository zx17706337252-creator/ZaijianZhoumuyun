package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatMessageEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatSessionEntity
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.privatechat.PrivateChatEngine
import com.zaijian.zhoumuyun.data.privatechat.SessionTriggerOutcome
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

    companion object {
        // 与 PrivateChatEngine.generateReply() 里 getRecentBySession(limit = 20) 对齐——
        // 超过 20 的部分喂给 LLM 的历史会被截断，允许用户设置比这更大的轮数没有意义，
        // 反而会导致长会话里角色"失忆"（忘记更早聊过什么）。
        const val MAX_TURNS_UPPER_BOUND = 20
        // 至少 2 轮：开场白 + 至少一次回应，才算"发生过一次真实交流"——
        // 与 PrivateChatEngine.runSession() 里"turnIndex >= 2 才生成记忆"的判断
        // 使用同一门槛，避免出现"只有开场白、对方从未回应"的空会话。
        const val MIN_TURNS_LOWER_BOUND = 2
    }

    fun updateParams(pairId: String, maxTurns: Int, maxSessions: Int, cooldown: Int) {
        viewModelScope.launch {
            val clampedTurns = maxTurns.coerceIn(MIN_TURNS_LOWER_BOUND, MAX_TURNS_UPPER_BOUND)
            val clampedSessions = maxSessions.coerceAtLeast(1)
            val clampedCooldown = cooldown.coerceAtLeast(0)
            pairRepo.updateParams(pairId, clampedTurns, clampedSessions, clampedCooldown)
            _toast.value = if (clampedTurns != maxTurns || clampedSessions != maxSessions || clampedCooldown != cooldown) {
                "参数已更新（每轮对话数已限制在 $MIN_TURNS_LOWER_BOUND-$MAX_TURNS_UPPER_BOUND 之间，其余数值已按最小有效值调整）"
            } else {
                "参数已更新"
            }
        }
    }

    fun triggerSession(pairId: String, initiatorId: Int, directive: String? = null) {
        viewModelScope.launch {
            val pair = pairRepo.get(pairId)
            if (pair == null) {
                _toast.value = "配对不存在"
                return@launch
            }
            // v2.7 统一：此前这里遇到未开启会拒绝并提示"该角色对私聊未开启"，
            // 但 PrivateChatSendTool（角色在对话里主动发起私聊）遇到同样情况是
            // 自动打开——同一件事两个入口行为不一致。按用户确认的方向统一为
            // "自动开启"：不再拦截，直接打开开关后继续往下走，跟工具入口对齐。
            // 自动开启属于本入口的 UI 行为（管理面板点按钮＝明确开启意图），
            // 不属于 checkCanStart 的通用校验规则，因此仍在委托给引擎前单独处理。
            if (!pair.enabled) {
                pairRepo.updateEnabled(pairId, true)
            }
            // 实时化重构：日上限/冷却/角色下线/全局开关/配对存在/发起者合法性，
            // 这五项判断不再在这里手动复刻一遍——直接委托给
            // PrivateChatEngine.triggerSession()，与 PrivateChatSendTool
            // （ChatScreen 工具调用入口）共用同一份 checkCanStart 实现，
            // 不再各写一份规则、事后靠人工核对两处是否同步。
            when (val outcome = AppContainer.instance.privateChatEngine.triggerSession(
                pairId = pairId,
                initiatorCharacterId = initiatorId,
            )) {
                is SessionTriggerOutcome.Started -> {
                    enqueuePrivateChatSession(getApplication(), pairId, initiatorId, directive)
                    _toast.value = "已发起私聊，请稍候"
                }
                is SessionTriggerOutcome.Skipped -> {
                    _toast.value = outcome.reason
                }
            }
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

    // C8 #45：角色自主下线后，owner 手动恢复为 ACTIVE（PrivateChatEngine.kt:142-145
    // 的静默跳过判定依赖这个字段，此前无 UI/ViewModel 入口能改回去，pair 永久卡死）
    fun resetDisconnect(pairId: String) {
        viewModelScope.launch {
            pairRepo.resetCharacterDisconnectState(pairId)
            _toast.value = "已恢复，可重新发起私聊"
        }
    }

    fun toggleKillSwitch(on: Boolean) {
        PrivateChatEngine.setKillSwitch(getApplication(), on)
        _killSwitchOn.value = on
        _toast.value = if (on) "已暂停所有私聊" else "已恢复私聊"
    }

    // A10-5 修复：删除私聊配对（含级联删除消息和会话记录）
    // 三张表无 ForeignKey/cascade 约束，Room 不会自动级联删除，
    // 需在同一事务内手动删除三张表的记录，避免孤儿数据残留。
    fun deletePair(pairId: String) {
        viewModelScope.launch {
            val db = AppDatabase.getInstance(getApplication())
            db.withTransaction {
                db.privateChatMessageDao().deleteByPairId(pairId)
                db.privateChatSessionDao().deleteByPairId(pairId)
                db.privateChatPairDao().deleteByPairId(pairId)
            }
            _toast.value = "配对已删除"
        }
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
