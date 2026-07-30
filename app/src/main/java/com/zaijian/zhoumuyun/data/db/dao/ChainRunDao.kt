package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity
import kotlinx.coroutines.flow.Flow

/**
 * 灵活自动化编排 · 链条运行实例 DAO
 *
 * 对照 WorkflowJobDao（findUnreported/markReported 播报机制）和 ScheduledJobDao
 * （claimJob/releaseLock 认领锁）的写法风格。
 *
 * §11.2 数据库级认领锁：[claimRun] / [releaseLock]，照抄 ScheduledJobDao.claimJob
 * 的条件 UPDATE 写法（WHERE lockedUntil IS NULL OR lockedUntil <= :claimNow）。
 *
 * §11.7 原子推进：[advanceAtomic]，单条 UPDATE 同时写 context + currentNodeIndex，
 * SQLite 单语句本身即原子，等价于 @Transaction 包裹两条 UPDATE 但更高效——
 * 对照 AppDatabase.recordStepAtomic()，后者因跨 workflow_step_results + workflow_jobs
 * 两张表才需要 @Transaction；chain_runs 两列在同一行，单 UPDATE 即可保证一致性。
 *
 * §11.10 未播报机制：[findUnreported] / [markReported]，对照 WorkflowJobDao 同名方法。
 * §11.12：findUnreported 带上 `OR characterId = -1` 分支，覆盖项目级链条。
 */
@Dao
interface ChainRunDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: ChainRunEntity)

    @Query("SELECT * FROM chain_runs WHERE id = :id")
    suspend fun findById(id: String): ChainRunEntity?

    @Query("SELECT * FROM chain_runs WHERE characterId = :characterId ORDER BY startedAt DESC")
    fun observeByCharacter(characterId: Int): Flow<List<ChainRunEntity>>

    // ── §11.3 开机恢复：按状态查询 ──────────────────────────

    /**
     * §11.3 开机恢复：查询指定状态的所有链条运行实例。
     * BootReceiver 用此方法查 status=RUNNING（需重新入队）和 status=WAITING
     * （WorkManager 自动恢复 WorkSpec，无需额外处理，但查询确认存在性）。
     */
    @Query("SELECT * FROM chain_runs WHERE status = :status")
    suspend fun findAllByStatus(status: String): List<ChainRunEntity>

    // ── §11.2 数据库级认领锁 ──────────────────────────────

    /**
     * §11.2 数据库级认领锁：条件 UPDATE，照抄 ScheduledJobDao.claimJob 写法。
     *
     * WHERE lockedUntil IS NULL OR lockedUntil <= :claimNow ——锁未设置或已过期才能认领。
     * 返回受影响行数：1=认领成功，0=被别的执行体先抢到了，调用方直接跳过（不抛异常）。
     * :lockExpiry = 当前时间 + LOCK_TTL_MS（3分钟），兜底"认领后执行体自己也崩了"。
     */
    @Query("UPDATE chain_runs SET lockedUntil = :lockExpiry WHERE id = :id AND (lockedUntil IS NULL OR lockedUntil <= :claimNow)")
    suspend fun claimRun(id: String, claimNow: Long, lockExpiry: Long): Int

    /**
     * §11.2 无条件释放锁，在 finally 块调用。照抄 ScheduledJobDao.releaseLock 写法。
     */
    @Query("UPDATE chain_runs SET lockedUntil = NULL WHERE id = :id")
    suspend fun releaseLock(id: String)

    // ── §11.7 原子推进 ────────────────────────────────────

    /**
     * §11.7 原子推进：context 与 currentNodeIndex 必须在同一事务内一起写入，
     * 不能分两次 UPDATE。此处用单条 UPDATE 同时更新两列 + updatedAt，SQLite
     * 单语句本身即原子，满足"不要分两次 UPDATE 语句"的要求。
     *
     * 对照 AppDatabase.recordStepAtomic()：后者因跨表（step_results + workflow_jobs）
     * 才需要 @Transaction 包裹两条 DAO 调用；此处两列在同一行，单 UPDATE 足矣。
     */
    @Query("UPDATE chain_runs SET context = :newContext, currentNodeIndex = :newNodeIndex, updatedAt = :now WHERE id = :id")
    suspend fun advanceAtomic(id: String, newContext: String, newNodeIndex: Int, now: Long)

    // ── §11.6 推进计数 ────────────────────────────────────

    /**
     * §11.6 节点推进计数：每次 advance() 推进节点时递增 visitCount。
     * ChainEngine.advance() 入口处检查 visitCount >= maxNodeVisits → markFailed。
     * 单独一条 UPDATE 而非合并进 advanceAtomic，因为 visitCount 递增与 context/index
     * 推进在 ChainEngine 里的时机不同（入口检查 vs 末尾推进）。
     */
    @Query("UPDATE chain_runs SET visitCount = visitCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementVisitCount(id: String, now: Long)

    // ── 状态流转 ──────────────────────────────────────────

    /**
     * Wait 节点：设为 WAITING 并记录唤醒时间。对照 §5 ChainEngine Wait 分支。
     */
    @Query("UPDATE chain_runs SET status = 'WAITING', wakeAtMs = :wakeAtMs, updatedAt = :now WHERE id = :id")
    suspend fun markWaiting(id: String, wakeAtMs: Long, now: Long)

    /**
     * 从 WAITING 恢复为 RUNNING（ChainResumeWorker 唤醒后调用），清除 wakeAtMs。
     */
    @Query("UPDATE chain_runs SET status = 'RUNNING', wakeAtMs = NULL, updatedAt = :now WHERE id = :id")
    suspend fun markRunning(id: String, now: Long)

    /**
     * 终态写入：COMPLETED / FAILED / CANCELLED。对照 WorkflowJobDao.finish()。
     */
    @Query("UPDATE chain_runs SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun finish(id: String, status: String, now: Long)

    /**
     * 终态写入 + 失败原因（FAILED 时用）。对照 WorkflowJobDao.finish() 的 failReason 参数。
     */
    @Query("UPDATE chain_runs SET status = :status, context = :context, updatedAt = :now WHERE id = :id")
    suspend fun finishWithContext(id: String, status: String, context: String, now: Long)

    // ── §11.10 未播报机制 ────────────────────────────────

    /**
     * §11.10 + §11.12 未播报查询：查已终结但未播报的链条，对齐 WorkflowJobDao.findUnreported。
     *
     * §11.12：`OR characterId = -1` 分支覆盖项目级链条（characterId=-1），
     * 使当前角色的链条播报与项目级链条播报共存，写法对照 WorkflowJobDao.findUnreported
     * 现有的 `WHERE (characterId = :characterId OR characterId = -1) AND isReported = 0
     * AND status != 'RUNNING'`。chain_runs 多一个 WAITING 状态，同样需要排除（仍在进行中）。
     */
    @Query("SELECT * FROM chain_runs WHERE (characterId = :characterId OR characterId = -1) AND isReported = 0 AND status != 'RUNNING' AND status != 'WAITING' ORDER BY startedAt DESC")
    suspend fun findUnreported(characterId: Int): List<ChainRunEntity>

    /**
     * §11.10 播报后标记。对照 WorkflowJobDao.markReported。
     */
    @Query("UPDATE chain_runs SET isReported = 1 WHERE id = :id")
    suspend fun markReported(id: String)
}
