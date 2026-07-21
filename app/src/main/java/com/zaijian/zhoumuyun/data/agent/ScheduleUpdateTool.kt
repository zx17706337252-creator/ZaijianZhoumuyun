package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.ProjectRepository
import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import java.util.concurrent.TimeUnit

/**
 * Phase 30 · 修改定时任务工具
 *
 * 标签格式（工具型）：
 *   <tool:schedule_update
 *     id="任务ID"
 *     title="新标题"
 *     tool="新工具名"
 *     params="key1=\"val1\",key2=\"val2\""
 *     interval_hours="24"
 *     delay_hours="2"
 *   />
 *
 * 标签格式（切换为工单型）：
 *   <tool:schedule_update
 *     id="任务ID"
 *     mode="agent_task"
 *     description="提醒用户该喝水了，用角色自己的语气说一句关心的话"
 *   />
 *
 * 标签格式（只改工单描述，不切换模式）：
 *   <tool:schedule_update
 *     id="任务ID"
 *     description="换个语气：直接说『起来喝口水啦』"
 *   />
 *
 * 参数说明：
 *   id              要修改的任务 ID（必填）
 *   mode            任务模式（可选）：
 *                     - "agent_task"  切换为工单型（mode B），需提供 description
 *                     - "tool"        切换为工具型（mode A），需提供 tool（已注册）
 *                     - 不传          保持原模式，允许在原模式下单独改字段
 *   title           新任务标题（可选，不传则保持原值）
 *   tool            新工具名（工具型可选）
 *   params          新工具参数，格式 key1="val1",key2="val2"（工具型可选）
 *   description     工单描述（工单型可选；切换为工单型时未传则沿用原 description）
 *   interval_hours  新重复间隔（可选；0 = 改为一次性任务）
 *   delay_hours     重新调度延迟小时数（可选；不传 = 保留原 nextRunAt 不变）
 *
 * 日程系统批次2改动（详见实现方案第六节6.6）：
 *   - 新增 mode/description 参数，按 mode 三分支计算 (newToolName, newDescription)：
 *     * mode="agent_task"：description 未传则用 existing.description，仍空则报错；
 *       toolName 落 AgentTaskJobExecutor.SENTINEL 哨兵值。
 *     * mode="tool"：校验 tool 已注册，description 置 null。
 *     * 未传 mode：保持原模式；原是工单型→允许只改 description；原是工具型→允许只改 tool/params。
 *   - newToolParamsJson：当目标为工单型时强制 "{}"（工单型无 toolParams 概念），
 *     否则保持原 params 解析逻辑。
 *   - updateJob 调用透传 newDescription（替换批次1临时塞的 existing.description 占位）。
 *   - 修正工具自身 description 文案：补一句支持切换模式 / 单独改描述，让 LLM 知道
 *     新参数可用（否则链路接不上）。
 */
class ScheduleUpdateTool(
    private val scheduleRepository: ScheduleRepository,
    private val projectRepository: ProjectRepository? = null,
    private val calendarSync: CalendarSyncHelper? = null,
    private val context: android.content.Context? = null,
) : AgentTool {

    override val name = "schedule_update"
    override val description = "修改已创建的定时任务（标题/间隔/执行内容），需要提供任务ID"
    override val usageNotes = "支持两种模式互转：mode=\"agent_task\" 切换为工单型（由 description 描述任务，到点让角色自己推理回应）；mode=\"tool\" 切换为工具型（到点直接调指定工具）。也可不传 mode 只单独改 description（工单型）或 tool/params（工具型）。params 必须用 key=\"val1\",key2=\"val2\" 格式（值中允许逗号），不要传 JSON 对象。可选参数 project_id 关联到某个项目（需是已存在的项目 ID）；传空串可解除关联，不传则保留原关联。"
    override val paramKeys = listOf("id", "mode", "title", "tool", "params", "description", "project_id", "interval_hours", "delay_hours")

    // params 的 key="val" 解析（含逗号安全、JSON fallback）与 interval/delay 的
    // 数字校验已收口到 ScheduleToolParamUtil（批次1 审计问题1/问题2 修复），
    // 与 ScheduleCreateTool 共用同一份实现，不再各自维护正则。

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val id = params["id"]?.trim()
        if (id.isNullOrEmpty()) {
            return ToolResult(name, false, "", error = "id 参数不能为空")
        }

        return try {
            // 先查出原任务
            val existing = scheduleRepository.getJob(id)
                ?: return ToolResult(name, false, "", error = "找不到任务 ID: $id")

            // 批次2：按 mode 三分支计算 (newToolName, newDescription)。
            // 实现方案第六节6.6 给出的分叉逻辑，逐字落地：
            //   - mode="agent_task"：切换为工单型。description 未传则沿用 existing.description，
            //     仍空则报错（工单型必须有描述）。toolName 落哨兵值。
            //   - mode="tool"：切换为工具型。校验 tool 已注册（未传 tool 则沿用 existing.toolName，
            //     但若 existing 本就是工单型，existing.toolName 是哨兵值，必然不在注册表里 → 报错，
            //     这是合理的：从工单型切到工具型必须显式提供 tool）。description 置 null。
            //   - 未传 mode：保持原模式。
            //     * 原是工单型：允许只改 description（最常见编辑场景——"换个语气"），
            //       description 未传则沿用 existing.description。toolName 保持哨兵值。
            //     * 原是工具型：允许只改 tool/params，description 保持 null。
            //       tool 未传则沿用 existing.toolName（不再校验注册——existing 既然存在必然已注册过，
            //       除非跨版本删过工具，那是另一回事）。
            val newMode = params["mode"]?.trim()?.lowercase()
            val newToolName: String
            val newDescription: String?
            when (newMode) {
                AgentTaskJobExecutor.SENTINEL -> {
                    val d = params["description"]?.trim()?.takeIf { it.isNotEmpty() } ?: existing.description
                    if (d.isNullOrEmpty()) {
                        return ToolResult(name, false, "", error = "mode=agent_task 时 description 不能为空")
                    }
                    newToolName = AgentTaskJobExecutor.SENTINEL
                    newDescription = d
                }
                "tool" -> {
                    val t = params["tool"]?.trim()?.takeIf { it.isNotEmpty() } ?: existing.toolName
                    if (AgentToolRegistry.get(t) == null) {
                        return ToolResult(name, false, "", error = "工具「$t」未注册")
                    }
                    newToolName = t
                    newDescription = null
                }
                else -> {
                    // 未传 mode：保持原模式，允许在原模式下单独改字段。
                    if (existing.toolName == AgentTaskJobExecutor.SENTINEL) {
                        // 原是工单型：允许只改 description，toolName 保持哨兵值。
                        newToolName = existing.toolName
                        newDescription = params["description"]?.trim()?.takeIf { it.isNotEmpty() }
                            ?: existing.description
                    } else {
                        // 原是工具型：允许只改 tool/params，description 保持 null。
                        newToolName = params["tool"]?.trim()?.takeIf { it.isNotEmpty() } ?: existing.toolName
                        newDescription = null
                    }
                }
            }

            // 解析新工具参数（未传则保持原值）
            // 批次2：目标为工单型时强制 toolParamsJson = "{}"——工单型没有
            // toolParams 概念（方案2.2/6.5），即便 existing 是工具型带了一堆参数，
            // 切换为工单型后这些参数也无意义，落 "{}" 干净。
            //
            // 审计报告问题1（P1，静默失败）修复：ScheduleToolParamUtil.parseToolParams
            // 先尝试把 params 当 JSON 解析（含转义 JSON 的 \" 还原），失败再 fallback 到
            // key="val1",key2="val2" 格式。此前只认后者，裸 JSON / 转义 JSON 传入时
            // parsed 为空，会静默回退到 existing.toolParamsJson——看起来更新成功，
            // 实际 toolParams 完全没变，是本报告 U3/U4 用例点名的同一根因。
            val newToolParamsJson: String = when {
                newToolName == AgentTaskJobExecutor.SENTINEL -> "{}"
                params["params"] == null -> existing.toolParamsJson
                else -> {
                    val parsed = ScheduleToolParamUtil.parseToolParams(params["params"])
                    if (parsed.isEmpty()) {
                        existing.toolParamsJson
                    } else {
                        org.json.JSONObject(parsed as Map<*, *>).toString()
                    }
                }
            }

            val newTitle = params["title"]?.trim()?.takeIf { it.isNotEmpty() } ?: existing.title

            // 日程系统第七节：解析 project_id（三态语义，与 description 未传保留原值同款范式）。
            // - 未传 params["project_id"]：保留 existing.projectId（不改动关联状态）
            // - 传空串：显式解除关联 → newProjectId = null
            // - 传非空值：校验项目存在性，不存在则报错（不静默存悬空引用）
            // projectRepository 为 null 时：传非空值直接报错（更安全的失败方向，
            // 与 ScheduleCreateTool 同款处理），未传/空串不受影响。
            val newProjectId: String? = when {
                !params.containsKey("project_id") -> existing.projectId
                params["project_id"]?.trim()?.isEmpty() == true -> null
                else -> {
                    val raw = params["project_id"]!!.trim()
                    val repo = projectRepository
                        ?: return ToolResult(name, false, "", error = "project_id 校验失败：未注入 ProjectRepository")
                    if (repo.getById(raw) == null) {
                        return ToolResult(name, false, "", error = "项目「$raw」不存在，无法关联")
                    }
                    raw
                }
            }

            // 解析新间隔
            // 审计报告问题2（P1，静默降级）修复：containsKey 为 true 但值非数字时，
            // 此前 toDoubleOrNull() ?: 0.0 会静默把"改间隔但填错数字"变成"改成一次性
            // 任务"，没有任何错误提示。现改为非数字时直接返回明确错误。
            val newRepeatIntervalMs: Long? = when {
                params.containsKey("interval_hours") -> {
                    val h = ScheduleToolParamUtil.parseHoursOrError(params["interval_hours"], "interval_hours")
                        .getOrElse { return ToolResult(name, false, "", error = it.message) }
                    if (h > 0) (h * TimeUnit.HOURS.toMillis(1)).toLong() else null
                }
                else -> existing.repeatIntervalMs
            }

            // P1-13-12 修复：原逻辑无论是否传入 delay_hours 都会重算 newNextRunAt
            // = 当前时间 + delayHours（未传时 delayHours 默认 0.0），导致仅想改个
            // 标题/参数的调用也会把 nextRunAt 重置为"立即执行"，造成任务提前触发。
            // 改为：只有显式传入 delay_hours 时才按其重算；否则保留原 nextRunAt 不变。
            // 审计报告问题2（P1，静默降级）修复：同 interval_hours，非数字时报错而非
            // 静默按 0.0（立即执行）处理。
            val newNextRunAt = if (params.containsKey("delay_hours")) {
                val delayHours = ScheduleToolParamUtil.parseHoursOrError(params["delay_hours"], "delay_hours")
                    .getOrElse { return ToolResult(name, false, "", error = it.message) }
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
                description      = newDescription,
                projectId        = newProjectId,
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

            val modeDesc = if (newToolName == AgentTaskJobExecutor.SENTINEL) "工单型" else "工具型"

            ToolResult(
                toolName = name,
                success  = true,
                content  = "已更新${modeDesc}定时任务「$newTitle」，$repeatDesc",
                userHint = "正在更新任务…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "", error = "更新失败：${e.message}")
        }
    }
}
