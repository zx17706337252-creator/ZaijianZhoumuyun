package com.zaijian.zhoumuyun.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 方向3（纠正话术不硬点名文件工具）单元测试。
 *
 * 背景：ToolCallInterceptor 里"空头承诺"纠正提示原先无条件写死
 * "如 excel_gen/pptx_gen/pdf_export 等"，一旦上面的判定本身是误判
 * （窄化后的正则仍有漏网之鱼，或者这句话跟文件毫无关系），模型会被这句
 * 提示硬推去调用一个跟当前语境完全无关的文件生成工具——这正是
 * agent_log.txt 里"没要求却收到测试表.xlsx"这个现象的直接根因。
 *
 * [ToolCallInterceptor.detectMentionedFileToolNames] 是 private 成员，
 * 本测试文件位于 app/src/test，与 app/src/main 是不同源码集，即使同包名
 * 也无法直接调用 private 函数，因此通过
 * [ToolCallInterceptor.testDetectMentionedFileToolNames]（internal，
 * 生产代码不使用，专供测试访问）驱动。
 */
class DetectMentionedFileToolNamesTest {

    @Test
    fun `未提及任何文件类型关键词-返回空集合`() {
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "他说让我去撩顾澜，我确实这么做了",
        )
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `只提到通用词-文件-不算命中`() {
        // "文件"是通用词，不在 FILE_TYPE_KEYWORD_MAP 里——file_export 的 .md/.txt
        // 也是"文件"，不能反推出具体该点名哪个工具，纠正提示应走中性话术分支。
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "文件已经生成了，你看看",
        )
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `提到Excel和表格-去重后只归到excel_gen`() {
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "Excel表格已经生成了",
        )
        assertEquals(setOf("excel_gen"), result)
    }

    @Test
    fun `同时提到PDF和Excel-两个工具都命中`() {
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "PDF和Excel都发给你了",
        )
        assertEquals(setOf("pdf_export", "excel_gen"), result)
    }

    @Test
    fun `提到PPT关键词-命中pptx_gen`() {
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "幻灯片我做好了",
        )
        assertEquals(setOf("pptx_gen"), result)
    }

    @Test
    fun `提到压缩包关键词-命中zip_export`() {
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "已经打包好压缩包了",
        )
        assertEquals(setOf("zip_export"), result)
    }

    // Fix-门控盲区补漏①：file_export（md/txt）此前完全没有关键词覆盖，
    // 是"md 全部没有在对话框出现、也没有落盘"这个反馈的直接根因之一。
    @Test
    fun `提到markdown关键词-命中file_export`() {
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "markdown文件已经生成了",
        )
        assertEquals(setOf("file_export"), result)
    }

    @Test
    fun `提到md文件关键词-命中file_export`() {
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "md文件我做好发你了",
        )
        assertEquals(setOf("file_export"), result)
    }

    @Test
    fun `裸词md不触发-避免误判聊天口癖`() {
        // 故意不收录裸词 "md"：中文聊天场景里 "md" 常被当成"妈的"的拼音缩写，
        // 收录会把普通吐槽误判成文件声明。
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "md，怎么又卡住了",
        )
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `提到txt文件关键词-命中file_export`() {
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "txt文件已经发给你了",
        )
        assertEquals(setOf("file_export"), result)
    }

    // Fix-门控盲区补漏②：html_gen 同样此前完全没有关键词覆盖。
    @Test
    fun `提到网页关键词-命中html_gen`() {
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "网页已经做好了",
        )
        assertEquals(setOf("html_gen"), result)
    }

    @Test
    fun `空字符串-返回空集合`() {
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames("")
        assertEquals(emptySet<String>(), result)
    }

    // ═══════════════════════════════════════════════════════════
    //  防谎报修复：裸 txt / md格式 复合词 / 用户输入并入检测。
    //  背景见 FILE_TYPE_KEYWORD_MAP（修改点4）与 detectMentionedFileToolNames
    //  的 userRequestText 参数（修改点6b）。
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `防谎报-裸词txt-命中file_export`() {
        // 用户口语"写个txt"（无"文件"、无点号），此前两张表都命中不了 file_export。
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "给我写个txt",
        )
        assertEquals(setOf("file_export"), result)
    }

    @Test
    fun `防谎报-md格式复合词-命中file_export`() {
        // 用户说"存成md格式"，裸词 md 不收（"妈的"歧义），md格式 复合词要能命中。
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            "存成md格式",
        )
        assertEquals(setOf("file_export"), result)
    }

    @Test
    fun `防谎报-用户输入并入-做个PPT命中pptx_gen`() {
        // agent 输出不带格式词时，靠用户输入判定文件语境（修改点6b）
        val result = ToolCallInterceptor.testDetectMentionedFileToolNames(
            text = "",
            userRequestText = "做个PPT",
        )
        assertEquals(setOf("pptx_gen"), result)
    }
}
