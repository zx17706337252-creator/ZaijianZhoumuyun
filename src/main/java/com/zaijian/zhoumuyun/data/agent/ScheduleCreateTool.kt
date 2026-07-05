package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import java.util.concurrent.TimeUnit

/**
 * Phase 29 · 创建定时任务工具
 *
 * 标签格式：
 *   <tool:schedule_create
 *     title="每日AI新闻"
 *     tool="web_search"
 *     params="query=\"上海,北京天气\""
 *     interval_hours="24"
 *     delay_hours="0"
 *   />
 *
 * 参数说明：
 *   title           任务标题（必填）
 *   tool            要执行的工具名（必填），必须已在 AgentToolRegistry 注册
 *   params          工具参数，格式：key="val1",key2="val2"（值中允许逗号，须用引号包裹）
 *   interval_hours  重复间隔小时数，0 或不填 = 一次性任务
 *   delay_hours     首次执行延迟小时数，默认 0（立即纳入调度）
 */
class ScheduleCreateTool(
    private val scheduleRepository: ScheduleRepository,
    private val characterIdProvider: () -> Int,
    private val calendarSync: CalendarSyncHelper? = null,
    private val context: android.content.Context? = null,
) : AgentTool {

    override val name = "schedule_create"
    override val paramKeys = listOf("title", "tool", "params", "interval_hours", "delay_hours")

    private companion object {
        // P2 修复（二次）：改为带引号格式 key="value"，与 ToolParser.ATTR_PATTERN 保持一致。
        // 旧正则 (\w+)=([^,]+) 的值部分仍以逗号为终止符，无法处理值中含逗号的场景。
        // 新正则 group(1)=key, group(2)=value（引号内内容，允许 \" 转义，允许逗号）。
        val PARAM_REGEX = Regex("""(\w+)="((?:[^"\\]|\\.)*)"""")
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val title = params["title"]?.trim()
        val toolName = params["tool"]?.trim()

        if (title.isNullOrEmpty()) {
            return ToolResult(name, false, "", error = "title 参数不能为空")
        }
        if (toolName.isNullOrEmpty()) {
            return ToolResult(name, false, "", error = "tool 参数不能为空")
        }

        // 解析工具参数（格式：key="val1",key2="val2 含逗号也安全"）
        // 用带引号正则 findAll 扫描，值中的逗号不再被截断
        val toolParams: Map<String, String> = params["params"]
            ?.let { PARAM_REGEX.findAll(it) }
            ?.associate { match ->
                match.groupValues[1].trim() to match.groupValues[2].trim()
            }
            ?: emptyMap()

        // 解析时间
        val intervalHours = params["interval_hours"]?.toDoubleOrNull() ?: 0.0
        val delayHours = params["delay_hours"]?.toDoubleOrNull() ?: 0.0

        val repeatIntervalMs = if (intervalHours > 0) {
            (intervalHours * TimeUnit.HOURS.toMillis(1)).toLong()
        } else null

        val nextRunAt = System.currentTimeMillis() +
                (delayHours * TimeUnit.HOURS.toMillis(1)).toLong()

            // 写入任务（本地 + 云端）
            return try {
                val jobId = scheduleRepository.createJob(
                    characterId      = params["__character_id"]?.toIntOrNull() ?: characterIdProvider(),
                    title            = title,
                    toolName         = toolName,
                    toolParams       = toolParams,
                    repeatIntervalMs = repeatIntervalMs,
                    nextRunAt        = nextRunAt,
                )

                // 同步到系统日历（权限未授予时静默跳过）
                calendarSync?.insertEvent(
                    jobId            = jobId,
                    title            = title,
                    nextRunAt        = nextRunAt,
                    repeatIntervalMs = repeatIntervalMs,
                )

                // 注册 WorkManager 后台调度
                context?.let {
                    val delayMs = (nextRunAt - System.currentTimeMillis()).coerceAtLeast(0L)
                    WorkManagerScheduler.enqueue(it, jobId, delayMs)
                }

                val repeatDesc = if (repeatIntervalMs != null) {
                    "每 $intervalHours 小时执行一次"
                } else "仅执行一次"

            ToolResult(
                toolName = name,
                success  = true,
                content  = "已创建定时任务「$title」，$repeatDesc，任务ID: $jobId",
                userHint = "正在创建定时任务…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "创建定时任务失败：${e.message?.take(80)}", e.message)
        }
    }
}
