// ============================================================
// 方案 A：跟着 ZaijianApp.onCreate() 走，同步构造，主线程
// ============================================================

// --- data/AppContainer.kt ---
package com.zaijian.zhoumuyun.data

import android.content.Context
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.repository.*
import com.zaijian.zhoumuyun.domain.*

/**
 * 全局单例依赖容器，持有原先在 ChatViewModel / RoundtableViewModel
 * 里各自构造一遍的 Repository / Engine 实例。
 *
 * 初始化方式：由 ZaijianApp.onCreate() 在 db 创建后立即同步构造，
 * 与 sharedPresenceEngine 的初始化时机对齐（同一批主线程操作，
 * 无 IO，构造成本是纯内存操作，符合 onCreate() 里"presenceEngine
 * 构建（纯内存，无 IO）"那条注释描述的同一类操作）。
 *
 * 与 by lazy 方案的关键区别：这里没有"首次访问触发构造"的时机不确定性——
 * ViewModel 访问 AppContainer.instance 时，它一定已经在 onCreate() 里
 * 构造完毕，因为 Application.onCreate() 保证先于任何 Activity/ViewModel
 * 创建执行。不需要用 @Volatile + 双重检查锁，因为不存在"多线程同时首次访问"
 * 的竞态窗口——构造只会发生一次，且发生在单线程的 onCreate() 里。
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
        // 不需要 @Volatile / synchronized：唯一的写入点是 ZaijianApp.onCreate()，
        // 单线程、只执行一次。ViewModel 侧只读，读的时候 onCreate() 早已跑完。
        private var _instance: AppContainer? = null

        /** 由 ZaijianApp.onCreate() 调用，仅此一处写入。 */
        fun init(context: Context) {
            if (_instance == null) {
                _instance = AppContainer(context.applicationContext)
            }
        }

        /**
         * ViewModel 侧取用。理论上 init() 必然先于任何 ViewModel 构造执行，
         * 这里的 !! 是有意为之——如果真的空了，说明 onCreate() 没跑，
         * 那是比"优雅降级"更需要立刻暴露的启动期 bug，非空断言让它在此处
         * 崩溃并给出清晰堆栈，而不是在后面某个随机调用点因 NPE 崩溃。
         */
        val instance: AppContainer get() = _instance!!
    }
}


// --- ZaijianApp.kt 改动点（onCreate() 内，紧跟 db 初始化之后） ---
override fun onCreate() {
    super.onCreate()
    // ...（不变的部分省略）...

    val db = try {
        AppDatabase.getInstance(this)
    } catch (e: Exception) {
        ZLog.e("ZaijianApp", "数据库初始化失败，App 无法启动", e)
        throw e
    }

    // 新增：AppContainer 紧跟 db 之后同步初始化，与 sharedPresenceEngine
    // 属于同一批"主线程、无 IO、纯内存构造"操作
    com.zaijian.zhoumuyun.data.AppContainer.init(this)

    // ...（后续 ProviderManager.init 等不变）...
}


// --- ChatViewModel.kt 改动点 ---
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val messageDao = db.messageDao()
    // 原来各自构造的这几行 ↓ 全部删除：
    // private val eventRepo = EventRepository(db.worldEventDao())
    // private val memoryRepo = MemoryRepository(db.memoryDao(), db.memoryCandidateDao())
    // private val memoryEngine = MemoryEngine(db, memoryRepo, eventRepo)
    // private val relationshipEngine = RelationshipEngine(db, db.relationshipDao(), eventRepo)
    // private val pregnancyRepo = PregnancyRepository(db.pregnancyDao())
    // private val characterStateRepo = CharacterStateRepository(db.characterStateDao())
    // private val pregnancyTriggerManager = PregnancyTriggerManager(...)

    // 改为从容器取：
    private val container = AppContainer.instance
    private val eventRepo get() = container.eventRepo
    private val memoryRepo get() = container.memoryRepo
    private val memoryEngine get() = container.memoryEngine
    private val relationshipEngine get() = container.relationshipEngine
    private val pregnancyRepo get() = container.pregnancyRepo
    private val characterStateRepo get() = container.characterStateRepo
    private val pregnancyTriggerManager get() = container.pregnancyTriggerManager

    // taskRepo/projectRepo/daughterRepo/workflowRepo 不在报告点名的"重复wiring"
    // 清单里（只在 ChatViewModel 出现，RoundtableViewModel 没有对应字段），
    // 暂不挪入 AppContainer，维持原样
    private val taskRepo = TaskRepository(db, db.taskDao(), db.worldEventDao())
    private val identityDao = db.characterIdentityDao()
    private val projectRepo = ProjectRepository(db.projectDao(), db.projectKnowledgeDao())
    private val daughterRepo = DaughterCharacterRepository(db.daughterCharacterDao())
    private val agentPlanDao = db.agentPlanDao()
    private val workflowRepo = WorkflowRepository(db, db.workflowJobDao(), db.workflowStepResultDao())
    // ...
}
