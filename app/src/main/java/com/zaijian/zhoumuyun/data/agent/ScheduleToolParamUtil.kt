package com.zaijian.zhoumuyun.data.agent

import org.json.JSONObject

/**
 * 日程审计批次1（问题1 / 问题2）共享修复工具。
 *
 * ScheduleCreateTool 与 ScheduleUpdateTool 共用同一份 params / interval_hours /
 * delay_hours 解析契约，此前两处各自复制一份有缺陷的逻辑，这里收口成单一真相源，
 * 避免以后再次出现"两个工具同源问题各修一半"的情况。
 */
internal object ScheduleToolParamUtil {

    // 与 ScheduleCreateTool / ScheduleUpdateTool 里的 PARAM_REGEX 保持一致：
    // key="value"，值内允许转义引号，逗号安全。
    private val PARAM_REGEX = Regex("""(\w+)="((?:[^"\\]|\\.)*)""" + "\"")

    /**
     * 审计报告问题1（P1，静默失败）修复。
     *
     * 根因：ToolParser 的 findBalancedJsonEnd 主动兼容了 params="{...}" 这种
     * "值是未转义 JSON" 的写法，会把整段 JSON 原文正确摘出来交给工具；但此前
     * execute() 只用 PARAM_REGEX 解析 key="val" 逗号分隔格式，完全不认 JSON，
     * 解析层防住了 JSON 截断，执行层却吃不下 JSON —— toolParams 静默变空，
     * execute 仍返回 success，数据落库但内容是空的。
     *
     * 修复：先尝试把整段值当 JSON 解析：
     *   1. 原文直接 JSONObject(value) —— 覆盖"裸 JSON"（{"query":"上海天气"}）
     *   2. 若失败，把 \" 还原成 " 后再 JSONObject —— 覆盖"转义 JSON"
     *      （{\"query\":\"上海天气\"}）
     *   3. 都失败，fallback 到现有 PARAM_REGEX（key="val1",key2="val2" 格式）
     * 嵌套 JSON（{"q":"a","extra":{"x":"y"}}）在 JSONObject 解析下会被当作
     * 普通键值对保留，嵌套对象整体作为字符串值存入 toolParams，不会再丢数据。
     *
     * 三种格式都不认时返回空 Map（维持原有"未传 params"时的行为不变）。
     */
    fun parseToolParams(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()

        // 1. 裸 JSON
        parseAsJson(raw)?.let { return it }

        // 2. 转义 JSON：先把 \" 还原成 "，再按 JSON 解析
        if (raw.contains("\\\"")) {
            parseAsJson(raw.replace("\\\"", "\""))?.let { return it }
        }

        // 3. fallback：key="val1",key2="val2" 格式
        val matches = PARAM_REGEX.findAll(raw).toList()
        if (matches.isNotEmpty()) {
            return matches.associate { match ->
                match.groupValues[1].trim() to match.groupValues[2].trim()
            }
        }

        return emptyMap()
    }

    private fun parseAsJson(text: String): Map<String, String>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return try {
            val obj = JSONObject(trimmed)
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                // 嵌套对象/数组整体转字符串存值，避免二次丢数据；标量值直接取字符串形式。
                result[key] = obj.get(key).toString()
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 审计报告问题2（P1，静默降级）修复。
     *
     * 根因：params["interval_hours"]?.toDoubleOrNull() ?: 0.0 —— toDoubleOrNull()
     * 解析失败返回 null，?: 0.0 把"用户想要重复任务但填错了数字"静默降级成
     * "一次性任务"（interval_hours）或"立即执行"（delay_hours），调用方拿不到
     * 任何错误提示。
     *
     * 修复：只有当原始值为空/未传时才默认 0.0（保留"可选参数不传"的合法场景）；
     * 一旦原始值非空但转换失败，返回 Result.failure 携带明确错误信息，调用方
     * （execute）据此返回 error，而不是静默继续创建/更新任务。
     */
    fun parseHoursOrError(raw: String?, fieldName: String): Result<Double> {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return Result.success(0.0)
        val value = trimmed.toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException("$fieldName 必须是数字，收到: $trimmed"))
        return Result.success(value)
    }
}
