package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
//  MemoryDomain — 记忆所属领域
// ─────────────────────────────────────────────────────────────

enum class MemoryDomain {
    PERSONAL,   // 用户偏好、习惯、关系、情绪模式
    WORK,       // 项目知识、任务经验、工作流程
    WORLD,      // 角色行为、角色互动、世界事件、关系演化
    RULE,       // Phase 25：从高分 Session 提炼的能力规则（由 Phase 26 rule_distill 写入）
    INFERENCE,  // Phase 5（zaijian）：角色的隐性推测记忆，注入时自动加「（我的猜测）」前缀
}

// ─────────────────────────────────────────────────────────────
//  MemoryScope — 记忆归属范围（待办3）
//
//  PERSONAL：个人记忆，仅属于特定角色（默认，向后兼容）。
//  GROUP   ：群记忆，属于圆桌会话的全员；由 roundtableId 关联圆桌。
// ─────────────────────────────────────────────────────────────

enum class MemoryScope {
    PERSONAL,
    GROUP,
}

@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["characterId", "domain"]),
        Index(value = ["characterId", "importance"]),
        Index(value = ["characterId", "isCore"]),
        Index(value = ["characterId", "isEternal"]),
        Index(value = ["projectId"]),
        Index(value = ["createdAt"]),
        Index(value = ["lastAccessedAt"]),
        // 待办3：群记忆按 roundtableId 查询需要单独索引
        Index(value = ["roundtableId"]),
    ]
)
data class MemoryEntity(
    @PrimaryKey val id: String,

    /** 所属角色 ID（发言人 id，不改为空，保留来源追溯语义） */
    val characterId: Int,

    /** 记忆所属领域 */
    val domain: String,         // MemoryDomain.name

    /**
     * 待办3：记忆归属范围。
     * - PERSONAL（默认）：仅属于 characterId 角色的个人记忆
     * - GROUP：圆桌场景下全员共享的群记忆，需配合 roundtableId 使用
     */
    val scope: String = MemoryScope.PERSONAL.name,

    /**
     * 待办3：群记忆所属的圆桌 ID（可空）。
     * scope=GROUP 时必须非空；scope=PERSONAL 时为 null。
     * 复用待办7已验证的格式：排序后 characterId 用 '_' 拼接的组合串。
     */
    val roundtableId: String? = null,

    /** 记忆内容（提炼后的自然语言描述） */
    val content: String,

    /** 重要度 1-5 */
    val importance: Int,

    /**
     * 关键词列表（空格分隔字符串，同步写入 FTS4 虚拟表）。
     * FTS4 检索依赖此字段，写入时需同步更新 memories_fts。
     */
    val keywords: String,       // 空格分隔，如 "银发 角色 偏好"

    /** 来源 WorldEvent ID，可追溯到具体事件 */
    val sourceEventId: String?,

    /** 是否为 Core Memory（importance=5，永不自动删除，每次都注入 Prompt） */
    val isCore: Boolean = false,

    /**
     * 是否为永恒状态记忆（生育记录等，永不参与蒸馏淘汰）。
     * 与 isCore 的区别：isEternal 由 writeEternalMemory() 直接写入，
     * 不经过候选层，不受蒸馏/过期清理影响，每次 Prompt 必然注入。
     */
    val isEternal: Boolean = false,

    /**
     * Phase 25：是否为已锁定的 RULE 记忆（isLocked=true 的规则才注入 Rule Layer）。
     *
     * 锁定条件（Phase 26 rule_distill 执行时判断）：
     *   - 该条规则置信度 ≥ 4.0
     *   - 至少出现在 3 次高分 EvaluationSession 提炼结果中
     *
     * 仅对 domain=RULE 的记忆有意义；其他 domain 此字段保持 false。
     */
    val isLocked: Boolean = false,

    /**
     * Phase 25：关联的学习目标 ID（仅 domain=RULE 时使用）。
     * Rule 按目标分组注入 Rule Layer，每目标最多注入 10 条规则。
     * 非 RULE 域记忆此字段为 null。
     */
    val goalId: String? = null,

    /**
     * 关联项目 ID（可空）。
     * ★ v3 新增：Project Memory 归属 MemoryDomain.WORK 且此字段非空。
     */
    val projectId: String? = null,

    /** 被 Prompt 检索召回的次数（影响 FinalScore 权重） */
    val accessCount: Int = 0,

    /**
     * FTS4 虚拟表的 rowid（与 memories_fts.rowid 对应，用于 JOIN）。
     *
     * S1 修复：memories 主表的 SQLite rowid（自增整型）与
     * memories_fts 虚拟表中通过 id.hashCode() 写入的 rowid 是两个不同的值，
     * 直接 JOIN ON m.rowid = fts.rowid 永远无法匹配，FTS 召回完全失效。
     * 修复方案：在主表显式存储写入 FTS 时使用的 rowid，
     * DAO 中 JOIN 改为 m.ftsRowId = fts.rowid，保证两侧数值一致。
     *
     * 历史已存在的记忆（升级前写入）ftsRowId 为 0（DEFAULT 0），
     * 会导致这些记忆 FTS 检索不到（但它们的 FTS 行本就是错误映射的），
     * 等 MemoryEngine 下次重写时自动修正。
     */
    val ftsRowId: Int = 0,

    val createdAt: Long,
    val updatedAt: Long,

    /** 最后一次被 Prompt 召回的时间（用于计算 recency_score） */
    val lastAccessedAt: Long,
)

// ─────────────────────────────────────────────────────────────
//  MemoryFtsEntity — FTS4 全文检索虚拟表
//
//  与 memories 主表同步写入，通过 rowid 关联。
//  仅存储用于检索的字段：content + keywords。
//  查询示例：SELECT * FROM memories_fts WHERE memories_fts MATCH :query
// ─────────────────────────────────────────────────────────────

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "memories_fts")
data class MemoryFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int,
    val content: String,
    val keywords: String,
)

// ─────────────────────────────────────────────────────────────
//  MemoryCandidateEntity — 记忆候选层
//
//  所有记忆必须先进入此表，经过打分后再决定是否写入 memories。
//  禁止直接向 memories 写入，必须经过 MemoryEngine.process()。
//
//  候选分数规则：
//  score 1 → 直接丢弃（不写入 memories）
//  score 2 → 写入，importance=2（7天后可删）
//  score 3-5 → 写入，importance=score
// ─────────────────────────────────────────────────────────────

@Entity(
    tableName = "memory_candidates",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["sourceEventId"]),
        Index(value = ["isProcessed"]),
        Index(value = ["createdAt"]),
    ]
)
data class MemoryCandidateEntity(
    @PrimaryKey val id: String,

    /** 所属角色 ID */
    val characterId: Int,

    /** 触发此候选的 WorldEvent ID（可空：手动写入或无来源事件时为 null） */
    val sourceEventId: String? = null,

    /** 候选记忆内容（原始文本，尚未提炼） */
    val content: String,

    /** 候选分数 1-5（1=丢弃，2-5=写入对应 importance） */
    val score: Int,

    /** 记忆领域 */
    val domain: String,         // MemoryDomain.name

    /**
     * 待办3：候选归属范围（与 MemoryEntity.scope 对应）。
     * 候选最终原样晋升为正式记忆，候选阶段就需要知道归属。
     */
    val scope: String = MemoryScope.PERSONAL.name,

    /**
     * 待办3：群记忆所属圆桌 ID（可空，scope=GROUP 时非空）。
     */
    val roundtableId: String? = null,

    /**
     * 关联项目 ID（可空）。
     * ★ v3 新增：Project 相关事件产生的候选带此字段。
     */
    val projectId: String? = null,

    /** 是否已处理（写入 memories 或丢弃） */
    val isProcessed: Boolean = false,

    /** 处理后生成的 Memory ID（如果 score > 1） */
    val resultMemoryId: String? = null,

    val createdAt: Long,
)
