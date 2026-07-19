package com.zaijian.zhoumuyun.data.agent

/**
 * AgentMetaTools.kt — 元认知 / 多 Agent 协作 / 知识获取工具
 *
 * 包含 8 个工具：
 *   RuleConflictCheckTool — 规则冲突检查（rule_conflict_check）
 *   SessionCompareTool    — Session 横向对比（session_compare）
 *   ProgressReportTool    — 进度报告生成（progress_report）
 *   AgentMessageTool      — Agent 间消息传递（agent_message）
 *   RoundtableTriggerTool — 触发圆桌讨论（roundtable_trigger）
 *   TaskDelegateTool      — 任务委派（task_delegate）
 *   WikiFetchTool         — Wikipedia 知识获取（wiki_fetch）
 *   ArxivSearchTool       — ArXiv 论文检索（arxiv_search）
 *
 * 注册入口：
 *   AgentToolRegistry.registerAgentMetaTools(context, memoryDao, sessionDao, goalDao, messageDao, taskDao)
 */

import android.content.Context
import com.zaijian.zhoumuyun.data.db.dao.EvaluationSessionDao
import com.zaijian.zhoumuyun.data.db.dao.LearningGoalDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryDao
import com.zaijian.zhoumuyun.data.db.dao.TaskDao
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import com.zaijian.zhoumuyun.data.db.entity.TaskStatus
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

// ─────────────────────────────────────────────────────────────
//  内部辅助：LLM 调用 —— 已提取为 AgentTool.callLlm（2.17），此处直接复用
// ─────────────────────────────────────────────────────────────

private suspend fun p3CallLlm(
    providerFn:   () -> LLMProvider?,
    systemPrompt: String,
    userPrompt:   String,
    maxTokens:    Int   = 800,
    temperature:  Float = 0.5f,
): String = AgentTool.callLlm(providerFn, systemPrompt, userPrompt, maxTokens, temperature)

// ─────────────────────────────────────────────────────────────
//  内部辅助：HTTP GET（复用 HttpURLConnection，与 BuiltinTools.kt 一致）
// ─────────────────────────────────────────────────────────────

private fun p3HttpGet(url: String, timeoutMs: Int = 8000): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.apply {
        requestMethod       = "GET"
        connectTimeout      = timeoutMs
        readTimeout         = timeoutMs
        setRequestProperty("User-Agent", "ZaijianApp/1.0 (Android)")
        setRequestProperty("Accept",     "application/json, text/xml, */*")
    }
    return try {
        if (conn.responseCode in 200..299) {
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } else {
            throw RuntimeException("HTTP ${conn.responseCode}: ${conn.responseMessage}")
        }
    } finally {
        conn.disconnect()
    }
}

// ═════════════════════════════════════════════════════════════
//  ⑳ RuleConflictCheckTool — 规则冲突检测
// ═════════════════════════════════════════════════════════════

/**
 * 规则冲突检测工具。
 *
 * 标签格式：
 *   <tool:rule_conflict_check new_rule="{待写入规则文本}" goal_id="{目标ID}"/>
 *
 * 实现：
 *   Step1: 读取同一 goal_id 下现有所有 RULE
 *   Step2: LLM 判断新规则与现有规则是否冲突/重复
 *   Step3: 返回冲突报告或「无冲突，可写入」
 *
 * 只提供信息，不自动阻止写入。通常由 rule_distill 内部调用，也可 Agent 主动调用。
 */
class RuleConflictCheckTool(
    private val providerFn:          () -> LLMProvider?,
    private val memoryDao:           MemoryDao,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name      = "rule_conflict_check"
    override val description = "检测一条待写入规则与同一目标下现有规则是否冲突或重复，只提示不阻止"
    override val paramKeys = listOf("new_rule", "goal_id")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val newRule = params["new_rule"]?.trim()
            val goalId  = params["goal_id"]?.trim()
            val charId  = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()

            if (newRule.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 new_rule 参数")
            }
            if (charId < 0) {
                return@withContext ToolResult(name, false, "", "角色未初始化")
            }

            return@withContext try {
                // Step 1: 读取现有规则
                val existingRules = if (!goalId.isNullOrEmpty()) {
                    memoryDao.getLockedRules(charId, goalId) +
                        memoryDao.getByDomain(charId, MemoryDomain.RULE.name, 50)
                            .filter { it.goalId == goalId && !it.isLocked }
                } else {
                    memoryDao.getAllRules(charId)
                }.distinctBy { it.id }

                if (existingRules.isEmpty()) {
                    return@withContext ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[规则冲突检测]\n当前无已有规则，无冲突，可直接写入。",
                    )
                }

                // Step 2: LLM 冲突分析
                val ruleList = existingRules.mapIndexed { i, r ->
                    val lock = if (r.isLocked) "🔒" else "  "
                    "$lock [${i + 1}] ${r.content.take(80)}"
                }.joinToString("\n")

                val prompt = """
请判断以下「待写入规则」与「现有规则集」是否存在冲突或重复：

【待写入规则】
$newRule

【现有规则集（共 ${existingRules.size} 条）】
$ruleList

请按以下格式输出（只输出分析结果，不加解释）：

结论: 【无冲突】 / 【存在冲突】 / 【内容重复】
冲突规则: [序号X]（若无冲突则填「无」）
冲突类型: 直接矛盾 / 语义重叠 / 无（从三选一）
建议: （30字内的处理建议）
                """.trimIndent()

                val analysis = p3CallLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是规则质量审查员，负责检测规则间的冲突与重复。简洁准确输出。",
                    userPrompt   = prompt,
                    maxTokens    = 200,
                    temperature  = 0.2f,
                )

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[规则冲突检测]\n$analysis",
                    userHint = "正在检测规则冲突…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "规则冲突检测失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ═════════════════════════════════════════════════════════════
//  ㉑ SessionCompareTool — Session 横向对比
// ═════════════════════════════════════════════════════════════

/**
 * Session 横向对比工具。
 *
 * 标签格式：
 *   <tool:session_compare goal_id="{目标ID}" count="{对比Session数, 默认5}"/>
 *
 * 实现：
 *   Step1: 从 DB 读取该目标最近 N 个已评分 Session（SCORED）
 *   Step2: LLM 分析分数趋势 + 评语变化 + 进步点
 *   Step3: 返回对比摘要
 *
 * Session 数量 < 2 时返回数据不足提示。
 */
class SessionCompareTool(
    private val providerFn:         () -> LLMProvider?,
    private val sessionDao:         EvaluationSessionDao,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name      = "session_compare"
    override val description = "对比某学习目标最近几次评分Session的分数趋势和进步点"
    override val paramKeys = listOf("goal_id", "count")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val goalId = params["goal_id"]?.trim()
            val count  = (params["count"]?.toIntOrNull() ?: 5).coerceIn(2, 20)
            val charId = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()

            if (charId < 0) {
                return@withContext ToolResult(name, false, "", "角色未初始化")
            }

            return@withContext try {
                // Step 1: 读取 SCORED Session
                val sessions = if (!goalId.isNullOrEmpty()) {
                    sessionDao.getScoredByGoal(goalId, count)
                } else {
                    sessionDao.getScoredHistory(charId, count)
                }

                if (sessions.size < 2) {
                    return@withContext ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[Session 对比]\n数据不足，至少需要 2 次评分记录（当前 ${sessions.size} 次）。",
                    )
                }

                // 构建 Session 摘要（最新在前）
                val sessionSummary = sessions.mapIndexed { i, s ->
                    val score    = s.compositeScore?.let { "%.1f".format(it) } ?: "未知"
                    val comment  = s.agentComment?.take(50) ?: "无评语"
                    val userNote = s.userNote?.take(30) ?: ""
                    val noteStr  = if (userNote.isNotEmpty()) "（用户备注：$userNote）" else ""
                    "[Session ${sessions.size - i}] 综合分: $score  评语: $comment$noteStr"
                }.joinToString("\n")

                // Step 2: LLM 分析趋势
                val scores = sessions.mapNotNull { it.compositeScore }
                val trend  = if (scores.size >= 2) {
                    val delta = scores.first() - scores.last()
                    when {
                        delta >  0.3f -> "📈 上升趋势"
                        delta < -0.3f -> "📉 下降趋势"
                        else          -> "➡️  平稳波动"
                    }
                } else "数据不足"

                val prompt = """
请根据以下 ${sessions.size} 次 Session 的评分记录，分析学习进步曲线：

$sessionSummary

整体趋势：$trend
最高分：${"%.1f".format(scores.maxOrNull() ?: 0f)}  最低分：${"%.1f".format(scores.minOrNull() ?: 0f)}

请输出（150字以内）：
1. 进步点：具体哪方面有改善
2. 待改进：持续存在的弱项
3. 总体评价：一句话概括
                """.trimIndent()

                val analysis = p3CallLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是学习进度分析师，从 Session 评分历史中提炼有价值的成长洞察。",
                    userPrompt   = prompt,
                    maxTokens    = 250,
                    temperature  = 0.4f,
                )

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[Session 对比分析（共 ${sessions.size} 次）]\n趋势：$trend\n\n$analysis",
                    userHint = "正在对比历史 Session…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "Session 对比失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ═════════════════════════════════════════════════════════════
//  ㉒ ProgressReportTool — 学习进度报告
// ═════════════════════════════════════════════════════════════

/**
 * 学习进度报告工具。
 *
 * 标签格式：
 *   <tool:progress_report goal_id="{目标ID}" format="{html|text}"/>
 *
 * 实现：
 *   Step1: 读取目标信息 + 所有 Session + 所有 Rule
 *   Step2: LLM 生成报告正文
 *   Step3: format=html 时 file_export 导出 HTML 报告；text 时直接显示
 *
 * 报告含：目标概述 + 进度百分比 + 高分 Session 摘要 + 已提炼规则列表 + 下阶段建议。
 * format 默认 text。
 */
class ProgressReportTool(
    private val providerFn:          () -> LLMProvider?,
    private val sessionDao:          EvaluationSessionDao,
    private val goalDao:             LearningGoalDao,
    private val memoryDao:           MemoryDao,
    private val fileExportTool:      FileExportTool,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name      = "progress_report"
    override val description = "生成某学习目标的完整进度报告（含摘要、规则清单、下阶段建议）"
    override val paramKeys = listOf("goal_id", "format")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val goalId = params["goal_id"]?.trim()
            val format = params["format"]?.lowercase() ?: "text"
            val charId = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()

            if (charId < 0) {
                return@withContext ToolResult(name, false, "", "角色未初始化")
            }

            return@withContext try {
                // Step 1: 读取目标数据
                val goal = if (!goalId.isNullOrEmpty()) {
                    goalDao.getById(goalId)
                } else {
                    goalDao.getActive(charId).firstOrNull()
                }

                val goalTitle    = goal?.title ?: "（未找到目标）"
                val progressPct  = goal?.let { "%.0f".format(it.progress * 100) } ?: "0"
                val goalStatus   = goal?.status ?: "UNKNOWN"

                // 读取 Session 历史
                val sessions = if (!goalId.isNullOrEmpty()) {
                    sessionDao.getScoredByGoal(goalId, 10)
                } else {
                    sessionDao.getScoredHistory(charId, 10)
                }
                val avgScore = sessions.mapNotNull { it.compositeScore }
                    .takeIf { it.isNotEmpty() }?.average()

                // 读取已提炼规则
                // 性能 M2 修复：goalId 非空时改用 getRulesByGoal 数据库层过滤，
                // 替代 getAllRules(charId) 全量加载后内存 filter。
                val rules = if (!goalId.isNullOrEmpty()) {
                    memoryDao.getRulesByGoal(charId, goalId)
                } else {
                    memoryDao.getAllRules(charId)
                }
                val lockedRules   = rules.filter { it.isLocked }
                val pendingRules  = rules.filter { !it.isLocked }

                // Step 2: LLM 生成报告
                val rulesSummary = buildString {
                    if (lockedRules.isNotEmpty()) {
                        appendLine("已锁定规则（${lockedRules.size}条）：")
                        lockedRules.take(5).forEach { appendLine("  🔒 ${it.content.take(60)}") }
                        if (lockedRules.size > 5) appendLine("  …（共 ${lockedRules.size} 条）")
                    }
                    if (pendingRules.isNotEmpty()) {
                        appendLine("待验证规则（${pendingRules.size}条）：")
                        pendingRules.take(3).forEach { appendLine("  📝 ${it.content.take(60)}") }
                        if (pendingRules.size > 3) appendLine("  …（共 ${pendingRules.size} 条）")
                    }
                }

                val sessionSummary = if (sessions.isEmpty()) "（暂无评分记录）"
                else sessions.take(3).joinToString("\n") { s ->
                    "  • 综合分 ${"%.1f".format(s.compositeScore ?: 0f)}：${s.agentComment?.take(40) ?: "无评语"}"
                }

                val prompt = """
请为以下学习目标生成简洁的进度报告（200字以内）：

目标：$goalTitle
当前进度：$progressPct%  状态：$goalStatus
评分历史（最近 ${sessions.size} 次，均分 ${avgScore?.let { "%.1f".format(it) } ?: "暂无"}）：
$sessionSummary

规则积累情况：
$rulesSummary

请按以下结构输出报告（简洁中文，不超过 200 字）：
【进度总览】目标+进度百分比一句话
【阶段成果】最突出的 1-2 个进展
【规则沉淀】已提炼规则总结（1句话）
【下阶段重点】最重要的 1 个改进方向
            """.trimIndent()

                val reportBody = p3CallLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是学习成果汇报专家，生成结构清晰、数据驱动的进度报告。",
                    userPrompt   = prompt,
                    maxTokens    = 350,
                    temperature  = 0.3f,
                )

                if (format == "html") {
                    // Step 3: 导出 HTML 报告
                    val htmlContent = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<title>学习进度报告 · $goalTitle</title>
<style>
  body { font-family: 'PingFang SC', sans-serif; max-width: 680px; margin: 40px auto;
         background: #1a1a2e; color: #e0e0f0; padding: 24px; border-radius: 12px; }
  h1 { color: #cba6f7; font-size: 1.5em; border-bottom: 1px solid #444; padding-bottom: 8px; }
  .meta { color: #888; font-size: 0.9em; margin: 8px 0 20px; }
  .progress-bar { background: #2a2a40; border-radius: 8px; height: 18px; margin: 8px 0; }
  .progress-fill { background: linear-gradient(90deg, #7c3aed, #cba6f7);
                   border-radius: 8px; height: 100%; width: ${progressPct}%; }
  .section { margin: 16px 0; padding: 14px; background: #22223a; border-radius: 8px; }
  .section pre { white-space: pre-wrap; line-height: 1.7; margin: 0; }
</style>
</head>
<body>
<h1>📊 学习进度报告</h1>
<div class="meta">目标：$goalTitle &nbsp;|&nbsp; 状态：$goalStatus &nbsp;|&nbsp; 生成时间：${java.util.Date()}</div>
<div>进度：$progressPct%</div>
<div class="progress-bar"><div class="progress-fill"></div></div>
<div class="section"><pre>$reportBody</pre></div>
<hr style="border-color: #444">
<div style="color:#666; font-size:0.8em; text-align:center">再见周慕云 · 自动生成</div>
</body>
</html>
                    """.trimIndent()

                    val exportResult = fileExportTool.execute(
                        mapOf(
                            "name"    to "进度报告_${goalTitle.take(20)}.html",
                            "content" to htmlContent,
                            "format"  to "html",
                        )
                    )

                    if (!exportResult.success) {
                        ToolResult(name, false, "报告导出失败：${exportResult.error}", exportResult.error)
                    } else {
                        ToolResult(
                            toolName = name,
                            success  = true,
                            content  = "[进度报告已导出：$goalTitle]\n${exportResult.content}",
                            userHint = "正在生成进度报告…",
                        )
                    }
                } else {
                    // 纯文本直接显示
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[学习进度报告：$goalTitle（$progressPct%）]\n\n$reportBody",
                        userHint = "正在生成进度报告…",
                    )
                }
            } catch (e: Exception) {
                ToolResult(name, false, "进度报告生成失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ═════════════════════════════════════════════════════════════
//  ㉓ AgentMessageTool — 向另一角色发送异步消息
// ═════════════════════════════════════════════════════════════

/**
 * 角色间异步消息工具。
 *
 * 标签格式：
 *   <tool:agent_message to_character_id="{角色ID}" content="{消息内容}"/>
 *
 * 实现：
 *   将消息以 source="agent_collab" 写入 MessageEntity（对接收方的消息流），
 *   接收方在下次进入对话时会看到这条系统消息。
 *   不依赖 RoundtableRepository，走 MessageRepository 薄包装操作消息表，保持解耦。
 *
 * 接收方下次进入对话时，ChatViewModel 会读取未读的 agent_collab 消息并作为上下文注入。
 */
class AgentMessageTool(
    private val messageDao:          MessageRepository,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name      = "agent_message"
    override val description = "向另一个角色发送异步消息，对方下次对话时会看到"
    override val paramKeys = listOf("to_character_id", "content")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val toCharId = params["to_character_id"]?.toIntOrNull()
            val content  = params["content"]?.trim()
            val fromId   = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()

            if (toCharId == null) {
                return@withContext ToolResult(name, false, "", "需要 to_character_id 参数（整数角色 ID）")
            }
            if (content.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 content 参数")
            }
            if (fromId < 0) {
                return@withContext ToolResult(name, false, "", "发送方角色未初始化")
            }
            if (toCharId == fromId) {
                return@withContext ToolResult(name, false, "不能向自己发送消息", "self-message")
            }

            return@withContext try {
                // 写入一条 role="system" 的消息到接收方消息流
                // 格式前缀 [AGENT_MSG:fromId] 用于 ChatViewModel 识别并作为异步上下文注入
                val msgEntity = com.zaijian.zhoumuyun.data.db.entity.MessageEntity(
                    id          = UUID.randomUUID().toString(),
                    characterId = toCharId,
                    role        = "system",
                    content     = "[AGENT_MSG:$fromId] $content",
                    createdAt   = System.currentTimeMillis(),
                )
                messageDao.insert(msgEntity)
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "消息已发送给角色 $toCharId，对方在下次对话时将看到此消息。",
                    userHint = "正在发送消息…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "消息发送失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ═════════════════════════════════════════════════════════════
//  ㉔ RoundtableTriggerTool — 主动发起圆桌讨论
// ═════════════════════════════════════════════════════════════

/**
 * 主动发起圆桌工具。
 *
 * 标签格式：
 *   <tool:roundtable_trigger topic="{议题}" participant_ids="{角色ID列表，逗号分隔，可选}"/>
 *
 * 实现：
 *   在 message DB 中写入一条 source="roundtable_trigger" 的系统消息，
 *   ChatViewModel / AppNavigation 通过 observeRoundtableTrigger() 检测该消息后自动导航到圆桌界面。
 *   此工具不直接操作 UI，通过 DB 事件驱动导航（与 Android ViewModel 架构兼容）。
 *
 * participant_ids 未填时由圆桌界面使用默认全员参与。
 */
class RoundtableTriggerTool(
    private val messageDao:          MessageRepository,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name      = "roundtable_trigger"
    override val description = "主动发起多角色圆桌讨论，用于「叫大家一起聊聊」这类场景"
    override val paramKeys = listOf("topic", "participant_ids")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val topic          = params["topic"]?.trim()
            val participantIds = params["participant_ids"]?.trim() ?: ""
            val fromId         = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()

            if (topic.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 topic 参数")
            }

            return@withContext try {
                // 构建触发内容，AppNavigation 监听此类消息并路由到圆桌
                val triggerContent = org.json.JSONObject().apply {
                    put("action",          "roundtable_start")
                    put("topic",           topic)
                    put("initiatorId",     fromId)
                    put("participantIds",  participantIds)
                }.toString()

                val msgEntity = com.zaijian.zhoumuyun.data.db.entity.MessageEntity(
                    id          = UUID.randomUUID().toString(),
                    characterId = fromId.coerceAtLeast(0),
                    role        = "system",
                    content     = "[ROUNDTABLE_TRIGGER] $triggerContent",
                    createdAt   = System.currentTimeMillis(),
                )
                messageDao.insert(msgEntity)

                val participantDesc = if (participantIds.isNotEmpty()) "（参与者：$participantIds）" else "（全员参与）"
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "圆桌讨论已发起，议题：$topic$participantDesc",
                    userHint = "正在发起圆桌…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "圆桌发起失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ═════════════════════════════════════════════════════════════
//  ㉕ TaskDelegateTool — 任务委托多个角色
// ═════════════════════════════════════════════════════════════

/**
 * 任务委托工具。
 *
 * 标签格式：
 *   <tool:task_delegate task="{任务描述}" delegate_to="{角色ID列表，逗号分隔}" due="{截止时间，可选}"/>
 *
 * 实现：
 *   Step1: LLM 将任务拆分为子任务列表
 *   Step2: 为每个角色写入一条 TaskEntity（source="delegation"）
 *   Step3: 返回委托清单
 *
 * 复用现有 tasks 表，source="delegation" 区分委托类型，不升级 DB。
 */
class TaskDelegateTool(
    private val providerFn:          () -> LLMProvider?,
    private val taskDao:             TaskDao,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name      = "task_delegate"
    override val description = "把一个任务拆分后委托给多个角色分别执行"
    override val paramKeys = listOf("task", "delegate_to", "due")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val taskDesc   = params["task"]?.trim()
            val delegateTo = params["delegate_to"]?.trim()
            val due        = params["due"]?.trim() ?: ""
            val fromId     = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()

            if (taskDesc.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 task 参数")
            }
            if (delegateTo.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 delegate_to 参数（角色ID列表，逗号分隔）")
            }

            val targetIds = delegateTo.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it > 0 }
                .distinct()

            if (targetIds.isEmpty()) {
                return@withContext ToolResult(name, false, "delegate_to 中没有有效的角色 ID", "invalid ids")
            }

            return@withContext try {
                // Step 1: LLM 拆分子任务
                val splitPrompt = """
请将以下任务拆分为 ${targetIds.size} 个子任务，每个角色一个，每条子任务一行，格式：
角色ID: 子任务描述（≤50字）

任务：$taskDesc
角色列表：${targetIds.joinToString(", ")}
${if (due.isNotEmpty()) "截止：$due" else ""}

只输出子任务列表，不加解释。
                """.trimIndent()

                val splitResult = p3CallLlm(
                    providerFn   = providerFn,
                    systemPrompt = "你是任务分解专家，将任务合理分配给不同角色，每条子任务清晰具体。",
                    userPrompt   = splitPrompt,
                    maxTokens    = 300,
                    temperature  = 0.3f,
                )

                // Step 2: 解析子任务并写入 DB
                val delegationMap = mutableMapOf<Int, String>()
                splitResult.lines().forEach { line ->
                    val match = Regex("^(\\d+)[:\\s]+(.+)$").find(line.trim())
                    if (match != null) {
                        val id   = match.groupValues[1].toIntOrNull()
                        val desc = match.groupValues[2].trim()
                        if (id != null && id in targetIds && desc.isNotEmpty()) {
                            delegationMap[id] = desc
                        }
                    }
                }

                // 对未解析到的角色，直接分配整个任务
                targetIds.forEach { id ->
                    if (id !in delegationMap) delegationMap[id] = taskDesc
                }

                val now = System.currentTimeMillis()
                val dueNote = if (due.isNotEmpty()) "（截止：$due）" else ""

                delegationMap.forEach { (charId, subTask) ->
                    taskDao.insert(
                        TaskEntity(
                            id          = UUID.randomUUID().toString(),
                            title       = "委托任务：${subTask.take(30)}",
                            description = "$subTask\n\n委托方：角色$fromId$dueNote",
                            characterId = charId,
                            status      = TaskStatus.PENDING.name,
                            toolName    = "task_delegate",
                            source      = "delegation",
                            createdAt   = now,
                            updatedAt   = now,
                        )
                    )
                }

                // Step 3: 构建委托清单
                val delegationList = delegationMap.entries.joinToString("\n") { (id, sub) ->
                    "  角色$id → $sub"
                }

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "[任务委托清单$dueNote]\n$delegationList\n\n共 ${delegationMap.size} 个子任务已分配。",
                    userHint = "正在分配任务…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "任务委托失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ═════════════════════════════════════════════════════════════
//  ㉖ WikiFetchTool — 维基百科词条
// ═════════════════════════════════════════════════════════════

/**
 * 维基百科词条抓取工具。
 *
 * 标签格式：
 *   <tool:wiki_fetch keyword="{搜索词}" lang="{zh|en, 默认zh}"/>
 *
 * 实现：
 *   调用 Wikipedia REST API /api/rest_v1/page/summary/{keyword}
 *   复用 HttpURLConnection（与 BuiltinTools.kt 保持一致）
 *
 * 输出：【词条名】+ 摘要正文（≤800字）
 * 404 时提示「未找到词条，尝试调整关键词或切换 lang=en」。
 */
class WikiFetchTool : AgentTool {

    override val name      = "wiki_fetch"
    override val description = "抓取维基百科词条摘要，用于查询百科知识类问题"
    override val paramKeys = listOf("keyword", "lang")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val keyword = params["keyword"]?.trim()
            if (keyword.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 keyword 参数")
            }

            val lang    = params["lang"]?.lowercase()?.takeIf { it in setOf("zh", "en") } ?: "zh"
            val encoded = URLEncoder.encode(keyword.replace(" ", "_"), "UTF-8")
            val url     = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded"

            return@withContext try {
                val body = p3HttpGet(url)
                val json = org.json.JSONObject(body)

                // Wikipedia REST API 返回 type="disambiguation" 时特殊处理
                val type    = json.optString("type", "")
                val title   = json.optString("title", keyword)
                val extract = json.optString("extract", "")

                if (extract.isEmpty()) {
                    return@withContext ToolResult(
                        name, false,
                        "未找到「$keyword」的词条摘要。建议：(1) 检查拼写 (2) 尝试 lang=en (3) 改用更精确的词条名",
                        "empty extract",
                    )
                }

                val disambiguationNote = if (type == "disambiguation") "\n⚠️ 此词条为消歧义页，请用更具体的词条名查询。" else ""
                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "【$title】（来源：Wikipedia/$lang）$disambiguationNote\n\n${extract.take(800)}",
                    userHint = "正在查询维基百科…",
                )
            } catch (e: Exception) {
                val msg = e.message ?: "未知错误"
                if ("404" in msg || "Not Found" in msg.lowercase()) {
                    ToolResult(name, false, "未找到词条「$keyword」，建议调整关键词或切换 lang=en。", "404")
                } else {
                    ToolResult(name, false, "Wiki 查询失败：${msg.take(80)}", msg)
                }
            }
        }
}

// ═════════════════════════════════════════════════════════════
//  ㉗ ArxivSearchTool — arXiv 论文搜索
// ═════════════════════════════════════════════════════════════

/**
 * arXiv 论文搜索工具。
 *
 * 标签格式：
 *   <tool:arxiv_search query="{搜索词（英文为佳）}" max_results="{1-5, 默认3}"/>
 *
 * 实现：
 *   调用 arXiv API export.arxiv.org/api/query，Regex 解析 Atom XML
 *   复用 HttpURLConnection（与 BuiltinTools.kt 保持一致）
 *
 * 输出：编号列表，论文标题 + 摘要前 300 字
 */
class ArxivSearchTool : AgentTool {

    override val name      = "arxiv_search"
    override val description = "搜索arXiv学术论文，返回标题和摘要，用于查找科研文献"
    override val paramKeys = listOf("query", "max_results")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val query      = params["query"]?.trim()
            if (query.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", "需要 query 参数")
            }

            val maxResult  = (params["max_results"]?.toIntOrNull() ?: 3).coerceIn(1, 5)
            val encoded    = URLEncoder.encode(query, "UTF-8")
            val url        = "https://export.arxiv.org/api/query?search_query=all:$encoded" +
                             "&start=0&max_results=$maxResult&sortBy=relevance"

            return@withContext try {
                val xml = p3HttpGet(url)

                // 解析 Atom XML（不引入 XML 库，用正则提取）
                val entries = Regex("<entry>([\\s\\S]*?)</entry>")
                    .findAll(xml).take(maxResult).toList()

                if (entries.isEmpty()) {
                    return@withContext ToolResult(
                        name, true,
                        "未找到关于「$query」的论文，建议：(1) 使用英文关键词 (2) 缩短搜索词",
                    )
                }

                val result = buildString {
                    appendLine("[arXiv 论文搜索：$query（共 ${entries.size} 篇）]")
                    appendLine()
                    entries.forEachIndexed { i, m ->
                        val title   = Regex("<title>([\\s\\S]*?)</title>")
                            .find(m.value)?.groupValues?.get(1)?.trim()
                            ?.replace(Regex("\\s+"), " ") ?: "（无标题）"
                        val summary = Regex("<summary>([\\s\\S]*?)</summary>")
                            .find(m.value)?.groupValues?.get(1)?.trim()
                            ?.replace(Regex("\\s+"), " ")?.take(300) ?: "（无摘要）"
                        val arxivId = Regex("<id>https?://arxiv\\.org/abs/([\\w./]+)</id>")
                            .find(m.value)?.groupValues?.get(1)?.trim() ?: ""

                        appendLine("${i + 1}. $title")
                        if (arxivId.isNotEmpty()) appendLine("   📄 arxiv.org/abs/$arxivId")
                        appendLine("   摘要: $summary")
                        appendLine()
                    }
                }

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = result.trimEnd(),
                    userHint = "正在搜索 arXiv…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "arXiv 搜索失败：${e.message?.take(80)}", e.message)
            }
        }
}

// ─────────────────────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────────────────────

/**
 * 注册 Phase 28 Part 3 工具（8个：自我管理1 + 可观测性2 + 角色协作3 + 知识获取2）。
 * 在 ZaijianApp.onCreate() 中调用。
 * characterIdProvider 以 -1 静态注册，由 ChatToolRegistrar.registerCharacterTools()
 * 动态覆盖（批次2 2-1修复前此处注释误写"由 ChatViewModel.init() 动态覆盖"，
 * 实际从未被覆盖，导致6个工具角色ID恒为-1）。
 */
fun AgentToolRegistry.registerAgentMetaTools(
    context:    Context,
    memoryDao:  MemoryDao,
    sessionDao: EvaluationSessionDao,
    goalDao:    LearningGoalDao,
    messageDao: MessageRepository,
    taskDao:    TaskDao,
) {
    val fileExport = FileExportTool.getInstance(context)
    val providerFn: () -> LLMProvider? = AgentTool.defaultProviderFn()
    registerAll(
        RuleConflictCheckTool(
            providerFn          = providerFn,
            memoryDao           = memoryDao,
            characterIdProvider = { -1 },
        ),
        SessionCompareTool(
            providerFn          = providerFn,
            sessionDao          = sessionDao,
            characterIdProvider = { -1 },
        ),
        ProgressReportTool(
            providerFn          = providerFn,
            sessionDao          = sessionDao,
            goalDao             = goalDao,
            memoryDao           = memoryDao,
            fileExportTool      = fileExport,
            characterIdProvider = { -1 },
        ),
        AgentMessageTool(
            messageDao          = messageDao,
            characterIdProvider = { -1 },
        ),
        RoundtableTriggerTool(
            messageDao          = messageDao,
            characterIdProvider = { -1 },
        ),
        TaskDelegateTool(
            providerFn          = providerFn,
            taskDao             = taskDao,
            characterIdProvider = { -1 },
        ),
        WikiFetchTool(),
        ArxivSearchTool(),
    )
}
