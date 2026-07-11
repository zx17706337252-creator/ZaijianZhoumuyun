package com.zaijian.zhoumuyun

// ⚠️ 重命名记录: Phase22Tools→AgentCoreTools, Phase25Tools→(merged), Phase28Part*→Creative/DataVis/AgentMetaTools

import android.app.Application
import com.zaijian.zhoumuyun.util.ZLog
import android.content.Context
import com.zaijian.zhoumuyun.data.agent.AgentToolRegistry
import com.zaijian.zhoumuyun.data.agent.CalendarSyncHelper
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
import com.zaijian.zhoumuyun.data.datastore.GithubConfig
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.domain.WorldSimulation
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.AgentPlanRepository
import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import com.zaijian.zhoumuyun.data.agent.CompetitionRoundManager
import com.zaijian.zhoumuyun.data.agent.DailyPracticeScheduler
import com.zaijian.zhoumuyun.data.agent.ProjectDailyPlannerTool
import com.zaijian.zhoumuyun.domain.CompetitionEngine
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionEngine
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
        @Volatile var sharedPresenceEngine: com.zaijian.zhoumuyun.domain.PresenceEngine? = null
            private set

        /**
         * P1-13-21 修复：将 appScope 公开为 companion object 成员，
         * 供 ZaijianMessagingService 等非 Activity/Fragment 组件复用，
         * 取代各处非结构化的 CoroutineScope(Dispatchers.IO).launch。
         * Application 销毁前不会被 cancel，生命周期与进程绑定
         * （P1-2-22：onTerminate() 中会尝试 cancel，见下方 clearAppScope()）。
         */
        @Volatile var appScope: kotlinx.coroutines.CoroutineScope? = null
            private set

        /**
         * P1-2-22 修复：供 onTerminate() 调用的收尾函数。appScope 的 setter
         * 是 companion object 私有的，实例方法无法直接写 appScope = null，
         * 因此提供这个 companion 内部函数完成 cancel + 置空。
         */
        fun clearAppScope() {
            appScope?.cancel()
            appScope = null
        }

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

        // P1-2-13 修复：原 tryInitCompetitionEngines(context) 已删除。
        // 核实确认：该方法在全项目范围内零调用点，是完全未被触发的死代码
        // （其注释所说"用户配置 Key 后调用此函数"从未被任何地方实际调用）。
        // 真正生效的装配逻辑是 onCreate() 内的 tryBuildCompetitionEngine()：
        // 通过 buildMutex 保护 + ProviderManager 配置变更监听器机制，实现了
        // 同样的"用户配置 Key 后自动装配"效果。删除死代码而非强行合并两个
        // 语义不完全相同的方法（一个 sync 无锁、一个 suspend 有锁），
        // 风险更低，且同样达成了减少重复维护成本的目标。
    }

    override fun onCreate() {
        super.onCreate()

        // 0. Fix-14: 外观设置共享单例提前初始化
        sharedAppearanceDataStore = AppearanceDataStore(this)

        // 0a. Phase 30 方案六：注册通知渠道（Android 8+ 必须）
        setupNotificationChannels()

        // 1. 初始化 Room 数据库（v4）
        //
        // P1-11 修复：原代码此处无任何保护。若 Room migration 失败
        // （AppDatabase.getInstance 已追加 fallbackToDestructiveMigration()
        // 兜底大多数场景，但磁盘满/权限等更底层 IO 错误仍可能抛出），
        // 会直接崩溃整个 App 且无日志。
        // 这里加的 try-catch 不是"吞掉异常让 App 假装正常运行"——数据库拿不到，
        // 后面几乎所有代码都依赖 db，继续跑只会在更随机的位置崩溃、更难诊断。
        // 所以捕获后记录日志，再 throw 原始异常，确定性地在此处崩溃并留下
        // 清晰的失败原因。真正的修复是数据库层的 fallbackToDestructiveMigration()，
        // 这里只是最后一道日志防线。
        val db = try {
            AppDatabase.getInstance(this)
        } catch (e: Exception) {
            ZLog.e("ZaijianApp", "数据库初始化失败，App 无法启动", e)
            throw e
        }

        // 1a. AppContainer 紧跟 db 之后同步初始化，与下方 presenceEngine 构建
        // 属于同一批"主线程、无 IO、纯内存构造"操作（Phase 3 修复手册）
        com.zaijian.zhoumuyun.data.AppContainer.init(this)

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
        //
        // P1-12 修复：原协程体无任何 try-catch，任一工具构造失败（比如某个 Tool
        // 构造函数访问了未初始化状态）都会让协程因未捕获异常直接终止——由于是普通
        // launch（非 SupervisorJob 特殊处理，单个子协程异常不会被父 scope 吞掉），
        // 且没有任何 ZLog 记录失败原因，连"注册到哪一步失败"都无法诊断。
        // 现在整体包 try-catch + ZLog，单个工具失败不会导致后续工具全部未注册，
        // 且失败原因可追踪。
        //
        // P1-2-21 修复：原调度补偿（云同步/本地补偿）与工具注册共用同一个协程尾部
        // 顺序执行——报告曾描述为"补偿阻塞工具注册"，经核实方向相反：实际是
        // 工具注册阻塞了补偿（补偿在协程尾部，需等前面全部工具注册完才开始），
        // 且若工具注册阶段抛异常，补偿操作根本执行不到。现拆分为两个独立的
        // appScope.launch，互不阻塞、互不连累。
        scope.launch(Dispatchers.Default) {
            try {
                registerAgentTools(db, this@ZaijianApp)
            } catch (e: Exception) {
                ZLog.e("ZaijianApp", "Agent 工具注册过程中发生异常，部分工具可能未注册成功", e)
            }
        }
        scope.launch(Dispatchers.Default) {
            try {
                val compensationScheduleRepository = ScheduleRepository(
                    scheduledJobDao = db.scheduledJobDao(),
                    jobResultDao    = db.jobResultDao(),
                )
                compensationScheduleRepository.retryPendingCloudSync()
                for (charId in 1..9) {
                    compensationScheduleRepository.syncCloudResults(charId)
                }
                compensationScheduleRepository.runLocalCompensation()
            } catch (e: Exception) {
                ZLog.e("ZaijianApp", "调度补偿（云同步/本地补偿）执行异常", e)
            }
        }
        // 报告第5条：PresenceEngine 收敛。原先在此处单独 new 一份 PresenceEngine
        // 再赋给 sharedPresenceEngine；现在 AppContainer.init(this) 内部已经
        // 自包含构造了同一个实例（见 AppContainer.presenceEngine 注释），这里
        // 改为直接引用该实例，避免"同一个 PresenceEngine 被构造两份"——虽然
        // presenceCache 等状态是 companion object 级别、跨实例共享，构造两份
        // 本身不会导致数据不一致，但会让人误以为存在两条独立的 PresenceEngine
        // 生命周期，徒增理解成本。PresenceEngine.init(this) 的调用也已随
        // AppContainer.init() 一并完成，此处不再重复调用。
        val presenceEngine = com.zaijian.zhoumuyun.data.AppContainer.instance.presenceEngine
        // Phase 30 方案二：对外公开实例，供 PresenceViewModel 订阅 taskCompletionFlow
        sharedPresenceEngine = presenceEngine

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
        scope.launch { tryBuildCompetitionEngine() }
        // 注册回调：Key 后续写入/切换时自动重试
        // Phase 3（3.3）改为多订阅者列表：addOnProviderConfigChangedListener 是追加
        // 而非覆盖，即使未来其他模块也注册监听，也不会互相顶掉。
        ProviderManager.instance.addOnProviderConfigChangedListener {
            scope.launch { tryBuildCompetitionEngine() }
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
            messageDao         = MessageRepository(db.messageDao()),      // Phase 4（zaijian）新增：情境感知主动消息
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
            appScope        = scope,
            jobResultDao    = db.jobResultDao(),
            scheduledJobDao = db.scheduledJobDao(),
            presenceEngine  = presenceEngine,
        )

        // ── Phase 30 方案五：App 启动时异步生成当日 Presence 文案 ──
        scope.launch {
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
        scope.launch {
            val hasActiveSpecialty = db.specialtyProfileDao().getAllActiveProfiles().isNotEmpty()
            if (hasActiveSpecialty) {
                DailyPracticeScheduler.scheduleNext(this@ZaijianApp)
            }
        }
    }

    /**
     * P1-2-22 修复：appScope 原先没有对应的 cancel 时机。
     * 需要说明：Android 文档明确指出 Application.onTerminate() 只在模拟器环境
     * 会被调用，真机上进程被杀死时系统不会调用它，所以这里加上的实际效果
     * 在真机场景非常有限（进程死亡时 CoroutineScope 本就会随进程一起消失，
     * 不 cancel 也不会真的泄漏）。作为最佳实践姿态加上，成本很低。
     */
    override fun onTerminate() {
        super.onTerminate()
        clearAppScope()
    }

    /**
     * Phase 30 方案二：监听 job_results 表中全部角色的未读结果，
     * 每当有新的未读结果写入时，通过 PresenceEngine.notifyTaskCompletion() 发射通知。
     */
    private fun observeAndNotifyResults(
        appScope:        CoroutineScope,
        jobResultDao:    com.zaijian.zhoumuyun.data.db.dao.JobResultDao,
        scheduledJobDao: com.zaijian.zhoumuyun.data.db.dao.ScheduledJobDao,
        presenceEngine:  com.zaijian.zhoumuyun.domain.PresenceEngine,
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
                // Phase 4（4.1）修复：补齐 WorkflowJobWorker 渠道遗漏。
                // 复用其 CHANNEL_ID/CHANNEL_NAME 常量（与上面 ScheduledJobWorker 的
                // 引用方式保持一致），而非在此处重新硬编码渠道名字符串——避免未来
                // WorkflowJobWorker.CHANNEL_NAME 改名时这里忘记同步，造成两处渠道名不一致。
                android.app.NotificationChannel(
                    com.zaijian.zhoumuyun.data.agent.WorkflowJobWorker.CHANNEL_ID,
                    com.zaijian.zhoumuyun.data.agent.WorkflowJobWorker.CHANNEL_NAME,
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "自动化工作流执行结果通知" },
            )
        )
    }

    /**
     * Phase 30 方案五：App 启动时为所有已解锁角色异步生成当日 Presence 文案。
     */
    private suspend fun generateDailyPresenceTexts(
        goalDao:        com.zaijian.zhoumuyun.data.db.dao.CharacterGoalDao,
        presenceEngine: com.zaijian.zhoumuyun.domain.PresenceEngine,
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

    /**
     * P1-12 修复：从 onCreate() 内联的 appScope.launch(Dispatchers.Default) { ... }
     * 中抽取出来的独立函数，便于外层统一包裹 try-catch + ZLog，
     * 单个工具构造失败不再导致整个协程未捕获崩溃、后续工具全部未注册。
     * context 由调用方传入（原代码用 this@ZaijianApp 隐式捕获 Application 实例）。
     */
    private suspend fun registerAgentTools(
        db:      AppDatabase,
        context: Context,
    ) {
        // 顺序：本地无网络工具 → 网络工具 → 个人助手 → 创作 → CoreTools → CreativeDoc/DataVis/AgentMeta
        AgentToolRegistry.registerDataTools()
        AgentToolRegistry.registerBuiltinTools(context)
        AgentToolRegistry.registerPersonalTools(context)
        AgentToolRegistry.registerCreativeTools()
        AgentToolRegistry.registerFileSystemTools(context)

        // ── AgentCoreTools ─────────────────────────────────────────────────
        //
        // 注意：MemoryWriteTool 依赖 MemoryRepository（保证 FTS 同步写入），
        //       不可直接传 memoryDao；其余工具直接依赖 Dao 即可。
        //       characterIdProvider 以 -1 静态注册，由 ChatViewModel.init(characterId)
        //       动态覆盖注册（与 CreativeDocTools 等模块一致）。
        val memoryRepository = MemoryRepository(
            memoryDao    = db.memoryDao(),
            candidateDao = db.memoryCandidateDao(),
        )
        // 第8条修复：PlanSaveTool/Soul系6工具原先直接传db.agentPlanDao()/
        // db.characterIdentityDao()裸DAO，构造函数签名已收敛为Repository类型，
        // 这里跟memoryRepository同一模式，只包一次、复用多次，不用每处重新裸取。
        val agentPlanRepository = AgentPlanRepository(db.agentPlanDao())
        val identityRepository = IdentityRepository(db.characterIdentityDao())
        AgentToolRegistry.registerAll(
            PlanSaveTool(
                agentPlanDao = agentPlanRepository,
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
                identityDao = identityRepository,
                characterId = { -1 },
            ),
            SoulClearTool(
                identityDao = identityRepository,
                characterId = { -1 },
            ),
            NarrativeMemoryUpdateTool(
                identityDao = identityRepository,
                characterId = { -1 },
            ),
            NarrativeMemoryClearTool(
                identityDao = identityRepository,
                characterId = { -1 },
            ),
            UserImpressionUpdateTool(
                identityDao = identityRepository,
                characterId = { -1 },
            ),
            UserImpressionClearTool(
                identityDao = identityRepository,
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
        AgentToolRegistry.registerCreativeDocTools(context)
        AgentToolRegistry.registerDataVisTools(
            context    = context,
            memoryDao  = db.memoryDao(),
            // 复审修复：SelfReflectTool 的 Step3 写入需要走 MemoryRepository.save()
            // 才能同步 FTS，否则自我反思记忆永久无法被全文检索召回。
            // 复用本函数（registerAgentTools）开头已创建的 memoryRepository，
            // 不再新建实例。
            memoryRepo = memoryRepository,
        )
        AgentToolRegistry.registerAgentMetaTools(
            context    = context,
            memoryDao  = db.memoryDao(),
            sessionDao = db.evaluationSessionDao(),
            goalDao    = db.learningGoalDao(),
            messageDao = MessageRepository(db.messageDao()),
            taskDao    = db.taskDao(),
        )

        // ── CICD · GitHub 配置存储 ──────────────────────────────
        val githubConfigStore = GithubConfigDataStore(context)
        // 批次B（1.8）清理：旧版本使用明文 preferencesDataStore("github_config")，
        // 已整体迁移到 EncryptedSharedPreferences。先尝试读出旧文件中的
        // owner/repo/token 写入新的加密存储（仅当新存储尚无真实 token 时），
        // 再删除旧文件残留。全程 try-catch 包裹，任何一步失败都不影响启动。
        migrateLegacyGithubConfig(context, githubConfigStore)

        // ── 邮件账号存储 + 真实邮件收发工具（email_send / email_fetch）──
        val emailAccountStore = EmailAccountStore(context)
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
            BuildApkDownloadTool(context = context, githubConfigStore = githubConfigStore),
        )

        // ── Phase 29 · 调度系统初始化 ─────────────────────────────
        val scheduleRepository = ScheduleRepository(
            scheduledJobDao = db.scheduledJobDao(),
            jobResultDao    = db.jobResultDao(),
        )

        // 修复手册 Phase 1.1：此前注册 Schedule 系列工具时遗漏了 CalendarSyncHelper 和
        // context 注入，导致 WorkManager 精确调度、系统日历同步、旧 WorkRequest 取消
        // 三条链路全部静默失效（三个工具构造函数的 calendarSync/context 参数默认 null）。
        // 现统一创建一个 CalendarSyncHelper 实例，注入给下面三个工具。
        val calendarSync = CalendarSyncHelper(context)

        // 注册 schedule_create 工具（characterId 由 ChatViewModel 动态覆盖）
        AgentToolRegistry.register(
            ScheduleCreateTool(
                scheduleRepository  = scheduleRepository,
                characterIdProvider = { -1 },
                calendarSync = calendarSync,
                context = context,
            )
        )

        // ── Phase 30 · 日程管理补全（delete / update / get / list） ──────
        AgentToolRegistry.registerAll(
            ScheduleDeleteTool(
                scheduleRepository = scheduleRepository,
                calendarSync = calendarSync,
                context = context,
            ),
            ScheduleUpdateTool(
                scheduleRepository = scheduleRepository,
                calendarSync = calendarSync,
                context = context,
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
                context             = context,
                characterIdProvider = { -1 },
            ),
            HeartbeatUpdateTool(
                context             = context,
                characterIdProvider = { -1 },
            ),
            HeartbeatDeleteTool(
                context             = context,
                characterIdProvider = { -1 },
            ),
        )
    }

    /**
     * 批次B（1.8）清理：旧版本使用明文 preferencesDataStore("github_config") 存储
     * owner/repo/token。迁移到加密存储前，先尝试读出旧文件中的字段并写入新存储
     * （仅当新存储当前无真实 token 时才写入，避免覆盖用户已手动配置的新值），
     * 再删除旧文件残留。旧文件不存在、读取失败、写入失败均不影响后续删除，
     * 全程 try-catch 包裹，任何一步异常都不会影响 App 启动。
     */
    private suspend fun migrateLegacyGithubConfig(
        context: Context,
        newStore: GithubConfigDataStore,
    ) = withContext(Dispatchers.IO) {
        val legacyFile = java.io.File(context.filesDir, "datastore/github_config.preferences_pb")
        if (legacyFile.exists()) {
            runCatching {
                // 新存储已有真实 token 时跳过读取，避免不必要的旧文件 IO 和覆盖风险。
                if (!newStore.getConfig().isConfigured) {
                    val legacyDataStore = PreferenceDataStoreFactory.create(
                        produceFile = { legacyFile }
                    )
                    val keyOwner = stringPreferencesKey("owner")
                    val keyRepo  = stringPreferencesKey("repo")
                    val keyToken = stringPreferencesKey("token")

                    val legacyPrefs = legacyDataStore.data.first()
                    val legacyOwner = legacyPrefs[keyOwner].orEmpty()
                    val legacyRepo  = legacyPrefs[keyRepo].orEmpty()
                    val legacyToken = legacyPrefs[keyToken].orEmpty()

                    if (legacyToken.isNotBlank() || legacyOwner.isNotBlank() || legacyRepo.isNotBlank()) {
                        newStore.saveConfig(
                            GithubConfig(
                                owner = legacyOwner,
                                repo  = legacyRepo,
                                token = legacyToken,
                            )
                        )
                        ZLog.d("ZaijianApp", "旧版 GitHub 配置已迁移到加密存储")
                    }
                }
            }.onFailure {
                ZLog.e("ZaijianApp", "旧版 GitHub 配置迁移失败，跳过（不影响启动）", it)
            }
        }

        // 无论迁移是否成功，旧明文文件都应删除（若不存在则 delete() 直接返回 false，
        // 无副作用，可安全重复调用）。
        runCatching { legacyFile.delete() }
        Unit
    }
}
