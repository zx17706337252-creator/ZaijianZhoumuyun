package com.zaijian.zhoumuyun.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格直传方案 W5 验收用例：[TablePayload] 序列化/反序列化往返测试。
 *
 * 覆盖设计文档第六节用例 1/2/3 的数据载体层——这三个用例的核心数据流都依赖
 * `TablePayload.toJson()` / `TablePayload.fromJson()` 的正确性（落库 `tableDataJson`
 * 前序列化、UI 层 `tablePayload` 计算属性反序列化），所以把往返测试放在最前面，
 * 确保数据载体层在任何数据规模下都不丢字段。
 *
 * 纯 JUnit 测试（不依赖 Android Context / Robolectric），覆盖：
 * - 基本往返（小表，≤50 行，验证字段不丢）
 * - 大表往返（5000 行规模，验证 List<List<String>> 嵌套结构不丢）
 * - 带 `exportedFileMetaJson` 的往返（>500 行场景的 xlsx 附件元信息不丢）
 * - 空值/边界值往返（空标题、空行、空列）
 * - 脏数据兜底（JSON 格式异常时 `fromJson` 返回 null 而非崩溃）
 *
 * 对应设计文档第六节：
 * - 用例 1（5 行回归）：[testRoundTripSmallTable]
 * - 用例 2（200 行 CSV）：[testRoundTripMediumTable]
 * - 用例 3（5000 行日程）：[testRoundTripLargeTableWithExcelMeta]
 */
class TablePayloadSerializationTest {

    // ═══════════════════════════════════════════════════════════
    //  用例 1：小表往返（≤50 行，回归 Markdown 路径的数据载体）
    // ═══════════════════════════════════════════════════════════

    /**
     * 用例 1：5 行数据（设计文档六节"回归测试，确认新功能没碰坏旧路径"）。
     *
     * 虽然 ≤50 行走 Markdown 路径不落 `tableDataJson`（W2 设计），但 `TablePayload`
     * 本身的序列化/反序列化在这个规模下必须正确——因为 W4 的 `tablePayload` 计算
     * 属性在任何规模下都用 `fromJson` 反序列化。本测试确保 5 行数据往返不丢字段。
     */
    @Test
    fun testRoundTripSmallTable() {
        val payload = TablePayload(
            title         = "小测试表",
            columns       = listOf("列A", "列B", "列C"),
            rows          = listOf(
                listOf("1", "苹果", "3.5"),
                listOf("2", "香蕉", "2.8"),
                listOf("3", "橙子", "4.2"),
                listOf("4", "葡萄", "12.5"),
                listOf("5", "西瓜", "8.0"),
            ),
            rowCountTotal = 5,
            generatedAt   = 1700000000000L,
        )

        val json = payload.toJson()
        val restored = TablePayload.fromJson(json)

        assertNotNull("反序列化不应返回 null", restored)
        restored!!
        assertEquals("title 往返一致", payload.title, restored.title)
        assertEquals("columns 往返一致", payload.columns, restored.columns)
        assertEquals("rows 行数一致", payload.rows.size, restored.rows.size)
        assertEquals("rows[0] 一致", payload.rows[0], restored.rows[0])
        assertEquals("rows[4] 一致", payload.rows[4], restored.rows[4])
        assertEquals("rowCountTotal 一致", payload.rowCountTotal, restored.rowCountTotal)
        assertEquals("generatedAt 一致", payload.generatedAt, restored.generatedAt)
        assertNull("exportedFileMetaJson 默认 null", restored.exportedFileMetaJson)
    }

    // ═══════════════════════════════════════════════════════════
    //  用例 2：中表往返（200 行 CSV 源，tableDataJson 存全量）
    // ═══════════════════════════════════════════════════════════

    /**
     * 用例 2：200 行数据（设计文档六节"真实CSV源，验证 tableDataJson 落库/读取"）。
     *
     * 50~500 行区间 `tableDataJson` 存全量数据（设计文档 3.4 阈值策略）。本测试
     * 构造 200 行 × 5 列的 payload，验证 `List<List<String>>` 嵌套结构在序列化/
     * 反序列化后不丢行、不丢列、单元格内容不损坏。
     */
    @Test
    fun testRoundTripMediumTable() {
        val columns = listOf("序号", "姓名", "部门", "职位", "薪资")
        val rows = (1..200).map { i ->
            listOf(
                i.toString(),
                "员工$i",
                if (i % 3 == 0) "销售部" else if (i % 3 == 1) "研发部" else "市场部",
                if (i < 50) "初级" else if (i < 150) "中级" else "高级",
                "${5000 + i * 10}",
            )
        }

        val payload = TablePayload(
            title         = "员工名单",
            columns       = columns,
            rows          = rows,
            rowCountTotal = 200,
            generatedAt   = 1700000000001L,
        )

        val json = payload.toJson()
        val restored = TablePayload.fromJson(json)!!

        assertEquals("200 行往返行数一致", 200, restored.rows.size)
        assertEquals("列数一致", 5, restored.columns.size)
        // 抽查首末行 + 中间行
        assertEquals("rows[0] 一致", rows[0], restored.rows[0])
        assertEquals("rows[99] 一致", rows[99], restored.rows[99])
        assertEquals("rows[199] 一致", rows[199], restored.rows[199])
        // 中文字符不损坏
        assertEquals("中文部门名不损坏", "销售部", restored.rows[2][2])
        assertEquals("中文职位不损坏", "高级", restored.rows[199][3])
    }

    // ═══════════════════════════════════════════════════════════
    //  用例 3：大表往返（5000 行 + xlsx 附件元信息）
    // ═══════════════════════════════════════════════════════════

    /**
     * 用例 3：5000 行数据（设计文档六节"日程数据源，触发自动生成 xlsx"）。
     *
     * >500 行场景 `tableDataJson` 只存前 10 行预览（[TableExportTool.PREVIEW_ROW_COUNT]），
     * 但 `rowCountTotal` 记录全量数（5000），`exportedFileMetaJson` 携带 xlsx 文件
     * 元信息。本测试验证：
     * - 预览行（10 行）往返不丢
     * - `rowCountTotal` 是全量数（5000），不是预览行数（10）
     * - `exportedFileMetaJson` 往返不丢（xlsx 附件元信息是 >500 行场景的关键）
     */
    @Test
    fun testRoundTripLargeTableWithExcelMeta() {
        val columns = listOf("标题", "角色ID", "工具", "下次执行", "周期", "状态", "关联项目")
        // 只存前 10 行预览（模拟 >500 行场景 applyThresholdStrategy 的产出）
        val previewRows = (1..10).map { i ->
            listOf("任务$i", "1", "工单", "2026-07-${20 + i} 09:00", "24h", "启用", "")
        }
        val fakeExcelMetaJson = """{"fileName":"角色1日程表.xlsx","absolutePath":"/vault/角色1日程表.xlsx","mimeType":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","size":123456}"""

        val payload = TablePayload(
            title                = "角色1日程表",
            columns              = columns,
            rows                 = previewRows,
            rowCountTotal        = 5000,   // 全量数，不是预览行数
            generatedAt          = 1700000000002L,
            exportedFileMetaJson = fakeExcelMetaJson,
        )

        val json = payload.toJson()
        val restored = TablePayload.fromJson(json)!!

        assertEquals("预览行数一致", 10, restored.rows.size)
        assertEquals("rowCountTotal 是全量数 5000（不是预览行数 10）", 5000, restored.rowCountTotal)
        assertNotNull("exportedFileMetaJson 不应丢失", restored.exportedFileMetaJson)
        assertEquals("exportedFileMetaJson 内容一致", fakeExcelMetaJson, restored.exportedFileMetaJson)
        // 预览行内容不损坏
        assertEquals("预览 rows[0] 一致", previewRows[0], restored.rows[0])
    }

    // ═══════════════════════════════════════════════════════════
    //  边界值/脏数据兜底
    // ═══════════════════════════════════════════════════════════

    /** 空标题/空行/空列的往返（边界值）。 */
    @Test
    fun testRoundTripEmptyValues() {
        val payload = TablePayload(
            title         = "",
            columns       = emptyList(),
            rows          = emptyList(),
            rowCountTotal = 0,
            generatedAt   = 0L,
        )
        val restored = TablePayload.fromJson(payload.toJson())!!
        assertEquals("空标题往返", "", restored.title)
        assertEquals("空 columns 往返", 0, restored.columns.size)
        assertEquals("空 rows 往返", 0, restored.rows.size)
        assertEquals("rowCountTotal=0 往返", 0, restored.rowCountTotal)
    }

    /** 脏数据兜底：JSON 格式异常时 `fromJson` 返回 null 而非崩溃。 */
    @Test
    fun testFromJsonDirtyData() {
        assertNull("空字符串应返回 null", TablePayload.fromJson(""))
        assertNull("非 JSON 应返回 null", TablePayload.fromJson("不是 JSON"))
        assertNull("缺字段应返回 null", TablePayload.fromJson("""{"title":"缺 columns"}"""))
        // 格式异常但部分可解析——fromJson 用 optXxx 兜底，不应崩溃
        // 但如果是彻底的 JSONException（如数组里塞对象），返回 null
        assertNull("结构错误应返回 null", TablePayload.fromJson("""{"rows":"应该是数组但给了字符串"}"""))
    }

    /** 单元格含特殊字符（逗号/引号/换行/中文）的往返。 */
    @Test
    fun testRoundTripSpecialCharacters() {
        val payload = TablePayload(
            title         = "特殊字符测试",
            columns       = listOf("含,逗号", "含\"引号\"", "含\n换行"),
            rows          = listOf(listOf("值,带逗号", "值\"带引号\"", "值\n带换行")),
            rowCountTotal = 1,
            generatedAt   = 1700000000003L,
        )
        val restored = TablePayload.fromJson(payload.toJson())!!
        assertEquals("含逗号单元格往返", "值,带逗号", restored.rows[0][0])
        assertEquals("含引号单元格往返", "值\"带引号\"", restored.rows[0][1])
        assertEquals("含换行单元格往返", "值\n带换行", restored.rows[0][2])
    }
}
