package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.agent.AgentMessageTool
import com.zaijian.zhoumuyun.data.agent.registerAgentStoreTools
import com.zaijian.zhoumuyun.data.agent.ChainCreateTool
import com.zaijian.zhoumuyun.data.agent.CiCdStartTool
import com.zaijian.zhoumuyun.data.agent.FileExportTool
import com.zaijian.zhoumuyun.data.agent.FileSearchTool
import com.zaijian.zhoumuyun.data.agent.ImageEditTool
import com.zaijian.zhoumuyun.data.agent.MediaInfoTool
import com.zaijian.zhoumuyun.data.agent.PdfReadTool
import com.zaijian.zhoumuyun.data.agent.GoalUpdateTool
import com.zaijian.zhoumuyun.data.agent.ProgressReportTool
import com.zaijian.zhoumuyun.data.agent.RoundtableTriggerTool
import com.zaijian.zhoumuyun.data.agent.RuleConflictCheckTool
import com.zaijian.zhoumuyun.data.agent.SessionCompareTool
import com.zaijian.zhoumuyun.data.agent.TaskDelegateTool
import com.zaijian.zhoumuyun.data.agent.VaultCallContextHolder
import com.zaijian.zhoumuyun.data.agent.MemoryQueryTool
import com.zaijian.zhoumuyun.data.agent.MemoryWriteTool
import com.zaijian.zhoumuyun.data.agent.SkillCreateTool
import com.zaijian.zhoumuyun.data.agent.SkillDeprecateTool
import com.zaijian.zhoumuyun.data.agent.SkillEditTool
import com.zaijian.zhoumuyun.data.agent.SkillExpandTool
import com.zaijian.zhoumuyun.data.agent.SkillFeedbackTool
import com.zaijian.zhoumuyun.data.agent.PlanSaveTool
import com.zaijian.zhoumuyun.data.agent.ProjectDailyPlannerTool
import com.zaijian.zhoumuyun.data.agent.RuleDistillTool
import com.zaijian.zhoumuyun.data.agent.RuleReviewTool
import com.zaijian.zhoumuyun.data.agent.SelfReflectTool
import com.zaijian.zhoumuyun.data.agent.TaskStartTool
import com.zaijian.zhoumuyun.data.agent.TaskUpdateTool
import com.zaijian.zhoumuyun.data.agent.TaskCompleteTool
import com.zaijian.zhoumuyun.data.agent.TaskCancelTool
import com.zaijian.zhoumuyun.data.agent.WorkflowStartTool
import com.zaijian.zhoumuyun.data.agent.TableExportTool
import com.zaijian.zhoumuyun.data.agent.ScheduleCreateTool
import com.zaijian.zhoumuyun.data.agent.ScheduleListTool
import com.zaijian.zhoumuyun.data.agent.ScheduleDeleteTool
import com.zaijian.zhoumuyun.data.agent.ScheduleUpdateTool
import com.zaijian.zhoumuyun.data.agent.ScheduleGetTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatSetTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatUpdateTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatDeleteTool
import com.zaijian.zhoumuyun.data.agent.ReminderTool
import com.zaijian.zhoumuyun.data.agent.registerSoulMemoryUserTools
import com.zaijian.zhoumuyun.data.agent.CalendarSyncHelper
import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.dao.EvaluationSessionDao
import com.zaijian.zhoumuyun.data.db.dao.LearningGoalDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryDao
import com.zaijian.zhoumuyun.data.db.dao.MessageDao
import com.zaijian.zhoumuyun.data.db.dao.TaskDao
import com.zaijian.zhoumuyun.data.memory.MemoryEngine
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.AgentPlanRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.LearningGoalRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.SkillRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import com.zaijian.zhoumuyun.data.repository.TaskRepository
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import com.zaijian.zhoumuyun.data.repository.ChainRunRepository
import com.zaijian.zhoumuyun.data.repository.ProjectRepository
import com.zaijian.zhoumuyun.data.repository.AgentStoreRepository

/**
 * 封装 Agent 工具注册逻辑，从 ChatViewModel 中提取。
 *
 * 静态工具（不依赖角色的 CiCdStartTool、ProjectDailyPlannerTool）在 [registerStaticTools]
 * 中注册一次即可；角色相关工具在 [registerCharacterTools] 中按 [currentCharacterId]
 * 动态覆盖注册，避免重复实例化。
 */
class ChatToolRegistrar(
    private val db: AppDatabase,
    private val getApplication: () -> Application,
    private val agentPlanRepo: AgentPlanRepository,
    private val memoryRepo: MemoryRepository,
    private val memoryDao: MemoryDao,
    private val learningGoalRepo: LearningGoalRepository,
    private val workflowRepo: WorkflowRepository,
    // 灵活自动化编排（验收缺口修复）：ChainCreateTool 覆盖注册用，与 workflowRepo
    // 同款来源——ChatViewModel 传入 AppContainer.instance.chainRunRepository。
    private val chainRunRepository: ChainRunRepository,
    private val taskRepo: TaskRepository,
    private val memoryEngine: MemoryEngine,
    private val scheduleRepo: ScheduleRepository,
    private val calendarSync: CalendarSyncHelper,
    private val identityRepo: IdentityRepository,
    private val githubConfigStore: GithubConfigDataStore,
    private val skillRepo: SkillRepository,
    // 同文件-01/02 修复：ScheduleCreateTool/ScheduleListTool 覆盖注册时需要
    // 真实 ProjectRepository，否则回落 null 导致 project_id 参数/项目标题关联失效。
    private val projectRepo: ProjectRepository,
    // Agent 结构化存储（方案_Agent结构化存储_最终版）：与上方各 repo 同款由
    // ChatViewModel 显式传入，供本类 registerCharacterTools() 第②处覆盖注册使用。
    private val agentStoreRepo: AgentStoreRepository,
) {
    private var toolsRegisteredForCharacterId: Int? = null

    /**
     * 注册不依赖角色 ID 的静态工具（CiCdStartTool 固定 -1，
     * ProjectDailyPlannerTool 不绑定角色），只需调用一次。
     */
    fun registerStaticTools() {
        // W6-3 修复：不依赖 characterId 的工具（CiCdStartTool 固定 -1，
        // ProjectDailyPlannerTool 不绑定角色）只在 ViewModel 创建时注册一次，
        // 不再每次切换角色时重复实例化——这些工具的构造函数参数在 ViewModel
        // 生命周期内不变，重复注册只是浪费对象分配。
        AgentToolRegistry.register(
            CiCdStartTool(
                context = getApplication(),
                githubConfigStore = githubConfigStore,
                db = db,
                workflowJobDao = db.workflowJobDao(),
                workflowStepResultDao = db.workflowStepResultDao(),
                characterId = { -1 },
            )
        )
        AgentToolRegistry.register(
            ProjectDailyPlannerTool(
                db         = db,
                projectDao = db.projectDao(),
                goalDao    = db.characterGoalDao(),
                taskDao    = db.taskDao(),
            )
        )
        // 文件处理·纯功能方案 v5：四个不依赖角色态的静态工具
        AgentToolRegistry.registerAll(
            PdfReadTool(context = getApplication()),
            MediaInfoTool(context = getApplication()),
            ImageEditTool(context = getApplication()),
            FileSearchTool(context = getApplication()),
        )
    }

    /**
     * 注册与当前角色绑定的工具，使用 [currentCharacterId] 动态覆盖
     * ZaijianApp 中 characterIdProvider={-1} 的静态占位注册。
     */
    fun registerCharacterTools(currentCharacterId: Int) {
        // v147 验收返工：setPersonal 降级为"默认值/兜底"——主身份来源已改为
        // 协程局部的 VaultCallContextElement（ChatMessageOrchestrator 在
        // streamWithTools 外层用 withVaultContext 注入）。此处 setPersonal
        // 仅用于无协程上下文的兜底路径（如 WorkflowEngine 后台执行）。
        // 放在 early-return 之前——即使工具已为该角色注册过，也要把默认身份
        // 复位到该角色，避免圆桌残留的 ROUNDTABLE 身份影响兜底路径。
        VaultCallContextHolder.setPersonal(currentCharacterId)

        if (toolsRegisteredForCharacterId == currentCharacterId) return
        toolsRegisteredForCharacterId = currentCharacterId

        val providerFn = { ProviderManager.instance.activeProvider }
        // 批次2 2-1修复：AgentMetaTools 的6个角色相关工具（rule_conflict_check /
        // session_compare / progress_report / agent_message / roundtable_trigger /
        // task_delegate）在 ZaijianApp.registerAgentMetaTools() 里以
        // characterIdProvider={-1} 静态注册，从未在此处被覆盖。后果：
        // - RoundtableTriggerTool 无 charId<0 校验，-1.coerceAtLeast(0)=0，
        //   消息静默写入 characterId=0（不存在的角色），工具仍返回 success=true
        //   ——假装成功+数据落脏。
        // - 其余4个有 charId<0 校验的工具在私聊里100%返回"角色未初始化"。
        // 此处用 currentCharacterId 动态覆盖，与 SelfReflectTool/RuleReviewTool
        // 同一覆盖范式。依赖来源对齐 ZaijianApp.registerAgentMetaTools() 调用处
        // （ZaijianApp.kt 第813-821行），勿与 registerStaticTools() 里
        // ProjectDailyPlannerTool 用的 db.characterGoalDao() 混用——这里要用
        // db.learningGoalDao()。
        val agentMessageRepo = MessageRepository(db.messageDao())
        val agentSessionDao  = db.evaluationSessionDao()
        val agentGoalDao     = db.learningGoalDao()
        val agentTaskDao     = db.taskDao()
        val agentFileExport  = FileExportTool.getInstance(getApplication())
        AgentToolRegistry.registerAll(
            PlanSaveTool(agentPlanDao = agentPlanRepo, characterId = { currentCharacterId }),
            MemoryWriteTool(memoryRepository = memoryRepo, characterId = { currentCharacterId }),
            MemoryQueryTool(memoryRepo = memoryRepo, characterId = { currentCharacterId }),
            // Window C 技能系统：5 个 AgentTool，characterId 范式对齐 MemoryWriteTool。
            // 静态占位（{-1}）已在 ZaijianApp.registerAgentTools() 注册，此处用真实角色覆盖。
            SkillCreateTool(repo = skillRepo, characterId = { currentCharacterId }),
            SkillEditTool(repo = skillRepo, characterId = { currentCharacterId }),
            SkillDeprecateTool(repo = skillRepo, characterId = { currentCharacterId }),
            SkillExpandTool(repo = skillRepo, characterId = { currentCharacterId }),
            SkillFeedbackTool(repo = skillRepo, characterId = { currentCharacterId }),
            GoalUpdateTool(goalRepo = learningGoalRepo, characterId = { currentCharacterId }),
            WorkflowStartTool(
                context = getApplication(),
                workflowRepository = workflowRepo,
                characterId = { currentCharacterId },
            ),
            // 灵活自动化编排（验收缺口修复）：ChainCreateTool 在 ZaijianApp 里以
            // characterIdProvider={-1} 静态占位注册（同 WorkflowStartTool 模式），
            // 但此前从未在这里被覆盖——execute() 第一行 charId<0 即拒绝，导致
            // chain_create 在任何真实聊天场景下都会 100% 返回"角色未初始化"，
            // 功能表面存在实则不可用。此处补上覆盖注册，与 WorkflowStartTool
            // 同一覆盖范式、同一 currentCharacterId 来源。
            ChainCreateTool(
                chainRunRepository = chainRunRepository,
                characterId = { currentCharacterId },
            ),
            // ── 2.3 工作台任务跟踪修复：补上"开始/更新/完成/取消"任务的入口 ──
            TaskStartTool(taskRepo = taskRepo, characterId = { currentCharacterId }),
            TaskUpdateTool(taskRepo = taskRepo, characterId = { currentCharacterId }),
            // W3-2 修复：补传 memoryEngine，任务完成后触发 onTaskCompleted 写记忆
            TaskCompleteTool(
                taskRepo    = taskRepo,
                characterId = { currentCharacterId },
                memoryEngine = { memoryEngine },
            ),
            TaskCancelTool(taskRepo = taskRepo, characterId = { currentCharacterId }),
            // 问题39修复：Soul/Memory/User 三模块 6 个工具的实例化代码此前在本文件
            // 和 ZaijianApp.kt 各写一份（仅 characterId 闭包不同），改用
            // AgentToolRegistry.registerSoulMemoryUserTools() 统一封装，本处传
            // currentCharacterId 覆盖 ZaijianApp 里的 -1 静态占位——覆盖时机、
            // 覆盖原因（updateSoulNote 等否则永远打到 characterId=-1 的行）均不变，
            // 只是不再各自手写 6 行几乎相同的构造代码。
            //
            // 注意：AgentToolRegistry.registerAll(...) 这个 vararg 调用只接受
            // AgentTool 实例，registerSoulMemoryUserTools() 是扩展函数不是
            // AgentTool，因此在 registerAll(...) 调用结束后单独调用（见下方）。
            // ── Fix-#1: 覆盖 ZaijianApp 里 characterIdProvider={-1} 的静态注册 ──
            // schedule_create / schedule_list / heartbeat_set / heartbeat_update /
            // heartbeat_delete 这5个工具在 ZaijianApp 里以 -1 注册，导致任务写入错误
            // 角色行，observeAndNotifyResults() 找不到 characterId=-1 的角色，
            // 推送永久跳过。此处用当前会话的 currentCharacterId 动态覆盖。
            // 问题8修复：补上 calendarSync/context，否则覆盖注册后这两个参数
            // 回落到构造函数默认值 null，日历同步与 WorkManager 精确调度失效。
            ScheduleCreateTool(
                scheduleRepository  = scheduleRepo,
                characterIdProvider = { currentCharacterId },
                projectRepository = projectRepo,
                calendarSync = calendarSync,
                context = getApplication(),
            ),
            ScheduleListTool(
                scheduleRepository  = scheduleRepo,
                characterIdProvider = { currentCharacterId },
                projectRepository = projectRepo,
            ),
            // 同文件-03/04/05 修复：schedule_delete/update/get 此前从未在此处覆盖
            // 注册，一直停留在 ZaijianApp 的 characterIdProvider={-1} 静态占位上。
            // 修复时顺带发现这三个工具原本压根没有 characterId 概念（只按任务 id
            // 操作），已在各自文件内补上 characterIdProvider 构造参数 + 归属校验
            // （existing.characterId != charId 时按"找不到"处理），此处与
            // schedule_create/schedule_list 同款用 currentCharacterId 覆盖。
            ScheduleDeleteTool(
                scheduleRepository  = scheduleRepo,
                calendarSync        = calendarSync,
                context             = getApplication(),
                characterIdProvider = { currentCharacterId },
            ),
            ScheduleUpdateTool(
                scheduleRepository  = scheduleRepo,
                projectRepository   = projectRepo,
                calendarSync        = calendarSync,
                context             = getApplication(),
                characterIdProvider = { currentCharacterId },
            ),
            ScheduleGetTool(
                scheduleRepository  = scheduleRepo,
                projectRepository   = projectRepo,
                characterIdProvider = { currentCharacterId },
            ),
            HeartbeatSetTool(
                context             = getApplication(),
                characterIdProvider = { currentCharacterId },
            ),
            HeartbeatUpdateTool(
                context             = getApplication(),
                characterIdProvider = { currentCharacterId },
            ),
            HeartbeatDeleteTool(
                context             = getApplication(),
                characterIdProvider = { currentCharacterId },
            ),
            // U2 延伸修复：覆盖 ZaijianApp 里 characterIdProvider={-1} 的静态注册——
            // 否则提醒触发时通知上的「查看日程」按钮永远指向 personal_schedule/-1，
            // 查不到角色，按钮形同失效。
            ReminderTool(
                context             = getApplication(),
                characterIdProvider = { currentCharacterId },
            ),
            // 问题24修复：SelfReflectTool（self_reflect）/RuleReviewTool（rule_review）
            // 在 DataVisTools.registerDataVisTools() 里以 characterIdProvider={-1} 静态
            // 注册，此前和 schedule_create 等一样从未在 ChatViewModel 里被覆盖注册。
            // execute() 内部虽然优先读 params["__character_id"]（LLM 工作流标签注入时
            // 能拿到正确角色），但私聊场景下 LLM 输出的 <tool:self_reflect .../> 标签
            // 通常不带 __character_id 属性（不是所有触发路径都走工作流注入），此时
            // fallback 到 characterIdProvider() 就会拿到 -1——反思记忆写入 characterId=-1
            // 这一不存在的行，查询该角色 WORK 域记忆时永远查不到；rule_review 同理会审视
            // 到 charId=-1 下的规则（大概率为空），而不是当前正在聊天的角色的规则。
            // 与 schedule_create/heartbeat_* 等既有 Fix-#1 覆盖注册同一模式，用
            // currentCharacterId 动态覆盖。
            SelfReflectTool(
                providerFn          = providerFn,
                memoryDao           = memoryDao,
                memoryRepo          = memoryRepo,
                characterIdProvider = { currentCharacterId },
            ),
            RuleReviewTool(
                providerFn          = providerFn,
                memoryDao           = memoryDao,
                characterIdProvider = { currentCharacterId },
            ),
            // W2 表格直传方案：覆盖 ZaijianApp.registerDataVisTools() 里
            // characterIdProvider={-1} 的静态占位注册——与 SelfReflectTool/RuleReviewTool
            // 同款 Fix-#1 模式。table_export 的来源 B（日程）需要真实 characterId 做
            // 跨角色权限校验（requestedCharId != currentCharId 则拒绝），静态注册的
            // {-1} 会让权限校验形同虚设（currentCharId=-1 时放行所有请求）。
            // 来源 A（CSV）的 vault 权限判断也依赖 currentVaultContext() 协程上下文里的
            // characterId，但工具实例本身持有 characterIdProvider 作为 fallback，这里
            // 覆盖后与协程上下文取值一致（currentVaultContext 已由 ToolCallInterceptor 注入）。
            TableExportTool(
                context             = getApplication(),
                scheduleRepository  = scheduleRepo,
                characterIdProvider = { currentCharacterId },
            ),
            // 批次2 2-1修复：覆盖 ZaijianApp.registerAgentMetaTools() 里
            // characterIdProvider={-1} 的6个静态占位注册。构造参数对齐
            // AgentMetaTools.kt 第887-917行，仅 characterIdProvider 改为
            // currentCharacterId。局部变量复用避免重复构造（agentMessageRepo
            // 被 AgentMessageTool/RoundtableTriggerTool 共用，agentSessionDao
            // 被 SessionCompareTool/ProgressReportTool 共用）。
            RuleConflictCheckTool(
                providerFn          = providerFn,
                memoryDao           = memoryDao,
                characterIdProvider = { currentCharacterId },
            ),
            SessionCompareTool(
                providerFn          = providerFn,
                sessionDao          = agentSessionDao,
                characterIdProvider = { currentCharacterId },
            ),
            ProgressReportTool(
                providerFn          = providerFn,
                sessionDao          = agentSessionDao,
                goalDao             = agentGoalDao,
                memoryDao           = memoryDao,
                fileExportTool      = agentFileExport,
                characterIdProvider = { currentCharacterId },
            ),
            AgentMessageTool(
                messageDao          = agentMessageRepo,
                characterIdProvider = { currentCharacterId },
            ),
            RoundtableTriggerTool(
                messageDao          = agentMessageRepo,
                characterIdProvider = { currentCharacterId },
            ),
            TaskDelegateTool(
                providerFn          = providerFn,
                db                  = db,
                taskDao             = agentTaskDao,
                characterIdProvider = { currentCharacterId },
            ),
        )
        // 问题39修复：见上方 registerAll(...) 内注释——统一封装的 Soul/Memory/User
        // 6 个工具注册，在此处传 currentCharacterId 覆盖 ZaijianApp 里的 -1 占位。
        AgentToolRegistry.registerSoulMemoryUserTools(
            identityDao = identityRepo,
            characterId = { currentCharacterId },
        )
        // Agent 结构化存储（方案_Agent结构化存储_最终版 8.10 第②处）：覆盖 ZaijianApp 里
        // characterIdProvider={-1} 的静态占位注册——5 个 store_* 工具若停留在 -1 占位版本，
        // 会把数据全部写到 ownerCharacterId=-1 这个不存在的角色下，工具执行"成功"但查不到。
        // 此处用 currentCharacterId 覆盖，与 SkillCreateTool/MemoryWriteTool 同款两阶段注册。
        AgentToolRegistry.registerAgentStoreTools(
            repo = agentStoreRepo,
            characterIdProvider = { currentCharacterId },
        )
        // S8-窗口11 P1-8-7 修复：改为 providerFn 闭包模式后，无需在注册时刻
        // 判断 providerFn() 是否为 null 才决定是否注册——工具本身可以无条件
        // 注册，execute() 时才动态取最新 Provider。此前 `providerFn()?.let` 写法
        // 若角色切换时刻用户恰好未配置 Key，会导致该次覆盖注册被跳过，
        // rule_distill 停留在 ZaijianApp 阶段的 characterId=-1 占位版本上，
        // 直到下次角色切换才有机会补上；改为无条件注册后不再有这个空窗。
        AgentToolRegistry.register(
            RuleDistillTool(providerFn = providerFn, memoryRepo = memoryRepo, goalRepo = learningGoalRepo, characterId = { currentCharacterId })
        )
    }
}