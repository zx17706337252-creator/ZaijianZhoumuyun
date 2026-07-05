package com.zaijian.zhoumuyun

// ⚠️ 重命名记录: Phase22Tools→AgentCoreTools, Phase25Tools→(merged), Phase28Part*→Creative/DataVis/AgentMetaTools

import android.app.Application
import com.zaijian.zhoumuyun.util.ZLog
import android.content.Context
import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.agent.GoalUpdateTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatDeleteTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatSetTool
import com.zaijian.zhoumuyun.data.agent.HeartbeatUpdateTool
import com.zaijian.zhoumuyun.data.agent.MemoryQueryTool
import com.zaijian.zhoumuyun.data.agent.MemoryWriteTool
import com.zaijian.zhoumuyun.data.agent.NarrativeMemoryClearTool
import com.zaijian.zhoumuyun.data.agent.NarrativeMemoryUpdateTool
import com.zaijian.zhoumuyun.data.agent.PlanSaveTool
import com.zaijian.zhoumuyun.data.agent.RuleDistillTool
import com.zaijian.zhoumuyun.data.agent.ScheduleCreateTool
import com.zaijian.zhoumuyun.data.agent.ScheduleDeleteTool
import com.zaijian.zhoumuyun.data.agent.ScheduleGetTool
import com.zaijian.zhoumuyun.data.agent.ScheduleListTool
import com.zaijian.zhoumuyun.data.agent.ScheduleUpdateTool
import com.zaijian.zhoumuyun.data.agent.SoulClearTool
import com.zaijian.zhoumuyun.data.agent.SoulUpdateTool
import com.zaijian.zhoumuyun.data.agent.BuildApkDownloadTool
import com.zaijian.zhoumuyun.data.agent.BuildApkTool
import com.zaijian.zhoumuyun.data.agent.BuildStatusCheckTool
import com.zaijian.zhoumuyun.data.agent.CreateGithubRepoTool
import com.zaijian.zhoumuyun.data.agent.GitCommitPushTool
import com.zaijian.zhoumuyun.data.agent.UserImpressionClearTool
import com.zaijian.zhoumuyun.data.agent.UserImpressionUpdateTool
import com.zaijian.zhoumuyun.data.agent.registerBuiltinTools
import com.zaijian.zhoumuyun.data.agent.registerCreativeTools
import com.zaijian.zhoumuyun.data.agent.registerFileSystemTools
import com.zaijian.zhoumuyun.data.agent.registerDataTools
import com.zaijian.zhoumuyun.data.agent.registerPersonalTools
import com.zaijian.zhoumuyun.data.agent.registerCreativeDocTools
import com.zaijian.zhoumuyun.data.agent.registerDataVisTools
import com.zaijian.zhoumuyun.data.agent.registerAgentMetaTools
import com.zaijian.zhoumuyun.data.agent.registerEmailTools
import com.zaijian.zhoumuyun.data.datastore.AppearanceDataStore
import com.zaijian.zhoumuyun.data.datastore.EmailAccountStore
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.engine.PresenceEngine
import com.zaijian.zhoumuyun.data.engine.WorldSimulation
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import com.zaijian.zhoumuyun.data.agent.CompetitionRoundManager
import com.zaijian.zhoumuyun.data.agent.DailyPracticeScheduler
import com.zaijian.zhoumuyun.data.agent.ProjectDailyPlannerTool
import com.zaijian.zhoumuyun.data.engine.CompetitionEngine
import com.zaijian.zhoumuyun.data.engine.SpecialtyEvolutionEngine
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.SpecialtyProfileRepository

/**
 * Application 入口（Phase 17 升级版）
 *
 * Phase 10 新增：
 * - 启动 WorldSimulation（三档懒计算，启动保护 10s）
 * - PresenceEngine 初始化
 *
 * Phase 11 新增：
 * - PresenceEngine 接入 WorldEventDao，Presence 变化持久化写入 world_events
 * - WorldSimulation Tier 3 接入 MemoryEngine.applyDecayAll()
 *
 * Phase 13 新增：
 * - 注册 5 个内置 Agent 工具（web_search / calculator / datetime / translate / file_read）
 * - 注册在 ProviderManager.init() 之后，确保 App 完整初始化后工具可用
 * - 所有工具注册为单例（App 生命周期内持有），AgentToolRegistry 线程安全
 *
 * Phase 17 新增：
 * - 注册 3 个新工具（weather / note_save / reminder）
 *
 * Phase 22 说明：
 * - 新增 4 个工具（plan_save / memory_write / memory_query / goal_update）
 * - 这 4 个工具绑定当前角色 ID，由 ChatViewModel.init(characterId) 动态注册
 * - ZaijianApp 中不静态注册，避免 characterId 未初始化时工具执行出错
 * - DB 升级至 v8（新增 agent_plans / learning_goals 表）
 * - weather：调用 open-meteo.com，完全免 Key，支持中文城市名解析
 * - note_save：写入 app 内部 notes/ 目录，永久本地持久化
 * - reminder：写入 app 内部 reminders/ JSON 文件，供后续 AlarmScheduler 接入
 *
 * Fix-17 重构：
 * - BuiltinTools.kt 拆分为 4 个模块文件
 * - 工具注册改为按模块调用（registerBuiltinTools / registerDataTools 等扩展函数）
 */
class ZaijianApp : Application() {

    // 单例（App 生命周期内持有，避免 GC）
    private lateinit var worldSimulation: WorldSimulation

    companion object {
        /**
         * Phase 30 方案二：公开 PresenceEngine 实例，
         * 供 PresenceViewModel 订阅 taskCompletionFlow。
         * 在 onCreate() 完成初始化前为 null。
         */
        // Fix-13-19：三个共享单例原为普通 var，跨线程读写存在可见性竞态。
        // 加 @Volatile 保证写操作对后续读线程立即可见。
        @Volatile var sharedPresenceEngine: com.zaijian.zhoumuyun.data.engine.PresenceEngine? = null
            private set

        /**
         * P1-13-21 修复：将 appScope 公开为 companion object 成员，
         * 供 ZaijianMessagingService 等非 Activity/Fragment 组件复用，
         * 取代各处非结构化的 CoroutineScope(Dispatchers.IO).launch。
         * Application 销毁前不会被 cancel，生命周期与进程绑定。
         */
        @Volatile var appScope: kotlinx.coroutines.CoroutineScope? = null
            private set

        /** Fix-14: 外观设置共享单例，由 onCreate() 提前初始化 */
        @Volatile var sharedAppearanceDataStore: AppearanceDataStore? = null
            private set

        /** 裁判与竞争机制核心引擎（窗口2A），供 CompetitionViewModel 取用 */
        @Volatile var sharedCompetitionEngine: CompetitionEngine? = null
            private set

        /** 裁判与竞争机制编排器（窗口2A），供 CompetitionViewModel 取用 */
        @Volatile var sharedCompetitionRoundManager: CompetitionRoundManager? = null
            private set

        /**
         * P-13 修复：tryBuildCompetitionEngine 装配段互斥锁。
         * 原仅靠 if (sharedCompetitionEngine != null) return 幂等检查，
         * 两个并发调用可能同时通过 null 检查并各自创建引擎实例，后者覆盖前者。
         */
        private val buildMutex = Mutex()

        /**
         * Fix-13-18：竞争引擎延迟初始化入口。
         * 启动时无 API Key → 引擎跳过初始化；用户后续在 ProfileScreen 配置 Key 后
         * 调用此函数完成装配，避免"先启动后配置 Key 则功能永久失效"的问题。
         * 已初始化过则幂等跳过（双检查，sharedCompetitionEngine != null 时直接返回）。
         */
        fun tryInitCompetitionEngines(context: android.content.Context) {
            if (sharedCompetitionEngine != null) return   // 已初始化，幂等
            val provider = ProviderManager.instance.activeProvider ?: return  // Key 仍未配置
            val db = com.zaijian.zhoumuyun.data.db.AppDatabase.getInstance(context)
            val specialtyEvolutionEngine = com.zaijian.zhoumuyun.data.engine.SpecialtyEvolutionEngine(provider)
            val competitionEngine = CompetitionEngine(
                provider        = provider,
                evolutionEngine = specialtyEvolutionEngine,
            )
            val competitionRoundManager = com.zaijian.zhoumuyun.data.agent.CompetitionRoundManager(
                db = db,
                competitionEngine = competitionEngine,
                daughterRepo = com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository(db.daughterCharacterDao()),
                memoryRepo = com.zaijian.zhoumuyun.data.repository.MemoryRepository(
                    memoryDao    = db.memoryDao(),
                    candidateDao = db.memoryCandidateDao(),
                ),
                specialtyProfileRepository = com.zaijian.zhoumuyun.data.repository.SpecialtyProfileRepository(
                    db                       = db,
                    specialtyProfileDao      = db.specialtyProfileDao(),
                    evolutionPlanDao         = db.evolutionPlanDao(),
                    practiceRecordDao        = db.practiceRecordDao(),
                    practiceRecordArchiveDao = db.practiceRecordArchiveDao(),
                    stageDigestDao           = db.stageDigestDao(),
                    systemSuggestionDao      = db.systemSuggestionDao(),
                ),
                provider = provider,
            )
            sharedCompetitionEngine = competitionEngine
            sharedCompetitionRoundManager = competitionRoundManager
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 0. Fix-14: 外观设置共享单例提前初始化
        sharedAppearanceDataStore = AppearanceDataStore(this)

        // 0a. Phase 30 方案六：注册通知渠道（Android 8+ 必须）
        setupNotificationChannels()

        // 1. 初始化 Room 数据库（v4）
        val db = AppDatabase.getInstance(this)

        // 2. 初始化 API Provider 管理器（延迟初始化 + 后台预加载）
        ProviderManager.init(this)
        ProviderManager.instance.preloadAsync()

        // 性能 M3 修复（完整版）：appScope 提前声明，全部工具注册 + 调度补偿均在后台执行。
        // 原代码在主线程同步完成：30+ 工具实例化、EncryptedSharedPreferences Keystore 读取
        // （activeProvider）、scheduleRepository 构建 + 云端同步。主线程累计耗时可达数百毫秒，
        // 低端机冷启动时产生明显白屏/卡顿。
        // 改为 appScope.launch(Dispatchers.Default) 后，主线程 onCreate 仅保留：
        //   · AppDatabase.getInstance（synchronized 单例，仅首次建库时有 IO，<50ms）
        //   · ProviderManager.init（SharedPreferences 读取，极轻）
        //   · PresenceEngine 构建（纯内存，无 IO）
        //   · WorldSimulation 构建 + start（纯内存 + 定时器启动）
        //   · ActivityLifecycleCallbacks 注册（无 IO）
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        appScope = scope   // P1-13-21：公开给 FCM Service 等全局使用
        val appScope = scope

        // 3. 注册 Agent 工具（按模块）
        //
        // 性能 M3 修复（完整版）：全部工具注册块移入 appScope.launch(Dispatchers.Default)，
        // 彻底解除对主线程的占用。原代码在主线程同步执行约 30+ 个工具实例化 + registerAll 调用，
        // 低端机冷启动时可产生明显卡顿。工具注册只需在 ChatViewModel 首次收到用户消息前完成，
        // 而 Activity 从 Application.onCreate 结束到用户实际发送第一条消息之间通常有数秒余量，
        // 异步注册不会影响工具可用性。
        //
        // AgentToolRegistry.allNames() 为空时，ToolCallInterceptor 透传 provider.chat()，零开销；
        // 注册进行中如有 LLM 返回工具标签，最多丢弃一次（极低概率，且仅冷启动极短窗口内），可接受。
        appScope.launch(Dispatchers.Default) {
            // 顺序：本地无网络工具 → 网络工具 → 个人助手 → 创作 → CoreTools → CreativeDoc/DataVis/AgentMeta
            AgentToolRegistry.registerDataTools()
            AgentToolRegistry.registerBuiltinTools(this@ZaijianApp)
            AgentToolRegistry.registerPersonalTools(this@ZaijianApp)
            AgentToolRegistry.registerCreativeTools()
            AgentToolRegistry.registerFileSystemTools(this@ZaijianApp)

            // ── AgentCoreTools（原 Phase22 + Phase25）─────────────────────────────
            //
            // 注意：MemoryWriteTool 依赖 MemoryRepository（保证 FTS 同步写入），
            //       不可直接传 memoryDao；其余工具直接依赖 Dao 即可。
            //       characterIdProvider 以 -1 静态注册，由 ChatViewModel.init(characterId)
            //       动态覆盖注册（与 CreativeDocTools 等模块一致）。
            val memoryRepository = MemoryRepository(
                memoryDao    = db.memoryDao(),
                candidateDao = db.memoryCandidateDao(),
            )
            AgentToolRegistry.registerAll(
                PlanSaveTool(
                    agentPlanDao = db.agentPlanDao(),
                    characterId  = { -1 },
                ),
                MemoryWriteTool(
                    memoryRepository = memoryRepository,
                    characterId      = { -1 },
                ),
                MemoryQueryTool(
                    memoryDao   = db.memoryDao(),
                    characterId = { -1 },
                ),
                GoalUpdateTool(
                    goalDao     = db.learningGoalDao(),
                    characterId = { -1 },
                ),
                // ── Soul/Memory/User 三模块 ──────────────────────────
                SoulUpdateTool(
                    identityDao = db.characterIdentityDao(),
                    characterId = { -1 },
                ),
                SoulClearTool(
                    identityDao = db.characterIdentityDao(),
                    characterId = { -1 },
                ),
                NarrativeMemoryUpdateTool(
                    identityDao = db.characterIdentityDao(),
                    characterId = { -1 },
                ),
                NarrativeMemoryClearTool(
                    identityDao = db.characterIdentityDao(),
                    characterId = { -1 },
                ),
                UserImpressionUpdateTool(
                    identityDao = db.characterIdentityDao(),
                    characterId = { -1 },
                ),
                UserImpressionClearTool(
                    identityDao = db.characterIdentityDao(),
                    characterId = { -1 },
                ),
            )

            // 若 provider 此时为 null（首次启动未配置 Key），工具将在
            // ChatViewModel 完成配置后由动态覆盖注册补上。
            // activeProvider 访问在 Default 线程内进行，避免 Keystore 首次创建耗时阻塞主线程。
            ProviderManager.instance.activeProvider?.let { p25Provider ->
                AgentToolRegistry.register(
                    RuleDistillTool(
                        provider    = p25Provider,
                        memoryRepo  = MemoryRepository(db.memoryDao(), db.memoryCandidateDao()),
                        memoryDao   = db.memoryDao(),
                        goalDao     = db.learningGoalDao(),
                        characterId = { -1 },
                    )
                )
            }

            // ── CreativeDocTools / DataVisTools / AgentMetaTools ─────────────────
            AgentToolRegistry.registerCreativeDocTools(this@ZaijianApp)
            AgentToolRegistry.registerDataVisTools(
                context    = this@ZaijianApp,
                memoryDao  = db.memoryDao(),
                // 复审修复：SelfReflectTool 的 Step3 写入需要走 MemoryRepository.save()
                // 才能同步 FTS，否则自我反思记忆永久无法被全文检索召回。
                // 复用本函数作用域内已创建的 memoryRepository（第176行），
                // 不再新建实例。
                memoryRepo = memoryRepository,
            )
            AgentToolRegistry.registerAgentMetaTools(
                context    = this@ZaijianApp,
                memoryDao  = db.memoryDao(),
                sessionDao = db.evaluationSessionDao(),
                goalDao    = db.learningGoalDao(),
                messageDao = db.messageDao(),
                taskDao    = db.taskDao(),
            )

            // ── CICD · GitHub 配置存储 ──────────────────────────────
            val githubConfigStore = GithubConfigDataStore(this@ZaijianApp)

            // ── 邮件账号存储 + 真实邮件收发工具（email_send / email_fetch）──
            val emailAccountStore = EmailAccountStore(this@ZaijianApp)
            AgentToolRegistry.registerEmailTools(emailAccountStore)

            // ── 成长系统 · 每日自我规划工具（project_daily_planner）────────
            // provider 为 null 时静默跳过（首次启动未配置Key），
            // ChatViewModel 完成配置后会覆盖注册。
            ProviderManager.instance.activeProvider?.let { plannerProvider ->
                AgentToolRegistry.register(
                    ProjectDailyPlannerTool(
                        provider   = plannerProvider,
                        projectDao = db.projectDao(),
                        goalDao    = db.characterGoalDao(),
                        taskDao    = db.taskDao(),
                    )
                )
            }

            // ── CICD · 注册原子工具（流水线各步骤可单独被 LLM 调用）──────
            AgentToolRegistry.registerAll(
                CreateGithubRepoTool(githubConfigStore),
                GitCommitPushTool(githubConfigStore),
                BuildApkTool(githubConfigStore),
                BuildStatusCheckTool(githubConfigStore),
                BuildApkDownloadTool(context = this@ZaijianApp, githubConfigStore = githubConfigStore),
            )

            // ── Phase 29 · 调度系统初始化 ─────────────────────────────
            val scheduleRepository = ScheduleRepository(
                scheduledJobDao = db.scheduledJobDao(),
                jobResultDao    = db.jobResultDao(),
            )

            // 注册 schedule_create 工具（characterId 由 ChatViewModel 动态覆盖）
            AgentToolRegistry.register(
                ScheduleCreateTool(
                    scheduleRepository  = scheduleRepository,
                    characterIdProvider = { -1 },
                )
            )

            // ── Phase 30 · 日程管理补全（delete / update / get / list） ──────
            AgentToolRegistry.registerAll(
                ScheduleDeleteTool(
                    scheduleRepository = scheduleRepository,
                ),
                ScheduleUpdateTool(
                    scheduleRepository = scheduleRepository,
                ),
                ScheduleGetTool(
                    scheduleRepository = scheduleRepository,
                ),
                ScheduleListTool(
                    scheduleRepository  = scheduleRepository,
                    characterIdProvider = { -1 },
                ),
            )

            // ── Phase 30 · 心跳检查清单（set / update / delete） ────────────
            AgentToolRegistry.registerAll(
                HeartbeatSetTool(
                    context             = this@ZaijianApp,
                    characterIdProvider = { -1 },
                ),
                HeartbeatUpdateTool(
                    context             = this@ZaijianApp,
                    characterIdProvider = { -1 },
                ),
                HeartbeatDeleteTool(
                    context             = this@ZaijianApp,
                    characterIdProvider = { -1 },
                ),
            )

            // 调度补偿：云端同步 + 本地补偿（IO 操作，本就应在后台）
            scheduleRepository.retryPendingCloudSync()
            for (charId in 1..9) {
                scheduleRepository.syncCloudResults(charId)
            }
            scheduleRepository.runLocalCompensation()
        } // end appScope.launch(Dispatchers.Default) — 工具注册块
        val presenceEngine = PresenceEngine(
            goalDao  = db.characterGoalDao(),
            eventDao = db.worldEventDao(),
        )
        // Phase 30 方案二：对外公开实例，供 PresenceViewModel 订阅 taskCompletionFlow
        sharedPresenceEngine = presenceEngine
        // 主动消息开关需要读取 SharedPreferences，传入 context
        PresenceEngine.init(this)

        // 窗口2A：P1-13-18 修复 — 竞争引擎装配改为"订阅 Provider 就绪"模式。
        // 原方案：onCreate 一次性 launch，Provider 未配置时跳过后永不重试。
        // 新方案：先尝试一次，再注册 onProviderConfigChanged 回调，
        //         用户在 ProfileScreen 配置/切换 Key 后自动触发重新装配（幂等）。
        // P-13 修复：改为 suspend，用 companion object 的 buildMutex 保护装配段，
        // 防止两个并发调用同时通过 null 检查各自创建引擎实例导致后者覆盖前者。
        suspend fun tryBuildCompetitionEngine() {
            buildMutex.withLock {
                if (sharedCompetitionEngine != null) return@withLock  // 已装配，幂等
                val activeProvider = ProviderManager.instance.activeProvider ?: return@withLock
                val specialtyEvolutionEngine = SpecialtyEvolutionEngine(activeProvider)
                val competitionEngine = CompetitionEngine(
                    provider        = activeProvider,
                    evolutionEngine = specialtyEvolutionEngine,
                )
                val competitionRoundManager = CompetitionRoundManager(
                    db = db,
                    competitionEngine = competitionEngine,
                    daughterRepo = DaughterCharacterRepository(db.daughterCharacterDao()),
                    memoryRepo = MemoryRepository(
                        memoryDao    = db.memoryDao(),
                        candidateDao = db.memoryCandidateDao(),
                    ),
                    specialtyProfileRepository = SpecialtyProfileRepository(
                        db                       = db,
                        specialtyProfileDao      = db.specialtyProfileDao(),
                        evolutionPlanDao         = db.evolutionPlanDao(),
                        practiceRecordDao        = db.practiceRecordDao(),
                        practiceRecordArchiveDao = db.practiceRecordArchiveDao(),
                        stageDigestDao           = db.stageDigestDao(),
                        systemSuggestionDao      = db.systemSuggestionDao(),
                    ),
                    provider = activeProvider,
                )
                sharedCompetitionEngine = competitionEngine
                sharedCompetitionRoundManager = competitionRoundManager
                ZLog.d("ZaijianApp", "竞争引擎装配完成")
            }
        }
        // 立即尝试一次（Key 已配置场景）
        appScope.launch { tryBuildCompetitionEngine() }
        // 注册回调：Key 后续写入/切换时自动重试
        ProviderManager.instance.onProviderConfigChanged = {
            appScope?.launch { tryBuildCompetitionEngine() }
        }

        // 5. 启动 World Simulation（Phase 20：注入 projectDao/eventDao/context 支持项目驱动 + 离线补偿）
        worldSimulation = WorldSimulation(
            relationshipDao    = db.relationshipDao(),
            goalDao            = db.characterGoalDao(),
            presenceEngine     = presenceEngine,
            memoryDao          = db.memoryDao(),
            candidateDao       = db.memoryCandidateDao(),
            projectDao         = db.projectDao(),      // Phase 20 新增：Project 驱动行为
            eventDao           = db.worldEventDao(),   // Phase 20 新增：写入 PROJECT_UPDATED 事件
            context            = this,                 // Phase 20 新增：DataStore 离线补偿
            messageDao         = db.messageDao(),      // Phase 4（zaijian）新增：情境感知主动消息
            daughterCharacterDao = db.daughterCharacterDao(), // daughters 覆盖修复
        )
        worldSimulation.start(startupDelayMs = WorldSimulation.STARTUP_DELAY_MS)

        // Fix-14 + A-7: 通过 ActivityLifecycleCallbacks 计数前台 Activity，
        // App 完全退出后台时 stop()，重新回到前台时 start()。
        //
        // A-7 修复：旋转（配置变更）时 onActivityStopped 先于 onActivityStarted 触发，
        // foregroundCount 会经历 1→0→1，导致 stop()/start() 意外触发、三档计时器全部重置。
        // 修复方式：在 Stopped/Started 回调中检查 activity.isChangingConfigurations，
        // 旋转期间跳过计数变更，不引入任何新依赖。
        var foregroundCount = 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                // 旋转重建时跳过：此次 Start 对应配置变更，不是真正的前台恢复
                if (activity.isChangingConfigurations) return
                foregroundCount++
                if (foregroundCount == 1) {
                    worldSimulation.start(startupDelayMs = 0L)
                    ZLog.d("ZaijianApp", "App foregrounded — WorldSimulation started")
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                // 旋转销毁时跳过：此次 Stop 对应配置变更，不是真正的退出前台
                if (activity.isChangingConfigurations) return
                foregroundCount--
                if (foregroundCount == 0) {
                    worldSimulation.stop()
                    ZLog.d("ZaijianApp", "App backgrounded — WorldSimulation stopped")
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })

        observeAndNotifyResults(
            appScope        = appScope,
            jobResultDao    = db.jobResultDao(),
            scheduledJobDao = db.scheduledJobDao(),
            presenceEngine  = presenceEngine,
        )

        // ── Phase 30 方案五：App 启动时异步生成当日 Presence 文案 ──
        appScope.launch {
            generateDailyPresenceTexts(
                goalDao        = db.characterGoalDao(),
                presenceEngine = presenceEngine,
            )
        }

        // ── P6 专长进化系统 · 每日修炼后台定时调度 ───────────────
        // 与上面的主动消息周期任务不同，这里用 OneTimeWorkRequest 自我重新
        // 调度的模式（见 DailyPracticeScheduler 文档），只在存在至少一个
        // isActive=true 的专长档案时才挂上每日链路，避免用户从未使用过
        // 这个功能时也在后台空跑（DailyPracticeWorker 内部会再次检查
        // activeProfiles 是否为空，这里的检查是为了避免连"调度"这个动作
        // 本身都不必要地发生）。
        appScope.launch {
            val hasActiveSpecialty = db.specialtyProfileDao().getAllActiveProfiles().isNotEmpty()
            if (hasActiveSpecialty) {
                DailyPracticeScheduler.scheduleNext(this@ZaijianApp)
            }
        }
    }

    /**
     * Phase 30 方案二：监听 job_results 表中全部角色的未读结果，
     * 每当有新的未读结果写入时，通过 PresenceEngine.notifyTaskCompletion() 发射通知。
     */
    private fun observeAndNotifyResults(
        appScope:        CoroutineScope,
        jobResultDao:    com.zaijian.zhoumuyun.data.db.dao.JobResultDao,
        scheduledJobDao: com.zaijian.zhoumuyun.data.db.dao.ScheduledJobDao,
        presenceEngine:  com.zaijian.zhoumuyun.data.engine.PresenceEngine,
    ) {
        // P1-13-23 修复（性能优化）：
        // 1. 通知开关缓存到内存变量 + SP 监听器实时同步，
        //    消除原先每次 collect 都重新打开 EncryptedSharedPreferences 的 IO 耗时。
        // 2. 用 Set<String> 增量去重替代 List.contains()（O(n²) → O(1)），
        //    避免高频回调时的 O(n) 全量扫描。
        val prefs = applicationContext.getSharedPreferences("user_profile", Context.MODE_PRIVATE)
        // AtomicBoolean 保证 SP 主线程写入对 IO 协程读取立即可见（替代无效的 @Volatile 局部变量）
        val notifyEnabled = AtomicBoolean(prefs.getBoolean("notify_task_done", true))
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == "notify_task_done") {
                notifyEnabled.set(prefs.getBoolean("notify_task_done", true))
            }
        }
        val notifiedIds = mutableSetOf<String>()

        appScope.launch {
            jobResultDao.observeAllUnread()
                .distinctUntilChanged()
                .collect { unreadResults ->
                    if (!notifyEnabled.get()) return@collect
                    for (result in unreadResults) {
                        if (!notifiedIds.add(result.id)) continue  // 已通知过，O(1) 去重
                        val character = DefaultCharacters.find { it.id == result.characterId }
                            ?: continue
                        val jobTitle = try {
                            scheduledJobDao.findById(result.jobId)?.title ?: result.toolName
                        } catch (_: Exception) {
                            result.toolName
                        }
                        presenceEngine.notifyTaskCompletion(
                            result        = result,
                            characterName = character.name,
                            jobTitle      = jobTitle,
                        )
                    }
                }
        }
    }

    /**
     * Phase 30 方案六：注册系统通知渠道（Android 8.0+ 必须在推送前创建）。
     */
    private fun setupNotificationChannels() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                android.app.NotificationChannel(
                    "task_result",
                    "任务完成",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "角色完成定时任务时通知你" },
                android.app.NotificationChannel(
                    "character_message",
                    "角色留言",
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "角色主动发消息时通知你" },
                // D-2 fix: 统一在 onCreate() 注册所有渠道，避免各 Worker/Receiver 各自动态创建
                android.app.NotificationChannel(
                    com.zaijian.zhoumuyun.data.agent.ScheduledJobWorker.CHANNEL_ID,
                    com.zaijian.zhoumuyun.data.agent.ScheduledJobWorker.CHANNEL_NAME,
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "定时任务执行结果通知" },
                android.app.NotificationChannel(
                    com.zaijian.zhoumuyun.data.agent.ReminderReceiver.CHANNEL_ID,
                    "角色提醒",
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "来自角色设置的提醒通知" },
            )
        )
    }

    /**
     * Phase 30 方案五：App 启动时为所有已解锁角色异步生成当日 Presence 文案。
     */
    private suspend fun generateDailyPresenceTexts(
        goalDao:        com.zaijian.zhoumuyun.data.db.dao.CharacterGoalDao,
        presenceEngine: com.zaijian.zhoumuyun.data.engine.PresenceEngine,
    ) {
        val provider = try {
            ProviderManager.instance.activeProvider
        } catch (e: Exception) {
            ZLog.w("ZaijianApp", "Provider not ready, skip daily note gen", e)
            return
        } ?: run {
            ZLog.w("ZaijianApp", "Provider is null, skip daily note gen")
            return
        }

        DefaultCharacters.filter { it.isUnlocked }.forEach { character ->
            if (presenceEngine.isDailyNoteGenerated(character.id)) return@forEach

            val topGoal = try { goalDao.getTopGoal(character.id) } catch (_: Exception) { null }

            presenceEngine.generateDailyNoteText(
                characterId   = character.id,
                characterName = character.name,
                persona       = character.identityConfig.persona,
                speechStyle   = character.identityConfig.speechStyle,
                goalTitle     = topGoal?.title,
                provider      = provider,
            )
        }
    }
}
