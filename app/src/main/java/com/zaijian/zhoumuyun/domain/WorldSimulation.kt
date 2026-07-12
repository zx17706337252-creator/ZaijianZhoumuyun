package com.zaijian.zhoumuyun.domain

import android.content.Context
import android.util.Log
import com.zaijian.zhoumuyun.util.ZLog
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // daughters 覆盖修复：注入 DaughterCharacterDao，Tier 循环将女儿 ID 合并到遍历列表
    private val daughterCharacterDao: com.zaijian.zhoumuyun.data.db.dao.DaughterCharacterDao? = null,
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
    private val tier2Mutex = Mutex()
    private val tier3Mutex = Mutex()

    private val memoryRepo: MemoryRepository? by lazy {
        if (memoryDao != null && candidateDao != null)
            MemoryRepository(memoryDao, candidateDao)
        else null
    }

    // Trust 衰减累积器（每 2h +0.0028，满 1 触发整数 -1）
    private val trustDecayAccumulator = ConcurrentHashMap<Int, Double>()

    /**
     * 每次 Tier tick 时调用，返回 DefaultCharacters + 已注册女儿的合并 ID 列表。
     * daughterCharacterDao 未注入时降级为只返回 DefaultCharacters（向前兼容）。
     */
    private suspend fun allCharacterIds(): List<Int> {
        val defaultIds = DefaultCharacters.map { it.id }
        val daughterIds = daughterCharacterDao?.getAllDaughterCharacterIds() ?: emptyList()
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

        val KEY_LAST_TIER2 = longPreferencesKey("last_tier2_run_at")
        val KEY_LAST_TIER3 = longPreferencesKey("last_tier3_run_at")

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
        runTier1()
        runTier2()
        runTier3()
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
            compensateOffline()
        }

        tier1Job = scope.launch {
            delay(startupDelayMs)
            ZLog.d(TAG, "Tier 1 loop started")
            while (true) {
                try { runTier1() } catch (e: Exception) { ZLog.w(TAG, "Tier1 error", e) }
                delay(TIER1_INTERVAL_MS)
            }
        }

        tier2Job = scope.launch {
            delay(startupDelayMs + 2_000L)
            ZLog.d(TAG, "Tier 2 loop started")
            while (true) {
                try { runTier2() } catch (e: Exception) { ZLog.w(TAG, "Tier2 error", e) }
                saveTimestamp(KEY_LAST_TIER2)
                delay(TIER2_INTERVAL_MS)
            }
        }

        tier3Job = scope.launch {
            delay(startupDelayMs + 5_000L)
            ZLog.d(TAG, "Tier 3 loop started")
            while (true) {
                try { runTier3() } catch (e: Exception) { ZLog.w(TAG, "Tier3 error", e) }
                saveTimestamp(KEY_LAST_TIER3)
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
                    try { runTier2() } catch (e: Exception) { ZLog.w(TAG, "Tier2 compensate error", e) }
                }
                saveTimestamp(KEY_LAST_TIER2)
            }

            val lastTier3 = prefs[KEY_LAST_TIER3] ?: 0L
            val tier3Rounds = ((now - lastTier3) / TIER3_INTERVAL_MS)
                .toInt().coerceIn(0, MAX_OFFLINE_ROUNDS)
            if (tier3Rounds > 0) {
                ZLog.d(TAG, "Offline compensation: Tier3 × $tier3Rounds rounds")
                repeat(tier3Rounds) {
                    try { runTier3() } catch (e: Exception) { ZLog.w(TAG, "Tier3 compensate error", e) }
                }
                saveTimestamp(KEY_LAST_TIER3)
            }
        } catch (e: Exception) {
            ZLog.w(TAG, "Offline compensation failed", e)
        }
    }

    private suspend fun saveTimestamp(key: Preferences.Key<Long>) {
        val ctx = context ?: return
        try {
            ctx.worldSimDataStore.safeEdit { prefs -> prefs[key] = System.currentTimeMillis() }
        } catch (e: Exception) {
            ZLog.w(TAG, "Timestamp save failed for key $key", e)
        }
    }

    // ── Tier 1 · 5 分钟 ──────────────────────────────────────

    private suspend fun runTier1() {
        Log.v(TAG, "Tier1 tick")
        val now = System.currentTimeMillis()
        // Phase 4：48h 窗口内有互动的所有角色 ID（用于嫉妒检测）
        val window48h = now - 48 * 3_600_000L
        val recentCharIds = messageDao?.getRecentCharacterIds(window48h) ?: emptyList()

        val allIds = allCharacterIds()

        allIds.forEach { charId ->
            try {
                val snapshot = presenceEngine.refreshPresence(charId)

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
                    )
                }
                // 若 messageDao 未注入（降级），refreshPresence 内部的原有目标触发逻辑保持不变
            } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
            } catch (e: Exception) {
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

                    val charId = leadMember.characterId.toIntOrNull() ?: return@forEach

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
                    presenceEngine.refreshPresence(charId)

                    ZLog.d(TAG, "Project-driven event for project=${project.title}, char=$charId")
                } catch (e: Exception) {
                    ZLog.w(TAG, "Project-driven behavior failed for project=${project.id}", e)
                }
            }
        } catch (e: Exception) {
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
            } catch (e: Exception) {
                ZLog.w(TAG, "Tier3: memory decay failed", e)
            }
            try {
                val characterIds = allCharacterIds()
                repo.cleanupProcessedCandidates(characterIds)
            } catch (e: Exception) {
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
        } catch (e: Exception) {
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
            } catch (e: Exception) {
                ZLog.w(TAG, "Trust decay accumulation failed for char=$charId", e)
            }
        }

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
                if (Math.random() > CONTAGION_PROB) continue

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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            ZLog.w(TAG, "Tier3: suppression relaxation failed", e)
        }
    }
}
