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

    // ═══════════════════════════════════════════════════════════
    //  以下 6 条为方向1（正则过宽）/ 方向2（thinking 泄漏）回归测试。
    //  背景见 ToolCallInterceptor 里 FALSE_COMPLETION_CLAIM_REGEX /
    //  hasGenericVerbFileCompletionClaim / looksLikeFalseFileCompletionClaim
    //  的 KDoc：agent_log.txt 里"我确实这么做了"被误判为空头承诺、进而被硬推
    //  去调用 excel_gen 生成一张无关测试表格，就是这条正则过宽导致的。
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `方向1-做了-日常收尾语不再误判`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("他说让我去撩顾澜，我确实这么做了。"),
            anyToolSucceeded = false,
        )
        assertEquals("他说让我去撩顾澜，我确实这么做了。", result)
    }

    @Test
    fun `方向1-弄完了-日常收尾语不再误判`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("这事儿弄完了，你别担心。"),
            anyToolSucceeded = false,
        )
        assertEquals("这事儿弄完了，你别担心。", result)
    }

    @Test
    fun `方向1-做好了-文件语境下依然被拦`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("表格我做好了。"),
            anyToolSucceeded = false,
        )
        assertEquals("", result)
    }

    @Test
    fun `方向1-弄完了-文件语境下依然被拦`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("这个文档弄完了。"),
            anyToolSucceeded = false,
        )
        assertEquals("", result)
    }

    @Test
    fun `方向2-thinking内完整虚假声明不再连累可见正文`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("[thinking:她问我文件弄好了没,我准备撒谎说已经生成了]我们聊得挺开心的。"),
            anyToolSucceeded = false,
        )
        assertEquals("[thinking:她问我文件弄好了没,我准备撒谎说已经生成了]我们聊得挺开心的。", result)
    }

    @Test
    fun `方向2-thinking内文件语境做好了不再连累可见正文`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("[thinking:其实表格还没做,但我想说表格我做好了]今天陪你聊了好久呢。"),
            anyToolSucceeded = false,
        )
        assertEquals("[thinking:其实表格还没做,但我想说表格我做好了]今天陪你聊了好久呢。", result)
    }

    // ═══════════════════════════════════════════════════════════
    //  防谎报修复（修改点 8）：锁死"写/搞/完成/整理"扩词 + 用户输入并入检测。
    //  背景见 ToolCallInterceptor 的 FILE_DELIVERY_RULE / GENERIC_VERB /
    //  hasGenericVerbFileCompletionClaim(带 userRequestText) 的 KDoc。
    //  核心：用户说"做个PPT"、agent 输出只回"搞定了"（不带格式词）也要能拦下。
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `防谎报-写好了-用户要求txt-拦截`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("写好了"),
            anyToolSucceeded = false,
            userRequestText = "给我写个txt",
        )
        assertEquals("", result)
    }

    @Test
    fun `防谎报-搞定了-用户要求PPT-拦截`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("搞定了"),
            anyToolSucceeded = false,
            userRequestText = "做个PPT",
        )
        assertEquals("", result)
    }

    @Test
    fun `防谎报-已经为您写好了-拦截`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("已经为您写好了"),
            anyToolSucceeded = false,
        )
        assertEquals("", result)
    }

    @Test
    fun `防谎报-写完了-无文件语境-放行`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("写完了"),
            anyToolSucceeded = false,
        )
        assertEquals("写完了", result)
    }

    @Test
    fun `防谎报-我确实这么做了-回归放行`() {
        val result = ToolCallInterceptor.runSentenceGate(
            deltas = listOf("我确实这么做了"),
            anyToolSucceeded = false,
        )
        assertEquals("我确实这么做了", result)
    }
}
