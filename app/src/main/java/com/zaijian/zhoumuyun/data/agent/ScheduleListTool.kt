package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.ProjectRepository
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
 *
 * 日程系统批次2改动（详见实现方案第六节6.4）：
 *   拼文本逻辑按 `job.toolName == AgentTaskJobExecutor.SENTINEL` 分叉：
 *     - 工单型（mode B）：展示 description 预览（take(40) + 超长加省略号，空则"（无描述）"），
 *       行首标签"工单内容:"，不再展示内部哨兵值"agent_task"这个对用户无意义的字面量。
 *     - 工具型（mode A，现状）：保持原"工具: xxx  周期/下次"一行不变。
 */
class ScheduleListTool(
    private val scheduleRepository: ScheduleRepository,
    private val characterIdProvider: () -> Int,
    private val projectRepository: ProjectRepository? = null,
) : AgentTool {

    override val name = "schedule_list"
    override val description = "列出某角色近期（默认7天内）的定时任务，用于「我最近有什么安排」这类查询"
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

            // 日程系统第七节：批量查项目标题，避免 N+1 查询。
            // 先 collect 所有非空 projectId，一次性 getByIds 取回，再按 id 映射成
            // "关联项目: <标题>" 片段。projectRepository 为 null（旧调用方未注入）
            // 或查不到时，fallback 显示 projectId 前 8 位——不让悬空引用导致整行展示失败。
            val projectTitleById: Map<String, String> = run {
                val ids = jobs.mapNotNull { it.projectId }.distinct()
                if (ids.isEmpty() || projectRepository == null) emptyMap()
                else projectRepository.getByIds(ids).associate { it.id to it.title }
            }
            fun projectTag(job: com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity): String {
                val pid = job.projectId ?: return ""
                val title = projectTitleById[pid] ?: pid.take(8)
                return "  关联项目: $title"
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
                    // 批次2：按是否工单型分叉展示第二行内容。
                    if (job.toolName == AgentTaskJobExecutor.SENTINEL) {
                        // 工单型：展示 description 预览，不展示内部哨兵值字面量。
                        // take(40) + 超长加省略号；空 description 兜底为"（无描述）"
                        // （理论上 create/update 已强校验非空，这里兜底防御脏数据）。
                        val rawDesc = job.description
                        val descPreview = if (rawDesc.isNullOrEmpty()) {
                            "（无描述）"
                        } else if (rawDesc.length > 40) {
                            rawDesc.take(40) + "…"
                        } else {
                            rawDesc
                        }
                        appendLine("   工单内容: $descPreview  周期: $repeatDesc  下次: $nextDesc${projectTag(job)}")
                    } else {
                        // 工具型：保持原逻辑不变。
                        appendLine("   工具: ${job.toolName}  周期: $repeatDesc  下次: $nextDesc${projectTag(job)}")
                    }
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
