package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import java.util.concurrent.TimeUnit

/**
 * Phase 30 · 修改定时任务工具
 *
 * 标签格式：
 *   <tool:schedule_update
 *     id="任务ID"
 *     title="新标题"
 *     tool="新工具名"
 *     params="key1=val1,key2=val2"
 *     interval_hours="24"
 *     delay_hours="2"
 *   />
 *
 * 参数说明：
 *   id              要修改的任务 ID（必填）
 *   title           新任务标题（可选，不传则保持原值）
 *   tool            新工具名（可选）
 *   params          新工具参数，格式 key1=val1,key2=val2（可选）
 *   interval_hours  新重复间隔（可选；0 = 改为一次性任务）
 *   delay_hours     重新调度延迟小时数（可选；不传 = 从现在起立即应用新间隔）
 */
class ScheduleUpdateTool(
    private val scheduleRepository: ScheduleRepository,
    private val calendarSync: CalendarSyncHelper? = null,
    private val context: android.content.Context? = null,
) : AgentTool {

    override val name = "schedule_update"
    override val paramKeys = listOf("id", "title", "tool", "params", "interval_hours", "delay_hours")

    private companion object {
        // P1-12-1 修复：与 ScheduleCreateTool 对齐，使用带引号正则解析 params 字段，
        // 旧实现用 split(",") + indexOf('=') 切割，值中含逗号时会被截断。
        // 新正则与 ToolParser.ATTR_PATTERN 格式一致：key="value"，允许值内含逗号和转义引号。
        val PARAM_REGEX = Regex("""(\w+)="((?:[^"\\]|\\.)*)"""")
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val id = params["id"]?.trim()
        if (id.isNullOrEmpty()) {
            return ToolResult(name, false, "", error = "id 参数不能为空")
        }

        return try {
            // 先查出原任务
            val existing = scheduleRepository.getJob(id)
                ?: return ToolResult(name, false, "", error = "找不到任务 ID: $id")

            val newTitle = params["title"]?.trim()?.takeIf { it.isNotEmpty() } ?: existing.title
            val newToolName = params["tool"]?.trim()?.takeIf { it.isNotEmpty() } ?: existing.toolName

            // 解析新工具参数（未传则保持原值）
            // P1-12-1 修复：改用带引号正则 findAll，与 ScheduleCreateTool 一致，
            // 值中含逗号时不再被截断（如 message="早上好，今天天气不错" 能正确解析）。
            val newToolParamsJson: String = params["params"]
                ?.let { PARAM_REGEX.findAll(it) }
                ?.associate { match ->
                    match.groupValues[1].trim() to match.groupValues[2].trim()
                }
                ?.takeIf { it.isNotEmpty() }
                ?.let { org.json.JSONObject(it as Map<*, *>).toString() }
                ?: existing.toolParamsJson

            // 解析新间隔
            val newRepeatIntervalMs: Long? = when {
                params.containsKey("interval_hours") -> {
                    val h = params["interval_hours"]!!.toDoubleOrNull() ?: 0.0
                    if (h > 0) (h * TimeUnit.HOURS.toMillis(1)).toLong() else null
                }
                else -> existing.repeatIntervalMs
            }

            // P1-13-12 修复：原逻辑无论是否传入 delay_hours 都会重算 newNextRunAt
            // = 当前时间 + delayHours（未传时 delayHours 默认 0.0），导致仅想改个
            // 标题/参数的调用也会把 nextRunAt 重置为"立即执行"，造成任务提前触发。
            // 改为：只有显式传入 delay_hours 时才按其重算；否则保留原 nextRunAt 不变。
            val newNextRunAt = if (params.containsKey("delay_hours")) {
                val delayHours = params["delay_hours"]!!.toDoubleOrNull() ?: 0.0
                System.currentTimeMillis() + (delayHours * TimeUnit.HOURS.toMillis(1)).toLong()
            } else {
                existing.nextRunAt
            }

            scheduleRepository.updateJob(
                id               = id,
                title            = newTitle,
                toolName         = newToolName,
                toolParamsJson   = newToolParamsJson,
                repeatIntervalMs = newRepeatIntervalMs,
                nextRunAt        = newNextRunAt,
            )

            // 同步更新系统日历事件（权限未授予时静默跳过）
            calendarSync?.updateEvent(
                jobId            = id,
                title            = newTitle,
                nextRunAt        = newNextRunAt,
                repeatIntervalMs = newRepeatIntervalMs,
            )

            // 取消旧 WorkRequest，重新按新时间入队
            context?.let {
                WorkManagerScheduler.cancel(it, id)
                val delayMs = (newNextRunAt - System.currentTimeMillis()).coerceAtLeast(0L)
                WorkManagerScheduler.enqueue(it, id, delayMs)
            }

            val repeatDesc = if (newRepeatIntervalMs != null) {
                val hours = newRepeatIntervalMs / TimeUnit.HOURS.toMillis(1).toDouble()
                "每 $hours 小时执行一次"
            } else "仅执行一次"

            ToolResult(
                toolName = name,
                success  = true,
                content  = "已更新定时任务「$newTitle」，$repeatDesc",
                userHint = "正在更新任务…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "", error = "更新失败：${e.message}")
        }
    }
}
