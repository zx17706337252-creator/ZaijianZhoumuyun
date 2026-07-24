package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.MessageEntity
import com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.prompt.PromptOrchestrator
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import com.zaijian.zhoumuyun.data.repository.AgentActivityRepository
import com.zaijian.zhoumuyun.data.repository.SkillRepository
import com.zaijian.zhoumuyun.data.repository.UserProfileRepository
import com.zaijian.zhoumuyun.domain.ChatTagParser
import com.zaijian.zhoumuyun.util.ZLog
import java.util.UUID

/**
 * 日程系统批次3新增：工单型任务（mode B）Headless 对话执行器。
 *
 * 职责：给定一个 `toolName == SENTINEL` 的 `ScheduledJobEntity`，跑一次 headless
 * 对话推理——把 `description` 当系统触发消息注入角色对话管线，复用
 * `ToolCallInterceptor.streamWithTools` 走完整 LLM 推理（含工具调用多轮），
 * 结果作为一条正常角色消息落库，返回 `ToolResult` 供 `ScheduledJobWorker`
 * 统一处理后续（写 `JobResultEntity`、发通知等逻辑不用变）。
 *
 * 为什么不能直接复用 `ChatMessageOrchestrator.sendMessage()`：
 *   `ChatMessageOrchestrator.sendMessage()` 深度耦合 UI 状态——
 *   `character = _uiState.value.character`、`getCurrentCharacterId()` 等只在
 *   ViewModel/Activity 生命周期内有效的状态。`ScheduledJobWorker` 运行时
 *   没有存活的 Activity/ViewModel，不能直接实例化或调用这一层。
 *
 * 已有先例：`ProactiveMessageWorker.doWork()` 证明模式可行——在无 Activity 环境
 * 下临时组装一套最小依赖（MessageRepository、CharacterStateRepository、
 * PresenceEngine、WorldSimulation），跑完一次检查即弃，不持有长生命周期状态。
 * 本执行器采用相同模式：Worker 内临时组装精简依赖，调用底层纯函数逻辑，
 * 不经过 ViewModel。
 *
 * 真正可复用的核心：`ToolCallInterceptor.streamWithTools` 是纯函数式的，不依赖
 * UI 状态，内部完整实现了：流式接收 LLM 输出 → 解析 `<tool:xxx/>` 标签 →
 * 执行工具（复用 `AgentToolRegistry`）→ 工具结果喂给 LLM 做第二轮 → 返回最终
 * 文本。这正是"角色到点自己判断要不要调工具"所需要的全部能力。
 *
 * 四处关键实现细节均已对照实际源码核实（方案第五节5.5），逐字复用既有写法：
 *   (1) 角色配置查询：复用 ChatViewModel.kt 第665-671 行
 *       `DefaultCharacters.find ?: daughterRepo.getCharacterConfig` + catch DaughterDataException
 *   (2) 历史消息 role 映射：复用 ChatMessageOrchestrator.kt 第145-156 行规则
 *   (3) 回复清洗三步顺序：stripThinkingTag → stripPsychText → stripMoodTag
 *       （ChatMessageOrchestrator.kt 第461-463 行，顺序固定）
 *   (4) 落库 role 约定：`role = characterId.toString()`
 *       （与 ProactiveMessageNotifier.kt 第108 行一致）
 *
 * 详见《日程系统_AI创建查询编辑_实现方案_v2.md》第五节 5.1-5.6。
 */
object AgentTaskJobExecutor {

    /**
     * 工单型任务哨兵值。`ScheduledJobEntity.toolName` 取此值时表示该任务是
     * 工单型（mode B），到点不调任何已注册工具，而是由本执行器走对话推理。
     *
     * 全项目唯一真相源——批次2 落地时曾用 `AgentTaskConstants.kt` 作过渡占位，
     * 本批次（批次3）按其 KDoc 约定收口到此常量。ScheduleCreateTool /
     * ScheduleUpdateTool / ScheduleListTool / ScheduleGetTool 共四个工具文件
     * 全部引用 `AgentTaskJobExecutor.SENTINEL`，删除 `AgentTaskConstants.kt`。
     *
     * `ScheduledJobWorker.doWork()` 亦按 `job.toolName == SENTINEL` 分叉：
     * 命中 → 调本执行器；否则走原 `AgentToolRegistry.get(toolName).execute(params)`。
     */
    const val SENTINEL: String = "agent_task"

    private const val TAG = "AgentTaskJobExecutor"

    /**
     * 执行一条工单型任务。
     *
     * @param context 应用 Context（Worker 传入）
     * @param db      AppDatabase 实例（Worker 传入，复用其 DAO）
     * @param job     待执行任务，调用方需保证 `job.toolName == SENTINEL`
     * @return ToolResult：success 时 content 为回复预览（take 80），
     *         failure 时 error 含失败原因，供 Worker 写入 JobResultEntity
     */
    suspend fun execute(
        context: Context,
        db: AppDatabase,
        job: ScheduledJobEntity,
    ): ToolResult {
        val description = job.description
            ?: return ToolResult(job.toolName, false, "", error = "工单缺少 description")

        val provider = ProviderManager.instance.activeProvider
            ?: return ToolResult(job.toolName, false, "", error = "未配置 API")

        // 1. 组装最小依赖（参照 ProactiveMessageWorker 的模式，已核实可行）
        //    Worker 内临时 new 一套 Repository、跑完即弃，不持有长生命周期状态。
        val messageRepo  = MessageRepository(db.messageDao())
        val identityRepo = IdentityRepository(db.characterIdentityDao())
        val daughterRepo = DaughterCharacterRepository(db = db, dao = db.daughterCharacterDao())
        // Window C 补做任务：工单路径补接 Skill Layer。与私聊路径语义等价——
        // job.characterId 明确，Agent 在后台执行实际工作时应能复用自有技能。
        // 范式对齐 ChatMessageOrchestrator.kt:213（SkillRegistry.buildSkillCatalogBlock），
        // 此处 execute() 已在 Dispatchers.IO 协程内（Worker 上下文），suspend 调用安全。
        val skillRepo = SkillRepository(db.skillDao())

        // 角色配置查询：复用 ChatViewModel.kt 第665-671 行的既有写法（已核实）
        //   - 1-9 号母亲角色在 DefaultCharacters 编译期常量里
        //   - >= 1000 的女儿角色走 daughterRepo.getCharacterConfig 反查
        //   - 女儿数据损坏会抛 DaughterDataException，降级返回失败
        val characterConfig: CharacterConfig = DefaultCharacters.find { it.id == job.characterId }
            ?: try {
                daughterRepo.getCharacterConfig(job.characterId)
            } catch (e: DaughterDataException) {
                return ToolResult(job.toolName, false, "", error = "角色数据异常，无法执行工单")
            }
            ?: return ToolResult(job.toolName, false, "", error = "找不到角色 characterId=${job.characterId}")

        // 2. 构造"系统触发消息"作为本轮的 user 侧输入
        //    前缀 [SCHEDULED_TASK] 让角色在 system prompt 的工具描述层之外，
        //    也能从消息内容本身识别出"这是日程到点触发，不是用户在说话"。
        val triggerText = "[SCHEDULED_TASK] $description"

        // 历史消息 role 映射：复用 ChatMessageOrchestrator.kt 第145-156 行的既有规则（已核实）
        //   - "user" / "assistant" 原样保留
        //   - "system" 中带 [AGENT_MSG: / [ROUNDTABLE_TRIGGER] 前缀的是内部控制信号，丢弃
        //   - 其余 "system"（如文件导入提示）按 user 身份带进历史
        //   - role = characterId.toString() 的主动消息映射为 "assistant"
        val history = messageRepo.getByCharacter(job.characterId).mapNotNull { msg ->
            when (msg.role) {
                "user", "assistant" -> LLMMessage(role = msg.role, content = msg.content)
                "system" -> if (msg.content.startsWith("[AGENT_MSG:") ||
                                msg.content.startsWith("[ROUNDTABLE_TRIGGER]")) null
                            else LLMMessage(role = "user", content = msg.content)
                else -> LLMMessage(role = "assistant", content = msg.content)
            }
        }

        // buildSystemPrompt 只传必填项 + toolDescriptionBlock
        // （方案5.4已核实：只有 character / identityEntity 必填，其余10+参数全有默认值，
        //  工单场景不需要孕期/关系快照等重上下文，留空即可）
        // Window C 补做任务：新增 skillCatalogBlock——§3 第一级目录注入，让 Agent 在
        // 工单模式下也能感知并按需 skill_expand 展开自有技能。无技能时返回空串，
        // buildSystemPrompt 内部自动跳过 Skill Layer，行为与既有工单一致。
        val skillCatalogBlock = SkillRegistry.buildSkillCatalogBlock(
            characterId = job.characterId,
            repo = skillRepo,
        )
        // 「称呼」功能性缺陷修复：此前工单路径未传 userName，恒为默认值"你"。
        // 本执行器本就是"Worker 内临时组装依赖、跑完即弃"模式（见类头注释），
        // 与 messageRepo/identityRepo/daughterRepo/skillRepo 同一处理方式，
        // 用收到的 context 直接构造，不复用 ViewModel 侧的容器单例。
        val userProfileRepo = UserProfileRepository(context)

        val systemPrompt = PromptOrchestrator.buildSystemPrompt(
            character            = characterConfig,
            identityEntity       = identityRepo.getById(job.characterId),
            userName             = userProfileRepo.getUserName(),
            toolDescriptionBlock = AgentToolRegistry.buildToolDescriptionBlock(),
            skillCatalogBlock    = skillCatalogBlock,
        )

        // stream=false：Worker 后台执行不需要打字机效果，整段返回更稳。
        // model="" 与 ChatMessageOrchestrator 一致——由 provider 内部按用户配置选模型。
        val config = LLMConfig(model = "", maxTokens = 2000, temperature = 0.8f, stream = false)

        // 3. 走完整推理 + 工具调用（复用 ToolCallInterceptor，不重新实现）
        //    streamWithTools 内部：流式接收 LLM 输出 → 解析 <tool:xxx/> → 执行工具
        //    → 工具结果喂给 LLM 做第二轮 → 返回最终文本。角色自己判断要不要调工具。
        //    B-1 fix：传入 activityContext，使工作流路径的工具失败降级
        //    也能写心迹事件 + 终态写入记忆系统（与其他三条主路径对齐）。
        val activityContext = ToolCallInterceptor.ActivityContext(
            characterId = job.characterId,
            sessionRef  = job.id,
            sceneType   = AgentActivityRepository.SceneType.WORKFLOW,
        )
        val fullReply = StringBuilder()
        try {
            ToolCallInterceptor.streamWithTools(
                provider         = provider,
                messages         = history + LLMMessage("user", triggerText),
                systemPrompt     = systemPrompt,
                config           = config,
                activityContext  = activityContext,
            ).collect { event ->
                if (event is StreamEvent.TextDelta) fullReply.append(event.text)
            }
        } catch (e: Exception) {
            ZLog.w(TAG, "streamWithTools failed for job=${job.id}", e)
            return ToolResult(job.toolName, false, "", error = "对话推理失败：${e.message?.take(80)}")
        }

        // 4. 三步清洗，顺序固定（已核实，见 ChatMessageOrchestrator.kt 第461-463 行）
        //    先剥 [thinking:]，再剥圆括号心理活动，最后剥末尾 [mood:xxx]，
        //    剩下的 cleanReply 才是最终存入 MessageEntity.content 的内容。
        //    否则工单产生的消息会带着未清洗的标签文本出现在聊天界面。
        val (afterThinking, _) = ChatTagParser.stripThinkingTag(fullReply.toString().trimEnd())
        val (afterPsych, _)    = ChatTagParser.stripPsychText(afterThinking)
        val (cleanReply, _)    = ChatTagParser.stripMoodTag(afterPsych)

        if (cleanReply.isBlank()) {
            return ToolResult(job.toolName, false, "", error = "角色回复为空（清洗后无内容）")
        }

        // 5. 落库为正常角色消息（role 约定已核实，见 ProactiveMessageNotifier.kt 第108 行）
        //    role = characterId.toString() 与 ChatMessageOrchestrator 的 role 映射规则
        //    （第155 行 else -> "assistant" 分支）严格对应——历史回放时这条消息会被
        //    映射为 "assistant" 进入下一轮对话上下文。
        messageRepo.insert(
            MessageEntity(
                id          = UUID.randomUUID().toString(),
                characterId = job.characterId,
                role        = job.characterId.toString(),
                content     = cleanReply,
                createdAt   = System.currentTimeMillis(),
            )
        )

        return ToolResult(
            toolName = job.toolName,
            success  = true,
            content  = cleanReply.take(80),
            userHint = null,
        )
    }
}
