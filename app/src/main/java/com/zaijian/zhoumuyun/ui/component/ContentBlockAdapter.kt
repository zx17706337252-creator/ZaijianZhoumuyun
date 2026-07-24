package com.zaijian.zhoumuyun.ui.component

import com.zaijian.zhoumuyun.data.model.ContentBlock
import com.zaijian.zhoumuyun.data.repository.AgentActivityRepository
import com.zaijian.zhoumuyun.data.repository.AgentActivityTimelineItem

/**
 * AgentActivityTimelineItem -> ContentBlock 适配层（窗口7定稿）。
 *
 * 设计依据：AgentActivityRepository.observeTimeline() 注释建议——
 * UI 组件不直接绑定心迹表字段名，通过本适配层间接消费。
 *
 * 转换规则：
 *   TOOL_CALL / DEGRADE_*  -> ContentBlock.ToolCall
 *   SKILL_CREATE           -> ContentBlock.SkillActivity(actionType=CREATE)
 *   SKILL_INVOKE           -> ContentBlock.SkillActivity(actionType=INVOKE)
 *   source=workflow_step   -> ContentBlock.WorkflowStep
 */
object ContentBlockAdapter {

    fun fromTimelineItem(item: AgentActivityTimelineItem): ContentBlock? {
        return when {
            item.source == "workflow_step" -> item.toWorkflowStep()

            item.eventType == AgentActivityRepository.EventType.SKILL_CREATE ->
                item.toSkillActivity(ContentBlock.SkillActionType.CREATE)
            item.eventType == AgentActivityRepository.EventType.SKILL_INVOKE ->
                item.toSkillActivity(ContentBlock.SkillActionType.INVOKE)

            item.eventType == AgentActivityRepository.EventType.TOOL_CALL ||
                item.eventType == AgentActivityRepository.EventType.DEGRADE_RETRY ||
                item.eventType == AgentActivityRepository.EventType.DEGRADE_SWITCH ||
                item.eventType == AgentActivityRepository.EventType.DEGRADE_GIVEUP ->
                item.toToolCall()

            else -> null
        }
    }

    fun fromTimelineItems(items: List<AgentActivityTimelineItem>): List<ContentBlock> =
        items.mapNotNull { fromTimelineItem(it) }

    private fun AgentActivityTimelineItem.toToolCall(): ContentBlock.ToolCall {
        val status = when (outcome) {
            AgentActivityRepository.Outcome.SUCCESS -> ContentBlock.ToolCallStatus.SUCCESS
            AgentActivityRepository.Outcome.FAIL -> ContentBlock.ToolCallStatus.FAIL
            AgentActivityRepository.Outcome.TIMEOUT -> ContentBlock.ToolCallStatus.TIMEOUT
            null -> ContentBlock.ToolCallStatus.PENDING
            else -> ContentBlock.ToolCallStatus.PENDING
        }
        return ContentBlock.ToolCall(
            toolName = toolName ?: eventType,
            status = status,
            outputSummary = outputSummary,
            decisionNote = decisionNote,
        )
    }

    private fun AgentActivityTimelineItem.toWorkflowStep(): ContentBlock.WorkflowStep {
        val status = when (outcome) {
            AgentActivityRepository.Outcome.SUCCESS -> ContentBlock.WorkflowStepStatus.SUCCESS
            AgentActivityRepository.Outcome.FAIL -> ContentBlock.WorkflowStepStatus.FAIL
            null -> ContentBlock.WorkflowStepStatus.PENDING
            else -> ContentBlock.WorkflowStepStatus.PENDING
        }
        return ContentBlock.WorkflowStep(
            stepName = toolName ?: "工作流步骤",
            status = status,
            outputSummary = outputSummary,
            nextAction = decisionNote,
        )
    }

    private fun AgentActivityTimelineItem.toSkillActivity(
        actionType: ContentBlock.SkillActionType,
    ): ContentBlock.SkillActivity {
        val status = when (outcome) {
            AgentActivityRepository.Outcome.SUCCESS -> ContentBlock.SkillActivityStatus.SUCCESS
            AgentActivityRepository.Outcome.FAIL -> ContentBlock.SkillActivityStatus.FAIL
            null -> ContentBlock.SkillActivityStatus.PENDING
            else -> ContentBlock.SkillActivityStatus.PENDING
        }
        return ContentBlock.SkillActivity(
            skillName = toolName ?: "未知技能",
            actionType = actionType,
            status = status,
            description = outputSummary,
        )
    }
}
