package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunStatus
import com.zaijian.zhoumuyun.data.repository.AgentActivityRepository
import com.zaijian.zhoumuyun.data.repository.ChainRunRepository
import com.zaijian.zhoumuyun.util.ZLog
import org.json.JSONObject

/**
 * 灵活自动化编排 · 节点解释器（§5 ChainEngine）
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责：给定一个 [ChainRunEntity]（runId），读取其对应的 [ChainDefinitionEntity]，
 * 解析节点列表，根据 currentNodeIndex 取出当前节点，按节点类型执行：
 *
 *   - **Wait**：设为 WAITING + wakeAtMs，安排延迟唤醒，函数返回（不递归）
 *   - **Check**：可选执行只读工具刷新 context → 求值条件表达式 → 走 onTrue/onFalse
 *     分支 → 原子推进 currentNodeIndex → 递归调用处理下一节点
 *   - **Action**：解析 goal 占位符 → 执行 WorkflowEngine → 合并结果到 context →
 *     原子推进 currentNodeIndex → 递归调用处理下一节点
 *   - **End**：写入终态（COMPLETED/CANCELLED），函数返回
 *
 * ── 设计要点（逐条对照 §11 补充）──────────────────────────────
 *
 * **§11.2 数据库级认领锁**：advance() 入口先 claimRun()，返回 0 直接 return（跳过，
 * 不是异常）；执行体末尾 finally { releaseLock() }。内存 Mutex 不在此层处理——
 * 同进程内的第二层保险由调用方（ChainResumeWorker）的 ExistingWorkPolicy 去重覆盖，
 * 跨进程竞争由数据库锁兜底。claim/release 包裹在 advance() 外层，递归调用的
 * advanceInternal() 不重复 claim（否则会因锁已持有而失败）。
 *
 * **§11.6 超时上限**：入口处检查 deadlineAt / visitCount >= maxNodeVisits → markFailed，
 * 对照 WorkflowEngine.runInternal() 207-221 行的对称写法。
 *
 * **§11.7 原子推进**：Check/Action 节点的 context 更新 + currentNodeIndex 推进
 * 通过 [ChainRunRepository.advanceAtomic] 单条 UPDATE 原子完成，不分两次调用。
 *
 * **§11.5 占位符缺失**：resolvePlaceholders 遇到缺失字段返回 null，ChainEngine
 * 收到 null 时直接 markFailed，不创建 WorkflowEngine 任务，不静默传值。
 *
 * **§11.2 递归推进**：Check/Action 节点执行完后递归调用 advanceInternal() 立即
 * 处理下一节点。try/finally 结构确保异常时 releaseLock 被执行（对照
 * WorkflowEngine.run() 的 finally 块写法）。
 *
 * ── 可测试性（§12.5.1(b)）────────────────────────────────────
 * [advance] 只依赖 [ChainRunRepository] + [ChainEngineDeps] 两个接口，不直接持有
 * Context / WorkManager / LLMProvider 引用。测试时传入 FakeChainRunRepository
 * （HashMap 实现）+ FakeChainEngineDeps（空操作 / 预设返回值），即可在纯 JVM
 * 环境验证状态机推进逻辑，不需要 Robolectric、不需要 instrumented test。
 * ═══════════════════════════════════════════════════════════════
 */
object ChainEngine {

    private const val TAG = "ChainEngine"

    /** §11.2：认领锁 TTL，与 ScheduledJobDao 同款 3 分钟 */
    private const val LOCK_TTL_MS = 3 * 60 * 1000L

    // ─────────────────────────────────────────────────────────
    //  公开入口
    // ─────────────────────────────────────────────────────────

    /**
     * 推进一条链条运行实例的当前节点。
     *
     * 调用方（ChainTriggerMatcher / ChainResumeWorker）负责在合适的协程里启动本方法。
     * Wait 节点执行后函数返回，等待 WorkManager 延迟唤醒后再次调用本方法续跑。
     *
     * §11.2：入口处先 claimRun()，返回 0 说明被其他执行体先抢到了，直接跳过（不抛异常）。
     * 执行体末尾 finally 无条件 releaseLock()，配合 LOCK_TTL_MS 兜底"认领后执行体崩溃"。
     *
     * @param runId      ChainRunEntity.id
     * @param repository 数据访问层（生产环境用 ChainRunRepositoryImpl，测试用 FakeChainRunRepository）
     * @param deps       外部依赖（scheduleResume / runAction / runCheckTool）
     */
    suspend fun advance(
        runId: String,
        repository: ChainRunRepository,
        deps: ChainEngineDeps,
    ) {
        val now = System.currentTimeMillis()
        // §11.2：数据库级认领锁
        val claimed = repository.claimRun(runId, now, now + LOCK_TTL_MS)
        if (claimed == 0) return  // 被其他执行体锁定，跳过（不是异常）

        try {
            advanceInternal(runId, repository, deps)
        } finally {
            // §11.2：无条件释放锁，在 finally 块调用
            // 对照 WorkflowEngine.run() 的 finally { jobMutexMap.compute... } 结构
            repository.releaseLock(runId)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  内部循环（在认领锁保护下运行，递归调用不重复 claim）
    // ─────────────────────────────────────────────────────────

    /**
     * 实际的节点处理逻辑，在 advance() 的 claim/releaseLock 保护下运行。
     *
     * Check/Action 节点执行完后递归调用本方法处理下一节点（§11.2 递归推进），
     * Wait/End 节点执行后返回（不递归）。
     */
    private suspend fun advanceInternal(
        runId: String,
        repository: ChainRunRepository,
        deps: ChainEngineDeps,
    ) {
        val run = repository.findById(runId) ?: return
        // 非 RUNNING 状态直接返回（幂等退出，防止重复执行）
        if (run.status != ChainRunStatus.RUNNING) return

        val now = System.currentTimeMillis()

        // ── §11.6：deadline 检查（对照 WorkflowEngine.runInternal() 211-216 行）──
        if (now >= run.deadlineAt) {
            repository.markFailed(runId, "链条总时长超过上限（deadlineAt=${run.deadlineAt}）")
            return
        }

        // ── §11.6：visitCount 检查（对照 WorkflowEngine 217-221 行 maxSteps 检查）──
        if (run.visitCount >= run.maxNodeVisits) {
            repository.markFailed(
                runId,
                "节点推进次数超过上限（maxNodeVisits=${run.maxNodeVisits}，当前 visitCount=${run.visitCount}）",
            )
            return
        }

        // §11.6：递增推进计数
        repository.incrementVisitCount(runId)

        // 读取链条定义并解析节点
        val def = repository.findDefinition(run.chainDefId) ?: return
        val nodes = try {
            ChainNodeCodec.deserialize(def.nodesJson)
        } catch (e: IllegalArgumentException) {
            repository.markFailed(runId, "nodesJson 解析失败: ${e.message?.take(200)}")
            return
        }

        val node = nodes.getOrNull(run.currentNodeIndex)
        if (node == null) {
            repository.markFailed(
                runId,
                "currentNodeIndex=${run.currentNodeIndex} 超出节点数组范围（size=${nodes.size}）",
            )
            return
        }

        // 按节点类型分发
        when (node) {
            is ChainNode.Wait -> handleWait(runId, run, node, repository, deps)
            is ChainNode.Check -> handleCheck(runId, run, node, nodes, repository, deps)
            is ChainNode.Action -> handleAction(runId, run, node, nodes, repository, deps)
            is ChainNode.End -> handleEnd(runId, node, repository)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Wait 节点
    // ─────────────────────────────────────────────────────────

    /**
     * §5 Wait 分支：推进 currentNodeIndex + 1 → 设为 WAITING + wakeAtMs → 安排延迟唤醒。
     *
     * §3.3：Wait 节点没有 next 字段——执行后由 ChainEngine 推进到 currentNodeIndex + 1
     * （顺序下一个节点），不像 Action/Check 那样通过节点 id 跳转。
     *
     * 必须在 markWaiting 之前推进 index，这样当 ChainResumeWorker 唤醒后调用
     * markRunning + advance 时，currentNodeIndex 已指向下一个节点，不会重复处理 Wait。
     *
     * Wait 节点不是"挂起协程 delay()"，而是复用 WorkManagerScheduler 的延迟入队能力，
     * 把状态持久化为 WAITING + wakeAtMs，进程可以被杀掉——WorkManager 到点唤醒后
     * 重新调用 advance()，从数据库读到 currentNodeIndex 继续跑。
     *
     * Wait 节点不递归调用 advanceInternal()——执行暂停，等待外部唤醒。
     */
    private suspend fun handleWait(
        runId: String,
        run: ChainRunEntity,
        node: ChainNode.Wait,
        repository: ChainRunRepository,
        deps: ChainEngineDeps,
    ) {
        // §3.3：推进到 currentNodeIndex + 1（Wait 无显式出边，顺序下一个节点）
        val nextIndex = run.currentNodeIndex + 1
        // 先推进 index（原子更新 context + index），再设为 WAITING
        repository.advanceAtomic(runId, run.context, nextIndex)

        val now = System.currentTimeMillis()
        val wakeAt = now + node.durationMs
        repository.markWaiting(runId, wakeAt)
        deps.scheduleResume(runId, node.durationMs)
        // 不递归——等待 ChainResumeWorker 唤醒后重新调用 advance()
    }

    // ─────────────────────────────────────────────────────────
    //  Check 节点
    // ─────────────────────────────────────────────────────────

    /**
     * §5 Check 分支：可选执行只读工具 → 求值条件表达式 → 走 onTrue/onFalse → 原子推进 → 递归。
     *
     * §11.7：context 更新（checkToolName 结果合并）+ currentNodeIndex 推进通过
     * advanceAtomic 单条 UPDATE 原子完成。
     */
    private suspend fun handleCheck(
        runId: String,
        run: ChainRunEntity,
        node: ChainNode.Check,
        nodes: List<ChainNode>,
        repository: ChainRunRepository,
        deps: ChainEngineDeps,
    ) {
        // 解析当前 context（可能被 checkToolName 更新）
        val ctx = try {
            JSONObject(run.context)
        } catch (e: Exception) {
            // context JSON 损坏，用空对象兜底（不中断链条，让 ConditionEvaluator 判 false）
            JSONObject()
        }

        // 可选：执行只读工具刷新 context 数据
        if (node.checkToolName != null) {
            var checkToolSuccess = true
            try {
                val toolResults = deps.runCheckTool(run, node.checkToolName)
                for ((key, value) in toolResults) {
                    ctx.put(key, value)
                }
            } catch (e: Exception) {
                checkToolSuccess = false
                logWarn("Check 节点 ${node.id} 执行工具 ${node.checkToolName} 失败: ${e.message?.take(120)}")
                // 工具失败不中断链条，用现有 context 继续求值（可能判 false 走 onFalse 分支）
            }
            // §7 心迹埋点：记录 Check 节点的只读工具调用（success 取决于是否抛异常；
            // 生产环境 runCheckTool 内部已 catch，恒为 true，此处保留以覆盖 Fake/异常实现）
            recordChainActivity(
                characterId = run.characterId,
                runId = runId,
                toolName = node.checkToolName,
                success = checkToolSuccess,
                outputSummary = null,
                errorMessage = if (checkToolSuccess) null else "Check工具执行异常",
            )
        }

        // 求值条件表达式（ConditionEvaluator 永不抛异常，缺失/类型不匹配判 false）
        val result = ConditionEvaluator.evaluate(node.expression, ctx)
        val nextId = if (result) node.onTrue else node.onFalse
        val nextIndex = nodes.indexOfFirst { it.id == nextId }

        if (nextIndex < 0) {
            // 不应发生——validate() 已校验 onTrue/onFalse 引用存在，此处防御性兜底
            repository.markFailed(runId, "Check 节点 ${node.id} 的 ${if (result) "onTrue" else "onFalse"}='$nextId' 在节点数组中未找到")
            return
        }

        // §11.7：原子推进（context 可能被 checkToolName 更新，与 index 一起原子写入）
        repository.advanceAtomic(runId, ctx.toString(), nextIndex)

        // §11.2：递归推进到下一节点（Check 节点不耗时，立即处理下一节点）
        advanceInternal(runId, repository, deps)
    }

    // ─────────────────────────────────────────────────────────
    //  Action 节点
    // ─────────────────────────────────────────────────────────

    /**
     * §5 Action 分支：解析占位符 → 执行工作流 → 合并结果到 context → 原子推进 → 递归。
     *
     * §11.5：resolvePlaceholders 遇到缺失字段返回 null，直接 markFailed，不创建任务。
     * §11.7：context 更新（actionResult）+ currentNodeIndex 推进通过 advanceAtomic 原子完成。
     */
    private suspend fun handleAction(
        runId: String,
        run: ChainRunEntity,
        node: ChainNode.Action,
        nodes: List<ChainNode>,
        repository: ChainRunRepository,
        deps: ChainEngineDeps,
    ) {
        // §11.5：解析 goal 中的 {{context.xxx}} 占位符
        val resolvedGoal = resolvePlaceholders(node.goal, run.context)
        if (resolvedGoal == null) {
            // 占位符引用的字段缺失，不创建 WorkflowEngine 任务，直接 markFailed
            // 对照 WorkflowEngine.runInternal() "recordStep 失败宁可中止也不带着错误数据继续跑"
            repository.markFailed(runId, "Action节点goal占位符解析失败：引用的字段在context中缺失（goal=${node.goal.take(100)}）")
            return
        }

        // 执行工作流任务
        val outcome = try {
            deps.runAction(run.characterId, resolvedGoal)
        } catch (e: Exception) {
            // runAction 抛异常时标记失败，不让异常击穿到 advance() 的 finally 块
            // （releaseLock 仍会执行，但 markFailed 需要在此处显式调用）
            repository.markFailed(runId, "Action节点执行异常: ${e.message?.take(200)}")
            return
        }

        when (outcome) {
            is ActionResult.Success -> {
                // §7 心迹埋点：Action 节点没有单一工具名（内部跑完整 WorkflowEngine），toolName=null
                recordChainActivity(
                    characterId = run.characterId,
                    runId = runId,
                    toolName = null,
                    success = true,
                    outputSummary = outcome.resultSummary,
                    errorMessage = null,
                )
                // 合并 actionResult 到 context
                val ctx = try {
                    JSONObject(run.context)
                } catch (e: Exception) {
                    JSONObject()
                }
                ctx.put("actionResult", outcome.resultSummary)

                val nextIndex = nodes.indexOfFirst { it.id == node.next }
                if (nextIndex < 0) {
                    repository.markFailed(runId, "Action 节点 ${node.id} 的 next='${node.next}' 在节点数组中未找到")
                    return
                }

                // §11.7：原子推进（context + index 单条 UPDATE）
                repository.advanceAtomic(runId, ctx.toString(), nextIndex)

                // 递归推进到下一节点
                advanceInternal(runId, repository, deps)
            }
            is ActionResult.Failure -> {
                // §7 心迹埋点：记录 Action 执行失败
                recordChainActivity(
                    characterId = run.characterId,
                    runId = runId,
                    toolName = null,
                    success = false,
                    outputSummary = null,
                    errorMessage = outcome.reason,
                )
                repository.markFailed(runId, "Action节点执行失败: ${outcome.reason}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  End 节点
    // ─────────────────────────────────────────────────────────

    /**
     * §5 End 分支：写入终态，函数返回。
     *
     * outcome 取值：COMPLETED | CANCELLED，由 markCompleted 写入 status 字段。
     * 不递归——链条执行结束。
     */
    private suspend fun handleEnd(
        runId: String,
        node: ChainNode.End,
        repository: ChainRunRepository,
    ) {
        repository.markCompleted(runId, node.outcome)
    }

    // ─────────────────────────────────────────────────────────
    //  §11.5 占位符解析
    // ─────────────────────────────────────────────────────────

    /**
     * 解析 goal 中的 `{{context.xxx}}` 占位符，用 context JSON 中对应的值替换。
     *
     * §11.5：如果任一占位符引用的字段在 context 中缺失，返回 null（不替换失败还继续走）。
     * ChainEngine 收到 null 时直接 markFailed，不创建 WorkflowEngine 任务。
     *
     * 支持点分路径：`{{context.mood.energy}}` → context.optJSONObject("mood")?.opt("energy")
     *
     * 纯函数，不依赖 Android 环境，可直接 JVM 单测。
     *
     * @param goal        原始 goal 文本，可能含 `{{context.xxx}}` 占位符
     * @param contextJson ChainRunEntity.context 的 JSON 字符串
     * @return 解析后的 goal 文本，或 null（有占位符引用的字段缺失时）
     */
    fun resolvePlaceholders(goal: String, contextJson: String): String? {
        val context = try {
            JSONObject(contextJson)
        } catch (e: Exception) {
            // context JSON 损坏——如果 goal 不含占位符，原样返回；否则返回 null
            if (!goal.contains("{{context.")) return goal
            return null
        }

        val pattern = Regex("""\{\{context\.([^}]+)\}\}""")
        val sb = StringBuilder()
        var lastEnd = 0

        for (match in pattern.findAll(goal)) {
            sb.append(goal, lastEnd, match.range.first)
            val fieldPath = match.groupValues[1].trim()
            val value = resolveFieldPath(context, fieldPath)
            if (value == null) {
                // §11.5：占位符引用的字段缺失，返回 null
                return null
            }
            sb.append(value.toString())
            lastEnd = match.range.last + 1
        }

        sb.append(goal, lastEnd, goal.length)
        return sb.toString()
    }

    /**
     * 按点分路径从 JSONObject 中取值。
     *
     * 如 "mood.energy" → context.optJSONObject("mood")?.opt("energy")。
     * 路径中任意一级缺失或类型不对（中间节点不是 JSONObject），返回 null。
     *
     * 与 ConditionEvaluator.resolveField 逻辑一致，但此处独立实现以避免
     * ConditionEvaluator 的 private 可见性限制。
     */
    private fun resolveFieldPath(context: JSONObject, path: String): Any? {
        val parts = path.split(".")
        if (parts.isEmpty()) return null

        var current: Any? = context
        for (part in parts) {
            current = when (current) {
                is JSONObject -> {
                    if (current.has(part) && !current.isNull(part)) current.get(part) else null
                }
                else -> null
            }
            if (current == null) return null
        }
        return current
    }

    // ── 日志 ──────────────────────────────────────────────

    private fun logWarn(msg: String) {
        try {
            ZLog.w(TAG, msg)
        } catch (e: Throwable) {
            System.err.println("[$TAG] $msg")
        }
    }

    // ── §7 心迹埋点 ──────────────────────────────────────

    /**
     * 写一条链条节点「心迹」事件，供「心迹」面板呈现 Agent 过程（§7）。
     *
     * 仿照 WorkflowEngine.recordWorkflowActivity() 的封装思路，但直接调用公开的
     * [AgentActivityRepository.recordEvent]——recordWorkflowActivity() 是 WorkflowEngine
     * 内部的 private 方法，ChainEngine 是独立的 object，语言层面访问不到。
     *
     * observeTimeline() 的 UNION 查询机制不需要额外改动：链条心迹事件写入
     * agent_activity_events 表（sceneType=chain），走 observeRecentByCharacter() 这一路径，
     * 与 workflow 场景共用同一张表、同一查询方法，只是 sceneType 值不同。
     *
     * 埋点失败不影响链条执行：try-catch 兜底，仅记日志（AppContainer 未初始化等极端场景）。
     */
    private suspend fun recordChainActivity(
        characterId: Int,
        runId: String,
        toolName: String?,
        success: Boolean,
        outputSummary: String?,
        errorMessage: String?,
    ) {
        try {
            AppContainer.instance.agentActivityRepo.recordEvent(
                characterId = characterId,
                sessionRef = runId,
                sceneType = AgentActivityRepository.SceneType.CHAIN,
                eventType = AgentActivityRepository.EventType.TOOL_CALL,
                toolName = toolName,
                outcome = if (success) AgentActivityRepository.Outcome.SUCCESS
                          else AgentActivityRepository.Outcome.FAIL,
                outputRaw = outputSummary,
                errorMessage = errorMessage,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "心迹埋点失败（不影响链条执行）", e)
        }
    }
}
