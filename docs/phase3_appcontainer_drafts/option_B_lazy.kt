// ============================================================
// 方案 B：独立 by lazy 单例，首次调用 getInstance() 时才构造
// ============================================================

// --- data/AppContainer.kt ---
package com.zaijian.zhoumuyun.data

import android.content.Context
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.repository.*
import com.zaijian.zhoumuyun.domain.*

/**
 * 全局单例依赖容器。
 *
 * 初始化方式：不在 ZaijianApp.onCreate() 里显式触发，而是任何调用方
 * （目前是 ChatViewModel / RoundtableViewModel 各自的构造函数）第一次
 * 调用 AppContainer.getInstance(context) 时才真正构造，之后复用。
 *
 * 需要处理的问题：ChatViewModel 和 RoundtableViewModel 可能在几乎同一时刻
 * 被创建（比如用户从多角色列表快速点进两个不同页面，或者配置变更导致
 * Activity 重建时旧/新 ViewModel 短暂共存），Compose 的 viewModel() 工厂
 * 调用发生在主线程没错，但如果以后有任何后台线程路径也会触碰到
 * AppContainer（比如 WorkManager Worker 里），双重检查锁就是必需的，
 * 不能只用一个裸 var + null 检查。
 */
class AppContainer private constructor(context: Context) {

    private val db = AppDatabase.getInstance(context)

    val eventRepo: EventRepository = EventRepository(db.worldEventDao())
    val memoryRepo: MemoryRepository = MemoryRepository(db.memoryDao(), db.memoryCandidateDao())
    val memoryEngine: MemoryEngine = MemoryEngine(db, memoryRepo, eventRepo)
    val relationshipEngine: RelationshipEngine = RelationshipEngine(
        db, db.relationshipDao(), eventRepo, db.relationshipMilestoneDao()
    )
    val pregnancyRepo: PregnancyRepository = PregnancyRepository(db.pregnancyDao())
    val characterStateRepo: CharacterStateRepository = CharacterStateRepository(db.characterStateDao())
    val pregnancyTriggerManager: PregnancyTriggerManager = PregnancyTriggerManager(
        db                   = db,
        pregnancyRepository  = pregnancyRepo,
        cycleRepository      = MenstrualCycleRepository(db.menstrualCycleDao()),
        stateRepository      = characterStateRepo,
        aiJudge              = FertileWindowConsentJudge(providerFn = { ProviderManager.instance.activeProvider }),
    )

    companion object {
        // 必须 @Volatile：写入可能发生在任意调用方所在的线程（虽然目前
        // 两个 ViewModel 都是主线程构造，但这是"独立于 onCreate 时机"方案
        // 的题中之义——既然不假设固定的初始化窗口，就不能假设固定的写入线程）。
        @Volatile private var _instance: AppContainer? = null

        /**
         * 双重检查锁（double-checked locking）：第一次检查避免每次调用都
         * 进 synchronized 块的性能代价；进入同步块后第二次检查防止两个线程
         * 都通过第一次检查后重复构造（这正是报告里点名要当心的
         * "两个并发调用同时通过 null 检查各自创建实例，后者覆盖前者"场景，
         * 项目里 buildMutex 保护 tryBuildCompetitionEngine() 就是同一类问题
         * 的先例）。
         */
        fun getInstance(context: Context): AppContainer {
            return _instance ?: synchronized(this) {
                _instance ?: AppContainer(context.applicationContext).also { _instance = it }
            }
        }
    }
}


// --- ZaijianApp.kt：不需要改动 onCreate() ---
// （这是方案 B 相对方案 A 的直接好处：onCreate() 完全不用碰，
//  风险面更小，但代价是"何时真正初始化"这件事从"看 onCreate 代码"
//  变成了"看哪个 ViewModel 最先调用 getInstance"，可预测性差一些）


// --- ChatViewModel.kt 改动点 ---
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val messageDao = db.messageDao()

    // 改为从容器取（首次调用即构造）：
    private val container = AppContainer.getInstance(application)
    private val eventRepo get() = container.eventRepo
    private val memoryRepo get() = container.memoryRepo
    private val memoryEngine get() = container.memoryEngine
    private val relationshipEngine get() = container.relationshipEngine
    private val pregnancyRepo get() = container.pregnancyRepo
    private val characterStateRepo get() = container.characterStateRepo
    private val pregnancyTriggerManager get() = container.pregnancyTriggerManager

    private val taskRepo = TaskRepository(db, db.taskDao(), db.worldEventDao())
    private val identityDao = db.characterIdentityDao()
    private val projectRepo = ProjectRepository(db.projectDao(), db.projectKnowledgeDao())
    private val daughterRepo = DaughterCharacterRepository(db.daughterCharacterDao())
    private val agentPlanDao = db.agentPlanDao()
    private val workflowRepo = WorkflowRepository(db, db.workflowJobDao(), db.workflowStepResultDao())
    // ...
}
