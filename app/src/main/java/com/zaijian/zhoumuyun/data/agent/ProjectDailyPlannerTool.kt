package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.dao.CharacterGoalDao
import com.zaijian.zhoumuyun.data.db.dao.ProjectDao
import com.zaijian.zhoumuyun.data.db.dao.TaskDao
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 成长系统 · 每日自我规划工具（Step 4）
 *
 * 由 ScheduledJobWorker 在后台（App可关闭）定时唤醒执行。
 * 也可由角色在对话中直接调用（<tool:project_daily_planner .../>）。
 *
 * 执行流程：
 *   1. 去重——今天是否已为该项目/角色规划过，是则跳过
 *   2. 拼上下文——项目描述 + 角色阶段目标 + 最近5天任务
 *   3. 调LLM生成今日计划（最多4条）
 *   4. 写入 TaskEntity（source="project_growth"）
 *   5. 返回 ToolResult，ScheduledJobWorker 自动用 content 发通知
 *
 * 触发方式（由 ProjectViewModel 在角色加入项目/项目设为ACTIVE时自动注册）：
 *   ScheduleRepository.createJob(
 *       toolName = "project_daily_planner",
 *       toolParams = mapOf("projectId" to project.id, "characterId" to charId.toString()),
 *       repeatIntervalMs = 24h, nextRunAt = 今晚21:00
 *   )
 */
class ProjectDailyPlannerTool(
    private val provider: LLMProvider,
    private val projectDao: ProjectDao,
    private val goalDao: CharacterGoalDao,
    private val taskDao: TaskDao,
) : AgentTool {

    override val name = "project_daily_planner"

    override val paramKeys = listOf("projectId", "characterId")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val projectId   = params["projectId"]
            ?: return@withContext ToolResult(name, false, "", error = "missing projectId")
        val characterId = params["characterId"]?.toIntOrNull()
            ?: return@withContext ToolResult(name, false, "", error = "missing or invalid characterId")

        try {
            // ① 去重：今天是否已规划过
            val todayStart = startOfToday()
            val existing = taskDao.getByCharacterProjectAndSource(
                characterId = characterId,
                projectId   = projectId,
                source      = SOURCE,
                after       = todayStart,
            )
            if (existing.isNotEmpty()) {
                return@withContext ToolResult(name, true, content = "今日已规划，跳过。")
            }

            // ② 拼上下文
            val project = projectDao.getById(projectId)
                ?: return@withContext ToolResult(name, false, "", error = "project not found: $projectId")

            val charGoal = goalDao.getByCharacterAndProject(characterId, projectId)

            val recentTasks = taskDao.getByCharacterProjectAndSource(
                characterId = characterId,
                projectId   = projectId,
                source      = SOURCE,
                after       = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5),
            ).take(10)

            val charName = DefaultCharacters.find { it.id == characterId }?.name ?: "她"

            val prompt = buildString {
                appendLine("你是${charName}，正在参与长期成长项目「${project.title}」。")
                appendLine("项目的长期方向是：${project.description}")
                if (charGoal != null) {
                    appendLine("你给自己定的阶段目标是：${charGoal.description}")
                }
                if (recentTasks.isNotEmpty()) {
                    appendLine("你最近几天已经做过的事（请不要重复）：")
                    recentTasks.forEach { appendLine("- ${it.title}") }
                }
                appendLine()
                appendLine("结合你的人设和上述方向，决定你今天想为这个项目做哪几件具体的小事。")
                appendLine("最多4条，每条一行，直接写事情本身，不要编号，不要解释原因。")
            }

            // ③ 调LLM
            val config = LLMConfig(
                model       = "",
                maxTokens   = 300,
                temperature = 0.7f,
                stream      = false,
            )
            val response = provider.chatSync(
                messages     = listOf(LLMMessage(role = "user", content = prompt)),
                systemPrompt = "",
                config       = config,
            )

            // ④ 解析每行为一条Task
            val lines = response.trim().lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length > 2 }
                .take(4)

            if (lines.isEmpty()) {
                return@withContext ToolResult(name, false, "", error = "LLM返回内容无法解析为任务列表")
            }

            val now = System.currentTimeMillis()
            lines.forEach { line ->
                taskDao.insert(
                    TaskEntity(
                        id            = UUID.randomUUID().toString(),
                        characterId   = characterId,
                        projectId     = projectId,
                        title         = line,
                        description   = "由成长规划自动生成",
                        source        = SOURCE,
                        status        = "PENDING",
                        createdAt     = now,
                        updatedAt     = now,
                    )
                )
            }

            // ⑤ 返回——ScheduledJobWorker 用 content 作为通知标题
            ToolResult(
                toolName = name,
                success  = true,
                content  = "${charName}为「${project.title}」规划了今日${lines.size}件事",
                userHint = "正在规划今日成长任务…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "", error = "规划失败：${e.message}")
        }
    }

    companion object {
        const val SOURCE = "project_growth"

        /** 今天零点的时间戳（毫秒） */
        fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
