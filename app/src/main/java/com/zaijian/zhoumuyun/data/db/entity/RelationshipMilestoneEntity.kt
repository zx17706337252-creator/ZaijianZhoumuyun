package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
//  RelationshipMilestoneEntity — P3（V5 执行方案）
//
//  背景：RelationshipEntity 只有数值（trust/affection/conflict/...），
//  没有字段记录"为什么是这个数字"。原本 RelationshipEntity.sourceEventId
//  是单一字段，一对角色关系只有一行记录，新值会直接覆盖旧值——撑不住
//  "先紧张、后和好"这种完整弧线，只能反映最新一次发生了什么。
//
//  这张表只追加、不覆盖：每个真正的关系转折点（不是日常数值微调）
//  落一条记录，Prompt 拼装时可以查询最近 1-2 条，让角色记得"曾经经历过什么"，
//  不只是"现在是什么状态"。
//
//  sourceEventId 指向 WorldEventEntity.id（系统唯一事实来源），
//  不指向 MemoryEntity（二次提炼产物，可能被裁剪/合并，容易悬空）。
// ─────────────────────────────────────────────────────────────

enum class RelationshipMilestoneDirection {
    /** 关系恶化节点，如"温泉夜事件后关系降到冰点" */
    WORSENED,
    /** 关系缓和/和好节点，如"扣子事件后关系缓和了不少" */
    REPAIRED,
    /** A9-3 修复：关系阶段跃迁节点（STRANGER→FAMILIAR→TRUSTED→IMPORTANT→CORE）。
     *  多次小 delta 累积导致 stage 跃迁但单次 delta 不足阈值时，由 applyDelta
     *  内的跃迁检测主动记录，弥补 maybeRecordMilestoneFromDelta 的单次阈值盲区。 */
    STAGE_TRANSITION,
}

@Entity(
    tableName = "relationship_milestones",
    indices = [
        Index(value = ["fromId", "toId"]),
        Index(value = ["createdAt"]),
    ],
)
data class RelationshipMilestoneEntity(
    @PrimaryKey val id: String,
    val fromId: String,
    val toId: String,
    /** RelationshipMilestoneDirection.name */
    val direction: String,
    /** 一句话描述，如"温泉夜事件后关系降到冰点" */
    val description: String,
    /** 关联的 WorldEvent ID，可空 */
    val sourceEventId: String? = null,
    val createdAt: Long,
)
