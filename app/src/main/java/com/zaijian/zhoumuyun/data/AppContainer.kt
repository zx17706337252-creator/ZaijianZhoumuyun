package com.zaijian.zhoumuyun.data

import android.content.Context
import com.zaijian.zhoumuyun.data.agent.CalendarSyncHelper
import com.zaijian.zhoumuyun.data.agent.CompetitionRoundManager
import com.zaijian.zhoumuyun.data.datastore.PregnancyPressureDataStore
import com.zaijian.zhoumuyun.data.datastore.SplashBackgroundDataStore
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.dao.JobResultDao
import com.zaijian.zhoumuyun.data.manager.FertileWindowConsentJudge
import com.zaijian.zhoumuyun.data.manager.PregnancyTriggerManager
import com.zaijian.zhoumuyun.data.manager.UserConsentIntentJudge
import com.zaijian.zhoumuyun.data.memory.MemoryEngine
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.AgentPlanRepository
import com.zaijian.zhoumuyun.data.repository.AgentStoreRepository
import com.zaijian.zhoumuyun.data.repository.CharacterGoalRepository
import com.zaijian.zhoumuyun.data.repository.CharacterStateRepository
import com.zaijian.zhoumuyun.data.repository.BriefingRepository
import com.zaijian.zhoumuyun.data.repository.CompetitionRoundRepository
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.EventRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.JudgeProfileRepository
import com.zaijian.zhoumuyun.data.repository.LearningGoalRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.SkillRepository
import com.zaijian.zhoumuyun.data.repository.MenstrualCycleRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import com.zaijian.zhoumuyun.data.repository.NotificationRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
import com.zaijian.zhoumuyun.data.repository.CharacterTitleRelationRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatPairRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatMessageRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatSessionRepository
import com.zaijian.zhoumuyun.data.privatechat.PrivateChatEngine
import com.zaijian.zhoumuyun.data.privatechat.PrivateChatExporter
import com.zaijian.zhoumuyun.data.repository.ProjectRepository
import com.zaijian.zhoumuyun.data.repository.RelationshipReadRepository
import com.zaijian.zhoumuyun.data.repository.RoundtableMessageRepository
import com.zaijian.zhoumuyun.data.repository.ScheduleRepository
import com.zaijian.zhoumuyun.data.repository.SpecialtyProfileRepository
import com.zaijian.zhoumuyun.data.repository.TaskRepository
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import com.zaijian.zhoumuyun.data.repository.AgentActivityRepository
import com.zaijian.zhoumuyun.data.repository.CapabilityPanelRepository
import com.zaijian.zhoumuyun.data.repository.CapabilityPanelRepositoryImpl
import com.zaijian.zhoumuyun.domain.CompetitionEngine
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.domain.ProactiveMessageNotifier
import com.zaijian.zhoumuyun.domain.RelationshipEngine
import com.zaijian.zhoumuyun.domain.SpecialtyEvolutionEngine
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全局单例依赖容器，持有原先在 ChatViewModel / RoundtableViewModel
 * 里各自构造一遍的 Repository / Engine 实例。
 *
 * 初始化方式：由 ZaijianApp.onCreate() 在 db 创建后立即同步构造，
 * 与 sharedPresenceEngine 的初始化时机对齐（同一批主线程操作，
 * 无 IO，构造成本是纯内存操作）。
 *
 * 与 by lazy 方案的关键区别：这里没有"首次访问触发构造"的时机不确定性——
 * ViewModel 访问 AppContainer.instance 时，它一定已经在 onCreate() 里
 * 构造完毕，因为 Application.onCreate() 保证先于任何 Activity/ViewModel
 * 创建执行。不需要用 @Volatile + 双重检查锁，因为不存在"多线程同时首次访问"
 * 的竞态窗口——构造只会发生一次，且发生在单线程的 onCreate() 里。
 *
 * 注意：`pregnancyTriggerManager` 本体不作为容器 val 字段——`ChatViewModel`/
 * `RoundtableViewModel`/`PregnancyViewModel` 三者的构造参数存在真实的功能性
 * 差异（是否传 `relationshipEngine`/`aiJudge`/`consentJudge`，见审计报告
 * Phase 3 决策 2），三种形态各自代表不同的真实调用场景，不适合收敛成单一
 * 共享实例。S8-窗口01 收口：改为容器提供三个工厂方法
 * （`createPregnancyTriggerManagerFull`/`ForRoundtable`/`Minimal`），把"构造
 * 参数差异"本身封装起来，调用方不再需要各自裸持 `db` 才能拼出这三种形态——
 * `fertileWindowConsentJudge`/`userConsentIntentJudge` 两个 AI 判定组件也随之
 * 收敛为容器共享单例（原先 ChatViewModel 每次构造 pregnancyTriggerManager 都
 * 各自 new 一份，无内部状态、构造参数完全一致，收敛后仅 ChatViewModel 一个
 * 调用方受益，RoundtableViewModel/PregnancyViewModel 本就不传这两个判定器）。
 * 本容器只提供它们都需要的 `pregnancyRepo`/`characterStateRepo` 两个共享依赖，
 * 以及供工厂方法内部使用的 `db`。
 *
 * ── [AUDIT-WONTFIX] 已裁定"不纳入本容器"的裸访问点（S8-窗口01 核查后标注）──
 * 以下几处 ViewModel 内的裸 db/DAO 持有，均已在各自文件内标注
 * `[AUDIT-WONTFIX S8-窗口01]` 并写明理由，后续 DI 收口审查请直接跳过，
 * 不要再列为待修问题：
 *   - IdentityViewModel.promotedSkillTagDao —— 全项目唯一调用点，无收敛意义
 *   - TaskViewModel.projectRepo —— 轻量单用途实例，非重复构造场景
 *   - SpecialtyEvolutionViewModel.db —— 供跨表事务操作直接使用，非偷懒未封装
 * 除以上三处外，其余 ViewModel 内的裸 db/AppDatabase.getInstance() 持有
 * （BottomNavBadgeViewModel、CompetitionViewModel、GlobalScheduleViewModel、
 * LearningGoalViewModel、PresenceViewModel、TaskViewModel 的其余裸 DAO 调用）
 * 仍是真实待办，未裁定为不做。
 * S8-窗口01 本批次已收口：RoundtableViewModel、PregnancyViewModel 的裸 db
 * 持有均已消灭（见上方 pregnancyTriggerManager 工厂方法说明）。ChatViewModel
 * 维持现状不动——它还有其余 9 处真实依赖 db（scheduleRepo/toolRegistrar/
 * evaluationEngine/distillationEngine/memoryDao 等），仅切换 pregnancyTriggerManager
 * 一项构造方式对其收益有限，留待单独立项处理，不在本批次范围内。
 */
class AppContainer private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(context)

    // 问题4修复：PregnancyPressureDataStore 完整实现但此前零实例化，
    // ChatViewModel/PregnancyTriggerManager 所有 pressureScale 参数一直硬编码
    // 1.0f。与其余 DataStore 类（AppearanceDataStore 等）持有模式一致，
    // 由容器构造函数收到的 context 直接构造，无需额外依赖。
    val pregnancyPressureDataStore: PregnancyPressureDataStore = PregnancyPressureDataStore(context)

    // 门扉页（启动页）自定义背景图配置。与 pregnancyPressureDataStore 同一持有
    // 模式：容器构造函数收到的 context 直接构造，无需额外依赖。SplashScreen 和
    // ProfileScreen（设置入口）都通过 AppContainer.instance 引用同一份实例。
    val splashBackgroundDataStore: SplashBackgroundDataStore = SplashBackgroundDataStore(context)


    // 阶段2 S-1 收尾：TimelineViewModel 原先本地独立构造，构造参数与此处完全
    // 一致，已切换为引用此实例。
    val eventRepo: EventRepository = EventRepository(db.worldEventDao())

    // 阶段2 S-1 批次1：MemoryRepository 此前在 ZaijianApp.onCreate() 内重复构造
    // 3 次 + CharacterPreviewViewModel/MemoryViewModel 各自独立构造，共至少 5 个
    // 实例。构造参数（memoryDao/memoryCandidateDao）在所有调用点完全一致，收敛为
    // 容器唯一持有源。ZaijianApp 内 3 处及 CharacterPreviewViewModel/
    // MemoryViewModel 均已切换为引用此处。
    val memoryRepo: MemoryRepository = MemoryRepository(db.memoryDao(), db.memoryCandidateDao(), db.memoryTagDao())
    val memoryEngine: MemoryEngine = MemoryEngine(db, memoryRepo, eventRepo)
    // Window C 技能系统：与 memoryRepo 同一持有模式（容器唯一实例），Agent 工具与
    // 未来 Window D 技能面板均引用此实例，保证任何一次写入经 Room Flow 推送给所有
    // observeSkills() 订阅者。范式对齐 memoryRepo（上方）。
    val skillRepo: SkillRepository = SkillRepository(db.skillDao())

    // 收尾交接清单 任务组A：ProfileScreen/ProfileStatsRow 原先各自在 Composable 内
    // remember{ AppDatabase.getInstance(context) } 现拿 db 再手动构造 Repository，
    // 现收敛为容器共享实例，与其余 Repository 保持同一持有模式。
    //
    // 阶段2 S-1：IdentityRepository 是全项目重复构造次数最多的 Repository
    // （原 7 处独立 new，构造参数均为 db.characterIdentityDao() 无差异）。
    // 已全部切换完毕：ChatViewModel/RoundtableViewModel/FamilyListViewModel（批次1）、
    // IdentityViewModel/PresenceViewModel（批次2）、LearningGoalViewModel/
    // TaskViewModel（批次3）。identityRepo 收口完成。
    val identityRepo: IdentityRepository = IdentityRepository(db.characterIdentityDao())
    val messageRepo: MessageRepository = MessageRepository(db.messageDao())
    // 阶段2 S-1 批次3：TaskViewModel.repo 原先独立 new（构造参数与此处完全
    // 一致），已切换为引用此实例。
    // 阶段2 S-1 收尾：ProjectViewModel.taskRepo 同样构造参数完全一致，已切换。
    // ChatViewModel 内的 taskRepo 仍独立持有，留待 S-3（ChatViewModel 拆分）
    // 一并处理。
    val taskRepo: TaskRepository = TaskRepository(db, db.taskDao(), db.worldEventDao())

    // 阶段2 S-2 遗留补项：GlobalScheduleViewModel 原先本地独立构造整套
    // db → dao → ScheduleRepository（构造参数与容器可提供的完全一致），
    // 与审计报告"窗口16 P5"点名的问题同源。此前 AppContainer 没有
    // scheduleRepo 字段可供其引用，现补齐，收口为容器唯一持有源，
    // 与 ScheduleCreateTool（Agent 工具路径）走同一套 calendarSync/context
    // 完整同步逻辑。
    // 阶段2 S-1 收尾：PersonalScheduleViewModel（5参构造，逐字段一致）、
    // ProjectViewModel（原3参构造，缺 calendarSync/context，但唯一调用方
    // scheduleDailyPlannerJob() 只用 createJob()，不触达这两个可选参数影响
    // 的分支，行为等价）均已切换为引用此实例。
    val scheduleRepo: ScheduleRepository = ScheduleRepository(
        scheduledJobDao = db.scheduledJobDao(),
        jobResultDao    = db.jobResultDao(),
        db              = db,
        calendarSync    = CalendarSyncHelper(appContext),
        context         = appContext,
    )

    // window13新问题2修复：ZaijianMessagingService 收到 FCM 推送时构造的
    // fakeResult（仅内存对象）需要落库，否则用户点击 Toast 进入任务中心时查不到
    // 记录，或 Toast 展示期间进程被杀导致任务完成记录永久丢失。jobResultDao
    // 本身就是 Room 生成的无状态 DAO，与 scheduleRepo 内部持有的同一个实例
    // 并不冲突，这里单独暴露一份供不需要 ScheduleRepository 其余业务逻辑的
    // 调用方（如 push 层）直接写入。
    val jobResultDao: JobResultDao = db.jobResultDao()

    // 阶段2 S-2 遗留补项：SpecialtyEvolutionViewModel 原先完全独立构造
    // SpecialtyProfileRepository（6 个参数手动传，无 AppContainer import，
    // 无 S-1 相关注释），对应审计报告"窗口10 疑似1"。reassembleCompetitionEngine()
    // 内部恰好构造了一个参数完全相同的 SpecialtyProfileRepository（供
    // CompetitionRoundManager 使用），是可以直接复用的重复实例。现收口为
    // 容器唯一持有源，reassembleCompetitionEngine() 与 SpecialtyEvolutionViewModel
    // 均改为引用此处，不再各自构造。
    val specialtyProfileRepo: SpecialtyProfileRepository = SpecialtyProfileRepository(
        db                       = db,
        specialtyProfileDao      = db.specialtyProfileDao(),
        evolutionPlanDao         = db.evolutionPlanDao(),
        practiceRecordDao        = db.practiceRecordDao(),
        practiceRecordArchiveDao = db.practiceRecordArchiveDao(),
        stageDigestDao           = db.stageDigestDao(),
        systemSuggestionDao      = db.systemSuggestionDao(),
    )

    // 统一为带 milestoneDao 的版本（审计报告 Phase 3 决策 2：
    // 一对一聊天以后也会记录关系里程碑，是一次真实的功能变化）。
    val relationshipEngine: RelationshipEngine = RelationshipEngine(
        db, db.relationshipDao(), eventRepo, db.relationshipMilestoneDao(),
        // A9-4 修复：传入 memoryEngine，关系里程碑记录后传播到 PERSONAL 域长期记忆。
        memoryEngine,
    )

    // S8-窗口01 修复：CharacterDetailScreen.kt（HeroCard 迷你版 BondRibbon）与
    // CharacterDetailRelationship.kt（RelationshipPanel 完整版关系面板）原先各自
    // 在 Composable 内 `remember { AppDatabase.getInstance(...) }` 裸拿 db 再直接
    // 调 relationshipDao/worldEventDao/relationshipMilestoneDao 三个 DAO，是
    // UI 层直触持久化层的分层违规，且无错误处理（Room 查询异常会导致重组崩溃）。
    // relationshipEngine 是写路径领域引擎，不适合挪用做纯读查询，故新增这个
    // 轻量只读 Repository：只包装查询 + 统一 catch/try-catch，不含业务规则。
    val relationshipReadRepo: RelationshipReadRepository = RelationshipReadRepository(
        db.relationshipDao(), db.worldEventDao(), db.relationshipMilestoneDao()
    )

    val pregnancyRepo: PregnancyRepository = PregnancyRepository(db.pregnancyDao())
    val characterStateRepo: CharacterStateRepository = CharacterStateRepository(db.characterStateDao())

    // S8-窗口01 收口：fertileWindowConsentJudge/userConsentIntentJudge 原先仅
    // ChatViewModel 在构造 pregnancyTriggerManager 时各自 new 一份（RoundtableViewModel/
    // PregnancyViewModel 不传这两个可选参数）。两个类都是无内部状态、单次调用的
    // AI 判定组件（见各自类头注释），构造参数完全一致
    // （providerFn = { ProviderManager.instance.activeProvider }），符合"无状态可
    // 共享单例"的收敛条件，收敛为容器共享实例。懒加载 providerFn 语义不变——
    // 用户切换 provider/Key 后两个判定器仍会取到最新 activeProvider。
    val fertileWindowConsentJudge: FertileWindowConsentJudge =
        FertileWindowConsentJudge(providerFn = { ProviderManager.instance.activeProvider })
    val userConsentIntentJudge: UserConsentIntentJudge =
        UserConsentIntentJudge(providerFn = { ProviderManager.instance.activeProvider })

    // S8-窗口01 收口：PregnancyTriggerManager 本体因构造参数存在真实功能性
    // 差异（是否传 relationshipEngine/aiJudge/consentJudge，见本类头部注释），
    // 不适合收敛成单一共享 val 实例，改为容器提供三个工厂方法，把三种调用方
    // 各自需要的构造形态封装起来——调用方不再需要各自持有 db 才能拼出
    // PregnancyTriggerManager，只需要调用对应工厂方法。
    //
    // 三个工厂方法与迁移前三处调用方的构造参数逐字段对应，未做任何行为变更：
    //   - createPregnancyTriggerManagerFull()        对应原 ChatViewModel（db +
    //     pregnancyRepo + cycleRepository + stateRepository + relationshipEngine
    //     + aiJudge + consentJudge，参数一致，其中 cycleRepository/stateRepository
    //     由调用方传入以保持与各 ViewModel 自身持有实例的一致性）
    //   - createPregnancyTriggerManagerForRoundtable() 对应原 RoundtableViewModel
    //     （db + pregnancyRepo + cycleRepository + stateRepository，不传
    //     relationshipEngine/aiJudge/consentJudge）
    //   - createPregnancyTriggerManagerMinimal()      对应原 PregnancyViewModel
    //     （db + pregnancyRepo + cycleRepository + stateRepository，三个可选
    //     参数均不传，与 ForRoundtable 参数集合相同，保留两个方法是为了让调用
    //     语义与三处调用方原有的注释/命名一一对应，避免"为什么 Roundtable 和
    //     PregnancyViewModel 共用一个工厂方法"的误读）
    //
    // cycleRepository/stateRepository 允许调用方传入而非固定用容器字段，是因为
    // 三处调用方原先各自持有的实例来源不完全相同（ChatViewModel/RoundtableViewModel
    // 走 container.menstrualCycleRepo/characterStateRepo，PregnancyViewModel 走
    // AppContainer.instance.menstrualCycleRepo/characterStateRepo——实际上是同一个
    // 共享实例，只是获取路径写法不同），保留传参可以不强行假设三处调用方未来
    // 不会再出现差异化需求；默认值指向容器自身共享实例，调用方不传时行为与
    // 原先完全一致。
    fun createPregnancyTriggerManagerFull(
        cycleRepository: MenstrualCycleRepository = menstrualCycleRepo,
        stateRepository: CharacterStateRepository = characterStateRepo,
    ): PregnancyTriggerManager = PregnancyTriggerManager(
        db                   = db,
        pregnancyRepository  = pregnancyRepo,
        cycleRepository      = cycleRepository,
        stateRepository      = stateRepository,
        relationshipEngine   = relationshipEngine,
        aiJudge              = fertileWindowConsentJudge,
        consentJudge         = userConsentIntentJudge,
        pressureDataStore    = pregnancyPressureDataStore,
    )

    fun createPregnancyTriggerManagerForRoundtable(
        cycleRepository: MenstrualCycleRepository = menstrualCycleRepo,
        stateRepository: CharacterStateRepository = characterStateRepo,
    ): PregnancyTriggerManager = PregnancyTriggerManager(
        db                   = db,
        pregnancyRepository  = pregnancyRepo,
        cycleRepository      = cycleRepository,
        stateRepository      = stateRepository,
        pressureDataStore    = pregnancyPressureDataStore,
    )

    fun createPregnancyTriggerManagerMinimal(
        cycleRepository: MenstrualCycleRepository = menstrualCycleRepo,
        stateRepository: CharacterStateRepository = characterStateRepo,
    ): PregnancyTriggerManager = PregnancyTriggerManager(
        db                   = db,
        pregnancyRepository  = pregnancyRepo,
        cycleRepository      = cycleRepository,
        stateRepository      = stateRepository,
        pressureDataStore    = pregnancyPressureDataStore,
    )

    // 报告第5条：PresenceEngine 收敛。原先由 ZaijianApp.onCreate() 构造并写入
    // companion object 的 sharedPresenceEngine（可空、跨线程可见性靠 @Volatile
    // 保证），ChatViewModel/RoundtableViewModel/PresenceViewModel/
    // ZaijianMessagingService 四处各自直接访问该全局单例。现在改为在
    // AppContainer 内部自包含构造（不依赖外部传参——PresenceEngine 的构造
    // 参数 goalDao/eventDao 均可从本容器已持有的 db 拿到），成为唯一构造源，
    // 与其余 6 项共享实例统一走同一套"容器持有、ViewModel 只读"模式。
    // PresenceEngine.init(context) 是 companion object 的静态方法（只是把
    // appContext 存到静态字段，供主动消息开关读取 SharedPreferences 用，
    // 与实例状态无关），在此一并调用，语义上等价于原先 ZaijianApp.onCreate()
    // 里紧跟 PresenceEngine 构造之后的那次调用。
    //
    // 阶段2 S-2 遗留补项：ChatViewModel/RoundtableViewModel/ChatScreen.kt 三处
    // 此前已切换为不再直接访问 ZaijianApp.sharedPresenceEngine，但 PresenceViewModel
    // 自身和 ZaijianMessagingService 漏改，仍保留对该全局单例的直接访问——
    // 与本段"四处已统一"的表述矛盾。sharedPresenceEngine 在 ZaijianApp.onCreate()
    // 内被赋值为本字段的同一实例，运行时行为不受影响，但代码路径未跟上迁移。
    // 现已将这两处均改为直接引用 AppContainer.instance.presenceEngine，
    // "四处已统一"的表述至此才真正成立。
    // window13结论7修复：此前未传 onProactiveMessage，前台场景下其他角色（非当前
    // 聊天页角色）的主动消息只经由 proactiveMessageFlow 内存广播，而 ChatViewModel
    // 只处理 msg.characterId == currentCharacterId 的消息——其余角色的消息既不落库
    // 也不弹通知，用户完全无感知地永久丢失。现补上与 ProactiveMessageWorker（后台
    // 路径）同款的 ProactiveMessageNotifier，确保内存广播（当前聊天页 Snackbar）和
    // 落库通知（其他场景）成对发生。
    //
    // 批次9 9-3修复：daughterCharacterRepo 声明上移到此处的 proactiveMessageNotifier
    // 之前，消除 proactiveMessageNotifier/presenceEngine 两处局部 new 重复构造。
    // 原先因声明顺序在前而各自局部 new 一份，构造参数与共享字段完全一致，纯重复构造。
    // 报告第6条：CharacterDetailScreen.kt 里查询女儿角色身份的 LaunchedEffect
    // 原先自己 remember { AppDatabase.getInstance(context) } 再手动
    // DaughterCharacterRepository(db.daughterCharacterDao())，是 Composable
    // 直接触达持久化层（审计报告 Phase 1 点名的最严重分层违规）。核查后发现
    // ChatViewModel/RoundtableViewModel 各自也独立持有一份构造参数完全相同的
    // DaughterCharacterRepository（仅 db.daughterCharacterDao()，无差异化配置），
    // 因此并入本容器共享，而不是报告原述"改走 IdentityViewModel"——后者的
    // uiState 是围绕人设编辑表单设计的，语义与"查一次女儿角色 CharacterConfig"
    // 不匹配，硬塞进去会污染已经很复杂的 IdentityUiState。
    // 阶段2 S-1：daughterCharacterRepo 已全部切换完毕：ChatViewModel/
    // RoundtableViewModel（批次1之前）、FamilyListViewModel（批次1）、
    // PresenceViewModel（批次2）、CompetitionViewModel（批次3）、
    // ProjectViewModel（收尾）。收口完成。
    val daughterCharacterRepo: DaughterCharacterRepository =
        DaughterCharacterRepository(db, db.daughterCharacterDao())

    private val proactiveMessageNotifier: ProactiveMessageNotifier = ProactiveMessageNotifier(
        context               = appContext,
        messageDao            = messageRepo,
        daughterCharacterRepo = daughterCharacterRepo, // 批次9 9-3修复：改引用共享字段
    )

    // AI 化主动消息新增依赖：messageRepo 供组装最近对话历史。
    // 批次9 9-3修复：daughterCharacterRepo 改引用上方共享字段（已上移声明），
    // 不再局部 new 重复构造。
    val presenceEngine: PresenceEngine = PresenceEngine(
        goalDao  = db.characterGoalDao(),
        eventDao = db.worldEventDao(),
        onProactiveMessage = { msg ->
            // 当前正在该角色聊天页：Snackbar 已经通过 proactiveMessageFlow 展示了，
            // 落库但不重复弹系统通知，避免双重打扰；其他角色/App 已退到后台：
            // 落库 + 正常弹通知。
            //
            // 用户反馈修复：原判定只看 foregroundChatCharacterId == msg.characterId，
            // 但该字段在 ChatViewModel（应用内单例，几乎不销毁）onCleared() 才会清空，
            // 用户退出聊天页切到后台后它依然卡在"最后进过的角色"，导致该角色的主动
            // 消息在用户明明已退到后台时仍被误判为"正在聊天页"而不弹通知，只有重新
            // 打开 App 才能看到。现改为角色匹配 且 App 确实在前台（isAppInForeground，
            // 由 ZaijianApp 的 ActivityLifecycleCallbacks 维护）两个条件同时成立才抑制。
            val suppress = msg.characterId == PresenceEngine.foregroundChatCharacterId &&
                PresenceEngine.isAppInForeground
            proactiveMessageNotifier.persistAndNotify(msg, suppressNotification = suppress)
        },
        messageDao            = messageRepo,
        daughterCharacterRepo  = daughterCharacterRepo, // 批次9 9-3修复：改引用共享字段
    )

    // 离线简报（Briefing）聚合层。只读，见《再见公馆》UI/UX 整合方案 v2.1 第四节。
    // menstrualCycleRepo 此前无任何 ViewModel 持有共享实例（BookCard 指示点尚未接入
    // 周期显示），Briefing 是第一个需要它的调用方，因此在此新构造并归入容器共享持有。
    val menstrualCycleRepo: MenstrualCycleRepository =
        MenstrualCycleRepository(db.menstrualCycleDao())

    val briefingRepo: BriefingRepository = BriefingRepository(
        relationshipDao          = db.relationshipDao(),
        relationshipMilestoneDao = db.relationshipMilestoneDao(),
        relationshipEngine       = relationshipEngine,
        pregnancyRepo            = pregnancyRepo,
        menstrualCycleRepo       = menstrualCycleRepo,
        projectDao               = db.projectDao(),
        taskDao                  = db.taskDao(),
        messageDao               = db.messageDao(),
        competitionRoundDao      = db.competitionRoundDao(),
        competitionEntryDao      = db.competitionEntryDao(),
        daughterCharacterRepo    = daughterCharacterRepo,
    )

    val notificationRepo: NotificationRepository = NotificationRepository(
        readStateDao = db.notificationReadStateDao(),
    )

    // S-1 缺口2：GoalViewModel 原先直接裸调 projectDao，现统一通过 ProjectRepository 访问。
    // 阶段2 S-1 收尾：ProjectViewModel 原先独立 new（构造参数完全一致，此前有
    // 过时注释称"AppContainer 没有此字段"），现已一并切换为容器共享实例。
    val projectRepo: ProjectRepository = ProjectRepository(
        projectDao   = db.projectDao(),
        knowledgeDao = db.projectKnowledgeDao(),
    )

    // S8-窗口01 收口：LearningGoalRepository 已存在（此前仅 RoundtableViewModel
    // 单独裸构造引用），全项目至少还有 BottomNavBadgeViewModel/LearningGoalViewModel
    // 裸持 learningGoalDao，构造参数完全一致，收敛为容器共享实例。
    val learningGoalRepo: LearningGoalRepository =
        LearningGoalRepository(db.learningGoalDao())

    // S8-窗口01 收口：AgentPlanRepository 原先在 ChatViewModel/RoundtableViewModel/
    // ZaijianApp 三处各自独立构造（构造参数均为 db.agentPlanDao() 无差异），
    // 收敛为容器共享实例。ChatViewModel 本次不改动（范围见审计报告 S8-窗口01
    // 高优先级项，留待专门批次处理），RoundtableViewModel/ZaijianApp 已切换。
    val agentPlanRepo: AgentPlanRepository =
        AgentPlanRepository(db.agentPlanDao())

    // S8-窗口01 收口：RoundtableMessageRepository 原先仅 RoundtableViewModel
    // 单独裸构造，此处收敛为容器共享实例（构造参数一致，且该 Repository 本身
    // 就是"圆桌消息"这一单一场景下唯一调用方，收口后无功能影响）。
    val roundtableMessageRepo: RoundtableMessageRepository =
        RoundtableMessageRepository(db.roundtableMessageDao())

    // 阶段2 S-1 最终收尾：v160 报告点名"暂不处理、留给未来批次"的两项 DI 缺口
    // 之一。GoalViewModel 原先仍裸持有 `goalDao = AppDatabase.getInstance(application)
    // .characterGoalDao()`（projectRepo 此前已切换，goalDao 是唯一残留的直连字段）。
    // characterGoalDao 的其余调用点（ChatToolRegistrar/ProactiveMessageWorker/
    // ZaijianApp 内构造 ProjectDailyPlannerTool/PresenceEngine）不受影响——
    // 那是 Domain/Agent 层工具类接受 DAO 作为构造参数的既定模式，不属于
    // S-1"ViewModel 绕过 AppContainer"的范围，本次不改动。
    val characterGoalRepo: CharacterGoalRepository =
        CharacterGoalRepository(db.characterGoalDao())

    // S-1 缺口2：ChatViewModel 原先直接 new WorkflowRepository，现统一通过容器共享。
    val workflowRepo: WorkflowRepository = WorkflowRepository(
        db                    = db,
        workflowJobDao        = db.workflowJobDao(),
        workflowStepResultDao = db.workflowStepResultDao(),
        context               = appContext,
    )

    // Window B（「心迹」，方案 2.2.2）：Agent 过程可见层 Repository。
    // 写入侧供降级策略状态机（2.1）、三处 UI 集成点（2.2.3）、WorkflowEngine
    // 镜像埋点（2.1.4）调用；读取侧供「心迹」面板合并时间线消费。构造参数与
    // workflowRepo 同源（同一份 db + 同两个 DAO），无差异化配置，归入容器共享。
    val agentActivityRepo: AgentActivityRepository = AgentActivityRepository(
        agentActivityDao        = db.agentActivityDao(),
        workflowStepResultDao   = db.workflowStepResultDao(),
    )

    // §2.2.4 能力面板只读查询面：Window D 消费方通过此接口获取角色能力快照，
    // 不直接查表。数据来源同 agentActivityRepo + workflowJobDao，无独立 DAO。
    val capabilityPanelRepo: CapabilityPanelRepository = CapabilityPanelRepositoryImpl(
        agentActivityDao = db.agentActivityDao(),
        workflowJobDao   = db.workflowJobDao(),
    )

    // 阶段2 S-1 最终收尾：v160 报告点名"暂不处理、留给未来批次"的两项 DI 缺口
    // 之二。JudgeProfileViewModel 原先整个裸持有 `db = AppDatabase.getInstance
    // (application)`，直接调 db.judgeProfileDao() 与 db.competitionRoundDao()，
    // 是全项目中唯二（连同 GoalViewModel）完全没有 Repository 层、直连 db 的
    // ViewModel。observeRoundsAsJudge 语义上属于"裁判视角"，收纳进本
    // Repository 而非另建 CompetitionRoundRepository——后者会牵连
    // CompetitionViewModel 对 competitionRoundDao() 的其余裸调用，超出本次
    // 迁移范围（详见 JudgeProfileRepository 类注释）。CompetitionRoundManager
    // 对 judgeProfileDao 的懒创建逻辑同理不受影响，那是 Domain/Agent 层的
    // 既定模式。
    val judgeProfileRepo: JudgeProfileRepository =
        JudgeProfileRepository(db, db.judgeProfileDao(), db.competitionRoundDao())

    // S8-窗口01 收口：CompetitionViewModel 原先裸持有 db，直接调
    // db.competitionRoundDao()/db.competitionEntryDao()。JudgeProfileRepository
    // 类注释里早已点名"CompetitionRoundRepository 留待未来批次统一处理"，
    // 现在补上，方法集合只覆盖 CompetitionViewModel 实际用到的部分（详见该
    // Repository 类注释）。CompetitionRoundManager 对这两个 DAO 的直接调用
    // 不受影响，维持既定的 Domain 层持有 DAO 构造依赖的模式。
    val competitionRoundRepo: CompetitionRoundRepository =
        CompetitionRoundRepository(db.competitionRoundDao(), db.competitionEntryDao())

    // ── 角色间私聊（方案_角色间私聊_v2-5）──────────────────────────
    // 三张独立表的 Repository，与 RoundtableMessageRepository 同款薄封装模式。
    val privateChatPairRepo: PrivateChatPairRepository =
        PrivateChatPairRepository(db.privateChatPairDao())
    val privateChatMessageRepo: PrivateChatMessageRepository =
        PrivateChatMessageRepository(db.privateChatMessageDao())
    val privateChatSessionRepo: PrivateChatSessionRepository =
        PrivateChatSessionRepository(db.privateChatSessionDao())

    // ── 角色间关系头衔系统（方案_角色间关系头衔系统_实施方案）────────────
    val characterTitleRelationRepo: CharacterTitleRelationRepository =
        CharacterTitleRelationRepository(db.characterTitleRelationDao(), db.impersonationPresetDao())

    // Agent 结构化存储（方案_Agent结构化存储_最终版）：与上方私聊三 repo 同款薄封装，
    // 容器唯一持有源，供 ZaijianApp 静态占位注册与 ChatToolRegistrar 角色覆盖注册两处引用。
    val agentStoreRepo: AgentStoreRepository =
        AgentStoreRepository(db.agentStoreDao())

    // ── 灵活自动化编排（方案_灵活自动化编排_改造设计方案_v1-5）─────────
    //
    // §6 ChainTriggerMatcher + EventBus：事件驱动的自动化链条系统。
    // chainRunRepository 是链条运行仓库，封装 ChainRunDao/ChainDefinitionDao/PendingEventDao。
    // eventPublisher 将"写 PendingEventEntity + EventBus.emit()"两步合一（§11.1），
    // 供业务代码一行调用完成事件发布。
    // chainTriggerMatcher 在 startChainSystem() 中构造并启动订阅（§6.1：必须挂在
    // appScope 上，不能挂在 ChatViewModel.viewModelScope 上）。
    val chainRunRepository: com.zaijian.zhoumuyun.data.repository.ChainRunRepositoryImpl =
        com.zaijian.zhoumuyun.data.repository.ChainRunRepositoryImpl(
            chainRunDao = db.chainRunDao(),
            chainDefinitionDao = db.chainDefinitionDao(),
            pendingEventDao = db.pendingEventDao(),
            context = appContext,
        )

    val eventPublisher: com.zaijian.zhoumuyun.data.agent.EventPublisher =
        com.zaijian.zhoumuyun.data.agent.EventPublisher(chainRunRepository)

    @Volatile var chainTriggerMatcher: com.zaijian.zhoumuyun.data.agent.ChainTriggerMatcher? = null
        private set

    /**
     * §6.1 启动链条触发匹配器：在 App 级 [scope]（appScope）上常驻订阅 EventBus.events。
     *
     * **必须在 ZaijianApp.onCreate() 里 appScope 创建之后调用**——AppContainer.init()
     * 执行时 appScope 尚未创建，无法在此处直接启动订阅。
     *
     * 同时执行 §11.1 事件落盘兜底：查所有 processed=false 的 PendingEventEntity，
     * 逐条重放给 ChainTriggerMatcher，成功后标记 processed=true。
     *
     * ChainEngineDeps 当前为 ProductionChainEngineDeps（§11.8 初始版本）：
     * scheduleResume 已实现（协程延迟），runAction/runCheckTool 待接入 WorkflowEngine。
     */
    fun startChainSystem(scope: kotlinx.coroutines.CoroutineScope) {
        if (chainTriggerMatcher != null) return  // 幂等，防止重复启动

        val engine = com.zaijian.zhoumuyun.data.agent.ChainEngine
        val deps = com.zaijian.zhoumuyun.data.agent.ProductionChainEngineDeps(
            scope = scope,
            chainEngine = engine,
            repository = chainRunRepository,
            context = appContext,
        )
        val matcher = com.zaijian.zhoumuyun.data.agent.ChainTriggerMatcher(
            repository = chainRunRepository,
            chainEngine = engine,
            deps = deps,
        )
        matcher.start(scope)
        chainTriggerMatcher = matcher

        // §11.1 事件落盘兜底：App 重启时重放未处理事件
        scope.launch {
            try {
                matcher.processPendingEvents()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("AppContainer", "processPendingEvents 失败", e)
            }
        }

        ZLog.d("AppContainer", "链条触发匹配器已启动")
    }

    // 私聊核心引擎：构造时直接依赖 daughterCharacterRepo（v2.5 两层硬编码查找，
    // 不引入 CharacterResolver 抽象层）。providerFn 用 () -> LLMProvider 延迟
    // 获取当前活跃 Provider（与 fertileWindowConsentJudge 同款写法），避免
    // AppContainer 初始化时 Provider 尚未装配的问题。
    val privateChatEngine: PrivateChatEngine = PrivateChatEngine(
        pairRepo             = privateChatPairRepo,
        messageRepo          = privateChatMessageRepo,
        sessionRepo          = privateChatSessionRepo,
        sessionAndPairDao    = db.privateChatSessionAndPairDao(),
        memoryRepo           = memoryRepo,
        identityRepo         = identityRepo,
        characterStateRepo   = characterStateRepo,
        daughterCharacterRepo = daughterCharacterRepo,
        titleRelationRepo     = characterTitleRelationRepo,
        providerFn           = { ProviderManager.instance.activeProvider },
        appContext            = appContext,
    )

    // 私聊导出模块：构造时直接依赖 daughterCharacterRepo，与 PrivateChatEngine
    // 各自持有一份（v2.5 设计上刻意接受的重复，不共享）。
    val privateChatExporter: PrivateChatExporter = PrivateChatExporter(
        messageRepo          = privateChatMessageRepo,
        sessionRepo          = privateChatSessionRepo,
        daughterCharacterRepo = daughterCharacterRepo,
    )

    // ── 阶段2 S-2：可变但受控的容器扩展 ──────────────────────────────
    //
    // competitionEngine/competitionRoundManager 与本文件其余字段性质不同：
    // 其余字段是"onCreate 内一次性构造、之后永久不变"的 val；这两个是
    // @Volatile var，原先定义在 ZaijianApp companion object 里，因为它们
    // 依赖 ProviderManager.activeProvider——App 首次启动时 Key 可能还没
    // 配置好，需要在 Provider 就绪后才能装配（见 reassembleCompetitionEngine()
    // 的调用时机：ZaijianApp.onCreate() 里先尝试一次，再注册
    // onProviderConfigChanged 监听器）。
    //
    // 纯结构性搬家：本次只是把原先散落在 ZaijianApp companion object 的
    // sharedCompetitionEngine/sharedCompetitionRoundManager/buildMutex/
    // tryBuildCompetitionEngine() 整体移入 AppContainer，成为容器统一持有
    // 的可变扩展点，不再是 ZaijianApp 之外还要单独记一个全局单例的例外。
    @Volatile var competitionEngine: CompetitionEngine? = null
        private set

    @Volatile var competitionRoundManager: CompetitionRoundManager? = null
        private set

    // 问题35修复：competitionEngine 为 null 有两种完全不同的原因——
    // ①用户尚未配置任何 Provider/Key（activeProvider 为 null，属正常未装配）
    // ②用户已配置 Key，但装配过程本身抛异常（构造函数出错等）。
    // 原先两者对调用方（CompetitionViewModel）表现完全一样，导致②的场景下
    // UI 误报"请先配置 API Key"，用户配了 Key 却看到这提示会不知所措。
    // 这里补一个显式标志区分两种情况，供 UI 展示更准确的提示。
    @Volatile var competitionEngineAssemblyFailed: Boolean = false
        private set

    private val competitionBuildMutex = Mutex()

    /**
     * 尝试装配竞争引擎（若尚未装配且 Provider 已就绪；或 force=true 时无条件重装）。
     * 调用方：ZaijianApp.onCreate() 启动时立即调用一次（force 默认 false）+ 注册
     * ProviderManager.onProviderConfigChanged 监听器在 Key 变更时调用（传 force=true）。
     *
     * 窗口02 结论6 修复（P2，逻辑bug，已知问题）：原版本
     * `if (competitionEngine != null) return@withLock` 是纯粹的"装配过一次就
     * 永久跳过"，与调用方 ZaijianApp.kt 里
     * `ProviderManager.instance.addOnProviderConfigChangedListener { ... }`
     * 的注册意图（"用户切换 Key 后重新装配"）矛盾：监听器确实会在切 Key 时
     * 触发调用，但只要 competitionEngine 已经非 null（几乎总是如此，因为
     * App 冷启动时通常已经装配过一次），本方法体直接短路返回，CompetitionEngine/
     * CompetitionRoundManager 内部持有的仍是首次装配时那个旧 LLMProvider 实例
     * （provider 是构造时传入的 private val，不会自己感知外部 Key 变化）。
     * 结果是用户在 ProfileScreen 切换/更新 API Key 后，界面上看起来
     * "配置已保存"，但竞赛功能（裁判评审/角色产出）实际仍在用旧 Key 请求
     * LLM——这条影响是真实的，不是理论风险。
     *
     * 修复方案：新增 `force` 参数区分两种调用场景——
     *   - force=false（App 冷启动首次尝试）：保持原幂等语义，已装配则跳过，
     *     避免同一个 activeProvider 值被无意义地重复构造。
     *   - force=true（onProviderConfigChanged 回调触发）：说明 Provider 配置
     *     确实发生了变化，无条件重新构造，用新 activeProvider 替换旧实例。
     * 之所以不改为"比较新旧 Provider 是否相等来自动判断"，是因为
     * ProviderManager.activeProvider 是计算属性，每次读取都 new 一个全新的
     * OpenAICompatProvider 实例且未覆写 equals()/hashCode()（默认引用相等），
     * baseUrl/apiKey/defaultModel 均为 private，容器这一层拿不到可比较的
     * 字段——引入这种比较需要改 OpenAICompatProvider 的可见性或加 data class
     * 语义，属于超出本次 bug 修复范围的架构改动。而 onCreate() 首次尝试与
     * onProviderConfigChanged 回调两个调用点本身就已经精确对应"第一次装配"
     * 与"配置确实变了"两种场景，让调用方显式传参更直接、风险更低。
     *
     * 重新装配是安全的：CompetitionRoundManager 状态机完全由数据库
     * competition_rounds.status 驱动（见该类头部注释），不持有跨调用的内存态；
     * 唯一调用方 CompetitionViewModel 通过
     * `AppContainer.instance.competitionRoundManager` 计算属性实时读取（见该
     * ViewModel 私有属性 getter），不缓存旧引用，重新装配后下一次调用自然拿到
     * 新实例，不会出现"半个操作在旧引擎、半个在新引擎"的撕裂。
     *
     * 窗口02复核新发现1修复：原方法体内构造逻辑无 try-catch 保护——若
     * SpecialtyEvolutionEngine/CompetitionEngine/CompetitionRoundManager
     * 任一构造过程中抛出异常，会直接向上传播到 ZaijianApp.kt 里
     * `scope.launch { appContainer.reassembleCompetitionEngine() }` 所在的
     * 协程，该协程挂在 SupervisorJob 下，异常不会波及其余协程，但会静默
     * 终止且无日志，难以定位。补齐 try-catch + ZLog.e，与 ZaijianApp.kt
     * 内 registerAgentTools/调度补偿等处的既有异常处理风格保持一致。
     * 装配失败时 competitionEngine/competitionRoundManager 保持原值不变
     * （无论 force 与否都不清空旧实例——force=true 场景下宁可继续用旧
     * Provider 兜底可用，也不要让功能突然变得完全不可用），下次 Provider
     * 配置变更回调触发时会自然重试，不需要额外的重试逻辑。
     *
     * @param force true 表示无条件重新装配（即使已装配过），用于响应
     *   Provider 配置确实发生变化的场景；false 保持原幂等语义，仅用于
     *   App 冷启动首次尝试。
     */
    suspend fun reassembleCompetitionEngine(force: Boolean = false) {
        competitionBuildMutex.withLock {
            if (competitionEngine != null && !force) return@withLock  // 已装配且非强制，幂等跳过
            val activeProvider = ProviderManager.instance.activeProvider ?: return@withLock
            try {
                val specialtyEvolutionEngine = SpecialtyEvolutionEngine(activeProvider)
                val newCompetitionEngine = CompetitionEngine(
                    provider        = activeProvider,
                    evolutionEngine = specialtyEvolutionEngine,
                )
                val newCompetitionRoundManager = CompetitionRoundManager(
                    db = db,
                    competitionEngine = newCompetitionEngine,
                    daughterRepo = daughterCharacterRepo,
                    memoryRepo = memoryRepo,
                    // 阶段2 S-2 遗留补项：改引用容器共享的 specialtyProfileRepo，
                    // 不再在此处重复构造一份参数完全相同的实例（见该字段声明处注释）。
                    specialtyProfileRepository = specialtyProfileRepo,
                    provider = activeProvider,
                )
                competitionEngine = newCompetitionEngine
                competitionRoundManager = newCompetitionRoundManager
                competitionEngineAssemblyFailed = false  // 成功后清除失败标志
                ZLog.d("AppContainer", "竞争引擎装配完成")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 必须重新抛出：结构化并发约定要求取消信号不能被吞掉
                // （与 FertileWindowConsentJudge/UserConsentIntentJudge 同款处理）。
                throw e
            } catch (e: Throwable) {
                ZLog.e("AppContainer", "竞争引擎装配失败，competitionEngine/competitionRoundManager 保持 null", e)
                competitionEngineAssemblyFailed = true
                // 不重新抛出：装配失败不应该导致 App 启动流程或 Provider 配置
                // 变更回调所在的协程崩溃。competitionEngine 保持 null，下次
                // onProviderConfigChanged 触发时会自然重试（重试成功会在上面清除标志）。
            }
        }
    }

    companion object {
        // 不需要 @Volatile / synchronized：唯一的写入点是 ZaijianApp.onCreate()，
        // 单线程、只执行一次。ViewModel 侧只读，读的时候 onCreate() 早已跑完。
        private var _instance: AppContainer? = null

        /** 由 ZaijianApp.onCreate() 调用，仅此一处写入。 */
        fun init(context: Context) {
            if (_instance == null) {
                _instance = AppContainer(context.applicationContext)
                // PresenceEngine 的 companion object 需要 appContext 才能读取
                // 主动消息开关（SharedPreferences）。原先由 ZaijianApp.onCreate()
                // 在构造 presenceEngine 之后单独调用，现随容器初始化一并完成。
                PresenceEngine.init(context.applicationContext)
            }
        }

        /**
         * ViewModel 侧取用。理论上 init() 必然先于任何 ViewModel 构造执行，
         * 这里的 !! 是有意为之——如果真的空了，说明 onCreate() 没跑，
         * 那是比"优雅降级"更需要立刻暴露的启动期 bug，非空断言让它在此处
         * 崩溃并给出清晰堆栈，而不是在后面某个随机调用点因 NPE 崩溃。
         */
        val instance: AppContainer get() = _instance!!

        /**
         * B3审查序号13修复：instance 的降级版。FCM 消息回调等系统入口理论上
         * 可能早于 ZaijianApp.onCreate() 触发（冷启动竞态），这类调用点要的是
         * "取不到就跳过这次"的优雅降级，而非 instance 那种"崩给你看"的设计
         * 意图——两者场景不同，不应该共用同一个 API 然后在外面套 try/catch
         * 硬吞掉本该触发崩溃的启动期 bug。ViewModel 等常规调用点应继续用
         * instance，不要改用这个。
         */
        fun instanceOrNull(): AppContainer? = _instance
    }
}
