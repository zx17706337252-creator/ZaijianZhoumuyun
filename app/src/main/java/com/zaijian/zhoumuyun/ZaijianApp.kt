package com.zaijian.zhoumuyun

// ⚠️ 重命名记录: Phase22Tools→AgentCoreTools, Phase25Tools→(merged), Phase28Part*→Creative/DataVis/AgentMetaTools

import android.app.Application
import com.zaijian.zhoumuyun.util.AgentLog
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
import com.zaijian.zhoumuyun.data.agent.SkillCreateTool
import com.zaijian.zhoumuyun.data.agent.SkillDeprecateTool
import com.zaijian.zhoumuyun.data.agent.SkillEditTool
import com.zaijian.zhoumuyun.data.agent.SkillExpandTool
import com.zaijian.zhoumuyun.data.agent.SkillFeedbackTool
import com.zaijian.zhoumuyun.data.agent.PlanSaveTool
import com.zaijian.zhoumuyun.data.agent.AgentTool
import com.zaijian.zhoumuyun.data.agent.RuleDistillTool
import com.zaijian.zhoumuyun.data.agent.ScheduleCreateTool
import com.zaijian.zhoumuyun.data.agent.ScheduleDeleteTool
import com.zaijian.zhoumuyun.data.agent.ScheduleGetTool
import com.zaijian.zhoumuyun.data.agent.ScheduleListTool
import com.zaijian.zhoumuyun.data.agent.ScheduleUpdateTool
import com.zaijian.zhoumuyun.data.agent.BuildApkDownloadTool
import com.zaijian.zhoumuyun.data.agent.BuildApkTool
import com.zaijian.zhoumuyun.data.agent.BuildStatusCheckTool
import com.zaijian.zhoumuyun.data.agent.CiCdStartTool
import com.zaijian.zhoumuyun.data.agent.WorkflowStartTool
import com.zaijian.zhoumuyun.data.agent.CreateGithubRepoTool
import com.zaijian.zhoumuyun.data.agent.GitCommitPushTool
import com.zaijian.zhoumuyun.data.agent.registerBuiltinTools
import com.zaijian.zhoumuyun.data.agent.registerCreativeTools
import com.zaijian.zhoumuyun.data.agent.registerFileSystemTools
import com.zaijian.zhoumuyun.data.agent.registerDataTools
import com.zaijian.zhoumuyun.data.agent.registerPersonalTools
import com.zaijian.zhoumuyun.data.agent.registerCreativeDocTools
import com.zaijian.zhoumuyun.data.agent.registerDataVisTools
import com.zaijian.zhoumuyun.data.agent.registerAgentMetaTools
import com.zaijian.zhoumuyun.data.agent.registerEmailTools
import com.zaijian.zhoumuyun.data.agent.registerSoulMemoryUserTools
import com.zaijian.zhoumuyun.data.agent.TaskStartTool
import com.zaijian.zhoumuyun.data.agent.TaskUpdateTool
import com.zaijian.zhoumuyun.data.agent.TaskCompleteTool
import com.zaijian.zhoumuyun.data.agent.TaskCancelTool
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
import com.zaijian.zhoumuyun.data.repository.ProjectRepository
import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import com.zaijian.zhoumuyun.data.repository.TaskRepository
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
// 批次5 5-1②补充修复：observeAndNotifyResults 的 while(isActive) 自愈重试循环需要
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import com.zaijian.zhoumuyun.data.agent.DailyPracticeScheduler
import com.zaijian.zhoumuyun.data.agent.ProjectDailyPlannerTool
import com.zaijian.zhoumuyun.data.agent.migrateExportsToVault
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository

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

        // 批次4 4-5修复：SharedPreferences 监听器强引用持有。
        // Android 的 SharedPreferencesImpl 用 WeakHashMap 存监听器（弱引用 key），
        // 匿名 lambda 只捕获局部变量不捕获 this，没有强引用持有，App 运行一段时间后
        // 可能被 GC 回收。回收后用户修改"任务完成通知"开关不再实时生效，直到重启 App。
        // 保存为 companion object 字段确保强引用持有，防止 GC 回收。
        @Volatile
        private var notifyTaskDoneListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

        // 批次5 5-1②补充修复：observeAndNotifyResults 长驻收集器异常重试的退避延迟。
        // 见该函数内 while(isActive) 重试循环的说明。
        private const val RETRY_DELAY_MS = 5_000L

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

        // 阶段2 S-2：sharedCompetitionEngine/sharedCompetitionRoundManager/
        // buildMutex/tryBuildCompetitionEngine() 已整体搬迁至 AppContainer
        // （见 AppContainer.competitionEngine / competitionRoundManager /
        // reassembleCompetitionEngine()）。原因：这两个字段是 @Volatile var、
        // 依赖 Provider 就绪后动态装配，与 AppContainer 其余"onCreate 内一次性
        // 构造、永久不变"的 val 字段性质不同，此前作为例外单独游离在
        // ZaijianApp companion object 里，现收敛为容器统一持有的"可变但受控"
        // 扩展点。CompetitionViewModel 改为访问
        // AppContainer.instance.competitionRoundManager。
        //
        // P1-2-13 修复：原 tryInitCompetitionEngines(context) 已删除。
        // 核实确认：该方法在全项目范围内零调用点，是完全未被触发的死代码
        // （其注释所说"用户配置 Key 后调用此函数"从未被任何地方实际调用）。
        // 真正生效的装配逻辑是 onCreate() 内调用的
        // AppContainer.instance.reassembleCompetitionEngine()：通过内部
        // Mutex 保护 + ProviderManager 配置变更监听器机制，实现了同样的
        // "用户配置 Key 后自动装配"效果。删除死代码而非强行合并两个
        // 语义不完全相同的方法（一个 sync 无锁、一个 suspend 有锁），
        // 风险更低，且同样达成了减少重复维护成本的目标。
    }

    override fun onCreate() {
        super.onCreate()

        // AgentLog 初始化：注入 appContext，否则日志静默丢弃
        com.zaijian.zhoumuyun.util.AgentLog.init(this)

        // S1问题3修复：全局未捕获异常处理器，将崩溃堆栈写入文件
        // 供开发者诊断，ZLog 仅写 logcat 在进程崩溃时不可追溯
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // 崩溃也写一份到 AgentLog
                kotlinx.coroutines.runBlocking {
                    com.zaijian.zhoumuyun.util.AgentLog.error(
                        "CrashHandler", "未捕获异常（线程 ${thread.name}）", throwable,
                    )
                }
                val crashDir = java.io.File(filesDir, "crashes")
                crashDir.mkdirs()
                val crashFile = java.io.File(crashDir, "crash_${System.currentTimeMillis()}.txt")
                crashFile.writeText(
                    buildString {
                        appendLine("Thread: ${thread.name}")
                        appendLine("Time: ${com.zaijian.zhoumuyun.util.TimeFormatUtils.formatDateTime(System.currentTimeMillis())}")
                        appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                        appendLine()
                        appendLine("Stack trace:")
                        throwable.printStackTrace(java.io.PrintWriter(java.io.StringWriter().also { sw ->
                            appendLine(sw.toString())
                        }))
                    }
                )
            } catch (_: Throwable) { /* 写文件失败不阻塞进程退出 */ }
            defaultHandler?.uncaughtException(thread, throwable)
            android.os.Process.killProcess(android.os.Process.myPid())
        }

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
        } catch (e: Throwable) {
            ZLog.e("ZaijianApp", "数据库初始化失败，App 无法启动", e)
            throw e
        }

        // 1a. AppContainer 紧跟 db 之后同步初始化，与下方 presenceEngine 构建
        // 属于同一批"主线程、无 IO、纯内存构造"操作（Phase 3 修复手册）
        com.zaijian.zhoumuyun.data.AppContainer.init(this)

        // v147（文件保险库改造）：一次性把旧 filesDir/exports/ 下的文件迁到
        // vault/shared/project/。同步执行（早于下方 scope.launch 的工具注册/写入），
        // 保证老数据不丢。用 runCatching 兜底，迁移失败绝不阻断 App 启动。
        // 幂等：vault/.migrated 标记文件存在则直接跳过。
        runCatching { migrateExportsToVault(this) }
            .onFailure { ZLog.w("ZaijianApp", "exports→vault 迁移失败（不阻断启动）", it) }

        // 2. 初始化 API Provider 管理器（延迟初始化 + 后台预加载）
        ProviderManager.init(this)
        ProviderManager.instance.preloadAsync()

        // P2-34 修复：预加载自定义启动页背景图到 Coil 内存缓存。
        // SplashScreen 首帧从 configFlow 拿到配置后用 rememberAsyncImagePainter
        // 异步加载图片——在此之前品牌 Logo 已经渲染，图片加载完毕后切换造成闪烁。
        // 此处在 App 启动时后台预取图片到内存缓存，SplashScreen 渲染时
        // memoryCachePolicy(ENABLED) 直接命中缓存，消除首帧闪烁。
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = com.zaijian.zhoumuyun.data.AppContainer.instance
                    .splashBackgroundDataStore.configFlow.first()
                if (config != null) {
                    val imageLoader = coil.Coil.imageLoader(this@ZaijianApp)
                    imageLoader.execute(
                        coil.request.ImageRequest.Builder(this@ZaijianApp)
                            .data(config.uri)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .build()
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("ZaijianApp", "预加载启动页背景图失败（不阻断启动）", e)
            }
        }

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
        // 现在各模块独立 try-catch 隔离，
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("ZaijianApp", "Agent 工具注册过程中发生异常，部分工具可能未注册成功", e)
            }
        }
        scope.launch(Dispatchers.Default) {
            try {
                val compensationScheduleRepository = ScheduleRepository(
                    scheduledJobDao = db.scheduledJobDao(),
                    jobResultDao    = db.jobResultDao(),
                    db              = db,
                    context         = applicationContext,  // 批次1 1-5修复：补 context，让 runLocalCompensation 的 finally 块重新入队逻辑生效
                )
                compensationScheduleRepository.retryPendingCloudSync()
                for (charId in 1..9) {
                    compensationScheduleRepository.syncCloudResults(charId)
                }
                compensationScheduleRepository.runLocalCompensation()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("ZaijianApp", "调度补偿（云同步/本地补偿）执行异常", e)
            }
        }

        // 批次1 1-8修复：恢复 Reminder 闹钟。
        // restoreReminderAlarms 原本只在 BootReceiver 调用一次，onCreate 完全没有恢复逻辑。
        // 注释宣称"用户下次打开 App 时 ZaijianApp.onCreate 会重新走一遍同样的逻辑"是假的。
        // 两种场景下 Reminder 闹钟永久丢失：①NTP 未同步导致 BootReceiver 命中
        // MIN_PLAUSIBLE_TIME_MS 早返回；②BootReceiver 外层 catch 吞掉 restoreReminderAlarms
        // 自身抛出的异常（JSON 解析失败等）。此处补一次调用作为兜底，与 DailyPractice、
        // ScheduledJobWorker 等其他调度项的 BootReceiver+onCreate 双处恢复模式一致。
        scope.launch(Dispatchers.Default) {
            try {
                com.zaijian.zhoumuyun.data.agent.BootReceiver.restoreReminderAlarms(this@ZaijianApp)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("ZaijianApp", "Reminder 闹钟恢复执行异常", e)
            }
        }

        // 离线简报 复核发现的既有缺口修复：MenstrualCycleRepository.initIfAbsent()
        // 此前全项目零调用点，导致所有角色周期锚点为 null、"排卵期"提示不会真实
        // 触发（安全兜底成 SAFE，不会崩，但功能未接入）。设计文档明确写的调用时机
        // 就是"App 启动时在 IO 协程中调用一次"，这里补上。
        // 与其它后台初始化同一模式：独立 launch + try-catch + ZLog，
        // 失败不影响冷启动、不连累其它子系统。
        scope.launch(Dispatchers.Default) {
            try {
                com.zaijian.zhoumuyun.data.AppContainer.instance.menstrualCycleRepo.initIfAbsent()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("ZaijianApp", "MenstrualCycleRepository.initIfAbsent() 执行异常", e)
            }
        }

        // 批次C·问题5 修复：分娩到期结算调度。
        // PregnancyRepository.settleDueDeliveries() 此前全项目零调用点，怀孕满 30 天
        // 不会自动触发分娩结算，isPregnant 永远卡在 true。
        // 两步：①挂上 12h 周期兜底轮询（PeriodicWorkRequest，幂等，重复 onCreate 不会
        // 重复入队）；②立即做一次检查，避免用户刚好在满 30 天那一刻打开 App 却要等
        // 下一个轮询点。与其它后台初始化同一模式：独立 launch + try-catch + ZLog。
        com.zaijian.zhoumuyun.data.agent.PregnancySettlementScheduler.ensurePeriodicWork(this)
        scope.launch(Dispatchers.Default) {
            val container = com.zaijian.zhoumuyun.data.AppContainer.instance
            com.zaijian.zhoumuyun.data.agent.PregnancySettlementScheduler.runImmediateCheck(
                context       = this@ZaijianApp,
                pregnancyRepo = container.pregnancyRepo,
                memoryRepo    = container.memoryRepo,
                daughterRepo  = container.daughterCharacterRepo,
            )
        }

        // 批次8 8-1修复：高频表定期清理（world_events + messages）
        // 两张表原先只增不删，长期使用后体积持续增长影响 DB 性能。
        // CleanupWorker 每 24h 执行一次双策略裁剪（时间裁剪 + 分组裁剪）。
        // 与 PregnancySettlementScheduler 同一安全模式：KEEP 策略，幂等。
        com.zaijian.zhoumuyun.data.agent.CleanupScheduler.ensurePeriodicWork(this)

        // S8-窗口12 结论2/结论8修复：主动消息周期检查（ProactiveMessageWorker）
        // 此前只在 ProfileScreen 开关切换的回调里被 scheduleProactiveMessageCheck()，
        // ZaijianApp.onCreate() 和 BootReceiver 均无调用。WorkManager 的
        // PeriodicWorkRequest 在设备重启后会被系统清空，若用户此前已开启主动消息
        // 但重启后未手动重新切换开关，主动消息检查会永久停止，且没有任何提示。
        // 现在改为：启动时读取 ProfileScreen 写入的同一个 SharedPreferences
        // （"user_profile" / "proactive_enabled"，默认值 true 与 ProfileScreen
        // 初始化开关状态时的默认值保持一致），已开启则调用
        // scheduleProactiveMessageCheck() 恢复周期任务。enqueueUniquePeriodicWork
        // 用的是 KEEP 策略，若任务已存在则本次调用是no-op，重复 onCreate 不会
        // 重复入队或打断现有周期，与 PregnancySettlementScheduler.ensurePeriodicWork()
        // 同一安全模式。
        runCatching {
            val userPrefs = applicationContext.getSharedPreferences("user_profile", Context.MODE_PRIVATE)
            if (userPrefs.getBoolean("proactive_enabled", true)) {
                com.zaijian.zhoumuyun.data.agent.WorkManagerScheduler
                    .scheduleProactiveMessageCheck(this@ZaijianApp)
            }
        }.onFailure { ZLog.e("ZaijianApp", "scheduleProactiveMessageCheck 启动恢复失败", it) }

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
        // 阶段2 S-2：装配逻辑本体（sharedCompetitionEngine/
        // sharedCompetitionRoundManager/buildMutex 的整套装配流程）已
        // 整体搬迁至 AppContainer.reassembleCompetitionEngine()，此处只负责
        // 触发调用，不再持有任何状态。
        // 窗口02 结论6 修复后，两处调用不再完全同构：首次调用（force=false）
        // 保持原幂等语义，回调触发（force=true）改为无条件重装，详见
        // reassembleCompetitionEngine() 方法文档。
        val appContainer = com.zaijian.zhoumuyun.data.AppContainer.instance
        // 立即尝试一次（Key 已配置场景）。force=false：沿用幂等语义，
        // 若此前已装配过（理论上冷启动阶段不会，但保持显式传参更清晰），不重复构造。
        scope.launch { appContainer.reassembleCompetitionEngine(force = false) }
        // 注册回调：Key 后续写入/切换时自动重试。
        // 结论6 修复：此处必须传 force=true——回调触发意味着 Provider 配置
        // 确实发生了变化（用户在 ProfileScreen 切换/更新了 Key），而
        // reassembleCompetitionEngine() 内部的幂等判断在 competitionEngine
        // 已非 null 时会直接跳过，若不传 force=true，本回调会变成实际上的
        // 空调用——用户切 Key 后竞赛引擎仍在用首次装配时的旧 Provider，
        // 造成"切换Key后竞赛引擎会更新"的错觉。
        // Phase 3（3.3）改为多订阅者列表：addOnProviderConfigChangedListener 是追加
        // 而非覆盖，即使未来其他模块也注册监听，也不会互相顶掉。
        ProviderManager.instance.addOnProviderConfigChangedListener {
            scope.launch { appContainer.reassembleCompetitionEngine(force = true) }
        }

        // 5. 启动 World Simulation（Phase 20：注入 projectDao/eventDao/context 支持项目驱动 + 离线补偿）
        worldSimulation = WorldSimulation(
            relationshipDao    = db.relationshipDao(),
            goalDao            = db.characterGoalDao(),
            presenceEngine     = presenceEngine,
            memoryDao          = db.memoryDao(),
            candidateDao       = db.memoryCandidateDao(),
            memoryTagDao       = db.memoryTagDao(),     // Bugfix：补上 memoryRepo 懒加载依赖的 MemoryTagDao
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
                    // 用户反馈修复：同步维护 App 前台状态，供 PresenceEngine 的
                    // onProactiveMessage 回调判断是否真的该抑制系统通知（不能只看
                    // foregroundChatCharacterId，见 PresenceEngine companion object 注释）。
                    com.zaijian.zhoumuyun.domain.PresenceEngine.isAppInForeground = true
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                // 旋转销毁时跳过：此次 Stop 对应配置变更，不是真正的退出前台
                if (activity.isChangingConfigurations) return
                foregroundCount--
                if (foregroundCount == 0) {
                    worldSimulation.stop()
                    ZLog.d("ZaijianApp", "App backgrounded — WorldSimulation stopped")
                    com.zaijian.zhoumuyun.domain.PresenceEngine.isAppInForeground = false
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
        // 审查报告问题9修复：补传 daughterRepo，让已注册的女儿角色也能收到
        // 每日 Presence 便签（此前仅遍历 DefaultCharacters，女儿 ID>=1000 永远
        // 被排除，与 BriefingRepository 早已支持 mothers+daughters 合并的
        // 现状不一致）。
        scope.launch {
            generateDailyPresenceTexts(
                goalDao        = db.characterGoalDao(),
                presenceEngine = presenceEngine,
                daughterRepo   = com.zaijian.zhoumuyun.data.AppContainer.instance.daughterCharacterRepo,
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
            try {
                val hasActiveSpecialty = db.specialtyProfileDao().getAllActiveProfiles().isNotEmpty()
                if (hasActiveSpecialty) {
                    DailyPracticeScheduler.scheduleNext(this@ZaijianApp)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 批次5 5-1①修复：原代码无 try-catch，scheduleNext 或 DB 查询异常时
                // "每日练习调度未建立"静默失效直到重启，且不走项目统一 ZLog.e 通道。
                // 与 263/270/293 行的 scope.launch 范式一致，补 try-catch + ZLog.e。
                ZLog.e("ZaijianApp", "每日练习调度建立失败，将在下次 App 启动时重试", e)
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
        // 批次4 4-5修复：把监听器保存为 companion object 字段（notifyTaskDoneListener），
        // 确保强引用持有，防止 GC 回收导致开关失效。原匿名 lambda 无强引用，
        // Android SharedPreferencesImpl 的 WeakHashMap 会在 GC 后回收监听器。
        notifyTaskDoneListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "notify_task_done") {
                notifyEnabled.set(prefs.getBoolean("notify_task_done", true))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(notifyTaskDoneListener)
        // 方案 5-11：LinkedHashSet 限制容量为 200，超出时移除最旧记录，
        // 防止长期运行后 notifiedIds 无限增长导致内存泄漏。
        val notifiedIds = java.util.Collections.newSetFromMap<String>(
            object : java.util.LinkedHashMap<String, Boolean>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                    return size > 200
                }
            }
        )

        appScope.launch {
            // 批次5 5-1②补充修复：原方案只在外层包 try-catch + ZLog.e，
            // observeAllUnread() 一旦抛异常（如 SQLiteException），catch 到之后
            // 协程直接结束——避免了 App 崩溃，但"任务完成通知"功能仍然永久失效，
            // 必须等用户重启 App 才能恢复，没有解决报告真正担心的"长驻收集器不会
            // 自动重试"这个核心问题。改为 while(isActive) 重试循环：捕获异常后不
            // 退出协程，而是重新订阅 observeAllUnread()，让通知功能在下一次
            // 重试时自动恢复，不需要重启 App。
            //
            // RETRY_DELAY_MS 退避延迟：若异常是持续性的（如 DB 文件损坏），
            // 没有延迟会让 while 循环在瞬间反复重试、疯狂打日志、空耗 CPU；
            // 5 秒退避足够让瞬时故障（锁竞争、短暂 IO 抖动）自行恢复，
            // 又不会让持续性故障产生过高频率的重试。
            while (isActive) {
                try {
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
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                    result.toolName
                                }
                                presenceEngine.notifyTaskCompletion(
                                    result        = result,
                                    characterName = character.name,
                                    jobTitle      = jobTitle,
                                )
                            }
                        }
                    // Room 的 Flow 正常情况下无限期发射，collect 不会自然返回；
                    // 若确实自然结束（理论上不应发生），退出循环避免忙等重启。
                    break
                } catch (e: CancellationException) {
                    throw e  // 协程正常取消（如 App 进程退出），不重试，直接向上传播
                } catch (e: Throwable) {
                    // 注意：collect 内部的 findById 已有自己的 try-catch（不中断循环），
                    // 这里兜底的是 Flow 上游异常和 collect lambda 内未预期的异常。
                    ZLog.e("ZaijianApp", "任务完成通知长驻收集器异常，${RETRY_DELAY_MS}ms 后自动重新订阅", e)
                    delay(RETRY_DELAY_MS)
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
                // 方案 5-12：分娩结算通知渠道
                android.app.NotificationChannel(
                    com.zaijian.zhoumuyun.data.agent.PregnancySettlementWorker.CHANNEL_ID,
                    com.zaijian.zhoumuyun.data.agent.PregnancySettlementWorker.CHANNEL_NAME,
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "角色生育结算通知" },
                // 修复（第4窗口审查报告问题4）：CiCdPipelineWorker 原自行调用
                // createNotificationChannel() 创建渠道，与其余 Worker 统一在此处
                // 注册的方式不一致，管理分散。现改为与 ScheduledJobWorker/
                // WorkflowJobWorker/PregnancySettlementWorker 同样的模式。
                android.app.NotificationChannel(
                    com.zaijian.zhoumuyun.data.agent.CiCdPipelineWorker.CHANNEL_ID,
                    com.zaijian.zhoumuyun.data.agent.CiCdPipelineWorker.CHANNEL_NAME,
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "编译流水线执行结果" },
                // S3问题7修复：BuildApkDownloadTool 原自行创建 apk_download 渠道，
                // 与其余 Worker 统一在此处注册
                android.app.NotificationChannel(
                    com.zaijian.zhoumuyun.data.agent.BuildApkDownloadTool.CHANNEL_ID,
                    com.zaijian.zhoumuyun.data.agent.BuildApkDownloadTool.CHANNEL_NAME,
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "APK 下载完成通知" },
            )
        )
    }

    /**
     * Phase 30 方案五：App 启动时为所有已解锁角色异步生成当日 Presence 文案。
     */
    private suspend fun generateDailyPresenceTexts(
        goalDao:        com.zaijian.zhoumuyun.data.db.dao.CharacterGoalDao,
        presenceEngine: com.zaijian.zhoumuyun.domain.PresenceEngine,
        daughterRepo:   DaughterCharacterRepository,
    ) {
        val provider = try {
            ProviderManager.instance.activeProvider
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("ZaijianApp", "Provider not ready, skip daily note gen", e)
            return
        } ?: run {
            ZLog.w("ZaijianApp", "Provider is null, skip daily note gen")
            return
        }

        DefaultCharacters.filter { it.isUnlocked }.forEach { character ->
            if (presenceEngine.isDailyNoteGenerated(character.id)) return@forEach

            // 方案 5-4：单个角色 getTopGoal / generateDailyNoteText 失败用独立
            // try-catch 隔离，不阻断其余角色的生成。与 Daughters 段一致。
            try {
                val topGoal = try { goalDao.getTopGoal(character.id) } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) { null }

                presenceEngine.generateDailyNoteText(
                    characterId   = character.id,
                    characterName = character.name,
                    persona       = character.identityConfig.persona,
                    speechStyle   = character.identityConfig.speechStyle,
                    goalTitle     = topGoal?.title,
                    provider      = provider,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("ZaijianApp", "generateDailyNoteText failed for characterId=${character.id}", e)
            }
        }

        // 审查报告问题9修复：DefaultCharacters 只覆盖 ID 1-9，已注册的女儿角色
        // （ID>=1000）此前永远不会走到这里，家族页面看到的女儿便签是空的。
        // 女儿的 name/persona/speechStyle 不在 DefaultCharacters 里，必须逐个
        // 反查 daughterRepo.getCharacterConfig() 才能拿到——不是简单把 ID 追加
        // 进同一个 forEach 就够了。单个女儿反查/生成失败不应影响其余女儿或
        // 已完成的原生角色，用独立 try-catch 逐条隔离。
        val daughterIds = try {
            daughterRepo.getAllDaughterCharacterIds()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("ZaijianApp", "getAllDaughterCharacterIds failed, skip daughter daily note gen", e)
            emptyList()
        }
        daughterIds.forEach daughterLoop@{ daughterId ->
            if (presenceEngine.isDailyNoteGenerated(daughterId)) return@daughterLoop

            val daughterConfig = try {
                daughterRepo.getCharacterConfig(daughterId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("ZaijianApp", "getCharacterConfig failed for daughterId=$daughterId, skip", e)
                null
            } ?: return@daughterLoop

            val topGoal = try { goalDao.getTopGoal(daughterId) } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) { null }

            try {
                presenceEngine.generateDailyNoteText(
                    characterId   = daughterId,
                    characterName = daughterConfig.name,
                    persona       = daughterConfig.identityConfig.persona,
                    speechStyle   = daughterConfig.identityConfig.speechStyle,
                    goalTitle     = topGoal?.title,
                    provider      = provider,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("ZaijianApp", "generateDailyNoteText failed for daughterId=$daughterId", e)
            }
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
        runCatching { AgentToolRegistry.registerDataTools() }.onFailure { ZLog.e("ZaijianApp", "registerDataTools 注册失败", it) }
        runCatching { AgentToolRegistry.registerBuiltinTools(context) }.onFailure { ZLog.e("ZaijianApp", "registerBuiltinTools 注册失败", it) }
        runCatching { AgentToolRegistry.registerPersonalTools(context) }.onFailure { ZLog.e("ZaijianApp", "registerPersonalTools 注册失败", it) }
        runCatching { AgentToolRegistry.registerCreativeTools() }.onFailure { ZLog.e("ZaijianApp", "registerCreativeTools 注册失败", it) }
        runCatching { AgentToolRegistry.registerFileSystemTools(context) }.onFailure { ZLog.e("ZaijianApp", "registerFileSystemTools 注册失败", it) }

        // ── AgentCoreTools ─────────────────────────────────────────────────
        //
        // 注意：MemoryWriteTool 依赖 MemoryRepository（保证 FTS 同步写入），
        //       不可直接传 memoryDao；其余工具直接依赖 Dao 即可。
        //       characterIdProvider 以 -1 静态注册，由 ChatViewModel.init(characterId)
        //       动态覆盖注册（与 CreativeDocTools 等模块一致）。
        // 阶段2 S-1 批次1收口：memoryRepository/identityRepository 原先各自独立
        // new（构造参数与容器完全一致）。AppContainer.init(this) 已在 onCreate
        // 同步阶段跑完（本方法由 scope.launch 异步调用，晚于 init），此处可安全
        // 引用容器共享实例，减少重复构造。
        val appContainer = com.zaijian.zhoumuyun.data.AppContainer.instance
        val memoryRepository = appContainer.memoryRepo
        // S8-窗口01 收口：agentPlanRepository/learningGoalRepository 原先在此
        // 独立 new（构造参数与容器完全一致，AgentPlanRepository 同样的重复
        // 构造还出现在 ChatViewModel/RoundtableViewModel，RoundtableViewModel
        // 已切换，ChatViewModel 本次不改动，留待专门批次处理），此处与
        // memoryRepository/identityRepository 同一模式改引用容器共享实例。
        val agentPlanRepository = appContainer.agentPlanRepo
        val identityRepository = appContainer.identityRepo
        val learningGoalRepository = appContainer.learningGoalRepo
        // 问题40修复：与 ChatViewModel.kt 的 taskRepo 构造完全一致，供下方
        // TaskStartTool 等4个工具的静态占位注册使用。
        val taskRepo = TaskRepository(db, db.taskDao(), db.worldEventDao())
        runCatching {
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
                    memoryRepo  = memoryRepository,
                    characterId = { -1 },
                ),
                // ── Window C 技能系统：5 个工具静态占位注册（{-1}），ChatToolRegistrar
                // .registerCharacterTools() 会按真实角色覆盖。范式对齐 MemoryWriteTool。
                // 此处 -1 占位的意义与 MemoryWriteTool 一致：保证 App 启动早期
                // AgentToolRegistry.get("skill_*") 即可命中，charId<0 兜底校验会拦住
                // 任何"角色未就绪"的误调用，不会写脏数据。
                SkillCreateTool(
                    repo        = appContainer.skillRepo,
                    characterId = { -1 },
                ),
                SkillEditTool(
                    repo        = appContainer.skillRepo,
                    characterId = { -1 },
                ),
                SkillDeprecateTool(
                    repo        = appContainer.skillRepo,
                    characterId = { -1 },
                ),
                SkillExpandTool(
                    repo        = appContainer.skillRepo,
                    characterId = { -1 },
                ),
                SkillFeedbackTool(
                    repo        = appContainer.skillRepo,
                    characterId = { -1 },
                ),
                GoalUpdateTool(
                    goalRepo    = learningGoalRepository,
                    characterId = { -1 },
                ),
                // ── 问题40修复：工作台任务跟踪 4 个工具，此前只在 ChatViewModel.init()
                // 动态覆盖注册，App 启动阶段完全没有静态占位注册（不同于同目录其余工具
                // 模块，如下方 registerSoulMemoryUserTools() 六个工具都有本文件内的
                // -1 占位）。后果：工作流后台执行（WorkflowEngine，无 ChatViewModel
                // 存活）想调用 task_start 等时，AgentToolRegistry.get() 直接返回 null
                // （"工具未注册"），而不是 self_reflect/rule_review 那种"注册了但
                // 角色ID会错"——是更彻底的缺失。
                // taskRepo 构造与 ChatViewModel.kt 完全一致（TaskRepository(db, db.taskDao(),
                // db.worldEventDao())），characterId 用 -1 占位，等 ChatViewModel.init()
                // 时按真实角色覆盖，与本文件其余角色绑定工具同一套两阶段注册模式。
                TaskStartTool(
                    taskRepo    = taskRepo,
                    characterId = { -1 },
                ),
                TaskUpdateTool(
                    taskRepo    = taskRepo,
                    characterId = { -1 },
                ),
                // W3-2 修复：静态占位注册同样补传 memoryEngine，覆盖 WorkflowEngine
                // 后台执行（无 ChatViewModel 存活）时 task_complete 触发的记忆写入路径。
                // AppContainer.init(this) 已在本方法调用前完成（见 onCreate 顺序），
                // 此处取 instance.memoryEngine 是安全的。
                TaskCompleteTool(
                    taskRepo    = taskRepo,
                    characterId = { -1 },
                    memoryEngine = { com.zaijian.zhoumuyun.data.AppContainer.instance.memoryEngine },
                ),
                TaskCancelTool(
                    taskRepo    = taskRepo,
                    characterId = { -1 },
                ),
            )
        }.onFailure { ZLog.e("ZaijianApp", "AgentCoreTools(PlanSave/Memory/Goal/Task) 注册失败", it) }
        // 问题39修复：Soul/Memory/User 三模块 6 个工具此前在本文件与 ChatViewModel.kt
        // 各自手写一份完全重复的实例化代码（唯一区别是 characterId 闭包），改用
        // registerSoulMemoryUserTools() 统一封装，本处传 -1 占位；ChatViewModel.init()
        // 改传 currentCharacterId 覆盖，两阶段注册的顺序/时机不变，只消除重复代码。
        runCatching {
            AgentToolRegistry.registerSoulMemoryUserTools(
                identityDao = identityRepository,
                characterId = { -1 },
            )
        }.onFailure { ZLog.e("ZaijianApp", "registerSoulMemoryUserTools 注册失败", it) }

        // S8-窗口11 P1-8-7 修复：RuleDistillTool 改为 providerFn 闭包模式后，
        // 不再需要在注册时刻判断 activeProvider 是否为 null——工具本身可以
        // 无条件注册，execute() 时才动态取最新 Provider。此前 `?.let` 写法
        // 会导致首次启动未配置 Key 时该工具直接跳过注册、完全不可用，
        // 需要等到 ChatViewModel.init() 覆盖注册时才补上；改为无条件注册后，
        // 即使用户此刻未配置 Key，工具也会在 execute() 时给出明确的
        // "请在设置中填写 API Key" 提示，而不是 <tool:rule_distill .../>
        // 标签被当成未知工具处理。
        runCatching {
            AgentToolRegistry.register(
                RuleDistillTool(
                    providerFn  = AgentTool.defaultProviderFn(),
                    memoryRepo  = memoryRepository,
                    goalRepo    = learningGoalRepository,
                    characterId = { -1 },
                )
            )
        }.onFailure { ZLog.e("ZaijianApp", "RuleDistillTool 注册失败", it) }

        // ── CreativeDocTools / DataVisTools / AgentMetaTools ─────────────────
        // W2 表格直传方案：scheduleRepository 必须在 registerDataVisTools 之前创建，
        // 因为 TableExportTool（在 DataVisTools.kt 里）注入了 scheduleRepository 作为
        // 来源 B 数据源。原 scheduleRepository 创建语句在下方（schedule_* 工具注册前），
        // 现提前到这里，供 registerDataVisTools 使用，下方原位置删除重复创建。
        val scheduleRepository = ScheduleRepository(
            scheduledJobDao = db.scheduledJobDao(),
            jobResultDao    = db.jobResultDao(),
            db              = db,
            context         = context,  // 批次1 1-5修复：补 context（registerAgentTools 形参），让 runLocalCompensation 的 finally 块重新入队逻辑生效
        )
        runCatching { AgentToolRegistry.registerCreativeDocTools(context) }.onFailure {
            // 诊断补丁（同批，补齐 2026-07-27 registerDataVisTools 那次遗漏的一处）：
            // registerCreativeDocTools 一次性注册 docx_gen/pdf_export/html_gen/
            // markdown_to_doc 等 10 个工具，任意一个构造失败会导致整批未注册——
            // LLM 的工具列表里根本不会出现这几个工具，既不会报"未注册"错误，
            // 也不会在 diag_export_log 里留下任何痕迹（此前只写 ZLog.e，仅 logcat
            // 可见），表现就是角色嘴上说"文档/PDF/网页已经生成/发给你了"，实际
            // 从未真正调用过工具——磁盘没文件，诊断日志也一片空白，用户完全无从
            // 排查。这里补一条 AgentLog.error，把真实异常类型 + 完整堆栈落进
            // 用户可导出的 agent_log.txt，只加日志，不改变任何现有行为/控制流。
            ZLog.e("ZaijianApp", "registerCreativeDocTools 注册失败", it)
            AgentLog.error("ZaijianApp", "registerCreativeDocTools 注册失败（docx_gen/pdf_export/html_gen/markdown_to_doc 等工具集体受影响）", it)
        }
        runCatching {
            AgentToolRegistry.registerDataVisTools(
                context            = context,
                memoryDao          = db.memoryDao(),
                // 复审修复：SelfReflectTool 的 Step3 写入需要走 MemoryRepository.save()
                // 才能同步 FTS，否则自我反思记忆永久无法被全文检索召回。
                // 复用本函数（registerAgentTools）开头已创建的 memoryRepository，
                // 不再新建实例。
                memoryRepo         = memoryRepository,
                // W2：TableExportTool 来源 B（日程数据源）需要 ScheduleRepository。
                scheduleRepository = scheduleRepository,
            )
        }.onFailure {
            // 诊断补丁（2026-07-27）：registerDataVisTools 把 excel_gen/pptx_gen/mindmap_gen/
            // flowchart_gen/table_export/csv_analyze/table_gen/self_reflect/rule_review 等
            // 十个工具放在同一个 registerAll(...) vararg 调用里，任意一个构造/类加载失败会
            // 导致整批工具集体不注册，但此前 onFailure 只写 ZLog.e（仅 logcat），排查时既没有
            // adb 环境、logcat 缓冲区也太小抓不到 App 启动阶段的这条日志，完全没有可用的排查
            // 入口。这里补一条 AgentLog.error，把真实异常类型 + 完整堆栈落进用户可导出的
            // agent_log.txt。只加日志，不改变任何现有行为/控制流。
            ZLog.e("ZaijianApp", "registerDataVisTools 注册失败", it)
            AgentLog.error("ZaijianApp", "registerDataVisTools 注册失败（excel_gen/pptx_gen 等十个工具集体受影响）", it)
        }
        runCatching {
            AgentToolRegistry.registerAgentMetaTools(
                context    = context,
                db         = db,
                memoryDao  = db.memoryDao(),
                sessionDao = db.evaluationSessionDao(),
                goalDao    = db.learningGoalDao(),
                messageDao = MessageRepository(db.messageDao()),
                taskDao    = db.taskDao(),
            )
        }.onFailure { ZLog.e("ZaijianApp", "registerAgentMetaTools 注册失败", it) }

        // ── CICD · GitHub 配置存储 ──────────────────────────────
        val githubConfigStore = GithubConfigDataStore(context)
        // 批次B（1.8）清理：旧版本使用明文 preferencesDataStore("github_config")，
        // 已整体迁移到 EncryptedSharedPreferences。先尝试读出旧文件中的
        // owner/repo/token 写入新的加密存储（仅当新存储尚无真实 token 时），
        // 再删除旧文件残留。全程 try-catch 包裹，任何一步失败都不影响启动。
        runCatching { migrateLegacyGithubConfig(context, githubConfigStore) }.onFailure { ZLog.e("ZaijianApp", "migrateLegacyGithubConfig 失败", it) }

        // ── 邮件账号存储 + 真实邮件收发工具（email_send / email_fetch）──
        val emailAccountStore = EmailAccountStore(context)
        runCatching { AgentToolRegistry.registerEmailTools(emailAccountStore) }.onFailure { ZLog.e("ZaijianApp", "registerEmailTools 注册失败", it) }

        // ── 成长系统 · 每日自我规划工具（project_daily_planner）────────
        // provider 为 null 时静默跳过（首次启动未配置Key），
        // ChatViewModel 完成配置后会覆盖注册。
        runCatching {
            AgentToolRegistry.register(
                ProjectDailyPlannerTool(
                    db         = db,
                    projectDao = db.projectDao(),
                    goalDao    = db.characterGoalDao(),
                    taskDao    = db.taskDao(),
                )
            )
        }.onFailure { ZLog.e("ZaijianApp", "ProjectDailyPlannerTool 注册失败", it) }

        // ── CICD · 注册原子工具（流水线各步骤可单独被 LLM 调用）──────
        runCatching {
            AgentToolRegistry.registerAll(
                CreateGithubRepoTool(githubConfigStore),
                GitCommitPushTool(githubConfigStore),
                BuildApkTool(githubConfigStore),
                BuildStatusCheckTool(githubConfigStore),
                BuildApkDownloadTool(context = context, githubConfigStore = githubConfigStore),
                // 批次4-1-2 修复：CiCdStartTool 此前只在 ChatViewModel 中注册，
                // 后台路径（如 WorkflowEngine 无 ChatViewModel 存活时）调用
                // cicd_start 会直接返回 null。在此补上静态占位注册，
                // characterId 固定传 -1（CI/CD 是项目级操作，不绑定当前聊天角色）。
                CiCdStartTool(
                    context               = context,
                    githubConfigStore     = githubConfigStore,
                    db                    = db,
                    workflowJobDao        = db.workflowJobDao(),
                    workflowStepResultDao = db.workflowStepResultDao(),
                    characterId           = { -1 },
                ),
                WorkflowStartTool(
                    context            = context,
                    // 修复：WorkflowStartTool 构造函数只需要 workflowRepository，
                    // 原代码错误地照搬了 CiCdStartTool 的 db/workflowJobDao/
                    // workflowStepResultDao 三参数写法，与真实构造函数不符。
                    workflowRepository = WorkflowRepository(db, db.workflowJobDao(), db.workflowStepResultDao(), context),
                    characterId        = { -1 },
                ),
            )
        }.onFailure { ZLog.e("ZaijianApp", "CICD 工具注册失败", it) }

        // ── Phase 29 · 调度系统初始化 ─────────────────────────────
        // W2：scheduleRepository 已提前到 registerDataVisTools 之前创建（上方），
        // 供 TableExportTool 注入使用；此处不再重复创建，原创建语句已删除。
        // 下方的 calendarSync / projectRepository 仍在此处创建（schedule_* 工具注册用）。

        // 修复手册 Phase 1.1：此前注册 Schedule 系列工具时遗漏了 CalendarSyncHelper 和
        // context 注入，导致 WorkManager 精确调度、系统日历同步、旧 WorkRequest 取消
        // 三条链路全部静默失效（三个工具构造函数的 calendarSync/context 参数默认 null）。
        // 现统一创建一个 CalendarSyncHelper 实例，注入给下面三个工具。
        val calendarSync = CalendarSyncHelper(context)

        // 日程系统第七节：创建一个 ProjectRepository 实例，注入给 schedule_* 工具，
        // 用于 project_id 参数的存在性校验（Create/Update）与项目标题展示（List/Get）。
        // 与 scheduleRepository 同款：在 Application onCreate 期间一次性创建，
        // 随后注入到各工具的单例实例中。
        val projectRepository = ProjectRepository(
            projectDao   = db.projectDao(),
            knowledgeDao = db.projectKnowledgeDao(),
        )

        // 注册 schedule_create 工具（characterId 由 ChatViewModel 动态覆盖）
        runCatching {
            AgentToolRegistry.register(
                ScheduleCreateTool(
                    scheduleRepository  = scheduleRepository,
                    characterIdProvider = { -1 },
                    projectRepository   = projectRepository,
                    calendarSync = calendarSync,
                    context = context,
                )
            )
        }.onFailure { ZLog.e("ZaijianApp", "ScheduleCreateTool 注册失败", it) }

        // ── Phase 30 · 日程管理补全（delete / update / get / list） ──────
        runCatching {
            AgentToolRegistry.registerAll(
                ScheduleDeleteTool(
                    scheduleRepository = scheduleRepository,
                    calendarSync = calendarSync,
                    context = context,
                    characterIdProvider = { -1 },
                ),
                ScheduleUpdateTool(
                    scheduleRepository = scheduleRepository,
                    projectRepository  = projectRepository,
                    calendarSync = calendarSync,
                    context = context,
                    characterIdProvider = { -1 },
                ),
                ScheduleGetTool(
                    scheduleRepository = scheduleRepository,
                    projectRepository  = projectRepository,
                    characterIdProvider = { -1 },
                ),
                ScheduleListTool(
                    scheduleRepository  = scheduleRepository,
                    characterIdProvider = { -1 },
                    projectRepository   = projectRepository,
                ),
            )
        }.onFailure { ZLog.e("ZaijianApp", "Schedule(delete/update/get/list) 注册失败", it) }

        // ── Phase 30 · 心跳检查清单（set / update / delete） ────────────
        runCatching {
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
        }.onFailure { ZLog.e("ZaijianApp", "Heartbeat(set/update/delete) 注册失败", it) }
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
