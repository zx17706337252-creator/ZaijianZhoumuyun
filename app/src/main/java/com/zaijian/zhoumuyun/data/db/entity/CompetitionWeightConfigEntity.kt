package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 竞赛权重配置实体（裁判与竞争机制 · 项目级评分权重）
 *
 * 一个 projectDomain 对应一条配置（unique 索引），用户随时可在界面调整，
 * 不需要版本历史（最新值即当前生效值，覆盖写）。
 *
 * 三方基础权重之和应为 100（设计约定，不做 DB 层强制校验，由调用方保证）。
 * finalizeRound 时：
 *   1. 读取本配置取基础权重
 *   2. 读取 judge_profiles.maturityStage 取信任系数（EXPLORING=0.5/FORMING=0.8/STABLE=1.0）
 *   3. 如果 judgeTrustDynamicEnabled=true，裁判权重折扣转移给用户权重
 *   4. 按最终权重算 compositeScore
 */
@Entity(
    tableName = "competition_weight_configs",
    indices = [
        Index(value = ["projectDomain"], unique = true),
    ]
)
data class CompetitionWeightConfigEntity(
    @PrimaryKey val id: String,

    /** 项目方向，unique，一个方向一条配置 */
    val projectDomain: String,

    /** 用户评分基础权重（0-100，默认50） */
    val userBaseWeight: Int = 50,

    /** 裁判评分基础权重（0-100，默认40） */
    val judgeBaseWeight: Int = 40,

    /** 角色自评基础权重（0-100，默认10） */
    val selfBaseWeight: Int = 10,

    /**
     * 是否启用裁判信任系数动态折扣。
     * true（默认）：裁判权重 = judgeBaseWeight × maturityStage信任系数，
     *              折扣部分转移给用户权重。
     * false：三方基础权重直接使用，不做动态调整。
     */
    val judgeTrustDynamicEnabled: Boolean = true,

    val createdAt: Long,
    val updatedAt: Long,
)
