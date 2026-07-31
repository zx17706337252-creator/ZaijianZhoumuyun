package com.zaijian.zhoumuyun.domain

import kotlin.random.Random

import android.content.Context
import android.util.Log
import com.zaijian.zhoumuyun.util.ZLog
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zaijian.zhoumuyun.data.datastore.safeData
import com.zaijian.zhoumuyun.data.datastore.safeEdit
import com.zaijian.zhoumuyun.data.db.dao.CharacterGoalDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryCandidateDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryDao
import com.zaijian.zhoumuyun.data.db.dao.ProjectDao
import com.zaijian.zhoumuyun.data.db.dao.RelationshipDao
import com.zaijian.zhoumuyun.data.db.dao.WorldEventDao
import com.zaijian.zhoumuyun.data.db.entity.EventDomain
import com.zaijian.zhoumuyun.data.db.entity.EventType
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "WorldSimulation"

private val Context.worldSimDataStore: DataStore<Preferences> by preferencesDataStore(name = "world_sim_timestamps")

/**
 * World Simulation — Phase 20（项目驱动 + 离线懒计算补偿 + 关系衰减修正）
 *
 * Phase 20 新增：
 *
 * ① Project 驱动行为（设计方案 §10.3, §19.9）
 *    - Tier 2 扫描活跃项目，为 LEAD 角色产生 PROJECT_UPDATED 事件
 *    - 同步刷新该角色 Presence
 *
 * ② 离线懒计算补偿（设计方案 §G）
 *    - DataStore 记录 lastTier2RunAt / lastTier3RunAt
 *    - App 前台时 compensateOffline() 补算错过的轮次（最多 20 轮）
 *
 * ③ 关系衰减参数修正（设计方案 §C）
 *    - curiosityDecay 从 Tier2(每30min -1) 移至 Tier3(每2h -1)
 *    - Tier3 新增 Trust 累积衰减（每 2h 积累 0.0028，满 1 整数衰减）
 *
 * ④ Energy 微调接入（Phase 20 PresenceEngine.refreshPresence 改为 suspend fun 返回 PresenceSnapshot）
 */
class WorldSimulation(
    private val relationshipDao: RelationshipDao,
    private val goalDao: CharacterGoalDao,
    private val presenceEngine: PresenceEngine,
    private val memoryDao: MemoryDao? = null,
    private val candidateDao: MemoryCandidateDao? = null,
    // Phase 20 新增：Project 驱动
    private val projectDao: ProjectDao? = null,
    private val eventDao: WorldEventDao? = null,
    // Phase 20 新增：离线补偿
    private val context: Context? = null,
    // Phase 4（zaijian）新增：情境感知主动消息
    private val messageDao: MessageRepository? = null,
    // Bugfix：memoryRepo 内部构建 MemoryRepository 需要 MemoryTagDao，
    // 原代码误引用了类作用域内不存在的 db 变量（db.memoryTagDao()），
    // 编译期报"未解析的引用"。改为与其余各 Dao 一致的显式构造参数注入，
    // 不引入 AppDatabase 整体依赖（保持与本文件其他 Dao 参数一致的最小改动面）。
    private val memoryTagDao: com.zaijian.zhoumuyun.data.db.dao.MemoryTagDao? = null,
    // 审查报告问题11修复：daughters 覆盖修复引入本参数时给了可空默认值 null，
    // 全项目排查后确认唯一两个实例化调用方（ZaijianApp.kt 前台常驻实例、
    // ProactiveMessageWorker.kt 后台周期检查实例）一直都在传入非空的
    // db.daughterCharacterDao()，没有任何调用方依赖过 null 默认值。
    // 保留可空默认值只会带来风险：未来任何新调用方（新增的 Worker、测试代码等）
    // 如果忘记传这个参数，Tier 遍历会静默退化为"只处理 DefaultCharacters"，
    // 女儿角色的 Presence/关系衰减/主动消息全部被悄悄跳过，且没有任何日志或
    // 报错提示——与 DaughterCharacterGenerator.onIdentityRegister 从可空改为
    // 必填时的理由完全一致（见该文件 47-54 行注释）：改为必填后，遗漏会在
    // 编译期直接报错，而不是运行期静默漏处理一整类角色。
    private val daughterCharacterDao: com.zaijian.zhoumuyun.data.db.dao.DaughterCharacterDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tier1Job: Job? = null
    private var tier2Job: Job? = null
    private var tier3Job: Job? = null

    // P1-6-2 修复：runTier2/runTier3 原先无互斥保护。
    // 场景一：App 启动时 compensateOfflineRounds() 补跑历史轮次，与定时 Job 几乎同时触发，
    //         两个协程并发执行同一个 Tier（如同时写 applyDecay / applyInterCharacterDecay），
    //         产生读改写竞态，衰减量可能被计算两次或互相覆盖。
    // 场景二：用户在极短时间内前后台切换，stop()/start() 交替调用，Job 被 cancel 后又立刻重启，
    //         上一个还未完成的 Tier 函数与新启动的产生重叠。
    // 修复：各 Tier 独立 Mutex，进入函数体时 withLock 串行化。
    // Tier1 极轻量（仅状态检查），保持现有行为不加锁。
    //
    // W1-009 修复：tier2Mutex/tier3Mutex 原为实例成员变量，前台 ZaijianApp 常驻的
    // WorldSimulation 实例与 ProactiveMessageWorker 每次后台执行时新建的
    // WorldSimulation 实例各自持有不同的 Mutex 对象，互不感知——两者可以同时
    // 执行 Tier2/Tier3，对同一批 relationships 表行产生并发读改写竞态（衰减
    // 覆盖用户增量更新，或反之）。改为 companion object 级别（类似
    // PregnancySettlementWorker.settlementMutex 的模式），使前台常驻实例和
    // 后台每次新建的实例共用同一把锁——只要是同一个 JVM 进程内，无论创建了
    // 多少个 WorldSimulation 实例，Tier2 之间、Tier3 之间都会被串行化。
    //
    // 锁顺序核查：runTier2/runTier3 各自独立加锁，不存在一个函数体内嵌套获取
    // 另一个 Tier 的锁的情况（两者只在 runProactiveCheckForCharacters 里顺序
    // 调用，不会同时持有两把锁），不引入锁顺序反转导致死锁的风险。

    private val memoryRepo: MemoryRepository? by lazy {
        if (memoryDao != null && candidateDao != null && memoryTagDao != null)
            MemoryRepository(memoryDao, candidateDao, memoryTagDao)
        else null
    }

    // S2问题6修复：Tier1 已刷新过的角色 ID，避免 runProjectDrivenBehavior 重复刷新
    private val refreshedInTier1 = mutableSetOf<Int>()

    /**
     * 每次 Tier tick 时调用，返回 DefaultCharacters + 已注册女儿的合并 ID 列表。
     * 审查报告问题11修复：daughterCharacterDao 现为必填参数，不再需要"未注入时
     * 降级"的向前兼容分支——构造函数签名本身已保证调用方一定传入了实例。
     */
    private suspend fun allCharacterIds(): List<Int> {
        val defaultIds = DefaultCharacters.map { it.id }
        val daughterIds = daughterCharacterDao.getAllDaughterCharacterIds()
        return defaultIds + daughterIds
    }

    private var isRunning = false

    // ── DataStore 键 ──────────────────────────────────────────

    companion object {
        const val STARTUP_DELAY_MS   = 10_000L
        const val TIER1_INTERVAL_MS  = 5  * 60 * 1000L
        const val TIER2_INTERVAL_MS  = 30 * 60 * 1000L
        const val TIER3_INTERVAL_MS  = 2  * 60 * 60 * 1000L
        const val GOAL_PROGRESS_TICK = 0.01f
        const val MAX_OFFLINE_ROUNDS = 20

        // 主动消息 JEALOUSY 判定收紧：48h 窗口内某其他角色的消息数达到此门槛，
        // 才计入 recentOtherCharIds（"聊得比较多"才算吃醋诱因，而非聊过一句就算）。
        const val JEALOUSY_MESSAGE_COUNT_THRESHOLD = 3

        // W1-009 修复：tier2Mutex/tier3Mutex 由实例成员改为 companion object 级别，
        // 使所有 WorldSimulation 实例（前台常驻 + 后台 Worker 每次新建）共用同一对
        // 锁，跨实例串行化 Tier2/Tier3 执行。详见上方构造函数后的注释说明。
        private val tier2Mutex = Mutex()
        private val tier3Mutex = Mutex()

        // P2-4 修复：为 runTier1 添加 companion object 级 Mutex，
        // 与 tier2Mutex/tier3Mutex 同级，确保前台 Tier1 循环与后台
        // ProactiveMessageWorker 的 runProactiveCheckForCharacters()
        // 不会并发访问同一角色的 Presence 缓存，避免冗余计算。
        private val tier1Mutex = Mutex()

        // B1审查序号1修复：trustDecayAccumulator 原为实例级 ConcurrentHashMap，
        // 前台常驻 WorldSimulation 实例（ZaijianApp.start()→restoreTrustAccumulator()
        // 载入）与 ProactiveMessageWorker 每次 doWork 新建的实例各持有独立空 Map——
        // Worker 直接调 runProactiveCheckForCharacters()，从不触达 start()/restore，
        // 其 runTier3 恒在空累加器上计算，saveTrustAccumulator() 把 DataStore 整键
        // 覆写成极小值，前台下次冷启动 restore 到的也是这个被钉死的小值，永远到不了
        // 1.0 触发阈值，"每30天-1"的信任衰减在纯后台场景下基本永不生效。改为
        // companion object 级别，与 tier1/2/3Mutex 同级，前台+Worker 共用同一份
        // 内存累加器（ConcurrentHashMap 本身线程安全，多实例并发 compute 不需要
        // 额外加锁；写盘仍在各自的 tier3Mutex.withLock 内串行）。
        private val trustDecayAccumulator = ConcurrentHashMap<Int, Double>()

    // S2问题3修复：Trust 衰减按时间差一次性计算，不受轮次上限影响
    // 30 天离线 ≈ 360 轮 Tier3，若轮次上限为 20 则衰减仅 0.056（实际应 ≈ 1.0）
    // 单次 applyTrustDecayByElapsed 直接基于时间差计算，绕过轮次上限
    const val MAX_OFFLINE_TRUST_DECAY_DAYS = 30

        val KEY_LAST_TIER2 = longPreferencesKey("last_tier2_run_at")
        val KEY_LAST_TIER3 = longPreferencesKey("last_tier3_run_at")

        // 方案 3-7：trust 衰减累积器余数持久化，进程被杀后恢复
        val KEY_TRUST_ACCUMULATOR = stringPreferencesKey("trust_decay_accumulator")

        // Trust 每 30 天 -1 = 每 2h 衰减 1/(30*12) ≈ 0.00278
        const val TRUST_DECAY_PER_TIER3 = 1.0 / (30 * 12)

        // 待办13：suppression 自然松动上限（75，不让心防完全消失）
        const val SUPPRESSION_RELAX_CAP = 75

        // 待办12：情绪感染参数
        /** 每个负向源角色每次 Tier1 tick 的感染概率（20%） */
        const val CONTAGION_PROB = 0.20
        /** 单次 tick 最多感染的目标数量，防止全场一起情绪崩 */
        const val MAX_CONTAGION_TARGETS = 2
    }

    // ── 主动消息检查（由 ProactiveMessageWorker 调用）───────

    suspend fun runProactiveCheckForCharacters() {
        ZLog.d(TAG, "runProactiveCheckForCharacters: start")
        refreshedInTier1.clear() // S2问题6修复：每次检查前清空跟踪集
        runTier1()
        // 问题40修复：saveTimestamp 只应在对应 Tier 成功跑完后才调用。
        // 之前无条件调用，若 runTier2()/runTier3() 异常，catch 吞掉后
        // 仍然照常推进时间戳，等于把"这一轮其实没跑成功"标记成"已完成"，
        // compensateOffline() 下次启动时会因为看到"较新"的时间戳而误判
        // 这段时间已经处理过，永久漏跑该补的轮次。
        val tier2Succeeded = try {
            runTier2()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Tier2 error (runProactiveCheck)", e)
            false
        }
        // 批次1 1-1修复：Worker 路径跑完 Tier2/Tier3 必须保存时间戳，否则回前台
        // compensateOffline() 会把 Worker 已执行的轮次重新算一遍（重复衰减关系维度）。
        // 与前台常驻循环（start() 第232/242行）的 saveTimestamp 范式对齐。
        if (tier2Succeeded) saveTimestamp(KEY_LAST_TIER2)
        val tier3Succeeded = try {
            runTier3()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Tier3 error (runProactiveCheck)", e)
            false
        }
        if (tier3Succeeded) saveTimestamp(KEY_LAST_TIER3)
        ZLog.d(TAG, "runProactiveCheckForCharacters: done")
    }

    // ── 启动 ──────────────────────────────────────────────────

    fun start(startupDelayMs: Long = STARTUP_DELAY_MS) {
        if (isRunning) return
        isRunning = true
        ZLog.d(TAG, "WorldSimulation starting (delay=${startupDelayMs}ms)")

        // Phase 20：冷启动后先补算离线轮次
        scope.launch {
            delay(startupDelayMs)
            restoreTrustAccumulator()  // 方案 3-7：恢复被杀前的 accumulator 余数状态
            compensateOffline()
        }

        tier1Job = scope.launch {
            delay(startupDelayMs)
            ZLog.d(TAG, "Tier 1 loop started")
            while (true) {
                try { runTier1() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { ZLog.w(TAG, "Tier1 error", e) }
                delay(TIER1_INTERVAL_MS)
            }
        }

        tier2Job = scope.launch {
            delay(startupDelayMs + 2_000L)
            ZLog.d(TAG, "Tier 2 loop started")
            while (true) {
                // 问题40修复：saveTimestamp 移入 try 块内、仅成功路径执行，
                // 与 runProactiveCheckForCharacters() 的处理方式保持一致。
                try {
                    runTier2()
                    saveTimestamp(KEY_LAST_TIER2)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.w(TAG, "Tier2 error", e)
                }
                delay(TIER2_INTERVAL_MS)
            }
        }

        tier3Job = scope.launch {
            delay(startupDelayMs + 5_000L)
            ZLog.d(TAG, "Tier 3 loop started")
            while (true) {
                // 问题40修复：同 Tier2，saveTimestamp 仅在成功路径执行。
                try {
                    runTier3()
                    saveTimestamp(KEY_LAST_TIER3)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.w(TAG, "Tier3 error", e)
                }
                delay(TIER3_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        tier1Job?.cancel()
        tier2Job?.cancel()
        tier3Job?.cancel()
        isRunning = false
        ZLog.d(TAG, "WorldSimulation stopped")
    }

    // ── Phase 20：离线补偿 ────────────────────────────────────

    /**
     * App 启动时调用，补算离线期间错过的 Tier2/Tier3 轮次。
     * 最多补算 MAX_OFFLINE_ROUNDS 轮，避免冷启动时过度阻塞。
     *
     * 3.14 补漏（批次 B 收尾）：本文件的 worldSimDataStore 此前一直是裸
     * `.data`/`edit {}` 调用，未接入批次 B 新增的 safeData()/safeEdit()——
     * 严格说不算功能缺陷，因为这里的外层 try-catch 早已兜底了 IOException
     * 及其他异常，不会崩溃；但为了和 data/datastore/ 目录下四个文件保持
     * 统一风格，顺手迁移过来。外层 try-catch 保留不变（拦截范围比
     * safeData()/safeEdit() 内部只拦 IOException 更宽，两层叠加不会有
     * 行为退化）。
     */
    private suspend fun compensateOffline() {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        try {
            val prefs = ctx.worldSimDataStore.safeData().first()

            val lastTier2 = prefs[KEY_LAST_TIER2] ?: 0L
            val tier2Rounds = ((now - lastTier2) / TIER2_INTERVAL_MS)
                .toInt().coerceIn(0, MAX_OFFLINE_ROUNDS)
            if (tier2Rounds > 0) {
                ZLog.d(TAG, "Offline compensation: Tier2 × $tier2Rounds rounds")
                repeat(tier2Rounds) {
                    try { runTier2() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { ZLog.w(TAG, "Tier2 compensate error", e) }
                }
                // 方案 3-9：不再保存时间戳，由 tier2Job 常规循环负责（delay=12s，补偿完成后 2s 内首次执行）
            }

            val lastTier3 = prefs[KEY_LAST_TIER3] ?: 0L
            // 批次1 1-3修复：首次启动/DataStore 清空时 lastTier3 默认 0L，elapsedTier3Ms
            // 会算出巨大值（从 1970 年到现在），被 cap 到 30 天上限导致 trust 一次性扣 30
            // 点（50→20）。lastTier3==0L 视为"无历史时间戳"（首次启动或 DataStore 被清空），
            // 跳过离线补偿，直接把 KEY_LAST_TIER3 设为 now，后续按正常节奏走。
            if (lastTier3 == 0L) {
                ZLog.d(TAG, "Offline compensation: lastTier3==0L, first launch or DataStore cleared, skip Tier3 compensate")
                saveTimestamp(KEY_LAST_TIER3)
            } else {
                val elapsedTier3Ms = now - lastTier3
                val rawTier3Rounds = (elapsedTier3Ms / TIER3_INTERVAL_MS).toInt()
                val tier3Rounds = rawTier3Rounds.coerceIn(0, MAX_OFFLINE_ROUNDS)
                if (tier3Rounds > 0) {
                    ZLog.d(TAG, "Offline compensation: Tier3 × $tier3Rounds rounds")
                    // 批次1 1-2修复：移除 applyTrustDecayByElapsed，只保留 repeat(runTier3())。
                    // 原代码两者并存导致同一段离线时间的 trust 衰减被双重计算——
                    // applyTrustDecayByElapsed 按时间差一次性给累加器加 cappedDecay，
                    // repeat(runTier3()) 每轮又给累加器加 TRUST_DECAY_PER_TIER3，合计≈2倍。
                    // runTier3() 内部第594-627行已完整处理 trust 累积衰减，且还含 curiosity
                    // 衰减、suppression 松动等逻辑，保留它即可覆盖 MAX_OFFLINE_ROUNDS 以内的补偿需求。
                    repeat(tier3Rounds) {
                        try { runTier3() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { ZLog.w(TAG, "Tier3 compensate error", e) }
                    }
                }
                // P1-29 修复（验收后重修）：rawTier3Rounds 可能远超 MAX_OFFLINE_ROUNDS，上面
                // repeat 只补了 MAX_OFFLINE_ROUNDS 轮。curiosity/suppression 等逐轮效应超出
                // 上限的部分按设计本就不做长尾补偿（避免冷启动阻塞），但 trust 是线性累积量，
                // 长期离线不做补偿会导致离线越久、trust 衰减占比越失真（3天少44%，30天少94%）。
                // 这里单独对被截断的轮次（rawTier3Rounds - tier3Rounds）按 TRUST_DECAY_PER_TIER3
                // 补进 trustDecayAccumulator，且总补偿天数受 MAX_OFFLINE_TRUST_DECAY_DAYS 上限
                // 保护，不放大到无限。只累加到累加器，不直接调用 applyTrustDecayForCharacter，
                // 避免和上面 repeat(runTier3()) 已计入的 tier3Rounds 部分重复计算（历史
                // 1-2修复要规避的正是这个双重计算问题）。
                val overflowRounds = (rawTier3Rounds - tier3Rounds).coerceAtLeast(0)
                if (overflowRounds > 0) {
                    val maxCompensatedRounds = MAX_OFFLINE_TRUST_DECAY_DAYS *
                        (24 * 60 * 60 * 1000L / TIER3_INTERVAL_MS).toInt()
                    val boundedOverflowRounds = overflowRounds
                        .coerceAtMost((maxCompensatedRounds - tier3Rounds).coerceAtLeast(0))
                    if (boundedOverflowRounds > 0) {
                        val supplementalDecay = boundedOverflowRounds * TRUST_DECAY_PER_TIER3
                        ZLog.d(
                            TAG,
                            "Offline compensation: Tier3 overflow rounds=$overflowRounds " +
                                "(bounded=$boundedOverflowRounds), supplemental trust decay=$supplementalDecay"
                        )
                        DefaultCharacters.forEach { char ->
                            try {
                                trustDecayAccumulator.compute(char.id) { _, v ->
                                    (v ?: 0.0) + supplementalDecay
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                ZLog.w(TAG, "Trust overflow compensate failed for char=${char.id}", e)
                            }
                        }
                        saveTrustAccumulator()
                    }
                }
                // 方案 3-9：不再保存时间戳，由 tier3Job 常规循环负责
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Offline compensation failed", e)
        }
    }

    private suspend fun saveTimestamp(key: Preferences.Key<Long>) {
        val ctx = context ?: return
        try {
            ctx.worldSimDataStore.safeEdit { prefs -> prefs[key] = System.currentTimeMillis() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Timestamp save failed for key $key", e)
        }
    }

    // ── 方案 3-7：trust 衰减累积器持久化 ──────────────────────

    private suspend fun saveTrustAccumulator() {
        val ctx = context ?: return
        try {
            val json = JSONObject()
            trustDecayAccumulator.forEach { (charId, acc) ->
                json.put(charId.toString(), acc)
            }
            ctx.worldSimDataStore.safeEdit { prefs ->
                prefs[KEY_TRUST_ACCUMULATOR] = json.toString()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Trust accumulator save failed", e)
        }
    }

    private suspend fun restoreTrustAccumulator() {
        val ctx = context ?: return
        try {
            val prefs = ctx.worldSimDataStore.safeData().first()
            val jsonStr = prefs[KEY_TRUST_ACCUMULATOR] ?: return
            val json = JSONObject(jsonStr)
            json.keys().forEach { key ->
                trustDecayAccumulator[key.toInt()] = json.getDouble(key)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Trust accumulator restore failed", e)
        }
    }

    // ── Tier 1 · 5 分钟 ──────────────────────────────────────

    private suspend fun runTier1() = tier1Mutex.withLock {
        Log.v(TAG, "Tier1 tick")
        val now = System.currentTimeMillis()
        // Phase 4：48h 窗口内有互动的角色 ID（用于嫉妒检测）。
        // 收紧判定：原先只要窗口内有过一条消息就计入，导致触发过泛
        // （用户只跟某角色聊一句，所有其他角色的 Tier1 都会判定"吃醋"）。
        // 改为按消息数量门槛过滤，只有"聊得比较多"的角色才计入。
        val window48h = now - 48 * 3_600_000L
        val recentCharIds = messageDao
            ?.getRecentCharacterMessageCounts(window48h)
            ?.filter { it.messageCount >= JEALOUSY_MESSAGE_COUNT_THRESHOLD }
            ?.map { it.characterId }
            ?: emptyList()

        val allIds = allCharacterIds()

        allIds.forEach { charId ->
            try {
                val snapshot = presenceEngine.refreshPresence(charId)
                refreshedInTier1.add(charId) // S2问题6修复：记录已刷新，避免后续重复

                // Phase 4：情境感知主动消息（替代原有简单目标进展触发）
                if (messageDao != null) {
                    val lastMsgAt   = messageDao.getLastMessageAt(charId) ?: 0L
                    val elapsedMs   = now - lastMsgAt
                    val rel = relationshipDao.get("user", charId.toString())
                    val otherCharIds = recentCharIds.filter { it != charId }
                    presenceEngine.tryEmitContextualProactiveMessage(
                        characterId        = charId,
                        // 故意传 null（非遗漏）：refreshPresence()（本函数上一行）内部的
                        // Phase 20 §H 逻辑已经在 newEnergy>60 && topGoal!=null 时调用过
                        // 同一个 buildProactiveMessage 并 emitProactiveMessage 了。
                        // 若此处再传真实 goalTitle，NORMAL 分支会对同一目标触发第二次，
                        // 唯一挡住重复发送的是节流窗口（PROACTIVE_THROTTLE_MS）——
                        // 也就是说会产生一次注定返回 null 的多余判断，而不是真正的双重发送。
                        // 传 null 让 NORMAL 分支在 mode 选择阶段直接短路，逻辑更干净。
                        // 详见 PresenceEngine.tryEmitContextualProactiveMessage() 的 NORMAL 分支注释。
                        goalTitle          = null,
                        mood               = snapshot.mood,
                        elapsedMs          = elapsedMs,
                        recentOtherCharIds = otherCharIds,
                        relAffection       = rel?.affection  ?: 50,
                        relTrust           = rel?.trust      ?: 50,
                        relDependence      = rel?.dependence ?: 50,
                        lastMsgAt          = lastMsgAt,
                    )
                }
                // 若 messageDao 未注入（降级），refreshPresence 内部的原有目标触发逻辑保持不变
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w(TAG, "Presence refresh failed for char $charId", e)
            }
        }

        // 待办12：情绪感染（圆桌场景 mood 互相影响）
        runMoodContagion(allIds)
    }

    // ── Tier 2 · 30 分钟 ─────────────────────────────────────

    private suspend fun runTier2() = tier2Mutex.withLock {
        Log.v(TAG, "Tier2 tick")
        val now = System.currentTimeMillis()
        val decayBefore = now - TIER2_INTERVAL_MS * 2

        // ① 关系衰减修正（Phase 20 §C）：curiosityDecay 移至 Tier3，此处仅 conflict
        try {
            relationshipDao.applyDecay(
                curiosityDecay = 0,  // 修正：Tier2 不再衰减 curiosity（改为 Tier3 每2h -1）
                affectionDecay = 0,  // 维持：防止突然变冷
                conflictDecay  = 1,  // 冲突每 30 分钟自然消解 -1
                decayBefore    = decayBefore,
                now            = now,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Relationship decay failed", e)
        }

        // ①​ B-6 修复：jalousyInterval/tension 定期衰减，防止只增不减逼近上限
        try {
            relationshipDao.applyInterCharacterDecay(
                decay       = 2,
                tensionDecay = 1,
                decayBefore = decayBefore,
                now         = now,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "InterCharacter decay failed", e)
        }

        // ② Goal 进度推进
        allCharacterIds().forEach { charId ->
            try {
                val topGoal = goalDao.getTopGoal(charId) ?: return@forEach
                if (topGoal.progress < 1f) {
                    val newProgress = (topGoal.progress + GOAL_PROGRESS_TICK).coerceAtMost(1f)
                    goalDao.updateProgress(topGoal.id, newProgress)
                    if (newProgress >= 1f) {
                        goalDao.deactivate(topGoal.id)
                        ZLog.d(TAG, "Goal completed: ${topGoal.title} for char $charId")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w(TAG, "Goal progress update failed for char $charId", e)
            }
        }

        // ③ Phase 20 §B：Project 驱动行为
        runProjectDrivenBehavior(now)
    }

    /**
     * 活跃项目驱动角色行为（设计方案 §10.3, §19.9）。
     *
     * 扫描所有 ACTIVE 项目，找到 LEAD 角色，产生 PROJECT_UPDATED 事件，
     * 并刷新该角色 Presence（activity 将体现项目内容）。
     */
    private suspend fun runProjectDrivenBehavior(now: Long) {
        val dao = projectDao ?: return
        val evtDao = eventDao ?: return
        try {
            // 获取活跃项目列表（suspend 版本，非 Flow）
            val activeProjects = dao.getActiveProjectsList()
            if (activeProjects.isEmpty()) return

            ZLog.d(TAG, "Project-driven behavior: ${activeProjects.size} active projects")

            activeProjects.forEach { project ->
                try {
                    // 找到 LEAD 角色
                    val members  = dao.getMembers(project.id)
                    val leadMember = members.firstOrNull { it.role == "LEAD" }
                        ?: members.firstOrNull()
                        ?: return@forEach

                    val charId = leadMember.characterId

                    // 找下一个未完成里程碑
                    val milestones     = dao.getMilestones(project.id)
                    val nextMilestone  = milestones.firstOrNull { !it.isCompleted }

                    val payload = JSONObject().apply {
                        put("projectId", project.id)
                        put("projectTitle", project.title)
                        put("action", "整理进度")
                        put("milestone", nextMilestone?.title ?: "规划下一步")
                        put("leadCharacterId", charId)
                    }.toString()

                    // 写入 PROJECT_UPDATED 事件
                    evtDao.append(
                        WorldEventEntity(
                            id         = UUID.randomUUID().toString(),
                            type       = EventType.PROJECT_UPDATED.name,
                            actorId    = charId.toString(),
                            targetId   = null,
                            domain     = EventDomain.WORK.name,
                            projectId  = project.id,
                            payload    = payload,
                            importance = 3,
                            createdAt  = now,
                        )
                    )

                    // 刷新 LEAD 角色 Presence（activity 体现项目状态）
                    // S2问题6修复：若该角色已在 runTier1 中被刷新过，跳过
                    if (charId !in refreshedInTier1) {
                        presenceEngine.refreshPresence(charId)
                    }

                    ZLog.d(TAG, "Project-driven event for project=${project.title}, char=$charId")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.w(TAG, "Project-driven behavior failed for project=${project.id}", e)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "runProjectDrivenBehavior failed", e)
        }
    }

    // ── Tier 3 · 2 小时 ──────────────────────────────────────

    private suspend fun runTier3() = tier3Mutex.withLock {
        Log.v(TAG, "Tier3 tick")
        val now = System.currentTimeMillis()
        val decayBefore = now - TIER3_INTERVAL_MS * 2

        // ① 记忆衰减
        memoryRepo?.let { repo ->
            try {
                val remaining = repo.applyDecayAll()
                ZLog.d(TAG, "Tier3: memory decay done, remaining=$remaining")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w(TAG, "Tier3: memory decay failed", e)
            }
            try {
                val characterIds = allCharacterIds()
                repo.cleanupProcessedCandidates(characterIds)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w(TAG, "Tier3: candidate cleanup failed", e)
            }
        }

        // ② Phase 20 §C：Curiosity 衰减（从 Tier2 移来，每 2h -1 更合理）
        try {
            relationshipDao.applyDecay(
                curiosityDecay = 1,   // 每 2h -1（设计方案每 14 天 -2，约合每 2h -0.012，此处取整）
                affectionDecay = 0,
                conflictDecay  = 0,   // Tier2 已处理
                decayBefore    = decayBefore,
                now            = now,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Tier3: curiosity decay failed", e)
        }

        // ③ Phase 20 §C：Trust 累积衰减（每 30 天 -1 → 每 2h -0.0028）
        // 设计：女儿角色（id ≥ 1000）不参与 Trust 自然衰减，只有母亲角色衰减。
        // （2026-07-12 用户明确确认：第二代、第三代女儿均不参与此衰减，
        //  这是真实业务设计意图，不是需要修复的缺陷——此前一度误判为历史遗留
        //  不一致并改用 allCharacterIds()，现按用户澄清撤销，改回原状。）
        DefaultCharacters.forEach { char ->
            val charId = char.id
            try {
                // 原子读改写：compute 保证"读旧值 + 累加 delta + 写回"在同一步原子完成，
                // 替代原 getOrDefault + 下标赋值的非原子模式（并发补算/重入时会丢更新）。
                val acc = trustDecayAccumulator.compute(charId) { _, v ->
                    (v ?: 0.0) + TRUST_DECAY_PER_TIER3
                } ?: 0.0
                if (acc >= 1.0) {
                    val intDecay = acc.toInt()
                    // M1 修复：改用按角色精确衰减，避免原 applyDecay（全局 UPDATE）
                    // 在 forEach 循环里对每个角色触发时重复衰减所有关系的 trust。
                    relationshipDao.applyTrustDecayForCharacter(
                        characterId = charId.toString(),
                        trustDecay  = intDecay,
                        now         = now,
                    )
                    ZLog.d(TAG, "Trust decay trigger: char=$charId decay=$intDecay")
                    // 原子扣减已落库的整数衰减量，保留余数继续累积
                    trustDecayAccumulator.compute(charId) { _, v ->
                        ((v ?: acc) - intDecay).coerceAtLeast(0.0)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w(TAG, "Trust decay accumulation failed for char=$charId", e)
            }
        }
        // 批次1 1-4修复：每次 Tier3 tick（无论是否触发整数衰减）都持久化累加器余数。
        // 原代码只在 acc>=1.0 触发整数衰减时才 saveTrustAccumulator()，用户日常
        // "每天杀 App"会导致累加器中的小数进度反复丢失（trust 要连续跑 30 天=360 次
        // tick 才触发一次保存），实际衰减速率低于设计预期。无条件保存确保进程被杀
        // 后余数可恢复。saveTrustAccumulator() 内部已有 try-catch 兜底，频繁写
        // DataStore 不会导致崩溃。
        saveTrustAccumulator()

        // ④ 待办13：suppression 自然松动（设计方案 §体验补全）
        //
        //    关系阶段达到 IMPORTANT 或 CORE 后，心防（suppression）每 2h +1，
        //    上限 75（刻意不到 100，避免心防完全消失成为默认状态）。
        //    只对 user→角色方向生效；角色间关系无 suppression 字段。
        runSuppressionRelaxation(now)
    }

    /**
     * 待办12：情绪感染（mood contagion）。
     *
     * 规则：
     *  - 仅当缓存中存在 ≥2 个角色的 Presence 时生效（圆桌场景才有意义）。
     *  - 扫描所有已缓存角色，收集"负向强情绪"来源：CONCERNED 或 TIRED。
     *  - 每个负向源角色，以 CONTAGION_PROB（20%）概率，随机选一个其他
     *    角色，将其 mood 向负方向降级一档（EXCITED→SATISFIED→CALM→REFLECTIVE→TIRED）。
     *  - 不直接覆盖 CONCERNED/TIRED，防止恶性循环叠加；不写 DB，只改内存缓存。
     *  - 单次 Tier1 tick 最多感染 MAX_CONTAGION_TARGETS 个目标，防止全场一起崩。
     */
    private fun runMoodContagion(activeIds: List<Int>) {
        try {
            // 取所有有缓存的角色
            val cached = activeIds.mapNotNull { id ->
                presenceEngine.getCachedPresence(id)?.let { snap -> id to snap }
            }
            if (cached.size < 2) return   // 只有1人，感染无意义

            val negativeSources = cached.filter { (_, snap) ->
                snap.mood == MoodType.CONCERNED || snap.mood == MoodType.TIRED
            }
            if (negativeSources.isEmpty()) return

            var contagionCount = 0
            for ((sourceId, _) in negativeSources) {
                if (contagionCount >= MAX_CONTAGION_TARGETS) break

                // 随机命中？
                if (Random.nextDouble() > CONTAGION_PROB) continue

                // 选一个非自身的随机目标
                val candidates = cached.filter { (id, snap) ->
                    id != sourceId &&
                    snap.mood != MoodType.CONCERNED &&
                    snap.mood != MoodType.TIRED
                }
                val (targetId, targetSnap) = candidates.randomOrNull() ?: continue

                val degraded = degradeMood(targetSnap.mood)
                presenceEngine.setCachedPresence(targetId, targetSnap.copy(mood = degraded))
                ZLog.d(TAG, "MoodContagion: char=$targetId mood ${targetSnap.mood}→$degraded (source=$sourceId)")
                contagionCount++
            }
        } catch (e: Throwable) {
            ZLog.w(TAG, "MoodContagion failed", e)
        }
    }

    /**
     * 将 mood 降级一档（向负向靠拢）。
     * EXCITED → SATISFIED → CALM → REFLECTIVE → TIRED（封顶，不进 CONCERNED）
     */
    private fun degradeMood(mood: MoodType): MoodType = when (mood) {
        MoodType.EXCITED    -> MoodType.SATISFIED
        MoodType.SATISFIED  -> MoodType.CALM
        MoodType.FOCUSED    -> MoodType.CALM
        MoodType.CURIOUS    -> MoodType.REFLECTIVE
        MoodType.CALM       -> MoodType.REFLECTIVE
        MoodType.REFLECTIVE -> MoodType.TIRED
        MoodType.TIRED,
        MoodType.CONCERNED  -> mood   // 已在底部，不再降
    }

    /**
     * 待办13：suppression 自然松动。
     *
     * 条件：user→角色，stage = IMPORTANT 或 CORE，suppression < 75。
     * 效果：每轮 +1，上限 75。
     * 节奏：Tier3（2h）——与 trust 衰减同频，"关系越深、心防越慢慢放松"。
     */
    private suspend fun runSuppressionRelaxation(now: Long) {
        // 方案 5-13：withTimeout(6_000) 保护，防止 LLM 调用在该低频后台任务中
        // 无限挂起（例如网络不畅时），避免 tier2Loop 被永久阻塞。
        try {
            withTimeout(6_000L) {
                runSuppressionRelaxationInternal(now)
            }
        } catch (_: TimeoutCancellationException) {
            ZLog.w(TAG, "runSuppressionRelaxation 超时，已跳过本轮")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "runSuppressionRelaxation 异常", e)
        }
    }

    private suspend fun runSuppressionRelaxationInternal(now: Long) {
        try {
            val userRelationships = relationshipDao.observeFrom("user")
                .first()
                .filter { rel ->
                    !rel.isInterCharacter &&
                    rel.stage in listOf(
                        com.zaijian.zhoumuyun.data.db.entity.RelationshipStage.IMPORTANT.name,
                        com.zaijian.zhoumuyun.data.db.entity.RelationshipStage.CORE.name,
                    ) &&
                    rel.suppression < SUPPRESSION_RELAX_CAP
                }

            userRelationships.forEach { rel ->
                // P-4 修复：原先 read-modify-write（读 rel.suppression + 1，再 updateSuppression 全量写）
                // 与 RelationshipEngine.applyDelta（在 deltaMutex 内）并发时会丢失更新。
                // 改为 incrementSuppression 增量 SQL（MIN(:cap, suppression + :delta)），
                // 单条 SQL 原子执行，无需在此处读取当前值，彻底消除竞态。
                relationshipDao.incrementSuppression(
                    fromId = rel.fromId,
                    toId   = rel.toId,
                    delta  = 1,
                    cap    = SUPPRESSION_RELAX_CAP,
                    now    = now,
                )
                Log.v(TAG, "Suppression relaxed: char=${rel.toId} +1 (cap=$SUPPRESSION_RELAX_CAP)")
            }

            if (userRelationships.isNotEmpty()) {
                ZLog.d(TAG, "Tier3: suppression relaxation applied to ${userRelationships.size} relationships")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Tier3: suppression relaxation failed", e)
        }
    }
}
