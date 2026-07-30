package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainTriggerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 灵活自动化编排 · ChainCreateTool 单元测试（§12.5.1(a) 创建阶段校验）
 *
 * 使用 [FakeChainRunRepository]（内存态 HashMap，不连 Room），纯 JVM 环境，
 * 仅依赖 JUnit 4 + kotlinx-coroutines-core（runBlocking）。
 *
 * 覆盖 §7 五条创建阶段校验：
 *  - ① charId < 0 拒绝
 *  - ② nodesJson 反序列化失败拒绝
 *  - ③ 节点结构校验失败拒绝（悬空引用 / End 不可达 / && 与 || 混用）
 *  - ④ checkToolName 白名单校验
 *  - 正常创建：完整链条落库，返回 success
 *
 * 额外覆盖：
 *  - 缺少 nodes 参数
 *  - checkToolName 为 null（合法）
 *  - checkToolName 在白名单内（合法）
 *  - 落库实体字段完整性（triggerType / triggerEventName / nodesJson / enabled）
 */
class ChainCreateToolTest {

    private lateinit var repo: FakeChainRunRepository
    private lateinit var tool: ChainCreateTool

    /** 有效角色 ID，模拟 ChatViewModel 动态覆盖后的状态 */
    private val validCharId = 42

    @Before
    fun setup() {
        repo = FakeChainRunRepository()
        tool = ChainCreateTool(
            chainRunRepository = repo,
            characterId = { validCharId },
        )
    }

    // ─────────────────────────────────────────────────────────
    // ① charId < 0 拒绝
    // ─────────────────────────────────────────────────────────

    @Test
    fun `校验1_charId为负数应拒绝创建`() = runBlocking {
        val negativeTool = ChainCreateTool(
            chainRunRepository = repo,
            characterId = { -1 },
        )
        val result = negativeTool.execute(mapOf(
            "name" to "测试链",
            "trigger_event" to "mood_below_threshold",
            "nodes" to validNodesJson(),
        ))
        assertFalse("charId < 0 应拒绝", result.success)
        assertTrue("错误信息应提及角色未初始化", result.error!!.contains("角色未初始化"))
    }

    // ─────────────────────────────────────────────────────────
    // 缺少 nodes 参数
    // ─────────────────────────────────────────────────────────

    @Test
    fun `缺少nodes参数应拒绝`() = runBlocking {
        val result = tool.execute(mapOf(
            "name" to "测试链",
            "trigger_event" to "mood_below_threshold",
        ))
        assertFalse("缺少 nodes 应拒绝", result.success)
        assertTrue(result.error!!.contains("nodes"))
    }

    @Test
    fun `nodes参数为空白应拒绝`() = runBlocking {
        val result = tool.execute(mapOf(
            "name" to "测试链",
            "trigger_event" to "mood_below_threshold",
            "nodes" to "   ",
        ))
        assertFalse("空白 nodes 应拒绝", result.success)
        assertTrue(result.error!!.contains("nodes"))
    }

    // ─────────────────────────────────────────────────────────
    // ② nodesJson 反序列化失败
    // ─────────────────────────────────────────────────────────

    @Test
    fun `校验2_非法JSON应拒绝`() = runBlocking {
        val result = tool.execute(mapOf(
            "name" to "测试链",
            "trigger_event" to "mood_below_threshold",
            "nodes" to "not a json",
        ))
        assertFalse("非法 JSON 应拒绝", result.success)
        assertTrue(result.error!!.contains("不是合法的 JSON"))
    }

    @Test
    fun `校验2_空数组应拒绝`() = runBlocking {
        val result = tool.execute(mapOf(
            "name" to "测试链",
            "trigger_event" to "mood_below_threshold",
            "nodes" to "[]",
        ))
        assertFalse("空数组应拒绝", result.success)
        assertTrue(result.error!!.contains("不能为空"))
    }

    // ─────────────────────────────────────────────────────────
    // ③ 节点结构校验失败
    // ─────────────────────────────────────────────────────────

    @Test
    fun `校验3_Action节点next引用不存在的id应拒绝`() = runBlocking {
        val json = """[
            {"type":"action","id":"a1","goal":"做某事","next":"nonexistent"},
            {"type":"end","id":"e1","outcome":"COMPLETED"}
        ]"""
        val result = tool.execute(mapOf(
            "name" to "测试链",
            "trigger_event" to "some_event",
            "nodes" to json,
        ))
        assertFalse("悬空引用应拒绝", result.success)
        assertTrue(result.error!!.contains("nonexistent"))
    }

    @Test
    fun `校验3_无End可达应拒绝`() = runBlocking {
        val json = """[
            {"type":"check","id":"c1","expression":"a==1","onTrue":"c1","onFalse":"c1"}
        ]"""
        val result = tool.execute(mapOf(
            "name" to "测试链",
            "trigger_event" to "some_event",
            "nodes" to json,
        ))
        assertFalse("无 End 可达应拒绝", result.success)
        assertTrue(result.error!!.contains("无法到达"))
    }

    @Test
    fun `校验3_expression混用AND和OR应拒绝`() = runBlocking {
        val json = """[
            {"type":"check","id":"c1","expression":"a==1 && b==2 || c==3","onTrue":"e1","onFalse":"e1"},
            {"type":"end","id":"e1","outcome":"COMPLETED"}
        ]"""
        val result = tool.execute(mapOf(
            "name" to "测试链",
            "trigger_event" to "some_event",
            "nodes" to json,
        ))
        assertFalse("&& 和 || 混用应拒绝", result.success)
        assertTrue(result.error!!.contains("&&"))
    }

    // ─────────────────────────────────────────────────────────
    // ④ checkToolName 白名单校验
    // ─────────────────────────────────────────────────────────

    @Test
    fun `校验4_checkToolName不在白名单应拒绝`() = runBlocking {
        val json = """[
            {"type":"check","id":"c1","expression":"a==1","checkToolName":"dangerous_tool","onTrue":"e1","onFalse":"e1"},
            {"type":"end","id":"e1","outcome":"COMPLETED"}
        ]"""
        val result = tool.execute(mapOf(
            "name" to "测试链",
            "trigger_event" to "some_event",
            "nodes" to json,
        ))
        assertFalse("非白名单 checkToolName 应拒绝", result.success)
        assertTrue(result.error!!.contains("dangerous_tool"))
        assertTrue(result.error!!.contains("白名单"))
    }

    @Test
    fun `校验4_checkToolName在白名单内应通过`() = runBlocking {
        // "weather" 在 SAFE_TOOL_NAMES 白名单内
        val json = """[
            {"type":"wait","id":"n1","durationMs":1800000},
            {"type":"check","id":"n2","expression":"mood.energy < 30","checkToolName":"weather","onTrue":"n3","onFalse":"n4"},
            {"type":"action","id":"n3","goal":"提醒用户休息","next":"n5"},
            {"type":"end","id":"n4","outcome":"CANCELLED"},
            {"type":"end","id":"n5","outcome":"COMPLETED"}
        ]"""
        val result = tool.execute(mapOf(
            "name" to "过半小时检查心情",
            "trigger_event" to "mood_below_threshold",
            "nodes" to json,
        ))
        assertTrue("白名单内 checkToolName 应通过: ${result.error}", result.success)
    }

    @Test
    fun `校验4_checkToolName为null应通过`() = runBlocking {
        // Check 节点不带 checkToolName 是合法的（纯表达式求值，不调外部工具）
        val json = """[
            {"type":"check","id":"c1","expression":"a==1","onTrue":"e1","onFalse":"e1"},
            {"type":"end","id":"e1","outcome":"COMPLETED"}
        ]"""
        val result = tool.execute(mapOf(
            "name" to "纯表达式检查",
            "trigger_event" to "some_event",
            "nodes" to json,
        ))
        assertTrue("checkToolName 为 null 应通过: ${result.error}", result.success)
    }

    @Test
    fun `校验4_多个Check节点中有一个checkToolName不在白名单应拒绝`() = runBlocking {
        val json = """[
            {"type":"check","id":"c1","expression":"a==1","checkToolName":"weather","onTrue":"c2","onFalse":"e1"},
            {"type":"check","id":"c2","expression":"b==2","checkToolName":"evil_tool","onTrue":"e2","onFalse":"e1"},
            {"type":"end","id":"e1","outcome":"CANCELLED"},
            {"type":"end","id":"e2","outcome":"COMPLETED"}
        ]"""
        val result = tool.execute(mapOf(
            "name" to "多检查节点",
            "trigger_event" to "some_event",
            "nodes" to json,
        ))
        assertFalse("任一 Check 节点 checkToolName 不在白名单应拒绝", result.success)
        assertTrue(result.error!!.contains("evil_tool"))
    }

    // ─────────────────────────────────────────────────────────
    // 正常创建：完整链条落库
    // ─────────────────────────────────────────────────────────

    @Test
    fun `正常创建_完整链条应落库并返回成功`() = runBlocking {
        val json = validNodesJson()
        val result = tool.execute(mapOf(
            "name" to "过半小时检查心情",
            "trigger_event" to "mood_below_threshold",
            "nodes" to json,
        ))
        assertTrue("完整链条应创建成功: ${result.error}", result.success)
        assertEquals("chain_create", result.toolName)
        assertTrue("返回内容应包含链条名称", result.content.contains("过半小时检查心情"))

        // 验证落库
        val allDefs = getAllDefinitions()
        assertEquals("应恰好创建一条定义", 1, allDefs.size)
        val def = allDefs.first()
        assertEquals(validCharId, def.characterId)
        assertEquals("过半小时检查心情", def.name)
        assertEquals(ChainTriggerType.EVENT, def.triggerType)
        assertEquals("mood_below_threshold", def.triggerEventName)
        assertEquals(json, def.nodesJson)
        assertTrue(def.enabled)
        assertNotNull(def.id)
        assertTrue(def.createdAt > 0)
    }

    @Test
    fun `正常创建_轮询链条应落库成功`() = runBlocking {
        // §11.6 合法轮询场景：每5分钟检查一次直到条件满足
        val json = """[
            {"type":"wait","id":"n1","durationMs":300000},
            {"type":"check","id":"n2","expression":"a==1","onTrue":"n3","onFalse":"n1"},
            {"type":"end","id":"n3","outcome":"COMPLETED"}
        ]"""
        val result = tool.execute(mapOf(
            "name" to "轮询检查链",
            "trigger_event" to "polling_event",
            "nodes" to json,
        ))
        assertTrue("轮询链条应创建成功: ${result.error}", result.success)

        val def = getAllDefinitions().first()
        assertEquals("轮询检查链", def.name)
    }

    @Test
    fun `正常创建_不带trigger_event应落库成功`() = runBlocking {
        // trigger_event 可选（triggerType=EVENT 但 triggerEventName 可为 null）
        val json = """[
            {"type":"check","id":"c1","expression":"a==1","onTrue":"e1","onFalse":"e1"},
            {"type":"end","id":"e1","outcome":"COMPLETED"}
        ]"""
        val result = tool.execute(mapOf(
            "name" to "无事件名链",
            "nodes" to json,
        ))
        assertTrue("不带 trigger_event 应创建成功: ${result.error}", result.success)

        val def = getAllDefinitions().first()
        // triggerEventName 可能为 null 或空字符串（取决于 params 取值）
        // 关键是创建成功，不因缺少 trigger_event 而失败
    }

    @Test
    fun `正常创建_多次创建应生成不同id`() = runBlocking {
        val json = validNodesJson()
        // 第一次创建
        tool.execute(mapOf(
            "name" to "链条A",
            "trigger_event" to "event_a",
            "nodes" to json,
        ))
        // 第二次创建
        tool.execute(mapOf(
            "name" to "链条B",
            "trigger_event" to "event_b",
            "nodes" to json,
        ))
        val allDefs = getAllDefinitions()
        assertEquals("应创建两条定义", 2, allDefs.size)
        val ids = allDefs.map { it.id }.toSet()
        assertEquals("两条定义 id 应不同", 2, ids.size)
    }

    // ─────────────────────────────────────────────────────────
    // 辅助方法
    // ─────────────────────────────────────────────────────────

    /** 返回一个合法的完整业务链条 JSON（Wait → Check → Action → End×2） */
    private fun validNodesJson(): String = """[
        {"type":"wait","id":"n1","durationMs":1800000},
        {"type":"check","id":"n2","expression":"mood.energy < 30","checkToolName":"weather","onTrue":"n3","onFalse":"n4"},
        {"type":"action","id":"n3","goal":"提醒用户休息","next":"n5"},
        {"type":"end","id":"n4","outcome":"CANCELLED"},
        {"type":"end","id":"n5","outcome":"COMPLETED"}
    ]"""

    /** 从 FakeChainRunRepository 读取所有已创建的链条定义 */
    private fun getAllDefinitions(): List<ChainDefinitionEntity> {
        // FakeChainRunRepository 内部用 ConcurrentHashMap 存储，
        // 没有公开的"列出全部"方法，但 peekRun/directInsertDefinition 是测试辅助。
        // 此处通过反射访问 definitions 字段获取全部定义。
        val field = FakeChainRunRepository::class.java
            .getDeclaredField("definitions")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(repo) as java.util.Map<String, ChainDefinitionEntity>
        return map.values.toList()
    }
}
