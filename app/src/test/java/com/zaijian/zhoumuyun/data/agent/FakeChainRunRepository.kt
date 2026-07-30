package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunStatus
import com.zaijian.zhoumuyun.data.db.entity.PendingEventEntity
import com.zaijian.zhoumuyun.data.repository.ChainRunRepository
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * 灵活自动化编排 · 内存态 Fake 仓库（§12.5.1(b) 验收要求）
 *
 * 实现 [ChainRunRepository] 同一接口，内部用 [ConcurrentHashMap] 存
 * [ChainRunEntity] / [ChainDefinitionEntity]，不落盘、不依赖 Room、不依赖 Android。
 *
 * 用途：把 [ChainEngine] 接上这个 Fake 仓库跑纯 JVM 单测，不需要 Robolectric、
 * 不需要 instrumented test。
 *
 * **线程安全**：[claimRun] 使用 synchronized 保证原子性条件检查——模拟 Room 的
 * 条件 UPDATE 语义（`WHERE lockedUntil IS NULL OR lockedUntil <= :claimNow`）。
 * 其余方法也通过 synchronized 保证读写一致性，使 §12.5.1(c) 并发认领锁测试
 * 可以用协程模拟竞态。
 *
 * **注意**：这只验证了 claimRun() 的条件更新逻辑本身正确，不代表验证了
 * Android 跨进程真实竞态（App 被杀+WorkManager 独立进程唤醒）的时序行为。
 */
class FakeChainRunRepository : ChainRunRepository {

    private val runs = ConcurrentHashMap<String, ChainRunEntity>()
    private val definitions = ConcurrentHashMap<String, ChainDefinitionEntity>()
    private val pendingEvents = ConcurrentHashMap<String, PendingEventEntity>()
    private val lock = Any()

    // ── 读取 ──────────────────────────────────────────────

    override suspend fun findById(runId: String): ChainRunEntity? =
        runs[runId]

    override suspend fun findDefinition(chainDefId: String): ChainDefinitionEntity? =
        definitions[chainDefId]

    // ── §11.2 数据库级认领锁 ────────────────────────────────

    /**
     * 模拟 Room 的条件 UPDATE：`WHERE lockedUntil IS NULL OR lockedUntil <= :claimNow`。
     *
     * synchronized 保证"检查 + 设置"是原子操作，使并发调用时只有一个返回 1。
     */
    override suspend fun claimRun(runId: String, claimNow: Long, lockExpiry: Long): Int {
        synchronized(lock) {
            val run = runs[runId] ?: return 0
            val lockedUntil = run.lockedUntil
            val canClaim = lockedUntil == null || lockedUntil <= claimNow
            if (!canClaim) return 0
            runs[runId] = run.copy(lockedUntil = lockExpiry)
            return 1
        }
    }

    override suspend fun releaseLock(runId: String) {
        synchronized(lock) {
            val run = runs[runId] ?: return
            runs[runId] = run.copy(lockedUntil = null)
        }
    }

    // ── §11.7 原子推进 ──────────────────────────────────────

    override suspend fun advanceAtomic(runId: String, newContext: String, newNodeIndex: Int) {
        synchronized(lock) {
            val run = runs[runId] ?: return
            runs[runId] = run.copy(
                context = newContext,
                currentNodeIndex = newNodeIndex,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    // ── §11.6 推进计数 ──────────────────────────────────────

    override suspend fun incrementVisitCount(runId: String) {
        synchronized(lock) {
            val run = runs[runId] ?: return
            runs[runId] = run.copy(
                visitCount = run.visitCount + 1,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    // ── 状态流转 ──────────────────────────────────────────

    override suspend fun markWaiting(runId: String, wakeAtMs: Long) {
        synchronized(lock) {
            val run = runs[runId] ?: return
            runs[runId] = run.copy(
                status = ChainRunStatus.WAITING,
                wakeAtMs = wakeAtMs,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun markRunning(runId: String) {
        synchronized(lock) {
            val run = runs[runId] ?: return
            runs[runId] = run.copy(
                status = ChainRunStatus.RUNNING,
                wakeAtMs = null,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun markCompleted(runId: String, outcome: String) {
        synchronized(lock) {
            val run = runs[runId] ?: return
            runs[runId] = run.copy(
                status = outcome,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun markFailed(runId: String, reason: String) {
        synchronized(lock) {
            val run = runs[runId] ?: return
            // 模拟 ChainRunRepositoryImpl.markFailed：将失败原因写入 context._failReason
            val ctx = try {
                JSONObject(run.context)
            } catch (e: Exception) {
                JSONObject()
            }
            ctx.put("_failReason", reason)
            runs[runId] = run.copy(
                status = ChainRunStatus.FAILED,
                context = ctx.toString(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    // ── §11.3 开机恢复 ──────────────────────────────────────

    override suspend fun findAllByStatus(status: String): List<ChainRunEntity> =
        runs.values.filter { it.status == status }

    // ── §11.10 未播报机制 ────────────────────────────────────

    override suspend fun findUnreported(characterId: Int): List<ChainRunEntity> =
        runs.values.filter {
            (it.characterId == characterId || it.characterId == -1) &&
                !it.isReported &&
                it.status != ChainRunStatus.RUNNING &&
                it.status != ChainRunStatus.WAITING
        }

    override suspend fun markReported(runId: String) {
        synchronized(lock) {
            val run = runs[runId] ?: return
            runs[runId] = run.copy(isReported = true)
        }
    }

    // ── §6 ChainTriggerMatcher：按事件名查询匹配的链条定义 ──────

    override suspend fun findDefinitionsByTriggerEvent(eventName: String): List<ChainDefinitionEntity> =
        definitions.values.filter {
            it.triggerEventName == eventName && it.enabled
        }

    // ── §11.1 待处理事件持久化 ────────────────────────────

    override suspend fun insertPendingEvent(event: PendingEventEntity) {
        pendingEvents[event.id] = event
    }

    override suspend fun findUnprocessedPendingEvents(): List<PendingEventEntity> =
        pendingEvents.values
            .filter { !it.processed }
            .sortedBy { it.createdAt }

    override suspend fun markPendingEventProcessed(id: String) {
        val event = pendingEvents[id] ?: return
        pendingEvents[id] = event.copy(processed = true)
    }

    // ── 写入 ──────────────────────────────────────────────

    override suspend fun insertRun(run: ChainRunEntity) {
        runs[run.id] = run
    }

    override suspend fun insertDefinition(def: ChainDefinitionEntity) {
        definitions[def.id] = def
    }

    // ── 测试辅助方法 ──────────────────────────────────────

    /** 测试用：直接读取当前状态（绕过 suspend 接口） */
    fun peekRun(runId: String): ChainRunEntity? = runs[runId]

    /** 测试用：直接写入（绕过 suspend 接口，用于 setup） */
    fun directInsertRun(run: ChainRunEntity) {
        runs[run.id] = run
    }

    /** 测试用：直接写入定义（绕过 suspend 接口，用于 setup） */
    fun directInsertDefinition(def: ChainDefinitionEntity) {
        definitions[def.id] = def
    }

    /** 测试用：清空所有数据 */
    fun clear() {
        runs.clear()
        definitions.clear()
        pendingEvents.clear()
    }
}
