package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.agent.TablePayload

/**
 * v1.49 修复（file_read 锁死机制复发性触发 + 出戏念旁白）：
 *
 * ToolCallInterceptor 的"强制读取文件"锁死机制此前把"这个文件已经读过"的凭证
 * 只记在本次 streamWithTools() 调用的局部变量里，函数一返回就丢失——数据库里
 * 只落最终 assistant 回复，从未记录中间的工具调用/工具结果消息。导致每条新消息
 * 重新组装 LLM 上下文时，都查不到"已读过"的证据，于是又把两轮强制重试 + 兜底
 * 自动读取整套流程重新跑一遍——这个文件只要还留在对话历史里就会一直复发，
 * 不会自愈（阿云反馈：索菲亚反复被"系统强制要求"读同一个文件，还把这条内部
 * 强制指令当角色台词念给用户听）。
 *
 * 修复方式：ToolCallInterceptor 在文件被读取（不管是 AI 主动调用还是程序兜底）
 * 后发出 StreamEvent.FileReadConfirmed，由 ChatMessageOrchestrator 持久化一条
 * 带这个前缀的标记消息（role="system"）。这条消息需要满足两个相反的要求：
 *   1) 要能进入下一轮的 LLM 上下文（ChatMessageOrchestrator 组装 messages 时，
 *      role="system" 且不以 [AGENT_MSG:/[ROUNDTABLE_TRIGGER] 开头的消息本就会
 *      映射成 LLMMessage(role="user")，不需要改这部分逻辑），让 ToolCallInterceptor
 *      的 alreadyRead 检测（找 role=="user" 且含"[工具执行结果]"+文件名的消息）
 *      能查到证据；
 *   2) 但不能作为聊天气泡出现在界面上——当前 UI 层（ChatSessionDelegate）对
 *      DB 里取出的消息没有任何按内容过滤的逻辑，任何 role != "user" 的消息都会
 *      落进 MessageBubble 的"角色气泡"分支，原样展示会让用户看到一条奇怪的
 *      系统提示，就像是角色自己说的话。
 * 所以这里只加一个内容前缀做标记，ChatSessionDelegate 组装 UI 展示列表时按
 * 这个前缀过滤掉，两头都不耽误。
 */
const val FILE_READ_MARK_PREFIX = "[FILE_READ_MARK]"

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

    /**
     * UI卡片预览修复（Fix-FileImportCard）：ChatMessageActionsDelegate.notifyFileImported()
     * 写入的"用户导入文件"通知，role="system"（这个 role 值本身不能改——LLM 上下文管线
     * 和 ToolCallInterceptor 的 file_read 强制锁死机制都依赖它现在的写入方式，见两处的
     * 对应注释），content 固定格式"用户导入了一个文件：X（路径：Y）"。此前这条消息落到
     * MessageBubble 的角色气泡分支里当纯文本显示——不仅左对齐头像显得像是角色说的话，
     * 还直接把内部绝对路径糊在用户脸上，也没有可点开预览的入口。
     * 这个属性只是"识别出这是这一类通知"，不改变它的存储方式。
     */
    val isUserFileImportNotice: Boolean get() =
        role == "system" &&
            content.startsWith(USER_FILE_IMPORT_PREFIX) &&
            content.contains(USER_FILE_IMPORT_PATH_TAG)

    /**
     * 用户导入的文件列表，供 MessageBubble 渲染成 FileExportCard（用户侧右对齐）。
     * 优先用 exportedFiles（v1.x 起 notifyFileImported() 已同步写入结构化元数据，
     * mimeType/sizeBytes 都是真实值）；旧数据只有纯文本通知、没有 exportedFilesJson
     * 时，从文本里把文件名和路径抠出来兜底合成一张卡片——mimeType 按扩展名猜测，
     * sizeBytes 未知场景置 0（toChatMessage() 是同步映射，这里不做磁盘 IO 判断
     * 文件是否还在/多大，旧消息卡片上的"0 B"是已知的展示层面折衷，不影响能否点开预览）。
     */
    val userImportedFiles: List<ExportedFile> get() {
        if (!isUserFileImportNotice) return emptyList()
        exportedFiles.takeIf { it.isNotEmpty() }?.let { return it }
        val body = content.removePrefix(USER_FILE_IMPORT_PREFIX)
        val idx = body.indexOf(USER_FILE_IMPORT_PATH_TAG)
        if (idx < 0) return emptyList()
        val fileName = body.substring(0, idx)
        val path = body.substring(idx + USER_FILE_IMPORT_PATH_TAG.length).removeSuffix("）")
        if (fileName.isBlank() || path.isBlank()) return emptyList()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val guessedMime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
        return listOf(
            ExportedFile(
                fileName = fileName,
                mimeType = guessedMime,
                sizeBytes = 0L,
                absolutePath = path,
            )
        )
    }

    private companion object {
        const val USER_FILE_IMPORT_PREFIX = "用户导入了一个文件："
        const val USER_FILE_IMPORT_PATH_TAG = "（路径："
    }
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
        } catch (_: Throwable) {
            emptyList()
        }
    }
    val legacy = legacyJson ?: return emptyList()
    return try {
        listOfNotNull(parseExportedFile(org.json.JSONObject(legacy)))
    } catch (_: Throwable) {
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
