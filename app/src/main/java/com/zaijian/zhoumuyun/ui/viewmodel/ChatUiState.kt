package com.zaijian.zhoumuyun.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.zaijian.zhoumuyun.data.model.ChatMode
import com.zaijian.zhoumuyun.domain.MoodType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class ChatUiState(
    val messages: ImmutableList<ChatMessage> = persistentListOf(),
    val character: com.zaijian.zhoumuyun.data.model.CharacterConfig? = null,
    val isTyping: Boolean = false,
    // 代码清洁：streamingContent 已从 uiState 中移除，改用独立 StateFlow 暴露
    val streamingHint: String? = null,
    val error: String? = null,
    val isApiKeyMissing: Boolean = false,
    val chatMode: ChatMode = ChatMode.COMPANION,
    val pendingEvaluationSessionId: String? = null,
    val pendingEvaluationReport: String? = null,
    val pendingAgentScore: Float? = null,
    val pendingDistillResult: DistillResult? = null,
    val knowledgeInjectMode: KnowledgeInjectMode = KnowledgeInjectMode.AUTO,
    val activeProjectId: String? = null,
    val activeProjects: ImmutableList<com.zaijian.zhoumuyun.data.db.entity.ProjectEntity> = persistentListOf(),
    val manualKnowledgeTriggerPending: Boolean = false,  // MANUAL 模式下：用户触发一次性注入
    // 1.1 受孕窗口同意对话框
    val fertileWindowConsentDialogText: String? = null,   // 非空时显示对话框
    val fertileWindowCharacterName: String = "",
    // 问题14修复：弹窗展示时捕获的角色ID快照（capturedCharId），而非实时的
    // currentCharacterId——弹窗展示期间用户若切换角色，onFertileWindowDialogResult()
    // 必须仍然作用在弹窗真正对应的角色上，不能被切换后的 currentCharacterId 顶替。
    val fertileWindowCharacterId: Int = -1,
    // D4 女儿生成失败提示（非空时 UI 弹 Snackbar）
    val pendingDaughterGenerationError: String? = null,
    // 主动消息前台实时呈现（非空时 UI 弹 Snackbar，含角色名 + 消息内容）
    val pendingProactiveMessage: String? = null,
    // UI M3 修复：角色当前心情，由 ViewModel 通过 StateFlow 推送，
    // ChatScreen 读 uiState.currentMood，不再直接访问全局单例 ZaijianApp.sharedPresenceEngine。
    val currentMood: MoodType? = null,
    // 聊天背景图：用户为当前角色设置的背景图 URI（null = 使用默认渐变背景）
    val backgroundImageUri: String? = null,
    // v55 修复：背景图取景偏移/缩放（来自 AvatarCropDialog 拖拽/缩放结果）。
    // scale=1f/offset=0f 时等价于旧版"直接 Crop 居中铺满"的行为。
    val backgroundOffsetX: Float = 0f,
    val backgroundOffsetY: Float = 0f,
    val backgroundScale: Float = 1f,
    // 待裁剪的背景图 URI：用户刚从相册选完图、裁剪弹窗还未确认时的中间态，
    // 非空时 UI 显示 AvatarCropDialog(shape = FULL_SCREEN)
    val pendingBackgroundCropUri: String? = null,
)

enum class KnowledgeInjectMode { AUTO, MANUAL }

data class DistillResult(
    val triggered: Boolean,
    val newlyLockedCount: Int,
    val goalTitle: String,
    val progressDelta: Float,
)
