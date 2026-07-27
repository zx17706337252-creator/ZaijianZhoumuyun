package com.zaijian.zhoumuyun.ui.screen.filepreview

/**
 * 预览内容数据模型（v1.48 应用内预览编辑）。
 *
 * 由 [com.zaijian.zhoumuyun.data.agent.FilePreviewParser.parse] 产生，
 * 供 [FilePreviewEditorScreen] 按类型分发渲染器。
 */
sealed class PreviewContent {

    /**
     * 文本类（可编辑）。
     *
     * @param text 文本内容
     * @param isMarkdown true=Markdown（预览模式用 MarkdownText 渲染），false=纯文本
     * @param sourceFilePath 来源文件路径（null 表示暂存模式，来自对话框内存文本）
     */
    data class Textual(
        val text: String,
        val isMarkdown: Boolean,
        val sourceFilePath: String?,
    ) : PreviewContent()

    /**
     * 表格类（csv 可编辑 / xlsx 只读）。
     *
     * @param columns 列头
     * @param rows 数据行
     * @param editable csv=true 可编辑单元格，xlsx=false 只读
     * @param sourceFilePath 来源文件路径（csv 编辑保存用，xlsx/xlsx 为 null）
     * @param isTruncated Excel 闪退修复：超大表格解析时行数封顶（见
     *   [com.zaijian.zhoumuyun.data.agent.FilePreviewParser] 的 MAX_PARSE_ROWS），
     *   true 表示实际行数超过预览上限、这里只展示前 N 行。只在只读（xlsx）场景
     *   会为 true——可编辑内容截断后如果允许保存会静默丢数据，所以只对只读表格截断。
     * @param sheetNames xlsx 多 sheet 支持：工作簿内全部 sheet 的显示名，按标签顺序排列；
     *   csv 或单 sheet 场景为空列表（UI 据此判断是否展示 sheet 切换标签）。
     * @param activeSheetIndex 当前 columns/rows 对应 [sheetNames] 里的第几个 sheet（0-based）。
     */
    data class Tabular(
        val columns: List<String>,
        val rows: List<List<String>>,
        val editable: Boolean,
        val sourceFilePath: String?,
        val isTruncated: Boolean = false,
        val sheetNames: List<String> = emptyList(),
        val activeSheetIndex: Int = 0,
    ) : PreviewContent()

    /**
     * HTML（可编辑源码，预览用 WebView 渲染）。
     *
     * @param source HTML 源码
     * @param sourceFilePath 来源文件路径（null 表示暂存模式）
     */
    data class Html(
        val source: String,
        val sourceFilePath: String?,
    ) : PreviewContent()

    /**
     * 不支持应用内预览的类型（pptx/zip/二进制等），或文件过大放弃应用内解析。
     *
     * @param filePath 文件路径（供导出/用其他应用打开）
     * @param fileName 文件名
     * @param mimeType MIME 类型
     * @param reason 具体原因文案（null 时 UI 用默认的"该文件类型暂不支持应用内预览"）。
     *   Excel 闪退修复新增：文件超过 [com.zaijian.zhoumuyun.data.agent.FilePreviewParser]
     *   的大小上限时会带上"文件过大"提示，而不是笼统地说"不支持该类型"。
     */
    data class Unsupported(
        val filePath: String,
        val fileName: String,
        val mimeType: String,
        val reason: String? = null,
    ) : PreviewContent()
}
