package com.zaijian.zhoumuyun.data.model

/**
 * 聊天模式（Phase 30 新增）
 *
 * 控制 Output Layer 的约束规则和工具调用权限：
 *
 * - [WORK]      工作模式：允许工具调用，结构化输出，回复长度不限
 * - [COMPANION] 陪伴模式：禁止工具注入，语气柔化，回复控制在 3-5 句
 *
 * 在 ChatViewModel 中通过 [setChatMode] 切换，
 * 切换后 PromptOrchestrator 动态替换 Output Layer（层位 8）。
 *
 * P1-08/P1-09/P2-08 拍板：原 NARRATIVE 旁白模式枚举值及其"自动激活/自动退回"
 * 核心逻辑从未实现（空壳功能），已彻底删除，不再保留枚举值。
 */
enum class ChatMode {
    WORK,
    COMPANION,
}
