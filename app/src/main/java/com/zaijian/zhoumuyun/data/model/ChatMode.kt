package com.zaijian.zhoumuyun.data.model

/**
 * 聊天模式（Phase 30 新增）
 *
 * 控制 Output Layer 的约束规则和工具调用权限：
 *
 * - [WORK]      工作模式：允许工具调用，结构化输出，回复长度不限
 * - [COMPANION] 陪伴模式：禁止工具注入，语气柔化，回复控制在 3-5 句
 * - [NARRATIVE] 旁白模式（Phase 5 zaijian）：COMPANION 的子模式，
 *               由用户发送「[旁白：…]」自动激活，角色以行为/内心独白回应场景描述；
 *               单轮结束后自动退回 COMPANION 模式。
 *
 * 在 ChatViewModel 中通过 [setChatMode] 切换，
 * 切换后 PromptOrchestrator 动态替换 Output Layer（层位 8）。
 */
enum class ChatMode {
    WORK,
    COMPANION,
    NARRATIVE,  // Phase 5（zaijian）：旁白模式，单轮激活，回复后自动退回 COMPANION
}
