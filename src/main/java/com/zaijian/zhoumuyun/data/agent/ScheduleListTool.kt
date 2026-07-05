package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Phase 30 · 列出定时任务工具
 *
 * 标签格式：
 *   <tool:schedule_list
 *     character_id="1"
 *     hours_ahead="72"
 *     enabled_only="true"
 *   />
 *
 * 参数说明：
 *   character_id   角色 ID（可选；不传 = 当前角色）
 *   hours_ahead    只列出未来 N 小时内将执行的任务（可选；默认 168 = 7天）
 *   enabled_only   是否只显示启用中的任务（可选；默认 true）
 */
class ScheduleListTool(
    private val scheduleRepository: ScheduleRepository,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name = "schedule_list"
    override val paramKeys = listOf("character_id", "hours_ahead", "enabled_only")

    // P1-8-4 修复：同 ScheduleGetTool，SimpleDateFormat 非线程安全且本工具
    // 是单例复用，改用线程安全、不可变的 DateTimeFormatter。
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val zone = ZoneId.systemDefault()
    private fun formatMillis(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(fmt)

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val charId = params["__character_id"]?.toIntOrNull() ?: params["character_id"]?.toIntOrNull() ?: characterIdProvider()
        val hoursAhead = params["hours_ahead"]?.toDoubleOrNull() ?: 168.0
        val enabledOnly = params["enabled_only"]?.trim()?.lowercase() != "false"

        return try {
            val jobs = scheduleRepository.listJobs(
                characterId  = charId,
                beforeMs     = System.currentTimeMillis() + (hoursAhead * TimeUnit.HOURS.toMillis(1)).toLong(),
                enabledOnly  = enabledOnly,
            )

            if (jobs.isEmpty()) {
                return ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "没有符合条件的定时任务。",
                )
            }

            val content = buildString {
                appendLine("共 ${jobs.size} 个任务：")
                jobs.forEachIndexed { i, job ->
                    val repeatDesc = if (job.repeatIntervalMs != null) {
                        val h = job.repeatIntervalMs / TimeUnit.HOURS.toMillis(1).toDouble()
                        "每${h}h"
                    } else "一次性"
                    val nextDesc = formatMillis(job.nextRunAt)
                    val statusMark = if (job.enabled) "✅" else "⏸"
                    appendLine("${i + 1}. $statusMark [${job.id.take(8)}] ${job.title}")
                    appendLine("   工具: ${job.toolName}  周期: $repeatDesc  下次: $nextDesc")
                }
            }.trimEnd()

            ToolResult(
                toolName = name,
                success  = true,
                content  = content,
            )
        } catch (e: Exception) {
            ToolResult(name, false, "", error = "列表查询失败：${e.message}")
        }
    }
}
