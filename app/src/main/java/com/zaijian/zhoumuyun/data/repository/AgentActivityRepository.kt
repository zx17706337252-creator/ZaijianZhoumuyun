package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.AgentActivityDao
import com.zaijian.zhoumuyun.data.db.dao.WorkflowStepResultDao
import com.zaijian.zhoumuyun.data.db.entity.AgentActivityEventEntity
import com.zaijian.zhoumuyun.data.db.entity.WorkflowStepResultEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/**
 * 「心迹」事件 Repository。见《Window B 执行方案 v1.1》2.2.2。
 *
 * 参照 [WorkflowRepository] 结构。职责：
 * 1. **写入**：供 2.1 节降级状态机、三处 UI 集成点（2.2.3）、WorkflowEngine 镜像
 *    埋点（2.1.4）调用。提供 [recordEvent] 薄封装，让调用点"加一行调用"即可落库
 *    （方案 2.2.3 明确要求三处集成点"各加一行调用 … 不改动这三个文件里已有的
 *    其余逻辑"），避免每个调用点各自拼 UUID/时间戳/截断逻辑。
 * 2. **合并视图**：把 `agent_activity_events` 和 `workflow_step_results`（工作流
 *    场景）按 `createdAt` 合并成统一时间线 Flow，供 UI 面板消费，避免 UI 层自己
 *    拼两张表（方案 2.2.2 末段）。
 *
 * ## 合并视图的字段定稿说明
 *
 * 方案 2.2.4 指出"此契约字段建议在 2.2.2/2.2.3 落地、有真实数据跑起来之后再
 * 最终定稿"。本类当前给出的是占位形状（[AgentActivityTimelineItem]），待真实
 * 数据跑通后由 Window D 面板需求驱动再定稿。
 *
 * ## sceneType / eventType 常量
 *
 * 集中定义，供三处 UI 集成点、降级状态机、WorkflowEngine 镜像埋点统一引用，
 * 避免各调用点手写字符串导致拼写漂移。
 */
class AgentActivityRepository(
    private val agentActivityDao: AgentActivityDao,
    private val workflowStepResultDao: WorkflowStepResultDao,
) {

    // ── sceneType（对应方案 2.2.3 三处集成点 + 2.1.4 工作流镜像）──────────
    object SceneType {
        const val CHAT = "chat"                           // 私聊主路径（ChatMessageOrchestrator）
        const val ROUNDTABLE_BOT = "roundtable_bot"       // 圆桌被动回复（RoundtableBotReplyGenerator）
        const val ROUNDTABLE_IDLE = "roundtable_idle"     // 圆桌闲时主动发言（RoundtableIdleManager）
        const val WORKFLOW = "workflow"                    // WorkflowEngine 镜像埋点（2.1.4）
    }

    // ── eventType（方案 2.2.2 字段说明）──────────────────────────────────
    object EventType {
        const val TOOL_CALL = "TOOL_CALL"
        const val DEGRADE_RETRY = "DEGRADE_RETRY"
        const val DEGRADE_SWITCH = "DEGRADE_SWITCH"
        const val DEGRADE_GIVEUP = "DEGRADE_GIVEUP"
        // 为 Window C 预留占位（本轮不用，字段已预留）
        const val SKILL_CREATE = "SKILL_CREATE"
        const val SKILL_INVOKE = "SKILL_INVOKE"
    }

    // ── outcome（方案 2.2.2 字段说明）───────────────────────────────────
    object Outcome {
        const val SUCCESS = "success"
        const val FAIL = "fail"
        const val TIMEOUT = "timeout"
    }

    /** outputSummary 截断上限（方案 2.2.2：建议≤300字）。 */
    private const val SUMMARY_MAX_CHARS = 300

    // ── 写入 ──────────────────────────────────────────────────────────────

    /**
     * 落库一条「心迹」事件。调用点只需传语义字段，UUID/时间戳/摘要截断由本方法处理。
     *
     * @param characterId  角色ID
     * @param sessionRef   关联到具体一次回复（messageId/roundtableMessageId/workflowJobId）
     * @param sceneType    [SceneType] 之一
     * @param eventType    [EventType] 之一
     * @param toolName     工具名（降级 giveup 也填触发失败的原始工具名）
     * @param outcome      [Outcome] 之一（null 表示事件尚无终态，如"已发起"）
     * @param toolParamsJson 工具参数 JSON，默认 "{}"
     * @param outputRaw    工具产出原文（会被截断为 ≤[SUMMARY_MAX_CHARS] 字存入 outputSummary）
     * @param errorMessage 失败时的简短错误信息
     * @param decisionNote 决策依据（如"上次同参数超时，改用 xxx 参数重试"）
     * @param attemptIndex 降级链路内第几次尝试，正常单次成功恒为 0
     * @param startedAt    开始时间戳；null 时取当前时间
     * @param completedAt  完成时间戳；null 表示未完成
     * @return 新建事件的 id（UUID）
     */
    suspend fun recordEvent(
        characterId: Int,
        sessionRef: String,
        sceneType: String,
        eventType: String,
        toolName: String?,
        outcome: String?,
        toolParamsJson: String = "{}",
        outputRaw: String? = null,
        errorMessage: String? = null,
        decisionNote: String? = null,
        attemptIndex: Int = 0,
        startedAt: Long? = null,
        completedAt: Long? = null,
    ): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        agentActivityDao.insert(
            AgentActivityEventEntity(
                id = id,
                characterId = characterId,
                sessionRef = sessionRef,
                sceneType = sceneType,
                eventType = eventType,
                toolName = toolName,
                toolParamsJson = toolParamsJson,
                attemptIndex = attemptIndex,
                outcome = outcome,
                outputSummary = outputRaw?.let { truncate(it) },
                errorMessage = errorMessage,
                decisionNote = decisionNote,
                startedAt = startedAt ?: now,
                completedAt = completedAt,
                createdAt = now,
            )
        )
        return id
    }

    // ── 读取 ──────────────────────────────────────────────────────────────

    /** 观察某角色最近 N 条「心迹」事件（按 createdAt 倒序）。 */
    fun observeRecentByCharacter(characterId: Int, limit: Int = 50): Flow<List<AgentActivityEventEntity>> =
        agentActivityDao.observeRecentByCharacter(characterId, limit)

    /** 按某次回复聚合查询全部事件（按 createdAt 升序）。 */
    suspend fun getBySession(sessionRef: String): List<AgentActivityEventEntity> =
        agentActivityDao.getBySession(sessionRef)

    // ── 合并视图（方案 2.2.2 末段）─────────────────────────────────────────

    /**
     * 把 `agent_activity_events` 与 `workflow_step_results`（工作流场景）按
     * `createdAt` 合并成统一时间线 Flow，供「心迹」面板消费。
     *
     * 工作流步骤通过 [WorkflowStepResultDao.observeStepsByCharacter] JOIN
     * `workflow_jobs` 按角色聚合后并入。两张表在查询层 UNION 呈现，不在存储层合并
     * （见 [AgentActivityEventEntity] 类注释"为什么不复用 WorkflowStepResultEntity"）。
     *
     * 注：2.1.4 工作流镜像埋点落地后，工作流步骤会同时写入 `agent_activity_events`
     * （sceneType=workflow），届时本合并视图的 workflow_step_results 分支与
     * activity 表的 workflow 分支会有信息重叠；这是有意的双写（workflow_step_results
     * 是工作流执行的事实来源，保留其在时间线的呈现），面板层后续可按需去重/折叠。
     *
     * 字段形状为占位（方案 2.2.4），待真实数据跑通后定稿。
     */
    fun observeTimeline(characterId: Int, limit: Int = 50): Flow<List<AgentActivityTimelineItem>> =
        combine(
            agentActivityDao.observeRecentByCharacter(characterId, limit),
            workflowStepResultDao.observeStepsByCharacter(characterId),
        ) { activities, steps ->
            val fromActivity = activities.map { it.toTimelineItem() }
            val fromSteps = steps.map { it.toTimelineItem() }
            // 合并后按 createdAt 倒序，取前 limit 条。两源各自最多 limit 条，
            // 合并后取 top(limit) 是"最近 N 条"的合理近似（占位实现，2.2.4 定稿）。
            (fromActivity + fromSteps)
                .sortedByDescending { it.createdAt }
                .take(limit)
        }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun truncate(s: String): String =
        if (s.length <= SUMMARY_MAX_CHARS) s else s.substring(0, SUMMARY_MAX_CHARS)

    private fun AgentActivityEventEntity.toTimelineItem(): AgentActivityTimelineItem =
        AgentActivityTimelineItem(
            id = id,
            createdAt = createdAt,
            sceneType = sceneType,
            eventType = eventType,
            toolName = toolName,
            outcome = outcome,
            outputSummary = outputSummary,
            decisionNote = decisionNote,
            source = SOURCE_ACTIVITY,
            sessionRef = sessionRef,
        )

    private fun WorkflowStepResultEntity.toTimelineItem(): AgentActivityTimelineItem =
        AgentActivityTimelineItem(
            id = id,
            createdAt = createdAt,
            sceneType = SceneType.WORKFLOW,
            eventType = EventType.TOOL_CALL,
            toolName = toolName,
            outcome = if (success) Outcome.SUCCESS else Outcome.FAIL,
            outputSummary = output?.let { truncate(it) },
            decisionNote = decidedNextAction,
            source = SOURCE_WORKFLOW_STEP,
            sessionRef = jobId,
        )

    private companion object {
        const val SOURCE_ACTIVITY = "activity"
        const val SOURCE_WORKFLOW_STEP = "workflow_step"
    }
}

/**
 * 「心迹」统一时间线条目（合并视图投影）。占位形状，见
 * [AgentActivityRepository.observeTimeline] 与方案 2.2.4。
 */
data class AgentActivityTimelineItem(
    val id: String,
    val createdAt: Long,
    val sceneType: String,
    val eventType: String,
    val toolName: String?,
    val outcome: String?,
    val outputSummary: String?,
    val decisionNote: String?,
    /** 来源表："activity"（agent_activity_events）/ "workflow_step"（workflow_step_results）。 */
    val source: String,
    /** 关联回复/任务：activity=sessionId，workflow_step=jobId。 */
    val sessionRef: String,
)
