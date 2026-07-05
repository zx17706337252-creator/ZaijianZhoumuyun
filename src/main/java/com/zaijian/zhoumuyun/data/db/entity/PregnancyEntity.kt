package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.zaijian.zhoumuyun.data.model.PregnancyState

// ─────────────────────────────────────────────────────────────
//  PregnancyEntity — D2.5 + D2.6（v21 → v22 → v23）
//
//  v22 变更（D2.5）：
//  - 删除 cycleDays（改为 PregnancyState.CYCLE_DAYS = 30 常量）
//  - 新增 consecutiveFailCount（连续失败次数）
//  - 新增 lastFailureInjectedAt（跨周期背景情绪注入冷却时间戳）
//
//  v23 变更（D2.6）：
//  - 新增 miscarriedAt（流产时间戳；null=未流产；怀孕开始时清零）
//
//  v24 变更（怀孕弹窗触发重构）：
//  - 新增 fertileWindowConsentAsked（本次排卵期窗口是否已弹过同意弹窗）
// ─────────────────────────────────────────────────────────────

@Entity(tableName = "pregnancy_state")
data class PregnancyEntity(
    @PrimaryKey val characterId: Int,
    val isPregnant: Boolean = false,
    val pregnancyStartedAt: Long? = null,
    val consecutiveFailCount: Int = 0,
    val lastFailureInjectedAt: Long? = null,
    /** D2.6：最近一次流产时间戳（null = 未流产）；开始新怀孕时清零 */
    val miscarriedAt: Long? = null,
    /** 怀孕弹窗触发重构：本次排卵期窗口内是否已经弹过同意弹窗 */
    val fertileWindowConsentAsked: Boolean = false,
)

fun PregnancyEntity.toDomain(): PregnancyState = PregnancyState(
    characterId               = characterId,
    isPregnant                = isPregnant,
    pregnancyStartedAt        = pregnancyStartedAt,
    consecutiveFailCount      = consecutiveFailCount,
    lastFailureInjectedAt     = lastFailureInjectedAt,
    miscarriedAt              = miscarriedAt,
    fertileWindowConsentAsked = fertileWindowConsentAsked,
)

fun PregnancyState.toEntity(): PregnancyEntity = PregnancyEntity(
    characterId               = characterId,
    isPregnant                = isPregnant,
    pregnancyStartedAt        = pregnancyStartedAt,
    consecutiveFailCount      = consecutiveFailCount,
    lastFailureInjectedAt     = lastFailureInjectedAt,
    miscarriedAt              = miscarriedAt,
    fertileWindowConsentAsked = fertileWindowConsentAsked,
)
