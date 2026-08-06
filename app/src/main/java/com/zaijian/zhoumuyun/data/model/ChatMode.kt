package com.zaijian.zhoumuyun.data.model

/**
 * 聊天模式（Phase 30 新增）
 *
 * 控制 Output Layer 的约束规则和工具调用权限：
 *
 * - [WORK]      工作模式：允许工具调用，结构化输出，回复长度不限
 * - [COMPANION] 陪伴模式：语气柔化，回复控制在 3-5 句；默认不主动汇报/不主动用工具，
 *               但这是 prompt 层面的软引导，不是硬禁用——用户明确提出具体请求
 *               （如"发给我""提醒我"）时角色仍应正常调用工具，见
 *               [com.zaijian.zhoumuyun.data.prompt.OutputPromptBuilder] 中的实际约束文案。
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
