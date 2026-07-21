package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.ui.viewmodel.extractExportedFileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 表格直传方案 W5 验收用例 5：并发共存（table_export + file_export 同一轮）。
 *
 * 设计文档第六节用例 5："一轮回复里同时触发 `table_export` 和 `file_export`
 * （两种附件类型），验证 `TableCard` 和 `FileExportCard` 在同一条消息里正确共存，
 * 不会互相覆盖（复用现有 `pendingExportedFiles` 多文件打包逻辑时要确认表格 payload
 * 不会被这条逻辑误吞）"。
 *
 * 核心验证点：[extractExportedFileJson]（从 `result.content` 用正则抓 `fileName`+
 * `absolutePath` JSON）**不会误吞** `table_export` 的 payload——因为 `table_export`
 * 的 `ToolResult.content` 是 Markdown 表格文本，不含 `fileName`/`absolutePath` JSON。
 * 而 `table_export` 的 payload 走 `ToolResult.tablePayloadJson` 专门字段（W2 验收修复），
 * 与 `extractExportedFileJson` 的 `content` 正则路径物理隔离。
 *
 * 纯 JUnit 测试（不依赖 Orchestrator 集成），覆盖：
 * - `extractExportedFileJson` 对 file_export 的 ToolResult 返回非 null（正向）
 * - `extractExportedFileJson` 对 table_export 的 ToolResult 返回 null（不误吞，核心）
 * - `ToolResult.tablePayloadJson` 与 `extractExportedFileJson` 物理隔离（两个字段互不依赖）
 *
 * ⚠️ 真实"同一轮 StreamEvent 流里 table_export + file_export 交替到达"的集成测试
 * 需要 Robolectric/真机环境（走 `ChatMessageOrchestrator.streamWithTools`），
 * 见 W5 交付说明"需要真机的用例"段。
 */
class TableExportCoexistenceTest {

    // ═══════════════════════════════════════════════════════════
    //  用例 5 核心：extractExportedFileJson 不会误吞 table_export 的 payload
    // ═══════════════════════════════════════════════════════════

    /**
     * file_export 工具的 ToolResult（content 里有 fileName+absolutePath JSON）。
     *
     * 验证 [extractExportedFileJson] 能正确识别——这是正向验证，确认提取逻辑正常工作。
     */
    @Test
    fun extractExportedFileJsonRecognizesFileExportResult() {
        val fileExportResult = ToolResult(
            toolName = "excel_gen",
            success  = true,
            content  = "已生成文件：{\"fileName\":\"报表.xlsx\",\"absolutePath\":\"/vault/报表.xlsx\",\"mimeType\":\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\",\"size\":12345}",
            // file_export 不产出 tablePayloadJson（tablePayloadJson 默认 null）
        )

        val extracted = extractExportedFileJson(fileExportResult)
        assertNotNull("file_export 结果应被 extractExportedFileJson 识别", extracted)
        // tablePayloadJson 为 null（file_export 不产出表格 payload）
        assertNull("file_export 的 tablePayloadJson 应为 null", fileExportResult.tablePayloadJson)
    }

    /**
     * 用例 5 核心：table_export 工具的 ToolResult（content 是 Markdown，不含文件 JSON）。
     *
     * 验证 [extractExportedFileJson] 对 table_export 的 ToolResult 返回 null——
     * **表格 payload 不会被文件收集逻辑误吞**。这是设计文档六节用例 5 的核心验证点。
     *
     * 原理：`table_export` 的 `ToolResult.content` 是 Markdown 表格文本（`### 标题`+`| 列 |`），
     * 不含 `{...fileName...absolutePath...}` JSON，所以 `extractExportedFileJson` 的正则
     * `\\{.*\\}` 匹配不到（或匹配到了但 JSONObject 没有 fileName/absolutePath 字段），
     * 返回 null。而表格 payload 在 `ToolResult.tablePayloadJson` 专门字段里，走完全独立的
     * 收集路径（`event.result.tablePayloadJson?.let { pendingTablePayloadJson = it }`）。
     */
    @Test
    fun extractExportedFileJsonDoesNotSwallowTableExportResult() {
        val tableExportResult = ToolResult(
            toolName = "table_export",
            success  = true,
            content  = "角色1日程表：共 200 行 × 7 列\n### 角色1日程表（共 200 行）\n\n| 标题 | 角色ID | 工具 |\n|---|---|---|\n| 任务1 | 1 | 工单 |",
            // table_export 产出 tablePayloadJson（200 行全量场景）
            tablePayloadJson = """{"title":"角色1日程表","columns":["标题","角色ID","工具"],"rows":[["任务1","1","工单"]],"rowCountTotal":200,"generatedAt":1700000000000}""",
        )

        // 核心断言：extractExportedFileJson 对 table_export 返回 null（不误吞）
        val extracted = extractExportedFileJson(tableExportResult)
        assertNull("table_export 的 content 不应被 extractExportedFileJson 误吞", extracted)

        // tablePayloadJson 非 null（走独立收集路径）
        assertNotNull("table_export 的 tablePayloadJson 应非 null", tableExportResult.tablePayloadJson)
    }

    /**
     * 用例 5 隔离性：同一轮里 table_export 和 file_export 的 ToolResult 各自走各的路径。
     *
     * 模拟 W4 管线里同一轮回复的 StreamEvent.ToolDone 流：
     * - 第一个 ToolDone 是 table_export → 走 tablePayloadJson 收集路径
     * - 第二个 ToolDone 是 file_export → 走 extractExportedFileJson 收集路径
     * 两个收集逻辑互不干扰，最终 pendingTablePayloadJson 和 pendingExportedFiles
     * 各自独立填充。
     */
    @Test
    fun tableExportAndFileExportCoexistInSameRound() {
        val tableResult = ToolResult(
            toolName = "table_export",
            success  = true,
            content  = "### 表格（共 200 行）",
            tablePayloadJson = """{"title":"表格","columns":["A"],"rows":[["1"]],"rowCountTotal":200,"generatedAt":1700000000000}""",
        )
        val fileResult = ToolResult(
            toolName = "excel_gen",
            success  = true,
            content  = "{\"fileName\":\"f.xlsx\",\"absolutePath\":\"/vault/f.xlsx\"}",
        )

        // 模拟 W4 的 ToolDone 收集逻辑（两个收集路径并行，互不干扰）
        val pendingExportedFiles = mutableListOf<String>()
        var pendingTablePayloadJson: String? = null

        // 第一个 ToolDone：table_export
        extractExportedFileJson(tableResult)?.let { pendingExportedFiles.add(it) }  // null，不 add
        tableResult.tablePayloadJson?.let { pendingTablePayloadJson = it }            // 非 null，填充

        // 第二个 ToolDone：file_export
        extractExportedFileJson(fileResult)?.let { pendingExportedFiles.add(it) }    // 非 null，add
        fileResult.tablePayloadJson?.let { pendingTablePayloadJson = it }             // null，不覆盖

        // 断言：两个收集器各自独立
        assertEquals("pendingExportedFiles 应只有 1 个文件（file_export 的）", 1, pendingExportedFiles.size)
        assertNotNull("pendingTablePayloadJson 应非 null（table_export 的）", pendingTablePayloadJson)
        // table_export 的 payload 没被 file_export 覆盖（file_export 的 tablePayloadJson 是 null）
        assertEquals("tablePayloadJson 内容正确", "表格", org.json.JSONObject(pendingTablePayloadJson!!).getString("title"))
    }

    /**
     * 用例 5 边界：同一轮里两次 table_export（以最后一个为准，单值覆盖）。
     *
     * 设计文档 3.3：`tableDataJson` 是单值字段（一条消息一个表格）。
     * 一轮里两次 `table_export` 时，`pendingTablePayloadJson` 以最后一个为准
     * （与 `pendingExportedFiles` 的"全部收集"不同）。
     */
    @Test
    fun multipleTableExportsInSameRoundLastOneWins() {
        val firstResult = ToolResult(
            toolName = "table_export",
            success  = true,
            content  = "### 第一个表格",
            tablePayloadJson = """{"title":"第一个表格","columns":["A"],"rows":[["1"]],"rowCountTotal":1,"generatedAt":1700000000001}""",
        )
        val secondResult = ToolResult(
            toolName = "table_export",
            success  = true,
            content  = "### 第二个表格",
            tablePayloadJson = """{"title":"第二个表格","columns":["B"],"rows":[["2"]],"rowCountTotal":1,"generatedAt":1700000000002}""",
        )

        var pendingTablePayloadJson: String? = null
        // 模拟两次 ToolDone
        firstResult.tablePayloadJson?.let { pendingTablePayloadJson = it }
        secondResult.tablePayloadJson?.let { pendingTablePayloadJson = it }

        // 最后一个为准
        val title = org.json.JSONObject(pendingTablePayloadJson!!).getString("title")
        assertEquals("两次 table_export 以最后一个为准", "第二个表格", title)
    }
}
