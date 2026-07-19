package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 已晋升擅长领域标签（角色详情页"能力"Tab · 擅长领域标签墙数据源）
 *
 * 背景：此前 CharacterDetailAbility.getSkillTags() 是硬编码占位符，
 * 与 IdentityPromotionEvaluator 的晋升流程完全没有接通。本表就是接通
 * 这条链路的落点——用户在专长档案页确认一次晋升后，
 * IdentityPromotionEvaluator.executePromotion 除了原有的写入
 * character_identity.soulNote 之外，额外调用
 * SpecialtyEvolutionEngine.distillSkillTag() 把这次晋升的完整特征描述
 * 浓缩成一个2-4字短标签，写入本表一条记录。
 *
 * 与 SpecialtyProfileEntity.domain（专长方向名，如"文学创作"）的区别：
 * domain 是用户设定的宽泛方向，本表的 tag 是真正走完晋升流程、
 * 被确认为角色"本能层"能力的具体短语（如"比喻""画面感"），
 * 颗粒度更细，且只有真正晋升过的角色才会有记录（未晋升过=空列表，
 * 标签墙需要处理这个空状态，不再是"人人都有五个假标签"）。
 *
 * 一个角色可以有多条记录（每次晋升一条），允许同一角色出现内容相近的
 * 标签（不做跨记录的去重合并——去重合并属于展示层可选的优化，
 * 不影响本表作为"晋升事件流水账"的定位）。
 */
@Entity(
    tableName = "promoted_skill_tags",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["specialtyId"]),
    ]
)
data class PromotedSkillTagEntity(
    @PrimaryKey val id: String,

    val characterId: Int,

    /** 来源专长档案ID，供追溯（如需要"点击标签跳转到对应专长档案页"之类的后续功能） */
    val specialtyId: String,

    /** 浓缩后的短标签（2-4字），标签墙直接展示这个字段 */
    val tag: String,

    /** 晋升前的完整特征描述原文，保留供追溯/调试，不在标签墙展示 */
    val sourceTraitSummary: String,

    val createdAt: Long,
)
