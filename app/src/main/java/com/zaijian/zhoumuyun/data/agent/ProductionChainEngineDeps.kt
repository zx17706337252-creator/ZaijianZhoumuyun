package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.ChainRunRepository
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CoroutineScope

/**
 * 灵活自动化编排 · 生产环境 ChainEngineDeps 实现（§11.8）
 *
 * 对接项目现有的 WorkflowEngine / AgentToolRegistry / WorkManagerScheduler。
 *
 * **当前实现状态**：
 * - [scheduleResume]：已接入 WorkManager（Step5），Wait 节点进程被杀后可续跑
 * - [runAction]：已接入 WorkflowEngine（Step6 §11.8）
 * - [runCheckTool]：已接入 AgentToolRegistry（Step6 §11.8），Check 节点只做等值比较
 *
 * @param scope      App 级 CoroutineScope（appScope）。Step5 之后 scheduleResume 改为
 *                   调用 WorkManagerScheduler，内部不再 launch，此 scope 仅作接口占位；
 *                   ChainResumeWorker 场景下现场构造一份即弃 CoroutineScope 即可。
 * @param chainEngine ChainEngine 实例，唤醒后重新调用 advance()
 * @param repository  ChainRunRepository，advance() 的数据访问层
 * @param context     用于 WorkManagerScheduler 调度（Step5 新增）
 */
class ProductionChainEngineDeps(
    private val scope: CoroutineScope,
    private val chainEngine: ChainEngine,
    private val repository: ChainRunRepository,
    private val context: Context,
) : ChainEngineDeps {

    /**
     * Wait 节点：安排延迟唤醒。
     *
     * Step5 实现：调用 [WorkManagerScheduler.enqueueChainResume]，把 [ChainResumeWorker]
     * 排入 WorkManager 队列，到点后重新调用 [ChainEngine.advance]。WorkManager 持久化
     * WorkSpec，App 被杀后到点仍会被系统拉起续跑——这是 Wait 节点核心承诺的落地。
     * （Step4 的协程 delay() 实现已删除：App 被杀即丢失，不保留死代码。）
     */
    override suspend fun scheduleResume(runId: String, delayMs: Long) {
        ZLog.d(TAG, "scheduleResume: runId=$runId, delayMs=$delayMs (WorkManager)")
        WorkManagerScheduler.enqueueChainResume(context, runId, delayMs)
    }

    /**
     * Action 节点：执行工作流任务（§11.8 完整实现）。
     *
     * workflowRepository.createJob(characterId, resolvedGoal) → WorkflowEngine.run() →
     * 读取终态返回 [ActionResult]。COMPLETED → Success(resultSummary)；
     * FAILED/TIMEOUT → Failure(reason)，由 ChainEngine.handleAction() 统一 markFailed。
     *
     * 第二层 Provider 检查：ChainResumeWorker.doWork() 已在 advance() 前检查过（§11.9），
     * 这里保留是因为 Action 节点可能不是链条首个节点（前面可能有 Check/Wait），让
     * runAction 可独立调用/测试，不依赖调用方一定先检查过，成本极低。
     */
    override suspend fun runAction(characterId: Int, resolvedGoal: String): ActionResult {
        val provider = ProviderManager.instance.activeProvider
            ?: return ActionResult.Failure("未配置可用的 LLM Provider")

        // 复用同一份 AppDatabase 单例，避免 workflowJobDao()/workflowStepResultDao() 各调一次
        val db = AppDatabase.getInstance(context)
        val workflowRepository = WorkflowRepository(
            db = db,
            workflowJobDao = db.workflowJobDao(),
            workflowStepResultDao = db.workflowStepResultDao(),
            context = context,
        )
        val jobId = workflowRepository.createJob(characterId, resolvedGoal)
        WorkflowEngine.run(jobId, workflowRepository, provider)
        val job = workflowRepository.findById(jobId)
            ?: return ActionResult.Failure("workflow job 执行后查询不到（jobId=$jobId）")

        return when (job.status) {
            WorkflowRepository.STATUS_COMPLETED ->
                ActionResult.Success(job.resultSummary ?: "已完成")
            else ->
                // FAILED / TIMEOUT 都归为 Failure，ChainEngine.handleAction() 收到
                // Failure 后统一调 markFailed，不需要在 ActionResult 里再区分子类型
                ActionResult.Failure(job.failReason ?: "workflow 执行未完成（status=${job.status}）")
        }
    }

    /**
     * Check 节点：执行只读工具刷新 context（§11.8 完整实现）。
     *
     * 白名单校验：Check 节点的 checkToolName 理论上已在 ChainCreateTool 创建阶段校验过，
     * 这里是运行时兜底（防御纵深，不信任"创建时校验过就永远安全"）。
     *
     * 工具输出是纯文本（ToolResult.content），Check 节点的 context 需要 key-value 结构
     * 供 ConditionEvaluator 求值。约定：工具输出统一写入固定 key "checkResult"。
     *
     * 【ConditionEvaluator 只支持 == != < > <= >= 六个比较符，不支持 contains】
     * 故 expression 只能写精确匹配，如 "checkResult == '晴'"，不能写包含匹配。
     * 本次不扩展 ConditionEvaluator（核心求值组件，被 Check 节点全链路依赖），
     * 等真的出现需要模糊匹配的具体场景再回来加。
     */
    override suspend fun runCheckTool(run: ChainRunEntity, toolName: String): Map<String, Any?> {
        if (toolName !in WorkflowEngine.SAFE_TOOL_NAMES) {
            ZLog.w(TAG, "runCheckTool: $toolName 不在白名单内，跳过")
            return emptyMap()
        }
        val tool = AgentToolRegistry.get(toolName) ?: return emptyMap()
        val result = try {
            // Check 节点当前设计（§3.3）只有 checkToolName 字段，没有工具参数字段——
            // 只能调用"无参数只读工具"（如 weather/datetime），传 emptyMap()。
            tool.execute(emptyMap())
        } catch (e: Exception) {
            ZLog.w(TAG, "runCheckTool: $toolName 执行异常: ${e.message?.take(120)}", e)
            return emptyMap()
        }
        if (!result.success) return emptyMap()

        // 工具输出统一写入固定 key "checkResult"，供 ChainDefinition 的 expression 引用
        return mapOf("checkResult" to result.content)
    }

    companion object {
        private const val TAG = "ProductionChainEngineDeps"
    }
}
