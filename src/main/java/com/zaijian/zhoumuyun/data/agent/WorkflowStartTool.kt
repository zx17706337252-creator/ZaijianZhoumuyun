package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository

/**
 * WorkflowStartTool — 多步骤工作流系统 Step 4：触发入口工具
 *
 * ═══════════════════════════════════════════════════════════════
 * 不新增检测层，复用 Phase 13 标签机制：注册为普通 AgentTool，
 * LLM 在正常回复里识别到复合目标，直接输出
 *   <tool:workflow_start goal="..."/>
 * 和 web_search / schedule_create 走同一套 ToolParser → ToolCallInterceptor 流程。
 *
 * execute() 只做两件快事，立即返回，不占用 ToolCallInterceptor.MAX_TOOL_ROUNDS 预算：
 *   ① WorkflowRepository.createJob() 写库（此刻 characterId() 拿到的是
 *      ChatViewModel.currentCharacterId，前台调用，准确可靠——写进
 *      WorkflowJobEntity.characterId 后永久保存，后台 Worker 不再依赖它）
 *   ② WorkManagerScheduler.enqueueWorkflow() 入队，真正的多步循环交给
 *      WorkflowJobWorker / WorkflowEngine 在后台跑
 *
 * 返回的 content 会被 ToolCallInterceptor 当作"工具结果"回注给 LLM，
 * 由角色用自己的语气转述给用户，所以这里只给一句简短的事实描述，
 * 不需要预先设计任何"播报话术"。
 * ═══════════════════════════════════════════════════════════════
 */
class WorkflowStartTool(
    private val context: Context,
    private val workflowRepository: WorkflowRepository,
    /** 与现有 Phase 22/28 工具同样的写法：lambda 取值，确保切角色后始终是当前角色 */
    private val characterId: () -> Int,
) : AgentTool {

    override val name = "workflow_start"
    override val paramKeys = listOf("goal")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val goal = params["goal"]?.trim()
        if (goal.isNullOrBlank()) {
            return ToolResult(
                toolName = name,
                success = false,
                content = "",
                error = "缺少 goal 参数，工作流无法创建",
            )
        }

        return try {
            val jobId = workflowRepository.createJob(
                characterId = characterId(),
                goal = goal,
            )
            WorkManagerScheduler.enqueueWorkflow(context, jobId)
            ToolResult(
                toolName = name,
                success = true,
                content = "已经开始在后台处理，弄完告诉你",
            )
        } catch (e: Exception) {
            // AgentTool 约定不抛异常
            ToolResult(
                toolName = name,
                success = false,
                content = "",
                error = "创建工作流任务失败：${e.message}",
            )
        }
    }
}
