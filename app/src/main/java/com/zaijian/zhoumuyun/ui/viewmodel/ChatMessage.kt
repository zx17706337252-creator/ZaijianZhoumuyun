package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.agent.TablePayload

data class ExportedFile(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val absolutePath: String,
    // 1.3 附件卡片类型区分：非 null 时卡片上多渲染一行提示（如"需用浏览器打开另存"），
    // 用于 docx_gen/pdf_export 这类"委托生成的伪二进制"文件，向后兼容（默认 null 不影响现有文件）。
    val openHint: String? = null,
) {
    val extLabel: String get() = fileName.substringAfterLast(".", "?").take(4).uppercase()
    val sizeLabel: String get() = when {
        sizeBytes < 1024 -> "${sizeBytes} B"
        sizeBytes < 1024 * 1024 -> "${"%.1f".format(sizeBytes / 1024.0)} KB"
        else -> "${"%.1f".format(sizeBytes / 1024.0 / 1024.0)} MB"
    }
}

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    /**
     * 单文件字段，本轮多个文件类工具调用时只保留最后一个——保留是为了兼容
     * 尚未切换到 exportedFilesJson 的旧读取路径，新代码应优先读 exportedFiles。
     */
    val exportedFileJson: String? = null,
    /**
     * v66（Agent附件下发方案 v2.0 · 1.7 P3）：多文件版本，JSON 数组字符串。
     * null = 该消息没有文件附件；历史消息永远为 null，即使 exportedFileJson 有值。
     */
    val exportedFilesJson: String? = null,
    /**
     * v67（表格直传方案 W4）：`table_export` 工具产出的 [TablePayload] 序列化 JSON。
     * null = 该消息没有表格；历史消息永远为 null。由 [ChatMessageOrchestrator] 在
     * `StreamEvent.ToolDone` 里收集 `event.result.tablePayloadJson` 写入
     * `MessageEntity.tableDataJson`，UI 层通过 [tablePayload] 计算属性反序列化渲染。
     * ≤50 行 Markdown 路径不落本字段（null）；50~500 行存全量；>500 行存前 10 行
     * 预览 + xlsx 附件走 `exportedFilesJson`（与表格字段同级、同生命周期）。
     */
    val tableDataJson: String? = null,
    // Fix-ThinkingLeak：从回复正文剥离出的内心推理/工具调用意图原文，null = 无想法内容。
    val thinkingText: String? = null,
    // v1.36 问题2：从回复正文中圆括号包裹的内容抽取出的心理感受/神态描写，null = 无心理描写。
    val psychText: String? = null,
) {
    @Deprecated("单文件读取路径，历史兼容用；新代码请用 exportedFiles", ReplaceWith("exportedFiles.firstOrNull()"))
    val exportedFile: ExportedFile? get() = exportedFiles.firstOrNull()

    /**
     * v66（1.7 P3）：优先解析 exportedFilesJson（多文件数组）；为空时退化为把
     * exportedFileJson 包成单元素 list——历史消息（只有旧字段有值）不会因为
     * 这次改造丢失已有的文件卡片。两个字段都为 null 时返回空 list。
     */
    val exportedFiles: List<ExportedFile> get() = parseExportedFilesWithFallback(exportedFilesJson, exportedFileJson)

    /**
     * v67（表格直传方案 W4）：从 [tableDataJson] 反序列化得到的 [TablePayload]。
     * null = 该消息没有表格（或 JSON 格式异常的历史脏数据兜底）。UI 层
     * `MessageBubble` 在 `message.tablePayload != null` 时渲染 [TableCard]。
     */
    val tablePayload: TablePayload? get() = tableDataJson?.let { TablePayload.fromJson(it) }
}

/**
 * v66（1.7 P3）：解析文件元数据的共享逻辑，供 ChatMessage.exportedFiles /
 * RoundtableMessage.exportedFiles 共用，避免私聊+圆桌两处各写一份、日后漏改其中一处。
 *
 * 优先用 filesJson（数组，v66 新字段）；为空/解析失败时退化用 legacyJson
 * （单对象，v65 及更早字段）包成单元素 list。
 */
internal fun parseExportedFilesWithFallback(filesJson: String?, legacyJson: String?): List<ExportedFile> {
    filesJson?.let { json ->
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> parseExportedFile(arr.optJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }
    val legacy = legacyJson ?: return emptyList()
    return try {
        listOfNotNull(parseExportedFile(org.json.JSONObject(legacy)))
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseExportedFile(obj: org.json.JSONObject?): ExportedFile? {
    obj ?: return null
    return ExportedFile(
        fileName = obj.optString("fileName", ""),
        mimeType = obj.optString("mimeType", "text/plain"),
        sizeBytes = obj.optLong("sizeBytes", 0),
        absolutePath = obj.optString("absolutePath", ""),
        openHint = obj.optString("openHint", "").ifBlank { null },
    )
}
