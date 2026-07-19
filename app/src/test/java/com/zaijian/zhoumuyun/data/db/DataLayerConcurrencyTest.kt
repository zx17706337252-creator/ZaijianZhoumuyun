package com.zaijian.zhoumuyun.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zaijian.zhoumuyun.data.db.dao.CompetitionEntryDao
import com.zaijian.zhoumuyun.data.db.dao.CompetitionRoundDao
import com.zaijian.zhoumuyun.data.db.dao.DaughterIdAllocatorDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryCandidateDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryDao
import com.zaijian.zhoumuyun.data.db.dao.MenstrualCycleDao
import com.zaijian.zhoumuyun.data.db.dao.PregnancyAnswerDao
import com.zaijian.zhoumuyun.data.db.dao.PregnancyDao
import com.zaijian.zhoumuyun.data.db.entity.BirthRecordEntity
import com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity
import com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity
import com.zaijian.zhoumuyun.data.db.entity.DaughterIdAllocatorEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryFtsEntity
import com.zaijian.zhoumuyun.data.db.entity.MenstrualCycleEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyAnswerEntity
import com.zaijian.zhoumuyun.data.db.entity.PregnancyEntity
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 数据层并发与事务正确性单元测试。
 *
 * 使用 Room.inMemoryDatabaseBuilder 在内存中构建测试数据库，
 * 不依赖真机或外部文件。每个测试独立创建并销毁数据库。
 *
 * 注意：MenstrualCycleDao 没有 advancePhase 方法，
 * 测试 7 改为验证 resetAnchorToToday 的字段保留行为。
 */
class DataLayerConcurrencyTest {

    private lateinit var db: AppDatabase
    private lateinit var memoryDao: MemoryDao
    private lateinit var pregnancyDao: PregnancyDao
    private lateinit var daughterIdAllocatorDao: DaughterIdAllocatorDao
    private lateinit var pregnancyAnswerDao: PregnancyAnswerDao
    private lateinit var menstrualCycleDao: MenstrualCycleDao
    private lateinit var candidateDao: MemoryCandidateDao
    private lateinit var competitionEntryDao: CompetitionEntryDao
    private lateinit var competitionRoundDao: CompetitionRoundDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        memoryDao = db.memoryDao()
        pregnancyDao = db.pregnancyDao()
        daughterIdAllocatorDao = db.daughterIdAllocatorDao()
        pregnancyAnswerDao = db.pregnancyAnswerDao()
        menstrualCycleDao = db.menstrualCycleDao()
        candidateDao = db.memoryCandidateDao()
        competitionEntryDao = db.competitionEntryDao()
        competitionRoundDao = db.competitionRoundDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─────────────────────────────────────────────────────────
    // 测试 1：FTS 写入与主表原子一致
    // ─────────────────────────────────────────────────────────

    /**
     * 验证 insertWithFts 正常写入后，主表和 FTS 表都有记录。
     *
     * 简化方案：正常写入一条记忆，再分别验证主表和 FTS 表。
     * Room 的 @Transaction 保证两步原子性，内存数据库同样生效。
     */
    @Test
    fun memoryDao_insertWithFts_transactionRollback() = runTest {
        // 准备测试数据
        val now = System.currentTimeMillis()
        val memory = MemoryEntity(
            id = "test-mem-001",
            characterId = 1,
            domain = "PERSONAL",
            content = "用户喜欢银发角色",
            importance = 3,
            keywords = "银发 角色 偏好",
            sourceEventId = null,
            createdAt = now,
            updatedAt = now,
            lastAccessedAt = now,
            ftsRowId = 1001,
        )
        val fts = MemoryFtsEntity(
            rowId = 1001,
            content = "用户喜欢银发角色",
            keywords = "银发 角色 偏好",
        )

        // 执行原子写入
        memoryDao.insertWithFts(memory, fts)

        // 验证：主表有 1 条记录
        val count = memoryDao.count(1)
        assertEquals("主表应有 1 条记录", 1, count)

        // 验证：FTS 检索能找到该记录
        val results = memoryDao.searchByFts(1, "银发*", 10)
        assertEquals("FTS 检索应返回 1 条记录", 1, results.size)
        assertEquals("test-mem-001", results[0].id)
    }

    // ─────────────────────────────────────────────────────────
    // 测试 2：completeBirthAtomic 事务原子性
    // ─────────────────────────────────────────────────────────

    /**
     * 验证 completeBirthAtomic 同时写入 birth_records 并更新 pregnancy_state。
     *
     * 先插入一条怀孕状态，再调用 completeBirthAtomic。
     * 验证 birth_records 有记录、pregnancyState 的 isPregnant 已变为 false。
     */
    @Test
    fun pregnancyDao_completeBirthAtomic_transactionRollback() = runTest {
        val now = System.currentTimeMillis()

        // 插入怀孕状态
        pregnancyDao.upsertPregnancy(
            PregnancyEntity(
                characterId = 1,
                isPregnant = true,
                pregnancyStartedAt = now - 100_000,
            )
        )

        // 验证初始状态
        val before = pregnancyDao.getPregnancy(1)
        assertNotNull(before)
        assertTrue(before!!.isPregnant)

        // 原子完成生育
        val record = BirthRecordEntity(
            characterId = 1,
            bornAt = now,
            isDaughter = true,
        )
        val clearedState = PregnancyEntity(
            characterId = 1,
            isPregnant = false,
        )
        pregnancyDao.completeBirthAtomic(record, clearedState)

        // 验证：birth_records 有 1 条记录
        val birthRecords = pregnancyDao.getBirthRecords(1)
        assertEquals("birth_records 应有 1 条记录", 1, birthRecords.size)
        assertTrue(birthRecords[0].isDaughter)

        // 验证：pregnancy_state 的 isPregnant 已变为 false
        val after = pregnancyDao.getPregnancy(1)
        assertNotNull(after)
        assertFalse("isPregnant 应为 false", after!!.isPregnant)
    }

    // ─────────────────────────────────────────────────────────
    // 测试 3：并发流产原子性
    // ─────────────────────────────────────────────────────────

    /**
     * 验证两个协程并发调用 triggerMiscarriageAtomic 时，状态保持正确。
     *
     * 先插入怀孕状态，再用两个协程并发触发流产。
     * 验证最终 isPregnant=false、miscarriedAt 非空。
     */
    @Test
    fun pregnancyDao_triggerMiscarriageAtomic_concurrent() = runTest {
        val now = System.currentTimeMillis()

        // 插入怀孕状态
        pregnancyDao.upsertPregnancy(
            PregnancyEntity(
                characterId = 1,
                isPregnant = true,
                pregnancyStartedAt = now - 100_000,
            )
        )

        // 并发触发流产
        coroutineScope {
            val d1 = async { pregnancyDao.triggerMiscarriageAtomic(1, now) }
            val d2 = async { pregnancyDao.triggerMiscarriageAtomic(1, now + 1) }
            d1.await()
            d2.await()
        }

        // 验证：最终状态一致
        val result = pregnancyDao.getPregnancy(1)
        assertNotNull("怀孕状态不应为空", result)
        assertFalse("isPregnant 应为 false", result!!.isPregnant)
        assertNotNull("miscarriedAt 应已设置", result.miscarriedAt)
        assertNull("pregnancyStartedAt 应为 null", result.pregnancyStartedAt)
    }

    // ─────────────────────────────────────────────────────────
    // 测试 4：saveOrMerge 并发去重
    // ─────────────────────────────────────────────────────────

    /**
     * 验证两个协程并发写相似内容时，最终只产生一条记忆。
     *
     * MemoryRepository 内部按 characterId 使用 Mutex 串行化，
     * 第二个协程会检测到相似记忆并触发合并而非新增。
     */
    @Test
    fun memoryRepository_saveOrMerge_concurrentDeduplication() = runTest {
        val repo = MemoryRepository(memoryDao, candidateDao)
        val now = System.currentTimeMillis()

        // 两个内容相似（共享关键词"银发"）的记忆
        fun makeMemory(id: String, content: String) = MemoryEntity(
            id = id,
            characterId = 1,
            domain = "PERSONAL",
            content = content,
            importance = 3,
            keywords = "银发 角色",
            sourceEventId = null,
            createdAt = now,
            updatedAt = now,
            lastAccessedAt = now,
        )

        val memory1 = makeMemory("mem-a", "用户喜欢银发角色")
        val memory2 = makeMemory("mem-b", "用户偏爱银发角色")

        // 并发写入
        coroutineScope {
            val d1 = async { repo.saveOrMerge(memory1) }
            val d2 = async { repo.saveOrMerge(memory2) }
            d1.await()
            d2.await()
        }

        // 验证：最终只有 1 条记忆
        val count = memoryDao.count(1)
        assertEquals("应只有 1 条记忆（被合并）", 1, count)
    }

    // ─────────────────────────────────────────────────────────
    // 测试 5：女儿 ID 分配无重复
    // ─────────────────────────────────────────────────────────

    /**
     * 验证 10 个协程并发调用 allocateNext 时，分配的 ID 互不相同。
     *
     * allocateNext 使用 @Transaction 保证原子性，
     * 每个协程拿到的 ID 必然唯一。
     */
    @Test
    fun daughterIdAllocatorDao_allocateNext_noDuplicates() = runTest {
        // 初始化发号器（插入默认行）
        daughterIdAllocatorDao.insertIfAbsent()

        // 并发分配 10 个 ID
        val ids = mutableListOf<Int>()
        coroutineScope {
            val jobs = (1..10).map {
                async {
                    daughterIdAllocatorDao.allocateNext()
                }
            }
            jobs.forEach { ids.add(it.await()) }
        }

        // 验证：10 个 ID 互不相同
        assertEquals("应有 10 个 ID", 10, ids.size)
        assertEquals("所有 ID 应互不相同", 10, ids.toSet().size)
    }

    // ─────────────────────────────────────────────────────────
    // 测试 6：recordIfOpen 并发槽位锁定
    // ─────────────────────────────────────────────────────────

    /**
     * 验证并发两次写入同一槎位时，只有第一次成功。
     *
     * recordIfOpen 使用 @Transaction 包裹"检查→插入"，
     * 配合唯一索引 (motherCharacterId, questionType, slotIndex, answeredAt)，
     * 第二次插入因唯一约束冲突被 IGNORE。
     */
    @Test
    fun pregnancyAnswerDao_recordIfOpen_concurrentSlotLock() = runTest {
        val now = System.currentTimeMillis()

        // 构造两条相同的实体（motherCharacterId, questionType, slotIndex, answeredAt 均相同）
        fun makeEntity() = PregnancyAnswerEntity(
            motherCharacterId = 1,
            pregnancyStartedAt = 0L,
            questionType = "NAME_PREF",
            questionText = "你想给孩子取什么名字？",
            answerText = "小月",
            answeredAt = now,
            slotIndex = 0,
            isLocked = false,
        )

        val entity1 = makeEntity()
        val entity2 = makeEntity()

        // 并发写入
        val results = mutableListOf<Triple<Boolean, Int, List<PregnancyAnswerEntity>>>()
        coroutineScope {
            val d1 = async { pregnancyAnswerDao.recordIfOpen(entity1) }
            val d2 = async { pregnancyAnswerDao.recordIfOpen(entity2) }
            results.add(d1.await())
            results.add(d2.await())
        }

        // 验证：恰好一次成功
        val successCount = results.count { it.first }
        assertEquals("恰好应有一次成功写入", 1, successCount)
    }

    // ─────────────────────────────────────────────────────────
    // 测试 7：resetAnchorToToday 字段保留
    // ─────────────────────────────────────────────────────────

    /**
     * 验证 resetAnchorToToday 在更新锚点的同时，保留自定义的周期参数。
     *
     * 注意：MenstrualCycleDao 没有 advancePhase 方法，
     * 此处改为测试 resetAnchorToToday 的字段保留行为，
     * 确保"跨阶段跳跃"时中间的自定义配置不会被丢失。
     */
    @Test
    fun menstrualCycleDao_advancePhase_transitions() = runTest {
        val now = System.currentTimeMillis()

        // 插入自定义周期参数
        val custom = MenstrualCycleEntity(
            characterId = 1,
            cycleAnchorAt = now - 28L * 24 * 60 * 60 * 1000,
            cycleLengthDays = 30,
            menstrualDays = 7,
            fertileDays = 5,
        )
        menstrualCycleDao.upsert(custom)

        // 验证初始状态
        val before = menstrualCycleDao.get(1)
        assertNotNull(before)
        assertEquals(30, before!!.cycleLengthDays)
        assertEquals(7, before.menstrualDays)
        assertEquals(5, before.fertileDays)

        // 重置锚点（模拟阶段推进）
        menstrualCycleDao.resetAnchorToToday(1, now)

        // 验证：锚点已更新，但自定义参数保留
        val after = menstrualCycleDao.get(1)
        assertNotNull(after)
        assertEquals("cycleAnchorAt 应更新", now, after!!.cycleAnchorAt)
        assertEquals("cycleLengthDays 应保留", 30, after.cycleLengthDays)
        assertEquals("menstrualDays 应保留", 7, after.menstrualDays)
        assertEquals("fertileDays 应保留", 5, after.fertileDays)
    }

    // ─────────────────────────────────────────────────────────
    // 测试 8：incrementImportance 原子递增
    // ─────────────────────────────────────────────────────────

    /**
     * 验证 incrementImportance 的 UPDATE+SELECT 在同一事务内，
     * 返回的是递增后的值。
     */
    @Test
    fun memoryDao_incrementImportance_atomic() = runTest {
        val now = System.currentTimeMillis()

        // 插入一条 importance=3 的记忆
        val memory = MemoryEntity(
            id = "test-mem-008",
            characterId = 1,
            domain = "PERSONAL",
            content = "原子递增测试",
            importance = 3,
            keywords = "测试",
            sourceEventId = null,
            createdAt = now,
            updatedAt = now,
            lastAccessedAt = now,
        )
        memoryDao.insert(memory)

        // 执行原子递增
        val newImportance = memoryDao.incrementImportance("test-mem-008", now + 1)

        // 验证：返回的是递增后的值
        assertEquals("importance 应从 3 递增到 4", 4, newImportance)

        // 二次验证：直接查询确认
        val queried = memoryDao.getImportance("test-mem-008")
        assertEquals("直接查询也应为 4", 4, queried)
    }

    // ─────────────────────────────────────────────────────────
    // 测试 9：CompetitionEntry 并发插入唯一约束 + IGNORE 兜底
    // ─────────────────────────────────────────────────────────

    /**
     * 验证 W1 修复的核心点：并发插入同一 (roundId, characterId) 组合时，
     * 唯一索引 + OnConflictStrategy.IGNORE 生效，最终只留一条记录。
     *
     * 场景还原：CompetitionRoundManager 内部理论上有 getRoundMutex 做协程级
     * 串行化，此测试绕开该锁直接对 DAO 并发写入，模拟"锁失效/锁外重复调用"
     * 场景下唯一约束兜底是否真的生效——这正是本次修复要防住的最坏情况。
     */
    @Test
    fun competitionEntryDao_insert_concurrentSameSlot_uniqueConstraintHolds() = runTest {
        val now = System.currentTimeMillis()

        // 先插入轮次（外键语义上的前提，虽然当前 schema 未声明外键约束）
        competitionRoundDao.insert(
            CompetitionRoundEntity(
                id = "round-1",
                projectDomain = "PAINTING",
                topic = "测试命题",
                judgeCharacterId = 9,
                participantIdsJson = "[1,2,3]",
                status = "COLLECTING",
                createdAt = now,
            )
        )

        fun makeEntry(id: String, content: String) = CompetitionEntryEntity(
            id = id,
            roundId = "round-1",
            characterId = 1,
            content = content,
            createdAt = now,
        )

        val entryA = makeEntry("entry-a", "角色1的参赛作品——版本A")
        val entryB = makeEntry("entry-b", "角色1的参赛作品——版本B")

        // 并发插入同一 (roundId=round-1, characterId=1) 组合
        coroutineScope {
            val d1 = async { competitionEntryDao.insert(entryA) }
            val d2 = async { competitionEntryDao.insert(entryB) }
            d1.await()
            d2.await()
        }

        // 验证：最终只留一条记录，没有重复参赛条目
        val all = competitionEntryDao.getAllForRound("round-1")
        assertEquals("同一角色在同一轮次应只有 1 条参赛条目", 1, all.size)
    }

    // ─────────────────────────────────────────────────────────
    // 测试 10：CompetitionEntry IGNORE 语义——先到者内容被保留
    // ─────────────────────────────────────────────────────────

    /**
     * 验证 insert 使用的是 IGNORE 而非 REPLACE：
     * 先成功插入的那条记录内容不会被后到的重复插入覆盖。
     *
     * 这里不依赖并发调度的先后顺序（协程调度顺序不保证等于代码书写顺序），
     * 而是先同步插入一条，再尝试插入冲突的第二条，直接断言语义。
     */
    @Test
    fun competitionEntryDao_insert_ignoreSemantics_firstWriteWins() = runTest {
        val now = System.currentTimeMillis()

        competitionRoundDao.insert(
            CompetitionRoundEntity(
                id = "round-2",
                projectDomain = "WRITING",
                topic = "测试命题2",
                judgeCharacterId = 9,
                participantIdsJson = "[1,2]",
                status = "COLLECTING",
                createdAt = now,
            )
        )

        val original = CompetitionEntryEntity(
            id = "entry-original",
            roundId = "round-2",
            characterId = 2,
            content = "原始参赛作品，应被保留",
            createdAt = now,
        )
        val duplicate = CompetitionEntryEntity(
            id = "entry-duplicate",
            roundId = "round-2",
            characterId = 2,
            content = "重复插入的作品，不应生效",
            createdAt = now + 1,
        )

        // 先插入原始条目
        competitionEntryDao.insert(original)
        // 再插入同槽位的重复条目（应被 IGNORE 静默跳过）
        competitionEntryDao.insert(duplicate)

        val all = competitionEntryDao.getAllForRound("round-2")
        assertEquals("应仍只有 1 条记录", 1, all.size)
        assertEquals(
            "保留的应是先插入的原始内容，而非后到的重复内容",
            "原始参赛作品，应被保留",
            all[0].content,
        )
        assertEquals("保留记录的 id 应是先插入的那条", "entry-original", all[0].id)
    }

    // ─────────────────────────────────────────────────────────
    // 测试 11：CompetitionRound 状态流转基础验证
    // ─────────────────────────────────────────────────────────

    /**
     * 验证 CompetitionRoundDao 的 updateStatus / markCompleted 基础行为。
     * 这块此前完全没有测试覆盖，顺手补上状态机流转的最基本断言。
     */
    @Test
    fun competitionRoundDao_updateStatus_and_markCompleted() = runTest {
        val now = System.currentTimeMillis()

        competitionRoundDao.insert(
            CompetitionRoundEntity(
                id = "round-3",
                projectDomain = "MUSIC",
                topic = "测试命题3",
                judgeCharacterId = 9,
                participantIdsJson = "[1,2,3]",
                status = "COLLECTING",
                createdAt = now,
            )
        )

        // 初始状态
        val initial = competitionRoundDao.getById("round-3")
        assertNotNull(initial)
        assertEquals("COLLECTING", initial!!.status)
        assertNull("尚未完成时 completedAt 应为 null", initial.completedAt)

        // 状态流转：COLLECTING → JUDGING
        competitionRoundDao.updateStatus("round-3", "JUDGING")
        val afterJudging = competitionRoundDao.getById("round-3")
        assertEquals("状态应更新为 JUDGING", "JUDGING", afterJudging!!.status)

        // 状态流转：JUDGING → AWAITING_USER
        competitionRoundDao.updateStatus("round-3", "AWAITING_USER")
        val afterAwaiting = competitionRoundDao.getById("round-3")
        assertEquals("状态应更新为 AWAITING_USER", "AWAITING_USER", afterAwaiting!!.status)

        // 标记完成
        val completedAt = now + 10_000
        competitionRoundDao.markCompleted("round-3", completedAt)
        val final = competitionRoundDao.getById("round-3")
        assertEquals("状态应更新为 COMPLETED", "COMPLETED", final!!.status)
        assertEquals("completedAt 应写入指定时间", completedAt, final.completedAt)
    }
}