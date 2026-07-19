package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.MenstrualCycleDao
import com.zaijian.zhoumuyun.data.db.entity.MenstrualCycleEntity
import com.zaijian.zhoumuyun.data.db.entity.toDomain
import com.zaijian.zhoumuyun.data.db.entity.toEntity
import com.zaijian.zhoumuyun.data.model.DefaultCycleOffsetDays
import com.zaijian.zhoumuyun.data.model.MenstrualCycleState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─────────────────────────────────────────────────────────────
//  MenstrualCycleRepository
//
//  职责：
//  1. 初始化——App 启动时为所有尚无记录的角色写入默认锚点
//     （用 DefaultCycleOffsetDays 错开九人的起点，视觉上不同步）
//  2. 读取——给 UI（BookCard 指示点）和 D2 解锁判定提供当前周期状态
//  3. 写入——允许手动重置某角色的周期起点（如剧情需要）
//
//  范围边界：本类只管理周期锚点数据，不接入 CharacterStateLayer、
//  不接入 Presence 行为联动，不接入 WorldSimulation。
//  怀孕状态（isPregnant）由调用方从 PregnancyRepository 获取并传入
//  MenstrualCycleState.currentPhase()，本类不持有怀孕状态。
// ─────────────────────────────────────────────────────────────

class MenstrualCycleRepository(
    private val dao: MenstrualCycleDao,
) {

    // ── 初始化 ────────────────────────────────────────────────

    /**
     * App 启动时在 IO 协程中调用一次。对数据库中尚无记录的角色，
     * 用 DefaultCycleOffsetDays 计算锚点并写入；已有记录的角色不覆盖。
     *
     * 基准时间取 [baseNow]（默认当前时间），九人按各自偏移天数向前推，
     * 使得「基准日」落在各人周期的第 (偏移%cycleLengthDays+1) 天。
     */
    suspend fun initIfAbsent(baseNow: Long = System.currentTimeMillis()) {
        for ((characterId, offsetDays) in DefaultCycleOffsetDays) {
            // P2-10 修复：改用 DAO 层的 initIfAbsent（@Transaction），
            // 将 count→insert 两步合并为原子操作，消除 TOCTOU 竞态。
            val anchor = baseNow - offsetDays.toLong() * 86_400_000L
            dao.initIfAbsent(
                MenstrualCycleEntity(
                    characterId     = characterId,
                    cycleAnchorAt   = anchor,
                )
            )
        }
    }

    // ── 读取 ──────────────────────────────────────────────────

    /**
     * 监听指定角色的周期状态；DB 中无记录时 fallback 到锚点为 null
     * 的默认值（currentPhase() 会安全返回 SAFE）。
     */
    fun observe(characterId: Int): Flow<MenstrualCycleState> =
        dao.observe(characterId).map { entity ->
            entity?.toDomain() ?: MenstrualCycleState(characterId = characterId)
        }

    suspend fun get(characterId: Int): MenstrualCycleState =
        dao.get(characterId)?.toDomain() ?: MenstrualCycleState(characterId = characterId)

    // ── 写入 ──────────────────────────────────────────────────

    /**
     * 重置某角色的周期起点为「今天是经期第一天」。
     * 叙事/剧情需要时调用（如角色来了月经的叙事描写触发后手动对齐）。
     *
     * 审查报告问题14修复：原来 Repository 层"先 dao.get() 读取，再
     * dao.upsert() 写入"两步无事务保护，两个并发重置可能互相覆盖。
     * 现改为调用 DAO 层新增的 @Transaction 方法，读写在同一事务内完成。
     */
    suspend fun resetAnchorToToday(characterId: Int, now: Long = System.currentTimeMillis()) {
        dao.resetAnchorToToday(characterId, now)
    }

    /**
     * 完整 upsert——直接替换整行（用于测试或精确调整起点）。
     */
    suspend fun upsert(state: MenstrualCycleState) = dao.upsert(state.toEntity())
}
