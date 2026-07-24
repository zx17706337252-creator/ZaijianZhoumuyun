package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.db.entity.MessageEntity
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 对话导出委托，从 ChatViewModel 中提取。
 *
 * 把当前角色的消息列表按时间顺序拼成文本，走 file_export 工具落地，
 * 产出的 metaJson 包进 system 消息插入数据库，FileExportCard 自动出现在消息流里。
 */
class ChatExportDelegate(
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val messageRepo: MessageRepository,
    private val viewModelScope: CoroutineScope,
    private val getCurrentCharacterId: () -> Int,
    private val reloadMessages: (Int) -> Unit,
) {
    /** 导出本次对话为 Markdown 文件。 */
    fun exportConversation() {
        val characterId = getCurrentCharacterId()
        if (characterId < 0) return
        val characterName = _uiState.value.character?.name ?: "对方"
        val messages = _uiState.value.messages
        if (messages.isEmpty()) {
            _uiState.update { it.copy(error = "当前没有可导出的对话内容") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val transcript = buildString {
                    messages.forEach { msg ->
                        if (msg.content.isBlank()) return@forEach
                        val speaker = if (msg.role == "user") "我" else characterName
                        appendLine("[$speaker] ${msg.content}")
                        appendLine()
                    }
                }.trimEnd()

                val exportTool = AgentToolRegistry.get("file_export")
                if (exportTool == null) {
                    ZLog.e("ChatExportDelegate", "导出对话失败：file_export 工具未注册")
                    _uiState.update { it.copy(error = "导出失败，请重试") }
                    return@launch
                }

                val fileName = "与${characterName}的对话记录"
                val result = exportTool.execute(
                    mapOf(
                        "name"    to fileName,
                        "content" to transcript,
                        "format"  to "md",
                    )
                )

                val exportedFileJson = extractExportedFileJson(result)
                if (!result.success || exportedFileJson == null) {
                    ZLog.e("ChatExportDelegate", "导出对话失败 characterId=$characterId error=${result.error}")
                    _uiState.update { it.copy(error = "导出失败，请重试") }
                    return@launch
                }

                messageRepo.insert(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = characterId,
                        role = "system",
                        content = "已导出本次对话",
                        createdAt = System.currentTimeMillis(),
                        exportedFileJson = exportedFileJson,
                    )
                )
                reloadMessages(characterId)
            } catch (e: Exception) {
                ZLog.e("ChatExportDelegate", "导出对话失败 characterId=$characterId", e)
                _uiState.update { it.copy(error = "导出失败，请重试") }
            }
        }
    }
}
