package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import com.zaijian.zhoumuyun.data.db.entity.WorkflowJobEntity
import com.zaijian.zhoumuyun.data.db.entity.WorkflowStepResultEntity
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.repository.AgentActivityRepository
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 多步骤工作流系统 · Step 2 — 引擎核心循环
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责：给定一个已存在的 WorkflowJobEntity（id），循环执行：
 *   ① 把目标 + 已执行历史喂给 LLM，要求其在三选一里做决定
 *      - 调用一个工具：<tool:工具名 参数="值"/>（复用 Phase 13 ToolParser）
 *      - 宣布完成：    <workflow:complete summary="给用户的汇报"/>
 *      - 宣布卡住：    <workflow:stuck reason="卡住的原因"/>
 *   ② 执行对应动作，结果写入 WorkflowStepResultEntity
 *   ③ currentStep + 1，回到①，直到终结状态或达到步数/超时上限
 *
 * ── 不在 Step 2 范围内（留给后续步骤）──────────────────────────
 *   · 不负责 launch 协程 / 不感知 WorkManager（Step 3）
 *   · 不负责创建 WorkflowJobEntity / 不感知触发入口（Step 4）
 *   · 不负责把结果回注到对话（Step 4）
 *
 * ── 可续跑性（为 Step 3 设计）────────────────────────────────────
 * [run] 是无状态的纯函数式循环：每次迭代都从数据库重新读取 job 和
 * step 历史，不依赖内存中的状态。即便上一次调用因进程被杀而中断，
 * 只要 WorkflowJobEntity.status 仍是 RUNNING，重新调用 [run] 即可
 * 从 currentStep 继续，不会重跑已完成的步骤。
 *
 * ── 已知限制（记录于 Step 2，已在 Step 4 解决）───────────────────
 * AgentToolRegistry 中部分工具（如 ScheduleCreateTool 系列）的
 * characterIdProvider 绑定的是 ChatViewModel.currentCharacterId，
 * 而不是调用时显式传入的角色 ID。当引擎在后台（无 ChatViewModel
 * 存活，如 App 被杀后由 WorkManager 拉起）执行涉及角色身份的工具时，
 * 可能取到错误或默认的 characterId。
 * Step 4 方案：不扩展 AgentTool 接口（影响面太大），改用 SAFE_TOOL_NAMES
 * 白名单——只允许工作流调用无状态工具，双重生效于 buildDecisionPrompt()
 * 的工具描述块过滤 + executeToolStep() 的执行前校验。
 * ═══════════════════════════════════════════════════════════════
 */
object WorkflowEngine {

    /** 单步 LLM 决策调用的 token 上限：只需输出一个标签，不需要很大 */
    private const val DECISION_MAX_TOKENS = 500

    /** 决策调用用低温度，要求稳定输出格式，不需要创造性 */
    private const val DECISION_TEMPERATURE = 0.1f

    /** 喂给决策 Prompt 时，单步历史结果文本截断长度（防止 prompt 随步数膨胀过快） */
    private const val HISTORY_OUTPUT_TRUNCATE = 300

    /**
     * H4 修复：per-job 并发互斥锁映射。
     *
     * 同一 jobId 可能被两条路径同时启动（如进程重启后 WorkManager 续跑，
     * 与 onCreate 中手动触发续跑同时发生），若两个协程并发进入 run() 循环，
     * 会出现：两边都读到相同的 currentStep → 都执行同一步 → currentStep 被
     * 其中一个更新后另一个再次推进，导致步骤重复或 currentStep 被写回旧值。
     *
     * 方案：以 jobId 为 key 维护一张 Mutex 表，run() 入口处 withLock 持有锁。
     * ConcurrentHashMap.getOrPut 本身不是原子的（双重写入风险），
     * 因此用 computeIfAbsent 代替，保证每个 jobId 只创建一个 Mutex 实例。
     * Mutex 本身不持有 job 数据，内存开销极低，无需主动清理。
     */
    private val jobMutexMap = ConcurrentHashMap<String, Mutex>()

    /**
     * Step 4：工作流后台执行允许调用的工具白名单。
     *
     * 解决文件头"已知限制"记录的 characterId 风险：不改 AgentTool 接口（影响 30+ 个
     * 工具实现，影响面太大），改用白名单——这里列出的工具均已逐一核对实现，构造函数
     * 不依赖 ChatViewModel.currentCharacterId，纯无状态，后台跑（无 ChatViewModel
     * 存活）时行为与前台完全一致，不存在拿到错误/默认 characterId 的风险。
     *
     * 双重生效位置：
     *   ① buildDecisionPrompt() —— 喂给 LLM 决策的工具描述块只展示白名单内的工具
     *   ② executeToolStep()     —— 即便 LLM 越界输出白名单外的工具标签，执行前拒绝
     *
     * ── 问题23修复（批次E）：白名单扩容 ──────────────────────────────
     * 原白名单只有 11 个，而 email_send/note_save/schedule_create 等生产力工具
     * 在工作流中完全无法被调用（报告原文举例）。核实后发现：报告举的三个例子里，
     * email_send/note_save 构造函数确实不依赖 characterId，可以安全加入；但
     * schedule_create（ScheduleCreateTool）本身就是文件头"已知限制"点名描述的
     * 那类风险工具——构造函数依赖 characterIdProvider: () -> Int，恰恰是无
     * ChatViewModel 存活时拿不到正确 characterId 的典型场景，报告在这一点上
     * 自相矛盾（既举它当白名单收窄的受害例子，又是白名单要拦截的对象），本次
     * 按"以代码事实为准"不采纳这个例子，schedule_create 维持排除。
     *
     * 扩容方法：对 AgentToolRegistry 注册的全部工具类逐一核对构造函数签名，
     * 只要构造函数不含 characterIdProvider/characterId 参数（即不绑定"当前是
     * 哪个角色"），就判定为无状态、后台安全，纳入白名单；含此类参数的一律维持
     * 排除，不逐个评估"这次执行是否恰好不受影响"，避免引入需要动态判断的隐性
     * 例外规则（例如 ScheduleDeleteTool/ScheduleUpdateTool 虽然构造函数没有
     * characterIdProvider，但业务语义是"修改/删除某个已存在的角色定时任务"，
     * 允许后台无限制调用会绕开原有的角色归属校验层，本次同样不加入，留待有
     * 明确业务需求时单独评估）。
     *
     * 按来源文件分组列出，方便下次新增工具时对照同类工具决定是否加入：
     */
    private val SAFE_TOOL_NAMES = setOf(
        // 原有 11 个（BuiltinTools / DataTools，均已确认无状态）
        "web_search", "calculator", "datetime", "translate", "file_read",
        "file_export", "weather", "url_fetch", "code_gen", "code_review", "unit_convert",
        // BuiltinTools.kt / PersonalTools.kt 其余无状态工具
        "countdown", "clipboard_write", "qr_decode",
        // FileSystemTools.kt —— 均只依赖 context，不依赖 characterId
        "folder_create", "folder_delete", "file_rename", "file_edit",
        "file_delete", "zip_extract", "zip_create", "file_organize",
        // DataVisTools.kt —— csv_analyze/chart_data/table_gen/各 gen 工具均无状态；
        // self_reflect/rule_review 见下方"问题24修复"说明，本次已加入
        "csv_analyze", "table_gen", "chart_data", "mindmap_gen", "flowchart_gen",
        // CreativeDocTools.kt —— 全部依赖 providerFn（可选 fileExportTool），无角色绑定
        "writing_critique", "outline_gen", "image_gen_prompt", "inspiration_fetch",
        "email_draft", "meeting_minutes", "docx_gen", "pdf_export", "html_gen", "markdown_to_doc",
        // DataVisTools.kt 剩余的 gen 系列（依赖 providerFn + context，无角色绑定）
        "excel_gen", "pptx_gen",
        // EmailTools.kt —— 账号级操作（EmailAccountStore 是应用级单账号配置），非角色级
        "email_send", "email_fetch",
        // Schedule 系列 —— 仅 schedule_get 是纯只读查询（按 id 定位，无角色绑定语义）；
        // create/delete/update/list 均排除（create/list 直接依赖 characterIdProvider，
        // delete/update 语义上归属某个角色的任务，见上方 class doc 说明）
        "schedule_get",
        // AgentMetaTools.kt —— 纯外部查询工具，无状态
        "arxiv_search", "wiki_fetch",
        // DataVisTools.kt —— 自我管理类工具，问题24修复：本身逻辑无状态（自我反思写自己
        // 的记忆、规则复审只出建议不改库），此前排除的唯一原因是 characterIdProvider
        // 静态占位为 -1 导致工作流后台跑必定拿到无效角色而失败；executeToolStep() 现已
        // 注入 job.characterId 作为 __character_id，与 ScheduledJobWorker 同一套机制，
        // 问题解除，纳入白名单
        "self_reflect", "rule_review",
        // WorkbenchTaskTools.kt —— 工作台任务跟踪，问题40修复：此前在 App 启动阶段完全
        // 没有静态占位注册（ZaijianApp.kt 缺失，不同于其余角色绑定工具模块），工作流场景
        // AgentToolRegistry.get() 直接返回 null；且工具内部原先不读 __character_id，
        // 只认闭包 characterId()，即便注册上了也会在工作流后台拿到 -1 占位角色且无保护、
        // 静默写脏数据。本次已同步补齐 ZaijianApp.kt 静态占位注册 + 四个工具的
        // __character_id 读取优先级 + charId < 0 拒绝执行，与 self_reflect/rule_review
        // 同一套模式，纳入白名单
        "task_start", "task_update", "task_complete", "task_cancel",
        // GitHub/CI-CD 工具（GithubConfigTools.kt 等）—— 依赖项目级 GithubConfigDataStore，
        // 非角色级配置，与问题12（cicd_start 改为项目级 characterId=-1）方向一致
        "build_apk", "build_apk_download", "build_status_check",
        "create_github_repo", "git_commit_push",
    )

    // ─────────────────────────────────────────────────────────
    //  公开入口
    // ─────────────────────────────────────────────────────────

    /**
     * 驱动一个工作流任务直到终结（COMPLETED / FAILED / TIMEOUT）或被取消。
     *
     * 调用方负责把本函数放进一个不阻塞当前对话的协程里启动（Step 4），
     * 以及在进程重启后重新调用本函数续跑（Step 3）。
     *
     * H4 修复：run() 入口持有 per-job Mutex。同一 jobId 同时被两条路径调用时，
     * 后来的调用会挂起等待，直到先到的调用退出（任务终结 / 协程被取消）。
     * 协程取消（CancellationException）会正常传播，不会卡在 withLock 内。
     *
     * @param jobId       WorkflowJobEntity.id，必须已存在于数据库（由 WorkflowRepository.createJob 创建）
     * @param repository  数据访问层
     * @param provider    本次决策用的 LLM Provider（建议与发起对话时使用的 Provider 一致）
     */
    suspend fun run(
        jobId: String,
        repository: WorkflowRepository,
        provider: LLMProvider,
    ) {
        val mutex = jobMutexMap.computeIfAbsent(jobId) { Mutex() }
        try {
            mutex.withLock {
                runInternal(jobId, repository, provider)
            }
        } finally {
            // 方案 3-8：withLock 已释放 Mutex，此时 remove 不会产生死锁或互斥失效。
            // 即使另一个协程在 remove 前通过 computeIfAbsent 拿到同一个 Mutex，
            // 它也能正常获取锁（Mutex 未被持有）。
            jobMutexMap.compute(jobId) { _, existing -> if (existing === mutex) null else existing }
        }
    }

    /** H4：实际执行循环，在 per-job Mutex 保护下运行 */
    private suspend fun runInternal(
        jobId: String,
        repository: WorkflowRepository,
        provider: LLMProvider,
    ) {
        val decisionConfig = LLMConfig(
            model = "",  // OpenAICompatProvider 实例已绑定具体 model，此处占位不影响调用
            maxTokens = DECISION_MAX_TOKENS,
            temperature = DECISION_TEMPERATURE,
            stream = false,
        )

        while (true) {
            val job = repository.findById(jobId) ?: return  // 任务已被删除，静默退出
            if (job.status != WorkflowRepository.STATUS_RUNNING) return  // 已终结，幂等退出（防止重复 run 时重复执行）

            val now = System.currentTimeMillis()
            if (now >= job.deadlineAt) {
                repository.markTimeout(jobId, buildLimitReason(job, "总耗时超过 10 分钟上限"))
                // §2.1.4 失败写回：以整个 job 的 goal 为 context
                recordJobFailure(repository, jobId, job.characterId, "workflow_timeout", "总耗时超过 10 分钟上限", job.currentStep)
                return
            }
            if (job.currentStep >= job.maxSteps) {
                repository.markTimeout(jobId, buildLimitReason(job, "步数达到上限（${job.maxSteps} 步）"))
                recordJobFailure(repository, jobId, job.characterId, "workflow_step_limit", "步数达到上限（${job.maxSteps} 步）", job.currentStep)
                return
            }

            val history = repository.getStepHistory(jobId)
            val decision = decideNextAction(job, history, provider, decisionConfig)
            val stepIndex = job.currentStep

            when (decision) {
                is EngineDecision.Complete -> {
                    repository.markCompleted(jobId, decision.summary)
                    return
                }
                is EngineDecision.Stuck -> {
                    repository.markFailed(jobId, decision.reason)
                    // §2.1.4 失败写回：LLM 自主判定卡住
                    recordJobFailure(repository, jobId, job.characterId, "workflow_stuck", decision.reason, stepIndex)
                    return
                }
                is EngineDecision.CallTool -> {
                    // 批次2 2-3修复：executeToolStep 返回 false 表示 recordStep 失败已
                    // markFailed，需中止循环避免工具重复执行（详见 executeToolStep 注释）
                    if (!executeToolStep(repository, jobId, stepIndex, decision, job.characterId)) {
                        // §2.1.4 失败写回：executeToolStep 内部 markFailed，此处补写记忆
                        recordJobFailure(repository, jobId, job.characterId, decision.call.toolName, "executeToolStep 失败（recordStep 异常）", stepIndex)
                        return
                    }
                    // 正常情况不 return，回到循环顶部重新读取 job（currentStep 已 +1）
                }
                is EngineDecision.Invalid -> {
                    // LLM 未按格式输出：计入一步，避免无效重试占满整个超时窗口而不消耗步数配额
                    try {
                        repository.recordStep(
                            jobId = jobId,
                            stepIndex = stepIndex,
                            toolName = null,
                            toolParamsJson = "{}",
                            success = false,
                            output = null,
                            errorMessage = "LLM 未输出有效的工具调用或控制标签",
                            decidedNextAction = decision.rawText,
                            startedAt = now,
                            completedAt = System.currentTimeMillis(),
                        )
                    } catch (e: Exception) {
                        // 批次2 2-3修复：同 executeToolStep 主路径——recordStep 失败时
                        // @Transaction 回滚导致 currentStep 没前进，继续循环会空转
                        // 到 deadlineAt 超时（虽不重复执行工具，但浪费 10 分钟）。
                        // markFailed 并 return，宁可中止也不空转。
                        runCatching {
                            repository.markFailed(jobId, "记录步骤失败（LLM 无效输出），中止工作流：${e.message?.take(120)}")
                        }
                        // §2.1.4 失败写回：LLM 无效输出 + recordStep 失败
                        recordJobFailure(repository, jobId, job.characterId, "llm_decision", "LLM 无效输出 + recordStep 失败：${e.message?.take(120)}", stepIndex)
                        return
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  单步：执行工具调用
    // ─────────────────────────────────────────────────────────

    private suspend fun executeToolStep(
        repository: WorkflowRepository,
        jobId: String,
        stepIndex: Int,
        decision: EngineDecision.CallTool,
        characterId: Int,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        val toolName = decision.call.toolName

        // 双重保险：即使 LLM 越界调用白名单外的工具（如绑定 currentCharacterId 不可靠的
        // ScheduleCreateTool 系列），引擎直接拒绝执行，记成失败步骤，不去碰那些风险点。
        // decideNextAction() 已经只把白名单内的工具描述喂给 LLM，这里是兜底，不依赖
        // LLM 严格遵守 Prompt 指令。
        if (toolName !in SAFE_TOOL_NAMES) {
            try {
                repository.recordStep(
                    jobId = jobId,
                    stepIndex = stepIndex,
                    toolName = toolName,
                    toolParamsJson = paramsToJson(decision.call.params),
                    success = false,
                    output = null,
                    errorMessage = "工具 $toolName 不在工作流安全白名单内，已拒绝执行",
                    decidedNextAction = decision.rawText,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis(),
                )
                // §2.1.4 镜像埋点：白名单拒绝也写一条心迹事件
                recordWorkflowActivity(
                    characterId    = characterId,
                    jobId          = jobId,
                    toolName       = toolName,
                    toolParamsJson = paramsToJson(decision.call.params),
                    success        = false,
                    output         = null,
                    errorMessage   = "工具 $toolName 不在工作流安全白名单内，已拒绝执行",
                    decisionNote   = decision.rawText,
                    startedAt      = startedAt,
                    completedAt    = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                // 批次2 2-3修复：recordStep 失败说明 DB 有问题。@Transaction 回滚
                // 导致 currentStep 没前进，若继续循环 LLM 会重复输出同一决策，对
                // 非幂等工具（git_commit_push/build_apk）产生重复副作用。此处改为
                // markFailed 并通知调用方中止循环，宁可中止也不重复跑副作用工具。
                runCatching {
                    repository.markFailed(jobId, "记录步骤失败（工具 $toolName 不在白名单），中止工作流避免重复执行：${e.message?.take(120)}")
                }
                return false
            }
            return true
        }

        // 问题24修复：与 ScheduledJobWorker（P-8 修复）同一套做法——注入 __character_id，
        // 工具执行时优先从 params 读取角色 ID，而不是回退到 characterIdProvider()（工作流
        // 后台执行时该闭包多为 -1 静态占位，或读到前台会话角色导致串数据）。
        // 只对本来就读取 __character_id 的工具生效（self_reflect/rule_review 等），
        // 不读这个 key 的工具（如 web_search）传了也不影响。
        val paramsWithCharId = decision.call.params + mapOf("__character_id" to characterId.toString())

        val tool = AgentToolRegistry.get(toolName)

        val result = if (tool == null) {
            null
        } else {
            try {
                tool.execute(paramsWithCharId)
            } catch (e: Exception) {
                // AgentTool 约定不抛异常，这里是双重兜底，避免任何意外异常打断整个工作流
                ToolResult(
                    toolName = toolName,
                    success = false,
                    content = "",
                    error = "工具执行异常：${e.message}",
                )
            }
        }

        // L-P1-1 修复：主路径 recordStep 补 try-catch，DB 写入失败仅记日志不阻断工作流
        try {
            repository.recordStep(
                jobId = jobId,
                stepIndex = stepIndex,
                toolName = toolName,
                toolParamsJson = paramsToJson(decision.call.params),
                success = result?.success == true,
                output = result?.content,
                errorMessage = when {
                    tool == null -> "工具未注册：$toolName"
                    result?.success == false -> result.error
                    else -> null
                },
                decidedNextAction = decision.rawText,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
            )
            // §2.1.4 镜像埋点：主路径也写一条心迹事件
            recordWorkflowActivity(
                characterId    = characterId,
                jobId          = jobId,
                toolName       = toolName,
                toolParamsJson = paramsToJson(decision.call.params),
                success        = result?.success == true,
                output         = result?.content,
                errorMessage   = when {
                    tool == null -> "工具未注册：$toolName"
                    result?.success == false -> result.error
                    else -> null
                },
                decisionNote   = decision.rawText,
                startedAt      = startedAt,
                completedAt    = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            // 批次2 2-3修复（核心风险点）：L-P1-1 此处只补了 try-catch 防止 DB 写入
            // 异常崩溃 App，但没解决原报告真正担心的问题——recordStepAtomic 用
            // @Transaction 把 insert 和 currentStep+1 绑在一起，写入失败时两者都
            // 回滚，被 catch 吞掉后循环回到顶部读到的还是旧 currentStep，LLM 在
            // temperature=0.1 下大概率重新输出同一条 CallTool 决策，工具被重复执行。
            // 对 git_commit_push 这类非幂等工具会产生重复 commit 污染历史；对
            // build_apk 会浪费 CI 配额。改为 markFailed 并返回 false 通知调用方
            // 中止循环，宁可中止也不重复跑副作用工具。
            runCatching {
                repository.markFailed(jobId, "记录步骤失败（工具 $toolName 副作用已执行），中止工作流避免重复执行：${e.message?.take(120)}")
            }
            return false
        }
        return true
    }

    // ─────────────────────────────────────────────────────────
    //  单步：向 LLM 请求决策
    // ─────────────────────────────────────────────────────────

    private suspend fun decideNextAction(
        job: WorkflowJobEntity,
        history: List<WorkflowStepResultEntity>,
        provider: LLMProvider,
        config: LLMConfig,
    ): EngineDecision {
        val systemPrompt = buildDecisionPrompt(job, history)
        val raw = try {
            provider.chatSyncWithRetry(
                messages = listOf(LLMMessage(role = "user", content = "请决定下一步行动。")),
                systemPrompt = systemPrompt,
                config = config,
            )
        } catch (e: Exception) {
            return EngineDecision.Invalid(rawText = "LLM 调用异常：${e.message}")
        }
        return parseDecision(raw)
    }

    private fun buildDecisionPrompt(
        job: WorkflowJobEntity,
        history: List<WorkflowStepResultEntity>,
    ): String = buildString {
        appendLine("你是一个任务执行 Agent，正在后台逐步完成一个复合目标。")
        appendLine("整个执行过程用户看不到，只会在最终看到一句汇报，所以你不需要保持人设语气，只需要做对决策。")
        appendLine()
        appendLine("[目标]")
        appendLine(job.goal)
        appendLine()
        if (history.isEmpty()) {
            appendLine("[执行历史] 尚未执行任何步骤。")
        } else {
            appendLine("[执行历史]")
            history.forEach { step ->
                val outcome = if (step.success) "成功" else "失败：${step.errorMessage ?: "未知错误"}"
                appendLine("第 ${step.stepIndex + 1} 步 · ${step.toolName ?: "（无工具调用）"} · $outcome")
                if (!step.output.isNullOrBlank()) {
                    appendLine("  结果摘要：${step.output.take(HISTORY_OUTPUT_TRUNCATE)}")
                }
            }
        }
        appendLine()
        val remainSteps = job.maxSteps - job.currentStep
        val remainMs = (job.deadlineAt - System.currentTimeMillis()).coerceAtLeast(0)
        appendLine("[剩余配额] 还可执行 $remainSteps 步，距总超时还剩约 ${remainMs / 1000} 秒。")
        appendLine("如果剩余配额明显不够完成目标，优先考虑用 <workflow:stuck/> 如实说明，而不是强行继续。")
        appendLine()
        // H3 修复：只把白名单内的工具描述喂给 LLM，而不是注册表中的全部工具。
        // 原实现调用 AgentToolRegistry.buildToolDescriptionBlock()，该函数返回所有已注册工具，
        // 包含依赖 ChatViewModel.currentCharacterId 的有状态工具（如 ScheduleCreateTool 系列），
        // 导致 LLM 看到这些工具后尝试调用，只靠 executeToolStep() 的执行前拦截作为唯一屏障。
        // 修复后从 Registry 中只取 SAFE_TOOL_NAMES 内的工具描述，双重保护真正生效。
        val safeTools = SAFE_TOOL_NAMES
            .mapNotNull { AgentToolRegistry.get(it) }
        if (safeTools.isNotEmpty()) {
            appendLine("[可用工具]")
            appendLine("当需要获取外部信息或执行计算时，在回复中嵌入以下格式的工具标签：")
            appendLine("<tool:工具名 参数名=\"参数值\"/>")
            appendLine()
            appendLine("可用工具：")
            safeTools.forEach { tool ->
                val paramDesc = tool.paramKeys.joinToString(" ") { key -> "$key=\"...\"" }
                appendLine("- ${tool.name}: $paramDesc")
            }
            appendLine()
            appendLine("工具执行后，结果会自动回注到对话中，你无需解释工具的存在。")
            appendLine()
        }
        appendLine("[输出要求]")
        appendLine("你的回复必须是且只能是下面三种标签中的恰好一个，不要输出任何其他文字、解释或多个标签：")
        appendLine("① 调用工具：<tool:工具名 参数=\"值\"/>")
        appendLine("② 目标已完成：<workflow:complete summary=\"给用户看的简短汇报，第一人称回顾口吻\"/>")
        appendLine("③ 无法继续（工具反复失败、目标本身有歧义或超出能力范围）：<workflow:stuck reason=\"卡住的原因\"/>")
    }

    // ─────────────────────────────────────────────────────────
    //  决策解析
    // ─────────────────────────────────────────────────────────

    private sealed class EngineDecision {
        data class CallTool(val call: ToolCall, val rawText: String) : EngineDecision()
        data class Complete(val summary: String, val rawText: String) : EngineDecision()
        data class Stuck(val reason: String, val rawText: String) : EngineDecision()
        data class Invalid(val rawText: String) : EngineDecision()
    }

    private val COMPLETE_PATTERN = Regex(
        """<workflow:complete\s+summary="((?:[^"\\]|\\.)*)"\s*/>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val STUCK_PATTERN = Regex(
        """<workflow:stuck\s+reason="((?:[^"\\]|\\.)*)"\s*/>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private fun parseDecision(raw: String): EngineDecision {
        COMPLETE_PATTERN.find(raw)?.let { match ->
            return EngineDecision.Complete(summary = unescapeAttr(match.groupValues[1]), rawText = raw)
        }
        STUCK_PATTERN.find(raw)?.let { match ->
            return EngineDecision.Stuck(reason = unescapeAttr(match.groupValues[1]), rawText = raw)
        }
        // 复用 Phase 13 ToolParser：非流式场景下一次性 feed 完整文本即可拿到完整标签
        val call = ToolParser().feed(raw).detectedCalls.firstOrNull()
        return if (call != null) {
            EngineDecision.CallTool(call = call, rawText = raw)
        } else {
            EngineDecision.Invalid(rawText = raw)
        }
    }

    private fun unescapeAttr(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\t", "\t")

    // ─────────────────────────────────────────────────────────
    //  工具方法
    // ─────────────────────────────────────────────────────────

    private fun paramsToJson(params: Map<String, String>): String =
        try {
            JSONObject(params).toString()
        } catch (_: Exception) {
            "{}"
        }

    // ─────────────────────────────────────────────────────────
    //  §2.1.4 心迹镜像埋点 + 失败写回
    // ─────────────────────────────────────────────────────────

    /**
     * §2.1.4 第1点：工作流步骤镜像写入 agent_activity_events（sceneType=workflow）。
     *
     * workflow_step_results 是工作流执行的事实来源，此处是镜像——让心迹时间线
     * 不必单独查 workflow_step_results 表就能看到工作流步骤。fire-and-forget，
     * DB 写入失败不影响工作流执行。
     */
    private suspend fun recordWorkflowActivity(
        characterId: Int,
        jobId: String,
        toolName: String?,
        toolParamsJson: String,
        success: Boolean,
        output: String?,
        errorMessage: String?,
        decisionNote: String?,
        startedAt: Long,
        completedAt: Long,
    ) {
        try {
            AppContainer.instance.agentActivityRepo.recordEvent(
                characterId    = characterId,
                sessionRef     = jobId,
                sceneType      = AgentActivityRepository.SceneType.WORKFLOW,
                eventType      = AgentActivityRepository.EventType.TOOL_CALL,
                toolName       = toolName,
                outcome        = if (success)
                    AgentActivityRepository.Outcome.SUCCESS
                else AgentActivityRepository.Outcome.FAIL,
                toolParamsJson = toolParamsJson,
                outputRaw      = output,
                errorMessage   = errorMessage,
                decisionNote   = decisionNote,
                startedAt      = startedAt,
                completedAt    = completedAt,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ZLog.w("WorkflowEngine", "心迹镜像埋点失败（不影响工作流）", e)
        }
    }

    /**
     * §2.1.4 第2点：Job 终结为 FAILED/TIMEOUT/STUCK 时，以整个 job 的 goal 为
     * goalContext 触发一次 [MemoryEngine.onToolFailureExhausted]。
     *
     * 不是每步单独触发，避免同一个失败任务反复写入多条几乎重复的记忆。
     * fire-and-forget，记忆写入失败不影响工作流终止流程。
     */
    private suspend fun recordJobFailure(
        repository: WorkflowRepository,
        jobId: String,
        characterId: Int,
        toolName: String,
        failureReason: String,
        stepsExecuted: Int,
    ) {
        val job = repository.findById(jobId) ?: return
        val goalContext = job.goal.take(200)
        try {
            AppContainer.instance.memoryEngine.onToolFailureExhausted(
                characterId       = characterId,
                toolName          = toolName,
                goalContext       = goalContext,
                failureReason     = failureReason,
                attemptsExhausted = stepsExecuted,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ZLog.w("WorkflowEngine", "失败写回记忆失败（不影响工作流终止）", e)
        }
    }

    private fun buildLimitReason(job: WorkflowJobEntity, cause: String): String =
        "在第 ${job.currentStep} 步处停止：$cause。已完成 ${job.currentStep} / ${job.maxSteps} 步。"
}
