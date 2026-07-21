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
     */
    data class Tabular(
        val columns: List<String>,
        val rows: List<List<String>>,
        val editable: Boolean,
        val sourceFilePath: String?,
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
     * 不支持应用内预览的类型（pptx/zip/二进制等）。
     *
     * @param filePath 文件路径（供导出/用其他应用打开）
     * @param fileName 文件名
     * @param mimeType MIME 类型
     */
    data class Unsupported(
        val filePath: String,
        val fileName: String,
        val mimeType: String,
    ) : PreviewContent()
}
