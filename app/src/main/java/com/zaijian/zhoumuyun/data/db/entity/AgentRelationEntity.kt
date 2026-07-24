package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.zaijian.zhoumuyun.data.model.AgentRelationStage

// ─────────────────────────────────────────────────────────────
//  AgentRelationEntity — 女儿 Agent 与用户的关系状态（D1）
//
//  每个女儿 Agent 一行（@PrimaryKey daughterId）。
//
//  ⚠️ daughterId 编号规则更新（D4 实装后生效，本注释为最新口径）：
//  daughterId 由 DaughterIdAllocator 全局顺序发号，从 1000 起跳
//  （1000、1001、1002……），与母亲的 characterId 没有数学关系，
//  不能用 daughterId / 100 反推母亲是谁。
//  （早期 D1 设计稿曾设想过 motherCharacterId × 100 + 生育序号的方案，
//  例如蒂法 daughterId=101，但 D4 实际落地时改用了全局顺序发号，
//  本类原先的注释还停留在旧方案，已更新，避免误导后续开发。）
//  按母亲查询请用下面这个独立字段 motherCharacterId，不要对 daughterId 做除法。
//
//  范围边界：
//  - 不并入九位母亲共用的 CharacterStateLayer 五维结构
//  - 阶段切换触发条件在 D5 细化，本阶段只做数据结构
//  - interactionCount 作为阶段切换的参考计数器，具体阈值 D5 决定
// ─────────────────────────────────────────────────────────────

@Entity(tableName = "agent_relation")
data class AgentRelationEntity(
    /** 女儿唯一 ID，由 DaughterIdAllocator 全局顺序发号（1000 起跳），与母亲 characterId 无数学关系 */
    @PrimaryKey val daughterId: Int,
    /** 对应母亲的 characterId（1-9） */
    val motherCharacterId: Int,
    /** 当前关系阶段（以 AgentRelationStage.name 存储；Room 无 TypeConverter 支持枚举列，与项目其余实体统一用 String 存储的约定对齐） */
    val stage: String = AgentRelationStage.STAGE_1_INITIAL.name,
    /** 与用户的累计有效交互次数（D5 用于阶段切换判定的参考字段） */
    val interactionCount: Int = 0,
    /** 关系建立时间戳（女儿 Agent 首次生成时写入） */
    val createdAt: Long = System.currentTimeMillis(),
    /** 最近一次阶段升级的时间戳；null 表示尚未升级过 */
    val lastStageUpAt: Long? = null,
)
