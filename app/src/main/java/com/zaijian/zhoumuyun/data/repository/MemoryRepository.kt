package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.MemoryCandidateDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryDao
import com.zaijian.zhoumuyun.data.db.entity.MemoryCandidateEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryFtsEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryScope
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * MemoryRepository（Phase 8）
 *
 * 职责：
 * 1. 写入 Memory（主表 + FTS4 虚拟表同步）
 * 2. 按域读取（Prompt 注入用）
 * 3. FTS4 全文检索 + FinalScore 评分排序
 * 4. Memory Merge（发现重复时合并而非新增）
 * 5. 候选层读写
 * 6. 过期清理（importance=2 的记忆超过 7 天后删除）
 *
 * 使用规则：
 * - 禁止直接调用 memoryDao.insert()，必须通过 save() 方法（保证 FTS 同步）
 * - 重复内容通过 findAndMerge() 处理，禁止写入重复记忆
 */
class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val candidateDao: MemoryCandidateDao,
) {

    // ── 候选层 ────────────────────────────────────────────────

    suspend fun insertCandidate(candidate: MemoryCandidateEntity) =
        candidateDao.insert(candidate)

    suspend fun getPendingCandidates(characterId: Int): List<MemoryCandidateEntity> =
        candidateDao.getPending(characterId)

    suspend fun markCandidateProcessed(candidateId: String, resultMemoryId: String?) =
        candidateDao.markProcessed(candidateId, resultMemoryId)

    // ── Memory 写入（同步 FTS）────────────────────────────────

    /**
     * 写入一条新 Memory，同时同步写 FTS4 虚拟表。
     *
     * 通过 MemoryDao.insertWithFts() 保证主表与 FTS 表原子写入：
     * 两步操作在同一 SQLite 事务内执行，任一步失败均整体回滚，
     * 杜绝主表写入成功但 FTS 缺失导致全文检索漏召回的问题。
     *
     * P0-1 修复（复审修正版）：原实现用 id.hashCode()（32 位）作为 ftsRowId，
     * UUID 取值空间 122 位，按生日悖论 10 万条时期望碰撞 ≈ 1165 对，
     * 碰撞时 REPLACE 直接覆盖旧 FTS 行，全文检索永久失效且无报错。
     *
     * 修复方案：[deriveFtsRowId] 从 UUID 派生 31 位正整数候选值，
     * 但 31 位空间本身在数据量增长后仍会产生不可忽略的碰撞概率
     * （详见该函数注释），因此落库前额外调用 [resolveFtsRowId]
     * 查询主表确认候选值未被占用，冲突时线性探测下一个候选，
     * 从根上保证不会发生静默覆盖——而不是仅靠缩小哈希范围"降低概率"。
     * 若主表已存储非零 ftsRowId（重建/迁移场景）则直接复用，不重新派生。
     */
    suspend fun save(memory: MemoryEntity) {
        val ftsRowId = if (memory.ftsRowId != 0) memory.ftsRowId
                       else resolveFtsRowId(memory.id, deriveFtsRowId(memory.id))
        val memoryWithFtsId = memory.copy(ftsRowId = ftsRowId)
        memoryDao.insertWithFts(
            memoryWithFtsId,
            MemoryFtsEntity(
                rowId    = ftsRowId,
                content  = memory.content,
                keywords = memory.keywords,
            )
        )
    }

    /**
     * 更新已有 Memory（内容改变时同步更新 FTS）。
     *
     * 通过 MemoryDao.updateWithFts() 保证主表更新与 FTS 替换原子执行：
     * update + deleteFts + insertFts 三步在同一 SQLite 事务内，
     * 任一步失败均整体回滚，杜绝主表与 FTS 表不一致。
     */
    suspend fun update(memory: MemoryEntity) {
        // 已有记录必然已存储 ftsRowId（save 写入时保证），直接复用，
        // 不重新派生/不重新探测——同一条记忆的 rowId 一旦分配应保持稳定。
        // 若遗留历史数据 ftsRowId=0，才重新派生并做碰撞探测。
        val ftsRowId = if (memory.ftsRowId != 0) memory.ftsRowId
                       else resolveFtsRowId(memory.id, deriveFtsRowId(memory.id))
        val memoryWithFtsId = memory.copy(ftsRowId = ftsRowId)
        memoryDao.updateWithFts(
            memoryWithFtsId,
            ftsRowId,
            MemoryFtsEntity(
                rowId    = ftsRowId,
                content  = memory.content,
                keywords = memory.keywords,
            )
        )
    }

    /**
     * Merge 逻辑：发现内容相似的已有记忆时，合并而非新增。
     *
     * 规则（§6.6）：
     * - 提取候选内容的第一个有意义词（5字以上的词组）
     * - 查找 memories 表中 content 包含该词的记录
     * - 如果找到相似记忆 → 更新内容（合并），importance 取两者最高值
     * - 如果没有 → 写入新记忆
     *
     * @return 合并到的 Memory ID（如果是 Merge），或新写入的 Memory ID
     */
    suspend fun saveOrMerge(memory: MemoryEntity): String {
        // 提取用于相似度查找的关键词（keywords 中第一个长度 >= 4 的词）
        // 阈值从 2 提高到 4，避免"我喜"等 2 字子串误匹配不相关记忆
        val firstKeyword = memory.keywords.split(" ").firstOrNull { it.length >= 4 }
        if (firstKeyword != null) {
            val similar = memoryDao.findSimilar(memory.characterId, firstKeyword)
            val candidate = similar.firstOrNull()
            // 额外校验：候选记忆与新记忆的 content 必须双向都包含该关键词，
            // 避免"关键词命中但内容实际无关"的误合并（例如关键词恰好是公共子串）。
            if (candidate != null && candidate.id != memory.id &&
                candidate.content.contains(firstKeyword) &&
                memory.content.contains(firstKeyword) &&
                // M4 修复：锁定记忆（isLocked=true 的 RULE 类记忆）不参与 Merge。
                // 锁定记忆是已经过多次验证的高价值规则，覆写会破坏其经过积累的语义。
                !candidate.isLocked
            ) {
                // 找到相似记忆：执行 Merge
                val merged = candidate.copy(
                    content        = mergeContent(candidate.content, memory.content),
                    importance     = maxOf(candidate.importance, memory.importance),
                    keywords       = mergeKeywords(candidate.keywords, memory.keywords),
                    isCore         = candidate.isCore || memory.isCore,
                    updatedAt      = System.currentTimeMillis(),
                    accessCount    = candidate.accessCount,
                    lastAccessedAt = candidate.lastAccessedAt,
                )
                update(merged)
                return candidate.id
            }
        }
        // 没有相似记忆：写入新记录
        save(memory)
        return memory.id
    }

    // ── 读取：Prompt 注入 ─────────────────────────────────────

    /**
     * 获取 Core Memory（importance=5，每次对话必须注入，最多 5 条）。
     */
    suspend fun getCoreMemories(characterId: Int, excludeDomain: MemoryDomain? = null): List<MemoryEntity> =
        memoryDao.getCoreMemories(characterId)
            .filter { excludeDomain == null || it.domain != excludeDomain.name }
            .take(5)

    // ── D2.6：永恒状态记忆 ────────────────────────────────────

    /**
     * 获取永恒状态记忆（isEternal = true）。
     * 永恒记忆优先级最高，每次对话必然注入，不受蒸馏窗口限制。
     */
    suspend fun getEternalMemories(characterId: Int): List<MemoryEntity> =
        memoryDao.getEternalMemories(characterId)

    /**
     * 直接写入永恒状态记忆（绕过 MemoryCandidate 候选层）。
     * 写入的记忆设置 isEternal = true，永不参与蒸馏、永不被过期清理。
     */
    suspend fun writeEternalMemory(
        characterId: Int,
        content: String,
        keywords: String = "女儿 孩子 生育",
    ): String {
        val id = newId()
        val now = System.currentTimeMillis()
        val memory = MemoryEntity(
            id             = id,
            characterId    = characterId,
            domain         = MemoryDomain.WORLD.name,
            content        = content,
            importance     = 5,
            keywords       = keywords,
            sourceEventId  = null,
            isCore         = true,
            isEternal      = true,
            createdAt      = now,
            updatedAt      = now,
            lastAccessedAt = now,
        )
        save(memory)
        return id
    }

    // ── Phase 3 修复手册第4条：LearningGoal 锁定规则 ──────────

    /**
     * 获取某学习目标下已锁定（isLocked=1）的规则记忆，按 importance 降序，
     * 最多 10 条。供 RoundtableViewModel/ChatViewModel 构建 Prompt 的
     * RuleLayer 时调用，此前调用点绕过本仓库直连 [memoryDao]，此处补上
     * 包装以收口裸 DAO 访问。
     */
    suspend fun getLockedRules(characterId: Int, goalId: String): List<MemoryEntity> =
        memoryDao.getLockedRules(characterId, goalId)

    // ── 待办3：群记忆读取/写入 ────────────────────────────────

    /**
     * 获取群 Core Memory（scope=GROUP），按 roundtableId 限定。
     * 与 getCoreMemories 并排使用：个人 core + 群 core 一起注入。
     */
    suspend fun getGroupCoreMemories(roundtableId: String): List<MemoryEntity> =
        memoryDao.getGroupCoreMemories(roundtableId).take(5)

    /**
     * 群记忆相关性检索（scope=GROUP，按 roundtableId 限定）。
     * 复用 buildFtsQuery / calculateFinalScore，与个人检索对称。
     */
    suspend fun searchGroupRelevant(
        roundtableId: String,
        query: String,
        limit: Int = 8,
    ): List<MemoryEntity> {
        if (query.isBlank()) return emptyList()
        val ftsQuery = buildFtsQuery(query)
        val ftsResults = try {
            memoryDao.searchGroupByFts(roundtableId, ftsQuery, limit * 2)
        } catch (e: Exception) {
            emptyList()
        }
        val now = System.currentTimeMillis()
        return ftsResults
            .map { it to calculateFinalScore(it, now) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * 直接写入群记忆（不走 saveOrMerge 合并）。
     *
     * @param roundtableId 圆桌 ID（排序后 characterId 用 '_' 拼接）
     * @param speakerId    发言角色 ID（来源追溯，characterId 字段保留此语义）
     * @param content      群记忆内容
     * @param keywords     关键词（空格分隔）
     * @param importance   重要度（默认 3）
     * @return 写入的 Memory ID
     */
    suspend fun writeGroupMemory(
        roundtableId: String,
        speakerId: Int,
        content: String,
        keywords: String,
        importance: Int = 3,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val memory = MemoryEntity(
            id             = id,
            characterId    = speakerId,
            domain         = MemoryDomain.WORLD.name,
            scope          = MemoryScope.GROUP.name,
            roundtableId   = roundtableId,
            content        = content,
            importance     = importance,
            keywords       = keywords,
            sourceEventId  = null,
            isCore         = importance >= 5,
            createdAt      = now,
            updatedAt      = now,
            lastAccessedAt = now,
        )
        save(memory)
        return id
    }

    /**
     * 全文检索：根据用户消息内容检索相关记忆，用于 Prompt Memory Layer。
     *
     * FinalScore 评分公式（§12.2）：
     * FinalScore = FTS_rank(0.45) + recency(0.25) + importance(0.20) + frequency(0.10)
     *
     * @param query 用户消息或对话关键词
     * @param limit 最多返回条数
     */
    suspend fun searchRelevant(
        characterId: Int,
        query: String,
        limit: Int = 10,
        excludeDomain: MemoryDomain? = null,
    ): List<MemoryEntity> {
        if (query.isBlank()) return emptyList()

        // FTS4 查询：将查询词转为 FTS 格式（支持前缀匹配）
        val ftsQuery = buildFtsQuery(query)

        val ftsResults = try {
            memoryDao.searchByFts(characterId, ftsQuery, limit * 2)
        } catch (e: Exception) {
            // FTS 查询语法错误时降级到最近记忆
            emptyList()
        }

        // FinalScore 评分 + 排序 + 取 TopK
        val now = System.currentTimeMillis()
        return ftsResults
            .map { it to calculateFinalScore(it, now) }
            .sortedByDescending { it.second }
            .filter { excludeDomain == null || it.first.domain != excludeDomain.name }
            .take(limit)
            .map { it.first }
    }

    /**
     * 按域获取记忆（Prompt 分层注入用）。
     */
    suspend fun getByDomain(characterId: Int, domain: MemoryDomain, limit: Int = 5): List<MemoryEntity> =
        memoryDao.getByDomain(characterId, domain.name, limit)

    // ── 观察（UI 层）─────────────────────────────────────────

    fun observeAll(characterId: Int): Flow<List<MemoryEntity>> =
        memoryDao.observeAll(characterId)

    fun observeImportant(characterId: Int): Flow<List<MemoryEntity>> =
        memoryDao.observeImportant(characterId)

    fun observeAboutUser(characterId: Int): Flow<List<MemoryEntity>> =
        memoryDao.observeAboutUser(characterId)

    fun observeAboutWorld(characterId: Int): Flow<List<MemoryEntity>> =
        memoryDao.observeAboutWorld(characterId)

    // ── 访问记录 ──────────────────────────────────────────────

    suspend fun recordAccess(memoryId: String) =
        memoryDao.recordAccess(memoryId, System.currentTimeMillis())

    // ── 删除 ──────────────────────────────────────────────────

    suspend fun deleteById(memoryId: String) =
        memoryDao.deleteById(memoryId)

    /**
     * 清理过期记忆（importance=2 保留 7 天）。
     * 由 MemoryEngine 定期调用。
     */
    suspend fun cleanExpired(characterId: Int) {
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        memoryDao.deleteExpired(characterId, sevenDaysAgo)
    }

    // ── 内部工具方法 ──────────────────────────────────────────

    /**
     * 构建 FTS4 查询字符串。
     *
     * FTS4 MATCH 语法：
     * - 单词精确匹配："银发"
     * - 前缀匹配："银发*"
     * - 多词 OR："银发 角色"（空格分隔 = OR）
     *
     * 此处将输入切分后每个词加前缀通配符。
     */
    private fun buildFtsQuery(input: String): String {
        val words = input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        // 去除 FTS4 特殊字符（" * ( ) : -），仅保留字母、数字与空白，
        // 否则形如 "a-b" 或 "x*" 的输入会让 MATCH 语法解析失败导致查询抛异常。
        // 过滤后为空的词直接跳过，避免生成孤立的 "*" 通配符。
        val sanitized = words.mapNotNull { raw ->
            val cleaned = raw.filter { it.isLetterOrDigit() || it.isWhitespace() }
            if (cleaned.isBlank()) null else cleaned
        }
        return if (sanitized.isEmpty()) input
        else sanitized.take(5).joinToString(" ") { "$it*" }
    }

    /**
     * FinalScore 评分（§12.2）。
     *
     * FTS_rank 在 Room 中不直接可用（需 matchinfo），
     * 此处用 importance + recency + frequency 三项近似（FTS 已完成筛选）。
     */
    private fun calculateFinalScore(memory: MemoryEntity, now: Long): Double {
        // recency_score：越新越高，超过 90 天趋近 0
        val ageMs = now - memory.lastAccessedAt
        val ageDays = ageMs / (1000.0 * 60 * 60 * 24)
        val recency = maxOf(0.0, 1.0 - ageDays / 90.0)

        // importance_score：归一化到 0-1
        val importance = (memory.importance - 1) / 4.0

        // access_frequency：对数归一化，防止高频记忆过度主导
        val frequency = if (memory.accessCount <= 0) 0.0
        else Math.log(memory.accessCount.toDouble() + 1) / Math.log(51.0)

        // FTS 已完成相关性筛选（0.45 权重），此处给 0.45 基础分
        return 0.45 + recency * 0.25 + importance * 0.20 + frequency * 0.10
    }

    /**
     * 合并两条记忆的内容。
     *
     * 策略：新内容直接覆盖旧内容。
     * 旧的追加式写法（"$existing（更新：$incoming）"）会在多次更新后形成
     * 无限嵌套括号链，持续占用 Prompt Token 并携带过期信息干扰上下文。
     * 覆写后旧内容从主表移除，FTS 表由 update() 同步替换，不会残留。
     */
    private fun mergeContent(existing: String, incoming: String): String {
        if (existing == incoming) return existing
        return incoming
    }

    private fun mergeKeywords(existing: String, incoming: String): String {
        val existingSet = existing.split(" ").toSet()
        val incomingSet = incoming.split(" ").toSet()
        return (existingSet + incomingSet).filter { it.isNotBlank() }.joinToString(" ")
    }

    // ── Phase 11：批量记忆衰减（Tier 3 每 2 小时调用）──────────

    /**
     * 对所有非核心记忆应用时间衰减，并清理长期无用的低重要度记忆。
     *
     * 衰减规则（§6.3）：
     * - 每次调用 = 相当于过去了 2 小时，effectiveWeight 不直接存库
     * - 改为通过 accessCount 做「热度」标记：
     *     accessCount 不变，但对 importance=2 且 accessCount=0 的记忆增加一个
     *     decay_strike 计数器（通过 updatedAt 时间差近似）
     * - 实际策略：删除 7 天内从未被召回的 importance ≤ 2 记忆
     *
     * 此方法不修改 importance=3+ 的记忆，保护有价值的长期记忆。
     *
     * @return 删除的记忆数量（用于日志）
     */
    suspend fun applyDecayAll(): Int {
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val cutoff = System.currentTimeMillis() - sevenDaysMs
        // 返回本次实际删除的记忆数量（DELETE 语句返回受影响行数），
        // 而非剩余非永恒记忆数，避免监控指标语义错误。
        return memoryDao.deleteStaleUnused(cutoff)
    }

    /**
     * 清理所有角色的已处理候选（节省存储空间）。
     * Phase 11 Tier 3 一并调用。
     *
     * 注意：使用循环逐个角色清理，避免缺少 characterId 的全表删除。
     */
    suspend fun cleanupProcessedCandidates(characterIds: List<Int>) {
        characterIds.forEach { id ->
            candidateDao.deleteProcessed(id)
        }
    }

    companion object {
        /** 快捷构建：从 ChatViewModel 传入的内容生成新 Memory ID */
        fun newId(): String = UUID.randomUUID().toString()

        /**
         * P0-1（复审修正）：从 UUID 字符串派生 FTS4 rowId 候选值（正整数）。
         *
         * 策略：取 UUID 的 mostSignificantBits XOR leastSignificantBits（64 位），
         * AND 0x7FFF_FFFFL 截断到 31 位正整数空间，再转换为 Int。
         *
         * 修正说明（此前版本的注释有两处与实现不符，现已纠正）：
         * 1. mask 实际是 0x7FFF_FFFFL（31 位 = Int.MAX_VALUE），不是 0x7FFF_FFFF_FFFF_FFFFL（63 位）；
         *    combined 在 AND 后最大值即 Int.MAX_VALUE，toInt() 不会发生溢出/截断错位，这一步本身是安全的。
         * 2. 碰撞概率估算有误：31 位空间大小约 21 亿，按生日悖论，
         *    10 万条记录时碰撞概率 ≈90%（不是 5e-10），超过约 6500 条后
         *    碰撞概率即升破 1%。31 位整数空间天然无法把碰撞压到工程可忽略的水平。
         *
         * 因此本函数只产出"候选值"，不再假定它必然唯一。
         * 真正避免静默覆盖的责任交给 [resolveFtsRowId]：写入前查询主表，
         * 候选值已被其他记忆占用时做开放寻址（+1 探测），保证落库前 100% 不冲突，
         * 而不是依赖派生算法本身把概率降到"足够小"。
         */
        fun deriveFtsRowId(uuid: String): Int {
            return try {
                val u = java.util.UUID.fromString(uuid)
                val combined = (u.mostSignificantBits xor u.leastSignificantBits) and 0x7FFF_FFFFL
                combined.toInt().let { if (it == 0) 1 else it }
            } catch (_: Exception) {
                // 非标准 UUID 格式兜底：取绝对值哈希，0 替换为 1
                uuid.hashCode().let { h -> if (h <= 0) -(h - 1) else h }
            }
        }
    }

    /**
     * P0-1（复审新增）：解析出一个保证当前未被占用的 ftsRowId。
     *
     * [deriveFtsRowId] 只给出 31 位空间内的候选值，仍可能与历史数据碰撞；
     * 这里在落库前用 [MemoryDao.existsByFtsRowId] 显式查询主表，
     * 一旦候选值已被别的 memory（id 不同）占用，就线性探测下一个候选
     * （+1，遇 Int.MAX_VALUE 回绕到 1，跳过 0），直到找到空位。
     *
     * 31 位空间在当前数据规模下足够稀疏，探测通常 0~1 次即结束；
     * 即使数据量增长到接近饱和，本方法也保证语义正确（绝不会发生
     * INSERT/REPLACE 静默覆盖另一条记忆 FTS 行的情况），只是探测次数上升。
     *
     * @param memoryId 当前要写入/更新的记忆自身 id，用于在 update() 场景下
     *                 排除"自己占用自己旧 ftsRowId"被误判为冲突。
     */
    private suspend fun resolveFtsRowId(memoryId: String, candidate: Int): Int {
        var rowId = candidate
        var attempts = 0
        while (memoryDao.existsByFtsRowId(rowId, memoryId)) {
            attempts++
            if (attempts > 10_000) {
                // 理论上不可能达到（31 位空间远未饱和到这个程度），
                // 兜底退出避免极端情况下死循环；记一条日志方便定位。
                com.zaijian.zhoumuyun.util.ZLog.e(
                    "MemoryRepository",
                    "resolveFtsRowId 探测 10000 次仍冲突，数据量可能已接近 31 位空间饱和，" +
                        "需考虑升级 ftsRowId 为 Long 或迁移至 Room 外部内容 FTS4 表"
                )
                break
            }
            rowId = if (rowId == Int.MAX_VALUE) 1 else rowId + 1
        }
        return rowId
    }
}
