package com.zaijian.zhoumuyun.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 方案 A 句子级事前门控单元测试。
 *
 * SentenceGate 是 ToolCallInterceptor 的 private 嵌套类，本测试文件位于
 * app/src/test，与 app/src/main 是不同源码集，即使同包名也无法直接实例化
 * private class。因此通过 [ToolCallInterceptor.runSentenceGate]（internal，
 * 生产代码不使用，专供测试访问）驱动一次完整的 feed → feedRemaining → flush
 * 生命周期，取最终放行文本做断言。
 *
 * 11 个场景各自独立成 @Test：任意一条失败只影响它自己，测试报告里能直接看到
 * 是哪个场景挂了，不需要再进 ToolCallInterceptor 内部去定位。
 */
class SentenceGateTest {

    @Test
    fun `正常文本全通过`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("好的，我来帮你。这是安排。"),
            anyToolSucceeded = false,
        )
        assertEquals("好的，我来帮你。这是安排。", result)
    }

    @Test
    fun `空头承诺被拦截`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("好的。已经为您生成了。"),
            anyToolSucceeded = false,
        )
        assertEquals("", result)
    }

    @Test
    fun `工具成功后合法收尾不拦`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("好的。已经为您生成了。"),
            anyToolSucceeded = true,
        )
        assertEquals("好的。已经为您生成了。", result)
    }

    @Test
    fun `本轮有pendingCalls不拦`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("好的。已经为您生成了。"),
            anyToolSucceeded = false,
            pendingCalls = listOf(ToolCall(toolName = "pdf_export", params = emptyMap(), rawTag = "")),
        )
        assertEquals("好的。已经为您生成了。", result)
    }

    @Test
    fun `跨句空头承诺滑动窗口拦截`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("已经把这份数据整理了一下今天。发送给你了"),
            anyToolSucceeded = false,
        )
        assertEquals("发送给你了", result)
    }

    @Test
    fun `迟一句发送-三句话全发出`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("第一句话。第二句话。第三句话。"),
            anyToolSucceeded = false,
        )
        assertEquals("第一句话。第二句话。第三句话。", result)
    }

    @Test
    fun `未成句残片不丢失`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("这是没有句号的半句话"),
            anyToolSucceeded = false,
        )
        assertEquals("这是没有句号的半句话", result)
    }

    @Test
    fun `多delta分片拼句`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("好的，", "我来帮", "你看看。", "这是结果。"),
            anyToolSucceeded = false,
        )
        assertEquals("好的，我来帮你看看。这是结果。", result)
    }

    @Test
    fun `请查收被拦`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("文件已准备好，请查收"),
            anyToolSucceeded = false,
        )
        assertEquals("", result)
    }

    @Test
    fun `异常路径不丢字`() {
        // skipFinalGateCheck = true：对应生产代码里异常路径的 flush(skipGateCheck = true)，
        // 优先级是"不丢字 > 不误判"，最后一句跳过正则拦截直接发出。
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("已经为您生成了。"),
            anyToolSucceeded = false,
            skipFinalGateCheck = true,
        )
        assertEquals("已经为您生成了。", result)
    }

    @Test
    fun `正常长文本不误伤`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf(
                "好的，我来帮你看看这个问题。",
                "根据我的分析，",
                "主要有以下几个原因。",
            ),
            anyToolSucceeded = false,
        )
        assertEquals("好的，我来帮你看看这个问题。根据我的分析，主要有以下几个原因。", result)
    }
}
