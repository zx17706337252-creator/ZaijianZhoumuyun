package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 竞赛参赛条目实体（裁判与竞争机制 · 单篇参赛作品及评分记录）
 *
 * 每个参赛角色在每轮竞赛中对应一条 Entry，记录：
 *   - 参赛作品原文（content）
 *   - 裁判评分与点评（judgeScore / judgeReasoning）
 *   - 角色自评（selfScore / selfReasoning，盲评，看不到他人产出和裁判分）
 *   - 用户评分（userScore / userComment / userRank，三种输入统一换算）
 *   - 最终综合分（compositeScore，由 finalizeRound 按权重配置算出）
 *
 * judgeReasoning 为裁判实名点评，含具体问题 + 提升方向，质量足够好可直接
 * 作为 SystemSuggestionEntity.content 写入专长档案页（输家奖惩反哺路径）。
 */
@Entity(
    tableName = "competition_entries",
    indices = [
        Index(value = ["roundId"]),
        Index(value = ["roundId", "characterId"]),
    ]
)
data class CompetitionEntryEntity(
    @PrimaryKey val id: String,

    val roundId: String,
    val characterId: Int,

    /** 参赛作品正文，由 generateCompetitionEntry 独立 LLM 调用生成 */
    val content: String,

    /** 裁判给分（0-100），null 表示评审尚未完成 */
    val judgeScore: Int? = null,

    /**
     * 裁判实名点评：指明具体问题 + 贴合该角色 styleNotes 的提升方向。
     * 实名评审，裁判在 prompt 里看到所有参赛者姓名和各自的 styleNotes。
     */
    val judgeReasoning: String = "",

    /** 角色自评分（0-100），盲评（看不到他人作品和裁判分） */
    val selfScore: Int? = null,

    /** 角色自评说明 */
    val selfReasoning: String = "",

    /**
     * 用户最终评分（0-100）。
     * 三种用户输入统一换算：直接打分 / 排名线性映射 / 评语情感粗粒度映射。
     */
    val userScore: Int? = null,

    /** 用户评语原文 */
    val userComment: String = "",

    /**
     * 用户排名（第1名 = 1，最后名 = N），
     * 仅当用户选择排名输入方式时写入，换算 userScore 后保留备查。
     */
    val userRank: Int? = null,

    /**
     * 综合加权分（0-100 float），finalizeRound 按 competition_weight_configs
     * 基础权重 + judge_profiles.maturityStage 信任系数动态调整后算出。
     */
    val compositeScore: Float = 0f,

    val createdAt: Long,
)
