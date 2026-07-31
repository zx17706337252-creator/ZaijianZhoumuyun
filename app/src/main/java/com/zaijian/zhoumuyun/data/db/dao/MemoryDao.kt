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

    /** Window A-1：按主键查单条记忆（L2 检索路由用）。 */
    @Query("SELECT * FROM memories WHERE id = :id")
    abstract suspend fun getById(id: String): MemoryEntity?

    // ── 读取：Core Memory（永远注入 Prompt）────────────────────

    /**
     * 获取个人 Core Memory（scope=PERSONAL），避免群记忆串到单人对话。
     *
     * 排序：importance DESC 优先——isCore 锚点收紧为"稀疏、慎重"后，应优先
     * 注入最重要的几条，而非最近更新的几条（buildMemoryBlock 注入时 take(5)）。
     *
     * C8#44 修复：加 isNarrativeOnly = 0 过滤。此查询是"永远注入 Prompt"的
     * core memory 通道（ChatMessageOrchestrator/PrivateChatEngine/
     * RoundtableBotReplyGenerator/RoundtableIdleManager 均直接调用），
     * 此前无过滤会把假扮身份识别期间产生的叙事记忆（owner 冒充 XX 撩本角色时
     * 写入、isNarrativeOnly=true）当成"owner 与本角色的正常互动核心记忆"
     * 一并注入，污染角色对 owner 关系的认知。GROUP scope 记忆不受影响——
     * speakerContext 机制未在圆桌路径接入（见 IdentityGuard.kt 头部注释），
     * GROUP scope 记忆结构上不会被打 isNarrativeOnly=true。
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND isCore = 1 AND scope = 'PERSONAL'
          AND isNarrativeOnly = 0
        ORDER BY importance DESC, updatedAt DESC
    """)
    abstract suspend fun getCoreMemories(characterId: Int): List<MemoryEntity>

    /** C8#44 修复：同 [getCoreMemories]，加 isNarrativeOnly = 0 过滤保持一致。 */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND isCore = 1 AND scope = 'PERSONAL'
          AND isNarrativeOnly = 0
        ORDER BY importance DESC, updatedAt DESC
    """)
    abstract fun observeCoreMemories(characterId: Int): Flow<List<MemoryEntity>>

    // ── 读取：按 domain 获取（Prompt 分域注入）────────────────

    /**
     * W3-5 修复：加 scope = 'PERSONAL' 过滤。此查询供 Prompt 分域注入使用，
     * 混入 GROUP scope 记忆会导致私聊 Prompt 里出现圆桌讨论内容，属逻辑错误
     * （不只是 UI 展示层面的困惑）。
     *
     * C8#44 修复：加 isNarrativeOnly = 0 过滤。调用方包括 CharacterPreviewViewModel
     * （人设预览直接展示给用户）、DataVisTools 的 SelfReflectTool（读 WORK 记忆喂给
     * LLM 生成自我反思）、AgentMetaTools 的 rule review（读 RULE 记忆），三处都属于
     * "把记忆内容当真实历史处理"的场景，不应包含假扮身份识别期间产生的叙事记忆。
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND domain = :domain AND scope = 'PERSONAL'
          AND isNarrativeOnly = 0
        ORDER BY importance DESC, lastAccessedAt DESC
        LIMIT :limit
    """)
    abstract suspend fun getByDomain(characterId: Int, domain: String, limit: Int = 5): List<MemoryEntity>

    // ── 读取：UI 展示（全部 / 重要 / 关于我 / 关于他）─────────

    /**
     * W3-5 修复：加 scope = 'PERSONAL' 过滤，避免圆桌群记忆混入私聊角色的
     * 记忆列表展示，导致用户看到"串号"的记忆（不知道这条记忆从哪来的）。
     *
     * C8#44 说明（有意不加 isNarrativeOnly 过滤）：这是记忆管理页
     * （MemoryViewModel，见其注释"总是观察全量，在 ViewModel 里过滤"）的数据源，
     * 用户对自己写下的全部记忆（含假扮场景产生的叙事记忆）应保留完整可见性和
     * 删除权，不应该被悄悄隐藏。与 getCoreMemories/getByDomain/searchByFts
     * 这类"喂给 Prompt/LLM"的查询是两种不同的读取场景，过滤诉求不同。
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND scope = 'PERSONAL'
        ORDER BY importance DESC, updatedAt DESC
    """)
    abstract fun observeAll(characterId: Int): Flow<List<MemoryEntity>>

    /**
     * W3-5 修复：同 observeAll，加 scope = 'PERSONAL' 过滤。
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId AND importance >= 4 AND scope = 'PERSONAL'
        ORDER BY updatedAt DESC
    """)
    abstract fun observeImportant(characterId: Int): Flow<List<MemoryEntity>>

    // ── FTS4 全文检索 ─────────────────────────────────────────

    /**
     * 全文检索：从 FTS 表找匹配的 rowId，再 JOIN 主表取完整数据。
     *
     * 说明：Room FTS4 的 MATCH 查询返回 rowid，
     * 此 rowId 对应 memories 表的 rowid（SQLite 内置行号）。
     * 通过子查询关联两张表。
     *
     * 注意：FTS4 MATCH 使用简单查询，tokenizer 为 TOKENIZER_UNICODE61。
     *
     * E1 审计报告任务1 修正：原注释写"中文用 TOKENIZER_UNICODE61 分词"，暗示
     * 该 tokenizer 会做中文分词。实测（用 fts4aux 虚表直接查看索引出的 token，
     * 见 E1 kotlin_port.py）证明：unicode61 不对连续中文字符做任何切分，一段
     * 没有标点/空格的中文会被整体索引成一个 token。中文分词的实际工作由
     * ChineseTokenizer 在写入侧（keywords 字段空格分隔的真实词）和查询侧
     *（buildFtsQuery 分词后加 *）完成——空格分隔后 unicode61 才会把每个词
     * 当作独立 token 索引，前缀匹配（word*）才能命中。
     * 调用方需将查询词用 "*" 包裹以支持前缀匹配（如 "永恒*"）。
     *
     * 待办3：个人全文检索限定 scope=PERSONAL，避免群记忆被单人对话搜出。
     *
     * C8#44 修复：加 isNarrativeOnly = 0 过滤。此查询是 MemoryQueryTool（LLM 可
     * 主动调用的 memory_query 工具）和一般记忆检索的共同入口，结果会原样喂回
     * LLM 上下文，同样不应包含假扮身份识别期间产生的叙事记忆。
     */
    @Query("""
        SELECT m.* FROM memories m
        INNER JOIN memories_fts fts ON m.ftsRowId = fts.rowid
        WHERE fts.memories_fts MATCH :query
          AND m.characterId = :characterId
          AND m.scope = 'PERSONAL'
          AND m.isNarrativeOnly = 0
        ORDER BY m.importance DESC, m.lastAccessedAt DESC
        LIMIT :limit
    """)
    abstract suspend fun searchByFts(characterId: Int, query: String, limit: Int = 10): List<MemoryEntity>

    // ── 待办3：群记忆查询 ─────────────────────────────────────

    /**
     * 获取圆桌群 Core Memory（scope=GROUP），按 roundtableId 限定范围。
     *
     * P2-3 修复（Window A 验收待办）：排序与个人侧 [getCoreMemories] 保持一致，
     * 改为 importance DESC 优先、updatedAt DESC 次之，确保高重要度群记忆排在前面。
     */
    @Query("""
        SELECT * FROM memories
        WHERE scope = 'GROUP' AND roundtableId = :roundtableId AND isCore = 1
        ORDER BY importance DESC, updatedAt DESC
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

    // ── D-1 修复：带 FTS 同步清理的删除 ──────────────────────

    /**
     * 删除单条记忆，同时在同一事务内清理 FTS 虚拟表中的对应行。
     * 参照 insertWithFts/updateWithFts 的事务模式。
     */
    @Transaction
    open suspend fun deleteWithFts(memoryId: String, ftsRowId: Int) {
        deleteById(memoryId)
        deleteFtsById(ftsRowId)
    }

    @Query("""
        SELECT ftsRowId FROM memories
        WHERE characterId = :characterId
          AND importance <= 2
          AND createdAt < :expiryTimestamp
          AND isCore = 0
          AND isEternal = 0
          AND scope = 'PERSONAL'
          AND ftsRowId != 0
    """)
    abstract suspend fun getFtsRowIdsForExpired(
        characterId: Int,
        expiryTimestamp: Long,
    ): List<Int>

    @Query("DELETE FROM memories_fts WHERE rowid IN (:rowIds)")
    abstract suspend fun deleteFtsByIds(rowIds: List<Int>)

    /**
     * W3-5 修复：加 scope = 'PERSONAL' 过滤，避免清理个人记忆时误删同一
     * characterId 下的 GROUP scope 记忆（GROUP 记忆的过期策略应独立于
     * PERSONAL 记忆，不应被这个方法一并清理）。
     */
    @Query("""
        DELETE FROM memories
        WHERE characterId = :characterId
          AND importance <= 2
          AND createdAt < :expiryTimestamp
          AND isCore = 0
          AND isEternal = 0
          AND scope = 'PERSONAL'
    """)
    abstract suspend fun deleteExpired(characterId: Int, expiryTimestamp: Long)

    // ── 合并辅助：按内容相似度查找已有记忆 ───────────────────

    /**
     * 查找内容最接近的已有记忆（用于 Merge 判断）。
     * 使用 LIKE 做简单的关键词包含匹配；精确检索走 FTS。
     *
     * W3-4 修复：原查询只按 characterId + content LIKE 过滤，没有区分 scope，
     * 如果传入 GROUP scope 的记忆做相似度查找，可能匹配到 PERSONAL scope 的
     * 记忆并触发跨 scope 合并（合并后 mergeContent 会用新内容覆盖旧内容，
     * 导致原 scope 的记忆内容被替换）。当前所有走 saveOrMerge 的候选都是
     * PERSONAL scope（GROUP 记忆走 MemoryWriteTool → saveOrMerge 写入，
     * 但 saveOrMerge 内部按 scope 分组查找候选，不会跨 scope 合并），
     * 这里补上过滤条件是为未来"GROUP scope 候选也走 saveOrMerge"的场景兜底。
     *
     * P1-2 修复（Window A 验收待办）：新增 [roundtableId] 参数。scope=GROUP 时
     * 同一角色在不同圆桌写入的群记忆如果关键词相近，findSimilar 可能跨圆桌匹配
     * 并触发 saveOrMerge 错误合并。传入 roundtableId 后，GROUP 查询额外按
     * roundtableId 过滤；PERSONAL 查询传 null（SQL 中 `:roundtableId IS NULL`
     * 短路通过，不影响 PERSONAL 行为）。
     */
    @Query("""
        SELECT * FROM memories
        WHERE characterId = :characterId
          AND scope = :scope
          AND (:roundtableId IS NULL OR roundtableId = :roundtableId)
          AND (content LIKE '%' || :keyword || '%')
        ORDER BY updatedAt DESC
        LIMIT 5
    """)
    abstract suspend fun findSimilar(
        characterId: Int,
        keyword: String,
        scope: String,
        roundtableId: String? = null,
    ): List<MemoryEntity>

    // 窗口04 新发现1 修复：原 SQL 无 scope 过滤，会把该角色作为发言人写入的
    // GROUP（群记忆）也计入"个人记忆数"统计——ProfileViewModel.loadStats()
    // 按 allIds.sumOf { memoryRepo.count(it) } 跨全部角色累加展示"记忆条数"，
    // 群记忆本质上是多个角色共享同一份记录（写入时 characterId 为发言人），
    // 不加过滤会让展示的个人记忆总数虚高，且随圆桌参与角色数增多而进一步膨胀。
    // 与本文件其余 PERSONAL 侧查询（observeAll/getCoreMemories等）保持一致的
    // scope = 'PERSONAL' 过滤口径。
    @Query("SELECT COUNT(*) FROM memories WHERE characterId = :characterId AND scope = 'PERSONAL'")
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
    abstract suspend fun getImportance(memoryId: String): Int?

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
        return getImportance(memoryId) ?: 0
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
     *
     * W3-5 修复：加 scope = 'PERSONAL' 过滤。GROUP scope 的圆桌群记忆走的是
     * MemoryWriteTool → saveOrMerge 写入（不经过候选评分），衰减策略应该独立于个人
     * 记忆，不应被这个全表扫描一并纳入处理。
     */
    @Query("""
        SELECT * FROM memories
        WHERE isCore = 0
          AND isEternal = 0
          AND scope = 'PERSONAL'
        ORDER BY lastAccessedAt ASC
    """)
    abstract suspend fun getAllForDecay(): List<MemoryEntity>

    /**
     * H3 修复：仅统计非永久记忆的数量，用于 applyDecayAll() 返回剩余 count。
     * 替代 getAllForDecay().size，避免把所有记忆实体加载到内存。
     *
     * W3-5 修复：同步给 getAllForDecay() 加了 scope = 'PERSONAL' 过滤，
     * 这里也要保持一致，否则两者统计口径不一致，count 会虚高于实际处理量。
     */
    @Query("SELECT COUNT(*) FROM memories WHERE isCore = 0 AND isEternal = 0 AND scope = 'PERSONAL'")
    abstract suspend fun countNonEternal(): Int

    /**
     * W3-5 修复：加 scope = 'PERSONAL' 过滤，避免这个全表清理任务把
     * GROUP scope 的圆桌群记忆也当作"无用记忆"删掉。GROUP 记忆的清理
     * 策略应该独立评估，不应该被这个为个人记忆设计的规则误伤。
     */
    @Query("""
        DELETE FROM memories
        WHERE isCore = 0
          AND isEternal = 0
          AND importance <= 2
          AND accessCount = 0
          AND createdAt < :cutoffMs
          AND scope = 'PERSONAL'
    """)
    abstract suspend fun deleteStaleUnused(cutoffMs: Long): Int
}
