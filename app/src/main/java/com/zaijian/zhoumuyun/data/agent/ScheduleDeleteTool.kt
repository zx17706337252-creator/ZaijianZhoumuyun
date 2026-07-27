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
    // 跨角色越权修复：此前本工具完全没有 characterId 概念，只按任务 id 操作，
    // 任何角色的会话只要知道（或猜到）一个 id 就能删除属于其他角色的定时任务。
    // 与 schedule_update/schedule_get 同款修复，characterIdProvider 由
    // ChatToolRegistrar.registerCharacterTools() 用 currentCharacterId 覆盖
    // ZaijianApp 静态注册的 {-1} 占位。
    private val characterIdProvider: () -> Int = { -1 },
) : AgentTool {

    override val name = "schedule_delete"
    override val description = "删除/取消一个已创建的定时任务，需要提供任务ID"
    override val paramKeys = listOf("id")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val id = params["id"]?.trim()

        if (id.isNullOrEmpty()) {
            return ToolResult(name, false, "", error = "id 参数不能为空")
        }

        val charId = characterIdProvider()
        if (charId < 0) {
            return ToolResult(name, false, "", error = "角色未初始化")
        }

        return try {
            // P2 修复（Batch5/Batch6审查报告问题3）：原实现不检查 id 是否存在就直接
            // deleteJob（DAO 层 DELETE WHERE id=x 对不存在的 id 本就是 0 行受影响，
            // 不会报错），导致 LLM 传一个已删除或从未存在的 id 时仍收到 success=true，
            // 以为删除生效了。现先查一次 getJob(id)，不存在则显式返回错误。
            val existing = scheduleRepository.getJob(id)
                ?: return ToolResult(name, false, "", error = "未找到 ID 为 $id 的定时任务，可能已被删除")

            // 跨角色越权修复：existing.characterId 与当前角色不一致时拒绝删除，
            // 防止角色A的会话删除角色B名下的定时任务。
            if (existing.characterId != charId) {
                return ToolResult(name, false, "", error = "未找到 ID 为 $id 的定时任务，可能已被删除")
            }

            scheduleRepository.deleteJob(id)

            // P1-21 修复：同步步骤独立异常隔离——deleteJob 已落库，
            // 日历/WorkManager 失败不应导致整体返回失败（否则 LLM 重试对已删除 id 再次删除）。
            var syncWarning = ""
            try {
                // 同步删除系统日历事件（权限未授予时静默跳过）
                calendarSync?.deleteEvent(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("ScheduleDeleteTool", "Calendar sync failed for job $id", e)
                syncWarning = "（日历同步失败，不影响任务）"
            }
            try {
                // 取消 WorkManager 中对应的 WorkRequest
                context?.let { WorkManagerScheduler.cancel(it, id) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("ScheduleDeleteTool", "WorkManager cancel failed for job $id", e)
                syncWarning = if (syncWarning.isEmpty()) "（后台调度取消失败，不影响任务删除）"
                              else "$syncWarning，后台调度取消失败"
            }

            ToolResult(
                toolName = name,
                success  = true,
                content  = "已删除定时任务（ID: $id）$syncWarning",
                userHint = "正在删除任务…",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "删除定时任务失败，请稍后重试。", "schedule_delete_failed", e)
        }
    }
}
