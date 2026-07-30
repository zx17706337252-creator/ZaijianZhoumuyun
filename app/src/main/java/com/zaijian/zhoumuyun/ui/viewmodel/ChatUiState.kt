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
    // 文档发送方式：true（默认）= 角色输出的文件卡片合并进文字气泡；
    // false = 文件卡片各自独立成一张气泡/卡片（旧版效果）。
    // 由 FileDeliveryDataStore 持久化，ChatViewModel.init 订阅后覆盖此默认值。
    val attachFilesTogether: Boolean = true,

    // ── 角色忠诚锁定·会话级状态位（方案 v1.5 第 1.4 节，验收后修复）──────────────
    // 原实现为单个 Boolean，但 ChatViewModel 挂在 Activity 级 ViewModelStore 上
    // （Fix-ChatVmScope，见 ChatScreen.kt），_uiState 跨全部角色复用、不随
    // ChatSessionDelegate.init(characterId) 重建——单个 Boolean 会导致"角色A误判
    // 命中 → 切到角色B/C/...全部被污染为 NON_OWNER"，直到杀进程才解除。
    // 改为按 characterId 分片：一旦本角色会话中任一级检测命中身份异常，
    // 该角色对应位置 true 并保持（不因后续几句话"表现正常"而自动解除，避免被中途
    // 洗白），但不影响其他角色。不落库、不进长期记忆。
    val defenseModeByCharacter: Map<Int, Boolean> = emptyMap(),

)

enum class KnowledgeInjectMode { AUTO, MANUAL }

data class DistillResult(
    val triggered: Boolean,
    val newlyLockedCount: Int,
    val goalTitle: String,
    val progressDelta: Float,
)
