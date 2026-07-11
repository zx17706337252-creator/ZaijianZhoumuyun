package com.zaijian.zhoumuyun.data

import android.content.Context
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.memory.MemoryEngine
import com.zaijian.zhoumuyun.data.repository.CharacterStateRepository
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.EventRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.domain.RelationshipEngine

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
 * 注意：`pregnancyTriggerManager` 不在这里——`ChatViewModel`/`RoundtableViewModel`
 * 对它的构造参数存在真实的功能性差异（是否传 `aiJudge`，见审计报告 Phase 3
 * 决策 2），维持现状由两个 ViewModel 各自构造，本容器只提供它们都需要的
 * `pregnancyRepo`/`characterStateRepo` 两个共享依赖。
 */
class AppContainer private constructor(context: Context) {

    private val db = AppDatabase.getInstance(context)

    val eventRepo: EventRepository = EventRepository(db.worldEventDao())
    val memoryRepo: MemoryRepository = MemoryRepository(db.memoryDao(), db.memoryCandidateDao())
    val memoryEngine: MemoryEngine = MemoryEngine(db, memoryRepo, eventRepo)

    // 统一为带 milestoneDao 的版本（审计报告 Phase 3 决策 2：
    // 一对一聊天以后也会记录关系里程碑，是一次真实的功能变化）。
    val relationshipEngine: RelationshipEngine = RelationshipEngine(
        db, db.relationshipDao(), eventRepo, db.relationshipMilestoneDao()
    )

    val pregnancyRepo: PregnancyRepository = PregnancyRepository(db.pregnancyDao())
    val characterStateRepo: CharacterStateRepository = CharacterStateRepository(db.characterStateDao())

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
    val presenceEngine: PresenceEngine = PresenceEngine(
        goalDao  = db.characterGoalDao(),
        eventDao = db.worldEventDao(),
    )

    // 报告第6条：CharacterDetailScreen.kt 里查询女儿角色身份的 LaunchedEffect
    // 原先自己 remember { AppDatabase.getInstance(context) } 再手动
    // DaughterCharacterRepository(db.daughterCharacterDao())，是 Composable
    // 直接触达持久化层（审计报告 Phase 1 点名的最严重分层违规）。核查后发现
    // ChatViewModel/RoundtableViewModel 各自也独立持有一份构造参数完全相同的
    // DaughterCharacterRepository（仅 db.daughterCharacterDao()，无差异化配置），
    // 因此并入本容器共享，而不是报告原述"改走 IdentityViewModel"——后者的
    // uiState 是围绕人设编辑表单设计的，语义与"查一次女儿角色 CharacterConfig"
    // 不匹配，硬塞进去会污染已经很复杂的 IdentityUiState。
    // 注意：ChatViewModel.daughterRepo / RoundtableViewModel.daughterCharacterRepo
    // 本次未改为引用此处——那是两个 ViewModel 内部的顺带清理，不在本条改动范围，
    // 留给以后需要时再做，避免本次改动面扩大。
    val daughterCharacterRepo: DaughterCharacterRepository =
        DaughterCharacterRepository(db.daughterCharacterDao())

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
    }
}
