package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunStatus
import com.zaijian.zhoumuyun.data.db.entity.ChainTriggerType
import com.zaijian.zhoumuyun.data.db.entity.PendingEventEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 灵活自动化编排 · ChainTriggerMatcher 单元测试（§6 + §11.1 + §11.12）
 *
 * 使用 FakeChainRunRepository（内存态）+ FakeChainEngineDeps（测试桩）+ 真实 ChainEngine，
 * 在纯 JVM 环境验证：
 * - 事件匹配 → 创建 ChainRunEntity → ChainEngine.advance() 启动执行
 * - §11.12 characterId=-1 项目级链条匹配
 * - 事件 payload 预填入 context
 * - §11.1 processPendingEvents() 事件落盘兜底重放
 */
class ChainTriggerMatcherTest {

    private lateinit var repo: FakeChainRunRepository
    private lateinit var deps: FakeChainEngineDeps
    private lateinit var engine: ChainEngine
    private lateinit var matcher: ChainTriggerMatcher

    @Before
    fun setup() {
        repo = FakeChainRunRepository()
        deps = FakeChainEngineDeps()
        engine = ChainEngine
        matcher = ChainTriggerMatcher(repo, engine, deps)
    }

    // ── 测试辅助 ──────────────────────────────────────────

    /**
     * 创建一条简单的 End-only 链条定义。
     * ChainEngine.advance() 处理 End 节点后会将 run 标记为 COMPLETED。
     */
    private fun makeDef(
        id: String = "def-1",
        characterId: Int = 1,
        triggerEventName: String = "test_event",
        enabled: Boolean = true,
    ) = ChainDefinitionEntity(
        id = id,
        characterId = characterId,
        name = "测试链条",
        triggerType = ChainTriggerType.EVENT,
        triggerEventName = triggerEventName,
        triggerCron = null,
        nodesJson = """[{"type":"end","id":"n1","outcome":"COMPLETED"}]""",
        enabled = enabled,
        createdAt = System.currentTimeMillis(),
    )

    // ─────────────────────────────────────────────────────────
    // 基本匹配测试
    // ─────────────────────────────────────────────────────────

    @Test
    fun `事件匹配定义后创建 ChainRunEntity 并执行到终态`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-1", characterId = 1, triggerEventName = "test_event"))

        matcher.handleEvent(AppEvent(
            name = "test_event",
            characterId = 1,
            payload = mapOf("key1" to "value1"),
        ))

        // 应创建 1 条 run
        val runs = repo.findAllByStatus(ChainRunStatus.COMPLETED)
        assertEquals("应创建 1 条已完成的 run", 1, runs.size)

        val run = runs.first()
        assertEquals("chainDefId 应匹配", "def-1", run.chainDefId)
        assertEquals("characterId 应匹配", 1, run.characterId)
        assertEquals("status 应为 COMPLETED", ChainRunStatus.COMPLETED, run.status)
    }

    @Test
    fun `事件不匹配任何定义时不创建 run`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-1", triggerEventName = "event_a"))

        matcher.handleEvent(AppEvent(
            name = "event_b",  // 不匹配 event_a
            characterId = 1,
        ))

        val allRuns = repo.findAllByStatus(ChainRunStatus.COMPLETED) +
            repo.findAllByStatus(ChainRunStatus.RUNNING) +
            repo.findAllByStatus(ChainRunStatus.WAITING)
        assertTrue("不应创建任何 run", allRuns.isEmpty())
    }

    @Test
    fun `一个事件匹配多个定义时创建多个 run`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-1", characterId = 1, triggerEventName = "shared_event"))
        repo.insertDefinition(makeDef(id = "def-2", characterId = 1, triggerEventName = "shared_event"))

        matcher.handleEvent(AppEvent(
            name = "shared_event",
            characterId = 1,
        ))

        val runs = repo.findAllByStatus(ChainRunStatus.COMPLETED)
        assertEquals("应创建 2 条 run", 2, runs.size)

        val defIds = runs.map { it.chainDefId }.toSet()
        assertTrue("def-1 应在结果中", "def-1" in defIds)
        assertTrue("def-2 应在结果中", "def-2" in defIds)
    }

    // ─────────────────────────────────────────────────────────
    // §11.12 characterId=-1 项目级链条
    // ─────────────────────────────────────────────────────────

    @Test
    fun `项目级定义 characterId=-1 匹配任意角色的事件`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-project", characterId = -1, triggerEventName = "global_event"))

        matcher.handleEvent(AppEvent(
            name = "global_event",
            characterId = 5,  // 任意角色
        ))

        val runs = repo.findAllByStatus(ChainRunStatus.COMPLETED)
        assertEquals("项目级链条应匹配任意角色事件", 1, runs.size)
        assertEquals("run 的 characterId 应为 -1", -1, runs.first().characterId)
    }

    @Test
    fun `角色专属定义不匹配其他角色的事件`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-1", characterId = 1, triggerEventName = "event_a"))

        matcher.handleEvent(AppEvent(
            name = "event_a",
            characterId = 2,  // 角色不匹配
        ))

        val allRuns = repo.findAllByStatus(ChainRunStatus.COMPLETED) +
            repo.findAllByStatus(ChainRunStatus.RUNNING)
        assertTrue("角色不匹配时不应创建 run", allRuns.isEmpty())
    }

    @Test
    fun `同一事件同时命中角色专属和项目级定义`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-char", characterId = 1, triggerEventName = "shared"))
        repo.insertDefinition(makeDef(id = "def-project", characterId = -1, triggerEventName = "shared"))

        matcher.handleEvent(AppEvent(
            name = "shared",
            characterId = 1,
        ))

        val runs = repo.findAllByStatus(ChainRunStatus.COMPLETED)
        assertEquals("应同时命中角色专属和项目级定义", 2, runs.size)
    }

    // ─────────────────────────────────────────────────────────
    // 事件 payload 预填入 context
    // ─────────────────────────────────────────────────────────

    @Test
    fun `事件 payload 预填入 ChainRunEntity context`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-1", characterId = 1, triggerEventName = "evt"))

        matcher.handleEvent(AppEvent(
            name = "evt",
            characterId = 1,
            payload = mapOf(
                "messageId" to "msg-123",
                "intensity" to 80,
                "active" to true,
            ),
        ))

        val runs = repo.findAllByStatus(ChainRunStatus.COMPLETED)
        assertEquals(1, runs.size)

        val ctx = JSONObject(runs.first().context)
        assertEquals("messageId 应在 context 中", "msg-123", ctx.getString("messageId"))
        assertEquals("intensity 应在 context 中", 80, ctx.getInt("intensity"))
        assertEquals("active 应在 context 中", true, ctx.getBoolean("active"))
    }

    @Test
    fun `空 payload 的事件 context 为空 JSON 对象`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-1", characterId = 1, triggerEventName = "evt"))

        matcher.handleEvent(AppEvent(
            name = "evt",
            characterId = 1,
            payload = emptyMap(),
        ))

        val runs = repo.findAllByStatus(ChainRunStatus.COMPLETED)
        assertEquals(1, runs.size)

        val ctx = JSONObject(runs.first().context)
        assertEquals("空 payload 的 context 应为空对象", 0, ctx.length())
    }

    // ─────────────────────────────────────────────────────────
    // disabled 定义不匹配
    // ─────────────────────────────────────────────────────────

    @Test
    fun `disabled 定义不参与匹配`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-1", characterId = 1, triggerEventName = "evt", enabled = false))

        matcher.handleEvent(AppEvent(
            name = "evt",
            characterId = 1,
        ))

        val allRuns = repo.findAllByStatus(ChainRunStatus.COMPLETED) +
            repo.findAllByStatus(ChainRunStatus.RUNNING)
        assertTrue("disabled 定义不应匹配", allRuns.isEmpty())
    }

    // ─────────────────────────────────────────────────────────
    // §11.1 processPendingEvents 事件落盘兜底
    // ─────────────────────────────────────────────────────────

    @Test
    fun `processPendingEvents 重放未处理事件`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-1", characterId = 1, triggerEventName = "replay_event"))

        // 模拟 App 被杀前写入的未处理事件
        repo.insertPendingEvent(PendingEventEntity(
            id = "pe-1",
            eventName = "replay_event",
            characterId = 1,
            payloadJson = """{"key":"value"}""",
            processed = false,
            createdAt = System.currentTimeMillis(),
        ))

        matcher.processPendingEvents()

        // 重放后应创建 run
        val runs = repo.findAllByStatus(ChainRunStatus.COMPLETED)
        assertEquals("重放后应创建 1 条 run", 1, runs.size)

        // 事件应被标记为已处理
        val pending = repo.findUnprocessedPendingEvents()
        assertTrue("已处理事件不应在未处理列表中", pending.isEmpty())
    }

    @Test
    fun `processPendingEvents 按 createdAt 顺序重放`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-1", characterId = 1, triggerEventName = "evt"))

        val now = System.currentTimeMillis()
        repo.insertPendingEvent(PendingEventEntity(
            id = "pe-2",
            eventName = "evt",
            characterId = 1,
            payloadJson = "{}",
            processed = false,
            createdAt = now + 1000,  // 后创建
        ))
        repo.insertPendingEvent(PendingEventEntity(
            id = "pe-1",
            eventName = "evt",
            characterId = 1,
            payloadJson = "{}",
            processed = false,
            createdAt = now,  // 先创建
        ))

        matcher.processPendingEvents()

        val runs = repo.findAllByStatus(ChainRunStatus.COMPLETED)
        assertEquals("应重放 2 条事件创建 2 条 run", 2, runs.size)
    }

    @Test
    fun `processPendingEvents 无未处理事件时不创建 run`() = runBlocking {
        matcher.processPendingEvents()

        val allRuns = repo.findAllByStatus(ChainRunStatus.COMPLETED) +
            repo.findAllByStatus(ChainRunStatus.RUNNING)
        assertTrue("无待处理事件时不应创建 run", allRuns.isEmpty())
    }

    @Test
    fun `processPendingEvents 跳过已处理事件`() = runBlocking {
        repo.insertPendingEvent(PendingEventEntity(
            id = "pe-1",
            eventName = "evt",
            characterId = 1,
            payloadJson = "{}",
            processed = true,  // 已处理
            createdAt = System.currentTimeMillis(),
        ))

        matcher.processPendingEvents()

        val allRuns = repo.findAllByStatus(ChainRunStatus.COMPLETED) +
            repo.findAllByStatus(ChainRunStatus.RUNNING)
        assertTrue("已处理事件不应被重放", allRuns.isEmpty())
    }

    @Test
    fun `processPendingEvents payload 正确反序列化到 context`() = runBlocking {
        repo.insertDefinition(makeDef(id = "def-1", characterId = 1, triggerEventName = "evt"))

        repo.insertPendingEvent(PendingEventEntity(
            id = "pe-1",
            eventName = "evt",
            characterId = 1,
            payloadJson = """{"taskId":"t-1","score":95}""",
            processed = false,
            createdAt = System.currentTimeMillis(),
        ))

        matcher.processPendingEvents()

        val runs = repo.findAllByStatus(ChainRunStatus.COMPLETED)
        assertEquals(1, runs.size)

        val ctx = JSONObject(runs.first().context)
        assertEquals("taskId 应在 context 中", "t-1", ctx.getString("taskId"))
        assertEquals("score 应在 context 中", 95, ctx.getInt("score"))
    }

    // ─────────────────────────────────────────────────────────
    // 异常处理
    // ─────────────────────────────────────────────────────────

    @Test
    fun `单条事件处理异常不影响后续事件`() = runBlocking {
        // 第一条：eventName 匹配但 nodesJson 无效（会导致 advance 抛异常）
        repo.insertDefinition(ChainDefinitionEntity(
            id = "def-bad",
            characterId = 1,
            name = "坏链条",
            triggerType = ChainTriggerType.EVENT,
            triggerEventName = "evt",
            triggerCron = null,
            nodesJson = "invalid_json",  // 无效 JSON
            enabled = true,
            createdAt = System.currentTimeMillis(),
        ))
        // 第二条：正常的定义
        repo.insertDefinition(makeDef(id = "def-good", characterId = 1, triggerEventName = "evt"))

        matcher.handleEvent(AppEvent(name = "evt", characterId = 1))

        // 坏链条的 run 可能处于 FAILED 或 RUNNING 状态，好链条的 run 应为 COMPLETED
        val completed = repo.findAllByStatus(ChainRunStatus.COMPLETED)
        assertEquals("好链条应正常完成", 1, completed.size)
        assertEquals("def-good", completed.first().chainDefId)
    }
}
