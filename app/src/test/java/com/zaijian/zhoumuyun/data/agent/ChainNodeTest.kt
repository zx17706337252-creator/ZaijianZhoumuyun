package com.zaijian.zhoumuyun.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ChainNode 密封类 + ChainNodeCodec 序列化/反序列化/校验的纯 JVM 单元测试。
 *
 * 不依赖 Android 环境、不依赖 Room、不依赖真机，仅依赖 org.json（项目已有依赖）
 * 和 JUnit 4。覆盖 §7 静态校验规则 #2（悬空引用）、#3（End 可达性）、#5（&&/|| 混用）。
 *
 * 对应《灵活自动化编排·改造设计方案》§12.5.1 中"能且必须提前用 JVM 单测验证的部分"，
 * 不过本测试属于 Step 1 范围（ChainNode 结构 + 序列化 + 校验），不是 §12.5.1(a) 的
 * ConditionEvaluator 测试（那是 Step 2）。
 */
class ChainNodeTest {

    // ─────────────────────────────────────────────────────────
    // 序列化 / 反序列化往返
    // ─────────────────────────────────────────────────────────

    @Test
    fun `序列化后反序列化应还原全部四种节点类型`() {
        val original = listOf(
            ChainNode.Wait("n1", 1_800_000),
            ChainNode.Check("n2", "mood.energy < 30", "weather", "n3", "n4"),
            ChainNode.Action("n3", "提醒用户休息", "n5"),
            ChainNode.End("n4", ChainEndOutcome.CANCELLED),
            ChainNode.End("n5", ChainEndOutcome.COMPLETED),
        )
        val json = ChainNodeCodec.serialize(original)
        val parsed = ChainNodeCodec.deserialize(json)
        assertEquals(original, parsed)
    }

    @Test
    fun `Check 节点 checkToolName 为 null 时序列化不应包含该字段`() {
        val nodes = listOf(
            ChainNode.Check("c1", "a==1", null, "e1", "e1"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val json = ChainNodeCodec.serialize(nodes)
        // checkToolName 不应出现在 JSON 中
        assertFalse("checkToolName 不应出现在序列化结果中", json.contains("checkToolName"))
        // 反序列化后 checkToolName 应为 null
        val parsed = ChainNodeCodec.deserialize(json)
        val check = parsed[0] as ChainNode.Check
        assertEquals(null, check.checkToolName)
    }

    @Test
    fun `Check 节点 checkToolName 非空时序列化应包含该字段且能还原`() {
        val nodes = listOf(
            ChainNode.Check("c1", "a==1", "weather", "e1", "e1"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val json = ChainNodeCodec.serialize(nodes)
        assertTrue("checkToolName 应出现在序列化结果中", json.contains("checkToolName"))
        val parsed = ChainNodeCodec.deserialize(json)
        val check = parsed[0] as ChainNode.Check
        assertEquals("weather", check.checkToolName)
    }

    @Test
    fun `反序列化应容忍 Wait 节点中多余的 next 字段`() {
        // §7 JSON 示例中 Wait 节点带了 next 字段，但 §3.3 Wait 类没有该字段。
        // 反序列化时应忽略多余字段，不报错。
        val json = """[
            {"type":"wait","id":"n1","durationMs":1800000,"next":"n2"},
            {"type":"end","id":"n2","outcome":"COMPLETED"}
        ]"""
        val parsed = ChainNodeCodec.deserialize(json)
        assertEquals(2, parsed.size)
        val wait = parsed[0] as ChainNode.Wait
        assertEquals("n1", wait.id)
        assertEquals(1_800_000, wait.durationMs)
    }

    @Test
    fun `反序列化非法 JSON 应抛出 IllegalArgumentException`() {
        try {
            ChainNodeCodec.deserialize("not a json")
            fail("应抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不是合法的 JSON"))
        }
    }

    @Test
    fun `反序列化空数组应抛出 IllegalArgumentException`() {
        try {
            ChainNodeCodec.deserialize("[]")
            fail("应抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不能为空"))
        }
    }

    @Test
    fun `反序列化未知 type 应抛出 IllegalArgumentException`() {
        val json = """[{"type":"unknown","id":"x"}]"""
        try {
            ChainNodeCodec.deserialize(json)
            fail("应抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("未知的节点 type"))
        }
    }

    // ─────────────────────────────────────────────────────────
    // §7 规则 #5：Check 节点 expression 不能同时含 && 和 ||
    // ─────────────────────────────────────────────────────────

    @Test
    fun `规则5_expression 同时含 AND 和 OR 应判非法`() {
        val nodes = listOf(
            ChainNode.Check("c1", "a==1 && b==2 || c==3", null, "e1", "e1"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertFalse("混用 && 和 || 应判非法", result.valid)
        assertTrue("错误信息应提及 && 和 ||", result.error!!.contains("&&"))
        assertTrue("错误信息应提及 && 和 ||", result.error!!.contains("||"))
    }

    @Test
    fun `规则5_expression 仅含 AND 应判合法`() {
        val nodes = listOf(
            ChainNode.Check("c1", "a==1 && b==2", null, "e1", "e1"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertTrue("仅含 && 应判合法: ${result.error}", result.valid)
    }

    @Test
    fun `规则5_expression 仅含 OR 应判合法`() {
        val nodes = listOf(
            ChainNode.Check("c1", "a==1 || b==2", null, "e1", "e1"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertTrue("仅含 || 应判合法: ${result.error}", result.valid)
    }

    @Test
    fun `规则5_expression 不含逻辑运算符应判合法`() {
        val nodes = listOf(
            ChainNode.Check("c1", "mood.energy < 30", null, "e1", "e1"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertTrue("不含逻辑运算符应判合法: ${result.error}", result.valid)
    }

    // ─────────────────────────────────────────────────────────
    // §7 规则 #2：所有 next/onTrue/onFalse 引用的 id 必须存在于数组内
    // ─────────────────────────────────────────────────────────

    @Test
    fun `规则2_Action 节点 next 引用不存在的 id 应判非法`() {
        val nodes = listOf(
            ChainNode.Action("a1", "goal", "nonexistent"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertFalse("next 引用不存在应判非法", result.valid)
        assertTrue(result.error!!.contains("nonexistent"))
    }

    @Test
    fun `规则2_Check 节点 onTrue 引用不存在的 id 应判非法`() {
        val nodes = listOf(
            ChainNode.Check("c1", "a==1", null, "nonexistent", "e1"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertFalse("onTrue 引用不存在应判非法", result.valid)
        assertTrue(result.error!!.contains("onTrue"))
    }

    @Test
    fun `规则2_Check 节点 onFalse 引用不存在的 id 应判非法`() {
        val nodes = listOf(
            ChainNode.Check("c1", "a==1", null, "e1", "nonexistent"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertFalse("onFalse 引用不存在应判非法", result.valid)
        assertTrue(result.error!!.contains("onFalse"))
    }

    @Test
    fun `规则2_所有引用合法时应判通过`() {
        val nodes = listOf(
            ChainNode.Wait("n1", 1000),
            ChainNode.Check("n2", "a==1", null, "n3", "n4"),
            ChainNode.Action("n3", "goal", "n4"),
            ChainNode.End("n4", ChainEndOutcome.COMPLETED),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertTrue("所有引用合法应判通过: ${result.error}", result.valid)
    }

    // ─────────────────────────────────────────────────────────
    // §7 规则 #3：从首节点沿引用遍历，至少能走到一个 End 节点
    // ─────────────────────────────────────────────────────────

    @Test
    fun `规则3_完整链条 Wait_Check_Action_End 应判可达`() {
        val nodes = listOf(
            ChainNode.Wait("n1", 1_800_000),               // index 0
            ChainNode.Check("n2", "mood.energy < 30", null, "n3", "n4"), // index 1
            ChainNode.Action("n3", "提醒用户休息", "n5"),     // index 2
            ChainNode.End("n4", ChainEndOutcome.CANCELLED),  // index 3
            ChainNode.End("n5", ChainEndOutcome.COMPLETED),  // index 4
        )
        val result = ChainNodeCodec.validate(nodes)
        assertTrue("完整链条应判可达: ${result.error}", result.valid)
    }

    @Test
    fun `规则3_轮询链 Check_onFalse_指回 Wait 应判可达`() {
        // §11.6 提到的合法轮询场景：每5分钟检查一次直到条件满足
        val nodes = listOf(
            ChainNode.Wait("n1", 300_000),                  // index 0
            ChainNode.Check("n2", "a==1", null, "n3", "n1"), // index 1, onFalse 回到 Wait
            ChainNode.End("n3", ChainEndOutcome.COMPLETED),  // index 2
        )
        val result = ChainNodeCodec.validate(nodes)
        assertTrue("轮询链通过 onTrue 可达 End 应判合法: ${result.error}", result.valid)
    }

    @Test
    fun `规则3_Check 自循环且无 End 应判不可达`() {
        val nodes = listOf(
            ChainNode.Check("n1", "a==1", null, "n1", "n1"), // 自循环，无 End
        )
        val result = ChainNodeCodec.validate(nodes)
        assertFalse("自循环无 End 应判非法", result.valid)
        assertTrue(result.error!!.contains("无法到达"))
    }

    @Test
    fun `规则3_Wait 后无节点且无 End 应判不可达`() {
        val nodes = listOf(
            ChainNode.Wait("n1", 1000), // Wait 后没有下一个节点
        )
        val result = ChainNodeCodec.validate(nodes)
        assertFalse("Wait 后无 End 应判非法", result.valid)
    }

    @Test
    fun `规则3_所有 Check 分支都指向自身互相循环应判不可达`() {
        val nodes = listOf(
            ChainNode.Check("n1", "a==1", null, "n2", "n2"),
            ChainNode.Check("n2", "b==1", null, "n1", "n1"),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertFalse("互相循环无 End 应判非法", result.valid)
    }

    // ─────────────────────────────────────────────────────────
    // parseAndValidate 便捷方法
    // ─────────────────────────────────────────────────────────

    @Test
    fun `parseAndValidate 合法 JSON 应返回 Success`() {
        val json = ChainNodeCodec.serialize(listOf(
            ChainNode.Wait("n1", 1000),
            ChainNode.End("n2", ChainEndOutcome.COMPLETED),
        ))
        val result = ChainNodeCodec.parseAndValidate(json)
        assertTrue(result is ChainParseResult.Success)
        val nodes = (result as ChainParseResult.Success).nodes
        assertEquals(2, nodes.size)
    }

    @Test
    fun `parseAndValidate 非法 JSON 应返回 Failure`() {
        val result = ChainNodeCodec.parseAndValidate("not json")
        assertTrue(result is ChainParseResult.Failure)
        assertTrue((result as ChainParseResult.Failure).error.contains("不是合法的 JSON"))
    }

    @Test
    fun `parseAndValidate 校验不通过应返回 Failure`() {
        val json = ChainNodeCodec.serialize(listOf(
            ChainNode.Check("c1", "a==1 && b==2 || c==3", null, "e1", "e1"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        ))
        val result = ChainNodeCodec.parseAndValidate(json)
        assertTrue(result is ChainParseResult.Failure)
        assertTrue((result as ChainParseResult.Failure).error.contains("&&"))
    }

    @Test
    fun `parseAndValidate 完整业务链条应返回 Success`() {
        // 模拟 §0 的完整场景："如果A发生了，过半小时检查B，然后决定要不要做C"
        val json = """[
            {"type":"wait","id":"n1","durationMs":1800000},
            {"type":"check","id":"n2","expression":"mood.energy < 30","checkToolName":"weather","onTrue":"n3","onFalse":"n4"},
            {"type":"action","id":"n3","goal":"提醒用户休息","next":"n5"},
            {"type":"end","id":"n4","outcome":"CANCELLED"},
            {"type":"end","id":"n5","outcome":"COMPLETED"}
        ]"""
        val result = ChainNodeCodec.parseAndValidate(json)
        assertTrue("完整业务链条应校验通过: ${(result as? ChainParseResult.Failure)?.error}", result is ChainParseResult.Success)
        val nodes = (result as ChainParseResult.Success).nodes
        assertEquals(5, nodes.size)
        // 验证各节点类型
        assertTrue(nodes[0] is ChainNode.Wait)
        assertTrue(nodes[1] is ChainNode.Check)
        assertTrue(nodes[2] is ChainNode.Action)
        assertTrue(nodes[3] is ChainNode.End)
        assertTrue(nodes[4] is ChainNode.End)
        // 验证 Check 节点的 checkToolName 正确还原
        val check = nodes[1] as ChainNode.Check
        assertEquals("weather", check.checkToolName)
    }
}
