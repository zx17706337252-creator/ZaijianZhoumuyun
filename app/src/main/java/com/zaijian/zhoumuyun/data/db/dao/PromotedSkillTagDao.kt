package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.PromotedSkillTagEntity
import kotlinx.coroutines.flow.Flow

/**
 * PromotedSkillTag DAO——角色详情页"擅长领域"标签墙数据源。
 *
 * 见 PromotedSkillTagEntity 文档注释：本表由
 * IdentityPromotionEvaluator.executePromotion 在用户确认晋升时写入，
 * getSkillTags() 从这里查询真实数据，不再是硬编码占位符。
 */
@Dao
interface PromotedSkillTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: PromotedSkillTagEntity)

    /** 角色详情页标签墙一次性读取（按晋升时间正序，早晋升的标签排前面） */
    @Query("SELECT * FROM promoted_skill_tags WHERE characterId = :characterId ORDER BY createdAt ASC")
    suspend fun getAllForCharacter(characterId: Int): List<PromotedSkillTagEntity>

    /** 响应式版本，供 Compose 页面直接 collectAsState，晋升发生后标签墙无需手动刷新即可更新 */
    @Query("SELECT * FROM promoted_skill_tags WHERE characterId = :characterId ORDER BY createdAt ASC")
    fun observeAllForCharacter(characterId: Int): Flow<List<PromotedSkillTagEntity>>

    @Query("DELETE FROM promoted_skill_tags WHERE id = :id")
    suspend fun deleteById(id: String)
}
