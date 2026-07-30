package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity

/**
 * 灵活自动化编排 · ChainEngine 外部依赖抽象
 *
 * ChainEngine.advance() 内部需要调用三类外部能力：
 * 1. **Wait 节点**：安排延迟唤醒（生产环境走 WorkManagerScheduler.enqueueChainResume）
 * 2. **Action 节点**：执行工作流任务（生产环境走 WorkflowRepository.createJob + WorkflowEngine.run）
 * 3. **Check 节点**：执行只读工具刷新数据（生产环境走 AgentToolRegistry）
 *
 * 这三类能力都依赖 Android 环境（Context、WorkManager、LLM Provider）或重量级组件
 * （WorkflowEngine），不能在纯 JVM 测试中直接使用。通过本接口抽象，测试时可传入
 * Fake 实现（空操作 / 返回预设值），使 ChainEngine 的状态机逻辑可独立验证。
 *
 * §12.5.1(b) 要求"不需要 Robolectric、不需要 instrumented test"，本接口是达成
 * 这一要求的关键设计——ChainEngine 只依赖 [ChainRunRepository] + [ChainEngineDeps]
 * 两个接口，不直接持有任何 Android 组件引用。
 */
interface ChainEngineDeps {

    /**
     * Wait 节点：安排延迟唤醒。
     *
     * 生产环境实现：调用 WorkManagerScheduler.enqueueChainResume(context, runId, delayMs)，
     * 把 ChainResumeWorker 排入 WorkManager 队列，到点后重新调用 ChainEngine.advance()。
     *
     * 测试环境实现：空操作（测试手动调用 advance() 模拟唤醒），或记录调用参数供断言。
     *
     * @param runId 链条运行实例 ID
     * @param delayMs 延迟毫秒数（Wait 节点的 durationMs）
     */
    suspend fun scheduleResume(runId: String, delayMs: Long)

    /**
     * Action 节点：执行工作流任务并返回结果。
     *
     * 生产环境实现：
     * 1. workflowRepository.createJob(characterId, resolvedGoal) 创建 WorkflowJobEntity
     * 2. WorkflowEngine.run(jobId, workflowRepository, provider) 执行到终结
     * 3. 读取 job 终态，返回 [ActionResult]
     *
     * 测试环境实现：直接返回预设的 [ActionResult]，不实际调用 WorkflowEngine。
     *
     * @param characterId 角色ID（支持 -1 项目级，§11.12）
     * @param resolvedGoal 已解析占位符的 goal 文本
     * @return 执行结果（成功含 resultSummary，失败含 failReason）
     */
    suspend fun runAction(characterId: Int, resolvedGoal: String): ActionResult

    /**
     * Check 节点：执行只读工具刷新 context 数据。
     *
     * 生产环境实现：从 AgentToolRegistry 获取工具，执行后将输出解析为 key-value 对
     * 写入 context。对照 WorkflowEngine.executeToolStep() 的白名单校验逻辑
     * （SAFE_TOOL_NAMES），只允许无状态工具。
     *
     * 测试环境实现：返回空 Map（不刷新数据）或返回预设数据。
     *
     * @param run 当前运行实例（提供 characterId、context 等上下文）
     * @param toolName 工具名称（Check 节点的 checkToolName）
     * @return 要合并到 context 的 key-value 对（空 Map 表示无需更新）
     */
    suspend fun runCheckTool(run: ChainRunEntity, toolName: String): Map<String, Any?>
}

/**
 * [ChainEngineDeps.runAction] 的返回值。
 *
 * 对照 WorkflowJobEntity 的终态：
 * - COMPLETED → [ActionResult.Success]，resultSummary 对应 job.resultSummary
 * - FAILED / TIMEOUT → [ActionResult.Failure]，reason 对应 job.failReason
 */
sealed class ActionResult {
    /** Action 执行成功，resultSummary 将被合并到 context 的 "actionResult" 字段 */
    data class Success(val resultSummary: String) : ActionResult()

    /** Action 执行失败，ChainEngine 将调用 markFailed 终止链条 */
    data class Failure(val reason: String) : ActionResult()
}
