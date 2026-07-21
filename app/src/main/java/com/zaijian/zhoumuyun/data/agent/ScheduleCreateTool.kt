package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.ProjectRepository
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
 * 或工单型（mode B，日程系统批次2新增）：
 *   <tool:schedule_create
 *     title="喝水提醒"
 *     mode="agent_task"
 *     description="提醒用户该喝水了，用角色自己的语气说一句关心的话"
 *     interval_hours="3"
 *   />
 *
 * 参数说明：
 *   title           任务标题（必填）
 *   mode            任务模式（可选；默认按工具型处理）：
 *                     - "agent_task"  工单型（mode B），到点把 description 作为系统触发消息
 *                                    注入角色对话管线走完整 LLM 推理，由角色自己判断要不要
 *                                    调工具、要说什么。适合「提醒喝水」这类没有现成工具可
 *                                    精确映射、或需要角色语言表达的模糊任务
 *                     - 其余值/不传  工具型（mode A，现状），到点直接调对应工具
 *   tool            要执行的工具名（工具型必填），必须已在 AgentToolRegistry 注册
 *   description     工单描述（工单型必填，工具型忽略）。自由文本，描述"日程要做的一件事"
 *   params          工具参数，格式：key="val1",key2="val2"（值中允许逗号，须用引号包裹）
 *   interval_hours  重复间隔小时数，0 或不填 = 一次性任务
 *   delay_hours     首次执行延迟小时数，默认 0（立即纳入调度）
 *
 * 日程系统批次2改动（详见实现方案第六节6.1）：
 *   - 新增 mode/description 参数，mode="agent_task" 时 toolName 落 AgentTaskJobExecutor.SENTINEL
 *     哨兵值（批次3 起统一收口于 AgentTaskJobExecutor.SENTINEL，全项目唯一真相源），
 *     跳过"工具已注册"校验，改校验 description 非空。
 *     Repository.createJob 的 description 形参已在批次1就绪。
 *   - 修正工具自身 description 文案：原"不适合「提醒我做事」这类单次口头约定（那种用 reminder）"
 *     指向一个项目里不存在的 reminder 工具，与新增的 mode=agent_task 路径自相矛盾，
 *     予以删除，改为引导 LLM 使用 agent_task 模式。
 */
class ScheduleCreateTool(
    private val scheduleRepository: ScheduleRepository,
    private val characterIdProvider: () -> Int,
    private val projectRepository: ProjectRepository? = null,
    private val calendarSync: CalendarSyncHelper? = null,
    private val context: android.content.Context? = null,
) : AgentTool {

    override val name = "schedule_create"
    override val description = "创建自动化定时任务，支持工具型和工单型两种模式"
    override val usageNotes = "两种模式：(A) 工具型，到点执行指定已注册工具（如每天定时web_search）；(B) 工单型 mode=\"agent_task\"，到点把 description 作为触发消息让角色自己推理回应，适合「提醒我喝水」这类没有现成工具可精确映射、需要角色语言表达的模糊任务。工具型需要明确的可重复执行动作，工单型只需写清楚要做什么。params 必须用 key=\"val1\",key2=\"val2\" 格式（值中允许逗号），不要传 JSON 对象。可选参数 project_id 关联到某个项目（需是已存在的项目 ID），用于把日程挂载到项目上。"
    override val paramKeys = listOf("title", "mode", "tool", "params", "description", "project_id", "interval_hours", "delay_hours")

    // params 的 key="val" 解析（含逗号安全、JSON fallback）与 interval/delay 的
    // 数字校验已收口到 ScheduleToolParamUtil（批次1 审计问题1/问题2 修复），
    // ScheduleCreateTool/ScheduleUpdateTool 共用同一份实现，不再各自维护正则。

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val title = params["title"]?.trim()
        if (title.isNullOrEmpty()) {
            return ToolResult(name, false, "", error = "title 参数不能为空")
        }

        // 日程系统批次2：按 mode 分叉校验与 toolName 落值。
        //   mode="agent_task" → 工单型（mode B），toolName 落哨兵值，跳过工具注册校验，
        //                       改校验 description 非空。工单型没有 toolParams 概念，
        //                       params 字段对工单型无意义，这里不强制报错（保持向后兼容，
        //                       LLM 即使误传也不会让创建失败，落库时会被 Repository 忽略）。
        //   其余/未传 mode    → 工具型（mode A，现状），toolName 必填且必须已注册。
        val mode = params["mode"]?.trim()?.lowercase()
        val isAgentTask = mode == AgentTaskJobExecutor.SENTINEL

        val toolName: String
        val description: String?

        if (isAgentTask) {
            val desc = params["description"]?.trim()
            if (desc.isNullOrEmpty()) {
                return ToolResult(name, false, "", error = "mode=agent_task 时 description 参数不能为空")
            }
            toolName = AgentTaskJobExecutor.SENTINEL
            description = desc
        } else {
            val t = params["tool"]?.trim()
            if (t.isNullOrEmpty()) {
                return ToolResult(name, false, "", error = "tool 参数不能为空")
            }
            // 修复（第4窗口审查报告问题5）：创建定时任务前校验 tool 是否已在 AgentToolRegistry 注册，
            // 避免创建出指向不存在工具的任务——此前该错误要等到 ScheduledJobWorker 实际触发执行时才会暴露，
            // 提前到创建阶段拦截，用户能立刻得到反馈并修正。
            if (AgentToolRegistry.get(t) == null) {
                return ToolResult(name, false, "", error = "工具「$t」未注册，无法创建定时任务")
            }
            toolName = t
            description = null
        }

        // 解析工具参数（格式：key="val1",key2="val2 含逗号也安全"，也兼容 JSON）
        // 工单型任务无 toolParams 概念，这里仍按原逻辑解析（即便 LLM 误传也不会报错），
        // 实际落库时 Repository 会把它写进 toolParamsJson——不影响工单执行路径，
        // 工单执行（批次3 AgentTaskJobExecutor）只读 description，不读 toolParamsJson。
        //
        // 审计报告问题1（P1，静默失败）修复：
        // ToolParser 的 findBalancedJsonEnd 会把 params="{...}" 这种"值是未转义 JSON"
        // 的写法整段原文摘出来交给这里，但 description 没告诉 LLM 该用 key="val" 格式，
        // LLM 偏向直接塞 JSON 对象的概率不低。此前只用 PARAM_REGEX 解析 key="val" 逗号
        // 分隔格式，完全不认 JSON，裸 JSON / 转义 JSON 都解出空 Map，execute 却仍返回
        // success——数据真实落库但内容是空的，比崩溃更难发现。
        // 现改为：先尝试把 params 当 JSON 解析（转义 JSON 先做 \" → " 还原），成功则
        // 按 JSON 键值取 toolParams；失败再 fallback 到现有 PARAM_REGEX，两种格式都兜住。
        val toolParams: Map<String, String> = ScheduleToolParamUtil.parseToolParams(params["params"])

        // 日程系统第七节：解析并校验可选的 project_id（关联项目）。
        // - 未传 / 空串 → projectId = null（独立日程，不关联任何项目）
        // - 非空 → 必须能在 ProjectRepository 查到，否则报错（不静默存悬空引用，
        //   避免后续展示侧 fallback 显示 projectId 前 8 位的糟糕体验）。
        // - projectRepository 为 null（旧调用方未注入）时：非空 project_id 直接报错，
        //   不允许"未注入校验器却放过校验"——这是更安全的失败方向。
        val rawProjectId = params["project_id"]?.trim()?.takeIf { it.isNotEmpty() }
        val projectId: String? = if (rawProjectId != null) {
            val repo = projectRepository
                ?: return ToolResult(name, false, "", error = "project_id 校验失败：未注入 ProjectRepository")
            val project = repo.getById(rawProjectId)
            if (project == null) {
                return ToolResult(name, false, "", error = "项目「$rawProjectId」不存在，无法关联")
            }
            rawProjectId
        } else {
            null
        }

        // 解析时间
        // 审计报告问题2（P1，静默降级）修复：非数字时不再 ?: 0.0 静默降级，
        // 而是返回明确错误——此前 LLM 想要重复任务但 interval_hours 填错时，
        // 会被悄悄创建成一次性任务，且没有任何错误提示。
        val intervalHours = ScheduleToolParamUtil.parseHoursOrError(params["interval_hours"], "interval_hours")
            .getOrElse { return ToolResult(name, false, "", error = it.message) }
        val delayHours = ScheduleToolParamUtil.parseHoursOrError(params["delay_hours"], "delay_hours")
            .getOrElse { return ToolResult(name, false, "", error = it.message) }

        val repeatIntervalMs = if (intervalHours > 0) {
            (intervalHours * TimeUnit.HOURS.toMillis(1)).toLong()
        } else null

        val nextRunAt = System.currentTimeMillis() +
                (delayHours * TimeUnit.HOURS.toMillis(1)).toLong()

            // 写入任务（本地 + 云端）
            return try {
                val charId = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()
                if (charId < 0) {
                    return ToolResult(name, false, "", error = "创建定时任务需要指定角色，当前会话未绑定角色")
                }
                val jobId = scheduleRepository.createJob(
                    characterId      = charId,
                    title            = title,
                    toolName         = toolName,
                    toolParams       = toolParams,
                    repeatIntervalMs = repeatIntervalMs,
                    nextRunAt        = nextRunAt,
                    description      = description,
                    projectId        = projectId,
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

                val modeDesc = if (isAgentTask) "工单型" else "工具型"

            ToolResult(
                toolName = name,
                success  = true,
                content  = "已创建${modeDesc}定时任务「$title」，$repeatDesc，任务ID: $jobId",
                userHint = "正在创建定时任务…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "创建定时任务失败：${e.message?.take(80)}", e.message)
        }
    }
}
