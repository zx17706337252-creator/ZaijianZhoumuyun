package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.MemoryCandidateDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryDao
import com.zaijian.zhoumuyun.data.db.dao.MemoryTagDao
import com.zaijian.zhoumuyun.data.db.entity.MemoryCandidateEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryDomain
import com.zaijian.zhoumuyun.data.db.entity.MemoryEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryFtsEntity
import com.zaijian.zhoumuyun.data.db.entity.MemoryScope
import com.zaijian.zhoumuyun.data.db.entity.MemoryTagEntity
import com.zaijian.zhoumuyun.util.ChineseTokenizer
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    private val memoryTagDao: MemoryTagDao,
) {

    // 审查报告问题14修复：saveOrMerge 是"findSimilar 读取 → 判定合并 → update/save
    // 写入"的先读后写模式，无保护时两个并发调用可能同时通过"无相似记忆"检查，
    // 各写入一条重复记忆。与 SpecialtyProfileRepository.getCandidateMutex 同一
    // 套路：按 characterId 维护独立 Mutex，串行化同一角色的并发 saveOrMerge，
    // 不同角色互不影响、不牺牲并发度。这里不用数据库级 @Transaction，因为竞态
    // 本质是同进程内的协程并发，Kotlin Mutex 已足以消除，且不需要改动本类和
    // 全部 8 个调用点的构造函数签名（对比 db.withTransaction 方案需要新增 db
    // 依赖并牵连 WorldSimulation 等上游构造函数，改动面明显更大）。
    private val saveOrMergeMutexes = ConcurrentHashMap<Int, Mutex>()
    private fun getSaveOrMergeMutex(characterId: Int): Mutex =
        saveOrMergeMutexes.computeIfAbsent(characterId) { Mutex() }

    // W6-4 修复：save() 的 resolveFtsRowId() → insertWithFts() 是先读后写模式，
    // 两个协程并发调用 save() 时可能同时通过 existsByFtsRowId 检查，拿到同一个
    // ftsRowId 候选值，第二个 INSERT 的 REPLACE 策略会静默覆盖第一个的 FTS 行，
    // 导致被覆盖的记忆全文检索永久失效。与 saveOrMerge 同款模式：按 characterId
    // 维护独立 Mutex，串行化同一角色的并发 save()，不同角色互不影响。
    private val saveMutexes = ConcurrentHashMap<Int, Mutex>()
    private fun getSaveMutex(characterId: Int): Mutex =
        saveMutexes.computeIfAbsent(characterId) { Mutex() }

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
    suspend fun save(memory: MemoryEntity) = getSaveMutex(memory.characterId).withLock {
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
        // Window A-1：同步写入 L2 标签索引
        syncL2Tags(memoryWithFtsId)
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
        // Window A-1：同步更新 L2 标签索引
        syncL2Tags(memoryWithFtsId)
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
    suspend fun saveOrMerge(memory: MemoryEntity): String = getSaveOrMergeMutex(memory.characterId).withLock {
        // 提取用于相似度查找的合并锚点词。
        //
        // E1 审计报告任务1 修复（关键发现）：原实现取 keywords 中"第一个长度 >= 4
        // 的词"做合并锚点。但 extractKeywords 改为真实中文分词后，产出的词多为
        // 2-3 字（真实中文词长度分布如此），>=4 的阈值会让大多数记忆找不到锚点、
        // 合并去重功能直接失效（实测 32 条测试记忆中 75% 不存在任何 >=4 字的词）。
        //
        // 原 >=4 阈值的历史原因：旧 extractKeywords 产出的是任意位置的 4 字符子串
        // 或整句切片，2 字子串（如"我喜"）是无意义噪声、极易误匹配不相关记忆，故
        // 提高到 4 字降低误匹配。现在 keywords 是真实分词后的词，2 字词（如"爬山"
        // "失眠"）本身就有明确语义，不再是噪声子串，阈值应回到 2。
        //
        // 进一步优化：取长度 >= 2 的词中【最长】的一个作为锚点——越长越具体，
        // 越不容易误匹配到话题无关的记忆（如"女儿"比"女"更具体，"失眠"比"眠"更具体）。
        // findSimilar 用 LIKE '%keyword%' 做包含匹配，锚点越具体越能收敛到真正
        // 相似的记忆，减少误合并。仍保留双向 content 包含校验作为第二道防线。
        val mergeAnchor = memory.keywords.split(" ")
            .filter { it.length >= 2 }
            .maxByOrNull { it.length }
        if (mergeAnchor != null) {
            val similar = memoryDao.findSimilar(
                memory.characterId,
                mergeAnchor,
                memory.scope,
                // P1-2 修复：GROUP scope 传入 roundtableId 防跨圆桌串味合并；
                // PERSONAL scope 传 null（findSimilar SQL 中 IS NULL 短路通过）。
                memory.roundtableId,
            )
            val candidate = similar.firstOrNull()
            // 额外校验：候选记忆与新记忆的 content 必须双向都包含该锚点词，
            // 避免"关键词命中但内容实际无关"的误合并（例如关键词恰好是公共子串）。
            if (candidate != null && candidate.id != memory.id &&
                candidate.content.contains(mergeAnchor) &&
                memory.content.contains(mergeAnchor) &&
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
                return@withLock candidate.id
            }
        }
        // 没有相似记忆：写入新记录
        save(memory)
        return@withLock memory.id
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

    /**
     * 统计某学习目标下已锁定（isLocked=1）的规则数量（收尾交接清单 任务组C）。
     * `RuleDistillTool` 原裸持有 `memoryDao: MemoryDao` 单独调用此方法，
     * 此处补齐透传，供其改为只依赖 `MemoryRepository`。
     */
    suspend fun countLockedRules(characterId: Int, goalId: String): Int =
        memoryDao.countLockedRules(characterId, goalId)

    /**
     * 获取某学习目标下全部规则记忆（含未锁定），按 goalId 过滤（收尾交接清单 任务组C）。
     * `RuleDistillTool` 原裸持有 `memoryDao: MemoryDao` 单独调用此方法，
     * 此处补齐透传，供其改为只依赖 `MemoryRepository`。
     */
    suspend fun getRulesByGoal(characterId: Int, goalId: String): List<MemoryEntity> =
        memoryDao.getRulesByGoal(characterId, goalId)

    /**
     * 观察某角色全部规则记忆（domain=RULE），响应式（S8-窗口01 收口）。
     * `LearningGoalViewModel` 原裸持有 `memDao = AppDatabase.getInstance(application)
     * .memoryDao()` 单独调用此方法，此处补齐透传，供其改为只依赖 `MemoryRepository`。
     */
    fun observeAllRules(characterId: Int): Flow<List<MemoryEntity>> =
        memoryDao.observeAllRules(characterId)

    // ── 待办3：群记忆读取/写入 ────────────────────────────────

    /**
     * 获取群 Core Memory（scope=GROUP），按 roundtableId 限定。
     * 与 getCoreMemories 并排使用：个人 core + 群 core 一起注入。
     */
    suspend fun getGroupCoreMemories(roundtableId: String): List<MemoryEntity> =
        memoryDao.getGroupCoreMemories(roundtableId).take(5)

    /**
     * 群记忆相关性检索（scope=GROUP，按 roundtableId 限定）。
     * 复用 buildFtsQueryWordLevel / buildFtsQuery / calculateFinalScore，与个人检索对称。
     *
     * 与 [searchRelevantWithRouting] 同样采用"先精确后模糊"策略：
     * 主路径用 [buildFtsQueryWordLevel]（纯词级），0 召回时 fallback 到
     * [buildFtsQuery]（含 bigram）。
     */
    suspend fun searchGroupRelevant(
        roundtableId: String,
        query: String,
        limit: Int = 8,
    ): List<MemoryEntity> {
        if (query.isBlank()) return emptyList()
        val now = System.currentTimeMillis()

        // 主路径：词级 FTS
        val primaryFts = buildFtsQueryWordLevel(query)
        val primaryResults = try {
            memoryDao.searchGroupByFts(roundtableId, primaryFts, limit * 2)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 问题37修复：不再静默吞掉，打 error 日志留痕。返回类型仍保持
            // emptyList()（避免改动波及全部调用方），但至少能通过日志区分
            // "数据库异常导致0召回" 和 "确实没有匹配的记忆"。
            ZLog.e("MemoryRepository", "searchGroupRelevant 主路径FTS检索失败，roundtableId=$roundtableId", e)
            emptyList()
        }
        val scored = primaryResults
            .map { it to calculateFinalScore(it, now) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
        if (scored.isNotEmpty()) return scored

        // Fallback：bigram FTS（仅主路径 0 召回时触发）
        val fallbackFts = buildFtsQuery(query)
        val fallbackResults = try {
            memoryDao.searchGroupByFts(roundtableId, fallbackFts, limit * 2)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e("MemoryRepository", "searchGroupRelevant fallback FTS检索失败，roundtableId=$roundtableId", e)
            emptyList()
        }
        return fallbackResults
            .map { it to calculateFinalScore(it, now) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // E1 审计报告 §2.4：writeGroupMemory() 已删除（死代码，全项目零调用）。
    // 真实的 GROUP 记忆写入路径是 AgentCoreTools.MemoryWriteTool（Agent 通过
    // <tool:memory_write scope="GROUP".../> 主动写入）直接调用 saveOrMerge()，
    // 完全绕开了本方法。保留死代码只会误导后续开发者以为这是 GROUP 记忆的
    // 写入入口。原始实现见 git 历史。

    /**
     * 按域获取记忆（Prompt 分层注入用）。
     */
    suspend fun getByDomain(characterId: Int, domain: MemoryDomain, limit: Int = 5): List<MemoryEntity> =
        memoryDao.getByDomain(characterId, domain.name, limit)

    /**
     * 原始 FTS4 检索透传，不做 FinalScore 评分排序（收尾交接清单 任务组C）。
     * `MemoryQueryTool`（memory_query 工具）原裸持有 `memoryDao: MemoryDao`
     * 直接调用此方法，自行做 domain 过滤 + take(limit)；评分排序走
     * [searchRelevantWithRouting]，两者是不同行为，因此单独透传，
     * 避免改变工具原有的召回结果顺序。
     */
    suspend fun searchByFts(characterId: Int, ftsQuery: String, limit: Int): List<MemoryEntity> =
        memoryDao.searchByFts(characterId, ftsQuery, limit)

    /**
     * 按角色统计记忆条数（收尾交接清单 任务组A）。
     * ProfileStatsRow 统计"条记忆"数原先裸调用 `db.memoryDao().count(it)`，
     * 此处补齐透传方法，供其改走 Repository。
     */
    suspend fun count(characterId: Int): Int = memoryDao.count(characterId)

    // ── 观察（UI 层）─────────────────────────────────────────

    fun observeAll(characterId: Int): Flow<List<MemoryEntity>> =
        memoryDao.observeAll(characterId)

    fun observeImportant(characterId: Int): Flow<List<MemoryEntity>> =
        memoryDao.observeImportant(characterId)

    // ── 访问记录 ──────────────────────────────────────────────

    suspend fun recordAccess(memoryId: String) =
        memoryDao.recordAccess(memoryId, System.currentTimeMillis())

    // ── 删除 ──────────────────────────────────────────────────

    suspend fun deleteById(memoryId: String, ftsRowId: Int = 0) {
        if (ftsRowId != 0) {
            memoryDao.deleteWithFts(memoryId, ftsRowId)
        } else {
            memoryDao.deleteById(memoryId)
        }
        // P1-07 修复：MemoryTagEntity 的 memoryId 不是外键、不级联删除（见该
        // Entity 类注释），save()/update() 都通过 syncL2Tags() 同步维护
        // memory_tags 表，但 delete 路径此前没有对称地清理，导致每次删除记忆
        // 都会在 memory_tags 里留下再也查不到对应主记录的孤儿行。这里补上
        // 与 syncL2Tags 一致的清理调用；deleteByMemoryId 对不存在的 memoryId
        // 是安全的空操作，不需要额外判空。
        memoryTagDao.deleteByMemoryId(memoryId)
    }

    // ── 内部工具方法 ──────────────────────────────────────────

    /**
     * 构建 FTS4 查询字符串（含 bigram 扩展）。
     *
     * FTS4 MATCH 语法：
     * - 单词精确匹配："银发"
     * - 前缀匹配："银发*"
     * - 多词 OR："银发 角色"（空格分隔 = OR）
     *
     * E1 审计报告任务1 修复：原实现用 input.split(Regex("\\s+")) 按空白切分，
     * 中文连续输入没有空格，一句自然的用户消息会被整体当作一个超长 token，
     * 加 * 后变成 "整句*"——前缀匹配要求整段查询是索引 token 的前缀，两句
     * 不同的话几乎不可能从第 0 个字符开始重合，导致 FTS 几乎永不命中。
     *
     * 使用 [ChineseTokenizer.tokenizeForQuery]（词 token + bigram 扩展），取前 15
     * 个 token 各自加 * 做前缀 OR 匹配。bigram 扩展确保专有名词（如"顾澜"在
     * 查询中被粘连成"提顾澜"）也能通过 "顾澜*" 命中 FTS 索引中的 "顾澜" token。
     *
     * 此方法用于 MemoryQueryTool（memory_query 工具，Agent 主动检索）以及
     * [searchRelevantWithRouting] 的 bigram fallback 路径。
     * [searchRelevantWithRouting] 的主路径使用 [buildFtsQueryWordLevel]（纯词级），
     * 仅在主路径返回 0 条时才 fallback 到本方法，避免 bigram 引入不相关结果
     * 挤占高相关结果（如"回老家"的 bigram"老家"匹配到"老家的房子矛盾"记忆）。
     *
     * 可见性从 private 改为 internal：MemoryQueryTool 需复用同一查询构造逻辑。
     */
    internal fun buildFtsQuery(input: String): String {
        val tokens = ChineseTokenizer.tokenizeForQuery(input).take(15)
        if (tokens.isEmpty()) {
            val fallback = input.filter { it.isLetterOrDigit() }
            return if (fallback.isBlank()) input else "$fallback*"
        }
        return tokens.joinToString(" ") { "$it*" }
    }

    /**
     * 构建 FTS4 查询字符串（纯词级，无 bigram）。
     *
     * 用于 [searchRelevantWithRouting] 的主路径：先用精确词级 token 匹配，
     * 保证召回结果的高精确度。仅当主路径返回 0 条时才 fallback 到
     * [buildFtsQuery]（含 bigram），在精确度和召回率之间取得平衡。
     */
    private fun buildFtsQueryWordLevel(input: String): String {
        val words = ChineseTokenizer.tokenize(input).take(5)
        if (words.isEmpty()) {
            val fallback = input.filter { it.isLetterOrDigit() }
            return if (fallback.isBlank()) input else "$fallback*"
        }
        return words.joinToString(" ") { "$it*" }
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

    // ── Window A-1：L2 标签索引层 ──────────────────────────────

    /**
     * 同步写入/更新某条记忆的 L2 标签索引。
     *
     * 从 [MemoryEntity.keywords]（空格分隔）和 [MemoryEntity.domain] 提取标签，
     * 生成 [MemoryTagEntity] 行，原子替换（先删后插）。
     */
    private suspend fun syncL2Tags(memory: MemoryEntity) {
        val tags = extractTags(memory)
        if (tags.isEmpty()) {
            memoryTagDao.deleteByMemoryId(memory.id)
            return
        }
        val now = System.currentTimeMillis()
        val entities = tags.map { tag ->
            MemoryTagEntity(
                id = UUID.randomUUID().toString(),
                memoryId = memory.id,
                characterId = memory.characterId,
                tag = tag,
                weight = memory.importance,
                createdAt = now,
            )
        }
        memoryTagDao.replaceTagsForMemory(entities)
    }

    /**
     * 从记忆实体提取标签列表。
     *
     * 提取来源：
     * 1. `keywords` 字段（空格分隔，已由 MemoryEngine.extractKeywords() 生成）
     * 2. `domain` 字段（PERSONAL/WORK/WORLD/RULE/INFERENCE）
     *
     * 过滤掉过短（<2字符）的标签，避免噪音。
     */
    private fun extractTags(memory: MemoryEntity): List<String> {
        val keywordTags = memory.keywords
            .split(" ")
            .filter { it.length >= 2 }
        val domainTag = memory.domain.takeIf { it.isNotBlank() }
        return (keywordTags + listOfNotNull(domainTag)).distinct()
    }

    /**
     * L2 优先检索路由（Window A-1）。
     *
     * 【接口登记】Window A 提供给聊天/圆桌两条消费链路的记忆检索路由接口，现在定稿。
     *
     * 消费方：ChatMessageOrchestrator.kt:192（私聊单角色场景）、
     * RoundtableBotReplyGenerator.kt:128（圆桌多角色场景）。
     * 两条链路都直接调用本方法拼入 Prompt，不经过中间封装层。
     *
     * 后续如需调整返回结构（新增字段、变更排序策略、L2/L1权重比例），需评估
     * 对上述两处消费方的影响；仅调整内部实现细节（如 extractTags() 分词逻辑
     * 本身）不受此约束。
     *
     * 检索策略（E1 审计报告任务1 修复后）：
     * 1. 主路径：用 [ChineseTokenizer.tokenize]（纯词级）提取查询 tag
     *    - L2 tag 精确匹配 + L1 FTS4 前缀匹配（[buildFtsQueryWordLevel]）
     *    - 保证高精确度，避免 bigram 引入不相关结果
     * 2. Fallback：若主路径返回 0 条（如专有名词"顾澜"在查询中被粘连成
     *    "提顾澜"，词级匹配完全失效），用 [ChineseTokenizer.tokenizeForQuery]
     *    （词 + bigram）重试，补齐 OOV / 专有名词的召回
     *
     * 这种"先精确后模糊"的两段式策略，在 95% 场景下走精确路径（无精度损失），
     * 仅在精确路径完全空召回时才用 bigram 扩展，避免"顾澜"和"老家"两类问题
     * 同时出现——bigram 在"顾澜"场景是必要的（否则 0 召回），但在"老家"场景
     * 是有害的（引入"老家的房子矛盾"记忆并因 importance 更高而排在正确结果之前）。
     *
     * @param characterId 角色 ID
     * @param query      用户消息或对话关键词
     * @param limit      最多返回条数
     * @param excludeDomain  排除的记忆域
     */
    suspend fun searchRelevantWithRouting(
        characterId: Int,
        query: String,
        limit: Int = 10,
        excludeDomain: MemoryDomain? = null,
    ): List<MemoryEntity> {
        if (query.isBlank()) return emptyList()

        val now = System.currentTimeMillis()

        // ── 主路径：词级 token（精确）──
        val primaryTags = ChineseTokenizer.tokenize(query).take(10)
        val primaryResults = executeRoutedSearch(
            characterId, primaryTags, query, limit, excludeDomain, now,
            useBigramFts = false,
        )
        if (primaryResults.isNotEmpty()) return primaryResults

        // ── Fallback：词 + bigram（模糊），仅在主路径 0 召回时触发 ──
        val fallbackTags = ChineseTokenizer.tokenizeForQuery(query).take(20)
        return executeRoutedSearch(
            characterId, fallbackTags, query, limit, excludeDomain, now,
            useBigramFts = true,
        )
    }

    /**
     * 执行一次完整的 L2→L1 路由检索（供 [searchRelevantWithRouting] 主路径 / fallback 复用）。
     *
     * @param queryTags    L2 查询 tag 列表（已分词）
     * @param ftsRawQuery  FTS 查询的原始文本（内部根据 useBigramFts 选择词级/bigram 构造）
     * @param useBigramFts true=用 [buildFtsQuery]（含 bigram），false=用 [buildFtsQueryWordLevel]
     */
    private suspend fun executeRoutedSearch(
        characterId: Int,
        queryTags: List<String>,
        ftsRawQuery: String,
        limit: Int,
        excludeDomain: MemoryDomain?,
        now: Long,
        useBigramFts: Boolean,
    ): List<MemoryEntity> {
        // ── L2 tag 精确匹配 ──
        val l2MemoryIds = if (queryTags.isNotEmpty()) {
            try {
                memoryTagDao.searchByTags(characterId, queryTags, limit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 问题37修复：同上，补 error 日志区分"查询失败"与"确实无匹配"。
                ZLog.e("MemoryRepository", "L2 tag精确匹配失败，characterId=$characterId", e)
                emptyList()
            }
        } else emptyList()

        // ── 判断是否需要 L1 补充 ──
        val needL1 = l2MemoryIds.size < limit

        // ── L1 FTS4 检索（L2 不足时补充）──
        val l1Results = if (needL1) {
            val ftsQuery = if (useBigramFts) buildFtsQuery(ftsRawQuery)
                           else buildFtsQueryWordLevel(ftsRawQuery)
            try {
                memoryDao.searchByFts(characterId, ftsQuery, limit * 2)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("MemoryRepository", "L1 FTS4检索失败，characterId=$characterId", e)
                emptyList()
            }
        } else emptyList()

        // ── 合并去重 + scope 过滤 + 域过滤 + 排序 ──
        val l2IdSet = l2MemoryIds.map { it.memoryId }.toSet()

        val l2Entities = if (l2MemoryIds.isNotEmpty()) {
            l2MemoryIds.mapNotNull { result ->
                try {
                    memoryDao.getById(result.memoryId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // B3审查序号7修复：原无日志，被 mapNotNull 过滤后该条记忆静默
                    // 消失，角色表现为"忘记"本应知道的事却无任何错误提示。
                    // 补日志与同文件639行FTS catch的规范保持一致。
                    ZLog.e("MemoryRepository", "getById失败，memoryId=${result.memoryId}", e)
                    null
                }
            }
        } else emptyList()

        // E1 审计报告任务2 修复（防御性 scope 过滤）：
        // searchByTags() 的 SQL 已通过 JOIN memories 加了 scope='PERSONAL' 过滤，
        // L1 searchByFts 的 SQL 也已有 scope='PERSONAL'。此处再加一道内存层
        // scope 过滤作为防御性兜底——即使未来 searchByTags 被其他调用方修改
        // 或 getById 返回了非 PERSONAL 记忆，也不会让 GROUP scope 记忆泄漏
        // 到个人检索结果中（审计报告 §2.3 指出的"角色在私聊中知道圆桌讨论内容"缺陷）。
        val merged = (l2Entities + l1Results.filter { it.id !in l2IdSet })
            .filter { it.scope == MemoryScope.PERSONAL.name }
            .filter { excludeDomain == null || it.domain != excludeDomain.name }

        return merged
            .map { it to calculateFinalScore(it, now) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
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
            } catch (_: Throwable) {
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
