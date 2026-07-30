package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunStatus
import com.zaijian.zhoumuyun.data.db.entity.ChainTriggerType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 灵活自动化编排 · ChainEngine 状态机推进测试（§12.5.1(b)）
 *
 * 使用 [FakeChainRunRepository]（内存态 HashMap，不连 Room）+ [FakeChainEngineDeps]
 * （空操作 / 预设返回值），把 ChainEngine 接上跑测试用例。
 *
 * **不需要 Robolectric、不需要 instrumented test**——纯 JVM 环境，仅依赖
 * JUnit 4 + org.json + kotlinx-coroutines-core（runBlocking）。
 *
 * 覆盖要求（§12.5.1(b) 逐条对照）：
 * - Wait → Check(true) → Action → End(COMPLETED) 完整跑通一条链，验证 context 传递
 * - Check onFalse 指回 Wait 形成轮询，验证 visitCount 递增 + 超限判 FAILED
 * - Action goal 占位符引用不存在的字段 → markFailed（§11.5）
 * - 连续 Check → Check → Check → Action 递归推进不栈溢出（§11.2）
 *
 * 额外覆盖：
 * - resolvePlaceholders 纯函数测试（含点分路径、缺失字段、无占位符）
 * - Action 执行失败 → markFailed
 * - deadlineAt 超时 → markFailed
 * - Wait 节点正确推进 currentNodeIndex（不会重复处理 Wait）
 */
class ChainEngineTest {

    private lateinit var repo: FakeChainRunRepository
    private lateinit var deps: FakeChainEngineDeps

    @Before
    fun setup() {
        repo = FakeChainRunRepository()
        deps = FakeChainEngineDeps()
    }

    // ── 测试辅助 ──────────────────────────────────────────

    private val now = System.currentTimeMillis()
    private val dayMs = 24L * 60 * 60 * 1000

    private fun makeRun(
        id: String = "run-1",
        chainDefId: String = "def-1",
        characterId: Int = 1,
        context: String = "{}",
        currentNodeIndex: Int = 0,
        visitCount: Int = 0,
        maxNodeVisits: Int = 200,
        deadlineAt: Long = now + 7 * dayMs,
    ) = ChainRunEntity(
        id = id,
        chainDefId = chainDefId,
        characterId = characterId,
        status = ChainRunStatus.RUNNING,
        currentNodeIndex = currentNodeIndex,
        context = context,
        wakeAtMs = null,
        visitCount = visitCount,
        maxNodeVisits = maxNodeVisits,
        deadlineAt = deadlineAt,
        lockedUntil = null,
        isReported = false,
        startedAt = now,
        updatedAt = now,
    )

    private fun makeDef(
        id: String = "def-1",
        nodesJson: String,
    ) = ChainDefinitionEntity(
        id = id,
        characterId = 1,
        name = "测试链条",
        triggerType = ChainTriggerType.EVENT,
        triggerEventName = "test_event",
        triggerCron = null,
        nodesJson = nodesJson,
        enabled = true,
        createdAt = now,
    )

    /** 便捷方法：构造节点列表 JSON */
    private fun nodesJson(vararg nodes: ChainNode): String =
        ChainNodeCodec.serialize(nodes.toList())

    /**
     * 模拟 ChainResumeWorker 唤醒：markRunning + advance。
     * Wait 节点执行后状态为 WAITING，唤醒时需先恢复为 RUNNING 再调用 advance。
     */
    private suspend fun simulateResume(runId: String) {
        repo.markRunning(runId)
        ChainEngine.advance(runId, repo, deps)
    }

    // ─────────────────────────────────────────────────────────
    // §12.5.1(b)-1: Wait → Check(true) → Action → End(COMPLETED) 完整跑通
    // ─────────────────────────────────────────────────────────

    @Test
    fun `完整链_Wait_Check_true_Action_End_COMPLETED_验证context传递`() = runBlocking {
        val chain = nodesJson(
            ChainNode.Wait(id = "n1", durationMs = 0),
            ChainNode.Check(id = "n2", expression = "energy < 30", onTrue = "n3", onFalse = "n4"),
            ChainNode.Action(id = "n3", goal = "提醒用户休息", next = "n5"),
            ChainNode.End(id = "n4", outcome = ChainEndOutcome.CANCELLED),
            ChainNode.End(id = "n5", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        repo.directInsertRun(makeRun(context = """{"energy":20}"""))

        // 第一次 advance：处理 Wait → WAITING + scheduleResume
        ChainEngine.advance("run-1", repo, deps)

        val afterWait = repo.peekRun("run-1")!!
        assertEquals("Wait 后状态应为 WAITING", ChainRunStatus.WAITING, afterWait.status)
        assertNotNull("wakeAtMs 应已设置", afterWait.wakeAtMs)
        assertEquals("currentNodeIndex 应推进到 1（Check 节点）", 1, afterWait.currentNodeIndex)
        assertEquals("scheduleResume 应被调用一次", 1, deps.scheduleResumeCalls.size)

        // 模拟唤醒：markRunning + advance
        simulateResume("run-1")

        val final = repo.peekRun("run-1")!!
        assertEquals("最终状态应为 COMPLETED", ChainRunStatus.COMPLETED, final.status)

        // 验证 context 传递：actionResult 应被写入 context
        val ctx = JSONObject(final.context)
        assertTrue("context 应包含 actionResult", ctx.has("actionResult"))
        assertEquals("actionResult 应为预设值", "任务完成", ctx.getString("actionResult"))

        // 验证 deps.runAction 被调用时 goal 已正确解析
        assertEquals("runAction 应被调用一次", 1, deps.runActionCalls.size)
        assertEquals("goal 应为原文（无占位符）", "提醒用户休息", deps.runActionCalls[0].second)
    }

    // ─────────────────────────────────────────────────────────
    // §12.5.1(b)-2: Check onFalse 轮询 + visitCount 超限判 FAILED
    // ─────────────────────────────────────────────────────────

    @Test
    fun `轮询链_Check_onFalse指回Wait_visitCount超限判FAILED`() = runBlocking {
        // 链条：Wait(n1) → Check(n2, energy<30, onTrue=n3, onFalse=n1)
        // energy=50 → Check 永远判 false → 轮询回 n1 → 直到 visitCount >= maxNodeVisits
        val chain = nodesJson(
            ChainNode.Wait(id = "n1", durationMs = 0),
            ChainNode.Check(id = "n2", expression = "energy < 30", onTrue = "n3", onFalse = "n1"),
            ChainNode.End(id = "n3", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        // maxNodeVisits=5，便于测试快速触达上限
        repo.directInsertRun(makeRun(context = """{"energy":50}""", maxNodeVisits = 5))

        // 第一轮：advance → Wait(visitCount=1) → WAITING
        ChainEngine.advance("run-1", repo, deps)
        var run = repo.peekRun("run-1")!!
        assertEquals("第一轮后应为 WAITING", ChainRunStatus.WAITING, run.status)
        assertEquals("visitCount 应为 1", 1, run.visitCount)

        // 第二轮：simulateResume → Check(visitCount=2) → false → advanceTo(n1) → Wait(visitCount=3) → WAITING
        simulateResume("run-1")
        run = repo.peekRun("run-1")!!
        assertEquals("第二轮后应为 WAITING", ChainRunStatus.WAITING, run.status)
        assertEquals("visitCount 应为 3", 3, run.visitCount)
        assertEquals("currentNodeIndex 应为 1（Wait推进到Check）", 1, run.currentNodeIndex)

        // 第三轮：simulateResume → Check(visitCount=4) → false → advanceTo(n1) → Wait(visitCount=5) → WAITING
        simulateResume("run-1")
        run = repo.peekRun("run-1")!!
        assertEquals("第三轮后应为 WAITING", ChainRunStatus.WAITING, run.status)
        assertEquals("visitCount 应为 5", 5, run.visitCount)

        // 第四轮：simulateResume → visitCount=5 >= maxNodeVisits=5 → markFailed
        simulateResume("run-1")
        run = repo.peekRun("run-1")!!
        assertEquals("第四轮后应为 FAILED", ChainRunStatus.FAILED, run.status)

        // 验证失败原因包含 visitCount 信息
        val ctx = JSONObject(run.context)
        assertTrue("context 应包含 _failReason", ctx.has("_failReason"))
        val reason = ctx.getString("_failReason")
        assertTrue("失败原因应提及推进次数超限", reason.contains("推进次数"))
    }

    @Test
    fun `轮询链_条件满足时正常跳出轮询到达End`() = runBlocking {
        // 同样的轮询链，但初始 energy=50，第二轮改为 energy=20（模拟条件变化）
        val chain = nodesJson(
            ChainNode.Wait(id = "n1", durationMs = 0),
            ChainNode.Check(id = "n2", expression = "energy < 30", onTrue = "n3", onFalse = "n1"),
            ChainNode.End(id = "n3", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        repo.directInsertRun(makeRun(context = """{"energy":50}""", maxNodeVisits = 100))

        // 第一轮：Wait → WAITING
        ChainEngine.advance("run-1", repo, deps)
        assertEquals(ChainRunStatus.WAITING, repo.peekRun("run-1")!!.status)

        // 第二轮：Check(energy=50) → false → 回 n1 → Wait → WAITING
        simulateResume("run-1")
        assertEquals(ChainRunStatus.WAITING, repo.peekRun("run-1")!!.status)

        // 模拟条件变化：手动修改 context 中的 energy
        val run = repo.peekRun("run-1")!!
        repo.directInsertRun(run.copy(context = """{"energy":20}"""))

        // 第三轮：Check(energy=20) → true → End(COMPLETED)
        simulateResume("run-1")
        assertEquals(ChainRunStatus.COMPLETED, repo.peekRun("run-1")!!.status)
    }

    // ─────────────────────────────────────────────────────────
    // §12.5.1(b)-3: Action goal 占位符引用不存在的字段 → markFailed
    // ─────────────────────────────────────────────────────────

    @Test
    fun `Action节点goal占位符引用缺失字段_直接markFailed`() = runBlocking {
        // goal 引用 {{context.missingField}}，但 context 中没有该字段
        val chain = nodesJson(
            ChainNode.Action(id = "n1", goal = "处理 {{context.missingField}} 的任务", next = "n2"),
            ChainNode.End(id = "n2", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        repo.directInsertRun(makeRun(context = """{"energy":50}"""))

        ChainEngine.advance("run-1", repo, deps)

        val run = repo.peekRun("run-1")!!
        assertEquals("应判 FAILED", ChainRunStatus.FAILED, run.status)

        val ctx = JSONObject(run.context)
        assertTrue("context 应包含 _failReason", ctx.has("_failReason"))
        val reason = ctx.getString("_failReason")
        assertTrue("失败原因应提及占位符解析失败", reason.contains("占位符"))

        // 验证 runAction 未被调用（不该创建 WorkflowEngine 任务）
        assertEquals("runAction 不应被调用", 0, deps.runActionCalls.size)
    }

    @Test
    fun `Action节点goal占位符引用存在的字段_正常解析并执行`() = runBlocking {
        val chain = nodesJson(
            ChainNode.Action(id = "n1", goal = "提醒：能量值 {{context.energy}} 偏低", next = "n2"),
            ChainNode.End(id = "n2", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        repo.directInsertRun(makeRun(context = """{"energy":15}"""))

        ChainEngine.advance("run-1", repo, deps)

        val run = repo.peekRun("run-1")!!
        assertEquals("应正常完成", ChainRunStatus.COMPLETED, run.status)
        assertEquals("runAction 应被调用一次", 1, deps.runActionCalls.size)
        assertEquals("goal 中的占位符应被替换", "提醒：能量值 15 偏低", deps.runActionCalls[0].second)
    }

    @Test
    fun `Action节点goal含点分路径占位符_正常解析`() = runBlocking {
        val chain = nodesJson(
            ChainNode.Action(id = "n1", goal = "天气：{{context.weather.status}}", next = "n2"),
            ChainNode.End(id = "n2", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        repo.directInsertRun(makeRun(context = """{"weather":{"status":"rainy"}}"""))

        ChainEngine.advance("run-1", repo, deps)

        assertEquals(ChainRunStatus.COMPLETED, repo.peekRun("run-1")!!.status)
        assertEquals("天气：rainy", deps.runActionCalls[0].second)
    }

    @Test
    fun `Action节点goal无占位符_原样传递`() = runBlocking {
        val chain = nodesJson(
            ChainNode.Action(id = "n1", goal = "固定任务描述", next = "n2"),
            ChainNode.End(id = "n2", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        repo.directInsertRun(makeRun(context = "{}"))

        ChainEngine.advance("run-1", repo, deps)

        assertEquals(ChainRunStatus.COMPLETED, repo.peekRun("run-1")!!.status)
        assertEquals("固定任务描述", deps.runActionCalls[0].second)
    }

    // ─────────────────────────────────────────────────────────
    // §12.5.1(b)-4: 连续 Check → Check → Check → Action 递归推进不栈溢出
    // ─────────────────────────────────────────────────────────

    @Test
    fun `连续Check链_三个Check后Action_End_不栈溢出且context正确`() = runBlocking {
        // Check1(energy>0, onTrue=n2, onFalse=end_cancelled)
        // Check2(energy>10, onTrue=n3, onFalse=end_cancelled)
        // Check3(energy>20, onTrue=n4_action, onFalse=end_cancelled)
        // Action(goal, next=end_completed)
        val chain = nodesJson(
            ChainNode.Check(id = "n1", expression = "energy > 0", onTrue = "n2", onFalse = "n_end1"),
            ChainNode.Check(id = "n2", expression = "energy > 10", onTrue = "n3", onFalse = "n_end1"),
            ChainNode.Check(id = "n3", expression = "energy > 20", onTrue = "n4", onFalse = "n_end1"),
            ChainNode.Action(id = "n4", goal = "能量充足，执行任务", next = "n_end2"),
            ChainNode.End(id = "n_end1", outcome = ChainEndOutcome.CANCELLED),
            ChainNode.End(id = "n_end2", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        repo.directInsertRun(makeRun(context = """{"energy":25}"""))

        // 一次 advance 调用应连续跑完 Check → Check → Check → Action → End
        ChainEngine.advance("run-1", repo, deps)

        val run = repo.peekRun("run-1")!!
        assertEquals("应正常完成", ChainRunStatus.COMPLETED, run.status)
        assertEquals("visitCount 应为 5（3个Check + 1个Action + 1个End）", 5, run.visitCount)
        assertEquals("runAction 应被调用一次", 1, deps.runActionCalls.size)
        assertEquals("actionResult 应在 context 中", "任务完成", JSONObject(run.context).getString("actionResult"))
    }

    @Test
    fun `连续Check链_中间Check判false走onFalse到End`() = runBlocking {
        val chain = nodesJson(
            ChainNode.Check(id = "n1", expression = "energy > 0", onTrue = "n2", onFalse = "n_end1"),
            ChainNode.Check(id = "n2", expression = "energy > 100", onTrue = "n3", onFalse = "n_end1"),
            ChainNode.Check(id = "n3", expression = "energy > 200", onTrue = "n4", onFalse = "n_end1"),
            ChainNode.Action(id = "n4", goal = "任务", next = "n_end2"),
            ChainNode.End(id = "n_end1", outcome = ChainEndOutcome.CANCELLED),
            ChainNode.End(id = "n_end2", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        repo.directInsertRun(makeRun(context = """{"energy":25}"""))

        ChainEngine.advance("run-1", repo, deps)

        val run = repo.peekRun("run-1")!!
        assertEquals("应在第二个Check判false后走到 CANCELLED End", ChainEndOutcome.CANCELLED, run.status)
        assertEquals("visitCount 应为 3（2个Check + 1个End）", 3, run.visitCount)
        assertEquals("runAction 不应被调用", 0, deps.runActionCalls.size)
    }

    // ─────────────────────────────────────────────────────────
    // 额外覆盖：Action 执行失败
    // ─────────────────────────────────────────────────────────

    @Test
    fun `Action执行返回Failure_链条判FAILED`() = runBlocking {
        val chain = nodesJson(
            ChainNode.Action(id = "n1", goal = "会失败的任务", next = "n2"),
            ChainNode.End(id = "n2", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        repo.directInsertRun(makeRun(context = "{}"))

        deps.actionResult = ActionResult.Failure("LLM Provider 未配置")

        ChainEngine.advance("run-1", repo, deps)

        val run = repo.peekRun("run-1")!!
        assertEquals(ChainRunStatus.FAILED, run.status)
        val reason = JSONObject(run.context).getString("_failReason")
        assertTrue("失败原因应包含 LLM Provider 未配置", reason.contains("LLM Provider 未配置"))
    }

    // ─────────────────────────────────────────────────────────
    // 额外覆盖：deadlineAt 超时
    // ─────────────────────────────────────────────────────────

    @Test
    fun `deadlineAt已过期_直接markFailed`() = runBlocking {
        val chain = nodesJson(
            ChainNode.Action(id = "n1", goal = "任务", next = "n2"),
            ChainNode.End(id = "n2", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        // deadlineAt 设为过去时间
        repo.directInsertRun(makeRun(deadlineAt = now - 1000))

        ChainEngine.advance("run-1", repo, deps)

        val run = repo.peekRun("run-1")!!
        assertEquals(ChainRunStatus.FAILED, run.status)
        val reason = JSONObject(run.context).getString("_failReason")
        assertTrue("失败原因应提及总时长超限", reason.contains("总时长"))
    }

    // ─────────────────────────────────────────────────────────
    // 额外覆盖：非 RUNNING 状态被跳过
    // ─────────────────────────────────────────────────────────

    @Test
    fun `非RUNNING状态被advance跳过_不执行任何操作`() = runBlocking {
        val chain = nodesJson(
            ChainNode.Action(id = "n1", goal = "任务", next = "n2"),
            ChainNode.End(id = "n2", outcome = ChainEndOutcome.COMPLETED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        // 直接构造一个 COMPLETED 状态的 run
        repo.directInsertRun(makeRun().copy(status = ChainRunStatus.COMPLETED))

        ChainEngine.advance("run-1", repo, deps)

        // runAction 不应被调用
        assertEquals("非 RUNNING 状态应跳过", 0, deps.runActionCalls.size)
    }

    // ─────────────────────────────────────────────────────────
    // 额外覆盖：Check 节点带 checkToolName
    // ─────────────────────────────────────────────────────────

    @Test
    fun `Check节点带checkToolName_工具结果合并到context后求值`() = runBlocking {
        val chain = nodesJson(
            ChainNode.Check(
                id = "n1",
                expression = "weather == \"rainy\"",
                checkToolName = "weather",
                onTrue = "n2",
                onFalse = "n3",
            ),
            ChainNode.End(id = "n2", outcome = ChainEndOutcome.COMPLETED),
            ChainNode.End(id = "n3", outcome = ChainEndOutcome.CANCELLED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        repo.directInsertRun(makeRun(context = "{}"))

        // 模拟 checkTool 返回 weather=rainy
        deps.checkToolResults = mapOf("weather" to "rainy")

        ChainEngine.advance("run-1", repo, deps)

        val run = repo.peekRun("run-1")!!
        assertEquals("weather=rainy → Check 判 true → COMPLETED", ChainRunStatus.COMPLETED, run.status)
        assertEquals("runCheckTool 应被调用一次", 1, deps.runCheckToolCalls.size)
    }

    @Test
    fun `Check节点带checkToolName_工具返回值不满足条件_走onFalse`() = runBlocking {
        val chain = nodesJson(
            ChainNode.Check(
                id = "n1",
                expression = "weather == \"rainy\"",
                checkToolName = "weather",
                onTrue = "n2",
                onFalse = "n3",
            ),
            ChainNode.End(id = "n2", outcome = ChainEndOutcome.COMPLETED),
            ChainNode.End(id = "n3", outcome = ChainEndOutcome.CANCELLED),
        )
        repo.directInsertDefinition(makeDef(nodesJson = chain))
        repo.directInsertRun(makeRun(context = "{}"))

        deps.checkToolResults = mapOf("weather" to "sunny")

        ChainEngine.advance("run-1", repo, deps)

        assertEquals("weather=sunny → Check 判 false → CANCELLED", ChainEndOutcome.CANCELLED, repo.peekRun("run-1")!!.status)
    }

    // ─────────────────────────────────────────────────────────
    // resolvePlaceholders 纯函数测试
    // ─────────────────────────────────────────────────────────

    @Test
    fun `resolvePlaceholders_无占位符_原样返回`() {
        val result = ChainEngine.resolvePlaceholders("固定文本", """{"a":1}""")
        assertEquals("固定文本", result)
    }

    @Test
    fun `resolvePlaceholders_单个占位符_正确替换`() {
        val result = ChainEngine.resolvePlaceholders("值是 {{context.energy}}", """{"energy":30}""")
        assertEquals("值是 30", result)
    }

    @Test
    fun `resolvePlaceholders_多个占位符_全部替换`() {
        val result = ChainEngine.resolvePlaceholders(
            "{{context.name}} 的能量是 {{context.energy}}",
            """{"name":"小明","energy":50}""",
        )
        assertEquals("小明 的能量是 50", result)
    }

    @Test
    fun `resolvePlaceholders_点分路径_正确替换`() {
        val result = ChainEngine.resolvePlaceholders(
            "天气：{{context.weather.status}}",
            """{"weather":{"status":"rainy"}}""",
        )
        assertEquals("天气：rainy", result)
    }

    @Test
    fun `resolvePlaceholders_字段缺失_返回null`() {
        val result = ChainEngine.resolvePlaceholders("{{context.missing}}", """{"a":1}""")
        assertNull("缺失字段应返回 null", result)
    }

    @Test
    fun `resolvePlaceholders_点分路径中间缺失_返回null`() {
        val result = ChainEngine.resolvePlaceholders(
            "{{context.a.b.c}}",
            """{"a":{"x":1}}""",
        )
        assertNull("中间路径缺失应返回 null", result)
    }

    @Test
    fun `resolvePlaceholders_部分占位符缺失_整体返回null`() {
        val result = ChainEngine.resolvePlaceholders(
            "{{context.energy}} 和 {{context.missing}}",
            """{"energy":30}""",
        )
        assertNull("任一占位符缺失应整体返回 null", result)
    }

    @Test
    fun `resolvePlaceholders_context损坏且无占位符_原样返回`() {
        val result = ChainEngine.resolvePlaceholders("固定文本", "not json")
        assertEquals("context 损坏但无占位符时应原样返回", "固定文本", result)
    }

    @Test
    fun `resolvePlaceholders_context损坏且有占位符_返回null`() {
        val result = ChainEngine.resolvePlaceholders("{{context.x}}", "not json")
        assertNull("context 损坏且有占位符时应返回 null", result)
    }
}

// ─────────────────────────────────────────────────────────
// FakeChainEngineDeps：ChainEngine 外部依赖的测试桩
// ─────────────────────────────────────────────────────────

/**
 * [ChainEngineDeps] 的测试实现。
 *
 * - [scheduleResume]：记录调用参数，不做任何操作（测试手动调用 advance 模拟唤醒）
 * - [runAction]：返回预设的 [ActionResult]，记录调用参数供断言
 * - [runCheckTool]：返回预设的 Map，记录调用参数供断言
 */
class FakeChainEngineDeps : ChainEngineDeps {

    /** Wait 节点调用记录：List<Pair<runId, delayMs>> */
    val scheduleResumeCalls = mutableListOf<Pair<String, Long>>()

    /** Action 节点调用记录：List<Pair<characterId, resolvedGoal>> */
    val runActionCalls = mutableListOf<Pair<Int, String>>()

    /** Check 节点 checkToolName 调用记录：List<Pair<toolName, run>> */
    val runCheckToolCalls = mutableListOf<Pair<String, ChainRunEntity>>()

    /** Action 执行的预设返回值 */
    var actionResult: ActionResult = ActionResult.Success("任务完成")

    /** Check 工具的预设返回值 */
    var checkToolResults: Map<String, Any?> = emptyMap()

    override suspend fun scheduleResume(runId: String, delayMs: Long) {
        scheduleResumeCalls.add(runId to delayMs)
    }

    override suspend fun runAction(characterId: Int, resolvedGoal: String): ActionResult {
        runActionCalls.add(characterId to resolvedGoal)
        return actionResult
    }

    override suspend fun runCheckTool(run: ChainRunEntity, toolName: String): Map<String, Any?> {
        runCheckToolCalls.add(toolName to run)
        return checkToolResults
    }
}
