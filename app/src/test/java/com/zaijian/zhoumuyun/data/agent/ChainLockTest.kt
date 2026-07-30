package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 灵活自动化编排 · 并发认领锁模拟测试（§12.5.1(c)）
 *
 * 用协程模拟竞态，验证 [FakeChainRunRepository.claimRun] 的条件 UPDATE 语义在
 * 内存态层面模拟出的效果下，确实只有一个协程认领成功（返回 1），另一个返回 0
 * 并直接跳过。
 *
 * **重要声明**：这只验证了 claimRun() 的 SQL 条件更新逻辑本身正确，不代表验证了
 * Android 跨进程真实竞态（App 被杀+WorkManager 独立进程唤醒）的时序行为。
 * 真实的跨进程并发需要真机/模拟器验证（§12.5.2）。
 *
 * 纯 JVM 环境，仅依赖 JUnit 4 + kotlinx-coroutines-core（runBlocking / async）。
 */
class ChainLockTest {

    private lateinit var repo: FakeChainRunRepository

    private val now = System.currentTimeMillis()

    @Before
    fun setup() {
        repo = FakeChainRunRepository()
    }

    private fun makeRun(
        id: String = "run-1",
        lockedUntil: Long? = null,
    ) = ChainRunEntity(
        id = id,
        chainDefId = "def-1",
        characterId = 1,
        status = ChainRunStatus.RUNNING,
        currentNodeIndex = 0,
        context = "{}",
        wakeAtMs = null,
        visitCount = 0,
        maxNodeVisits = 200,
        deadlineAt = now + 7L * 24 * 60 * 60 * 1000,
        lockedUntil = lockedUntil,
        isReported = false,
        startedAt = now,
        updatedAt = now,
    )

    // ─────────────────────────────────────────────────────────
    // §12.5.1(c): 两个协程同时 claimRun，只有一个成功
    // ─────────────────────────────────────────────────────────

    /**
     * 核心测试：两个协程同时对同一个 runId 调用 claimRun()。
     *
     * **这只验证了 claimRun() 的 SQL 条件更新逻辑本身正确，不代表验证了
     * Android 跨进程真实竞态（App 被杀+WorkManager 独立进程唤醒）的时序行为。**
     *
     * FakeChainRunRepository.claimRun 使用 synchronized 保证"检查 + 设置"原子性，
     * 模拟 Room 的条件 UPDATE（`WHERE lockedUntil IS NULL OR lockedUntil <= :claimNow`）。
     * 两个协程并发调用时，synchronized 保证只有一个先进入临界区、成功设置 lockedUntil，
     * 另一个进入时发现 lockedUntil 已被设置且未过期，返回 0。
     */
    @Test
    fun `两个协程同时claimRun_只有一个返回1另一个返回0`() = runBlocking {
        repo.directInsertRun(makeRun())

        val lockExpiry = now + 180_000 // 3 分钟 TTL

        coroutineScope {
            val deferred1 = async { repo.claimRun("run-1", now, lockExpiry) }
            val deferred2 = async { repo.claimRun("run-1", now, lockExpiry) }

            val result1 = deferred1.await()
            val result2 = deferred2.await()

            // 一个返回 1，另一个返回 0
            val results = setOf(result1, result2)
            assertTrue("应包含 1（认领成功）", 1 in results)
            assertTrue("应包含 0（被锁定跳过）", 0 in results)
        }

        // 验证最终 lockedUntil 已被设置
        val run = repo.peekRun("run-1")!!
        assertNotNull("lockedUntil 应已设置", run.lockedUntil)
    }

    @Test
    fun `首次claimRun返回1_第二次claimRun返回0`() = runBlocking {
        repo.directInsertRun(makeRun())

        val lockExpiry = now + 180_000
        val first = repo.claimRun("run-1", now, lockExpiry)
        val second = repo.claimRun("run-1", now, lockExpiry)

        assertEquals("首次认领应返回 1", 1, first)
        assertEquals("第二次认领应返回 0（已被锁）", 0, second)
    }

    // ─────────────────────────────────────────────────────────
    // 锁过期后可重新认领
    // ─────────────────────────────────────────────────────────

    @Test
    fun `锁过期后claimRun可重新认领`() = runBlocking {
        // lockedUntil 设为过去时间（锁已过期）
        val expiredLock = now - 1000
        repo.directInsertRun(makeRun(lockedUntil = expiredLock))

        // claimNow > expiredLock → 条件 lockedUntil <= claimNow 满足 → 可认领
        val newLockExpiry = now + 180_000
        val result = repo.claimRun("run-1", now, newLockExpiry)

        assertEquals("锁过期后应能重新认领", 1, result)
        assertEquals("lockedUntil 应更新为新值", newLockExpiry, repo.peekRun("run-1")!!.lockedUntil)
    }

    @Test
    fun `锁未过期时claimRun返回0`() = runBlocking {
        // lockedUntil 设为未来时间（锁未过期）
        val activeLock = now + 60_000 // 1 分钟后过期
        repo.directInsertRun(makeRun(lockedUntil = activeLock))

        val result = repo.claimRun("run-1", now, now + 180_000)

        assertEquals("锁未过期时应返回 0", 0, result)
    }

    // ─────────────────────────────────────────────────────────
    // releaseLock 后可重新认领
    // ─────────────────────────────────────────────────────────

    @Test
    fun `releaseLock后claimRun可再次成功`() = runBlocking {
        repo.directInsertRun(makeRun())

        val lockExpiry = now + 180_000
        repo.claimRun("run-1", now, lockExpiry)
        repo.releaseLock("run-1")

        val afterRelease = repo.claimRun("run-1", now, lockExpiry)
        assertEquals("释放锁后应能再次认领", 1, afterRelease)
    }

    // ─────────────────────────────────────────────────────────
    // claimRun 对不存在的 run 返回 0
    // ─────────────────────────────────────────────────────────

    @Test
    fun `claimRun对不存在的run返回0`() = runBlocking {
        val result = repo.claimRun("nonexistent", now, now + 180_000)
        assertEquals("不存在的 run 应返回 0", 0, result)
    }

    // ─────────────────────────────────────────────────────────
    // ChainEngine.advance 并发调用同一 runId，只有一个执行
    // ─────────────────────────────────────────────────────────

    /**
     * 验证 ChainEngine.advance() 的认领锁在并发场景下生效：
     * 两个协程同时对同一 runId 调用 advance()，只有一个会实际执行节点逻辑，
     * 另一个因 claimRun 返回 0 而直接跳过。
     *
     * **这只验证了 claimRun() 的 SQL 条件更新逻辑本身正确，不代表验证了
     * Android 跨进程真实竞态（App 被杀+WorkManager 独立进程唤醒）的时序行为。**
     */
    @Test
    fun `两个协程同时advance同一runId_只有一个执行节点逻辑`() = runBlocking {
        val nodesJson = ChainNodeCodec.serialize(listOf(
            ChainNode.Action(id = "n1", goal = "任务", next = "n2"),
            ChainNode.End(id = "n2", outcome = ChainEndOutcome.COMPLETED),
        ))

        repo.directInsertDefinition(
            com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity(
                id = "def-1",
                characterId = 1,
                name = "测试",
                triggerType = com.zaijian.zhoumuyun.data.db.entity.ChainTriggerType.EVENT,
                triggerEventName = "test",
                triggerCron = null,
                nodesJson = nodesJson,
                enabled = true,
                createdAt = now,
            )
        )
        repo.directInsertRun(makeRun())

        val deps = FakeChainEngineDeps()

        coroutineScope {
            val deferred1 = async { ChainEngine.advance("run-1", repo, deps) }
            val deferred2 = async { ChainEngine.advance("run-1", repo, deps) }

            deferred1.await()
            deferred2.await()
        }

        // runAction 应只被调用一次（只有一个 advance 实际执行了节点逻辑）
        assertEquals("runAction 应只被调用一次", 1, deps.runActionCalls.size)

        // 最终状态应为 COMPLETED
        assertEquals(ChainRunStatus.COMPLETED, repo.peekRun("run-1")!!.status)
    }

    // ─────────────────────────────────────────────────────────
    // 多个协程并发 claimRun，仍然只有一个成功
    // ─────────────────────────────────────────────────────────

    /**
     * **这只验证了 claimRun() 的 SQL 条件更新逻辑本身正确，不代表验证了
     * Android 跨进程真实竞态（App 被杀+WorkManager 独立进程唤醒）的时序行为。**
     */
    @Test
    fun `多个协程并发claimRun_仍然只有一个成功`() = runBlocking {
        repo.directInsertRun(makeRun())

        val lockExpiry = now + 180_000
        val successCount = coroutineScope {
            val deferreds = (1..10).map {
                async { repo.claimRun("run-1", now, lockExpiry) }
            }
            deferreds.map { it.await() }.count { it == 1 }
        }

        assertEquals("10 个协程并发认领，只有 1 个应成功", 1, successCount)
    }
}
