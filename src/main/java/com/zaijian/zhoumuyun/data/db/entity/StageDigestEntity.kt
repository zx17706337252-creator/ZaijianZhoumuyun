package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 阶段摘要实体（P6 专长进化系统 · 三层蒸馏的第2层）
 *
 * 当某专长下 digestStatus="RAW" 的 PracticeRecord 累积到阈值（默认10条），
 * DistillationTrigger 将其蒸馏成一条 StageDigest。摘要按 comparisonResult
 * 分层处理权重：REINFORCING 类记录被概括为一句话，EMERGING 类需具体描述，
 * CONFLICTING 类完整保留（见设计方案第5.2节）。
 *
 * mergedIntoProfile=false 的阶段摘要继续累积，达到阈值（默认3条）后
 * 进一步蒸馏并入 SpecialtyProfileEntity.styleNotes（第三层）。
 * 合并后 mergedIntoProfile 改为 true，但记录本身不删除——阶段摘要这一层
 * 同样需要在专长档案页可追溯查看。
 */
@Entity(
    tableName = "stage_digests",
    indices = [
        Index(value = ["specialtyId"]),
        Index(value = ["specialtyId", "createdAt"]),
        Index(value = ["specialtyId", "mergedIntoProfile"]),
        Index(value = ["characterId"]),
    ]
)
data class StageDigestEntity(
    @PrimaryKey val id: String,

    val characterId: Int,

    val specialtyId: String,

    /** 本阶段摘要正文：从多条 PracticeRecord 蒸馏而来，建议200-400字 */
    val digestContent: String,

    /** 本阶段覆盖的练习记录数量 */
    val sourceRecordCount: Int,

    /** 本阶段时间范围 */
    val periodStart: Long,
    val periodEnd: Long,

    /** 本次蒸馏是否检测到与已有 styleNotes 冲突的内容（生成时即标注，供后续处理参考） */
    val hasConflict: Boolean = false,

    /** 若 hasConflict=true，简述冲突点 */
    val conflictSummary: String = "",

    /**
     * 是否已并入 styleNotes：
     *   false = 仍是独立阶段摘要，等待下一轮蒸馏
     *   true  = 已被合并进 SpecialtyProfile.styleNotes，本记录降级为历史索引
     */
    val mergedIntoProfile: Boolean = false,

    val createdAt: Long,
)
