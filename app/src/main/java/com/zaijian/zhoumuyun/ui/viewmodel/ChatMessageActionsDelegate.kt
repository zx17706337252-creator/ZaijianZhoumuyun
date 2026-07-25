package com.zaijian.zhoumuyun.ui.viewmodel

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
 * 消息操作委托，从 ChatViewModel 中提取。
 *
 * 封装文件导入通知、消息清空、主动消息清除等操作。
 */
class ChatMessageActionsDelegate(
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val messageRepo: MessageRepository,
    private val viewModelScope: CoroutineScope,
    private val getCurrentCharacterId: () -> Int,
    private val reloadMessages: (Int) -> Unit,
) {
    /**
     * 用户上传文件后插入 system 消息通知 AI。
     * ToolCallInterceptor 会扫描消息历史里的"用户导入了一个文件"通知，
     * 自动执行 file_read 工具并把结果注入对话历史——程序层面锁死。
     *
     * role 固定 "system"、content 固定"用户导入了一个文件：X（路径：Y）"这个格式，
     * 两者都不能改：ChatMessageOrchestrator 组装 LLM 上下文时按这个 role 识别、
     * ToolCallInterceptor 的 file_read 强制锁死机制按这个 content 格式正则提取路径，
     * 改动任何一处都会让已经修过的两个 bug（file_read 锁死失效、文件导入盲区）复发。
     *
     * Fix-FileImportCard：新增 mimeType/sizeBytes 参数，写入 exportedFilesJson——
     * 复用角色发文件已有的 ExportedFile 结构，UI 层 MessageBubble 据此在用户
     * 气泡侧渲染 FileExportCard（图标+文件名+大小+打开预览），不再是一行纯文本
     * 系统提示。只加字段、不改 role/content，LLM 侧管线完全不受影响。
     */
    fun notifyFileImported(fileName: String, absolutePath: String, mimeType: String, sizeBytes: Long) {
        val characterId = getCurrentCharacterId()
        if (characterId < 0) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val exportedFilesJson = org.json.JSONArray().put(
                    org.json.JSONObject().apply {
                        put("fileName", fileName)
                        put("mimeType", mimeType)
                        put("sizeBytes", sizeBytes)
                        put("absolutePath", absolutePath)
                    }
                ).toString()
                messageRepo.insert(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = characterId,
                        role = "system",
                        content = "用户导入了一个文件：$fileName（路径：$absolutePath）",
                        createdAt = System.currentTimeMillis(),
                        exportedFilesJson = exportedFilesJson,
                    )
                )
                reloadMessages(characterId)
            } catch (e: Exception) {
                ZLog.e("ChatMessageActionsDelegate", "文件导入失败 fileName=$fileName", e)
                _uiState.update { it.copy(error = "文件导入失败，请重试") }
            }
        }
    }

    /** 清空当前角色的全部消息。 */
    fun clearMessages() {
        val characterId = getCurrentCharacterId()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                messageRepo.deleteByCharacter(characterId)
                reloadMessages(characterId)
            } catch (e: Exception) {
                ZLog.e("ChatMessageActionsDelegate", "清空消息失败", e)
                _uiState.update { it.copy(error = "清空消息失败，请重试") }
            }
        }
    }

    /** 清除主动消息提示，并刷新消息列表让气泡即时出现。 */
    fun clearProactiveMessage() {
        _uiState.update { it.copy(pendingProactiveMessage = null) }
        reloadMessages(getCurrentCharacterId())
    }
}
