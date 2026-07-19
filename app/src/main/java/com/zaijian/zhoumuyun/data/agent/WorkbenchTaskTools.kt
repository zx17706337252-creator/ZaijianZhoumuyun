package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.memory.MemoryEngine
import com.zaijian.zhoumuyun.data.repository.TaskRepository
import com.zaijian.zhoumuyun.util.ZLog

/**
 * 2.3 工作台任务跟踪修复 · WorkbenchTaskTools.kt
 *
 * ═══════════════════════════════════════════════════════════════
 * 工具列表（4个）
 * ═══════════════════════════════════════════════════════════════
 *
 *   ① TaskStartTool    — 开始一个工作台任务（task_start）
 *   ② TaskUpdateTool   — 更新进度/备注（task_update）
 *   ③ TaskCompleteTool — 标记完成（task_complete）
 *   ④ TaskCancelTool   — 取消任务（task_cancel）
 *
 * 背景：
 *   数据库层（TaskRepository/TaskDao/TaskEntity）原本就是完整的，
 *   ChatViewModel 每轮也确实会把"该角色当前 RUNNING/PENDING 的任务"
 *   组装进 taskLayerBlock 注入 Prompt——但此前完全没有入口能创建/更新
 *   这些任务，taskLayerBlock 永远是空的（数据库里没有任务）。
 *
 *   这 4 个工具补上这个入口：角色在对话中可以主动说"我现在开始帮你
 *   做XX"，通过 <tool:task_start.../> 落库成一条 RUNNING 任务；
 *   之后这条任务就会持续出现在该角色每一轮的 Prompt 里，让角色"记得"
 *   自己在做这件事，直到它自己调用 task_complete/task_cancel 收尾。
 *
 * 任务定位方式：
 *   task_update/task_complete/task_cancel 都不强制要求 LLM 记住 task_id
 *   （文本生成模型跨轮记 UUID 不现实）。优先用 title 模糊匹配该角色名下
 *   "进行中"的任务，匹配不到时回退到"最近一条进行中任务"。
 *
 * 注册方式：与 PlanSaveTool 等角色绑定工具一致，characterId 闭包由
 * ChatViewModel.init(characterId) 动态覆盖注册；App 启动阶段在 ZaijianApp.kt
 * 内以 characterId = { -1 } 静态占位注册（问题40修复，此前完全没有这层占位）。
 *
 * 角色 ID 读取优先级（问题40修复，与 self_reflect/rule_review 同一套）：
 * execute() 内优先读 params["__character_id"]（WorkflowEngine 后台执行工作流
 * 任务时会注入该任务绑定的 characterId），读不到才回退到闭包 characterId()
 * （前台聊天场景）；charId < 0 时直接拒绝执行，不静默把无效角色写进任务表。
 */

// ─────────────────────────────────────────────────────────────
//  ① TaskStartTool
// ─────────────────────────────────────────────────────────────

/**
 * 开始一个工作台任务。
 *
 * 标签格式：<tool:task_start title="任务标题" description="任务说明"/>
 * 落库为 RUNNING 状态，source="chat_tool"，立刻可被下一轮 taskLayerBlock 读取。
 */
class TaskStartTool(
    private val taskRepo: TaskRepository,
    private val characterId: () -> Int,
) : AgentTool {

    override val name      = "task_start"
    override val description = "创建并开始一个工作台任务（状态RUNNING），用于「开始跟进/记录一个任务」"
    override val paramKeys = listOf("title", "description")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val title = params["title"]?.trim()?.take(60)
            ?: return ToolResult(name, false, "", "缺少 title 参数（任务标题）")
        val description = params["description"]?.trim() ?: ""
        // 问题40修复：与 self_reflect/rule_review 同一套读取优先级——工作流引擎
        // 会注入 __character_id（该任务本就绑定的角色），优先读它；不存在时
        // （前台聊天场景）回退到闭包 characterId()。charId < 0 时直接拒绝，
        // 不静默把无效角色写进任务表（此前无此保护，会直接落库成 -1）。
        val charId = params["__character_id"]?.toIntOrNull() ?: characterId()
        if (charId < 0) {
            return ToolResult(name, false, "", "角色未初始化")
        }

        return try {
            taskRepo.createTask(
                title       = title,
                description = description,
                characterId = charId,
            )
            ToolResult(
                toolName = name,
                success  = true,
                content  = "[任务已开始]\n标题：$title${if (description.isNotBlank()) "\n说明：$description" else ""}\n\n你现在记住了这个正在进行的任务，之后每一轮都会被提醒，直到你标记它完成或取消。",
                userHint = "正在开始一个任务…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "开始任务时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ② TaskUpdateTool
// ─────────────────────────────────────────────────────────────

/**
 * 更新进行中任务的进度/备注，不改变状态（仍是 RUNNING）。
 *
 * 标签格式：<tool:task_update title="任务标题（可选，用于匹配）" progress="0.6" note="最新进展"/>
 * title 缺省时，回退到该角色名下"最近一条进行中任务"。
 */
class TaskUpdateTool(
    private val taskRepo: TaskRepository,
    private val characterId: () -> Int,
) : AgentTool {

    override val name      = "task_update"
    override val description = "更新进行中任务的进度百分比和备注，不改变任务状态"
    override val paramKeys = listOf("title", "progress", "note")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val titleHint = params["title"]?.trim()?.takeIf { it.isNotBlank() }
        val note      = params["note"]?.trim() ?: ""
        val progress  = params["progress"]?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f)
        // 问题40修复：同 TaskStartTool，优先读工作流注入的 __character_id
        val charId = params["__character_id"]?.toIntOrNull() ?: characterId()
        if (charId < 0) {
            return ToolResult(name, false, "", "角色未初始化")
        }

        return try {
            val task = taskRepo.findActiveTask(charId, titleHint)
                ?: return ToolResult(name, false, "", "没有找到进行中的任务，先用 task_start 开始一个吧")

            taskRepo.updateDescription(
                id          = task.id,
                description = note,
                progress    = progress ?: task.progress,
            )
            ToolResult(
                toolName = name,
                success  = true,
                content  = "[任务进度已更新]\n标题：${task.title}${if (note.isNotBlank()) "\n最新进展：$note" else ""}${progress?.let { "\n进度：${(it * 100).toInt()}%" } ?: ""}",
                userHint = "正在更新任务进度…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "更新任务进度时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ③ TaskCompleteTool
// ─────────────────────────────────────────────────────────────

/**
 * 标记任务完成。完成后该任务不再出现在 taskLayerBlock 里，
 * 且会产生 TASK_COMPLETED 事件，触发 MemoryEngine 生成记忆候选。
 *
 * 标签格式：<tool:task_complete title="任务标题（可选）" result="任务结果总结"/>
 */
class TaskCompleteTool(
    private val taskRepo: TaskRepository,
    private val characterId: () -> Int,
    // W3-2 修复：注入 memoryEngine 用于任务完成后写记忆。用可空闭包而非直接持有
    // MemoryEngine 实例，是为了兼容 ZaijianApp.kt 里"先静态占位注册、
    // ChatViewModel.init() 再动态覆盖"的两阶段注册模式——静态占位阶段
    // AppContainer 已初始化，此处始终可以拿到同一个共享 MemoryEngine 实例，
    // 保留闭包只是为了与 characterId 的写法保持一致、便于未来测试替身注入。
    private val memoryEngine: () -> MemoryEngine? = { null },
) : AgentTool {

    override val name      = "task_complete"
    override val description = "标记任务为已完成，触发记忆候选生成"
    override val paramKeys = listOf("title", "result")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val titleHint = params["title"]?.trim()?.takeIf { it.isNotBlank() }
        val result    = params["result"]?.trim()
            ?: return ToolResult(name, false, "", "缺少 result 参数（任务结果总结）")
        // 问题40修复：同 TaskStartTool，优先读工作流注入的 __character_id
        val charId = params["__character_id"]?.toIntOrNull() ?: characterId()
        if (charId < 0) {
            return ToolResult(name, false, "", "角色未初始化")
        }

        return try {
            val task = taskRepo.findActiveTask(charId, titleHint)
                ?: return ToolResult(name, false, "", "没有找到进行中的任务可以标记完成")

            taskRepo.completeTask(id = task.id, resultSummary = result)

            // W3-2 修复：任务完成后从未调用 memoryEngine.onTaskCompleted()，
            // 是最有记忆价值的事件之一被静默丢弃。这里补上调用；记忆写入失败
            // 不应影响任务完成本身已经成功的结果，故单独 try-catch、仅记录日志。
            try {
                memoryEngine()?.onTaskCompleted(
                    characterId   = charId,
                    taskTitle     = task.title,
                    resultSummary = result,
                    toolName      = null,
                    sourceEventId = task.id,
                )
            } catch (e: Exception) {
                ZLog.e("TaskCompleteTool", "任务完成记忆写入失败 taskId=${task.id}", e)
            }

            ToolResult(
                toolName = name,
                success  = true,
                content  = "[任务已完成]\n标题：${task.title}\n结果：$result",
                userHint = "正在完成任务…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "标记任务完成时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ④ TaskCancelTool
// ─────────────────────────────────────────────────────────────

/**
 * 取消任务（用户改变想法，或角色判断这件事不用做了）。
 *
 * 标签格式：<tool:task_cancel title="任务标题（可选）" reason="取消原因（可选）"/>
 */
class TaskCancelTool(
    private val taskRepo: TaskRepository,
    private val characterId: () -> Int,
) : AgentTool {

    override val name      = "task_cancel"
    override val description = "取消一个任务（用户改主意或角色判断不用做了）"
    override val paramKeys = listOf("title", "reason")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val titleHint = params["title"]?.trim()?.takeIf { it.isNotBlank() }
        val reason    = params["reason"]?.trim() ?: ""
        // 问题40修复：同 TaskStartTool，优先读工作流注入的 __character_id
        val charId = params["__character_id"]?.toIntOrNull() ?: characterId()
        if (charId < 0) {
            return ToolResult(name, false, "", "角色未初始化")
        }

        return try {
            val task = taskRepo.findActiveTask(charId, titleHint)
                ?: return ToolResult(name, false, "", "没有找到进行中的任务可以取消")

            taskRepo.cancelTask(task.id)
            ToolResult(
                toolName = name,
                success  = true,
                content  = "[任务已取消]\n标题：${task.title}${if (reason.isNotBlank()) "\n原因：$reason" else ""}",
                userHint = "正在取消任务…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "取消任务时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}
