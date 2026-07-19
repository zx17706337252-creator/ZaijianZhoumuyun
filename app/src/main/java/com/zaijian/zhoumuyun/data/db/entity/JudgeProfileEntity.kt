package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 裁判档案实体（裁判与竞争机制 · 裁判标准训练）
 *
 * 一个角色在某个项目方向上可以拥有一份裁判档案。
 * 档案由系统在角色第一次被指定为裁判时懒创建，
 * standardNotes="" + maturityStage="EXPLORING" 表示"按自己审美评"。
 *
 * standardNotes 是核心字段：评判标准说明书，整段覆盖写，
 * 训练路径复用 SpecialtyProfile 的候选修正池机制
 * （candidateCorrectionsJson / matchCandidateObservation / 3次转正阈值）。
 *
 * maturityStage 与 competition_weight_configs 的 judgeTrustDynamicEnabled
 * 共同决定裁判权重的信任系数（EXPLORING=0.5 / FORMING=0.8 / STABLE=1.0）。
 */
@Entity(
    tableName = "judge_profiles",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["characterId", "domain"], unique = true),
    ]
)
data class JudgeProfileEntity(
    @PrimaryKey val id: String,

    val characterId: Int,

    /** 评判的项目方向，与 specialty_profiles.domain 语义一致 */
    val domain: String,

    /** 用户最初训练时说的原话，整段保留，作为标准训练的校准基准 */
    val anchorIntent: String,

    /**
     * 评判标准说明书，整段覆盖写。
     * 初始为空字符串，裁判先按自身审美评；
     * 通过候选修正池训练后逐步写入。
     */
    val standardNotes: String = "",

    /** 已主持过的裁判次数（每次 finalizeRound 后递增） */
    val judgeCount: Int = 0,

    /**
     * 裁判成熟度：
     *   "EXPLORING" 摸索期：信任系数 0.5
     *   "FORMING"   成型期：信任系数 0.8
     *   "STABLE"    稳定期：信任系数 1.0
     */
    val maturityStage: String = "EXPLORING",

    /**
     * 候选修正池：JSON 数组字符串，结构同 specialty_profiles.candidateObservationsJson。
     * 单次反馈先记候选，matchCandidateObservation 语义匹配达到3次阈值后
     * 触发确认流程正式写入 standardNotes。
     */
    val candidateCorrectionsJson: String = "[]",

    /** 是否存在未解决的评判标准冲突 */
    val hasUnresolvedConflict: Boolean = false,

    /** 若 hasUnresolvedConflict=true，描述冲突内容 */
    val unresolvedConflictDescription: String = "",

    /** 该裁判档案是否生效（用户可停用） */
    val isActive: Boolean = true,

    val lastJudgedAt: Long = 0,

    val createdAt: Long,
    val updatedAt: Long,
)
