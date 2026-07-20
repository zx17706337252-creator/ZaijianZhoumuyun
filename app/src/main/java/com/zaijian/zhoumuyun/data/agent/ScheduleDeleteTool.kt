package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.ScheduleRepository

/**
 * Phase 30 · 删除定时任务工具
 *
 * 标签格式：
 *   <tool:schedule_delete id="任务ID" />
 *
 * 参数说明：
 *   id   要删除的任务 ID（必填，由 schedule_create 返回）
 */
class ScheduleDeleteTool(
    private val scheduleRepository: ScheduleRepository,
    private val calendarSync: CalendarSyncHelper? = null,
    private val context: android.content.Context? = null,
) : AgentTool {

    override val name = "schedule_delete"
    override val description = "删除/取消一个已创建的定时任务，需要提供任务ID"
    override val paramKeys = listOf("id")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val id = params["id"]?.trim()

        if (id.isNullOrEmpty()) {
            return ToolResult(name, false, "", error = "id 参数不能为空")
        }

        return try {
            // P2 修复（Batch5/Batch6审查报告问题3）：原实现不检查 id 是否存在就直接
            // deleteJob（DAO 层 DELETE WHERE id=x 对不存在的 id 本就是 0 行受影响，
            // 不会报错），导致 LLM 传一个已删除或从未存在的 id 时仍收到 success=true，
            // 以为删除生效了。现先查一次 getJob(id)，不存在则显式返回错误。
            val existing = scheduleRepository.getJob(id)
                ?: return ToolResult(name, false, "", error = "未找到 ID 为 $id 的定时任务，可能已被删除")

            scheduleRepository.deleteJob(id)
            // 同步删除系统日历事件（权限未授予时静默跳过）
            calendarSync?.deleteEvent(id)
            // 取消 WorkManager 中对应的 WorkRequest
            context?.let { WorkManagerScheduler.cancel(it, id) }
            ToolResult(
                toolName = name,
                success  = true,
                content  = "已删除定时任务（ID: $id）",
                userHint = "正在删除任务…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "", error = "删除失败：${e.message}")
        }
    }
}
