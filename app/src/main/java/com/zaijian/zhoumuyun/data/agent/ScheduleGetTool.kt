package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.ProjectRepository
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
 *
 * 日程系统批次2改动（详见实现方案第六节6.5）：
 *   拼文本逻辑按 `job.toolName == AgentTaskJobExecutor.SENTINEL` 分叉：
 *     - 工单型（mode B）：输出完整 description（不截断，schedule_get 本就是查单条详情），
 *       标签"工单内容:"，不输出"参数:"那一行（工单型没有 toolParams 概念）。
 *     - 工具型（mode A，现状）：保持原"工具:"+"参数:"两行不变。
 */
class ScheduleGetTool(
    private val scheduleRepository: ScheduleRepository,
    private val projectRepository: ProjectRepository? = null,
    // 跨角色越权修复：与 ScheduleDeleteTool/ScheduleUpdateTool 同款——原实现
    // 完全没有 characterId 概念，任何角色都能查到其他角色名下定时任务的详情。
    private val characterIdProvider: () -> Int = { -1 },
) : AgentTool {

    override val name = "schedule_get"
    override val description = "查询单个定时任务的详情，需要提供 schedule_create 返回的任务ID"
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

        val charId = characterIdProvider()
        if (charId < 0) {
            return ToolResult(name, false, "", error = "角色未初始化")
        }

        return try {
            val job = scheduleRepository.getJob(id)
                ?: return ToolResult(name, false, "", error = "找不到任务 ID: $id")

            // 跨角色越权修复：job.characterId 与当前角色不一致时按"找不到"处理，
            // 不暴露该 id 属于其他角色的事实。
            if (job.characterId != charId) {
                return ToolResult(name, false, "", error = "找不到任务 ID: $id")
            }

            val repeatDesc = if (job.repeatIntervalMs != null) {
                val hours = job.repeatIntervalMs / TimeUnit.HOURS.toMillis(1).toDouble()
                "每 $hours 小时"
            } else "一次性"

            val nextRunDesc = formatMillis(job.nextRunAt)
            val lastRunDesc = job.lastRunAt?.let { formatMillis(it) } ?: "尚未执行"
            val statusDesc  = if (job.enabled) "启用" else "已禁用"

            val isAgentTask = job.toolName == AgentTaskJobExecutor.SENTINEL

            // 批次2：工具参数描述只在工具型分支计算——工单型没有 toolParams 概念，
            // 无意义解析 JSONObject 反而可能因脏数据抛异常。这里按需解析。
            val toolParamsDesc = if (!isAgentTask) {
                try {
                    val json = org.json.JSONObject(job.toolParamsJson)
                    json.keys().asSequence()
                        .map { k -> "$k=${json.getString(k)}" }
                        .joinToString(", ")
                        .ifEmpty { "（无参数）" }
                } catch (_: Exception) { job.toolParamsJson }
            } else {
                null  // 工单型不展示参数行
            }

            // 日程系统第七节：查项目标题（单条 getById，无 N+1 问题）。
            // projectRepository 为 null 或查不到时，fallback 显示 projectId 前 8 位，
            // 与 ScheduleListTool 的 projectTag 同款兜底策略。
            val projectLine: String? = job.projectId?.let { pid ->
                val title = if (projectRepository != null) {
                    projectRepository.getById(pid)?.title ?: pid.take(8)
                } else {
                    pid.take(8)
                }
                "  关联项目 : $title"
            }

            val content = buildString {
                appendLine("任务详情：")
                appendLine("  ID       : $id")
                appendLine("  标题     : ${job.title}")
                if (isAgentTask) {
                    // 工单型：展示完整 description（不截断），不输出"工具:"/"参数:"两行。
                    // 理论上 description 非空（create/update 已强校验），这里兜底防御脏数据。
                    appendLine("  工单内容 : ${job.description ?: "（无描述）"}")
                } else {
                    // 工具型：保持原"工具:"+"参数:"两行。
                    appendLine("  工具     : ${job.toolName}")
                    appendLine("  参数     : $toolParamsDesc")
                }
                // 关联项目（两种模式通用，独立于工单/工具型分叉）
                if (projectLine != null) appendLine(projectLine)
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
