package com.zaijian.zhoumuyun.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zaijian.zhoumuyun.data.db.dao.ChainDefinitionDao
import com.zaijian.zhoumuyun.data.db.dao.ChainRunDao
import com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainRunStatus
import com.zaijian.zhoumuyun.data.db.entity.ChainTriggerType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 灵活自动化编排 · DAO 层内存态单元测试。
 *
 * 使用 Room.inMemoryDatabaseBuilder 在内存中构建测试数据库，不依赖真机或外部文件。
 * 风格对照项目已有的 DataLayerConcurrencyTest.kt。
 *
 * 覆盖：
 * - ChainDefinitionDao：CRUD + findByTriggerEventEnabled
 * - ChainRunDao.claimRun/releaseLock（§11.2 数据库级认领锁）
 * - ChainRunDao.advanceAtomic（§11.7 原子推进）
 * - ChainRunDao.findUnreported/markReported（§11.10 + §11.12 OR characterId = -1）
 * - ChainRunDao.incrementVisitCount（§11.6 推进计数）
 *
 * 注意：本测试依赖 ApplicationProvider + Room.inMemoryDatabaseBuilder，在纯 JVM 环境
 * 下需要 Robolectric 才能运行（项目当前未引入 Robolectric，DataLayerConcurrencyTest
 * 同款限制）。在 Android Studio 中可作为 instrumented test 在模拟器/真机上运行。
 */
class ChainDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var chainDefDao: ChainDefinitionDao
    private lateinit var chainRunDao: ChainRunDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        chainDefDao = db.chainDefinitionDao()
        chainRunDao = db.chainRunDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── 测试辅助 ──────────────────────────────────────────

    private fun makeRun(
        id: String = "run-1",
        chainDefId: String = "def-1",
        characterId: Int = 1,
        status: String = ChainRunStatus.RUNNING,
        now: Long = System.currentTimeMillis(),
    ) = ChainRunEntity(
        id = id,
        chainDefId = chainDefId,
        characterId = characterId,
        status = status,
        currentNodeIndex = 0,
        context = "{}",
        wakeAtMs = null,
        visitCount = 0,
        maxNodeVisits = 200,
        deadlineAt = now + 7L * 24 * 60 * 60 * 1000, // 7 天
        lockedUntil = null,
        isReported = false,
        startedAt = now,
        updatedAt = now,
    )

    private fun makeDef(
        id: String = "def-1",
        characterId: Int = 1,
        triggerType: String = ChainTriggerType.EVENT,
        triggerEventName: String? = "mood_below_threshold",
        now: Long = System.currentTimeMillis(),
    ) = ChainDefinitionEntity(
        id = id,
        characterId = characterId,
        name = "测试链条",
        triggerType = triggerType,
        triggerEventName = triggerEventName,
        triggerCron = null,
        nodesJson = "[]",
        enabled = true,
        createdAt = now,
    )

    // ─────────────────────────────────────────────────────────
    // ChainDefinitionDao 基础测试
    // ─────────────────────────────────────────────────────────

    @Test
    fun `ChainDefinitionDao_insert 后 findById 能查到`() = runTest {
        val def = makeDef()
        chainDefDao.insert(def)
        val found = chainDefDao.findById("def-1")
        assertNotNull(found)
        assertEquals("测试链条", found!!.name)
        assertEquals(ChainTriggerType.EVENT, found.triggerType)
    }

    @Test
    fun `ChainDefinitionDao_findByTriggerEventEnabled 只返回启用且事件名匹配的定义`() = runTest {
        chainDefDao.insert(makeDef(id = "d1", triggerEventName = "event_a", characterId = 1))
        chainDefDao.insert(makeDef(id = "d2", triggerEventName = "event_a", characterId = 2))
        chainDefDao.insert(makeDef(id = "d3", triggerEventName = "event_b", characterId = 1))
        // d4：事件名匹配但 disabled
        chainDefDao.insert(makeDef(id = "d4", triggerEventName = "event_a", characterId = 3))
        chainDefDao.updateEnabled("d4", false)

        val results = chainDefDao.findByTriggerEventEnabled("event_a")
        assertEquals("应返回 2 条（d1/d2 匹配 event_a 且 enabled，d3 是 event_b，d4 被 disabled）", 2, results.size)
        val ids = results.map { it.id }.toSet()
        assertTrue("d1 应在结果中", "d1" in ids)
        assertTrue("d2 应在结果中", "d2" in ids)
    }

    @Test
    fun `ChainDefinitionDao_deleteById 后查不到`() = runTest {
        chainDefDao.insert(makeDef(id = "d1"))
        chainDefDao.deleteById("d1")
        assertNull(chainDefDao.findById("d1"))
    }

    // ─────────────────────────────────────────────────────────
    // §11.2 数据库级认领锁
    // ─────────────────────────────────────────────────────────

    @Test
    fun `claimRun 首次认领返回1_第二次认领返回0`() = runTest {
        val now = System.currentTimeMillis()
        chainRunDao.insert(makeRun(id = "run-1", now = now))

        val lockExpiry = now + 180_000 // 3 分钟 TTL
        val first = chainRunDao.claimRun("run-1", now, lockExpiry)
        val second = chainRunDao.claimRun("run-1", now, lockExpiry)

        assertEquals("首次认领应返回 1", 1, first)
        assertEquals("第二次认领应返回 0（已被锁）", 0, second)
    }

    @Test
    fun `releaseLock 后 claimRun 可再次成功`() = runTest {
        val now = System.currentTimeMillis()
        chainRunDao.insert(makeRun(id = "run-1", now = now))

        val lockExpiry = now + 180_000
        chainRunDao.claimRun("run-1", now, lockExpiry)
        chainRunDao.releaseLock("run-1")

        val afterRelease = chainRunDao.claimRun("run-1", now, lockExpiry)
        assertEquals("释放锁后应能再次认领", 1, afterRelease)
    }

    @Test
    fun `claimRun 锁过期后可被重新认领`() = runTest {
        val now = System.currentTimeMillis()
        chainRunDao.insert(makeRun(id = "run-1", now = now))

        // 用过去的时间作为 lockExpiry（锁已过期）
        val expiredLock = now - 1000
        chainRunDao.claimRun("run-1", now, expiredLock)

        // 锁已过期，新的 claimNow > expiredLock，应能认领
        val reClaim = chainRunDao.claimRun("run-1", now + 2000, now + 2000 + 180_000)
        assertEquals("锁过期后应能重新认领", 1, reClaim)
    }

    // ─────────────────────────────────────────────────────────
    // §11.7 原子推进
    // ─────────────────────────────────────────────────────────

    @Test
    fun `advanceAtomic 同时更新 context 和 currentNodeIndex`() = runTest {
        val now = System.currentTimeMillis()
        chainRunDao.insert(makeRun(id = "run-1", now = now))

        chainRunDao.advanceAtomic("run-1", """{"result":"ok"}""", 3, now + 1000)

        val updated = chainRunDao.findById("run-1")!!
        assertEquals("context 应已更新", """{"result":"ok"}""", updated.context)
        assertEquals("currentNodeIndex 应已更新", 3, updated.currentNodeIndex)
        assertEquals("updatedAt 应已更新", now + 1000, updated.updatedAt)
    }

    @Test
    fun `advanceAtomic 不影响其他字段`() = runTest {
        val now = System.currentTimeMillis()
        chainRunDao.insert(makeRun(id = "run-1", now = now))

        chainRunDao.advanceAtomic("run-1", """{"step":1}""", 1, now + 500)

        val updated = chainRunDao.findById("run-1")!!
        assertEquals("status 不应变", ChainRunStatus.RUNNING, updated.status)
        assertEquals("visitCount 不应变", 0, updated.visitCount)
        assertEquals("isReported 不应变", false, updated.isReported)
    }

    // ─────────────────────────────────────────────────────────
    // §11.6 推进计数
    // ─────────────────────────────────────────────────────────

    @Test
    fun `incrementVisitCount 每次递增1`() = runTest {
        val now = System.currentTimeMillis()
        chainRunDao.insert(makeRun(id = "run-1", now = now))

        chainRunDao.incrementVisitCount("run-1", now + 1)
        chainRunDao.incrementVisitCount("run-1", now + 2)
        chainRunDao.incrementVisitCount("run-1", now + 3)

        val updated = chainRunDao.findById("run-1")!!
        assertEquals("visitCount 应为 3", 3, updated.visitCount)
    }

    // ─────────────────────────────────────────────────────────
    // §11.10 + §11.12 未播报查询
    // ─────────────────────────────────────────────────────────

    @Test
    fun `findUnreported 只返回已终结且未播报的链条`() = runTest {
        val now = System.currentTimeMillis()
        // 已终结未播报（应返回）
        chainRunDao.insert(makeRun(id = "r1", characterId = 1, status = ChainRunStatus.COMPLETED, now = now))
        chainRunDao.insert(makeRun(id = "r2", characterId = 1, status = ChainRunStatus.FAILED, now = now))
        // RUNNING 未播报（不应返回——仍在进行中）
        chainRunDao.insert(makeRun(id = "r3", characterId = 1, status = ChainRunStatus.RUNNING, now = now))
        // WAITING 未播报（不应返回——仍在进行中）
        chainRunDao.insert(makeRun(id = "r4", characterId = 1, status = ChainRunStatus.WAITING, now = now))
        // 已终结已播报（不应返回）
        chainRunDao.insert(makeRun(id = "r5", characterId = 1, status = ChainRunStatus.COMPLETED, now = now))
        chainRunDao.markReported("r5")

        val results = chainRunDao.findUnreported(1)
        assertEquals("应返回 2 条（r1 COMPLETED + r2 FAILED）", 2, results.size)
        val ids = results.map { it.id }.toSet()
        assertTrue("r1 应在结果中", "r1" in ids)
        assertTrue("r2 应在结果中", "r2" in ids)
    }

    @Test
    fun `findUnreported 带 OR characterId_=_-1 分支覆盖项目级链条`() = runTest {
        val now = System.currentTimeMillis()
        // 角色专属链条（characterId=1）
        chainRunDao.insert(makeRun(id = "r1", characterId = 1, status = ChainRunStatus.COMPLETED, now = now))
        // 项目级链条（characterId=-1），§11.12
        chainRunDao.insert(makeRun(id = "r2", characterId = -1, status = ChainRunStatus.COMPLETED, now = now))
        // 另一个角色的链条（characterId=2），不应出现在 characterId=1 的查询中
        chainRunDao.insert(makeRun(id = "r3", characterId = 2, status = ChainRunStatus.COMPLETED, now = now))

        val results = chainRunDao.findUnreported(1)
        assertEquals("应返回 2 条（r1 角色1 + r2 项目级 -1）", 2, results.size)
        val ids = results.map { it.id }.toSet()
        assertTrue("r1（角色1）应在结果中", "r1" in ids)
        assertTrue("r2（项目级 -1）应在结果中", "r2" in ids)
    }

    @Test
    fun `markReported 后 findUnreported 不再返回该条`() = runTest {
        val now = System.currentTimeMillis()
        chainRunDao.insert(makeRun(id = "r1", characterId = 1, status = ChainRunStatus.COMPLETED, now = now))

        assertEquals("播报前应有 1 条", 1, chainRunDao.findUnreported(1).size)

        chainRunDao.markReported("r1")

        assertEquals("播报后应为 0 条", 0, chainRunDao.findUnreported(1).size)

        val updated = chainRunDao.findById("r1")!!
        assertTrue("isReported 应为 true", updated.isReported)
    }

    // ─────────────────────────────────────────────────────────
    // §11.3 开机恢复查询
    // ─────────────────────────────────────────────────────────

    @Test
    fun `findAllByStatus 按状态过滤链条运行实例`() = runTest {
        val now = System.currentTimeMillis()
        chainRunDao.insert(makeRun(id = "r1", status = ChainRunStatus.RUNNING, now = now))
        chainRunDao.insert(makeRun(id = "r2", status = ChainRunStatus.WAITING, now = now))
        chainRunDao.insert(makeRun(id = "r3", status = ChainRunStatus.COMPLETED, now = now))
        chainRunDao.insert(makeRun(id = "r4", status = ChainRunStatus.RUNNING, now = now))

        val running = chainRunDao.findAllByStatus(ChainRunStatus.RUNNING)
        assertEquals("RUNNING 应有 2 条", 2, running.size)

        val waiting = chainRunDao.findAllByStatus(ChainRunStatus.WAITING)
        assertEquals("WAITING 应有 1 条", 1, waiting.size)
    }

    // ─────────────────────────────────────────────────────────
    // 状态流转
    // ─────────────────────────────────────────────────────────

    @Test
    fun `markWaiting 设置 WAITING 状态和 wakeAtMs`() = runTest {
        val now = System.currentTimeMillis()
        chainRunDao.insert(makeRun(id = "r1", status = ChainRunStatus.RUNNING, now = now))

        val wakeAt = now + 1_800_000
        chainRunDao.markWaiting("r1", wakeAt, now + 1)

        val updated = chainRunDao.findById("r1")!!
        assertEquals(ChainRunStatus.WAITING, updated.status)
        assertEquals(wakeAt, updated.wakeAtMs)
    }

    @Test
    fun `markRunning 从 WAITING 恢复并清除 wakeAtMs`() = runTest {
        val now = System.currentTimeMillis()
        chainRunDao.insert(makeRun(id = "r1", status = ChainRunStatus.WAITING, now = now))

        chainRunDao.markRunning("r1", now + 1)

        val updated = chainRunDao.findById("r1")!!
        assertEquals(ChainRunStatus.RUNNING, updated.status)
        assertNull("wakeAtMs 应被清除", updated.wakeAtMs)
    }

    @Test
    fun `finish 写入终态状态`() = runTest {
        val now = System.currentTimeMillis()
        chainRunDao.insert(makeRun(id = "r1", status = ChainRunStatus.RUNNING, now = now))

        chainRunDao.finish("r1", ChainRunStatus.COMPLETED, now + 1)

        val updated = chainRunDao.findById("r1")!!
        assertEquals(ChainRunStatus.COMPLETED, updated.status)
    }
}
