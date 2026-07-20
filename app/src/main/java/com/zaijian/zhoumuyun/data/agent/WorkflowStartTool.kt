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
    override val description = "触发多步骤自动化工作流，用于说清楚的复杂连续任务后台执行"
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
        // 批次2 2-2修复：拒绝以 "CI/CD: " 开头的 goal。该前缀是 cicd_start 工具
        // 创建的 job 的固定标识，BootReceiver.kt 第154行用它区分 CiCdPipelineWorker
        // （markFailed 不可恢复）和 WorkflowJobWorker（enqueueWorkflow 续跑）。
        // 若 LLM 把 workflow_start 的 goal 写成这个前缀（用户说"帮我跑CI/CD"时
        // 尤甚），设备重启后该 job 会被误判为不可恢复任务直接 markFailed，
        // 即使 WorkManager 自动恢复了原 Worker，WorkflowEngine.run() 一看
        // status 已是 FAILED 就立刻 return，任务永久卡死。此处拒绝该前缀，
        // 从源头堵住误判，无需改 BootReceiver 的启发式分支。
        if (goal.startsWith("CI/CD: ")) {
            return ToolResult(
                toolName = name,
                success = false,
                content = "",
                error = "goal 不能以 'CI/CD: ' 开头，该前缀保留给 cicd_start 工具；请换一个描述",
            )
        }

        // P2 修复（Batch5/Batch6审查报告问题9）：原实现直接把 characterId() 的返回值
        // 传给 createJob，没有像 WorkbenchTaskTools 等角色绑定工具那样先校验
        // charId < 0。App 启动阶段静态占位注册时 characterId() 可能仍是 -1
        // （尚未被 ChatViewModel.init() 动态覆盖），此时调用会把 characterId=-1
        // 静默写进 WorkflowJobEntity，后台 Worker 处理时角色上下文错误但不会报错。
        // 现在提前拒绝，与 TaskStartTool 等工具的校验方式保持一致。
        val charId = characterId()
        if (charId < 0) {
            return ToolResult(
                toolName = name,
                success = false,
                content = "",
                error = "角色未初始化",
            )
        }

        return try {
            val jobId = workflowRepository.createJob(
                characterId = charId,
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
