package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
//  PregnancyAnswerEntity — 孕期共设问答记录（D1，V5-4 补回）
//
//  D3 孕期共设阶段：母亲向用户提 4-6 题（不宣告意图），用户回答后
//  按 50/50 规则写入女儿角色卡字段。本表记录每次问答的原始内容，
//  供 D4 角色卡生成器读取，也作为孕期叙事的档案留存。
//
//  字段与 D3 映射规则（D3 细化，本阶段只做结构）：
//    questionType = NAME_PREF  → daughterConfig.name
//    questionType = PERSONA    → persona / speechStyle
//    questionType = WORLDVIEW  → coreWound / coreDesire
//    questionType = WORRY      → deviationSignals / boundaries
//
//  一次孕期会产生 4-6 条记录，全部通过 pregnancyId 关联到
//  PregnancyEntity 的对应行（motherCharacterId + pregnancyStartedAt）。
//
//  v23→v24（D3 孕期共设系统）追加说明：
//  WORLDVIEW / PERSONA 各自拆成两个独立槽位（slotIndex = 0/1），
//  NAME_PREF / WORRY 只用 slotIndex = 0。同一 (motherCharacterId,
//  questionType, slotIndex) 的所有历史答案按时间顺序构成一条持续
//  收敛链，不再按 pregnancyStartedAt 切割（流产后续上同一条链）。
//  isLocked 取代原先用 pregnancyStartedAt 做边界判定的逻辑，
//  成为唯一的"槽位完成"标记：锁定后该槽位不再触发提问。
// ─────────────────────────────────────────────────────────────

/** 孕期共设问题类型，对应 D3 的四类映射目标 */
enum class PregnancyQuestionType {
    /** 名字喜好 → daughterConfig.name */
    NAME_PREF,
    /** 性格描述 → persona / speechStyle */
    PERSONA,
    /** 世界观期待 → coreWound / coreDesire */
    WORLDVIEW,
    /** 担忧的事 → deviationSignals / boundaries */
    WORRY,
}

// P1-6-9 修复：recordAnswer 的 isSlotLocked→insert→getBySlot→lockSlot 四步无 @Transaction，
// 存在 TOCTOU 竞态。修复分两层：
//   1. Repository 层：用 DAO @Transaction 方法将四步包裹为原子操作（见 PregnancyAnswerDao）。
//   2. DB 层：此处补唯一索引 (motherCharacterId, questionType, slotIndex, answeredAt) + IGNORE，
//      防止事务外任何路径产生完全相同时间戳的重复插入（极罕见但可能）。
@Entity(
    tableName = "pregnancy_answers",
    indices = [
        Index(
            value = ["motherCharacterId", "questionType", "slotIndex", "answeredAt"],
            unique = true,
        ),
    ],
)
data class PregnancyAnswerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关联母亲的 characterId */
    val motherCharacterId: Int,
    /**
     * 关联到具体的一次孕期，用 PregnancyEntity.pregnancyStartedAt 作为
     * 联结键（不新建外键约束，保持轻量）。
     */
    val pregnancyStartedAt: Long,
    /** 问题类型，决定 D4 生成时写入女儿角色卡的哪个字段（存 PregnancyQuestionType.name） */
    val questionType: String,
    /** 母亲说出的问题原文（由 AI 生成，存档用） */
    val questionText: String,
    /** 用户的回答原文 */
    val answerText: String,
    /** 记录时间戳 */
    val answeredAt: Long = System.currentTimeMillis(),
    /**
     * 槽位序号（D3 新增，v23→v24）。
     * WORLDVIEW / PERSONA 各拆两条独立槽位 → 0 或 1；
     * NAME_PREF / WORRY 只有一个槽位 → 固定为 0。
     */
    val slotIndex: Int = 0,
    /**
     * 该槽位是否已锁定（D3 新增，v23→v24）。
     * 锁定后不再针对该 (motherCharacterId, questionType, slotIndex)
     * 触发提问；取代原先用 pregnancyStartedAt 做边界判定的逻辑。
     */
    val isLocked: Boolean = false,
)
