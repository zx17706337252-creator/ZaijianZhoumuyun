package com.zaijian.zhoumuyun.data.model

// ═══════════════════════════════════════════════════════════════
//  ContentBlock —— 内容与渲染分离机制（窗口3 第17号模块核心交付物）
//
//  设计依据：窗口3交付报告 第六节
//
//  两种内容层级：
//    层1 · 纯文字对话内容（paragraph 块内部的 TextSegment 行内语义标记）
//    层2 · 非纯文字产出（document/image/table_file/link/file 等独立卡片块）
//
//  与 ChatTagParser 的关系：
//    [mood:xxx]      → 剥离不显示（ChatTagParser 处理，不在 ContentBlock 体系内）
//    [thinking:xxx]  → 大段推理，独立折叠卡（Thinking 块）
//    [action:...]    → 行内动作标记，斜体浅色展示（TextSegment）
//    [thought:...]   → 行内心理活动，引号+底纹展示（TextSegment）
// ═══════════════════════════════════════════════════════════════

/**
 * 段落内行内语义标记类型（窗口3报告 6.4 节）
 */
enum class TextSegmentType {
    /** 对话：默认类型，正常字重、正常颜色 */
    DIALOGUE,

    /** 动作：[action:...] 标记，斜体、颜色降低透明度 */
    ACTION,

    /** 心理活动：[thought:...] 标记，引号包裹+浅色底纹，行内展示不折叠 */
    THOUGHT,
}

/**
 * 段落内的文本片段（仅 Paragraph 块内部使用）
 *
 * @param text       文字内容
 * @param semanticType 语义类型：对话/动作/心理
 */
data class TextSegment(
    val text: String,
    val semanticType: TextSegmentType = TextSegmentType.DIALOGUE,
)

/**
 * 通用块元数据（窗口3报告 6.5 节）
 *
 * 供窗口5信息密度专项直接使用，不需要另外新增独立于块结构之外的密度控制机制。
 *
 * @param collapsible         是否可折叠
 * @param collapseThreshold   默认折叠阈值（如代码块超过N行自动折叠）
 */
data class ContentBlockMetadata(
    val collapsible: Boolean? = null,
    val collapseThreshold: Int? = null,
)

/**
 * ContentBlock 顶层结构（窗口3报告 6.5 节）
 *
 * 使用 sealed class 实现：每种 type 对应一个 data class 子类型，
 * 字段结构按 6.3/6.4 节表格定义。
 *
 * 基础块类型（本轮覆盖）：Heading / Paragraph / ListBlock / Code / Table / Quote
 * 层2 非文字产出块：Document / Image / TableFile / Link / FileBlock
 * Agent 过程类块（窗口7定稿）：ToolCall / Thinking / MemoryUpdate / WorkflowStep / SkillActivity
 */
sealed class ContentBlock {

    /** 标题（H1-H6 降级为统一层级感） */
    data class Heading(
        val text: String,
        val level: Int,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 正文段落，层1三种语义标记的承载体
     * @param segments 段内可混排对话/动作/心理三种语义
     */
    data class Paragraph(
        val segments: List<TextSegment>,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 有序/无序列表
     * @param ordered true=有序(1. 2. 3.)，false=无序(- * +)
     */
    data class ListBlock(
        val items: List<String>,
        val ordered: Boolean,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 代码块
     * @param content  代码内容
     * @param language 语言标识（可选，如 kotlin/python）
     */
    data class Code(
        val content: String,
        val language: String?,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 表格
     * @param headers 表头
     * @param rows    数据行
     */
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /** 引用 */
    data class Quote(
        val text: String,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    // ── 层2 非文字产出块类型 ──────────────────────────

    /**
     * 文档产出（如报告、文章）
     * @param title       文档标题
     * @param fileUrl     文件 URL
     * @param previewText 预览摘要（可选）
     * @param fileType    文件类型（如 pdf/docx/md）
     */
    data class Document(
        val title: String,
        val fileUrl: String,
        val previewText: String?,
        val fileType: String,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 图片产出
     * @param url     图片 URL
     * @param caption 图片说明（可选）
     * @param width   宽度（可选）
     * @param height  高度（可选）
     */
    data class Image(
        val url: String,
        val caption: String?,
        val width: Int?,
        val height: Int?,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 表格类文件（区别于内联 table 块，指整份表格文件如 Excel/CSV 产出）
     * @param title    文件标题
     * @param fileUrl  文件 URL
     * @param rowCount 行数（可选）
     */
    data class TableFile(
        val title: String,
        val fileUrl: String,
        val rowCount: Int?,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 外部/内部链接
     * @param title       链接标题
     * @param url         链接 URL
     * @param description 摘要说明（可选）
     */
    data class Link(
        val title: String,
        val url: String,
        val description: String?,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 通用文件产出（非文档/图片/表格）
     * @param title      文件标题
     * @param fileUrl    文件 URL
     * @param fileType   文件类型
     * @param sizeLabel  文件大小标签（可选，如"1.2 KB"）
     */
    data class FileBlock(
        val title: String,
        val fileUrl: String,
        val fileType: String,
        val sizeLabel: String?,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    // ── Agent 过程类块类型（窗口7定稿） ──────────────────────────
    //
    // 字段结构基于以下数据源核实：
    //   ToolCall     <- AgentActivityEventEntity（eventType=TOOL_CALL/DEGRADE_*）
    //   Thinking     <- ChatTagParser [thinking:xxx] 标签剥离后的思考内容
    //   MemoryUpdate <- MemoryRepository.save() / AgentCoreTools MemoryWriteTool
    //   WorkflowStep <- WorkflowStepResultEntity
    //   SkillActivity<- SkillTools（Window C）+ AgentActivityEventEntity（SKILL_*）
    //
    // 适配层 ContentBlockAdapter 负责从 AgentActivityTimelineItem 转换为这些块，
    // UI 组件不直接绑定心迹表字段名（遵循 AgentActivityRepository 注释建议）。

    /**
     * 工具调用展示（窗口7定稿）
     *
     * 数据来源：AgentActivityEventEntity（eventType=TOOL_CALL/DEGRADE_*）。
     * 降级场景（DEGRADE_RETRY/SWITCH/GIVEUP）也用本块呈现，通过 [decisionNote]
     * 区分"为什么换了策略"。
     *
     * @param toolName     工具名称
     * @param status       调用状态枚举
     * @param paramsSummary 工具参数的可读摘要（从 toolParamsJson 转换，非原始 JSON）
     * @param outputSummary 工具产出摘要（<=300字，截断自 outputRaw）
     * @param errorMessage 失败时的简短错误信息
     * @param durationMs   执行耗时（completedAt - startedAt），null 表示未完成
     * @param decisionNote 决策依据（如"上次同参数超时，改用 xxx 参数重试"）
     */
    data class ToolCall(
        val toolName: String,
        val status: ToolCallStatus,
        val paramsSummary: String? = null,
        val outputSummary: String? = null,
        val errorMessage: String? = null,
        val durationMs: Long? = null,
        val decisionNote: String? = null,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 工具调用状态枚举（窗口7定稿）
     *
     * 映射 AgentActivityRepository.Outcome + 未完成态。
     */
    enum class ToolCallStatus {
        SUCCESS,
        FAIL,
        TIMEOUT,
        PENDING,
    }

    /**
     * 思考过程展示（窗口7定稿）
     *
     * 数据来源：ChatTagParser 从 AI 输出中剥离的 [thinking:xxx] 标签内容。
     *
     * @param content 思考内容（完整文本，渲染层负责折叠/截断）
     */
    data class Thinking(
        val content: String,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 新记忆生成展示（窗口7定稿）
     *
     * 数据来源：MemoryRepository.save() / AgentCoreTools MemoryWriteTool。
     *
     * @param summary    记忆摘要
     * @param actionType 操作类型枚举
     * @param domain     记忆域标签（PERSONAL/WORK/WORLD/RULE/INFERENCE），可选
     */
    data class MemoryUpdate(
        val summary: String,
        val actionType: MemoryActionType,
        val domain: String? = null,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 记忆操作类型枚举（窗口7定稿）
     */
    enum class MemoryActionType {
        CREATE,
        UPDATE,
        DELETE,
    }

    /**
     * 工作流步骤展示（窗口7定稿）
     *
     * 数据来源：WorkflowStepResultEntity。
     *
     * @param stepName      步骤名称（映射自 toolName）
     * @param status        步骤状态枚举
     * @param outputSummary 步骤产出摘要
     * @param errorMessage  失败时的错误信息
     * @param nextAction    下一步决策（decidedNextAction，如"重试"或"跳过"）
     */
    data class WorkflowStep(
        val stepName: String,
        val status: WorkflowStepStatus,
        val outputSummary: String? = null,
        val errorMessage: String? = null,
        val nextAction: String? = null,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 工作流步骤状态枚举（窗口7定稿）
     */
    enum class WorkflowStepStatus {
        SUCCESS,
        FAIL,
        PENDING,
        SKIPPED,
    }

    /**
     * 技能活动展示（窗口7定稿·第五类 Agent 过程块）
     *
     * 数据来源：SkillTools（Window C 已落地）+ AgentActivityEventEntity
     * （eventType=SKILL_CREATE/SKILL_INVOKE，已在 EventType 中预留）。
     *
     * @param skillName   技能名称
     * @param actionType  操作类型枚举
     * @param status      活动状态枚举
     * @param description 操作描述或技能 shortDescriptor
     */
    data class SkillActivity(
        val skillName: String,
        val actionType: SkillActionType,
        val status: SkillActivityStatus,
        val description: String? = null,
        override val metadata: ContentBlockMetadata? = null,
    ) : ContentBlock()

    /**
     * 技能操作类型枚举（窗口7定稿）
     *
     * 映射 SkillTools 的 5 个工具（skill_create/skill_invoke/skill_expand/
     * skill_edit/skill_deprecate）按操作语义归类。
     */
    enum class SkillActionType {
        CREATE,
        INVOKE,
        EDIT,
        DEACTIVATE,
    }

    /**
     * 技能活动状态枚举（窗口7定稿）
     */
    enum class SkillActivityStatus {
        SUCCESS,
        FAIL,
        PENDING,
    }

    /** 可选的通用元数据 */
    abstract val metadata: ContentBlockMetadata?
}
