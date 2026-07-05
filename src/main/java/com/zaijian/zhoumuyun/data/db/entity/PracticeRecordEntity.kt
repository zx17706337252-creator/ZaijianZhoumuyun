package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 修炼记录实体（P6 专长进化系统 · 三层蒸馏的第1层：原始产出）
 *
 * DailyPracticeWorker 每天为每个生效中的 SpecialtyProfile 产出一条记录。
 * 记录的生命周期：RAW（原始全文是主要查询对象）→ DIGESTED（已被蒸馏进某个
 * StageDigest，content 字段被替换为占位提示，原文移入 PracticeRecordArchiveEntity）。
 * MILESTONE 状态用于用户手动确认过的节点，蒸馏判断流程不会自动将其降级。
 */
@Entity(
    tableName = "practice_records",
    indices = [
        Index(value = ["specialtyId"]),
        Index(value = ["specialtyId", "createdAt"]),
        Index(value = ["specialtyId", "digestStatus"]),
        Index(value = ["characterId"]),
    ]
)
data class PracticeRecordEntity(
    @PrimaryKey val id: String,

    val characterId: Int,

    /** 关联的专长档案 ID */
    val specialtyId: String,

    /** 本次练习的主题/角度，AI自己定，如"离别场景的留白写法" */
    val practiceTopic: String,

    /**
     * 产出全文。
     * digestStatus="DIGESTED" 时，本字段会被替换为占位提示字符串
     * （如 "[已蒸馏，原文见阶段摘要 #digestId]"），完整原文转存
     * PracticeRecordArchiveEntity，不在日常查询路径中携带大段文本。
     */
    val content: String,

    /**
     * 风格比对结果，三选一：
     *   "REINFORCING" 重复确认型（延续已有特征，蒸馏时权重最低）
     *   "EMERGING"    候选新特征（尚未转正，写入候选观察池）
     *   "CONFLICTING" 风格分歧（与已有styleNotes冲突，蒸馏时完整保留更久）
     */
    val comparisonResult: String,

    /** 风格比对的简短说明（LLM生成，≤100字，解释判断依据） */
    val comparisonNote: String,

    /** 若 comparisonResult="EMERGING"，记录观察到的具体特征描述；否则为空 */
    val observedTrait: String = "",

    /**
     * 蒸馏状态：
     *   "RAW"        原始全文仍是主要查询对象
     *   "DIGESTED"   已被蒸馏进某个 StageDigest，本记录降级为索引
     *   "MILESTONE"  用户手动确认过的里程碑，永久保护，不会被普通蒸馏判断流程降级
     */
    val digestStatus: String = "RAW",

    /** 蒸馏后归入的 StageDigest ID，digestStatus=DIGESTED 时非空 */
    val digestedIntoId: String? = null,

    val createdAt: Long,
)
