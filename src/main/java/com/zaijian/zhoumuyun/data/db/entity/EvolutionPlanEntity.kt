package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 专长进化方案实体（P6 专长进化系统）
 *
 * 与 Phase 22 的 AgentPlanEntity 不同：AgentPlanEntity 同一角色只保留一条
 * isActive=true 的方案，写新版本时旧方案语义上"归档不可见"。
 * EvolutionPlanEntity 要求历史版本同样可查（专长档案页需要展示方案随进展
 * 自我调整的演变过程），所以 isActive=false 的旧记录不会被任何查询路径
 * 排除，只是不再作为"当前生效方案"参与 DailyPracticeWorker 的逐日练习。
 *
 * 每个 SpecialtyProfile 对应一串 EvolutionPlan 版本链，version 从1开始递增，
 * 同一 specialtyId 任意时刻只有一条 isActive=true。
 */
@Entity(
    tableName = "evolution_plans",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["characterId", "specialtyId"]),
        Index(value = ["characterId", "isActive"]),
        // P1-6-1 修复补充：并发写入时 db.withTransaction 保证串行，但唯一约束是数据库层兜底，
        // 防止事务外任何路径（如旧版迁移/测试写入）产生重复版本号。IGNORE 策略配合使用。
        Index(value = ["specialtyId", "version"], unique = true),
    ]
)
data class EvolutionPlanEntity(
    @PrimaryKey val id: String,

    val characterId: Int,

    /** 关联的专长档案 ID（SpecialtyProfileEntity.id） */
    val specialtyId: String,

    /** 方案版本号，从1开始，每次AI自我修订+1，不覆盖旧版本 */
    val version: Int,

    /** 方案正文：AI自己规划的阶段性安排，叙述体而非清单 */
    val content: String,

    /**
     * 触发本次修订的原因，三类：
     *   "USER_INITIATED"  用户首次布置方向
     *   "SELF_ADJUSTED"   AI根据进展自我调整（见 DailyPracticeWorker 触发条件）
     *   "USER_REQUESTED"  用户手动要求重新规划
     */
    val revisionReason: String,

    /** 是否为当前生效版本，同一 specialtyId 只有一条 isActive=true */
    val isActive: Boolean = true,

    val createdAt: Long,
)
