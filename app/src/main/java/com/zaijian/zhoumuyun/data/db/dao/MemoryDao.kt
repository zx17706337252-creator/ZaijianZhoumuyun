package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryFtsEntity
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────
//  MemoryDao — 长期记忆主表 + FTS4 虚拟表
//
//  注意：改为 abstract class（非 interface），以支持带方法体的
//  @Transaction 复合操作。Room 对 abstract class DAO 同样能
//  生成实现类，行为与 interface DAO 完全一致。
// ─────────────────────────────────────────────────────────────

@Dao
abstract class MemoryDao {

    // ── 原子写入（@Transaction 复合方法）─────────────────────

    /**
     * 插入一条 Memory，同时在同一事务内写入 FTS4 虚拟表。
     *
     * 保证主表与 FTS 表原子一致：若 FTS 写入失败，整体回滚；
     * 若进程中途被杀，SQLite WAL 机制确保两张表同时回滚。
     */
    @Transaction
    open suspend fun insertWithFts(memory: MemoryEntity, fts: MemoryFtsEntity) {
        insert(memory)
        insertFts(fts)
    }

    /**
     * 更新一条 Memory，同时在同一事务内替换 FTS4 虚拟表记录。
     *
     * FTS 虚拟表不支持 UPDATE，需先 DELETE 旧行再 INSERT 新行。
     * 三步操作包裹在同一 @Transaction 内，保证原子性。
     */
    @Transaction
    open suspend fun updateWithFts(memory: MemoryEntity, ftsRowId: Int, fts: MemoryFtsEntity) {
        update(memory)
        deleteFtsById(ftsRowId)
        insertFts(fts)
    }

    // ── 底层单步写入（仅供复合方法内部调用，Repository 禁止直接调用）──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(memory: MemoryEntity)

    @Update
    abstract suspend fun update(memory: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFts(fts: MemoryFtsEntity)

    /**
     * 更新 FTS 虚拟表（先删后插，因为 UPDATE 在 FTS 表无效）。
     * 通过 rowId 定位并替换。
     */
    @Query("DELETE FROM memories_fts WHERE rowid = :rowId")
    abstract suspend fun deleteFtsById(rowId: Int)

    /**
     * P0-1 复审修复：碰撞检测用查询。
     *
     * deriveFtsRowId() 派生出的候选 rowId 落在 31 位正整数空间，
     * 生日悖论下当 memories 表达到几千条规模时碰撞概率即不可忽略
     * （31 位空间下 10 万条记录碰撞概率 ≈ 90%，而非此前注释错误估算的 ≈5e-10）。
     * 仅靠缩小哈希范围无法把碰撞概率压到可忽略，必须在写入前显式检测
     * 候选 rowId 是否已被其他 memory 占用，避免 INSERT/REPLACE 时
     * 静默覆盖另一条记忆的 FTS 行。
     *
     * 排除 excludeId 自身：update() 场景下复用同一条记忆自己的旧 ftsRowId
     * 不应被判定为"冲突"。
     */
    @Query("SELECT EXISTS(SELECT 1 FROM memories WHERE ftsRowId = :rowId AND id != :excludeId)")
    abstract suspend fun existsByFtsRowId(rowId: Int, excludeId: String): Boolean

    // ── 读取：Core Memory（永远注入 Prompt）────────────────────

    /**
     * 获取个人 Core Memory（scope=PERSONAL），避免群记忆串到单人对话。
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND isCore = 1 AND scope = 'PERSONAL'
        ORDER BY updatedAt DESC
    """)
    abstract suspend fun getCoreMemories(characterId: Int): List<MemoryEntity>

    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND isCore = 1 AND scope = 'PERSONAL'
        ORDER BY updatedAt DESC
    """)
    abstract fun observeCoreMemories(characterId: Int): Flow<List<MemoryEntity>>

    /**
     * 获取永恒状态记忆（isEternal = true）。
     * 用于 Prompt 注入：优先级最高，每次对话必然注入，不受蒸馏窗口限制。
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND isEternal = 1
        ORDER BY createdAt ASC
    """)
    abstract suspend fun getEternalMemories(characterId: Int): List<MemoryEntity>

    // ── 读取：按 domain 获取（Prompt 分域注入）────────────────

    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND domain = :domain
        ORDER BY importance DESC, lastAccessedAt DESC
        LIMIT :limit
    """)
    abstract suspend fun getByDomain(characterId: Int, domain: String, limit: Int = 5): List<MemoryEntity>

    // ── 读取：UI 展示（全部 / 重要 / 关于我 / 关于他）─────────

    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId
        ORDER BY importance DESC, updatedAt DESC
    """)
    abstract fun observeAll(characterId: Int): Flow<List<MemoryEntity>>

    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND importance >= 4
        ORDER BY updatedAt DESC
    """)
    abstract fun observeImportant(characterId: Int): Flow<List<MemoryEntity>>

    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND domain = 'PERSONAL'
        ORDER BY updatedAt DESC
    """)
    abstract fun observeAboutUser(characterId: Int): Flow<List<MemoryEntity>>

    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND domain = 'WORLD'
        ORDER BY updatedAt DESC
    """)
    abstract fun observeAboutWorld(characterId: Int): Flow<List<MemoryEntity>>

    // ── FTS4 全文检索 ─────────────────────────────────────────

    /**
     * 全文检索：从 FTS 表找匹配的 rowId，再 JOIN 主表取完整数据。
     *
     * 说明：Room FTS4 的 MATCH 查询返回 rowid，
     * 此 rowId 对应 memories 表的 rowid（SQLite 内置行号）。
     * 通过子查询关联两张表。
     *
     * 注意：FTS4 MATCH 使用简单查询，中文用 TOKENIZER_UNICODE61 分词。
     * 调用方需将查询词用 "*" 包裹以支持前缀匹配（如 "永恒*"）。
     *
     * 待办3：个人全文检索限定 scope=PERSONAL，避免群记忆被单人对话搜出。
     */
    @Query("""
        SELECT m.* FROM memories m
        INNER JOIN memories_fts fts ON m.ftsRowId = fts.rowid
        WHERE fts.memories_fts MATCH :query
          AND m.characterId = :characterId
          AND m.scope = 'PERSONAL'
        ORDER BY m.importance DESC, m.lastAccessedAt DESC
        LIMIT :limit
    """)
    abstract suspend fun searchByFts(characterId: Int, query: String, limit: Int = 10): List<MemoryEntity>

    // ── 待办3：群记忆查询 ─────────────────────────────────────

    /**
     * 获取圆桌群 Core Memory（scope=GROUP），按 roundtableId 限定范围。
     */
    @Query("""
        SELECT * FROM memories
        WHERE scope = 'GROUP' AND roundtableId = :roundtableId AND isCore = 1
        ORDER BY updatedAt DESC
    """)
    abstract suspend fun getGroupCoreMemories(roundtableId: String): List<MemoryEntity>

    /**
     * 群记忆全文检索（scope=GROUP），按 roundtableId 限定范围。
     */
    @Query("""
        SELECT m.* FROM memories m
        INNER JOIN memories_fts fts ON m.ftsRowId = fts.rowid
        WHERE fts.memories_fts MATCH :query
          AND m.scope = 'GROUP'
          AND m.roundtableId = :roundtableId
        ORDER BY m.importance DESC, m.lastAccessedAt DESC
        LIMIT :limit
    """)
    abstract suspend fun searchGroupByFts(roundtableId: String, query: String, limit: Int = 10): List<MemoryEntity>

    // ── 更新访问记录（被 Prompt 召回时调用）──────────────────

    @Query("""
        UPDATE memories
        SET accessCount = accessCount + 1,
            lastAccessedAt = :accessedAt
        WHERE id = :memoryId
    """)
    abstract suspend fun recordAccess(memoryId: String, accessedAt: Long)

    // ── 删除 ──────────────────────────────────────────────────

    @Query("DELETE FROM memories WHERE id = :memoryId")
    abstract suspend fun deleteById(memoryId: String)

    @Query("""
        DELETE FROM memories
        WHERE characterId = :characterId
          AND importance <= 2
          AND createdAt < :expiryTimestamp
          AND isCore = 0
          AND isEternal = 0
    """)
    abstract suspend fun deleteExpired(characterId: Int, expiryTimestamp: Long)

    // ── 合并辅助：按内容相似度查找已有记忆 ───────────────────

    /**
     * 查找内容最接近的已有记忆（用于 Merge 判断）。
     * 使用 LIKE 做简单的关键词包含匹配；精确检索走 FTS。
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId
          AND (content LIKE '%' || :keyword || '%')
        ORDER BY updatedAt DESC
        LIMIT 5
    """)
    abstract suspend fun findSimilar(characterId: Int, keyword: String): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories WHERE characterId = :characterId")
    abstract suspend fun count(characterId: Int): Int

    // ── Phase 25：Rule Layer 查询 ─────────────────────────────

    /**
     * 获取指定角色、指定学习目标下所有 isLocked=true 的 RULE 记忆。
     *
     * Phase 25 Rule Layer 注入规则：
     *   - 每目标最多返回 10 条规则（按 importance 降序）
     *   - isLocked=true 才注入，未锁定规则只写入 DB 不注入 Prompt
     *
     * @param characterId  当前角色 ID
     * @param goalId       关联学习目标 ID
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId
          AND domain = 'RULE'
          AND isLocked = 1
          AND goalId = :goalId
        ORDER BY importance DESC, updatedAt DESC
        LIMIT 10
    """)
    abstract suspend fun getLockedRules(characterId: Int, goalId: String): List<MemoryEntity>

    /**
     * 获取指定角色下全部 RULE 记忆（含未锁定，供 Phase 26 rule_distill 读取）。
     *
     * @param characterId  当前角色 ID
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId
          AND domain = 'RULE'
        ORDER BY goalId ASC, importance DESC, updatedAt DESC
    """)
    abstract suspend fun getAllRules(characterId: Int): List<MemoryEntity>

    /**
     * 性能 M2 修复：按 goalId 在数据库层过滤的 RULE 记忆查询，
     * 替代「getAllRules(characterId) 全量加载后内存 filter { it.goalId == goalId }」的写法。
     * 角色规则积累较多时，原写法每次都要把该角色全部 RULE 记忆（含未锁定、跨目标）
     * 整表读入内存，仅为了筛出当前目标这一小部分。
     *
     * @param characterId  当前角色 ID
     * @param goalId       目标 ID
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId
          AND domain = 'RULE'
          AND goalId = :goalId
        ORDER BY importance DESC, updatedAt DESC
    """)
    abstract suspend fun getRulesByGoal(characterId: Int, goalId: String): List<MemoryEntity>

    /**
     * 原子递增 importance（上限 5），避免并发 read-modify-write 竞态。
     * Phase 26 rule_distill 调用，替代旧的 read → copy → update 模式。
     *
     * 由于 Room @Query 不支持直接返回更新后的值，此处先 UPDATE 再 SELECT。
     * 两步在同一 Dispatchers.IO 协程内串行执行，DistillationEngine 调用方
     * 已通过 Mutex（见 RelationshipEngine）或单线程 IO 保证同一 id 不并发调用。
     */
    @Query("""
        UPDATE memories
        SET importance = MIN(5, importance + 1),
            updatedAt  = :updatedAt
        WHERE id = :memoryId
    """)
    abstract suspend fun atomicIncrementImportance(memoryId: String, updatedAt: Long)

    @Query("SELECT importance FROM memories WHERE id = :memoryId")
    abstract suspend fun getImportance(memoryId: String): Int

    /**
     * 组合原子递增 + 读取，返回递增后的 importance 值（1-5）。
     *
     * P-5 修复：加 @Transaction 将 UPDATE + SELECT 包裹在同一事务内，
     * 保证 SELECT 读取的一定是本次 UPDATE 写入的值，消除两步之间
     * 另一个协程插入写入导致读到错误值的竞态窗口。
     */
    @Transaction
    open suspend fun incrementImportance(memoryId: String, updatedAt: Long): Int {
        atomicIncrementImportance(memoryId, updatedAt)
        return getImportance(memoryId)
    }


    @Query("""
        UPDATE memories
        SET isLocked = 1,
            updatedAt = :updatedAt
        WHERE id = :memoryId
    """)
    abstract suspend fun lockRule(memoryId: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * 统计某目标下已锁定规则数量（Phase 25 Token 预算检查）。
     */
    @Query("""
        SELECT COUNT(*) FROM memories
        WHERE characterId = :characterId
          AND domain = 'RULE'
          AND isLocked = 1
          AND goalId = :goalId
    """)
    abstract suspend fun countLockedRules(characterId: Int, goalId: String): Int

    /**
     * Phase 27：实时观察指定角色的全部 RULE 记忆（含未锁定）。
     *
     * 供 LearningGoalViewModel 订阅，当规则写入/锁定时自动触发 UI 刷新。
     * 按 goalId 分组、importance 降序展示。
     *
     * @param characterId 当前角色 ID
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId
          AND domain = 'RULE'
        ORDER BY goalId ASC, isLocked DESC, importance DESC, updatedAt DESC
    """)
    abstract fun observeAllRules(characterId: Int): Flow<List<MemoryEntity>>

    /**
     * 获取所有非核心记忆，供衰减引擎批量处理。
     * isCore = 1 或 isEternal = 1 的记忆永不衰减，跳过。
     */
    @Query("""
        SELECT * FROM memories
        WHERE isCore = 0
          AND isEternal = 0
        ORDER BY lastAccessedAt ASC
    """)
    abstract suspend fun getAllForDecay(): List<MemoryEntity>

    /**
     * H3 修复：仅统计非永久记忆的数量，用于 applyDecayAll() 返回剩余 count。
     * 替代 getAllForDecay().size，避免把所有记忆实体加载到内存。
     */
    @Query("SELECT COUNT(*) FROM memories WHERE isCore = 0 AND isEternal = 0")
    abstract suspend fun countNonEternal(): Int

    /**
     * 批量更新 accessCount（衰减后落库）。
     * 使用 REPLACE 语义复用 insert。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(memories: List<MemoryEntity>)

    /**
     * 删除 importance <= 2 且 accessCount == 0（从未被召回）
     * 且创建超过 7 天的记忆。Phase 11 Tier 3 定期清理。
     */
    @Query("""
        DELETE FROM memories
        WHERE isCore = 0
          AND isEternal = 0
          AND importance <= 2
          AND accessCount = 0
          AND createdAt < :cutoffMs
    """)
    abstract suspend fun deleteStaleUnused(cutoffMs: Long): Int
}
