package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity
import com.zaijian.zhoumuyun.data.db.entity.PendingEventEntity

/**
 * 灵活自动化编排 · 链条运行仓库接口
 *
 * 对应《灵活自动化编排·改造设计方案》§5 ChainEngine.advance() 所需的数据访问层抽象。
 * 将 ChainRunDao / ChainDefinitionDao 的操作封装为一个接口，使 ChainEngine 不直接
 * 依赖 Room DAO，便于用 [FakeChainRunRepository]（内存态 HashMap 实现）做纯 JVM 单测
 * （§12.5.1(b) 要求"不需要 Robolectric、不需要 instrumented test"）。
 *
 * 方法语义对照：
 * - [findById] / [findDefinition]：读取运行实例 / 链条定义
 * - [claimRun] / [releaseLock]：§11.2 数据库级认领锁
 * - [advanceAtomic]：§11.7 原子推进（context + currentNodeIndex 单条 UPDATE）
 * - [incrementVisitCount]：§11.6 推进计数
 * - [markWaiting] / [markRunning]：Wait 节点状态流转
 * - [markCompleted] / [markFailed]：终态写入
 * - [findUnreported] / [markReported]：§11.10 未播报机制
 * - [findAllByStatus]：§11.3 开机恢复查询
 */
interface ChainRunRepository {

    // ── 读取 ──────────────────────────────────────────────

    suspend fun findById(runId: String): ChainRunEntity?

    suspend fun findDefinition(chainDefId: String): ChainDefinitionEntity?

    // ── §11.2 数据库级认领锁 ────────────────────────────────

    /**
     * 条件 UPDATE：lockedUntil IS NULL OR lockedUntil <= claimNow 时才能认领。
     * @return 1=认领成功，0=已被其他执行体锁定
     */
    suspend fun claimRun(runId: String, claimNow: Long, lockExpiry: Long): Int

    suspend fun releaseLock(runId: String)

    // ── §11.7 原子推进 ──────────────────────────────────────

    /**
     * 单条 UPDATE 同时写入 context + currentNodeIndex + updatedAt。
     * 不分两次 UPDATE，防止进程被杀在两次调用之间导致数据不一致。
     */
    suspend fun advanceAtomic(runId: String, newContext: String, newNodeIndex: Int)

    // ── §11.6 推进计数 ──────────────────────────────────────

    suspend fun incrementVisitCount(runId: String)

    // ── 状态流转 ──────────────────────────────────────────

    /** Wait 节点：设为 WAITING 并记录唤醒时间 */
    suspend fun markWaiting(runId: String, wakeAtMs: Long)

    /** 从 WAITING 恢复为 RUNNING（ChainResumeWorker 唤醒后调用） */
    suspend fun markRunning(runId: String)

    /** 终态写入：COMPLETED / CANCELLED（End 节点 outcome） */
    suspend fun markCompleted(runId: String, outcome: String)

    /** 终态写入：FAILED + 失败原因 */
    suspend fun markFailed(runId: String, reason: String)

    // ── §11.3 开机恢复 ──────────────────────────────────────

    suspend fun findAllByStatus(status: String): List<ChainRunEntity>

    // ── §11.10 未播报机制 ────────────────────────────────────

    suspend fun findUnreported(characterId: Int): List<ChainRunEntity>

    suspend fun markReported(runId: String)

    // ── §6 ChainTriggerMatcher：按事件名查询匹配的链条定义 ──────

    /**
     * 查所有 triggerType=EVENT 且 triggerEventName 匹配、enabled=true 的 ChainDefinitionEntity。
     * §11.12：返回结果包含 characterId=-1 的项目级定义，由调用方按事件 characterId 做二次过滤。
     */
    suspend fun findDefinitionsByTriggerEvent(eventName: String): List<ChainDefinitionEntity>

    // ── §11.1 待处理事件持久化 ────────────────────────────

    suspend fun insertPendingEvent(event: PendingEventEntity)

    suspend fun findUnprocessedPendingEvents(): List<PendingEventEntity>

    suspend fun markPendingEventProcessed(id: String)

    // ── 写入（触发层 / 测试用）────────────────────────────

    suspend fun insertRun(run: ChainRunEntity)

    suspend fun insertDefinition(def: ChainDefinitionEntity)
}
