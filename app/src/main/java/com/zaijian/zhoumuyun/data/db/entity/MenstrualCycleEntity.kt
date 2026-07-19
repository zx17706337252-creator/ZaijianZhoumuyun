package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.zaijian.zhoumuyun.data.model.MenstrualCycleState

// ─────────────────────────────────────────────────────────────
//  MenstrualCycleEntity — 生理周期锚点持久化（v19 → v20）
//
//  每个角色至多一行（@PrimaryKey characterId），只持久化
//  「周期起点时间戳」和各人的周期参数。其余所有状态（当前阶段、
//  是否在排卵窗口）全部由 MenstrualCycleState.currentPhase() 实时算出，
//  不存入数据库，保持 DB 简洁。
//
//  初始化时机：ZaijianApp 启动时在 IO 协程里调用
//  MenstrualCycleRepository.initIfAbsent()，对数据库里没有记录的角色
//  用 DefaultCycleOffsetDays 计算并写入锚点，有记录的不覆盖。
// ─────────────────────────────────────────────────────────────

@Entity(tableName = "menstrual_cycle")
data class MenstrualCycleEntity(
    @PrimaryKey val characterId: Int,
    /** 本轮经期第一天的毫秒时间戳；null 表示尚未初始化 */
    val cycleAnchorAt: Long? = null,
    val cycleLengthDays: Int = 28,
    val menstrualDays: Int = 5,
    val fertileDays: Int = 6,
)

fun MenstrualCycleEntity.toDomain(): MenstrualCycleState = MenstrualCycleState(
    characterId      = characterId,
    cycleAnchorAt    = cycleAnchorAt,
    cycleLengthDays  = cycleLengthDays,
    menstrualDays    = menstrualDays,
    fertileDays      = fertileDays,
)

fun MenstrualCycleState.toEntity(): MenstrualCycleEntity = MenstrualCycleEntity(
    characterId      = characterId,
    cycleAnchorAt    = cycleAnchorAt,
    cycleLengthDays  = cycleLengthDays,
    menstrualDays    = menstrualDays,
    fertileDays      = fertileDays,
)
