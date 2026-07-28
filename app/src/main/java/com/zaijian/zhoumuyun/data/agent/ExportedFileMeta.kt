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
 *
 * 文件卡片消失根因修复：原实现用 `Regex("""\{.*\}""")` 从 content 里"从第一个 {
 * 贪婪匹配到最后一个 }"。但 excel_gen/pptx_gen/pdf_export/zip_export 等工具的
 * content 格式是"人类可读前缀文字 + \n + 元数据 JSON"（如 "Excel 文件已生成：
 * $fileName（...，N 个sheet：sheet1、sheet2）\n{JSON}"），前缀文字里的 fileName、
 * sheet 名、PPT 标题等字段都直接来自 LLM 标签参数，是完全自由的文本——一旦其中
 * 恰好出现 `{` 或 `}`（如标题写成"我的{产品}介绍"），贪婪正则就会把这个杂散花括号
 * 当成 JSON 的一部分，从它开始一路匹配到真正 JSON 的收尾 `}`，拼出一段不是合法
 * JSON 的超集字符串，`JSONObject(...)` 解析失败，函数返回 null——工具本身执行
 * 成功、文件也确实落盘了，但这里静默判定"没有可展示的文件元数据"，UI 端因此不出
 * 文件卡片。用户看到的是角色如实说"已生成"（工具结果内容本身没错），但对话里却
 * 没有任何文件卡片，看起来像是"没落盘"。
 *
 * 改为从字符串末尾往前找"能配平到字符串末尾"的最后一个 '{'（元数据 JSON 在所有
 * 调用点都是拼在 content 最后，紧跟在人类可读前缀之后，这一点在四个生成点—— 
 * BuiltinTools.FileExportTool/ArchiveExportTool、DataVisTools.ExcelGenTool/
 * PptxGenTool——都成立），配平算法与 [ToolParser.findBalancedJsonEnd] 同思路：
 * 逐字符扫描维护花括号深度，字符串字面量内部的花括号不计入深度，避免被值内容
 * 误判。前缀文字里出现的杂散花括号由于配平不到字符串末尾（或配平后解析不出
 * fileName/absolutePath 字段）会被自然跳过，不会再误吞前缀文本。
 */
internal fun extractExportedFileJson(content: String): String? {
    val start = findTrailingJsonObjectStart(content) ?: return null
    val candidate = content.substring(start)
    return try {
        val obj = org.json.JSONObject(candidate)
        if (obj.has("fileName") && obj.has("absolutePath")) candidate else null
    } catch (_: Throwable) {
        null
    }
}

/**
 * 从 [text] 末尾往前找最后一个 '{'，且该 '{' 到字符串末尾（去除尾部空白后）
 * 花括号深度能配平为 0——即这个 '{' 确实是"贴着字符串结尾的那个 JSON 对象"的
 * 起点，而不是前缀文字里偶然出现的杂散字符。
 *
 * 与 [ToolParser.findBalancedJsonEnd]（从起点往前找终点）方向相反，这里是从
 * 字符串末尾倒推起点，因为已知 JSON 一定在 content 的最后、且必须一路配平到
 * 字符串结尾（中间不能有多余内容）。字符串字面量内部的 '{'/'}' 不参与计数。
 *
 * @return 起点下标；找不到满足条件的 '{' 时返回 null
 */
internal fun findTrailingJsonObjectStart(text: String): Int? {
    val trimmed = text.trimEnd()
    if (trimmed.isEmpty() || trimmed.last() != '}') return null

    val candidateEnd = trimmed.length
    var searchFrom = trimmed.length - 1
    while (true) {
        val openIdx = trimmed.lastIndexOf('{', searchFrom)
        if (openIdx < 0) return null

        if (isBalancedToEnd(trimmed, openIdx, candidateEnd)) {
            return openIdx
        }
        // 这个 '{' 配平不到末尾（说明它是前缀文字里的杂散字符，或嵌套在真正
        // JSON 内部——但内部的 '{' 不会单独配平到末尾，因为外层还有内容），
        // 继续往前找下一个候选。
        searchFrom = openIdx - 1
        if (searchFrom < 0) return null
    }
}

/** 检查 [text] 在 [start]（必须是 '{'）到 [end] 区间内花括号是否恰好配平为 0。 */
private fun isBalancedToEnd(text: String, start: Int, end: Int): Boolean {
    var depth = 0
    var inString = false
    var i = start
    while (i < end) {
        val c = text[i]
        if (inString) {
            when (c) {
                '\\' -> { i += 2; continue }
                '"' -> inString = false
            }
            i++
            continue
        }
        when (c) {
            '\\' -> { i += 2; continue }
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i + 1 == end
                if (depth < 0) return false
            }
        }
        i++
    }
    return false  // 到 end 都没归零，说明未配平（大括号缺失/被截断）
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
