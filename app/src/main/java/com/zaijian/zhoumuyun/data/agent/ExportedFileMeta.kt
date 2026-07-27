package com.zaijian.zhoumuyun.data.agent

/**
 * 文件导出元数据识别 / 打包 —— 唯一实现（P3-2：三份副本统一）。
 *
 * 背景：file_export / excel_gen / pptx_gen / docx_gen / pdf_export / html_gen 等
 * 导出类工具的 content 都遵循 "xxx已生成：文件名（大小）\n{JSON}" 这个约定格式
 * （JSON 里固定带 fileName + absolutePath），用一次正则 + JSON 校验通吃，不需要
 * 按 toolName 逐个 if 分支——未来新增导出工具不需要再改这里。
 *
 * 在下沉之前，这套逻辑曾在三处各自维护一份完全相同的实现：
 *   - ui.viewmodel.ChatMessageOrchestrator（私聊 / 圆桌 / 导出，4 个调用点共用）
 *   - data.agent.ToolCallInterceptor（孤儿文件兜底，理由是"data.agent 不应反向
 *     依赖 ui.viewmodel"）
 *   - data.agent.AgentTaskJobExecutor（headless 工单执行器，理由同上）
 *
 * 三份逻辑完全一致、当时都没有 bug，但字段改名（如 fileName/absolutePath 改名）
 * 需要同时改三处，漏改任何一处编译期都不会报错——纯维护性风险。
 *
 * 现在把它下沉到 data.agent 层作为唯一实现：data.agent 内部的调用方
 * （ToolCallInterceptor、AgentTaskJobExecutor）直接用；ui.viewmodel 层
 * （ChatMessageOrchestrator 及其 4 个调用点：私聊、圆桌两条路径、对话导出）
 * 通过下面的 [extractExportedFileJson] / [packExportedFilesJson] 顶层函数调用——
 * ui.viewmodel 依赖 data.agent 本来就是正常的层次方向，不存在反向依赖问题。
 */
internal fun extractExportedFileJson(result: ToolResult): String? {
    if (!result.success) return null
    return extractExportedFileJson(result.content)
}

/**
 * 从工具结果文本里提取文件元数据 JSON（fileName/absolutePath 字段齐全才算数）。
 * 供只有 content 字符串、没有完整 [ToolResult]（如孤儿兜底场景）的调用方直接使用。
 */
internal fun extractExportedFileJson(content: String): String? {
    val match = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(content) ?: return null
    return try {
        val obj = org.json.JSONObject(match.value)
        if (obj.has("fileName") && obj.has("absolutePath")) match.value else null
    } catch (_: Throwable) {
        null
    }
}

/**
 * 把本轮收集到的多个文件元数据 JSON 打包成一个 JSON 数组字符串，写入
 * exportedFilesJson。空列表返回 null（与"该消息没有文件附件"的语义一致，
 * 不存空数组字符串）。
 */
internal fun packExportedFilesJson(fileJsonList: List<String>): String? {
    if (fileJsonList.isEmpty()) return null
    val arr = org.json.JSONArray()
    fileJsonList.forEach { arr.put(org.json.JSONObject(it)) }
    return arr.toString()
}
