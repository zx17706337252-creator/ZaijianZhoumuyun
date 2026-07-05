package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 专长档案实体（P6 专长进化系统 · 三层蒸馏的第3层：核心风格）
 *
 * 一个角色可以同时拥有多个专长方向（如"文学创作"和"情感对话"各一份档案），
 * 每份档案独立积累、独立蒸馏、独立判断是否晋升 Identity Layer。
 *
 * styleNotes 是核心字段：整段覆盖写的风格说明书，类比成长笔记而非技巧清单。
 * 硬上限 1000 字（与 character_identity.narrativeMemory 同量级），超出
 * 触发强制蒸馏压缩（见 DistillationEngine.mergeStageDigestsIntoProfile）。
 *
 * candidateObservationsJson 存放尚未转正的特征观察，每条记录出现次数；
 * 达到转正阈值（默认3次）后，摸索期/成型期走用户确认流程才正式写入
 * styleNotes，稳定期走更细粒度的"强化/补充/冲突"判断（见方案第5.3/5.5节）。
 */
@Entity(
    tableName = "specialty_profiles",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["characterId", "domain"]),
        Index(value = ["characterId", "isActive"]),
    ]
)
data class SpecialtyProfileEntity(
    @PrimaryKey val id: String,

    val characterId: Int,

    /** 专长方向，用户指定，如"文学创作"、"情感对话" */
    val domain: String,

    /** 用户最初设定方向时的原话，整段保留，作为后续所有蒸馏的校准基准 */
    val anchorIntent: String,

    /**
     * 风格说明书本体，整段覆盖写。
     * 硬上限 1000 字，超出触发蒸馏倒逼压缩。
     */
    val styleNotes: String = "",

    /** 已完成的修炼次数，决定 maturityStage 的判定依据之一 */
    val practiceCount: Int = 0,

    /**
     * 成熟度：
     *   "EXPLORING" 摸索期（1-5次）：候选特征不轻易转正
     *   "FORMING"   成型期（6-15次）：反复出现的特征经用户确认后转正
     *   "STABLE"    稳定期（16次+）：新观察走"强化/补充/冲突"判断，不简单追加
     */
    val maturityStage: String = "EXPLORING",

    /**
     * 候选观察池：JSON数组字符串，存尚未转正的特征观察。
     * 结构：[{"trait":"...","firstSeenAt":...,"occurrenceCount":...,"lastSeenAt":...}]
     */
    val candidateObservationsJson: String = "[]",

    /**
     * 是否存在未解决的风格分歧（阶段摘要并入 styleNotes 时检测到的冲突）。
     * true 时晋升 Identity Layer 的判定流程会直接拒绝（见方案第6.2节条件4）。
     */
    val hasUnresolvedConflict: Boolean = false,

    /** 若 hasUnresolvedConflict=true，描述分歧内容，供用户在专长档案页裁决 */
    val unresolvedConflictDescription: String = "",

    /**
     * 是否已晋升至 Identity Layer（并入 character_identity.soulNote）。
     * true 后，styleNotes 中已晋升的部分会被移除（避免重复注入同一信息），
     * 仅保留尚未晋升的新动态。一份专长档案可以多次触发晋升评估
     * （新一轮稳定特征出现时），所以本字段表示"当前是否存在已晋升内容"，
     * 不代表这份档案再也不会有新的 styleNotes 积累。
     */
    val promotedToIdentity: Boolean = false,

    /** 用户是否至少有过一次主动确认互动（候选转正确认 / 专长档案页认可操作），晋升判定条件3 */
    val hasUserConfirmedAtLeastOnce: Boolean = false,

    /** 该专长方向是否仍在进行（用户可在专长档案页停用，停用后 DailyPracticeWorker 跳过） */
    val isActive: Boolean = true,

    val lastPracticeAt: Long = 0,
    val lastDigestAt: Long = 0,

    val createdAt: Long,
    val updatedAt: Long,
)
