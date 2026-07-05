package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Phase 30 · 查询单个定时任务详情工具
 *
 * 标签格式：
 *   <tool:schedule_get id="任务ID" />
 *
 * 参数说明：
 *   id   要查询的任务 ID（必填）
 */
class ScheduleGetTool(
    private val scheduleRepository: ScheduleRepository,
) : AgentTool {

    override val name = "schedule_get"
    override val paramKeys = listOf("id")

    // P1-8-4 修复：SimpleDateFormat 非线程安全，本工具实例在 AgentToolRegistry
    // 中是单例复用，多个并发请求（前台聊天 + 后台 Worker 同时调用）共享同一个 sdf
    // 实例会导致内部 Calendar 状态被并发修改，产生 ArrayIndexOutOfBoundsException
    // 或格式化结果错乱。DateTimeFormatter 不可变且线程安全，可放心作为单例字段。
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val zone = ZoneId.systemDefault()
    private fun formatMillis(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(fmt)

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val id = params["id"]?.trim()
        if (id.isNullOrEmpty()) {
            return ToolResult(name, false, "", error = "id 参数不能为空")
        }

        return try {
            val job = scheduleRepository.getJob(id)
                ?: return ToolResult(name, false, "", error = "找不到任务 ID: $id")

            val repeatDesc = if (job.repeatIntervalMs != null) {
                val hours = job.repeatIntervalMs / TimeUnit.HOURS.toMillis(1).toDouble()
                "每 $hours 小时"
            } else "一次性"

            val nextRunDesc = formatMillis(job.nextRunAt)
            val lastRunDesc = job.lastRunAt?.let { formatMillis(it) } ?: "尚未执行"
            val statusDesc  = if (job.enabled) "启用" else "已禁用"

            val toolParamsDesc = try {
                val json = org.json.JSONObject(job.toolParamsJson)
                json.keys().asSequence()
                    .map { k -> "$k=${json.getString(k)}" }
                    .joinToString(", ")
                    .ifEmpty { "（无参数）" }
            } catch (_: Exception) { job.toolParamsJson }

            val content = buildString {
                appendLine("任务详情：")
                appendLine("  ID       : $id")
                appendLine("  标题     : ${job.title}")
                appendLine("  工具     : ${job.toolName}")
                appendLine("  参数     : $toolParamsDesc")
                appendLine("  重复     : $repeatDesc")
                appendLine("  下次执行 : $nextRunDesc")
                appendLine("  上次执行 : $lastRunDesc")
                append  ("  状态     : $statusDesc")
            }

            ToolResult(
                toolName = name,
                success  = true,
                content  = content,
            )
        } catch (e: Exception) {
            ToolResult(name, false, "", error = "查询失败：${e.message}")
        }
    }
}
