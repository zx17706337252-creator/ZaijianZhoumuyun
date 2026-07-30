package com.zaijian.zhoumuyun.data.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConditionEvaluator 纯 JVM 单元测试（§12.5.1(a)）。
 *
 * 不依赖 Android 环境、不依赖 Room、不依赖真机，仅依赖 org.json + JUnit 4。
 *
 * 覆盖要求（§12.5.1(a) 逐条对照）：
 * - 单一比较（==/!=/</>/<=/>=）× 数字/字符串/布尔三种字面量类型的正常匹配
 * - 字段路径缺失（context 里没有 mood 或没有 energy）—— 判 false 且不抛异常
 * - 类型不匹配（字段实际是字符串但表达式按数字比较）—— 判 false 且不抛异常
 * - &&/|| 混用 —— 两处都要测：
 *   1) ChainCreateTool 静态校验（§7 规则 #5，通过 ChainNodeCodec.validate 测试）
 *   2) ConditionEvaluator.evaluate() 自身防御性兜底（判 false + 不抛异常）
 *
 * 语义空白处理方式（§12.5.3 要求显式写明）：
 * - "类型不匹配"：统一判 false + 记录日志（含期望类型/实际类型），不抛异常。
 *   理由：取不到值或类型对不上，本质上都是"条件目前不成立"的表现（§4 正文定死）。
 * - "&&/|| 混用"：两道防线。第一道：ChainCreateTool 创建阶段静态校验拦截（§7 规则 #5）；
 *   第二道：ConditionEvaluator.evaluate() 遇到混用输入判 false + 记录日志，不抛异常（防御性兜底）。
 */
class ConditionEvaluatorTest {

    // ─────────────────────────────────────────────────────────
    // §12.5.1(a) 单一比较 × 三种字面量类型的正常匹配
    // ─────────────────────────────────────────────────────────

    // ── 数字字面量 × 六种比较符 ──────────────────────────────

    @Test
    fun `数字_==_匹配时返回 true`() {
        val ctx = JSONObject().apply { put("energy", 30) }
        assertTrue(ConditionEvaluator.evaluate("energy == 30", ctx))
    }

    @Test
    fun `数字_==_不匹配时返回 false`() {
        val ctx = JSONObject().apply { put("energy", 50) }
        assertFalse(ConditionEvaluator.evaluate("energy == 30", ctx))
    }

    @Test
    fun `数字_!=_匹配时返回 true`() {
        val ctx = JSONObject().apply { put("energy", 50) }
        assertTrue(ConditionEvaluator.evaluate("energy != 30", ctx))
    }

    @Test
    fun `数字_!=_不匹配时返回 false`() {
        val ctx = JSONObject().apply { put("energy", 30) }
        assertFalse(ConditionEvaluator.evaluate("energy != 30", ctx))
    }

    @Test
    fun `数字_LT_小于时返回 true`() {
        val ctx = JSONObject().apply { put("energy", 20) }
        assertTrue(ConditionEvaluator.evaluate("energy < 30", ctx))
    }

    @Test
    fun `数字_LT_等于时返回 false`() {
        val ctx = JSONObject().apply { put("energy", 30) }
        assertFalse(ConditionEvaluator.evaluate("energy < 30", ctx))
    }

    @Test
    fun `数字_GT_大于时返回 true`() {
        val ctx = JSONObject().apply { put("energy", 50) }
        assertTrue(ConditionEvaluator.evaluate("energy > 30", ctx))
    }

    @Test
    fun `数字_GT_等于时返回 false`() {
        val ctx = JSONObject().apply { put("energy", 30) }
        assertFalse(ConditionEvaluator.evaluate("energy > 30", ctx))
    }

    @Test
    fun `数字_LE_等于时返回 true`() {
        val ctx = JSONObject().apply { put("energy", 30) }
        assertTrue(ConditionEvaluator.evaluate("energy <= 30", ctx))
    }

    @Test
    fun `数字_LE_大于时返回 false`() {
        val ctx = JSONObject().apply { put("energy", 50) }
        assertFalse(ConditionEvaluator.evaluate("energy <= 30", ctx))
    }

    @Test
    fun `数字_GE_等于时返回 true`() {
        val ctx = JSONObject().apply { put("energy", 30) }
        assertTrue(ConditionEvaluator.evaluate("energy >= 30", ctx))
    }

    @Test
    fun `数字_GE_小于时返回 false`() {
        val ctx = JSONObject().apply { put("energy", 20) }
        assertFalse(ConditionEvaluator.evaluate("energy >= 30", ctx))
    }

    @Test
    fun `数字_Long类型_==_匹配时返回 true`() {
        val ctx = JSONObject().apply { put("minutesAgo", 1800L) }
        assertTrue(ConditionEvaluator.evaluate("minutesAgo == 1800", ctx))
    }

    @Test
    fun `数字_小数字面量_匹配时返回 true`() {
        val ctx = JSONObject().apply { put("ratio", 0.85) }
        assertTrue(ConditionEvaluator.evaluate("ratio > 0.8", ctx))
    }

    @Test
    fun `数字_小数字面量_不匹配时返回 false`() {
        val ctx = JSONObject().apply { put("ratio", 0.5) }
        assertFalse(ConditionEvaluator.evaluate("ratio > 0.8", ctx))
    }

    // ── 字符串字面量 × ==和!= ────────────────────────────────

    @Test
    fun `字符串_==_匹配时返回 true`() {
        val ctx = JSONObject().apply { put("weather", "rainy") }
        assertTrue(ConditionEvaluator.evaluate("""weather == "rainy" """, ctx))
    }

    @Test
    fun `字符串_==_不匹配时返回 false`() {
        val ctx = JSONObject().apply { put("weather", "sunny") }
        assertFalse(ConditionEvaluator.evaluate("""weather == "rainy" """, ctx))
    }

    @Test
    fun `字符串_!=_匹配时返回 true`() {
        val ctx = JSONObject().apply { put("weather", "sunny") }
        assertTrue(ConditionEvaluator.evaluate("""weather != "rainy" """, ctx))
    }

    @Test
    fun `字符串_!=_不匹配时返回 false`() {
        val ctx = JSONObject().apply { put("weather", "rainy") }
        assertFalse(ConditionEvaluator.evaluate("""weather != "rainy" """, ctx))
    }

    // ── 布尔字面量 × ==和!= ──────────────────────────────────

    @Test
    fun `布尔_==_true_匹配时返回 true`() {
        val ctx = JSONObject().apply { put("isReady", true) }
        assertTrue(ConditionEvaluator.evaluate("isReady == true", ctx))
    }

    @Test
    fun `布尔_==_false_匹配时返回 true`() {
        val ctx = JSONObject().apply { put("isReady", false) }
        assertTrue(ConditionEvaluator.evaluate("isReady == false", ctx))
    }

    @Test
    fun `布尔_==_true_不匹配时返回 false`() {
        val ctx = JSONObject().apply { put("isReady", false) }
        assertFalse(ConditionEvaluator.evaluate("isReady == true", ctx))
    }

    @Test
    fun `布尔_!=_匹配时返回 true`() {
        val ctx = JSONObject().apply { put("isReady", false) }
        assertTrue(ConditionEvaluator.evaluate("isReady != true", ctx))
    }

    @Test
    fun `布尔_!=_不匹配时返回 false`() {
        val ctx = JSONObject().apply { put("isReady", true) }
        assertFalse(ConditionEvaluator.evaluate("isReady != true", ctx))
    }

    // ─────────────────────────────────────────────────────────
    // §12.5.1(a) 点分路径（嵌套字段）
    // ─────────────────────────────────────────────────────────

    @Test
    fun `点分路径_mood_dot_energy_取到嵌套值`() {
        val ctx = JSONObject().apply {
            put("mood", JSONObject().apply { put("energy", 25) })
        }
        assertTrue(ConditionEvaluator.evaluate("mood.energy < 30", ctx))
    }

    @Test
    fun `点分路径_三层嵌套_取到值`() {
        val ctx = JSONObject().apply {
            put("a", JSONObject().apply {
                put("b", JSONObject().apply { put("c", 42) })
            })
        }
        assertTrue(ConditionEvaluator.evaluate("a.b.c == 42", ctx))
    }

    // ─────────────────────────────────────────────────────────
    // §12.5.1(a) && 组合
    // ─────────────────────────────────────────────────────────

    @Test
    fun `AND_两个子表达式都为true时返回true`() {
        val ctx = JSONObject().apply {
            put("energy", 20)
            put("weather", "rainy")
        }
        assertTrue(ConditionEvaluator.evaluate("""energy < 30 && weather == "rainy" """, ctx))
    }

    @Test
    fun `AND_一个子表达式为false时返回false`() {
        val ctx = JSONObject().apply {
            put("energy", 50)
            put("weather", "rainy")
        }
        assertFalse(ConditionEvaluator.evaluate("""energy < 30 && weather == "rainy" """, ctx))
    }

    @Test
    fun `AND_两个子表达式都为false时返回false`() {
        val ctx = JSONObject().apply {
            put("energy", 50)
            put("weather", "sunny")
        }
        assertFalse(ConditionEvaluator.evaluate("""energy < 30 && weather == "rainy" """, ctx))
    }

    @Test
    fun `AND_三个子表达式全为true时返回true`() {
        val ctx = JSONObject().apply {
            put("a", 1)
            put("b", 2)
            put("c", 3)
        }
        assertTrue(ConditionEvaluator.evaluate("a == 1 && b == 2 && c == 3", ctx))
    }

    // ─────────────────────────────────────────────────────────
    // §12.5.1(a) || 组合
    // ─────────────────────────────────────────────────────────

    @Test
    fun `OR_第一个子表达式为true时返回true`() {
        val ctx = JSONObject().apply {
            put("energy", 20)
            put("weather", "sunny")
        }
        assertTrue(ConditionEvaluator.evaluate("""energy < 30 || weather == "rainy" """, ctx))
    }

    @Test
    fun `OR_第二个子表达式为true时返回true`() {
        val ctx = JSONObject().apply {
            put("energy", 50)
            put("weather", "rainy")
        }
        assertTrue(ConditionEvaluator.evaluate("""energy < 30 || weather == "rainy" """, ctx))
    }

    @Test
    fun `OR_两个子表达式都为false时返回false`() {
        val ctx = JSONObject().apply {
            put("energy", 50)
            put("weather", "sunny")
        }
        assertFalse(ConditionEvaluator.evaluate("""energy < 30 || weather == "rainy" """, ctx))
    }

    // ─────────────────────────────────────────────────────────
    // §12.5.1(a) 字段路径缺失 —— 判 false 且不抛异常
    // ─────────────────────────────────────────────────────────

    @Test
    fun `字段路径缺失_顶层字段不存在_判false且不抛异常`() {
        val ctx = JSONObject().apply { put("other", 10) }
        assertFalse(ConditionEvaluator.evaluate("energy < 30", ctx))
    }

    @Test
    fun `字段路径缺失_嵌套字段的父节点不存在_判false且不抛异常`() {
        val ctx = JSONObject().apply { put("other", 10) }
        assertFalse(ConditionEvaluator.evaluate("mood.energy < 30", ctx))
    }

    @Test
    fun `字段路径缺失_嵌套字段的子节点不存在_判false且不抛异常`() {
        val ctx = JSONObject().apply {
            put("mood", JSONObject().apply { put("other", 10) })
        }
        assertFalse(ConditionEvaluator.evaluate("mood.energy < 30", ctx))
    }

    @Test
    fun `字段路径缺失_AND组合中一个子表达式字段缺失_整体判false`() {
        val ctx = JSONObject().apply {
            put("energy", 20)
            // weather 字段不存在
        }
        assertFalse(ConditionEvaluator.evaluate("""energy < 30 && weather == "rainy" """, ctx))
    }

    @Test
    fun `字段路径缺失_OR组合中一个子表达式字段缺失_另一个为true_整体判true`() {
        // 字段缺失判 false，但 || 的另一个子表达式为 true，整体仍为 true
        val ctx = JSONObject().apply {
            put("energy", 20)
            // weather 字段不存在
        }
        assertTrue(ConditionEvaluator.evaluate("""energy < 30 || weather == "rainy" """, ctx))
    }

    @Test
    fun `字段路径缺失_OR组合中两个字段都缺失_整体判false`() {
        val ctx = JSONObject()
        assertFalse(ConditionEvaluator.evaluate("""energy < 30 || weather == "rainy" """, ctx))
    }

    // ─────────────────────────────────────────────────────────
    // §12.5.1(a) 类型不匹配 —— 判 false 且不抛异常
    // §12.5.3 要求：必须写明处理方式
    // 处理方式：统一判 false + 记录日志（含期望类型/实际类型），不抛异常
    // ─────────────────────────────────────────────────────────

    @Test
    fun `类型不匹配_字段是字符串但表达式按数字比较_判false且不抛异常`() {
        val ctx = JSONObject().apply { put("energy", "high") }
        assertFalse(ConditionEvaluator.evaluate("energy < 30", ctx))
    }

    @Test
    fun `类型不匹配_字段是数字但表达式按字符串比较_判false且不抛异常`() {
        val ctx = JSONObject().apply { put("weather", 42) }
        assertFalse(ConditionEvaluator.evaluate("""weather == "rainy" """, ctx))
    }

    @Test
    fun `类型不匹配_字段是字符串但表达式按布尔比较_判false且不抛异常`() {
        val ctx = JSONObject().apply { put("isReady", "yes") }
        assertFalse(ConditionEvaluator.evaluate("isReady == true", ctx))
    }

    @Test
    fun `类型不匹配_字段是数字但表达式按布尔比较_判false且不抛异常`() {
        val ctx = JSONObject().apply { put("isReady", 1) }
        assertFalse(ConditionEvaluator.evaluate("isReady == true", ctx))
    }

    @Test
    fun `类型不匹配_字段是布尔但表达式按数字比较_判false且不抛异常`() {
        val ctx = JSONObject().apply { put("energy", true) }
        assertFalse(ConditionEvaluator.evaluate("energy < 30", ctx))
    }

    @Test
    fun `类型不匹配_AND组合中一个子表达式类型不匹配_整体判false`() {
        val ctx = JSONObject().apply {
            put("energy", 20)
            put("weather", 42) // 数字，但表达式按字符串比较
        }
        assertFalse(ConditionEvaluator.evaluate("""energy < 30 && weather == "rainy" """, ctx))
    }

    // ─────────────────────────────────────────────────────────
    // §12.5.1(a) &&/|| 混用 —— 两处都要测
    // ─────────────────────────────────────────────────────────

    // ── 处1：ChainCreateTool 静态校验（§7 规则 #5，通过 ChainNodeCodec.validate 测试）──

    @Test
    fun `混用_静态校验_ChainNodeCodec_validate_拒绝混用表达式`() {
        val nodes = listOf(
            ChainNode.Check("c1", "a==1 && b==2 || c==3", null, "e1", "e1"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertFalse("静态校验应拒绝混用表达式", result.valid)
        assertTrue("错误信息应提及 &&", result.error!!.contains("&&"))
        assertTrue("错误信息应提及 ||", result.error!!.contains("||"))
    }

    @Test
    fun `混用_静态校验_仅含AND时通过`() {
        val nodes = listOf(
            ChainNode.Check("c1", "a==1 && b==2", null, "e1", "e1"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertTrue("仅含 && 应通过: ${result.error}", result.valid)
    }

    @Test
    fun `混用_静态校验_仅含OR时通过`() {
        val nodes = listOf(
            ChainNode.Check("c1", "a==1 || b==2", null, "e1", "e1"),
            ChainNode.End("e1", ChainEndOutcome.COMPLETED),
        )
        val result = ChainNodeCodec.validate(nodes)
        assertTrue("仅含 || 应通过: ${result.error}", result.valid)
    }

    // ── 处2：ConditionEvaluator.evaluate() 自身防御性兜底 ──────

    @Test
    fun `混用_运行时防御性兜底_evaluate遇到混用判false且不抛异常`() {
        val ctx = JSONObject().apply {
            put("a", 1)
            put("b", 2)
            put("c", 3)
        }
        // 即使所有子条件都为 true，混用本身就应该判 false
        assertFalse(
            "evaluate 遇到 &&/|| 混用应判 false",
            ConditionEvaluator.evaluate("a==1 && b==2 || c==3", ctx),
        )
    }

    @Test
    fun `混用_运行时防御性兜底_即使所有子条件为true也判false`() {
        val ctx = JSONObject().apply {
            put("a", 1)
            put("b", 2)
            put("c", 3)
        }
        // 所有子条件 a==1, b==2, c==3 都为 true
        // 但 && 和 || 混用，判 false
        assertFalse(ConditionEvaluator.evaluate("a==1 && b==2 || c==3", ctx))
    }

    @Test
    fun `混用_运行时防御性兜底_不抛异常`() {
        val ctx = JSONObject()
        // 验证不抛异常——如果能执行到这里就说明没抛
        val result = ConditionEvaluator.evaluate("a==1 && b==2 || c==3", ctx)
        assertFalse("混用应判 false", result)
    }

    // ─────────────────────────────────────────────────────────
    // 边界情况补充
    // ─────────────────────────────────────────────────────────

    @Test
    fun `空表达式判false且不抛异常`() {
        assertFalse(ConditionEvaluator.evaluate("", JSONObject()))
    }

    @Test
    fun `纯空白表达式判false且不抛异常`() {
        assertFalse(ConditionEvaluator.evaluate("   ", JSONObject()))
    }

    @Test
    fun `无法解析的比较符判false`() {
        val ctx = JSONObject().apply { put("energy", 30) }
        // 没有 ==/!=/</>/<=/>= 的表达式
        assertFalse(ConditionEvaluator.evaluate("energy 30", ctx))
    }

    @Test
    fun `字面量不是合法类型时判false`() {
        val ctx = JSONObject().apply { put("energy", 30) }
        // abc 既不是数字、也不是双引号字符串、也不是 true/false
        assertFalse(ConditionEvaluator.evaluate("energy == abc", ctx))
    }

    @Test
    fun `null值字段判false`() {
        val ctx = JSONObject().apply { put("energy", JSONObject.NULL) }
        assertFalse(ConditionEvaluator.evaluate("energy == 30", ctx))
    }

    @Test
    fun `AND组合中布尔和数字混合比较`() {
        val ctx = JSONObject().apply {
            put("isReady", true)
            put("energy", 50)
        }
        assertTrue(ConditionEvaluator.evaluate("isReady == true && energy > 30", ctx))
    }

    @Test
    fun `OR组合中布尔和数字混合比较`() {
        val ctx = JSONObject().apply {
            put("isReady", false)
            put("energy", 50)
        }
        assertTrue(ConditionEvaluator.evaluate("isReady == true || energy > 30", ctx))
    }

    @Test
    fun `字符串中包含比较符字符不影响解析`() {
        // 字段值是 "a==b"，表达式按 == 比较应匹配
        val ctx = JSONObject().apply { put("text", "a==b") }
        assertTrue(ConditionEvaluator.evaluate("""text == "a==b" """, ctx))
    }

    @Test
    fun `JSONArray类型字段按数字比较判false`() {
        val ctx = JSONObject().apply { put("items", JSONArray("[1,2,3]")) }
        assertFalse(ConditionEvaluator.evaluate("items == 3", ctx))
    }

    @Test
    fun `JSONObject类型字段按数字比较判false`() {
        val ctx = JSONObject().apply { put("nested", JSONObject("{\"a\":1}")) }
        assertFalse(ConditionEvaluator.evaluate("nested == 1", ctx))
    }

    @Test
    fun `点分路径中间节点是数字而非JSONObject时判false`() {
        val ctx = JSONObject().apply { put("a", 42) }
        // a 是数字，a.b 会因为 a 不是 JSONObject 而返回 null
        assertFalse(ConditionEvaluator.evaluate("a.b == 1", ctx))
    }

    // ─────────────────────────────────────────────────────────
    // 回归测试：字符串字面量内含比较符字符（parseClause bug 修复）
    // Bug：parseClause() 原先对整个 clause 做全局 indexOf 查找运算符，
    //      当字符串字面量内容本身含比较符字符时会解析错乱。
    // 修复：先定位字符串字面量的引号边界，运算符查找范围限制在引号之前。
    // ─────────────────────────────────────────────────────────

    @Test
    fun `字符串字面量含LE符_字段值匹配时返回true`() {
        val ctx = JSONObject().apply { put("mode", "<=") }
        assertTrue(ConditionEvaluator.evaluate("""mode == "<=" """, ctx))
    }

    @Test
    fun `字符串字面量含LE符_字段值不匹配时返回false`() {
        val ctx = JSONObject().apply { put("mode", ">=") }
        assertFalse(ConditionEvaluator.evaluate("""mode == "<=" """, ctx))
    }

    @Test
    fun `字符串字面量含GE符_字段值匹配时返回true`() {
        val ctx = JSONObject().apply { put("mode", ">=") }
        assertTrue(ConditionEvaluator.evaluate("""mode == ">=" """, ctx))
    }

    @Test
    fun `字符串字面量含GE符_字段值不匹配时返回false`() {
        val ctx = JSONObject().apply { put("mode", "<=") }
        assertFalse(ConditionEvaluator.evaluate("""mode == ">=" """, ctx))
    }

    @Test
    fun `字符串字面量含EQ符_字段值匹配时返回true`() {
        val ctx = JSONObject().apply { put("mode", "==") }
        assertTrue(ConditionEvaluator.evaluate("""mode == "==" """, ctx))
    }

    @Test
    fun `字符串字面量含NE符_字段值匹配时返回true`() {
        val ctx = JSONObject().apply { put("mode", "!=") }
        assertTrue(ConditionEvaluator.evaluate("""mode == "!=" """, ctx))
    }

    @Test
    fun `字符串字面量含LT符_字段值匹配时返回true`() {
        val ctx = JSONObject().apply { put("mode", "<") }
        assertTrue(ConditionEvaluator.evaluate("""mode == "<" """, ctx))
    }

    @Test
    fun `字符串字面量含GT符_字段值匹配时返回true`() {
        val ctx = JSONObject().apply { put("mode", ">") }
        assertTrue(ConditionEvaluator.evaluate("""mode == ">" """, ctx))
    }

    @Test
    fun `字符串字面量含多个比较符_字段值匹配时返回true`() {
        val ctx = JSONObject().apply { put("expr", "a<=b>=c") }
        assertTrue(ConditionEvaluator.evaluate("""expr == "a<=b>=c" """, ctx))
    }

    @Test
    fun `字符串字面量含比较符_用NE比较_字段值不匹配时返回true`() {
        val ctx = JSONObject().apply { put("mode", "normal") }
        assertTrue(ConditionEvaluator.evaluate("""mode != "<=" """, ctx))
    }

    @Test
    fun `字符串字面量含比较符_点分路径_字段值匹配时返回true`() {
        val ctx = JSONObject().apply {
            put("config", JSONObject().apply { put("mode", "<=") })
        }
        assertTrue(ConditionEvaluator.evaluate("""config.mode == "<=" """, ctx))
    }
}
